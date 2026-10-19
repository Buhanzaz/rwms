-- Retain the manager capital-repair choice in both immutable frozen-plan and final-plan evidence.
ALTER TABLE public.finding_plan_snapshot
  ADD COLUMN force_capital_repair boolean NOT NULL DEFAULT false;

ALTER TABLE public.inventory_final_plan_entry
  ADD COLUMN force_capital_repair boolean NOT NULL DEFAULT false;

ALTER TABLE public.inventory_final_plan_entry
  DROP CONSTRAINT ck_inventory_final_plan_entry_no_work,
  ADD CONSTRAINT ck_inventory_final_plan_entry_no_work CHECK (
    (NOT has_work
      AND plan_fingerprint_sha256 IS NULL
      AND target_kind IS NULL
      AND priority IS NULL
      AND NOT movement_to_repair
      AND NOT force_capital_repair
      AND movement_scheduled_date IS NULL
      AND repair_scheduled_date IS NULL
      AND collision_candidates = '[]'::jsonb
      AND reconciliation_decision IS NULL)
    OR
    (has_work
      AND asset_id IS NOT NULL
      AND asset_version IS NOT NULL
      AND plan_fingerprint_sha256 ~ '^[0-9a-f]{64}$'
      AND target_kind IN ('ESTIMATE', 'REPAIR')
      AND priority BETWEEN 1 AND 5
      AND repair_scheduled_date IS NOT NULL
      AND (movement_to_repair = (movement_scheduled_date IS NOT NULL))));
