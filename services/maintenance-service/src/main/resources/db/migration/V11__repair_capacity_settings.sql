CREATE TABLE public.repair_capacity_settings (
  warehouse_id uuid NOT NULL,
  version bigint NOT NULL DEFAULT 0,
  max_repairs_per_day integer NOT NULL DEFAULT 6,
  created_at timestamptz NOT NULL,
  updated_at timestamptz NOT NULL,
  CONSTRAINT repair_capacity_settings_pkey PRIMARY KEY (warehouse_id),
  CONSTRAINT ck_repair_capacity_settings_version CHECK (version >= 0),
  CONSTRAINT ck_repair_capacity_settings_max CHECK (max_repairs_per_day > 0)
);
