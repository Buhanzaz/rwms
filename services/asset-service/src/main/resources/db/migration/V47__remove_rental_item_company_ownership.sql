DO $$
BEGIN
  IF (
    SELECT count(DISTINCT company_id)
      FROM (
        SELECT company_id FROM public.rental_item
        UNION
        SELECT company_id FROM public.rental_item_html_import
      ) AS owned_rows
     WHERE company_id IS NOT NULL
  ) > 1 THEN
    RAISE EXCEPTION
      'V47 cannot remove rental-item company ownership from a multi-company asset database';
  END IF;
END;
$$;

DROP TRIGGER prevent_rental_item_company_change ON public.rental_item;
DROP TRIGGER prevent_rental_item_html_import_company_change ON public.rental_item_html_import;

DROP FUNCTION public.prevent_rental_item_company_change();
DROP FUNCTION public.prevent_rental_item_html_import_company_change();

DROP INDEX public.idx_rental_item_company_warehouse_status;
DROP INDEX public.idx_rental_item_html_import_company_warehouse;

ALTER TABLE public.rental_item
    DROP COLUMN company_id;

ALTER TABLE public.rental_item_html_import
    DROP COLUMN company_id;
