DO $$
DECLARE
  global_head_count bigint;
  legacy_head_count bigint;
  global_owner_count bigint;
  global_head_version bigint;
  legacy_head_version bigint;
  legacy_warehouse_id uuid;
BEGIN
  SELECT count(*) INTO global_head_count
    FROM public.company_kpi_settings;
  SELECT count(*) INTO legacy_head_count
    FROM public.warehouse_kpi_settings;
  SELECT count(DISTINCT owner_id) INTO global_owner_count
    FROM (
      SELECT id AS owner_id FROM public.company_kpi_settings
      UNION
      SELECT company_id AS owner_id
        FROM public.kpi_work_schedule
       WHERE company_id IS NOT NULL
      UNION
      SELECT company_id AS owner_id
        FROM public.kpi_activation_receipt
       WHERE company_id IS NOT NULL
    ) AS global_owners;

  IF global_head_count > 1 THEN
    RAISE EXCEPTION
      'V48 requires at most one current KPI settings head; reconcile duplicate heads before upgrading';
  END IF;

  IF global_owner_count > 1 THEN
    RAISE EXCEPTION
      'V48 cannot remove KPI company ownership from multiple distinct owner identifiers';
  END IF;

  IF legacy_head_count > 1 THEN
    RAISE EXCEPTION
      'V48 cannot choose between multiple legacy warehouse KPI settings heads';
  END IF;

  -- The old head was owned by one warehouse even though the installation had one
  -- company. When no newer global head exists, promote its complete current state
  -- and every schedule revision owned by that warehouse into the global sequence.
  IF global_head_count = 0 AND legacy_head_count = 1 THEN
    SELECT warehouse_id
      INTO legacy_warehouse_id
      FROM public.warehouse_kpi_settings;

    UPDATE public.kpi_work_schedule
       SET warehouse_id = NULL,
           company_id = '00000000-0000-0000-0000-000000000001'::uuid
     WHERE warehouse_id = legacy_warehouse_id;

    INSERT INTO public.company_kpi_settings(
      id, version, revision_marker, palette_id, status, data_available_from,
      active_schedule_id, pending_schedule_id)
    SELECT
      '00000000-0000-0000-0000-000000000001'::uuid,
      version,
      revision_marker,
      palette_id,
      status,
      data_available_from,
      active_schedule_id,
      pending_schedule_id
    FROM public.warehouse_kpi_settings;
  ELSIF global_head_count = 1 AND legacy_head_count = 1 THEN
    SELECT version INTO global_head_version
      FROM public.company_kpi_settings;
    SELECT version INTO legacy_head_version
      FROM public.warehouse_kpi_settings;

    -- A strictly newer global head wins. The legacy palette, schedules and
    -- activation receipts remain in their own historical tables; only its
    -- obsolete mutable head is retired below.
    IF global_head_version <= legacy_head_version THEN
      RAISE EXCEPTION
        'V48 cannot prove the global KPI settings head is newer than the legacy warehouse head';
    END IF;
  END IF;

  UPDATE public.company_kpi_settings
     SET id = '00000000-0000-0000-0000-000000000001'::uuid
   WHERE id <> '00000000-0000-0000-0000-000000000001'::uuid;
END;
$$;

ALTER TABLE public.company_kpi_settings
  RENAME TO kpi_settings;

ALTER TABLE public.kpi_settings
  RENAME CONSTRAINT uk_company_kpi_settings_palette TO uk_kpi_settings_palette;
ALTER TABLE public.kpi_settings
  RENAME CONSTRAINT fk_company_kpi_settings_palette TO fk_kpi_settings_palette;
ALTER TABLE public.kpi_settings
  RENAME CONSTRAINT fk_company_kpi_settings_active_schedule TO fk_kpi_settings_active_schedule;
ALTER TABLE public.kpi_settings
  RENAME CONSTRAINT fk_company_kpi_settings_pending_schedule TO fk_kpi_settings_pending_schedule;
ALTER TABLE public.kpi_settings
  RENAME CONSTRAINT ck_company_kpi_settings_status TO ck_kpi_settings_status;
ALTER TABLE public.kpi_settings
  RENAME CONSTRAINT ck_company_kpi_settings_distinct_schedule
    TO ck_kpi_settings_distinct_schedule;
ALTER TABLE public.kpi_settings
  ADD CONSTRAINT ck_kpi_settings_singleton_id
    CHECK (id = '00000000-0000-0000-0000-000000000001'::uuid);

ALTER TABLE public.kpi_work_schedule
  DROP CONSTRAINT ck_kpi_work_schedule_owner;
ALTER TABLE public.kpi_activation_receipt
  DROP CONSTRAINT ck_kpi_activation_receipt_owner;

UPDATE public.kpi_work_schedule
   SET company_id = NULL
 WHERE company_id IS NOT NULL;
UPDATE public.kpi_activation_receipt
   SET company_id = NULL
 WHERE company_id IS NOT NULL;

DROP INDEX public.idx_kpi_work_schedule_company_effective;
DROP INDEX public.uk_kpi_work_schedule_company_scheduled_effective;

ALTER TABLE public.kpi_work_schedule
  DROP COLUMN company_id;
ALTER TABLE public.kpi_activation_receipt
  DROP COLUMN company_id;

COMMENT ON COLUMN public.kpi_work_schedule.warehouse_id IS
  'Immutable historical warehouse owner; global revisions use NULL.';
COMMENT ON COLUMN public.kpi_activation_receipt.warehouse_id IS
  'Immutable historical warehouse owner; global activation receipts use NULL.';

DO $$
BEGIN
  IF EXISTS (
    SELECT 1
      FROM public.kpi_work_schedule
     WHERE warehouse_id IS NULL
       AND scheduled
     GROUP BY effective_from
    HAVING count(*) > 1
  ) THEN
    RAISE EXCEPTION
      'V48 requires one scheduled global KPI revision per effective date';
  END IF;
END;
$$;

CREATE INDEX idx_kpi_work_schedule_global_effective
  ON public.kpi_work_schedule(effective_from, scheduled)
  WHERE warehouse_id IS NULL;

CREATE UNIQUE INDEX uk_kpi_work_schedule_global_scheduled_effective
  ON public.kpi_work_schedule(effective_from)
  WHERE warehouse_id IS NULL AND scheduled;

DROP TABLE public.warehouse_kpi_settings;
