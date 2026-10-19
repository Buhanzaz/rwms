-- A one-time cutover from stage-shaped movement markers to the canonical repair flag.
-- Runtime code reads only maintenance_repair.movement_to_repair after this migration.
-- Completed legacy task-board entries remain in their owning task-board/event histories; the
-- active maintenance projection no longer retains a second movement-stage representation.

ALTER TABLE public.maintenance_repair
  ADD COLUMN movement_to_repair boolean NOT NULL DEFAULT false;

UPDATE public.maintenance_repair AS repair
SET movement_to_repair = COALESCE(repair.movement_to_repair, false)
      OR EXISTS (
        SELECT 1
        FROM public.repair_stage AS stage
        WHERE stage.repair_id = repair.id
          AND stage.stage_kind = 'MOVE_TO_REPAIR'
      ),
    movement_to_shipment = COALESCE(repair.movement_to_shipment, false)
      OR EXISTS (
        SELECT 1
        FROM public.repair_stage AS stage
        WHERE stage.repair_id = repair.id
          AND stage.stage_kind = 'MOVE_FROM_REPAIR'
      );

-- A movement stage that is still active cannot be discarded safely: it may still be owned by a
-- worker/task-board process. Evidence is similarly immutable historical proof, so require an
-- explicit operator decision instead of creating a dangling reference or deleting history.
DO $$
BEGIN
  IF EXISTS (
    SELECT 1
    FROM public.repair_stage AS stage
    WHERE stage.stage_kind IN ('MOVE_TO_REPAIR', 'MOVE_FROM_REPAIR')
      AND stage.state <> 'DONE'
  ) THEN
    RAISE EXCEPTION
      'cannot cut over active legacy movement stages; complete or reconcile them before V27';
  END IF;

  IF EXISTS (
    SELECT 1
    FROM public.repair_task_evidence AS evidence
    JOIN public.repair_stage AS stage
      ON stage.repair_id = evidence.repair_id
     AND stage.stage_id = evidence.repair_stage_id
    WHERE stage.stage_kind IN ('MOVE_TO_REPAIR', 'MOVE_FROM_REPAIR')
  ) THEN
    RAISE EXCEPTION
      'cannot cut over legacy movement stages referenced by repair_task_evidence';
  END IF;
END
$$;

-- Planning fields describe only an inbound movement. Keeping AUTO on repairs that never requested
-- delivery would be a second implicit source of logistics intent.
ALTER TABLE public.maintenance_repair
  DROP CONSTRAINT ck_maintenance_repair_logistics_planning,
  ALTER COLUMN logistics_planning_mode DROP NOT NULL,
  ALTER COLUMN logistics_planning_mode DROP DEFAULT;

UPDATE public.maintenance_repair
SET logistics_planning_mode = CASE
      WHEN movement_to_repair THEN COALESCE(logistics_planning_mode, 'AUTO')
      ELSE NULL
    END,
    logistics_scheduled_date = CASE
      WHEN movement_to_repair
       AND logistics_planning_mode = 'FIXED_DATE'
       AND logistics_scheduled_date IS NOT NULL THEN logistics_scheduled_date
      ELSE NULL
    END;

UPDATE public.maintenance_repair
SET logistics_planning_mode = 'AUTO'
WHERE movement_to_repair
  AND (logistics_planning_mode IS NULL OR logistics_planning_mode = 'FIXED_DATE')
  AND logistics_scheduled_date IS NULL;

ALTER TABLE public.maintenance_repair
  ADD CONSTRAINT ck_maintenance_repair_logistics_planning CHECK (
    (
      NOT movement_to_repair
      AND logistics_planning_mode IS NULL
      AND logistics_scheduled_date IS NULL
    )
    OR
    (
      movement_to_repair
      AND logistics_planning_mode = 'AUTO'
      AND logistics_scheduled_date IS NULL
    )
    OR
    (
      movement_to_repair
      AND logistics_planning_mode = 'FIXED_DATE'
      AND logistics_scheduled_date IS NOT NULL
    )
  );

-- Frozen inventory plans are immutable at runtime. Canonicalize their snapshots and all hashes
-- inside this migration, then restore the same immutability trigger.
CREATE FUNCTION public.maintenance_v27_inventory_plan(document jsonb)
RETURNS jsonb
LANGUAGE plpgsql
STABLE
AS $$
DECLARE
  result jsonb;
  stages jsonb := '[]'::jsonb;
  stage jsonb;
  stage_no integer := 0;
  inbound boolean;
  outbound boolean;
  requested_mode text;
  requested_date jsonb;
  mode jsonb;
  scheduled_date jsonb;
