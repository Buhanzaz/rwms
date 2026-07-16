-- F4T expand-only task-board event store and deterministic migration baseline.
-- Existing JPA projections, RabbitMQ outbox/inbox and historical evidence remain intact.
-- Worker PII stays in the existing operational projection. Events carry only the opaque
-- revision_marker; a separate profile/PII owner remains UNKNOWN and is not synthesized here.

CREATE TABLE public.event_stream_head (
  aggregate_type varchar(64) NOT NULL,
  aggregate_id varchar(128) NOT NULL,
  current_version bigint NOT NULL,
  last_event_id uuid NOT NULL,
  updated_at timestamptz NOT NULL,
  CONSTRAINT event_stream_head_pkey PRIMARY KEY (aggregate_type, aggregate_id),
  CONSTRAINT ck_event_stream_head_type CHECK (aggregate_type IN (
    'WORKER_CLASS', 'WORKER', 'WORKER_GROUP', 'WORK_QUEUE',
    'QUEUE_USAGE_REFERENCE', 'BOARD_TASK', 'QUEUE_ENTRY'
  )),
  CONSTRAINT ck_event_stream_head_version CHECK (current_version >= 0)
);

CREATE TABLE public.domain_event (
  event_id uuid NOT NULL,
  aggregate_type varchar(64) NOT NULL,
  aggregate_id varchar(128) NOT NULL,
  aggregate_version bigint NOT NULL,
  event_type varchar(160) NOT NULL,
  event_version integer NOT NULL,
  occurred_at timestamptz,
  recorded_at timestamptz NOT NULL,
  correlation_id uuid NOT NULL,
  causation_id uuid,
  actor_ref jsonb,
  payload jsonb NOT NULL,
  payload_sha256 char(64) NOT NULL,
  baseline boolean NOT NULL DEFAULT false,
  CONSTRAINT domain_event_pkey PRIMARY KEY (event_id),
  CONSTRAINT uk_domain_event_stream_version
    UNIQUE (aggregate_type, aggregate_id, aggregate_version),
  CONSTRAINT uk_domain_event_outbox_identity
    UNIQUE (event_id, aggregate_type, aggregate_id, aggregate_version, event_type),
  CONSTRAINT fk_domain_event_stream_head
    FOREIGN KEY (aggregate_type, aggregate_id)
    REFERENCES public.event_stream_head(aggregate_type, aggregate_id),
  CONSTRAINT ck_domain_event_type CHECK (aggregate_type IN (
    'WORKER_CLASS', 'WORKER', 'WORKER_GROUP', 'WORK_QUEUE',
    'QUEUE_USAGE_REFERENCE', 'BOARD_TASK', 'QUEUE_ENTRY'
  )),
  CONSTRAINT ck_domain_event_version
    CHECK (aggregate_version >= 0 AND event_version = 1),
  CONSTRAINT ck_domain_event_payload CHECK (
    jsonb_typeof(payload) = 'object'
    AND payload_sha256 ~ '^[0-9a-f]{64}$'
  ),
  CONSTRAINT ck_domain_event_actor_ref CHECK (
    actor_ref IS NULL OR jsonb_typeof(actor_ref) = 'object'
  ),
  CONSTRAINT ck_domain_event_baseline CHECK (
    (baseline AND occurred_at IS NULL AND event_type =
      'task-board.' || replace(lower(aggregate_type), '_', '-') || '.baseline.v1')
    OR
    (NOT baseline AND occurred_at IS NOT NULL AND event_type <>
      'task-board.' || replace(lower(aggregate_type), '_', '-') || '.baseline.v1')
  )
);

CREATE INDEX idx_domain_event_stream
  ON public.domain_event(aggregate_type, aggregate_id, aggregate_version);
CREATE INDEX idx_domain_event_recorded
  ON public.domain_event(recorded_at, event_id);

CREATE TABLE public.aggregate_snapshot (
  aggregate_type varchar(64) NOT NULL,
  aggregate_id varchar(128) NOT NULL,
  aggregate_version bigint NOT NULL,
  state jsonb NOT NULL,
  state_sha256 char(64) NOT NULL,
  recorded_at timestamptz NOT NULL,
  CONSTRAINT aggregate_snapshot_pkey
    PRIMARY KEY (aggregate_type, aggregate_id, aggregate_version),
  CONSTRAINT fk_aggregate_snapshot_stream_head
    FOREIGN KEY (aggregate_type, aggregate_id)
    REFERENCES public.event_stream_head(aggregate_type, aggregate_id),
  CONSTRAINT ck_aggregate_snapshot_version CHECK (aggregate_version >= 0),
  CONSTRAINT ck_aggregate_snapshot_state CHECK (
    jsonb_typeof(state) = 'object'
    AND state_sha256 ~ '^[0-9a-f]{64}$'
    AND state_sha256 = encode(sha256(convert_to(state::text, 'UTF8')), 'hex')
  )
);

