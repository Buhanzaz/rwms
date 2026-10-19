-- Furniture selected for an estimate or direct repair lives in asset-owned pending-return custody.
-- Maintenance stores one approval decision per immutable custody claim/root equipment; it never
-- treats the whole repair chain as the write-off row for that furniture.

ALTER TABLE public.property_disposition_decision
  ADD COLUMN maintenance_custody_claim_id uuid,
  ADD COLUMN maintenance_custody_version bigint;

ALTER TABLE public.property_disposition_decision
  ALTER COLUMN expected_asset_version DROP NOT NULL,
  DROP CONSTRAINT ck_property_disposition_decision_versions,
  DROP CONSTRAINT ck_property_disposition_decision_asset_kind,
  ADD CONSTRAINT ck_property_disposition_decision_versions CHECK (
    version >= 0
    AND recovery_version >= 0
    AND (expected_asset_version IS NULL OR expected_asset_version >= 0)
    AND (expected_source_balance_version IS NULL OR expected_source_balance_version >= 0)
    AND (maintenance_custody_version IS NULL OR maintenance_custody_version >= 0)),
  ADD CONSTRAINT ck_property_disposition_decision_asset_kind CHECK (
    (asset_kind = 'CABIN'
      AND expected_asset_version IS NOT NULL
      AND quantity IS NULL
      AND expected_source_balance_version IS NULL
      AND maintenance_custody_claim_id IS NULL
      AND maintenance_custody_version IS NULL)
    OR
    (asset_kind = 'EQUIPMENT'
      AND quantity IS NOT NULL
      AND quantity > 0
      AND contents_mode IS NULL
      AND ((maintenance_custody_claim_id IS NULL
          AND maintenance_custody_version IS NULL
          AND expected_asset_version IS NOT NULL
          AND expected_source_balance_version IS NOT NULL)
        OR (maintenance_custody_claim_id IS NOT NULL
          AND maintenance_custody_version IS NOT NULL
          AND expected_asset_version IS NULL
          AND expected_source_balance_version IS NULL))));

DROP INDEX public.uq_property_disposition_decision_repair_root;
CREATE UNIQUE INDEX uq_property_disposition_decision_repair_root
  ON public.property_disposition_decision(root_repair_id)
  WHERE source IN ('REPAIR', 'ESTIMATE')
    AND asset_kind = 'CABIN'
    AND root_repair_id IS NOT NULL;

CREATE UNIQUE INDEX uq_property_disposition_decision_maintenance_custody_claim
  ON public.property_disposition_decision(maintenance_custody_claim_id)
  WHERE maintenance_custody_claim_id IS NOT NULL;

-- Bounded retries count consecutive dependency failures, not successful saga phases or worker
-- movement polling passes.
ALTER TABLE public.property_disposition_processing_claim
  ADD COLUMN failure_count integer NOT NULL DEFAULT 0,
  ADD CONSTRAINT ck_property_disposition_processing_claim_failure_count
    CHECK (failure_count >= 0);
