package realtime

import (
	"context"
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"strings"
	"sync"
	"testing"
	"time"

	"github.com/google/uuid"
)

func TestEventJSONOmitsUnsetMediaIdentity(t *testing.T) {
	event := Event{EventID: uuid.New(), WarehouseID: uuid.New(), Scope: "RESYNC", Revision: 3, OccurredAt: time.Now().UTC()}
	body, err := json.Marshal(event)
	if err != nil {
		t.Fatalf("json.Marshal() error = %v", err)
	}
	encoded := string(body)
	for _, forbidden := range []string{"mediaId", "ownerType", "ownerId", "generation"} {
		if strings.Contains(encoded, `"`+forbidden+`"`) {
			t.Fatalf("unset %s leaked into event: %s", forbidden, encoded)
		}
	}
	for _, required := range []string{"eventId", "warehouseId", "scope", "revision", "occurredAt"} {
		if !strings.Contains(encoded, `"`+required+`"`) {
			t.Fatalf("required %s missing from event: %s", required, encoded)
		}
	}
}

func TestHubStreamsInitialResyncAndWarehouseScopedChanges(t *testing.T) {
	hub := NewHub()
	warehouseID := uuid.New()
	foreignWarehouseID := uuid.New()
	ctx, cancel := context.WithCancel(context.Background())
	defer cancel()
	writer := &streamWriter{header: make(http.Header), flushed: make(chan struct{}, 8)}
	request := httptest.NewRequest(http.MethodGet, "/api/media/v1/events", nil).WithContext(ctx)
	done := make(chan struct{})
	go func() {
		hub.ServeHTTP(writer, request, warehouseID)
		close(done)
	}()

	waitForFlush(t, writer)
	foreignMediaID := uuid.New()
	hub.Publish(Event{
		EventID: uuid.New(), WarehouseID: foreignWarehouseID, Scope: "MEDIA_CHANGED",
		MediaID: foreignMediaID, OccurredAt: time.Now().UTC(),
	})
	if strings.Contains(writer.body(), foreignMediaID.String()) {
		t.Fatalf("foreign warehouse event leaked into stream: %q", writer.body())
	}

	mediaID := uuid.New()
	hub.Publish(Event{
		EventID: uuid.New(), WarehouseID: warehouseID, Scope: "MEDIA_CHANGED",
		MediaID: mediaID, OccurredAt: time.Now().UTC(),
	})
	waitFor(t, 2*time.Second, func() bool {
		return strings.Contains(writer.body(), mediaID.String())
	})
	if !strings.Contains(writer.body(), `"scope":"RESYNC"`) {
		t.Fatalf("initial stream body = %q, want RESYNC", writer.body())
	}
	if writer.Header().Get("Content-Type") != "text/event-stream" ||
		!strings.Contains(writer.body(), "event: warehouse-invalidation\n") {
		t.Fatalf("stream headers/body = %#v %q", writer.Header(), writer.body())
	}

	cancel()
	select {
	case <-done:
	case <-time.After(2 * time.Second):
		t.Fatal("stream did not stop after request cancellation")
	}
}

func TestHubCollapsesSlowSubscriberBacklogToResync(t *testing.T) {
	hub := NewHub()
	warehouseID := uuid.New()
	slow := &subscriber{events: make(chan Event, 32)}
	hub.add(warehouseID, slow)
	defer hub.remove(warehouseID, slow)

	for index := 0; index < cap(slow.events); index++ {
		hub.Publish(Event{WarehouseID: warehouseID, Scope: "MEDIA_CHANGED", MediaID: uuid.New()})
	}
	latest := Event{WarehouseID: warehouseID, Scope: "MEDIA_CHANGED", MediaID: uuid.New(), Revision: 41}
	hub.Publish(latest)

	if len(slow.events) != 1 {
		t.Fatalf("slow subscriber backlog = %d, want one RESYNC", len(slow.events))
	}
	event := <-slow.events
	if event.Scope != "RESYNC" || event.WarehouseID != warehouseID || event.Revision != latest.Revision ||
		event.EventID == uuid.Nil || event.OccurredAt.IsZero() {
		t.Fatalf("collapsed event = %#v", event)
	}
}

func waitForFlush(t *testing.T, writer *streamWriter) {
	t.Helper()
	select {
	case <-writer.flushed:
	case <-time.After(2 * time.Second):
		t.Fatal("stream did not flush initial event")
	}
}

func waitFor(t *testing.T, timeout time.Duration, condition func() bool) {
	t.Helper()
	deadline := time.Now().Add(timeout)
	for time.Now().Before(deadline) {
		if condition() {
			return
		}
		time.Sleep(5 * time.Millisecond)
	}
	t.Fatal("condition was not met before timeout")
}

type streamWriter struct {
	mu      sync.Mutex
	header  http.Header
	written strings.Builder
	flushed chan struct{}
}

func (writer *streamWriter) Header() http.Header {
	return writer.header
}

func (writer *streamWriter) WriteHeader(int) {}

func (writer *streamWriter) Write(value []byte) (int, error) {
	writer.mu.Lock()
	defer writer.mu.Unlock()
	return writer.written.Write(value)
}

func (writer *streamWriter) Flush() {
	select {
	case writer.flushed <- struct{}{}:
	default:
	}
}

func (writer *streamWriter) body() string {
	writer.mu.Lock()
	defer writer.mu.Unlock()
	return writer.written.String()
}
