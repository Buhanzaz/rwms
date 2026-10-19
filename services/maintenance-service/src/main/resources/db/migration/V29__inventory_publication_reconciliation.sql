-- Completed inventory publication is a new immutable source boundary.  It does not mutate the
-- Stage 7 repair-only source rows, which remain historical evidence for the legacy endpoint.

ALTER TABLE public.maintenance_estimate
  ADD COLUMN priority integer NOT NULL DEFAULT 3,
  ADD COLUMN movement_to_repair boolean NOT NULL DEFAULT false,
  ADD COLUMN movement_scheduled_date date,
  ADD COLUMN inventory_superseded_at timestamptz,
  ADD CONSTRAINT ck_maintenance_estimate_priority CHECK (priority BETWEEN 1 AND 5),
  ADD CONSTRAINT ck_maintenance_estimate_movement_scheduled CHECK (
    movement_to_repair OR movement_scheduled_date IS NULL
  );

ALTER TABLE public.integration_reconciliation
  DROP CONSTRAINT ck_reconciliation_state,
  ADD CONSTRAINT ck_reconciliation_state CHECK (
    state IN (
      'PENDING', 'RETRY_PENDING', 'CONFIRMED', 'RECONCILIATION_REQUIRED', 'QUARANTINED',
      'CANCELLED'
    )
  );

CREATE TABLE public.inventory_publication_source_operation (
  inventory_id uuid NOT NULL,
  final_plan_version bigint NOT NULL,
  finding_id uuid NOT NULL,
  version bigint NOT NULL DEFAULT 0,
  request_sha256 varchar(64) NOT NULL,
  created_at timestamptz NOT NULL,
  CONSTRAINT inventory_publication_source_operation_pkey
    PRIMARY KEY (inventory_id, final_plan_version, finding_id),
  CONSTRAINT ck_inventory_publication_source_operation_version CHECK (version >= 0),
  CONSTRAINT ck_inventory_publication_source_operation_hash CHECK (
    request_sha256 ~ '^[0-9a-f]{64}$'
  ),
  CONSTRAINT ck_inventory_publication_source_operation_plan_version CHECK (
    final_plan_version >= 1
  )
);

CREATE TABLE public.inventory_publication_source (
  inventory_id uuid NOT NULL,
  final_plan_version bigint NOT NULL,
  finding_id uuid NOT NULL,
  version bigint NOT NULL DEFAULT 0,
  warehouse_id uuid NOT NULL,
  finding_revision bigint NOT NULL,
  asset_id uuid NOT NULL,
  asset_version_snapshot bigint NOT NULL,
  final_plan_sha256 varchar(64) NOT NULL,
  plan_fingerprint_sha256 varchar(64) NOT NULL,
  snapshot_schema_version integer NOT NULL,
  plan_snapshot jsonb NOT NULL,
  media_snapshot jsonb NOT NULL,
  priority integer NOT NULL,
  movement_to_repair boolean NOT NULL,
  movement_scheduled_date date,
  repair_scheduled_date date NOT NULL,
  strategy varchar(16) NOT NULL,
  selected_target_kind varchar(16),
  selected_target_id uuid,
  superseded_target_kind varchar(16),
  superseded_target_id uuid,
  target_kind varchar(16) NOT NULL,
  target_id uuid NOT NULL,
  estimate_id uuid,
  repair_id uuid,
  request_sha256 varchar(64) NOT NULL,
  idempotency_key uuid NOT NULL,
  created_at timestamptz NOT NULL,
  CONSTRAINT inventory_publication_source_pkey
    PRIMARY KEY (inventory_id, final_plan_version, finding_id),
  CONSTRAINT fk_inventory_publication_source_operation
    FOREIGN KEY (inventory_id, final_plan_version, finding_id)
    REFERENCES public.inventory_publication_source_operation(
      inventory_id, final_plan_version, finding_id
    ),
  CONSTRAINT fk_inventory_publication_source_estimate
    FOREIGN KEY (estimate_id) REFERENCES public.maintenance_estimate(id),
  CONSTRAINT fk_inventory_publication_source_repair
    FOREIGN KEY (repair_id) REFERENCES public.maintenance_repair(id),
  CONSTRAINT ck_inventory_publication_source_version CHECK (version >= 0),
  CONSTRAINT ck_inventory_publication_source_plan_version CHECK (final_plan_version >= 1),
  CONSTRAINT ck_inventory_publication_source_finding_revision CHECK (finding_revision >= 1),
  CONSTRAINT ck_inventory_publication_source_asset_version CHECK (asset_version_snapshot >= 0),
  CONSTRAINT ck_inventory_publication_source_hashes CHECK (
    final_plan_sha256 ~ '^[0-9a-f]{64}$'
    AND plan_fingerprint_sha256 ~ '^[0-9a-f]{64}$'
    AND request_sha256 ~ '^[0-9a-f]{64}$'
  ),
  CONSTRAINT ck_inventory_publication_source_schema CHECK (
    snapshot_schema_version IN (1, 2)
  ),
  CONSTRAINT ck_inventory_publication_source_json CHECK (
    jsonb_typeof(plan_snapshot) = 'object'
    AND jsonb_typeof(media_snapshot) = 'array'
  ),
  CONSTRAINT ck_inventory_publication_source_priority CHECK (priority BETWEEN 1 AND 5),
  CONSTRAINT ck_inventory_publication_source_movement CHECK (
    movement_to_repair OR movement_scheduled_date IS NULL
  ),
  CONSTRAINT ck_inventory_publication_source_strategy CHECK (
    (strategy = 'CREATE'
      AND selected_target_kind IS NULL AND selected_target_id IS NULL
      AND superseded_target_kind IS NULL AND superseded_target_id IS NULL)
    OR (strategy IN ('REPLACE', 'MERGE')
      AND selected_target_kind IN ('ESTIMATE', 'REPAIR') AND selected_target_id IS NOT NULL
      AND superseded_target_kind = selected_target_kind
      AND superseded_target_id = selected_target_id)
  ),
  CONSTRAINT ck_inventory_publication_source_target CHECK (
    (target_kind = 'ESTIMATE' AND target_id = estimate_id AND repair_id IS NULL)
    OR (target_kind = 'REPAIR' AND target_id = repair_id AND estimate_id IS NULL)
  )
);

