ALTER TABLE public.logistics_idempotency_record
    DROP CONSTRAINT ck_logistics_idempotency_operation;

ALTER TABLE public.logistics_idempotency_record
    ADD CONSTRAINT ck_logistics_idempotency_operation CHECK (
        operation_name IN (
            'CREATE_RETURN',
            'CREATE_SHIPMENT',
            'CREATE_TRANSFER',
            'REGISTER_RETURN'
        )
    );

CREATE UNIQUE INDEX uk_logistics_external_attempt_line_operation
    ON public.logistics_external_attempt (document_id, line_id, operation_type)
    WHERE line_id IS NOT NULL;

CREATE UNIQUE INDEX uk_logistics_external_attempt_document_operation
    ON public.logistics_external_attempt (document_id, operation_type)
    WHERE line_id IS NULL;
