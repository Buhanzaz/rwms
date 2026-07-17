-- Runtime/recovery expansion for the combined Stage 3-4 media-service.
-- V1 is immutable. This migration preserves every V1 row and adds no delete,
-- retention, orphan-cleanup, or object-purge behavior.

alter table media_asset
    add column next_generation integer not null default 1,
    add column source_version_id varchar(255),
    add column source_etag varchar(255),
    add column source_checksum_sha256 char(64),
    add column finalized_content_type varchar(255),
    add column finalized_size_bytes bigint,
    add constraint media_asset_next_generation_positive check (next_generation > 0),
    add constraint media_asset_source_checksum_shape check (
        source_checksum_sha256 is null or source_checksum_sha256 ~ '^[0-9a-f]{64}$'
    ),
    add constraint media_asset_finalized_size_positive check (
        finalized_size_bytes is null or finalized_size_bytes > 0
    );

update media_asset
set next_generation = greatest(current_generation, coalesce(pending_generation, 0)) + 1,
    version = greatest(version, 1);

alter table media_asset
    add constraint media_asset_runtime_source_state check (
        (processing_status = 'UPLOADING' and source_version_id is null)
        or (processing_status in ('PROCESSING','READY','FAILED','DELETED')
            and source_version_id is not null and source_checksum_sha256 is not null)
    ) not valid,
    add constraint media_asset_runtime_pending_state check (
        (processing_status = 'PROCESSING'
            and pending_generation is not null and pending_rotation_degrees is not null)
        or (processing_status <> 'PROCESSING'
            and pending_generation is null and pending_rotation_degrees is null)
    ) not valid;

alter table media_processing_job
    add column source_version_id varchar(255),
    add column source_checksum_sha256 char(64),
    add column lease_fence bigint not null default 0,
    add column next_attempt_at timestamptz not null default clock_timestamp(),
    add constraint media_processing_job_lease_fence_nonnegative check (lease_fence >= 0),
    add constraint media_processing_job_source_checksum_shape check (
        source_checksum_sha256 is null or source_checksum_sha256 ~ '^[0-9a-f]{64}$'
    ),
    add constraint media_processing_job_runtime_source_required check (
        generation > 0 and source_version_id is not null
            and source_checksum_sha256 is not null
    ) not valid,
    add constraint media_processing_job_runtime_lease_state check (
        (job_status = 'RUNNING' and lease_owner is not null and lease_token is not null
            and lease_until is not null and lease_fence > 0)
        or (job_status <> 'RUNNING' and lease_owner is null and lease_token is null
            and lease_until is null)
    ) not valid;

-- V1 intentionally allowed generation zero. It is retained as evidence and
-- quarantined below rather than making the upgrade fail or rewriting history.
alter table media_variant
    add column object_version_id varchar(255),
    add constraint media_variant_runtime_pinned check (
        generation > 0 and object_version_id is not null and size_bytes > 0
            and checksum_sha256 ~ '^[0-9a-f]{64}$'
    ) not valid;

alter table media_upload_session
    add column expected_checksum_sha256 char(64),
    add constraint media_upload_session_checksum_shape check (
        expected_checksum_sha256 is null or expected_checksum_sha256 ~ '^[0-9a-f]{64}$'
    ),
    add constraint media_upload_session_runtime_checksum_required check (
        expected_checksum_sha256 is not null
    ) not valid;

create table media_event_stream_head (
    aggregate_type varchar(64) not null,
    aggregate_id uuid not null,
    stream_version bigint not null check (stream_version > 0),
    updated_at timestamptz not null default clock_timestamp(),
    primary key (aggregate_type, aggregate_id)
);

create table media_domain_event (
    event_id uuid primary key,
    aggregate_type varchar(64) not null,
    aggregate_id uuid not null,
    aggregate_version bigint not null check (aggregate_version > 0),
    event_type varchar(128) not null,
    event_version integer not null check (event_version > 0),
    payload jsonb not null,
    payload_wire bytea not null,
    payload_sha256 char(64) not null check (payload_sha256 ~ '^[0-9a-f]{64}$'),
    actor_ref jsonb,
    correlation_id uuid not null,
    causation_id uuid,
    occurred_at timestamptz,
    recorded_at timestamptz not null default clock_timestamp(),
    unique (aggregate_type, aggregate_id, aggregate_version),
    check (jsonb_typeof(payload) = 'object'),
    check (actor_ref is null or jsonb_typeof(actor_ref) = 'object'),
    check (payload_sha256 = encode(sha256(payload_wire), 'hex')),
    check (payload = convert_from(payload_wire, 'UTF8')::jsonb)
);

