-- Replace warehouse-selected GENERAL queue bindings with one authoritative
-- system-wide repair-table template. LOGISTICS_DRIVER work queues remain
-- warehouse-owned and are deliberately excluded from every synchronization.

ALTER TABLE public.queue_definition
  ADD COLUMN sort_order integer NOT NULL DEFAULT 0,
  ADD COLUMN active boolean NOT NULL DEFAULT true,
  ADD COLUMN hidden boolean NOT NULL DEFAULT false,
  ADD COLUMN collapsed boolean NOT NULL DEFAULT false,
  ADD COLUMN holding_period_minutes integer,
  ADD COLUMN notification_threshold integer,
  ADD COLUMN notify_when_threshold_reached boolean NOT NULL DEFAULT false,
  ADD COLUMN result_photo_min_count integer NOT NULL DEFAULT 1;

-- SPB is the reviewed source for each definition when it has that queue.
-- Otherwise the first warehouse/queue UUID is deterministic.
WITH reviewed_source AS (
  SELECT DISTINCT ON (queue.definition_id)
         queue.definition_id,
         queue.sort_order,
         queue.active,
         queue.hidden,
         queue.collapsed,
         queue.holding_period_minutes,
         queue.notification_threshold,
         queue.notify_when_threshold_reached,
         queue.result_photo_min_count
    FROM public.work_queue queue
   ORDER BY
         queue.definition_id,
         CASE
           WHEN queue.warehouse_id =
                '00000000-0000-0000-0000-000000000001'::uuid
             THEN 0
           ELSE 1
         END,
         queue.warehouse_id,
         queue.id
)
UPDATE public.queue_definition definition
   SET sort_order = source.sort_order,
       active = source.active,
       hidden = source.hidden,
       collapsed = source.collapsed,
       holding_period_minutes =
         CASE
           WHEN definition.queue_type = 'HOLDING'
             THEN source.holding_period_minutes
           ELSE NULL
         END,
       notification_threshold =
         CASE
           WHEN definition.queue_type = 'HOLDING'
             THEN source.notification_threshold
           ELSE NULL
         END,
       notify_when_threshold_reached =
         definition.queue_type = 'HOLDING'
         AND source.notify_when_threshold_reached,
       result_photo_min_count = source.result_photo_min_count
  FROM reviewed_source source
 WHERE source.definition_id = definition.id;

-- Global order is compact and stable. Holding tables remain terminal; within
-- each partition the reviewed order, names and UUIDs are deterministic.
WITH ordered AS (
  SELECT definition.id,
         row_number() OVER (
           ORDER BY
                    CASE
                      WHEN definition.queue_type = 'HOLDING' THEN 1
                      ELSE 0
                    END,
                    definition.sort_order,
                    definition.normalized_name,
                    definition.queue_type,
                    definition.id
         ) * 10 AS normalized_order
    FROM public.queue_definition definition
   WHERE definition.queue_purpose = 'GENERAL'
)
UPDATE public.queue_definition definition
   SET sort_order = ordered.normalized_order
  FROM ordered
 WHERE ordered.id = definition.id;

ALTER TABLE public.queue_definition
  ADD CONSTRAINT ck_queue_definition_sort_order
    CHECK (sort_order >= 0),
  ADD CONSTRAINT ck_queue_definition_holding
    CHECK (
      queue_type = 'HOLDING'
      OR (
        holding_period_minutes IS NULL
        AND notification_threshold IS NULL
        AND notify_when_threshold_reached = false
      )
    ),
  ADD CONSTRAINT ck_queue_definition_holding_values
    CHECK (
      (holding_period_minutes IS NULL OR holding_period_minutes >= 0)
      AND (notification_threshold IS NULL OR notification_threshold >= 0)
    ),
  ADD CONSTRAINT ck_queue_definition_result_photos
    CHECK (result_photo_min_count BETWEEN 0 AND 20);

CREATE INDEX idx_queue_definition_global_order
  ON public.queue_definition(
    queue_purpose, sort_order, normalized_name, queue_type, id);

