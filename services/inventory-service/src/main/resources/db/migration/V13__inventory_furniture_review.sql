-- Inventory completion has an explicit furniture review stage. A later
-- registry-conflict resolution may deliberately invalidate the frozen review
-- and return an ACTIVE session to CABINS; historical sessions are not
-- fabricated into furniture reviews.
ALTER TABLE public.inventory_session
  ADD COLUMN review_stage varchar(16),
  ADD COLUMN furniture_asset_snapshot_sha256 varchar(64),
  ADD COLUMN furniture_asset_snapshot jsonb,
  ADD COLUMN furniture_review_sha256 varchar(64),
  ADD COLUMN furniture_stock_observation jsonb,
  ADD COLUMN furniture_reviewed_by_actor_ref jsonb,
  ADD COLUMN furniture_reviewed_at timestamptz;

UPDATE public.inventory_session
SET review_stage = 'CABINS'
WHERE review_stage IS NULL;

ALTER TABLE public.inventory_session
  ALTER COLUMN review_stage SET NOT NULL,
  ALTER COLUMN review_stage SET DEFAULT 'CABINS',
  ADD CONSTRAINT ck_inventory_session_review_stage
    CHECK (review_stage IN ('CABINS', 'FURNITURE')),
  ADD CONSTRAINT ck_inventory_session_furniture_review
    CHECK (
      (
        review_stage = 'CABINS'
        AND furniture_asset_snapshot_sha256 IS NULL
        AND furniture_asset_snapshot IS NULL
        AND furniture_review_sha256 IS NULL
        AND furniture_stock_observation IS NULL
        AND furniture_reviewed_by_actor_ref IS NULL
        AND furniture_reviewed_at IS NULL
      )
      OR
      (
        review_stage = 'FURNITURE'
        AND furniture_asset_snapshot_sha256 ~ '^[0-9a-f]{64}$'
        AND jsonb_typeof(furniture_asset_snapshot) = 'object'
        AND (
          (
            furniture_review_sha256 IS NULL
            AND furniture_stock_observation IS NULL
            AND furniture_reviewed_by_actor_ref IS NULL
            AND furniture_reviewed_at IS NULL
          )
          OR
          (
            furniture_review_sha256 ~ '^[0-9a-f]{64}$'
            AND jsonb_typeof(furniture_stock_observation) = 'object'
            AND jsonb_typeof(furniture_reviewed_by_actor_ref) = 'object'
            AND jsonb_exists_all(
              furniture_reviewed_by_actor_ref,
              array['subjectId','principalType','profileRevision'])
            AND furniture_reviewed_by_actor_ref
              - array['subjectId','principalType','profileRevision'] = '{}'::jsonb
            AND furniture_reviewed_at IS NOT NULL
          )
        )
      )
    );

CREATE TABLE public.inventory_furniture_reconciliation_intent (
  inventory_id uuid NOT NULL,
  intent_revision bigint NOT NULL DEFAULT 0,
  state varchar(32) NOT NULL,
  idempotency_key uuid NOT NULL,
  asset_snapshot_sha256 varchar(64) NOT NULL,
  review_sha256 varchar(64) NOT NULL,
  request_sha256 varchar(64) NOT NULL,
  request_body jsonb NOT NULL,
  attempt_count integer NOT NULL DEFAULT 0,
  next_attempt_at timestamptz NOT NULL,
  failure_code varchar(64),
  created_at timestamptz NOT NULL,
  updated_at timestamptz NOT NULL,
  completed_at timestamptz,
  CONSTRAINT inventory_furniture_reconciliation_intent_pkey PRIMARY KEY (inventory_id),
  CONSTRAINT fk_inventory_furniture_reconciliation_session
    FOREIGN KEY (inventory_id) REFERENCES public.inventory_session(id),
  CONSTRAINT ck_inventory_furniture_reconciliation_revision CHECK (intent_revision >= 0),
  CONSTRAINT ck_inventory_furniture_reconciliation_state CHECK (
    state IN ('PENDING', 'SUCCEEDED', 'TRANSIENT_FAILED', 'BLOCKED')),
  CONSTRAINT ck_inventory_furniture_reconciliation_hashes CHECK (
    asset_snapshot_sha256 ~ '^[0-9a-f]{64}$'
    AND review_sha256 ~ '^[0-9a-f]{64}$'
    AND request_sha256 ~ '^[0-9a-f]{64}$'),
  CONSTRAINT ck_inventory_furniture_reconciliation_request
    CHECK (jsonb_typeof(request_body) = 'object'),
  CONSTRAINT ck_inventory_furniture_reconciliation_attempts CHECK (attempt_count >= 0),
  CONSTRAINT ck_inventory_furniture_reconciliation_failure CHECK (
    (state IN ('PENDING', 'SUCCEEDED') AND failure_code IS NULL)
    OR
    (state IN ('TRANSIENT_FAILED', 'BLOCKED')
      AND length(btrim(failure_code)) BETWEEN 1 AND 64)
  ),
  CONSTRAINT ck_inventory_furniture_reconciliation_completion CHECK (
    (state IN ('SUCCEEDED', 'BLOCKED') AND completed_at IS NOT NULL)
    OR (state IN ('PENDING', 'TRANSIENT_FAILED') AND completed_at IS NULL)
  ),
  CONSTRAINT ck_inventory_furniture_reconciliation_times CHECK (updated_at >= created_at)
);

CREATE INDEX idx_inventory_furniture_reconciliation_retry
  ON public.inventory_furniture_reconciliation_intent(next_attempt_at, inventory_id)
  WHERE state IN ('PENDING', 'TRANSIENT_FAILED');
