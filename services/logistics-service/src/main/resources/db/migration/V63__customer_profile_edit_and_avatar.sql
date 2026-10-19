ALTER TABLE public.customer_profile
    ADD COLUMN avatar_warehouse_id uuid,
    ADD COLUMN avatar_media_id uuid,
    ADD COLUMN avatar_generation bigint;

ALTER TABLE public.customer_profile
    ADD CONSTRAINT ck_customer_profile_avatar_scope CHECK (
        avatar_media_id IS NULL OR avatar_warehouse_id IS NOT NULL
    ),
    ADD CONSTRAINT ck_customer_profile_avatar_generation CHECK (
        (avatar_media_id IS NULL AND avatar_generation IS NULL)
        OR (avatar_media_id IS NOT NULL AND avatar_generation >= 1)
    );

CREATE INDEX ix_customer_profile_avatar_media
    ON public.customer_profile (avatar_media_id, avatar_generation)
    WHERE avatar_media_id IS NOT NULL;
