-- Warehouses are company-owned. The initial company UUID is shared with auth-service's
-- additive adoption migration, but warehouse-service deliberately has no cross-database FK.
-- Warehouse type models production responsibility; directed support links remain an
-- independent many-to-many logistics capability graph.
ALTER TABLE public.warehouse
  ADD COLUMN company_id uuid,
  ADD COLUMN warehouse_type varchar(32),
  ADD COLUMN production_warehouse_id uuid;

UPDATE public.warehouse
SET company_id = 'ae0d6f97-f0c5-576a-9ea7-1ddcc1a03b48'::uuid,
    warehouse_type = CASE
      WHEN representative THEN 'REPRESENTATIVE'
      ELSE 'PRODUCTION'
    END;

-- An existing representative can acquire a production parent only from deterministic
-- historical evidence. Do not guess when the support graph has no unique production source.
DO $$
DECLARE
  unresolved_representatives text;
BEGIN
  SELECT string_agg(
           representative.id::text || ' (' || eligible_source_count::text || ' eligible sources)',
           ', '
           ORDER BY representative.id)
    INTO unresolved_representatives
  FROM (
    SELECT served.id,
           count(link.support_warehouse_id) FILTER (WHERE support.id IS NOT NULL)
             AS eligible_source_count
      FROM public.warehouse AS served
      LEFT JOIN public.warehouse_support_link AS link
        ON link.served_warehouse_id = served.id
      LEFT JOIN public.warehouse AS support
        ON support.id = link.support_warehouse_id
       AND support.representative = false
     WHERE served.representative = true
     GROUP BY served.id
    HAVING count(link.support_warehouse_id) FILTER (WHERE support.id IS NOT NULL) <> 1
  ) AS representative;

  IF unresolved_representatives IS NOT NULL THEN
    RAISE EXCEPTION USING
      ERRCODE = '23514',
      MESSAGE = 'warehouse company/type migration failed: representative warehouse requires exactly one eligible production source',
      DETAIL = 'Unresolved representatives: ' || unresolved_representatives,
      HINT = 'Create exactly one support link from a non-representative warehouse for each representative, then retry the migration.';
  END IF;
END;
$$;

UPDATE public.warehouse AS served
SET production_warehouse_id = (
  SELECT link.support_warehouse_id
    FROM public.warehouse_support_link AS link
    JOIN public.warehouse AS support
      ON support.id = link.support_warehouse_id
     AND support.representative = false
   WHERE link.served_warehouse_id = served.id)
WHERE served.representative = true;

ALTER TABLE public.warehouse
  ALTER COLUMN company_id SET NOT NULL,
  ALTER COLUMN warehouse_type SET NOT NULL,
  ADD CONSTRAINT ck_warehouse_type
    CHECK (warehouse_type IN ('PRODUCTION', 'REPRESENTATIVE')),
  ADD CONSTRAINT ck_warehouse_type_parent
    CHECK (
      (warehouse_type = 'PRODUCTION' AND production_warehouse_id IS NULL)
      OR
      (warehouse_type = 'REPRESENTATIVE' AND production_warehouse_id IS NOT NULL)
    ),
  ADD CONSTRAINT ck_warehouse_production_parent_not_self
    CHECK (production_warehouse_id IS NULL OR production_warehouse_id <> id),
  ADD CONSTRAINT fk_warehouse_production_parent
    FOREIGN KEY (production_warehouse_id) REFERENCES public.warehouse(id) ON DELETE RESTRICT,
  ADD CONSTRAINT ck_warehouse_representative_projection
    CHECK (representative = (warehouse_type = 'REPRESENTATIVE')),
  ADD CONSTRAINT ck_warehouse_coordinates_not_origin
    CHECK (latitude IS NULL OR latitude <> 0 OR longitude <> 0) NOT VALID;

ALTER TABLE public.warehouse
  DROP CONSTRAINT uk_warehouse_normalized_name,
  ADD CONSTRAINT uk_warehouse_company_normalized_name UNIQUE (company_id, normalized_name);

CREATE INDEX idx_warehouse_company_type_active
  ON public.warehouse(company_id, warehouse_type, active, sort_order, normalized_name, id);

CREATE INDEX idx_warehouse_production_parent
  ON public.warehouse(production_warehouse_id, active, id)
  WHERE production_warehouse_id IS NOT NULL;

-- Keep the legacy representative column as a write-through compatibility projection while
-- warehouse_type becomes the authoritative classification.
CREATE FUNCTION public.synchronize_and_validate_warehouse_classification()
RETURNS trigger
LANGUAGE plpgsql
AS $$
DECLARE
  parent_company_id uuid;
  parent_type varchar(32);
