ALTER TABLE public.rental_order
    DROP CONSTRAINT ck_rental_order_creator_role,
    ADD CONSTRAINT ck_rental_order_creator_role CHECK (
        created_by_role IN (
            'SYSTEM_ADMIN', 'WMS_ADMIN', 'WAREHOUSE_MANAGER',
            'RENTAL_MANAGER', 'VIEWER', 'CUSTOMER'
        )
    ) NOT VALID;
ALTER TABLE public.rental_order VALIDATE CONSTRAINT ck_rental_order_creator_role;

ALTER TABLE public.rental_order_audit_event
    DROP CONSTRAINT ck_rental_order_audit_actor_role,
    ADD CONSTRAINT ck_rental_order_audit_actor_role CHECK (
        actor_role IN (
            'SYSTEM_ADMIN', 'WMS_ADMIN', 'WAREHOUSE_MANAGER',
            'RENTAL_MANAGER', 'VIEWER', 'CUSTOMER'
        )
    ) NOT VALID;
ALTER TABLE public.rental_order_audit_event
    VALIDATE CONSTRAINT ck_rental_order_audit_actor_role;

ALTER TABLE public.rental_inquiry
    DROP CONSTRAINT ck_rental_inquiry_manager_role,
    ADD CONSTRAINT ck_rental_inquiry_manager_role CHECK (
        manager_role IN (
            'SYSTEM_ADMIN', 'WMS_ADMIN', 'WAREHOUSE_MANAGER',
            'RENTAL_MANAGER', 'VIEWER', 'CUSTOMER'
        )
    ) NOT VALID;
ALTER TABLE public.rental_inquiry VALIDATE CONSTRAINT ck_rental_inquiry_manager_role;

ALTER TABLE public.rental_inquiry_search_attempt
    DROP CONSTRAINT ck_rental_inquiry_search_attempt_actor_role,
    ADD CONSTRAINT ck_rental_inquiry_search_attempt_actor_role CHECK (
        actor_role IN (
            'SYSTEM_ADMIN', 'WMS_ADMIN', 'WAREHOUSE_MANAGER',
            'RENTAL_MANAGER', 'VIEWER', 'CUSTOMER'
        )
    ) NOT VALID;
ALTER TABLE public.rental_inquiry_search_attempt
    VALIDATE CONSTRAINT ck_rental_inquiry_search_attempt_actor_role;

ALTER TABLE public.rental_inquiry_selection_receipt
    DROP CONSTRAINT ck_rental_inquiry_selection_receipt_actor_role,
    ADD CONSTRAINT ck_rental_inquiry_selection_receipt_actor_role CHECK (
        actor_role IN (
            'SYSTEM_ADMIN', 'WMS_ADMIN', 'WAREHOUSE_MANAGER',
            'RENTAL_MANAGER', 'VIEWER', 'CUSTOMER'
        )
    ) NOT VALID;
ALTER TABLE public.rental_inquiry_selection_receipt
    VALIDATE CONSTRAINT ck_rental_inquiry_selection_receipt_actor_role;

CREATE TABLE public.customer_profile (
    id uuid NOT NULL,
    version bigint NOT NULL DEFAULT 0,
    auth_subject_id uuid NOT NULL,
    client_id uuid NOT NULL,
    entity_type varchar(32) NOT NULL,
    first_name varchar(255),
    last_name varchar(255),
    company_name varchar(512),
    phone varchar(32) NOT NULL,
    email varchar(320),
    additional_info varchar(2000),
    created_at timestamptz NOT NULL,
    updated_at timestamptz NOT NULL,
    CONSTRAINT customer_profile_pkey PRIMARY KEY (id),
    CONSTRAINT uk_customer_profile_auth_subject UNIQUE (auth_subject_id),
    CONSTRAINT uk_customer_profile_client UNIQUE (client_id),
    CONSTRAINT fk_customer_profile_client FOREIGN KEY (client_id)
        REFERENCES public.order_client(id),
    CONSTRAINT ck_customer_profile_version CHECK (version >= 0),
    CONSTRAINT ck_customer_profile_entity_type CHECK (entity_type IN ('INDIVIDUAL', 'LEGAL')),
    CONSTRAINT ck_customer_profile_identity CHECK (
        (entity_type = 'INDIVIDUAL'
            AND length(btrim(first_name)) BETWEEN 1 AND 255
            AND length(btrim(last_name)) BETWEEN 1 AND 255
            AND company_name IS NULL)
        OR
        (entity_type = 'LEGAL'
            AND length(btrim(company_name)) BETWEEN 1 AND 512)
    ),
    CONSTRAINT ck_customer_profile_phone CHECK (length(btrim(phone)) BETWEEN 1 AND 32),
    CONSTRAINT ck_customer_profile_email CHECK (
        email IS NULL OR length(btrim(email)) BETWEEN 3 AND 320
    ),
    CONSTRAINT ck_customer_profile_additional_info CHECK (
        additional_info IS NULL OR length(btrim(additional_info)) BETWEEN 1 AND 2000
    )
);

