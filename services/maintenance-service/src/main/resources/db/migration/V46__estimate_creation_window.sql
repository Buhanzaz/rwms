CREATE TABLE public.estimate_creation_window_settings (
  warehouse_id uuid NOT NULL,
  version bigint NOT NULL DEFAULT 0,
  days integer NOT NULL DEFAULT 7,
  created_at timestamptz NOT NULL,
  updated_at timestamptz NOT NULL,
  CONSTRAINT estimate_creation_window_settings_pkey PRIMARY KEY (warehouse_id),
  CONSTRAINT ck_estimate_creation_window_settings_version CHECK (version >= 0),
  CONSTRAINT ck_estimate_creation_window_settings_days CHECK (days BETWEEN 1 AND 3650),
  CONSTRAINT ck_estimate_creation_window_settings_time CHECK (updated_at >= created_at)
);

COMMENT ON TABLE public.estimate_creation_window_settings IS
  'Maintenance-owned warehouse-local window for opening a new estimate after return arrival.';

ALTER TABLE public.logistics_return_shortage
  ADD COLUMN arrived_at timestamptz;

COMMENT ON COLUMN public.logistics_return_shortage.arrived_at IS
  'Immutable logistics.return.inspection-required.v1 occurred_at used when the automatic estimate was created; historical rows may be null.';
