ALTER TABLE public.logistics_external_attempt
    ADD COLUMN row_version bigint NOT NULL DEFAULT 0,
    ADD CONSTRAINT ck_logistics_external_attempt_row_version CHECK (row_version >= 0);

ALTER TABLE public.logistics_guard
    ADD COLUMN row_version bigint NOT NULL DEFAULT 0,
    ADD CONSTRAINT ck_logistics_guard_row_version CHECK (row_version >= 0);

ALTER TABLE public.logistics_media_reference
    ADD COLUMN row_version bigint NOT NULL DEFAULT 0,
    ADD CONSTRAINT ck_logistics_media_reference_row_version CHECK (row_version >= 0);
