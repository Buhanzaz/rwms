CREATE TABLE public.inventory_membership_movement (
  id uuid NOT NULL,
  inventory_id uuid NOT NULL,
  source_event_id uuid NOT NULL,
  asset_id uuid NOT NULL,
  movement_type varchar(24) NOT NULL,
  finding_origin varchar(32) NOT NULL,
  display_canonical_number varchar(128) NOT NULL,
  from_warehouse_id uuid,
  to_warehouse_id uuid,
  asset_status varchar(48),
  tenant_snapshot varchar(512),
  occurred_at timestamptz NOT NULL,
  CONSTRAINT inventory_membership_movement_pkey PRIMARY KEY (id),
  CONSTRAINT uk_inventory_membership_movement_source UNIQUE (
    inventory_id, source_event_id),
  CONSTRAINT fk_inventory_membership_movement_session FOREIGN KEY (inventory_id)
    REFERENCES public.inventory_session(id),
  CONSTRAINT ck_inventory_membership_movement_type CHECK (
    movement_type IN ('DEPARTED','TRANSFERRED','ARRIVED')),
  CONSTRAINT ck_inventory_membership_movement_origin CHECK (
    finding_origin IN ('EXPECTED','ADDED_NEW','ADDED_USED','UNEXPECTED_EXISTING')),
  CONSTRAINT ck_inventory_membership_movement_display CHECK (
    btrim(display_canonical_number) <> ''),
  CONSTRAINT ck_inventory_membership_movement_direction CHECK (
    (movement_type = 'ARRIVED' AND to_warehouse_id IS NOT NULL)
    OR (movement_type IN ('DEPARTED','TRANSFERRED') AND from_warehouse_id IS NOT NULL))
);

CREATE INDEX idx_inventory_membership_movement_session_time
  ON public.inventory_membership_movement(inventory_id, occurred_at, id);

CREATE TRIGGER inventory_membership_movement_append_only
BEFORE UPDATE OR DELETE ON public.inventory_membership_movement
FOR EACH ROW EXECUTE FUNCTION public.reject_inventory_append_only_mutation();

-- V4 normalized the then-active dynamic arrivals as immutable-start
-- exceptions. Restore those rows to the live expected population. The
-- original finding event retains their pre-V4 origin and source event.
WITH repair_candidates AS (
  SELECT
    finding.id,
    finding.inventory_id,
    row_number() OVER (
      PARTITION BY finding.inventory_id
      ORDER BY finding.created_at, finding.id) AS repair_order
  FROM public.inventory_finding finding
  JOIN public.inventory_session session
    ON session.id = finding.inventory_id
   AND session.lifecycle = 'ACTIVE'
  WHERE finding.origin = 'UNEXPECTED_EXISTING'
    AND finding.expected_item_id IS NULL
    AND EXISTS (
      SELECT 1
      FROM public.domain_event event
      WHERE event.aggregate_type = 'FINDING'
        AND event.aggregate_id = finding.id::text
        AND event.event_type = 'inventory.finding.added.v1'
        AND event.event_body->>'origin' = 'EXPECTED')
),
repaired AS (
  UPDATE public.inventory_finding finding
  SET origin = 'EXPECTED',
      expected_item_id = gen_random_uuid()
  FROM repair_candidates candidate
  WHERE finding.id = candidate.id
  RETURNING finding.*
)
INSERT INTO public.inventory_expected_item(
  row_id, inventory_id, finding_id, item_order, asset_id, asset_version_snapshot,
  asset_status_snapshot, display_canonical_number, identity_match_key,
  safe_passport_snapshot, safe_contents_snapshot, captured_at)
SELECT
  repaired.expected_item_id,
  repaired.inventory_id,
  repaired.id,
  COALESCE((
    SELECT max(existing.item_order)
    FROM public.inventory_expected_item existing
    WHERE existing.inventory_id = repaired.inventory_id), -1)
    + candidate.repair_order,
  repaired.asset_id,
  repaired.asset_version_snapshot,
  COALESCE(repaired.current_status, 'WAREHOUSE'),
  repaired.display_canonical_number,
  repaired.identity_match_key,
  '{}'::jsonb,
  '[]'::jsonb,
  repaired.created_at
FROM repaired
JOIN repair_candidates candidate ON candidate.id = repaired.id
WHERE repaired.asset_id IS NOT NULL
  AND repaired.asset_version_snapshot IS NOT NULL;

