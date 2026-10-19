ALTER TABLE public.equipment_allocation_hold
  ADD COLUMN order_id uuid,
  ADD COLUMN target_rental_item_id uuid,
  ADD COLUMN order_units jsonb,
  ADD COLUMN replacement_source_reservation_id uuid,
  ADD CONSTRAINT ck_equipment_hold_order_context CHECK (
    (order_id IS NULL
      AND target_rental_item_id IS NULL
      AND order_units IS NULL
      AND replacement_source_reservation_id IS NULL)
    OR (order_id IS NOT NULL
      AND target_rental_item_id IS NOT NULL
      AND order_units IS NOT NULL
      AND jsonb_typeof(order_units) = 'array'));

CREATE INDEX ix_equipment_hold_active_order_target
  ON public.equipment_allocation_hold(order_id, target_rental_item_id, equipment_id)
  WHERE state = 'ACTIVE' AND order_id IS NOT NULL;
