-- Approved property dispositions are intentionally durable.  Maintenance owns
-- the human decision; asset owns the fenced physical truth that makes the
-- decision safe to execute after an administrator and, where necessary, a
-- worker task have completed their work.

ALTER TABLE public.rental_item
  DROP CONSTRAINT ck_rental_item_status;

ALTER TABLE public.rental_item
  ADD CONSTRAINT ck_rental_item_status CHECK (status IN (
    'RENTED','BOOKED','REPAIR','WAITING_REPAIR_CHECK','WRITTEN_OFF','LOST',
    'CAPITAL_REPAIR','AFTER_RENT','WAITING_ESTIMATE_CONFIRMATION','SALE',
    'USED_SALE','RESERVED','FREE','WAREHOUSE','OWN_NEEDS','IN_TRANSFER'
  ));

-- A logistics reservation is immutable evidence of one executed physical
-- movement.  The nullable link keeps historical movements untouched while
-- allowing a disposition APPLY to prove that its selected cabin contents were
-- actually moved to stock by the worker workflow.
ALTER TABLE public.equipment_movement
  ADD COLUMN origin_reservation_id uuid;

ALTER TABLE public.equipment_movement
  ADD CONSTRAINT fk_equipment_movement_origin_reservation
  FOREIGN KEY (origin_reservation_id)
  REFERENCES public.equipment_allocation_hold(id);

CREATE UNIQUE INDEX uk_equipment_movement_origin_reservation
  ON public.equipment_movement(origin_reservation_id)
  WHERE origin_reservation_id IS NOT NULL;

CREATE TABLE public.property_disposition_fence (
  decision_id uuid NOT NULL,
  version bigint NOT NULL DEFAULT 0,
  request_sha256 varchar(64) NOT NULL,
  warehouse_id uuid NOT NULL,
  asset_kind varchar(16) NOT NULL,
  asset_id uuid NOT NULL,
  disposition varchar(16) NOT NULL,
  expected_asset_version bigint,
  source_balance_id uuid,
  expected_source_balance_version bigint,
  quantity bigint,
  contents_mode varchar(32),
  maintenance_lease_id uuid,
  maintenance_lease_fencing_token bigint,
  maintenance_lease_owner_type varchar(64),
  maintenance_lease_owner_id uuid,
  primary_hold_id uuid,
  state varchar(16) NOT NULL,
  prepared_at timestamptz NOT NULL,
  applied_at timestamptz,
  effect_id uuid,
  completed_movement_task_id uuid,
  created_at timestamptz NOT NULL,
  updated_at timestamptz NOT NULL,
  CONSTRAINT property_disposition_fence_pkey PRIMARY KEY (decision_id),
  CONSTRAINT ck_property_disposition_fence_version CHECK (version >= 0),
  CONSTRAINT ck_property_disposition_fence_request_sha256 CHECK (
    request_sha256 ~ '^[0-9a-f]{64}$'),
  CONSTRAINT ck_property_disposition_fence_asset_kind CHECK (
    asset_kind IN ('CABIN','EQUIPMENT')),
  CONSTRAINT ck_property_disposition_fence_disposition CHECK (
    disposition IN ('WRITE_OFF','LOSS')),
  CONSTRAINT ck_property_disposition_fence_asset_shape CHECK (
    (
      asset_kind = 'CABIN'
      AND expected_asset_version IS NOT NULL
      AND expected_asset_version >= 0
      AND source_balance_id IS NULL
      AND expected_source_balance_version IS NULL
      AND quantity IS NULL
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
    )
  ),
  CONSTRAINT ck_property_disposition_fence_contents_mode CHECK (
    contents_mode IS NULL OR contents_mode IN ('MOVE_SELECTED_TO_STOCK','DISPOSE_WITH_CABIN')),
  CONSTRAINT ck_property_disposition_fence_lease_proof CHECK (
    (
      maintenance_lease_id IS NULL
      AND maintenance_lease_fencing_token IS NULL
      AND maintenance_lease_owner_type IS NULL
      AND maintenance_lease_owner_id IS NULL
    )
    OR
    (
      maintenance_lease_id IS NOT NULL
      AND maintenance_lease_fencing_token IS NOT NULL
      AND maintenance_lease_fencing_token > 0
      AND maintenance_lease_owner_type IN ('MAINTENANCE_ESTIMATE','MAINTENANCE_REPAIR')
      AND maintenance_lease_owner_id IS NOT NULL
    )
  ),
  CONSTRAINT ck_property_disposition_fence_state CHECK (
    state IN ('PREPARED','APPLIED')),
  CONSTRAINT ck_property_disposition_fence_application CHECK (
    (
      state = 'PREPARED'
      AND applied_at IS NULL
      AND effect_id IS NULL
      AND completed_movement_task_id IS NULL
    )
    OR
    (
      state = 'APPLIED'
      AND applied_at IS NOT NULL
      AND effect_id IS NOT NULL
    )
  ),
  CONSTRAINT fk_property_disposition_fence_source_balance
    FOREIGN KEY (source_balance_id) REFERENCES public.equipment_balance(id),
  CONSTRAINT fk_property_disposition_fence_primary_hold
    FOREIGN KEY (primary_hold_id) REFERENCES public.equipment_allocation_hold(id)
);

