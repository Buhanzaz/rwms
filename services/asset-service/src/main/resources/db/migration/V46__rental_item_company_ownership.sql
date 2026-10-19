-- Adopt the existing single-company asset installation without rewriting any
-- cabin identity, custody, lifecycle or event-stream state.

ALTER TABLE public.rental_item
  ADD COLUMN company_id uuid;

UPDATE public.rental_item
SET company_id = 'ae0d6f97-f0c5-576a-9ea7-1ddcc1a03b48'::uuid
WHERE company_id IS NULL;

ALTER TABLE public.rental_item
  ALTER COLUMN company_id SET NOT NULL;

ALTER TABLE public.rental_item_html_import
  ADD COLUMN company_id uuid;

UPDATE public.rental_item_html_import
SET company_id = 'ae0d6f97-f0c5-576a-9ea7-1ddcc1a03b48'::uuid
WHERE company_id IS NULL;

ALTER TABLE public.rental_item_html_import
  ALTER COLUMN company_id SET NOT NULL;

CREATE INDEX idx_rental_item_company_warehouse_status
  ON public.rental_item(company_id, warehouse_id, status, identity_match_key, id);

CREATE INDEX idx_rental_item_html_import_company_warehouse
  ON public.rental_item_html_import(company_id, warehouse_id, updated_at DESC, id);

CREATE OR REPLACE FUNCTION public.prevent_rental_item_company_change()
RETURNS trigger
LANGUAGE plpgsql
AS $$
BEGIN
  IF NEW.company_id IS DISTINCT FROM OLD.company_id THEN
    RAISE EXCEPTION 'rental item company ownership is immutable'
      USING ERRCODE = '23514';
  END IF;
  RETURN NEW;
END
$$;

CREATE TRIGGER prevent_rental_item_company_change
BEFORE UPDATE OF company_id ON public.rental_item
FOR EACH ROW EXECUTE FUNCTION public.prevent_rental_item_company_change();

CREATE OR REPLACE FUNCTION public.prevent_rental_item_html_import_company_change()
RETURNS trigger
LANGUAGE plpgsql
AS $$
BEGIN
  IF NEW.company_id IS DISTINCT FROM OLD.company_id THEN
    RAISE EXCEPTION 'rental item html import company ownership is immutable'
      USING ERRCODE = '23514';
  END IF;
  RETURN NEW;
END
$$;

CREATE TRIGGER prevent_rental_item_html_import_company_change
BEFORE UPDATE OF company_id ON public.rental_item_html_import
FOR EACH ROW EXECUTE FUNCTION public.prevent_rental_item_html_import_company_change();
