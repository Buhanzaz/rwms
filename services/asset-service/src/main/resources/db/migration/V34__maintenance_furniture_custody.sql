-- Furniture selected for an estimate or direct repair leaves the cabin before
-- the work starts, but it is not a terminal loss.  This append-only ledger
-- records the exact cabin source and carries the quantity until it returns to
-- STOCK or an already-approved maintenance property decision applies it.

CREATE TABLE public.maintenance_furniture_custody_claim (
  id uuid NOT NULL,
  actor_subject_id uuid NOT NULL,
  command_idempotency_key uuid NOT NULL,
  request_sha256 varchar(64) NOT NULL,
  owner_type varchar(64) NOT NULL,
  owner_id uuid NOT NULL,
  rental_item_id uuid NOT NULL,
  warehouse_id uuid NOT NULL,
  equipment_id uuid NOT NULL,
  source_balance_id uuid NOT NULL,
  source_balance_version bigint NOT NULL,
  quantity bigint NOT NULL,
  selected_at timestamptz NOT NULL,
  created_at timestamptz NOT NULL,
  CONSTRAINT maintenance_furniture_custody_claim_pkey PRIMARY KEY (id),
  CONSTRAINT ck_maintenance_furniture_custody_claim_request_sha256 CHECK (
    request_sha256 ~ '^[0-9a-f]{64}$'),
  CONSTRAINT ck_maintenance_furniture_custody_claim_owner_type CHECK (
    owner_type IN ('MAINTENANCE_ESTIMATE','MAINTENANCE_REPAIR')),
  CONSTRAINT ck_maintenance_furniture_custody_claim_source_version CHECK (
    source_balance_version >= 0),
  CONSTRAINT ck_maintenance_furniture_custody_claim_quantity CHECK (quantity > 0),
  CONSTRAINT fk_maintenance_furniture_custody_claim_rental_item
    FOREIGN KEY (rental_item_id) REFERENCES public.rental_item(id),
  CONSTRAINT fk_maintenance_furniture_custody_claim_equipment
    FOREIGN KEY (equipment_id) REFERENCES public.equipment_catalog_item(id),
  CONSTRAINT fk_maintenance_furniture_custody_claim_source_balance
    FOREIGN KEY (source_balance_id) REFERENCES public.equipment_balance(id)
);

-- This is a permanent command sink independent of expiring HTTP replay
-- records. A partial command can never be silently re-bound to a different
-- furniture line after a retry or recovery.
CREATE UNIQUE INDEX uk_maintenance_furniture_custody_claim_command_line
  ON public.maintenance_furniture_custody_claim(
    actor_subject_id, command_idempotency_key, rental_item_id, equipment_id);
CREATE INDEX idx_maintenance_furniture_custody_claim_owner
  ON public.maintenance_furniture_custody_claim(owner_type, owner_id, selected_at, id);

