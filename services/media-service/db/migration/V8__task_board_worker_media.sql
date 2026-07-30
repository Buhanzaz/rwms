-- Task-board worker result media. The task-board stream is the sole owner of
-- entry visibility; this service projects its monotonic owner proof and fails
-- closed for every worker request until that projection is current and active.
-- No object, legacy media row, or user data is deleted by this migration.

alter table media_owner_binding
    drop constraint media_owner_binding_owner_proof_scope_check;
alter table media_owner_binding
    drop constraint media_owner_binding_owner_identity_check;
alter table media_owner_binding
    add constraint media_owner_binding_owner_proof_scope_check check (
        (owner_type = 'INVENTORY_FINDING' and proof_aggregate_type = 'FINDING')
        or (owner_type = 'CABIN' and proof_aggregate_type = 'RENTAL_ITEM')
        or (owner_type = 'MAINTENANCE_ESTIMATE' and proof_aggregate_type = 'ESTIMATE')
        or (owner_type = 'MAINTENANCE_REPAIR' and proof_aggregate_type = 'REPAIR')
        or (owner_type = 'MAINTENANCE_ACCEPTANCE' and proof_aggregate_type = 'ACCEPTANCE')
        or (owner_type = 'MAINTENANCE_CATALOG_NODE' and proof_aggregate_type = 'CATALOG_NODE')
        or (owner_type = 'LOGISTICS_RETURN' and proof_aggregate_type = 'RETURN')
        or (owner_type = 'LOGISTICS_SHIPMENT' and proof_aggregate_type = 'SHIPMENT')
        or (owner_type = 'LOGISTICS_TRANSFER' and proof_aggregate_type = 'TRANSFER')
        or (owner_type = 'TASK_BOARD_ENTRY' and proof_aggregate_type = 'TASK_BOARD_ENTRY_OWNER_PROOF')
    );
alter table media_owner_binding
    add constraint media_owner_binding_owner_identity_check check (
        (owner_type in (
            'INVENTORY_FINDING','CABIN','MAINTENANCE_ESTIMATE','MAINTENANCE_REPAIR',
            'MAINTENANCE_ACCEPTANCE','MAINTENANCE_CATALOG_NODE','TASK_BOARD_ENTRY'
        ) and owner_id = proof_aggregate_id::text)
        or (owner_type in (
            'LOGISTICS_RETURN','LOGISTICS_SHIPMENT','LOGISTICS_TRANSFER'
        ) and owner_id ~ '^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}:[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$')
    );

alter table media_asset
    add column client_reference_id uuid,
    add column created_by_principal_type varchar(16),
    add column created_by_actor_id uuid,
    add constraint media_asset_task_board_client_reference_check check (
        (owner_type = 'TASK_BOARD_ENTRY' and client_reference_id is not null)
        or (owner_type <> 'TASK_BOARD_ENTRY' and client_reference_id is null)
    ) not valid,
    add constraint media_asset_created_by_actor_check check (
        (created_by_principal_type is null and created_by_actor_id is null)
        or (created_by_principal_type in ('USER','WORKER') and created_by_actor_id is not null)
    ) not valid;

-- A worker may recreate an expired transport session for the same evidence,
-- but it must never create a second logical media asset for it.
create unique index media_asset_task_board_evidence_unique_idx
    on media_asset (owner_id, client_reference_id)
    where owner_type = 'TASK_BOARD_ENTRY';

alter table media_upload_session
    add column principal_type varchar(16) not null default 'USER'
        check (principal_type in ('USER','WORKER'));
alter table media_upload_session
    drop constraint if exists media_upload_session_subject_id_idempotency_key_key;
alter table media_upload_session
    add constraint media_upload_session_principal_idempotency_key
        unique (principal_type, subject_id, idempotency_key);

alter table media_command_idempotency
    add column principal_type varchar(16) not null default 'USER'
        check (principal_type in ('USER','WORKER'));
alter table media_command_idempotency
    drop constraint media_command_idempotency_pkey;
alter table media_command_idempotency
    add primary key (principal_type, subject_id, command_type, idempotency_key);

create table media_task_board_entry_owner_proof_inbox (
    consumer_name varchar(128) not null,
    event_id uuid not null,
    topic varchar(255) not null,
    event_type varchar(128) not null,
    aggregate_type varchar(64) not null,
    aggregate_id uuid not null,
    aggregate_version bigint not null check (aggregate_version >= 0),
    record_key uuid not null,
    warehouse_id uuid not null,
    route_index integer not null check (route_index >= 0),
    active boolean not null,
    body_sha256 char(64) not null check (body_sha256 ~ '^[0-9a-f]{64}$'),
    wire_body bytea not null,
    outcome varchar(16) not null check (outcome in ('APPLIED','QUARANTINED')),
    failure_code varchar(64),
    received_at timestamptz not null default clock_timestamp(),
    processed_at timestamptz,
    primary key (consumer_name,event_id),
    unique (consumer_name,aggregate_type,aggregate_id,aggregate_version),
    check (consumer_name = 'media-service-task-board-entry-owner-proof-v1'),
    check (topic = 'rwms.task-board.entry-owner-proof.v1'),
    check (event_type = 'task-board.entry-owner-proof.changed.v1'),
    check (aggregate_type = 'TASK_BOARD_ENTRY_OWNER_PROOF'),
    check (aggregate_id = record_key),
    check (body_sha256 = encode(sha256(wire_body), 'hex')),
    check (
        (outcome = 'APPLIED' and processed_at is not null and failure_code is null)
        or (outcome = 'QUARANTINED' and processed_at is null and failure_code is not null)
    )
);

