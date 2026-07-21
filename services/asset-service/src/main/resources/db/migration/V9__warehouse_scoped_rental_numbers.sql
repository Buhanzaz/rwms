-- Rental numbers are identities inside a warehouse, not across the whole
-- company. V5 preserved all 195 old-panel rows and UUIDs, but assigned the 75
-- Moscow cabins synthetic global numbers BYT-121..BYT-195. Correct those rows
-- back to their retained old-panel legacyNumber values without changing V5 or
-- inventing replacement aggregates.

DO $$
BEGIN
  IF EXISTS (
    SELECT 1
    FROM (
      SELECT item.warehouse_id,
             CASE
               WHEN item.passport_json::jsonb->>'source' = 'old-panel-rental-items-v1'
                 AND item.passport_json::jsonb->>'legacyWarehouseId' = 'msk'
               THEN upper(regexp_replace(
                 item.passport_json::jsonb->>'legacyNumber', '[ -]', '', 'g'))
               ELSE item.identity_match_key
             END AS proposed_identity_match_key
      FROM public.rental_item AS item
    ) AS proposed
    GROUP BY proposed.warehouse_id, proposed.proposed_identity_match_key
    HAVING count(*) > 1
  ) THEN
    RAISE EXCEPTION
      'warehouse-scoped rental number correction requires explicit collision reconciliation';
  END IF;
END $$;

ALTER TABLE public.rental_item
  DROP CONSTRAINT uk_rental_item_identity_match_key;

CREATE TEMPORARY TABLE corrected_old_panel_cabin_number (
  rental_item_id uuid PRIMARY KEY,
  corrected_number varchar(128) NOT NULL,
  corrected_identity_match_key varchar(128) NOT NULL,
  corrected_number_sha256 char(64) NOT NULL,
  prior_version bigint NOT NULL,
  correction_version bigint NOT NULL,
  correction_event_id uuid NOT NULL UNIQUE,
  corrected_at timestamptz NOT NULL
) ON COMMIT DROP;

INSERT INTO corrected_old_panel_cabin_number(
  rental_item_id,
  corrected_number,
  corrected_identity_match_key,
  corrected_number_sha256,
  prior_version,
  correction_version,
  correction_event_id,
  corrected_at)
SELECT item.id,
       item.passport_json::jsonb->>'legacyNumber',
       upper(regexp_replace(
         item.passport_json::jsonb->>'legacyNumber', '[ -]', '', 'g')),
       encode(sha256(convert_to(
         item.passport_json::jsonb->>'legacyNumber', 'UTF8')), 'hex'),
       item.version,
       item.version + 1,
       gen_random_uuid(),
       clock_timestamp()
FROM public.rental_item AS item
WHERE item.passport_json::jsonb->>'source' = 'old-panel-rental-items-v1'
  AND item.passport_json::jsonb->>'legacyWarehouseId' = 'msk';

DO $$
DECLARE
  correction_count integer;
