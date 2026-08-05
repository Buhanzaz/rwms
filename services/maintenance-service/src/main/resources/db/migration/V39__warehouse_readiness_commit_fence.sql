-- The local readiness fence closes the interval between a remote admission check and the local
-- blocker commit. Every new maintenance root takes the same warehouse advisory lock as readiness.

CREATE TABLE public.warehouse_readiness_fence (
  id uuid NOT NULL,
  warehouse_id uuid NOT NULL,
  warehouse_version bigint NOT NULL,
  state varchar(16) NOT NULL,
  release_reason varchar(64),
  fenced_at timestamptz NOT NULL,
  sealed_at timestamptz,
  released_at timestamptz,
  updated_at timestamptz NOT NULL,
  CONSTRAINT warehouse_readiness_fence_pkey PRIMARY KEY (id),
  CONSTRAINT uq_warehouse_readiness_fence_version UNIQUE (warehouse_id, warehouse_version),
  CONSTRAINT ck_warehouse_readiness_fence_version CHECK (warehouse_version >= 0),
  CONSTRAINT ck_warehouse_readiness_fence_state CHECK (
    state IN ('FENCED', 'SEALED', 'RELEASED')),
  CONSTRAINT ck_warehouse_readiness_fence_transition CHECK (
    (state = 'FENCED'
      AND sealed_at IS NULL
      AND released_at IS NULL
      AND release_reason IS NULL)
    OR (state = 'SEALED'
      AND sealed_at IS NOT NULL
      AND released_at IS NULL
      AND release_reason IS NULL)
    OR (state = 'RELEASED'
      AND sealed_at IS NULL
      AND released_at IS NOT NULL
      AND release_reason IS NOT NULL
      AND btrim(release_reason) <> ''))
);

CREATE UNIQUE INDEX uq_warehouse_readiness_fence_active
  ON public.warehouse_readiness_fence(warehouse_id)
  WHERE state IN ('FENCED', 'SEALED');

CREATE INDEX idx_warehouse_readiness_fence_state
  ON public.warehouse_readiness_fence(state, warehouse_id, warehouse_version);

CREATE FUNCTION public.guard_maintenance_warehouse_readiness_fence()
RETURNS trigger
LANGUAGE plpgsql
AS $$
DECLARE
  guard_kind text := COALESCE(TG_ARGV[0], '');
  next_row jsonb := to_jsonb(NEW);
  previous_row jsonb;
  target_warehouse_id uuid;
  previous_warehouse_id uuid;
  introduces_blocker boolean := false;
  next_is_blocker boolean := false;
  previous_is_blocker boolean := false;
BEGIN
  target_warehouse_id := NULLIF(next_row ->> 'warehouse_id', '')::uuid;

  -- A stable operation marker replay is not a new blocker; its immutable identity is checked by
  -- the application store after this trigger and ON CONFLICT remains a no-op.
  IF guard_kind = 'OPERATION_MARK' THEN
    IF TG_OP = 'INSERT'
        AND EXISTS (
          SELECT 1
            FROM public.warehouse_operation_mark_outbox existing
           WHERE existing.warehouse_id = target_warehouse_id
             AND existing.operation_id = (next_row ->> 'operation_id')::uuid) THEN
      RETURN NEW;
    END IF;
  END IF;

  CASE guard_kind
    WHEN 'ESTIMATE' THEN
      next_is_blocker := next_row ->> 'state' <> 'COMPLETED';
    WHEN 'REPAIR' THEN
      next_is_blocker := NOT (
        next_row ->> 'execution_state' = 'CANCELLED'
        OR next_row ->> 'acceptance_state' IN ('ACCEPTED', 'WRITTEN_OFF'));
    WHEN 'DISPOSITION' THEN
      next_is_blocker := next_row ->> 'state' NOT IN ('EFFECTIVE', 'REJECTED');
    WHEN 'FURNITURE_LINK' THEN
      next_is_blocker := next_row ->> 'state' NOT IN ('CONFIRMED', 'ABANDONED');
    WHEN 'OPERATION_MARK' THEN
      next_is_blocker := next_row ->> 'state' <> 'CONFIRMED';
    WHEN 'ALLOCATION' THEN
      next_is_blocker := next_row ->> 'state' <> 'RELEASED';
    WHEN 'CATALOG' THEN
      next_is_blocker := true;
    ELSE
      RAISE EXCEPTION 'unsupported maintenance readiness guard kind %', guard_kind;
  END CASE;

  IF TG_OP = 'INSERT' THEN
    introduces_blocker := next_is_blocker;
  ELSE
    previous_row := to_jsonb(OLD);
    previous_warehouse_id := NULLIF(previous_row ->> 'warehouse_id', '')::uuid;
    CASE guard_kind
      WHEN 'ESTIMATE' THEN
        previous_is_blocker := previous_row ->> 'state' <> 'COMPLETED';
      WHEN 'REPAIR' THEN
        previous_is_blocker := NOT (
          previous_row ->> 'execution_state' = 'CANCELLED'
          OR previous_row ->> 'acceptance_state' IN ('ACCEPTED', 'WRITTEN_OFF'));
      WHEN 'DISPOSITION' THEN
        previous_is_blocker := previous_row ->> 'state' NOT IN ('EFFECTIVE', 'REJECTED');
      WHEN 'FURNITURE_LINK' THEN
        previous_is_blocker := previous_row ->> 'state' NOT IN ('CONFIRMED', 'ABANDONED');
      WHEN 'OPERATION_MARK' THEN
        previous_is_blocker := previous_row ->> 'state' <> 'CONFIRMED';
      WHEN 'ALLOCATION' THEN
        previous_is_blocker := previous_row ->> 'state' <> 'RELEASED';
      WHEN 'CATALOG' THEN
        previous_is_blocker := false;
    END CASE;
    introduces_blocker := target_warehouse_id IS DISTINCT FROM previous_warehouse_id
      OR (next_is_blocker AND NOT previous_is_blocker);
  END IF;

  -- Existing work may continue changing while it drains. Only a new blocker, terminal-to-active
  -- transition, warehouse move or catalog mutation participates in the commit fence.
  IF NOT introduces_blocker THEN
    RETURN NEW;
  END IF;

  PERFORM pg_advisory_xact_lock(
    hashtextextended('maintenance:warehouse-readiness:' || target_warehouse_id::text, 0));

  IF EXISTS (
    SELECT 1
      FROM public.warehouse_readiness_fence fence
     WHERE fence.warehouse_id = target_warehouse_id
       AND fence.state IN ('FENCED', 'SEALED')) THEN
    RAISE EXCEPTION 'maintenance warehouse readiness fence rejects a new blocker for %',
      target_warehouse_id
      USING ERRCODE = '23514',
            CONSTRAINT = 'ck_maintenance_warehouse_readiness_fence_guard';
  END IF;
  RETURN NEW;
