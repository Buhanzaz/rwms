CREATE TABLE public.logistics_document (
    id uuid NOT NULL,
    version bigint NOT NULL,
    document_type varchar(16) NOT NULL,
    state varchar(40) NOT NULL,
    warehouse_id uuid NOT NULL,
    destination_warehouse_id uuid,
    party_snapshot varchar(512),
    driver_snapshot varchar(512),
    requested_by_subject_id uuid NOT NULL,
    correlation_id uuid NOT NULL,
    created_at timestamptz NOT NULL,
    updated_at timestamptz NOT NULL,
    CONSTRAINT logistics_document_pkey PRIMARY KEY (id),
    CONSTRAINT ck_logistics_document_version CHECK (version >= 0),
    CONSTRAINT ck_logistics_document_type CHECK (
        document_type IN ('RETURN', 'SHIPMENT', 'TRANSFER')
    ),
    CONSTRAINT ck_logistics_document_state CHECK (
        state IN (
            'DRAFT', 'REGISTERING', 'INSPECTION_REQUIRED', 'ACCEPTING', 'ACCEPTED',
            'ESTIMATE_PENDING', 'ESTIMATE_REQUESTED', 'PREPARING',
            'AWAITING_CONFIRMATION', 'CONFIRMING_PREPARATION', 'SHIPPED',
            'DEPARTING', 'IN_TRANSIT', 'ARRIVING', 'COMPLETED', 'CANCELLED',
            'CONFLICT', 'RECONCILIATION_REQUIRED'
        )
    ),
    CONSTRAINT ck_logistics_document_scope CHECK (
        (document_type = 'TRANSFER'
            AND destination_warehouse_id IS NOT NULL
            AND destination_warehouse_id <> warehouse_id)
        OR
        (document_type IN ('RETURN', 'SHIPMENT')
            AND destination_warehouse_id IS NULL)
    ),
    CONSTRAINT ck_logistics_document_shipment_snapshot CHECK (
        (document_type = 'SHIPMENT'
            AND party_snapshot IS NOT NULL
            AND btrim(party_snapshot) <> ''
            AND driver_snapshot IS NOT NULL
            AND btrim(driver_snapshot) <> '')
        OR document_type <> 'SHIPMENT'
    )
);

CREATE INDEX idx_logistics_document_warehouse_type_created
    ON public.logistics_document (warehouse_id, document_type, created_at DESC, id);
CREATE INDEX idx_logistics_document_destination_created
    ON public.logistics_document (destination_warehouse_id, created_at DESC, id)
    WHERE destination_warehouse_id IS NOT NULL;

CREATE TABLE public.logistics_document_line (
    id uuid NOT NULL,
    version bigint NOT NULL,
    document_id uuid NOT NULL,
    line_number integer NOT NULL,
    asset_id uuid NOT NULL,
    asset_version bigint NOT NULL,
    state varchar(16) NOT NULL,
    tenant_snapshot varchar(512),
    passport_snapshot jsonb,
    expected_contents_snapshot jsonb,
    factual_contents_snapshot jsonb,
    source_allocation_snapshot jsonb,
    created_at timestamptz NOT NULL,
    updated_at timestamptz NOT NULL,
    CONSTRAINT logistics_document_line_pkey PRIMARY KEY (id),
    CONSTRAINT fk_logistics_document_line_document
        FOREIGN KEY (document_id) REFERENCES public.logistics_document (id),
    CONSTRAINT uk_logistics_document_line_number UNIQUE (document_id, line_number),
    CONSTRAINT uk_logistics_document_line_asset UNIQUE (document_id, asset_id),
    CONSTRAINT ck_logistics_document_line_version CHECK (version >= 0 AND asset_version >= 0),
    CONSTRAINT ck_logistics_document_line_number CHECK (line_number >= 1),
    CONSTRAINT ck_logistics_document_line_state CHECK (
        state IN ('PENDING', 'DEPARTING', 'DEPARTED', 'ARRIVING', 'ARRIVED', 'CONFLICT', 'CANCELLED')
    ),
    CONSTRAINT ck_logistics_document_line_json CHECK (
        (passport_snapshot IS NULL OR jsonb_typeof(passport_snapshot) = 'object')
        AND (expected_contents_snapshot IS NULL OR jsonb_typeof(expected_contents_snapshot) = 'object')
        AND (factual_contents_snapshot IS NULL OR jsonb_typeof(factual_contents_snapshot) = 'object')
        AND (source_allocation_snapshot IS NULL OR jsonb_typeof(source_allocation_snapshot) = 'object')
    )
);

