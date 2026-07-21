-- Active catalog routing is protected in task-board by durable CATALOG_POSITION references.
-- Registration and later cleanup are maintenance-owned reconciliation work; no cross-service
-- transaction or cross-database foreign key is introduced.
ALTER TABLE public.integration_reconciliation
  ADD COLUMN catalog_version_id uuid,
  ADD COLUMN catalog_node_id uuid,
  ADD COLUMN catalog_queue_id uuid,
  ADD COLUMN catalog_external_reference_id varchar(128),
  ADD CONSTRAINT ck_reconciliation_catalog_identity CHECK (
    (catalog_version_id IS NULL
      AND catalog_node_id IS NULL
      AND catalog_queue_id IS NULL
      AND catalog_external_reference_id IS NULL)
    OR
    (dependency_type = 'TASK_BOARD'
      AND repair_id IS NULL
      AND operation_type IN ('REGISTER_CATALOG_POSITION','DELETE_CATALOG_POSITION')
      AND catalog_version_id IS NOT NULL
      AND catalog_node_id IS NOT NULL
      AND catalog_queue_id IS NOT NULL
      AND length(btrim(catalog_external_reference_id)) BETWEEN 1 AND 128
      AND response_snapshot IS NOT NULL)
  );

CREATE UNIQUE INDEX uk_reconciliation_catalog_operation
  ON public.integration_reconciliation(operation_type, catalog_version_id, catalog_node_id)
  WHERE catalog_version_id IS NOT NULL;
CREATE INDEX idx_reconciliation_catalog_truth
  ON public.integration_reconciliation(catalog_version_id, operation_type, state)
  WHERE catalog_version_id IS NOT NULL;

-- Upgrade safety: catalogs activated before this migration receive the same durable registration
-- intent as a newly activated catalog. Superseded catalogs are intentionally not backfilled: no
-- CATALOG_POSITION reference could have been created by the previous maintenance implementation.
WITH routed AS (
  SELECT
    version.id AS catalog_version_id,
    node.node_id AS catalog_node_id,
    node.routing_queue_id AS queue_id,
    'catalog:' || version.id || ':' || node.node_id AS external_reference_id
  FROM public.catalog_version version
  JOIN public.catalog_node node ON node.catalog_version_id = version.id
  WHERE version.state = 'ACTIVE'
    AND node.routing_queue_id IS NOT NULL
), identities AS (
  SELECT
    routed.*,
    md5('catalog-routing-row:' || catalog_version_id || ':' || catalog_node_id)::uuid AS row_id,
    md5('register-catalog-position:' || catalog_version_id || ':' || catalog_node_id
      || ':' || queue_id)::uuid AS idempotency_key
  FROM routed
)
INSERT INTO public.integration_reconciliation(
  id, repair_id, dependency_type, operation_type, idempotency_key, state,
  attempt_count, next_attempt_at, response_snapshot, review_version,
  created_at, updated_at, catalog_version_id, catalog_node_id, catalog_queue_id,
  catalog_external_reference_id)
SELECT
  row_id, NULL, 'TASK_BOARD', 'REGISTER_CATALOG_POSITION', idempotency_key, 'PENDING',
  0, clock_timestamp(),
  jsonb_build_object(
    'catalogVersionId', catalog_version_id,
    'catalogNodeId', catalog_node_id,
    'queueId', queue_id,
    'externalReferenceId', external_reference_id,
    'predecessorKeys', jsonb_build_array()),
  0, clock_timestamp(), clock_timestamp(), catalog_version_id, catalog_node_id, queue_id,
  external_reference_id
FROM identities
ON CONFLICT (dependency_type, operation_type, idempotency_key) DO NOTHING;