END;
$$;

CREATE FUNCTION public.guard_maintenance_disposition_claim_readiness()
RETURNS trigger
LANGUAGE plpgsql
AS $$
DECLARE
  target_warehouse_id uuid;
  introduces_blocker boolean := false;
BEGIN
  IF TG_OP = 'INSERT' THEN
    introduces_blocker := NEW.status <> 'COMPLETED';
  ELSE
    introduces_blocker := NEW.decision_id IS DISTINCT FROM OLD.decision_id
      OR (NEW.status <> 'COMPLETED' AND OLD.status = 'COMPLETED');
  END IF;
  IF NOT introduces_blocker THEN
    RETURN NEW;
  END IF;

  SELECT warehouse_id INTO target_warehouse_id
    FROM public.property_disposition_decision
   WHERE id = NEW.decision_id;
  IF target_warehouse_id IS NULL THEN
    RAISE EXCEPTION 'property disposition claim has no warehouse owner';
  END IF;

  PERFORM pg_advisory_xact_lock(
    hashtextextended('maintenance:warehouse-readiness:' || target_warehouse_id::text, 0));
  IF EXISTS (
    SELECT 1 FROM public.warehouse_readiness_fence
     WHERE warehouse_id = target_warehouse_id
       AND state IN ('FENCED', 'SEALED')) THEN
    RAISE EXCEPTION 'maintenance warehouse readiness fence rejects a new disposition claim for %',
      target_warehouse_id
      USING ERRCODE = '23514',
            CONSTRAINT = 'ck_maintenance_warehouse_readiness_fence_guard';
  END IF;
  RETURN NEW;
END;
$$;

CREATE FUNCTION public.maintenance_reconciliation_warehouse_ids(reconciliation jsonb)
RETURNS SETOF uuid
LANGUAGE sql
STABLE
AS $$
  SELECT DISTINCT candidate
    FROM (
      VALUES (NULLIF(reconciliation ->> 'media_warehouse_id', '')::uuid)
      UNION ALL
      SELECT repair.warehouse_id
        FROM public.maintenance_repair repair
       WHERE repair.id = NULLIF(reconciliation ->> 'repair_id', '')::uuid
      UNION ALL
      SELECT catalog.warehouse_id
        FROM public.catalog_version catalog
       WHERE catalog.id = NULLIF(reconciliation ->> 'catalog_version_id', '')::uuid
      UNION ALL
      SELECT estimate.warehouse_id
        FROM public.maintenance_estimate estimate
       WHERE estimate.id = CASE
         WHEN reconciliation -> 'response_snapshot' ->> 'estimateId'
              ~* '^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$'
           THEN (reconciliation -> 'response_snapshot' ->> 'estimateId')::uuid
         ELSE NULL
       END
      UNION ALL
      SELECT CASE
        WHEN reconciliation -> 'response_snapshot' ->> 'warehouseId'
             ~* '^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$'
          THEN (reconciliation -> 'response_snapshot' ->> 'warehouseId')::uuid
        ELSE NULL
      END
    ) resolved(candidate)
   WHERE candidate IS NOT NULL
   ORDER BY candidate;
