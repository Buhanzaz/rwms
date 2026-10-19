-- V9 completed the only business use of the old-panel identity aliases.
-- Canonical rental_item columns now own the UUID, warehouse and cabin number,
-- so the legacy passport aliases are technical residue and must not be exposed.
-- Keep current business passport values such as price, shipmentDate and tenant.

UPDATE public.rental_item
SET passport_json = (
  passport_json::jsonb
    - ARRAY[
        'legacyId',
        'legacyWarehouseId',
        'legacyNumber',
        'legacyPhotos'
      ]::text[]
)::text
WHERE passport_json::jsonb ?| ARRAY[
  'legacyId',
  'legacyWarehouseId',
  'legacyNumber',
  'legacyPhotos'
];

-- Keep service-local replay snapshots coherent with the live projection.
UPDATE public.aggregate_snapshot AS snapshot
SET state = jsonb_set(
      snapshot.state,
      ARRAY['passport']::text[],
      (snapshot.state->'passport')
        - ARRAY[
            'legacyId',
            'legacyWarehouseId',
            'legacyNumber',
            'legacyPhotos'
          ]::text[],
      false),
    state_sha256 = encode(
      sha256(convert_to(
        jsonb_set(
          snapshot.state,
          ARRAY['passport']::text[],
          (snapshot.state->'passport')
            - ARRAY[
                'legacyId',
                'legacyWarehouseId',
                'legacyNumber',
                'legacyPhotos'
              ]::text[],
          false)::text,
        'UTF8')),
      'hex')
WHERE snapshot.aggregate_type = 'RENTAL_ITEM'
  AND (snapshot.state->'passport') ?| ARRAY[
    'legacyId',
    'legacyWarehouseId',
    'legacyNumber',
    'legacyPhotos'
  ];

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
  AND checkpoint.aggregate_version = item.version;
