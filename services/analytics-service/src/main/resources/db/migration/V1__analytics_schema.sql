CREATE TABLE analytics_inbox (
    event_id uuid PRIMARY KEY,
    row_version bigint NOT NULL DEFAULT 0,
    envelope_sha256 char(64) NOT NULL,
    decision varchar(24) NOT NULL,
    attempt_count integer NOT NULL,
    received_at timestamptz NOT NULL,
    decided_at timestamptz,
    CONSTRAINT ck_analytics_inbox_digest CHECK (envelope_sha256 ~ '^[0-9a-f]{64}$'),
    CONSTRAINT ck_analytics_inbox_decision CHECK (
        decision IN ('RECEIVED', 'HELD', 'PROCESSED', 'STALE', 'DLT')
    ),
    CONSTRAINT ck_analytics_inbox_attempt CHECK (attempt_count > 0)
);

CREATE TABLE analytics_source_fact (
    id uuid PRIMARY KEY,
    event_id uuid NOT NULL,
    source_topic varchar(200) NOT NULL,
    source_partition integer NOT NULL,
    source_offset bigint NOT NULL,
    aggregate_id uuid NOT NULL,
    aggregate_version bigint NOT NULL,
    envelope_sha256 char(64) NOT NULL,
    canonical_envelope jsonb NOT NULL,
    recorded_at timestamptz NOT NULL,
    ingested_at timestamptz NOT NULL,
    CONSTRAINT uq_analytics_source_event UNIQUE (event_id),
    CONSTRAINT uq_analytics_source_coordinate UNIQUE (
        source_topic, source_partition, source_offset
    ),
    CONSTRAINT ck_analytics_source_coordinates CHECK (
        source_partition >= 0 AND source_offset >= 0 AND aggregate_version >= 0
    ),
    CONSTRAINT ck_analytics_source_digest CHECK (envelope_sha256 ~ '^[0-9a-f]{64}$'),
    CONSTRAINT ck_analytics_source_object CHECK (jsonb_typeof(canonical_envelope) = 'object')
);

CREATE INDEX ix_analytics_source_aggregate_version
    ON analytics_source_fact (aggregate_id, aggregate_version, event_id);

CREATE TABLE analytics_aggregate_checkpoint (
    id uuid PRIMARY KEY,
    row_version bigint NOT NULL DEFAULT 0,
    consumer_group varchar(128) NOT NULL,
    source_topic varchar(200) NOT NULL,
    aggregate_type varchar(64) NOT NULL,
    aggregate_id uuid NOT NULL,
    applied_version bigint NOT NULL DEFAULT -1,
    gap_open boolean NOT NULL DEFAULT false,
    expected_version bigint,
    observed_version bigint,
    gap_attempt_count integer NOT NULL DEFAULT 0,
    gap_first_seen_at timestamptz,
    terminally_blocked boolean NOT NULL DEFAULT false,
    updated_at timestamptz NOT NULL,
    CONSTRAINT uq_analytics_aggregate_checkpoint UNIQUE (
        consumer_group, source_topic, aggregate_type, aggregate_id
    ),
    CONSTRAINT ck_analytics_checkpoint_applied CHECK (applied_version >= -1),
    CONSTRAINT ck_analytics_checkpoint_gap_attempt CHECK (gap_attempt_count >= 0),
    CONSTRAINT ck_analytics_checkpoint_gap_shape CHECK (
        (gap_open
            AND expected_version IS NOT NULL
            AND observed_version IS NOT NULL
            AND observed_version >= expected_version
            AND gap_first_seen_at IS NOT NULL)
        OR
        (NOT gap_open
            AND expected_version IS NULL
            AND observed_version IS NULL
            AND gap_first_seen_at IS NULL)
    )
);

CREATE INDEX ix_analytics_checkpoint_open_gap
    ON analytics_aggregate_checkpoint (gap_open, terminally_blocked, gap_first_seen_at);

CREATE TABLE analytics_partition_checkpoint (
    id uuid PRIMARY KEY,
    row_version bigint NOT NULL DEFAULT 0,
    consumer_group varchar(128) NOT NULL,
    source_topic varchar(200) NOT NULL,
    source_partition integer NOT NULL,
    last_accepted_offset bigint NOT NULL DEFAULT -1,
    updated_at timestamptz NOT NULL,
    CONSTRAINT uq_analytics_partition_checkpoint UNIQUE (
        consumer_group, source_topic, source_partition
    ),
    CONSTRAINT ck_analytics_partition_values CHECK (
        source_partition >= 0 AND last_accepted_offset >= -1
    )
);