CREATE TABLE public.projection_checkpoint (
  projection_name varchar(128) NOT NULL,
  aggregate_type varchar(64) NOT NULL,
  aggregate_id varchar(128) NOT NULL,
  aggregate_version bigint NOT NULL,
  projection_sha256 char(64) NOT NULL,
  updated_at timestamptz NOT NULL,
  CONSTRAINT projection_checkpoint_pkey
    PRIMARY KEY (projection_name, aggregate_type, aggregate_id),
  CONSTRAINT ck_projection_checkpoint_type CHECK (aggregate_type IN (
    'WORKER_CLASS', 'WORKER', 'WORKER_GROUP', 'WORK_QUEUE',
    'QUEUE_USAGE_REFERENCE', 'BOARD_TASK', 'QUEUE_ENTRY'
  )),
  CONSTRAINT ck_projection_checkpoint_version CHECK (aggregate_version >= 0),
  CONSTRAINT ck_projection_checkpoint_sha256
    CHECK (projection_sha256 ~ '^[0-9a-f]{64}$')
);

CREATE TABLE public.outbox_event (
  event_id uuid NOT NULL,
  aggregate_type varchar(64) NOT NULL,
  aggregate_id varchar(128) NOT NULL,
  aggregate_version bigint NOT NULL,
  event_type varchar(160) NOT NULL,
  topic varchar(192) NOT NULL,
  envelope_body jsonb NOT NULL,
  envelope_sha256 char(64) NOT NULL,
  status varchar(24) NOT NULL DEFAULT 'PENDING',
  attempt_count integer NOT NULL DEFAULT 0,
  next_attempt_at timestamptz NOT NULL,
  lease_owner varchar(128),
  lease_token uuid,
  lease_until timestamptz,
  published_at timestamptz,
  dlt_at timestamptz,
  last_error_code varchar(64),
  created_at timestamptz NOT NULL,
  CONSTRAINT outbox_event_pkey PRIMARY KEY (event_id),
  CONSTRAINT uk_outbox_event_stream_version
    UNIQUE (aggregate_type, aggregate_id, aggregate_version),
  CONSTRAINT fk_outbox_event_domain_event
    FOREIGN KEY (event_id, aggregate_type, aggregate_id, aggregate_version, event_type)
    REFERENCES public.domain_event(
      event_id, aggregate_type, aggregate_id, aggregate_version, event_type),
  CONSTRAINT ck_outbox_event_version
    CHECK (aggregate_version >= 0 AND attempt_count >= 0),
  CONSTRAINT ck_outbox_event_body CHECK (
    jsonb_typeof(envelope_body) = 'object'
    AND envelope_sha256 ~ '^[0-9a-f]{64}$'
    AND envelope_sha256 = encode(
      sha256(convert_to(envelope_body::text, 'UTF8')), 'hex')
  ),
  CONSTRAINT ck_outbox_event_status CHECK (
    status IN ('PENDING', 'IN_FLIGHT', 'PUBLISHED', 'DLT', 'QUARANTINED')
  ),
  CONSTRAINT ck_outbox_event_topic CHECK (
    topic = 'rwms.task-board.' || replace(lower(aggregate_type), '_', '-') || '.v1'
  ),
  CONSTRAINT ck_outbox_event_lease CHECK (
    (status = 'IN_FLIGHT' AND lease_owner IS NOT NULL
      AND lease_token IS NOT NULL AND lease_until IS NOT NULL)
    OR
    (status <> 'IN_FLIGHT' AND lease_owner IS NULL
      AND lease_token IS NULL AND lease_until IS NULL)
  ),
  CONSTRAINT ck_outbox_event_published CHECK (
    (status = 'PUBLISHED') = (published_at IS NOT NULL)
  ),
  CONSTRAINT ck_outbox_event_dlt CHECK (
    (status = 'DLT') = (dlt_at IS NOT NULL)
  ),
  CONSTRAINT ck_outbox_event_error_code CHECK (
    last_error_code IS NULL OR last_error_code ~ '^[A-Z][A-Z0-9_]{0,63}$'
  )
);

CREATE INDEX idx_outbox_event_pending
  ON public.outbox_event(next_attempt_at, created_at, event_id)
  WHERE status = 'PENDING';
CREATE INDEX idx_outbox_event_expired_lease
  ON public.outbox_event(lease_until, created_at, event_id)
  WHERE status = 'IN_FLIGHT';

