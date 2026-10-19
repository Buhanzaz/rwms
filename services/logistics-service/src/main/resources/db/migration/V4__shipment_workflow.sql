ALTER TABLE public.logistics_idempotency_record
    DROP CONSTRAINT ck_logistics_idempotency_operation;

ALTER TABLE public.logistics_idempotency_record
    ADD CONSTRAINT ck_logistics_idempotency_operation CHECK (
        operation_name IN (
            'CREATE_RETURN',
            'CREATE_SHIPMENT',
            'CREATE_TRANSFER',
            'REGISTER_RETURN',
            'ACCEPT_RETURN',
            'REQUEST_RETURN_ESTIMATE',
            'PLAN_SHIPMENT',
            'CONFIRM_SHIPMENT',
            'CANCEL_SHIPMENT'
        )
    );

ALTER TABLE public.logistics_document
    DROP CONSTRAINT ck_logistics_document_state;

ALTER TABLE public.logistics_document
    ADD CONSTRAINT ck_logistics_document_state CHECK (
        state IN (
            'DRAFT', 'REGISTERING', 'INSPECTION_REQUIRED', 'ACCEPTING', 'ACCEPTED',
            'ESTIMATE_PENDING', 'ESTIMATE_REQUESTED', 'PREPARING',
            'AWAITING_CONFIRMATION', 'CONFIRMING_PREPARATION', 'SHIPPED', 'CANCELLING',
            'DEPARTING', 'IN_TRANSIT', 'ARRIVING', 'COMPLETED', 'CANCELLED',
            'CONFLICT', 'RECONCILIATION_REQUIRED'
        )
    );

ALTER TABLE public.logistics_equipment_hold_reference
    ADD COLUMN row_version bigint NOT NULL DEFAULT 0,
    ADD COLUMN equipment_id uuid NOT NULL,
    ADD COLUMN warehouse_id uuid NOT NULL,
    ADD COLUMN quantity bigint NOT NULL,
    ADD COLUMN expected_stock_version bigint NOT NULL,
    ADD COLUMN hold_version bigint NOT NULL;

ALTER TABLE public.logistics_equipment_hold_reference
    ADD CONSTRAINT ck_logistics_equipment_hold_row_version CHECK (row_version >= 0),
    ADD CONSTRAINT ck_logistics_equipment_hold_quantity CHECK (quantity >= 1),
    ADD CONSTRAINT ck_logistics_equipment_hold_expected_stock_version CHECK (expected_stock_version >= 0),
    ADD CONSTRAINT ck_logistics_equipment_hold_version CHECK (hold_version >= 0);

CREATE UNIQUE INDEX uk_logistics_equipment_hold_line_equipment
    ON public.logistics_equipment_hold_reference (line_id, equipment_id);

ALTER TABLE public.logistics_task_reference
    ADD COLUMN row_version bigint NOT NULL DEFAULT 0,
    ADD COLUMN task_id uuid,
    ADD COLUMN task_version bigint,
    ADD COLUMN warehouse_id uuid NOT NULL,
    ADD COLUMN done_at timestamptz;

ALTER TABLE public.logistics_task_reference
    DROP CONSTRAINT ck_logistics_task_reference_state;

ALTER TABLE public.logistics_task_reference
    ADD CONSTRAINT ck_logistics_task_reference_state CHECK (
        task_state IN ('PENDING', 'REGISTERED', 'READY', 'DONE', 'CANCELLED', 'CONFLICT', 'RECONCILIATION_REQUIRED')
    );

ALTER TABLE public.logistics_task_reference
    ADD CONSTRAINT ck_logistics_task_reference_row_version CHECK (row_version >= 0),
    ADD CONSTRAINT ck_logistics_task_reference_task_version CHECK (
        task_version IS NULL OR task_version >= 0
    ),
    ADD CONSTRAINT ck_logistics_task_reference_task_identity CHECK (
        (task_state = 'PENDING' AND task_id IS NULL AND task_version IS NULL AND done_at IS NULL)
        OR
        (task_state = 'CANCELLED' AND task_id IS NULL AND task_version IS NULL AND done_at IS NULL)
        OR
        (task_state <> 'PENDING' AND task_id IS NOT NULL AND task_version IS NOT NULL)
    );