BEGIN
  SELECT count(*) INTO correction_count
  FROM corrected_old_panel_cabin_number;
  IF correction_count <> 75 THEN
    RAISE EXCEPTION
      'expected 75 Moscow old-panel cabin number corrections, found %',
      correction_count;
  END IF;

  IF EXISTS (
    SELECT 1
    FROM corrected_old_panel_cabin_number AS correction
    JOIN public.rental_item AS item
      ON item.id = correction.rental_item_id
    LEFT JOIN public.event_stream_head AS head
      ON head.aggregate_type = 'RENTAL_ITEM'
     AND head.aggregate_id = correction.rental_item_id::text
     AND head.current_version = correction.prior_version
    LEFT JOIN public.domain_event AS latest_event
      ON latest_event.event_id = head.last_event_id
     AND latest_event.aggregate_type = head.aggregate_type
     AND latest_event.aggregate_id = head.aggregate_id
     AND latest_event.aggregate_version = head.current_version
    LEFT JOIN public.aggregate_snapshot AS snapshot
      ON snapshot.aggregate_type = 'RENTAL_ITEM'
     AND snapshot.aggregate_id = correction.rental_item_id::text
     AND snapshot.aggregate_version = correction.prior_version
    LEFT JOIN public.projection_checkpoint AS checkpoint
      ON checkpoint.projection_name = 'asset-live-v1'
     AND checkpoint.aggregate_type = 'RENTAL_ITEM'
     AND checkpoint.aggregate_id = correction.rental_item_id::text
     AND checkpoint.aggregate_version = correction.prior_version
    LEFT JOIN public.outbox_event AS outbox
      ON outbox.event_id = head.last_event_id
     AND outbox.aggregate_type = head.aggregate_type
     AND outbox.aggregate_id = head.aggregate_id
     AND outbox.aggregate_version = head.current_version
    WHERE head.aggregate_id IS NULL
       OR latest_event.event_id IS NULL
       OR snapshot.aggregate_id IS NULL
       OR checkpoint.aggregate_id IS NULL
       OR outbox.event_id IS NULL
       OR checkpoint.projection_sha256 <> snapshot.state_sha256
       OR snapshot.state_sha256 <>
          encode(sha256(convert_to(snapshot.state::text, 'UTF8')), 'hex')
       OR (snapshot.state->>'version')::bigint <> item.version
       OR snapshot.state->>'warehouseId' <> item.warehouse_id::text
       OR snapshot.state->>'number' <> item.display_canonical_number
       OR snapshot.state->>'status' <> item.status
       OR snapshot.state->'passport' <> item.passport_json::jsonb
       OR snapshot.state->'tags' <> item.tags_json::jsonb
  ) THEN
    RAISE EXCEPTION
      'Moscow cabin number correction requires coherent current stream, snapshot, checkpoint and outbox state';
  END IF;
END $$;

UPDATE public.rental_item AS item
SET display_canonical_number = correction.corrected_number,
    identity_match_key = correction.corrected_identity_match_key,
    version = correction.correction_version,
    updated_at = correction.corrected_at
FROM corrected_old_panel_cabin_number AS correction
WHERE item.id = correction.rental_item_id;

ALTER TABLE public.rental_item
  ADD CONSTRAINT uk_rental_item_warehouse_identity_match_key
    UNIQUE (warehouse_id, identity_match_key);

-- Domain events are append-only. Preserve every V5 fact and append one normal
-- passport-change fact because the correction is derived from the retained
-- legacyNumber passport field. This advances each corrected aggregate by one
-- version and keeps replay, snapshot, checkpoint and outbox invariants intact.
UPDATE public.event_stream_head AS head
SET current_version = correction.correction_version,
    last_event_id = correction.correction_event_id,
    updated_at = correction.corrected_at
FROM corrected_old_panel_cabin_number AS correction
WHERE head.aggregate_type = 'RENTAL_ITEM'
  AND head.aggregate_id = correction.rental_item_id::text
  AND head.current_version = correction.prior_version;

WITH facts AS (
  SELECT correction.*,
         jsonb_build_object(
           'rentalItemId', item.id::text,
           'warehouseId', item.warehouse_id::text,
           'status', item.status,
           'numberSha256', correction.corrected_number_sha256) AS payload
  FROM corrected_old_panel_cabin_number AS correction
  JOIN public.rental_item AS item ON item.id = correction.rental_item_id
)
INSERT INTO public.domain_event(
  event_id, aggregate_type, aggregate_id, aggregate_version, event_type,
  event_version, occurred_at, recorded_at, correlation_id, causation_id,
  actor_ref, payload, payload_sha256, baseline)
SELECT correction_event_id,
       'RENTAL_ITEM',
       rental_item_id::text,
       correction_version,
       'asset.rental-item.passport-changed.v1',
       1,
       corrected_at,
       corrected_at,
       correction_event_id,
       null,
       null,
       payload,
       encode(sha256(convert_to(payload::text, 'UTF8')), 'hex'),
       false
FROM facts;

WITH snapshots AS (
  SELECT correction.*,
         jsonb_build_object(
           'rentalItemId', item.id::text,
           'version', item.version,
           'warehouseId', item.warehouse_id::text,
           'number', item.display_canonical_number,
           'status', item.status,
           'rentalType', item.rental_type,
           'dimensions', item.dimensions,
           'finishing', item.finishing,
           'category', item.category,
           'characteristics', item.characteristics,
           'linoleum', item.linoleum,
           'generalComment', item.general_comment,
           'passport', item.passport_json::jsonb,
           'tags', item.tags_json::jsonb) AS state
  FROM corrected_old_panel_cabin_number AS correction
  JOIN public.rental_item AS item ON item.id = correction.rental_item_id
)
INSERT INTO public.aggregate_snapshot(
  aggregate_type, aggregate_id, aggregate_version, state, state_sha256,
  recorded_at)