CREATE TABLE public.sanitized_dead_letter (
  dlt_id uuid NOT NULL,
  destination varchar(224) NOT NULL,
  message_sha256 char(64) NOT NULL,
  failure_code varchar(64) NOT NULL,
  safe_body jsonb NOT NULL,
  body_sha256 char(64) NOT NULL,
  status varchar(16) NOT NULL DEFAULT 'PENDING',
  attempt_count integer NOT NULL DEFAULT 0,
  next_attempt_at timestamptz NOT NULL,
  lease_owner varchar(128),
  lease_token uuid,
  lease_until timestamptz,
  created_at timestamptz NOT NULL,
  published_at timestamptz,
  CONSTRAINT sanitized_dead_letter_pkey PRIMARY KEY (dlt_id),
  CONSTRAINT ck_sanitized_dead_letter_destination CHECK (destination IN (
    'rwms.task-board.worker-class.v1.task-board-shadow-v1.dlt',
    'rwms.task-board.worker.v1.task-board-shadow-v1.dlt',
    'rwms.task-board.worker-group.v1.task-board-shadow-v1.dlt',
    'rwms.task-board.work-queue.v1.task-board-shadow-v1.dlt',
    'rwms.task-board.queue-usage-reference.v1.task-board-shadow-v1.dlt',
    'rwms.task-board.board-task.v1.task-board-shadow-v1.dlt',
    'rwms.task-board.queue-entry.v1.task-board-shadow-v1.dlt'
  )),
  CONSTRAINT ck_sanitized_dead_letter_failure_code
    CHECK (failure_code IN ('VALIDATION_REJECTED', 'PROCESSING_FAILED')),
  CONSTRAINT ck_sanitized_dead_letter_hashes CHECK (
    message_sha256 ~ '^[0-9a-f]{64}$'
    AND body_sha256 ~ '^[0-9a-f]{64}$'
    AND body_sha256 = encode(sha256(convert_to(safe_body::text, 'UTF8')), 'hex')
  ),
  CONSTRAINT ck_sanitized_dead_letter_safe_body CHECK (
    jsonb_typeof(safe_body) = 'object'
    AND safe_body ?& ARRAY['failureCode', 'messageSha256', 'recordedAt']
    AND safe_body - ARRAY['failureCode', 'messageSha256', 'recordedAt'] = '{}'::jsonb
    AND jsonb_typeof(safe_body->'failureCode') = 'string'
    AND jsonb_typeof(safe_body->'messageSha256') = 'string'
    AND jsonb_typeof(safe_body->'recordedAt') = 'string'
    AND safe_body->>'failureCode' = failure_code
    AND safe_body->>'messageSha256' = message_sha256
    AND safe_body->>'recordedAt'
      ~ '^[0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2}:[0-9]{2}(\.[0-9]+)?(Z|[+-][0-9]{2}:[0-9]{2})$'
    AND (safe_body->>'recordedAt')::timestamptz IS NOT NULL
  ),
  CONSTRAINT ck_sanitized_dead_letter_status CHECK (
    status IN ('PENDING', 'IN_FLIGHT', 'PUBLISHED', 'FAILED')
  ),
  CONSTRAINT ck_sanitized_dead_letter_attempts CHECK (attempt_count >= 0),
  CONSTRAINT ck_sanitized_dead_letter_lease CHECK (
    (status = 'IN_FLIGHT' AND lease_owner IS NOT NULL
      AND lease_token IS NOT NULL AND lease_until IS NOT NULL)
    OR
    (status <> 'IN_FLIGHT' AND lease_owner IS NULL
      AND lease_token IS NULL AND lease_until IS NULL)
  ),
  CONSTRAINT ck_sanitized_dead_letter_published CHECK (
    (status = 'PUBLISHED') = (published_at IS NOT NULL)
  )
);

CREATE INDEX idx_sanitized_dead_letter_pending
  ON public.sanitized_dead_letter(next_attempt_at, created_at, dlt_id)
  WHERE status = 'PENDING';
CREATE INDEX idx_sanitized_dead_letter_expired_lease
  ON public.sanitized_dead_letter(lease_until, created_at, dlt_id)
  WHERE status = 'IN_FLIGHT';

CREATE TABLE public.inbox_message (
  consumer_group varchar(128) NOT NULL,
  event_id uuid NOT NULL,
  aggregate_type varchar(64) NOT NULL,
  aggregate_id varchar(128) NOT NULL,
  aggregate_version bigint NOT NULL,
  payload_sha256 char(64) NOT NULL,
  status varchar(24) NOT NULL,
  attempt_count integer NOT NULL DEFAULT 0,
  received_at timestamptz NOT NULL,
  processed_at timestamptz,
  next_attempt_at timestamptz,
  dlt_at timestamptz,
  quarantine_reason varchar(128),
  CONSTRAINT inbox_message_pkey PRIMARY KEY (consumer_group, event_id),
  CONSTRAINT ck_inbox_message_type CHECK (aggregate_type IN (
    'WORKER_CLASS', 'WORKER', 'WORKER_GROUP', 'WORK_QUEUE',
    'QUEUE_USAGE_REFERENCE', 'BOARD_TASK', 'QUEUE_ENTRY'
  )),
  CONSTRAINT ck_inbox_message_version
    CHECK (aggregate_version >= 0 AND attempt_count >= 0),
  CONSTRAINT ck_inbox_message_hash CHECK (payload_sha256 ~ '^[0-9a-f]{64}$'),
  CONSTRAINT ck_inbox_message_status CHECK (
    status IN ('RECEIVED', 'PROCESSED', 'RETRY', 'DLT', 'QUARANTINED')
  )
);

CREATE INDEX idx_inbox_message_retry
  ON public.inbox_message(consumer_group, next_attempt_at, received_at)
  WHERE status = 'RETRY';

CREATE TABLE public.consumer_aggregate_checkpoint (
  consumer_group varchar(128) NOT NULL,
  aggregate_type varchar(64) NOT NULL,
  aggregate_id varchar(128) NOT NULL,
  last_event_id uuid,
  last_aggregate_version bigint NOT NULL,
  blocked boolean NOT NULL DEFAULT false,
  quarantine_reason varchar(128),
  updated_at timestamptz NOT NULL,
  CONSTRAINT consumer_aggregate_checkpoint_pkey
    PRIMARY KEY (consumer_group, aggregate_type, aggregate_id),
  CONSTRAINT ck_consumer_checkpoint_type CHECK (aggregate_type IN (
    'WORKER_CLASS', 'WORKER', 'WORKER_GROUP', 'WORK_QUEUE',
    'QUEUE_USAGE_REFERENCE', 'BOARD_TASK', 'QUEUE_ENTRY'
  )),
  CONSTRAINT ck_consumer_checkpoint_version CHECK (
    (last_aggregate_version = -1 AND last_event_id IS NULL)
    OR (last_aggregate_version >= 0 AND last_event_id IS NOT NULL)
  ),
  CONSTRAINT ck_consumer_checkpoint_blocked CHECK (
    (blocked AND quarantine_reason IS NOT NULL)
    OR (NOT blocked AND quarantine_reason IS NULL)
  )
);

