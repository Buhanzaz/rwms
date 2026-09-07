-- Inspection evidence remains inventory-owned while operational movement stays asset/logistics-owned.
-- Imported return evidence may be read after another operational transition; its snapshot must
-- retain the actual owner status rather than substitute one of the automatic-capture statuses.
ALTER TABLE public.inventory_expected_item
  DROP CONSTRAINT ck_expected_item_status,
  ADD CONSTRAINT ck_expected_item_status CHECK (asset_status_snapshot IN (
    'RENTED','BOOKED','REPAIR','WAITING_REPAIR_CHECK','WRITTEN_OFF','LOST','CAPITAL_REPAIR',
    'AFTER_RENT','WAITING_ESTIMATE_CONFIRMATION','SALE','USED_SALE','RESERVED','FREE',
    'WAREHOUSE','OWN_NEEDS','IN_TRANSFER'));

ALTER TABLE public.inventory_finding
  ADD COLUMN inspection_source varchar(24),
  ADD COLUMN external_inspection_asset_version bigint,
  ADD COLUMN inspection_superseded_by_departure boolean NOT NULL DEFAULT false,
  ADD COLUMN membership_event_asset_version bigint,
  ADD COLUMN membership_event_warehouse_id uuid,
  ADD COLUMN membership_event_status varchar(48);

UPDATE public.inventory_finding
SET inspection_source = CASE WHEN inspection <> 'NOT_INSPECTED' THEN 'INVENTORY' END,
    membership_event_asset_version = CASE WHEN current_warehouse_id IS NOT NULL THEN asset_version_snapshot END,
    membership_event_warehouse_id = current_warehouse_id,
    membership_event_status = current_status;

ALTER TABLE public.inventory_finding
  DROP CONSTRAINT ck_finding_inspection_registry_snapshot,
  ADD CONSTRAINT ck_finding_inspection_provenance CHECK (
    (inspection = 'NOT_INSPECTED' AND inspection_source IS NULL)
    OR (inspection <> 'NOT_INSPECTED' AND inspection_source IS NOT NULL
      AND inspection_source IN ('INVENTORY', 'LOGISTICS_RETURN'))
  ),
  ADD CONSTRAINT ck_finding_inspection_registry_snapshot CHECK (
    (
      (inspection = 'NOT_INSPECTED' AND inspection_source IS NULL AND external_inspection_asset_version IS NULL)
      OR (inspection = 'READY' AND inspection_source = 'LOGISTICS_RETURN'
          AND external_inspection_asset_version IS NOT NULL AND external_inspection_asset_version >= 0)
    ) AND inspection_asset_version IS NULL AND inspection_warehouse_id IS NULL
      AND inspection_status IS NULL AND inspection_display_canonical_number IS NULL
      AND inspection_tenant_snapshot IS NULL AND inspection_passport_snapshot IS NULL
      AND inspection_contents_snapshot IS NULL AND inspection_repairs_snapshot IS NULL
    OR (
      inspection IN ('READY', 'WORK_STAGED') AND inspection_source = 'INVENTORY'
      AND external_inspection_asset_version IS NULL
      AND inspection_asset_version IS NOT NULL AND inspection_asset_version >= 0
      AND inspection_warehouse_id IS NOT NULL AND inspection_status IS NOT NULL
      AND length(btrim(inspection_status)) BETWEEN 1 AND 48
      AND inspection_display_canonical_number IS NOT NULL
      AND length(btrim(inspection_display_canonical_number)) BETWEEN 1 AND 128
      AND inspection_passport_snapshot IS NOT NULL AND jsonb_typeof(inspection_passport_snapshot) = 'object'
      AND inspection_contents_snapshot IS NOT NULL AND jsonb_typeof(inspection_contents_snapshot) = 'array'
      AND inspection_repairs_snapshot IS NOT NULL AND jsonb_typeof(inspection_repairs_snapshot) = 'array'
      AND (inspection_tenant_snapshot IS NULL OR length(btrim(inspection_tenant_snapshot)) BETWEEN 1 AND 512)
    )
  ),
  ADD CONSTRAINT ck_finding_membership_event CHECK (
    (membership_event_asset_version IS NULL AND membership_event_warehouse_id IS NULL AND membership_event_status IS NULL)
    OR (membership_event_asset_version IS NOT NULL AND membership_event_asset_version >= 0
        AND membership_event_warehouse_id IS NOT NULL AND membership_event_status IS NOT NULL
        AND length(btrim(membership_event_status)) BETWEEN 1 AND 48)
  );

