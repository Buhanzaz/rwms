ALTER TABLE public.logistics_idempotency_record
    DROP CONSTRAINT ck_logistics_idempotency_operation;

ALTER TABLE public.logistics_idempotency_record
    ADD CONSTRAINT ck_logistics_idempotency_operation CHECK (
        operation_name IN (
            'CREATE_RETURN',
            'CREATE_SHIPMENT',
            'CREATE_TRANSFER',
            'REGISTER_RETURN',
            'ACCEPT_RETURN',
            'REQUEST_RETURN_ESTIMATE'
        )
    );

ALTER TABLE public.logistics_guard
    ADD COLUMN lease_version bigint;

ALTER TABLE public.logistics_guard
    ADD CONSTRAINT ck_logistics_guard_lease_version CHECK (
        lease_version IS NULL OR lease_version >= 0
    );

ALTER TABLE public.logistics_media_reference
    ADD COLUMN generation bigint;

ALTER TABLE public.logistics_media_reference
    ADD CONSTRAINT ck_logistics_media_reference_generation CHECK (
        generation IS NULL OR generation >= 1
    );

CREATE TABLE public.logistics_return_shortage_snapshot (
    id uuid NOT NULL,
    document_id uuid NOT NULL,
    line_id uuid NOT NULL,
    warehouse_id uuid NOT NULL,
    rental_item_id uuid NOT NULL,
    rental_item_version bigint NOT NULL,
    shortages jsonb NOT NULL,
    snapshot_sha256 char(64) NOT NULL,
    created_at timestamptz NOT NULL,
    CONSTRAINT logistics_return_shortage_snapshot_pkey PRIMARY KEY (id),
    CONSTRAINT fk_logistics_return_shortage_snapshot_document
        FOREIGN KEY (document_id) REFERENCES public.logistics_document (id),
    CONSTRAINT fk_logistics_return_shortage_snapshot_line
        FOREIGN KEY (line_id) REFERENCES public.logistics_document_line (id),
    CONSTRAINT uk_logistics_return_shortage_snapshot_line UNIQUE (line_id),
    CONSTRAINT ck_logistics_return_shortage_snapshot_version CHECK (
        rental_item_version >= 0
    ),
    CONSTRAINT ck_logistics_return_shortage_snapshot_json CHECK (
        jsonb_typeof(shortages) = 'object'
    ),
    CONSTRAINT ck_logistics_return_shortage_snapshot_sha256 CHECK (
        snapshot_sha256 ~ '^[0-9a-f]{64}$'
    )
);
