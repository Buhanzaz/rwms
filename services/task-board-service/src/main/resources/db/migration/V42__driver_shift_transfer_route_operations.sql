-- Add transfer cargo identity and the effective vehicle capacity without rewriting legacy plans.

ALTER TABLE public.driver_shift_plan
  ADD COLUMN cabin_capacity integer,
  ADD CONSTRAINT ck_driver_shift_plan_cabin_capacity
    CHECK (cabin_capacity IS NULL OR cabin_capacity BETWEEN 1 AND 2);

ALTER TABLE public.driver_shift_route_operation
  ADD COLUMN source_transfer_id uuid;

ALTER TABLE public.driver_shift_route_operation
  DROP CONSTRAINT ck_driver_shift_route_operation_kind,
  ADD CONSTRAINT ck_driver_shift_route_operation_kind CHECK (operation_kind IN (
    'ORIGIN_START','TRANSFER_LOAD','INBOUND_POSITIONING','TRANSFER_UNLOAD',
    'DEPOT_LOAD','DELIVERY','PICKUP','DEPOT_UNLOAD','DEPOT_RETURN','RETURN_POSITIONING'
  ));

ALTER TABLE public.driver_shift_route_operation
  DROP CONSTRAINT ck_driver_shift_route_operation_location,
  ADD CONSTRAINT ck_driver_shift_route_operation_location CHECK (
    (operation_kind IN ('DELIVERY','PICKUP')
      AND source_task_id IS NOT NULL
      AND source_transfer_id IS NULL
      AND warehouse_id IS NULL)
    OR
    (operation_kind IN ('TRANSFER_LOAD','TRANSFER_UNLOAD')
      AND source_task_id IS NULL
      AND source_transfer_id IS NOT NULL
      AND warehouse_id IS NOT NULL)
    OR
    (operation_kind NOT IN ('DELIVERY','PICKUP','TRANSFER_LOAD','TRANSFER_UNLOAD')
      AND source_task_id IS NULL
      AND source_transfer_id IS NULL
      AND warehouse_id IS NOT NULL)
  );
