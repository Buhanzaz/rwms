-- Maintenance owns repair complexity thresholds and physical repair-place truth.
-- Existing capacity values are preserved in place; no runtime legacy fallback remains.
ALTER TABLE public.repair_capacity_settings
  RENAME COLUMN max_repairs_per_day TO repair_place_count;

ALTER TABLE public.repair_capacity_settings
  DROP CONSTRAINT ck_repair_capacity_settings_max,
  ADD CONSTRAINT ck_repair_capacity_settings_count CHECK (repair_place_count > 0);

COMMENT ON COLUMN public.repair_capacity_settings.repair_place_count IS
  'Maximum concurrent RESERVED/OCCUPIED/READY_TO_RELEASE repair-place allocations.';

CREATE TABLE public.repair_complexity_settings (
  warehouse_id uuid NOT NULL,
  version bigint NOT NULL DEFAULT 0,
  light_boundary_minutes integer NOT NULL DEFAULT 60,
  medium_boundary_minutes integer NOT NULL DEFAULT 180,
  complex_boundary_minutes integer NOT NULL DEFAULT 360,
  imported_from_task_board_version bigint,
  imported_at timestamptz,
  created_at timestamptz NOT NULL,
  updated_at timestamptz NOT NULL,
  CONSTRAINT repair_complexity_settings_pkey PRIMARY KEY (warehouse_id),
  CONSTRAINT ck_repair_complexity_settings_version CHECK (version >= 0),
  CONSTRAINT ck_repair_complexity_settings_boundaries CHECK (
    light_boundary_minutes > 0
    AND light_boundary_minutes < medium_boundary_minutes
    AND medium_boundary_minutes < complex_boundary_minutes
  ),
  CONSTRAINT ck_repair_complexity_settings_import CHECK (
    (
      imported_from_task_board_version IS NULL
      AND imported_at IS NULL
    )
    OR
    (
      imported_from_task_board_version >= 0
      AND imported_at IS NOT NULL
    )
  )
);

COMMENT ON TABLE public.repair_complexity_settings IS
  'Per-warehouse canonical thresholds. imported_* records the one-time task-board cutover only.';

CREATE TABLE public.repair_place_allocation (
  id uuid NOT NULL,
  version bigint NOT NULL DEFAULT 0,
  warehouse_id uuid NOT NULL,
  repair_id uuid NOT NULL,
  state varchar(24) NOT NULL,
  created_at timestamptz NOT NULL,
  updated_at timestamptz NOT NULL,
  CONSTRAINT repair_place_allocation_pkey PRIMARY KEY (id),
  CONSTRAINT fk_repair_place_allocation_repair
    FOREIGN KEY (repair_id) REFERENCES public.maintenance_repair(id),
  CONSTRAINT ck_repair_place_allocation_version CHECK (version >= 0),
  CONSTRAINT ck_repair_place_allocation_state CHECK (
    state IN ('RESERVED','OCCUPIED','READY_TO_RELEASE','RELEASED')
  )
);

CREATE INDEX idx_repair_place_allocation_warehouse_state
  ON public.repair_place_allocation(warehouse_id, state, created_at, id);

CREATE UNIQUE INDEX uk_repair_place_allocation_active_repair
  ON public.repair_place_allocation(repair_id)
  WHERE state <> 'RELEASED';

COMMENT ON TABLE public.repair_place_allocation IS
  'Repair-place lifecycle cycles; only one cycle per repair may be active and RELEASED rows remain audit history.';

ALTER TABLE public.maintenance_repair
  ADD COLUMN reclassification_state varchar(24) NOT NULL DEFAULT 'STABLE',
  ADD CONSTRAINT ck_maintenance_repair_reclassification_state CHECK (
    reclassification_state IN ('STABLE','RECLASSIFYING_CAPITAL','EXTERNAL_CAPITAL')
  );

-- The local event store uses full-state snapshots. Extend every historical repair state with the
-- same STABLE default as the JPA projection and recompute the canonical hashes atomically.
WITH normalized AS (
  SELECT
    event_id,
    jsonb_set(
      payload,
      '{state,reclassificationState}',
      '"STABLE"'::jsonb,
      true
    ) AS payload
  FROM public.domain_event
  WHERE aggregate_type = 'REPAIR'
)
UPDATE public.domain_event AS event
SET payload = normalized.payload,
    payload_sha256 = encode(
      sha256(convert_to(normalized.payload::text, 'UTF8')),
      'hex'
    )
