UPDATE public.board_task
   SET planned_driver_worker_id = NULL,
       planned_driver_name_snapshot = NULL
 WHERE driver_audience_mode = 'WAREHOUSE_DRIVERS'
   AND (planned_driver_worker_id IS NOT NULL OR planned_driver_name_snapshot IS NOT NULL);

ALTER TABLE public.board_task
  DROP CONSTRAINT ck_board_task_driver_audience,
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
        AND planned_driver_worker_id IS NULL
        AND planned_driver_name_snapshot IS NULL
      )
    );
