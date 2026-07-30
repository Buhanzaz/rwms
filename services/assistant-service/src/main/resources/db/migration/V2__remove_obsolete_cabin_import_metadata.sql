CREATE FUNCTION public.strip_obsolete_cabin_import_metadata_v2(input_value jsonb)
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
      jsonb_object_agg(entry.key, public.strip_obsolete_cabin_import_metadata_v2(entry.value)),
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
        public.strip_obsolete_cabin_import_metadata_v2(element.value)
        ORDER BY element.ordinality),
      '[]'::jsonb)
    INTO cleaned
    FROM jsonb_array_elements(input_value) WITH ORDINALITY AS element(value, ordinality);
    RETURN cleaned;
  END IF;

  RETURN input_value;
END;
$$;

UPDATE public.assistant_tool_call
SET result_payload =
  public.strip_obsolete_cabin_import_metadata_v2(result_payload)
WHERE result_payload IS NOT NULL
  AND (
    result_payload::text ILIKE '%legacy%'
    OR result_payload::text ILIKE '%old-panel-rental-items-v1%'
    OR result_payload::text ILIKE '%locationNodeId%'
    OR result_payload::text ILIKE '%hasPhotos%'
    OR result_payload::text ILIKE '%photoCount%'
    OR result_payload::text ILIKE '%mainPhotoUrl%'
    OR result_payload::text ILIKE '%previewPhotoUrls%');

DROP FUNCTION public.strip_obsolete_cabin_import_metadata_v2(jsonb);
