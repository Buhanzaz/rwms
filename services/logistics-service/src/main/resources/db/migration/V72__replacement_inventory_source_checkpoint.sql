-- A replacement may keep the order's regional warehouse while selecting a cabin and furniture
-- from an authorized support warehouse. Freeze that physical source for crash recovery.

ALTER TABLE public.shipment_furniture_movement_task
    ADD COLUMN replacement_inventory_source_warehouse_id uuid;

UPDATE public.shipment_furniture_movement_task link
SET replacement_inventory_source_warehouse_id = orders.warehouse_id
FROM public.rental_order orders
WHERE link.order_id = orders.id
  AND link.replacement_idempotency_key IS NOT NULL
  AND link.replacement_inventory_source_warehouse_id IS NULL;

ALTER TABLE public.shipment_furniture_movement_task
    DROP CONSTRAINT ck_shipment_furniture_movement_task_replacement,
    ADD CONSTRAINT ck_shipment_furniture_movement_task_replacement
        CHECK (
            (
                replacement_idempotency_key IS NULL
                AND replacement_batch_idempotency_key IS NULL
                AND replacement_pair_index IS NULL
                AND old_rental_item_id IS NULL
                AND replacement_actor_subject_id IS NULL
                AND replacement_actor_role IS NULL
                AND replacement_request_sha256 IS NULL
                AND replacement_presentation_id IS NULL
                AND replacement_inventory_source_warehouse_id IS NULL
                AND replacement_source_reservation_id IS NULL
                AND replacement_completed_at IS NULL
                AND replacement_rejected_at IS NULL
                AND document_id IS NOT NULL
                AND equipment_movement_task_id IS NOT NULL
                AND line_count > 0
            )
            OR (
                replacement_idempotency_key IS NOT NULL
                AND replacement_batch_idempotency_key IS NOT NULL
                AND replacement_pair_index >= 0
                AND order_id IS NOT NULL
                AND old_rental_item_id IS NOT NULL
                AND old_rental_item_id <> rental_item_id
                AND replacement_actor_subject_id IS NOT NULL
                AND replacement_actor_role IS NOT NULL
                AND replacement_request_sha256 ~ '^[0-9a-f]{64}$'
                AND replacement_inventory_source_warehouse_id IS NOT NULL
                AND NOT (
                    replacement_completed_at IS NOT NULL
                    AND replacement_rejected_at IS NOT NULL
                )
                AND (
                    equipment_movement_task_id IS NOT NULL
                    OR replacement_source_reservation_id IS NULL
                )
                AND (
                    replacement_completed_at IS NULL
                    OR equipment_movement_task_id IS NULL
                    OR replacement_source_reservation_id IS NOT NULL
                )
                AND (
                    (line_count = 0 AND equipment_movement_task_id IS NULL)
                    OR (line_count > 0 AND equipment_movement_task_id IS NOT NULL)
                )
            )
        );

CREATE INDEX idx_shipment_furniture_movement_task_replacement_source
    ON public.shipment_furniture_movement_task
        (replacement_inventory_source_warehouse_id, order_id, replacement_batch_idempotency_key)
    WHERE replacement_idempotency_key IS NOT NULL;
