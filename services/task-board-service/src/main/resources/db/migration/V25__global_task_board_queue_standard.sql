-- GENERAL queues are one system-wide task-board standard.  A warehouse-local
-- work_queue remains only a physical projection: queue entries and their
-- immutable history continue to reference its existing UUID.  Runtime code
-- synchronizes that projection and emits normal WORK_QUEUE events after this
-- schema migration has selected the reviewed global source below.

ALTER TABLE public.queue_definition
  ADD COLUMN sort_order integer NOT NULL DEFAULT 0,
  ADD COLUMN active boolean NOT NULL DEFAULT true,
  ADD COLUMN hidden boolean NOT NULL DEFAULT false,
  ADD COLUMN collapsed boolean NOT NULL DEFAULT false,
  ADD COLUMN holding_period_minutes integer,
  ADD COLUMN notification_threshold integer,
  ADD COLUMN notify_when_threshold_reached boolean NOT NULL DEFAULT false,
  ADD COLUMN result_photo_min_count integer NOT NULL DEFAULT 1;

-- Keep the audited SPB standard where it exists.  A deterministic first
-- connection is used only when SPB has never had this particular queue.
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
 WHERE definition.id = source.definition_id;

-- Order is global and compact.  Holding queues remain terminal, matching the
-- established task-board invariant.
WITH ordered AS (
  SELECT definition.id,
         row_number() OVER (
           ORDER BY
             CASE WHEN definition.queue_type = 'HOLDING' THEN 1 ELSE 0 END,
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
 WHERE definition.id = ordered.id;

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

-- Use the same reviewed queue as the global class-template source.  Local
-- bindings are intentionally not deleted here: application reconciliation
-- performs the forward-only projection change with normal event streams.
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
    || ':global-task-board-binding:v25'
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
