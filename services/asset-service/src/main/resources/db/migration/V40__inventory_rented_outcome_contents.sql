-- A missing cabin may be authoritatively recorded as rented by completed inventory. Its reviewed
-- contents replace cabin buckets only; STOCK quantities remain untouched.

ALTER TABLE public.inventory_asset_outcome_watermark
  DROP CONSTRAINT ck_inventory_outcome_watermark_status,
  ADD COLUMN shipment_contents_sha256 varchar(64),
  ADD CONSTRAINT ck_inventory_outcome_watermark_status CHECK (
    desired_status IN ('FREE','REPAIR','CAPITAL_REPAIR','RENTED')),
  ADD CONSTRAINT ck_inventory_outcome_watermark_shipment_contents CHECK (
    (desired_status = 'RENTED' AND shipment_contents_sha256 ~ '^[0-9a-f]{64}$')
    OR (desired_status <> 'RENTED' AND shipment_contents_sha256 IS NULL));

ALTER TABLE public.inventory_asset_outcome_receipt
  DROP CONSTRAINT ck_inventory_outcome_receipt_status,
  DROP CONSTRAINT ck_inventory_outcome_receipt_response_status,
  ADD CONSTRAINT ck_inventory_outcome_receipt_status CHECK (
    desired_status IN ('FREE','REPAIR','CAPITAL_REPAIR','RENTED')),
  ADD CONSTRAINT ck_inventory_outcome_receipt_response_status CHECK (
    response_status IN ('FREE','REPAIR','CAPITAL_REPAIR','RENTED'));
