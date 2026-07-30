package persistence

import (
	"context"

	"github.com/google/uuid"
	"github.com/jackc/pgx/v5"
)

// CabinPresentationSnapshotRecord is the deliberately narrow projection used
// by logistics to assemble a cabin presentation. It contains no object-store
// locator, filename, content type, or other media metadata.
type CabinPresentationSnapshotRecord struct {
	CabinID uuid.UUID
	Photos  []CabinPresentationPhotoRecord
}

// CabinPresentationPhotoRecord names one currently displayable cabin image and
// only the derived variants that may be requested through the private stream.
type CabinPresentationPhotoRecord struct {
	MediaID    uuid.UUID
	Generation int
	SortOrder  int64
	HasSmall   bool
	HasLarge   bool
}

// ReadCabinPresentationSnapshots returns a stable, bounded read projection for
// an exact set of cabin IDs. Only active, current CABIN owner bindings and
// READY image assets at their current generation participate. The owner
// bindings are share-locked through consume so a concurrent revocation cannot
// race a presentation snapshot.
func (repository *Repository) ReadCabinPresentationSnapshots(
	ctx context.Context,
	warehouseID uuid.UUID,
	cabinIDs []uuid.UUID,
	consume func([]CabinPresentationSnapshotRecord) error,
) error {
	if warehouseID == uuid.Nil || len(cabinIDs) < 1 || len(cabinIDs) > 100 || consume == nil {
		return ErrConflict
	}
	requested := make([]string, len(cabinIDs))
	seen := make(map[uuid.UUID]struct{}, len(cabinIDs))
	for index, cabinID := range cabinIDs {
		if cabinID == uuid.Nil {
			return ErrConflict
		}
		if _, duplicate := seen[cabinID]; duplicate {
			return ErrConflict
		}
		seen[cabinID] = struct{}{}
		requested[index] = cabinID.String()
	}

	tx, err := repository.pool.BeginTx(ctx, pgx.TxOptions{IsoLevel: pgx.ReadCommitted})
	if err != nil {
		return err
	}
	defer tx.Rollback(ctx)

	rows, err := tx.Query(ctx, `/* media_logistics_cabin_presentation_bindings */
		select binding.owner_id
		from unnest($2::text[]) with ordinality as requested(owner_id, position)
		join media_owner_binding binding
		  on binding.owner_type='CABIN' and binding.owner_id=requested.owner_id
		 and binding.warehouse_id=$1 and binding.active
		join media_consumer_aggregate_checkpoint checkpoint
		  on checkpoint.consumer_name=binding.proof_consumer_name
		 and checkpoint.aggregate_type=binding.proof_aggregate_type
		 and checkpoint.aggregate_id=binding.proof_aggregate_id
		 and checkpoint.aggregate_version>=binding.proof_aggregate_version
		where not exists (select 1 from media_quarantined_aggregate quarantine
			where quarantine.consumer_name=binding.proof_consumer_name
			  and quarantine.aggregate_type=binding.proof_aggregate_type
			  and quarantine.aggregate_id=binding.proof_aggregate_id
			  and quarantine.reconciled_at is null)
		order by requested.position
		for share of binding`, warehouseID, requested)
	if err != nil {
		return err
	}
	authorized := make([]string, 0, len(cabinIDs))
	for rows.Next() {
		var ownerID string
		if err := rows.Scan(&ownerID); err != nil {
			rows.Close()
			return err
		}
		authorized = append(authorized, ownerID)
	}
	if err := rows.Err(); err != nil {
		rows.Close()
		return err
	}
	rows.Close()

	records := make([]CabinPresentationSnapshotRecord, 0, len(authorized))
	byCabinID := make(map[string]int, len(authorized))
	for _, ownerID := range authorized {
		cabinID, parseErr := uuid.Parse(ownerID)
		if parseErr != nil || cabinID == uuid.Nil {
			return ErrConflict
		}
		byCabinID[ownerID] = len(records)
		records = append(records, CabinPresentationSnapshotRecord{
			CabinID: cabinID,
			Photos:  make([]CabinPresentationPhotoRecord, 0),
		})
	}
	if len(authorized) == 0 {
		if err := consume(records); err != nil {
			return err
		}
		return tx.Commit(ctx)
	}

	rows, err = tx.Query(ctx, `/* media_logistics_cabin_presentation_photos */
		select a.owner_id,a.media_id,a.current_generation,a.sort_order,
			bool_or(variant.variant='SMALL') as has_small,
			bool_or(variant.variant='LARGE') as has_large
		from media_asset a
		join media_variant variant on variant.media_id=a.media_id
		 and variant.generation=a.current_generation
		 and variant.variant in ('SMALL','LARGE')
		 and variant.object_version_id<>''
		where a.owner_type='CABIN' and a.warehouse_id=$1
		  and a.owner_id=any($2::text[]) and a.media_kind='IMAGE'
		  and a.processing_status='READY' and a.current_generation>0
		  and a.deleted_at is null and media_asset_is_available(a.media_id)
		group by a.owner_id,a.media_id,a.current_generation,a.sort_order,a.created_at
		order by array_position($2::text[], a.owner_id),a.sort_order,a.created_at,a.media_id`,
		warehouseID, authorized)
	if err != nil {
		return err
	}
	for rows.Next() {
		var ownerID string
		var photo CabinPresentationPhotoRecord
		if err := rows.Scan(&ownerID, &photo.MediaID, &photo.Generation, &photo.SortOrder,
			&photo.HasSmall, &photo.HasLarge); err != nil {
			rows.Close()
			return err
		}
		index, found := byCabinID[ownerID]
		if !found || photo.MediaID == uuid.Nil || photo.Generation <= 0 || (!photo.HasSmall && !photo.HasLarge) {
			rows.Close()
			return ErrConflict
		}
		records[index].Photos = append(records[index].Photos, photo)
	}
	if err := rows.Err(); err != nil {
		rows.Close()
		return err
	}
	rows.Close()
	if err := consume(records); err != nil {
		return err
	}
	return tx.Commit(ctx)
}
