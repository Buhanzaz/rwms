-- Before this migration TRANSFER_DEPART was allowed only from FREE, so any
-- already in-flight rental item has one deterministic origin status.
ALTER TABLE public.rental_item
  ADD COLUMN transfer_origin_status varchar(64);

CREATE TEMPORARY TABLE rwms_v24_in_flight_rental_item (
  aggregate_id varchar(255) PRIMARY KEY
) ON COMMIT DROP;

INSERT INTO rwms_v24_in_flight_rental_item(aggregate_id)
SELECT id::text
FROM public.rental_item
WHERE status = 'IN_TRANSFER';

UPDATE public.rental_item
SET transfer_origin_status = 'FREE'
WHERE status = 'IN_TRANSFER';

-- Preserve the same inferred origin in the event/snapshot evidence. Historical
-- versions of TRANSFER_DEPART were allowed only from FREE.
CREATE TEMPORARY TABLE rwms_v24_transfer_event (
  event_id uuid PRIMARY KEY
) ON COMMIT DROP;

INSERT INTO rwms_v24_transfer_event(event_id)
SELECT event.event_id
FROM public.domain_event AS event
JOIN rwms_v24_in_flight_rental_item AS item
  ON item.aggregate_id = event.aggregate_id
WHERE event.aggregate_type = 'RENTAL_ITEM'
  AND event.payload->>'status' = 'IN_TRANSFER';

ALTER TABLE public.domain_event
  DISABLE TRIGGER trg_domain_event_immutable;

UPDATE public.domain_event AS event
SET payload = event.payload
      || jsonb_build_object('transferAssetStatus', 'FREE'),
    payload_sha256 = encode(
      sha256(convert_to(
        (event.payload
          || jsonb_build_object('transferAssetStatus', 'FREE'))::text,
        'UTF8')),
      'hex')
WHERE event.event_id IN (
  SELECT event_id FROM rwms_v24_transfer_event
);

ALTER TABLE public.domain_event
  ENABLE TRIGGER trg_domain_event_immutable;

UPDATE public.outbox_event AS outbox
SET envelope_body = jsonb_set(
      outbox.envelope_body,
      ARRAY['payload', 'transferAssetStatus']::text[],
      to_jsonb('FREE'::text),
      true),
    envelope_sha256 = encode(
      sha256(convert_to(
        jsonb_set(
          outbox.envelope_body,
          ARRAY['payload', 'transferAssetStatus']::text[],
          to_jsonb('FREE'::text),
          true)::text,
        'UTF8')),
      'hex')
WHERE outbox.event_id IN (
  SELECT event_id FROM rwms_v24_transfer_event
);

UPDATE public.aggregate_snapshot AS snapshot
SET state = snapshot.state
      || jsonb_build_object('transferOriginStatus', 'FREE'),
    state_sha256 = encode(
      sha256(convert_to(
        (snapshot.state
          || jsonb_build_object('transferOriginStatus', 'FREE'))::text,
        'UTF8')),
      'hex')
WHERE snapshot.aggregate_type = 'RENTAL_ITEM'
  AND snapshot.aggregate_id IN (
    SELECT aggregate_id FROM rwms_v24_in_flight_rental_item
  )
  AND snapshot.state->>'status' = 'IN_TRANSFER';

UPDATE public.projection_checkpoint AS checkpoint
SET projection_sha256 = snapshot.state_sha256
FROM public.aggregate_snapshot AS snapshot
WHERE snapshot.aggregate_type = checkpoint.aggregate_type
  AND snapshot.aggregate_id = checkpoint.aggregate_id
  AND snapshot.aggregate_version = checkpoint.aggregate_version
  AND checkpoint.projection_sha256 IS DISTINCT FROM snapshot.state_sha256;

ALTER TABLE public.rental_item
  ADD CONSTRAINT ck_rental_item_transfer_origin_status CHECK (
    transfer_origin_status IS NULL
    OR transfer_origin_status IN ('FREE', 'REPAIR')
  ),
  ADD CONSTRAINT ck_rental_item_transfer_state CHECK (
    (status = 'IN_TRANSFER' AND transfer_origin_status IS NOT NULL)
    OR (status <> 'IN_TRANSFER' AND transfer_origin_status IS NULL)
  );
