-- Preserve the former warehouse rows as migration provenance. Runtime reads only the
-- singleton tables; conflicting warehouse values require an explicit consolidation choice.
DO $$
BEGIN
  IF (SELECT count(DISTINCT days) FROM public.estimate_creation_window_settings) > 1 THEN
    RAISE EXCEPTION 'Cannot globalize estimate creation window: warehouse values differ';
  END IF;
  IF (SELECT count(DISTINCT (light_boundary_minutes, medium_boundary_minutes, complex_boundary_minutes))
        FROM public.repair_complexity_settings) > 1 THEN
    RAISE EXCEPTION 'Cannot globalize repair complexity: warehouse values differ';
  END IF;
END;
$$;

CREATE TABLE public.global_estimate_creation_window_settings (
  id uuid PRIMARY KEY CHECK (id = '00000000-0000-0000-0000-000000000001'::uuid),
  version bigint NOT NULL DEFAULT 0 CHECK (version >= 0),
  days integer NOT NULL CHECK (days BETWEEN 1 AND 3650),
  created_at timestamptz NOT NULL,
  updated_at timestamptz NOT NULL
);

INSERT INTO public.global_estimate_creation_window_settings (id, days, created_at, updated_at)
SELECT '00000000-0000-0000-0000-000000000001', coalesce(min(days), 7), now(), now()
  FROM public.estimate_creation_window_settings;

CREATE TABLE public.global_repair_complexity_settings (
  id uuid PRIMARY KEY CHECK (id = '00000000-0000-0000-0000-000000000001'::uuid),
  version bigint NOT NULL DEFAULT 0 CHECK (version >= 0),
  light_boundary_minutes integer NOT NULL,
  medium_boundary_minutes integer NOT NULL,
  complex_boundary_minutes integer NOT NULL,
  created_at timestamptz NOT NULL,
  updated_at timestamptz NOT NULL,
  CONSTRAINT ck_global_repair_complexity_boundaries CHECK (
    light_boundary_minutes > 0
    AND light_boundary_minutes < medium_boundary_minutes
    AND medium_boundary_minutes < complex_boundary_minutes)
);

INSERT INTO public.global_repair_complexity_settings (
  id, light_boundary_minutes, medium_boundary_minutes, complex_boundary_minutes, created_at, updated_at)
SELECT '00000000-0000-0000-0000-000000000001',
       coalesce(min(light_boundary_minutes), 60),
       coalesce(min(medium_boundary_minutes), 180),
       coalesce(min(complex_boundary_minutes), 360), now(), now()
  FROM public.repair_complexity_settings;

COMMENT ON TABLE public.estimate_creation_window_settings IS
  'Archived warehouse settings retained by V51; runtime uses global_estimate_creation_window_settings.';
COMMENT ON TABLE public.repair_complexity_settings IS
  'Archived warehouse settings retained by V51; runtime uses global_repair_complexity_settings.';

-- A prior warehouse override may now apply to repairs that previously used defaults.
-- Keep the existing readiness fence and durable, idempotent recalculation mechanism.
INSERT INTO public.integration_reconciliation (
  id, repair_id, dependency_type, operation_type, idempotency_key, state,
  attempt_count, next_attempt_at, response_snapshot, review_version, created_at, updated_at)
SELECT md5('maintenance-v51-global-complexity-work:' || repair.id::text)::uuid,
       repair.id, 'ASSET', 'SYNC_REPAIR_COMPLEXITY_STATUS',
       md5('maintenance-v51-global-complexity-command:' || repair.id::text)::uuid,
       'PENDING', 0, now(), jsonb_build_object('repairId', repair.id::text), 0, now(), now()
  FROM public.maintenance_repair repair
  LEFT JOIN public.repair_complexity_settings previous ON previous.warehouse_id = repair.warehouse_id
  CROSS JOIN public.global_repair_complexity_settings settings
 WHERE repair.execution_state IN ('QUEUED', 'IN_PROGRESS')
   AND repair.acceptance_state NOT IN ('ACCEPTED', 'WRITTEN_OFF')
   AND (coalesce(previous.light_boundary_minutes, 60),
        coalesce(previous.medium_boundary_minutes, 180),
        coalesce(previous.complex_boundary_minutes, 360))
       IS DISTINCT FROM (settings.light_boundary_minutes, settings.medium_boundary_minutes,
                         settings.complex_boundary_minutes);