CREATE TABLE public.version_gap_quarantine (
  quarantine_id uuid NOT NULL,
  consumer_group varchar(128) NOT NULL,
  aggregate_type varchar(64) NOT NULL,
  aggregate_id varchar(128) NOT NULL,
  expected_version bigint NOT NULL,
  received_version bigint NOT NULL,
  received_event_id uuid NOT NULL,
  payload_sha256 char(64) NOT NULL,
  reason_code varchar(128) NOT NULL,
  status varchar(24) NOT NULL DEFAULT 'OPEN',
  detected_at timestamptz NOT NULL,
  resolved_at timestamptz,
  resolution_reason varchar(500),
  resolved_by_subject_id uuid,
  CONSTRAINT version_gap_quarantine_pkey PRIMARY KEY (quarantine_id),
  CONSTRAINT uk_version_gap_quarantine_event
    UNIQUE (consumer_group, received_event_id),
  CONSTRAINT ck_version_gap_quarantine_type CHECK (aggregate_type IN (
    'WORKER_CLASS', 'WORKER', 'WORKER_GROUP', 'WORK_QUEUE',
    'QUEUE_USAGE_REFERENCE', 'BOARD_TASK', 'QUEUE_ENTRY'
  )),
  CONSTRAINT ck_version_gap_quarantine_versions CHECK (
    expected_version >= 0 AND received_version > expected_version
  ),
  CONSTRAINT ck_version_gap_quarantine_hash
    CHECK (payload_sha256 ~ '^[0-9a-f]{64}$'),
  CONSTRAINT ck_version_gap_quarantine_status
    CHECK (status IN ('OPEN', 'RESOLVED')),
  CONSTRAINT ck_version_gap_quarantine_reason_code
    CHECK (reason_code = 'AGGREGATE_VERSION_GAP'),
  CONSTRAINT ck_version_gap_quarantine_resolution CHECK (
    (status = 'OPEN' AND resolved_at IS NULL
      AND resolution_reason IS NULL AND resolved_by_subject_id IS NULL)
    OR
    (status = 'RESOLVED' AND resolved_at IS NOT NULL
      AND resolution_reason IS NOT NULL AND btrim(resolution_reason) <> ''
      AND resolved_by_subject_id IS NOT NULL)
  )
);

CREATE TABLE public.task_board_kafka_cutover_map (
  legacy_event_id uuid NOT NULL,
  kafka_event_id uuid NOT NULL,
  mapping_type varchar(40) NOT NULL,
  mapped_at timestamptz NOT NULL,
  CONSTRAINT task_board_kafka_cutover_map_pkey PRIMARY KEY (legacy_event_id),
  CONSTRAINT uk_task_board_kafka_cutover_map_kafka UNIQUE (kafka_event_id),
  CONSTRAINT fk_task_board_kafka_cutover_map_legacy
    FOREIGN KEY (legacy_event_id) REFERENCES public.task_board_outbox(event_id),
  CONSTRAINT fk_task_board_kafka_cutover_map_kafka
    FOREIGN KEY (kafka_event_id) REFERENCES public.outbox_event(event_id),
  CONSTRAINT ck_task_board_kafka_cutover_map_type
    CHECK (mapping_type = 'AUTHORITATIVE_EVENT_V2')
);

CREATE UNIQUE INDEX uk_version_gap_quarantine_open_aggregate
  ON public.version_gap_quarantine(consumer_group, aggregate_type, aggregate_id)
  WHERE status = 'OPEN';

CREATE TABLE public.replay_operation_audit (
  operation_id uuid NOT NULL,
  actor_subject_id uuid NOT NULL,
  reason varchar(500) NOT NULL,
  status varchar(16) NOT NULL,
  started_at timestamptz NOT NULL,
  completed_at timestamptz,
  before_aggregate_count integer NOT NULL,
  before_version_sum bigint NOT NULL,
  before_checksum char(64) NOT NULL,
  after_aggregate_count integer,
  after_version_sum bigint,
  after_checksum char(64),
  failure_code varchar(64),
  CONSTRAINT replay_operation_audit_pkey PRIMARY KEY (operation_id),
  CONSTRAINT ck_replay_operation_audit_reason CHECK (
    btrim(reason) <> '' AND reason = btrim(reason) AND reason !~ '[[:cntrl:]]'
  ),
  CONSTRAINT ck_replay_operation_audit_status
    CHECK (status IN ('STARTED', 'COMPLETED', 'FAILED')),
  CONSTRAINT ck_replay_operation_audit_before CHECK (
    before_aggregate_count >= 0 AND before_version_sum >= 0
    AND before_checksum ~ '^[0-9a-f]{64}$'
  ),
  CONSTRAINT ck_replay_operation_audit_terminal CHECK (
    (status = 'STARTED' AND completed_at IS NULL
      AND after_aggregate_count IS NULL AND after_version_sum IS NULL
      AND after_checksum IS NULL AND failure_code IS NULL)
    OR
    (status = 'COMPLETED' AND completed_at IS NOT NULL
      AND completed_at >= started_at AND after_aggregate_count >= 0
      AND after_version_sum >= 0 AND after_checksum ~ '^[0-9a-f]{64}$'
      AND failure_code IS NULL)
    OR
    (status = 'FAILED' AND completed_at IS NOT NULL
      AND completed_at >= started_at
      AND after_aggregate_count IS NULL AND after_version_sum IS NULL
      AND after_checksum IS NULL AND failure_code IN (
        'BLOCKED_AGGREGATE', 'STREAM_VALIDATION_FAILED',
        'PARITY_MISMATCH', 'REPLAY_FAILED'))
  )
);

