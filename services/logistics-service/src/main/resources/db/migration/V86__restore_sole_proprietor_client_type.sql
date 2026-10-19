ALTER TABLE public.order_client
    DROP CONSTRAINT ck_order_client_type;

ALTER TABLE public.order_client
    ADD CONSTRAINT ck_order_client_type CHECK (
        client_type IN ('INDIVIDUAL', 'SOLE_PROPRIETOR', 'LEGAL_ENTITY')
    );
