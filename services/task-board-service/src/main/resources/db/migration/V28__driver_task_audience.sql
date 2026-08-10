ALTER TABLE public.board_task
  ADD COLUMN driver_audience_mode varchar(32),
  ADD COLUMN planned_driver_worker_id uuid,
  ADD COLUMN planned_driver_name_snapshot varchar(512);

UPDATE public.board_task task
   SET driver_audience_mode = 'WAREHOUSE_DRIVERS'
 WHERE EXISTS (
   SELECT 1
     FROM public.task_sync_source source
    WHERE source.board_task_id = task.id
      AND source.source_client_id = 'logistics-service'
      AND source.source_type = 'LOGISTICS_DRIVER_TASK'
 );

ALTER TABLE public.board_task
  ADD CONSTRAINT ck_board_task_driver_audience_mode
    CHECK (
      driver_audience_mode IS NULL
      OR driver_audience_mode IN ('UNASSIGNED', 'ASSIGNED_DRIVER', 'WAREHOUSE_DRIVERS')
    ),
  ADD CONSTRAINT ck_board_task_driver_audience
    CHECK (
      (
        driver_audience_mode IS NULL
        AND planned_driver_worker_id IS NULL
        AND planned_driver_name_snapshot IS NULL
      )
      OR (
        driver_audience_mode = 'UNASSIGNED'
        AND planned_driver_worker_id IS NULL
        AND planned_driver_name_snapshot IS NULL
      )
      OR (
        driver_audience_mode = 'ASSIGNED_DRIVER'
        AND planned_driver_worker_id IS NOT NULL
        AND planned_driver_name_snapshot IS NOT NULL
      )
      OR (
        driver_audience_mode = 'WAREHOUSE_DRIVERS'
        AND (
          (
            planned_driver_worker_id IS NULL
            AND planned_driver_name_snapshot IS NULL
          )
          OR (
            planned_driver_worker_id IS NOT NULL
            AND planned_driver_name_snapshot IS NOT NULL
          )
        )
      )
    );

CREATE INDEX idx_board_task_driver_audience
  ON public.board_task(warehouse_id, driver_audience_mode, planned_driver_worker_id)
  WHERE driver_audience_mode IS NOT NULL;
