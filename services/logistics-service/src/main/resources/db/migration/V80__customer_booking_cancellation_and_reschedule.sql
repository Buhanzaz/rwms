ALTER TABLE public.customer_rental_session
    DROP CONSTRAINT ck_customer_rental_session_state,
    DROP CONSTRAINT ck_customer_rental_session_pending,
    DROP CONSTRAINT ck_customer_rental_session_booking;

ALTER TABLE public.customer_rental_session
    ADD CONSTRAINT ck_customer_rental_session_state CHECK (
        state IN (
            'ACTIVE', 'SELECTION_PENDING', 'CHECKOUT_PENDING', 'BOOKED',
            'CANCEL_PENDING', 'CANCELLED'
        )
    ),
    ADD CONSTRAINT ck_customer_rental_session_pending CHECK (
        (
            state IN ('SELECTION_PENDING', 'CHECKOUT_PENDING')
            AND pending_command_key IS NOT NULL
            AND pending_command_sha256 ~ '^[0-9a-f]{64}$'
        )
        OR
        (
            state IN ('ACTIVE', 'BOOKED', 'CANCEL_PENDING', 'CANCELLED')
            AND pending_command_key IS NULL
            AND pending_command_sha256 IS NULL
        )
    ),
    ADD CONSTRAINT ck_customer_rental_session_booking CHECK (
        (
            state IN ('BOOKED', 'CANCEL_PENDING', 'CANCELLED')
            AND booking_id IS NOT NULL
            AND order_id IS NOT NULL
            AND presentation_token IS NOT NULL
        )
        OR state NOT IN ('BOOKED', 'CANCEL_PENDING', 'CANCELLED')
    );

ALTER TABLE public.customer_delivery_slot
    DROP CONSTRAINT ck_customer_delivery_slot_booking;

ALTER TABLE public.customer_delivery_slot
    ADD CONSTRAINT ck_customer_delivery_slot_booking CHECK (
        (state = 'CONFIRMED' AND booking_id IS NOT NULL AND order_id IS NOT NULL)
        OR (state = 'CHECKOUT_PENDING' AND booking_id IS NOT NULL)
        OR (
            state = 'RELEASED'
            AND (
                (booking_id IS NULL AND order_id IS NULL)
                OR (booking_id IS NOT NULL AND order_id IS NOT NULL)
            )
        )
        OR (
            state NOT IN ('CHECKOUT_PENDING', 'CONFIRMED', 'RELEASED')
            AND booking_id IS NULL
            AND order_id IS NULL
        )
    );

DROP INDEX public.uk_customer_delivery_slot_booking;

CREATE UNIQUE INDEX uk_customer_delivery_slot_booking
    ON public.customer_delivery_slot (booking_id)
    WHERE state IN ('CHECKOUT_PENDING', 'CONFIRMED');

CREATE TABLE public.customer_booking_mutation (
    id uuid NOT NULL,
    version bigint NOT NULL DEFAULT 0,
    customer_subject_id uuid NOT NULL,
    booking_id uuid NOT NULL,
    inquiry_id uuid NOT NULL,
    order_id uuid NOT NULL,
    operation varchar(32) NOT NULL,
    state varchar(32) NOT NULL,
    idempotency_key uuid NOT NULL,
    request_sha256 varchar(64) NOT NULL,
    expected_session_version bigint NOT NULL,
    expected_order_version bigint NOT NULL,
    old_slot_id uuid NOT NULL,
    new_slot_id uuid,
    attempt_count integer NOT NULL DEFAULT 0,
    next_attempt_at timestamptz,
    lease_token uuid,
    lease_until timestamptz,
    last_error_code varchar(64),
    completed_at timestamptz,
    created_at timestamptz NOT NULL,
    updated_at timestamptz NOT NULL,
    CONSTRAINT customer_booking_mutation_pkey PRIMARY KEY (id),
    CONSTRAINT fk_customer_booking_mutation_inquiry FOREIGN KEY (inquiry_id)
        REFERENCES public.rental_inquiry(id),
    CONSTRAINT fk_customer_booking_mutation_order FOREIGN KEY (order_id)
        REFERENCES public.rental_order(id),
    CONSTRAINT fk_customer_booking_mutation_old_slot FOREIGN KEY (old_slot_id)
        REFERENCES public.customer_delivery_slot(id),
    CONSTRAINT fk_customer_booking_mutation_new_slot FOREIGN KEY (new_slot_id)
        REFERENCES public.customer_delivery_slot(id),
    CONSTRAINT uk_customer_booking_mutation_subject_key
        UNIQUE (customer_subject_id, idempotency_key),
    CONSTRAINT ck_customer_booking_mutation_version CHECK (version >= 0),
    CONSTRAINT ck_customer_booking_mutation_operation CHECK (
        operation IN ('CANCEL', 'RESCHEDULE')
    ),
    CONSTRAINT ck_customer_booking_mutation_state CHECK (
        state IN ('PENDING', 'COMPLETED', 'QUARANTINED')
    ),
    CONSTRAINT ck_customer_booking_mutation_hash CHECK (
        request_sha256 ~ '^[0-9a-f]{64}$'
    ),
    CONSTRAINT ck_customer_booking_mutation_versions CHECK (
        expected_session_version >= 0 AND expected_order_version >= 0
    ),
    CONSTRAINT ck_customer_booking_mutation_attempts CHECK (attempt_count >= 0),
    CONSTRAINT ck_customer_booking_mutation_slot CHECK (
        (operation = 'CANCEL' AND new_slot_id IS NULL)
        OR (operation = 'RESCHEDULE' AND new_slot_id IS NOT NULL)
    ),
    CONSTRAINT ck_customer_booking_mutation_lease CHECK (
        (lease_token IS NULL) = (lease_until IS NULL)
    ),
    CONSTRAINT ck_customer_booking_mutation_lifecycle CHECK (
        (
            state = 'PENDING'
            AND operation = 'CANCEL'
            AND next_attempt_at IS NOT NULL
            AND completed_at IS NULL
        )
        OR (
            state = 'QUARANTINED'
            AND operation = 'CANCEL'
            AND next_attempt_at IS NULL
            AND lease_token IS NULL
            AND lease_until IS NULL
            AND completed_at IS NULL
        )
        OR (
            state = 'COMPLETED'
            AND next_attempt_at IS NULL
            AND lease_token IS NULL
            AND lease_until IS NULL
            AND completed_at IS NOT NULL
        )
    )
);

CREATE UNIQUE INDEX uk_customer_booking_mutation_open_booking
    ON public.customer_booking_mutation (booking_id)
    WHERE state IN ('PENDING', 'QUARANTINED');

CREATE INDEX idx_customer_booking_mutation_open_order
    ON public.customer_booking_mutation (order_id)
    WHERE operation = 'CANCEL' AND state IN ('PENDING', 'QUARANTINED');

CREATE INDEX idx_customer_booking_mutation_due
    ON public.customer_booking_mutation (next_attempt_at, created_at, id)
    WHERE operation = 'CANCEL' AND state = 'PENDING';

CREATE INDEX idx_customer_booking_mutation_booking_history
    ON public.customer_booking_mutation (booking_id, created_at DESC, id DESC);