BEGIN
  IF document IS NULL OR jsonb_typeof(document) <> 'object' THEN
    RAISE EXCEPTION 'inventory repair plan snapshot must be an object';
  END IF;

  inbound := COALESCE(NULLIF(document ->> 'movementToRepair', '')::boolean, false)
      OR COALESCE(NULLIF(document ->> 'moveToRepairRequired', '')::boolean, false);
  outbound := COALESCE(NULLIF(document ->> 'movementToShipment', '')::boolean, false)
      OR COALESCE(NULLIF(document ->> 'moveFromRepairRequired', '')::boolean, false);
  requested_mode := upper(COALESCE(
      NULLIF(document ->> 'logisticsPlanningMode', ''),
      'AUTO'));
  requested_date := COALESCE(document -> 'logisticsScheduledDate', 'null'::jsonb);

  IF inbound
      AND requested_mode = 'FIXED_DATE'
      AND requested_date <> 'null'::jsonb THEN
    mode := '"FIXED_DATE"'::jsonb;
    scheduled_date := requested_date;
  ELSIF inbound THEN
    mode := '"AUTO"'::jsonb;
    scheduled_date := 'null'::jsonb;
  ELSE
    mode := 'null'::jsonb;
    scheduled_date := 'null'::jsonb;
  END IF;

  FOR stage IN
    SELECT value
    FROM jsonb_array_elements(
      CASE
        WHEN jsonb_typeof(document -> 'stages') = 'array' THEN document -> 'stages'
        ELSE '[]'::jsonb
      END)
  LOOP
    IF stage ->> 'kind' = 'REPAIR_WORK' THEN
      stage := (stage - 'order') || jsonb_build_object('order', stage_no);
      stages := stages || jsonb_build_array(stage);
      stage_no := stage_no + 1;
    END IF;
  END LOOP;

  result := document
      - 'moveToRepairRequired'
      - 'moveFromRepairRequired'
      - 'movementToRepair'
      - 'movementToShipment'
      - 'logisticsPlanningMode'
      - 'logisticsScheduledDate';
  RETURN result || jsonb_build_object(
      'stages', stages,
      'movementToRepair', inbound,
      'movementToShipment', outbound,
      'logisticsPlanningMode', mode,
      'logisticsScheduledDate', scheduled_date);
END
$$;

CREATE FUNCTION public.maintenance_v27_freeze_request(
  warehouse_id uuid,
  inventory_id uuid,
  finding_id uuid,
  source_revision bigint,
  snapshot jsonb
)
RETURNS jsonb
LANGUAGE sql
STABLE
AS $$
  SELECT jsonb_build_object(
    'warehouseId', warehouse_id,
    'inventoryId', inventory_id,
    'findingId', finding_id,
    'sourceRevision', source_revision,
    'mode', snapshot -> 'mode',
    'lines', COALESCE((
      SELECT jsonb_agg(
        CASE line ->> 'aggregationKind'
          WHEN 'CATALOG' THEN jsonb_build_object(
            'aggregationKind', 'CATALOG',
            'catalogNodeId', line -> 'catalogNodeId',
            'description', NULL,
            'type', NULL,
            'unit', NULL,
            'quantity', line -> 'quantity',
            'unitPriceMinor', NULL,
            'normativeMinutes', NULL,
            'groupComment', line -> 'groupComment',
            'mediaReferences', COALESCE(line -> 'mediaReferences', '[]'::jsonb)
          )
          ELSE jsonb_build_object(
            'aggregationKind', 'MANUAL',
            'catalogNodeId', NULL,
            'description', line -> 'description',
            'type', line -> 'type',
            'unit', line -> 'unit',
            'quantity', line -> 'quantity',
            'unitPriceMinor', line -> 'unitPriceMinor',
            'normativeMinutes', line -> 'normativeMinutes',
            'groupComment', line -> 'groupComment',
            'mediaReferences', COALESCE(line -> 'mediaReferences', '[]'::jsonb)
          )
        END
        ORDER BY position
      )
      FROM jsonb_array_elements(
        CASE
          WHEN jsonb_typeof(snapshot -> 'lines') = 'array' THEN snapshot -> 'lines'
          ELSE '[]'::jsonb
        END) WITH ORDINALITY AS source(line, position)
    ), '[]'::jsonb),
    'plan', COALESCE((
      SELECT jsonb_agg(
        jsonb_build_object(
          'catalogNodeId', stage -> 'catalogNodeId',
          'kind', stage -> 'kind',
          'order', stage -> 'order'
        )
        ORDER BY position
      )
      FROM jsonb_array_elements(
        CASE
          WHEN jsonb_typeof(snapshot -> 'stages') = 'array' THEN snapshot -> 'stages'
          ELSE '[]'::jsonb
        END) WITH ORDINALITY AS source(stage, position)
    ), '[]'::jsonb),
    'mediaReferences', COALESCE(snapshot -> 'mediaReferences', '[]'::jsonb),
    'priority', snapshot -> 'priority',
    'coverMediaId', snapshot -> 'coverMediaId',
    'movementToRepair', snapshot -> 'movementToRepair',
    'movementToShipment', snapshot -> 'movementToShipment',
    'logisticsPlanningMode', snapshot -> 'logisticsPlanningMode',
    'logisticsScheduledDate', snapshot -> 'logisticsScheduledDate'
  )
