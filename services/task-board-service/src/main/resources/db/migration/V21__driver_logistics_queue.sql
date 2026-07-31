-- Cut the reviewed movement queue over to the dedicated driver-logistics
-- purpose without changing the existing SPB work_queue, queue_entry or task
-- identities.  Runtime code only reads the new shape after this migration.

ALTER TABLE public.queue_definition
  ADD COLUMN queue_purpose varchar(32) NOT NULL DEFAULT 'GENERAL',
  ADD CONSTRAINT ck_queue_definition_purpose
    CHECK (queue_purpose IN ('GENERAL', 'LOGISTICS_DRIVER')),
  ADD CONSTRAINT ck_queue_definition_driver_type
    CHECK (queue_purpose <> 'LOGISTICS_DRIVER' OR queue_type = 'MOVEMENT');

UPDATE public.queue_definition
   SET queue_purpose = 'LOGISTICS_DRIVER'
 WHERE id = 'c4d01176-43d3-4b69-ae1f-9a2a2e6c9971';

DO $$
BEGIN
  IF EXISTS (
    SELECT 1
      FROM public.work_queue queue
      JOIN public.queue_definition definition ON definition.id = queue.definition_id
      LEFT JOIN public.work_queue_class_binding primary_binding
        ON primary_binding.queue_id = queue.id
       AND primary_binding.binding_order = 0
     WHERE definition.queue_purpose = 'LOGISTICS_DRIVER'
       AND queue.active
       AND primary_binding.worker_class_id IS DISTINCT FROM
           '442f7eed-563a-4a98-8a6c-84886256a07a'::uuid
       AND EXISTS (
         SELECT 1
           FROM public.queue_entry entry
          WHERE entry.queue_id = queue.id
            AND entry.status IN ('WAITING', 'IN_PROGRESS', 'PAUSED')
       )
  ) THEN
    RAISE EXCEPTION
      'A legacy driver queue has active entries but no reviewed driver primary class';
  END IF;
END
$$;

-- A warehouse does not acquire driver logistics merely because a legacy board
-- row reused the SPB definition.  Only the reviewed SPB binding remains active;
-- other warehouses must explicitly attach/configure the global queue.
UPDATE public.work_queue queue
   SET active = false,
       hidden = true,
       revision_marker = md5(queue.id::text || ':driver-logistics-disabled:v21')::uuid
  FROM public.queue_definition definition
 WHERE definition.id = queue.definition_id
   AND definition.queue_purpose = 'LOGISTICS_DRIVER'
   AND NOT EXISTS (
     SELECT 1
       FROM public.work_queue_class_binding primary_binding
      WHERE primary_binding.queue_id = queue.id
        AND primary_binding.binding_order = 0
        AND primary_binding.worker_class_id =
            '442f7eed-563a-4a98-8a6c-84886256a07a'::uuid
   );

UPDATE public.worker_class
   SET name = 'Водители'
 WHERE id = '442f7eed-563a-4a98-8a6c-84886256a07a'
   AND name IS DISTINCT FROM 'Водители';

-- Every logistics completion must select one READY result photo.  The queue
-- setting remains the single enforcement mechanism used by both the worker
-- API and the ordinary task-board completion rules.
UPDATE public.work_queue queue
   SET result_photo_min_count = GREATEST(queue.result_photo_min_count, 1),
       revision_marker = md5(queue.id::text || ':driver-result-photo:v21')::uuid
  FROM public.queue_definition definition
 WHERE definition.id = queue.definition_id
   AND definition.queue_purpose = 'LOGISTICS_DRIVER';

ALTER TABLE public.work_queue_class_binding
  ADD COLUMN participation_policy varchar(16),
  ADD COLUMN notify_on_primary_take boolean NOT NULL DEFAULT false;

UPDATE public.work_queue_class_binding binding
   SET participation_policy =
       CASE
         WHEN binding.binding_order = 0 THEN 'PRIMARY'
         WHEN definition.queue_purpose = 'LOGISTICS_DRIVER' THEN 'OPTIONAL'
         WHEN binding.notify_urgent THEN 'REQUIRED'
         ELSE 'OPTIONAL'
       END,
       notify_on_primary_take =
       CASE
         WHEN binding.binding_order = 0 THEN false
         ELSE binding.notify_urgent
       END
  FROM public.work_queue queue
  JOIN public.queue_definition definition ON definition.id = queue.definition_id
 WHERE queue.id = binding.queue_id;

ALTER TABLE public.work_queue_class_binding
  ALTER COLUMN participation_policy SET NOT NULL,
  ADD CONSTRAINT ck_work_queue_binding_participation
    CHECK (participation_policy IN ('PRIMARY', 'REQUIRED', 'OPTIONAL')),
  ADD CONSTRAINT ck_work_queue_binding_primary_order
    CHECK (
      (binding_order = 0 AND participation_policy = 'PRIMARY')
      OR
      (binding_order > 0 AND participation_policy <> 'PRIMARY')
    ),
  ADD CONSTRAINT ck_work_queue_binding_primary_notification
    CHECK (participation_policy <> 'PRIMARY' OR notify_on_primary_take = false);

ALTER TABLE public.board_task
  ADD COLUMN task_lane varchar(16) NOT NULL DEFAULT 'SCHEDULED',
  ADD CONSTRAINT ck_board_task_lane
    CHECK (task_lane IN ('SCHEDULED', 'CURRENT'));