create index media_task_board_entry_owner_proof_inbox_quarantine_idx
    on media_task_board_entry_owner_proof_inbox (aggregate_id,aggregate_version)
    where outcome = 'QUARANTINED';

create table media_task_board_entry_owner_proof_conflict (
    consumer_name varchar(128) not null,
    event_id uuid not null,
    prior_event_id uuid,
    prior_aggregate_id uuid,
    prior_aggregate_version bigint,
    prior_body_sha256 char(64),
    incoming_aggregate_id uuid not null,
    incoming_aggregate_version bigint not null check (incoming_aggregate_version >= 0),
    incoming_record_key uuid not null,
    incoming_body_sha256 char(64) not null check (incoming_body_sha256 ~ '^[0-9a-f]{64}$'),
    incoming_wire_body bytea not null,
    failure_code varchar(64) not null,
    detected_at timestamptz not null default clock_timestamp(),
    primary key (consumer_name,event_id,incoming_body_sha256),
    check (consumer_name = 'media-service-task-board-entry-owner-proof-v1'),
    check (incoming_body_sha256 = encode(sha256(incoming_wire_body), 'hex')),
    check ((prior_event_id is null) = (prior_body_sha256 is null))
);

create table media_task_board_entry_owner_proof (
    entry_id uuid primary key,
    warehouse_id uuid not null,
    route_index integer not null check (route_index >= 0),
    active boolean not null,
    aggregate_version bigint not null check (aggregate_version >= 0),
    proof_event_id uuid not null unique,
    body_sha256 char(64) not null check (body_sha256 ~ '^[0-9a-f]{64}$'),
    quarantined boolean not null default false,
    quarantine_reason varchar(64),
    updated_at timestamptz not null default clock_timestamp(),
    check ((quarantined and quarantine_reason is not null)
        or (not quarantined and quarantine_reason is null))
);

create table media_task_board_entry_allowed_worker (
    entry_id uuid not null references media_task_board_entry_owner_proof (entry_id),
    worker_id uuid not null,
    primary key (entry_id,worker_id)
);

create index media_task_board_entry_allowed_worker_access_idx
    on media_task_board_entry_allowed_worker (worker_id,entry_id);

create table media_task_board_entry_source_media_reference (
    entry_id uuid not null references media_task_board_entry_owner_proof (entry_id),
    media_id uuid not null,
    generation integer not null check (generation >= 1),
    primary key (entry_id,media_id,generation)
);

-- Domain events and replay snapshots retain the stable evidence ID and the
-- server-derived actor. Keep the prior canonical state function under an
-- explicit historical name rather than reconstructing old payload fields in
-- Go; this makes every post-V8 event deterministic while preserving V1-V7
-- replay bytes exactly as they were recorded.
alter function media_full_state(uuid) rename to media_full_state_v7;

create function media_full_state(p_media_id uuid)
returns jsonb language sql stable as $$
select jsonb_set(
    jsonb_set(
        jsonb_set(
            jsonb_set(
                media_full_state_v7(p_media_id),
                '{asset,clientReferenceId}',
                coalesce(to_jsonb(asset.client_reference_id), 'null'::jsonb), true),
            '{asset,createdByPrincipalType}',
            coalesce(to_jsonb(asset.created_by_principal_type), 'null'::jsonb), true),
        '{asset,createdByActorId}',
        coalesce(to_jsonb(asset.created_by_actor_id), 'null'::jsonb), true),
    '{uploadSession}',
    case when session.media_id is null then state.value -> 'uploadSession'
         else jsonb_set(state.value -> 'uploadSession', '{principalType}',
             to_jsonb(session.principal_type), true)
    end,
    true)
from media_asset asset
cross join lateral (select media_full_state_v7(asset.media_id) as value) state
left join media_upload_session session on session.media_id=asset.media_id
where asset.media_id=p_media_id
$$;

-- Invalid task-board records are retained in the service-local dead-letter
-- ledger before their Kafka offset is committed. A valid but out-of-order
-- proof is represented by the durable inbox/quarantine tables above.
alter table media_dead_letter
    add constraint media_dead_letter_task_board_owner_failure_check check (
        consumer_name <> 'media-service-task-board-entry-owner-proof-v1'
        or failure_code in (
            'INVALID_TASK_BOARD_ENTRY_OWNER_PROOF',
            'TASK_BOARD_OWNER_EVENT_ID_CONFLICT'
        )
    );

-- Existing rows predate TASK_BOARD_ENTRY and have no worker actor fields, so
-- both checks can be validated during the same upgrade rather than leaving a
-- permanent unvalidated integrity gap in the production schema.
alter table media_asset
    validate constraint media_asset_task_board_client_reference_check;
alter table media_asset
    validate constraint media_asset_created_by_actor_check;
