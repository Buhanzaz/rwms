ALTER TABLE public.rental_order
    DROP CONSTRAINT ck_rental_order_status;

ALTER TABLE public.rental_order
    ADD CONSTRAINT ck_rental_order_status CHECK (
        status IN ('DRAFT', 'SAVED', 'FULFILLED', 'CLOSED', 'CANCELLED')
    );

ALTER TABLE public.rental_order_audit_event
    DROP CONSTRAINT ck_rental_order_audit_event_type;

ALTER TABLE public.rental_order_audit_event
    ADD CONSTRAINT ck_rental_order_audit_event_type CHECK (
        event_type IN (
            'ORDER_CREATED', 'CLIENT_SELECTED', 'CLIENT_CREATED',
            'WAREHOUSE_SELECTED', 'UNIT_ADDED', 'UNIT_ADD_CONFLICT',
            'UNIT_REMOVED', 'RESERVATION_CREATED', 'RESERVATION_RELEASED',
            'EQUIPMENT_ADDED', 'EQUIPMENT_INCREASED', 'EQUIPMENT_DECREASED',
            'WAREHOUSE_OPERATION_CREATED', 'ORDER_CHANGED', 'ORDER_SAVED',
            'ORDER_FULFILLED', 'ORDER_CLOSED', 'ORDER_CANCELLED'
        )
    );

ALTER TABLE public.logistics_document
    ADD COLUMN scheduled_at timestamptz,
    ADD COLUMN rental_order_id uuid;

ALTER TABLE public.logistics_document
    DROP CONSTRAINT ck_logistics_document_shipment_snapshot;

ALTER TABLE public.logistics_document
    ADD CONSTRAINT ck_logistics_document_shipment_snapshot CHECK (
        document_type <> 'SHIPMENT'
        OR (
            party_snapshot IS NOT NULL
            AND btrim(party_snapshot) <> ''
        )
    ),
    ADD CONSTRAINT ck_logistics_document_schedule_scope CHECK (
        scheduled_at IS NULL OR document_type IN ('SHIPMENT', 'RETURN')
    ),
    ADD CONSTRAINT ck_logistics_document_rental_order_scope CHECK (
        rental_order_id IS NULL OR document_type IN ('SHIPMENT', 'RETURN')
    ),
    ADD CONSTRAINT fk_logistics_document_rental_order FOREIGN KEY (rental_order_id)
        REFERENCES public.rental_order(id);

CREATE UNIQUE INDEX uk_logistics_document_shipment_order
    ON public.logistics_document (rental_order_id)
    WHERE document_type = 'SHIPMENT' AND rental_order_id IS NOT NULL;

CREATE UNIQUE INDEX uk_logistics_document_return_order
    ON public.logistics_document (rental_order_id)
    WHERE document_type = 'RETURN' AND rental_order_id IS NOT NULL;

CREATE INDEX idx_logistics_document_schedule
    ON public.logistics_document (warehouse_id, document_type, scheduled_at, id);
