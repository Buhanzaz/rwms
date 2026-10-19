ALTER TABLE public.order_client
    DROP CONSTRAINT ck_order_client_type;

ALTER TABLE public.order_client
    ADD CONSTRAINT ck_order_client_type CHECK (
        client_type IN ('INDIVIDUAL', 'SOLE_PROPRIETOR', 'LEGAL_ENTITY')
    ),
    ADD COLUMN contact_person varchar(255),
    ADD COLUMN responsible_manager_id uuid,
    ADD COLUMN responsible_manager_display_name varchar(255),
    ADD COLUMN comment varchar(2000),
    ADD COLUMN source varchar(255);

UPDATE public.order_client
SET responsible_manager_id = created_by_subject_id
WHERE responsible_manager_id IS NULL;

ALTER TABLE public.order_client
    ALTER COLUMN responsible_manager_id SET NOT NULL,
    ADD CONSTRAINT ck_order_client_contact_person CHECK (
        client_type = 'INDIVIDUAL'
        OR (
            contact_person IS NOT NULL
            AND length(btrim(contact_person)) BETWEEN 1 AND 255
        )
    ) NOT VALID,
    ADD CONSTRAINT ck_order_client_phone_required CHECK (
        phone IS NOT NULL AND normalized_phone IS NOT NULL
    ) NOT VALID,
    ADD CONSTRAINT ck_order_client_manager_display_name CHECK (
        responsible_manager_display_name IS NULL
        OR length(btrim(responsible_manager_display_name)) BETWEEN 1 AND 255
    ),
    ADD CONSTRAINT ck_order_client_comment CHECK (
        comment IS NULL OR length(btrim(comment)) BETWEEN 1 AND 2000
    ),
    ADD CONSTRAINT ck_order_client_source CHECK (
        source IS NULL OR length(btrim(source)) BETWEEN 1 AND 255
    );

CREATE INDEX idx_order_client_responsible_manager
    ON public.order_client (responsible_manager_id, updated_at DESC, id);

ALTER TABLE public.rental_order
    ADD COLUMN delivery_address varchar(1000),
    ADD COLUMN latitude numeric(9, 6),
    ADD COLUMN longitude numeric(10, 6),
    ADD COLUMN contact_phone varchar(32),
    ADD COLUMN comment varchar(2000),
    ADD CONSTRAINT ck_rental_order_delivery_address CHECK (
        delivery_address IS NULL
        OR length(btrim(delivery_address)) BETWEEN 1 AND 1000
    ),
    ADD CONSTRAINT ck_rental_order_coordinates CHECK (
        (latitude IS NULL AND longitude IS NULL)
        OR (
            latitude IS NOT NULL
            AND longitude IS NOT NULL
            AND latitude BETWEEN -90 AND 90
            AND longitude BETWEEN -180 AND 180
        )
    ),
    ADD CONSTRAINT ck_rental_order_contact_phone CHECK (
        contact_phone IS NULL OR contact_phone ~ '^\+[1-9][0-9]{6,14}$'
    ),
    ADD CONSTRAINT ck_rental_order_comment CHECK (
        comment IS NULL OR length(btrim(comment)) BETWEEN 1 AND 2000
    );

CREATE TABLE public.rental_order_acceptable_delivery_date (
    order_id uuid NOT NULL,
    position integer NOT NULL,
    delivery_date date NOT NULL,
    CONSTRAINT rental_order_acceptable_delivery_date_pkey
        PRIMARY KEY (order_id, position),
    CONSTRAINT fk_rental_order_acceptable_delivery_date_order
        FOREIGN KEY (order_id) REFERENCES public.rental_order(id) ON DELETE CASCADE,
    CONSTRAINT uq_rental_order_acceptable_delivery_date
        UNIQUE (order_id, delivery_date),
    CONSTRAINT ck_rental_order_acceptable_delivery_date_position
        CHECK (position BETWEEN 0 AND 30)
);

CREATE INDEX idx_rental_order_acceptable_delivery_date
    ON public.rental_order_acceptable_delivery_date (delivery_date, order_id);

CREATE TABLE public.rental_inquiry_selection_receipt (
    id uuid NOT NULL,
    version bigint NOT NULL DEFAULT 0,
    inquiry_id uuid NOT NULL,
    subject_id uuid NOT NULL,
    public_idempotency_key uuid NOT NULL,
    request_sha256 char(64) NOT NULL,
    warehouse_id uuid NOT NULL,
    command_type varchar(16) NOT NULL,
    downstream_request_body text NOT NULL,
    downstream_request_sha256 char(64) NOT NULL,
    actor_role varchar(32) NOT NULL,
    command_expires_at timestamptz NOT NULL,
    hold_expires_at timestamptz,
    state varchar(16) NOT NULL,
    response_body text,
    rejection_code varchar(64),
    created_at timestamptz NOT NULL,
    updated_at timestamptz NOT NULL,
    terminal_at timestamptz,
    CONSTRAINT rental_inquiry_selection_receipt_pkey PRIMARY KEY (id),
    CONSTRAINT fk_rental_inquiry_selection_receipt_inquiry
        FOREIGN KEY (inquiry_id) REFERENCES public.rental_inquiry(id),
    CONSTRAINT uq_rental_inquiry_selection_receipt_subject_key
        UNIQUE (subject_id, public_idempotency_key),
    CONSTRAINT ck_rental_inquiry_selection_receipt_version CHECK (version >= 0),
    CONSTRAINT ck_rental_inquiry_selection_receipt_hashes CHECK (
        request_sha256 ~ '^[0-9a-f]{64}$'
        AND downstream_request_sha256 ~ '^[0-9a-f]{64}$'
    ),
    CONSTRAINT ck_rental_inquiry_selection_receipt_actor_role CHECK (
        actor_role IN (
            'SYSTEM_ADMIN', 'WMS_ADMIN', 'WAREHOUSE_MANAGER',
            'RENTAL_MANAGER', 'VIEWER'
        )
    ),
    CONSTRAINT ck_rental_inquiry_selection_receipt_command CHECK (
        command_expires_at > created_at
        AND (
            (command_type = 'REPLACE' AND hold_expires_at = command_expires_at)
            OR (command_type = 'RELEASE' AND hold_expires_at IS NULL)
        )
    ),
    CONSTRAINT ck_rental_inquiry_selection_receipt_state CHECK (
        state IN ('PREPARED', 'COMPLETED', 'REJECTED', 'EXPIRED')
    ),
    CONSTRAINT ck_rental_inquiry_selection_receipt_terminal CHECK (
        (state = 'PREPARED'
            AND response_body IS NULL
            AND rejection_code IS NULL
            AND terminal_at IS NULL)
        OR (state = 'COMPLETED'
            AND response_body IS NOT NULL
            AND rejection_code IS NULL
            AND terminal_at IS NOT NULL)
        OR (state = 'REJECTED'
            AND response_body IS NULL
            AND rejection_code IS NOT NULL
            AND terminal_at IS NOT NULL)
        OR (state = 'EXPIRED'
            AND response_body IS NULL
            AND rejection_code IS NULL
            AND terminal_at IS NOT NULL)
    )
);

CREATE UNIQUE INDEX uq_rental_inquiry_selection_receipt_prepared
    ON public.rental_inquiry_selection_receipt (inquiry_id)
    WHERE state = 'PREPARED';

CREATE INDEX idx_rental_inquiry_selection_receipt_inquiry
    ON public.rental_inquiry_selection_receipt (inquiry_id, created_at DESC, id);