CREATE UNIQUE INDEX uk_property_disposition_prepared_asset
  ON public.property_disposition_fence(asset_kind, asset_id, warehouse_id)
  WHERE state = 'PREPARED';

CREATE INDEX idx_property_disposition_fence_asset_state
  ON public.property_disposition_fence(asset_kind, asset_id, warehouse_id, state);

CREATE TABLE public.property_disposition_fence_content (
  id uuid NOT NULL,
  decision_id uuid NOT NULL,
  equipment_id uuid NOT NULL,
  source_balance_id uuid NOT NULL,
  expected_balance_version bigint NOT NULL,
  current_quantity bigint NOT NULL,
  move_quantity bigint NOT NULL,
  disposition_quantity bigint NOT NULL,
  hold_id uuid NOT NULL,
  created_at timestamptz NOT NULL,
  CONSTRAINT property_disposition_fence_content_pkey PRIMARY KEY (id),
  CONSTRAINT uk_property_disposition_fence_content_equipment
    UNIQUE (decision_id, equipment_id),
  CONSTRAINT uk_property_disposition_fence_content_hold UNIQUE (hold_id),
  CONSTRAINT ck_property_disposition_fence_content_expected_version CHECK (
    expected_balance_version >= 0),
  CONSTRAINT ck_property_disposition_fence_content_current_quantity CHECK (
    current_quantity > 0),
  CONSTRAINT ck_property_disposition_fence_content_move_quantity CHECK (
    move_quantity >= 0 AND move_quantity <= current_quantity),
  CONSTRAINT ck_property_disposition_fence_content_disposition_quantity CHECK (
    disposition_quantity = current_quantity - move_quantity),
  CONSTRAINT fk_property_disposition_fence_content_fence
    FOREIGN KEY (decision_id) REFERENCES public.property_disposition_fence(decision_id),
  CONSTRAINT fk_property_disposition_fence_content_catalog
    FOREIGN KEY (equipment_id) REFERENCES public.equipment_catalog_item(id),
  CONSTRAINT fk_property_disposition_fence_content_source_balance
    FOREIGN KEY (source_balance_id) REFERENCES public.equipment_balance(id),
  CONSTRAINT fk_property_disposition_fence_content_hold
    FOREIGN KEY (hold_id) REFERENCES public.equipment_allocation_hold(id)
);

CREATE INDEX idx_property_disposition_fence_content_source
  ON public.property_disposition_fence_content(source_balance_id, decision_id);

CREATE TABLE public.property_disposition_effect (
  effect_id uuid NOT NULL,
  decision_id uuid NOT NULL,
  response_body jsonb NOT NULL,
  response_sha256 varchar(64) NOT NULL,
  applied_at timestamptz NOT NULL,
  CONSTRAINT property_disposition_effect_pkey PRIMARY KEY (effect_id),
  CONSTRAINT uk_property_disposition_effect_decision UNIQUE (decision_id),
  CONSTRAINT ck_property_disposition_effect_response_sha256 CHECK (
    response_sha256 ~ '^[0-9a-f]{64}$'),
  CONSTRAINT ck_property_disposition_effect_response_body CHECK (
    jsonb_typeof(response_body) = 'object'),
  CONSTRAINT fk_property_disposition_effect_fence
    FOREIGN KEY (decision_id) REFERENCES public.property_disposition_fence(decision_id)
);

