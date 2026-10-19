-- Split native driver and worker installations while preserving legacy WorkerApp
-- registration tokens. Current clients register a Firebase Installation ID.
ALTER TABLE public.worker_device_registration
  ADD COLUMN target_kind varchar(16) NOT NULL DEFAULT 'TOKEN',
  ADD COLUMN app_surface varchar(16) NOT NULL DEFAULT 'WORKER';

ALTER TABLE public.worker_device_registration
  DROP CONSTRAINT ck_worker_device_status,
  ADD CONSTRAINT ck_worker_device_status
    CHECK (status IN ('ACTIVE', 'REVOKED')),
  ADD CONSTRAINT ck_worker_device_target_kind
    CHECK (target_kind IN ('TOKEN', 'FID')),
  ADD CONSTRAINT ck_worker_device_app_surface
    CHECK (app_surface IN ('WORKER', 'DRIVER'));

DROP INDEX public.uk_worker_device_provider_token;
CREATE UNIQUE INDEX uk_worker_device_provider_target
  ON public.worker_device_registration(provider, target_kind, provider_token);
CREATE INDEX idx_worker_device_push_audience
  ON public.worker_device_registration(worker_id, app_surface, status, updated_at);

-- A join notification is persisted in the same transaction as the driver's TAKE.
-- Delivery is at least once; event_id lets Android clients deduplicate retries.
CREATE TABLE public.worker_push_outbox (
  event_id uuid NOT NULL,
  worker_id uuid NOT NULL,
  warehouse_id uuid NOT NULL,
  entry_id uuid NOT NULL,
  revision bigint NOT NULL,
  event_type varchar(32) NOT NULL,
  status varchar(16) NOT NULL,
  attempt_count integer NOT NULL DEFAULT 0,
  available_at timestamptz NOT NULL,
  lease_until timestamptz,
  last_error varchar(1000),
  created_at timestamptz NOT NULL,
  updated_at timestamptz NOT NULL,
  CONSTRAINT worker_push_outbox_pkey PRIMARY KEY (event_id),
  CONSTRAINT fk_worker_push_outbox_worker
    FOREIGN KEY (worker_id) REFERENCES public.worker(id) ON DELETE CASCADE,
  CONSTRAINT ck_worker_push_outbox_revision CHECK (revision >= 0),
  CONSTRAINT ck_worker_push_outbox_event_type
    CHECK (event_type = 'TASK_JOIN_AVAILABLE'),
  CONSTRAINT ck_worker_push_outbox_status
    CHECK (status IN ('PENDING', 'SENDING', 'SENT', 'DEAD')),
  CONSTRAINT ck_worker_push_outbox_attempt_count CHECK (attempt_count >= 0)
);

CREATE INDEX idx_worker_push_outbox_pending
  ON public.worker_push_outbox(available_at, created_at, event_id)
  WHERE status IN ('PENDING', 'SENDING');

-- Every configured secondary class on a driver-logistics queue is the
-- required slinger role: driver TAKE notifies it, JOIN interrupts the
-- slinger's current group task, and completion resumes that task.
UPDATE public.work_queue_class_binding binding
   SET participation_policy = 'REQUIRED',
       stop_task_on_take = true,
       notify_on_primary_take = true,
       version = binding.version + 1
  FROM public.work_queue queue
  JOIN public.queue_definition definition ON definition.id = queue.definition_id
 WHERE queue.id = binding.queue_id
   AND definition.queue_purpose = 'LOGISTICS_DRIVER'
   AND binding.binding_order > 0
   AND (
     binding.participation_policy IS DISTINCT FROM 'REQUIRED'
     OR binding.stop_task_on_take IS DISTINCT FROM true
     OR binding.notify_on_primary_take IS DISTINCT FROM true
   );

UPDATE public.work_queue queue
   SET result_photo_min_count = GREATEST(queue.result_photo_min_count, 1)
  FROM public.queue_definition definition
 WHERE definition.id = queue.definition_id
   AND definition.queue_purpose = 'LOGISTICS_DRIVER';