$$;

DROP TRIGGER trg_inventory_repair_source_immutable
  ON public.inventory_repair_source;

UPDATE public.inventory_repair_source
SET plan_snapshot = public.maintenance_v27_inventory_plan(plan_snapshot);

UPDATE public.inventory_repair_source
SET plan_fingerprint = encode(
      sha256(convert_to(plan_snapshot::text, 'UTF8')),
      'hex'
    ),
    plan_request_sha256 = encode(
      sha256(convert_to(
        public.maintenance_v27_freeze_request(
          warehouse_id,
          inventory_id,
          finding_id,
          source_revision,
          plan_snapshot
        )::text,
        'UTF8'
      )),
      'hex'
    );

UPDATE public.inventory_repair_source_operation AS operation
SET request_sha256 = source.plan_request_sha256
FROM public.inventory_repair_source AS source
WHERE source.inventory_id = operation.inventory_id
  AND source.finding_id = operation.finding_id;

UPDATE public.inventory_repair_source AS source
SET source_fingerprint = encode(
      sha256(convert_to(
        jsonb_build_object(
          'inventoryId', source.inventory_id,
          'findingId', source.finding_id,
          'sourceRevision', source.source_revision,
          'warehouseId', source.warehouse_id,
          'rentalItemId', source.rental_item_id,
          'rentalItemVersion', source.rental_item_version_snapshot,
          'dispatchDate', repair.dispatch_date,
          'planFingerprint', source.plan_fingerprint,
          'snapshot', source.plan_snapshot
        )::text,
        'UTF8'
      )),
      'hex'
    )
FROM public.maintenance_repair AS repair
WHERE source.repair_id = repair.id;

CREATE TRIGGER trg_inventory_repair_source_immutable
BEFORE UPDATE ON public.inventory_repair_source
FOR EACH ROW
EXECUTE FUNCTION public.enforce_inventory_repair_source_immutability();

-- Full-state events and snapshots are the replay authority. Rewrite them together with their
-- checksums so replayed state remains byte-identical to the new JPA projection.
CREATE FUNCTION public.maintenance_v27_repair_state(document jsonb)
RETURNS jsonb
LANGUAGE plpgsql
STABLE
AS $$
DECLARE
  result jsonb;
  stages jsonb := '[]'::jsonb;
  stage jsonb;
  inbound boolean;
  outbound boolean;
  requested_mode text;
  requested_date jsonb;
  mode jsonb;
  scheduled_date jsonb;
