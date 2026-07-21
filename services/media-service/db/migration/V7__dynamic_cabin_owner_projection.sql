-- Dynamic CABIN owner projection from the authoritative asset-service
-- RENTAL_ITEM stream. V4 remains immutable migration evidence for the 195
-- old-panel cabins; the live consumer replaces those bindings only after it
-- has observed the canonical version-zero create fact.

create table media_cabin_owner_inbox (
    consumer_name varchar(128) not null,
    event_id uuid not null,
    topic varchar(255) not null,
    event_type varchar(128) not null,
    aggregate_type varchar(64) not null,
    aggregate_id uuid not null,
    aggregate_version bigint not null check (aggregate_version >= 0),
    record_key uuid not null,
    payload_owner_id uuid not null,
    warehouse_id uuid,
    rental_status varchar(64),
    owner_revision bigint check (owner_revision is null or owner_revision >= 0),
    active boolean,
    body_sha256 char(64) not null check (body_sha256 ~ '^[0-9a-f]{64}$'),
    wire_body bytea not null,
    outcome varchar(16) not null check (outcome in ('APPLIED','DLT','QUARANTINED')),
    failure_code varchar(64),
    received_at timestamptz not null default clock_timestamp(),
    processed_at timestamptz,
    primary key (consumer_name,event_id),
    check (consumer_name = 'media-service-cabin-owner-v1'),
    check (topic = 'rwms.asset.rental-item.v1'),
    check (aggregate_type = 'RENTAL_ITEM'),
    check (aggregate_id = record_key),
    check (body_sha256 = encode(sha256(wire_body), 'hex')),
    check (
        (event_type in (
            'asset.rental-item.created.v1',
            'asset.rental-item.passport-changed.v1',
            'asset.rental-item.status-changed.v1',
            'asset.rental-item.warehouse-changed.v1',
            'asset.rental-item.logistics-effect-applied.v1'
        ) and warehouse_id is not null and rental_status is not null
          and owner_revision = aggregate_version and active is not null)
        or (event_type in (
            'asset.rental-item.general-comment-changed.v1',
            'asset.rental-item.manual-note-added.v1'
        ) and warehouse_id is null and rental_status is null
          and owner_revision is null and active is null)
    ),
    check (
        (outcome = 'APPLIED' and processed_at is not null and failure_code is null)
        or (outcome in ('DLT','QUARANTINED') and processed_at is null
            and failure_code is not null)
    )
);

create index media_cabin_owner_inbox_quarantine_idx
    on media_cabin_owner_inbox (aggregate_id,aggregate_version)
    where outcome = 'QUARANTINED';

-- Changed bytes under one event ID cannot replace the first receipt. The
-- incoming bytes are retained only for reviewed reconciliation; both owner
-- aggregates remain fail-closed through media_quarantined_aggregate.
create table media_cabin_owner_event_conflict (
    consumer_name varchar(128) not null,
    event_id uuid not null,
    prior_aggregate_id uuid not null,
    prior_aggregate_version bigint not null check (prior_aggregate_version >= 0),
    prior_record_key uuid not null,
    prior_body_sha256 char(64) not null check (prior_body_sha256 ~ '^[0-9a-f]{64}$'),
    incoming_aggregate_id uuid not null,
    incoming_aggregate_version bigint not null check (incoming_aggregate_version >= 0),
    incoming_record_key uuid not null,
    incoming_body_sha256 char(64) not null check (incoming_body_sha256 ~ '^[0-9a-f]{64}$'),
    incoming_wire_body bytea not null,
    detected_at timestamptz not null default clock_timestamp(),
    prior_reconciled_at timestamptz,
    prior_resolution_reason varchar(500),
    prior_resolved_by_subject_id uuid,
    primary key (consumer_name,event_id,incoming_body_sha256),
    check (consumer_name = 'media-service-cabin-owner-v1'),
    check (incoming_body_sha256 = encode(sha256(incoming_wire_body), 'hex')),
    check (
        (prior_reconciled_at is null and prior_resolution_reason is null
            and prior_resolved_by_subject_id is null)
        or (prior_reconciled_at is not null and prior_resolution_reason is not null
            and prior_resolved_by_subject_id is not null)
    )
);

-- WRITTEN_OFF is the only terminal RentalItem status and is projected as an
-- inactive binding; later reactivation is rejected by the consumer.
-- No startup seed, browser state or TTL is introduced. Public authorization
-- remains the existing media_owner_binding + consumer checkpoint + open
-- quarantine join.