CREATE INDEX idx_logistics_document_line_asset
    ON public.logistics_document_line (asset_id, state, document_id);

CREATE TABLE public.logistics_guard (
    id uuid NOT NULL,
    document_id uuid NOT NULL,
    line_id uuid NOT NULL,
    asset_id uuid NOT NULL,
    guard_state varchar(32) NOT NULL,
    lease_id uuid,
    fence_token bigint,
    observed_asset_version bigint,
    acquired_at timestamptz,
    released_at timestamptz,
    created_at timestamptz NOT NULL,
    updated_at timestamptz NOT NULL,
    CONSTRAINT logistics_guard_pkey PRIMARY KEY (id),
    CONSTRAINT fk_logistics_guard_document
        FOREIGN KEY (document_id) REFERENCES public.logistics_document (id),
    CONSTRAINT fk_logistics_guard_line
        FOREIGN KEY (line_id) REFERENCES public.logistics_document_line (id),
    CONSTRAINT uk_logistics_guard_line UNIQUE (line_id),
    CONSTRAINT ck_logistics_guard_state CHECK (
        guard_state IN ('PENDING', 'ACTIVE', 'RELEASED', 'CONFLICT', 'RECONCILIATION_REQUIRED')
    ),
    CONSTRAINT ck_logistics_guard_fence CHECK (
        fence_token IS NULL OR fence_token >= 0
    )
);

CREATE UNIQUE INDEX uk_logistics_guard_active_asset
    ON public.logistics_guard (asset_id)
    WHERE guard_state IN ('PENDING', 'ACTIVE');

CREATE TABLE public.logistics_external_attempt (
    id uuid NOT NULL,
    document_id uuid NOT NULL,
    line_id uuid,
    operation_id uuid NOT NULL,
    target_service varchar(32) NOT NULL,
    operation_type varchar(64) NOT NULL,
    request_sha256 char(64) NOT NULL,
    response_sha256 char(64),
    result varchar(32) NOT NULL,
    retry_count integer NOT NULL DEFAULT 0,
    next_attempt_at timestamptz,
    correlation_id uuid NOT NULL,
    causation_id uuid,
    created_at timestamptz NOT NULL,
    completed_at timestamptz,
    CONSTRAINT logistics_external_attempt_pkey PRIMARY KEY (id),
    CONSTRAINT fk_logistics_external_attempt_document
        FOREIGN KEY (document_id) REFERENCES public.logistics_document (id),
    CONSTRAINT fk_logistics_external_attempt_line
        FOREIGN KEY (line_id) REFERENCES public.logistics_document_line (id),
    CONSTRAINT uk_logistics_external_attempt_operation UNIQUE (operation_id),
    CONSTRAINT ck_logistics_external_attempt_target CHECK (
        target_service IN ('ASSET', 'TASK_BOARD', 'MAINTENANCE', 'WAREHOUSE', 'MEDIA')
    ),
    CONSTRAINT ck_logistics_external_attempt_result CHECK (
        result IN ('PENDING', 'CONFIRMED', 'PERMANENT_REJECTION', 'RETRY', 'RECONCILIATION_REQUIRED')
    ),
    CONSTRAINT ck_logistics_external_attempt_hash CHECK (
        request_sha256 ~ '^[0-9a-f]{64}$'
        AND (response_sha256 IS NULL OR response_sha256 ~ '^[0-9a-f]{64}$')
    ),
    CONSTRAINT ck_logistics_external_attempt_retry CHECK (retry_count >= 0)
);

