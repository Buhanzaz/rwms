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

	t.Run("aggregate version may skip but must strictly advance", func(t *testing.T) {
		ownerID, warehouseID := uuid.New(), uuid.New()
		initial := ServiceOwnerProofCommand{
			SourceService: MaintenanceOwnerProofService,
			OwnerType:     OwnerTypeMaintenanceRepair, OwnerID: ownerID,
			WarehouseID: warehouseID, OwnerRevision: 0, AggregateVersion: 0,
			ProofEventID: uuid.New(), Active: true,
		}
		if _, _, err := repository.UpsertServiceOwnerProof(ctx, initial); err != nil {
			t.Fatalf("initial aggregate proof: %v", err)
		}
		skipped := initial
		skipped.ProofEventID = uuid.New()
		skipped.OwnerRevision = 1
		skipped.AggregateVersion = 3
		if record, replayed, err := repository.UpsertServiceOwnerProof(ctx, skipped); err != nil || replayed ||
			record.AggregateVersion != 3 || record.OwnerRevision != 1 {
			t.Fatalf("skipped aggregate proof = %#v replayed:%v error:%v", record, replayed, err)
		}
		stale := skipped
		stale.ProofEventID = uuid.New()
		stale.OwnerRevision = 2
		stale.AggregateVersion = 2
		if _, _, err := repository.UpsertServiceOwnerProof(ctx, stale); !errors.Is(err, ErrConflict) {
			t.Fatalf("aggregate regression error = %v, want ErrConflict", err)
		}
		assertServiceOwnerProofReceiptFailure(t, ctx, database, stale.ProofEventID,
			"AGGREGATE_VERSION_REGRESSION")
	})

	t.Run("owner revisions must be exactly next", func(t *testing.T) {
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
		nonIncreasing := initial
		nonIncreasing.ProofEventID = uuid.New()
		nonIncreasing.AggregateVersion = 3
		if _, _, err := repository.UpsertServiceOwnerProof(ctx, nonIncreasing); !errors.Is(err, ErrConflict) {
			t.Fatalf("non-increasing owner revision error = %v, want ErrConflict", err)
		}
		assertServiceOwnerProofReceiptFailure(t, ctx, database, nonIncreasing.ProofEventID,
			"OWNER_REVISION_CONFLICT")

		gapOwnerID := uuid.New()
		ownerGap := initial
		ownerGap.OwnerID = gapOwnerID
		ownerGap.ProofEventID = uuid.New()
		ownerGap.OwnerRevision = 5
		ownerGap.AggregateVersion = 3
		if _, _, err := repository.UpsertServiceOwnerProof(ctx, ownerGap); err != nil {
			t.Fatalf("owner revision gap baseline proof: %v", err)
		}
		ownerGap.ProofEventID = uuid.New()
		ownerGap.OwnerRevision = 7
		ownerGap.AggregateVersion = 4
		if _, _, err := repository.UpsertServiceOwnerProof(ctx, ownerGap); !errors.Is(err, ErrConflict) {
			t.Fatalf("owner revision gap error = %v, want ErrConflict", err)
		}
		assertServiceOwnerProofReceiptFailure(t, ctx, database, ownerGap.ProofEventID, "OWNER_REVISION_GAP")

		regressionOwnerID := uuid.New()
		regression := initial
		regression.OwnerID = regressionOwnerID
		regression.ProofEventID = uuid.New()
		if _, _, err := repository.UpsertServiceOwnerProof(ctx, regression); err != nil {
			t.Fatalf("owner revision regression baseline proof: %v", err)
		}
		regression.ProofEventID = uuid.New()
		regression.OwnerRevision = 2
		regression.AggregateVersion = 3
		if _, _, err := repository.UpsertServiceOwnerProof(ctx, regression); !errors.Is(err, ErrConflict) {
			t.Fatalf("owner revision regression error = %v, want ErrConflict", err)
		}
		assertServiceOwnerProofReceiptFailure(t, ctx, database, regression.ProofEventID,
			"OWNER_REVISION_REGRESSION")
	})

	t.Run("legacy aggregate-version-gap replay recovers under the current rule", func(t *testing.T) {
		ownerID, warehouseID := uuid.New(), uuid.New()
		initial := ServiceOwnerProofCommand{
			SourceService: MaintenanceOwnerProofService,
			OwnerType:     OwnerTypeMaintenanceCatalogNode, OwnerID: ownerID,
			WarehouseID: warehouseID, OwnerRevision: 0, AggregateVersion: 0,
			ProofEventID: uuid.New(), Active: true,
		}
		if _, _, err := repository.UpsertServiceOwnerProof(ctx, initial); err != nil {
			t.Fatalf("legacy recovery baseline proof: %v", err)
		}
		legacyGap := initial
		legacyGap.ProofEventID = uuid.New()
		legacyGap.OwnerRevision = 1
		legacyGap.AggregateVersion = 3
		seedLegacyAggregateVersionGap(t, ctx, database, repository, legacyGap)
		assertServiceOwnerProofReceiptFailure(t, ctx, database, legacyGap.ProofEventID,
			"AGGREGATE_VERSION_GAP")

		if record, replayed, err := repository.UpsertServiceOwnerProof(ctx, legacyGap); err != nil || replayed ||
			record.AggregateVersion != legacyGap.AggregateVersion || record.OwnerRevision != legacyGap.OwnerRevision {
			t.Fatalf("legacy aggregate gap replay = %#v replayed:%v error:%v", record, replayed, err)
		}
		if _, replayed, err := repository.UpsertServiceOwnerProof(ctx, legacyGap); err != nil || !replayed {
			t.Fatalf("recovered proof exact replay = %v, %v", replayed, err)
		}

		var outcome, failureCode string
		if err := database.Pool.QueryRow(ctx, `select outcome,coalesce(failure_code,'')
			from media_service_owner_proof_receipt where proof_event_id=$1`, legacyGap.ProofEventID).
			Scan(&outcome, &failureCode); err != nil || outcome != "APPLIED" || failureCode != "" {
			t.Fatalf("recovered receipt outcome=%q failure=%q error=%v", outcome, failureCode, err)
		}
		var openQuarantines int
		if err := database.Pool.QueryRow(ctx, `select count(*) from media_quarantined_aggregate
			where consumer_name=$1 and aggregate_type='CATALOG_NODE' and aggregate_id=$2
			  and reconciled_at is null`, MaintenanceOwnerProofConsumer, ownerID).
			Scan(&openQuarantines); err != nil || openQuarantines != 0 {
			t.Fatalf("recovered proof open quarantines=%d error=%v", openQuarantines, err)
		}
		var aggregateVersion, ownerRevision int64
		var active, quarantined bool
		if err := database.Pool.QueryRow(ctx, `select aggregate_version,owner_revision,active,quarantined
			from media_service_owner_proof_checkpoint where owner_type=$1 and owner_id=$2`,
			legacyGap.OwnerType, ownerID.String()).Scan(&aggregateVersion, &ownerRevision, &active, &quarantined); err != nil ||
			aggregateVersion != 3 || ownerRevision != 1 || !active || quarantined {
			t.Fatalf("recovered checkpoint aggregate=%d owner=%d active=%v quarantined=%v error=%v",
				aggregateVersion, ownerRevision, active, quarantined, err)
		}
	})

	t.Run("legacy aggregate-version-gap replay does not override another quarantine", func(t *testing.T) {
		ownerID, warehouseID := uuid.New(), uuid.New()
		initial := ServiceOwnerProofCommand{
			SourceService: MaintenanceOwnerProofService,
			OwnerType:     OwnerTypeMaintenanceRepair, OwnerID: ownerID,
			WarehouseID: warehouseID, OwnerRevision: 0, AggregateVersion: 0,
			ProofEventID: uuid.New(), Active: true,
		}
		if _, _, err := repository.UpsertServiceOwnerProof(ctx, initial); err != nil {
			t.Fatalf("other-quarantine baseline proof: %v", err)
		}
		legacyGap := initial
		legacyGap.ProofEventID = uuid.New()
		legacyGap.OwnerRevision = 1
		legacyGap.AggregateVersion = 3
		seedLegacyAggregateVersionGap(t, ctx, database, repository, legacyGap)

		blocked := legacyGap
		blocked.ProofEventID = uuid.New()
		blocked.OwnerRevision = 2
		blocked.AggregateVersion = 4
		if _, _, err := repository.UpsertServiceOwnerProof(ctx, blocked); !errors.Is(err, ErrConflict) {
			t.Fatalf("proof while quarantined error = %v, want ErrConflict", err)
		}
		if _, _, err := repository.UpsertServiceOwnerProof(ctx, legacyGap); !errors.Is(err, ErrConflict) {
			t.Fatalf("legacy replay over another quarantine error = %v, want ErrConflict", err)
		}
		assertServiceOwnerProofReceiptFailure(t, ctx, database, legacyGap.ProofEventID,
			"AGGREGATE_VERSION_GAP")
		var reason string
		if err := database.Pool.QueryRow(ctx, `select reason_code from media_quarantined_aggregate
			where consumer_name=$1 and aggregate_type='REPAIR' and aggregate_id=$2
			  and reconciled_at is null`, MaintenanceOwnerProofConsumer, ownerID).Scan(&reason); err != nil ||
			reason != "OWNER_PROOF_QUARANTINED" {
			t.Fatalf("other quarantine reason=%q error=%v", reason, err)
		}
	})

	t.Run("event ID reuse remains quarantined", func(t *testing.T) {
		eventConflictOwnerID, warehouseID := uuid.New(), uuid.New()
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

	t.Run("current cabin cover soft delete retains archive metadata", func(t *testing.T) {
		cabinID, warehouseID := uuid.New(), uuid.New()
		proof := cabinOwnerProofMessage(cabinID, cabinID, warehouseID, 0,
			cabinCreatedEvent, "FREE")
		if result, err := repository.ApplyCabinOwnerMessage(ctx, proof); err != nil ||
			result.Duplicate || result.Quarantined {
			t.Fatalf("apply cabin proof = %#v error:%v", result, err)
		}

		create := cabinUploadCommand(cabinID, warehouseID, 0)
		asset, replayed, err := repository.CreateUpload(ctx, create)
		if err != nil || replayed {
			t.Fatalf("create cabin cover = %#v replayed:%v error:%v", asset, replayed, err)
		}
		if _, err := database.Pool.Exec(ctx, `update media_asset set
			processing_status='READY',current_generation=1,next_generation=2,version=2,
			source_version_id='retained-source-version',source_etag='retained-source-etag',
			source_checksum_sha256=$2,finalized_content_type='image/jpeg',
			finalized_size_bytes=128,size_bytes=128 where media_id=$1`,
			asset.ID, create.ChecksumSHA256); err != nil {
			t.Fatalf("prepare cabin cover: %v", err)
		}
		variantObjectKey := "retained/" + asset.ID.String() + "/small.webp"
		if _, err := database.Pool.Exec(ctx, `insert into media_variant (
			media_id,generation,variant,object_key,object_version_id,content_type,
			size_bytes,width,height,checksum_sha256)
		values ($1,1,'SMALL',$2,'retained-variant-version','image/webp',64,360,240,$3)`,
			asset.ID, variantObjectKey, create.ChecksumSHA256); err != nil {
			t.Fatalf("insert retained cabin variant: %v", err)
		}
		associationTx, err := database.Pool.Begin(ctx)
		if err != nil {
			t.Fatalf("begin cabin cover association: %v", err)
		}
		defer associationTx.Rollback(ctx)
		readyAsset, err := repository.assetForUpdate(ctx, associationTx, asset.ID)
		if err != nil {
			t.Fatalf("load ready cabin cover: %v", err)
		}
		if err := repository.associateProcessedCabinImage(ctx, associationTx, readyAsset, uuid.New()); err != nil {
			t.Fatalf("associate cabin cover: %v", err)
		}
		if err := associationTx.Commit(ctx); err != nil {
			t.Fatalf("commit cabin cover association: %v", err)
		}

		deleteCommand := DeleteCommand{
			MediaID: asset.ID, OwnerType: OwnerTypeCabin, OwnerID: cabinID.String(),
			WarehouseID: warehouseID, SubjectID: uuid.New(), IdempotencyKey: uuid.New(),
			RequestSHA256: hex64('9'), ExpectedVersion: 2, CorrelationID: uuid.New(),
		}
		deleted, replayed, err := repository.Delete(ctx, deleteCommand)
		if err != nil || replayed || deleted.Status != media.StatusDeleted || deleted.Version != 3 {
			t.Fatalf("delete current cabin cover = %#v replayed:%v error:%v", deleted, replayed, err)
		}

		var coverIsNull, folderIsNull bool
		var libraryVersion int64
		if err := database.Pool.QueryRow(ctx, `select cover_media_id is null,
			active_gallery_folder_id is null,version
			from media_cabin_photo_library where cabin_id=$1`, cabinID).
			Scan(&coverIsNull, &folderIsNull, &libraryVersion); err != nil {
			t.Fatalf("read cleared cabin cover pointers: %v", err)
		}
		var photoRows, variantRows, historyRows int
		var mediaGeneration int
		var galleryFolderID uuid.UUID
		if err := database.Pool.QueryRow(ctx, `select count(*),max(media_generation),
			max(gallery_folder_id::text)::uuid from media_cabin_photo
			where cabin_id=$1 and media_id=$2`, cabinID, asset.ID).
			Scan(&photoRows, &mediaGeneration, &galleryFolderID); err != nil {
			t.Fatalf("read retained cabin photo association: %v", err)
		}
		if err := database.Pool.QueryRow(ctx, `select count(*) from media_variant
			where media_id=$1 and object_key=$2`, asset.ID, variantObjectKey).Scan(&variantRows); err != nil {
			t.Fatalf("read retained cabin variant metadata: %v", err)
		}
		if err := database.Pool.QueryRow(ctx, `select count(*) from media_cabin_cover_history
			where cabin_id=$1 and cover_media_id=$2`, cabinID, asset.ID).Scan(&historyRows); err != nil {
			t.Fatalf("read retained cabin cover history: %v", err)
		}
		var deletedAtPresent bool
		var status, sourceObjectKey, sourceVersionID string
		if err := database.Pool.QueryRow(ctx, `select processing_status,deleted_at is not null,
			source_object_key,source_version_id from media_asset where media_id=$1`, asset.ID).
			Scan(&status, &deletedAtPresent, &sourceObjectKey, &sourceVersionID); err != nil {
			t.Fatalf("read logically deleted cabin asset: %v", err)
		}
		if !coverIsNull || !folderIsNull || libraryVersion != 2 || photoRows != 1 ||
			mediaGeneration != 1 || galleryFolderID != asset.FolderID || variantRows != 1 ||
			historyRows != 1 || status != string(media.StatusDeleted) || !deletedAtPresent ||
			sourceObjectKey != create.SourceObjectKey || sourceVersionID != "retained-source-version" {
			t.Fatalf("deleted cabin cover pointers=%v/%v library=%d photo=%d generation=%d folder=%s variant=%d history=%d status=%s deletedAt=%v source=%q version=%q",
				coverIsNull, folderIsNull, libraryVersion, photoRows, mediaGeneration,
				galleryFolderID, variantRows, historyRows, status, deletedAtPresent,
				sourceObjectKey, sourceVersionID)
		}
	})
}

func seedLegacyAggregateVersionGap(
	t *testing.T,
	ctx context.Context,
	database *Database,
	repository *Repository,
	command ServiceOwnerProofCommand,
) {
	t.Helper()
	proof, err := normalizeServiceOwnerProof(command)
	if err != nil {
		t.Fatalf("normalize legacy aggregate gap: %v", err)
	}
	tx, err := database.Pool.Begin(ctx)
	if err != nil {
		t.Fatalf("begin legacy aggregate gap seed: %v", err)
	}
	defer tx.Rollback(ctx)
	checkpoint, found, err := readServiceOwnerCheckpoint(ctx, tx, proof.OwnerType, proof.InternalOwnerID)
	if err != nil || !found {
		t.Fatalf("read legacy aggregate gap checkpoint found=%v error=%v", found, err)
	}
	prior := checkpoint.persistedProof(proof.OwnerType, proof.InternalOwnerID)
	if err := repository.rejectServiceOwnerProof(ctx, tx, proof, &prior,
		"AGGREGATE_VERSION_GAP", checkpoint.AggregateVersion+1, proof.AggregateVersion); err != nil {
		t.Fatalf("seed legacy aggregate gap: %v", err)
	}
	if err := tx.Commit(ctx); err != nil {
		t.Fatalf("commit legacy aggregate gap seed: %v", err)
	}
}

func assertServiceOwnerProofReceiptFailure(
	t *testing.T,
	ctx context.Context,
	database *Database,
	proofEventID uuid.UUID,
	wantFailureCode string,
) {
	t.Helper()
	var outcome, failureCode string
	if err := database.Pool.QueryRow(ctx, `select outcome,coalesce(failure_code,'')
		from media_service_owner_proof_receipt where proof_event_id=$1`, proofEventID).
		Scan(&outcome, &failureCode); err != nil || outcome != "QUARANTINED" || failureCode != wantFailureCode {
		t.Fatalf("receipt %s outcome=%q failure=%q error=%v; want QUARANTINED/%q",
			proofEventID, outcome, failureCode, err, wantFailureCode)
	}
}
