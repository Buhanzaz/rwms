-- Catalog business identifiers are UUIDs. Names and queue metadata are historical display
-- snapshots only; no legacy business code survives this schema version.

DROP INDEX IF EXISTS public.uk_catalog_version_active_global;
CREATE UNIQUE INDEX uk_catalog_version_active
  ON public.catalog_version(warehouse_id)
  WHERE state = 'ACTIVE';

COMMENT ON COLUMN public.catalog_version.warehouse_id IS
  'Owning warehouse for this catalog history and its one active version.';

ALTER TABLE public.catalog_node
  ADD COLUMN routing_queue_name varchar(255);

-- Legacy queue identifiers cannot be treated as names, and UUIDs must never leak into UI
-- labels. Use an explicit unavailable snapshot until task-board preflight refreshes it.
UPDATE public.catalog_node
SET routing_queue_name = 'Очередь недоступна'
WHERE routing_queue_id IS NOT NULL;

ALTER TABLE public.catalog_node
  DROP CONSTRAINT IF EXISTS uk_catalog_node_code,
  DROP CONSTRAINT IF EXISTS ck_catalog_node_code,
  DROP CONSTRAINT IF EXISTS ck_catalog_node_routing,
  DROP CONSTRAINT IF EXISTS ck_catalog_node_references,
  DROP CONSTRAINT IF EXISTS ck_catalog_node_furniture_equipment,
  DROP COLUMN code,
  DROP COLUMN furniture_equipment_code,
  DROP COLUMN routing_queue_code,
  DROP COLUMN opaque_references;

ALTER TABLE public.catalog_node
  RENAME COLUMN routing_queue_kind TO routing_queue_type;

ALTER TABLE public.catalog_node
  ADD CONSTRAINT ck_catalog_node_furniture_equipment
    CHECK (
      (furniture_equipment_id IS NULL AND furniture_equipment_name IS NULL)
      OR
      (node_type = 'MATERIAL'
        AND furniture_equipment_id IS NOT NULL
        AND length(btrim(furniture_equipment_name)) BETWEEN 1 AND 255)
    ),
  ADD CONSTRAINT ck_catalog_node_routing
    CHECK (
      (routing_queue_id IS NULL
        AND routing_queue_name IS NULL
        AND routing_queue_type IS NULL)
      OR
      (routing_queue_id IS NOT NULL
        AND length(btrim(routing_queue_name)) BETWEEN 1 AND 255
        AND length(btrim(routing_queue_type)) BETWEEN 1 AND 64)
    );

ALTER TABLE public.estimate_plan_stage
  ADD COLUMN routing_queue_name varchar(255);

UPDATE public.estimate_plan_stage
SET routing_queue_name = 'Очередь недоступна';

ALTER TABLE public.estimate_plan_stage
  DROP CONSTRAINT IF EXISTS ck_estimate_plan_routing,
  DROP COLUMN routing_queue_code;

ALTER TABLE public.estimate_plan_stage
  RENAME COLUMN routing_queue_kind TO routing_queue_type;

ALTER TABLE public.estimate_plan_stage
  ALTER COLUMN routing_queue_name SET NOT NULL,
  ADD CONSTRAINT ck_estimate_plan_routing
    CHECK (
      length(btrim(routing_queue_name)) BETWEEN 1 AND 255
      AND length(btrim(routing_queue_type)) BETWEEN 1 AND 64
    );

ALTER TABLE public.repair_stage
  ADD COLUMN routing_queue_name varchar(255);

UPDATE public.repair_stage
SET routing_queue_name = 'Очередь недоступна';

ALTER TABLE public.repair_stage
  DROP CONSTRAINT IF EXISTS ck_repair_stage_routing,
  DROP COLUMN routing_queue_code;

ALTER TABLE public.repair_stage
  RENAME COLUMN routing_queue_kind TO routing_queue_type;

ALTER TABLE public.repair_stage
  ALTER COLUMN routing_queue_name SET NOT NULL,
  ADD CONSTRAINT ck_repair_stage_routing
    CHECK (
      length(btrim(routing_queue_name)) BETWEEN 1 AND 255
      AND length(btrim(routing_queue_type)) BETWEEN 1 AND 64
    );

CREATE FUNCTION public.maintenance_v18_strip_catalog_identifiers(document jsonb)
RETURNS jsonb
LANGUAGE sql
IMMUTABLE
AS $$
  SELECT CASE jsonb_typeof(document)
    WHEN 'object' THEN COALESCE((
      SELECT jsonb_object_agg(entry.key, public.maintenance_v18_strip_catalog_identifiers(entry.value))
      FROM jsonb_each(document) AS entry
      WHERE entry.key <> ALL (ARRAY[
        'code', 'catalogNodeCode', 'routingQueueCode', 'queueCode', 'equipmentCode',
        'opaqueReferences', 'references'
      ])
    ), '{}'::jsonb)
    WHEN 'array' THEN COALESCE((
      SELECT jsonb_agg(public.maintenance_v18_strip_catalog_identifiers(value))
      FROM jsonb_array_elements(document) AS value
    ), '[]'::jsonb)
    ELSE document
  END
$$;

UPDATE public.estimate_line
SET catalog_snapshot = public.maintenance_v18_strip_catalog_identifiers(catalog_snapshot)
WHERE catalog_snapshot IS NOT NULL;

UPDATE public.repair_stage
SET work_lines = public.maintenance_v18_strip_catalog_identifiers(work_lines),
    material_lines = public.maintenance_v18_strip_catalog_identifiers(material_lines);

DROP FUNCTION public.maintenance_v18_strip_catalog_identifiers(jsonb);
