ALTER TABLE rental_item_fact_projection
  ADD COLUMN inventory_isolated boolean NOT NULL DEFAULT false;

COMMENT ON COLUMN rental_item_fact_projection.inventory_isolated IS
  'Reversible asset-owned visibility fence; isolated sources cannot receive ordinary maintenance commands';
