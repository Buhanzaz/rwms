-- RWMS task-board cumulative Flyway schema, version 4.
--
-- This is the clean-install source for the reviewed schema after historical
-- releases 0001-0004. Existing non-empty databases must pass
-- database/flyway/verify-version-4.sql and be explicitly baselined at version 4.
-- Historical release files remain immutable migration evidence.

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

ALTER TABLE public.board_task
  ADD COLUMN IF NOT EXISTS request_fingerprint varchar(64);
DO $rwms$
BEGIN
  IF NOT EXISTS (
    SELECT 1 FROM pg_constraint
    WHERE conname = 'ck_board_task_request_fingerprint'
      AND conrelid = 'public.board_task'::regclass
  ) THEN
    ALTER TABLE public.board_task
      ADD CONSTRAINT ck_board_task_request_fingerprint
      CHECK (request_fingerprint IS NULL OR request_fingerprint ~ '^[0-9a-f]{64}$');
  END IF;
END $rwms$;

CREATE TABLE IF NOT EXISTS public.task_board_outbox (
  event_id uuid PRIMARY KEY,
  event_type varchar(128) NOT NULL,
  event_version integer NOT NULL,
  routing_key varchar(160) NOT NULL,
  aggregate_type varchar(64) NOT NULL,
  aggregate_id varchar(128) NOT NULL,
  aggregate_version bigint NOT NULL,
  envelope_body text NOT NULL,
  envelope_sha256 varchar(64) NOT NULL,
  correlation_id uuid NOT NULL,
  causation_id uuid,
  actor_id varchar(128),
  actor_type varchar(64),
  actor_display_name varchar(256),
  occurred_at timestamptz NOT NULL,
  created_at timestamptz NOT NULL,
  status varchar(16) NOT NULL DEFAULT 'PENDING',
  attempt_count integer NOT NULL DEFAULT 0,
  next_attempt_at timestamptz NOT NULL,
  lease_until timestamptz,
  lease_owner varchar(128),
  lease_token uuid,
  published_at timestamptz,
  last_error varchar(1000),
  CONSTRAINT uk_task_board_outbox_semantic
    UNIQUE (aggregate_type, aggregate_id, aggregate_version, event_type),
  CONSTRAINT ck_task_board_outbox_event_version CHECK (event_version > 0),
  CONSTRAINT ck_task_board_outbox_aggregate_version CHECK (aggregate_version >= 0),
  CONSTRAINT ck_task_board_outbox_attempt_count CHECK (attempt_count >= 0),
  CONSTRAINT ck_task_board_outbox_body CHECK (
    octet_length(envelope_body) BETWEEN 1 AND 1048576
    AND envelope_sha256 ~ '^[0-9a-f]{64}$'
  ),
  CONSTRAINT ck_task_board_outbox_status CHECK (status IN ('PENDING','IN_FLIGHT','PUBLISHED')),
  CONSTRAINT ck_task_board_outbox_lease CHECK (
    (status = 'IN_FLIGHT'
      AND lease_until IS NOT NULL AND lease_owner IS NOT NULL AND lease_token IS NOT NULL)
    OR
    (status <> 'IN_FLIGHT'
      AND lease_until IS NULL AND lease_owner IS NULL AND lease_token IS NULL)
  ),
  CONSTRAINT ck_task_board_outbox_published CHECK (
    (status = 'PUBLISHED') = (published_at IS NOT NULL)
  ),
  CONSTRAINT ck_task_board_outbox_contract CHECK (
    event_version = 1 AND (
    (event_type = 'task-board.board-task.created'
      AND routing_key = 'task-board.board-task.created.v1')
    OR
    (event_type = 'task-board.board-task.cancelled'
      AND routing_key = 'task-board.board-task.cancelled.v1')
    )
  )
);
CREATE INDEX IF NOT EXISTS idx_task_board_outbox_pending
  ON public.task_board_outbox(next_attempt_at, occurred_at, event_id)
  WHERE status = 'PENDING';
