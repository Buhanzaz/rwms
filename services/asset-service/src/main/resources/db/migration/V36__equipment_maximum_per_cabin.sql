ALTER TABLE public.equipment_catalog_item
  ADD COLUMN maximum_per_cabin integer;

ALTER TABLE public.equipment_catalog_item
  ADD CONSTRAINT ck_equipment_catalog_maximum_per_cabin
    CHECK (maximum_per_cabin IS NULL OR maximum_per_cabin > 0);
