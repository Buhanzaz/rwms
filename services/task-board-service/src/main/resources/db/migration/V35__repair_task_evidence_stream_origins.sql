-- Worker evidence reservations are internal task-board projection state. Before V35 the first
-- externally visible READY/REVIEW_REQUIRED fact incorrectly used that projection's version 1,
-- so the ordered outbox quarantined it because stream version 0 did not exist.
--
-- Preserve every immutable fact and repair only entirely unpublished TASK_EVIDENCE streams.
-- Deterministic origin facts make the predecessor explicit; the original quarantined fact is
-- then retried in aggregate order. Already published streams are deliberately left untouched.
with bad_stream as (
    select
        event.aggregate_id,
        min(event.aggregate_version) as first_version
    from domain_event event
    join outbox_event outbox on outbox.event_id = event.event_id
    where event.aggregate_type = 'TASK_EVIDENCE'
    group by event.aggregate_id
    having min(event.aggregate_version) > 0
       and not bool_or(outbox.status = 'PUBLISHED')
),
first_event as (
    select
        event.aggregate_id,
        bad_stream.first_version,
        event.event_type,
        event.recorded_at,
        event.actor_ref,
        event.payload,
        event.payload_sha256,
        outbox.topic
    from bad_stream
    join domain_event event
      on event.aggregate_type = 'TASK_EVIDENCE'
     and event.aggregate_id = bad_stream.aggregate_id
     and event.aggregate_version = bad_stream.first_version
    join outbox_event outbox on outbox.event_id = event.event_id
),
missing_version as (
    select
        first_event.*,
        missing.aggregate_version
    from first_event
    cross join lateral generate_series(
        0::bigint,
        first_event.first_version - 1
    ) as missing(aggregate_version)
),
prepared as (
    select
        (
            substr(event_hash, 1, 8) || '-' ||
            substr(event_hash, 9, 4) || '-' ||
            substr(event_hash, 13, 4) || '-' ||
            substr(event_hash, 17, 4) || '-' ||
            substr(event_hash, 21, 12)
        )::uuid as event_id,
        aggregate_id,
        aggregate_version,
        event_type,
        recorded_at
            - ((first_version - aggregate_version) * interval '1 microsecond')
            as event_time,
        actor_ref,
        payload,
        payload_sha256,
        topic
    from (
        select
            missing_version.*,
            md5(
                'rwms:task-evidence-origin:v1:' ||
                aggregate_id || ':' || aggregate_version::text
            ) as event_hash
        from missing_version
    ) value
),
inserted_domain as (
    insert into domain_event(
        event_id,
        aggregate_type,
        aggregate_id,
        aggregate_version,
        event_type,
        event_version,
        occurred_at,
        recorded_at,
        correlation_id,
        causation_id,
        actor_ref,
        payload,
        payload_sha256,
        baseline
    )
    select
        event_id,
        'TASK_EVIDENCE',
        aggregate_id,
        aggregate_version,
        event_type,
        1,
        event_time,
        event_time,
        event_id,
        null,
        actor_ref,
        payload,
        payload_sha256,
        false
    from prepared
    returning event_id
),
envelope as (
    select
        prepared.*,
        jsonb_build_object(
            'envelopeVersion', 2,
            'eventId', prepared.event_id,
            'eventType', prepared.event_type,
            'eventVersion', 1,
            'occurredAt', prepared.event_time,
            'recordedAt', prepared.event_time,
            'producer', 'task-board-service',
            'aggregateType', 'TASK_EVIDENCE',
            'aggregateId', prepared.aggregate_id,
            'aggregateVersion', prepared.aggregate_version,
            'correlation', jsonb_build_object(
                'correlationId', prepared.event_id,
                'causationId', null
            ),
            'actorRef', prepared.actor_ref,
            'payload', prepared.payload
        ) as body
    from prepared
    join inserted_domain using (event_id)
)
insert into outbox_event(
    event_id,
    aggregate_type,
    aggregate_id,
    aggregate_version,
    event_type,
    topic,
    envelope_body,
    envelope_sha256,
    status,
    attempt_count,
    next_attempt_at,
    created_at
)
select
    event_id,
    'TASK_EVIDENCE',
    aggregate_id,
    aggregate_version,
    event_type,
    topic,
    body,
    encode(sha256(convert_to(body::text, 'UTF8')), 'hex'),
    'PENDING',
    0,
    clock_timestamp(),
    event_time
from envelope;

update outbox_event original
set status = 'PENDING',
    attempt_count = 0,
    next_attempt_at = clock_timestamp(),
    lease_owner = null,
    lease_token = null,
    lease_until = null,
    published_at = null,
    dlt_at = null,
    last_error_code = 'STREAM_ORIGIN_REPAIRED'
where original.aggregate_type = 'TASK_EVIDENCE'
  and original.status = 'QUARANTINED'
  and original.last_error_code = 'AGGREGATE_VERSION_GAP'
  and exists (
      select 1
      from domain_event origin
      where origin.aggregate_type = original.aggregate_type
        and origin.aggregate_id = original.aggregate_id
        and origin.aggregate_version = 0
        and origin.event_id <> original.event_id
  );
