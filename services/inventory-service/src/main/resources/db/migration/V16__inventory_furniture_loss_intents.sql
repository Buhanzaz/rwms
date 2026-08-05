-- Inventory shortages are proposals owned by maintenance-service. They are retained here as
-- durable, immutable request evidence until the administrator-owned LOSS decision is recorded.
CREATE TABLE public.inventory_furniture_loss_intent (
  finding_id uuid PRIMARY KEY,
  intent_revision bigint NOT NULL DEFAULT 0,
  inventory_id uuid NOT NULL REFERENCES public.inventory_session(id),
  warehouse_id uuid NOT NULL,
  equipment_id uuid NOT NULL,
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
  CONSTRAINT uq_inventory_furniture_loss_equipment UNIQUE (inventory_id, equipment_id),
  CONSTRAINT ck_inventory_furniture_loss_state CHECK (
    state IN ('PENDING', 'SUCCEEDED', 'TRANSIENT_FAILED', 'BLOCKED')
  ),
  CONSTRAINT ck_inventory_furniture_loss_request_sha CHECK (
    request_sha256 ~ '^[0-9a-f]{64}$'
  ),
  CONSTRAINT ck_inventory_furniture_loss_request_body CHECK (
    jsonb_typeof(request_body) = 'object'
  ),
  CONSTRAINT ck_inventory_furniture_loss_attempt CHECK (attempt_count >= 0),
  CONSTRAINT ck_inventory_furniture_loss_result CHECK (
    (
      state = 'SUCCEEDED'
      AND decision_id IS NOT NULL
      AND failure_code IS NULL
      AND completed_at IS NOT NULL
    )
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

CREATE INDEX ix_inventory_furniture_loss_recovery
  ON public.inventory_furniture_loss_intent (state, next_attempt_at, finding_id)
  WHERE state IN ('PENDING', 'TRANSIENT_FAILED');

CREATE INDEX ix_inventory_furniture_loss_inventory
  ON public.inventory_furniture_loss_intent (inventory_id, finding_id);
