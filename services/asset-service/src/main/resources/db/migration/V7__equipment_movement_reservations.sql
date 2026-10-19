-- A logistics worker task reserves a concrete source balance rather than a
-- warehouse-wide equipment total. Existing shipment holds remain valid stock
-- holds; where their stock row exists, retain that source identity too.

ALTER TABLE public.equipment_allocation_hold
  ADD COLUMN source_balance_id uuid,
  ADD COLUMN executed_at timestamptz;

ALTER TABLE public.equipment_allocation_hold
  ADD CONSTRAINT fk_equipment_hold_source_balance
    FOREIGN KEY (source_balance_id) REFERENCES public.equipment_balance(id);

UPDATE public.equipment_allocation_hold AS hold
SET source_balance_id = stock.id
FROM public.equipment_balance AS stock
WHERE hold.source_balance_id IS NULL
  AND stock.equipment_id = hold.equipment_id
  AND stock.warehouse_id = hold.warehouse_id
  AND stock.rental_item_id IS NULL
  AND stock.location_kind = 'STOCK';

ALTER TABLE public.equipment_allocation_hold
  DROP CONSTRAINT ck_equipment_hold_state,
  DROP CONSTRAINT ck_equipment_hold_release;

ALTER TABLE public.equipment_allocation_hold
  ADD CONSTRAINT ck_equipment_hold_state
    CHECK (state IN ('ACTIVE','COMMITTED','RELEASED','EXPIRED','EXECUTED')),
  ADD CONSTRAINT ck_equipment_hold_release CHECK (
    (state = 'ACTIVE'
      AND released_at IS NULL AND committed_at IS NULL AND executed_at IS NULL)
    OR (state = 'COMMITTED'
      AND released_at IS NULL AND committed_at IS NOT NULL AND executed_at IS NULL)
    OR (state IN ('RELEASED','EXPIRED')
      AND released_at IS NOT NULL AND executed_at IS NULL)
    OR (state = 'EXECUTED'
      AND released_at IS NULL AND committed_at IS NULL AND executed_at IS NOT NULL)
  ),
  ADD CONSTRAINT ck_equipment_hold_movement_source CHECK (
    owner_type <> 'LOGISTICS_EQUIPMENT_MOVEMENT' OR source_balance_id IS NOT NULL
  );

CREATE INDEX idx_equipment_hold_source_active
  ON public.equipment_allocation_hold(source_balance_id, expires_at)
  WHERE state = 'ACTIVE';

CREATE UNIQUE INDEX uk_equipment_hold_active_movement_line
  ON public.equipment_allocation_hold(owner_type, owner_id)
  WHERE owner_type = 'LOGISTICS_EQUIPMENT_MOVEMENT' AND state = 'ACTIVE';

ALTER TABLE public.domain_event
  DROP CONSTRAINT ck_asset_domain_event_type,
  ADD CONSTRAINT ck_asset_domain_event_type CHECK (event_type IN (
    'asset.rental-item.created.v1','asset.rental-item.passport-changed.v1',
    'asset.rental-item.status-changed.v1','asset.rental-item.warehouse-changed.v1',
    'asset.rental-item.logistics-effect-applied.v1',
    'asset.rental-item.general-comment-changed.v1','asset.rental-item.manual-note-added.v1',
    'asset.equipment-catalog.created.v1','asset.equipment-catalog.changed.v1',
    'asset.equipment-balance.changed.v1','asset.equipment-movement.transferred.v1',
    'asset.equipment-movement.written-off.v1','asset.equipment-movement.lost.v1',
    'asset.equipment-allocation-hold.acquired.v1','asset.equipment-allocation-hold.renewed.v1',
    'asset.equipment-allocation-hold.committed.v1','asset.equipment-allocation-hold.released.v1',
    'asset.equipment-allocation-hold.expired.v1','asset.equipment-allocation-hold.executed.v1',
    'asset.operation-lease.acquired.v1','asset.operation-lease.renewed.v1',
    'asset.operation-lease.released.v1','asset.operation-lease.expired.v1',
    'asset.classifier.created.v1','asset.classifier.changed.v1'));
