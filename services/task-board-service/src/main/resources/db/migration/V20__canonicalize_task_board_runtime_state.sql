-- V5, V8, V11, V14, V18 and V19 introduced fields that older stored facts
-- could only read through runtime compatibility normalizers. Canonicalize those
-- facts once, together with every dependent checksum, so runtime validation and
-- replay can remain exact and current-shape only.

CREATE OR REPLACE FUNCTION public.task_board_request_fingerprint_v4(payload jsonb)
RETURNS varchar(64)
LANGUAGE sql
IMMUTABLE
STRICT
AS $$
  SELECT encode(
    sha256(convert_to('task-board-create:v4' || payload::text, 'UTF8')),
    'hex')
$$;

CREATE FUNCTION public.canonicalize_task_board_fact_v20(
  fact_type varchar,
  fact_id varchar,
  fact_payload jsonb,
  fact_recorded_at timestamptz)
RETURNS jsonb
LANGUAGE plpgsql
STABLE
AS $$
DECLARE
  canonical jsonb := fact_payload;
  canonical_bindings jsonb;
  canonical_definition_id text;
  canonical_reference_id text;
  canonical_scheduled_date date;
  canonical_priority integer;
  canonical_pinned boolean;
  canonical_budget bigint;
  canonical_photo_count integer;
BEGIN
  CASE fact_type
    WHEN 'WORK_QUEUE' THEN
      canonical := canonical - 'groupBindings';

      SELECT queue.definition_id::text, queue.result_photo_min_count
        INTO canonical_definition_id, canonical_photo_count
        FROM public.work_queue queue
       WHERE queue.id::text = fact_id;

      canonical_definition_id := COALESCE(
        canonical_definition_id,
        canonical->>'queueDefinitionId',
        canonical->>'workQueueId');
      IF canonical_definition_id IS NULL THEN
        RAISE EXCEPTION 'Cannot canonicalize WORK_QUEUE fact % without an identity', fact_id;
      END IF;
      canonical := jsonb_set(
        canonical,
        '{queueDefinitionId}',
        to_jsonb(canonical_definition_id),
        true);

      IF NOT canonical ? 'resultPhotoMinCount' THEN
        canonical_photo_count := COALESCE(
          canonical_photo_count,
          CASE canonical->>'queueType' WHEN 'HOLDING' THEN 0 ELSE 1 END);
        canonical := jsonb_set(
          canonical,
          '{resultPhotoMinCount}',
          to_jsonb(canonical_photo_count),
          true);
      END IF;

      SELECT COALESCE(
               jsonb_agg(
                 (stored.binding - 'bindingOrder' - 'notifyUrgent')
                 || jsonb_build_object(
                   'bindingOrder',
                     COALESCE(current_binding.binding_order, stored.position::integer - 1),
                   'notifyUrgent',
                     COALESCE(current_binding.notify_urgent, false))
                 ORDER BY
                   COALESCE(current_binding.binding_order, stored.position::integer - 1),
                   stored.binding->>'bindingId'),
               '[]'::jsonb)
        INTO canonical_bindings
        FROM jsonb_array_elements(
               COALESCE(canonical->'classBindings', '[]'::jsonb))
             WITH ORDINALITY AS stored(binding, position)
        LEFT JOIN public.work_queue_class_binding current_binding
          ON current_binding.id::text = stored.binding->>'bindingId';
      canonical := jsonb_set(
        canonical,
        '{classBindings}',
        canonical_bindings,
        true);

    WHEN 'QUEUE_USAGE_REFERENCE' THEN
      SELECT reference.queue_definition_id::text
        INTO canonical_reference_id
        FROM public.queue_usage_reference reference
       WHERE reference.id::text = fact_id;
      IF canonical_reference_id IS NOT NULL THEN
        canonical := jsonb_set(
          canonical,
          '{queueId}',
          to_jsonb(canonical_reference_id),
          true);
      END IF;

    WHEN 'BOARD_TASK' THEN
      SELECT task.scheduled_date, task.priority, task.pinned
        INTO canonical_scheduled_date, canonical_priority, canonical_pinned
        FROM public.board_task task
       WHERE task.id::text = fact_id;
      canonical_scheduled_date := COALESCE(
        canonical_scheduled_date,
        CASE
          WHEN canonical->>'deadlineAt' IS NOT NULL
            THEN (((canonical->>'deadlineAt')::timestamptz)
              AT TIME ZONE 'Europe/Moscow')::date
          ELSE (fact_recorded_at AT TIME ZONE 'Europe/Moscow')::date
        END);
      canonical_priority := COALESCE(canonical_priority, 3);
      canonical_pinned := COALESCE(canonical_pinned, false);
      IF NOT canonical ? 'scheduledDate' THEN
        canonical := jsonb_set(
          canonical,
          '{scheduledDate}',
          to_jsonb(canonical_scheduled_date::text),
          true);
      END IF;
      IF NOT canonical ? 'priority' THEN
        canonical := jsonb_set(
          canonical,
          '{priority}',
          to_jsonb(canonical_priority),
          true);
      END IF;
      IF NOT canonical ? 'pinned' THEN
        canonical := jsonb_set(
          canonical,
          '{pinned}',
          to_jsonb(canonical_pinned),
          true);
      END IF;

    WHEN 'WORKER' THEN
      IF NOT canonical ? 'currentGroupId' THEN
        canonical := jsonb_set(
          canonical,
          '{currentGroupId}',
          'null'::jsonb,
          true);
      END IF;

    WHEN 'WORKER_GROUP' THEN
      IF NOT canonical ? 'operationalStatus' THEN
        canonical := jsonb_set(
          canonical,
          '{operationalStatus}',
          '"AVAILABLE"'::jsonb,
          true);
      END IF;

    WHEN 'QUEUE_ENTRY' THEN
      canonical_budget :=
        CASE
          WHEN jsonb_typeof(canonical->'plannedDurationMinutes') = 'number'
            AND (canonical->>'plannedDurationMinutes')::bigint > 0
          THEN (canonical->>'plannedDurationMinutes')::bigint * 60
          ELSE NULL
        END;
      IF NOT canonical ? 'originalBudgetSeconds' THEN
        canonical := jsonb_set(
          canonical,
          '{originalBudgetSeconds}',
          COALESCE(to_jsonb(canonical_budget), 'null'::jsonb),
          true);
      END IF;
      IF NOT canonical ? 'currentBudgetSeconds' THEN
        canonical := jsonb_set(
          canonical,
          '{currentBudgetSeconds}',
          COALESCE(to_jsonb(canonical_budget), 'null'::jsonb),
          true);
      END IF;
  END CASE;
  RETURN canonical;
