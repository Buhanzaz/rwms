ALTER TABLE public.order_unit_reservation
    ADD COLUMN client_id uuid,
    ADD COLUMN tenant_snapshot varchar(512);

ALTER TABLE public.order_unit_reservation
    ADD CONSTRAINT ck_order_unit_reservation_client_projection CHECK (
        (client_id IS NULL AND tenant_snapshot IS NULL)
        OR (
            client_id IS NOT NULL
            AND tenant_snapshot IS NOT NULL
            AND btrim(tenant_snapshot) <> ''
        )
    );