CREATE TABLE public.queue_definition_class_binding (
  id uuid PRIMARY KEY,
  version bigint NOT NULL DEFAULT 0,
  definition_id uuid NOT NULL,
  worker_class_id uuid NOT NULL,
  stop_task_on_take boolean NOT NULL DEFAULT false,
  binding_order integer NOT NULL,
  participation_policy varchar(16) NOT NULL,
  notify_on_primary_take boolean NOT NULL DEFAULT false,
  CONSTRAINT fk_queue_definition_binding_definition
    FOREIGN KEY (definition_id) REFERENCES public.queue_definition(id),
  CONSTRAINT fk_queue_definition_binding_class
    FOREIGN KEY (worker_class_id) REFERENCES public.worker_class(id),
  CONSTRAINT uk_queue_definition_binding
    UNIQUE (definition_id, worker_class_id),
  CONSTRAINT uk_queue_definition_binding_order
    UNIQUE (definition_id, binding_order),
  CONSTRAINT ck_queue_definition_binding_order
    CHECK (binding_order >= 0),
  CONSTRAINT ck_queue_definition_binding_participation
    CHECK (participation_policy IN ('PRIMARY', 'REQUIRED', 'OPTIONAL')),
  CONSTRAINT ck_queue_definition_binding_primary_order
    CHECK (
      (binding_order = 0 AND participation_policy = 'PRIMARY')
      OR
      (binding_order > 0 AND participation_policy <> 'PRIMARY')
    ),
  CONSTRAINT ck_queue_definition_binding_primary_notification
    CHECK (
      participation_policy <> 'PRIMARY'
      OR notify_on_primary_take = false
    )
);

WITH reviewed_queue AS (
  SELECT DISTINCT ON (queue.definition_id)
         queue.definition_id,
         queue.id AS queue_id
    FROM public.work_queue queue
    JOIN public.queue_definition definition
      ON definition.id = queue.definition_id
   WHERE definition.queue_purpose = 'GENERAL'
   ORDER BY
         queue.definition_id,
         CASE
           WHEN queue.warehouse_id =
                '00000000-0000-0000-0000-000000000001'::uuid
             THEN 0
           ELSE 1
         END,
         queue.warehouse_id,
         queue.id
)
INSERT INTO public.queue_definition_class_binding(
  id,
  version,
  definition_id,
  worker_class_id,
  stop_task_on_take,
  binding_order,
  participation_policy,
  notify_on_primary_take)
SELECT
  md5(
    reviewed.definition_id::text
    || ':'
    || binding.worker_class_id::text
    || ':global-binding:v23'
  )::uuid,
  0,
  reviewed.definition_id,
  binding.worker_class_id,
  binding.stop_task_on_take,
  binding.binding_order,
  binding.participation_policy,
  binding.notify_on_primary_take
FROM reviewed_queue reviewed
JOIN public.work_queue_class_binding binding
  ON binding.queue_id = reviewed.queue_id
ORDER BY reviewed.definition_id, binding.binding_order, binding.id;

-- Synchronize every existing GENERAL projection while preserving work_queue
-- identities referenced by tasks, queue history and maintenance routing.
UPDATE public.work_queue queue
   SET sort_order = definition.sort_order,
       active = definition.active,
       hidden = definition.hidden,
       collapsed = definition.collapsed,
       holding_period_minutes = definition.holding_period_minutes,
       notification_threshold = definition.notification_threshold,
       notify_when_threshold_reached =
         definition.notify_when_threshold_reached,
       result_photo_min_count = definition.result_photo_min_count,
       revision_marker =
         md5(queue.id::text || ':global-repair-template:v23')::uuid
  FROM public.queue_definition definition
 WHERE definition.id = queue.definition_id
   AND definition.queue_purpose = 'GENERAL';

CREATE TEMP TABLE v23_missing_general_queue
ON COMMIT DROP
AS
SELECT
  md5(
    warehouse.id::text
    || ':'
    || definition.id::text
    || ':global-work-queue:v23'
  )::uuid AS id,
  warehouse.id AS warehouse_id,
  definition.id AS definition_id
FROM public.warehouse_metadata warehouse
CROSS JOIN public.queue_definition definition
WHERE warehouse.active
  AND definition.queue_purpose = 'GENERAL'
  AND NOT EXISTS (
    SELECT 1
      FROM public.work_queue queue
     WHERE queue.warehouse_id = warehouse.id
       AND queue.definition_id = definition.id
  );