FROM normalized
WHERE normalized.event_id = event.event_id;

WITH normalized AS (
  SELECT
    aggregate_type,
    aggregate_id,
    aggregate_version,
    jsonb_set(
      state,
      '{reclassificationState}',
      '"STABLE"'::jsonb,
      true
    ) AS state
  FROM public.aggregate_snapshot
  WHERE aggregate_type = 'REPAIR'
)
UPDATE public.aggregate_snapshot AS snapshot
SET state = normalized.state,
    state_sha256 = encode(
      sha256(convert_to(normalized.state::text, 'UTF8')),
      'hex'
    )
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
WHERE checkpoint.aggregate_type = 'REPAIR'
  AND checkpoint.aggregate_type = head.aggregate_type
  AND checkpoint.aggregate_id = head.aggregate_id;

ALTER TABLE public.maintenance_repair
  DROP CONSTRAINT ck_repair_task_generation,
  ADD CONSTRAINT ck_repair_task_generation CHECK (
    task_generation_state IN ('PENDING_GENERATION','GENERATED','NOT_REQUIRED','FAILED')
  );

ALTER TABLE public.repair_stage
  DROP CONSTRAINT ck_repair_stage_generation,
  ADD CONSTRAINT ck_repair_stage_generation CHECK (
    task_generation_state IN ('PENDING_GENERATION','GENERATED','NOT_REQUIRED','FAILED')
  );

-- Existing active work already present at a warehouse consumes a physical place. Completed
-- repairs awaiting acceptance/release are ready to leave but still consume that place.
INSERT INTO public.repair_place_allocation (
  id,
  version,
  warehouse_id,
  repair_id,
  state,
  created_at,
  updated_at
)
SELECT
  gen_random_uuid(),
  0,
  repair.warehouse_id,
  repair.id,
  CASE
    WHEN repair.execution_state = 'COMPLETED' THEN 'READY_TO_RELEASE'
    ELSE 'OCCUPIED'
  END,
  now(),
  now()
FROM public.maintenance_repair AS repair
WHERE repair.execution_state IN ('QUEUED','IN_PROGRESS','COMPLETED')
  AND repair.acceptance_state NOT IN ('ACCEPTED','WRITTEN_OFF')
  AND NOT EXISTS (
    SELECT 1
    FROM public.rental_item_fact_projection AS item
    WHERE item.rental_item_id = repair.rental_item_id
      AND item.asset_status = 'CAPITAL_REPAIR'
  )
  AND NOT EXISTS (
    SELECT 1
    FROM public.repair_place_allocation AS allocation
    WHERE allocation.repair_id = repair.id
      AND allocation.state <> 'RELEASED'
  );

-- Re-evaluate every active repair through the service-owned calculator after startup. This is a
-- one-time durable cutover command, not a runtime task-board threshold fallback. Capital results
-- withdraw the ordinary task before the repair becomes externally executable/acceptable.
INSERT INTO public.integration_reconciliation (
  id,
  repair_id,
  dependency_type,
  operation_type,
  idempotency_key,
  state,
  attempt_count,
  next_attempt_at,
  last_error_code,
  response_snapshot,
  review_version,
  review_subject_id,
  review_reason,
  reviewed_at,
  created_at,
  updated_at
)
SELECT
  md5('maintenance-v23-repair-complexity-work:' || repair.id::text)::uuid,
  repair.id,
  'ASSET',
  'SYNC_REPAIR_COMPLEXITY_STATUS',
  md5('maintenance-v23-repair-complexity-command:' || repair.id::text)::uuid,
  'PENDING',
  0,
  now(),
  NULL,
  jsonb_build_object('repairId', repair.id::text),
  0,
  NULL,
  NULL,
  NULL,
  now(),
  now()
FROM public.maintenance_repair AS repair
WHERE repair.execution_state IN ('QUEUED','IN_PROGRESS')
  AND repair.acceptance_state NOT IN ('ACCEPTED','WRITTEN_OFF')
ON CONFLICT (dependency_type, operation_type, idempotency_key) DO NOTHING;
