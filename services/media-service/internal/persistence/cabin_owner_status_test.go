package persistence

import (
	"crypto/sha256"
	"encoding/hex"
	"testing"
	"time"

	"github.com/google/uuid"
)

func TestRentalItemStatusDoesNotAcceptNew(t *testing.T) {
	t.Parallel()

	if !validRentalItemStatus("FREE") {
		t.Fatal("FREE must remain a valid rental-item status")
	}
	if validRentalItemStatus("NEW") {
		t.Fatal("NEW is a category, not a rental-item status")
	}
}

func TestValidateCabinOwnerMessageAcceptsInventoryVisibilityMarkerWithoutProof(t *testing.T) {
	t.Parallel()

	aggregateID := uuid.New()
	wireBody := []byte("inventory visibility marker")
	sum := sha256.Sum256(wireBody)
	message := CabinOwnerMessage{
		EventID: uuid.New(), BodySHA256: hex.EncodeToString(sum[:]), WireBody: wireBody,
		Topic: AssetRentalItemTopic, EventType: cabinInventoryVisibilityEvent,
		AggregateType: CabinOwnerAggregate, AggregateID: aggregateID, AggregateVersion: 1,
		RecordKey: aggregateID, PayloadOwnerID: aggregateID, RecordedAt: time.Now().UTC(),
	}
	if err := validateCabinOwnerMessage(message); err != nil {
		t.Fatalf("validateCabinOwnerMessage() error = %v", err)
	}
}