CREATE INDEX idx_logistics_external_attempt_retry
    ON public.logistics_external_attempt (next_attempt_at, created_at, id)
    WHERE result = 'RETRY';

CREATE TABLE public.logistics_media_reference (
    id uuid NOT NULL,
    document_id uuid NOT NULL,
    line_id uuid NOT NULL,
    media_id uuid NOT NULL,
    purpose varchar(32) NOT NULL,
    readiness varchar(24) NOT NULL,
    owner_verified_at timestamptz,
    created_at timestamptz NOT NULL,
    CONSTRAINT logistics_media_reference_pkey PRIMARY KEY (id),
    CONSTRAINT fk_logistics_media_reference_document
        FOREIGN KEY (document_id) REFERENCES public.logistics_document (id),
    CONSTRAINT fk_logistics_media_reference_line
        FOREIGN KEY (line_id) REFERENCES public.logistics_document_line (id),
    CONSTRAINT uk_logistics_media_reference UNIQUE (line_id, media_id, purpose),
    CONSTRAINT ck_logistics_media_reference_purpose CHECK (
        purpose IN ('RETURN_INSPECTION', 'TRANSFER_ARRIVAL')
    ),
    CONSTRAINT ck_logistics_media_reference_readiness CHECK (
        readiness IN ('PENDING', 'READY', 'REJECTED', 'RECONCILIATION_REQUIRED')
    )
);

CREATE TABLE public.logistics_equipment_hold_reference (
    id uuid NOT NULL,
    document_id uuid NOT NULL,
    line_id uuid NOT NULL,
    hold_id uuid NOT NULL,
    hold_state varchar(24) NOT NULL,
    created_at timestamptz NOT NULL,
    updated_at timestamptz NOT NULL,
    CONSTRAINT logistics_equipment_hold_reference_pkey PRIMARY KEY (id),
    CONSTRAINT fk_logistics_equipment_hold_document
        FOREIGN KEY (document_id) REFERENCES public.logistics_document (id),
    CONSTRAINT fk_logistics_equipment_hold_line
        FOREIGN KEY (line_id) REFERENCES public.logistics_document_line (id),
    CONSTRAINT uk_logistics_equipment_hold UNIQUE (hold_id),
    CONSTRAINT ck_logistics_equipment_hold_state CHECK (
        hold_state IN ('ACTIVE', 'COMMITTED', 'RELEASED', 'CONFLICT', 'RECONCILIATION_REQUIRED')
    )
);

CREATE TABLE public.logistics_task_reference (
    id uuid NOT NULL,
    document_id uuid NOT NULL,
    line_id uuid NOT NULL,
    external_task_id uuid NOT NULL,
    task_state varchar(24) NOT NULL,
    created_at timestamptz NOT NULL,
    updated_at timestamptz NOT NULL,
    CONSTRAINT logistics_task_reference_pkey PRIMARY KEY (id),
    CONSTRAINT fk_logistics_task_reference_document
        FOREIGN KEY (document_id) REFERENCES public.logistics_document (id),
    CONSTRAINT fk_logistics_task_reference_line
        FOREIGN KEY (line_id) REFERENCES public.logistics_document_line (id),
    CONSTRAINT uk_logistics_task_reference_line UNIQUE (line_id),
    CONSTRAINT uk_logistics_task_reference_external UNIQUE (external_task_id),
    CONSTRAINT ck_logistics_task_reference_state CHECK (
        task_state IN ('REGISTERED', 'READY', 'CANCELLED', 'CONFLICT', 'RECONCILIATION_REQUIRED')
    )
);