ALTER TABLE public.property_disposition_fence
  ADD CONSTRAINT fk_property_disposition_fence_effect
  FOREIGN KEY (effect_id) REFERENCES public.property_disposition_effect(effect_id);

CREATE TABLE public.property_disposition_audit_event (
  event_id uuid NOT NULL,
  decision_id uuid NOT NULL,
  event_type varchar(24) NOT NULL,
  actor_subject_id uuid NOT NULL,
  request_sha256 varchar(64) NOT NULL,
  event_body jsonb NOT NULL,
  event_sha256 varchar(64) NOT NULL,
  occurred_at timestamptz NOT NULL,
  CONSTRAINT property_disposition_audit_event_pkey PRIMARY KEY (event_id),
  CONSTRAINT ck_property_disposition_audit_event_type CHECK (
    event_type IN ('PREPARED','APPLIED')),
  CONSTRAINT ck_property_disposition_audit_event_request_sha256 CHECK (
    request_sha256 ~ '^[0-9a-f]{64}$'),
  CONSTRAINT ck_property_disposition_audit_event_sha256 CHECK (
    event_sha256 ~ '^[0-9a-f]{64}$'),
  CONSTRAINT ck_property_disposition_audit_event_body CHECK (
    jsonb_typeof(event_body) = 'object'),
  CONSTRAINT fk_property_disposition_audit_event_fence
    FOREIGN KEY (decision_id) REFERENCES public.property_disposition_fence(decision_id)
);

CREATE INDEX idx_property_disposition_audit_event_decision_occurred
  ON public.property_disposition_audit_event(decision_id, occurred_at, event_id);

CREATE FUNCTION public.prevent_property_disposition_fence_identity_change()
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

CREATE FUNCTION public.prevent_property_disposition_truncate()
RETURNS trigger
LANGUAGE plpgsql
AS $$
BEGIN
  RAISE EXCEPTION 'property disposition evidence cannot be truncated';
END;
$$;

CREATE TRIGGER trg_property_disposition_fence_identity
BEFORE UPDATE ON public.property_disposition_fence
FOR EACH ROW EXECUTE FUNCTION public.prevent_property_disposition_fence_identity_change();

CREATE TRIGGER trg_property_disposition_fence_no_delete
BEFORE DELETE ON public.property_disposition_fence
FOR EACH ROW EXECUTE FUNCTION public.prevent_asset_hard_delete();

CREATE TRIGGER trg_property_disposition_content_immutable
BEFORE UPDATE OR DELETE ON public.property_disposition_fence_content
FOR EACH ROW EXECUTE FUNCTION public.prevent_asset_append_only_mutation();

CREATE TRIGGER trg_property_disposition_effect_immutable
BEFORE UPDATE OR DELETE ON public.property_disposition_effect
FOR EACH ROW EXECUTE FUNCTION public.prevent_asset_append_only_mutation();

CREATE TRIGGER trg_property_disposition_audit_immutable
BEFORE UPDATE OR DELETE ON public.property_disposition_audit_event
FOR EACH ROW EXECUTE FUNCTION public.prevent_asset_append_only_mutation();

CREATE TRIGGER trg_property_disposition_fence_no_truncate
BEFORE TRUNCATE ON public.property_disposition_fence
FOR EACH STATEMENT EXECUTE FUNCTION public.prevent_property_disposition_truncate();

CREATE TRIGGER trg_property_disposition_content_no_truncate
BEFORE TRUNCATE ON public.property_disposition_fence_content
FOR EACH STATEMENT EXECUTE FUNCTION public.prevent_property_disposition_truncate();

CREATE TRIGGER trg_property_disposition_effect_no_truncate
BEFORE TRUNCATE ON public.property_disposition_effect
FOR EACH STATEMENT EXECUTE FUNCTION public.prevent_property_disposition_truncate();

CREATE TRIGGER trg_property_disposition_audit_no_truncate
BEFORE TRUNCATE ON public.property_disposition_audit_event
FOR EACH STATEMENT EXECUTE FUNCTION public.prevent_property_disposition_truncate();
