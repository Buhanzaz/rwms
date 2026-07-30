-- Worker-facing equipment movement tasks must always have a positive execution budget.
-- This table is workflow-owned rather than an event-store projection, so the in-place repair is
-- authoritative and idempotent.
UPDATE public.equipment_movement_task
SET planned_duration_minutes = 60
WHERE planned_duration_minutes IS NULL
   OR planned_duration_minutes < 1;

ALTER TABLE public.equipment_movement_task
    ALTER COLUMN planned_duration_minutes SET NOT NULL;

ALTER TABLE public.equipment_movement_task
    DROP CONSTRAINT ck_equipment_movement_task_duration;

ALTER TABLE public.equipment_movement_task
    ADD CONSTRAINT ck_equipment_movement_task_duration CHECK (
        planned_duration_minutes >= 1
    );
