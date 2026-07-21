-- Maintenance owns the authorization lifecycle for its media owner types. Proof delivery is a
-- durable, ordered reconciliation: a later owner revision cannot overtake an unconfirmed one.
ALTER TABLE public.integration_reconciliation
  ADD COLUMN media_owner_type varchar(64),
  ADD COLUMN media_owner_id uuid,
  ADD COLUMN media_warehouse_id uuid,
  ADD COLUMN media_owner_revision bigint,
  ADD COLUMN media_aggregate_version bigint,
  ADD COLUMN media_source_id uuid,
  ADD COLUMN media_source_version bigint,
  ADD COLUMN media_proof_event_id uuid,
  ADD COLUMN media_active boolean;

ALTER TABLE public.integration_reconciliation
  DROP CONSTRAINT ck_reconciliation_dependency,
  ADD CONSTRAINT ck_reconciliation_dependency
    CHECK (dependency_type IN ('ASSET','TASK_BOARD','MEDIA')),
  ADD CONSTRAINT ck_reconciliation_media_identity CHECK (
    (dependency_type <> 'MEDIA'
      AND media_owner_type IS NULL
      AND media_owner_id IS NULL
      AND media_warehouse_id IS NULL
      AND media_owner_revision IS NULL
      AND media_aggregate_version IS NULL
      AND media_source_id IS NULL
      AND media_source_version IS NULL
      AND media_proof_event_id IS NULL
      AND media_active IS NULL)
    OR
    (dependency_type = 'MEDIA'
      AND repair_id IS NULL
      AND operation_type = 'UPSERT_MEDIA_OWNER_PROOF'
      AND media_owner_type IN (
        'MAINTENANCE_ESTIMATE',
        'MAINTENANCE_REPAIR',
        'MAINTENANCE_ACCEPTANCE',
        'MAINTENANCE_CATALOG_NODE')
      AND media_owner_id IS NOT NULL
      AND media_warehouse_id IS NOT NULL
      AND media_owner_revision >= 0
      AND media_aggregate_version >= 0
      AND media_source_id IS NOT NULL
      AND media_source_version >= 0
      AND media_proof_event_id IS NOT NULL
      AND media_active IS NOT NULL
      AND response_snapshot IS NOT NULL)
  );

CREATE UNIQUE INDEX uk_reconciliation_media_owner_revision
  ON public.integration_reconciliation(media_owner_type, media_owner_id, media_owner_revision)
  WHERE dependency_type = 'MEDIA';
CREATE UNIQUE INDEX uk_reconciliation_media_source_revision
  ON public.integration_reconciliation(
    media_owner_type, media_owner_id, media_source_id, media_source_version)
  WHERE dependency_type = 'MEDIA';
CREATE UNIQUE INDEX uk_reconciliation_media_proof_event
  ON public.integration_reconciliation(media_proof_event_id)
  WHERE dependency_type = 'MEDIA';