WITH migration_clock AS (
  SELECT clock_timestamp() AS recorded_at
), baseline AS (
  SELECT 'WORKER_CLASS'::varchar AS aggregate_type, id, version
    FROM public.worker_class
  UNION ALL
  SELECT 'WORKER', id, version FROM public.worker
  UNION ALL
  SELECT 'WORKER_GROUP', id, version FROM public.worker_group
  UNION ALL
  SELECT 'WORK_QUEUE', id, version FROM public.work_queue
  UNION ALL
  SELECT 'QUEUE_USAGE_REFERENCE', id, version FROM public.queue_usage_reference
  UNION ALL
  SELECT 'BOARD_TASK', id, version FROM public.board_task
  UNION ALL
  SELECT 'QUEUE_ENTRY', id, version FROM public.queue_entry
)
INSERT INTO public.event_stream_head(
  aggregate_type, aggregate_id, current_version, last_event_id, updated_at)
SELECT
  aggregate_type,
  id::text,
  version,
  md5(aggregate_type || ':' || id::text || ':' || version::text || ':baseline.v1')::uuid,
  migration_clock.recorded_at
FROM baseline CROSS JOIN migration_clock
ON CONFLICT (aggregate_type, aggregate_id) DO NOTHING;

WITH baseline AS (
  SELECT
    'WORKER_CLASS'::varchar AS aggregate_type,
    item.id,
    item.version,
    jsonb_build_object(
      'workerClassId', item.id,
      'revisionMarker', item.revision_marker,
      'code', item.code,
      'sortOrder', item.sort_order,
      'active', item.active,
      'deleted', false) AS payload
  FROM public.worker_class item

  UNION ALL

  SELECT
    'WORKER',
    item.id,
    item.version,
    jsonb_build_object(
      'workerId', item.id,
      'warehouseId', item.warehouse_id,
      'active', item.active,
      'profileRevision', item.revision_marker,
      'qualifications', COALESCE((
        SELECT jsonb_agg(jsonb_build_object(
          'assignmentId', assignment.id,
          'version', assignment.version,
          'workerClassId', assignment.worker_class_id,
          'active', assignment.active)
          ORDER BY assignment.id)
        FROM public.worker_class_assignment assignment
        WHERE assignment.worker_id = item.id
      ), '[]'::jsonb),
      'deleted', false)
  FROM public.worker item

  UNION ALL

  SELECT
    'WORKER_GROUP',
    item.id,
    item.version,
    jsonb_build_object(
      'workerGroupId', item.id,
      'revisionMarker', item.revision_marker,
      'warehouseId', item.warehouse_id,
      'workerClassId', item.worker_class_id,
      'active', item.active,
      'members', COALESCE((
        SELECT jsonb_agg(jsonb_build_object(
          'membershipId', member.id,
          'version', member.version,
          'workerId', member.worker_id,
          'active', member.active)
          ORDER BY member.id)
        FROM public.worker_group_member member
        WHERE member.worker_group_id = item.id
      ), '[]'::jsonb),
      'deleted', false)
  FROM public.worker_group item

  UNION ALL

  SELECT
    'WORK_QUEUE',
    item.id,
    item.version,
    jsonb_build_object(
      'workQueueId', item.id,
      'revisionMarker', item.revision_marker,
      'warehouseId', item.warehouse_id,
      'code', item.code,
      'queueType', item.queue_type,
      'sortOrder', item.sort_order,
      'active', item.active,
      'hidden', item.hidden,
      'collapsed', item.collapsed,
      'holdingPeriodMinutes', item.holding_period_minutes,
      'notificationThreshold', item.notification_threshold,
      'notifyWhenThresholdReached', item.notify_when_threshold_reached,
      'classBindings', COALESCE((
        SELECT jsonb_agg(jsonb_build_object(
          'bindingId', binding.id,
          'version', binding.version,
          'workerClassId', binding.worker_class_id,
          'stopTaskOnTake', binding.stop_task_on_take)
          ORDER BY binding.id)
        FROM public.work_queue_class_binding binding
        WHERE binding.queue_id = item.id
      ), '[]'::jsonb),
      'deleted', false)
  FROM public.work_queue item

  UNION ALL

  SELECT
    'QUEUE_USAGE_REFERENCE',
    item.id,
    item.version,
    jsonb_build_object(
      'queueUsageReferenceId', item.id,
      'revisionMarker', item.revision_marker,
      'queueId', item.queue_id,
      'referenceType', item.reference_type,
      'externalReferenceHash', encode(sha256(
        convert_to('task-board-queue-reference:v1', 'UTF8')
        || decode('00', 'hex')
        || convert_to(item.external_reference_id, 'UTF8')), 'hex'),
      'deleted', false)
  FROM public.queue_usage_reference item

  UNION ALL

  SELECT
    'BOARD_TASK',
    item.id,
    item.version,
    jsonb_build_object(
      'boardTaskId', item.id,
      'warehouseId', item.warehouse_id,
      'externalTaskId', item.external_task_id,
      'status', item.status,
      'plannedDurationMinutes', item.planned_duration_minutes,
      'deadlineAt', CASE WHEN item.deadline_at IS NULL THEN NULL ELSE
        replace((item.deadline_at AT TIME ZONE 'UTC')::text, ' ', 'T') || 'Z' END,
      'doneAt', CASE WHEN item.done_at IS NULL THEN NULL ELSE
        replace((item.done_at AT TIME ZONE 'UTC')::text, ' ', 'T') || 'Z' END,
      'deleted', false)
  FROM public.board_task item

  UNION ALL

  SELECT
    'QUEUE_ENTRY',
    item.id,
    item.version,
    jsonb_build_object(
      'queueEntryId', item.id,
      'taskId', item.task_id,
      'queueId', item.queue_id,
      'queueCode', item.queue_code,
      'routeIndex', item.route_index,
      'queuePosition', item.queue_position,
      'entryType', item.entry_type,
      'status', item.status,
      'plannedDurationMinutes', item.planned_duration_minutes,
      'activeStartedAt', CASE WHEN item.active_started_at IS NULL THEN NULL ELSE
        replace((item.active_started_at AT TIME ZONE 'UTC')::text, ' ', 'T') || 'Z' END,
      'pausedAt', CASE WHEN item.paused_at IS NULL THEN NULL ELSE
        replace((item.paused_at AT TIME ZONE 'UTC')::text, ' ', 'T') || 'Z' END,
      'doneAt', CASE WHEN item.done_at IS NULL THEN NULL ELSE
        replace((item.done_at AT TIME ZONE 'UTC')::text, ' ', 'T') || 'Z' END,
      'activeWorkSeconds', item.active_work_seconds,
      'pauseOrigin', item.pause_origin,
      'assignments', COALESCE((
        SELECT jsonb_agg(jsonb_build_object(
          'assignmentId', assignment.id,
          'version', assignment.version,
          'workerGroupId', assignment.worker_group_id,
          'workerId', assignment.worker_id,
          'status', assignment.status,
          'assignedAt', CASE WHEN assignment.assigned_at IS NULL THEN NULL ELSE
            replace((assignment.assigned_at AT TIME ZONE 'UTC')::text, ' ', 'T') || 'Z' END,
          'startedAt', CASE WHEN assignment.started_at IS NULL THEN NULL ELSE
            replace((assignment.started_at AT TIME ZONE 'UTC')::text, ' ', 'T') || 'Z' END,
          'pausedAt', CASE WHEN assignment.paused_at IS NULL THEN NULL ELSE
            replace((assignment.paused_at AT TIME ZONE 'UTC')::text, ' ', 'T') || 'Z' END,
          'finishedAt', CASE WHEN assignment.finished_at IS NULL THEN NULL ELSE
            replace((assignment.finished_at AT TIME ZONE 'UTC')::text, ' ', 'T') || 'Z' END)
          ORDER BY assignment.id)
        FROM public.task_assignment assignment
        WHERE assignment.queue_entry_id = item.id
      ), '[]'::jsonb),
      'timeEvents', COALESCE((
        SELECT jsonb_agg(jsonb_build_object(
          'timeEventId', time_event.id,
          'version', time_event.version,
          'workerId', time_event.worker_id,
          'eventType', time_event.event_type,
          'createdAt',
            replace((time_event.created_at AT TIME ZONE 'UTC')::text, ' ', 'T') || 'Z',
          'relatedEntryId', time_event.related_entry_id)
          ORDER BY time_event.id)
        FROM public.task_time_event time_event
        WHERE time_event.queue_entry_id = item.id
      ), '[]'::jsonb),
      'interruptions', COALESCE((
        SELECT jsonb_agg(jsonb_build_object(
          'interruptionId', interruption.id,
          'version', interruption.version,
          'workerId', interruption.worker_id,
          'interruptedEntryId', interruption.interrupted_entry_id,
          'interruptingEntryId', interruption.interrupting_entry_id,
          'active', interruption.active,
          'createdAt',
            replace((interruption.created_at AT TIME ZONE 'UTC')::text, ' ', 'T') || 'Z',
          'resolvedAt', CASE WHEN interruption.resolved_at IS NULL THEN NULL ELSE
            replace((interruption.resolved_at AT TIME ZONE 'UTC')::text, ' ', 'T') || 'Z' END)
          ORDER BY interruption.id)
        FROM public.task_auto_interruption interruption
        WHERE interruption.interrupted_entry_id = item.id
           OR interruption.interrupting_entry_id = item.id
      ), '[]'::jsonb),
      'deleted', false)
  FROM public.queue_entry item
)
INSERT INTO public.domain_event(
  event_id, aggregate_type, aggregate_id, aggregate_version,
  event_type, event_version, occurred_at, recorded_at,
  correlation_id, causation_id, actor_ref, payload, payload_sha256, baseline)
