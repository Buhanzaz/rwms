CREATE TABLE public.dossier_source_fact (
    id uuid NOT NULL,
    event_id uuid NOT NULL,
    producer varchar(24) NOT NULL,
    source_topic varchar(200) NOT NULL,
    source_partition integer NOT NULL,
    source_offset bigint NOT NULL,
    kafka_key uuid NOT NULL,
    aggregate_type varchar(64) NOT NULL,
    aggregate_id uuid NOT NULL,
    aggregate_version bigint NOT NULL,
    event_type varchar(160) NOT NULL,
    event_version integer NOT NULL,
    payload_sha256 char(64) NOT NULL,
    canonical_envelope jsonb NOT NULL,
    occurred_at timestamptz,
    recorded_at timestamptz NOT NULL,
    actor_subject_id uuid,
    actor_principal_type varchar(64),
    actor_profile_revision varchar(64),
    correlation_id uuid NOT NULL,
    causation_id uuid,
    subject_cabin_id uuid,
    subject_warehouse_id uuid,
    subject_secondary_id uuid,
    activity_code varchar(64),
    ingested_at timestamptz NOT NULL,
    CONSTRAINT dossier_source_fact_pkey PRIMARY KEY (id),
    CONSTRAINT uq_dossier_source_fact_event UNIQUE (event_id),
    CONSTRAINT uq_dossier_source_fact_coordinate UNIQUE (source_topic, source_partition, source_offset),
    CONSTRAINT ck_dossier_source_fact_producer CHECK (producer IN ('ASSET','MAINTENANCE','INVENTORY','MEDIA','LOGISTICS','TASK_BOARD')),
    CONSTRAINT ck_dossier_source_fact_partition CHECK (source_partition >= 0),
    CONSTRAINT ck_dossier_source_fact_offset CHECK (source_offset >= 0),
    CONSTRAINT ck_dossier_source_fact_key CHECK (kafka_key = aggregate_id),
    CONSTRAINT ck_dossier_source_fact_version CHECK (aggregate_version >= 0),
    CONSTRAINT ck_dossier_source_fact_event_version CHECK (event_version > 0),
    CONSTRAINT ck_dossier_source_fact_hash CHECK (payload_sha256 ~ '^[0-9a-f]{64}$'),
    CONSTRAINT ck_dossier_source_fact_envelope CHECK (jsonb_typeof(canonical_envelope) = 'object'),
    CONSTRAINT ck_dossier_source_fact_actor CHECK (
        (actor_subject_id IS NULL AND actor_principal_type IS NULL AND actor_profile_revision IS NULL)
        OR (actor_subject_id IS NOT NULL AND actor_principal_type IS NOT NULL
            AND actor_principal_type ~ '^[A-Z][A-Z0-9_]{0,63}$'
            AND (actor_profile_revision IS NULL OR actor_profile_revision ~ '^(?:[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}|[0-9a-f]{64})$'))
    ),
    CONSTRAINT ck_dossier_source_fact_subject_snapshot CHECK (
        subject_cabin_id IS NULL OR subject_warehouse_id IS NOT NULL
    )
);

CREATE INDEX idx_dossier_source_fact_stream
    ON public.dossier_source_fact (producer, source_topic, aggregate_type, aggregate_id, aggregate_version);
CREATE INDEX idx_dossier_source_fact_ingested
    ON public.dossier_source_fact (ingested_at, event_id);
CREATE INDEX idx_dossier_source_fact_subject
    ON public.dossier_source_fact (producer, subject_secondary_id, recorded_at, event_id);

