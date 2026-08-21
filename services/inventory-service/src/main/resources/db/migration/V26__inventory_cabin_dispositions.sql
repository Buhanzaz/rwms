-- Inventory owns the review that turns found rentals into historical returns and every missing
-- cabin into either an explicit shipment or an automatic maintenance-owned write-off.
CREATE TABLE public.inventory_cabin_disposition_review (
  inventory_id uuid PRIMARY KEY REFERENCES public.inventory_session(id),
  review_revision bigint NOT NULL DEFAULT 0,
  phase varchar(16) NOT NULL,
  source_sha256 varchar(64) NOT NULL,
  returns_sha256 varchar(64),
  shipments_sha256 varchar(64),
  created_at timestamptz NOT NULL,
  updated_at timestamptz NOT NULL,
  CONSTRAINT ck_inventory_cabin_disposition_phase CHECK (
    phase IN ('RETURNS', 'SHIPMENTS', 'COMPLETED')
  ),
  CONSTRAINT ck_inventory_cabin_disposition_hashes CHECK (
    source_sha256 ~ '^[0-9a-f]{64}$'
    AND (returns_sha256 IS NULL OR returns_sha256 ~ '^[0-9a-f]{64}$')
    AND (shipments_sha256 IS NULL OR shipments_sha256 ~ '^[0-9a-f]{64}$')
    AND (
      (phase = 'RETURNS' AND returns_sha256 IS NULL AND shipments_sha256 IS NULL)
      OR (phase = 'SHIPMENTS' AND returns_sha256 IS NOT NULL AND shipments_sha256 IS NULL)
      OR (phase = 'COMPLETED' AND returns_sha256 IS NOT NULL AND shipments_sha256 IS NOT NULL)
    )
  ),
  CONSTRAINT ck_inventory_cabin_disposition_times CHECK (updated_at >= created_at)
);

CREATE TABLE public.inventory_cabin_disposition_row (
  inventory_id uuid NOT NULL,
  finding_id uuid NOT NULL,
  finding_revision bigint NOT NULL,
  asset_id uuid NOT NULL,
  asset_version bigint NOT NULL,
  display_canonical_number varchar(128) NOT NULL,
  candidate_kind varchar(16) NOT NULL,
  disposition_kind varchar(16),
  disposition_details jsonb,
  PRIMARY KEY (inventory_id, finding_id),
  CONSTRAINT fk_inventory_cabin_disposition_review FOREIGN KEY (inventory_id)
    REFERENCES public.inventory_cabin_disposition_review(inventory_id) ON DELETE CASCADE,
  CONSTRAINT fk_inventory_cabin_disposition_finding FOREIGN KEY (inventory_id, finding_id)
    REFERENCES public.inventory_finding(inventory_id, id),
  CONSTRAINT ck_inventory_cabin_disposition_row_revision CHECK (
    finding_revision >= 0 AND asset_version >= 0
  ),
  CONSTRAINT ck_inventory_cabin_disposition_row_number CHECK (
    length(btrim(display_canonical_number)) BETWEEN 1 AND 128
  ),
  CONSTRAINT ck_inventory_cabin_disposition_row_candidate CHECK (
    candidate_kind IN ('LOCAL', 'RETURN', 'MISSING')
  ),
  CONSTRAINT ck_inventory_cabin_disposition_row_decision CHECK (
    (disposition_kind IS NULL AND disposition_details IS NULL AND candidate_kind IN ('RETURN', 'MISSING'))
    OR (
      disposition_kind IN ('LOCAL', 'SHIPMENT', 'WRITE_OFF')
      AND disposition_details IS NOT NULL
      AND jsonb_typeof(disposition_details) = 'object'
      AND (
        (candidate_kind IN ('LOCAL', 'RETURN') AND disposition_kind = 'LOCAL')
        OR (candidate_kind = 'MISSING' AND disposition_kind IN ('SHIPMENT', 'WRITE_OFF'))
      )
    )
  )
);

CREATE INDEX ix_inventory_cabin_disposition_row_kind
  ON public.inventory_cabin_disposition_row (inventory_id, candidate_kind, finding_id);
CREATE INDEX ix_inventory_cabin_disposition_row_result
  ON public.inventory_cabin_disposition_row (inventory_id, disposition_kind, finding_id);

-- Every new final-plan generation freezes its reviewed disposition. Historical plans did not
-- contain shipment/write-off decisions, so they retain their prior LOCAL semantics.
ALTER TABLE public.inventory_final_plan_entry
  ADD COLUMN disposition_kind varchar(16),
  ADD COLUMN disposition_details jsonb;

UPDATE public.inventory_final_plan_entry
SET disposition_kind = 'LOCAL',
    disposition_details = '{"formerRental":null}'::jsonb
WHERE disposition_kind IS NULL;

