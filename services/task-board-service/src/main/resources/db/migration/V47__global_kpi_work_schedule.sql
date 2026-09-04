ALTER TABLE public.kpi_settings
  ADD COLUMN status varchar(32) NOT NULL DEFAULT 'UNCONFIGURED',
  ADD COLUMN data_available_from date,
  ADD COLUMN active_schedule_id uuid,
  ADD COLUMN pending_schedule_id uuid;

ALTER TABLE public.kpi_settings
  ADD CONSTRAINT fk_kpi_settings_active_schedule
    FOREIGN KEY (active_schedule_id) REFERENCES public.kpi_work_schedule(id),
  ADD CONSTRAINT fk_kpi_settings_pending_schedule
    FOREIGN KEY (pending_schedule_id) REFERENCES public.kpi_work_schedule(id),
  ADD CONSTRAINT ck_kpi_settings_status
    CHECK (status IN ('UNCONFIGURED','DRAFT','SCHEDULED','ACTIVE')),
  ADD CONSTRAINT ck_kpi_settings_distinct_schedule
    CHECK (active_schedule_id IS NULL OR active_schedule_id IS DISTINCT FROM pending_schedule_id);

-- The current policy is installation-wide. Historic per-warehouse revisions remain immutable
-- evidence but their obsolete mutable heads must not remain available as a second runtime path.
DROP TABLE public.warehouse_kpi_settings;

ALTER TABLE public.kpi_work_schedule
  ALTER COLUMN warehouse_id DROP NOT NULL;

CREATE INDEX idx_kpi_work_schedule_global_effective
  ON public.kpi_work_schedule(effective_from, scheduled)
  WHERE warehouse_id IS NULL;

CREATE UNIQUE INDEX uk_kpi_work_schedule_global_scheduled_effective
  ON public.kpi_work_schedule(effective_from)
  WHERE warehouse_id IS NULL AND scheduled;

ALTER TABLE public.kpi_activation_receipt
  ALTER COLUMN warehouse_id DROP NOT NULL;

COMMENT ON COLUMN public.kpi_work_schedule.warehouse_id IS
  'Null for the installation-wide schedule; populated only on immutable pre-global history.';

COMMENT ON COLUMN public.kpi_activation_receipt.warehouse_id IS
  'Null for installation-wide activations; populated only on immutable pre-global history.';
