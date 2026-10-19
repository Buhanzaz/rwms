ALTER TABLE public.auth_subject
    ADD COLUMN rental_access boolean NOT NULL DEFAULT false;

UPDATE public.auth_subject
SET rental_access = true
WHERE principal_type = 'USER'
  AND global_role IN ('SYSTEM_ADMIN', 'WMS_ADMIN', 'RENTAL_MANAGER');
