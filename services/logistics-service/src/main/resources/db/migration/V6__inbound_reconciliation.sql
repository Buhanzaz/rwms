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
            'REQUEST_RETURN_ESTIMATE',
            'PLAN_SHIPMENT',
            'CONFIRM_SHIPMENT',
            'CANCEL_SHIPMENT',
            'DEPART_TRANSFER_LINE',
            'ARRIVE_TRANSFER_LINE',
            'CANCEL_TRANSFER',
            'RECONCILE_DOCUMENT'
        )
    );

ALTER TABLE public.inbox_message
    ADD COLUMN source_topic varchar(200),
    ADD COLUMN event_type varchar(160),
    ADD COLUMN envelope_body jsonb;

ALTER TABLE public.inbox_message
    ADD CONSTRAINT ck_logistics_inbox_source_topic CHECK (
        source_topic IS NULL OR source_topic IN (
            'rwms.asset.rental-item.v1',
            'rwms.asset.operation-lease.v1',
            'rwms.asset.equipment-allocation-hold.v1',
            'rwms.task-board.board-task.v1',
            'rwms.maintenance.estimate.v1',
            'rwms.media.media.v1'
        )
    ),
    ADD CONSTRAINT ck_logistics_inbox_event_type CHECK (
        event_type IS NULL OR event_type ~ '^[a-z][a-z0-9.-]{0,159}$'
    ),
    ADD CONSTRAINT ck_logistics_inbox_envelope CHECK (
        envelope_body IS NULL OR jsonb_typeof(envelope_body) = 'object'
    );

CREATE TABLE public.logistics_inbound_replay_message (
    event_id uuid NOT NULL,
    source_topic varchar(200) NOT NULL,
    kafka_key varchar(128) NOT NULL,
    aggregate_type varchar(64) NOT NULL,
    aggregate_id varchar(128) NOT NULL,
    aggregate_version bigint NOT NULL,
    event_type varchar(160) NOT NULL,
    recorded_at timestamptz NOT NULL,
    envelope_body jsonb NOT NULL,
    message_sha256 char(64) NOT NULL,
    state varchar(24) NOT NULL,
    staged_at timestamptz NOT NULL,
    updated_at timestamptz NOT NULL,
    CONSTRAINT logistics_inbound_replay_message_pkey PRIMARY KEY (event_id),
    CONSTRAINT ck_logistics_inbound_replay_key CHECK (
        kafka_key = aggregate_id
    ),
    CONSTRAINT ck_logistics_inbound_replay_version CHECK (
        aggregate_version >= 0
    ),
    CONSTRAINT ck_logistics_inbound_replay_hash CHECK (
        message_sha256 ~ '^[0-9a-f]{64}$'
    ),
    CONSTRAINT ck_logistics_inbound_replay_envelope CHECK (
        jsonb_typeof(envelope_body) = 'object'
    ),
    CONSTRAINT ck_logistics_inbound_replay_state CHECK (
        state IN ('STAGED', 'APPLIED', 'DLT', 'REPLAY_APPROVED', 'REJECTED')
    )
);

CREATE INDEX idx_logistics_inbound_replay_stream
    ON public.logistics_inbound_replay_message (
        source_topic, aggregate_type, aggregate_id, aggregate_version, event_id
    );

CREATE TABLE public.logistics_inbound_observation (
    consumer_group varchar(128) NOT NULL,
    event_id uuid NOT NULL,
    source_topic varchar(200) NOT NULL,
    aggregate_type varchar(64) NOT NULL,
    aggregate_id varchar(128) NOT NULL,
    aggregate_version bigint NOT NULL,
    event_type varchar(160) NOT NULL,
    payload_sha256 char(64) NOT NULL,
    recorded_at timestamptz NOT NULL,
    CONSTRAINT logistics_inbound_observation_pkey PRIMARY KEY (consumer_group, event_id),
    CONSTRAINT ck_logistics_inbound_observation_version CHECK (
        aggregate_version >= 0
    ),
    CONSTRAINT ck_logistics_inbound_observation_hash CHECK (
        payload_sha256 ~ '^[0-9a-f]{64}$'
    )
);

CREATE INDEX idx_logistics_inbound_observation_stream
    ON public.logistics_inbound_observation (
        consumer_group, source_topic, aggregate_type, aggregate_id, aggregate_version, event_id
    );

CREATE TABLE public.logistics_reconciliation_request (
    id uuid NOT NULL,
    document_id uuid NOT NULL,
    request_reason varchar(500) NOT NULL,
    requested_by_subject_id uuid NOT NULL,
    correlation_id uuid NOT NULL,
    created_at timestamptz NOT NULL,
    CONSTRAINT logistics_reconciliation_request_pkey PRIMARY KEY (id),
    CONSTRAINT fk_logistics_reconciliation_request_document
        FOREIGN KEY (document_id) REFERENCES public.logistics_document (id),
    CONSTRAINT ck_logistics_reconciliation_request_reason CHECK (
        btrim(request_reason) <> ''
    )
);

CREATE INDEX idx_logistics_reconciliation_request_document_created
    ON public.logistics_reconciliation_request (document_id, created_at DESC, id);

ALTER TABLE public.sanitized_dead_letter
    DROP CONSTRAINT ck_logistics_dead_letter_destination;

ALTER TABLE public.sanitized_dead_letter
    ADD CONSTRAINT ck_logistics_dead_letter_destination CHECK (
        destination LIKE 'rwms.logistics.return.v1.%.dlt'
        OR destination LIKE 'rwms.logistics.shipment.v1.%.dlt'
        OR destination LIKE 'rwms.logistics.transfer.v1.%.dlt'
        OR destination = 'rwms.logistics.inbound.v1.dlt'
    );

ALTER TABLE public.sanitized_dead_letter
    DROP CONSTRAINT ck_logistics_dead_letter_failure_code;

ALTER TABLE public.sanitized_dead_letter
    ADD CONSTRAINT ck_logistics_dead_letter_failure_code CHECK (
        failure_code IN (
            'VALIDATION_REJECTED',
            'PROCESSING_FAILED',
            'VERSION_GAP',
            'EVENT_ID_CONFLICT'
        )
    );