SELECT
  head.last_event_id,
  baseline.aggregate_type,
  baseline.id::text,
  baseline.version,
  'task-board.' || replace(lower(baseline.aggregate_type), '_', '-') || '.baseline.v1',
  1,
  NULL,
  head.updated_at,
  md5(head.last_event_id::text || ':correlation')::uuid,
  NULL,
  NULL,
  baseline.payload,
  encode(sha256(convert_to(baseline.payload::text, 'UTF8')), 'hex'),
  true
FROM baseline
JOIN public.event_stream_head head
  ON head.aggregate_type = baseline.aggregate_type
 AND head.aggregate_id = baseline.id::text
ON CONFLICT (event_id) DO NOTHING;

INSERT INTO public.projection_checkpoint(
  projection_name, aggregate_type, aggregate_id,
  aggregate_version, projection_sha256, updated_at)
SELECT
  'task-board-live-v1', aggregate_type, aggregate_id,
  aggregate_version, payload_sha256, recorded_at
FROM public.domain_event
WHERE baseline
ON CONFLICT (projection_name, aggregate_type, aggregate_id) DO NOTHING;

CREATE FUNCTION public.reject_domain_event_mutation()
RETURNS trigger
LANGUAGE plpgsql
AS $$
BEGIN
  RAISE EXCEPTION 'domain_event is append-only';
