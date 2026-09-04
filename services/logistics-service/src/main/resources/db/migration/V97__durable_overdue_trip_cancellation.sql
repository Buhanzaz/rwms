ALTER TABLE public.driver_logistics_task
  ADD COLUMN trip_expiry_requested_at timestamptz,
  ADD COLUMN trip_expiry_cargo_review_required boolean NOT NULL DEFAULT false;

CREATE INDEX ix_driver_logistics_task_overdue_trip
  ON public.driver_logistics_task(warehouse_id, scheduled_date, id)
  WHERE task_kind IN ('SHIPMENT', 'RETURN', 'TRANSFER')
    AND state IN ('REGISTERING', 'SCHEDULED', 'CURRENT');