CREATE TABLE public.logistics_reconciliation (
    id uuid NOT NULL,
    document_id uuid NOT NULL,
    line_id uuid,
    state varchar(16) NOT NULL,
    reason_code varchar(64) NOT NULL,
    opened_at timestamptz NOT NULL,
    resolved_at timestamptz,
    resolution_reason varchar(500),
    resolved_by_subject_id uuid,
    CONSTRAINT logistics_reconciliation_pkey PRIMARY KEY (id),
    CONSTRAINT fk_logistics_reconciliation_document
        FOREIGN KEY (document_id) REFERENCES public.logistics_document (id),
    CONSTRAINT fk_logistics_reconciliation_line
        FOREIGN KEY (line_id) REFERENCES public.logistics_document_line (id),
    CONSTRAINT ck_logistics_reconciliation_state CHECK (state IN ('OPEN', 'RESOLVED')),
    CONSTRAINT ck_logistics_reconciliation_resolution CHECK (
        (state = 'OPEN' AND resolved_at IS NULL AND resolution_reason IS NULL AND resolved_by_subject_id IS NULL)
        OR
        (state = 'RESOLVED' AND resolved_at IS NOT NULL AND resolution_reason IS NOT NULL
            AND btrim(resolution_reason) <> '' AND resolved_by_subject_id IS NOT NULL)
    )
);

CREATE UNIQUE INDEX uk_logistics_reconciliation_open_document
    ON public.logistics_reconciliation (document_id, coalesce(line_id, '00000000-0000-0000-0000-000000000000'::uuid))
    WHERE state = 'OPEN';

CREATE TABLE public.logistics_idempotency_record (
    id uuid NOT NULL,
    subject_id uuid NOT NULL,
    operation_name varchar(64) NOT NULL,
    idempotency_key uuid NOT NULL,
    request_sha256 char(64) NOT NULL,
    document_id uuid NOT NULL,
    response_document_version bigint NOT NULL,
    created_at timestamptz NOT NULL,
    expires_at timestamptz NOT NULL,
    CONSTRAINT logistics_idempotency_record_pkey PRIMARY KEY (id),
    CONSTRAINT fk_logistics_idempotency_document
        FOREIGN KEY (document_id) REFERENCES public.logistics_document (id),
    CONSTRAINT uk_logistics_idempotency_subject_operation_key
        UNIQUE (subject_id, operation_name, idempotency_key),
    CONSTRAINT ck_logistics_idempotency_operation CHECK (
        operation_name IN ('CREATE_RETURN', 'CREATE_SHIPMENT', 'CREATE_TRANSFER')
    ),
    CONSTRAINT ck_logistics_idempotency_hash CHECK (request_sha256 ~ '^[0-9a-f]{64}$'),
    CONSTRAINT ck_logistics_idempotency_version CHECK (response_document_version >= 0),
    CONSTRAINT ck_logistics_idempotency_expiry CHECK (expires_at > created_at)
);

CREATE INDEX idx_logistics_idempotency_expiry
    ON public.logistics_idempotency_record (expires_at, id);

CREATE TABLE public.event_stream_head (
    aggregate_type varchar(64) NOT NULL,
    aggregate_id varchar(128) NOT NULL,
    current_version bigint NOT NULL,
    last_event_id uuid NOT NULL,
    updated_at timestamptz NOT NULL,
    CONSTRAINT event_stream_head_pkey PRIMARY KEY (aggregate_type, aggregate_id),
    CONSTRAINT ck_logistics_event_stream_head_type CHECK (
        aggregate_type IN ('RETURN', 'SHIPMENT', 'TRANSFER')
    ),
    CONSTRAINT ck_logistics_event_stream_head_version CHECK (current_version >= 0)
);

