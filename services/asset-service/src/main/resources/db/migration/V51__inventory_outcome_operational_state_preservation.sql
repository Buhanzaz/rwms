-- A completed inventory may publish corrected passport evidence for a cabin whose live rental,
-- repair, order, transfer or presentation state belongs to another workflow. The explicit mode is
-- durable in both replay records and ordering watermarks; pre-V51 outcomes remain status-applying.

ALTER TABLE public.inventory_asset_outcome_watermark
  DROP CONSTRAINT ck_inventory_outcome_watermark_status,
  DROP CONSTRAINT ck_inventory_outcome_watermark_shipment_contents,
  ALTER COLUMN desired_status DROP NOT NULL,
  ADD COLUMN preserve_operational_state boolean NOT NULL DEFAULT false,
  ADD CONSTRAINT ck_inventory_outcome_watermark_status CHECK (
    (preserve_operational_state AND desired_status IS NULL)
    OR (
      NOT preserve_operational_state
      AND desired_status IS NOT NULL
      AND desired_status IN ('FREE','REPAIR','CAPITAL_REPAIR','RENTED')
    )),
  ADD CONSTRAINT ck_inventory_outcome_watermark_shipment_contents CHECK (
    (
      NOT preserve_operational_state
      AND desired_status = 'RENTED'
      AND shipment_contents_sha256 IS NOT NULL
      AND shipment_contents_sha256 ~ '^[0-9a-f]{64}$'
    )
    OR (
      (preserve_operational_state OR desired_status <> 'RENTED')
      AND shipment_contents_sha256 IS NULL
    ));

ALTER TABLE public.inventory_asset_outcome_receipt
  DROP CONSTRAINT ck_inventory_outcome_receipt_status,
  DROP CONSTRAINT ck_inventory_outcome_receipt_response_status,
  ALTER COLUMN desired_status DROP NOT NULL,
  ADD COLUMN preserve_operational_state boolean NOT NULL DEFAULT false,
  ADD CONSTRAINT ck_inventory_outcome_receipt_status CHECK (
    (preserve_operational_state AND desired_status IS NULL)
    OR (
      NOT preserve_operational_state
      AND desired_status IS NOT NULL
      AND desired_status IN ('FREE','REPAIR','CAPITAL_REPAIR','RENTED')
    )),
  ADD CONSTRAINT ck_inventory_outcome_receipt_response_status CHECK (
    response_status IN (
      'RENTED','BOOKED','REPAIR','WAITING_REPAIR_CHECK','WRITTEN_OFF','LOST','CAPITAL_REPAIR',
      'AFTER_RENT','WAITING_ESTIMATE_CONFIRMATION','SALE','USED_SALE','RESERVED','FREE',
      'WAREHOUSE','OWN_NEEDS','IN_TRANSFER'
    ));