create index media_domain_event_replay_idx
    on media_domain_event (aggregate_type, aggregate_id, aggregate_version);

create table media_event_snapshot (
    aggregate_type varchar(64) not null,
    aggregate_id uuid not null,
    aggregate_version bigint not null check (aggregate_version > 0),
    snapshot jsonb not null,
    snapshot_wire bytea not null,
    snapshot_sha256 char(64) not null check (snapshot_sha256 ~ '^[0-9a-f]{64}$'),
    created_at timestamptz not null default clock_timestamp(),
    primary key (aggregate_type, aggregate_id),
    check (jsonb_typeof(snapshot) = 'object'),
    check (snapshot_sha256 = encode(sha256(snapshot_wire), 'hex')),
    check (snapshot = convert_from(snapshot_wire, 'UTF8')::jsonb)
);

create table media_projection_checkpoint (
    projection_name varchar(128) primary key,
    last_recorded_at timestamptz,
    last_event_id uuid,
    updated_at timestamptz not null default clock_timestamp(),
    check ((last_recorded_at is null) = (last_event_id is null))
);

create table media_consumer_aggregate_checkpoint (
    consumer_name varchar(128) not null,
    aggregate_type varchar(64) not null,
    aggregate_id uuid not null,
    aggregate_version bigint not null check (aggregate_version >= 0),
    updated_at timestamptz not null default clock_timestamp(),
    primary key (consumer_name, aggregate_type, aggregate_id)
);

create table media_quarantined_aggregate (
    consumer_name varchar(128) not null,
    aggregate_type varchar(64) not null,
    aggregate_id uuid not null,
    expected_version bigint not null check (expected_version > 0),
    observed_version bigint not null check (observed_version > 0),
    reason_code varchar(64) not null,
    first_event_id uuid not null,
    quarantined_at timestamptz not null default clock_timestamp(),
    reconciled_at timestamptz,
    primary key (consumer_name, aggregate_type, aggregate_id),
    check (
        (reason_code = 'VERSION_GAP' and observed_version <> expected_version)
        or (reason_code <> 'VERSION_GAP' and observed_version = expected_version)
    )
);

create table media_owner_binding (
    owner_type varchar(64) not null,
    owner_id varchar(128) not null,
    warehouse_id uuid not null,
    owner_revision bigint not null check (owner_revision >= 0),
    proof_event_id uuid not null,
    proof_consumer_name varchar(128) not null,
    proof_aggregate_type varchar(64) not null,
    proof_aggregate_id uuid not null,
    proof_aggregate_version bigint not null check (proof_aggregate_version > 0),
    proof_recorded_at timestamptz not null,
    active boolean not null,
    updated_at timestamptz not null default clock_timestamp(),
    primary key (owner_type, owner_id),
    unique (proof_event_id),
    check (owner_type = 'INVENTORY_FINDING'),
    check (proof_aggregate_type = 'INVENTORY_FINDING'),
    check (owner_id = proof_aggregate_id::text)
);

create index media_owner_binding_authorization_idx
    on media_owner_binding (warehouse_id, owner_type, owner_id, owner_revision)
    where active;

create table media_retry_schedule (
    consumer_name varchar(128) not null,
    event_id uuid not null,
    body_sha256 char(64) not null check (body_sha256 ~ '^[0-9a-f]{64}$'),
    attempt integer not null check (attempt between 1 and 3),
    available_at timestamptz not null,
    last_error_code varchar(64) not null,
    created_at timestamptz not null default clock_timestamp(),
    primary key (consumer_name, event_id)
);

