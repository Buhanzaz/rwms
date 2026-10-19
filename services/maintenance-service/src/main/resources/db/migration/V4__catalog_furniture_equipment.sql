-- Furniture remains a maintenance catalog MATERIAL, while the referenced
-- master-equipment item is an immutable cross-service snapshot (never a FK).
ALTER TABLE public.catalog_node
  ADD COLUMN furniture_category boolean NOT NULL DEFAULT false,
  ADD COLUMN furniture_equipment_id uuid,
  ADD COLUMN furniture_equipment_code varchar(64),
  ADD COLUMN furniture_equipment_name varchar(255);

UPDATE public.catalog_node
SET furniture_category = true
WHERE node_type = 'CATEGORY'
  AND parent_node_id IS NULL
  AND code = 'FURNITURE';

-- Pre-V4 revision snapshots did not carry the explicit nullable furniture link.
UPDATE public.estimate_line
SET catalog_snapshot = jsonb_set(
  catalog_snapshot,
  '{furnitureEquipment}',
  'null'::jsonb,
  true)
WHERE catalog_snapshot IS NOT NULL
  AND NOT catalog_snapshot ? 'furnitureEquipment';

-- An unexpired idempotency response may contain the same pre-V4 snapshot nested in an
-- EstimateResponse or EstimateCommandResult. Keep those durable replays readable after the API
-- starts requiring an explicit nullable furnitureEquipment property.
CREATE FUNCTION public.maintenance_v4_add_furniture_equipment(document jsonb)
RETURNS jsonb
LANGUAGE plpgsql
IMMUTABLE
STRICT
AS $$
DECLARE
  migrated jsonb;
BEGIN
  IF jsonb_typeof(document) = 'object' THEN
    SELECT coalesce(
      jsonb_object_agg(entry.key, public.maintenance_v4_add_furniture_equipment(entry.value)),
      '{}'::jsonb)
    INTO migrated
    FROM jsonb_each(document) AS entry(key, value);

    IF migrated ? 'catalogVersionId'
        AND migrated ? 'nodeId'
        AND migrated ? 'nodeType'
        AND migrated ? 'durationMinutes'
        AND migrated ? 'routing'
        AND NOT migrated ? 'furnitureEquipment' THEN
      migrated = migrated || '{"furnitureEquipment":null}'::jsonb;
    END IF;
    RETURN migrated;
  END IF;

  IF jsonb_typeof(document) = 'array' THEN
    SELECT coalesce(
      jsonb_agg(
        public.maintenance_v4_add_furniture_equipment(entry.value)
        ORDER BY entry.ordinality),
      '[]'::jsonb)
    INTO migrated
    FROM jsonb_array_elements(document) WITH ORDINALITY AS entry(value, ordinality);
    RETURN migrated;
  END IF;

  RETURN document;
END;
$$;

UPDATE public.maintenance_idempotency_record
SET response_body = public.maintenance_v4_add_furniture_equipment(response_body)
WHERE command_scope = 'estimate.create'
  OR command_scope LIKE 'estimate.complete:%'
  OR command_scope LIKE 'estimate.amend:%';

DROP FUNCTION public.maintenance_v4_add_furniture_equipment(jsonb);

ALTER TABLE public.catalog_node
  ADD CONSTRAINT ck_catalog_node_furniture_category
    CHECK (NOT furniture_category OR node_type = 'CATEGORY'),
  ADD CONSTRAINT ck_catalog_node_furniture_equipment
    CHECK (
      (furniture_equipment_id IS NULL
        AND furniture_equipment_code IS NULL
        AND furniture_equipment_name IS NULL)
      OR
      (node_type = 'MATERIAL'
        AND furniture_equipment_id IS NOT NULL
        AND furniture_equipment_code IS NOT NULL
        AND furniture_equipment_name IS NOT NULL
        AND length(btrim(furniture_equipment_code)) BETWEEN 1 AND 64
        AND length(btrim(furniture_equipment_name)) BETWEEN 1 AND 255)
    );
