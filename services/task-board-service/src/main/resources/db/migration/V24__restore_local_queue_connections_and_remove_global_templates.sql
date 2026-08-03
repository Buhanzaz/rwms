-- V23 was a one-time, now-applied migration that incorrectly moved warehouse-local operational
-- state into queue_definition and manufactured GENERAL work_queue rows.  Its checksum is
-- immutable.  This forward-only repair uses the audited 2026-07-31 12:50 UTC pre-V23 snapshot
-- to restore only the surviving reviewed warehouse connections.  It deliberately does not
-- recreate the MSK HOLDING connection deleted by a proven post-V23 command.
--
-- A recovery event is appended for every restored queue, so the live projection, stream head,
-- checkpoint and outbox remain coherent.  Old events are retained; this migration never rewrites
-- their payloads or hashes.

CREATE TEMP TABLE v24_local_queue_manifest (
  id uuid PRIMARY KEY,
  warehouse_id uuid NOT NULL,
  definition_id uuid NOT NULL,
  sort_order integer NOT NULL,
  active boolean NOT NULL,
  hidden boolean NOT NULL,
  collapsed boolean NOT NULL,
  holding_period_minutes integer,
  notification_threshold integer,
  notify_when_threshold_reached boolean NOT NULL,
  result_photo_min_count integer NOT NULL
) ON COMMIT DROP;

INSERT INTO v24_local_queue_manifest(
  id, warehouse_id, definition_id, sort_order, active, hidden, collapsed,
  holding_period_minutes, notification_threshold, notify_when_threshold_reached,
  result_photo_min_count)
