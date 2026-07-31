package persistence

import (
	"context"
	"crypto/sha256"
	"encoding/hex"
	"errors"
	"fmt"
	"os"
	"testing"
	"time"

	"dev.buhanzaz.rwms/media-service/internal/media"
	"dev.buhanzaz.rwms/media-service/internal/testsupport"
	"github.com/google/uuid"
)

func TestDynamicCabinOwnerProjectionAuthorizesServerCreatedCabinAndMovesWarehouseIntegration(
	t *testing.T,
) {
	databaseURL := os.Getenv("MEDIA_TEST_DATABASE_URL")
	if databaseURL == "" {
		t.Skip("MEDIA_TEST_DATABASE_URL is not configured")
	}
	databaseURL = testsupport.NewMigratedMediaDatabase(t, databaseURL)
	ctx, cancel := context.WithTimeout(context.Background(), 30*time.Second)
	defer cancel()
	database, err := Open(ctx, databaseURL)
	if err != nil {
		t.Fatalf("Open() error = %v", err)
	}
	defer database.Close()
	repository := NewRepository(database.Pool)

	cabinID := uuid.New()
	firstWarehouseID := uuid.New()
	secondWarehouseID := uuid.New()
	created := cabinOwnerProofMessage(cabinID, cabinID, firstWarehouseID, 0,
		cabinCreatedEvent, "FREE")
	if result, err := repository.ApplyCabinOwnerMessage(ctx, created); err != nil ||
		result.Duplicate || result.Quarantined {
		t.Fatalf("ApplyCabinOwnerMessage(created) = %#v, %v", result, err)
	}
	assertCabinOwnerBinding(t, ctx, database, cabinID, firstWarehouseID, 0, true)

	upload := createCommand(cabinID, firstWarehouseID, media.KindImage, 0)
	upload.OwnerType = OwnerTypeCabin
	if _, replayed, err := repository.CreateUpload(ctx, upload); err != nil || replayed {
		t.Fatalf("CreateUpload(dynamic cabin) = replayed:%v error:%v", replayed, err)
	}
	foreignUpload := createCommand(cabinID, secondWarehouseID, media.KindImage, 1)
	foreignUpload.OwnerType = OwnerTypeCabin
	if _, _, err := repository.CreateUpload(ctx, foreignUpload); !errors.Is(err, ErrOwnerProofMissing) {
		t.Fatalf("CreateUpload(pre-move foreign warehouse) error = %v, want ErrOwnerProofMissing", err)
	}

	if result, err := repository.ApplyCabinOwnerMessage(ctx, created); err != nil ||
		!result.Duplicate || result.Quarantined {
		t.Fatalf("ApplyCabinOwnerMessage(exact duplicate) = %#v, %v", result, err)
	}

	moved := cabinOwnerProofMessage(cabinID, cabinID, secondWarehouseID, 1,
		cabinWarehouseEvent, "IN_TRANSFER")
	if result, err := repository.ApplyCabinOwnerMessage(ctx, moved); err != nil ||
		result.Duplicate || result.Quarantined {
		t.Fatalf("ApplyCabinOwnerMessage(move) = %#v, %v", result, err)
	}
	assertCabinOwnerBinding(t, ctx, database, cabinID, secondWarehouseID, 1, true)

	oldWarehouseUpload := createCommand(cabinID, firstWarehouseID, media.KindImage, 2)
	oldWarehouseUpload.OwnerType = OwnerTypeCabin
	if _, _, err := repository.CreateUpload(ctx, oldWarehouseUpload); !errors.Is(err, ErrOwnerProofMissing) {
		t.Fatalf("CreateUpload(post-move old warehouse) error = %v, want ErrOwnerProofMissing", err)
	}
	if _, err := repository.ListOwner(ctx, OwnerTypeCabin, cabinID.String(), firstWarehouseID,
		10, nil); !errors.Is(err, ErrOwnerProofMissing) {
		t.Fatalf("ListOwner(post-move old warehouse) error = %v, want ErrOwnerProofMissing", err)
	}
	newWarehouseUpload := createCommand(cabinID, secondWarehouseID, media.KindImage, 3)
	newWarehouseUpload.OwnerType = OwnerTypeCabin
	if _, replayed, err := repository.CreateUpload(ctx, newWarehouseUpload); err != nil || replayed {
		t.Fatalf("CreateUpload(post-move new warehouse) = replayed:%v error:%v", replayed, err)
	}
	assets, err := repository.ListOwner(ctx, OwnerTypeCabin, cabinID.String(), secondWarehouseID,
		10, nil)
	if err != nil || len(assets) != 2 || assets[0].ID != upload.MediaID ||
		assets[1].ID != newWarehouseUpload.MediaID {
		t.Fatalf("ListOwner(post-move new warehouse) = %#v, %v", assets, err)
	}

	comment := cabinOwnerMarkerMessage(cabinID, 2, cabinCommentEvent)
	if result, err := repository.ApplyCabinOwnerMessage(ctx, comment); err != nil ||
		result.Duplicate || result.Quarantined {
		t.Fatalf("ApplyCabinOwnerMessage(comment marker) = %#v, %v", result, err)
	}
	assertCabinOwnerBinding(t, ctx, database, cabinID, secondWarehouseID, 1, true)
	assertCabinOwnerCheckpoint(t, ctx, database, cabinID, 2)

	writtenOff := cabinOwnerProofMessage(cabinID, cabinID, secondWarehouseID, 3,
		cabinStatusEvent, "WRITTEN_OFF")
	if result, err := repository.ApplyCabinOwnerMessage(ctx, writtenOff); err != nil ||
		result.Duplicate || result.Quarantined {
		t.Fatalf("ApplyCabinOwnerMessage(written off) = %#v, %v", result, err)
	}
	assertCabinOwnerBinding(t, ctx, database, cabinID, secondWarehouseID, 3, false)
	if _, _, err := repository.CreateUpload(ctx,
		cabinUploadCommand(cabinID, secondWarehouseID, 4)); !errors.Is(err, ErrOwnerProofMissing) {
		t.Fatalf("CreateUpload(written-off cabin) error = %v, want ErrOwnerProofMissing", err)
	}

	reactivated := cabinOwnerProofMessage(cabinID, cabinID, secondWarehouseID, 4,
		cabinStatusEvent, "FREE")
	if result, err := repository.ApplyCabinOwnerMessage(ctx, reactivated); err != nil ||
		!result.Quarantined {
		t.Fatalf("ApplyCabinOwnerMessage(terminal reactivation) = %#v, %v", result, err)
	}
	assertOpenCabinOwnerQuarantine(t, ctx, database, cabinID, "TERMINAL_REACTIVATION")
}

