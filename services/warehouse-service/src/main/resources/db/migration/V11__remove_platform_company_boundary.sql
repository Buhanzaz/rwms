DO $$
BEGIN
  IF (
    SELECT count(DISTINCT company_id)
      FROM public.warehouse
     WHERE company_id IS NOT NULL
  ) > 1 THEN
    RAISE EXCEPTION
      'V11 cannot remove the warehouse company boundary from a multi-company database';
  END IF;
END;
$$;

DROP TRIGGER trg_warehouse_classification ON public.warehouse;
DROP TRIGGER trg_warehouse_representative_support_links ON public.warehouse;

DROP FUNCTION public.synchronize_and_validate_warehouse_classification();
DROP FUNCTION public.prevent_representative_clear_with_support_links();

ALTER TABLE public.warehouse
  DROP CONSTRAINT ck_warehouse_type,
  DROP CONSTRAINT ck_warehouse_type_parent,
  DROP CONSTRAINT ck_warehouse_production_parent_not_self,
  DROP CONSTRAINT fk_warehouse_production_parent,
  DROP CONSTRAINT ck_warehouse_representative_projection,
  DROP CONSTRAINT ck_warehouse_object_classification,
  DROP CONSTRAINT uk_warehouse_company_normalized_name;

DROP INDEX public.idx_warehouse_company_type_active;
DROP INDEX public.idx_warehouse_company_classification_active;
DROP INDEX public.idx_warehouse_production_parent;

ALTER TABLE public.warehouse
  RENAME COLUMN production_warehouse_id TO representative_parent_warehouse_id;

CREATE OR REPLACE FUNCTION public.validate_warehouse_support_link_endpoints()
RETURNS trigger
LANGUAGE plpgsql
AS $$
DECLARE
  support_active boolean;
  served_active boolean;
  served_representative boolean;
BEGIN
  PERFORM 1
    FROM public.warehouse
   WHERE id IN (NEW.support_warehouse_id, NEW.served_warehouse_id)
   ORDER BY id
   FOR SHARE;

  SELECT active
    INTO support_active
    FROM public.warehouse
   WHERE id = NEW.support_warehouse_id;
  SELECT active, representative_parent_warehouse_id IS NOT NULL
    INTO served_active, served_representative
    FROM public.warehouse
   WHERE id = NEW.served_warehouse_id;

  IF support_active IS DISTINCT FROM true THEN
    RAISE EXCEPTION 'support warehouse must be active';
  END IF;
  IF served_active IS DISTINCT FROM true OR served_representative IS DISTINCT FROM true THEN
    RAISE EXCEPTION 'served warehouse must be active and representative';
  END IF;
  RETURN NEW;
END;
$$;

ALTER TABLE public.warehouse
  DROP COLUMN warehouse_type,
  DROP COLUMN company_id,
  DROP COLUMN representative;

ALTER TABLE public.warehouse
  ADD CONSTRAINT uk_warehouse_normalized_name UNIQUE (normalized_name),
  ADD CONSTRAINT fk_warehouse_representative_parent
    FOREIGN KEY (representative_parent_warehouse_id) REFERENCES public.warehouse(id) ON DELETE RESTRICT,
  ADD CONSTRAINT ck_warehouse_representative_parent_not_self
    CHECK (
      representative_parent_warehouse_id IS NULL
      OR representative_parent_warehouse_id <> id
    ),
  ADD CONSTRAINT ck_warehouse_object_classification
    CHECK (
      (representative_parent_warehouse_id IS NOT NULL
        AND production = false
        AND main_warehouse = false)
      OR
      (representative_parent_warehouse_id IS NULL
        AND (production = true OR main_warehouse = true))
    );

CREATE INDEX idx_warehouse_classification_active
  ON public.warehouse(production, main_warehouse, active, sort_order, normalized_name, id);

CREATE INDEX idx_warehouse_representative_parent
  ON public.warehouse(representative_parent_warehouse_id, active, id)
  WHERE representative_parent_warehouse_id IS NOT NULL;

CREATE FUNCTION public.synchronize_and_validate_warehouse_classification()
RETURNS trigger
LANGUAGE plpgsql
AS $$
DECLARE
  parent_production boolean;
  parent_main_warehouse boolean;
BEGIN
  IF NEW.representative_parent_warehouse_id IS NOT NULL
      AND NEW.representative_parent_warehouse_id = NEW.id THEN
    RAISE EXCEPTION 'representative parent cannot reference itself';
  END IF;

  IF NEW.representative_parent_warehouse_id IS NOT NULL THEN
    NEW.production := false;
    NEW.main_warehouse := false;

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

CREATE TRIGGER trg_warehouse_classification
BEFORE INSERT OR UPDATE OF production, main_warehouse, representative_parent_warehouse_id
ON public.warehouse
FOR EACH ROW
EXECUTE FUNCTION public.synchronize_and_validate_warehouse_classification();

UPDATE public.idempotency_record AS record
SET response_body =
      (record.response_body - 'companyId' - 'warehouseType' - 'productionWarehouseId')
      || jsonb_build_object(
           'representative', warehouse.representative_parent_warehouse_id IS NOT NULL,
           'production', warehouse.production,
           'mainWarehouse', warehouse.main_warehouse,
           'representativeParentWarehouseId', warehouse.representative_parent_warehouse_id)
FROM public.warehouse AS warehouse
WHERE warehouse.id = record.warehouse_id;