VALUES
  ('4c7df6c8-6ef0-4c85-8bef-b471ca305270', '00000000-0000-0000-0000-000000000001', '4c7df6c8-6ef0-4c85-8bef-b471ca305270', 20, true, false, false, null, null, false, 1),
  ('8873b3d1-2148-47cf-a0ed-789b853f1242', '00000000-0000-0000-0000-000000000001', '8873b3d1-2148-47cf-a0ed-789b853f1242', 30, true, false, false, null, null, false, 1),
  ('6a743d06-cd6e-463c-83e9-de15aa4983f3', '00000000-0000-0000-0000-000000000001', '6a743d06-cd6e-463c-83e9-de15aa4983f3', 40, true, false, false, null, null, false, 1),
  ('3dc1a941-852f-4f91-9791-171ba2001029', '00000000-0000-0000-0000-000000000001', '3dc1a941-852f-4f91-9791-171ba2001029', 50, true, false, false, null, null, false, 1),
  ('cc7085f8-2105-4dd4-a648-b779c0b84158', '00000000-0000-0000-0000-000000000001', 'cc7085f8-2105-4dd4-a648-b779c0b84158', 60, true, false, false, null, null, false, 1),
  ('6e691b85-4718-4597-89ae-4663c90bf0cc', '00000000-0000-0000-0000-000000000001', '6e691b85-4718-4597-89ae-4663c90bf0cc', 70, true, false, false, null, null, false, 1),
  ('096ada15-e366-4478-a86c-037bcddbd83e', '00000000-0000-0000-0000-000000000001', '096ada15-e366-4478-a86c-037bcddbd83e', 80, true, false, false, null, null, false, 1),
  ('47ea2d78-8eab-4d03-ad95-21a4de77f307', '00000000-0000-0000-0000-000000000002', '4c7df6c8-6ef0-4c85-8bef-b471ca305270', 20, true, false, false, null, null, false, 1),
  ('54641234-be75-480c-926c-e4ee695d9bdb', '00000000-0000-0000-0000-000000000002', '8873b3d1-2148-47cf-a0ed-789b853f1242', 30, true, false, false, null, null, false, 1),
  ('07196c02-1698-4b02-9926-8503360988f4', '00000000-0000-0000-0000-000000000002', '6a743d06-cd6e-463c-83e9-de15aa4983f3', 40, true, false, false, null, null, false, 1),
  ('b89335c7-0b70-436a-9227-5b7b4c2f9cab', '00000000-0000-0000-0000-000000000002', '3dc1a941-852f-4f91-9791-171ba2001029', 50, true, false, false, null, null, false, 1),
  ('26a0dcb8-b586-4d5b-8470-05afe4205540', '00000000-0000-0000-0000-000000000002', 'cc7085f8-2105-4dd4-a648-b779c0b84158', 60, true, false, false, null, null, false, 1),
  ('56343733-5813-435d-a75d-60b7fc7702b4', '00000000-0000-0000-0000-000000000002', '6e691b85-4718-4597-89ae-4663c90bf0cc', 70, true, false, false, null, null, false, 1),
  ('1454bae3-5553-4c42-8a6e-fd9d45a5d12b', 'c89b65f1-2891-4176-bd88-1d231e869a25', '4c7df6c8-6ef0-4c85-8bef-b471ca305270', 20, true, false, false, null, null, false, 1),
  ('1d9cc642-d02f-42c5-bcdc-6cfcc060ed3c', 'c89b65f1-2891-4176-bd88-1d231e869a25', '8873b3d1-2148-47cf-a0ed-789b853f1242', 30, true, false, false, null, null, false, 1),
  ('6965438a-ab77-4bb7-bc56-78bc5b3ad411', 'c89b65f1-2891-4176-bd88-1d231e869a25', '6a743d06-cd6e-463c-83e9-de15aa4983f3', 40, true, false, false, null, null, false, 1),
  ('e985cc6a-162e-49d1-aaa7-a4db72c20736', 'c89b65f1-2891-4176-bd88-1d231e869a25', '3dc1a941-852f-4f91-9791-171ba2001029', 50, true, false, false, null, null, false, 1),
  ('58cd5976-2bf3-483f-a263-080430f7ff69', 'c89b65f1-2891-4176-bd88-1d231e869a25', 'cc7085f8-2105-4dd4-a648-b779c0b84158', 60, true, false, false, null, null, false, 1),
  ('95425828-338d-4ac4-b99f-701412e65e06', 'c89b65f1-2891-4176-bd88-1d231e869a25', '6e691b85-4718-4597-89ae-4663c90bf0cc', 70, true, false, false, null, null, false, 1),
  ('4e11a44b-1449-461c-b73c-762c866a2b1f', 'c89b65f1-2891-4176-bd88-1d231e869a25', '096ada15-e366-4478-a86c-037bcddbd83e', 80, true, false, false, null, null, false, 1);

CREATE TEMP TABLE v24_local_queue_binding_manifest (
  id uuid PRIMARY KEY,
  version bigint NOT NULL,
  queue_id uuid NOT NULL,
  worker_class_id uuid NOT NULL,
  stop_task_on_take boolean NOT NULL,
  binding_order integer NOT NULL,
  participation_policy varchar(16) NOT NULL,
  notify_on_primary_take boolean NOT NULL
) ON COMMIT DROP;

INSERT INTO v24_local_queue_binding_manifest(
  id, version, queue_id, worker_class_id, stop_task_on_take, binding_order,
  participation_policy, notify_on_primary_take)