CREATE TABLE public.dossier_inbox (
    event_id uuid NOT NULL,
    row_version bigint NOT NULL DEFAULT 0,
    payload_sha256 char(64) NOT NULL,
    decision varchar(32) NOT NULL,
    attempt_count integer NOT NULL,
    received_at timestamptz NOT NULL,
    decided_at timestamptz,
    CONSTRAINT dossier_inbox_pkey PRIMARY KEY (event_id),
    CONSTRAINT ck_dossier_inbox_hash CHECK (payload_sha256 ~ '^[0-9a-f]{64}$'),
    CONSTRAINT ck_dossier_inbox_decision CHECK (decision IN ('RECEIVED','PROCESSED','DUPLICATE','QUARANTINED','DLT')),
    CONSTRAINT ck_dossier_inbox_attempts CHECK (attempt_count >= 1)
);

CREATE TABLE public.dossier_partition_checkpoint (
    id uuid NOT NULL,
    row_version bigint NOT NULL DEFAULT 0,
    consumer_group varchar(128) NOT NULL,
    source_topic varchar(200) NOT NULL,
    source_partition integer NOT NULL,
    last_accepted_offset bigint NOT NULL,
    updated_at timestamptz NOT NULL,
    CONSTRAINT dossier_partition_checkpoint_pkey PRIMARY KEY (id),
    CONSTRAINT uq_dossier_partition_checkpoint UNIQUE (consumer_group, source_topic, source_partition),
    CONSTRAINT ck_dossier_partition_checkpoint_partition CHECK (source_partition >= 0),
    CONSTRAINT ck_dossier_partition_checkpoint_offset CHECK (last_accepted_offset >= -1)
);

CREATE TABLE public.dossier_aggregate_checkpoint (
    id uuid NOT NULL,
    row_version bigint NOT NULL DEFAULT 0,
    consumer_group varchar(128) NOT NULL,
    producer varchar(24) NOT NULL,
    source_topic varchar(200) NOT NULL,
    aggregate_type varchar(64) NOT NULL,
    aggregate_id uuid NOT NULL,
    applied_version bigint NOT NULL,
    blocked boolean NOT NULL,
    blocked_reason varchar(40),
    expected_version bigint,
    observed_version bigint,
    blocked_at timestamptz,
    updated_at timestamptz NOT NULL,
    CONSTRAINT dossier_aggregate_checkpoint_pkey PRIMARY KEY (id),
    CONSTRAINT uq_dossier_aggregate_checkpoint UNIQUE (consumer_group, producer, source_topic, aggregate_type, aggregate_id),
    CONSTRAINT ck_dossier_aggregate_checkpoint_producer CHECK (producer IN ('ASSET','MAINTENANCE','INVENTORY','MEDIA','LOGISTICS','TASK_BOARD')),
    CONSTRAINT ck_dossier_aggregate_checkpoint_version CHECK (applied_version >= -1),
    CONSTRAINT ck_dossier_aggregate_checkpoint_block_reason CHECK (
        blocked_reason IS NULL OR blocked_reason IN ('MISSING_PREFIX','EVENT_IDENTITY_CONFLICT','MEDIA_GENERATION_CONFLICT','PROCESSING_FAILED')
    ),
    CONSTRAINT ck_dossier_aggregate_checkpoint_block CHECK (
        (NOT blocked AND blocked_reason IS NULL AND expected_version IS NULL AND observed_version IS NULL AND blocked_at IS NULL)
        OR (blocked AND blocked_reason = 'MISSING_PREFIX' AND expected_version IS NOT NULL
            AND observed_version IS NOT NULL AND blocked_at IS NOT NULL AND observed_version > expected_version)
        OR (blocked AND blocked_reason IN ('EVENT_IDENTITY_CONFLICT','MEDIA_GENERATION_CONFLICT','PROCESSING_FAILED')
            AND expected_version IS NULL AND observed_version IS NULL AND blocked_at IS NOT NULL)
    )
);

