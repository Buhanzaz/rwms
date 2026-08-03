-- A capital repair may be returned from the driver queue without deleting its movement history.
-- Only one non-cancelled movement for the same source remains allowed at a time.
ALTER TABLE public.driver_logistics_task
  DROP CONSTRAINT uk_driver_logistics_task_source_kind;

CREATE UNIQUE INDEX uk_driver_logistics_task_active_source_kind
  ON public.driver_logistics_task(source_type, source_id, task_kind)
  WHERE state <> 'CANCELLED';
