-- Restores the mandatory tenant boundary after the withdrawn V9 platform-admin experiment.
UPDATE public.auth_subject
SET company_id = 'ae0d6f97-f0c5-576a-9ea7-1ddcc1a03b48'::uuid
WHERE company_id IS NULL
  AND principal_type = 'USER'
  AND global_role = 'SYSTEM_ADMIN';

DO $$
BEGIN
    IF EXISTS (
        SELECT 1
        FROM public.auth_subject
        WHERE company_id IS NULL
    ) THEN
        RAISE EXCEPTION
            'Cannot restore subject company boundary: every auth subject must belong to a company';
    END IF;
END
$$;

ALTER TABLE public.auth_subject
    ALTER COLUMN company_id SET NOT NULL;
