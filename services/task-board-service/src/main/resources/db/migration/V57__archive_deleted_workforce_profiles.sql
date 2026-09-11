-- Historical assignments and KPI intervals retain their owning identities.
ALTER TABLE public.worker ADD COLUMN archived boolean NOT NULL DEFAULT false;
ALTER TABLE public.worker_group ADD COLUMN archived boolean NOT NULL DEFAULT false;
ALTER TABLE public.worker ADD CONSTRAINT ck_worker_archived_inactive
    CHECK (NOT archived OR (NOT active AND app_login IS NULL AND current_group_id IS NULL));
ALTER TABLE public.worker_group ADD CONSTRAINT ck_worker_group_archived_inactive
    CHECK (NOT archived OR NOT active);

ALTER TABLE public.worker_group DROP CONSTRAINT uk_worker_group_name;
CREATE UNIQUE INDEX uk_worker_group_name ON public.worker_group(warehouse_id, name)
    WHERE NOT archived;
