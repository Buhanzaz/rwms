CREATE TABLE IF NOT EXISTS public.worker_class (
  id uuid PRIMARY KEY, version bigint NOT NULL DEFAULT 0, revision_marker uuid NOT NULL,
  code varchar(64) NOT NULL, name varchar(128) NOT NULL, description varchar(1000),
  comment_text varchar(1000), sort_order integer NOT NULL DEFAULT 0, active boolean NOT NULL DEFAULT true,
  CONSTRAINT uk_worker_class_code UNIQUE (code)
);
CREATE TABLE IF NOT EXISTS public.worker (
  id uuid PRIMARY KEY, version bigint NOT NULL DEFAULT 0, revision_marker uuid NOT NULL,
  warehouse_id uuid NOT NULL, first_name varchar(128), last_name varchar(128), middle_name varchar(128),
  display_name varchar(256) NOT NULL, active boolean NOT NULL DEFAULT true, comment_text varchar(1000),
  app_login varchar(128), credential_status varchar(32) NOT NULL DEFAULT 'NOT_CONFIGURED',
  credential_error varchar(1000), CONSTRAINT uk_worker_app_login UNIQUE (app_login)
);
CREATE INDEX IF NOT EXISTS idx_worker_warehouse ON public.worker(warehouse_id, active, display_name);
CREATE TABLE IF NOT EXISTS public.worker_deletion_intent (
  id uuid PRIMARY KEY, version bigint NOT NULL DEFAULT 0, worker_id uuid NOT NULL,
  status varchar(32) NOT NULL, last_error varchar(1000), created_at timestamptz NOT NULL,
  updated_at timestamptz NOT NULL,
  CONSTRAINT fk_worker_deletion_intent_worker FOREIGN KEY (worker_id) REFERENCES public.worker(id),
  CONSTRAINT uk_worker_deletion_intent_worker UNIQUE (worker_id)
);
CREATE TABLE IF NOT EXISTS public.worker_class_assignment (
  id uuid PRIMARY KEY, version bigint NOT NULL DEFAULT 0, worker_id uuid NOT NULL,
  worker_class_id uuid NOT NULL, active boolean NOT NULL DEFAULT true, comment_text varchar(1000),
  CONSTRAINT fk_worker_class_assignment_worker FOREIGN KEY (worker_id) REFERENCES public.worker(id),
  CONSTRAINT fk_worker_class_assignment_class FOREIGN KEY (worker_class_id) REFERENCES public.worker_class(id),
  CONSTRAINT uk_worker_class_assignment UNIQUE (worker_id, worker_class_id)
);
CREATE TABLE IF NOT EXISTS public.worker_group (
  id uuid PRIMARY KEY, version bigint NOT NULL DEFAULT 0, revision_marker uuid NOT NULL,
  warehouse_id uuid NOT NULL, worker_class_id uuid NOT NULL, name varchar(128) NOT NULL,
  description varchar(1000), active boolean NOT NULL DEFAULT true,
  CONSTRAINT fk_worker_group_class FOREIGN KEY (worker_class_id) REFERENCES public.worker_class(id),
  CONSTRAINT uk_worker_group_name UNIQUE (warehouse_id, name)
);
CREATE INDEX IF NOT EXISTS idx_worker_group_warehouse ON public.worker_group(warehouse_id, active, name);
CREATE TABLE IF NOT EXISTS public.worker_group_member (
  id uuid PRIMARY KEY, version bigint NOT NULL DEFAULT 0, worker_group_id uuid NOT NULL,
  worker_id uuid NOT NULL, role_in_group varchar(128), active boolean NOT NULL DEFAULT true,
  CONSTRAINT fk_worker_group_member_group FOREIGN KEY (worker_group_id) REFERENCES public.worker_group(id),
  CONSTRAINT fk_worker_group_member_worker FOREIGN KEY (worker_id) REFERENCES public.worker(id),
  CONSTRAINT uk_worker_group_member UNIQUE (worker_group_id, worker_id)
);
CREATE TABLE IF NOT EXISTS public.work_queue (
  id uuid PRIMARY KEY, version bigint NOT NULL DEFAULT 0, revision_marker uuid NOT NULL,
  warehouse_id uuid NOT NULL, code varchar(64) NOT NULL, name varchar(128) NOT NULL,
  description varchar(1000), queue_type varchar(32) NOT NULL, sort_order integer NOT NULL DEFAULT 0,
  active boolean NOT NULL DEFAULT true, hidden boolean NOT NULL DEFAULT false,
  collapsed boolean NOT NULL DEFAULT false, holding_period_minutes integer,
  notification_threshold integer, notify_when_threshold_reached boolean NOT NULL DEFAULT false,
  CONSTRAINT uk_work_queue_code UNIQUE (warehouse_id, code),
  CONSTRAINT ck_work_queue_holding CHECK (
    queue_type = 'HOLDING' OR
    (holding_period_minutes IS NULL AND notification_threshold IS NULL AND notify_when_threshold_reached = false)
  )
);
CREATE INDEX IF NOT EXISTS idx_work_queue_order ON public.work_queue(warehouse_id, sort_order, name);
CREATE TABLE IF NOT EXISTS public.work_queue_class_binding (
  id uuid PRIMARY KEY, version bigint NOT NULL DEFAULT 0, queue_id uuid NOT NULL,
  worker_class_id uuid NOT NULL, stop_task_on_take boolean NOT NULL DEFAULT false,
  CONSTRAINT fk_work_queue_binding_queue FOREIGN KEY (queue_id) REFERENCES public.work_queue(id),
  CONSTRAINT fk_work_queue_binding_class FOREIGN KEY (worker_class_id) REFERENCES public.worker_class(id),
  CONSTRAINT uk_work_queue_binding UNIQUE (queue_id, worker_class_id)
);
CREATE TABLE IF NOT EXISTS public.board_task (
  id uuid PRIMARY KEY, version bigint NOT NULL DEFAULT 0, warehouse_id uuid NOT NULL,
  external_task_id uuid, title varchar(256) NOT NULL, unit_number varchar(64),
  description varchar(2000), status varchar(32) NOT NULL, planned_duration_minutes integer,
  deadline_at timestamptz, done_at timestamptz,
  CONSTRAINT uk_board_task_external UNIQUE (external_task_id)
);
CREATE INDEX IF NOT EXISTS idx_board_task_warehouse_status ON public.board_task(warehouse_id, status);
CREATE TABLE IF NOT EXISTS public.queue_entry (
  id uuid PRIMARY KEY, version bigint NOT NULL DEFAULT 0, task_id uuid NOT NULL,
  queue_id uuid, queue_code varchar(64), route_index integer NOT NULL, queue_position integer NOT NULL,
  entry_type varchar(16) NOT NULL, status varchar(32) NOT NULL, task_text varchar(2000),
  planned_duration_minutes integer, active_started_at timestamptz, paused_at timestamptz,
  done_at timestamptz, active_work_seconds bigint NOT NULL DEFAULT 0, pause_origin varchar(16),
  CONSTRAINT fk_queue_entry_task FOREIGN KEY (task_id) REFERENCES public.board_task(id),
  CONSTRAINT fk_queue_entry_queue FOREIGN KEY (queue_id) REFERENCES public.work_queue(id),
  CONSTRAINT uk_queue_entry_route UNIQUE (task_id, route_index)
);
CREATE INDEX IF NOT EXISTS idx_queue_entry_board ON public.queue_entry(queue_id, status, entry_type, queue_position);
CREATE TABLE IF NOT EXISTS public.task_assignment (
  id uuid PRIMARY KEY, version bigint NOT NULL DEFAULT 0, queue_entry_id uuid NOT NULL,
  worker_group_id uuid, worker_id uuid, worker_name_snapshot varchar(256),
  group_name_snapshot varchar(128), status varchar(32) NOT NULL, assigned_at timestamptz NOT NULL,
  started_at timestamptz, paused_at timestamptz, finished_at timestamptz,
  CONSTRAINT fk_task_assignment_entry FOREIGN KEY (queue_entry_id) REFERENCES public.queue_entry(id),
  CONSTRAINT fk_task_assignment_group FOREIGN KEY (worker_group_id) REFERENCES public.worker_group(id),
  CONSTRAINT fk_task_assignment_worker FOREIGN KEY (worker_id) REFERENCES public.worker(id)
);
CREATE INDEX IF NOT EXISTS idx_task_assignment_worker_status ON public.task_assignment(worker_id, status);
CREATE TABLE IF NOT EXISTS public.task_time_event (
  id uuid PRIMARY KEY, version bigint NOT NULL DEFAULT 0, queue_entry_id uuid NOT NULL,
  worker_id uuid, worker_name_snapshot varchar(256), group_name_snapshot varchar(128),
  event_type varchar(32) NOT NULL, reason varchar(1000), created_at timestamptz NOT NULL,
  related_entry_id uuid,
  CONSTRAINT fk_task_time_event_entry FOREIGN KEY (queue_entry_id) REFERENCES public.queue_entry(id),
  CONSTRAINT fk_task_time_event_worker FOREIGN KEY (worker_id) REFERENCES public.worker(id)
);
CREATE TABLE IF NOT EXISTS public.task_auto_interruption (
  id uuid PRIMARY KEY, version bigint NOT NULL DEFAULT 0, worker_id uuid NOT NULL,
  interrupted_entry_id uuid NOT NULL, interrupting_entry_id uuid NOT NULL,
  active boolean NOT NULL DEFAULT true, created_at timestamptz NOT NULL, resolved_at timestamptz,
  CONSTRAINT fk_auto_interruption_worker FOREIGN KEY (worker_id) REFERENCES public.worker(id),
  CONSTRAINT fk_auto_interruption_interrupted FOREIGN KEY (interrupted_entry_id) REFERENCES public.queue_entry(id),
  CONSTRAINT fk_auto_interruption_interrupting FOREIGN KEY (interrupting_entry_id) REFERENCES public.queue_entry(id),
  CONSTRAINT uk_auto_interruption UNIQUE (worker_id, interrupted_entry_id, interrupting_entry_id)
);
CREATE TABLE IF NOT EXISTS public.queue_usage_reference (
  id uuid PRIMARY KEY, version bigint NOT NULL DEFAULT 0, revision_marker uuid NOT NULL,
  queue_id uuid NOT NULL, reference_type varchar(32) NOT NULL, external_reference_id varchar(128) NOT NULL,
  CONSTRAINT fk_queue_usage_queue FOREIGN KEY (queue_id) REFERENCES public.work_queue(id),
  CONSTRAINT uk_queue_usage_reference UNIQUE (reference_type, external_reference_id)
);
