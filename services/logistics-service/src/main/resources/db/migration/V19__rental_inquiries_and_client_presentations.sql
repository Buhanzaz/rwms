CREATE TABLE public.rental_inquiry (
    id uuid NOT NULL,
    version bigint NOT NULL DEFAULT 0,
    conversation_id uuid NOT NULL,
    client_id uuid NOT NULL,
    manager_id uuid NOT NULL,
    manager_display_name varchar(255) NOT NULL,
    manager_role varchar(32) NOT NULL,
    warehouse_id uuid,
    state varchar(16) NOT NULL,
    booked_order_id uuid,
    created_at timestamptz NOT NULL,
    updated_at timestamptz NOT NULL,
    booked_at timestamptz,
    CONSTRAINT rental_inquiry_pkey PRIMARY KEY (id),
    CONSTRAINT uk_rental_inquiry_conversation UNIQUE (conversation_id),
    CONSTRAINT fk_rental_inquiry_client FOREIGN KEY (client_id)
        REFERENCES public.order_client(id),
    CONSTRAINT ck_rental_inquiry_version CHECK (version >= 0),
    CONSTRAINT ck_rental_inquiry_state CHECK (
        state IN ('ACTIVE', 'BOOKED', 'ARCHIVED')
    ),
    CONSTRAINT ck_rental_inquiry_manager_role CHECK (
        manager_role IN (
            'SYSTEM_ADMIN', 'WMS_ADMIN', 'WAREHOUSE_MANAGER',
            'RENTAL_MANAGER', 'VIEWER'
        )
    ),
    CONSTRAINT ck_rental_inquiry_booking CHECK (
        (
            state = 'BOOKED'
            AND booked_order_id IS NOT NULL
            AND booked_at IS NOT NULL
        )
        OR (
            state IN ('ACTIVE', 'ARCHIVED')
            AND booked_order_id IS NULL
            AND booked_at IS NULL
        )
    )
);

CREATE INDEX idx_rental_inquiry_manager_state
    ON public.rental_inquiry (manager_id, state, updated_at DESC, id);

CREATE TABLE public.client_presentation (
    id uuid NOT NULL,
    version bigint NOT NULL DEFAULT 0,
    inquiry_id uuid NOT NULL,
    revision bigint NOT NULL,
    warehouse_id uuid NOT NULL,
    state varchar(24) NOT NULL,
    expires_at timestamptz NOT NULL,
    view_until timestamptz NOT NULL,
    booked_order_id uuid,
    last_publish_idempotency_key uuid NOT NULL,
    last_publish_request_sha256 char(64) NOT NULL,
    created_at timestamptz NOT NULL,
    updated_at timestamptz NOT NULL,
    booked_at timestamptz,
    CONSTRAINT client_presentation_pkey PRIMARY KEY (id),
    CONSTRAINT uk_client_presentation_inquiry UNIQUE (inquiry_id),
    CONSTRAINT fk_client_presentation_inquiry FOREIGN KEY (inquiry_id)
        REFERENCES public.rental_inquiry(id),
    CONSTRAINT ck_client_presentation_version CHECK (version >= 0),
    CONSTRAINT ck_client_presentation_revision CHECK (revision >= 1),
    CONSTRAINT ck_client_presentation_state CHECK (
        state IN ('ACTIVE', 'BOOKING_PENDING', 'BOOKED', 'REVOKED')
    ),
    CONSTRAINT ck_client_presentation_lifetime CHECK (view_until > expires_at),
    CONSTRAINT ck_client_presentation_publish_hash CHECK (
        last_publish_request_sha256 ~ '^[0-9a-f]{64}$'
    ),
    CONSTRAINT ck_client_presentation_booking CHECK (
        (
            state = 'ACTIVE'
            AND booked_order_id IS NULL
            AND booked_at IS NULL
        )
        OR (
            state = 'BOOKING_PENDING'
            AND booked_order_id IS NOT NULL
            AND booked_at IS NULL
        )
        OR (
            state = 'BOOKED'
            AND booked_order_id IS NOT NULL
            AND booked_at IS NOT NULL
        )
        OR state = 'REVOKED'
    )
);

CREATE TABLE public.client_presentation_item (
    id uuid NOT NULL,
    presentation_id uuid NOT NULL,
    presentation_revision bigint NOT NULL,
    rental_item_id uuid NOT NULL,
    group_key varchar(128) NOT NULL,
    group_label varchar(255) NOT NULL,
    sort_order integer NOT NULL,
    cabin_snapshot_json jsonb NOT NULL,
    media_snapshot_json jsonb NOT NULL,
    created_at timestamptz NOT NULL,
    CONSTRAINT client_presentation_item_pkey PRIMARY KEY (id),
    CONSTRAINT fk_client_presentation_item_presentation
        FOREIGN KEY (presentation_id)
        REFERENCES public.client_presentation(id),
    CONSTRAINT uk_client_presentation_item_revision_cabin
        UNIQUE (presentation_id, presentation_revision, rental_item_id),
    CONSTRAINT uk_client_presentation_item_revision_order
        UNIQUE (presentation_id, presentation_revision, sort_order),
    CONSTRAINT ck_client_presentation_item_revision CHECK (
        presentation_revision >= 1
    ),
    CONSTRAINT ck_client_presentation_item_sort CHECK (sort_order >= 0),
    CONSTRAINT ck_client_presentation_item_cabin_json CHECK (
        jsonb_typeof(cabin_snapshot_json) = 'object'
    ),
    CONSTRAINT ck_client_presentation_item_media_json CHECK (
        jsonb_typeof(media_snapshot_json) = 'array'
    )
);

