create table assistant_clarification_question (
  id uuid primary key,
  version bigint not null,
  conversation_id uuid not null references assistant_conversation(id),
  turn_message_id uuid not null references assistant_message(id),
  tool_call_id uuid not null references assistant_tool_call(id),
  branch_key varchar(255) not null,
  kind varchar(32) not null,
  prompt varchar(2000) not null,
  warehouse_id uuid not null,
  cabin_type varchar(255),
  options_payload jsonb not null,
  status varchar(16) not null,
  answered_option_id uuid,
  created_at timestamptz not null,
  answered_at timestamptz,
  constraint chk_assistant_clarification_kind
    check (kind in ('CABIN_TYPE', 'FINISH', 'DIMENSIONS', 'CATEGORY', 'SEARCH_MERGE')),
  constraint chk_assistant_clarification_options
    check (jsonb_typeof(options_payload) = 'array' and jsonb_array_length(options_payload) between 2 and 30),
  constraint chk_assistant_clarification_status
    check (status in ('PENDING', 'ANSWERED', 'SUPERSEDED')),
  constraint chk_assistant_clarification_answer
    check (
      (status = 'ANSWERED' and answered_option_id is not null and answered_at is not null)
      or (status in ('PENDING', 'SUPERSEDED') and answered_option_id is null and answered_at is null)
    )
);

create index idx_assistant_clarification_conversation_created
  on assistant_clarification_question (conversation_id, created_at, id);

create unique index uq_assistant_clarification_pending_branch
  on assistant_clarification_question (conversation_id, branch_key)
  where status = 'PENDING';