create table media_dead_letter (
    consumer_name varchar(128) not null,
    event_id uuid not null,
    aggregate_type varchar(64),
    aggregate_id uuid,
    aggregate_version bigint,
    body_sha256 char(64) not null check (body_sha256 ~ '^[0-9a-f]{64}$'),
    failure_code varchar(64) not null,
    attempt_count integer not null check (attempt_count between 0 and 4),
    recorded_at timestamptz not null default clock_timestamp(),
    primary key (consumer_name, event_id),
    check (aggregate_version is null or aggregate_version > 0)
);

create table media_processing_inbox (
    consumer_name varchar(128) not null,
    event_id uuid not null,
    topic varchar(255) not null,
    event_type varchar(128) not null,
    aggregate_type varchar(64) not null,
    aggregate_id uuid not null,
    aggregate_version bigint not null check (aggregate_version > 0),
    record_key uuid not null,
    body_sha256 char(64) not null check (body_sha256 ~ '^[0-9a-f]{64}$'),
    outcome varchar(16) not null check (outcome in ('APPLIED','DLT','QUARANTINED')),
    received_at timestamptz not null default clock_timestamp(),
    primary key (consumer_name,event_id),
    check (aggregate_type = 'PROCESSING_JOB'),
    check (aggregate_id = record_key)
);

create table media_owner_proof_inbox (
    consumer_name varchar(128) not null,
    event_id uuid not null,
    aggregate_type varchar(64) not null,
    aggregate_id uuid not null,
    aggregate_version bigint not null check (aggregate_version > 0),
    body_sha256 char(64) not null check (body_sha256 ~ '^[0-9a-f]{64}$'),
    owner_type varchar(64) not null check (owner_type = 'INVENTORY_FINDING'),
    owner_id varchar(128) not null,
    warehouse_id uuid not null,
    owner_revision bigint not null check (owner_revision >= 0),
    active boolean not null,
    received_at timestamptz not null default clock_timestamp(),
    primary key (consumer_name,event_id),
    unique (consumer_name,aggregate_type,aggregate_id,aggregate_version),
    check (aggregate_type = 'INVENTORY_FINDING'),
    check (owner_id = aggregate_id::text)
);

create table media_recovery_quarantine (
    source_table varchar(64) not null,
    source_id uuid not null,
    reason_code varchar(64) not null,
    evidence_sha256 char(64) not null check (evidence_sha256 ~ '^[0-9a-f]{64}$'),
    quarantined_at timestamptz not null default clock_timestamp(),
    primary key (source_table, source_id, reason_code)
);

-- One runtime visibility predicate keeps every asset-facing query and mutation
-- aligned with recovery quarantine. Quarantine is intentionally fail-closed:
-- a row becomes available only through a later reviewed migration which removes
-- its evidence after the legacy state has been reconciled.
create or replace function media_asset_is_available(p_media_id uuid)
returns boolean
language sql
stable
as $$
    select not exists (
        select 1
        from media_recovery_quarantine quarantine
        where quarantine.source_table='media_asset'
          and quarantine.source_id=p_media_id
    )
$$;

