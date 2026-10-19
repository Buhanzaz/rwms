-- Warehouse timezone is operational metadata. A change after a warehouse has
-- operated is appended here rather than rewriting the zone used by historical
-- operations and reports. The warehouse.time_zone column remains a compact
-- current/default value for legacy-compatible aggregate payloads; reads resolve
-- this immutable history at the requested timestamp.

ALTER TABLE public.warehouse
  ADD COLUMN time_zone_revision bigint NOT NULL DEFAULT 0,
  ADD CONSTRAINT ck_warehouse_time_zone_revision CHECK (time_zone_revision >= 0);

CREATE TABLE public.warehouse_time_zone_history (
  id uuid NOT NULL DEFAULT gen_random_uuid(),
  warehouse_id uuid NOT NULL,
  effective_from timestamptz NOT NULL,
  time_zone varchar(64) NOT NULL,
  recorded_at timestamptz NOT NULL,
  CONSTRAINT warehouse_time_zone_history_pkey PRIMARY KEY (id),
  CONSTRAINT uk_warehouse_time_zone_history_effective UNIQUE (warehouse_id, effective_from),
  CONSTRAINT fk_warehouse_time_zone_history_warehouse
    FOREIGN KEY (warehouse_id) REFERENCES public.warehouse(id) ON DELETE CASCADE,
  CONSTRAINT ck_warehouse_time_zone_history_time_zone
    CHECK (length(btrim(time_zone)) BETWEEN 1 AND 64)
);

CREATE INDEX idx_warehouse_time_zone_history_as_of
  ON public.warehouse_time_zone_history(warehouse_id, effective_from DESC);

CREATE FUNCTION public.prevent_warehouse_time_zone_history_mutation()
RETURNS trigger
LANGUAGE plpgsql
AS $$
BEGIN
  -- A physical warehouse delete is not a supported runtime command, but the
  -- foreign-key cascade must be able to remove dependent immutable evidence if
  -- a database is retired or test fixtures are torn down. Direct history edits
  -- remain forbidden.
  IF TG_OP = 'DELETE'
      AND (pg_trigger_depth() > 1
        OR NOT EXISTS (
          SELECT 1 FROM public.warehouse WHERE id = OLD.warehouse_id)) THEN
    RETURN OLD;
  END IF;
  RAISE EXCEPTION 'warehouse timezone history is immutable';
END;
$$;

CREATE TRIGGER trg_warehouse_time_zone_history_immutable
BEFORE UPDATE OR DELETE ON public.warehouse_time_zone_history
FOR EACH ROW
EXECUTE FUNCTION public.prevent_warehouse_time_zone_history_mutation();

-- A state row makes the “has this warehouse ever operated?” decision durable.
-- Existing records are conservatively treated as already operated because their
-- pre-migration operation history is outside this service and must never be
-- reinterpreted by a later timezone correction.
CREATE TABLE public.warehouse_operation_state (
  warehouse_id uuid NOT NULL,
  first_operation_at timestamptz NOT NULL,
  CONSTRAINT warehouse_operation_state_pkey PRIMARY KEY (warehouse_id),
  CONSTRAINT fk_warehouse_operation_state_warehouse
    FOREIGN KEY (warehouse_id) REFERENCES public.warehouse(id) ON DELETE CASCADE
);

CREATE TABLE public.warehouse_operation_mark (
  warehouse_id uuid NOT NULL,
  operation_source varchar(32) NOT NULL,
  operation_id uuid NOT NULL,
  occurred_at timestamptz NOT NULL,
  recorded_at timestamptz NOT NULL,
  CONSTRAINT warehouse_operation_mark_pkey
    PRIMARY KEY (warehouse_id, operation_source, operation_id),
  CONSTRAINT fk_warehouse_operation_mark_warehouse
    FOREIGN KEY (warehouse_id) REFERENCES public.warehouse(id) ON DELETE CASCADE,
  CONSTRAINT ck_warehouse_operation_mark_source
    CHECK (operation_source IN ('ASSET', 'INVENTORY', 'LOGISTICS', 'MAINTENANCE'))
);

CREATE INDEX idx_warehouse_operation_mark_first
  ON public.warehouse_operation_mark(warehouse_id, occurred_at);

CREATE FUNCTION public.prevent_warehouse_operation_mark_mutation()
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
  RAISE EXCEPTION 'warehouse operation mark is immutable';
END;
$$;

CREATE TRIGGER trg_warehouse_operation_mark_immutable
BEFORE UPDATE OR DELETE ON public.warehouse_operation_mark
FOR EACH ROW
EXECUTE FUNCTION public.prevent_warehouse_operation_mark_mutation();

INSERT INTO public.warehouse_time_zone_history(
  warehouse_id, effective_from, time_zone, recorded_at)
SELECT id, created_at, time_zone, clock_timestamp()
FROM public.warehouse;

INSERT INTO public.warehouse_operation_state(warehouse_id, first_operation_at)
SELECT id, created_at
FROM public.warehouse;
