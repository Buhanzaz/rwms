-- An order can dispatch its cabins in several independently scheduled batches.
-- These indexes previously forced one whole-order shipment and one whole-order return.
DROP INDEX public.uk_logistics_document_shipment_order;
DROP INDEX public.uk_logistics_document_return_order;

ALTER TABLE public.logistics_document
    ADD COLUMN rental_shipment_id uuid;

ALTER TABLE public.logistics_document
    ADD CONSTRAINT fk_logistics_document_rental_shipment
        FOREIGN KEY (rental_shipment_id) REFERENCES public.logistics_document(id),
    ADD CONSTRAINT ck_logistics_document_rental_shipment_scope CHECK (
        rental_shipment_id IS NULL
        OR (
            document_type = 'RETURN'
            AND rental_order_id IS NOT NULL
            AND rental_shipment_id <> id
        )
    );

CREATE UNIQUE INDEX uk_logistics_document_return_shipment
    ON public.logistics_document (rental_shipment_id)
    WHERE document_type = 'RETURN' AND rental_shipment_id IS NOT NULL;

CREATE INDEX idx_logistics_document_rental_order_type_state
    ON public.logistics_document (rental_order_id, document_type, state, created_at, id)
    WHERE rental_order_id IS NOT NULL;

CREATE TABLE public.rental_order_unit_term (
    id uuid NOT NULL,
    version bigint NOT NULL DEFAULT 0,
    order_id uuid NOT NULL,
    rental_item_id uuid NOT NULL,
    rental_months bigint NOT NULL,
    rental_shipment_id uuid,
    shipment_date date,
    return_date date,
    created_at timestamptz NOT NULL,
    updated_at timestamptz NOT NULL,
    CONSTRAINT rental_order_unit_term_pkey PRIMARY KEY (id),
    CONSTRAINT fk_rental_order_unit_term_order
        FOREIGN KEY (order_id) REFERENCES public.rental_order(id),
    CONSTRAINT fk_rental_order_unit_term_rental_shipment
        FOREIGN KEY (rental_shipment_id) REFERENCES public.logistics_document(id),
    CONSTRAINT uk_rental_order_unit_term UNIQUE (order_id, rental_item_id),
    CONSTRAINT ck_rental_order_unit_term_version CHECK (version >= 0),
    CONSTRAINT ck_rental_order_unit_term_months CHECK (rental_months >= 1),
    CONSTRAINT ck_rental_order_unit_term_dates CHECK (
        (rental_shipment_id IS NULL AND shipment_date IS NULL AND return_date IS NULL)
        OR (
            rental_shipment_id IS NOT NULL
            AND shipment_date IS NOT NULL
            AND return_date IS NOT NULL
            AND return_date > shipment_date
        )
    )
);

CREATE INDEX idx_rental_order_unit_term_order
    ON public.rental_order_unit_term (order_id, rental_item_id);

CREATE INDEX idx_rental_order_unit_term_shipment
    ON public.rental_order_unit_term (rental_shipment_id, rental_item_id)
    WHERE rental_shipment_id IS NOT NULL;

ALTER TABLE public.logistics_idempotency_record
    DROP CONSTRAINT ck_logistics_idempotency_operation;

ALTER TABLE public.logistics_idempotency_record
    ADD CONSTRAINT ck_logistics_idempotency_operation CHECK (
        operation_name IN (
            'CREATE_RETURN',
            'CREATE_SHIPMENT',
            'CREATE_RENTAL_ORDER_SHIPMENT',
            'CREATE_TRANSFER',
            'REGISTER_RETURN',
            'ACCEPT_RETURN',
            'REQUEST_RETURN_ESTIMATE',
            'PLAN_SHIPMENT',
            'CONFIRM_SHIPMENT',
            'CANCEL_SHIPMENT',
            'DEPART_TRANSFER_LINE',
            'ARRIVE_TRANSFER_LINE',
            'CANCEL_TRANSFER',
            'RECONCILE_DOCUMENT'
        )
    );