INSERT INTO public.work_queue(
  id,
  version,
  revision_marker,
  warehouse_id,
  definition_id,
  sort_order,
  active,
  hidden,
  collapsed,
  holding_period_minutes,
  notification_threshold,
  notify_when_threshold_reached,
  result_photo_min_count)
SELECT
  missing.id,
  0,
  md5(missing.id::text || ':global-repair-template:v23')::uuid,
  missing.warehouse_id,
  missing.definition_id,
  definition.sort_order,
  definition.active,
  definition.hidden,
  definition.collapsed,
  definition.holding_period_minutes,
  definition.notification_threshold,
  definition.notify_when_threshold_reached,
  definition.result_photo_min_count
FROM v23_missing_general_queue missing
JOIN public.queue_definition definition
  ON definition.id = missing.definition_id
ORDER BY missing.warehouse_id, definition.sort_order, definition.id;

-- Keep binding UUIDs where the reviewed class remains attached to a queue.
-- Bindings are not independently selected or edited after this cutover.
CREATE TEMP TABLE v23_existing_general_binding
ON COMMIT DROP
AS
SELECT binding.*
FROM public.work_queue_class_binding binding
JOIN public.work_queue queue ON queue.id = binding.queue_id
JOIN public.queue_definition definition
  ON definition.id = queue.definition_id
WHERE definition.queue_purpose = 'GENERAL';

DELETE FROM public.work_queue_class_binding binding
USING public.work_queue queue, public.queue_definition definition
WHERE binding.queue_id = queue.id
  AND definition.id = queue.definition_id
  AND definition.queue_purpose = 'GENERAL';

INSERT INTO public.work_queue_class_binding(
  id,
  version,
  queue_id,
  worker_class_id,
  stop_task_on_take,
  binding_order,
  participation_policy,
  notify_on_primary_take)
SELECT
  COALESCE(
    existing.id,
    md5(
      queue.id::text
      || ':'
      || template.worker_class_id::text
      || ':derived-binding:v23'
    )::uuid
  ),
  COALESCE(existing.version, 0),
  queue.id,
  template.worker_class_id,
  template.stop_task_on_take,
  template.binding_order,
  template.participation_policy,
  template.notify_on_primary_take
FROM public.work_queue queue
JOIN public.queue_definition definition
  ON definition.id = queue.definition_id
JOIN public.queue_definition_class_binding template
  ON template.definition_id = definition.id
LEFT JOIN v23_existing_general_binding existing
  ON existing.queue_id = queue.id
 AND existing.worker_class_id = template.worker_class_id
WHERE definition.queue_purpose = 'GENERAL'
ORDER BY queue.id, template.binding_order, template.id;

-- The projection rewrite below keeps the event store/checksums exact after
-- the reviewed template replaced warehouse-local GENERAL settings. This is
-- the same audited expand/contract treatment used by V20/V21; queue versions
-- and event stream versions remain unchanged.
CREATE FUNCTION public.canonicalize_global_work_queue_fact_v23(
  fact_id varchar,
  fact_payload jsonb)
RETURNS jsonb
LANGUAGE plpgsql
STABLE
AS $$
DECLARE
  queue_row public.work_queue%ROWTYPE;
  definition_row public.queue_definition%ROWTYPE;
  deleted_value boolean;
  canonical_bindings jsonb;
BEGIN
  SELECT queue.*
    INTO queue_row
    FROM public.work_queue queue
   WHERE queue.id::text = fact_id;
  IF queue_row.id IS NULL THEN
    RETURN fact_payload;
  END IF;

  SELECT definition.*
    INTO definition_row
    FROM public.queue_definition definition
   WHERE definition.id = queue_row.definition_id;
  deleted_value := COALESCE((fact_payload->>'deleted')::boolean, false);

  SELECT COALESCE(
           jsonb_agg(
             jsonb_build_object(
               'bindingId', binding.id,
               'version', binding.version,
               'workerClassId', binding.worker_class_id,
               'bindingOrder', binding.binding_order,
               'stopTaskOnTake', binding.stop_task_on_take,
               'participationPolicy', binding.participation_policy,
               'notifyOnPrimaryTake', binding.notify_on_primary_take
             )
             ORDER BY binding.binding_order, binding.id
           ),
           '[]'::jsonb
         )
    INTO canonical_bindings
    FROM public.work_queue_class_binding binding
   WHERE binding.queue_id = queue_row.id;

  RETURN jsonb_build_object(
    'workQueueId', queue_row.id,
    'revisionMarker', queue_row.revision_marker,
    'warehouseId', queue_row.warehouse_id,
    'queueDefinitionId', queue_row.definition_id,
    'queueType', definition_row.queue_type,
    'queuePurpose', definition_row.queue_purpose,
    'sortOrder', queue_row.sort_order,
    'active', queue_row.active,
    'hidden', queue_row.hidden,
    'collapsed', queue_row.collapsed,
    'holdingPeriodMinutes', queue_row.holding_period_minutes,
    'notificationThreshold', queue_row.notification_threshold,
    'notifyWhenThresholdReached',
      queue_row.notify_when_threshold_reached,
    'resultPhotoMinCount', queue_row.result_photo_min_count,
    'classBindings', canonical_bindings,
    'deleted', deleted_value
  );
