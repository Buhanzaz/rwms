ALTER TABLE public.order_unit_reservation
    DROP CONSTRAINT ck_order_unit_reservation_added_role;

ALTER TABLE public.order_unit_reservation
    ADD CONSTRAINT ck_order_unit_reservation_added_role CHECK (
        added_by_role IN (
            'SYSTEM_ADMIN', 'WMS_ADMIN', 'WAREHOUSE_MANAGER',
            'RENTAL_MANAGER', 'CUSTOMER', 'VIEWER'
        )
    ) NOT VALID;

ALTER TABLE public.order_unit_reservation
    VALIDATE CONSTRAINT ck_order_unit_reservation_added_role;

ALTER TABLE public.order_unit_reservation
    DROP CONSTRAINT ck_order_unit_reservation_released_role;

ALTER TABLE public.order_unit_reservation
    ADD CONSTRAINT ck_order_unit_reservation_released_role CHECK (
        released_by_role IS NULL OR released_by_role IN (
            'SYSTEM_ADMIN', 'WMS_ADMIN', 'WAREHOUSE_MANAGER',
            'RENTAL_MANAGER', 'CUSTOMER', 'VIEWER'
        )
    ) NOT VALID;

ALTER TABLE public.order_unit_reservation
    VALIDATE CONSTRAINT ck_order_unit_reservation_released_role;

ALTER TABLE public.presentation_unit_hold
    DROP CONSTRAINT ck_presentation_unit_hold_role;

ALTER TABLE public.presentation_unit_hold
    ADD CONSTRAINT ck_presentation_unit_hold_role CHECK (
        created_by_role IN (
            'SYSTEM_ADMIN', 'WMS_ADMIN', 'WAREHOUSE_MANAGER',
            'RENTAL_MANAGER', 'CUSTOMER', 'VIEWER'
        )
    ) NOT VALID;

ALTER TABLE public.presentation_unit_hold
    VALIDATE CONSTRAINT ck_presentation_unit_hold_role;
