ALTER TABLE public.cabin_photo_presentation
    ADD COLUMN metadata_snapshot_json jsonb NOT NULL DEFAULT '{}'::jsonb;

ALTER TABLE public.cabin_photo_presentation
    ALTER COLUMN metadata_snapshot_json DROP DEFAULT,
    ADD CONSTRAINT ck_cabin_photo_presentation_metadata_snapshot
        CHECK (jsonb_typeof(metadata_snapshot_json) = 'object');
