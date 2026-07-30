ALTER TABLE public.inventory_finding
  ADD COLUMN current_display_canonical_number varchar(128),
  ADD COLUMN current_passport_snapshot jsonb,
  ADD COLUMN current_contents_snapshot jsonb,
  ADD COLUMN current_repairs_snapshot jsonb,
  ADD COLUMN inspection_asset_version bigint,
  ADD COLUMN inspection_warehouse_id uuid,
  ADD COLUMN inspection_status varchar(48),
  ADD COLUMN inspection_display_canonical_number varchar(128),
  ADD COLUMN inspection_tenant_snapshot varchar(512),
  ADD COLUMN inspection_passport_snapshot jsonb,
  ADD COLUMN inspection_contents_snapshot jsonb,
  ADD COLUMN inspection_repairs_snapshot jsonb,
  ADD COLUMN conflict_resolution_strategy varchar(32),
  ADD COLUMN conflict_resolution_current_sha256 varchar(64),
  ADD COLUMN conflict_resolution_reason varchar(2000),
  ADD COLUMN conflict_resolved_at timestamptz,
  ADD COLUMN conflict_resolved_by_actor_ref jsonb;

UPDATE public.inventory_finding finding
SET current_display_canonical_number = finding.display_canonical_number,
    current_passport_snapshot = COALESCE(
      (
        SELECT expected.safe_passport_snapshot
        FROM public.inventory_expected_item expected
        WHERE expected.inventory_id = finding.inventory_id
          AND expected.finding_id = finding.id
      ),
      CASE
        WHEN finding.passport_observation_state = 'PRESENT'
          THEN finding.passport_observation
        ELSE '{}'::jsonb
      END),
    current_contents_snapshot = COALESCE(
      (
        SELECT expected.safe_contents_snapshot
        FROM public.inventory_expected_item expected
        WHERE expected.inventory_id = finding.inventory_id
          AND expected.finding_id = finding.id
      ),
      CASE
        WHEN finding.equipment_observation_state = 'PRESENT'
          THEN finding.equipment_observation
        ELSE '[]'::jsonb
      END),
    current_repairs_snapshot = '[]'::jsonb
WHERE finding.current_warehouse_id IS NOT NULL
  AND finding.current_status IS NOT NULL;

UPDATE public.inventory_finding finding
SET inspection_asset_version = COALESCE(
      finding.asset_version_snapshot,
      (
        SELECT expected.asset_version_snapshot
        FROM public.inventory_expected_item expected
        WHERE expected.inventory_id = finding.inventory_id
          AND expected.finding_id = finding.id
      )),
    inspection_warehouse_id = COALESCE(
      finding.current_warehouse_id,
      (
        SELECT session.warehouse_id
        FROM public.inventory_session session
        WHERE session.id = finding.inventory_id
      )),
    inspection_status = COALESCE(
      finding.current_status,
      (
        SELECT expected.asset_status_snapshot
        FROM public.inventory_expected_item expected
        WHERE expected.inventory_id = finding.inventory_id
          AND expected.finding_id = finding.id
      ),
      'WAREHOUSE'),
    inspection_display_canonical_number = COALESCE(
      finding.current_display_canonical_number,
      finding.display_canonical_number),
    inspection_tenant_snapshot = finding.current_tenant_snapshot,
    inspection_passport_snapshot = COALESCE(
      finding.current_passport_snapshot,
      (
        SELECT expected.safe_passport_snapshot
        FROM public.inventory_expected_item expected
        WHERE expected.inventory_id = finding.inventory_id
          AND expected.finding_id = finding.id
      ),
      '{}'::jsonb),
    inspection_contents_snapshot = COALESCE(
      finding.current_contents_snapshot,
      (
        SELECT expected.safe_contents_snapshot
        FROM public.inventory_expected_item expected
        WHERE expected.inventory_id = finding.inventory_id
          AND expected.finding_id = finding.id
      ),
      '[]'::jsonb),
    inspection_repairs_snapshot = COALESCE(
      finding.current_repairs_snapshot,
      '[]'::jsonb)
WHERE finding.inspection IN ('READY', 'WORK_STAGED');

ALTER TABLE public.inventory_finding
  ADD CONSTRAINT ck_finding_current_registry_snapshot CHECK (
    (
      current_warehouse_id IS NULL
      AND current_status IS NULL
      AND current_tenant_snapshot IS NULL
      AND current_display_canonical_number IS NULL
      AND current_passport_snapshot IS NULL
      AND current_contents_snapshot IS NULL
      AND current_repairs_snapshot IS NULL
    )
    OR (
      asset_id IS NOT NULL
      AND current_warehouse_id IS NOT NULL
      AND length(btrim(current_status)) BETWEEN 1 AND 48
      AND length(btrim(current_display_canonical_number)) BETWEEN 1 AND 128
      AND jsonb_typeof(current_passport_snapshot) = 'object'
      AND jsonb_typeof(current_contents_snapshot) = 'array'
      AND jsonb_typeof(current_repairs_snapshot) = 'array'
      AND (
        current_tenant_snapshot IS NULL
        OR length(btrim(current_tenant_snapshot)) BETWEEN 1 AND 512
      )
    )
  ),
  ADD CONSTRAINT ck_finding_inspection_registry_snapshot CHECK (
    (
      inspection = 'NOT_INSPECTED'
      AND inspection_asset_version IS NULL
      AND inspection_warehouse_id IS NULL
      AND inspection_status IS NULL
      AND inspection_display_canonical_number IS NULL
      AND inspection_tenant_snapshot IS NULL
      AND inspection_passport_snapshot IS NULL
      AND inspection_contents_snapshot IS NULL
      AND inspection_repairs_snapshot IS NULL
    )
    OR (
      inspection IN ('READY', 'WORK_STAGED')
      AND inspection_asset_version >= 0
      AND inspection_warehouse_id IS NOT NULL
      AND length(btrim(inspection_status)) BETWEEN 1 AND 48
      AND length(btrim(inspection_display_canonical_number)) BETWEEN 1 AND 128
      AND jsonb_typeof(inspection_passport_snapshot) = 'object'
      AND jsonb_typeof(inspection_contents_snapshot) = 'array'
      AND jsonb_typeof(inspection_repairs_snapshot) = 'array'
      AND (
        inspection_tenant_snapshot IS NULL
        OR length(btrim(inspection_tenant_snapshot)) BETWEEN 1 AND 512
      )
    )
  ),
  ADD CONSTRAINT ck_finding_conflict_resolution CHECK (
    (
      conflict_resolution_strategy IS NULL
      AND conflict_resolution_current_sha256 IS NULL
      AND conflict_resolution_reason IS NULL
      AND conflict_resolved_at IS NULL
      AND conflict_resolved_by_actor_ref IS NULL
    )
    OR (
      inspection IN ('READY', 'WORK_STAGED')
      AND conflict_resolution_strategy IN ('ACCEPT_REGISTRY', 'KEEP_INSPECTION')
      AND conflict_resolution_current_sha256 ~ '^[0-9a-f]{64}$'
      AND (
        conflict_resolution_reason IS NULL
        OR length(btrim(conflict_resolution_reason)) BETWEEN 1 AND 2000
      )
      AND conflict_resolved_at IS NOT NULL
      AND jsonb_typeof(conflict_resolved_by_actor_ref) = 'object'
      AND jsonb_exists_all(
        conflict_resolved_by_actor_ref,
        array['subjectId','principalType','profileRevision'])
      AND conflict_resolved_by_actor_ref
        - array['subjectId','principalType','profileRevision'] = '{}'::jsonb
    )
  );