CREATE TABLE public.dossier_projection_generation (
    id uuid NOT NULL,
    row_version bigint NOT NULL DEFAULT 0,
    state varchar(24) NOT NULL,
    created_at timestamptz NOT NULL,
    activated_at timestamptz,
    retired_at timestamptz,
    CONSTRAINT dossier_projection_generation_pkey PRIMARY KEY (id),
    CONSTRAINT ck_dossier_projection_generation_state CHECK (state IN ('BUILDING','READY','ACTIVE','REJECTED','RETIRED')),
    CONSTRAINT ck_dossier_projection_generation_times CHECK (
        (state IN ('BUILDING','READY','REJECTED') AND activated_at IS NULL AND retired_at IS NULL)
        OR (state = 'ACTIVE' AND activated_at IS NOT NULL AND retired_at IS NULL)
        OR (state = 'RETIRED' AND activated_at IS NOT NULL AND retired_at IS NOT NULL)
    )
);

CREATE UNIQUE INDEX uq_dossier_single_active_generation
    ON public.dossier_projection_generation ((state)) WHERE state = 'ACTIVE';

CREATE TABLE public.dossier_active_generation (
    id uuid NOT NULL,
    row_version bigint NOT NULL DEFAULT 0,
    pointer_name varchar(32) NOT NULL,
    generation_id uuid NOT NULL,
    updated_at timestamptz NOT NULL,
    CONSTRAINT dossier_active_generation_pkey PRIMARY KEY (id),
    CONSTRAINT uq_dossier_active_generation_pointer UNIQUE (pointer_name),
    CONSTRAINT ck_dossier_active_generation_pointer CHECK (pointer_name = 'DOSSIER'),
    CONSTRAINT fk_dossier_active_generation_generation FOREIGN KEY (generation_id)
        REFERENCES public.dossier_projection_generation (id)
);

INSERT INTO public.dossier_projection_generation(
    id, row_version, state, created_at, activated_at, retired_at
) VALUES (
    '00000000-0000-0000-0000-000000000901', 0, 'ACTIVE',
    '2026-07-18T00:00:00Z', '2026-07-18T00:00:00Z', NULL
);

INSERT INTO public.dossier_active_generation(
    id, row_version, pointer_name, generation_id, updated_at
) VALUES (
    '00000000-0000-0000-0000-000000000902', 0, 'DOSSIER',
    '00000000-0000-0000-0000-000000000901', '2026-07-18T00:00:00Z'
);

CREATE TABLE public.dossier_subject_association (
    id uuid NOT NULL,
    row_version bigint NOT NULL DEFAULT 0,
    generation_id uuid NOT NULL,
    producer varchar(24) NOT NULL,
    source_type varchar(64) NOT NULL,
    source_id uuid NOT NULL,
    cabin_id uuid NOT NULL,
    warehouse_id uuid NOT NULL,
    source_event_id uuid NOT NULL,
    proven_at timestamptz NOT NULL,
    CONSTRAINT dossier_subject_association_pkey PRIMARY KEY (id),
    CONSTRAINT uq_dossier_subject_association_source UNIQUE (producer, source_type, source_id, generation_id),
    CONSTRAINT ck_dossier_subject_association_producer CHECK (producer IN ('ASSET','MAINTENANCE','INVENTORY','MEDIA','LOGISTICS','TASK_BOARD')),
    CONSTRAINT fk_dossier_subject_association_generation FOREIGN KEY (generation_id)
        REFERENCES public.dossier_projection_generation (id),
    CONSTRAINT fk_dossier_subject_association_fact FOREIGN KEY (source_event_id)
        REFERENCES public.dossier_source_fact (event_id)
);

CREATE INDEX idx_dossier_subject_association_cabin
    ON public.dossier_subject_association (cabin_id, warehouse_id);

