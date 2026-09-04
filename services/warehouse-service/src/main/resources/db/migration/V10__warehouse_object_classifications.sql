-- Independent classifications distinguish production objects, primary RWMS warehouses and
-- representative warehouses without introducing an organization/tenant dimension.
ALTER TABLE public.warehouse
  ADD COLUMN production boolean NOT NULL DEFAULT true,
  ADD COLUMN main_warehouse boolean NOT NULL DEFAULT false;

-- Existing ordinary warehouses remain primary RWMS warehouses. Representatives have neither
-- independent flag and continue to reference their responsible parent.
UPDATE public.warehouse
SET production = false,
    main_warehouse = NOT representative;

ALTER TABLE public.warehouse
  DROP CONSTRAINT ck_warehouse_coordinates_not_origin;

ALTER TABLE public.warehouse
  ADD CONSTRAINT ck_warehouse_coordinates_not_origin
    CHECK (latitude IS NULL OR latitude <> 0 OR longitude <> 0) NOT VALID,
  ADD CONSTRAINT ck_warehouse_object_classification
    CHECK (
      (representative_parent_warehouse_id IS NOT NULL AND representative = true
        AND production = false AND main_warehouse = false)
      OR
      (representative_parent_warehouse_id IS NULL AND representative = false
        AND (production = true OR main_warehouse = true))
    );

CREATE INDEX idx_warehouse_classification_active
  ON public.warehouse(production, main_warehouse, active, sort_order, normalized_name, id);

CREATE OR REPLACE FUNCTION public.synchronize_and_validate_warehouse_classification()
RETURNS trigger
LANGUAGE plpgsql
AS $$
DECLARE
  parent_production boolean;
  parent_main_warehouse boolean;
BEGIN
  NEW.representative := (NEW.representative_parent_warehouse_id IS NOT NULL);

  IF NEW.representative THEN
    NEW.production := false;
    NEW.main_warehouse := false;
  END IF;

  IF NEW.representative_parent_warehouse_id = NEW.id THEN
    RAISE EXCEPTION 'representative parent cannot reference itself';
  END IF;

  IF NEW.representative THEN
    SELECT production, main_warehouse
      INTO parent_production, parent_main_warehouse
      FROM public.warehouse
     WHERE id = NEW.representative_parent_warehouse_id
     FOR SHARE;

    IF NOT FOUND THEN
      RAISE EXCEPTION 'representative parent does not exist';
    END IF;
    IF parent_production IS DISTINCT FROM true
        AND parent_main_warehouse IS DISTINCT FROM true THEN
      RAISE EXCEPTION 'representative parent must be production or main';
    END IF;
  ELSIF NOT NEW.production AND NOT NEW.main_warehouse THEN
    RAISE EXCEPTION 'object must be production, main, or representative';
  END IF;

  IF TG_OP = 'UPDATE'
      AND OLD.representative_parent_warehouse_id IS NOT NULL
      AND NEW.representative_parent_warehouse_id IS NULL
      AND EXISTS (
        SELECT 1
          FROM public.warehouse_support_link
         WHERE served_warehouse_id = OLD.id) THEN
    RAISE EXCEPTION 'remove warehouse support links before clearing representative classification';
  END IF;

  IF TG_OP = 'UPDATE'
      AND (OLD.production OR OLD.main_warehouse)
      AND NOT (NEW.production OR NEW.main_warehouse)
      AND EXISTS (
        SELECT 1
          FROM public.warehouse AS child
         WHERE child.representative_parent_warehouse_id = OLD.id) THEN
    RAISE EXCEPTION 'parent object classification is referenced by representative warehouses';
  END IF;

  RETURN NEW;
END;
$$;

DROP TRIGGER trg_warehouse_classification ON public.warehouse;
CREATE TRIGGER trg_warehouse_classification
BEFORE INSERT OR UPDATE OF representative, production, main_warehouse, representative_parent_warehouse_id
ON public.warehouse
FOR EACH ROW
EXECUTE FUNCTION public.synchronize_and_validate_warehouse_classification();

-- Durable create replays gain current classifications without changing idempotency identity.
UPDATE public.idempotency_record AS record
SET response_body = record.response_body
  || jsonb_build_object(
       'production', warehouse.production,
       'mainWarehouse', warehouse.main_warehouse,
       'representativeParentWarehouseId', warehouse.representative_parent_warehouse_id)
FROM public.warehouse AS warehouse
WHERE warehouse.id = record.warehouse_id;
