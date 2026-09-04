-- Exact ready-transfer lookup used to enrich one immutable cross-warehouse driver route.

CREATE INDEX idx_transfer_plan_route_ready_cargo
  ON public.transfer_plan (
    trip_driver_id,
    trip_vehicle_id,
    planned_departure_at,
    planned_arrival_at
  )
  WHERE state = 'CONFIRMED'
    AND reservation_readiness = 'RESERVED'
    AND workflow_state = 'READY';