ALTER TABLE public.inventory_final_plan_entry
  ALTER COLUMN disposition_kind SET NOT NULL,
  ALTER COLUMN disposition_details SET NOT NULL,
  ADD CONSTRAINT ck_inventory_final_plan_disposition CHECK (
    disposition_kind IN ('LOCAL', 'SHIPMENT', 'WRITE_OFF')
    AND jsonb_typeof(disposition_details) = 'object'
    AND (disposition_kind = 'LOCAL' OR has_work = false)
  );

-- A write-off is a durable proposal to maintenance. Inventory never fabricates an asset status
-- for it and retries the same immutable request with the same idempotency key.
CREATE TABLE public.inventory_cabin_write_off_intent (
  finding_id uuid PRIMARY KEY,
  intent_revision bigint NOT NULL DEFAULT 0,
  inventory_id uuid NOT NULL REFERENCES public.inventory_session(id),
  warehouse_id uuid NOT NULL,
  cabin_id uuid NOT NULL,
  final_plan_version bigint NOT NULL,
  outcome_reapplication_no bigint NOT NULL,
  state varchar(32) NOT NULL,
  idempotency_key uuid NOT NULL UNIQUE,
  request_sha256 varchar(64) NOT NULL,
  request_body jsonb NOT NULL,
  attempt_count integer NOT NULL DEFAULT 0,
  next_attempt_at timestamptz NOT NULL,
  failure_code varchar(64),
  decision_id uuid,
  created_at timestamptz NOT NULL,
  updated_at timestamptz NOT NULL,
  completed_at timestamptz,
  CONSTRAINT uq_inventory_cabin_write_off UNIQUE (inventory_id, cabin_id),
  CONSTRAINT fk_inventory_cabin_write_off_logistics_generation FOREIGN KEY (
    inventory_id, final_plan_version, outcome_reapplication_no
  ) REFERENCES public.inventory_plan_logistics_effect (
    inventory_id, final_plan_version, outcome_reapplication_no
  ),
  CONSTRAINT ck_inventory_cabin_write_off_state CHECK (
    state IN ('PENDING', 'SUCCEEDED', 'TRANSIENT_FAILED', 'BLOCKED')
  ),
  CONSTRAINT ck_inventory_cabin_write_off_request CHECK (
    request_sha256 ~ '^[0-9a-f]{64}$' AND jsonb_typeof(request_body) = 'object'
  ),
  CONSTRAINT ck_inventory_cabin_write_off_attempt CHECK (attempt_count >= 0),
  CONSTRAINT ck_inventory_cabin_write_off_generation CHECK (
    final_plan_version >= 1 AND outcome_reapplication_no >= 0
  ),
  CONSTRAINT ck_inventory_cabin_write_off_result CHECK (
    (state = 'SUCCEEDED' AND decision_id IS NOT NULL AND failure_code IS NULL AND completed_at IS NOT NULL)
    OR (
      state IN ('PENDING', 'TRANSIENT_FAILED')
      AND decision_id IS NULL
      AND completed_at IS NULL
      AND (
        (state = 'PENDING' AND failure_code IS NULL)
        OR (state = 'TRANSIENT_FAILED' AND length(btrim(failure_code)) BETWEEN 1 AND 64)
      )
    )
    OR (
      state = 'BLOCKED'
      AND decision_id IS NULL
      AND length(btrim(failure_code)) BETWEEN 1 AND 64
      AND completed_at IS NOT NULL
    )
  )
);

CREATE INDEX ix_inventory_cabin_write_off_recovery
  ON public.inventory_cabin_write_off_intent (state, next_attempt_at, finding_id)
  WHERE state IN ('PENDING', 'TRANSIENT_FAILED');
CREATE INDEX ix_inventory_cabin_write_off_inventory
  ON public.inventory_cabin_write_off_intent (inventory_id, finding_id);

-- Shipment publication is asset-only like FREE, but establishes RENTED with frozen contents.
ALTER TABLE public.inventory_publication_intent
  DROP CONSTRAINT ck_inventory_publication_asset_outcome,
  ADD CONSTRAINT ck_inventory_publication_asset_outcome CHECK (
    desired_asset_status IN ('FREE', 'REPAIR', 'CAPITAL_REPAIR', 'RENTED')
    AND (
      (effective_asset_version IS NULL AND asset_outcome_result IS NULL)
      OR (
        effective_asset_version >= 0
        AND asset_outcome_result IS NOT NULL
        AND jsonb_typeof(asset_outcome_result) = 'object'
      )
    )
    AND (
      (desired_asset_status IN ('FREE', 'RENTED') AND target_kind IS NULL)
      OR (desired_asset_status IN ('REPAIR', 'CAPITAL_REPAIR') AND target_kind = 'REPAIR')
    )
  ),
  DROP CONSTRAINT ck_inventory_publication_success,
  ADD CONSTRAINT ck_inventory_publication_success CHECK (
    (
      state = 'SUCCEEDED'
      AND (
        (
          desired_asset_status IN ('FREE', 'RENTED')
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
              AND (maintenance_outcome IS DISTINCT FROM 'SUCCESSOR' OR target_kind = 'REPAIR')
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