CREATE INDEX idx_client_presentation_item_current
    ON public.client_presentation_item
        (presentation_id, presentation_revision, sort_order, id);

CREATE TABLE public.rental_settings (
    id uuid NOT NULL,
    version bigint NOT NULL DEFAULT 0,
    presentation_hold_minutes integer NOT NULL,
    updated_by_subject_id uuid NOT NULL,
    updated_at timestamptz NOT NULL,
    CONSTRAINT rental_settings_pkey PRIMARY KEY (id),
    CONSTRAINT ck_rental_settings_singleton CHECK (
        id = '00000000-0000-0000-0000-000000000001'::uuid
    ),
    CONSTRAINT ck_rental_settings_version CHECK (version >= 0),
    CONSTRAINT ck_rental_settings_hold_minutes CHECK (
        presentation_hold_minutes BETWEEN 5 AND 1440
    )
);

INSERT INTO public.rental_settings(
    id, version, presentation_hold_minutes, updated_by_subject_id, updated_at)
VALUES (
    '00000000-0000-0000-0000-000000000001',
    0,
    60,
    '00000000-0000-0000-0000-000000000000',
    clock_timestamp()
);

CREATE TABLE public.presentation_booking (
    id uuid NOT NULL,
    version bigint NOT NULL DEFAULT 0,
    presentation_id uuid NOT NULL,
    presentation_revision bigint NOT NULL,
    idempotency_key uuid NOT NULL,
    order_id uuid,
    selected_item_ids_json jsonb NOT NULL,
    state varchar(16) NOT NULL,
    attempt_count integer NOT NULL DEFAULT 0,
    last_error_code varchar(64),
    created_at timestamptz NOT NULL,
    updated_at timestamptz NOT NULL,
    completed_at timestamptz,
    CONSTRAINT presentation_booking_pkey PRIMARY KEY (id),
    CONSTRAINT fk_presentation_booking_presentation
        FOREIGN KEY (presentation_id)
        REFERENCES public.client_presentation(id),
    CONSTRAINT uk_presentation_booking_idempotency
        UNIQUE (presentation_id, idempotency_key),
    CONSTRAINT uk_presentation_booking_revision
        UNIQUE (presentation_id, presentation_revision),
    CONSTRAINT ck_presentation_booking_version CHECK (version >= 0),
    CONSTRAINT ck_presentation_booking_revision CHECK (
        presentation_revision >= 1
    ),
    CONSTRAINT ck_presentation_booking_selection CHECK (
        jsonb_typeof(selected_item_ids_json) = 'array'
        AND jsonb_array_length(selected_item_ids_json) BETWEEN 1 AND 100
    ),
    CONSTRAINT ck_presentation_booking_state CHECK (
        state IN ('PENDING', 'COMPLETED', 'REJECTED')
    ),
    CONSTRAINT ck_presentation_booking_attempts CHECK (attempt_count >= 0),
    CONSTRAINT ck_presentation_booking_completion CHECK (
        (state = 'PENDING' AND completed_at IS NULL)
        OR (state IN ('COMPLETED', 'REJECTED') AND completed_at IS NOT NULL)
    )
);

CREATE INDEX idx_presentation_booking_pending
    ON public.presentation_booking (state, created_at, id)
    WHERE state = 'PENDING';

CREATE TABLE public.rental_inquiry_outbox (
    event_id uuid NOT NULL,
    event_type varchar(96) NOT NULL,
    inquiry_id uuid NOT NULL,
    conversation_id uuid NOT NULL,
    order_id uuid NOT NULL,
    payload jsonb NOT NULL,
    status varchar(16) NOT NULL,
    attempt_count integer NOT NULL DEFAULT 0,
    next_attempt_at timestamptz NOT NULL,
    created_at timestamptz NOT NULL,
    published_at timestamptz,
    CONSTRAINT rental_inquiry_outbox_pkey PRIMARY KEY (event_id),
    CONSTRAINT uk_rental_inquiry_outbox_order UNIQUE (order_id),
    CONSTRAINT ck_rental_inquiry_outbox_event_type CHECK (
        event_type = 'logistics.rental-inquiry.booked.v1'
    ),
    CONSTRAINT ck_rental_inquiry_outbox_payload CHECK (
        jsonb_typeof(payload) = 'object'
    ),
    CONSTRAINT ck_rental_inquiry_outbox_status CHECK (
        status IN ('PENDING', 'PUBLISHED')
    ),
    CONSTRAINT ck_rental_inquiry_outbox_attempts CHECK (attempt_count >= 0)
);

CREATE INDEX idx_rental_inquiry_outbox_pending
    ON public.rental_inquiry_outbox (status, next_attempt_at, created_at, event_id)
    WHERE status = 'PENDING';
