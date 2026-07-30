-- NEW was an incorrect lifecycle status. Availability is represented only by
-- FREE, while "Новая" is a rental-item category. Rewrite every persisted
-- status-shaped JSON value and the typed projections before closing the schema
-- vocabulary so idempotency replays, inventory captures and event replay all
-- expose the corrected model.

CREATE TEMPORARY TABLE rwms_new_rental_item (
  id uuid PRIMARY KEY
) ON COMMIT DROP;

INSERT INTO rwms_new_rental_item(id)
SELECT id
FROM public.rental_item
WHERE status = 'NEW';

CREATE FUNCTION pg_temp.rwms_remove_new_status(input_value jsonb)
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
        CASE
          WHEN lower(entry.key) LIKE '%status'
            AND entry.value = to_jsonb('NEW'::text)
          THEN to_jsonb('FREE'::text)
          ELSE pg_temp.rwms_remove_new_status(entry.value)
        END),
      '{}'::jsonb)
    INTO cleaned
    FROM jsonb_each(input_value) AS entry;
    RETURN cleaned;
  END IF;

  IF jsonb_typeof(input_value) = 'array' THEN
    SELECT coalesce(
      jsonb_agg(
        pg_temp.rwms_remove_new_status(element.value)
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

ALTER TABLE public.rental_item
  DROP CONSTRAINT ck_rental_item_status;

UPDATE public.rental_item
SET status = 'FREE',
    category = 'Новая'
WHERE id IN (SELECT id FROM rwms_new_rental_item);

ALTER TABLE public.rental_item
  ADD CONSTRAINT ck_rental_item_status CHECK (status IN (
    'RENTED','BOOKED','REPAIR','WAITING_REPAIR_CHECK','WRITTEN_OFF',
    'CAPITAL_REPAIR','AFTER_RENT','WAITING_ESTIMATE_CONFIRMATION','SALE',
    'USED_SALE','RESERVED','FREE','WAREHOUSE','OWN_NEEDS','IN_TRANSFER'));

-- Historical snapshots are retained, but the invalid vocabulary is corrected
-- in place and every checksum that protects replay state is recalculated.
ALTER TABLE public.domain_event
  DISABLE TRIGGER trg_domain_event_immutable;

UPDATE public.domain_event AS event
SET payload = corrected.payload,
    payload_sha256 = encode(
      sha256(convert_to(corrected.payload::text, 'UTF8')),
      'hex')
FROM (
  SELECT event_id, pg_temp.rwms_remove_new_status(payload) AS payload
  FROM public.domain_event
) AS corrected
WHERE corrected.event_id = event.event_id
  AND corrected.payload IS DISTINCT FROM event.payload;

ALTER TABLE public.domain_event
  ENABLE TRIGGER trg_domain_event_immutable;

UPDATE public.outbox_event AS outbox
SET envelope_body = corrected.envelope_body,
    envelope_sha256 = encode(
      sha256(convert_to(corrected.envelope_body::text, 'UTF8')),
      'hex')
FROM (
  SELECT
    event_id,
    pg_temp.rwms_remove_new_status(envelope_body) AS envelope_body
  FROM public.outbox_event
) AS corrected
WHERE corrected.event_id = outbox.event_id
  AND corrected.envelope_body IS DISTINCT FROM outbox.envelope_body;

UPDATE public.aggregate_snapshot AS snapshot
SET state = corrected.state,
    state_sha256 = encode(
      sha256(convert_to(corrected.state::text, 'UTF8')),
      'hex')
FROM (
  SELECT
    source.aggregate_type,
    source.aggregate_id,
    source.aggregate_version,
    CASE
      WHEN source.aggregate_type = 'RENTAL_ITEM'
        AND source.aggregate_id IN (
          SELECT id::text FROM rwms_new_rental_item)
      THEN jsonb_set(
        pg_temp.rwms_remove_new_status(source.state),
        ARRAY['category']::text[],
        to_jsonb('Новая'::text),
        true)
      ELSE pg_temp.rwms_remove_new_status(source.state)
    END AS state
  FROM public.aggregate_snapshot AS source
) AS corrected
WHERE corrected.aggregate_type = snapshot.aggregate_type
  AND corrected.aggregate_id = snapshot.aggregate_id
  AND corrected.aggregate_version = snapshot.aggregate_version
  AND corrected.state IS DISTINCT FROM snapshot.state;

UPDATE public.projection_checkpoint AS checkpoint
SET projection_sha256 = snapshot.state_sha256
FROM public.aggregate_snapshot AS snapshot
WHERE snapshot.aggregate_type = checkpoint.aggregate_type
  AND snapshot.aggregate_id = checkpoint.aggregate_id
  AND snapshot.aggregate_version = checkpoint.aggregate_version
  AND checkpoint.projection_sha256 IS DISTINCT FROM snapshot.state_sha256;

-- Captures are append-only operational evidence. The correction is an
-- explicitly authorised vocabulary migration; restore the guard immediately
-- after updating the retained typed and JSON snapshots.
ALTER TABLE public.inventory_asset_capture_member
  DISABLE TRIGGER trg_inventory_asset_capture_member_no_mutation;

ALTER TABLE public.inventory_asset_capture_member
  DROP CONSTRAINT ck_inventory_asset_capture_member_status;

UPDATE public.inventory_asset_capture_member
SET status = CASE status WHEN 'NEW' THEN 'FREE' ELSE status END,
    passport_snapshot = CASE
      WHEN status = 'NEW'
      THEN jsonb_set(
        pg_temp.rwms_remove_new_status(passport_snapshot),
        ARRAY['category']::text[],
        to_jsonb('Новая'::text),
        true)
      ELSE pg_temp.rwms_remove_new_status(passport_snapshot)
    END
WHERE status = 'NEW'
   OR pg_temp.rwms_remove_new_status(passport_snapshot)
        IS DISTINCT FROM passport_snapshot;

ALTER TABLE public.inventory_asset_capture_member
  ADD CONSTRAINT ck_inventory_asset_capture_member_status CHECK (status IN (
    'BOOKED','REPAIR','WAITING_REPAIR_CHECK','CAPITAL_REPAIR','AFTER_RENT',
    'SALE','USED_SALE','RESERVED','FREE','WAREHOUSE','OWN_NEEDS'));

ALTER TABLE public.inventory_asset_capture_member
  ENABLE TRIGGER trg_inventory_asset_capture_member_no_mutation;

ALTER TABLE public.inventory_asset_source
  DISABLE TRIGGER trg_inventory_asset_source_no_update;

UPDATE public.inventory_asset_source AS source
SET response_body = pg_temp.rwms_remove_new_status(response_body)
WHERE pg_temp.rwms_remove_new_status(response_body)
  IS DISTINCT FROM response_body;

ALTER TABLE public.inventory_asset_source
  ENABLE TRIGGER trg_inventory_asset_source_no_update;

UPDATE public.asset_idempotency_record AS record
SET response_body = pg_temp.rwms_remove_new_status(response_body)
WHERE pg_temp.rwms_remove_new_status(response_body)
  IS DISTINCT FROM response_body;
