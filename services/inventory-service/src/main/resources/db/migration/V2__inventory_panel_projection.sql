ALTER TABLE public.inventory_session
  ADD COLUMN started_by_display_name varchar(255);

UPDATE public.inventory_session
SET started_by_display_name = started_by_subject_id::text;

ALTER TABLE public.inventory_session
  ALTER COLUMN started_by_display_name SET NOT NULL,
  ADD CONSTRAINT ck_inventory_started_by_display_name CHECK (
    length(btrim(started_by_display_name)) BETWEEN 1 AND 255);

ALTER TABLE public.inventory_finding
  ADD COLUMN current_warehouse_id uuid,
  ADD COLUMN current_status varchar(48),
  ADD COLUMN current_tenant_snapshot varchar(512),
  ADD COLUMN inspection_comment varchar(2000) NOT NULL DEFAULT '';

UPDATE public.inventory_finding finding
SET current_warehouse_id = session.warehouse_id,
    current_status = expected.asset_status_snapshot,
    current_tenant_snapshot = NULLIF(btrim(expected.safe_passport_snapshot ->> 'tenant'), '')
FROM public.inventory_session session,
     public.inventory_expected_item expected
WHERE finding.inventory_id = session.id
  AND finding.inventory_id = expected.inventory_id
  AND finding.expected_item_id = expected.row_id;

ALTER TABLE public.inventory_finding
  ADD CONSTRAINT ck_finding_current_snapshot CHECK (
    (current_warehouse_id IS NULL AND current_status IS NULL AND current_tenant_snapshot IS NULL)
    OR (asset_id IS NOT NULL AND current_warehouse_id IS NOT NULL
      AND length(btrim(current_status)) BETWEEN 1 AND 48
      AND (current_tenant_snapshot IS NULL
        OR length(btrim(current_tenant_snapshot)) BETWEEN 1 AND 512))),
  ADD CONSTRAINT ck_finding_inspection_comment CHECK (
    length(inspection_comment) <= 2000);
