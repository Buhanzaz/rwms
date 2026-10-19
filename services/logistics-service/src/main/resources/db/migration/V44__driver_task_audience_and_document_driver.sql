ALTER TABLE logistics_document
    ADD COLUMN driver_worker_id UUID;

ALTER TABLE driver_logistics_task
    DROP CONSTRAINT ck_driver_logistics_task_source,
    DROP CONSTRAINT ck_driver_logistics_task_kind,
    ADD CONSTRAINT ck_driver_logistics_task_source
        CHECK (source_type IN (
            'REPAIR', 'ESTIMATE', 'INVENTORY', 'REPAIR_PLACE', 'CAPITAL_REPAIR',
            'MANUAL', 'LOGISTICS_DOCUMENT_LINE'
        )),
    ADD CONSTRAINT ck_driver_logistics_task_kind
        CHECK (task_kind IN (
            'DELIVER_TO_REPAIR', 'REMOVE_FROM_REPAIR', 'CAPITAL_TO_PRODUCTION',
            'GENERAL_MOVEMENT', 'SHIPMENT', 'RETURN', 'TRANSFER'
        ));

ALTER TABLE driver_logistics_task
    ADD COLUMN driver_audience_mode VARCHAR(32) NOT NULL DEFAULT 'WAREHOUSE_DRIVERS',
    ADD COLUMN planned_driver_worker_id UUID,
    ADD COLUMN planned_driver_name_snapshot VARCHAR(512);

ALTER TABLE driver_logistics_task
    ADD CONSTRAINT ck_driver_logistics_task_audience
    CHECK (
        (driver_audience_mode = 'UNASSIGNED'
            AND planned_driver_worker_id IS NULL
            AND planned_driver_name_snapshot IS NULL)
        OR (driver_audience_mode = 'ASSIGNED_DRIVER'
            AND planned_driver_worker_id IS NOT NULL
            AND planned_driver_name_snapshot IS NOT NULL)
        OR (driver_audience_mode = 'WAREHOUSE_DRIVERS'
            AND ((planned_driver_worker_id IS NULL AND planned_driver_name_snapshot IS NULL)
                OR (planned_driver_worker_id IS NOT NULL
                    AND planned_driver_name_snapshot IS NOT NULL)))
    );

CREATE INDEX ix_driver_logistics_task_planned_worker
    ON driver_logistics_task (warehouse_id, planned_driver_worker_id)
    WHERE planned_driver_worker_id IS NOT NULL;