ALTER TABLE public.inventory_cabin_disposition_row
  DROP CONSTRAINT ck_inventory_cabin_disposition_row_candidate,
  DROP CONSTRAINT ck_inventory_cabin_disposition_row_decision,
  ADD CONSTRAINT ck_inventory_cabin_disposition_row_candidate CHECK (
    candidate_kind IN ('LOCAL', 'RETURN', 'MISSING', 'PRESERVE')
  ),
  ADD CONSTRAINT ck_inventory_cabin_disposition_row_decision CHECK (
    (disposition_kind IS NULL AND disposition_details IS NULL AND candidate_kind IN ('RETURN', 'MISSING'))
    OR (disposition_kind IS NOT NULL AND disposition_details IS NOT NULL
        AND jsonb_typeof(disposition_details) = 'object'
        AND ((candidate_kind IN ('LOCAL', 'RETURN') AND disposition_kind = 'LOCAL')
          OR (candidate_kind = 'MISSING' AND disposition_kind IN ('SHIPMENT', 'WRITE_OFF'))
          OR (candidate_kind = 'PRESERVE' AND disposition_kind = 'PRESERVE')))
  );

ALTER TABLE public.inventory_final_plan_entry
  DROP CONSTRAINT ck_inventory_final_plan_disposition,
  ADD CONSTRAINT ck_inventory_final_plan_disposition CHECK (
    disposition_kind IN ('LOCAL', 'SHIPMENT', 'WRITE_OFF', 'PRESERVE')
    AND jsonb_typeof(disposition_details) = 'object'
    AND (disposition_kind = 'LOCAL' OR has_work = false)
  );

-- NULL desired status explicitly means passport/photo-only, not FREE or a missing outcome.
ALTER TABLE public.inventory_publication_intent
  ADD COLUMN expected_asset_version bigint CHECK (expected_asset_version IS NULL OR expected_asset_version >= 0),
  ALTER COLUMN desired_asset_status DROP NOT NULL,
  DROP CONSTRAINT ck_inventory_publication_asset_outcome,
  DROP CONSTRAINT ck_inventory_publication_success,
  ADD CONSTRAINT ck_inventory_publication_asset_outcome CHECK (
    (desired_asset_status IS NULL OR desired_asset_status IN ('FREE', 'REPAIR', 'CAPITAL_REPAIR', 'RENTED'))
    AND (desired_asset_status IS NOT NULL OR (target_kind IS NULL AND expected_asset_version IS NULL))
    AND ((effective_asset_version IS NULL AND asset_outcome_result IS NULL)
      OR (effective_asset_version IS NOT NULL AND effective_asset_version >= 0
        AND asset_outcome_result IS NOT NULL AND jsonb_typeof(asset_outcome_result) = 'object'))
    AND (((desired_asset_status IS NULL OR desired_asset_status IN ('FREE', 'RENTED')) AND target_kind IS NULL)
      OR (desired_asset_status IN ('REPAIR', 'CAPITAL_REPAIR') AND target_kind = 'REPAIR'))
  ),
  ADD CONSTRAINT ck_inventory_publication_success CHECK (
    (state = 'SUCCEEDED' AND (
      ((desired_asset_status IS NULL OR desired_asset_status IN ('FREE', 'RENTED'))
        AND effective_asset_version IS NOT NULL AND asset_outcome_result IS NOT NULL
        AND target_kind IS NULL AND target_id IS NULL
        AND maintenance_estimate_id IS NULL AND maintenance_repair_id IS NULL
        AND maintenance_outcome IS NULL AND maintenance_result IS NULL)
      OR (desired_asset_status IN ('REPAIR', 'CAPITAL_REPAIR') AND (
        (maintenance_outcome = 'MATCHED' AND target_kind IS NULL AND target_id IS NULL
          AND maintenance_estimate_id IS NULL AND maintenance_repair_id IS NULL)
        OR (maintenance_outcome IS DISTINCT FROM 'MATCHED' AND target_kind IS NOT NULL
          AND target_id IS NOT NULL AND (maintenance_outcome IS DISTINCT FROM 'SUCCESSOR' OR target_kind = 'REPAIR'))))))
    OR (state <> 'SUCCEEDED' AND target_id IS NULL AND maintenance_repair_id IS NULL
      AND maintenance_estimate_id IS NULL AND maintenance_outcome IS NULL AND maintenance_result IS NULL)
  );
