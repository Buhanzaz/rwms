-- A catalog's warehouse_id and a furniture-link intent's warehouse_id are retained audit
-- provenance. They are not operational warehouse ownership: the catalog, its routing queue
-- reconciliation and asset equipment mapping are installation-wide.

DROP TRIGGER IF EXISTS catalog_version_readiness_guard ON public.catalog_version;
DROP TRIGGER IF EXISTS furniture_equipment_link_readiness_guard
  ON public.furniture_equipment_link_intent;

CREATE OR REPLACE FUNCTION public.maintenance_reconciliation_warehouse_ids(reconciliation jsonb)
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

CREATE OR REPLACE FUNCTION public.guard_maintenance_reconciliation_readiness()
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
    IF NEW.dependency_type = 'TASK_BOARD'
        AND NEW.operation_type IN ('REGISTER_CATALOG_POSITION', 'DELETE_CATALOG_POSITION')
        AND NEW.catalog_version_id IS NOT NULL THEN
      RETURN NEW;
    END IF;
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