func TestDynamicCabinOwnerProjectionQuarantinesMismatchAndReconcilesGapIntegration(
	t *testing.T,
) {
	databaseURL := os.Getenv("MEDIA_TEST_DATABASE_URL")
	if databaseURL == "" {
		t.Skip("MEDIA_TEST_DATABASE_URL is not configured")
	}
	databaseURL = testsupport.NewMigratedMediaDatabase(t, databaseURL)
	ctx, cancel := context.WithTimeout(context.Background(), 30*time.Second)
	defer cancel()
	database, err := Open(ctx, databaseURL)
	if err != nil {
		t.Fatalf("Open() error = %v", err)
	}
	defer database.Close()
	repository := NewRepository(database.Pool)

	mismatchedCabinID := uuid.New()
	mismatch := cabinOwnerProofMessage(mismatchedCabinID, uuid.New(), uuid.New(), 0,
		cabinCreatedEvent, "FREE")
	if result, err := repository.ApplyCabinOwnerMessage(ctx, mismatch); err != nil ||
		!result.Quarantined {
		t.Fatalf("ApplyCabinOwnerMessage(owner mismatch) = %#v, %v", result, err)
	}
	assertOpenCabinOwnerQuarantine(t, ctx, database, mismatchedCabinID,
		"OWNER_IDENTITY_CONFLICT")
	var bindingCount int
	if err := database.Pool.QueryRow(ctx, `select count(*) from media_owner_binding
		where owner_type='CABIN' and owner_id=$1`, mismatchedCabinID.String()).Scan(&bindingCount); err != nil {
		t.Fatalf("count mismatched cabin binding: %v", err)
	}
	if bindingCount != 0 {
		t.Fatalf("mismatched cabin binding count = %d, want 0", bindingCount)
	}

	cabinID := uuid.New()
	firstWarehouseID := uuid.New()
	secondWarehouseID := uuid.New()
	gap := cabinOwnerProofMessage(cabinID, cabinID, secondWarehouseID, 2,
		cabinWarehouseEvent, "IN_TRANSFER")
	if result, err := repository.ApplyCabinOwnerMessage(ctx, gap); err != nil ||
		!result.Quarantined {
		t.Fatalf("ApplyCabinOwnerMessage(gap) = %#v, %v", result, err)
	}
	assertOpenCabinOwnerQuarantine(t, ctx, database, cabinID, "VERSION_GAP")

	created := cabinOwnerProofMessage(cabinID, cabinID, firstWarehouseID, 0,
		cabinCreatedEvent, "FREE")
	marker := cabinOwnerMarkerMessage(cabinID, 1, cabinNoteEvent)
	if err := repository.ReconcileCabinOwnerAggregate(ctx, cabinID, -1, uuid.New(),
		"reviewed authoritative asset stream", []CabinOwnerMessage{created, marker, gap}); err != nil {
		t.Fatalf("ReconcileCabinOwnerAggregate() error = %v", err)
	}
	assertCabinOwnerBinding(t, ctx, database, cabinID, secondWarehouseID, 2, true)
	assertCabinOwnerCheckpoint(t, ctx, database, cabinID, 2)
	var openQuarantine int
	if err := database.Pool.QueryRow(ctx, `select count(*) from media_quarantined_aggregate
		where consumer_name=$1 and aggregate_type='RENTAL_ITEM' and aggregate_id=$2
		  and reconciled_at is null`, CabinOwnerConsumerGroup, cabinID).Scan(&openQuarantine); err != nil {
		t.Fatalf("count open cabin quarantine after reconciliation: %v", err)
	}
	if openQuarantine != 0 {
		t.Fatalf("open cabin quarantine after reconciliation = %d, want 0", openQuarantine)
	}
	if _, replayed, err := repository.CreateUpload(ctx,
		cabinUploadCommand(cabinID, secondWarehouseID, 0)); err != nil || replayed {
		t.Fatalf("CreateUpload(reconciled cabin) = replayed:%v error:%v", replayed, err)
	}
}

