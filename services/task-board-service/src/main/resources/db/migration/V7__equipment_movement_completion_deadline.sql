-- Equipment-movement tasks are the only task type whose completion is fenced by deadline.
-- Existing task-board tasks keep their historical deadline semantics.
ALTER TABLE public.board_task
  ADD COLUMN completion_deadline_enforced boolean NOT NULL DEFAULT false;
