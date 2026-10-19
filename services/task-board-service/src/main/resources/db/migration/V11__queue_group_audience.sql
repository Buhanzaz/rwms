CREATE TABLE public.work_queue_group_binding (
  id uuid NOT NULL,
  version bigint NOT NULL DEFAULT 0,
  queue_id uuid NOT NULL,
  worker_group_id uuid NOT NULL,
  availability_mode varchar(16) NOT NULL DEFAULT 'AVAILABLE',
  stop_task_on_take boolean NOT NULL DEFAULT false,
  CONSTRAINT work_queue_group_binding_pkey PRIMARY KEY (id),
  CONSTRAINT fk_work_queue_group_binding_queue
    FOREIGN KEY (queue_id) REFERENCES public.work_queue(id),
  CONSTRAINT fk_work_queue_group_binding_group
    FOREIGN KEY (worker_group_id) REFERENCES public.worker_group(id),
  CONSTRAINT uk_work_queue_group_binding UNIQUE (queue_id, worker_group_id),
  CONSTRAINT ck_work_queue_group_binding_mode
    CHECK (availability_mode IN ('AVAILABLE', 'MANDATORY'))
);

CREATE INDEX idx_work_queue_group_binding_group
  ON public.work_queue_group_binding(worker_group_id, queue_id);

ALTER TABLE public.work_queue
  ADD COLUMN result_photo_min_count integer;

UPDATE public.work_queue
SET result_photo_min_count =
  CASE WHEN queue_type = 'HOLDING' THEN 0 ELSE 1 END;

ALTER TABLE public.work_queue
  ALTER COLUMN result_photo_min_count SET NOT NULL,
  ADD CONSTRAINT ck_work_queue_result_photo_min_count
    CHECK (result_photo_min_count BETWEEN 0 AND 20);