BEGIN
  NEW.representative := (NEW.warehouse_type = 'REPRESENTATIVE');

  IF NEW.production_warehouse_id IS NOT NULL AND NEW.production_warehouse_id = NEW.id THEN
    RAISE EXCEPTION 'production warehouse parent cannot reference itself';
  END IF;

  IF NEW.warehouse_type = 'PRODUCTION' AND NEW.production_warehouse_id IS NOT NULL THEN
    RAISE EXCEPTION 'production warehouse cannot have a production parent';
  END IF;

  IF NEW.warehouse_type = 'REPRESENTATIVE' THEN
    IF NEW.production_warehouse_id IS NULL THEN
      RAISE EXCEPTION 'representative warehouse requires a production parent';
    END IF;

    SELECT company_id, warehouse_type
      INTO parent_company_id, parent_type
     FROM public.warehouse
     WHERE id = NEW.production_warehouse_id
     FOR SHARE;

    IF NOT FOUND THEN
      RAISE EXCEPTION 'production warehouse parent does not exist';
    END IF;
    IF parent_type IS DISTINCT FROM 'PRODUCTION' THEN
      RAISE EXCEPTION 'representative parent must be a production warehouse';
    END IF;
    IF parent_company_id IS DISTINCT FROM NEW.company_id THEN
      RAISE EXCEPTION 'representative and production parent must belong to the same company';
    END IF;
  END IF;

  IF TG_OP = 'UPDATE'
      AND OLD.warehouse_type = 'REPRESENTATIVE'
      AND NEW.warehouse_type <> 'REPRESENTATIVE'
      AND EXISTS (
        SELECT 1
          FROM public.warehouse_support_link
         WHERE served_warehouse_id = OLD.id) THEN
    RAISE EXCEPTION 'remove warehouse support links before changing representative warehouse type';
  END IF;

  IF TG_OP = 'UPDATE'
      AND (OLD.company_id IS DISTINCT FROM NEW.company_id
        OR OLD.warehouse_type IS DISTINCT FROM NEW.warehouse_type)
      AND EXISTS (
        SELECT 1
          FROM public.warehouse AS child
         WHERE child.production_warehouse_id = OLD.id
           AND (NEW.warehouse_type <> 'PRODUCTION' OR child.company_id <> NEW.company_id)) THEN
    RAISE EXCEPTION 'production warehouse company or type is referenced by representative warehouses';
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

CREATE TRIGGER trg_warehouse_classification
BEFORE INSERT OR UPDATE OF company_id, warehouse_type, production_warehouse_id
ON public.warehouse
FOR EACH ROW
EXECUTE FUNCTION public.synchronize_and_validate_warehouse_classification();

-- Preserve every V8 capability/calendar rule and add the tenant boundary at the endpoint.
CREATE OR REPLACE FUNCTION public.validate_warehouse_support_link_endpoints()
RETURNS trigger
LANGUAGE plpgsql
AS $$
DECLARE
  support_active boolean;
  support_company_id uuid;
  served_active boolean;
  served_representative boolean;
  served_company_id uuid;
BEGIN
  PERFORM 1
    FROM public.warehouse
   WHERE id IN (NEW.support_warehouse_id, NEW.served_warehouse_id)
   ORDER BY id
   FOR SHARE;

  SELECT active, company_id
    INTO support_active, support_company_id
    FROM public.warehouse
   WHERE id = NEW.support_warehouse_id;
  SELECT active, representative, company_id
    INTO served_active, served_representative, served_company_id
    FROM public.warehouse
   WHERE id = NEW.served_warehouse_id;
  IF support_active IS DISTINCT FROM true THEN
    RAISE EXCEPTION 'support warehouse must be active';
  END IF;
  IF served_active IS DISTINCT FROM true OR served_representative IS DISTINCT FROM true THEN
    RAISE EXCEPTION 'served warehouse must be active and representative';
  END IF;
  IF support_company_id IS DISTINCT FROM served_company_id THEN
    RAISE EXCEPTION 'warehouse support-link endpoints must belong to the same company';
  END IF;
  RETURN NEW;
END;
$$;

-- Durable create replays are part of the public response boundary and must project the
-- current company and warehouse classification after an additive upgrade.
UPDATE public.idempotency_record AS record
SET response_body = record.response_body
  || jsonb_build_object(
       'companyId', warehouse.company_id,
       'warehouseType', warehouse.warehouse_type,
       'productionWarehouseId', warehouse.production_warehouse_id)
FROM public.warehouse AS warehouse
WHERE warehouse.id = record.warehouse_id;
