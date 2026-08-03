-- Comments belong to work definitions and work lines only. Remove legacy material
-- text once and enforce the canonical catalog invariant at the database boundary.
UPDATE public.catalog_node
SET comment = NULL
WHERE node_type = 'MATERIAL'
  AND comment IS NOT NULL;

UPDATE public.estimate_line
SET comment = NULL
WHERE line_type = 'MATERIAL'
  AND comment IS NOT NULL;

UPDATE public.repair_stage AS stage
SET material_lines = (
  SELECT coalesce(
      jsonb_agg(
        jsonb_set(item.value, '{comment}', 'null'::jsonb, true)
        ORDER BY item.ordinality),
      '[]'::jsonb) AS lines
  FROM jsonb_array_elements(stage.material_lines)
       WITH ORDINALITY AS item(value, ordinality)
)
WHERE jsonb_path_exists(stage.material_lines, '$[*] ? (@.comment != null)');

ALTER TABLE public.catalog_node
  ADD CONSTRAINT ck_catalog_node_material_comment
    CHECK (node_type <> 'MATERIAL' OR comment IS NULL);

ALTER TABLE public.estimate_line
  ADD CONSTRAINT ck_estimate_line_material_comment
    CHECK (line_type <> 'MATERIAL' OR comment IS NULL);

ALTER TABLE public.repair_stage
  ADD CONSTRAINT ck_repair_stage_material_comment
    CHECK (NOT jsonb_path_exists(material_lines, '$[*] ? (@.comment != null)'));

COMMENT ON COLUMN public.catalog_node.comment IS
  'Optional catalog text for non-MATERIAL nodes; MATERIAL must remain null.';

COMMENT ON COLUMN public.estimate_line.comment IS
  'Optional WORK-line comment; MATERIAL must remain null.';
