ALTER TABLE public.board_task
  ADD COLUMN scheduled_date date,
  ADD COLUMN priority integer NOT NULL DEFAULT 3,
  ADD COLUMN pinned boolean NOT NULL DEFAULT false;

UPDATE public.board_task
SET scheduled_date = COALESCE(
  (deadline_at AT TIME ZONE 'Europe/Moscow')::date,
  (CURRENT_TIMESTAMP AT TIME ZONE 'Europe/Moscow')::date)
WHERE scheduled_date IS NULL;

ALTER TABLE public.board_task
  ALTER COLUMN scheduled_date SET NOT NULL,
  ALTER COLUMN scheduled_date
    SET DEFAULT ((CURRENT_TIMESTAMP AT TIME ZONE 'Europe/Moscow')::date),
  ADD CONSTRAINT ck_board_task_priority CHECK (priority BETWEEN 1 AND 5);

CREATE INDEX idx_board_task_warehouse_schedule
  ON public.board_task(warehouse_id, scheduled_date, status);
