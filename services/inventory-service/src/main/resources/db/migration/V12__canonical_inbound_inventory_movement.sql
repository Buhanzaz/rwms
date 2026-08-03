-- A one-time cutover from stage-shaped movement markers to explicit frozen-plan movement flags.
-- Runtime code reads only the two snapshot flags after this migration.

ALTER TABLE public.finding_plan_snapshot
  ADD COLUMN movement_to_repair boolean NOT NULL DEFAULT false,
  ADD COLUMN movement_to_shipment boolean NOT NULL DEFAULT false;

UPDATE public.finding_plan_snapshot AS snapshot
SET movement_to_repair = COALESCE(snapshot.movement_to_repair, false)
      OR COALESCE((snapshot.source_snapshot ->> 'movementToRepair')::boolean, false)
      OR COALESCE((snapshot.source_snapshot ->> 'moveToRepairRequired')::boolean, false)
      OR EXISTS (
        SELECT 1
        FROM public.finding_plan_stage AS stage
        WHERE stage.finding_id = snapshot.finding_id
          AND stage.finding_revision = snapshot.finding_revision
          AND stage.stage_kind = 'MOVE_TO_REPAIR'
      ),
    movement_to_shipment = COALESCE(snapshot.movement_to_shipment, false)
      OR COALESCE((snapshot.source_snapshot ->> 'movementToShipment')::boolean, false)
      OR COALESCE((snapshot.source_snapshot ->> 'moveFromRepairRequired')::boolean, false)
      OR EXISTS (
        SELECT 1
        FROM public.finding_plan_stage AS stage
        WHERE stage.finding_id = snapshot.finding_id
          AND stage.finding_revision = snapshot.finding_revision
          AND stage.stage_kind = 'MOVE_FROM_REPAIR'
      );

-- Planning is an inbound-only decision. An old AUTO value on a plan without inbound movement
-- was an implicit movement intent and must not survive the cutover.
ALTER TABLE public.finding_plan_snapshot
  DROP CONSTRAINT ck_finding_plan_snapshot_logistics_planning,
  ALTER COLUMN logistics_planning_mode DROP NOT NULL,
  ALTER COLUMN logistics_planning_mode DROP DEFAULT;

UPDATE public.finding_plan_snapshot
SET logistics_planning_mode = CASE
      WHEN movement_to_repair
       AND logistics_planning_mode = 'FIXED_DATE'
       AND logistics_scheduled_date IS NOT NULL THEN 'FIXED_DATE'
      WHEN movement_to_repair THEN 'AUTO'
      ELSE NULL
    END,
    logistics_scheduled_date = CASE
      WHEN movement_to_repair
       AND logistics_planning_mode = 'FIXED_DATE'
       AND logistics_scheduled_date IS NOT NULL THEN logistics_scheduled_date
      ELSE NULL
    END;

ALTER TABLE public.finding_plan_snapshot
  ADD CONSTRAINT ck_finding_plan_snapshot_logistics_planning CHECK (
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

-- Frozen snapshots are immutable at runtime. Rewrite every legacy document exactly once here,
-- including the maintenance-issued fingerprint that binds future publication.
CREATE FUNCTION public.inventory_v12_canonical_frozen_plan(
  document jsonb,
  inbound boolean,
  outbound boolean,
  planning_mode varchar,
  planning_date date
)
RETURNS jsonb
LANGUAGE plpgsql
STABLE
AS $$
DECLARE
  result jsonb;
  stages jsonb := '[]'::jsonb;
  stage jsonb;
  stage_no integer := 0;
  mode jsonb;
  scheduled_date jsonb;
BEGIN
  IF document IS NULL OR jsonb_typeof(document) <> 'object' THEN
    RAISE EXCEPTION 'inventory frozen plan snapshot must be an object';
  END IF;

  IF inbound AND planning_mode = 'FIXED_DATE' AND planning_date IS NOT NULL THEN
    mode := '"FIXED_DATE"'::jsonb;
    scheduled_date := to_jsonb(planning_date);
  ELSIF inbound AND planning_mode = 'AUTO' AND planning_date IS NULL THEN
    mode := '"AUTO"'::jsonb;
    scheduled_date := 'null'::jsonb;
  ELSIF NOT inbound AND planning_mode IS NULL AND planning_date IS NULL THEN
    mode := 'null'::jsonb;
    scheduled_date := 'null'::jsonb;
  ELSE
    RAISE EXCEPTION 'inventory frozen plan logistics planning is invalid';
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
      'priority', COALESCE(document -> 'priority', '3'::jsonb),
      'movementToRepair', inbound,
      'movementToShipment', outbound,
      'logisticsPlanningMode', mode,
      'logisticsScheduledDate', scheduled_date);
END
$$;

UPDATE public.finding_plan_snapshot AS snapshot
SET source_snapshot = public.inventory_v12_canonical_frozen_plan(
      snapshot.source_snapshot,
      snapshot.movement_to_repair,
      snapshot.movement_to_shipment,
      snapshot.logistics_planning_mode,
      snapshot.logistics_scheduled_date);

UPDATE public.finding_plan_snapshot
SET plan_fingerprint_sha256 = encode(
      sha256(convert_to(source_snapshot::text, 'UTF8')),
      'hex'
    );

UPDATE public.inventory_finding AS finding
SET maintenance_plan_fingerprint_sha256 = snapshot.plan_fingerprint_sha256
FROM public.finding_plan_snapshot AS snapshot
WHERE finding.id = snapshot.finding_id
  AND finding.finding_revision = snapshot.finding_revision
  AND finding.inspection = 'WORK_STAGED';

-- The frozen stage projection remains as display evidence, but no longer represents movement.
ALTER TABLE public.finding_plan_stage
  DROP CONSTRAINT uk_finding_plan_stage_no;

DELETE FROM public.finding_plan_stage
WHERE stage_kind IN ('MOVE_TO_REPAIR', 'MOVE_FROM_REPAIR');

WITH ordered AS (
  SELECT
    row_id,
    row_number() OVER (
      PARTITION BY finding_id, finding_revision
      ORDER BY stage_no, row_id
    ) - 1 AS stage_no
  FROM public.finding_plan_stage
)
UPDATE public.finding_plan_stage AS stage
SET stage_no = ordered.stage_no
FROM ordered
WHERE ordered.row_id = stage.row_id;

-- Reindexing the retained rows queues the pre-existing deferred limit trigger. PostgreSQL must
-- drain it before the same table's constraints can be replaced in this migration.
SET CONSTRAINTS ck_finding_plan_stage_limit IMMEDIATE;

ALTER TABLE public.finding_plan_stage
  ADD CONSTRAINT uk_finding_plan_stage_no
    UNIQUE (finding_id, finding_revision, stage_no),
  DROP CONSTRAINT ck_finding_plan_stage_kind,
  ADD CONSTRAINT ck_finding_plan_stage_kind CHECK (stage_kind = 'REPAIR_WORK'),
  DROP COLUMN movement_required;

DROP FUNCTION public.inventory_v12_canonical_frozen_plan(jsonb, boolean, boolean, varchar, date);
