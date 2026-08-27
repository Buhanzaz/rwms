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
	PhotoCount   int64
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
// image associations are counted when they belong to the library's active
// gallery folder, while only READY assets at their current generation are
// returned as photos. The owner bindings are share-locked through consume so a
// concurrent revocation cannot race a presentation snapshot. Each photo list
// puts the canonical cover first and then preserves the remaining association
// order, capped at one hundred photos per cabin. Consumers can compare the full
// logical PhotoCount with the returned list and fail closed on processing gaps
// or overflow. A library without an active folder produces a zero count and an
// empty photo list while retained archive folders remain untouched.
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
		with image_assets as materialized (
			select photo.cabin_id::text as cabin_id,asset.media_id,
				asset.current_generation,photo.media_generation,
				asset.processing_status,photo.sort_order as association_sort_order,
				photo.attached_at,(asset.media_id=library.cover_media_id) as is_cover
			from media_cabin_photo photo
			join media_cabin_photo_library library on library.cabin_id=photo.cabin_id
			join media_asset asset on asset.media_id=photo.media_id
			where photo.warehouse_id=$1 and photo.cabin_id::text=any($2::text[])
			  and photo.gallery_folder_id=library.active_gallery_folder_id
			  and asset.media_kind='IMAGE' and asset.deleted_at is null
			  and media_asset_is_available(asset.media_id)
		), counts as (
			select cabin_id,count(*)::bigint as photo_count
			from image_assets
			group by cabin_id
		), ready_photos as materialized (
			select image.cabin_id,image.media_id,image.current_generation,
				image.association_sort_order,image.attached_at,image.is_cover,
				bool_or(variant.variant='SMALL') as has_small,
				bool_or(variant.variant='LARGE') as has_large
			from image_assets image
			join media_variant variant on variant.media_id=image.media_id
				 and variant.generation=image.current_generation
				 and variant.variant in ('SMALL','LARGE')
				 and variant.object_version_id<>''
			where image.media_generation=image.current_generation
			  and image.processing_status='READY' and image.current_generation>0
			group by image.cabin_id,image.media_id,image.current_generation,
				image.association_sort_order,image.attached_at,image.is_cover
		), ranked_photos as (
			select cabin_id,media_id,current_generation,
			(row_number() over (
				partition by cabin_id
				order by is_cover desc,association_sort_order,attached_at,media_id
			)-1)::bigint as presentation_sort_order,
			is_cover,has_small,has_large
			from ready_photos
		)
		select counts.cabin_id,counts.photo_count,ranked.media_id,
			ranked.current_generation,ranked.presentation_sort_order,
			ranked.is_cover,ranked.has_small,ranked.has_large
		from counts
		left join ranked_photos ranked on ranked.cabin_id=counts.cabin_id
		 and ranked.presentation_sort_order<100
		order by array_position($2::text[], counts.cabin_id),
			ranked.presentation_sort_order nulls last`,
		warehouseID, authorized)
	if err != nil {
		return err
	}
	for rows.Next() {
		var ownerID string
		var photoCount int64
		var mediaID *uuid.UUID
		var generation *int
		var sortOrder *int64
		var isCover, hasSmall, hasLarge *bool
		if err := rows.Scan(&ownerID, &photoCount, &mediaID, &generation, &sortOrder,
			&isCover, &hasSmall, &hasLarge); err != nil {
			rows.Close()
			return err
		}
		index, found := byCabinID[ownerID]
		if !found || photoCount < 0 {
			rows.Close()
			return ErrConflict
		}
		records[index].PhotoCount = photoCount
		if mediaID == nil {
			if generation != nil || sortOrder != nil || isCover != nil || hasSmall != nil || hasLarge != nil {
				rows.Close()
				return ErrConflict
			}
			continue
		}
		if generation == nil || sortOrder == nil || isCover == nil || hasSmall == nil || hasLarge == nil ||
			*mediaID == uuid.Nil || *generation <= 0 || (!*hasSmall && !*hasLarge) {
			rows.Close()
			return ErrConflict
		}
		photo := CabinPresentationPhotoRecord{
			MediaID: *mediaID, Generation: *generation, SortOrder: *sortOrder,
			HasSmall: *hasSmall, HasLarge: *hasLarge,
		}
		records[index].Photos = append(records[index].Photos, photo)
		if *isCover {
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
