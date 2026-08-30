-- Driver-shift evidence reuses the canonical media lifecycle while keeping
-- task-board's shift aggregate and worker audience authoritative. Existing
-- assets, proofs, objects and upload sessions are preserved unchanged.

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
        or (owner_type = 'LOGISTICS_CUSTOMER_PROFILE' and proof_aggregate_type = 'CUSTOMER_PROFILE')
        or (owner_type = 'TASK_BOARD_ENTRY' and proof_aggregate_type = 'TASK_BOARD_ENTRY_OWNER_PROOF')
        or (owner_type = 'DRIVER_SHIFT' and proof_aggregate_type = 'DRIVER_SHIFT_OWNER_PROOF')
    );
alter table media_owner_binding
    add constraint media_owner_binding_owner_identity_check check (
        (owner_type in (
            'INVENTORY_FINDING','CABIN','MAINTENANCE_ESTIMATE','MAINTENANCE_REPAIR',
            'MAINTENANCE_ACCEPTANCE','MAINTENANCE_CATALOG_NODE','LOGISTICS_CUSTOMER_PROFILE',
            'TASK_BOARD_ENTRY','DRIVER_SHIFT'
        ) and owner_id = proof_aggregate_id::text)
        or (owner_type in (
            'LOGISTICS_RETURN','LOGISTICS_SHIPMENT','LOGISTICS_TRANSFER'
        ) and owner_id ~ '^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}:[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$')
    );

alter table media_asset
    drop constraint media_asset_task_board_client_reference_check;
alter table media_asset
    add constraint media_asset_worker_evidence_client_reference_check check (
        (owner_type in ('TASK_BOARD_ENTRY','DRIVER_SHIFT') and client_reference_id is not null)
        or (owner_type not in ('TASK_BOARD_ENTRY','DRIVER_SHIFT') and client_reference_id is null)
    ) not valid;

create unique index media_asset_driver_shift_reference_unique_idx
    on media_asset (owner_id, client_reference_id)
    where owner_type = 'DRIVER_SHIFT';

create table media_driver_shift_owner_proof_inbox (
    consumer_name varchar(128) not null,
    event_id uuid not null,
    topic varchar(255) not null,
    event_type varchar(128) not null,
    aggregate_type varchar(64) not null,
    aggregate_id uuid not null,
    aggregate_version bigint not null check (aggregate_version >= 0),
    record_key uuid not null,
    warehouse_id uuid not null,
    active boolean not null,
    body_sha256 char(64) not null check (body_sha256 ~ '^[0-9a-f]{64}$'),
    wire_body bytea not null,
    outcome varchar(16) not null check (outcome in ('APPLIED','QUARANTINED')),
    failure_code varchar(64),
    received_at timestamptz not null default clock_timestamp(),
    processed_at timestamptz,
    primary key (consumer_name,event_id),
    unique (consumer_name,aggregate_type,aggregate_id,aggregate_version),
    check (consumer_name = 'media-service-driver-shift-owner-proof-v1'),
    check (topic = 'rwms.task-board.driver-shift-owner-proof.v1'),
    check (event_type = 'task-board.driver-shift-owner-proof.changed.v1'),
    check (aggregate_type = 'DRIVER_SHIFT_OWNER_PROOF'),
    check (aggregate_id = record_key),
    check (body_sha256 = encode(sha256(wire_body), 'hex')),
    check (
        (outcome = 'APPLIED' and processed_at is not null and failure_code is null)
        or (outcome = 'QUARANTINED' and processed_at is null and failure_code is not null)
    )
);

create index media_driver_shift_owner_proof_inbox_quarantine_idx
    on media_driver_shift_owner_proof_inbox (aggregate_id,aggregate_version)
    where outcome = 'QUARANTINED';

create table media_driver_shift_owner_proof_conflict (
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
    check (consumer_name = 'media-service-driver-shift-owner-proof-v1'),
    check (incoming_body_sha256 = encode(sha256(incoming_wire_body), 'hex')),
    check ((prior_event_id is null) = (prior_body_sha256 is null))
);

create table media_driver_shift_owner_proof (
    shift_id uuid primary key,
    warehouse_id uuid not null,
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

create table media_driver_shift_allowed_worker (
    shift_id uuid not null references media_driver_shift_owner_proof (shift_id),
    worker_id uuid not null,
    primary key (shift_id,worker_id)
);

create index media_driver_shift_allowed_worker_access_idx
    on media_driver_shift_allowed_worker (worker_id,shift_id);

create table media_driver_shift_reader_worker (
    shift_id uuid not null references media_driver_shift_owner_proof (shift_id),
    worker_id uuid not null,
    primary key (shift_id,worker_id)
);

create index media_driver_shift_reader_worker_access_idx
    on media_driver_shift_reader_worker (worker_id,shift_id);

alter table media_dead_letter
    drop constraint media_dead_letter_task_board_owner_failure_check;
alter table media_dead_letter
    add constraint media_dead_letter_task_board_owner_failure_check check (
        (consumer_name <> 'media-service-task-board-entry-owner-proof-v1'
            or failure_code in (
                'INVALID_TASK_BOARD_ENTRY_OWNER_PROOF',
                'TASK_BOARD_OWNER_EVENT_ID_CONFLICT'
            ))
        and (consumer_name <> 'media-service-driver-shift-owner-proof-v1'
            or failure_code in (
                'INVALID_DRIVER_SHIFT_OWNER_PROOF',
                'DRIVER_SHIFT_OWNER_EVENT_ID_CONFLICT'
            ))
    );

alter table media_asset
    validate constraint media_asset_worker_evidence_client_reference_check;
