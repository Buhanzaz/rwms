create table media_asset (
    media_id uuid primary key,
    owner_type varchar(64) not null,
    owner_id varchar(128) not null,
    warehouse_id uuid not null,
    media_kind varchar(16) not null check (media_kind in ('IMAGE', 'VIDEO')),
    original_file_name varchar(512) not null,
    original_content_type varchar(255) not null,
    source_object_key varchar(1024) not null unique,
    processing_status varchar(16) not null check (processing_status in ('UPLOADING', 'PROCESSING', 'READY', 'FAILED', 'DELETED')),
    rotation_degrees smallint not null default 0 check (rotation_degrees in (0, 90, 180, 270)),
    current_generation integer not null default 0 check (current_generation >= 0),
    pending_generation integer check (pending_generation > 0),
    pending_rotation_degrees smallint check (pending_rotation_degrees in (0, 90, 180, 270)),
    sort_order bigint not null default 0,
    size_bytes bigint,
    processing_attempts integer not null default 0 check (processing_attempts >= 0),
    processing_error varchar(2000),
    version bigint not null default 0 check (version >= 0),
    created_at timestamptz not null default clock_timestamp(),
    updated_at timestamptz not null default clock_timestamp(),
    deleted_at timestamptz,
    check (
        (pending_generation is null and pending_rotation_degrees is null)
        or (
            pending_generation > current_generation
            and pending_rotation_degrees is not null
        )
    )
);

create index media_asset_owner_order_idx
    on media_asset (owner_type, owner_id, media_kind, sort_order, created_at)
    where deleted_at is null;

create table media_variant (
    media_id uuid not null references media_asset (media_id),
    generation integer not null check (generation >= 0),
    variant varchar(16) not null check (variant in ('SMALL', 'MEDIUM', 'LARGE', 'ORIGINAL')),
    object_key varchar(1024) not null,
    content_type varchar(255) not null,
    size_bytes bigint not null check (size_bytes >= 0),
    width integer,
    height integer,
    checksum_sha256 char(64) not null,
    created_at timestamptz not null default clock_timestamp(),
    primary key (media_id, generation, variant),
    unique (object_key),
    check ((width is null and height is null) or (width > 0 and height > 0))
);

create table media_upload_session (
    upload_session_id uuid primary key,
    media_id uuid not null unique references media_asset (media_id),
    subject_id uuid not null,
    idempotency_key uuid not null,
    expected_content_length bigint not null check (expected_content_length > 0),
    expected_content_type varchar(255) not null,
    expires_at timestamptz not null,
    completed_at timestamptz,
    created_at timestamptz not null default clock_timestamp(),
    unique (subject_id, idempotency_key)
);

create index media_upload_session_open_idx
    on media_upload_session (expires_at)
    where completed_at is null;

create table media_command_idempotency (
    subject_id uuid not null,
    command_type varchar(64) not null,
    idempotency_key uuid not null,
    request_sha256 char(64) not null,
    media_id uuid not null references media_asset (media_id),
    created_at timestamptz not null default clock_timestamp(),
    expires_at timestamptz not null,
    primary key (subject_id, command_type, idempotency_key)
);

create index media_command_idempotency_expiry_idx
    on media_command_idempotency (expires_at);

create table media_processing_job (
    processing_job_id uuid primary key,
    media_id uuid not null references media_asset (media_id),
    generation integer not null check (generation >= 0),
    processing_kind varchar(16) not null check (processing_kind in ('INITIAL', 'ROTATION')),
    requested_rotation_degrees smallint not null check (requested_rotation_degrees in (0, 90, 180, 270)),
    job_status varchar(16) not null check (job_status in ('PENDING', 'RUNNING', 'COMPLETED', 'FAILED')),
    attempt_count integer not null default 0 check (attempt_count >= 0),
    lease_owner varchar(128),
    lease_token uuid,
    lease_until timestamptz,
    last_error varchar(2000),
    created_at timestamptz not null default clock_timestamp(),
    completed_at timestamptz,
    unique (media_id, generation)
);

create index media_processing_job_claim_idx
    on media_processing_job (job_status, created_at)
    where job_status in ('PENDING', 'RUNNING');

create table media_outbox_event (
    event_id uuid primary key,
    aggregate_id uuid not null references media_asset (media_id),
    aggregate_version bigint not null check (aggregate_version >= 0),
    event_type varchar(128) not null,
    topic varchar(255) not null,
    envelope_body jsonb not null,
    envelope_sha256 char(64) not null,
    event_status varchar(16) not null default 'PENDING' check (event_status in ('PENDING', 'PUBLISHING', 'PUBLISHED', 'FAILED')),
    attempt_count integer not null default 0 check (attempt_count >= 0),
    lease_owner varchar(128),
    lease_token uuid,
    lease_until timestamptz,
    last_error varchar(2000),
    recorded_at timestamptz not null default clock_timestamp(),
    published_at timestamptz,
    unique (aggregate_id, aggregate_version, event_type)
);

create index media_outbox_event_claim_idx
    on media_outbox_event (event_status, recorded_at)
    where event_status in ('PENDING', 'PUBLISHING');

create table media_inbox_message (
    consumer_name varchar(128) not null,
    event_id uuid not null,
    body_sha256 char(64) not null,
    received_at timestamptz not null default clock_timestamp(),
    primary key (consumer_name, event_id)
);
