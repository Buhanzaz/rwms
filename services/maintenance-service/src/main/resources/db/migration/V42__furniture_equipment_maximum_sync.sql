ALTER TABLE public.furniture_equipment_link_intent
  ADD COLUMN requested_equipment_version bigint,
  ADD COLUMN requested_maximum_per_cabin integer,
  ADD COLUMN equipment_version bigint,
  ADD COLUMN maximum_per_cabin integer,
  ADD COLUMN observed_equipment_version bigint,
  ADD COLUMN observed_maximum_per_cabin integer;

UPDATE public.furniture_equipment_link_intent
SET equipment_version = 0,
    observed_equipment_version = 0
WHERE state = 'CONFIRMED';

ALTER TABLE public.furniture_equipment_link_intent
  ADD CONSTRAINT ck_furniture_link_equipment_versions
    CHECK (
      (requested_equipment_version IS NULL OR requested_equipment_version >= 0)
      AND (equipment_version IS NULL OR equipment_version >= 0)
      AND (observed_equipment_version IS NULL OR observed_equipment_version >= 0)),
  ADD CONSTRAINT ck_furniture_link_maximum_per_cabin
    CHECK (
      (requested_maximum_per_cabin IS NULL OR requested_maximum_per_cabin > 0)
      AND (maximum_per_cabin IS NULL OR maximum_per_cabin > 0)
      AND (observed_maximum_per_cabin IS NULL OR observed_maximum_per_cabin > 0));

ALTER TABLE public.furniture_equipment_link_intent
  DROP CONSTRAINT ck_furniture_link_confirmation,
  ADD CONSTRAINT ck_furniture_link_confirmation CHECK (
    (state = 'CONFIRMED'
      AND equipment_id IS NOT NULL
      AND equipment_name IS NOT NULL
      AND btrim(equipment_name) <> ''
      AND equipment_version IS NOT NULL
      AND confirmed_at IS NOT NULL)
    OR (state <> 'CONFIRMED'
      AND equipment_id IS NULL
      AND equipment_name IS NULL
      AND equipment_version IS NULL
      AND maximum_per_cabin IS NULL
      AND confirmed_at IS NULL));
