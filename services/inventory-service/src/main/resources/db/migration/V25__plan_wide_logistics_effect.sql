CREATE TABLE public.inventory_plan_logistics_effect (
  id uuid NOT NULL,
  effect_revision bigint NOT NULL DEFAULT 0,
  inventory_id uuid NOT NULL,
  final_plan_version bigint NOT NULL,
  final_plan_sha256 varchar(64) NOT NULL,
  outcome_reapplication_no bigint NOT NULL,
  state varchar(24) NOT NULL,
  idempotency_key uuid NOT NULL,
  request_sha256 varchar(64) NOT NULL,
  request_body jsonb NOT NULL,
  response_body jsonb,
  attempt_count integer NOT NULL DEFAULT 0,
  next_attempt_at timestamptz NOT NULL,
  failure_code varchar(64),
  created_at timestamptz NOT NULL,
  updated_at timestamptz NOT NULL,
  completed_at timestamptz,
  CONSTRAINT inventory_plan_logistics_effect_pkey PRIMARY KEY (id),
  CONSTRAINT fk_inventory_plan_logistics_effect_session FOREIGN KEY (inventory_id)
    REFERENCES public.inventory_session(id),
  CONSTRAINT uk_inventory_plan_logistics_generation UNIQUE (
    inventory_id, final_plan_version, outcome_reapplication_no),
  CONSTRAINT uk_inventory_plan_logistics_idempotency UNIQUE (idempotency_key),
  CONSTRAINT ck_inventory_plan_logistics_revision CHECK (effect_revision >= 0),
  CONSTRAINT ck_inventory_plan_logistics_plan CHECK (
    final_plan_version >= 1
    AND final_plan_sha256 ~ '^[0-9a-f]{64}$'
    AND outcome_reapplication_no >= 0),
  CONSTRAINT ck_inventory_plan_logistics_state CHECK (
    state IN ('READY','PENDING','SUCCEEDED','TRANSIENT_FAILED','BLOCKED')),
  CONSTRAINT ck_inventory_plan_logistics_request CHECK (
    request_sha256 ~ '^[0-9a-f]{64}$'
    AND jsonb_typeof(request_body) = 'object'),
  CONSTRAINT ck_inventory_plan_logistics_attempt CHECK (attempt_count >= 0),
  CONSTRAINT ck_inventory_plan_logistics_result CHECK (
    (state = 'SUCCEEDED'
      AND response_body IS NOT NULL
      AND jsonb_typeof(response_body) = 'object'
      AND failure_code IS NULL
      AND completed_at IS NOT NULL)
    OR (state = 'BLOCKED'
      AND response_body IS NULL
      AND length(btrim(failure_code)) BETWEEN 1 AND 64
      AND completed_at IS NOT NULL)
    OR (state = 'TRANSIENT_FAILED'
      AND response_body IS NULL
      AND length(btrim(failure_code)) BETWEEN 1 AND 64
      AND completed_at IS NULL)
    OR (state IN ('READY','PENDING')
      AND response_body IS NULL
      AND failure_code IS NULL
      AND completed_at IS NULL)),
  CONSTRAINT ck_inventory_plan_logistics_times CHECK (
    updated_at >= created_at)
);

CREATE INDEX idx_inventory_plan_logistics_recovery
  ON public.inventory_plan_logistics_effect(state, next_attempt_at, id)
  WHERE state IN ('READY','PENDING','TRANSIENT_FAILED');
