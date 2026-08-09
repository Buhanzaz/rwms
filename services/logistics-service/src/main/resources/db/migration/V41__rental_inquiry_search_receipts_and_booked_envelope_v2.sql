-- Rental-inquiry cabin search is a remotely effective command. The receipt freezes both the
-- public request identity and the exact asset-service request bytes before any remote call.
CREATE TABLE public.rental_inquiry_search_attempt (
    id uuid NOT NULL,
    version bigint NOT NULL DEFAULT 0,
    inquiry_id uuid NOT NULL,
    subject_id uuid NOT NULL,
    operation_name varchar(64) NOT NULL,
    public_idempotency_key uuid NOT NULL,
    request_sha256 char(64) NOT NULL,
    warehouse_id uuid NOT NULL,
    downstream_idempotency_key uuid NOT NULL,
    downstream_request_body text NOT NULL,
    downstream_request_sha256 char(64) NOT NULL,
    actor_role varchar(32) NOT NULL,
    hold_expires_at timestamptz NOT NULL,
    state varchar(16) NOT NULL,
    response_body text,
    rejection_code varchar(64),
    created_at timestamptz NOT NULL,
    updated_at timestamptz NOT NULL,
    terminal_at timestamptz,
    CONSTRAINT rental_inquiry_search_attempt_pkey PRIMARY KEY (id),
    CONSTRAINT fk_rental_inquiry_search_attempt_inquiry
        FOREIGN KEY (inquiry_id) REFERENCES public.rental_inquiry(id),
    CONSTRAINT uk_rental_inquiry_search_attempt_subject_operation_key
        UNIQUE (subject_id, operation_name, public_idempotency_key),
    CONSTRAINT uk_rental_inquiry_search_attempt_downstream_key
        UNIQUE (downstream_idempotency_key),
    CONSTRAINT ck_rental_inquiry_search_attempt_version CHECK (version >= 0),
    CONSTRAINT ck_rental_inquiry_search_attempt_operation CHECK (
        operation_name = 'RENTAL_INQUIRY_CABIN_SEARCH'
    ),
    CONSTRAINT ck_rental_inquiry_search_attempt_request_hash CHECK (
        request_sha256 ~ '^[0-9a-f]{64}$'
    ),
    CONSTRAINT ck_rental_inquiry_search_attempt_downstream_hash CHECK (
        downstream_request_sha256 ~ '^[0-9a-f]{64}$'
    ),
    CONSTRAINT ck_rental_inquiry_search_attempt_request_body CHECK (
        octet_length(downstream_request_body) BETWEEN 2 AND 65535
    ),
    CONSTRAINT ck_rental_inquiry_search_attempt_actor_role CHECK (
        actor_role IN (
            'SYSTEM_ADMIN', 'WMS_ADMIN', 'WAREHOUSE_MANAGER',
            'RENTAL_MANAGER', 'VIEWER'
        )
    ),
    CONSTRAINT ck_rental_inquiry_search_attempt_lifetime CHECK (
        hold_expires_at > created_at
    ),
    CONSTRAINT ck_rental_inquiry_search_attempt_state CHECK (
        state IN ('PREPARED', 'COMPLETED', 'REJECTED', 'EXPIRED')
    ),
    CONSTRAINT ck_rental_inquiry_search_attempt_terminal CHECK (
        (
            state = 'PREPARED'
            AND response_body IS NULL
            AND rejection_code IS NULL
            AND terminal_at IS NULL
        )
        OR (
            state = 'COMPLETED'
            AND response_body IS NOT NULL
            AND rejection_code IS NULL
            AND terminal_at IS NOT NULL
        )
        OR (
            state = 'REJECTED'
            AND response_body IS NULL
            AND rejection_code IS NOT NULL
            AND terminal_at IS NOT NULL
        )
        OR (
            state = 'EXPIRED'
            AND response_body IS NULL
            AND rejection_code IS NULL
            AND terminal_at IS NOT NULL
        )
    )
);

-- The inquiry row is locked before preparing a search. This partial index is the final database
-- fence against two live remote hold commands for the same inquiry.
CREATE UNIQUE INDEX uk_rental_inquiry_search_attempt_one_prepared
    ON public.rental_inquiry_search_attempt (inquiry_id)
    WHERE state = 'PREPARED';

CREATE INDEX idx_rental_inquiry_search_attempt_inquiry_created
    ON public.rental_inquiry_search_attempt (inquiry_id, created_at DESC, id);