END;
$$;

CREATE TRIGGER trg_domain_event_append_only
BEFORE UPDATE OR DELETE ON public.domain_event
FOR EACH ROW EXECUTE FUNCTION public.reject_domain_event_mutation();

CREATE TRIGGER trg_domain_event_no_truncate
BEFORE TRUNCATE ON public.domain_event
FOR EACH STATEMENT EXECUTE FUNCTION public.reject_domain_event_mutation();

CREATE FUNCTION public.reject_task_board_kafka_cutover_map_mutation()
RETURNS trigger
LANGUAGE plpgsql
AS $$
BEGIN
  RAISE EXCEPTION 'task_board_kafka_cutover_map is append-only';
END;
$$;

CREATE TRIGGER trg_task_board_kafka_cutover_map_append_only
BEFORE UPDATE OR DELETE ON public.task_board_kafka_cutover_map
FOR EACH ROW EXECUTE FUNCTION public.reject_task_board_kafka_cutover_map_mutation();

CREATE TRIGGER trg_task_board_kafka_cutover_map_no_truncate
BEFORE TRUNCATE ON public.task_board_kafka_cutover_map
FOR EACH STATEMENT EXECUTE FUNCTION public.reject_task_board_kafka_cutover_map_mutation();

CREATE FUNCTION public.enforce_outbox_immutable_metadata()
RETURNS trigger
LANGUAGE plpgsql
AS $$
BEGIN
  IF NEW.event_id IS DISTINCT FROM OLD.event_id
      OR NEW.aggregate_type IS DISTINCT FROM OLD.aggregate_type
      OR NEW.aggregate_id IS DISTINCT FROM OLD.aggregate_id
      OR NEW.aggregate_version IS DISTINCT FROM OLD.aggregate_version
      OR NEW.event_type IS DISTINCT FROM OLD.event_type
      OR NEW.topic IS DISTINCT FROM OLD.topic
      OR NEW.envelope_body IS DISTINCT FROM OLD.envelope_body
      OR NEW.envelope_sha256 IS DISTINCT FROM OLD.envelope_sha256
      OR NEW.created_at IS DISTINCT FROM OLD.created_at THEN
    RAISE EXCEPTION 'outbox event metadata is immutable';
  END IF;
  RETURN NEW;
END;
$$;

CREATE TRIGGER trg_outbox_event_immutable_metadata
BEFORE UPDATE ON public.outbox_event
FOR EACH ROW EXECUTE FUNCTION public.enforce_outbox_immutable_metadata();

CREATE FUNCTION public.enforce_outbox_domain_event_parity()
RETURNS trigger
LANGUAGE plpgsql
AS $$
BEGIN
  IF NOT EXISTS (
    SELECT 1
    FROM public.domain_event event
    WHERE event.event_id = NEW.event_id
      AND event.aggregate_type = NEW.aggregate_type
      AND event.aggregate_id = NEW.aggregate_id
      AND event.aggregate_version = NEW.aggregate_version
      AND event.event_type = NEW.event_type
      AND NOT event.baseline
      AND NEW.envelope_body ?& ARRAY[
        'envelopeVersion', 'eventId', 'eventType', 'eventVersion',
        'occurredAt', 'recordedAt', 'producer', 'aggregateType',
        'aggregateId', 'aggregateVersion', 'correlation', 'actorRef', 'payload']
      AND NEW.envelope_body - ARRAY[
        'envelopeVersion', 'eventId', 'eventType', 'eventVersion',
        'occurredAt', 'recordedAt', 'producer', 'aggregateType',
        'aggregateId', 'aggregateVersion', 'correlation', 'actorRef', 'payload'] = '{}'::jsonb
      AND NEW.envelope_body->>'envelopeVersion' = '2'
      AND NEW.envelope_body->>'eventId' = event.event_id::text
      AND NEW.envelope_body->>'eventType' = event.event_type
      AND (NEW.envelope_body->>'eventVersion')::integer = event.event_version
      AND (NEW.envelope_body->>'occurredAt')::timestamptz = event.occurred_at
      AND (NEW.envelope_body->>'recordedAt')::timestamptz = event.recorded_at
      AND NEW.envelope_body->>'producer' = 'task-board-service'
      AND NEW.envelope_body->>'aggregateType' = event.aggregate_type
      AND NEW.envelope_body->>'aggregateId' = event.aggregate_id
      AND (NEW.envelope_body->>'aggregateVersion')::bigint = event.aggregate_version
      AND jsonb_typeof(NEW.envelope_body->'correlation') = 'object'
      AND (NEW.envelope_body->'correlation') ?& ARRAY['correlationId', 'causationId']
      AND (NEW.envelope_body->'correlation') - ARRAY['correlationId', 'causationId'] = '{}'::jsonb
      AND (NEW.envelope_body->'correlation'->>'correlationId')::uuid = event.correlation_id
      AND (NEW.envelope_body->'correlation'->>'causationId')::uuid
        IS NOT DISTINCT FROM event.causation_id
      AND NEW.envelope_body->'actorRef' = COALESCE(event.actor_ref, 'null'::jsonb)
      AND NEW.envelope_body->'payload' = event.payload
      AND event.payload_sha256 = encode(
        sha256(convert_to(event.payload::text, 'UTF8')), 'hex')
  ) THEN
    RAISE EXCEPTION 'outbox envelope does not match authoritative domain event';
  END IF;
  RETURN NEW;
