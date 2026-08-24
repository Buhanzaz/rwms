ALTER TABLE public.logistics_document
    ADD COLUMN warehouse_driver_pool boolean NOT NULL DEFAULT false;

ALTER TABLE public.logistics_document
    ADD CONSTRAINT ck_logistics_document_warehouse_driver_pool
    CHECK (
        NOT warehouse_driver_pool
        OR (document_type = 'SHIPMENT' AND driver_worker_id IS NULL)
    );

ALTER TABLE public.driver_logistics_task
    DROP CONSTRAINT ck_driver_logistics_task_kind_audience,
    ADD CONSTRAINT ck_driver_logistics_task_kind_audience
    CHECK (
        (task_kind = 'SHIPMENT'
            AND driver_audience_mode IN ('UNASSIGNED', 'ASSIGNED_DRIVER', 'WAREHOUSE_DRIVERS'))
        OR (task_kind = 'RETURN'
            AND driver_audience_mode IN ('UNASSIGNED', 'ASSIGNED_DRIVER'))
        OR (task_kind NOT IN ('SHIPMENT', 'RETURN')
            AND driver_audience_mode = 'WAREHOUSE_DRIVERS')
    );