-- V19 payloads predate DomainEventEnvelopeV2. Fail closed if a legacy row cannot be related back
-- to its immutable booking context; silently inventing actor, causation, or aggregate revision
-- would make a published fact unverifiable.
DO $$
BEGIN
    IF EXISTS (
        SELECT 1
        FROM public.rental_inquiry_outbox outbox
        LEFT JOIN public.rental_inquiry inquiry
            ON inquiry.id = outbox.inquiry_id
        WHERE outbox.payload ->> 'envelopeVersion' IS DISTINCT FROM '2'
          AND (
              inquiry.id IS NULL
              OR inquiry.conversation_id <> outbox.conversation_id
              OR inquiry.booked_order_id <> outbox.order_id
              OR (
                  SELECT count(*)
                  FROM public.client_presentation presentation
                  JOIN public.presentation_booking booking
                      ON booking.presentation_id = presentation.id
                  WHERE presentation.inquiry_id = outbox.inquiry_id
                    AND booking.order_id = outbox.order_id
                    AND booking.state = 'COMPLETED'
              ) <> 1
          )
    ) THEN
        RAISE EXCEPTION
            'Cannot canonicalize a rental inquiry outbox row without its completed booking context';
    END IF;
END $$;

-- Preserve event_id, conversation key, occurrence time and delivery state. In particular, a
-- published row remains published and can never become relayable again.
UPDATE public.rental_inquiry_outbox outbox
SET payload = jsonb_build_object(
        'envelopeVersion', 2,
        'eventId', outbox.event_id,
        'eventType', outbox.event_type,
        'eventVersion', 1,
        'occurredAt', COALESCE(
            NULLIF(outbox.payload ->> 'occurredAt', '')::timestamptz,
            outbox.created_at
        ),
        'recordedAt', outbox.created_at,
        'producer', 'logistics-service',
        'aggregateType', 'RENTAL_INQUIRY',
        'aggregateId', outbox.inquiry_id::text,
        'aggregateVersion', inquiry.version,
        'correlation', jsonb_build_object(
            'correlationId', outbox.conversation_id,
            'causationId', booking.id
        ),
        'actorRef', jsonb_build_object(
            'subjectId', inquiry.manager_id,
            'principalType', 'USER',
            'profileRevision', NULL
        ),
        'payload', jsonb_build_object(
            'conversationId', outbox.conversation_id,
            'orderId', outbox.order_id
        )
    )
FROM public.rental_inquiry inquiry
JOIN public.client_presentation presentation
    ON presentation.inquiry_id = inquiry.id
JOIN public.presentation_booking booking
    ON booking.presentation_id = presentation.id
    AND booking.state = 'COMPLETED'
WHERE outbox.payload ->> 'envelopeVersion' IS DISTINCT FROM '2'
  AND inquiry.id = outbox.inquiry_id
  AND booking.order_id = outbox.order_id;

ALTER TABLE public.rental_inquiry_outbox
    DROP CONSTRAINT ck_rental_inquiry_outbox_payload,
    ADD CONSTRAINT ck_rental_inquiry_outbox_payload CHECK (
        jsonb_typeof(payload) = 'object'
        AND payload ->> 'envelopeVersion' = '2'
        AND payload ->> 'eventId' = event_id::text
        AND payload ->> 'eventType' = event_type
        AND payload ->> 'eventVersion' = '1'
        AND payload ->> 'producer' = 'logistics-service'
        AND payload ->> 'aggregateType' = 'RENTAL_INQUIRY'
        AND payload ->> 'aggregateId' = inquiry_id::text
        AND payload #>> '{correlation,correlationId}' = conversation_id::text
        AND payload #>> '{payload,conversationId}' = conversation_id::text
        AND payload #>> '{payload,orderId}' = order_id::text
        AND jsonb_typeof(payload -> 'payload') = 'object'
    );

DO $$
BEGIN
    IF EXISTS (
        SELECT 1
        FROM public.rental_inquiry_outbox
        WHERE payload ->> 'eventId' <> event_id::text
           OR payload #>> '{correlation,correlationId}' <> conversation_id::text
           OR payload #>> '{payload,orderId}' <> order_id::text
           OR status NOT IN ('PENDING', 'PUBLISHED')
    ) THEN
        RAISE EXCEPTION 'Rental inquiry outbox V2 canonicalization changed a durable identity';
    END IF;
END $$;