CREATE TABLE public.domain_event (
    event_id uuid NOT NULL,
    aggregate_type varchar(64) NOT NULL,
    aggregate_id varchar(128) NOT NULL,
    aggregate_version bigint NOT NULL,
    event_type varchar(160) NOT NULL,
    event_version integer NOT NULL,
    occurred_at timestamptz,
    recorded_at timestamptz NOT NULL,
    correlation_id uuid NOT NULL,
    causation_id uuid,
    actor_ref jsonb,
    payload jsonb NOT NULL,
    payload_sha256 char(64) NOT NULL,
    baseline boolean NOT NULL DEFAULT false,
    CONSTRAINT domain_event_pkey PRIMARY KEY (event_id),
    CONSTRAINT uk_logistics_domain_event_stream_version
        UNIQUE (aggregate_type, aggregate_id, aggregate_version),
    CONSTRAINT uk_logistics_domain_event_identity
        UNIQUE (event_id, aggregate_type, aggregate_id, aggregate_version, event_type),
    CONSTRAINT fk_logistics_domain_event_stream_head
        FOREIGN KEY (aggregate_type, aggregate_id)
        REFERENCES public.event_stream_head (aggregate_type, aggregate_id),
    CONSTRAINT ck_logistics_domain_event_type CHECK (
        (
            (aggregate_type = 'RETURN' AND event_type LIKE 'logistics.return.%.v1')
            OR (aggregate_type = 'SHIPMENT' AND event_type LIKE 'logistics.shipment.%.v1')
            OR (aggregate_type = 'TRANSFER' AND event_type LIKE 'logistics.transfer.%.v1')
        )
        AND event_version = 1
    ),
    CONSTRAINT ck_logistics_domain_event_version CHECK (aggregate_version >= 0),
    CONSTRAINT ck_logistics_domain_event_payload CHECK (
        jsonb_typeof(payload) = 'object'
        AND payload_sha256 ~ '^[0-9a-f]{64}$'
        AND payload_sha256 = encode(sha256(convert_to(payload::text, 'UTF8')), 'hex')
    ),
    CONSTRAINT ck_logistics_domain_event_actor_ref CHECK (
        actor_ref IS NULL OR jsonb_typeof(actor_ref) = 'object'
    ),
    CONSTRAINT ck_logistics_domain_event_baseline_time CHECK (
        NOT baseline OR occurred_at IS NULL
    )
);

CREATE INDEX idx_logistics_domain_event_stream
    ON public.domain_event (aggregate_type, aggregate_id, aggregate_version);
CREATE INDEX idx_logistics_domain_event_recorded
    ON public.domain_event (recorded_at, event_id);

CREATE TABLE public.aggregate_snapshot (
    aggregate_type varchar(64) NOT NULL,
    aggregate_id varchar(128) NOT NULL,
    aggregate_version bigint NOT NULL,
    state jsonb NOT NULL,
    state_sha256 char(64) NOT NULL,
    recorded_at timestamptz NOT NULL,
    CONSTRAINT aggregate_snapshot_pkey PRIMARY KEY (aggregate_type, aggregate_id, aggregate_version),
    CONSTRAINT fk_logistics_aggregate_snapshot_stream_head
        FOREIGN KEY (aggregate_type, aggregate_id)
        REFERENCES public.event_stream_head (aggregate_type, aggregate_id),
    CONSTRAINT ck_logistics_aggregate_snapshot_type CHECK (
        aggregate_type IN ('RETURN', 'SHIPMENT', 'TRANSFER')
    ),
    CONSTRAINT ck_logistics_aggregate_snapshot_version CHECK (aggregate_version >= 0),
    CONSTRAINT ck_logistics_aggregate_snapshot_state CHECK (
        jsonb_typeof(state) = 'object'
        AND state_sha256 ~ '^[0-9a-f]{64}$'
        AND state_sha256 = encode(sha256(convert_to(state::text, 'UTF8')), 'hex')
    )
);

