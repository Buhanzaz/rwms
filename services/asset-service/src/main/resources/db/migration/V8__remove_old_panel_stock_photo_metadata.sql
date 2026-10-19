-- The old-panel transfer carried browser-only Unsplash fixtures inside each
-- migrated rental passport. Canonical photos now belong to media-service, so
-- remove only those known stock-photo fields from the exact transferred scope.
-- Rental identity, numbers, warehouse links, status, equipment and every other
-- passport field remain unchanged. No aggregate version or domain event is
-- invented for this non-business metadata cleanup.

UPDATE public.rental_item
SET passport_json = (
  (passport_json::jsonb
    - 'legacyPhotos'::text
    - 'previewPhotoUrls'::text
    - 'mainPhotoUrl'::text)
  || jsonb_build_object('hasPhotos', false, 'photoCount', 0)
)::text
WHERE passport_json::jsonb->>'source' = 'old-panel-rental-items-v1';

-- Keep the service-local replay snapshots consistent with the live JPA row.
-- The target set still comes exclusively from the source predicate above.
UPDATE public.aggregate_snapshot AS snapshot
	SET state = jsonb_set(
	      snapshot.state,
	      ARRAY['passport']::text[],
	      ((jsonb_extract_path(snapshot.state, 'passport')
        - 'legacyPhotos'::text
        - 'previewPhotoUrls'::text
        - 'mainPhotoUrl'::text)
        || jsonb_build_object('hasPhotos', false, 'photoCount', 0)),
      false),
    state_sha256 = encode(
      sha256(convert_to(
	        jsonb_set(
	          snapshot.state,
	          ARRAY['passport']::text[],
	          ((jsonb_extract_path(snapshot.state, 'passport')
            - 'legacyPhotos'::text
            - 'previewPhotoUrls'::text
            - 'mainPhotoUrl'::text)
            || jsonb_build_object('hasPhotos', false, 'photoCount', 0)),
          false)::text,
        'UTF8')),
      'hex')
FROM public.rental_item AS item
WHERE snapshot.aggregate_type = 'RENTAL_ITEM'
  AND snapshot.aggregate_id = item.id::text
  AND item.passport_json::jsonb->>'source' = 'old-panel-rental-items-v1'
  AND jsonb_extract_path_text(snapshot.state, 'passport', 'source') =
    'old-panel-rental-items-v1';

UPDATE public.projection_checkpoint AS checkpoint
SET projection_sha256 = snapshot.state_sha256
FROM public.rental_item AS item
JOIN public.aggregate_snapshot AS snapshot
  ON snapshot.aggregate_type = 'RENTAL_ITEM'
 AND snapshot.aggregate_id = item.id::text
 AND snapshot.aggregate_version = item.version
WHERE checkpoint.projection_name = 'asset-live-v1'
  AND checkpoint.aggregate_type = 'RENTAL_ITEM'
  AND checkpoint.aggregate_id = item.id::text
  AND checkpoint.aggregate_version = item.version
  AND item.passport_json::jsonb->>'source' = 'old-panel-rental-items-v1';