-- Backfill movement rows still provable for an active session: arrivals that
-- were added after the initial capture and cabins currently outside the live
-- session population. Completed history remains untouched.
WITH captured_population AS (
  SELECT DISTINCT ON (result.operation_id)
    result.operation_id,
    result.total_count
  FROM public.inventory_start_capture_result result
  WHERE result.outcome = 'CAPTURED'
  ORDER BY result.operation_id, result.technical_attempt DESC
),
arrival_candidates AS (
  SELECT
    finding.inventory_id,
    COALESCE(event.causation_id, attachment.technical_attempt_id, event.event_id)
      AS source_event_id,
    finding.asset_id,
    finding.origin,
    finding.display_canonical_number,
    session.warehouse_id,
    finding.current_status,
    finding.current_tenant_snapshot,
    COALESCE(event.occurred_at, event.recorded_at, finding.created_at) AS occurred_at
  FROM public.inventory_finding finding
  JOIN public.inventory_session session
    ON session.id = finding.inventory_id
   AND session.lifecycle = 'ACTIVE'
  JOIN captured_population capture
    ON capture.operation_id = session.start_operation_id
  LEFT JOIN public.inventory_expected_item expected
    ON expected.finding_id = finding.id
  LEFT JOIN LATERAL (
    SELECT value.*
    FROM public.domain_event value
    WHERE value.aggregate_type = 'FINDING'
      AND value.aggregate_id = finding.id::text
      AND value.event_type = 'inventory.finding.added.v1'
    ORDER BY value.aggregate_version
    LIMIT 1
  ) event ON true
  LEFT JOIN public.inventory_source_attachment attachment
    ON attachment.inventory_id = finding.inventory_id
   AND attachment.finding_id = finding.id
  WHERE finding.membership_active
    AND finding.asset_id IS NOT NULL
    AND (
      finding.origin <> 'EXPECTED'
      OR expected.item_order >= capture.total_count)
)
INSERT INTO public.inventory_membership_movement(
  id, inventory_id, source_event_id, asset_id, movement_type, finding_origin,
  display_canonical_number, from_warehouse_id, to_warehouse_id, asset_status,
  tenant_snapshot, occurred_at)
SELECT
  gen_random_uuid(),
  candidate.inventory_id,
  candidate.source_event_id,
  candidate.asset_id,
  'ARRIVED',
  candidate.origin,
  candidate.display_canonical_number,
  NULL,
  candidate.warehouse_id,
  candidate.current_status,
  candidate.current_tenant_snapshot,
  candidate.occurred_at
FROM arrival_candidates candidate
WHERE candidate.source_event_id IS NOT NULL
ON CONFLICT (inventory_id, source_event_id) DO NOTHING;

INSERT INTO public.inventory_membership_movement(
  id, inventory_id, source_event_id, asset_id, movement_type, finding_origin,
  display_canonical_number, from_warehouse_id, to_warehouse_id, asset_status,
  tenant_snapshot, occurred_at)
SELECT
  gen_random_uuid(),
  finding.inventory_id,
  finding.id,
  finding.asset_id,
  CASE
    WHEN finding.current_status = 'IN_TRANSFER'
      OR finding.current_warehouse_id IS NOT NULL
        AND finding.current_warehouse_id <> session.warehouse_id
      THEN 'TRANSFERRED'
    ELSE 'DEPARTED'
  END,
  finding.origin,
  finding.display_canonical_number,
  session.warehouse_id,
  CASE
    WHEN finding.current_warehouse_id <> session.warehouse_id
      THEN finding.current_warehouse_id
    ELSE NULL
  END,
  finding.current_status,
  finding.current_tenant_snapshot,
  finding.updated_at
FROM public.inventory_finding finding
JOIN public.inventory_session session
  ON session.id = finding.inventory_id
 AND session.lifecycle = 'ACTIVE'
WHERE NOT finding.membership_active
  AND finding.asset_id IS NOT NULL
ON CONFLICT (inventory_id, source_event_id) DO NOTHING;

UPDATE public.inventory_session session
SET expected_population_count = (
  SELECT count(*)::integer
  FROM public.inventory_finding finding
  WHERE finding.inventory_id = session.id
    AND finding.membership_active
    AND finding.origin = 'EXPECTED')
WHERE session.lifecycle = 'ACTIVE';
