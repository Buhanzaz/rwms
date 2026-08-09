alter table assistant_event_inbox
  add column canonical_envelope_sha256 char(64),
  add column source_topic varchar(249),
  add column source_partition integer,
  add column source_offset bigint,
  add column processing_state varchar(32) not null default 'LEGACY_PROCESSED',
  add column attempt_count integer not null default 0,
  add column next_attempt_at timestamptz,
  add column processed_at timestamptz,
  add column dead_lettered_at timestamptz;

update assistant_event_inbox
set processed_at = received_at
where processing_state = 'LEGACY_PROCESSED';

alter table assistant_event_inbox
  alter column processing_state drop default,
  alter column attempt_count drop default,
  add constraint chk_assistant_event_inbox_hash
    check (
      canonical_envelope_sha256 is null
      or canonical_envelope_sha256 ~ '^[0-9a-f]{64}$'
    ),
  add constraint chk_assistant_event_inbox_source
    check (
      (source_topic is null and source_partition is null and source_offset is null)
      or (
        source_topic is not null
        and source_partition is not null
        and source_offset is not null
        and source_partition >= 0
        and source_offset >= 0
      )
    ),
  add constraint chk_assistant_event_inbox_state
    check (processing_state in ('LEGACY_PROCESSED', 'STAGED', 'PROCESSED', 'DLT')),
  add constraint chk_assistant_event_inbox_attempt_count
    check (attempt_count between 0 and 4),
  add constraint chk_assistant_event_inbox_lifecycle
    check (
      (
        processing_state = 'LEGACY_PROCESSED'
        and canonical_envelope_sha256 is null
        and source_topic is null
        and attempt_count = 0
        and processed_at is not null
        and dead_lettered_at is null
      )
      or (
        processing_state = 'STAGED'
        and canonical_envelope_sha256 is not null
        and source_topic is not null
        and processed_at is null
        and dead_lettered_at is null
      )
      or (
        processing_state = 'PROCESSED'
        and canonical_envelope_sha256 is not null
        and source_topic is not null
        and attempt_count between 1 and 4
        and processed_at is not null
        and dead_lettered_at is null
      )
      or (
        processing_state = 'DLT'
        and canonical_envelope_sha256 is not null
        and source_topic is not null
        and attempt_count = 4
        and processed_at is null
        and dead_lettered_at is not null
      )
    );

create unique index uq_assistant_event_inbox_source_receipt
  on assistant_event_inbox (source_topic, source_partition, source_offset)
  where source_topic is not null;

create index idx_assistant_event_inbox_processing
  on assistant_event_inbox (processing_state, next_attempt_at, received_at);

create table assistant_event_dead_letter (
  dlt_id uuid primary key,
  source_topic varchar(249) not null,
  source_partition integer not null,
  source_offset bigint not null,
  source_event_id uuid references assistant_event_inbox(event_id),
  message_sha256 char(64) not null,
  failure_code varchar(64) not null,
  replay_state varchar(32) not null,
  review_version bigint not null,
  reviewed_by_subject_id uuid,
  reviewed_at timestamptz,
  replayed_at timestamptz,
  created_at timestamptz not null,
  constraint uq_assistant_event_dead_letter_source
    unique (source_topic, source_partition, source_offset),
  constraint chk_assistant_event_dead_letter_source
    check (source_partition >= 0 and source_offset >= 0),
  constraint chk_assistant_event_dead_letter_hash
    check (message_sha256 ~ '^[0-9a-f]{64}$'),
  constraint chk_assistant_event_dead_letter_failure
    check (
      failure_code in (
        'SOURCE_RECORD_INVALID',
        'SOURCE_SCHEMA_REJECTED',
        'SOURCE_RECORD_KEY_MISMATCH',
        'EVENT_ID_CONFLICT',
        'PROCESSING_FAILED'
      )
    ),
  constraint chk_assistant_event_dead_letter_replay_state
    check (
      replay_state in (
        'NOT_REPLAYABLE',
        'AWAITING_REVIEW',
        'APPROVED',
        'REJECTED',
        'REPLAYED'
      )
    ),
  constraint chk_assistant_event_dead_letter_review_version
    check (review_version >= 0),
  constraint chk_assistant_event_dead_letter_replay_source
    check (
      (replay_state = 'NOT_REPLAYABLE' and source_event_id is null)
      or (replay_state <> 'NOT_REPLAYABLE' and source_event_id is not null)
    ),
  constraint chk_assistant_event_dead_letter_review
    check (
      (
        replay_state in ('NOT_REPLAYABLE', 'AWAITING_REVIEW')
        and review_version = 0
        and reviewed_by_subject_id is null
        and reviewed_at is null
        and replayed_at is null
      )
      or (
        replay_state in ('APPROVED', 'REJECTED')
        and review_version > 0
        and reviewed_by_subject_id is not null
        and reviewed_at is not null
        and replayed_at is null
      )
      or (
        replay_state = 'REPLAYED'
        and review_version > 0
        and reviewed_by_subject_id is not null
        and reviewed_at is not null
        and replayed_at is not null
      )
    )
);

create index idx_assistant_event_dead_letter_review
  on assistant_event_dead_letter (replay_state, created_at, dlt_id);
