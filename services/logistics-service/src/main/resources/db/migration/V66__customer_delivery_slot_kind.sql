ALTER TABLE public.customer_delivery_slot
    ADD COLUMN slot_kind varchar(32);

UPDATE public.customer_delivery_slot
SET slot_kind = 'FIXED_WINDOW';

ALTER TABLE public.customer_delivery_slot
    ALTER COLUMN slot_kind SET NOT NULL,
    ADD CONSTRAINT ck_customer_delivery_slot_kind CHECK (
        slot_kind IN ('FIXED_WINDOW', 'DURING_DAY')
    );