VALUES
  ('f6667cf3-0c3b-4567-baa1-a9480858577f', 0, '096ada15-e366-4478-a86c-037bcddbd83e', '6cee2fd5-a5d9-4205-a2bf-272181b8a284', false, 0, 'PRIMARY', false),
  ('a9184e46-7b42-4995-a753-f84da1768632', 0, '1454bae3-5553-4c42-8a6e-fd9d45a5d12b', '6cee2fd5-a5d9-4205-a2bf-272181b8a284', false, 0, 'PRIMARY', false),
  ('123177cf-bf86-4a56-9125-95844437263b', 0, '1d9cc642-d02f-42c5-bcdc-6cfcc060ed3c', '6cee2fd5-a5d9-4205-a2bf-272181b8a284', false, 0, 'PRIMARY', false),
  ('a77515aa-3089-4379-884f-59618a5f0de5', 0, '3dc1a941-852f-4f91-9791-171ba2001029', 'e5601b96-05b5-4631-a4d6-a2dc16065921', false, 0, 'PRIMARY', false),
  ('d3624ae5-fbe7-4f13-97ef-a0974f8eb4c8', 0, '4c7df6c8-6ef0-4c85-8bef-b471ca305270', '6cee2fd5-a5d9-4205-a2bf-272181b8a284', false, 0, 'PRIMARY', false),
  ('60bb4c75-69e5-4066-ac6c-2f0aed034643', 0, '4e11a44b-1449-461c-b73c-762c866a2b1f', '6cee2fd5-a5d9-4205-a2bf-272181b8a284', false, 0, 'PRIMARY', false),
  ('942df5c5-3eb7-4b10-876c-c3246f3054d7', 0, '58cd5976-2bf3-483f-a263-080430f7ff69', 'c426e935-5862-41e4-a18d-fa3b8b84f350', false, 0, 'PRIMARY', false),
  ('2a672128-3275-42cc-b8d3-99e53e0ce8bd', 0, '6965438a-ab77-4bb7-bc56-78bc5b3ad411', 'f783e05e-fefd-4dbf-9dba-214da1e37d54', false, 0, 'PRIMARY', false),
  ('5bc07dc5-9c34-4352-80a3-4605866fa3ad', 0, '6a743d06-cd6e-463c-83e9-de15aa4983f3', 'f783e05e-fefd-4dbf-9dba-214da1e37d54', false, 0, 'PRIMARY', false),
  ('8f7a83b0-2746-4e80-ba92-dfda218f2ad2', 0, '6e691b85-4718-4597-89ae-4663c90bf0cc', '537c4a3e-8666-414a-92c6-d4cec43c4212', false, 0, 'PRIMARY', false),
  ('8e0d6717-e41f-4eaf-ab32-136087c6d5a6', 0, '8873b3d1-2148-47cf-a0ed-789b853f1242', '6cee2fd5-a5d9-4205-a2bf-272181b8a284', false, 0, 'PRIMARY', false),
  ('abb69662-f4e5-43a7-b988-3350b694e90e', 0, '95425828-338d-4ac4-b99f-701412e65e06', '537c4a3e-8666-414a-92c6-d4cec43c4212', false, 0, 'PRIMARY', false),
  ('4474df3a-c15d-4493-9913-3f1c62daa50f', 0, 'cc7085f8-2105-4dd4-a648-b779c0b84158', 'c426e935-5862-41e4-a18d-fa3b8b84f350', false, 0, 'PRIMARY', false),
  ('825b3004-f333-41ff-9cc4-fbd2fed12828', 0, 'e985cc6a-162e-49d1-aaa7-a4db72c20736', 'e5601b96-05b5-4631-a4d6-a2dc16065921', false, 0, 'PRIMARY', false);

-- If a reviewed queue or one of its reviewed worker classes has since been removed, preserve the
-- surviving live connection rather than synthesizing a replacement from the backup manifest.
CREATE TEMP TABLE v24_restorable_local_queue ON COMMIT DROP AS
SELECT manifest.*
FROM v24_local_queue_manifest manifest
JOIN public.work_queue queue
  ON queue.id = manifest.id
 AND queue.warehouse_id = manifest.warehouse_id
 AND queue.definition_id = manifest.definition_id
JOIN public.queue_definition definition ON definition.id = queue.definition_id
WHERE definition.queue_purpose = 'GENERAL'
  AND NOT EXISTS (
    SELECT 1
    FROM v24_local_queue_binding_manifest binding
    LEFT JOIN public.worker_class worker_class ON worker_class.id = binding.worker_class_id
    WHERE binding.queue_id = manifest.id
      AND worker_class.id IS NULL
  );

