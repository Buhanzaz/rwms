CREATE TABLE public.cabin_photo_presentation (
    id uuid NOT NULL,
    version bigint NOT NULL DEFAULT 0,
    cabin_id uuid NOT NULL,
    cabin_number varchar(128) NOT NULL,
    warehouse_id uuid NOT NULL,
    rental_item_version bigint NOT NULL,
    created_by_subject_id uuid NOT NULL,
    idempotency_key uuid NOT NULL,
    request_sha256 varchar(64) NOT NULL,
    photo_snapshot_json jsonb NOT NULL,
    created_at timestamptz NOT NULL,
    CONSTRAINT cabin_photo_presentation_pkey PRIMARY KEY (id),
    CONSTRAINT uk_cabin_photo_presentation_subject_key
        UNIQUE (created_by_subject_id, idempotency_key),
    CONSTRAINT ck_cabin_photo_presentation_version CHECK (version >= 0),
    CONSTRAINT ck_cabin_photo_presentation_rental_item_version CHECK (rental_item_version >= 0),
    CONSTRAINT ck_cabin_photo_presentation_cabin_number CHECK (
        length(btrim(cabin_number)) BETWEEN 1 AND 128
    ),
    CONSTRAINT ck_cabin_photo_presentation_request_hash CHECK (
        request_sha256 ~ '^[0-9a-f]{64}$'
    ),
    CONSTRAINT ck_cabin_photo_presentation_photo_snapshot CHECK (
        jsonb_typeof(photo_snapshot_json) = 'array'
        AND jsonb_array_length(photo_snapshot_json) BETWEEN 1 AND 100
    )
);

CREATE INDEX idx_cabin_photo_presentation_cabin_created
    ON public.cabin_photo_presentation (cabin_id, created_at DESC, id);
