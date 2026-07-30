-- Durable, service-private import jobs for public Yandex.Disk folders. The
-- browser URL and Yandex download href are intentionally never persisted;
-- only the parsed 14-character public key and resource path remain inside the
-- media-service database. This migration adds no object deletion or cleanup.

create table media_asset_import_job (
    job_id uuid primary key,
    asset_import_id uuid not null unique,
    warehouse_id uuid not null,
    preflight_idempotency_key uuid not null,
    preflight_request_sha256 char(64) not null
        check (preflight_request_sha256 ~ '^[0-9a-f]{64}$'),
    activation_idempotency_key uuid,
    activation_request_sha256 char(64)
        check (activation_request_sha256 is null or activation_request_sha256 ~ '^[0-9a-f]{64}$'),
    job_status varchar(32) not null check (job_status in (
        'PREFLIGHT_PENDING','PREFLIGHT_RUNNING','PREFLIGHT_READY',
        'ACTIVATION_PENDING','ACTIVATION_RUNNING','COMPLETED','FAILED'
    )),
    failed_phase varchar(16) check (failed_phase in ('PREFLIGHT','ACTIVATION')),
    failure_code varchar(64),
    preflight_attempt_count integer not null default 0 check (preflight_attempt_count >= 0),
    activation_attempt_count integer not null default 0 check (activation_attempt_count >= 0),
    next_attempt_at timestamptz not null default clock_timestamp(),
    lease_owner varchar(128),
    lease_token uuid,
    lease_until timestamptz,
    created_at timestamptz not null default clock_timestamp(),
    updated_at timestamptz not null default clock_timestamp(),
    check ((lease_owner is null and lease_token is null and lease_until is null)
        or (lease_owner is not null and lease_token is not null and lease_until is not null)),
    check ((job_status = 'FAILED' and failed_phase is not null and failure_code is not null)
        or (job_status <> 'FAILED' and failed_phase is null)),
    check ((job_status in ('PREFLIGHT_RUNNING','ACTIVATION_RUNNING') and lease_owner is not null)
        or (job_status not in ('PREFLIGHT_RUNNING','ACTIVATION_RUNNING')))
);

create index media_asset_import_job_claim_idx
    on media_asset_import_job (next_attempt_at, created_at)
    where job_status in ('PREFLIGHT_PENDING','PREFLIGHT_RUNNING','ACTIVATION_PENDING','ACTIVATION_RUNNING');

create table media_asset_import_source (
    job_id uuid not null references media_asset_import_job (job_id),
    source_row_id uuid not null,
    yandex_public_key char(14) not null
        check (yandex_public_key ~ '^[A-Za-z0-9_-]{14}$'),
    cabin_id uuid,
    created_at timestamptz not null default clock_timestamp(),
    primary key (job_id, source_row_id)
);

create table media_asset_import_entry (
    entry_id uuid not null,
    job_id uuid not null,
    source_row_id uuid not null,
    resource_path varchar(4096) not null,
    file_name varchar(512) not null,
    content_type varchar(255) not null,
    discovered_size_bytes bigint,
    entry_status varchar(16) not null check (entry_status in (
        'PREPARED','DOWNLOADING','IMPORTED','SKIPPED','FAILED'
    )),
    warning_code varchar(64),
    media_id uuid references media_asset (media_id),
    created_at timestamptz not null default clock_timestamp(),
    updated_at timestamptz not null default clock_timestamp(),
    primary key (entry_id),
    unique (job_id, source_row_id, resource_path),
    foreign key (job_id, source_row_id)
        references media_asset_import_source (job_id, source_row_id),
    check (discovered_size_bytes is null or discovered_size_bytes >= 0),
    check ((entry_status = 'IMPORTED' and media_id is not null)
        or (entry_status <> 'IMPORTED')),
    check ((entry_status in ('SKIPPED','FAILED') and warning_code is not null)
        or (entry_status not in ('SKIPPED','FAILED')))
);

create index media_asset_import_entry_job_status_idx
    on media_asset_import_entry (job_id, entry_status, source_row_id, entry_id);

create table media_asset_import_retry_receipt (
    job_id uuid not null references media_asset_import_job (job_id),
    idempotency_key uuid not null,
    request_sha256 char(64) not null check (request_sha256 ~ '^[0-9a-f]{64}$'),
    created_at timestamptz not null default clock_timestamp(),
    primary key (job_id, idempotency_key)
);
