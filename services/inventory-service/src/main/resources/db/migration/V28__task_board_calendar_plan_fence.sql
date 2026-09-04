-- Final-plan generations prepared before task-board became the common object-calendar owner do
-- not contain reproducible calendar evidence. They must be prepared again rather than silently
-- completed with the retired inventory weekday/capacity rules. Completed history remains intact.
ALTER TABLE public.inventory_final_plan
  ADD COLUMN task_board_calendar_from date,
  ADD COLUMN task_board_calendar_through date,
  ADD COLUMN task_board_calendar_fingerprint varchar(64),
  ADD COLUMN task_board_calendar_snapshot jsonb,
  ADD CONSTRAINT ck_inventory_final_plan_task_board_calendar_evidence CHECK (
    (
      task_board_calendar_from IS NULL
      AND task_board_calendar_through IS NULL
      AND task_board_calendar_fingerprint IS NULL
      AND task_board_calendar_snapshot IS NULL
    )
    OR (
      task_board_calendar_from IS NOT NULL
      AND task_board_calendar_through IS NOT NULL
      AND task_board_calendar_through >= task_board_calendar_from
      AND task_board_calendar_fingerprint ~ '^[0-9a-f]{64}$'
      AND jsonb_typeof(task_board_calendar_snapshot) = 'object'
    )
  );

UPDATE public.inventory_final_plan
SET state = 'STALE', updated_at = clock_timestamp()
WHERE state = 'DRAFT';