$$;

CREATE FUNCTION public.guard_maintenance_reconciliation_readiness()
RETURNS trigger
LANGUAGE plpgsql
AS $$
DECLARE
  next_row jsonb := to_jsonb(NEW);
  previous_row jsonb;
  next_warehouses uuid[];
  previous_warehouses uuid[];
  target_warehouse_id uuid;
  introduces_blocker boolean := false;
BEGIN
  IF NEW.state NOT IN ('CONFIRMED', 'CANCELLED') THEN
    SELECT COALESCE(array_agg(value ORDER BY value), ARRAY[]::uuid[]) INTO next_warehouses
      FROM public.maintenance_reconciliation_warehouse_ids(next_row) value;
    IF TG_OP = 'INSERT' THEN
      introduces_blocker := true;
    ELSE
      previous_row := to_jsonb(OLD);
      SELECT COALESCE(array_agg(value ORDER BY value), ARRAY[]::uuid[]) INTO previous_warehouses
        FROM public.maintenance_reconciliation_warehouse_ids(previous_row) value;
      introduces_blocker := OLD.state IN ('CONFIRMED', 'CANCELLED')
        OR next_warehouses IS DISTINCT FROM previous_warehouses;
    END IF;
  END IF;
  IF NOT introduces_blocker THEN
    RETURN NEW;
  END IF;
  IF cardinality(next_warehouses) = 0 THEN
    RAISE EXCEPTION 'nonterminal maintenance reconciliation has no warehouse owner'
      USING ERRCODE = '23514',
            CONSTRAINT = 'ck_maintenance_reconciliation_warehouse_owner';
  END IF;

  FOREACH target_warehouse_id IN ARRAY next_warehouses LOOP
    PERFORM pg_advisory_xact_lock(
      hashtextextended('maintenance:warehouse-readiness:' || target_warehouse_id::text, 0));
    IF EXISTS (
      SELECT 1 FROM public.warehouse_readiness_fence
       WHERE warehouse_id = target_warehouse_id
         AND state IN ('FENCED', 'SEALED')) THEN
      RAISE EXCEPTION 'maintenance warehouse readiness fence rejects new reconciliation work for %',
        target_warehouse_id
        USING ERRCODE = '23514',
              CONSTRAINT = 'ck_maintenance_warehouse_readiness_fence_guard';
    END IF;
  END LOOP;
  RETURN NEW;
END;
$$;

CREATE TRIGGER maintenance_estimate_readiness_guard
BEFORE INSERT OR UPDATE ON public.maintenance_estimate
FOR EACH ROW
EXECUTE FUNCTION public.guard_maintenance_warehouse_readiness_fence('ESTIMATE');

CREATE TRIGGER maintenance_repair_readiness_guard
BEFORE INSERT OR UPDATE ON public.maintenance_repair
FOR EACH ROW
EXECUTE FUNCTION public.guard_maintenance_warehouse_readiness_fence('REPAIR');

CREATE TRIGGER property_disposition_readiness_guard
BEFORE INSERT OR UPDATE ON public.property_disposition_decision
FOR EACH ROW
EXECUTE FUNCTION public.guard_maintenance_warehouse_readiness_fence('DISPOSITION');

CREATE TRIGGER property_disposition_claim_readiness_guard
BEFORE INSERT OR UPDATE ON public.property_disposition_processing_claim
FOR EACH ROW
EXECUTE FUNCTION public.guard_maintenance_disposition_claim_readiness();

CREATE TRIGGER furniture_equipment_link_readiness_guard
BEFORE INSERT OR UPDATE ON public.furniture_equipment_link_intent
FOR EACH ROW
EXECUTE FUNCTION public.guard_maintenance_warehouse_readiness_fence('FURNITURE_LINK');

CREATE TRIGGER warehouse_operation_mark_readiness_guard
BEFORE INSERT OR UPDATE ON public.warehouse_operation_mark_outbox
FOR EACH ROW
EXECUTE FUNCTION public.guard_maintenance_warehouse_readiness_fence('OPERATION_MARK');

CREATE TRIGGER repair_place_allocation_readiness_guard
BEFORE INSERT OR UPDATE ON public.repair_place_allocation
FOR EACH ROW
EXECUTE FUNCTION public.guard_maintenance_warehouse_readiness_fence('ALLOCATION');

CREATE TRIGGER integration_reconciliation_readiness_guard
BEFORE INSERT OR UPDATE ON public.integration_reconciliation
FOR EACH ROW
EXECUTE FUNCTION public.guard_maintenance_reconciliation_readiness();

CREATE TRIGGER catalog_version_readiness_guard
BEFORE INSERT OR UPDATE ON public.catalog_version
FOR EACH ROW
EXECUTE FUNCTION public.guard_maintenance_warehouse_readiness_fence('CATALOG');
