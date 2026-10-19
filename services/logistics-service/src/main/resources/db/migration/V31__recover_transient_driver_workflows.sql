-- Versions through V30 treated a finite transient retry budget as a human
-- reconciliation condition.  Those rows carry enough task-board evidence to
-- continue safely, so reopen only that exact legacy outcome once.
UPDATE public.driver_logistics_task
SET state = CASE
      WHEN task_board_task_id IS NULL THEN 'REGISTERING'
      WHEN task_board_done_at IS NOT NULL OR task_board_entry_status = 'DONE' THEN 'FINALIZING'
      ELSE 'SCHEDULED'
    END,
    retry_count = 0,
    next_attempt_at = clock_timestamp(),
    failure_code = NULL,
    updated_at = clock_timestamp()
WHERE state = 'RECONCILIATION_REQUIRED'
  AND failure_code = 'DEPENDENCY_TRANSIENT';
