UPDATE logistics_document
   SET driver_snapshot = NULL,
       driver_worker_id = NULL
 WHERE document_type = 'TRANSFER'
   AND (driver_snapshot IS NOT NULL OR driver_worker_id IS NOT NULL);

ALTER TABLE logistics_document
    ADD CONSTRAINT ck_logistics_document_transfer_driver
    CHECK (
        document_type <> 'TRANSFER'
        OR (driver_snapshot IS NULL AND driver_worker_id IS NULL)
    );

UPDATE driver_logistics_task
   SET driver_audience_mode = CASE
           WHEN planned_driver_worker_id IS NULL THEN 'UNASSIGNED'
           ELSE 'ASSIGNED_DRIVER'
       END
 WHERE task_kind IN ('SHIPMENT', 'RETURN');

UPDATE driver_logistics_task
   SET driver_audience_mode = 'WAREHOUSE_DRIVERS',
       planned_driver_worker_id = NULL,
       planned_driver_name_snapshot = NULL
 WHERE task_kind NOT IN ('SHIPMENT', 'RETURN');

ALTER TABLE driver_logistics_task
    DROP CONSTRAINT ck_driver_logistics_task_audience,
    ADD CONSTRAINT ck_driver_logistics_task_audience
    CHECK (
        (driver_audience_mode = 'UNASSIGNED'
            AND planned_driver_worker_id IS NULL
            AND planned_driver_name_snapshot IS NULL)
        OR (driver_audience_mode = 'ASSIGNED_DRIVER'
            AND planned_driver_worker_id IS NOT NULL
            AND planned_driver_name_snapshot IS NOT NULL)
        OR (driver_audience_mode = 'WAREHOUSE_DRIVERS'
            AND planned_driver_worker_id IS NULL
            AND planned_driver_name_snapshot IS NULL)
    ),
    ADD CONSTRAINT ck_driver_logistics_task_kind_audience
    CHECK (
        (task_kind IN ('SHIPMENT', 'RETURN')
            AND driver_audience_mode IN ('UNASSIGNED', 'ASSIGNED_DRIVER'))
        OR (task_kind NOT IN ('SHIPMENT', 'RETURN')
            AND driver_audience_mode = 'WAREHOUSE_DRIVERS')
    );