CREATE TEMP TABLE v24_recovery_clock ON COMMIT DROP AS
SELECT clock_timestamp() AS recorded_at;

UPDATE public.work_queue queue
   SET version = queue.version + 1,
       revision_marker = md5(queue.id::text || ':local-queue-recovery:v24')::uuid,
       sort_order = manifest.sort_order,
       active = manifest.active,
       hidden = manifest.hidden,
       collapsed = manifest.collapsed,
       holding_period_minutes = manifest.holding_period_minutes,
       notification_threshold = manifest.notification_threshold,
       notify_when_threshold_reached = manifest.notify_when_threshold_reached,
       result_photo_min_count = manifest.result_photo_min_count
  FROM v24_restorable_local_queue manifest
 WHERE queue.id = manifest.id;

DELETE FROM public.work_queue_class_binding binding
USING v24_restorable_local_queue queue
WHERE binding.queue_id = queue.id;

INSERT INTO public.work_queue_class_binding(
  id, version, queue_id, worker_class_id, stop_task_on_take, binding_order,
  participation_policy, notify_on_primary_take)
SELECT binding.id,
       binding.version,
       binding.queue_id,
       binding.worker_class_id,
       binding.stop_task_on_take,
       binding.binding_order,
       binding.participation_policy,
       binding.notify_on_primary_take
  FROM v24_local_queue_binding_manifest binding
  JOIN v24_restorable_local_queue queue ON queue.id = binding.queue_id
  JOIN public.worker_class worker_class ON worker_class.id = binding.worker_class_id;

CREATE TEMP TABLE v24_local_queue_recovery_event ON COMMIT DROP AS
WITH facts AS (
  SELECT queue.id,
         queue.version AS projection_version,
         stream.current_version AS previous_stream_version,
         stream.aggregate_id IS NULL AS baseline,
         clock.recorded_at,
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
           'notifyWhenThresholdReached', queue.notify_when_threshold_reached,
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
                        'notifyOnPrimaryTake', binding.notify_on_primary_take)
                      ORDER BY binding.binding_order, binding.id)
             FROM public.work_queue_class_binding binding
             WHERE binding.queue_id = queue.id
           ), '[]'::jsonb),
           'deleted', false) AS payload
    FROM public.work_queue queue
    JOIN v24_restorable_local_queue manifest ON manifest.id = queue.id
    JOIN public.queue_definition definition ON definition.id = queue.definition_id
    CROSS JOIN v24_recovery_clock clock
    LEFT JOIN public.event_stream_head stream
      ON stream.aggregate_type = 'WORK_QUEUE'
     AND stream.aggregate_id = queue.id::text
), identified AS (
  SELECT facts.*,
         CASE
           WHEN baseline THEN projection_version
           ELSE previous_stream_version + 1
         END AS aggregate_version,
         CASE
           WHEN baseline THEN 'task-board.work-queue.baseline.v1'
           ELSE 'task-board.work-queue.changed.v1'
         END AS event_type
    FROM facts
), event_ids AS (
  SELECT identified.*,
         md5(
           'WORK_QUEUE:' || id::text || ':' || aggregate_version::text
           || ':local-queue-recovery:v24')::uuid AS event_id
    FROM identified
), envelopes AS (
  SELECT event_ids.*,
         md5(event_id::text || ':correlation:v24')::uuid AS correlation_id,
         encode(sha256(convert_to(payload::text, 'UTF8')), 'hex') AS payload_sha256,
         jsonb_build_object(
           'envelopeVersion', 2,
           'eventId', event_id,
           'eventType', event_type,
           'eventVersion', 1,
           'occurredAt', CASE WHEN baseline THEN NULL::timestamptz ELSE recorded_at END,
           'recordedAt', recorded_at,
           'producer', 'task-board-service',
           'aggregateType', 'WORK_QUEUE',
           'aggregateId', id::text,
           'aggregateVersion', aggregate_version,
           'correlation', jsonb_build_object(
             'correlationId', md5(event_id::text || ':correlation:v24')::uuid,
             'causationId', NULL::uuid),
           'actorRef', NULL::jsonb,
           'payload', payload) AS envelope
    FROM event_ids
)
SELECT id,
       baseline,
       aggregate_version,
       event_id,
       event_type,
       recorded_at,
       correlation_id,
       payload,
       payload_sha256,
       envelope,
       encode(sha256(convert_to(envelope::text, 'UTF8')), 'hex') AS envelope_sha256
  FROM envelopes;

