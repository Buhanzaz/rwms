-- One estimate catalog is authoritative for every warehouse.  The warehouse_id on a
-- catalog version is retained only as immutable creation/audit context.
DROP INDEX IF EXISTS public.uk_catalog_version_active;
DROP INDEX IF EXISTS public.uk_catalog_version_active_global;

WITH ranked_active AS (
  SELECT
    id,
    row_number() OVER (
      ORDER BY
        CASE
          WHEN warehouse_id = '00000000-0000-0000-0000-000000000001'::uuid THEN 0
          ELSE 1
        END,
        activated_at DESC NULLS LAST,
        created_at DESC,
        id
    ) AS position
  FROM public.catalog_version
  WHERE state = 'ACTIVE'
)
UPDATE public.catalog_version AS catalog
SET state = 'SUPERSEDED',
    version = catalog.version + 1
FROM ranked_active
WHERE catalog.id = ranked_active.id
  AND ranked_active.position > 1;

CREATE UNIQUE INDEX uk_catalog_version_active_global
  ON public.catalog_version ((1))
  WHERE state = 'ACTIVE';

COMMENT ON COLUMN public.catalog_version.warehouse_id IS
  'Immutable creation/audit context. Catalog content, history, and active truth are global.';

-- The queue UUID stored in catalog and repair snapshots is a stable global queue-definition
-- UUID.  Task-board resolves it to the target warehouse binding at command time.
COMMENT ON COLUMN public.catalog_node.routing_queue_id IS
  'Global task-board queue-definition UUID; never a warehouse-local queue name.';
COMMENT ON COLUMN public.estimate_plan_stage.routing_queue_id IS
  'Global task-board queue-definition UUID captured from the catalog.';
COMMENT ON COLUMN public.repair_stage.routing_queue_id IS
  'Global task-board queue-definition UUID captured from the repair plan.';

ALTER TABLE public.catalog_node
  ADD COLUMN forces_capital_repair boolean NOT NULL DEFAULT false,
  ADD COLUMN characteristic_id uuid,
  ADD COLUMN characteristic_name varchar(255),
  ADD CONSTRAINT ck_catalog_node_forces_capital
    CHECK (NOT forces_capital_repair OR node_type = 'WORK'),
  ADD CONSTRAINT ck_catalog_node_characteristic
    CHECK (
      (characteristic_id IS NULL AND characteristic_name IS NULL)
      OR
      (
        node_type = 'MATERIAL'
        AND characteristic_id IS NOT NULL
        AND length(btrim(characteristic_name)) BETWEEN 1 AND 255
      )
    );

COMMENT ON COLUMN public.catalog_node.forces_capital_repair IS
  'Global work definition flag. Any selected flagged work forces CAPITAL complexity.';
COMMENT ON COLUMN public.catalog_node.characteristic_id IS
  'Canonical asset-service cabin characteristic UUID applied only after successful acceptance.';

-- Repair-complexity badge colors are one global estimate-catalog setting.
CREATE TABLE public.repair_complexity_colors (
  id uuid NOT NULL,
  version bigint NOT NULL DEFAULT 0,
  light_color varchar(7) NOT NULL,
  medium_color varchar(7) NOT NULL,
  complex_color varchar(7) NOT NULL,
  capital_color varchar(7) NOT NULL,
  created_at timestamptz NOT NULL,
  updated_at timestamptz NOT NULL,
  CONSTRAINT repair_complexity_colors_pkey PRIMARY KEY (id),
  CONSTRAINT ck_repair_complexity_colors_singleton
    CHECK (id = '00000000-0000-0000-0000-000000000001'::uuid),
  CONSTRAINT ck_repair_complexity_colors_version CHECK (version >= 0),
  CONSTRAINT ck_repair_complexity_light_color
    CHECK (light_color ~ '^#[0-9A-F]{6}$'),
  CONSTRAINT ck_repair_complexity_medium_color
    CHECK (medium_color ~ '^#[0-9A-F]{6}$'),
  CONSTRAINT ck_repair_complexity_complex_color
    CHECK (complex_color ~ '^#[0-9A-F]{6}$'),
  CONSTRAINT ck_repair_complexity_capital_color
    CHECK (capital_color ~ '^#[0-9A-F]{6}$')
);

INSERT INTO public.repair_complexity_colors (
  id,
  version,
  light_color,
  medium_color,
  complex_color,
  capital_color,
  created_at,
  updated_at
) VALUES (
  '00000000-0000-0000-0000-000000000001'::uuid,
  0,
  '#22C55E',
  '#EAB308',
  '#F97316',
  '#DC2626',
  now(),
  now()
);

-- A transfer parks the same repair chain; it never creates a replacement repair.  These
-- coordinates make prepare/arrival completion replayable by logistics-service.
ALTER TABLE public.maintenance_repair
  ADD COLUMN movement_to_shipment boolean NOT NULL DEFAULT false,
  ADD COLUMN transfer_state varchar(24) NOT NULL DEFAULT 'NONE',
  ADD COLUMN transfer_document_id uuid,
  ADD COLUMN transfer_line_id uuid,
  ADD COLUMN transfer_target_warehouse_id uuid,
  ADD CONSTRAINT ck_repair_transfer_state
    CHECK (transfer_state IN ('NONE', 'DEPARTURE_PREPARED')),
  ADD CONSTRAINT ck_repair_transfer_coordinates
    CHECK (
      (
        transfer_state = 'NONE'
        AND transfer_document_id IS NULL
        AND transfer_line_id IS NULL
        AND transfer_target_warehouse_id IS NULL
      )
      OR
      (
        transfer_state = 'DEPARTURE_PREPARED'
        AND transfer_document_id IS NOT NULL
        AND transfer_line_id IS NOT NULL
        AND transfer_target_warehouse_id IS NOT NULL
        AND transfer_target_warehouse_id <> warehouse_id
      )
    );

CREATE INDEX idx_repair_transfer_line
  ON public.maintenance_repair(transfer_line_id)
  WHERE transfer_state = 'DEPARTURE_PREPARED';

ALTER TABLE public.domain_event
  DROP CONSTRAINT ck_maintenance_event_type,
  ADD CONSTRAINT ck_maintenance_event_type CHECK (event_type IN (
    'maintenance.catalog-version.imported.v1',
    'maintenance.catalog-version.changed.v1',
    'maintenance.catalog-version.activated.v1',
    'maintenance.catalog-version.superseded.v1',
    'maintenance.estimate.created.v1',
    'maintenance.estimate.draft-changed.v1',
    'maintenance.estimate.completed.v1',
    'maintenance.estimate.amended.v1',
    'maintenance.repair.created.v1',
    'maintenance.repair.plan-changed.v1',
    'maintenance.repair.queued.v1',
    'maintenance.repair.stage-completed.v1',
    'maintenance.repair.pending-acceptance.v1',
    'maintenance.repair.rework-created.v1',
    'maintenance.repair.transfer-prepared.v1',
    'maintenance.repair.transferred.v1',
    'maintenance.repair.accepted.v1',
    'maintenance.repair.written-off.v1'
  ));
