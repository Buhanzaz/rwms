-- Inventory retains the asset passport captured when a session starts.
-- Remove only the known old-panel transport metadata from that nested
-- passport. Business values price, shipmentDate and tenant remain intact. A
-- differently sourced passport keeps its source while the obsolete keys are
-- still removed.

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

UPDATE public.inventory_expected_item AS expected
SET safe_passport_snapshot = jsonb_set(
  expected.safe_passport_snapshot,
  ARRAY['passport']::text[],
  pg_temp.rwms_sanitize_old_panel_passport(
    expected.safe_passport_snapshot->'passport'),
  false)
WHERE jsonb_typeof(expected.safe_passport_snapshot->'passport') = 'object'
  AND (
    (expected.safe_passport_snapshot->'passport')::text ILIKE '%legacy%'
    OR (expected.safe_passport_snapshot->'passport')::text ILIKE
      '%old-panel-rental-items-v1%'
    OR (expected.safe_passport_snapshot->'passport')::text ILIKE
      '%locationNodeId%'
    OR (expected.safe_passport_snapshot->'passport')::text ILIKE
      '%hasPhotos%'
    OR (expected.safe_passport_snapshot->'passport')::text ILIKE
      '%photoCount%'
    OR (expected.safe_passport_snapshot->'passport')::text ILIKE
      '%mainPhotoUrl%'
    OR (expected.safe_passport_snapshot->'passport')::text ILIKE
      '%previewPhotoUrls%');