INSERT INTO public.event_stream_head(
  aggregate_type, aggregate_id, current_version, last_event_id, updated_at)
SELECT 'WORK_QUEUE', event.id::text, event.aggregate_version, event.event_id, event.recorded_at
  FROM v24_local_queue_recovery_event event
 WHERE event.baseline;

UPDATE public.event_stream_head stream
   SET current_version = event.aggregate_version,
       last_event_id = event.event_id,
       updated_at = event.recorded_at
  FROM v24_local_queue_recovery_event event
 WHERE NOT event.baseline
   AND stream.aggregate_type = 'WORK_QUEUE'
   AND stream.aggregate_id = event.id::text;

INSERT INTO public.domain_event(
  event_id, aggregate_type, aggregate_id, aggregate_version, event_type, event_version,
  occurred_at, recorded_at, correlation_id, causation_id, actor_ref, payload, payload_sha256,
  baseline)
SELECT event.event_id,
       'WORK_QUEUE',
       event.id::text,
       event.aggregate_version,
       event.event_type,
       1,
       CASE WHEN event.baseline THEN NULL::timestamptz ELSE event.recorded_at END,
       event.recorded_at,
       event.correlation_id,
       NULL,
       NULL,
       event.payload,
       event.payload_sha256,
       event.baseline
  FROM v24_local_queue_recovery_event event;

INSERT INTO public.outbox_event(
  event_id, aggregate_type, aggregate_id, aggregate_version, event_type, topic,
  envelope_body, envelope_sha256, status, attempt_count, next_attempt_at, created_at)
SELECT event.event_id,
       'WORK_QUEUE',
       event.id::text,
       event.aggregate_version,
       event.event_type,
       'rwms.task-board.work-queue.v1',
       event.envelope,
       event.envelope_sha256,
       'PENDING',
       0,
       event.recorded_at,
       event.recorded_at
  FROM v24_local_queue_recovery_event event
 WHERE NOT event.baseline;

INSERT INTO public.projection_checkpoint(
  projection_name, aggregate_type, aggregate_id, aggregate_version, projection_sha256, updated_at)
SELECT 'task-board-live-v1',
       'WORK_QUEUE',
       event.id::text,
       event.aggregate_version,
       event.payload_sha256,
       event.recorded_at
  FROM v24_local_queue_recovery_event event
ON CONFLICT (projection_name, aggregate_type, aggregate_id)
DO UPDATE SET aggregate_version = EXCLUDED.aggregate_version,
              projection_sha256 = EXCLUDED.projection_sha256,
              updated_at = EXCLUDED.updated_at;

-- A V23 synthetic connection is identified by its deterministic UUID.  The guard is intentionally
-- operational rather than event-store based: V23's baseline/batch events are migration artefacts,
-- but an entry (and therefore its task/time/history chain) is real work and blocks deletion.
CREATE TEMP TABLE v24_unused_synthetic_queue ON COMMIT DROP AS
SELECT queue.id
  FROM public.work_queue queue
  JOIN public.queue_definition definition ON definition.id = queue.definition_id
 WHERE definition.queue_purpose = 'GENERAL'
   AND queue.warehouse_id <> '00000000-0000-0000-0000-000000000001'::uuid
   AND queue.id = md5(
         queue.warehouse_id::text || ':' || queue.definition_id::text
         || ':global-work-queue:v23')::uuid
   AND NOT EXISTS (
     SELECT 1 FROM public.queue_entry entry WHERE entry.queue_id = queue.id
   )
   AND NOT EXISTS (
     SELECT 1
       FROM public.task_time_event time_event
       JOIN public.queue_entry entry ON entry.id = time_event.queue_entry_id
      WHERE entry.queue_id = queue.id
   );