create table media_transport_outbox (
    event_id uuid primary key,
    aggregate_type varchar(64) not null,
    aggregate_id uuid not null,
    aggregate_version bigint not null check (aggregate_version > 0),
    event_type varchar(128) not null,
    topic varchar(255) not null,
    record_key uuid not null,
    publication_ordinal bigint not null check (publication_ordinal > 0),
    depends_on_event_id uuid references media_transport_outbox (event_id),
    envelope_body jsonb not null,
    wire_body bytea not null,
    envelope_sha256 char(64) not null check (envelope_sha256 ~ '^[0-9a-f]{64}$'),
    event_status varchar(16) not null default 'PENDING'
        check (event_status in ('PENDING', 'PUBLISHING', 'PUBLISHED', 'FAILED')),
    attempt_count integer not null default 0 check (attempt_count >= 0),
    next_attempt_at timestamptz not null default clock_timestamp(),
    lease_owner varchar(128),
    lease_token uuid,
    lease_fence bigint not null default 0 check (lease_fence >= 0),
    lease_until timestamptz,
    last_error_code varchar(64),
    recorded_at timestamptz not null default clock_timestamp(),
    published_at timestamptz,
    unique (aggregate_type, aggregate_id, aggregate_version, event_type),
    unique (aggregate_type, aggregate_id, publication_ordinal),
    check (jsonb_typeof(envelope_body) = 'object'),
    check (envelope_sha256 = encode(sha256(wire_body), 'hex')),
    check (envelope_body = convert_from(wire_body, 'UTF8')::jsonb),
    check (depends_on_event_id is null or depends_on_event_id <> event_id),
    check (
        (event_type in (
            'media.media.uploaded.v1','media.media.ready.v1','media.media.failed.v1',
            'media.media.rotated.v1','media.media.deleted.v1'
         ) and aggregate_type='MEDIA' and topic='rwms.media.media.v1'
            and record_key=aggregate_id and depends_on_event_id is null)
        or (event_type='media.processing.request.v1'
            and aggregate_type='PROCESSING_JOB' and aggregate_version=1
            and topic='rwms.media.processing.v1' and record_key=aggregate_id
            and depends_on_event_id is not null)
        or (event_type='media.processing.dlt.v1'
            and aggregate_type='PROCESSING_DLT' and aggregate_version=1
            and topic='rwms.media.processing.v1.media-service-processing-v1.dlt'
            and record_key=aggregate_id)
    ),
    check (
        (event_status = 'PUBLISHING' and lease_owner is not null and lease_token is not null
            and lease_until is not null and lease_fence > 0)
        or (event_status <> 'PUBLISHING' and lease_owner is null and lease_token is null
            and lease_until is null)
    ),
    check (
        (event_status = 'PUBLISHED' and published_at is not null)
        or (event_status <> 'PUBLISHED' and published_at is null)
    )
);

create index media_transport_outbox_claim_idx
    on media_transport_outbox (next_attempt_at, recorded_at, publication_ordinal)
    where event_status in ('PENDING', 'PUBLISHING');

create index media_transport_outbox_dependency_idx
    on media_transport_outbox (depends_on_event_id);

create or replace function media_validate_outbox_dependency()
returns trigger language plpgsql as $$
begin
    if new.depends_on_event_id is null then
        return new;
    end if;
    if exists (
        with recursive ancestors(event_id,depends_on_event_id) as (
            select event_id,depends_on_event_id from media_transport_outbox
            where event_id=new.depends_on_event_id
            union all
            select parent.event_id,parent.depends_on_event_id
            from media_transport_outbox parent
            join ancestors child on parent.event_id=child.depends_on_event_id
        )
        select 1 from ancestors where event_id=new.event_id
    ) then
        raise exception 'media outbox dependency cycle';
    end if;
    return new;
end;
$$;

create trigger media_transport_outbox_dependency_guard
before insert or update of depends_on_event_id on media_transport_outbox
for each row execute function media_validate_outbox_dependency();

create table media_legacy_outbox_migration (
    legacy_event_id uuid primary key references media_outbox_event(event_id),
    transport_event_id uuid references media_transport_outbox(event_id),
    migration_disposition varchar(32) not null
        check (migration_disposition in ('PUBLISHED_HISTORICAL', 'UNPUBLISHED_QUARANTINED')),
    migrated_at timestamptz not null default clock_timestamp(),
    check (transport_event_id is null)
);

create table media_runtime_lease (
    lease_name varchar(128) primary key,
    lease_owner varchar(128) not null,
    lease_token uuid not null,
    lease_fence bigint not null check (lease_fence > 0),
    lease_until timestamptz not null,
    updated_at timestamptz not null default clock_timestamp()
);

create index media_processing_job_retry_claim_idx
    on media_processing_job (next_attempt_at, created_at)
    where job_status in ('PENDING', 'RUNNING');

