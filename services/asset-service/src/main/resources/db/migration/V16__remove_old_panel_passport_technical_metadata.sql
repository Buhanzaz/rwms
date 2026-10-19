-- Canonical rental identity and media are now owned by typed columns and
-- media-service. Remove the remaining old-panel transport metadata only from
-- the exact passport scope; useful price, shipmentDate and tenant values stay.
-- A differently sourced passport keeps its source while the obsolete keys are
-- still removed. The helper lives only for this migration connection.

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

UPDATE public.rental_item
SET passport_json =
  pg_temp.rwms_sanitize_old_panel_passport(passport_json::jsonb)::text
WHERE jsonb_typeof(passport_json::jsonb) = 'object'
  AND (
    passport_json ILIKE '%legacy%'
    OR passport_json ILIKE '%old-panel-rental-items-v1%'
    OR passport_json ILIKE '%locationNodeId%'
    OR passport_json ILIKE '%hasPhotos%'
    OR passport_json ILIKE '%photoCount%'
    OR passport_json ILIKE '%mainPhotoUrl%'
    OR passport_json ILIKE '%previewPhotoUrls%');

-- Every historical RENTAL_ITEM snapshot is service-local replay state. Keep
-- its checksum coherent after redacting the same exact passport object.
UPDATE public.aggregate_snapshot AS snapshot
SET state = jsonb_set(
      snapshot.state,
      ARRAY['passport']::text[],
      pg_temp.rwms_sanitize_old_panel_passport(
        snapshot.state->'passport'),
      false),
    state_sha256 = encode(
      sha256(convert_to(
        jsonb_set(
          snapshot.state,
          ARRAY['passport']::text[],
          pg_temp.rwms_sanitize_old_panel_passport(
            snapshot.state->'passport'),
          false)::text,
        'UTF8')),
      'hex')
WHERE snapshot.aggregate_type = 'RENTAL_ITEM'
  AND jsonb_typeof(snapshot.state->'passport') = 'object'
  AND (
    (snapshot.state->'passport')::text ILIKE '%legacy%'
    OR (snapshot.state->'passport')::text ILIKE
      '%old-panel-rental-items-v1%'
    OR (snapshot.state->'passport')::text ILIKE '%locationNodeId%'
    OR (snapshot.state->'passport')::text ILIKE '%hasPhotos%'
    OR (snapshot.state->'passport')::text ILIKE '%photoCount%'
    OR (snapshot.state->'passport')::text ILIKE '%mainPhotoUrl%'
    OR (snapshot.state->'passport')::text ILIKE '%previewPhotoUrls%');

UPDATE public.projection_checkpoint AS checkpoint
SET projection_sha256 = snapshot.state_sha256
FROM public.rental_item AS item
JOIN public.aggregate_snapshot AS snapshot
  ON snapshot.aggregate_type = 'RENTAL_ITEM'
 AND snapshot.aggregate_id = item.id::text
 AND snapshot.aggregate_version = item.version
WHERE checkpoint.projection_name = 'asset-live-v1'
  AND checkpoint.aggregate_type = 'RENTAL_ITEM'
  AND checkpoint.aggregate_id = item.id::text
  AND checkpoint.aggregate_version = item.version;

-- Capture members are append-only evidence. Flyway runs PostgreSQL migrations
-- transactionally, so the named trigger is restored automatically on rollback
-- and explicitly re-enabled on success. Keep membership_digest unchanged: it
-- is the immutable capture/cursor identity, while this update only redacts
-- non-business transport metadata from the retained snapshot.
ALTER TABLE public.inventory_asset_capture_member
  DISABLE TRIGGER trg_inventory_asset_capture_member_no_mutation;

UPDATE public.inventory_asset_capture_member AS member
SET passport_snapshot = jsonb_set(
  member.passport_snapshot,
  ARRAY['passport']::text[],
  pg_temp.rwms_sanitize_old_panel_passport(
    member.passport_snapshot->'passport'),
  false)
WHERE jsonb_typeof(member.passport_snapshot->'passport') = 'object'
  AND (
    (member.passport_snapshot->'passport')::text ILIKE '%legacy%'
    OR (member.passport_snapshot->'passport')::text ILIKE
      '%old-panel-rental-items-v1%'
    OR (member.passport_snapshot->'passport')::text ILIKE
      '%locationNodeId%'
    OR (member.passport_snapshot->'passport')::text ILIKE '%hasPhotos%'
    OR (member.passport_snapshot->'passport')::text ILIKE '%photoCount%'
    OR (member.passport_snapshot->'passport')::text ILIKE '%mainPhotoUrl%'
    OR (member.passport_snapshot->'passport')::text ILIKE
      '%previewPhotoUrls%');

ALTER TABLE public.inventory_asset_capture_member
  ENABLE TRIGGER trg_inventory_asset_capture_member_no_mutation;

-- Successful command responses are replayed verbatim from the idempotency
-- store, so sanitize their response passport as well. request_sha256 protects
-- the request and is deliberately not rewritten.
UPDATE public.asset_idempotency_record AS record
SET response_body = jsonb_set(
  record.response_body,
  ARRAY['passport']::text[],
  pg_temp.rwms_sanitize_old_panel_passport(
    record.response_body->'passport'),
  false)
WHERE jsonb_typeof(record.response_body->'passport') = 'object'
  AND (
    (record.response_body->'passport')::text ILIKE '%legacy%'
    OR (record.response_body->'passport')::text ILIKE
      '%old-panel-rental-items-v1%'
    OR (record.response_body->'passport')::text ILIKE '%locationNodeId%'
    OR (record.response_body->'passport')::text ILIKE '%hasPhotos%'
    OR (record.response_body->'passport')::text ILIKE '%photoCount%'
    OR (record.response_body->'passport')::text ILIKE '%mainPhotoUrl%'
    OR (record.response_body->'passport')::text ILIKE
      '%previewPhotoUrls%');
