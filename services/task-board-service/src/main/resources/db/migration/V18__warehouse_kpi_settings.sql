CREATE TABLE public.warehouse_metadata (
  id uuid PRIMARY KEY,
  version bigint NOT NULL DEFAULT 0,
  source_version bigint NOT NULL,
  time_zone varchar(64) NOT NULL,
  active boolean NOT NULL,
  CONSTRAINT ck_warehouse_metadata_source_version CHECK (source_version >= 0)
);

CREATE TABLE public.warehouse_event_inbox (
  event_id uuid PRIMARY KEY,
  event_hash varchar(64) NOT NULL,
  aggregate_version bigint NOT NULL,
  processed_at timestamptz NOT NULL,
  CONSTRAINT ck_warehouse_event_inbox_hash CHECK (event_hash ~ '^[0-9a-f]{64}$'),
  CONSTRAINT ck_warehouse_event_inbox_version CHECK (aggregate_version >= 0)
);

CREATE TABLE public.kpi_palette (
  id uuid PRIMARY KEY,
  version bigint NOT NULL DEFAULT 0,
  overdue_color varchar(7) NOT NULL,
  CONSTRAINT ck_kpi_palette_overdue_color
    CHECK (overdue_color ~ '^#[0-9A-F]{6}$')
);

CREATE TABLE public.kpi_palette_range (
  id uuid PRIMARY KEY,
  version bigint NOT NULL DEFAULT 0,
  palette_id uuid NOT NULL,
  from_percent integer NOT NULL,
  to_percent integer NOT NULL,
  color varchar(7) NOT NULL,
  CONSTRAINT fk_kpi_palette_range_palette
    FOREIGN KEY (palette_id) REFERENCES public.kpi_palette(id) ON DELETE CASCADE,
  CONSTRAINT uk_kpi_palette_range_start UNIQUE (palette_id, from_percent),
  CONSTRAINT ck_kpi_palette_range_bounds
    CHECK (from_percent >= 0 AND to_percent <= 100 AND from_percent < to_percent),
  CONSTRAINT ck_kpi_palette_range_color
    CHECK (color ~ '^#[0-9A-F]{6}$')
);

CREATE TABLE public.kpi_work_schedule (
  id uuid PRIMARY KEY,
  version bigint NOT NULL DEFAULT 0,
  warehouse_id uuid NOT NULL,
  effective_from date NOT NULL,
  shift_start time NOT NULL,
  shift_end time NOT NULL,
  days_off_mask integer NOT NULL,
  scheduled boolean NOT NULL DEFAULT false,
  CONSTRAINT ck_kpi_work_schedule_shift CHECK (shift_start < shift_end),
  CONSTRAINT ck_kpi_work_schedule_days_off CHECK (days_off_mask BETWEEN 0 AND 127)
);

CREATE TABLE public.kpi_work_break (
  id uuid PRIMARY KEY,
  version bigint NOT NULL DEFAULT 0,
  schedule_id uuid NOT NULL,
  break_start time NOT NULL,
  break_end time NOT NULL,
  CONSTRAINT fk_kpi_work_break_schedule
    FOREIGN KEY (schedule_id) REFERENCES public.kpi_work_schedule(id) ON DELETE CASCADE,
  CONSTRAINT uk_kpi_work_break_start UNIQUE (schedule_id, break_start),
  CONSTRAINT ck_kpi_work_break_bounds CHECK (break_start < break_end)
);

CREATE TABLE public.warehouse_kpi_settings (
  id uuid PRIMARY KEY,
  version bigint NOT NULL DEFAULT 0,
  revision_marker uuid NOT NULL,
  warehouse_id uuid NOT NULL,
  time_zone varchar(64) NOT NULL,
  status varchar(32) NOT NULL,
  data_available_from date,
  palette_id uuid,
  active_schedule_id uuid,
  pending_schedule_id uuid,
  CONSTRAINT uk_warehouse_kpi_settings_warehouse UNIQUE (warehouse_id),
  CONSTRAINT fk_warehouse_kpi_settings_palette
    FOREIGN KEY (palette_id) REFERENCES public.kpi_palette(id),
  CONSTRAINT fk_warehouse_kpi_settings_active_schedule
    FOREIGN KEY (active_schedule_id) REFERENCES public.kpi_work_schedule(id),
  CONSTRAINT fk_warehouse_kpi_settings_pending_schedule
    FOREIGN KEY (pending_schedule_id) REFERENCES public.kpi_work_schedule(id),
  CONSTRAINT ck_warehouse_kpi_settings_status
    CHECK (status IN ('UNCONFIGURED','DRAFT','SCHEDULED','ACTIVE')),
  CONSTRAINT ck_warehouse_kpi_settings_distinct_schedule
    CHECK (active_schedule_id IS NULL OR active_schedule_id IS DISTINCT FROM pending_schedule_id)
);

