-- Service-owned media scopes and durable private owner-proof commands.
-- Existing INVENTORY_FINDING and CABIN bindings remain unchanged. This
-- migration adds no object deletion or retention behaviour: DELETED assets
-- keep every immutable MinIO version and all media_variant provenance rows.

alter table media_owner_binding
    drop constraint media_owner_binding_owner_proof_scope_check;
alter table media_owner_binding
    drop constraint media_owner_binding_check;
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
    );
alter table media_owner_binding
    add constraint media_owner_binding_owner_identity_check check (
        (owner_type in (
            'INVENTORY_FINDING','CABIN','MAINTENANCE_ESTIMATE','MAINTENANCE_REPAIR',
            'MAINTENANCE_ACCEPTANCE','MAINTENANCE_CATALOG_NODE'
        ) and owner_id = proof_aggregate_id::text)
        or (owner_type in (
            'LOGISTICS_RETURN','LOGISTICS_SHIPMENT','LOGISTICS_TRANSFER'
        ) and owner_id ~ '^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}:[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$')
    );

-- A user may discard an unfinished upload as well as a processed asset. The
-- prior V2 rule required source provenance for every DELETED row, which made
-- soft-deleting an UPLOADING row impossible. All non-deleted runtime states
-- retain their original invariant.
alter table media_asset
    drop constraint media_asset_runtime_source_state;
alter table media_asset
    add constraint media_asset_runtime_source_state check (
        (processing_status = 'UPLOADING' and source_version_id is null)
        or (processing_status in ('PROCESSING','READY','FAILED')
            and source_version_id is not null and source_checksum_sha256 is not null)
        or processing_status = 'DELETED'
    ) not valid;

create table media_service_owner_proof_checkpoint (
    owner_type varchar(64) not null,
    owner_id varchar(128) not null,
    source_service varchar(128) not null,
    consumer_name varchar(128) not null,
    aggregate_type varchar(64) not null,
    aggregate_id uuid not null,
    aggregate_version bigint not null check (aggregate_version >= 0),
    owner_revision bigint not null check (owner_revision >= 0),
    warehouse_id uuid not null,
    document_id uuid,
    line_id uuid,
    active boolean not null,
    last_proof_event_id uuid not null,
    last_request_sha256 char(64) not null
        check (last_request_sha256 ~ '^[0-9a-f]{64}$'),
    quarantined boolean not null default false,
    quarantine_reason varchar(64),
    updated_at timestamptz not null default clock_timestamp(),
    primary key (owner_type, owner_id),
    unique (consumer_name, aggregate_type, aggregate_id),
    check (
        (source_service = 'maintenance-service'
            and consumer_name = 'media-service-maintenance-owner-proof-v1'
            and owner_type in (
                'MAINTENANCE_ESTIMATE','MAINTENANCE_REPAIR',
                'MAINTENANCE_ACCEPTANCE','MAINTENANCE_CATALOG_NODE'
            )
            and document_id is null and line_id is null)
        or (source_service = 'logistics-service'
            and consumer_name = 'media-service-logistics-owner-proof-v1'
            and owner_type in (
                'LOGISTICS_RETURN','LOGISTICS_SHIPMENT','LOGISTICS_TRANSFER'
            )
            and document_id is not null and line_id is not null
            and owner_id = document_id::text || ':' || line_id::text)
    ),
    check (
        (owner_type = 'MAINTENANCE_ESTIMATE' and aggregate_type = 'ESTIMATE')
        or (owner_type = 'MAINTENANCE_REPAIR' and aggregate_type = 'REPAIR')
        or (owner_type = 'MAINTENANCE_ACCEPTANCE' and aggregate_type = 'ACCEPTANCE')
        or (owner_type = 'MAINTENANCE_CATALOG_NODE' and aggregate_type = 'CATALOG_NODE')
        or (owner_type = 'LOGISTICS_RETURN' and aggregate_type = 'RETURN')
        or (owner_type = 'LOGISTICS_SHIPMENT' and aggregate_type = 'SHIPMENT')
        or (owner_type = 'LOGISTICS_TRANSFER' and aggregate_type = 'TRANSFER')
    ),
    check (
        (quarantined and quarantine_reason is not null)
        or (not quarantined and quarantine_reason is null)
    )
);

create table media_service_owner_proof_receipt (
    proof_event_id uuid primary key,
    source_service varchar(128) not null,
    consumer_name varchar(128) not null,
    owner_type varchar(64) not null,
    owner_id varchar(128) not null,
    document_id uuid,
    line_id uuid,
    warehouse_id uuid not null,
    owner_revision bigint not null check (owner_revision >= 0),
    aggregate_type varchar(64) not null,
    aggregate_id uuid not null,
    aggregate_version bigint not null check (aggregate_version >= 0),
    active boolean not null,
    request_sha256 char(64) not null check (request_sha256 ~ '^[0-9a-f]{64}$'),
    outcome varchar(16) not null check (outcome in ('APPLIED','QUARANTINED')),
    failure_code varchar(64),
    received_at timestamptz not null default clock_timestamp(),
    check (
        (outcome = 'APPLIED' and failure_code is null)
        or (outcome = 'QUARANTINED' and failure_code is not null)
    ),
    check (
        (source_service = 'maintenance-service'
            and consumer_name = 'media-service-maintenance-owner-proof-v1'
            and owner_type in (
                'MAINTENANCE_ESTIMATE','MAINTENANCE_REPAIR',
                'MAINTENANCE_ACCEPTANCE','MAINTENANCE_CATALOG_NODE'
            )
            and document_id is null and line_id is null)
        or (source_service = 'logistics-service'
            and consumer_name = 'media-service-logistics-owner-proof-v1'
            and owner_type in (
                'LOGISTICS_RETURN','LOGISTICS_SHIPMENT','LOGISTICS_TRANSFER'
            )
            and document_id is not null and line_id is not null
            and owner_id = document_id::text || ':' || line_id::text)
    )
);

create index media_service_owner_proof_receipt_version_idx
    on media_service_owner_proof_receipt (
        owner_type, owner_id, aggregate_version, received_at
    );

-- Conflicting reuses cannot replace the authoritative receipt. Persist only
-- identity and hashes as conflict evidence, then quarantine both known owner
-- aggregates in the same transaction.
create table media_service_owner_proof_conflict (
    conflict_id uuid primary key,
    incoming_proof_event_id uuid not null,
    incoming_source_service varchar(128) not null,
    incoming_owner_type varchar(64) not null,
    incoming_owner_id varchar(128) not null,
    incoming_aggregate_version bigint not null check (incoming_aggregate_version >= 0),
    incoming_owner_revision bigint not null check (incoming_owner_revision >= 0),
    incoming_request_sha256 char(64) not null
        check (incoming_request_sha256 ~ '^[0-9a-f]{64}$'),
    prior_proof_event_id uuid,
    prior_owner_type varchar(64),
    prior_owner_id varchar(128),
    prior_aggregate_version bigint,
    prior_request_sha256 char(64),
    failure_code varchar(64) not null,
    detected_at timestamptz not null default clock_timestamp(),
    unique (incoming_proof_event_id, incoming_request_sha256),
    check (prior_request_sha256 is null or prior_request_sha256 ~ '^[0-9a-f]{64}$'),
    check ((prior_proof_event_id is null) = (prior_request_sha256 is null))
);
