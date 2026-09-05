ALTER TABLE public.order_unit_reservation
    DROP CONSTRAINT ck_order_unit_reservation_released_role;

ALTER TABLE public.order_unit_reservation
    ADD CONSTRAINT ck_order_unit_reservation_released_role CHECK (
        released_by_role IS NULL OR released_by_role IN (
            'SYSTEM_ADMIN', 'WMS_ADMIN', 'WAREHOUSE_MANAGER',
            'RENTAL_MANAGER', 'CUSTOMER', 'VIEWER', 'LOGISTICS_SERVICE'
        )
    ) NOT VALID;

ALTER TABLE public.order_unit_reservation
    VALIDATE CONSTRAINT ck_order_unit_reservation_released_role;
