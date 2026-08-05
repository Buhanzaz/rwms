-- Warehouse lifecycle is a one-way state machine. The retained active column is a
-- compatibility projection only: it is true exactly when the warehouse accepts
-- incoming work (ACTIVE), never a writable lifecycle command.
ALTER TABLE public.warehouse
  ADD COLUMN lifecycle_state varchar(16) NOT NULL DEFAULT 'ACTIVE',
  ADD COLUMN lifecycle_revision bigint NOT NULL DEFAULT 0;

UPDATE public.warehouse
SET lifecycle_state = CASE WHEN active THEN 'ACTIVE' ELSE 'INACTIVE' END;

-- Create replay bodies are a live idempotency boundary, not historical mock data. Backfill the
-- added response field so a request retried across this deployment still satisfies the contract.
UPDATE public.idempotency_record AS record
SET response_body = record.response_body || jsonb_build_object('lifecycleState', warehouse.lifecycle_state)
FROM public.warehouse AS warehouse
WHERE warehouse.id = record.warehouse_id
  AND NOT (record.response_body ? 'lifecycleState');

ALTER TABLE public.warehouse
  ADD CONSTRAINT ck_warehouse_lifecycle_state
    CHECK (lifecycle_state IN ('ACTIVE', 'DRAINING', 'INACTIVE')),
  ADD CONSTRAINT ck_warehouse_lifecycle_revision
    CHECK (lifecycle_revision >= 0),
  ADD CONSTRAINT ck_warehouse_active_lifecycle_state
    CHECK (
      (lifecycle_state = 'ACTIVE' AND active)
      OR (lifecycle_state IN ('DRAINING', 'INACTIVE') AND NOT active)
    );

DROP INDEX IF EXISTS public.idx_warehouse_active_order;
CREATE INDEX idx_warehouse_lifecycle_order
  ON public.warehouse(lifecycle_state, sort_order, name, id);

-- No readiness is synthesized for records that were already inactive before this
-- migration. They remain historical evidence but are never represented as a new,
-- confirmed lifecycle completion.
CREATE TABLE public.warehouse_lifecycle_readiness (
  warehouse_id uuid NOT NULL,
  readiness_owner varchar(32) NOT NULL,
  warehouse_version bigint NOT NULL,
  confirmed_at timestamptz NOT NULL,
  CONSTRAINT warehouse_lifecycle_readiness_pkey
    PRIMARY KEY (warehouse_id, readiness_owner),
  CONSTRAINT fk_warehouse_lifecycle_readiness_warehouse
    FOREIGN KEY (warehouse_id) REFERENCES public.warehouse(id) ON DELETE CASCADE,
  CONSTRAINT ck_warehouse_lifecycle_readiness_owner
    CHECK (readiness_owner IN ('ASSET', 'INVENTORY', 'LOGISTICS', 'MAINTENANCE', 'TASK_BOARD')),
  CONSTRAINT ck_warehouse_lifecycle_readiness_version
    CHECK (warehouse_version >= 0)
);

CREATE TABLE public.warehouse_lifecycle_transition (
  warehouse_id uuid NOT NULL,
  warehouse_version bigint NOT NULL,
  transition varchar(32) NOT NULL,
  recorded_at timestamptz NOT NULL,
  CONSTRAINT warehouse_lifecycle_transition_pkey
    PRIMARY KEY (warehouse_id, warehouse_version),
  CONSTRAINT fk_warehouse_lifecycle_transition_warehouse
    FOREIGN KEY (warehouse_id) REFERENCES public.warehouse(id) ON DELETE CASCADE,
  CONSTRAINT ck_warehouse_lifecycle_transition_kind
    CHECK (transition IN ('DRAINING_STARTED', 'INACTIVATED')),
  CONSTRAINT ck_warehouse_lifecycle_transition_version
    CHECK (warehouse_version >= 0)
);

CREATE FUNCTION public.prevent_warehouse_lifecycle_readiness_mutation()
RETURNS trigger
LANGUAGE plpgsql
AS $$
BEGIN
  IF TG_OP = 'DELETE'
      AND (pg_trigger_depth() > 1
        OR NOT EXISTS (
          SELECT 1 FROM public.warehouse WHERE id = OLD.warehouse_id)) THEN
    RETURN OLD;
  END IF;
  RAISE EXCEPTION 'warehouse lifecycle readiness is immutable';
END;
$$;

CREATE TRIGGER trg_warehouse_lifecycle_readiness_immutable
BEFORE UPDATE OR DELETE ON public.warehouse_lifecycle_readiness
FOR EACH ROW
EXECUTE FUNCTION public.prevent_warehouse_lifecycle_readiness_mutation();

CREATE FUNCTION public.prevent_warehouse_lifecycle_transition_mutation()
RETURNS trigger
LANGUAGE plpgsql
AS $$
BEGIN
  IF TG_OP = 'DELETE'
      AND (pg_trigger_depth() > 1
        OR NOT EXISTS (
          SELECT 1 FROM public.warehouse WHERE id = OLD.warehouse_id)) THEN
    RETURN OLD;
  END IF;
  RAISE EXCEPTION 'warehouse lifecycle transition is immutable';
END;
$$;

CREATE TRIGGER trg_warehouse_lifecycle_transition_immutable
BEFORE UPDATE OR DELETE ON public.warehouse_lifecycle_transition
FOR EACH ROW
EXECUTE FUNCTION public.prevent_warehouse_lifecycle_transition_mutation();