CREATE TABLE public.projection_checkpoint (
    projection_name varchar(128) NOT NULL,
    aggregate_type varchar(64) NOT NULL,
    aggregate_id varchar(128) NOT NULL,
    aggregate_version bigint NOT NULL,
    projection_sha256 char(64) NOT NULL,
    updated_at timestamptz NOT NULL,
    CONSTRAINT projection_checkpoint_pkey PRIMARY KEY (projection_name, aggregate_type, aggregate_id),
    CONSTRAINT ck_logistics_projection_checkpoint_type CHECK (
        aggregate_type IN ('RETURN', 'SHIPMENT', 'TRANSFER')
    ),
    CONSTRAINT ck_logistics_projection_checkpoint_version CHECK (aggregate_version >= 0),
    CONSTRAINT ck_logistics_projection_checkpoint_hash CHECK (projection_sha256 ~ '^[0-9a-f]{64}$')
);

CREATE TABLE public.outbox_event (
    event_id uuid NOT NULL,
    aggregate_type varchar(64) NOT NULL,
    aggregate_id varchar(128) NOT NULL,
    aggregate_version bigint NOT NULL,
    event_type varchar(160) NOT NULL,
    topic varchar(160) NOT NULL,
    envelope_body jsonb NOT NULL,
    envelope_sha256 char(64) NOT NULL,
    status varchar(24) NOT NULL DEFAULT 'PENDING',
    attempt_count integer NOT NULL DEFAULT 0,
    next_attempt_at timestamptz NOT NULL,
    lease_owner varchar(128),
    lease_token uuid,
    lease_until timestamptz,
    published_at timestamptz,
    dlt_at timestamptz,
    last_error_code varchar(64),
    created_at timestamptz NOT NULL,
    CONSTRAINT outbox_event_pkey PRIMARY KEY (event_id),
    CONSTRAINT uk_logistics_outbox_stream_version UNIQUE (aggregate_type, aggregate_id, aggregate_version),
    CONSTRAINT fk_logistics_outbox_domain_event
        FOREIGN KEY (event_id, aggregate_type, aggregate_id, aggregate_version, event_type)
        REFERENCES public.domain_event (event_id, aggregate_type, aggregate_id, aggregate_version, event_type),
    CONSTRAINT ck_logistics_outbox_type CHECK (
        (aggregate_type = 'RETURN' AND event_type LIKE 'logistics.return.%.v1')
        OR (aggregate_type = 'SHIPMENT' AND event_type LIKE 'logistics.shipment.%.v1')
        OR (aggregate_type = 'TRANSFER' AND event_type LIKE 'logistics.transfer.%.v1')
    ),
    CONSTRAINT ck_logistics_outbox_version CHECK (aggregate_version >= 0 AND attempt_count >= 0),
    CONSTRAINT ck_logistics_outbox_body CHECK (
        jsonb_typeof(envelope_body) = 'object'
        AND envelope_sha256 ~ '^[0-9a-f]{64}$'
        AND envelope_sha256 = encode(sha256(convert_to(envelope_body::text, 'UTF8')), 'hex')
    ),
    CONSTRAINT ck_logistics_outbox_status CHECK (
        status IN ('PENDING', 'IN_FLIGHT', 'PUBLISHED', 'DLT', 'QUARANTINED')
    ),
    CONSTRAINT ck_logistics_outbox_topic CHECK (
        topic IN ('rwms.logistics.return.v1', 'rwms.logistics.shipment.v1', 'rwms.logistics.transfer.v1')
    ),
    CONSTRAINT ck_logistics_outbox_lease CHECK (
        (status = 'IN_FLIGHT' AND lease_owner IS NOT NULL AND lease_token IS NOT NULL AND lease_until IS NOT NULL)
        OR (status <> 'IN_FLIGHT' AND lease_owner IS NULL AND lease_token IS NULL AND lease_until IS NULL)
    ),
    CONSTRAINT ck_logistics_outbox_published CHECK ((status = 'PUBLISHED') = (published_at IS NOT NULL)),
    CONSTRAINT ck_logistics_outbox_dlt CHECK ((status = 'DLT') = (dlt_at IS NOT NULL)),
    CONSTRAINT ck_logistics_outbox_error_code CHECK (
        last_error_code IS NULL OR last_error_code ~ '^[A-Z][A-Z0-9_]{0,63}$'
    )
);

