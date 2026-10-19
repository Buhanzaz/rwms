-- The newest completed inventory is authoritative for maintenance-owned active work.  Durable
-- source, idempotency, watermark and per-target rows keep every remote cancellation replay-safe
-- while preserving estimates, repairs, stages, evidence and event history in place.

CREATE TABLE public.inventory_authoritative_outcome (
  inventory_id uuid NOT NULL,
  final_plan_version bigint NOT NULL,
  finding_id uuid NOT NULL,
  version bigint NOT NULL DEFAULT 0,
  request_sha256 varchar(64) NOT NULL,
  request_snapshot jsonb NOT NULL,
  warehouse_id uuid NOT NULL,
  asset_id uuid NOT NULL,
  inventory_completed_at timestamptz NOT NULL,
  final_plan_sha256 varchar(64) NOT NULL,
  finding_revision bigint NOT NULL,
  authoritative_asset_version bigint NOT NULL,
  desired_status varchar(32) NOT NULL,
  outcome_kind varchar(16) NOT NULL,
  phase varchar(32) NOT NULL,
  target_repair_id uuid,
  response_snapshot jsonb,
  created_at timestamptz NOT NULL,
  updated_at timestamptz NOT NULL,
  applied_at timestamptz,
  CONSTRAINT inventory_authoritative_outcome_pkey
    PRIMARY KEY (inventory_id, final_plan_version, finding_id),
  CONSTRAINT fk_inventory_authoritative_outcome_target_repair
    FOREIGN KEY (target_repair_id) REFERENCES public.maintenance_repair(id),
  CONSTRAINT ck_inventory_authoritative_outcome_version CHECK (version >= 0),
  CONSTRAINT ck_inventory_authoritative_outcome_plan_version CHECK (final_plan_version >= 1),
  CONSTRAINT ck_inventory_authoritative_outcome_finding_revision CHECK (finding_revision >= 1),
  CONSTRAINT ck_inventory_authoritative_outcome_asset_version CHECK (
    authoritative_asset_version >= 0
  ),
  CONSTRAINT ck_inventory_authoritative_outcome_hashes CHECK (
    request_sha256 ~ '^[0-9a-f]{64}$'
    AND final_plan_sha256 ~ '^[0-9a-f]{64}$'
  ),
  CONSTRAINT ck_inventory_authoritative_outcome_request CHECK (
    jsonb_typeof(request_snapshot) = 'object'
  ),
  CONSTRAINT ck_inventory_authoritative_outcome_kind CHECK (
    (outcome_kind = 'NO_WORK' AND desired_status = 'FREE')
    OR (outcome_kind = 'WORK' AND desired_status IN ('REPAIR', 'CAPITAL_REPAIR'))
  ),
  CONSTRAINT ck_inventory_authoritative_outcome_phase CHECK (
    (phase = 'PREPARED'
      AND target_repair_id IS NULL AND response_snapshot IS NULL AND applied_at IS NULL)
    OR (phase = 'EFFECTS_SETTLED'
      AND target_repair_id IS NULL AND response_snapshot IS NULL AND applied_at IS NULL)
    OR (phase = 'TARGET_CREATED'
      AND outcome_kind = 'WORK' AND target_repair_id IS NOT NULL
      AND response_snapshot IS NULL AND applied_at IS NULL)
    OR (phase = 'APPLIED'
      AND response_snapshot IS NOT NULL AND applied_at IS NOT NULL
      AND ((outcome_kind = 'NO_WORK' AND target_repair_id IS NULL)
        OR (outcome_kind = 'WORK' AND target_repair_id IS NOT NULL)))
  )
);

CREATE INDEX idx_inventory_authoritative_outcome_asset
  ON public.inventory_authoritative_outcome(asset_id, inventory_completed_at DESC);
CREATE INDEX idx_inventory_authoritative_outcome_pending
  ON public.inventory_authoritative_outcome(phase, updated_at)
  WHERE phase <> 'APPLIED';

CREATE TABLE public.inventory_authoritative_outcome_receipt (
  idempotency_key uuid NOT NULL,
  inventory_id uuid NOT NULL,
  final_plan_version bigint NOT NULL,
  finding_id uuid NOT NULL,
  request_sha256 varchar(64) NOT NULL,
  response_snapshot jsonb,
  created_at timestamptz NOT NULL,
  completed_at timestamptz,
  CONSTRAINT inventory_authoritative_outcome_receipt_pkey PRIMARY KEY (idempotency_key),
  CONSTRAINT fk_inventory_authoritative_outcome_receipt_source
    FOREIGN KEY (inventory_id, final_plan_version, finding_id)
    REFERENCES public.inventory_authoritative_outcome(inventory_id, final_plan_version, finding_id),
  CONSTRAINT ck_inventory_authoritative_outcome_receipt_hash CHECK (
    request_sha256 ~ '^[0-9a-f]{64}$'
  ),
  CONSTRAINT ck_inventory_authoritative_outcome_receipt_response CHECK (
    (response_snapshot IS NULL AND completed_at IS NULL)
    OR (response_snapshot IS NOT NULL AND completed_at IS NOT NULL)
  )
);

CREATE INDEX idx_inventory_authoritative_outcome_receipt_source
  ON public.inventory_authoritative_outcome_receipt(
    inventory_id, final_plan_version, finding_id, created_at
  );

