-- One regional order may reserve cabins and their furniture at multiple physical warehouses.
-- Preserve the existing aggregate and evidence rows, but fence active allocations per source.

DROP INDEX public.uk_order_equipment_reservation_active_order_equipment;

CREATE UNIQUE INDEX uk_order_equipment_reservation_active_order_warehouse_equipment
    ON public.order_equipment_reservation (order_id, warehouse_id, equipment_id)
    WHERE state = 'ACTIVE';
