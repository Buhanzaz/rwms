ALTER TABLE public.work_queue_class_binding
  ADD COLUMN binding_order integer,
  ADD COLUMN notify_urgent boolean NOT NULL DEFAULT false;

WITH ordered AS (
  SELECT binding.id,
         row_number() OVER (
           PARTITION BY binding.queue_id
           ORDER BY worker_class.sort_order, worker_class.name, binding.id
         ) - 1 AS binding_order
  FROM public.work_queue_class_binding binding
  JOIN public.worker_class ON worker_class.id = binding.worker_class_id
)
UPDATE public.work_queue_class_binding binding
SET binding_order = ordered.binding_order
FROM ordered
WHERE ordered.id = binding.id;

ALTER TABLE public.work_queue_class_binding
  ALTER COLUMN binding_order SET NOT NULL,
  ADD CONSTRAINT ck_work_queue_binding_order
    CHECK (binding_order >= 0),
  ADD CONSTRAINT uk_work_queue_binding_order
    UNIQUE (queue_id, binding_order);

DROP TABLE public.work_queue_group_binding;

ALTER TABLE public.worker_group_member
  DROP COLUMN role_in_group;

ALTER TABLE public.queue_entry
  ADD COLUMN revision_marker uuid;

UPDATE public.queue_entry
SET revision_marker = md5(id::text || ':queue-entry:v14')::uuid;

ALTER TABLE public.queue_entry
  ALTER COLUMN revision_marker SET NOT NULL;
