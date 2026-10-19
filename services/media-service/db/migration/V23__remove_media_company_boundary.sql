DO $$
BEGIN
  IF (
    SELECT count(DISTINCT company_id)
      FROM (
        SELECT company_id FROM public.media_asset
        UNION
        SELECT company_id FROM public.media_asset_import_job
      ) AS owned_rows
     WHERE company_id IS NOT NULL
  ) > 1 THEN
    RAISE EXCEPTION
      'V23 cannot remove the media company boundary from a multi-company database';
  END IF;
END;
$$;

DROP TRIGGER media_asset_company_immutable ON public.media_asset;
DROP TRIGGER media_asset_import_company_immutable ON public.media_asset_import_job;

DROP FUNCTION public.prevent_media_asset_company_change();
DROP FUNCTION public.prevent_media_asset_import_company_change();

DROP INDEX public.media_asset_company_owner_order_idx;
DROP INDEX public.media_asset_import_company_job_idx;

ALTER TABLE public.media_asset
    DROP COLUMN company_id;

ALTER TABLE public.media_asset_import_job
    DROP COLUMN company_id;