CREATE TABLE public.maintenance_furniture_custody_event (
  event_id uuid NOT NULL,
  claim_id uuid NOT NULL,
  event_version bigint NOT NULL,
  event_type varchar(32) NOT NULL,
  quantity bigint NOT NULL,
  target_balance_id uuid,
  target_balance_version bigint,
  target_balance_quantity bigint,
  decision_id uuid,
  return_reference_id uuid,
  idempotency_key uuid,
  request_sha256 varchar(64) NOT NULL,
  actor_subject_id uuid NOT NULL,
  event_body jsonb NOT NULL,
  event_sha256 varchar(64) NOT NULL,
  occurred_at timestamptz NOT NULL,
  CONSTRAINT maintenance_furniture_custody_event_pkey PRIMARY KEY (event_id),
  CONSTRAINT uk_maintenance_furniture_custody_event_version
    UNIQUE (claim_id, event_version),
  CONSTRAINT ck_maintenance_furniture_custody_event_version CHECK (event_version >= 0),
  CONSTRAINT ck_maintenance_furniture_custody_event_type CHECK (
    event_type IN ('SELECTED','RETURNED_TO_STOCK','DISPOSITION_PREPARED','DISPOSITION_APPLIED')),
  CONSTRAINT ck_maintenance_furniture_custody_event_quantity CHECK (quantity > 0),
  CONSTRAINT ck_maintenance_furniture_custody_event_request_sha256 CHECK (
    request_sha256 ~ '^[0-9a-f]{64}$'),
  CONSTRAINT ck_maintenance_furniture_custody_event_sha256 CHECK (
    event_sha256 ~ '^[0-9a-f]{64}$'),
  CONSTRAINT ck_maintenance_furniture_custody_event_body CHECK (
    jsonb_typeof(event_body) = 'object'),
  CONSTRAINT ck_maintenance_furniture_custody_event_target_shape CHECK (
    (target_balance_id IS NULL AND target_balance_version IS NULL AND target_balance_quantity IS NULL)
    OR
    (target_balance_id IS NOT NULL AND target_balance_version IS NOT NULL
      AND target_balance_version >= 0 AND target_balance_quantity IS NOT NULL
      AND target_balance_quantity >= 0)),
  CONSTRAINT ck_maintenance_furniture_custody_event_shape CHECK (
    (event_type = 'SELECTED'
      AND event_version = 0
      AND target_balance_id IS NULL
      AND decision_id IS NULL
      AND return_reference_id IS NULL
      AND idempotency_key IS NULL)
    OR
    (event_type = 'RETURNED_TO_STOCK'
      AND event_version > 0
      AND target_balance_id IS NOT NULL
      AND decision_id IS NULL
      AND return_reference_id IS NOT NULL
      AND idempotency_key IS NOT NULL)
    OR
    (event_type = 'DISPOSITION_PREPARED'
      AND event_version > 0
      AND target_balance_id IS NULL
      AND decision_id IS NOT NULL
      AND return_reference_id IS NULL
      AND idempotency_key IS NULL)
    OR
    (event_type = 'DISPOSITION_APPLIED'
      AND event_version > 0
      AND target_balance_id IS NOT NULL
      AND decision_id IS NOT NULL
      AND return_reference_id IS NULL
      AND idempotency_key IS NULL)
  ),
  CONSTRAINT fk_maintenance_furniture_custody_event_claim
    FOREIGN KEY (claim_id) REFERENCES public.maintenance_furniture_custody_claim(id),
  CONSTRAINT fk_maintenance_furniture_custody_event_target_balance
    FOREIGN KEY (target_balance_id) REFERENCES public.equipment_balance(id)
);

CREATE UNIQUE INDEX uk_maintenance_furniture_custody_return_reference
  ON public.maintenance_furniture_custody_event(claim_id, return_reference_id)
  WHERE event_type = 'RETURNED_TO_STOCK';
CREATE UNIQUE INDEX uk_maintenance_furniture_custody_return_idempotency
  ON public.maintenance_furniture_custody_event(claim_id, idempotency_key)
  WHERE event_type = 'RETURNED_TO_STOCK';
CREATE UNIQUE INDEX uk_maintenance_furniture_custody_decision_event
  ON public.maintenance_furniture_custody_event(claim_id, decision_id, event_type)
  WHERE decision_id IS NOT NULL;
CREATE INDEX idx_maintenance_furniture_custody_event_claim
  ON public.maintenance_furniture_custody_event(claim_id, event_version);

CREATE FUNCTION public.prevent_maintenance_furniture_custody_truncate()
RETURNS trigger
LANGUAGE plpgsql
AS $$
BEGIN
  RAISE EXCEPTION 'maintenance furniture custody evidence cannot be truncated';
END;
$$;

CREATE TRIGGER trg_maintenance_furniture_custody_claim_immutable
BEFORE UPDATE OR DELETE ON public.maintenance_furniture_custody_claim
FOR EACH ROW EXECUTE FUNCTION public.prevent_asset_append_only_mutation();

CREATE TRIGGER trg_maintenance_furniture_custody_event_immutable
BEFORE UPDATE OR DELETE ON public.maintenance_furniture_custody_event
FOR EACH ROW EXECUTE FUNCTION public.prevent_asset_append_only_mutation();

CREATE TRIGGER trg_maintenance_furniture_custody_claim_no_truncate
BEFORE TRUNCATE ON public.maintenance_furniture_custody_claim
FOR EACH STATEMENT EXECUTE FUNCTION public.prevent_maintenance_furniture_custody_truncate();

CREATE TRIGGER trg_maintenance_furniture_custody_event_no_truncate
BEFORE TRUNCATE ON public.maintenance_furniture_custody_event
FOR EACH STATEMENT EXECUTE FUNCTION public.prevent_maintenance_furniture_custody_truncate();

-- A custody-backed equipment decision has no STOCK source. The immutable
-- claim/event ledger is its source evidence instead. Existing stock and cabin
-- fences remain byte-for-byte compatible with the prior shape.
ALTER TABLE public.property_disposition_fence
  ADD COLUMN maintenance_custody_claim_id uuid,
  ADD COLUMN maintenance_custody_version bigint;