func cabinOwnerProofMessage(
	aggregateID uuid.UUID,
	payloadOwnerID uuid.UUID,
	warehouseID uuid.UUID,
	version int64,
	eventType string,
	status string,
) CabinOwnerMessage {
	wireBody := []byte(fmt.Sprintf("%s|%s|%s|%d|%s|%s", aggregateID, payloadOwnerID,
		warehouseID, version, eventType, status))
	sum := sha256.Sum256(wireBody)
	return CabinOwnerMessage{
		EventID: uuid.New(), BodySHA256: hex.EncodeToString(sum[:]), WireBody: wireBody,
		Topic: AssetRentalItemTopic, EventType: eventType, AggregateType: CabinOwnerAggregate,
		AggregateID: aggregateID, AggregateVersion: version, RecordKey: aggregateID,
		PayloadOwnerID: payloadOwnerID, RecordedAt: time.Now().UTC(),
		Proof: &CabinOwnerProof{
			WarehouseID: warehouseID, Status: status, OwnerRevision: version,
			Active: status != "WRITTEN_OFF",
		},
	}
}

func cabinOwnerMarkerMessage(aggregateID uuid.UUID, version int64, eventType string) CabinOwnerMessage {
	wireBody := []byte(fmt.Sprintf("%s|%d|%s", aggregateID, version, eventType))
	sum := sha256.Sum256(wireBody)
	return CabinOwnerMessage{
		EventID: uuid.New(), BodySHA256: hex.EncodeToString(sum[:]), WireBody: wireBody,
		Topic: AssetRentalItemTopic, EventType: eventType, AggregateType: CabinOwnerAggregate,
		AggregateID: aggregateID, AggregateVersion: version, RecordKey: aggregateID,
		PayloadOwnerID: aggregateID, RecordedAt: time.Now().UTC(),
	}
}

func cabinUploadCommand(cabinID, warehouseID uuid.UUID, sortOrder int64) CreateUploadCommand {
	command := createCommand(cabinID, warehouseID, media.KindImage, sortOrder)
	command.OwnerType = OwnerTypeCabin
	return command
}

func assertCabinOwnerBinding(
	t *testing.T,
	ctx context.Context,
	database *Database,
	cabinID uuid.UUID,
	wantWarehouseID uuid.UUID,
	wantRevision int64,
	wantActive bool,
) {
	t.Helper()
	var warehouseID uuid.UUID
	var revision int64
	var active bool
	var consumerName string
	if err := database.Pool.QueryRow(ctx, `select warehouse_id,owner_revision,active,
		proof_consumer_name from media_owner_binding where owner_type='CABIN' and owner_id=$1`,
		cabinID.String()).Scan(&warehouseID, &revision, &active, &consumerName); err != nil {
		t.Fatalf("read cabin binding: %v", err)
	}
	if warehouseID != wantWarehouseID || revision != wantRevision || active != wantActive ||
		consumerName != CabinOwnerConsumerGroup {
		t.Fatalf("cabin binding = warehouse:%s revision:%d active:%v consumer:%s",
			warehouseID, revision, active, consumerName)
	}
}

func assertCabinOwnerCheckpoint(
	t *testing.T,
	ctx context.Context,
	database *Database,
	cabinID uuid.UUID,
	wantVersion int64,
) {
	t.Helper()
	var version int64
	if err := database.Pool.QueryRow(ctx, `select aggregate_version
		from media_consumer_aggregate_checkpoint where consumer_name=$1
		  and aggregate_type='RENTAL_ITEM' and aggregate_id=$2`, CabinOwnerConsumerGroup,
		cabinID).Scan(&version); err != nil {
		t.Fatalf("read cabin checkpoint: %v", err)
	}
	if version != wantVersion {
		t.Fatalf("cabin checkpoint = %d, want %d", version, wantVersion)
	}
}

func assertOpenCabinOwnerQuarantine(
	t *testing.T,
	ctx context.Context,
	database *Database,
	cabinID uuid.UUID,
	wantReason string,
) {
	t.Helper()
	var reason string
	if err := database.Pool.QueryRow(ctx, `select reason_code from media_quarantined_aggregate
		where consumer_name=$1 and aggregate_type='RENTAL_ITEM' and aggregate_id=$2
		  and reconciled_at is null`, CabinOwnerConsumerGroup, cabinID).Scan(&reason); err != nil {
		t.Fatalf("read open cabin quarantine: %v", err)
	}
	if reason != wantReason {
		t.Fatalf("open cabin quarantine reason = %s, want %s", reason, wantReason)
	}
}