END;
$$;

CREATE TRIGGER trg_outbox_event_domain_parity
BEFORE INSERT OR UPDATE OF
  event_id, aggregate_type, aggregate_id, aggregate_version,
  event_type, topic, envelope_body, envelope_sha256
ON public.outbox_event
FOR EACH ROW EXECUTE FUNCTION public.enforce_outbox_domain_event_parity();

CREATE FUNCTION public.enforce_sanitized_dead_letter_immutable_metadata()
RETURNS trigger
LANGUAGE plpgsql
AS $$
BEGIN
  IF NEW.dlt_id IS DISTINCT FROM OLD.dlt_id
      OR NEW.destination IS DISTINCT FROM OLD.destination
      OR NEW.message_sha256 IS DISTINCT FROM OLD.message_sha256
      OR NEW.failure_code IS DISTINCT FROM OLD.failure_code
      OR NEW.safe_body IS DISTINCT FROM OLD.safe_body
      OR NEW.body_sha256 IS DISTINCT FROM OLD.body_sha256
      OR NEW.created_at IS DISTINCT FROM OLD.created_at THEN
    RAISE EXCEPTION 'sanitized dead letter metadata is immutable';
  END IF;
  RETURN NEW;
END;
$$;

CREATE TRIGGER trg_sanitized_dead_letter_immutable_metadata
BEFORE UPDATE ON public.sanitized_dead_letter
FOR EACH ROW EXECUTE FUNCTION public.enforce_sanitized_dead_letter_immutable_metadata();

CREATE FUNCTION public.enforce_replay_operation_audit_start()
RETURNS trigger
LANGUAGE plpgsql
AS $$
BEGIN
  IF NEW.status <> 'STARTED' THEN
    RAISE EXCEPTION 'replay operation audit must begin in STARTED status';
  END IF;
  RETURN NEW;
END;
$$;

CREATE TRIGGER trg_replay_operation_audit_start
BEFORE INSERT ON public.replay_operation_audit
FOR EACH ROW EXECUTE FUNCTION public.enforce_replay_operation_audit_start();

CREATE FUNCTION public.enforce_replay_operation_audit_lifecycle()
RETURNS trigger
LANGUAGE plpgsql
AS $$
BEGIN
  IF NEW.operation_id IS DISTINCT FROM OLD.operation_id
      OR NEW.actor_subject_id IS DISTINCT FROM OLD.actor_subject_id
      OR NEW.reason IS DISTINCT FROM OLD.reason
      OR NEW.started_at IS DISTINCT FROM OLD.started_at
      OR NEW.before_aggregate_count IS DISTINCT FROM OLD.before_aggregate_count
      OR NEW.before_version_sum IS DISTINCT FROM OLD.before_version_sum
      OR NEW.before_checksum IS DISTINCT FROM OLD.before_checksum
      OR OLD.status <> 'STARTED'
      OR NEW.status NOT IN ('COMPLETED', 'FAILED') THEN
    RAISE EXCEPTION 'replay operation audit is append-only after one terminal transition';
  END IF;
  RETURN NEW;
END;
$$;

CREATE TRIGGER trg_replay_operation_audit_lifecycle
BEFORE UPDATE ON public.replay_operation_audit
FOR EACH ROW EXECUTE FUNCTION public.enforce_replay_operation_audit_lifecycle();

CREATE FUNCTION public.reject_replay_operation_audit_removal()
RETURNS trigger
LANGUAGE plpgsql
AS $$
BEGIN
  RAISE EXCEPTION 'replay operation audit cannot be removed';
END;
$$;

CREATE TRIGGER trg_replay_operation_audit_no_delete
BEFORE DELETE ON public.replay_operation_audit
FOR EACH ROW EXECUTE FUNCTION public.reject_replay_operation_audit_removal();

CREATE TRIGGER trg_replay_operation_audit_no_truncate
BEFORE TRUNCATE ON public.replay_operation_audit
FOR EACH STATEMENT EXECUTE FUNCTION public.reject_replay_operation_audit_removal();
