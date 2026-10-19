-- The estimate/repair catalog is one global configuration shared by every warehouse.
-- warehouse_id remains immutable creation/audit context for historical event compatibility;
-- it no longer partitions active catalog truth.
DO $$
BEGIN
  IF (SELECT count(*) FROM public.catalog_version WHERE state = 'ACTIVE') > 1 THEN
    RAISE EXCEPTION
      'Global catalog migration requires exactly one active catalog version after explicit data consolidation';
  END IF;
END
$$;

DROP INDEX IF EXISTS public.uk_catalog_version_active;

CREATE UNIQUE INDEX uk_catalog_version_active_global
  ON public.catalog_version ((1))
  WHERE state = 'ACTIVE';

COMMENT ON COLUMN public.catalog_version.warehouse_id IS
  'Immutable creation/routing context; catalog content and active truth are global.';
