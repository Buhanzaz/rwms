-- HTML imports may contain legacy catalog values that cannot be mapped
-- reliably. Keep the cabin importable with an explicit, editable placeholder
-- instead of fabricating a match or storing a nullable composition.
INSERT INTO public.cabin_catalog_item(
  id, version, kind, name, name_normalized, active, created_at, updated_at)
VALUES
  ('af57f2b0-3a71-4b7f-8d2f-000000000501', 0, 'TYPE', '—', '—', true, clock_timestamp(), clock_timestamp()),
  ('af57f2b0-3a71-4b7f-8d2f-000000000502', 0, 'DIMENSION', '—', '—', true, clock_timestamp(), clock_timestamp()),
  ('af57f2b0-3a71-4b7f-8d2f-000000000503', 0, 'FINISHING', '—', '—', true, clock_timestamp(), clock_timestamp())
ON CONFLICT (kind, name_normalized) DO NOTHING;

INSERT INTO public.cabin_type_dimension(
  id, version, cabin_type_id, dimension_id, sort_order, created_at, updated_at)
SELECT
  'af57f2b0-3a71-4b7f-8d2f-000000000504',
  0,
  type_item.id,
  dimension_item.id,
  0,
  clock_timestamp(),
  clock_timestamp()
FROM public.cabin_catalog_item AS type_item
JOIN public.cabin_catalog_item AS dimension_item
  ON dimension_item.kind = 'DIMENSION'
  AND dimension_item.name_normalized = '—'
WHERE type_item.kind = 'TYPE'
  AND type_item.name_normalized = '—'
ON CONFLICT (cabin_type_id, dimension_id) DO NOTHING;
