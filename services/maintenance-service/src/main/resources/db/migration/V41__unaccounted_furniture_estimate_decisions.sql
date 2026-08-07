ALTER TABLE public.maintenance_repair
  ADD COLUMN furniture_accounting_mode varchar(32) NOT NULL
    DEFAULT 'TRACKED_CABIN_CONTENTS';

ALTER TABLE public.maintenance_repair
  ADD CONSTRAINT ck_maintenance_repair_furniture_accounting_mode CHECK (
    furniture_accounting_mode IN ('TRACKED_CABIN_CONTENTS', 'UNACCOUNTED_CABIN_CONTENTS')
  );

ALTER TABLE public.property_disposition_decision
  DROP CONSTRAINT ck_property_disposition_decision_source,
  DROP CONSTRAINT ck_property_disposition_decision_effect_state,
  DROP CONSTRAINT ck_property_disposition_decision_effect,
  DROP CONSTRAINT ck_property_disposition_decision_asset_kind,
  ADD CONSTRAINT ck_property_disposition_decision_source CHECK (
    source IN ('MANUAL', 'REPAIR', 'ESTIMATE', 'UNACCOUNTED', 'INVENTORY')
  ),
  ADD CONSTRAINT ck_property_disposition_decision_effect_state CHECK (
    (source = 'UNACCOUNTED'
      AND ((state IN ('PENDING_APPROVAL', 'APPROVED', 'EFFECTIVE', 'REJECTED')
            AND asset_effect_state = 'NOT_REQUIRED')
           OR (state = 'QUARANTINED' AND asset_effect_state = 'QUARANTINED')))
    OR (source <> 'UNACCOUNTED'
      AND ((state IN ('PENDING_APPROVAL', 'APPROVED', 'MOVEMENT_PENDING', 'REJECTED')
            AND asset_effect_state = 'NOT_STARTED')
           OR (state = 'EFFECT_PENDING' AND asset_effect_state = 'PENDING')
           OR (state = 'EFFECTIVE' AND asset_effect_state = 'APPLIED')
           OR (state = 'QUARANTINED' AND asset_effect_state = 'QUARANTINED')))
  ),
  ADD CONSTRAINT ck_property_disposition_decision_effect CHECK (
    (state <> 'EFFECTIVE' OR effect_id IS NOT NULL OR source = 'UNACCOUNTED')
    AND (effect_id IS NULL OR state = 'EFFECTIVE')
    AND (state <> 'MOVEMENT_PENDING' OR movement_task_id IS NOT NULL)
  ),
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
          AND expected_source_balance_version IS NULL)
        OR (source = 'UNACCOUNTED'
          AND maintenance_custody_claim_id IS NULL
          AND maintenance_custody_version IS NULL
          AND expected_asset_version IS NULL
          AND expected_source_balance_version IS NULL)))
  );

CREATE UNIQUE INDEX uk_property_disposition_unaccounted_repair_equipment
  ON public.property_disposition_decision (source_repair_id, asset_id)
  WHERE source = 'UNACCOUNTED';