CREATE INDEX idx_logistics_outbox_pending
    ON public.outbox_event (next_attempt_at, created_at, event_id)
    WHERE status = 'PENDING';
CREATE INDEX idx_logistics_outbox_expired_lease
    ON public.outbox_event (lease_until, created_at, event_id)
    WHERE status = 'IN_FLIGHT';

CREATE TABLE public.inbox_message (
    consumer_group varchar(128) NOT NULL,
    event_id uuid NOT NULL,
    aggregate_type varchar(64) NOT NULL,
    aggregate_id varchar(128) NOT NULL,
    aggregate_version bigint NOT NULL,
    payload_sha256 char(64) NOT NULL,
    status varchar(24) NOT NULL,
    attempt_count integer NOT NULL DEFAULT 0,
    received_at timestamptz NOT NULL,
    processed_at timestamptz,
    next_attempt_at timestamptz,
    dlt_at timestamptz,
    quarantine_reason varchar(128),
    CONSTRAINT inbox_message_pkey PRIMARY KEY (consumer_group, event_id),
    CONSTRAINT ck_logistics_inbox_version CHECK (aggregate_version >= 0 AND attempt_count >= 0),
    CONSTRAINT ck_logistics_inbox_hash CHECK (payload_sha256 ~ '^[0-9a-f]{64}$'),
    CONSTRAINT ck_logistics_inbox_status CHECK (
        status IN ('RECEIVED', 'PROCESSED', 'RETRY', 'DLT', 'QUARANTINED')
    )
);

CREATE INDEX idx_logistics_inbox_retry
    ON public.inbox_message (consumer_group, next_attempt_at, received_at)
    WHERE status = 'RETRY';

CREATE TABLE public.consumer_aggregate_checkpoint (
    consumer_group varchar(128) NOT NULL,
    aggregate_type varchar(64) NOT NULL,
    aggregate_id varchar(128) NOT NULL,
    last_event_id uuid,
    last_aggregate_version bigint NOT NULL,
    blocked boolean NOT NULL DEFAULT false,
    quarantine_reason varchar(128),
    updated_at timestamptz NOT NULL,
    CONSTRAINT consumer_aggregate_checkpoint_pkey
        PRIMARY KEY (consumer_group, aggregate_type, aggregate_id),
    CONSTRAINT ck_logistics_consumer_checkpoint_version CHECK (
        (last_aggregate_version = -1 AND last_event_id IS NULL)
        OR (last_aggregate_version >= 0 AND last_event_id IS NOT NULL)
    ),
    CONSTRAINT ck_logistics_consumer_checkpoint_blocked CHECK (
        (blocked AND quarantine_reason IS NOT NULL)
        OR (NOT blocked AND quarantine_reason IS NULL)
    )
);