CREATE TABLE public.dossier_activity (
    id uuid NOT NULL,
    activity_id uuid NOT NULL,
    generation_id uuid NOT NULL,
    source_event_id uuid NOT NULL,
    cabin_id uuid NOT NULL,
    warehouse_id uuid NOT NULL,
    activity_code varchar(64) NOT NULL,
    source_producer varchar(24) NOT NULL,
    source_aggregate_type varchar(64) NOT NULL,
    source_aggregate_id uuid NOT NULL,
    source_secondary_id uuid,
    occurred_at timestamptz,
    recorded_at timestamptz NOT NULL,
    actor_subject_id uuid,
    actor_principal_type varchar(64),
    actor_profile_revision varchar(64),
    correlation_id uuid NOT NULL,
    causation_id uuid,
    created_at timestamptz NOT NULL,
    CONSTRAINT dossier_activity_pkey PRIMARY KEY (id),
    CONSTRAINT uq_dossier_activity_generation UNIQUE (activity_id, generation_id),
    CONSTRAINT uq_dossier_activity_source_cabin_generation UNIQUE (source_event_id, cabin_id, generation_id),
    CONSTRAINT ck_dossier_activity_code CHECK (activity_code IN (
        'CABIN_CREATED','CABIN_PASSPORT_CHANGED','CABIN_STATUS_CHANGED','CABIN_WAREHOUSE_CHANGED',
        'CABIN_LOGISTICS_EFFECT_APPLIED','CABIN_COMMENT_REVISION_CHANGED','CABIN_MANUAL_NOTE_ADDED',
        'ESTIMATE_CREATED','ESTIMATE_DRAFT_CHANGED','ESTIMATE_COMPLETED','ESTIMATE_AMENDED',
        'REPAIR_CREATED','REPAIR_PLAN_CHANGED','REPAIR_QUEUED','REPAIR_STAGE_COMPLETED',
        'REPAIR_PENDING_ACCEPTANCE','REPAIR_REWORK_CREATED','REPAIR_ACCEPTED','REPAIR_WRITTEN_OFF',
        'INVENTORY_FINDING_ADDED','INVENTORY_INSPECTION_SAVED','INVENTORY_PUBLICATION_READY',
        'INVENTORY_PUBLICATION_REQUESTED','INVENTORY_PUBLICATION_SUCCEEDED',
        'INVENTORY_PUBLICATION_TRANSIENT_FAILED','INVENTORY_PUBLICATION_BLOCKED',
        'INVENTORY_PUBLICATION_CLOSED_BLOCKED','MEDIA_READY','MEDIA_FAILED','MEDIA_ROTATED','MEDIA_DELETED'
    )),
    CONSTRAINT ck_dossier_activity_producer CHECK (source_producer IN ('ASSET','MAINTENANCE','INVENTORY','MEDIA','LOGISTICS','TASK_BOARD')),
    CONSTRAINT ck_dossier_activity_actor CHECK (
        (actor_subject_id IS NULL AND actor_principal_type IS NULL AND actor_profile_revision IS NULL)
        OR (actor_subject_id IS NOT NULL AND actor_principal_type IS NOT NULL
            AND actor_principal_type ~ '^[A-Z][A-Z0-9_]{0,63}$'
            AND (actor_profile_revision IS NULL OR actor_profile_revision ~ '^(?:[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}|[0-9a-f]{64})$'))
    ),
    CONSTRAINT fk_dossier_activity_generation FOREIGN KEY (generation_id)
        REFERENCES public.dossier_projection_generation (id),
    CONSTRAINT fk_dossier_activity_fact FOREIGN KEY (source_event_id)
        REFERENCES public.dossier_source_fact (event_id)
);

CREATE INDEX idx_dossier_activity_query
    ON public.dossier_activity (cabin_id, generation_id, occurred_at DESC NULLS LAST, recorded_at DESC, source_event_id DESC);
CREATE INDEX idx_dossier_activity_warehouse
    ON public.dossier_activity (warehouse_id, cabin_id, generation_id);