-- One canonical full-state representation is shared by migration baselines,
-- runtime domain events, replay snapshots and shadow-projection verification.
-- Transport facts remain separately sanitized and never reuse this payload.
create or replace function media_full_state(p_media_id uuid)
returns jsonb language sql stable as $$
select jsonb_build_object(
    'schemaVersion', 1,
    'asset', jsonb_build_object(
        'mediaId', asset.media_id,
        'ownerType', asset.owner_type,
        'ownerId', asset.owner_id,
        'warehouseId', asset.warehouse_id,
        'kind', asset.media_kind,
        'fileName', asset.original_file_name,
        'contentType', asset.original_content_type,
        'sourceObjectKey', asset.source_object_key,
        'sourceVersionId', asset.source_version_id,
        'sourceEtag', asset.source_etag,
        'sourceChecksumSha256', asset.source_checksum_sha256,
        'finalizedContentType', asset.finalized_content_type,
        'finalizedSizeBytes', asset.finalized_size_bytes,
        'status', asset.processing_status,
        'rotationDegrees', asset.rotation_degrees,
        'currentGeneration', asset.current_generation,
        'pendingGeneration', asset.pending_generation,
        'pendingRotationDegrees', asset.pending_rotation_degrees,
        'nextGeneration', asset.next_generation,
        'sortOrder', asset.sort_order,
        'sizeBytes', asset.size_bytes,
        'processingAttempts', asset.processing_attempts,
        'processingError', asset.processing_error,
        'version', asset.version,
        'createdAt', asset.created_at,
        'updatedAt', asset.updated_at,
        'deletedAt', asset.deleted_at
    ),
    'uploadSession', (
        select jsonb_build_object(
            'uploadSessionId', session.upload_session_id,
            'mediaId', session.media_id,
            'subjectId', session.subject_id,
            'idempotencyKey', session.idempotency_key,
            'expectedContentLength', session.expected_content_length,
            'expectedContentType', session.expected_content_type,
            'expectedChecksumSha256', session.expected_checksum_sha256,
            'expiresAt', session.expires_at,
            'completedAt', session.completed_at,
            'createdAt', session.created_at
        )
        from media_upload_session session where session.media_id=asset.media_id
    ),
    'processingJobs', coalesce((
        select jsonb_agg(jsonb_build_object(
            'processingJobId', job.processing_job_id,
            'mediaId', job.media_id,
            'generation', job.generation,
            'processingKind', job.processing_kind,
            'requestedRotationDegrees', job.requested_rotation_degrees,
            -- RUNNING/PENDING, attempt counters, leases and retry timing are
            -- delivery mechanics. Normalize an active intent to PENDING so a
            -- lease acquisition/release never mutates canonical domain state.
            'status', case
                when asset.processing_status='PROCESSING'
                    and asset.pending_generation=job.generation then 'PENDING'
                else job.job_status
            end,
            'sourceVersionId', job.source_version_id,
            'sourceChecksumSha256', job.source_checksum_sha256,
            'createdAt', job.created_at,
            'completedAt', job.completed_at
        ) order by job.generation,job.processing_job_id)
        from media_processing_job job where job.media_id=asset.media_id
    ), '[]'::jsonb),
    'variants', coalesce((
        select jsonb_agg(jsonb_build_object(
            'mediaId', variant.media_id,
            'generation', variant.generation,
            'variant', variant.variant,
            'objectKey', variant.object_key,
            'objectVersionId', variant.object_version_id,
            'contentType', variant.content_type,
            'sizeBytes', variant.size_bytes,
            'width', variant.width,
            'height', variant.height,
            'checksumSha256', variant.checksum_sha256,
            'createdAt', variant.created_at
        ) order by variant.generation,variant.variant)
        from media_variant variant where variant.media_id=asset.media_id
    ), '[]'::jsonb)
)
from media_asset asset
where asset.media_id=p_media_id
$$;

-- Deterministic sanitized local baseline. It is a service-local replay seed,
-- never a public business fact. It uses the same complete local state shape as
-- every later domain event and never enters the transport outbox.
with baseline as (
    select a.*,
           media_full_state(a.media_id) as state,
           (substr(md5('media-baseline-event:' || a.media_id::text),1,8) || '-' ||
            substr(md5('media-baseline-event:' || a.media_id::text),9,4) || '-' ||
            substr(md5('media-baseline-event:' || a.media_id::text),13,4) || '-' ||
            substr(md5('media-baseline-event:' || a.media_id::text),17,4) || '-' ||
            substr(md5('media-baseline-event:' || a.media_id::text),21,12))::uuid as event_id,
           (substr(md5('media-baseline-correlation:' || a.media_id::text),1,8) || '-' ||
            substr(md5('media-baseline-correlation:' || a.media_id::text),9,4) || '-' ||
            substr(md5('media-baseline-correlation:' || a.media_id::text),13,4) || '-' ||
            substr(md5('media-baseline-correlation:' || a.media_id::text),17,4) || '-' ||
            substr(md5('media-baseline-correlation:' || a.media_id::text),21,12))::uuid as correlation_id
    from media_asset a
), wire as (
    select baseline.*, convert_to(state::text, 'UTF8') as state_wire
    from baseline
)
insert into media_domain_event (
    event_id,aggregate_type,aggregate_id,aggregate_version,event_type,event_version,
    payload,payload_wire,payload_sha256,actor_ref,correlation_id,recorded_at)
