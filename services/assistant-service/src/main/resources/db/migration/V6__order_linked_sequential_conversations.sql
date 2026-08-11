alter table assistant_conversation
  add column rental_order_id uuid;

create index idx_assistant_conversation_owner_order_active
  on assistant_conversation (owner_subject_id, rental_order_id, updated_at desc)
  where rental_order_id is not null and not archived;

create unique index uq_assistant_conversation_active_order
  on assistant_conversation (rental_order_id)
  where rental_order_id is not null and not archived;

alter table assistant_clarification_question
  add column sequence_number integer;

with ranked as (
  select
    id,
    row_number() over (
      partition by conversation_id
      order by created_at, id
    ) as sequence_number
  from assistant_clarification_question
)
update assistant_clarification_question question
set sequence_number = ranked.sequence_number
from ranked
where question.id = ranked.id;

alter table assistant_clarification_question
  alter column sequence_number set not null,
  add constraint chk_assistant_clarification_sequence
    check (sequence_number > 0);

alter table assistant_clarification_question
  drop constraint chk_assistant_clarification_status,
  drop constraint chk_assistant_clarification_answer;

alter table assistant_clarification_question
  add constraint chk_assistant_clarification_status
    check (status in ('QUEUED', 'PENDING', 'ANSWERED', 'SUPERSEDED')),
  add constraint chk_assistant_clarification_answer
    check (
      (status = 'ANSWERED' and answered_option_id is not null and answered_at is not null)
      or (
        status in ('QUEUED', 'PENDING', 'SUPERSEDED')
        and answered_option_id is null
        and answered_at is null
      )
    );

with pending as (
  select
    id,
    row_number() over (
      partition by conversation_id
      order by sequence_number
    ) as pending_position
  from assistant_clarification_question
  where status = 'PENDING'
)
update assistant_clarification_question question
set status = 'QUEUED'
from pending
where question.id = pending.id
  and pending.pending_position > 1;

drop index uq_assistant_clarification_pending_branch;

create unique index uq_assistant_clarification_conversation_sequence
  on assistant_clarification_question (conversation_id, sequence_number);

create unique index uq_assistant_clarification_pending_conversation
  on assistant_clarification_question (conversation_id)
  where status = 'PENDING';