ALTER TABLE public.property_disposition_fence
  ADD CONSTRAINT fk_property_disposition_fence_maintenance_custody_claim
  FOREIGN KEY (maintenance_custody_claim_id)
  REFERENCES public.maintenance_furniture_custody_claim(id);

ALTER TABLE public.property_disposition_fence
  DROP CONSTRAINT ck_property_disposition_fence_asset_shape;

ALTER TABLE public.property_disposition_fence
  ADD CONSTRAINT ck_property_disposition_fence_asset_shape CHECK (
    (
      asset_kind = 'CABIN'
      AND expected_asset_version IS NOT NULL
      AND expected_asset_version >= 0
      AND source_balance_id IS NULL
      AND expected_source_balance_version IS NULL
      AND quantity IS NULL
      AND maintenance_custody_claim_id IS NULL
      AND maintenance_custody_version IS NULL
    )
    OR
    (
      asset_kind = 'EQUIPMENT'
      AND expected_asset_version IS NULL
      AND source_balance_id IS NOT NULL
      AND expected_source_balance_version IS NOT NULL
      AND expected_source_balance_version >= 0
      AND quantity IS NOT NULL
      AND quantity > 0
      AND contents_mode IS NULL
      AND maintenance_custody_claim_id IS NULL
      AND maintenance_custody_version IS NULL
    )
    OR
    (
      asset_kind = 'EQUIPMENT'
      AND expected_asset_version IS NULL
      AND source_balance_id IS NULL
      AND expected_source_balance_version IS NULL
      AND quantity IS NOT NULL
      AND quantity > 0
      AND contents_mode IS NULL
      AND maintenance_custody_claim_id IS NOT NULL
      AND maintenance_custody_version IS NOT NULL
      AND maintenance_custody_version >= 0
    )
  );

DROP INDEX public.uk_property_disposition_prepared_asset;

-- A physical CABIN/STOCK source remains exclusive. Independent custody
-- claims for the same furniture identity may be approved concurrently because
-- each has its own exact immutable quantity fence.
CREATE UNIQUE INDEX uk_property_disposition_prepared_physical_asset
  ON public.property_disposition_fence(asset_kind, asset_id, warehouse_id)
  WHERE state = 'PREPARED' AND maintenance_custody_claim_id IS NULL;

CREATE OR REPLACE FUNCTION public.prevent_property_disposition_fence_identity_change()
RETURNS trigger
LANGUAGE plpgsql
AS $$
BEGIN
  IF (NEW.decision_id,
      NEW.request_sha256,
      NEW.warehouse_id,
      NEW.asset_kind,
      NEW.asset_id,
      NEW.disposition,
      NEW.expected_asset_version,
      NEW.source_balance_id,
      NEW.expected_source_balance_version,
      NEW.quantity,
      NEW.contents_mode,
      NEW.maintenance_lease_id,
      NEW.maintenance_lease_fencing_token,
      NEW.maintenance_lease_owner_type,
      NEW.maintenance_lease_owner_id,
      NEW.primary_hold_id,
      NEW.maintenance_custody_claim_id,
      NEW.maintenance_custody_version,
      NEW.prepared_at,
      NEW.created_at)
     IS DISTINCT FROM
     (OLD.decision_id,
      OLD.request_sha256,
      OLD.warehouse_id,
      OLD.asset_kind,
      OLD.asset_id,
      OLD.disposition,
      OLD.expected_asset_version,
      OLD.source_balance_id,
      OLD.expected_source_balance_version,
      OLD.quantity,
      OLD.contents_mode,
      OLD.maintenance_lease_id,
      OLD.maintenance_lease_fencing_token,
      OLD.maintenance_lease_owner_type,
      OLD.maintenance_lease_owner_id,
      OLD.primary_hold_id,
      OLD.maintenance_custody_claim_id,
      OLD.maintenance_custody_version,
      OLD.prepared_at,
      OLD.created_at) THEN
    RAISE EXCEPTION 'property disposition fence identity is immutable';
  END IF;

  IF OLD.state = 'APPLIED' THEN
    RAISE EXCEPTION 'applied property disposition fence is immutable';
  END IF;

  IF NEW.state <> 'APPLIED'
     OR NEW.applied_at IS NULL
     OR NEW.effect_id IS NULL THEN
    RAISE EXCEPTION 'property disposition fence may only transition to APPLIED';
  END IF;

  RETURN NEW;
END;
$$;