END
$$;

ALTER TABLE public.domain_event DISABLE TRIGGER USER;
ALTER TABLE public.outbox_event DISABLE TRIGGER USER;

WITH canonical AS (
  SELECT
    event.event_id,
    public.canonicalize_global_work_queue_fact_v23(
      event.aggregate_id,
      event.payload
    ) AS payload
  FROM public.domain_event event
  WHERE event.aggregate_type = 'WORK_QUEUE'
)
UPDATE public.domain_event event
   SET payload = canonical.payload,
       payload_sha256 = encode(
         sha256(convert_to(canonical.payload::text, 'UTF8')),
         'hex')
  FROM canonical
 WHERE event.event_id = canonical.event_id
   AND event.payload IS DISTINCT FROM canonical.payload;

WITH canonical AS (
  SELECT
    snapshot.aggregate_type,
    snapshot.aggregate_id,
    snapshot.aggregate_version,
    public.canonicalize_global_work_queue_fact_v23(
      snapshot.aggregate_id,
      snapshot.state
    ) AS state
  FROM public.aggregate_snapshot snapshot
  WHERE snapshot.aggregate_type = 'WORK_QUEUE'
)
UPDATE public.aggregate_snapshot snapshot
   SET state = canonical.state,
       state_sha256 = encode(
         sha256(convert_to(canonical.state::text, 'UTF8')),
         'hex')
  FROM canonical
 WHERE snapshot.aggregate_type = canonical.aggregate_type
   AND snapshot.aggregate_id = canonical.aggregate_id
   AND snapshot.aggregate_version = canonical.aggregate_version
   AND snapshot.state IS DISTINCT FROM canonical.state;

UPDATE public.outbox_event outbox
   SET envelope_body = jsonb_set(
         outbox.envelope_body,
         '{payload}',
         event.payload,
         false
       ),
       envelope_sha256 = encode(
         sha256(convert_to(
           jsonb_set(
             outbox.envelope_body,
             '{payload}',
             event.payload,
             false
           )::text,
           'UTF8'
         )),
         'hex'
       )
  FROM public.domain_event event
 WHERE outbox.event_id = event.event_id
   AND outbox.aggregate_type = 'WORK_QUEUE'
   AND outbox.envelope_body->'payload' IS DISTINCT FROM event.payload;

UPDATE public.projection_checkpoint checkpoint
   SET projection_sha256 = event.payload_sha256
  FROM public.domain_event event
 WHERE checkpoint.aggregate_type = 'WORK_QUEUE'
   AND checkpoint.aggregate_type = event.aggregate_type
   AND checkpoint.aggregate_id = event.aggregate_id
   AND checkpoint.aggregate_version = event.aggregate_version
   AND checkpoint.projection_sha256 IS DISTINCT FROM event.payload_sha256;

UPDATE public.inbox_message inbox
   SET payload_sha256 = event.payload_sha256
  FROM public.domain_event event
 WHERE inbox.aggregate_type = 'WORK_QUEUE'
   AND inbox.event_id = event.event_id
   AND inbox.payload_sha256 IS DISTINCT FROM event.payload_sha256;

UPDATE public.version_gap_quarantine quarantine
   SET payload_sha256 = event.payload_sha256
  FROM public.domain_event event
 WHERE quarantine.aggregate_type = 'WORK_QUEUE'
   AND quarantine.received_event_id = event.event_id
   AND quarantine.payload_sha256 IS DISTINCT FROM event.payload_sha256;