CREATE TABLE public.kpi_activation_receipt (
  operation_id uuid PRIMARY KEY,
  warehouse_id uuid NOT NULL,
  expected_version bigint NOT NULL,
  resulting_version bigint NOT NULL,
  processed_at timestamptz NOT NULL,
  CONSTRAINT ck_kpi_activation_receipt_versions
    CHECK (expected_version >= 0 AND resulting_version >= expected_version)
);

ALTER TABLE public.worker_group
  ADD COLUMN operational_status varchar(32) NOT NULL DEFAULT 'AVAILABLE',
  ADD COLUMN unavailable_since timestamptz,
  ADD COLUMN unavailability_reason varchar(1000);

ALTER TABLE public.worker_group
  ADD CONSTRAINT ck_worker_group_operational_status
    CHECK (operational_status IN ('AVAILABLE','DISABLED')),
  ADD CONSTRAINT ck_worker_group_unavailability
    CHECK (
      (operational_status = 'AVAILABLE'
        AND unavailable_since IS NULL
        AND unavailability_reason IS NULL)
      OR
      (operational_status = 'DISABLED'
        AND unavailable_since IS NOT NULL)
    );

ALTER TABLE public.worker
  ADD COLUMN current_group_id uuid,
  ADD CONSTRAINT fk_worker_current_group
    FOREIGN KEY (current_group_id) REFERENCES public.worker_group(id);

CREATE INDEX idx_worker_current_group
  ON public.worker(current_group_id)
  WHERE current_group_id IS NOT NULL;

CREATE TABLE public.worker_current_group_interval (
  id uuid PRIMARY KEY,
  worker_id uuid NOT NULL,
  worker_group_id uuid NOT NULL,
  started_at timestamptz NOT NULL,
  ended_at timestamptz,
  CONSTRAINT fk_worker_current_group_interval_worker
    FOREIGN KEY (worker_id) REFERENCES public.worker(id),
  CONSTRAINT fk_worker_current_group_interval_group
    FOREIGN KEY (worker_group_id) REFERENCES public.worker_group(id),
  CONSTRAINT ck_worker_current_group_interval_order
    CHECK (ended_at IS NULL OR ended_at >= started_at)
);

CREATE UNIQUE INDEX uk_worker_current_group_interval_open
  ON public.worker_current_group_interval(worker_id)
  WHERE ended_at IS NULL;

CREATE TABLE public.worker_group_availability_interval (
  id uuid PRIMARY KEY,
  worker_group_id uuid NOT NULL,
  status varchar(32) NOT NULL,
  reason varchar(1000),
  started_at timestamptz NOT NULL,
  ended_at timestamptz,
  CONSTRAINT fk_worker_group_availability_interval_group
    FOREIGN KEY (worker_group_id) REFERENCES public.worker_group(id),
  CONSTRAINT ck_worker_group_availability_interval_status
    CHECK (status IN ('AVAILABLE','DISABLED')),
  CONSTRAINT ck_worker_group_availability_interval_order
    CHECK (ended_at IS NULL OR ended_at >= started_at)
);

CREATE UNIQUE INDEX uk_worker_group_availability_interval_open
  ON public.worker_group_availability_interval(worker_group_id)
  WHERE ended_at IS NULL;

INSERT INTO public.worker_group_availability_interval(
  id,worker_group_id,status,reason,started_at,ended_at)
SELECT gen_random_uuid(),id,'AVAILABLE',null,clock_timestamp(),null
  FROM public.worker_group;

ALTER TABLE public.queue_entry
  ADD COLUMN original_budget_seconds bigint,
  ADD COLUMN current_budget_seconds bigint;

