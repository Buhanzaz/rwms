ALTER TABLE public.company_kpi_settings
  ADD COLUMN status varchar(32) NOT NULL DEFAULT 'UNCONFIGURED',
  ADD COLUMN data_available_from date,
  ADD COLUMN active_schedule_id uuid,
  ADD COLUMN pending_schedule_id uuid;

ALTER TABLE public.company_kpi_settings
  ADD CONSTRAINT fk_company_kpi_settings_active_schedule
    FOREIGN KEY (active_schedule_id) REFERENCES public.kpi_work_schedule(id),
  ADD CONSTRAINT fk_company_kpi_settings_pending_schedule
    FOREIGN KEY (pending_schedule_id) REFERENCES public.kpi_work_schedule(id),
  ADD CONSTRAINT ck_company_kpi_settings_status
    CHECK (status IN ('UNCONFIGURED','DRAFT','SCHEDULED','ACTIVE')),
  ADD CONSTRAINT ck_company_kpi_settings_distinct_schedule
    CHECK (active_schedule_id IS NULL OR active_schedule_id IS DISTINCT FROM pending_schedule_id);

ALTER TABLE public.kpi_work_schedule
  ADD COLUMN company_id uuid,
  ALTER COLUMN warehouse_id DROP NOT NULL;

ALTER TABLE public.kpi_work_schedule
  ADD CONSTRAINT ck_kpi_work_schedule_owner
    CHECK ((company_id IS NULL) <> (warehouse_id IS NULL));

CREATE INDEX idx_kpi_work_schedule_company_effective
  ON public.kpi_work_schedule(company_id, effective_from, scheduled)
  WHERE company_id IS NOT NULL;

CREATE UNIQUE INDEX uk_kpi_work_schedule_company_scheduled_effective
  ON public.kpi_work_schedule(company_id, effective_from)
  WHERE company_id IS NOT NULL AND scheduled;

ALTER TABLE public.kpi_activation_receipt
  ADD COLUMN company_id uuid,
  ALTER COLUMN warehouse_id DROP NOT NULL;

ALTER TABLE public.kpi_activation_receipt
  ADD CONSTRAINT ck_kpi_activation_receipt_owner
    CHECK ((company_id IS NULL) <> (warehouse_id IS NULL));

COMMENT ON COLUMN public.kpi_work_schedule.warehouse_id IS
  'Legacy owner retained only for immutable historical revisions created before V47.';

COMMENT ON COLUMN public.kpi_activation_receipt.warehouse_id IS
  'Legacy owner retained only for immutable activation receipts created before V47.';
