-- Catalog and classifier identities are UUID-only.  Historical event and
-- snapshot JSON remains authoritative replay data, so redact the retired
-- identifiers before the relational columns disappear and recompute every
-- checksum that protects the changed document.
CREATE FUNCTION pg_temp.rwms_remove_legacy_business_codes(input_value jsonb)
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
        pg_temp.rwms_remove_legacy_business_codes(entry.value)),
      '{}'::jsonb)
    INTO cleaned
    FROM jsonb_each(input_value) AS entry
    WHERE lower(entry.key) NOT IN ('code', 'equipmentcode', 'classifiercode');
    RETURN cleaned;
  END IF;

  IF jsonb_typeof(input_value) = 'array' THEN
    SELECT coalesce(
      jsonb_agg(
        pg_temp.rwms_remove_legacy_business_codes(element.value)
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

ALTER TABLE public.domain_event
  DISABLE TRIGGER trg_domain_event_immutable;

UPDATE public.domain_event AS event
SET payload = sanitized.payload,
    payload_sha256 = encode(
      sha256(convert_to(sanitized.payload::text, 'UTF8')),
      'hex')
FROM (
  SELECT event_id,
         pg_temp.rwms_remove_legacy_business_codes(payload) AS payload
  FROM public.domain_event
  WHERE aggregate_type IN ('EQUIPMENT_CATALOG', 'CLASSIFIER')
) AS sanitized
WHERE sanitized.event_id = event.event_id
  AND sanitized.payload IS DISTINCT FROM event.payload;

ALTER TABLE public.domain_event
  ENABLE TRIGGER trg_domain_event_immutable;

UPDATE public.outbox_event AS outbox
SET envelope_body = sanitized.envelope_body,
    envelope_sha256 = encode(
      sha256(convert_to(sanitized.envelope_body::text, 'UTF8')),
      'hex')
FROM (
  SELECT event_id,
         pg_temp.rwms_remove_legacy_business_codes(envelope_body) AS envelope_body
  FROM public.outbox_event
  WHERE aggregate_type IN ('EQUIPMENT_CATALOG', 'CLASSIFIER')
) AS sanitized
WHERE sanitized.event_id = outbox.event_id
  AND sanitized.envelope_body IS DISTINCT FROM outbox.envelope_body;

UPDATE public.aggregate_snapshot AS snapshot
SET state = sanitized.state,
    state_sha256 = encode(
      sha256(convert_to(sanitized.state::text, 'UTF8')),
      'hex')
FROM (
  SELECT aggregate_type,
         aggregate_id,
         aggregate_version,
         pg_temp.rwms_remove_legacy_business_codes(state) AS state
  FROM public.aggregate_snapshot
  WHERE aggregate_type IN ('EQUIPMENT_CATALOG', 'CLASSIFIER')
) AS sanitized
WHERE sanitized.aggregate_type = snapshot.aggregate_type
  AND sanitized.aggregate_id = snapshot.aggregate_id
  AND sanitized.aggregate_version = snapshot.aggregate_version
  AND sanitized.state IS DISTINCT FROM snapshot.state;

UPDATE public.projection_checkpoint AS checkpoint
SET projection_sha256 = snapshot.state_sha256
FROM public.aggregate_snapshot AS snapshot
WHERE snapshot.aggregate_type = checkpoint.aggregate_type
  AND snapshot.aggregate_id = checkpoint.aggregate_id
  AND snapshot.aggregate_version = checkpoint.aggregate_version
  AND checkpoint.projection_sha256 IS DISTINCT FROM snapshot.state_sha256;

-- Successful idempotency replays are public command responses, not arbitrary
-- passports.  Only the two retired identifier response scopes are rewritten.
UPDATE public.asset_idempotency_record AS record
SET response_body = pg_temp.rwms_remove_legacy_business_codes(response_body)
WHERE record.command_scope IN ('equipment-catalog.create', 'classifier.create')
  AND pg_temp.rwms_remove_legacy_business_codes(record.response_body)
        IS DISTINCT FROM record.response_body;

-- These tables were left only as old-panel attribute/tag schema.  Drop the
-- dependent tables explicitly rather than relying on CASCADE.
DROP TABLE IF EXISTS public.rental_item_attribute_value;
DROP TABLE IF EXISTS public.asset_classifier_attribute;
DROP TABLE IF EXISTS public.asset_attribute_option;
DROP TABLE IF EXISTS public.asset_attribute_definition;
DROP TABLE IF EXISTS public.rental_item_tag;
DROP TABLE IF EXISTS public.rental_tag;

ALTER TABLE public.equipment_catalog_item
  DROP CONSTRAINT IF EXISTS uk_equipment_catalog_item_code,
  DROP CONSTRAINT IF EXISTS ck_equipment_catalog_item_code;

ALTER TABLE public.asset_classifier
  DROP CONSTRAINT IF EXISTS uk_asset_classifier_type_code,
  DROP CONSTRAINT IF EXISTS ck_asset_classifier_code;

ALTER TABLE public.equipment_catalog_item
  DROP COLUMN IF EXISTS code;

ALTER TABLE public.asset_classifier
  DROP COLUMN IF EXISTS code;

CREATE INDEX IF NOT EXISTS idx_equipment_catalog_item_active_name
  ON public.equipment_catalog_item(active, name, id);

CREATE INDEX IF NOT EXISTS idx_asset_classifier_type_order_name
  ON public.asset_classifier(classifier_type, sort_order, name, id);
