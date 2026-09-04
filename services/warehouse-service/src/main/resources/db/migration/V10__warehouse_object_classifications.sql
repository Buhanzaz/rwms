-- Independent object classifications supersede the V9 production/representative role while
-- retaining its type and parent columns as compatibility projections for older consumers.
ALTER TABLE public.warehouse
  ADD COLUMN production boolean NOT NULL DEFAULT true,
  ADD COLUMN main_warehouse boolean NOT NULL DEFAULT false;

-- V9 deliberately retained historical 0,0 coordinates as a NOT VALID constraint. Recreate that
-- constraint after the backfill so this classification-only update does not reject those rows.
ALTER TABLE public.warehouse
  DROP CONSTRAINT ck_warehouse_coordinates_not_origin;

-- Existing ordinary V9 warehouses remain usable in RWMS. A representative has neither flag.
UPDATE public.warehouse
SET production = false,
    main_warehouse = (warehouse_type = 'PRODUCTION');

ALTER TABLE public.warehouse
  ADD CONSTRAINT ck_warehouse_coordinates_not_origin
    CHECK (latitude IS NULL OR latitude <> 0 OR longitude <> 0) NOT VALID,
  ADD CONSTRAINT ck_warehouse_object_classification
    CHECK (
      (production_warehouse_id IS NOT NULL AND representative = true
        AND production = false AND main_warehouse = false)
      OR
      (production_warehouse_id IS NULL AND representative = false
        AND (production = true OR main_warehouse = true))
    );

CREATE INDEX idx_warehouse_company_classification_active
  ON public.warehouse(company_id, production, main_warehouse, active, sort_order, normalized_name, id);

-- Preserve the V9 compatibility fields, but let the independent flags determine whether a
-- regular object can serve as a representative parent.
CREATE OR REPLACE FUNCTION public.synchronize_and_validate_warehouse_classification()
RETURNS trigger
LANGUAGE plpgsql
AS $$
DECLARE
  parent_company_id uuid;
  parent_production boolean;
  parent_main_warehouse boolean;
BEGIN
  IF NEW.warehouse_type IS NULL
      OR NEW.warehouse_type NOT IN ('PRODUCTION', 'REPRESENTATIVE') THEN
    RAISE EXCEPTION USING
      ERRCODE = '23514',
      MESSAGE = 'ck_warehouse_type: warehouse_type must be PRODUCTION or REPRESENTATIVE';
  END IF;

  IF NEW.production_warehouse_id IS NOT NULL AND NEW.warehouse_type = 'PRODUCTION' THEN
    RAISE EXCEPTION 'production warehouse cannot have a production parent';
  END IF;
  IF NEW.warehouse_type = 'REPRESENTATIVE' AND NEW.production_warehouse_id IS NULL THEN
    RAISE EXCEPTION 'representative warehouse requires a parent object';
  END IF;

  NEW.representative := (NEW.production_warehouse_id IS NOT NULL);
  NEW.warehouse_type := CASE
    WHEN NEW.representative THEN 'REPRESENTATIVE'
    ELSE 'PRODUCTION'
  END;

  IF TG_OP = 'UPDATE'
      AND OLD.production_warehouse_id IS NOT NULL
      AND NEW.production_warehouse_id IS NULL
      AND EXISTS (
        SELECT 1
          FROM public.warehouse_support_link
         WHERE served_warehouse_id = OLD.id) THEN
    RAISE EXCEPTION 'remove warehouse support links before clearing representative classification';
  END IF;

  IF NEW.representative THEN
    NEW.production := false;
    NEW.main_warehouse := false;
  END IF;

  IF NEW.production_warehouse_id IS NOT NULL AND NEW.production_warehouse_id = NEW.id THEN
    RAISE EXCEPTION 'representative parent cannot reference itself';
  END IF;

  IF NEW.representative THEN
    SELECT company_id, production, main_warehouse
      INTO parent_company_id, parent_production, parent_main_warehouse
     FROM public.warehouse
     WHERE id = NEW.production_warehouse_id
     FOR SHARE;

    IF NOT FOUND THEN
      RAISE EXCEPTION 'representative parent does not exist';
    END IF;
    IF parent_production IS DISTINCT FROM true
        AND parent_main_warehouse IS DISTINCT FROM true THEN
      RAISE EXCEPTION 'representative parent must be production or main';
    END IF;
    IF parent_company_id IS DISTINCT FROM NEW.company_id THEN
      RAISE EXCEPTION 'representative and parent must belong to the same company';
    END IF;
  ELSIF NOT NEW.production AND NOT NEW.main_warehouse THEN
    RAISE EXCEPTION 'object must be production, main, or representative';
  END IF;

  IF TG_OP = 'UPDATE'
      AND (OLD.company_id IS DISTINCT FROM NEW.company_id
        OR (OLD.production OR OLD.main_warehouse)
            AND NOT (NEW.production OR NEW.main_warehouse))
      AND EXISTS (
        SELECT 1
          FROM public.warehouse AS child
         WHERE child.production_warehouse_id = OLD.id
           AND (NOT (NEW.production OR NEW.main_warehouse)
             OR child.company_id <> NEW.company_id)) THEN
    RAISE EXCEPTION 'parent object company or classification is referenced by representative warehouses';
  END IF;

  IF TG_OP = 'UPDATE'
      AND OLD.company_id IS DISTINCT FROM NEW.company_id
      AND EXISTS (
        SELECT 1
          FROM public.warehouse_support_link AS link
          JOIN public.warehouse AS peer
            ON peer.id = CASE
              WHEN link.support_warehouse_id = OLD.id THEN link.served_warehouse_id
              ELSE link.support_warehouse_id
            END
         WHERE (link.support_warehouse_id = OLD.id OR link.served_warehouse_id = OLD.id)
           AND peer.company_id <> NEW.company_id) THEN
    RAISE EXCEPTION 'warehouse company cannot diverge from existing support-link endpoints';
  END IF;

  RETURN NEW;
END;
$$;

DROP TRIGGER trg_warehouse_classification ON public.warehouse;
CREATE TRIGGER trg_warehouse_classification
BEFORE INSERT OR UPDATE OF company_id, warehouse_type, production, main_warehouse, production_warehouse_id
ON public.warehouse
FOR EACH ROW
EXECUTE FUNCTION public.synchronize_and_validate_warehouse_classification();

-- Durable create replays must gain the current object classification without changing their
-- historical idempotency identity or status.
UPDATE public.idempotency_record AS record
SET response_body = record.response_body
  || jsonb_build_object(
       'production', warehouse.production,
       'mainWarehouse', warehouse.main_warehouse,
       'representativeParentWarehouseId', warehouse.production_warehouse_id)
FROM public.warehouse AS warehouse
WHERE warehouse.id = record.warehouse_id;
