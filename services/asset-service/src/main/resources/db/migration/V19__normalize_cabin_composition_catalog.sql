-- Cabin passport values are catalog identities, not free text.  A selected
-- characteristic occupies its own relation row so "door, conditioner" can
-- never become one artificial characteristic again.
CREATE TABLE public.cabin_catalog_item (
  id uuid NOT NULL,
  version bigint NOT NULL DEFAULT 0,
  kind varchar(32) NOT NULL,
  name varchar(255) NOT NULL,
  name_normalized varchar(255) NOT NULL,
  active boolean NOT NULL DEFAULT true,
  created_at timestamptz NOT NULL,
  updated_at timestamptz NOT NULL,
  CONSTRAINT cabin_catalog_item_pkey PRIMARY KEY (id),
  CONSTRAINT uk_cabin_catalog_item_kind_name UNIQUE (kind, name_normalized),
  CONSTRAINT ck_cabin_catalog_item_version CHECK (version >= 0),
  CONSTRAINT ck_cabin_catalog_item_kind CHECK (
    kind IN ('TYPE', 'DIMENSION', 'FINISHING', 'CHARACTERISTIC')),
  CONSTRAINT ck_cabin_catalog_item_name CHECK (
    length(btrim(name)) BETWEEN 1 AND 255
    AND name_normalized = lower(btrim(name)))
);
CREATE INDEX idx_cabin_catalog_item_kind_active_name
  ON public.cabin_catalog_item(kind, active, name, id);

CREATE TABLE public.cabin_type_dimension (
  id uuid NOT NULL,
  version bigint NOT NULL DEFAULT 0,
  cabin_type_id uuid NOT NULL,
  dimension_id uuid NOT NULL,
  sort_order integer NOT NULL,
  created_at timestamptz NOT NULL,
  updated_at timestamptz NOT NULL,
  CONSTRAINT cabin_type_dimension_pkey PRIMARY KEY (id),
  CONSTRAINT uk_cabin_type_dimension UNIQUE (cabin_type_id, dimension_id),
  CONSTRAINT ck_cabin_type_dimension_version CHECK (version >= 0),
  CONSTRAINT ck_cabin_type_dimension_order CHECK (sort_order >= 0),
  CONSTRAINT fk_cabin_type_dimension_type FOREIGN KEY (cabin_type_id)
    REFERENCES public.cabin_catalog_item(id),
  CONSTRAINT fk_cabin_type_dimension_dimension FOREIGN KEY (dimension_id)
    REFERENCES public.cabin_catalog_item(id)
);
CREATE INDEX idx_cabin_type_dimension_type_order
  ON public.cabin_type_dimension(cabin_type_id, sort_order, id);

CREATE TABLE public.rental_item_characteristic (
  id uuid NOT NULL,
  rental_item_id uuid NOT NULL,
  characteristic_id uuid NOT NULL,
  sort_order integer NOT NULL,
  CONSTRAINT rental_item_characteristic_pkey PRIMARY KEY (id),
  CONSTRAINT uk_rental_item_characteristic UNIQUE (rental_item_id, characteristic_id),
  CONSTRAINT ck_rental_item_characteristic_order CHECK (sort_order >= 0),
  CONSTRAINT fk_rental_item_characteristic_item FOREIGN KEY (rental_item_id)
    REFERENCES public.rental_item(id),
  CONSTRAINT fk_rental_item_characteristic_catalog FOREIGN KEY (characteristic_id)
    REFERENCES public.cabin_catalog_item(id)
);
CREATE INDEX idx_rental_item_characteristic_item_order
  ON public.rental_item_characteristic(rental_item_id, sort_order, id);

ALTER TABLE public.rental_item
  ADD COLUMN cabin_type_id uuid,
  ADD COLUMN cabin_dimension_id uuid,
  ADD COLUMN cabin_finishing_id uuid;

-- Seed the exact operator-visible choices that existed before normalization.
-- UUIDs are stable identifiers, while names remain editable in Settings.
INSERT INTO public.cabin_catalog_item(
  id, version, kind, name, name_normalized, active, created_at, updated_at)