UPDATE public.queue_entry
   SET original_budget_seconds = planned_duration_minutes::bigint * 60,
       current_budget_seconds = planned_duration_minutes::bigint * 60
 WHERE planned_duration_minutes > 0;

ALTER TABLE public.queue_entry
  ADD CONSTRAINT ck_queue_entry_original_budget
    CHECK (original_budget_seconds IS NULL OR original_budget_seconds > 0),
  ADD CONSTRAINT ck_queue_entry_current_budget
    CHECK (current_budget_seconds IS NULL OR current_budget_seconds > 0),
  ADD CONSTRAINT ck_queue_entry_budget_pair
    CHECK (
      (original_budget_seconds IS NULL AND current_budget_seconds IS NULL)
      OR
      (original_budget_seconds IS NOT NULL AND current_budget_seconds IS NOT NULL)
    );

CREATE INDEX idx_kpi_work_schedule_effective
  ON public.kpi_work_schedule(warehouse_id, effective_from, scheduled);

CREATE UNIQUE INDEX uk_kpi_work_schedule_scheduled_effective
  ON public.kpi_work_schedule(warehouse_id, effective_from)
  WHERE scheduled;

CREATE TABLE public.group_kpi_day_state (
  id uuid PRIMARY KEY,
  version bigint NOT NULL DEFAULT 0,
  warehouse_id uuid NOT NULL,
  worker_group_id uuid NOT NULL,
  local_date date NOT NULL,
  data_available_from date NOT NULL,
  formula_version varchar(32) NOT NULL,
  completed_budget_seconds bigint NOT NULL DEFAULT 0,
  earned_remaining_seconds bigint NOT NULL DEFAULT 0,
  active_seconds bigint NOT NULL DEFAULT 0,
  penalized_idle_seconds bigint NOT NULL DEFAULT 0,
  completed_task_count bigint NOT NULL DEFAULT 0,
  open_state varchar(32),
  open_state_started_at timestamptz,
  penalty_starts_at timestamptz,
  next_transition_at timestamptz,
  evidence_as_of timestamptz NOT NULL,
  CONSTRAINT uk_group_kpi_day UNIQUE (warehouse_id, worker_group_id, local_date),
  CONSTRAINT ck_group_kpi_day_non_negative CHECK (
    completed_budget_seconds >= 0
    AND earned_remaining_seconds >= 0
    AND earned_remaining_seconds <= completed_budget_seconds
    AND active_seconds >= 0
    AND penalized_idle_seconds >= 0
    AND completed_task_count >= 0
  ),
  CONSTRAINT ck_group_kpi_day_formula CHECK (formula_version = 'kpi-v1'),
  CONSTRAINT ck_group_kpi_day_open_state CHECK (
    open_state IS NULL
    OR open_state IN ('WORKING','IDLE_GRACE','IDLE_PENALIZED','EXCLUDED')
  ),
  CONSTRAINT ck_group_kpi_day_open_timestamps CHECK (
    (open_state IS NULL AND open_state_started_at IS NULL AND penalty_starts_at IS NULL)
    OR
    (open_state IS NOT NULL AND open_state_started_at IS NOT NULL)
  )
);

CREATE INDEX idx_group_kpi_day_due
  ON public.group_kpi_day_state(next_transition_at)
  WHERE next_transition_at IS NOT NULL;

CREATE TABLE public.group_kpi_responsibility_segment (
  id uuid PRIMARY KEY,
  version bigint NOT NULL DEFAULT 0,
  warehouse_id uuid NOT NULL,
  worker_group_id uuid NOT NULL,
  queue_entry_id uuid NOT NULL,
  budget_seconds bigint NOT NULL,
  active_seconds bigint NOT NULL DEFAULT 0,
  started_at timestamptz NOT NULL,
  finished_at timestamptz,
  outcome varchar(32) NOT NULL,
  CONSTRAINT fk_group_kpi_segment_group
    FOREIGN KEY (worker_group_id) REFERENCES public.worker_group(id),
  CONSTRAINT fk_group_kpi_segment_entry
    FOREIGN KEY (queue_entry_id) REFERENCES public.queue_entry(id),
  CONSTRAINT ck_group_kpi_segment_budget CHECK (budget_seconds >= 0),
  CONSTRAINT ck_group_kpi_segment_active CHECK (active_seconds >= 0),
  CONSTRAINT ck_group_kpi_segment_outcome
    CHECK (outcome IN ('OPEN','COMPLETED','RETURNED')),
  CONSTRAINT ck_group_kpi_segment_finish CHECK (
    (outcome = 'OPEN' AND finished_at IS NULL)
    OR
    (outcome <> 'OPEN' AND finished_at IS NOT NULL)
  )
);

