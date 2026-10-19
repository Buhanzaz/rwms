alter table board_task
  add column has_problem boolean not null default false,
  add column incomplete boolean not null default false,
  add column completed_work_percent double precision not null default 0,
  add column requirements_revision uuid,
  add column requirements jsonb not null default '[]'::jsonb;
alter table board_task add constraint ck_board_task_completed_work_percent
  check (completed_work_percent between 0 and 100);
alter table queue_entry add column requirements_revision uuid;
alter table queue_entry drop constraint ck_queue_entry_current_budget;
alter table queue_entry add constraint ck_queue_entry_current_budget
  check (current_budget_seconds is null or current_budget_seconds >= 0);
alter table task_problem_report
  add column requested_missing_item_ids jsonb not null default '[]'::jsonb,
  add column missing_items jsonb not null default '[]'::jsonb,
  add column expected_entry_version bigint,
  add column unit_number varchar(64),
  add column applied_to_all boolean not null default false;
alter table kpi_palette add column problem_color varchar(7) not null default '#FF3B30';
create table task_problem_bulk_receipt (
  operation_id uuid primary key,
  report_id uuid not null references task_problem_report(id),
  warehouse_id uuid not null,
  manager_id uuid not null,
  affected_task_ids jsonb not null,
  recorded_at timestamptz not null default clock_timestamp()
);
