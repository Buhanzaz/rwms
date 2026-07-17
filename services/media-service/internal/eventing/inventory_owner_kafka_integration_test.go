package eventing

import (
	"context"
	"encoding/json"
	"testing"
	"time"

	"dev.buhanzaz.rwms/media-service/internal/persistence"
	"dev.buhanzaz.rwms/media-service/internal/worker"
	"github.com/google/uuid"
	"github.com/twmb/franz-go/pkg/kgo"
)

func TestInventoryOwnerKafkaOrderedDuplicateAndInvalidDLTAckIntegration(t *testing.T) {
	databaseURL, brokers := kafkaIntegrationEnvironment(t)
	ctx, cancel := context.WithTimeout(context.Background(), 45*time.Second)
	defer cancel()
	database, err := persistence.Open(ctx, databaseURL)
	if err != nil {
		t.Fatalf("open media database: %v", err)
	}
	defer database.Close()
	repository := persistence.NewRepository(database.Pool)
	producer, err := NewProducer(brokers)
	if err != nil {
		t.Fatal(err)
	}
	defer producer.Close()
	ensureKafkaTopics(t, ctx, producer, []string{
		persistence.InventorySessionTopic, persistence.InventoryOwnerDLTTopic,
	})
	consumerClient, err := worker.NewInventoryOwnerKafkaConsumer(brokers,
		persistence.InventoryOwnerConsumerGroup, persistence.InventorySessionTopic)
	if err != nil {
		t.Fatal(err)
	}
	consumer := worker.NewInventoryOwnerConsumer(repository, consumerClient, integrationLogger())
	runtimeContext, stopRuntime := context.WithCancel(ctx)
	consumerDone := make(chan error, 1)
	go func() { consumerDone <- consumer.Run(runtimeContext) }()
	relay := NewRelay(repository, producer, "owner-kafka-relay-"+uuid.NewString(), integrationLogger())
	relayDone := make(chan error, 1)
	go func() { relayDone <- relay.Run(runtimeContext) }()

	ownerID, warehouseID := uuid.New(), uuid.New()
	marker := inventoryKafkaRecord(t, ownerID, warehouseID, uuid.New(), 0,
		"inventory.finding.added.v1", 0, true)
	proof := inventoryKafkaRecord(t, ownerID, warehouseID, uuid.New(), 1,
		persistence.InventoryOwnerProofEvent, 0, true)
	for _, record := range []*kgo.Record{marker, proof, proof} {
		if err := producer.ProduceSync(ctx, record).FirstErr(); err != nil {
			t.Fatalf("produce inventory owner record: %v", err)
		}
	}
	waitForOwnerBinding(t, ctx, database, ownerID, warehouseID)
	var applied int
	if err := database.Pool.QueryRow(ctx, `select count(*) from media_inventory_finding_inbox
		where consumer_name=$1 and aggregate_id=$2 and outcome='APPLIED'`,
		persistence.InventoryOwnerConsumerGroup, ownerID).Scan(&applied); err != nil {
		t.Fatal(err)
	}
	if applied != 2 {
		t.Fatalf("ordered/duplicate inbox rows = %d, want 2", applied)
	}

	invalid := inventoryKafkaRecord(t, uuid.New(), uuid.New(), uuid.New(), 0,
		persistence.InventoryOwnerProofEvent, 0, true)
	invalid.Key = []byte(uuid.NewString())
	if err := producer.ProduceSync(ctx, invalid).FirstErr(); err != nil {
		t.Fatal(err)
	}
	waitForInventoryOwnerDLTPublished(t, ctx, database)

	stopRuntime()
	consumer.Close()
	if err := relay.Close(context.Background()); err != nil {
		t.Fatal(err)
	}
	select {
	case err := <-consumerDone:
		if err != nil {
			t.Fatalf("owner consumer stopped: %v", err)
		}
	case <-time.After(5 * time.Second):
		t.Fatal("owner consumer did not stop")
	}
	select {
	case err := <-relayDone:
		if err != nil {
			t.Fatalf("relay stopped: %v", err)
		}
	case <-time.After(5 * time.Second):
		t.Fatal("relay did not stop")
	}
}

func inventoryKafkaRecord(
	t *testing.T,
	ownerID, warehouseID, eventID uuid.UUID,
	version int64,
	eventType string,
	ownerRevision int64,
	active bool,
) *kgo.Record {
	t.Helper()
	payload := map[string]any{
		"ownerType": "INVENTORY_FINDING", "ownerId": ownerID,
		"warehouseId": warehouseID, "ownerRevision": ownerRevision, "active": active,
	}
	if eventType != persistence.InventoryOwnerProofEvent {
		payload = map[string]any{
			"inventoryId": uuid.New(), "findingId": ownerID, "warehouseId": warehouseID,
			"sessionRevision": 0, "findingRevision": 0, "origin": "EXPECTED",
			"inspection": "NOT_INSPECTED", "reconciliation": "MISSING", "assetId": uuid.New(),
			"sourceAttached": false, "mediaCount": 0, "planFingerprintSha256": nil,
		}
	}
	body, err := json.Marshal(map[string]any{
		"envelopeVersion": 2, "eventId": eventID, "eventType": eventType, "eventVersion": 1,
		"occurredAt": nil, "recordedAt": time.Now().UTC(), "producer": "inventory-service",
		"aggregateType": "FINDING", "aggregateId": ownerID, "aggregateVersion": version,
		"correlation": map[string]any{"correlationId": uuid.New(), "causationId": nil},
		"actorRef":    nil, "payload": payload,
	})
	if err != nil {
		t.Fatal(err)
	}
	return &kgo.Record{Topic: persistence.InventorySessionTopic, Key: []byte(ownerID.String()), Value: body}
}

func waitForOwnerBinding(t *testing.T, ctx context.Context, database *persistence.Database,
	ownerID, warehouseID uuid.UUID) {
	t.Helper()
	deadline := time.Now().Add(15 * time.Second)
	for {
		var found bool
		err := database.Pool.QueryRow(ctx, `select exists(select 1 from media_owner_binding
			where owner_type='INVENTORY_FINDING' and owner_id=$1 and warehouse_id=$2 and active)`,
			ownerID.String(), warehouseID).Scan(&found)
		if err == nil && found {
			return
		}
		if time.Now().After(deadline) {
			t.Fatalf("inventory owner binding was not applied: %v", err)
		}
		time.Sleep(25 * time.Millisecond)
	}
}

func waitForInventoryOwnerDLTPublished(t *testing.T, ctx context.Context, database *persistence.Database) {
	t.Helper()
	deadline := time.Now().Add(15 * time.Second)
	for {
		var count int
		err := database.Pool.QueryRow(ctx, `select count(*) from media_transport_outbox
			where aggregate_type='INVENTORY_OWNER_DLT' and event_status='PUBLISHED'`).Scan(&count)
		if err == nil && count > 0 {
			return
		}
		if time.Now().After(deadline) {
			t.Fatalf("inventory owner DLT was not broker-acknowledged: %v", err)
		}
		time.Sleep(25 * time.Millisecond)
	}
}
