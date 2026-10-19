ALTER TABLE public.driver_logistics_task
    ALTER COLUMN cabin_id DROP NOT NULL,
    ADD CONSTRAINT ck_driver_logistics_task_cabin_context CHECK (
        cabin_id IS NOT NULL
        OR (
            source_type = 'LOGISTICS_DOCUMENT'
            AND task_kind = 'TRANSFER'
            AND repair_id IS NULL
        )
    );

-- A transfer may now retain the exact assigned trip driver selected by logistics. Other
-- warehouse movements remain shared work, and returns keep their existing audience rules.
ALTER TABLE public.driver_logistics_task
    DROP CONSTRAINT ck_driver_logistics_task_kind_audience,
    ADD CONSTRAINT ck_driver_logistics_task_kind_audience CHECK (
        (task_kind = 'SHIPMENT'
            AND driver_audience_mode IN ('UNASSIGNED', 'ASSIGNED_DRIVER', 'WAREHOUSE_DRIVERS'))
        OR (task_kind = 'RETURN'
            AND driver_audience_mode IN ('UNASSIGNED', 'ASSIGNED_DRIVER'))
        OR (task_kind = 'TRANSFER'
            AND driver_audience_mode IN ('ASSIGNED_DRIVER', 'WAREHOUSE_DRIVERS'))
        OR (task_kind NOT IN ('SHIPMENT', 'RETURN', 'TRANSFER')
            AND driver_audience_mode = 'WAREHOUSE_DRIVERS')
    );
