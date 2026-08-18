-- Catalog rows created before durable furniture-link reconciliation retain an
-- asset equipment snapshot but have no node-UUID external reference in
-- asset-service. A node UUID may occur in its active, draft and superseded
-- catalog snapshots, so select one current canonical snapshot before creating
-- the one durable intent. The existing reconciler then establishes the reference
-- through its idempotent private ensure command. The local snapshot stays
-- authoritative only for post-reconciliation conflict detection; asset-service
-- remains the owner of the live version and maximum.
WITH canonical_nodes AS (
  SELECT DISTINCT ON (node.node_id)
    node.node_id,
    catalog.warehouse_id,
    catalog.id AS source_catalog_version_id,
    catalog.version AS source_catalog_expected_version,
    btrim(node.furniture_equipment_name) AS requested_name
  FROM public.catalog_node node
  JOIN public.catalog_version catalog ON catalog.id = node.catalog_version_id
  WHERE node.node_type = 'MATERIAL'
    AND node.furniture_equipment_id IS NOT NULL
    AND btrim(coalesce(node.furniture_equipment_name, '')) <> ''
  ORDER BY
    node.node_id,
    CASE catalog.state
      WHEN 'ACTIVE' THEN 0
      WHEN 'DRAFT' THEN 1
      WHEN 'SUPERSEDED' THEN 2
      ELSE 3
    END,
    catalog.updated_at DESC,
    catalog.id DESC
)
INSERT INTO public.furniture_equipment_link_intent (
  node_id,
  warehouse_id,
  source_catalog_version_id,
  source_catalog_expected_version,
  requested_name,
  requested_equipment_version,
  requested_maximum_per_cabin,
  state,
  attempt_count,
  next_attempt_at,
  review_version,
  created_at,
  updated_at)
SELECT
  node.node_id,
  node.warehouse_id,
  node.source_catalog_version_id,
  node.source_catalog_expected_version,
  node.requested_name,
  null,
  null,
  'PENDING',
  0,
  clock_timestamp(),
  0,
  clock_timestamp(),
  clock_timestamp()
FROM canonical_nodes node
LEFT JOIN public.furniture_equipment_link_intent intent ON intent.node_id = node.node_id
WHERE intent.node_id IS NULL;