CREATE TABLE public.dossier_media_projection (
    id uuid NOT NULL,
    row_version bigint NOT NULL DEFAULT 0,
    generation_id uuid NOT NULL,
    cabin_id uuid NOT NULL,
    warehouse_id uuid NOT NULL,
    media_id uuid NOT NULL,
    inventory_finding_id uuid NOT NULL,
    media_generation bigint NOT NULL,
    source_aggregate_version bigint NOT NULL,
    state varchar(24) NOT NULL,
    source_event_id uuid NOT NULL,
    updated_at timestamptz NOT NULL,
    CONSTRAINT dossier_media_projection_pkey PRIMARY KEY (id),
    CONSTRAINT uq_dossier_media_projection UNIQUE (cabin_id, media_id, generation_id),
    CONSTRAINT ck_dossier_media_projection_generation CHECK (media_generation >= 0),
    CONSTRAINT ck_dossier_media_projection_source_version CHECK (source_aggregate_version >= 0),
    CONSTRAINT ck_dossier_media_projection_state CHECK (state IN ('PROCESSING','READY','FAILED','DELETED')),
    CONSTRAINT fk_dossier_media_projection_generation FOREIGN KEY (generation_id)
        REFERENCES public.dossier_projection_generation (id),
    CONSTRAINT fk_dossier_media_projection_fact FOREIGN KEY (source_event_id)
        REFERENCES public.dossier_source_fact (event_id)
);

CREATE INDEX idx_dossier_media_projection_cabin
    ON public.dossier_media_projection (cabin_id, generation_id, state, media_id);

CREATE TABLE public.dossier_unlinked_fact (
    id uuid NOT NULL,
    row_version bigint NOT NULL DEFAULT 0,
    generation_id uuid NOT NULL,
    source_event_id uuid NOT NULL,
    subject_cabin_id uuid,
    reason varchar(40) NOT NULL,
    source_producer varchar(24) NOT NULL,
    source_aggregate_type varchar(64) NOT NULL,
    source_aggregate_id uuid NOT NULL,
    payload_sha256 char(64) NOT NULL,
    recorded_at timestamptz NOT NULL,
    resolved_at timestamptz,
    CONSTRAINT dossier_unlinked_fact_pkey PRIMARY KEY (id),
    CONSTRAINT uq_dossier_unlinked_fact_generation UNIQUE (source_event_id, generation_id),
    CONSTRAINT ck_dossier_unlinked_fact_reason CHECK (reason IN ('SUBJECT_NOT_PROVIDED','SUBJECT_NOT_YET_PROVEN','WAREHOUSE_NOT_PROVIDED','MISSING_PREFIX','AGGREGATE_QUARANTINED')),
    CONSTRAINT ck_dossier_unlinked_fact_producer CHECK (source_producer IN ('ASSET','MAINTENANCE','INVENTORY','MEDIA','LOGISTICS','TASK_BOARD')),
    CONSTRAINT ck_dossier_unlinked_fact_hash CHECK (payload_sha256 ~ '^[0-9a-f]{64}$'),
    CONSTRAINT fk_dossier_unlinked_fact_generation FOREIGN KEY (generation_id)
        REFERENCES public.dossier_projection_generation (id),
    CONSTRAINT fk_dossier_unlinked_fact_source FOREIGN KEY (source_event_id)
        REFERENCES public.dossier_source_fact (event_id)
);

CREATE INDEX idx_dossier_unlinked_unresolved
    ON public.dossier_unlinked_fact (generation_id, subject_cabin_id, source_event_id)
    WHERE resolved_at IS NULL;

