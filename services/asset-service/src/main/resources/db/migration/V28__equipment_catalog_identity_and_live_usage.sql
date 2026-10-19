ALTER TABLE public.equipment_catalog_item
  ADD COLUMN normalized_name varchar(255);

UPDATE public.equipment_catalog_item
SET normalized_name = lower(regexp_replace(btrim(name), '[[:space:]]+', ' ', 'g'));

DO $$
DECLARE
  duplicate_names text;
BEGIN
  SELECT string_agg(normalized_name, ', ' ORDER BY normalized_name)
    INTO duplicate_names
    FROM (
      SELECT normalized_name
      FROM public.equipment_catalog_item
      GROUP BY normalized_name
      HAVING count(*) > 1
      ORDER BY normalized_name
      LIMIT 20
    ) duplicates;
  IF duplicate_names IS NOT NULL THEN
    RAISE EXCEPTION
      'Equipment display names must be unique after normalization; resolve duplicates first: %',
      duplicate_names;
  END IF;
END
$$;

ALTER TABLE public.equipment_catalog_item
  ALTER COLUMN normalized_name SET NOT NULL,
  ADD CONSTRAINT ck_equipment_catalog_normalized_name_nonblank
    CHECK (length(btrim(normalized_name)) BETWEEN 1 AND 255),
  ADD CONSTRAINT uk_equipment_catalog_normalized_name UNIQUE (normalized_name);

CREATE INDEX idx_equipment_balance_live_catalog
  ON public.equipment_balance(equipment_id, location_kind)
  WHERE quantity > 0;

CREATE INDEX idx_equipment_hold_live_catalog
  ON public.equipment_allocation_hold(equipment_id, state, expires_at);
