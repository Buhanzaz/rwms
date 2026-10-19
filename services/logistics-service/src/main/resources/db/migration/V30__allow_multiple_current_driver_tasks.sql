-- The current driver lane is an ordered work queue.  It may contain one entry
-- per available repair place (and ready removals), so it must not be limited
-- to a single local workflow row.
DROP INDEX IF EXISTS public.uk_driver_logistics_task_one_current;

ALTER TABLE public.driver_logistics_task
  ADD COLUMN manual_promotion_hold boolean NOT NULL DEFAULT false;

CREATE INDEX ix_driver_logistics_task_manual_hold
  ON public.driver_logistics_task(warehouse_id, manual_promotion_hold, state)
  WHERE manual_promotion_hold;
