-- Stage 7 inventory finding owner-proof consumer. V1/V2 remain immutable.

alter table media_owner_binding
    drop constraint media_owner_binding_proof_aggregate_version_check;
alter table media_owner_binding
    add constraint media_owner_binding_proof_aggregate_version_check
        check (proof_aggregate_version >= 0);
alter table media_owner_binding
    drop constraint media_owner_binding_proof_aggregate_type_check;

-- V2 bindings were created through an internal fixture boundary and do not
-- prove the canonical FINDING stream bootstrap introduced by Task 1B. Keep
-- their identity as migration evidence, but fail closed until an operator
-- reconciles the authoritative inventory event-store stream.
insert into media_quarantined_aggregate (
    consumer_name,aggregate_type,aggregate_id,expected_version,observed_version,
    reason_code,first_event_id)
select 'media-service-inventory-owner-v1','FINDING',proof_aggregate_id,
       proof_aggregate_version,proof_aggregate_version,
       'UNVERIFIED_PRE_TASK1B_BINDING',proof_event_id
from media_owner_binding
on conflict (consumer_name,aggregate_type,aggregate_id) do update set
    expected_version=excluded.expected_version,
    observed_version=excluded.observed_version,
    reason_code=excluded.reason_code,
    first_event_id=excluded.first_event_id,
    quarantined_at=clock_timestamp(),reconciled_at=null;

update media_owner_binding
set active=false,
    proof_consumer_name='media-service-inventory-owner-v1',
    proof_aggregate_type='FINDING',
    updated_at=clock_timestamp();

alter table media_owner_binding
    add constraint media_owner_binding_proof_aggregate_type_check
        check (proof_aggregate_type = 'FINDING');

alter table media_quarantined_aggregate
    drop constraint media_quarantined_aggregate_expected_version_check;
alter table media_quarantined_aggregate
    drop constraint media_quarantined_aggregate_observed_version_check;
alter table media_quarantined_aggregate
    drop constraint media_quarantined_aggregate_check;
alter table media_quarantined_aggregate
    add constraint media_quarantined_aggregate_expected_version_check
        check (expected_version >= 0);
alter table media_quarantined_aggregate
    add constraint media_quarantined_aggregate_observed_version_check
        check (observed_version >= 0);
alter table media_quarantined_aggregate
    add column resolution_reason varchar(500),
    add column resolved_by_subject_id uuid,
    add constraint media_quarantined_aggregate_resolution_check check (
        (reconciled_at is null and resolution_reason is null and resolved_by_subject_id is null)
        or (reconciled_at is not null and resolution_reason is not null
            and resolved_by_subject_id is not null)
    ),
    add constraint media_quarantined_aggregate_direction_check check (
        (reason_code = 'VERSION_GAP' and observed_version > expected_version)
        or (reason_code = 'AGGREGATE_VERSION_REGRESSION' and observed_version < expected_version)
        or reason_code = 'EVENT_ID_CONFLICT'
        or (reason_code not in ('VERSION_GAP','AGGREGATE_VERSION_REGRESSION','EVENT_ID_CONFLICT')
            and observed_version = expected_version)
    );

alter table media_dead_letter
    drop constraint media_dead_letter_aggregate_version_check;
alter table media_dead_letter
    add constraint media_dead_letter_aggregate_version_check
        check (aggregate_version is null or aggregate_version >= 0);

create table media_inventory_finding_inbox (
    consumer_name varchar(128) not null,
    event_id uuid not null,
    topic varchar(255) not null,
    event_type varchar(128) not null,
    aggregate_type varchar(64) not null,
    aggregate_id uuid not null,
    aggregate_version bigint not null check (aggregate_version >= 0),
    record_key uuid not null,
    warehouse_id uuid not null,
    body_sha256 char(64) not null check (body_sha256 ~ '^[0-9a-f]{64}$'),
    wire_body bytea not null,
    owner_revision bigint check (owner_revision is null or owner_revision >= 0),
    outcome varchar(16) not null check (outcome in ('APPLIED','DLT','QUARANTINED')),
    failure_code varchar(64),
    received_at timestamptz not null default clock_timestamp(),
    processed_at timestamptz,
    primary key (consumer_name,event_id),
    check (consumer_name = 'media-service-inventory-owner-v1'),
    check (topic = 'rwms.inventory.session.v1'),
    check (aggregate_type = 'FINDING'),
    check (aggregate_id = record_key),
    check (body_sha256 = encode(sha256(wire_body), 'hex')),
    check (
        (event_type = 'inventory.finding.owner-proof.v1' and owner_revision is not null)
        or (event_type in ('inventory.finding.added.v1','inventory.finding.inspection-saved.v1')
            and owner_revision is null)
    ),
    check (
        (outcome = 'APPLIED' and processed_at is not null and failure_code is null)
        or (outcome in ('DLT','QUARANTINED') and processed_at is null
            and failure_code is not null)
    )
);

