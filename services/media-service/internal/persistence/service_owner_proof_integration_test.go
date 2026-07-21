package persistence

import (
	"context"
	"errors"
	"os"
	"testing"
	"time"

	"dev.buhanzaz.rwms/media-service/internal/media"
	"dev.buhanzaz.rwms/media-service/internal/testsupport"
	"github.com/google/uuid"
)

func TestServiceOwnerProofAndSoftDeleteIntegration(t *testing.T) {
	databaseURL := os.Getenv("MEDIA_TEST_DATABASE_URL")
	if databaseURL == "" {
		t.Skip("MEDIA_TEST_DATABASE_URL is not configured")
	}
	databaseURL = testsupport.NewMigratedMediaDatabase(t, databaseURL)
	ctx, cancel := context.WithTimeout(context.Background(), 45*time.Second)
	defer cancel()
	database, err := Open(ctx, databaseURL)
	if err != nil {
		t.Fatalf("Open() error = %v", err)
	}
	defer database.Close()
	repository := NewRepository(database.Pool)

	t.Run("maintenance exact replay and changed same version fail closed", func(t *testing.T) {
		ownerID, warehouseID := uuid.New(), uuid.New()
		proof := ServiceOwnerProofCommand{
			SourceService: MaintenanceOwnerProofService,
			OwnerType:     OwnerTypeMaintenanceEstimate, OwnerID: ownerID,
			WarehouseID: warehouseID, OwnerRevision: 0, AggregateVersion: 0,
			ProofEventID: uuid.New(), Active: true,
		}
		if record, replayed, err := repository.UpsertServiceOwnerProof(ctx, proof); err != nil || replayed ||
			record.OwnerID != ownerID || record.WarehouseID != warehouseID {
			t.Fatalf("new maintenance proof = %#v replayed:%v error:%v", record, replayed, err)
		}
		if _, replayed, err := repository.UpsertServiceOwnerProof(ctx, proof); err != nil || !replayed {
			t.Fatalf("maintenance proof replay = %v, %v", replayed, err)
		}

		create := createCommand(ownerID, warehouseID, media.KindImage, 0)
		create.OwnerType = OwnerTypeMaintenanceEstimate
		if _, replayed, err := repository.CreateUpload(ctx, create); err != nil || replayed {
			t.Fatalf("proved maintenance upload = replayed:%v error:%v", replayed, err)
		}
		foreign := createCommand(ownerID, uuid.New(), media.KindImage, 1)
		foreign.OwnerType = OwnerTypeMaintenanceEstimate
		if _, _, err := repository.CreateUpload(ctx, foreign); !errors.Is(err, ErrOwnerProofMissing) {
			t.Fatalf("foreign maintenance warehouse error = %v, want ErrOwnerProofMissing", err)
		}

		changedSameVersion := proof
		changedSameVersion.ProofEventID = uuid.New()
		changedSameVersion.Active = false
		if _, _, err := repository.UpsertServiceOwnerProof(ctx, changedSameVersion); !errors.Is(err, ErrConflict) {
			t.Fatalf("changed same version error = %v, want ErrConflict", err)
		}
		if _, _, err := repository.CreateUpload(ctx,
			func() CreateUploadCommand {
				command := createCommand(ownerID, warehouseID, media.KindImage, 2)
				command.OwnerType = OwnerTypeMaintenanceEstimate
				return command
			}()); !errors.Is(err, ErrOwnerProofMissing) {
			t.Fatalf("quarantined maintenance owner error = %v, want ErrOwnerProofMissing", err)
		}
		var openQuarantine int
		if err := database.Pool.QueryRow(ctx, `select count(*) from media_quarantined_aggregate
			where consumer_name=$1 and aggregate_type='ESTIMATE' and aggregate_id=$2
			  and reconciled_at is null`, MaintenanceOwnerProofConsumer, ownerID).Scan(&openQuarantine); err != nil || openQuarantine != 1 {
			t.Fatalf("maintenance quarantine count = %d, %v", openQuarantine, err)
		}
	})

	t.Run("gap and owner revision anomalies remain quarantined", func(t *testing.T) {
		gapOwnerID := uuid.New()
		gap := ServiceOwnerProofCommand{
			SourceService: MaintenanceOwnerProofService,
			OwnerType:     OwnerTypeMaintenanceRepair, OwnerID: gapOwnerID,
			WarehouseID: uuid.New(), OwnerRevision: 0, AggregateVersion: 2,
			ProofEventID: uuid.New(), Active: true,
		}
		if _, _, err := repository.UpsertServiceOwnerProof(ctx, gap); !errors.Is(err, ErrConflict) {
			t.Fatalf("initial aggregate gap error = %v, want ErrConflict", err)
		}
		gap.AggregateVersion = 0
		gap.ProofEventID = uuid.New()
		if _, _, err := repository.UpsertServiceOwnerProof(ctx, gap); !errors.Is(err, ErrConflict) {
			t.Fatalf("post-gap bootstrap error = %v, want durable ErrConflict", err)
		}

		ownerID, warehouseID := uuid.New(), uuid.New()
		initial := ServiceOwnerProofCommand{
			SourceService: MaintenanceOwnerProofService,
			OwnerType:     OwnerTypeMaintenanceAcceptance, OwnerID: ownerID,
			WarehouseID: warehouseID, OwnerRevision: 3, AggregateVersion: 0,
			ProofEventID: uuid.New(), Active: true,
		}
		if _, _, err := repository.UpsertServiceOwnerProof(ctx, initial); err != nil {
			t.Fatalf("initial owner revision proof: %v", err)
		}
		ownerGap := initial
		ownerGap.ProofEventID = uuid.New()
		ownerGap.AggregateVersion = 1
		ownerGap.OwnerRevision = 5
		if _, _, err := repository.UpsertServiceOwnerProof(ctx, ownerGap); !errors.Is(err, ErrConflict) {
			t.Fatalf("owner revision gap error = %v, want ErrConflict", err)
		}

		regressionOwnerID := uuid.New()
		regression := ServiceOwnerProofCommand{
			SourceService: MaintenanceOwnerProofService,
			OwnerType:     OwnerTypeMaintenanceCatalogNode, OwnerID: regressionOwnerID,
			WarehouseID: warehouseID, OwnerRevision: 3, AggregateVersion: 0,
			ProofEventID: uuid.New(), Active: true,
		}
		if _, _, err := repository.UpsertServiceOwnerProof(ctx, regression); err != nil {
			t.Fatalf("regression baseline proof: %v", err)
		}
		regression.AggregateVersion = 1
		regression.OwnerRevision = 2
		regression.ProofEventID = uuid.New()
		if _, _, err := repository.UpsertServiceOwnerProof(ctx, regression); !errors.Is(err, ErrConflict) {
			t.Fatalf("owner revision regression error = %v, want ErrConflict", err)
		}

		eventConflictOwnerID := uuid.New()
		eventConflict := ServiceOwnerProofCommand{
			SourceService: MaintenanceOwnerProofService,
			OwnerType:     OwnerTypeMaintenanceRepair, OwnerID: eventConflictOwnerID,
			WarehouseID: warehouseID, OwnerRevision: 0, AggregateVersion: 0,
			ProofEventID: uuid.New(), Active: true,
		}
		if _, _, err := repository.UpsertServiceOwnerProof(ctx, eventConflict); err != nil {
			t.Fatalf("event conflict baseline proof: %v", err)
		}
		eventConflict.Active = false
		if _, _, err := repository.UpsertServiceOwnerProof(ctx, eventConflict); !errors.Is(err, ErrConflict) {
			t.Fatalf("changed event-ID replay error = %v, want ErrConflict", err)
		}
	})

	t.Run("logistics transfer uses only destination warehouse and structured identity", func(t *testing.T) {
		documentID, lineID := uuid.New(), uuid.New()
		destinationWarehouseID, sourceWarehouseID := uuid.New(), uuid.New()
		proof := ServiceOwnerProofCommand{
			SourceService: LogisticsOwnerProofService,
			OwnerType:     OwnerTypeLogisticsTransfer, DocumentID: documentID, LineID: lineID,
			WarehouseID: destinationWarehouseID, OwnerRevision: 0, AggregateVersion: 0,
			ProofEventID: uuid.New(), Active: true,
		}
		if record, replayed, err := repository.UpsertServiceOwnerProof(ctx, proof); err != nil || replayed ||
			record.DocumentID != documentID || record.LineID != lineID {
			t.Fatalf("logistics destination proof = %#v replayed:%v error:%v", record, replayed, err)
		}
		internalOwnerID := LogisticsOwnerID(documentID, lineID)
		create := createCommand(documentID, destinationWarehouseID, media.KindImage, 0)
		create.OwnerType = OwnerTypeLogisticsTransfer
		create.OwnerID = internalOwnerID
		asset, replayed, err := repository.CreateUpload(ctx, create)
		if err != nil || replayed {
			t.Fatalf("destination logistics upload = replayed:%v error:%v", replayed, err)
		}
		foreign := createCommand(documentID, sourceWarehouseID, media.KindImage, 1)
		foreign.OwnerType = OwnerTypeLogisticsTransfer
		foreign.OwnerID = internalOwnerID
		if _, _, err := repository.CreateUpload(ctx, foreign); !errors.Is(err, ErrOwnerProofMissing) {
			t.Fatalf("source warehouse upload error = %v, want ErrOwnerProofMissing", err)
		}

		if _, err := database.Pool.Exec(ctx, `update media_asset set processing_status='READY',
			current_generation=1,next_generation=2,version=3,source_version_id='source-version',
			source_etag='source-etag',source_checksum_sha256=$2,finalized_content_type='image/jpeg',
			finalized_size_bytes=128,size_bytes=128 where media_id=$1`, asset.ID,
			create.ChecksumSHA256); err != nil {
			t.Fatalf("prepare logistics media: %v", err)
		}
		if _, err := database.Pool.Exec(ctx, `insert into media_variant (
			media_id,generation,variant,object_key,object_version_id,content_type,
			size_bytes,width,height,checksum_sha256)
		values ($1,1,'SMALL',$2,'derived-version','image/webp',64,360,240,$3)`,
			asset.ID, "derived/"+asset.ID.String()+"/small.webp", create.ChecksumSHA256); err != nil {
			t.Fatalf("insert logistics derived variant: %v", err)
		}
		reference := ValidateLogisticsReferencesCommand{
			OwnerType: OwnerTypeLogisticsTransfer, OwnerID: internalOwnerID,
			WarehouseID: destinationWarehouseID,
			References:  []ReadyMediaReference{{MediaID: asset.ID, Generation: 1}},
		}
		if err := repository.ValidateLogisticsReferences(ctx, reference); err != nil {
			t.Fatalf("validate destination media reference: %v", err)
		}
		reference.WarehouseID = sourceWarehouseID
		if err := repository.ValidateLogisticsReferences(ctx, reference); !errors.Is(err, ErrReferenceNotReady) {
			t.Fatalf("source media reference error = %v, want ErrReferenceNotReady", err)
		}

		deleteCommand := DeleteCommand{
			MediaID: asset.ID, OwnerType: OwnerTypeLogisticsTransfer, OwnerID: internalOwnerID,
			WarehouseID: destinationWarehouseID, SubjectID: uuid.New(), IdempotencyKey: uuid.New(),
			RequestSHA256: hex64('d'), ExpectedVersion: 3, CorrelationID: uuid.New(),
		}
		deleted, replayed, err := repository.Delete(ctx, deleteCommand)
		if err != nil || replayed || deleted.Status != media.StatusDeleted || deleted.Version != 4 {
			t.Fatalf("soft delete = %#v replayed:%v error:%v", deleted, replayed, err)
		}
		replayedAsset, replayed, err := repository.Delete(ctx, deleteCommand)
		if err != nil || !replayed || replayedAsset.Version != deleted.Version {
			t.Fatalf("soft delete replay = %#v replayed:%v error:%v", replayedAsset, replayed, err)
		}
		stale := deleteCommand
		stale.IdempotencyKey = uuid.New()
		stale.RequestSHA256 = hex64('e')
		if _, _, err := repository.Delete(ctx, stale); !errors.Is(err, ErrConflict) {
			t.Fatalf("stale delete error = %v, want ErrConflict", err)
		}
		wrongOwner := deleteCommand
		wrongOwner.IdempotencyKey = uuid.New()
		wrongOwner.RequestSHA256 = hex64('f')
		wrongOwner.OwnerID = LogisticsOwnerID(documentID, uuid.New())
		if _, _, err := repository.Delete(ctx, wrongOwner); !errors.Is(err, ErrNotFound) {
			t.Fatalf("wrong owner delete error = %v, want ErrNotFound", err)
		}

		var deletedFacts, variants int
		var sourceObjectKey string
		if err := database.Pool.QueryRow(ctx, `select count(*) from media_transport_outbox
			where aggregate_id=$1 and event_type='media.media.deleted.v1'`, asset.ID).Scan(&deletedFacts); err != nil {
			t.Fatalf("count deleted facts: %v", err)
		}
		if err := database.Pool.QueryRow(ctx, `select count(*) from media_variant where media_id=$1`,
			asset.ID).Scan(&variants); err != nil {
			t.Fatalf("count retained variants: %v", err)
		}
		if err := database.Pool.QueryRow(ctx, `select source_object_key from media_asset where media_id=$1`,
			asset.ID).Scan(&sourceObjectKey); err != nil {
			t.Fatalf("read retained source key: %v", err)
		}
		if deletedFacts != 1 || variants != 1 || sourceObjectKey != create.SourceObjectKey {
			t.Fatalf("soft-delete persistence facts=%d variants=%d source=%q want source=%q",
				deletedFacts, variants, sourceObjectKey, create.SourceObjectKey)
		}
	})
}