-- domain_event is append-only during normal operation.  These exact V23 synthetic aggregates
-- are the narrow audited exception: their only possible history is V23 baseline/batch artefacts
-- and the operational guard above has proved that no task data refers to them.
ALTER TABLE public.domain_event DISABLE TRIGGER USER;

DELETE FROM public.inbox_message inbox
USING v24_unused_synthetic_queue queue
WHERE inbox.aggregate_type = 'WORK_QUEUE'
  AND inbox.aggregate_id = queue.id::text;

DELETE FROM public.version_gap_quarantine quarantine
USING v24_unused_synthetic_queue queue
WHERE quarantine.aggregate_type = 'WORK_QUEUE'
  AND quarantine.aggregate_id = queue.id::text;

DELETE FROM public.consumer_aggregate_checkpoint checkpoint
USING v24_unused_synthetic_queue queue
WHERE checkpoint.aggregate_type = 'WORK_QUEUE'
  AND checkpoint.aggregate_id = queue.id::text;

DELETE FROM public.projection_checkpoint checkpoint
USING v24_unused_synthetic_queue queue
WHERE checkpoint.aggregate_type = 'WORK_QUEUE'
  AND checkpoint.aggregate_id = queue.id::text;

DELETE FROM public.aggregate_snapshot snapshot
USING v24_unused_synthetic_queue queue
WHERE snapshot.aggregate_type = 'WORK_QUEUE'
  AND snapshot.aggregate_id = queue.id::text;

DELETE FROM public.outbox_event outbox
USING v24_unused_synthetic_queue queue
WHERE outbox.aggregate_type = 'WORK_QUEUE'
  AND outbox.aggregate_id = queue.id::text;

DELETE FROM public.domain_event event
USING v24_unused_synthetic_queue queue
WHERE event.aggregate_type = 'WORK_QUEUE'
  AND event.aggregate_id = queue.id::text;

ALTER TABLE public.domain_event ENABLE TRIGGER USER;

DELETE FROM public.event_stream_head stream
USING v24_unused_synthetic_queue queue
WHERE stream.aggregate_type = 'WORK_QUEUE'
  AND stream.aggregate_id = queue.id::text;

DELETE FROM public.work_queue_class_binding binding
USING v24_unused_synthetic_queue queue
WHERE binding.queue_id = queue.id;

DELETE FROM public.work_queue queue
USING v24_unused_synthetic_queue synthetic
WHERE queue.id = synthetic.id;

-- Remove every V23-only schema object.  Runtime state now belongs solely to work_queue and its
-- existing work_queue_class_binding rows; QueueDefinition is again a pure global catalog record.
DROP TABLE public.queue_definition_class_binding;

DROP INDEX public.idx_queue_definition_global_order;

ALTER TABLE public.queue_definition
  DROP CONSTRAINT ck_queue_definition_sort_order,
  DROP CONSTRAINT ck_queue_definition_holding,
  DROP CONSTRAINT ck_queue_definition_holding_values,
  DROP CONSTRAINT ck_queue_definition_result_photos,
  DROP COLUMN sort_order,
  DROP COLUMN active,
  DROP COLUMN hidden,
  DROP COLUMN collapsed,
  DROP COLUMN holding_period_minutes,
  DROP COLUMN notification_threshold,
  DROP COLUMN notify_when_threshold_reached,
  DROP COLUMN result_photo_min_count;
