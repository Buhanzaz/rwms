ALTER TABLE public.inventory_finding
  ADD COLUMN membership_active boolean NOT NULL DEFAULT true,
  ADD CONSTRAINT ck_finding_membership_owner_proof CHECK (
    membership_active OR NOT owner_proof_active);

CREATE INDEX idx_finding_active_session
  ON public.inventory_finding(inventory_id, created_at, id)
  WHERE membership_active;

CREATE INDEX idx_finding_active_asset
  ON public.inventory_finding(asset_id, inventory_id)
  WHERE membership_active AND asset_id IS NOT NULL;

ALTER TABLE public.inbox_message
  DROP CONSTRAINT ck_inventory_inbox_topic,
  ADD CONSTRAINT ck_inventory_inbox_topic CHECK (
    source_topic IN ('rwms.media.media.v1', 'rwms.asset.rental-item.v1'));
