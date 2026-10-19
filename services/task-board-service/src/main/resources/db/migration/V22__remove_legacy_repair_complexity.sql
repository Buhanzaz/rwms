-- maintenance-service is now the sole owner of repair-complexity boundaries.
-- Refuse to discard any non-default task-board value: it must first be moved
-- by the controlled maintenance import while the old deployment is active.

DO $$
BEGIN
  IF EXISTS (
    SELECT 1
      FROM public.warehouse_kpi_settings
     WHERE repair_light_boundary_minutes IS DISTINCT FROM 60
        OR repair_medium_boundary_minutes IS DISTINCT FROM 180
        OR repair_complex_boundary_minutes IS DISTINCT FROM 360
  ) THEN
    RAISE EXCEPTION
      'V22 cannot remove non-default task-board repair-complexity boundaries. On the old deployment, first run the controlled maintenance import, verify maintenance-service settings, reset the retired task-board values to 60/180/360, then retry V22.';
  END IF;
END
$$;

ALTER TABLE public.warehouse_kpi_settings
  DROP CONSTRAINT ck_warehouse_kpi_repair_complexity_boundaries,
  DROP COLUMN repair_light_boundary_minutes,
  DROP COLUMN repair_medium_boundary_minutes,
  DROP COLUMN repair_complex_boundary_minutes;