CREATE TABLE public.inventory_authoritative_outcome_watermark (
  asset_id uuid NOT NULL,
  version bigint NOT NULL DEFAULT 0,
  warehouse_id uuid NOT NULL,
  inventory_completed_at timestamptz NOT NULL,
  inventory_id uuid NOT NULL,
  final_plan_version bigint NOT NULL,
  finding_id uuid NOT NULL,
  final_plan_sha256 varchar(64) NOT NULL,
  finding_revision bigint NOT NULL,
  desired_status varchar(32) NOT NULL,
  request_sha256 varchar(64) NOT NULL,
  updated_at timestamptz NOT NULL,
  CONSTRAINT inventory_authoritative_outcome_watermark_pkey PRIMARY KEY (asset_id),
  CONSTRAINT fk_inventory_authoritative_outcome_watermark_source
    FOREIGN KEY (inventory_id, final_plan_version, finding_id)
    REFERENCES public.inventory_authoritative_outcome(inventory_id, final_plan_version, finding_id),
  CONSTRAINT ck_inventory_authoritative_outcome_watermark_version CHECK (version >= 0),
  CONSTRAINT ck_inventory_authoritative_outcome_watermark_plan_version CHECK (
    final_plan_version >= 1
  ),
  CONSTRAINT ck_inventory_authoritative_outcome_watermark_finding_revision CHECK (
    finding_revision >= 1
  ),
  CONSTRAINT ck_inventory_authoritative_outcome_watermark_status CHECK (
    desired_status IN ('FREE', 'REPAIR', 'CAPITAL_REPAIR')
  ),
  CONSTRAINT ck_inventory_authoritative_outcome_watermark_hashes CHECK (
    final_plan_sha256 ~ '^[0-9a-f]{64}$'
    AND request_sha256 ~ '^[0-9a-f]{64}$'
  )
);

CREATE TABLE public.inventory_authoritative_outcome_target (
  id uuid NOT NULL,
  version bigint NOT NULL DEFAULT 0,
  inventory_id uuid NOT NULL,
  final_plan_version bigint NOT NULL,
  finding_id uuid NOT NULL,
  target_kind varchar(16) NOT NULL,
  target_id uuid NOT NULL,
  task_external_id uuid,
  task_expected_version bigint,
  task_attempt_count bigint NOT NULL DEFAULT 0,
  task_outcome varchar(32),
  driver_kind varchar(32),
  driver_attempt_count bigint NOT NULL DEFAULT 0,
  driver_outcome varchar(32),
  driver_task_id uuid,
  repair_place_allocation_id uuid,
  repair_place_allocation_version bigint,
  lease_id uuid,
  lease_version bigint,
  fencing_token bigint,
  lease_owner_type varchar(64),
  lease_owner_id uuid,
  lease_attempt_count bigint NOT NULL DEFAULT 0,
  lease_released boolean NOT NULL DEFAULT false,
  local_superseded boolean NOT NULL DEFAULT false,
  created_at timestamptz NOT NULL,
  updated_at timestamptz NOT NULL,
  CONSTRAINT inventory_authoritative_outcome_target_pkey PRIMARY KEY (id),
  CONSTRAINT fk_inventory_authoritative_outcome_target_source
    FOREIGN KEY (inventory_id, final_plan_version, finding_id)
    REFERENCES public.inventory_authoritative_outcome(inventory_id, final_plan_version, finding_id),
  CONSTRAINT uk_inventory_authoritative_outcome_target
    UNIQUE (inventory_id, final_plan_version, finding_id, target_kind, target_id),
  CONSTRAINT ck_inventory_authoritative_outcome_target_version CHECK (version >= 0),
  CONSTRAINT ck_inventory_authoritative_outcome_target_kind CHECK (
    target_kind IN ('ESTIMATE', 'REPAIR')
  ),
  CONSTRAINT ck_inventory_authoritative_outcome_target_task CHECK (
    task_attempt_count >= 0
    AND ((task_external_id IS NULL AND task_expected_version IS NULL AND task_outcome IS NULL)
      OR (task_external_id IS NOT NULL AND task_expected_version IS NOT NULL
        AND task_expected_version >= 0))
  ),
  CONSTRAINT ck_inventory_authoritative_outcome_target_driver CHECK (
    driver_attempt_count >= 0
    AND (driver_kind IS NULL OR driver_kind IN ('DELIVER_TO_REPAIR', 'CAPITAL_TO_PRODUCTION'))
    AND ((repair_place_allocation_id IS NULL AND repair_place_allocation_version IS NULL)
      OR (repair_place_allocation_id IS NOT NULL
        AND repair_place_allocation_version IS NOT NULL
        AND repair_place_allocation_version >= 0))
  ),
  CONSTRAINT ck_inventory_authoritative_outcome_target_lease CHECK (
    lease_attempt_count >= 0
    AND ((lease_id IS NULL AND lease_version IS NULL AND fencing_token IS NULL
      AND lease_owner_type IS NULL AND lease_owner_id IS NULL AND NOT lease_released)
      OR (lease_id IS NOT NULL AND lease_version IS NOT NULL AND lease_version >= 0
        AND fencing_token IS NOT NULL AND fencing_token >= 1
        AND lease_owner_type IS NOT NULL AND length(lease_owner_type) BETWEEN 1 AND 64
        AND lease_owner_id IS NOT NULL))
  )
);

CREATE INDEX idx_inventory_authoritative_outcome_target_pending
  ON public.inventory_authoritative_outcome_target(
    inventory_id, final_plan_version, finding_id, local_superseded, target_id
  );
