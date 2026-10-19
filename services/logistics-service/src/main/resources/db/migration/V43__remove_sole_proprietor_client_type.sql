-- Preserve existing client and order identities while retiring the former sole-proprietor form.
-- A same-phone legal-entity row is ambiguous and must be reconciled explicitly rather than merged.
DO $$
BEGIN
    IF EXISTS (
        SELECT 1
        FROM public.order_client proprietor
        JOIN public.order_client legal_entity
            ON legal_entity.client_type = 'LEGAL_ENTITY'
            AND legal_entity.normalized_phone = proprietor.normalized_phone
        WHERE proprietor.client_type = 'SOLE_PROPRIETOR'
            AND proprietor.normalized_phone IS NOT NULL
    ) THEN
        RAISE EXCEPTION
            'Cannot reclassify SOLE_PROPRIETOR clients with the same normalized phone as LEGAL_ENTITY clients; reconcile the duplicate client records before V43';
    END IF;
END;
$$;

UPDATE public.order_client
SET client_type = 'LEGAL_ENTITY'
WHERE client_type = 'SOLE_PROPRIETOR';

ALTER TABLE public.order_client
    DROP CONSTRAINT ck_order_client_type;

ALTER TABLE public.order_client
    ADD CONSTRAINT ck_order_client_type CHECK (
        client_type IN ('INDIVIDUAL', 'LEGAL_ENTITY')
    );