CREATE TABLE public.dossier_sanitized_dead_letter (
    id uuid NOT NULL,
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
    attempt_count integer NOT NULL,
    next_attempt_at timestamptz NOT NULL,
    failed_at timestamptz NOT NULL,
    published_at timestamptz,
    CONSTRAINT dossier_sanitized_dead_letter_pkey PRIMARY KEY (id),
    CONSTRAINT uq_dossier_dead_letter_identity UNIQUE (
        source_topic, source_partition, source_offset, message_sha256, failure_code
    ),
    CONSTRAINT ck_dossier_dead_letter_partition CHECK (source_partition >= 0),
    CONSTRAINT ck_dossier_dead_letter_offset CHECK (source_offset >= 0),
    CONSTRAINT ck_dossier_dead_letter_key_hash CHECK (record_key_sha256 ~ '^[0-9a-f]{64}$'),
    CONSTRAINT ck_dossier_dead_letter_message_hash CHECK (message_sha256 ~ '^[0-9a-f]{64}$'),
    CONSTRAINT ck_dossier_dead_letter_code CHECK (failure_code IN ('INVALID_ENVELOPE','UNSUPPORTED_PRODUCER','UNSUPPORTED_AGGREGATE_TYPE','UNSUPPORTED_EVENT_TYPE','UNSUPPORTED_EVENT_VERSION','INVALID_PAYLOAD','RECORD_KEY_MISMATCH','EVENT_IDENTITY_CONFLICT','MEDIA_GENERATION_CONFLICT','PROCESSING_FAILED')),
    CONSTRAINT ck_dossier_dead_letter_status CHECK (status IN ('PENDING','RETRY','PUBLISHED','DLT')),
    CONSTRAINT ck_dossier_dead_letter_attempt CHECK (attempt_count >= 0),
    CONSTRAINT ck_dossier_dead_letter_destination CHECK (destination LIKE '%.dossier-projection-v1.dlt'),
    CONSTRAINT ck_dossier_dead_letter_publication CHECK (
        (status = 'PUBLISHED' AND published_at IS NOT NULL)
        OR (status <> 'PUBLISHED' AND published_at IS NULL)
    )
);

CREATE INDEX idx_dossier_dead_letter_failed
    ON public.dossier_sanitized_dead_letter (failed_at, id);

CREATE TABLE public.dossier_replay_run (
    id uuid NOT NULL,
    row_version bigint NOT NULL DEFAULT 0,
    source_generation_id uuid NOT NULL,
    target_generation_id uuid NOT NULL,
    state varchar(24) NOT NULL,
    journal_high_water_at timestamptz NOT NULL,
    partition_high_water_sha256 char(64) NOT NULL,
    source_row_count bigint,
    target_row_count bigint,
    source_canonical_sha256 char(64),
    target_canonical_sha256 char(64),
    started_at timestamptz NOT NULL,
    completed_at timestamptz,
    CONSTRAINT dossier_replay_run_pkey PRIMARY KEY (id),
    CONSTRAINT ck_dossier_replay_run_state CHECK (state IN ('BUILDING','TAILING','VERIFYING','READY','ACTIVATED','REJECTED')),
    CONSTRAINT ck_dossier_replay_run_partition_hash CHECK (partition_high_water_sha256 ~ '^[0-9a-f]{64}$'),
    CONSTRAINT ck_dossier_replay_run_source_count CHECK (source_row_count IS NULL OR source_row_count >= 0),
    CONSTRAINT ck_dossier_replay_run_target_count CHECK (target_row_count IS NULL OR target_row_count >= 0),
    CONSTRAINT ck_dossier_replay_run_source_hash CHECK (source_canonical_sha256 IS NULL OR source_canonical_sha256 ~ '^[0-9a-f]{64}$'),
    CONSTRAINT ck_dossier_replay_run_target_hash CHECK (target_canonical_sha256 IS NULL OR target_canonical_sha256 ~ '^[0-9a-f]{64}$'),
    CONSTRAINT ck_dossier_replay_run_completion CHECK (
        (state IN ('BUILDING','TAILING','VERIFYING','READY') AND completed_at IS NULL)
        OR (state IN ('ACTIVATED','REJECTED') AND completed_at IS NOT NULL)
    ),
    CONSTRAINT fk_dossier_replay_run_source_generation FOREIGN KEY (source_generation_id)
        REFERENCES public.dossier_projection_generation (id),
    CONSTRAINT fk_dossier_replay_run_target_generation FOREIGN KEY (target_generation_id)
        REFERENCES public.dossier_projection_generation (id)
);

