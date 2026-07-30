-- Client presentations retain the cabin snapshot shown to the client.
-- Sanitize only its nested passport; do not touch unrelated domain source
-- fields or the useful price, shipmentDate and tenant values. A differently
-- sourced passport keeps its source while the obsolete keys are removed.

CREATE FUNCTION pg_temp.rwms_sanitize_old_panel_passport(input_value jsonb)
RETURNS jsonb
LANGUAGE plpgsql
IMMUTABLE
STRICT
AS $$
DECLARE
  cleaned jsonb;
BEGIN
  IF jsonb_typeof(input_value) = 'object' THEN
    SELECT coalesce(
      jsonb_object_agg(
        entry.key,
        pg_temp.rwms_sanitize_old_panel_passport(entry.value)),
      '{}'::jsonb)
    INTO cleaned
    FROM jsonb_each(input_value) AS entry
    WHERE lower(entry.key) NOT LIKE 'legacy%'
      AND lower(entry.key) NOT IN (
        'locationnodeid',
        'hasphotos',
        'photocount',
        'mainphotourl',
        'previewphotourls')
      AND NOT (
        lower(entry.key) = 'source'
        AND entry.value = to_jsonb('old-panel-rental-items-v1'::text));
    RETURN cleaned;
  END IF;

  IF jsonb_typeof(input_value) = 'array' THEN
    SELECT coalesce(
      jsonb_agg(
        pg_temp.rwms_sanitize_old_panel_passport(element.value)
        ORDER BY element.ordinality),
      '[]'::jsonb)
    INTO cleaned
    FROM jsonb_array_elements(input_value)
      WITH ORDINALITY AS element(value, ordinality);
    RETURN cleaned;
  END IF;

  RETURN input_value;
END;
$$;

UPDATE public.client_presentation_item AS item
SET cabin_snapshot_json = jsonb_set(
  item.cabin_snapshot_json,
  ARRAY['passport']::text[],
  pg_temp.rwms_sanitize_old_panel_passport(
    item.cabin_snapshot_json->'passport'),
  false)
WHERE jsonb_typeof(item.cabin_snapshot_json->'passport') = 'object'
  AND (
    (item.cabin_snapshot_json->'passport')::text ILIKE '%legacy%'
    OR (item.cabin_snapshot_json->'passport')::text ILIKE
      '%old-panel-rental-items-v1%'
    OR (item.cabin_snapshot_json->'passport')::text ILIKE '%locationNodeId%'
    OR (item.cabin_snapshot_json->'passport')::text ILIKE '%hasPhotos%'
    OR (item.cabin_snapshot_json->'passport')::text ILIKE '%photoCount%'
    OR (item.cabin_snapshot_json->'passport')::text ILIKE '%mainPhotoUrl%'
    OR (item.cabin_snapshot_json->'passport')::text ILIKE
      '%previewPhotoUrls%');
