package realtime

import (
	"encoding/json"
	"fmt"
	"net/http"
	"sync"
	"time"

	"github.com/google/uuid"
)

// Event is a deliberately small invalidation fact. It contains identifiers and
// a revision only; the browser must re-read the owning projection after it
// receives the event.
type Event struct {
	EventID     uuid.UUID `json:"eventId"`
	WarehouseID uuid.UUID `json:"warehouseId"`
	Scope       string    `json:"scope"`
	MediaID     uuid.UUID `json:"mediaId,omitempty"`
	OwnerType   string    `json:"ownerType,omitempty"`
	OwnerID     string    `json:"ownerId,omitempty"`
	Generation  int       `json:"generation,omitempty"`
	Revision    int64     `json:"revision"`
	OccurredAt  time.Time `json:"occurredAt"`
}

// MarshalJSON keeps optional UUID fields absent when an invalidation carries
// no media (for example the initial RESYNC marker). uuid.UUID is a fixed-size
// array, so encoding/json does not honor omitempty for a nil UUID by itself.
func (event Event) MarshalJSON() ([]byte, error) {
	type wireEvent struct {
		EventID     uuid.UUID  `json:"eventId"`
		WarehouseID uuid.UUID  `json:"warehouseId"`
		Scope       string     `json:"scope"`
		MediaID     *uuid.UUID `json:"mediaId,omitempty"`
		OwnerType   string     `json:"ownerType,omitempty"`
		OwnerID     string     `json:"ownerId,omitempty"`
		Generation  int        `json:"generation,omitempty"`
		Revision    int64      `json:"revision"`
		OccurredAt  time.Time  `json:"occurredAt"`
	}
	var mediaID *uuid.UUID
	if event.MediaID != uuid.Nil {
		mediaID = &event.MediaID
	}
	return json.Marshal(wireEvent{
		EventID: event.EventID, WarehouseID: event.WarehouseID, Scope: event.Scope,
		MediaID: mediaID, OwnerType: event.OwnerType, OwnerID: event.OwnerID,
		Generation: event.Generation, Revision: event.Revision, OccurredAt: event.OccurredAt,
	})
}

// Publisher is used by API handlers and asynchronous workers without coupling
// them to the HTTP server implementation.
type Publisher interface {
	Publish(Event)
}

type subscriber struct {
	events chan Event
}

// Hub fans invalidations out to authenticated subscribers for one warehouse.
// It is process-local by design: the durable media facts remain in PostgreSQL
// and Kafka; reconnecting clients always perform a complete scoped refresh.
type Hub struct {
	mu          sync.Mutex
	subscribers map[uuid.UUID]map[*subscriber]struct{}
}

func NewHub() *Hub {
	return &Hub{subscribers: make(map[uuid.UUID]map[*subscriber]struct{})}
}

func (hub *Hub) Publish(event Event) {
	if hub == nil || event.WarehouseID == uuid.Nil {
		return
	}
	if event.EventID == uuid.Nil {
		event.EventID = uuid.New()
	}
	if event.OccurredAt.IsZero() {
		event.OccurredAt = time.Now().UTC()
	}

	hub.mu.Lock()
	defer hub.mu.Unlock()
	for subscriber := range hub.subscribers[event.WarehouseID] {
		select {
		case subscriber.events <- event:
		default:
			// A slow tab must not block a write path. A RESYNC event preserves
			// correctness when several granular events outrun the client.
			for len(subscriber.events) > 0 {
				<-subscriber.events
			}
			subscriber.events <- Event{
				EventID: event.EventID, WarehouseID: event.WarehouseID,
				Scope: "RESYNC", Revision: event.Revision, OccurredAt: event.OccurredAt,
			}
		}
	}
}

// ServeHTTP writes an authenticated warehouse-scoped SSE stream. Authorization
// is intentionally performed by the caller before entering this method.
func (hub *Hub) ServeHTTP(writer http.ResponseWriter, request *http.Request, warehouseID uuid.UUID) {
	if hub == nil || warehouseID == uuid.Nil {
		writeStreamProblem(writer, http.StatusBadRequest, "invalid warehouse")
		return
	}
	flusher, ok := writer.(http.Flusher)
	if !ok {
		writeStreamProblem(writer, http.StatusInternalServerError, "streaming is unavailable")
		return
	}

	writer.Header().Set("Content-Type", "text/event-stream")
	writer.Header().Set("Cache-Control", "no-cache, no-store, must-revalidate")
	writer.Header().Set("Connection", "keep-alive")
	writer.Header().Set("X-Accel-Buffering", "no")

	subscriber := &subscriber{events: make(chan Event, 32)}
	hub.add(warehouseID, subscriber)
	defer hub.remove(warehouseID, subscriber)

	// A subscription starts with a resync marker. This also makes reconnects
	// safe when the process-local hub did not retain events during a disconnect.
	initial := Event{EventID: uuid.New(), WarehouseID: warehouseID, Scope: "RESYNC", OccurredAt: time.Now().UTC()}
	if err := writeEvent(writer, flusher, initial); err != nil {
		return
	}

	keepAlive := time.NewTicker(15 * time.Second)
	defer keepAlive.Stop()
	for {
		select {
		case <-request.Context().Done():
			return
		case event := <-subscriber.events:
			if err := writeEvent(writer, flusher, event); err != nil {
				return
			}
		case <-keepAlive.C:
			if _, err := fmt.Fprint(writer, ": keep-alive\n\n"); err != nil {
				return
			}
			flusher.Flush()
		}
	}
}

func (hub *Hub) add(warehouseID uuid.UUID, subscription *subscriber) {
	hub.mu.Lock()
	defer hub.mu.Unlock()
	warehouseSubscribers := hub.subscribers[warehouseID]
	if warehouseSubscribers == nil {
		warehouseSubscribers = make(map[*subscriber]struct{})
		hub.subscribers[warehouseID] = warehouseSubscribers
	}
	warehouseSubscribers[subscription] = struct{}{}
}

func (hub *Hub) remove(warehouseID uuid.UUID, subscription *subscriber) {
	hub.mu.Lock()
	defer hub.mu.Unlock()
	warehouseSubscribers := hub.subscribers[warehouseID]
	if warehouseSubscribers == nil {
		return
	}
	delete(warehouseSubscribers, subscription)
	if len(warehouseSubscribers) == 0 {
		delete(hub.subscribers, warehouseID)
	}
}

func writeEvent(writer http.ResponseWriter, flusher http.Flusher, event Event) error {
	body, err := json.Marshal(event)
	if err != nil {
		return err
	}
	if _, err := fmt.Fprintf(writer, "id: %s\nevent: warehouse-invalidation\ndata: %s\n\n", event.EventID, body); err != nil {
		return err
	}
	flusher.Flush()
	return nil
}

func writeStreamProblem(writer http.ResponseWriter, status int, message string) {
	if writer.Header().Get("Content-Type") == "" {
		writer.Header().Set("Content-Type", "application/problem+json")
	}
	writer.WriteHeader(status)
	_, _ = fmt.Fprintf(writer, `{"title":%q,"status":%d}`, message, status)
}
