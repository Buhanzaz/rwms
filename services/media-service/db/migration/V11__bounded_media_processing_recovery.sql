-- Bounded recovery metadata for the media processing consumer.
--
-- The migration is additive. It does not retain Kafka payloads, object keys,
-- user text, or dependency errors. Existing FAILED jobs without exact DLT
-- evidence are represented by the closed LEGACY_TERMINAL reason and remain
-- terminal until a separately authorized workflow is implemented.

alter table media_processing_job
    add column attempt_in_cycle integer not null default 0;

update media_processing_job
set attempt_in_cycle = case
    when job_status = 'FAILED' then least(greatest(attempt_count, 1), 4)
    when job_status in ('PENDING', 'RUNNING') then least(attempt_count, 3)
    else 0
end;

alter table media_processing_job
    add constraint media_processing_job_attempt_in_cycle_bounded
        check (attempt_in_cycle between 0 and 4);

create index media_processing_job_active_age_idx
    on media_processing_job (created_at, processing_job_id)
    where job_status in ('PENDING', 'RUNNING');

create table media_processing_terminal (
    processing_job_id uuid not null references media_processing_job (processing_job_id),
    terminal_version bigint not null check (terminal_version > 0),
    source_event_id uuid,
    source_body_sha256 char(64),
    failure_code varchar(64) not null check (
        failure_code in (
            'VALIDATION_FAILED',
            'PROCESSING_DEPENDENCY_UNAVAILABLE',
            'PROCESSING_ATTEMPT_EXHAUSTED',
            'LEGACY_TERMINAL'
        )
    ),
    attempt_count integer not null check (attempt_count between 1 and 4),
    terminal_at timestamptz not null default clock_timestamp(),
    primary key (processing_job_id, terminal_version),
    check (
        (failure_code = 'LEGACY_TERMINAL'
            and source_event_id is null and source_body_sha256 is null)
        or
        (failure_code <> 'LEGACY_TERMINAL'
            and source_event_id is not null
            and source_body_sha256 ~ '^[0-9a-f]{64}$')
    )
);

create table media_processing_retry_review (
    review_id uuid not null,
    processing_job_id uuid not null,
    terminal_version bigint not null,
    reviewed_by_subject_id uuid not null,
    decision varchar(24) not null check (
        decision in ('RETRY_APPROVED', 'RETRY_REJECTED')
    ),
    reason_code varchar(64) not null check (
        reason_code in (
            'DEPENDENCY_RECOVERED',
            'SOURCE_REPAIRED',
            'ATTEMPT_BUDGET_RESET',
            'VALIDATION_CONFIRMED',
            'POLICY_REJECTED'
        )
    ),
    reviewed_at timestamptz not null default clock_timestamp(),
    primary key (review_id),
    unique (processing_job_id, terminal_version),
    foreign key (processing_job_id, terminal_version)
        references media_processing_terminal (processing_job_id, terminal_version),
    check (
        (decision = 'RETRY_APPROVED'
            and reason_code in (
                'DEPENDENCY_RECOVERED',
                'SOURCE_REPAIRED',
                'ATTEMPT_BUDGET_RESET'
            ))
        or
        (decision = 'RETRY_REJECTED'
            and reason_code in ('VALIDATION_CONFIRMED', 'POLICY_REJECTED'))
    )
);

create index media_processing_terminal_age_idx
    on media_processing_terminal (terminal_at, processing_job_id, terminal_version);

insert into media_processing_terminal (
    processing_job_id,terminal_version,source_event_id,source_body_sha256,
    failure_code,attempt_count,terminal_at)
select job.processing_job_id,1,
       case when dead.eligible_count = 1 then dead.event_id end,
       case when dead.eligible_count = 1 then dead.body_sha256 end,
       case
           when dead.eligible_count = 1
               then dead.failure_code
           else 'LEGACY_TERMINAL'
       end,
       least(greatest(job.attempt_in_cycle, 1), 4),
       coalesce(job.completed_at, job.created_at)
from media_processing_job job
left join lateral (
    select count(*) as eligible_count,
           min(event_id::text)::uuid as event_id,
           min(body_sha256) as body_sha256,
           min(failure_code) as failure_code
    from media_dead_letter
    where consumer_name = 'media-processing-v1'
      and aggregate_id = job.processing_job_id
      and failure_code in ('VALIDATION_FAILED','PROCESSING_DEPENDENCY_UNAVAILABLE')
) dead on true
where job.job_status = 'FAILED';

create or replace function media_forbid_processing_recovery_mutation()
returns trigger language plpgsql as $$
begin
    raise exception 'media processing recovery evidence is append-only';
end;
$$;

create trigger media_processing_terminal_append_only
before update or delete on media_processing_terminal
for each row execute function media_forbid_processing_recovery_mutation();

create trigger media_processing_retry_review_append_only
before update or delete on media_processing_retry_review
for each row execute function media_forbid_processing_recovery_mutation();
