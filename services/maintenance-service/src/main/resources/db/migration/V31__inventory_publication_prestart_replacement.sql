-- A logistics cancellation can synchronously call maintenance to release a repair place.  Keep
-- its coordinator state durable and perform that callback-capable remote effect outside every
-- local transaction/row lock.  The immutable source is written only after this saga is applied.

CREATE TABLE public.inventory_publication_prestart_replacement (
  inventory_id uuid NOT NULL,
  final_plan_version bigint NOT NULL,
  finding_id uuid NOT NULL,
  version bigint NOT NULL DEFAULT 0,
  request_sha256 varchar(64) NOT NULL,
  request_idempotency_key uuid NOT NULL,
  request_snapshot jsonb NOT NULL,
  warehouse_id uuid NOT NULL,
  asset_id uuid NOT NULL,
  predecessor_repair_id uuid NOT NULL,
  predecessor_mode varchar(32) NOT NULL,
  driver_kind varchar(32) NOT NULL,
  task_external_id uuid NOT NULL,
  task_expected_version bigint,
  task_guard_required boolean NOT NULL,
  lease_id uuid,
  lease_version bigint,
  fencing_token bigint,
  lease_owner_type varchar(64),
  lease_owner_id uuid,
  phase varchar(32) NOT NULL,
  driver_outcome varchar(32),
  task_outcome varchar(32),
  occupancy_reassignment_required boolean NOT NULL DEFAULT false,
  repair_place_allocation_id uuid,
  repair_place_allocation_version bigint,
  successor_repair_id uuid,
  compensated_at timestamptz,
  lease_released_at timestamptz,
  applied_at timestamptz,
  created_at timestamptz NOT NULL,
  updated_at timestamptz NOT NULL,
  CONSTRAINT inventory_publication_prestart_replacement_pkey
    PRIMARY KEY (inventory_id, final_plan_version, finding_id),
  CONSTRAINT fk_inventory_publication_prestart_replacement_operation
    FOREIGN KEY (inventory_id, final_plan_version, finding_id)
    REFERENCES public.inventory_publication_source_operation(
      inventory_id, final_plan_version, finding_id
    ),
  CONSTRAINT fk_inventory_publication_prestart_replacement_predecessor
    FOREIGN KEY (predecessor_repair_id) REFERENCES public.maintenance_repair(id),
  CONSTRAINT fk_inventory_publication_prestart_replacement_successor
    FOREIGN KEY (successor_repair_id) REFERENCES public.maintenance_repair(id),
  CONSTRAINT ck_inventory_publication_prestart_replacement_version CHECK (version >= 0),
  CONSTRAINT ck_inventory_publication_prestart_replacement_plan_version CHECK (
    final_plan_version >= 1
  ),
  CONSTRAINT ck_inventory_publication_prestart_replacement_hash CHECK (
    request_sha256 ~ '^[0-9a-f]{64}$'
  ),
  CONSTRAINT ck_inventory_publication_prestart_replacement_request CHECK (
    jsonb_typeof(request_snapshot) = 'object'
  ),
  CONSTRAINT ck_inventory_publication_prestart_replacement_mode CHECK (
    predecessor_mode IN ('ORDINARY', 'EXTERNAL_CAPITAL')
    AND driver_kind IN ('DELIVER_TO_REPAIR', 'CAPITAL_TO_PRODUCTION')
  ),
  CONSTRAINT ck_inventory_publication_prestart_replacement_task_guard CHECK (
    (task_guard_required
      AND task_expected_version IS NOT NULL AND task_expected_version >= 0)
    OR (NOT task_guard_required AND task_expected_version IS NULL)
  ),
  CONSTRAINT ck_inventory_publication_prestart_replacement_lease CHECK (
    (lease_id IS NULL AND lease_version IS NULL AND fencing_token IS NULL
      AND lease_owner_type IS NULL AND lease_owner_id IS NULL)
    OR (lease_id IS NOT NULL AND lease_version IS NOT NULL AND lease_version >= 0
      AND fencing_token IS NOT NULL AND fencing_token >= 1
      AND lease_owner_type IS NOT NULL AND length(lease_owner_type) BETWEEN 1 AND 64
      AND lease_owner_id IS NOT NULL)
  ),
  CONSTRAINT ck_inventory_publication_prestart_replacement_allocation CHECK (
    (occupancy_reassignment_required
      AND repair_place_allocation_id IS NOT NULL
      AND repair_place_allocation_version IS NOT NULL
      AND repair_place_allocation_version >= 0)
    OR (NOT occupancy_reassignment_required
      AND repair_place_allocation_id IS NULL
      AND repair_place_allocation_version IS NULL)
  ),
  CONSTRAINT ck_inventory_publication_prestart_replacement_phase CHECK (
    (phase = 'PREPARED'
      AND driver_outcome IS NULL AND task_outcome IS NULL
      AND NOT occupancy_reassignment_required
      AND successor_repair_id IS NULL
      AND compensated_at IS NULL AND lease_released_at IS NULL AND applied_at IS NULL)
    OR (phase = 'COMPENSATED'
      AND driver_outcome IN ('ABSENT', 'CANCELLED', 'COMPLETED')
      AND ((task_guard_required AND task_outcome IN ('CANCELLED', 'ALREADY_CANCELLED'))
        OR (NOT task_guard_required AND task_outcome IS NULL))
      AND successor_repair_id IS NULL
      AND compensated_at IS NOT NULL AND lease_released_at IS NULL AND applied_at IS NULL)
    OR (phase = 'SUCCESSOR_CREATED'
      AND driver_outcome IN ('ABSENT', 'CANCELLED', 'COMPLETED')
      AND ((task_guard_required AND task_outcome IN ('CANCELLED', 'ALREADY_CANCELLED'))
        OR (NOT task_guard_required AND task_outcome IS NULL))
      AND successor_repair_id IS NOT NULL
      AND compensated_at IS NOT NULL AND lease_released_at IS NULL AND applied_at IS NULL)
    OR (phase = 'LEASE_RELEASED'
      AND driver_outcome IN ('ABSENT', 'CANCELLED', 'COMPLETED')
      AND ((task_guard_required AND task_outcome IN ('CANCELLED', 'ALREADY_CANCELLED'))
        OR (NOT task_guard_required AND task_outcome IS NULL))
      AND successor_repair_id IS NOT NULL
      AND compensated_at IS NOT NULL AND lease_released_at IS NOT NULL AND applied_at IS NULL)
    OR (phase = 'APPLIED' AND applied_at IS NOT NULL)
  )
);

CREATE UNIQUE INDEX uk_inventory_publication_prestart_replacement_active_repair
  ON public.inventory_publication_prestart_replacement(predecessor_repair_id)
  WHERE phase <> 'APPLIED';

CREATE INDEX idx_inventory_publication_prestart_replacement_pending
  ON public.inventory_publication_prestart_replacement(phase, updated_at)
  WHERE phase <> 'APPLIED';
