-- Frozen maintenance plans retain UUID identities and display snapshots, never business codes.

ALTER TABLE public.finding_plan_stage
  ADD COLUMN catalog_node_id uuid,
  ADD COLUMN catalog_node_name varchar(255),
  ADD COLUMN routing_queue_name varchar(255);

-- Existing frozen rows predate display-name snapshots. Retain their UUID identities, but use
-- explicit unavailable labels rather than an unsafe lookup or exposing UUIDs as UI text.
UPDATE public.finding_plan_stage
SET catalog_node_id = COALESCE(
      NULLIF(safe_snapshot ->> 'catalogNodeId', '')::uuid,
      md5('finding-plan-stage-node:' || finding_id || ':' || finding_revision || ':' || stage_no)::uuid),
    catalog_node_name = COALESCE(
      NULLIF(btrim(safe_snapshot ->> 'catalogNodeName'), ''),
      'Позиция каталога недоступна'),
    routing_queue_name = 'Очередь недоступна';

-- The preceding update fires the legacy deferred stage-limit trigger. PostgreSQL requires
-- those events to be drained before this migration can alter the same table.
SET CONSTRAINTS ck_finding_plan_stage_limit IMMEDIATE;

ALTER TABLE public.finding_plan_stage
  DROP CONSTRAINT IF EXISTS ck_finding_plan_stage_routing,
  DROP COLUMN routing_queue_code;

ALTER TABLE public.finding_plan_stage
  RENAME COLUMN routing_queue_kind TO routing_queue_type;

ALTER TABLE public.finding_plan_stage
  ALTER COLUMN catalog_node_id SET NOT NULL,
  ALTER COLUMN catalog_node_name SET NOT NULL,
  ALTER COLUMN routing_queue_name SET NOT NULL,
  ADD CONSTRAINT ck_finding_plan_stage_routing
    CHECK (
      length(btrim(catalog_node_name)) BETWEEN 1 AND 255
      AND length(btrim(routing_queue_name)) BETWEEN 1 AND 255
      AND length(btrim(routing_queue_type)) BETWEEN 1 AND 64
    );

CREATE FUNCTION public.inventory_v9_strip_frozen_plan_identifiers(document jsonb)
RETURNS jsonb
LANGUAGE sql
IMMUTABLE
AS $$
  SELECT CASE jsonb_typeof(document)
    WHEN 'object' THEN COALESCE((
      SELECT jsonb_object_agg(entry.key, public.inventory_v9_strip_frozen_plan_identifiers(entry.value))
      FROM jsonb_each(document) AS entry
      WHERE entry.key <> ALL (ARRAY['catalogNodeCode', 'routingQueueCode', 'queueCode', 'code'])
    ), '{}'::jsonb)
    WHEN 'array' THEN COALESCE((
      SELECT jsonb_agg(public.inventory_v9_strip_frozen_plan_identifiers(value))
      FROM jsonb_array_elements(document) AS value
    ), '[]'::jsonb)
    ELSE document
  END
$$;

UPDATE public.finding_plan_stage
SET safe_snapshot = public.inventory_v9_strip_frozen_plan_identifiers(safe_snapshot);

UPDATE public.finding_plan_snapshot
SET source_snapshot = public.inventory_v9_strip_frozen_plan_identifiers(source_snapshot);

DROP FUNCTION public.inventory_v9_strip_frozen_plan_identifiers(jsonb);