CREATE TABLE public.dossier_replay_partition_high_water (
    id uuid NOT NULL,
    replay_run_id uuid NOT NULL,
    source_topic varchar(200) NOT NULL,
    source_partition integer NOT NULL,
    max_offset bigint NOT NULL,
    CONSTRAINT dossier_replay_partition_high_water_pkey PRIMARY KEY (id),
    CONSTRAINT uq_dossier_replay_partition_high_water UNIQUE (
        replay_run_id, source_topic, source_partition
    ),
    CONSTRAINT ck_dossier_replay_partition_high_water_partition CHECK (source_partition >= 0),
    CONSTRAINT ck_dossier_replay_partition_high_water_offset CHECK (max_offset >= -1),
    CONSTRAINT fk_dossier_replay_partition_high_water_run FOREIGN KEY (replay_run_id)
        REFERENCES public.dossier_replay_run (id)
);

CREATE INDEX idx_dossier_replay_partition_high_water_run
    ON public.dossier_replay_partition_high_water (replay_run_id, source_topic, source_partition);

CREATE TABLE public.dossier_cabin_publication_head (
    id uuid NOT NULL,
    row_version bigint NOT NULL DEFAULT 0,
    cabin_id uuid NOT NULL,
    published_version bigint NOT NULL,
    updated_at timestamptz NOT NULL,
    CONSTRAINT dossier_cabin_publication_head_pkey PRIMARY KEY (id),
    CONSTRAINT uq_dossier_cabin_publication_head_cabin UNIQUE (cabin_id),
    CONSTRAINT ck_dossier_cabin_publication_head_version CHECK (published_version >= -1)
);

CREATE TABLE public.dossier_outbox_event (
    event_id uuid NOT NULL,
    row_version bigint NOT NULL DEFAULT 0,
    cabin_id uuid NOT NULL,
    aggregate_version bigint NOT NULL,
    source_event_id uuid NOT NULL,
    destination varchar(200) NOT NULL,
    event_type varchar(160) NOT NULL,
    payload_sha256 char(64) NOT NULL,
    canonical_payload jsonb NOT NULL,
    status varchar(24) NOT NULL,
    attempt_count integer NOT NULL,
    next_attempt_at timestamptz NOT NULL,
    created_at timestamptz NOT NULL,
    published_at timestamptz,
    CONSTRAINT dossier_outbox_event_pkey PRIMARY KEY (event_id),
    CONSTRAINT uq_dossier_outbox_source_cabin UNIQUE (source_event_id, cabin_id),
    CONSTRAINT uq_dossier_outbox_cabin_version UNIQUE (cabin_id, aggregate_version),
    CONSTRAINT ck_dossier_outbox_version CHECK (aggregate_version >= 0),
    CONSTRAINT ck_dossier_outbox_destination CHECK (destination = 'rwms.dossier.cabin-activity.v1'),
    CONSTRAINT ck_dossier_outbox_event_type CHECK (event_type = 'dossier.cabin-activity.projected.v1'),
    CONSTRAINT ck_dossier_outbox_hash CHECK (payload_sha256 ~ '^[0-9a-f]{64}$'),
    CONSTRAINT ck_dossier_outbox_payload CHECK (jsonb_typeof(canonical_payload) = 'object'),
    CONSTRAINT ck_dossier_outbox_status CHECK (status IN ('PENDING','RETRY','PUBLISHED','DLT')),
    CONSTRAINT ck_dossier_outbox_attempt CHECK (attempt_count >= 0),
    CONSTRAINT ck_dossier_outbox_publication CHECK (
        (status = 'PUBLISHED' AND published_at IS NOT NULL)
        OR (status <> 'PUBLISHED' AND published_at IS NULL)
    ),
    CONSTRAINT fk_dossier_outbox_source FOREIGN KEY (source_event_id)
        REFERENCES public.dossier_source_fact (event_id)
);

CREATE INDEX idx_dossier_outbox_due
    ON public.dossier_outbox_event (status, next_attempt_at, created_at, event_id);
