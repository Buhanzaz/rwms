-- Completed inventory becomes the current asset truth before optional maintenance publication.
-- Existing publication attempts/results remain immutable audit history.
ALTER TABLE public.inventory_publication_intent
  ADD COLUMN desired_asset_status varchar(24),
  ADD COLUMN effective_asset_version bigint,
  ADD COLUMN asset_outcome_result jsonb;

UPDATE public.inventory_publication_intent intent
SET desired_asset_status = CASE
  WHEN entry.force_capital_repair THEN 'CAPITAL_REPAIR'
  ELSE 'REPAIR'
END
FROM public.inventory_final_plan_entry entry
WHERE entry.inventory_id = intent.inventory_id
  AND entry.final_plan_version = intent.final_plan_version
  AND entry.finding_id = intent.finding_id;

UPDATE public.inventory_publication_intent
SET desired_asset_status = 'REPAIR'
WHERE desired_asset_status IS NULL;

ALTER TABLE public.inventory_publication_intent
  ALTER COLUMN desired_asset_status SET NOT NULL,
  ADD CONSTRAINT ck_inventory_publication_asset_outcome CHECK (
    desired_asset_status IN ('FREE', 'REPAIR', 'CAPITAL_REPAIR')
    AND (
      (effective_asset_version IS NULL AND asset_outcome_result IS NULL)
      OR (
        effective_asset_version >= 0
        AND asset_outcome_result IS NOT NULL
        AND jsonb_typeof(asset_outcome_result) = 'object'
      )
    )
    AND (
      (desired_asset_status = 'FREE' AND target_kind IS NULL)
      OR desired_asset_status IN ('REPAIR', 'CAPITAL_REPAIR')
    )
  ),
  DROP CONSTRAINT ck_inventory_publication_success,
  ADD CONSTRAINT ck_inventory_publication_success CHECK (
    (
      state = 'SUCCEEDED'
      AND (
        (
          desired_asset_status = 'FREE'
          AND effective_asset_version IS NOT NULL
          AND asset_outcome_result IS NOT NULL
          AND target_kind IS NULL
          AND target_id IS NULL
          AND maintenance_estimate_id IS NULL
          AND maintenance_repair_id IS NULL
          AND maintenance_outcome IS NULL
          AND maintenance_result IS NULL
        )
        OR (
          desired_asset_status IN ('REPAIR', 'CAPITAL_REPAIR')
          AND (
            (
              maintenance_outcome = 'MATCHED'
              AND target_kind IS NULL
              AND target_id IS NULL
              AND maintenance_estimate_id IS NULL
              AND maintenance_repair_id IS NULL
            )
            OR (
              maintenance_outcome IS DISTINCT FROM 'MATCHED'
              AND target_kind IS NOT NULL
              AND target_id IS NOT NULL
              AND (
                maintenance_outcome IS DISTINCT FROM 'SUCCESSOR'
                OR target_kind = 'REPAIR'
              )
            )
          )
        )
      )
    )
    OR (
      state <> 'SUCCEEDED'
      AND target_id IS NULL
      AND maintenance_repair_id IS NULL
      AND maintenance_estimate_id IS NULL
      AND maintenance_outcome IS NULL
      AND maintenance_result IS NULL
    )
  );

ALTER TABLE public.inventory_publication_attempt_result
  ADD COLUMN asset_outcome_result jsonb,
  DROP CONSTRAINT ck_publication_attempt_result_shape,
  ADD CONSTRAINT ck_publication_attempt_result_asset_outcome CHECK (
    asset_outcome_result IS NULL OR jsonb_typeof(asset_outcome_result) = 'object'
  ),
  ADD CONSTRAINT ck_publication_attempt_result_shape CHECK (
    (
      outcome = 'SUCCEEDED'
      AND failure_code IS NULL
      AND message_sha256 IS NULL
      AND (
        (maintenance_outcome IS NULL AND maintenance_result IS NULL
          AND (repair_id IS NOT NULL OR asset_outcome_result IS NOT NULL))
        OR (
          maintenance_outcome IN ('CREATED', 'SUCCESSOR', 'MATCHED')
          AND maintenance_result IS NOT NULL
          AND jsonb_typeof(maintenance_result) = 'object'
          AND (maintenance_outcome <> 'MATCHED' OR repair_id IS NULL)
          AND (maintenance_outcome <> 'SUCCESSOR' OR repair_id IS NOT NULL)
        )
      )
    )
    OR (
      outcome IN ('TRANSIENT_FAILED', 'BLOCKED')
      AND length(btrim(failure_code)) BETWEEN 1 AND 64
      AND message_sha256 ~ '^[0-9a-f]{64}$'
      AND repair_id IS NULL
      AND maintenance_outcome IS NULL
      AND maintenance_result IS NULL
    )
  );