VALUES
  ('af57f2b0-3a71-4b7f-8d2f-000000000001', 0, 'TYPE', 'БК-1', 'бк-1', true, clock_timestamp(), clock_timestamp()),
  ('af57f2b0-3a71-4b7f-8d2f-000000000002', 0, 'TYPE', 'БК-2', 'бк-2', true, clock_timestamp(), clock_timestamp()),
  ('af57f2b0-3a71-4b7f-8d2f-000000000003', 0, 'TYPE', 'БК-3', 'бк-3', true, clock_timestamp(), clock_timestamp()),
  ('af57f2b0-3a71-4b7f-8d2f-000000000004', 0, 'TYPE', 'БК-4', 'бк-4', true, clock_timestamp(), clock_timestamp()),
  ('af57f2b0-3a71-4b7f-8d2f-000000000005', 0, 'TYPE', 'БК-5', 'бк-5', true, clock_timestamp(), clock_timestamp()),
  ('af57f2b0-3a71-4b7f-8d2f-000000000006', 0, 'TYPE', 'БК-6', 'бк-6', true, clock_timestamp(), clock_timestamp()),
  ('af57f2b0-3a71-4b7f-8d2f-000000000007', 0, 'TYPE', 'БК-Склад', 'бк-склад', true, clock_timestamp(), clock_timestamp()),
  ('af57f2b0-3a71-4b7f-8d2f-000000000008', 0, 'TYPE', 'БК-Санблок', 'бк-санблок', true, clock_timestamp(), clock_timestamp()),
  ('af57f2b0-3a71-4b7f-8d2f-000000000009', 0, 'TYPE', 'БК-Модуль из 2х', 'бк-модуль из 2х', true, clock_timestamp(), clock_timestamp()),
  ('af57f2b0-3a71-4b7f-8d2f-000000000010', 0, 'TYPE', 'БК-Модуль из 3', 'бк-модуль из 3', true, clock_timestamp(), clock_timestamp()),
  ('af57f2b0-3a71-4b7f-8d2f-000000000011', 0, 'TYPE', 'БК-Пост охраны', 'бк-пост охраны', true, clock_timestamp(), clock_timestamp()),
  ('af57f2b0-3a71-4b7f-8d2f-000000000101', 0, 'DIMENSION', '2x2', '2x2', true, clock_timestamp(), clock_timestamp()),
  ('af57f2b0-3a71-4b7f-8d2f-000000000102', 0, 'DIMENSION', '2.4x2', '2.4x2', true, clock_timestamp(), clock_timestamp()),
  ('af57f2b0-3a71-4b7f-8d2f-000000000103', 0, 'DIMENSION', '2.4x2.4', '2.4x2.4', true, clock_timestamp(), clock_timestamp()),
  ('af57f2b0-3a71-4b7f-8d2f-000000000104', 0, 'DIMENSION', '2.4x3', '2.4x3', true, clock_timestamp(), clock_timestamp()),
  ('af57f2b0-3a71-4b7f-8d2f-000000000105', 0, 'DIMENSION', '2.4x4', '2.4x4', true, clock_timestamp(), clock_timestamp()),
  ('af57f2b0-3a71-4b7f-8d2f-000000000106', 0, 'DIMENSION', '2.4x5', '2.4x5', true, clock_timestamp(), clock_timestamp()),
  ('af57f2b0-3a71-4b7f-8d2f-000000000107', 0, 'DIMENSION', '2.4x6', '2.4x6', true, clock_timestamp(), clock_timestamp()),
  ('af57f2b0-3a71-4b7f-8d2f-000000000108', 0, 'DIMENSION', '3x3', '3x3', true, clock_timestamp(), clock_timestamp()),
  ('af57f2b0-3a71-4b7f-8d2f-000000000109', 0, 'DIMENSION', '4.8x6', '4.8x6', true, clock_timestamp(), clock_timestamp()),
  ('af57f2b0-3a71-4b7f-8d2f-000000000110', 0, 'DIMENSION', '7.2x6', '7.2x6', true, clock_timestamp(), clock_timestamp()),
  ('af57f2b0-3a71-4b7f-8d2f-000000000201', 0, 'FINISHING', 'ДВП', 'двп', true, clock_timestamp(), clock_timestamp()),
  ('af57f2b0-3a71-4b7f-8d2f-000000000202', 0, 'FINISHING', 'ЛДСП', 'лдсп', true, clock_timestamp(), clock_timestamp()),
  ('af57f2b0-3a71-4b7f-8d2f-000000000203', 0, 'FINISHING', 'ПВХ', 'пвх', true, clock_timestamp(), clock_timestamp()),
  ('af57f2b0-3a71-4b7f-8d2f-000000000204', 0, 'FINISHING', 'ОСБ', 'осб', true, clock_timestamp(), clock_timestamp()),
  ('af57f2b0-3a71-4b7f-8d2f-000000000205', 0, 'FINISHING', 'Вагонка', 'вагонка', true, clock_timestamp(), clock_timestamp()),
  ('af57f2b0-3a71-4b7f-8d2f-000000000206', 0, 'FINISHING', 'СМЛО', 'смло', true, clock_timestamp(), clock_timestamp()),
  ('af57f2b0-3a71-4b7f-8d2f-000000000207', 0, 'FINISHING', 'Сэндвич', 'сэндвич', true, clock_timestamp(), clock_timestamp()),
  ('af57f2b0-3a71-4b7f-8d2f-000000000301', 0, 'CHARACTERISTIC', 'Пластиковое окно', 'пластиковое окно', true, clock_timestamp(), clock_timestamp()),
  ('af57f2b0-3a71-4b7f-8d2f-000000000302', 0, 'CHARACTERISTIC', 'Электрика КК', 'электрика кк', true, clock_timestamp(), clock_timestamp()),
  ('af57f2b0-3a71-4b7f-8d2f-000000000303', 0, 'CHARACTERISTIC', 'Электрика КК + УЗО', 'электрика кк + узо', true, clock_timestamp(), clock_timestamp()),
  ('af57f2b0-3a71-4b7f-8d2f-000000000304', 0, 'CHARACTERISTIC', 'Электрика КК + УЗО + счётчик', 'электрика кк + узо + счётчик', true, clock_timestamp(), clock_timestamp()),
  ('af57f2b0-3a71-4b7f-8d2f-000000000305', 0, 'CHARACTERISTIC', 'Электрика КК + счётчик', 'электрика кк + счётчик', true, clock_timestamp(), clock_timestamp()),
  ('af57f2b0-3a71-4b7f-8d2f-000000000306', 0, 'CHARACTERISTIC', 'Металлическая дверь', 'металлическая дверь', true, clock_timestamp(), clock_timestamp()),
  ('af57f2b0-3a71-4b7f-8d2f-000000000307', 0, 'CHARACTERISTIC', 'Кондиционер', 'кондиционер', true, clock_timestamp(), clock_timestamp()),
  ('af57f2b0-3a71-4b7f-8d2f-000000000308', 0, 'CHARACTERISTIC', 'Две лампы', 'две лампы', true, clock_timestamp(), clock_timestamp()),
  ('af57f2b0-3a71-4b7f-8d2f-000000000309', 0, 'CHARACTERISTIC', 'Мама-папа', 'мама-папа', true, clock_timestamp(), clock_timestamp())