BEGIN
  IF document IS NULL OR jsonb_typeof(document) <> 'object' THEN
    RAISE EXCEPTION 'repair full-state snapshot must be an object';
  END IF;

  inbound := COALESCE(NULLIF(document ->> 'movementToRepair', '')::boolean, false)
      OR EXISTS (
        SELECT 1
        FROM jsonb_array_elements(
          CASE
            WHEN jsonb_typeof(document -> 'stages') = 'array' THEN document -> 'stages'
            ELSE '[]'::jsonb
          END) AS legacy(stage)
        WHERE legacy.stage ->> 'kind' = 'MOVE_TO_REPAIR'
      );
  outbound := COALESCE(NULLIF(document ->> 'movementToShipment', '')::boolean, false)
      OR EXISTS (
        SELECT 1
        FROM jsonb_array_elements(
          CASE
            WHEN jsonb_typeof(document -> 'stages') = 'array' THEN document -> 'stages'
            ELSE '[]'::jsonb
          END) AS legacy(stage)
        WHERE legacy.stage ->> 'kind' = 'MOVE_FROM_REPAIR'
      );
  requested_mode := upper(COALESCE(
      NULLIF(document ->> 'logisticsPlanningMode', ''),
      'AUTO'));
  requested_date := COALESCE(document -> 'logisticsScheduledDate', 'null'::jsonb);

  IF inbound
      AND requested_mode = 'FIXED_DATE'
      AND requested_date <> 'null'::jsonb THEN
    mode := '"FIXED_DATE"'::jsonb;
    scheduled_date := requested_date;
  ELSIF inbound THEN
    mode := '"AUTO"'::jsonb;
    scheduled_date := 'null'::jsonb;
  ELSE
    mode := 'null'::jsonb;
    scheduled_date := 'null'::jsonb;
  END IF;

  FOR stage IN
    SELECT value
    FROM jsonb_array_elements(
      CASE
        WHEN jsonb_typeof(document -> 'stages') = 'array' THEN document -> 'stages'
        ELSE '[]'::jsonb
      END)
  LOOP
    IF stage ->> 'kind' = 'REPAIR_WORK' THEN
      -- Keep the existing work-stage number. It is also the task-board route index and does not
      -- need to be contiguous after a retired completed movement marker.
      stages := stages || jsonb_build_array(stage);
    END IF;
  END LOOP;

  result := document
      - 'movementToRepair'
      - 'movementToShipment'
      - 'logisticsPlanningMode'
      - 'logisticsScheduledDate';
  RETURN result || jsonb_build_object(
      'movementToRepair', inbound,
      'movementToShipment', outbound,
      'logisticsPlanningMode', mode,
      'logisticsScheduledDate', scheduled_date,
      'stages', stages);
END
$$;

CREATE FUNCTION public.maintenance_v27_estimate_state(document jsonb)
RETURNS jsonb
LANGUAGE plpgsql
STABLE
AS $$
DECLARE
  result jsonb;
  revisions jsonb := '[]'::jsonb;
  revision jsonb;
  plan jsonb;
  stage jsonb;
  stage_no integer;
BEGIN
  IF document IS NULL OR jsonb_typeof(document) <> 'object' THEN
    RAISE EXCEPTION 'estimate full-state snapshot must be an object';
  END IF;

  FOR revision IN
    SELECT value
    FROM jsonb_array_elements(
      CASE
        WHEN jsonb_typeof(document -> 'revisions') = 'array' THEN document -> 'revisions'
        ELSE '[]'::jsonb
      END)
  LOOP
    plan := '[]'::jsonb;
    stage_no := 0;
    FOR stage IN
      SELECT value
      FROM jsonb_array_elements(
        CASE
          WHEN jsonb_typeof(revision -> 'plan') = 'array' THEN revision -> 'plan'
          ELSE '[]'::jsonb
        END)
    LOOP
      IF stage ->> 'kind' = 'REPAIR_WORK' THEN
        stage := (stage - 'stageNo') || jsonb_build_object('stageNo', stage_no);
        plan := plan || jsonb_build_array(stage);
        stage_no := stage_no + 1;
      END IF;
    END LOOP;
    revision := (revision - 'plan') || jsonb_build_object('plan', plan);
    revisions := revisions || jsonb_build_array(revision);
  END LOOP;

  result := document - 'revisions';
  RETURN result || jsonb_build_object('revisions', revisions);
END
$$;

CREATE FUNCTION public.maintenance_v27_repair_fact(document jsonb)
RETURNS jsonb
LANGUAGE plpgsql
STABLE
AS $$
DECLARE
  result jsonb;
  stages jsonb := '[]'::jsonb;
  stage jsonb;
BEGIN
  IF document IS NULL OR jsonb_typeof(document) <> 'object' THEN
    RAISE EXCEPTION 'repair integration fact must be an object';
  END IF;
  FOR stage IN
    SELECT value
    FROM jsonb_array_elements(
      CASE
        WHEN jsonb_typeof(document -> 'stages') = 'array' THEN document -> 'stages'
        ELSE '[]'::jsonb
      END)
  LOOP
    IF stage ->> 'kind' = 'REPAIR_WORK' THEN
      stages := stages || jsonb_build_array(stage);
    END IF;
  END LOOP;
  result := document - 'stages';
  RETURN result || jsonb_build_object('stages', stages);
