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
	CabinID      uuid.UUID
	CoverMediaID *uuid.UUID
	Photos       []CabinPresentationPhotoRecord
}

// CabinPresentationPhotoRecord names one currently displayable cabin image and
// only the derived variants that may be requested through the private stream.
// SortOrder is the zero-based cover-first presentation position, not the
// retained CABIN association order.
type CabinPresentationPhotoRecord struct {
	MediaID    uuid.UUID
	Generation int
	SortOrder  int64
	HasSmall   bool
	HasLarge   bool
}

// ReadCabinPresentationSnapshots returns a stable, bounded read projection for
// an exact set of cabin IDs. Only active, current CABIN owner bindings and
// READY image assets at their current generation across every retained gallery
// folder participate. The owner bindings are share-locked through consume so a
// concurrent revocation cannot race a presentation snapshot. Each photo list
// puts the canonical cover first and then preserves the remaining association
// order; selecting a cover never hides older folders.
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
		with ready_photos as materialized (
			select photo.cabin_id::text as cabin_id,asset.media_id,
				asset.current_generation,photo.sort_order as association_sort_order,
				photo.attached_at,(asset.media_id=library.cover_media_id) as is_cover,
				bool_or(variant.variant='SMALL') as has_small,
				bool_or(variant.variant='LARGE') as has_large
			from media_cabin_photo photo
			join media_cabin_photo_library library on library.cabin_id=photo.cabin_id
			join media_asset asset on asset.media_id=photo.media_id
			join media_variant variant on variant.media_id=asset.media_id
				 and variant.generation=asset.current_generation
				 and variant.variant in ('SMALL','LARGE')
				 and variant.object_version_id<>''
			where photo.warehouse_id=$1 and photo.cabin_id::text=any($2::text[])
			  and photo.media_generation=asset.current_generation
			  and asset.media_kind='IMAGE' and asset.processing_status='READY'
			  and asset.current_generation>0 and asset.deleted_at is null
			  and media_asset_is_available(asset.media_id)
			group by photo.cabin_id,asset.media_id,asset.current_generation,photo.sort_order,
				photo.attached_at,library.cover_media_id
		)
		select cabin_id,media_id,current_generation,
			(row_number() over (
				partition by cabin_id
				order by is_cover desc,association_sort_order,attached_at,media_id
			)-1)::bigint as presentation_sort_order,
			is_cover,has_small,has_large
		from ready_photos
		order by array_position($2::text[], cabin_id),is_cover desc,
			association_sort_order,attached_at,media_id`,
		warehouseID, authorized)
	if err != nil {
		return err
	}
	for rows.Next() {
		var ownerID string
		var photo CabinPresentationPhotoRecord
		var isCover bool
		if err := rows.Scan(&ownerID, &photo.MediaID, &photo.Generation, &photo.SortOrder,
			&isCover,
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
		if isCover {
			copyOfMediaID := photo.MediaID
			records[index].CoverMediaID = &copyOfMediaID
		}
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