ON CONFLICT (kind, name_normalized) DO NOTHING;

-- Preserve any already-entered custom values as editable catalog rows.
INSERT INTO public.cabin_catalog_item(
  id, version, kind, name, name_normalized, active, created_at, updated_at)
SELECT gen_random_uuid(), 0, source.kind, source.name, lower(source.name), true,
       clock_timestamp(), clock_timestamp()
FROM (
  SELECT DISTINCT 'TYPE'::varchar(32) AS kind, btrim(rental_type) AS name
  FROM public.rental_item
  WHERE nullif(btrim(rental_type), '') IS NOT NULL
  UNION
  SELECT DISTINCT 'DIMENSION'::varchar(32), btrim(dimensions)
  FROM public.rental_item
  WHERE nullif(btrim(dimensions), '') IS NOT NULL
  UNION
  SELECT DISTINCT 'FINISHING'::varchar(32), btrim(finishing)
  FROM public.rental_item
  WHERE nullif(btrim(finishing), '') IS NOT NULL
  UNION
  SELECT DISTINCT 'CHARACTERISTIC'::varchar(32), btrim(parts.value)
  FROM public.rental_item AS item
  CROSS JOIN LATERAL regexp_split_to_table(coalesce(item.characteristics, ''), E'\\s*,\\s*')
    WITH ORDINALITY AS parts(value, ordinality)
  WHERE nullif(btrim(parts.value), '') IS NOT NULL
) AS source
ON CONFLICT (kind, name_normalized) DO NOTHING;

UPDATE public.rental_item AS item
SET cabin_type_id = catalog.id
FROM public.cabin_catalog_item AS catalog
WHERE catalog.kind = 'TYPE'
  AND catalog.name_normalized = lower(btrim(item.rental_type));

UPDATE public.rental_item AS item
SET cabin_dimension_id = catalog.id
FROM public.cabin_catalog_item AS catalog
WHERE catalog.kind = 'DIMENSION'
  AND catalog.name_normalized = lower(btrim(item.dimensions));

UPDATE public.rental_item AS item
SET cabin_finishing_id = catalog.id
FROM public.cabin_catalog_item AS catalog
WHERE catalog.kind = 'FINISHING'
  AND catalog.name_normalized = lower(btrim(item.finishing));

