-- Representative warehouses belong to this installation and reference one responsible local
-- warehouse. Directed support links remain an independent many-to-many logistics capability.
ALTER TABLE public.warehouse
  ADD COLUMN representative_parent_warehouse_id uuid;

-- An existing representative can acquire a parent only from deterministic historical evidence.
-- Do not guess when the support graph has no unique non-representative source.
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
      MESSAGE = 'warehouse hierarchy migration failed: representative warehouse requires exactly one eligible parent',
      DETAIL = 'Unresolved representatives: ' || unresolved_representatives,
      HINT = 'Create exactly one support link from a non-representative warehouse for each representative, then retry the migration.';
  END IF;
END;
$$;

UPDATE public.warehouse AS served
SET representative_parent_warehouse_id = (
  SELECT link.support_warehouse_id
    FROM public.warehouse_support_link AS link
    JOIN public.warehouse AS support
      ON support.id = link.support_warehouse_id
     AND support.representative = false
   WHERE link.served_warehouse_id = served.id)
WHERE served.representative = true;

ALTER TABLE public.warehouse
  ADD CONSTRAINT ck_warehouse_representative_parent_not_self
    CHECK (representative_parent_warehouse_id IS NULL OR representative_parent_warehouse_id <> id),
  ADD CONSTRAINT fk_warehouse_representative_parent
    FOREIGN KEY (representative_parent_warehouse_id)
      REFERENCES public.warehouse(id) ON DELETE RESTRICT,
  ADD CONSTRAINT ck_warehouse_representative_projection
    CHECK (representative = (representative_parent_warehouse_id IS NOT NULL)),
  ADD CONSTRAINT ck_warehouse_coordinates_not_origin
    CHECK (latitude IS NULL OR latitude <> 0 OR longitude <> 0) NOT VALID;

CREATE INDEX idx_warehouse_representative_parent
  ON public.warehouse(representative_parent_warehouse_id, active, id)
  WHERE representative_parent_warehouse_id IS NOT NULL;

CREATE FUNCTION public.synchronize_and_validate_warehouse_classification()
RETURNS trigger
LANGUAGE plpgsql
AS $$
DECLARE
  parent_representative boolean;
BEGIN
  NEW.representative := (NEW.representative_parent_warehouse_id IS NOT NULL);

  IF NEW.representative_parent_warehouse_id = NEW.id THEN
    RAISE EXCEPTION 'representative parent cannot reference itself';
  END IF;

  IF NEW.representative THEN
    SELECT representative
      INTO parent_representative
      FROM public.warehouse
     WHERE id = NEW.representative_parent_warehouse_id
     FOR SHARE;
    IF NOT FOUND THEN
      RAISE EXCEPTION 'representative parent does not exist';
    END IF;
    IF parent_representative IS DISTINCT FROM false THEN
      RAISE EXCEPTION 'representative parent must be a regular warehouse';
    END IF;
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
      AND OLD.representative = false
      AND NEW.representative = true
      AND EXISTS (
        SELECT 1
          FROM public.warehouse AS child
         WHERE child.representative_parent_warehouse_id = OLD.id) THEN
    RAISE EXCEPTION 'representative parent is referenced by other warehouses';
  END IF;

  RETURN NEW;
END;
$$;

CREATE TRIGGER trg_warehouse_classification
BEFORE INSERT OR UPDATE OF representative, representative_parent_warehouse_id
ON public.warehouse
FOR EACH ROW
EXECUTE FUNCTION public.synchronize_and_validate_warehouse_classification();