-- Existing service-owned aggregates must become usable by media-service after the upgrade too.
-- A catalog node UUID is logical and only unique inside one warehouse. The UUIDv3 expression
-- below must stay byte-for-byte equivalent to MaintenanceMediaOwnerId.catalogNode. It preserves
-- one owner across catalog versions in a warehouse while isolating the same node UUID elsewhere.
-- maintenance_media_reference stores the physical catalog row_id rather than a media owner ID,
-- so existing references require no rewrite and remain attached to their original catalog rows.
WITH catalog_owner_digest AS (
  SELECT
    node.node_id,
    version.warehouse_id,
    version.id AS source_id,
    version.version AS source_version,
    version.updated_at AS source_updated_at,
    decode(md5(
      'maintenance-catalog-node-owner:' || version.warehouse_id::text || ':' ||
      node.node_id::text), 'hex') AS owner_digest
  FROM public.catalog_node node
  JOIN public.catalog_version version ON version.id = node.catalog_version_id
), catalog_owner_scoped AS (
  SELECT
    node_id,
    warehouse_id,
    source_id,
    source_version,
    source_updated_at,
    set_byte(
      set_byte(owner_digest, 6, (get_byte(owner_digest, 6) & 15) | 48),
      8,
      (get_byte(owner_digest, 8) & 63) | 128) AS owner_uuid_bytes
  FROM catalog_owner_digest
), owner_candidates AS (
  SELECT
    'MAINTENANCE_CATALOG_NODE'::varchar AS owner_type,
    (
      substr(encode(owner_uuid_bytes, 'hex'), 1, 8) || '-' ||
      substr(encode(owner_uuid_bytes, 'hex'), 9, 4) || '-' ||
      substr(encode(owner_uuid_bytes, 'hex'), 13, 4) || '-' ||
      substr(encode(owner_uuid_bytes, 'hex'), 17, 4) || '-' ||
      substr(encode(owner_uuid_bytes, 'hex'), 21, 12)
    )::uuid AS owner_id,
    warehouse_id,
    source_id,
    source_version,
    source_updated_at
  FROM catalog_owner_scoped
  UNION ALL
  SELECT
    'MAINTENANCE_ESTIMATE', estimate.id, estimate.warehouse_id,
    estimate.id, estimate.version, estimate.updated_at
  FROM public.maintenance_estimate estimate
  UNION ALL
  SELECT
    'MAINTENANCE_REPAIR', repair.id, repair.warehouse_id,
    repair.id, repair.version, repair.updated_at
  FROM public.maintenance_repair repair
  UNION ALL
  SELECT
    'MAINTENANCE_ACCEPTANCE', repair.id, repair.warehouse_id,
    repair.id, repair.version, repair.updated_at
  FROM public.maintenance_repair repair
  WHERE repair.acceptance_state <> 'NOT_READY'
), latest_owner AS (
  SELECT owner_type, owner_id, warehouse_id, source_id, source_version
  FROM (
    SELECT candidate.*,
      row_number() OVER (
        PARTITION BY owner_type, owner_id
        ORDER BY source_updated_at DESC, source_id::text DESC) AS owner_rank
    FROM owner_candidates candidate
  ) ranked
  WHERE owner_rank = 1
), proof_identity AS (
  SELECT latest_owner.*,
    (
      substr(md5('maintenance-media-proof:' || owner_type || ':' || owner_id), 1, 8) || '-' ||
      substr(md5('maintenance-media-proof:' || owner_type || ':' || owner_id), 9, 4) || '-' ||
      substr(md5('maintenance-media-proof:' || owner_type || ':' || owner_id), 13, 4) || '-' ||
      substr(md5('maintenance-media-proof:' || owner_type || ':' || owner_id), 17, 4) || '-' ||
      substr(md5('maintenance-media-proof:' || owner_type || ':' || owner_id), 21, 12)
    )::uuid AS proof_event_id,
    (
      substr(md5('maintenance-media-work:' || owner_type || ':' || owner_id), 1, 8) || '-' ||
      substr(md5('maintenance-media-work:' || owner_type || ':' || owner_id), 9, 4) || '-' ||
      substr(md5('maintenance-media-work:' || owner_type || ':' || owner_id), 13, 4) || '-' ||
      substr(md5('maintenance-media-work:' || owner_type || ':' || owner_id), 17, 4) || '-' ||
      substr(md5('maintenance-media-work:' || owner_type || ':' || owner_id), 21, 12)
    )::uuid AS work_id
  FROM latest_owner
)
INSERT INTO public.integration_reconciliation(
  id, repair_id, dependency_type, operation_type, idempotency_key, state,
  attempt_count, next_attempt_at, response_snapshot, review_version,
  created_at, updated_at, media_owner_type, media_owner_id, media_warehouse_id,
  media_owner_revision,
  media_aggregate_version, media_source_id, media_source_version,
  media_proof_event_id, media_active)
SELECT
  work_id, NULL, 'MEDIA', 'UPSERT_MEDIA_OWNER_PROOF', proof_event_id, 'PENDING',
  0, clock_timestamp(),
  jsonb_build_object(
    'ownerType', owner_type,
    'ownerId', owner_id,
    'warehouseId', warehouse_id,
    'ownerRevision', 0,
    'aggregateVersion', source_version,
    'proofEventId', proof_event_id,
    'active', true),
  0, clock_timestamp(), clock_timestamp(), owner_type, owner_id, warehouse_id, 0,
  source_version, source_id, source_version, proof_event_id, true
FROM proof_identity;
