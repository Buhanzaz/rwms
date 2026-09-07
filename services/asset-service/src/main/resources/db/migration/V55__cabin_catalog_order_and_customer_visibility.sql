-- Catalog positions are global within a kind. Existing values retain the prior
-- deterministic display order until an operator explicitly reorders the kind.
ALTER TABLE public.cabin_catalog_item
  ADD COLUMN sort_order integer,
  ADD COLUMN customer_visible boolean NOT NULL DEFAULT true;

WITH ranked AS (
  SELECT
    id,
    (row_number() OVER (PARTITION BY kind ORDER BY name ASC, id ASC) - 1)::integer AS sort_order
  FROM public.cabin_catalog_item
)
UPDATE public.cabin_catalog_item AS item
SET sort_order = ranked.sort_order
FROM ranked
WHERE ranked.id = item.id;

-- Give every still-live legacy create replay the new fields before resolving values whose
-- catalog row still exists. A created value may have been deleted while its replay record remains.
UPDATE public.asset_idempotency_record AS record
SET response_body =
  record.response_body
  || jsonb_build_object(
       'sortOrder', 0,
       'customerVisible', true)
WHERE record.command_scope = 'cabin-catalog.create'
  AND record.expires_at > clock_timestamp()
  AND (
    record.response_body->'sortOrder' IS NULL
    OR record.response_body->'customerVisible' IS NULL);

UPDATE public.asset_idempotency_record AS record
SET response_body =
  record.response_body
  || jsonb_build_object(
       'sortOrder', item.sort_order,
       'customerVisible', item.customer_visible)
FROM public.cabin_catalog_item AS item
WHERE record.command_scope = 'cabin-catalog.create'
  AND record.expires_at > clock_timestamp()
  AND record.response_body->>'id' = item.id::text
  AND (
    record.response_body->'sortOrder' IS DISTINCT FROM to_jsonb(item.sort_order)
    OR record.response_body->'customerVisible' IS DISTINCT FROM to_jsonb(item.customer_visible));

DO $$
BEGIN
  IF EXISTS (
    SELECT 1
    FROM public.asset_idempotency_record AS record
    WHERE record.command_scope = 'cabin-catalog.create'
      AND record.expires_at > clock_timestamp()
      AND (
        jsonb_typeof(record.response_body->'sortOrder') <> 'number'
        OR jsonb_typeof(record.response_body->'customerVisible') <> 'boolean')) THEN
    RAISE EXCEPTION 'Live cabin-catalog create replay was not upgraded';
  END IF;
  IF EXISTS (
    SELECT 1
    FROM public.asset_idempotency_record AS record
    JOIN public.cabin_catalog_item AS item
      ON record.response_body->>'id' = item.id::text
    WHERE record.command_scope = 'cabin-catalog.create'
      AND record.expires_at > clock_timestamp()
      AND (
        record.response_body->'sortOrder' IS DISTINCT FROM to_jsonb(item.sort_order)
        OR record.response_body->'customerVisible' IS DISTINCT FROM to_jsonb(item.customer_visible))) THEN
    RAISE EXCEPTION 'Live cabin-catalog create replay does not match its catalog item';
  END IF;
END;
$$;

DO $$
BEGIN
  IF EXISTS (
    SELECT 1
    FROM public.cabin_catalog_item
    GROUP BY kind, sort_order
    HAVING count(*) > 1) THEN
    RAISE EXCEPTION 'Cabin catalog order backfill produced duplicate positions';
  END IF;
END;
$$;

ALTER TABLE public.cabin_catalog_item
  ALTER COLUMN sort_order SET NOT NULL,
  ADD CONSTRAINT ck_cabin_catalog_item_sort_order CHECK (sort_order >= 0),
  ADD CONSTRAINT ck_cabin_catalog_item_customer_visibility CHECK (
    kind = 'CHARACTERISTIC' OR customer_visible),
  ADD CONSTRAINT uq_cabin_catalog_item_kind_sort_order
    UNIQUE (kind, sort_order) DEFERRABLE INITIALLY DEFERRED;

CREATE INDEX idx_cabin_catalog_item_kind_active_sort_order
  ON public.cabin_catalog_item(kind, active, sort_order, id);
