-- Maintenance owns the user's delivery-planning choice. Logistics owns execution and resolves
-- AUTO to an actual date; no browser-owned or legacy task-board fallback remains.
ALTER TABLE public.maintenance_repair
  ADD COLUMN logistics_planning_mode varchar(16) NOT NULL DEFAULT 'AUTO',
  ADD COLUMN logistics_scheduled_date date,
  ADD CONSTRAINT ck_maintenance_repair_logistics_planning CHECK (
    (
      logistics_planning_mode = 'AUTO'
      AND logistics_scheduled_date IS NULL
    )
    OR
    (
      logistics_planning_mode = 'FIXED_DATE'
      AND logistics_scheduled_date IS NOT NULL
    )
  );

ALTER TABLE public.integration_reconciliation
  DROP CONSTRAINT ck_reconciliation_dependency,
  ADD CONSTRAINT ck_reconciliation_dependency
    CHECK (dependency_type IN ('ASSET','TASK_BOARD','MEDIA','LOGISTICS'));

-- Repair event payloads and snapshots are full-state records. Extend historical state atomically
-- and realign every integrity hash/checkpoint with the canonical JSON representation.
WITH normalized AS (
  SELECT
    event_id,
    jsonb_set(
      jsonb_set(
        payload,
        '{state,logisticsPlanningMode}',
        '"AUTO"'::jsonb,
        true
      ),
      '{state,logisticsScheduledDate}',
      'null'::jsonb,
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
      jsonb_set(
        state,
        '{logisticsPlanningMode}',
        '"AUTO"'::jsonb,
        true
      ),
      '{logisticsScheduledDate}',
      'null'::jsonb,
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
