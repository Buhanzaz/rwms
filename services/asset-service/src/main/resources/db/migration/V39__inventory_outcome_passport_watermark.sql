-- V38 outcomes fingerprinted only status/final-plan identity. A nullable hash marks those existing
-- watermarks for one exact-source adoption; every newly applied outcome stores the immutable
-- inventory passport observation hash and detects any later equal-time payload drift.
ALTER TABLE public.inventory_asset_outcome_watermark
  ADD COLUMN passport_observation_sha256 varchar(64);

ALTER TABLE public.inventory_asset_outcome_watermark
  ADD CONSTRAINT ck_inventory_outcome_watermark_passport_sha CHECK (
    passport_observation_sha256 IS NULL
    OR passport_observation_sha256 ~ '^[0-9a-f]{64}$');
