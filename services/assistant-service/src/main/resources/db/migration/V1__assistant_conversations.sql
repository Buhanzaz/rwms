create table assistant_conversation (
  id uuid primary key,
  version bigint not null,
  owner_subject_id uuid not null,
  client_id uuid not null,
  rental_inquiry_id uuid not null,
  client_type varchar(64),
  client_display_name varchar(255),
  archived boolean not null default false,
  archived_at timestamptz,
  created_at timestamptz not null,
  updated_at timestamptz not null,
  constraint uq_assistant_conversation_rental_inquiry unique (rental_inquiry_id),
  constraint chk_assistant_conversation_archive_time
    check ((archived and archived_at is not null) or (not archived and archived_at is null))
);

create index idx_assistant_conversation_owner_active_updated
  on assistant_conversation (owner_subject_id, archived, updated_at desc);

create table assistant_message (
  id uuid primary key,
  conversation_id uuid not null references assistant_conversation(id),
  role varchar(16) not null,
  content text not null,
  created_at timestamptz not null,
  constraint chk_assistant_message_role
    check (role in ('USER', 'ASSISTANT'))
);

create index idx_assistant_message_conversation_created
  on assistant_message (conversation_id, created_at, id);

create table assistant_tool_call (
  id uuid primary key,
  conversation_id uuid not null references assistant_conversation(id),
  turn_message_id uuid not null references assistant_message(id),
  provider_call_id varchar(255) not null,
  tool_name varchar(96) not null,
  arguments_payload jsonb not null,
  result_payload jsonb,
  status varchar(16) not null,
  failure_code varchar(96),
  created_at timestamptz not null,
  completed_at timestamptz,
  constraint chk_assistant_tool_call_status
    check (status in ('STARTED', 'COMPLETED', 'FAILED')),
  constraint chk_assistant_tool_call_completion
    check (
      (status = 'STARTED' and completed_at is null)
      or (status in ('COMPLETED', 'FAILED') and completed_at is not null)
    )
);

create index idx_assistant_tool_call_conversation_created
  on assistant_tool_call (conversation_id, created_at, id);

create table assistant_event_inbox (
  event_id uuid primary key,
  event_type varchar(128) not null,
  occurred_at timestamptz not null,
  rental_inquiry_id uuid not null,
  conversation_id uuid not null,
  order_id uuid not null,
  received_at timestamptz not null,
  payload jsonb not null
);

create index idx_assistant_event_inbox_conversation
  on assistant_event_inbox (conversation_id);