ALTER TABLE public.outbox_event ENABLE TRIGGER USER;
ALTER TABLE public.domain_event ENABLE TRIGGER USER;

DROP FUNCTION public.canonicalize_global_work_queue_fact_v23(varchar, jsonb);

-- Migration-created work queues receive an event-store baseline, so every
-- subsequent synchronized update uses the same optimistic stream discipline
-- as queues created by the running service.
WITH baseline AS (
  SELECT
    queue.id,
    queue.version,
    md5(
      'WORK_QUEUE:'
      || queue.id::text
      || ':'
      || queue.version::text
      || ':baseline.v23'
    )::uuid AS event_id,
    clock_timestamp() AS recorded_at
  FROM public.work_queue queue
  JOIN v23_missing_general_queue missing ON missing.id = queue.id
)
INSERT INTO public.event_stream_head(
  aggregate_type,
  aggregate_id,
  current_version,
  last_event_id,
  updated_at)
SELECT
  'WORK_QUEUE',
  baseline.id::text,
  baseline.version,
  baseline.event_id,
  baseline.recorded_at
FROM baseline;

WITH payloads AS (
  SELECT
    queue.id,
    queue.version,
    head.last_event_id AS event_id,
    head.updated_at AS recorded_at,
    jsonb_build_object(
      'workQueueId', queue.id,
      'revisionMarker', queue.revision_marker,
      'warehouseId', queue.warehouse_id,
      'queueDefinitionId', queue.definition_id,
      'queueType', definition.queue_type,
      'queuePurpose', definition.queue_purpose,
      'sortOrder', queue.sort_order,
      'active', queue.active,
      'hidden', queue.hidden,
      'collapsed', queue.collapsed,
      'holdingPeriodMinutes', queue.holding_period_minutes,
      'notificationThreshold', queue.notification_threshold,
      'notifyWhenThresholdReached',
        queue.notify_when_threshold_reached,
      'resultPhotoMinCount', queue.result_photo_min_count,
      'classBindings', COALESCE((
        SELECT jsonb_agg(
          jsonb_build_object(
            'bindingId', binding.id,
            'version', binding.version,
            'workerClassId', binding.worker_class_id,
            'bindingOrder', binding.binding_order,
            'stopTaskOnTake', binding.stop_task_on_take,
            'participationPolicy', binding.participation_policy,
            'notifyOnPrimaryTake', binding.notify_on_primary_take
          )
          ORDER BY binding.binding_order, binding.id
        )
        FROM public.work_queue_class_binding binding
        WHERE binding.queue_id = queue.id
      ), '[]'::jsonb),
      'deleted', false
    ) AS payload
  FROM public.work_queue queue
  JOIN v23_missing_general_queue missing ON missing.id = queue.id
  JOIN public.queue_definition definition
    ON definition.id = queue.definition_id
  JOIN public.event_stream_head head
    ON head.aggregate_type = 'WORK_QUEUE'
   AND head.aggregate_id = queue.id::text
)
INSERT INTO public.domain_event(
  event_id,
  aggregate_type,
  aggregate_id,
  aggregate_version,
  event_type,
  event_version,
  occurred_at,
  recorded_at,
  correlation_id,
  causation_id,
  actor_ref,
  payload,
  payload_sha256,
  baseline)
SELECT
  payloads.event_id,
  'WORK_QUEUE',
  payloads.id::text,
  payloads.version,
  'task-board.work-queue.baseline.v1',
  1,
  NULL,
  payloads.recorded_at,
  md5(payloads.event_id::text || ':correlation')::uuid,
  NULL,
  NULL,
  payloads.payload,
  encode(
    sha256(convert_to(payloads.payload::text, 'UTF8')),
    'hex'
  ),
  true
FROM payloads;

INSERT INTO public.projection_checkpoint(
  projection_name,
  aggregate_type,
  aggregate_id,
  aggregate_version,
  projection_sha256,
  updated_at)
SELECT
  'task-board-live-v1',
  event.aggregate_type,
  event.aggregate_id,
  event.aggregate_version,
  event.payload_sha256,
  event.recorded_at
FROM public.domain_event event
JOIN v23_missing_general_queue missing
  ON missing.id::text = event.aggregate_id
WHERE event.aggregate_type = 'WORK_QUEUE'
  AND event.baseline;