CREATE TABLE public.version_gap_quarantine (
    quarantine_id uuid NOT NULL,
    consumer_group varchar(128) NOT NULL,
    aggregate_type varchar(64) NOT NULL,
    aggregate_id varchar(128) NOT NULL,
    expected_version bigint NOT NULL,
    received_version bigint NOT NULL,
    received_event_id uuid NOT NULL,
    payload_sha256 char(64) NOT NULL,
    reason_code varchar(128) NOT NULL,
    status varchar(24) NOT NULL DEFAULT 'OPEN',
    detected_at timestamptz NOT NULL,
    resolved_at timestamptz,
    resolution_reason varchar(500),
    resolved_by_subject_id uuid,
    CONSTRAINT version_gap_quarantine_pkey PRIMARY KEY (quarantine_id),
    CONSTRAINT uk_logistics_version_gap_event UNIQUE (consumer_group, received_event_id),
    CONSTRAINT ck_logistics_version_gap_versions CHECK (
        expected_version >= 0 AND received_version > expected_version
    ),
    CONSTRAINT ck_logistics_version_gap_hash CHECK (payload_sha256 ~ '^[0-9a-f]{64}$'),
    CONSTRAINT ck_logistics_version_gap_status CHECK (status IN ('OPEN', 'RESOLVED')),
    CONSTRAINT ck_logistics_version_gap_reason CHECK (reason_code = 'AGGREGATE_VERSION_GAP'),
    CONSTRAINT ck_logistics_version_gap_resolution CHECK (
        (status = 'OPEN' AND resolved_at IS NULL AND resolution_reason IS NULL AND resolved_by_subject_id IS NULL)
        OR
        (status = 'RESOLVED' AND resolved_at IS NOT NULL AND resolution_reason IS NOT NULL
            AND btrim(resolution_reason) <> '' AND resolved_by_subject_id IS NOT NULL)
    )
);

CREATE UNIQUE INDEX uk_logistics_version_gap_open_aggregate
    ON public.version_gap_quarantine (consumer_group, aggregate_type, aggregate_id)
    WHERE status = 'OPEN';

CREATE TABLE public.sanitized_dead_letter (
    dlt_id uuid NOT NULL,
    destination varchar(200) NOT NULL,
    message_sha256 char(64) NOT NULL,
    failure_code varchar(64) NOT NULL,
    safe_body jsonb NOT NULL,
    body_sha256 char(64) NOT NULL,
    status varchar(16) NOT NULL DEFAULT 'PENDING',
    attempt_count integer NOT NULL DEFAULT 0,
    next_attempt_at timestamptz NOT NULL,
    lease_owner varchar(128),
    lease_token uuid,
    lease_until timestamptz,
    created_at timestamptz NOT NULL,
    published_at timestamptz,
    CONSTRAINT sanitized_dead_letter_pkey PRIMARY KEY (dlt_id),
    CONSTRAINT ck_logistics_dead_letter_destination CHECK (
        destination LIKE 'rwms.logistics.return.v1.%.dlt'
        OR destination LIKE 'rwms.logistics.shipment.v1.%.dlt'
        OR destination LIKE 'rwms.logistics.transfer.v1.%.dlt'
    ),
    CONSTRAINT ck_logistics_dead_letter_failure_code CHECK (
        failure_code IN ('VALIDATION_REJECTED', 'PROCESSING_FAILED')
    ),
    CONSTRAINT ck_logistics_dead_letter_hashes CHECK (
        message_sha256 ~ '^[0-9a-f]{64}$' AND body_sha256 ~ '^[0-9a-f]{64}$'
    ),
    CONSTRAINT ck_logistics_dead_letter_body CHECK (jsonb_typeof(safe_body) = 'object'),
    CONSTRAINT ck_logistics_dead_letter_status CHECK (
        status IN ('PENDING', 'IN_FLIGHT', 'PUBLISHED', 'FAILED')
    ),
    CONSTRAINT ck_logistics_dead_letter_attempts CHECK (attempt_count >= 0),
    CONSTRAINT ck_logistics_dead_letter_lease CHECK (
        (status = 'IN_FLIGHT' AND lease_owner IS NOT NULL AND lease_token IS NOT NULL AND lease_until IS NOT NULL)
        OR (status <> 'IN_FLIGHT' AND lease_owner IS NULL AND lease_token IS NULL AND lease_until IS NULL)
    ),
    CONSTRAINT ck_logistics_dead_letter_published CHECK (
        (status = 'PUBLISHED') = (published_at IS NOT NULL)
    )
);

CREATE INDEX idx_logistics_dead_letter_pending
    ON public.sanitized_dead_letter (next_attempt_at, created_at, dlt_id)
    WHERE status = 'PENDING';