END
$$;

WITH normalized AS (
  SELECT
    event_id,
    jsonb_set(
      payload,
      '{state}',
      public.maintenance_v27_repair_state(payload -> 'state'),
      false
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
    event_id,
    jsonb_set(
      envelope_body,
      '{payload}',
      public.maintenance_v27_repair_fact(envelope_body -> 'payload'),
      false
    ) AS envelope_body
  FROM public.outbox_event
  WHERE aggregate_type = 'REPAIR'
)
UPDATE public.outbox_event AS outbox
SET envelope_body = normalized.envelope_body,
    envelope_sha256 = encode(
      sha256(convert_to(normalized.envelope_body::text, 'UTF8')),
      'hex'
    )
FROM normalized
WHERE normalized.event_id = outbox.event_id;

WITH normalized AS (
  SELECT
    aggregate_type,
    aggregate_id,
    aggregate_version,
    public.maintenance_v27_repair_state(state) AS state
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

WITH normalized AS (
  SELECT
    event_id,
    jsonb_set(
      payload,
      '{state}',
      public.maintenance_v27_estimate_state(payload -> 'state'),
      false
    ) AS payload
  FROM public.domain_event
  WHERE aggregate_type = 'ESTIMATE'
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
    public.maintenance_v27_estimate_state(state) AS state
  FROM public.aggregate_snapshot
  WHERE aggregate_type = 'ESTIMATE'
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

-- Estimate stages are not externally addressed. Rebuild their sequence after removing legacy
-- markers while the unique index is intentionally absent, then restore the same invariant.
ALTER TABLE public.estimate_plan_stage
  DROP CONSTRAINT uk_estimate_plan_stage;

DELETE FROM public.estimate_plan_stage
WHERE stage_kind IN ('MOVE_TO_REPAIR', 'MOVE_FROM_REPAIR');

WITH ordered AS (
  SELECT
    row_id,
    row_number() OVER (
      PARTITION BY estimate_id, estimate_revision
      ORDER BY stage_no, row_id
    ) - 1 AS stage_no
  FROM public.estimate_plan_stage
)
UPDATE public.estimate_plan_stage AS stage
SET stage_no = ordered.stage_no
FROM ordered
WHERE ordered.row_id = stage.row_id;

ALTER TABLE public.estimate_plan_stage
  ADD CONSTRAINT uk_estimate_plan_stage
    UNIQUE (estimate_id, estimate_revision, stage_no),
  DROP CONSTRAINT ck_estimate_plan_kind,
  ADD CONSTRAINT ck_estimate_plan_kind CHECK (stage_kind = 'REPAIR_WORK');

DELETE FROM public.repair_stage
WHERE stage_kind IN ('MOVE_TO_REPAIR', 'MOVE_FROM_REPAIR');

ALTER TABLE public.repair_stage
  DROP CONSTRAINT ck_repair_stage_kind,
  ADD CONSTRAINT ck_repair_stage_kind CHECK (stage_kind = 'REPAIR_WORK');

UPDATE public.projection_checkpoint AS checkpoint
SET projection_sha256 = snapshot.state_sha256,
    updated_at = clock_timestamp()
FROM public.event_stream_head AS head
JOIN public.aggregate_snapshot AS snapshot
  ON snapshot.aggregate_type = head.aggregate_type
 AND snapshot.aggregate_id = head.aggregate_id
 AND snapshot.aggregate_version = head.current_version
WHERE checkpoint.aggregate_type IN ('ESTIMATE', 'REPAIR')
  AND checkpoint.aggregate_type = head.aggregate_type
  AND checkpoint.aggregate_id = head.aggregate_id;

DROP FUNCTION public.maintenance_v27_repair_fact(jsonb);
DROP FUNCTION public.maintenance_v27_estimate_state(jsonb);
DROP FUNCTION public.maintenance_v27_repair_state(jsonb);
DROP FUNCTION public.maintenance_v27_freeze_request(uuid, uuid, uuid, bigint, jsonb);
DROP FUNCTION public.maintenance_v27_inventory_plan(jsonb);