CREATE INDEX IF NOT EXISTS idx_task_board_outbox_expired_lease
  ON public.task_board_outbox(lease_until, occurred_at, event_id)
  WHERE status = 'IN_FLIGHT';
CREATE INDEX IF NOT EXISTS idx_task_board_outbox_aggregate_head
  ON public.task_board_outbox(aggregate_type, aggregate_id, aggregate_version, occurred_at, event_id)
  WHERE status <> 'PUBLISHED';

CREATE TABLE IF NOT EXISTS public.task_board_inbox (
  consumer_name varchar(128) NOT NULL,
  event_id uuid NOT NULL,
  event_hash varchar(64) NOT NULL,
  event_type varchar(128) NOT NULL,
  event_version integer NOT NULL,
  received_at timestamptz NOT NULL,
  processed_at timestamptz NOT NULL,
  CONSTRAINT pk_task_board_inbox PRIMARY KEY (consumer_name, event_id),
  CONSTRAINT ck_task_board_inbox_hash CHECK (event_hash ~ '^[0-9a-f]{64}$'),
  CONSTRAINT ck_task_board_inbox_version CHECK (event_version > 0)
);

DO $rwms$
BEGIN
  IF EXISTS (
    SELECT 1 FROM public.worker_class
    GROUP BY lower(code)
    HAVING count(*) > 1
  ) THEN
    RAISE EXCEPTION 'Cannot enforce case-insensitive worker class identity: duplicate normalized values exist';
  END IF;
  IF EXISTS (
    SELECT 1 FROM public.worker
    WHERE app_login IS NOT NULL
    GROUP BY lower(app_login)
    HAVING count(*) > 1
  ) THEN
    RAISE EXCEPTION 'Cannot enforce case-insensitive worker login identity: duplicate normalized values exist';
  END IF;
  IF EXISTS (
    SELECT 1 FROM public.work_queue
    GROUP BY warehouse_id, lower(code)
    HAVING count(*) > 1
  ) THEN
    RAISE EXCEPTION 'Cannot enforce case-insensitive work queue identity: duplicate normalized values exist';
  END IF;
END $rwms$;

CREATE UNIQUE INDEX IF NOT EXISTS uk_worker_class_code_ci
  ON public.worker_class (lower(code));
CREATE UNIQUE INDEX IF NOT EXISTS uk_worker_app_login_ci
  ON public.worker (lower(app_login))
  WHERE app_login IS NOT NULL;
CREATE UNIQUE INDEX IF NOT EXISTS uk_work_queue_code_ci
  ON public.work_queue (warehouse_id, lower(code));

ALTER TABLE public.worker
  ADD COLUMN IF NOT EXISTS credential_operation_id uuid,
  ADD COLUMN IF NOT EXISTS credential_operation_type varchar(32),
  ADD COLUMN IF NOT EXISTS credential_operation_started_at timestamptz;

DO $rwms$
BEGIN
  IF NOT EXISTS (
    SELECT 1
    FROM pg_constraint
    WHERE conrelid = 'public.worker'::regclass
      AND conname = 'ck_worker_credential_operation_type'
  ) THEN
    ALTER TABLE public.worker
      ADD CONSTRAINT ck_worker_credential_operation_type
      CHECK (credential_operation_type IN (
        'CONFIGURE',
        'RESET',
        'DISABLE',
        'CLEAR',
        'RECONCILE_DISABLE'
      ));
  END IF;

  IF NOT EXISTS (
    SELECT 1
    FROM pg_constraint
    WHERE conrelid = 'public.worker'::regclass
      AND conname = 'ck_worker_credential_operation_metadata'
  ) THEN
    ALTER TABLE public.worker
      ADD CONSTRAINT ck_worker_credential_operation_metadata
      CHECK (num_nonnulls(
        credential_operation_id,
        credential_operation_type,
        credential_operation_started_at
      ) IN (0, 3));
  END IF;
END $rwms$;

