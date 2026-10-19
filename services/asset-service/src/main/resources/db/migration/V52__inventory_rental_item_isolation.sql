ALTER TABLE public.rental_item
  ADD COLUMN inventory_isolation_id uuid DEFAULT NULL;

CREATE INDEX idx_rental_item_inventory_isolation
  ON public.rental_item(inventory_isolation_id, id)
  WHERE inventory_isolation_id IS NOT NULL;

COMMENT ON COLUMN public.rental_item.inventory_isolation_id IS
  'Inventory UUID that temporarily excludes a retained legacy source cabin from ordinary asset visibility.';