ALTER TABLE public.worker_task_evidence
  ADD COLUMN selected_for_completion boolean NOT NULL DEFAULT false;

CREATE UNIQUE INDEX uk_worker_task_evidence_selected_completion
  ON public.worker_task_evidence(entry_id)
  WHERE selected_for_completion;

ALTER TABLE public.task_sync_source
  DROP CONSTRAINT ck_task_sync_source_reference,
  ADD CONSTRAINT ck_task_sync_source_reference
    CHECK (
      (source_type IS NULL AND source_id IS NULL)
      OR (
        source_type IN ('MAINTENANCE_REPAIR', 'LOGISTICS_DRIVER_TASK')
        AND source_id IS NOT NULL
      )
    );

ALTER TABLE public.worker_task_evidence
  DROP CONSTRAINT ck_worker_task_evidence_source,
  ADD CONSTRAINT ck_worker_task_evidence_source
    CHECK (
      (source_type IS NULL AND source_id IS NULL)
      OR (
        source_type IN ('MAINTENANCE_REPAIR', 'LOGISTICS_DRIVER_TASK')
        AND source_id IS NOT NULL
      )
    );

CREATE FUNCTION public.canonicalize_driver_logistics_fact_v21(
  fact_type varchar,
  fact_id varchar,
  fact_payload jsonb)
RETURNS jsonb
LANGUAGE plpgsql
STABLE
AS $$
DECLARE
  canonical jsonb := fact_payload;
  canonical_bindings jsonb;
  canonical_purpose text;
BEGIN
  CASE fact_type
    WHEN 'WORK_QUEUE' THEN
      SELECT definition.queue_purpose
        INTO canonical_purpose
        FROM public.work_queue queue
        JOIN public.queue_definition definition ON definition.id = queue.definition_id
       WHERE queue.id::text = fact_id;

      canonical_purpose := COALESCE(canonical_purpose, 'GENERAL');
      canonical := jsonb_set(
        canonical - 'queuePurpose',
        '{queuePurpose}',
        to_jsonb(canonical_purpose),
        true);

      SELECT COALESCE(
               jsonb_agg(
                 (stored.binding - 'notifyUrgent'
                                 - 'participationPolicy'
                                 - 'notifyOnPrimaryTake')
                 || jsonb_build_object(
                   'participationPolicy',
                     CASE
                       WHEN COALESCE(
                              current_binding.binding_order,
                              (stored.binding->>'bindingOrder')::integer,
                              stored.position::integer - 1) = 0
                         THEN 'PRIMARY'
                       WHEN canonical_purpose = 'LOGISTICS_DRIVER'
                         THEN 'OPTIONAL'
                       WHEN COALESCE(
                              current_binding.notify_on_primary_take,
                              (stored.binding->>'notifyUrgent')::boolean,
                              false)
                         THEN 'REQUIRED'
                       ELSE 'OPTIONAL'
                     END,
                   'notifyOnPrimaryTake',
                     CASE
                       WHEN COALESCE(
                              current_binding.binding_order,
                              (stored.binding->>'bindingOrder')::integer,
                              stored.position::integer - 1) = 0
                         THEN false
                       ELSE COALESCE(
                              current_binding.notify_on_primary_take,
                              (stored.binding->>'notifyUrgent')::boolean,
                              false)
                     END)
                 ORDER BY
                   COALESCE(
                     current_binding.binding_order,
                     (stored.binding->>'bindingOrder')::integer,
                     stored.position::integer - 1),
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
      canonical := jsonb_set(
        canonical - 'resultPhotoMinCount',
        '{resultPhotoMinCount}',
        to_jsonb(COALESCE((
          SELECT queue.result_photo_min_count
            FROM public.work_queue queue
           WHERE queue.id::text = fact_id
        ), 1)),
        true);

    WHEN 'BOARD_TASK' THEN
      canonical := jsonb_set(
        canonical - 'lane',
        '{lane}',
        to_jsonb(COALESCE((
          SELECT task.task_lane
            FROM public.board_task task
           WHERE task.id::text = fact_id
        ), 'SCHEDULED')),
        true);
  END CASE;
  RETURN canonical;
END
$$;

-- One audited rewrite removes the old notifyUrgent fact shape.  All dependent
-- hashes are updated in the same transaction before the old column is dropped.
ALTER TABLE public.domain_event DISABLE TRIGGER USER;
ALTER TABLE public.outbox_event DISABLE TRIGGER USER;

WITH canonical AS (
  SELECT event.event_id,
         public.canonicalize_driver_logistics_fact_v21(
           event.aggregate_type, event.aggregate_id, event.payload) AS payload
    FROM public.domain_event event
   WHERE event.aggregate_type IN ('WORK_QUEUE', 'BOARD_TASK')
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
  SELECT snapshot.aggregate_type,
         snapshot.aggregate_id,
         snapshot.aggregate_version,
         public.canonicalize_driver_logistics_fact_v21(
           snapshot.aggregate_type, snapshot.aggregate_id, snapshot.state) AS state
    FROM public.aggregate_snapshot snapshot
   WHERE snapshot.aggregate_type IN ('WORK_QUEUE', 'BOARD_TASK')
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

DROP FUNCTION public.canonicalize_driver_logistics_fact_v21(
  varchar, varchar, jsonb);

WITH canonical AS (
  SELECT task.id,
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
             'lane', task.task_lane,
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

ALTER TABLE public.work_queue_class_binding
  DROP COLUMN notify_urgent;
