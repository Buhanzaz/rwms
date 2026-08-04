-- Final-plan reconciliation can prove that every frozen line is already represented by a
-- started repair. Preserve maintenance's immutable result while allowing that MATCHED success to
-- have no created target. Legacy repair-source publications intentionally retain a null outcome.
ALTER TABLE public.inventory_publication_intent
  ADD COLUMN maintenance_outcome varchar(16),
  ADD COLUMN maintenance_result jsonb;

-- Attempt results are append-only evidence as well. A final-plan success can produce an estimate
-- or a no-op MATCHED result, so repair_id alone cannot describe every successful outcome.
ALTER TABLE public.inventory_publication_attempt_result
  ADD COLUMN maintenance_outcome varchar(16),
  ADD COLUMN maintenance_result jsonb,
  DROP CONSTRAINT ck_publication_attempt_result_shape,
  ADD CONSTRAINT ck_publication_attempt_result_shape CHECK (
    (
      outcome = 'SUCCEEDED'
      AND failure_code IS NULL
      AND message_sha256 IS NULL
      AND (
        (maintenance_outcome IS NULL AND maintenance_result IS NULL AND repair_id IS NOT NULL)
        OR (
          maintenance_outcome IN ('CREATED', 'SUCCESSOR', 'MATCHED')
          AND maintenance_result IS NOT NULL
          AND jsonb_typeof(maintenance_result) = 'object'
          AND (
            maintenance_outcome <> 'MATCHED'
            OR repair_id IS NULL
          )
          AND (
            maintenance_outcome <> 'SUCCESSOR'
            OR repair_id IS NOT NULL
          )
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

ALTER TABLE public.inventory_publication_intent
  ADD CONSTRAINT ck_inventory_publication_maintenance_result CHECK (
    (maintenance_outcome IS NULL AND maintenance_result IS NULL)
    OR (
      maintenance_outcome IN ('CREATED', 'SUCCESSOR', 'MATCHED')
      AND maintenance_result IS NOT NULL
      AND jsonb_typeof(maintenance_result) = 'object'
    )
  ),
  DROP CONSTRAINT ck_inventory_publication_success,
  ADD CONSTRAINT ck_inventory_publication_success CHECK (
    (
      state = 'SUCCEEDED'
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
    OR (
      state <> 'SUCCEEDED'
      AND target_id IS NULL
      AND maintenance_repair_id IS NULL
      AND maintenance_estimate_id IS NULL
      AND maintenance_outcome IS NULL
      AND maintenance_result IS NULL
    )
  );
