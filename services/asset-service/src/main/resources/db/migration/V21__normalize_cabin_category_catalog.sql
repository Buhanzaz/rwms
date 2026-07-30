-- Categories participate in the same UUID-backed mapping workflow as the
-- other cabin passport values. The legacy text remains during the expand
-- phase as a denormalized label for existing consumers.
ALTER TABLE public.cabin_catalog_item
  DROP CONSTRAINT ck_cabin_catalog_item_kind;

ALTER TABLE public.cabin_catalog_item
  ADD CONSTRAINT ck_cabin_catalog_item_kind CHECK (
    kind IN ('TYPE', 'DIMENSION', 'FINISHING', 'CATEGORY', 'CHARACTERISTIC'));

INSERT INTO public.cabin_catalog_item(
  id, version, kind, name, name_normalized, active, created_at, updated_at)
VALUES
  ('af57f2b0-3a71-4b7f-8d2f-000000000401', 0, 'CATEGORY', 'Новая', 'новая', true, clock_timestamp(), clock_timestamp()),
  ('af57f2b0-3a71-4b7f-8d2f-000000000402', 0, 'CATEGORY', 'ИТР', 'итр', true, clock_timestamp(), clock_timestamp()),
  ('af57f2b0-3a71-4b7f-8d2f-000000000403', 0, 'CATEGORY', 'Обычная', 'обычная', true, clock_timestamp(), clock_timestamp()),
  ('af57f2b0-3a71-4b7f-8d2f-000000000404', 0, 'CATEGORY', 'Санблок', 'санблок', true, clock_timestamp(), clock_timestamp())
ON CONFLICT (kind, name_normalized) DO NOTHING;

INSERT INTO public.cabin_catalog_item(
  id, version, kind, name, name_normalized, active, created_at, updated_at)
SELECT gen_random_uuid(), 0, 'CATEGORY', source.name, lower(source.name), true,
       clock_timestamp(), clock_timestamp()
FROM (
  SELECT DISTINCT btrim(category) AS name
  FROM public.rental_item
  WHERE nullif(btrim(category), '') IS NOT NULL
) AS source
ON CONFLICT (kind, name_normalized) DO NOTHING;

ALTER TABLE public.rental_item
  ADD COLUMN cabin_category_id uuid;

UPDATE public.rental_item AS item
SET cabin_category_id = catalog.id
FROM public.cabin_catalog_item AS catalog
WHERE catalog.kind = 'CATEGORY'
  AND catalog.name_normalized = lower(btrim(item.category));

ALTER TABLE public.rental_item
  ADD CONSTRAINT fk_rental_item_cabin_category FOREIGN KEY (cabin_category_id)
    REFERENCES public.cabin_catalog_item(id);

CREATE INDEX idx_rental_item_cabin_category
  ON public.rental_item(cabin_category_id);