create index media_inventory_finding_inbox_quarantine_idx
    on media_inventory_finding_inbox (aggregate_id,aggregate_version)
    where outcome = 'QUARANTINED';

create table media_inventory_finding_stream (
    consumer_name varchar(128) not null,
    aggregate_id uuid not null,
    warehouse_id uuid not null,
    bootstrap_event_id uuid not null,
    bootstrapped_at timestamptz not null,
    primary key (consumer_name,aggregate_id),
    unique (consumer_name,bootstrap_event_id),
    check (consumer_name = 'media-service-inventory-owner-v1')
);

create table media_inventory_finding_event_conflict (
    consumer_name varchar(128) not null,
    event_id uuid not null,
    prior_aggregate_id uuid not null,
    prior_aggregate_version bigint not null check (prior_aggregate_version >= 0),
    prior_record_key uuid not null,
    prior_body_sha256 char(64) not null check (prior_body_sha256 ~ '^[0-9a-f]{64}$'),
    incoming_aggregate_id uuid not null,
    incoming_aggregate_version bigint not null check (incoming_aggregate_version >= 0),
    incoming_record_key uuid not null,
    incoming_warehouse_id uuid not null,
    incoming_body_sha256 char(64) not null check (incoming_body_sha256 ~ '^[0-9a-f]{64}$'),
    incoming_wire_body bytea not null,
    detected_at timestamptz not null default clock_timestamp(),
    prior_reconciled_at timestamptz,
    prior_resolution_reason varchar(500),
    prior_resolved_by_subject_id uuid,
    primary key (consumer_name,event_id,incoming_body_sha256),
    check (consumer_name = 'media-service-inventory-owner-v1'),
    check (incoming_body_sha256 = encode(sha256(incoming_wire_body), 'hex')),
    check (
        (prior_reconciled_at is null and prior_resolution_reason is null
            and prior_resolved_by_subject_id is null)
        or (prior_reconciled_at is not null and prior_resolution_reason is not null
            and prior_resolved_by_subject_id is not null)
    )
);

-- Durable owner retries must retain enough immutable identity to fail closed
-- on event-ID/body reuse before the event reaches the inbox. Processing-worker
-- retries share this table and intentionally leave these columns null.
alter table media_retry_schedule
    add column aggregate_type varchar(64),
    add column aggregate_id uuid,
    add column aggregate_version bigint,
    add column record_key uuid,
    add column warehouse_id uuid,
    add constraint media_retry_schedule_inventory_owner_identity_check check (
        consumer_name <> 'media-service-inventory-owner-v1'
        or (
            aggregate_type = 'FINDING'
            and aggregate_id is not null
            and aggregate_version >= 0
            and record_key = aggregate_id
            and warehouse_id is not null
        )
    );

alter table media_dead_letter
    add constraint media_dead_letter_inventory_owner_failure_check check (
        consumer_name <> 'media-service-inventory-owner-v1'
        or failure_code in (
            'INVALID_INVENTORY_OWNER_FACT',
            'OWNER_EVENT_ID_CONFLICT',
            'OWNER_PROOF_PROCESSING_FAILED'
        )
    );

alter table media_transport_outbox drop constraint media_transport_outbox_check3;
alter table media_transport_outbox
    add constraint media_transport_outbox_contract_check check (
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
        or (event_type='media.inventory-owner.dlt.v1'
            and aggregate_type='INVENTORY_OWNER_DLT' and aggregate_version=1
            and topic='rwms.inventory.session.v1.media-service-inventory-owner-v1.dlt'
            and depends_on_event_id is null)
    );