CREATE TABLE analytics_group_kpi_day (
    evidence_id uuid PRIMARY KEY,
    row_version bigint NOT NULL DEFAULT 0,
    warehouse_id uuid NOT NULL,
    worker_group_id uuid NOT NULL,
    local_date date NOT NULL,
    data_available_from date NOT NULL,
    formula_version varchar(32) NOT NULL,
    completed_budget_seconds bigint NOT NULL,
    earned_remaining_seconds bigint NOT NULL,
    active_seconds bigint NOT NULL,
    penalized_idle_seconds bigint NOT NULL,
    completed_task_count bigint NOT NULL,
    open_state varchar(24),
    open_state_started_at timestamptz,
    penalty_starts_at timestamptz,
    next_transition_at timestamptz,
    evidence_as_of timestamptz NOT NULL,
    source_event_id uuid NOT NULL,
    source_aggregate_version bigint NOT NULL,
    updated_at timestamptz NOT NULL,
    CONSTRAINT uq_analytics_group_day UNIQUE (warehouse_id, worker_group_id, local_date),
    CONSTRAINT ck_analytics_group_day_available CHECK (data_available_from <= local_date),
    CONSTRAINT ck_analytics_group_day_formula CHECK (formula_version = 'kpi-v1'),
    CONSTRAINT ck_analytics_group_day_components CHECK (
        completed_budget_seconds >= 0
        AND earned_remaining_seconds >= 0
        AND earned_remaining_seconds <= completed_budget_seconds
        AND active_seconds >= 0
        AND penalized_idle_seconds >= 0
        AND completed_task_count >= 0
        AND source_aggregate_version >= 0
    ),
    CONSTRAINT ck_analytics_group_day_state CHECK (
        open_state IS NULL
        OR open_state IN ('WORKING', 'IDLE_GRACE', 'IDLE_PENALIZED', 'EXCLUDED')
    ),
    CONSTRAINT ck_analytics_group_day_open_started CHECK (
        (open_state IS NULL AND open_state_started_at IS NULL)
        OR (open_state IS NOT NULL AND open_state_started_at IS NOT NULL)
    ),
    CONSTRAINT ck_analytics_group_day_penalty CHECK (
        (open_state IN ('IDLE_GRACE', 'IDLE_PENALIZED') AND penalty_starts_at IS NOT NULL)
        OR (open_state IS DISTINCT FROM 'IDLE_GRACE'
            AND open_state IS DISTINCT FROM 'IDLE_PENALIZED'
            AND penalty_starts_at IS NULL)
    )
);

CREATE INDEX ix_analytics_group_day_period
    ON analytics_group_kpi_day (warehouse_id, local_date, worker_group_id);

CREATE TABLE analytics_sanitized_dead_letter (
    id uuid PRIMARY KEY,
    row_version bigint NOT NULL DEFAULT 0,
    source_event_id uuid,
    source_aggregate_id uuid,
    source_topic varchar(200) NOT NULL,
    source_partition integer NOT NULL,
    source_offset bigint NOT NULL,
    destination varchar(240) NOT NULL,
    record_key_sha256 char(64) NOT NULL,
    message_sha256 char(64) NOT NULL,
    failure_code varchar(40) NOT NULL,
    status varchar(24) NOT NULL,
    attempt_count integer NOT NULL DEFAULT 0,
    next_attempt_at timestamptz NOT NULL,
    failed_at timestamptz NOT NULL,
    published_at timestamptz,
    CONSTRAINT ck_analytics_dlt_coordinates CHECK (
        source_partition >= 0 AND source_offset >= 0 AND attempt_count >= 0
    ),
    CONSTRAINT ck_analytics_dlt_record_digest CHECK (record_key_sha256 ~ '^[0-9a-f]{64}$'),
    CONSTRAINT ck_analytics_dlt_message_digest CHECK (message_sha256 ~ '^[0-9a-f]{64}$'),
    CONSTRAINT ck_analytics_dlt_failure CHECK (
        failure_code IN (
            'INVALID_ENVELOPE',
            'INVALID_PAYLOAD',
            'RECORD_KEY_MISMATCH',
            'EVENT_IDENTITY_CONFLICT',
            'SOURCE_COORDINATE_CONFLICT',
            'MISSING_AGGREGATE_VERSION',
            'PROCESSING_FAILED'
        )
    ),
    CONSTRAINT ck_analytics_dlt_status CHECK (status IN ('PENDING', 'RETRY', 'PUBLISHED', 'DLT'))
);

CREATE INDEX ix_analytics_dlt_relay
    ON analytics_sanitized_dead_letter (status, next_attempt_at, failed_at);
