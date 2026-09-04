-- Immutable executable route operations projected with one replaceable-until-frozen shift plan.

CREATE TABLE public.driver_shift_route_operation (
  id uuid NOT NULL,
  version bigint NOT NULL DEFAULT 0,
  driver_shift_plan_id uuid NOT NULL,
  operation_sequence integer NOT NULL,
  operation_kind varchar(32) NOT NULL,
  warehouse_id uuid,
  source_task_id uuid,
  location_label varchar(500) NOT NULL,
  planned_arrival timestamptz NOT NULL,
  planned_departure timestamptz NOT NULL,
  load_before integer NOT NULL,
  load_after integer NOT NULL,
  CONSTRAINT driver_shift_route_operation_pkey PRIMARY KEY (id),
  CONSTRAINT fk_driver_shift_route_operation_plan FOREIGN KEY (driver_shift_plan_id)
    REFERENCES public.driver_shift_plan(id) ON DELETE CASCADE,
  CONSTRAINT uk_driver_shift_route_operation_sequence
    UNIQUE (driver_shift_plan_id, operation_sequence),
  CONSTRAINT ck_driver_shift_route_operation_version CHECK (version >= 0),
  CONSTRAINT ck_driver_shift_route_operation_sequence CHECK (operation_sequence >= 1),
  CONSTRAINT ck_driver_shift_route_operation_kind CHECK (operation_kind IN (
    'ORIGIN_START','INBOUND_POSITIONING','DEPOT_LOAD','DELIVERY','PICKUP',
    'DEPOT_UNLOAD','DEPOT_RETURN','RETURN_POSITIONING'
  )),
  CONSTRAINT ck_driver_shift_route_operation_label CHECK (btrim(location_label) <> ''),
  CONSTRAINT ck_driver_shift_route_operation_time CHECK (
    (operation_kind IN ('INBOUND_POSITIONING','RETURN_POSITIONING')
      AND planned_arrival >= planned_departure)
    OR
    (operation_kind NOT IN ('INBOUND_POSITIONING','RETURN_POSITIONING')
      AND planned_departure >= planned_arrival)
  ),
  CONSTRAINT ck_driver_shift_route_operation_location CHECK (
    (operation_kind IN ('DELIVERY','PICKUP')
      AND source_task_id IS NOT NULL AND warehouse_id IS NULL)
    OR
    (operation_kind NOT IN ('DELIVERY','PICKUP')
      AND source_task_id IS NULL AND warehouse_id IS NOT NULL)
  ),
  CONSTRAINT ck_driver_shift_route_operation_load CHECK (
    load_before >= 0 AND load_after >= 0
    AND (operation_kind NOT IN ('ORIGIN_START','INBOUND_POSITIONING','RETURN_POSITIONING')
      OR load_before = load_after)
  )
);

CREATE INDEX idx_driver_shift_route_operation_plan
  ON public.driver_shift_route_operation(driver_shift_plan_id, operation_sequence);