CREATE UNIQUE INDEX uk_inventory_publication_source_estimate
  ON public.inventory_publication_source(estimate_id)
  WHERE estimate_id IS NOT NULL;
CREATE UNIQUE INDEX uk_inventory_publication_source_repair
  ON public.inventory_publication_source(repair_id)
  WHERE repair_id IS NOT NULL;
CREATE INDEX idx_inventory_publication_source_asset
  ON public.inventory_publication_source(asset_id, created_at, finding_id);

CREATE FUNCTION public.enforce_inventory_publication_source_immutability()
RETURNS trigger
LANGUAGE plpgsql
AS $$
BEGIN
  RAISE EXCEPTION 'inventory publication sources are immutable'
    USING ERRCODE = '23514';
END;
$$;

CREATE TRIGGER trg_inventory_publication_source_immutable
BEFORE UPDATE ON public.inventory_publication_source
FOR EACH ROW
EXECUTE FUNCTION public.enforce_inventory_publication_source_immutability();

-- The full-state event log is replay authority.  New estimate fields are appended to every
-- historical ESTIMATE state with their immutable default values and matching canonical hashes.
WITH normalized AS (
  SELECT
    event_id,
    jsonb_set(
      payload,
      '{state}',
      (payload -> 'state') || jsonb_build_object(
        'priority', 3,
        'movementToRepair', false,
        'movementScheduledDate', null,
        'inventorySupersededAt', null
      ),
      false
    ) AS payload
  FROM public.domain_event
  WHERE aggregate_type = 'ESTIMATE'
)
UPDATE public.domain_event AS event
SET payload = normalized.payload,
    payload_sha256 = encode(sha256(convert_to(normalized.payload::text, 'UTF8')), 'hex')
FROM normalized
WHERE normalized.event_id = event.event_id;

WITH normalized AS (
  SELECT
    aggregate_type,
    aggregate_id,
    aggregate_version,
    state || jsonb_build_object(
      'priority', 3,
      'movementToRepair', false,
      'movementScheduledDate', null,
      'inventorySupersededAt', null
    ) AS state
  FROM public.aggregate_snapshot
  WHERE aggregate_type = 'ESTIMATE'
)
UPDATE public.aggregate_snapshot AS snapshot
SET state = normalized.state,
    state_sha256 = encode(sha256(convert_to(normalized.state::text, 'UTF8')), 'hex')
FROM normalized
WHERE normalized.aggregate_type = snapshot.aggregate_type
  AND normalized.aggregate_id = snapshot.aggregate_id
  AND normalized.aggregate_version = snapshot.aggregate_version;

UPDATE public.projection_checkpoint AS checkpoint
SET projection_sha256 = snapshot.state_sha256,
    updated_at = clock_timestamp()
FROM public.event_stream_head AS head
JOIN public.aggregate_snapshot AS snapshot
  ON snapshot.aggregate_type = head.aggregate_type
 AND snapshot.aggregate_id = head.aggregate_id
 AND snapshot.aggregate_version = head.current_version
WHERE checkpoint.aggregate_type = 'ESTIMATE'
  AND checkpoint.aggregate_type = head.aggregate_type
  AND checkpoint.aggregate_id = head.aggregate_id;