CREATE TABLE public.customer_rental_session (
    id uuid NOT NULL,
    version bigint NOT NULL DEFAULT 0,
    inquiry_id uuid NOT NULL,
    customer_subject_id uuid NOT NULL,
    warehouse_id uuid NOT NULL,
    state varchar(32) NOT NULL,
    equipment_selection_json text NOT NULL DEFAULT '[]',
    pending_command_key uuid,
    pending_command_sha256 varchar(64),
    last_selection_key uuid,
    last_selection_sha256 varchar(64),
    delivery_slot_id uuid,
    booking_id uuid,
    order_id uuid,
    presentation_token varchar(2048),
    checkout_command_key uuid,
    checkout_command_sha256 varchar(64),
    created_at timestamptz NOT NULL,
    updated_at timestamptz NOT NULL,
    CONSTRAINT customer_rental_session_pkey PRIMARY KEY (id),
    CONSTRAINT uk_customer_rental_session_inquiry UNIQUE (inquiry_id),
    CONSTRAINT fk_customer_rental_session_inquiry FOREIGN KEY (inquiry_id)
        REFERENCES public.rental_inquiry(id),
    CONSTRAINT ck_customer_rental_session_version CHECK (version >= 0),
    CONSTRAINT ck_customer_rental_session_state CHECK (
        state IN ('ACTIVE', 'SELECTION_PENDING', 'CHECKOUT_PENDING', 'BOOKED')
    ),
    CONSTRAINT ck_customer_rental_session_equipment_json CHECK (
        jsonb_typeof(equipment_selection_json::jsonb) = 'array'
    ),
    CONSTRAINT ck_customer_rental_session_pending CHECK (
        (state IN ('SELECTION_PENDING', 'CHECKOUT_PENDING')
            AND pending_command_key IS NOT NULL
            AND pending_command_sha256 ~ '^[0-9a-f]{64}$')
        OR
        (state IN ('ACTIVE', 'BOOKED')
            AND pending_command_key IS NULL
            AND pending_command_sha256 IS NULL)
    ),
    CONSTRAINT ck_customer_rental_session_last_selection CHECK (
        (last_selection_key IS NULL AND last_selection_sha256 IS NULL)
        OR
        (last_selection_key IS NOT NULL AND last_selection_sha256 ~ '^[0-9a-f]{64}$')
    ),
    CONSTRAINT ck_customer_rental_session_booking CHECK (
        (state = 'BOOKED'
            AND booking_id IS NOT NULL
            AND order_id IS NOT NULL
            AND presentation_token IS NOT NULL)
        OR state <> 'BOOKED'
    ),
    CONSTRAINT ck_customer_rental_session_checkout_key CHECK (
        (checkout_command_key IS NULL AND checkout_command_sha256 IS NULL)
        OR
        (checkout_command_key IS NOT NULL AND checkout_command_sha256 ~ '^[0-9a-f]{64}$')
    )
);

CREATE INDEX idx_customer_rental_session_subject_created
    ON public.customer_rental_session (customer_subject_id, created_at DESC, id DESC);
CREATE INDEX idx_customer_rental_session_state_updated
    ON public.customer_rental_session (state, updated_at, id);

CREATE TABLE public.customer_delivery_slot (
    id uuid NOT NULL,
    version bigint NOT NULL DEFAULT 0,
    customer_subject_id uuid NOT NULL,
    inquiry_id uuid NOT NULL,
    warehouse_id uuid NOT NULL,
    delivery_date date NOT NULL,
    window_start time NOT NULL,
    window_end time NOT NULL,
    delivery_address varchar(1000) NOT NULL,
    latitude numeric(8,6) NOT NULL,
    longitude numeric(9,6) NOT NULL,
    cabin_count integer NOT NULL,
    one_way_travel_seconds bigint NOT NULL,
    travel_zone_hours integer NOT NULL,
    capacity_remaining integer NOT NULL,
    state varchar(32) NOT NULL,
    expires_at timestamptz NOT NULL,
    booking_id uuid,
    order_id uuid,
    created_at timestamptz NOT NULL,
    updated_at timestamptz NOT NULL,
    CONSTRAINT customer_delivery_slot_pkey PRIMARY KEY (id),
    CONSTRAINT fk_customer_delivery_slot_inquiry FOREIGN KEY (inquiry_id)
        REFERENCES public.rental_inquiry(id),
    CONSTRAINT ck_customer_delivery_slot_version CHECK (version >= 0),
    CONSTRAINT ck_customer_delivery_slot_window CHECK (window_start < window_end),
    CONSTRAINT ck_customer_delivery_slot_address CHECK (
        length(btrim(delivery_address)) BETWEEN 1 AND 1000
    ),
    CONSTRAINT ck_customer_delivery_slot_coordinates CHECK (
        latitude BETWEEN -90 AND 90 AND longitude BETWEEN -180 AND 180
    ),
    CONSTRAINT ck_customer_delivery_slot_capacity CHECK (
        cabin_count > 0
        AND one_way_travel_seconds >= 0
        AND travel_zone_hours BETWEEN 1 AND 4
        AND capacity_remaining >= 0
    ),
    CONSTRAINT ck_customer_delivery_slot_state CHECK (
        state IN ('OFFERED', 'HELD', 'CHECKOUT_PENDING', 'CONFIRMED', 'RELEASED')
    ),
    CONSTRAINT ck_customer_delivery_slot_booking CHECK (
        (state = 'CONFIRMED' AND booking_id IS NOT NULL AND order_id IS NOT NULL)
        OR
        (state = 'CHECKOUT_PENDING' AND booking_id IS NOT NULL)
        OR
        (state NOT IN ('CHECKOUT_PENDING', 'CONFIRMED')
            AND booking_id IS NULL AND order_id IS NULL)
    )
);

CREATE INDEX idx_customer_delivery_slot_capacity
    ON public.customer_delivery_slot (
        warehouse_id, delivery_date, window_start, state, expires_at, id
    );
CREATE INDEX idx_customer_delivery_slot_inquiry
    ON public.customer_delivery_slot (inquiry_id, state, created_at DESC, id DESC);
CREATE UNIQUE INDEX uk_customer_delivery_slot_confirmed_order
    ON public.customer_delivery_slot (order_id)
    WHERE state = 'CONFIRMED';

CREATE UNIQUE INDEX uk_customer_delivery_slot_booking
    ON public.customer_delivery_slot (booking_id)
    WHERE booking_id IS NOT NULL;