SELECT 'RENTAL_ITEM',
       rental_item_id::text,
       correction_version,
       state,
       encode(sha256(convert_to(state::text, 'UTF8')), 'hex'),
       corrected_at
FROM snapshots;

WITH envelopes AS (
  SELECT correction.*,
         event.payload,
         jsonb_build_object(
           'envelopeVersion', 2,
           'eventId', correction.correction_event_id::text,
           'eventType', 'asset.rental-item.passport-changed.v1',
           'eventVersion', 1,
           'occurredAt', correction.corrected_at,
           'recordedAt', correction.corrected_at,
           'producer', 'asset-service',
           'aggregateType', 'RENTAL_ITEM',
           'aggregateId', correction.rental_item_id::text,
           'aggregateVersion', correction.correction_version,
           'correlation', jsonb_build_object(
             'correlationId', correction.correction_event_id::text,
             'causationId', null),
           'actorRef', null,
           'payload', event.payload) AS envelope
  FROM corrected_old_panel_cabin_number AS correction
  JOIN public.domain_event AS event
    ON event.event_id = correction.correction_event_id
)
INSERT INTO public.outbox_event(
  event_id, aggregate_type, aggregate_id, aggregate_version, event_type, topic,
  envelope_body, envelope_sha256, status, attempt_count, next_attempt_at,
  created_at)
SELECT correction_event_id,
       'RENTAL_ITEM',
       rental_item_id::text,
       correction_version,
       'asset.rental-item.passport-changed.v1',
       'rwms.asset.rental-item.v1',
       envelope,
       encode(sha256(convert_to(envelope::text, 'UTF8')), 'hex'),
       'PENDING',
       0,
       corrected_at,
       corrected_at
FROM envelopes;

UPDATE public.projection_checkpoint AS checkpoint
SET aggregate_version = correction.correction_version,
    projection_sha256 = snapshot.state_sha256,
    updated_at = correction.corrected_at
FROM corrected_old_panel_cabin_number AS correction
JOIN public.aggregate_snapshot AS snapshot
  ON snapshot.aggregate_type = 'RENTAL_ITEM'
 AND snapshot.aggregate_id = correction.rental_item_id::text
 AND snapshot.aggregate_version = correction.correction_version
WHERE checkpoint.projection_name = 'asset-live-v1'
  AND checkpoint.aggregate_type = 'RENTAL_ITEM'
  AND checkpoint.aggregate_id = correction.rental_item_id::text;

-- Inventory source claims used the same global number key. Introduce a
-- technical UUID primary key and a warehouse-scoped unique key. A crash could
-- have left a pre-V9 claim without its final source row, so warehouse_id stays
-- nullable only for that retained legacy evidence; a retry binds it through
-- the owning service before use. All newly created claims are warehouse-bound.
ALTER TABLE public.inventory_asset_number_claim
  ADD COLUMN claim_id uuid,
  ADD COLUMN warehouse_id uuid;

UPDATE public.inventory_asset_number_claim AS claim
SET claim_id = gen_random_uuid(),
    warehouse_id = item.warehouse_id
FROM public.inventory_asset_source AS source
JOIN public.rental_item AS item ON item.id = source.rental_item_id
WHERE source.inventory_id = claim.inventory_id
  AND source.finding_id = claim.finding_id;

UPDATE public.inventory_asset_number_claim
SET claim_id = gen_random_uuid()
WHERE claim_id IS NULL;

ALTER TABLE public.inventory_asset_number_claim
  ALTER COLUMN claim_id SET NOT NULL,
  DROP CONSTRAINT inventory_asset_number_claim_pkey,
  ADD CONSTRAINT inventory_asset_number_claim_pkey PRIMARY KEY (claim_id),
  ADD CONSTRAINT uk_inventory_asset_number_claim_warehouse_key
    UNIQUE NULLS NOT DISTINCT (warehouse_id, identity_match_key);