END
$$;

-- The event store is append-only at runtime. This is the one audited,
-- transactional rewrite; user triggers are restored before the migration ends.
ALTER TABLE public.domain_event DISABLE TRIGGER USER;
ALTER TABLE public.outbox_event DISABLE TRIGGER USER;

WITH canonical AS (
  SELECT
    event.event_id,
    public.canonicalize_task_board_fact_v20(
      event.aggregate_type,
      event.aggregate_id,
      event.payload,
      event.recorded_at) AS payload
  FROM public.domain_event event
  WHERE event.aggregate_type IN (
    'WORK_QUEUE',
    'QUEUE_USAGE_REFERENCE',
    'BOARD_TASK',
    'WORKER',
    'WORKER_GROUP',
    'QUEUE_ENTRY')
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
    public.canonicalize_task_board_fact_v20(
      snapshot.aggregate_type,
      snapshot.aggregate_id,
      snapshot.state,
      snapshot.recorded_at) AS state
  FROM public.aggregate_snapshot snapshot
  WHERE snapshot.aggregate_type IN (
    'WORK_QUEUE',
    'QUEUE_USAGE_REFERENCE',
    'BOARD_TASK',
    'WORKER',
    'WORKER_GROUP',
    'QUEUE_ENTRY')
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
      false),
    envelope_sha256 = encode(
      sha256(convert_to(
        jsonb_set(outbox.envelope_body, '{payload}', event.payload, false)::text,
        'UTF8')),
      'hex')
FROM public.domain_event event
WHERE outbox.event_id = event.event_id
  AND outbox.envelope_body->'payload' IS DISTINCT FROM event.payload;

UPDATE public.projection_checkpoint checkpoint
SET projection_sha256 = event.payload_sha256
FROM public.domain_event event
WHERE checkpoint.aggregate_type = event.aggregate_type
  AND checkpoint.aggregate_id = event.aggregate_id
  AND checkpoint.aggregate_version = event.aggregate_version
  AND checkpoint.projection_sha256 IS DISTINCT FROM event.payload_sha256;

UPDATE public.inbox_message inbox
SET payload_sha256 = event.payload_sha256
FROM public.domain_event event
WHERE inbox.event_id = event.event_id
  AND inbox.payload_sha256 IS DISTINCT FROM event.payload_sha256;

UPDATE public.version_gap_quarantine quarantine
SET payload_sha256 = event.payload_sha256
FROM public.domain_event event
WHERE quarantine.received_event_id = event.event_id
  AND quarantine.payload_sha256 IS DISTINCT FROM event.payload_sha256;

ALTER TABLE public.outbox_event ENABLE TRIGGER USER;
ALTER TABLE public.domain_event ENABLE TRIGGER USER;

DROP FUNCTION public.canonicalize_task_board_fact_v20(
  varchar, varchar, jsonb, timestamptz);

-- Canonical generic request fingerprints always include effective scheduling
-- and all worker-visible route snapshots. Special fenced logistics tasks keep
-- their dedicated fingerprint schema.
WITH canonical AS (
  SELECT
    task.id,
    public.task_board_request_fingerprint_v4(
      jsonb_build_object(
        'warehouseId', task.warehouse_id::text,
        'externalTaskId', task.external_task_id::text,
        'title', task.title,
        'unitNumber', task.unit_number,
        'description', task.description,
        'plannedDurationMinutes', task.planned_duration_minutes,
        'deadlineEpochMicros',
          CASE
            WHEN task.deadline_at IS NULL THEN NULL
            ELSE trunc(extract(epoch FROM task.deadline_at) * 1000000)::bigint
          END,
        'scheduledDate', task.scheduled_date::text,
        'priority', task.priority,
        'route', COALESCE((
          SELECT jsonb_agg(
                   jsonb_build_object(
                     'queueDefinitionId', queue.definition_id::text,
                     'taskText', entry.task_text,
                     'plannedDurationMinutes', entry.planned_duration_minutes,
                     'works', entry.worker_works,
                     'materials', entry.worker_materials,
                     'comments', entry.worker_comments,
                     'sourceMedia', entry.source_media_references)
                   ORDER BY entry.route_index)
            FROM public.queue_entry entry
            JOIN public.work_queue queue ON queue.id = entry.queue_id
           WHERE entry.task_id = task.id
        ), '[]'::jsonb))
    ) AS fingerprint
  FROM public.board_task task
  WHERE task.external_task_id IS NOT NULL
    AND NOT task.completion_deadline_enforced
)
UPDATE public.board_task task
SET request_fingerprint = canonical.fingerprint
FROM canonical
WHERE task.id = canonical.id
  AND task.request_fingerprint IS DISTINCT FROM canonical.fingerprint;