WITH legacy_tokens AS (
  SELECT item.id AS rental_item_id, btrim(parts.value) AS name, parts.ordinality
  FROM public.rental_item AS item
  CROSS JOIN LATERAL regexp_split_to_table(coalesce(item.characteristics, ''), E'\\s*,\\s*')
    WITH ORDINALITY AS parts(value, ordinality)
  WHERE nullif(btrim(parts.value), '') IS NOT NULL
), deduplicated AS (
  SELECT DISTINCT ON (rental_item_id, lower(name))
    rental_item_id, name, ordinality
  FROM legacy_tokens
  ORDER BY rental_item_id, lower(name), ordinality
), positioned AS (
  SELECT rental_item_id, name,
    row_number() OVER (PARTITION BY rental_item_id ORDER BY ordinality) - 1 AS sort_order
  FROM deduplicated
)
INSERT INTO public.rental_item_characteristic(id, rental_item_id, characteristic_id, sort_order)
SELECT gen_random_uuid(), positioned.rental_item_id, catalog.id, positioned.sort_order
FROM positioned
JOIN public.cabin_catalog_item AS catalog
  ON catalog.kind = 'CHARACTERISTIC'
  AND catalog.name_normalized = lower(positioned.name)
ON CONFLICT (rental_item_id, characteristic_id) DO NOTHING;

-- The old fixed compatibility matrix is seed data, and existing valid pairs
-- are also retained so custom historical types remain usable after upgrade.
WITH configured_pairs(type_name, dimension_name, sort_order) AS (
  VALUES
    ('БК-1', '2.4x6', 0), ('БК-2', '2.4x6', 0),
    ('БК-3', '2.4x6', 0), ('БК-4', '2.4x6', 0),
    ('БК-5', '2.4x6', 0), ('БК-6', '2.4x6', 0),
    ('БК-Склад', '2.4x6', 0), ('БК-Санблок', '2.4x6', 0),
    ('БК-Модуль из 2х', '4.8x6', 0), ('БК-Модуль из 3', '7.2x6', 0),
    ('БК-Пост охраны', '2x2', 0), ('БК-Пост охраны', '2.4x2', 1),
    ('БК-Пост охраны', '2.4x2.4', 2), ('БК-Пост охраны', '2.4x3', 3),
    ('БК-Пост охраны', '2.4x4', 4), ('БК-Пост охраны', '2.4x5', 5),
    ('БК-Пост охраны', '3x3', 6)
)
INSERT INTO public.cabin_type_dimension(
  id, version, cabin_type_id, dimension_id, sort_order, created_at, updated_at)
SELECT gen_random_uuid(), 0, type_item.id, dimension_item.id, pairs.sort_order,
       clock_timestamp(), clock_timestamp()
FROM configured_pairs AS pairs
JOIN public.cabin_catalog_item AS type_item
  ON type_item.kind = 'TYPE' AND type_item.name_normalized = lower(pairs.type_name)
JOIN public.cabin_catalog_item AS dimension_item
  ON dimension_item.kind = 'DIMENSION' AND dimension_item.name_normalized = lower(pairs.dimension_name)
ON CONFLICT (cabin_type_id, dimension_id) DO NOTHING;

INSERT INTO public.cabin_type_dimension(
  id, version, cabin_type_id, dimension_id, sort_order, created_at, updated_at)
SELECT gen_random_uuid(), 0, item.cabin_type_id, item.cabin_dimension_id, 999,
       clock_timestamp(), clock_timestamp()
FROM public.rental_item AS item
WHERE item.cabin_type_id IS NOT NULL
  AND item.cabin_dimension_id IS NOT NULL
ON CONFLICT (cabin_type_id, dimension_id) DO NOTHING;

ALTER TABLE public.rental_item
  ADD CONSTRAINT fk_rental_item_cabin_type FOREIGN KEY (cabin_type_id)
    REFERENCES public.cabin_catalog_item(id),
  ADD CONSTRAINT fk_rental_item_cabin_dimension FOREIGN KEY (cabin_dimension_id)
    REFERENCES public.cabin_catalog_item(id),
  ADD CONSTRAINT fk_rental_item_cabin_finishing FOREIGN KEY (cabin_finishing_id)
    REFERENCES public.cabin_catalog_item(id);

CREATE INDEX idx_rental_item_cabin_composition
  ON public.rental_item(cabin_type_id, cabin_dimension_id, cabin_finishing_id);

ALTER TABLE public.rental_item
  DROP COLUMN rental_type,
  DROP COLUMN dimensions,
  DROP COLUMN finishing,
  DROP COLUMN characteristics;