select event_id,'MEDIA',media_id,1,'media.projection.baselined.v1',1,
       state,state_wire,encode(sha256(state_wire),'hex'),null,correlation_id,updated_at
from wire;

insert into media_event_stream_head (aggregate_type,aggregate_id,stream_version,updated_at)
select 'MEDIA',media_id,1,updated_at from media_asset;

with snapshot as (
    select event.aggregate_id,event.aggregate_version,event.payload,
           event.payload_wire,event.payload_sha256,event.recorded_at
    from media_domain_event event
    where event.event_type='media.projection.baselined.v1'
)
insert into media_event_snapshot (
    aggregate_type,aggregate_id,aggregate_version,snapshot,snapshot_wire,snapshot_sha256,created_at)
select 'MEDIA',aggregate_id,aggregate_version,payload,payload_wire,payload_sha256,recorded_at
from snapshot;

-- Preserve unsafe V1 rows unchanged and make them unavailable to the runtime.
insert into media_recovery_quarantine (source_table,source_id,reason_code,evidence_sha256)
select 'media_processing_job',processing_job_id,'LEGACY_UNPINNED_OR_ZERO_GENERATION',
       encode(sha256(convert_to(row_to_json(job)::text,'UTF8')),'hex')
from media_processing_job job
where generation <= 0 or source_version_id is null or source_checksum_sha256 is null;

insert into media_recovery_quarantine (source_table,source_id,reason_code,evidence_sha256)
select 'media_asset',asset.media_id,'LEGACY_UNPINNED_PROCESSING_STATE',
       encode(sha256(convert_to(row_to_json(asset)::text,'UTF8')),'hex')
from media_asset asset
where processing_status='PROCESSING'
  and (source_version_id is null or source_checksum_sha256 is null);

insert into media_recovery_quarantine (source_table,source_id,reason_code,evidence_sha256)
select 'media_asset',variant.media_id,'LEGACY_UNPINNED_VARIANT',
       encode(sha256(convert_to(jsonb_build_object(
           'mediaId',variant.media_id,'generation',variant.generation,'variant',variant.variant
       )::text,'UTF8')),'hex')
from media_variant variant
where variant.generation <= 0 or variant.object_version_id is null
on conflict do nothing;

-- V1 persisted JSONB but not the exact original wire bytes. Re-serializing it
-- would manufacture a new checksum and could legitimize malformed envelopes.
-- No legacy row is therefore copied into the active V2 transport outbox.
insert into media_recovery_quarantine (source_table,source_id,reason_code,evidence_sha256)
select 'media_outbox_event',legacy.event_id,'LEGACY_OUTBOX_UNPROVEN_WIRE',
       encode(sha256(convert_to(row_to_json(legacy)::text,'UTF8')),'hex')
from media_outbox_event legacy
where legacy.event_status <> 'PUBLISHED';

insert into media_legacy_outbox_migration (legacy_event_id,transport_event_id,migration_disposition)
select legacy.event_id,null,
       case when legacy.event_status='PUBLISHED'
            then 'PUBLISHED_HISTORICAL' else 'UNPUBLISHED_QUARANTINED' end
from media_outbox_event legacy;

create or replace function media_forbid_domain_event_mutation()
returns trigger language plpgsql as $$
begin
    raise exception 'media_domain_event is append-only';
end;
$$;

create trigger media_domain_event_append_only
before update or delete on media_domain_event
for each row execute function media_forbid_domain_event_mutation();