CREATE UNIQUE INDEX uk_group_kpi_segment_open_entry
  ON public.group_kpi_responsibility_segment(queue_entry_id, worker_group_id)
  WHERE outcome = 'OPEN';

CREATE TABLE public.group_kpi_segment_day (
  id uuid PRIMARY KEY,
  version bigint NOT NULL DEFAULT 0,
  segment_id uuid NOT NULL,
  evidence_id uuid NOT NULL,
  active_seconds bigint NOT NULL DEFAULT 0,
  CONSTRAINT fk_group_kpi_segment_day_segment
    FOREIGN KEY (segment_id) REFERENCES public.group_kpi_responsibility_segment(id),
  CONSTRAINT fk_group_kpi_segment_day_evidence
    FOREIGN KEY (evidence_id) REFERENCES public.group_kpi_day_state(id),
  CONSTRAINT uk_group_kpi_segment_day UNIQUE (segment_id, evidence_id),
  CONSTRAINT ck_group_kpi_segment_day_active CHECK (active_seconds >= 0)
);

ALTER TABLE public.event_stream_head
  DROP CONSTRAINT ck_event_stream_head_type;
ALTER TABLE public.event_stream_head
  ADD CONSTRAINT ck_event_stream_head_type CHECK (aggregate_type IN (
    'WORKER_CLASS', 'WORKER', 'WORKER_GROUP', 'WORK_QUEUE',
    'QUEUE_USAGE_REFERENCE', 'BOARD_TASK', 'QUEUE_ENTRY',
    'TASK_BOARD_ENTRY_OWNER_PROOF', 'TASK_EVIDENCE', 'GROUP_KPI_DAY'
  ));

ALTER TABLE public.domain_event
  DROP CONSTRAINT ck_domain_event_type;
ALTER TABLE public.domain_event
  ADD CONSTRAINT ck_domain_event_type CHECK (aggregate_type IN (
    'WORKER_CLASS', 'WORKER', 'WORKER_GROUP', 'WORK_QUEUE',
    'QUEUE_USAGE_REFERENCE', 'BOARD_TASK', 'QUEUE_ENTRY',
    'TASK_BOARD_ENTRY_OWNER_PROOF', 'TASK_EVIDENCE', 'GROUP_KPI_DAY'
  ));

ALTER TABLE public.projection_checkpoint
  DROP CONSTRAINT ck_projection_checkpoint_type;
ALTER TABLE public.projection_checkpoint
  ADD CONSTRAINT ck_projection_checkpoint_type CHECK (aggregate_type IN (
    'WORKER_CLASS', 'WORKER', 'WORKER_GROUP', 'WORK_QUEUE',
    'QUEUE_USAGE_REFERENCE', 'BOARD_TASK', 'QUEUE_ENTRY',
    'TASK_BOARD_ENTRY_OWNER_PROOF', 'TASK_EVIDENCE', 'GROUP_KPI_DAY'
  ));

ALTER TABLE public.outbox_event
  DROP CONSTRAINT ck_outbox_event_topic;
ALTER TABLE public.outbox_event
  ADD CONSTRAINT ck_outbox_event_topic CHECK (
    (
      aggregate_type IN (
        'WORKER_CLASS', 'WORKER', 'WORKER_GROUP', 'WORK_QUEUE',
        'QUEUE_USAGE_REFERENCE', 'BOARD_TASK', 'QUEUE_ENTRY', 'GROUP_KPI_DAY'
      )
      AND topic =
        'rwms.task-board.' || replace(lower(aggregate_type), '_', '-') || '.v1'
    )
    OR (
      aggregate_type = 'TASK_BOARD_ENTRY_OWNER_PROOF'
      AND topic = 'rwms.task-board.entry-owner-proof.v1'
    )
    OR (
      aggregate_type = 'TASK_EVIDENCE'
      AND topic = 'rwms.task-board.task-evidence.v1'
    )
  );
