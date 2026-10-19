ALTER TABLE public.transfer_plan
    ADD COLUMN workflow_state varchar(32) NOT NULL DEFAULT 'NOT_STARTED',
    ADD COLUMN workflow_failure_code varchar(96),
    ADD COLUMN trip_driver_assignment_id uuid,
    ADD COLUMN trip_driver_assignment_version bigint,
    ADD COLUMN trip_driver_assignment_status varchar(16),
    ADD COLUMN repositioned_driver_assignment_id uuid,
    ADD COLUMN repositioned_driver_assignment_version bigint,
    ADD COLUMN repositioned_driver_assignment_status varchar(16),
    ADD CONSTRAINT ck_transfer_plan_workflow_state CHECK (
        workflow_state IN (
            'NOT_STARTED',
            'RESERVING',
            'READY',
            'IN_TRANSIT',
            'COMPLETING',
            'COMPLETED',
            'RELEASING',
            'RELEASED',
            'CONFLICT',
            'RECONCILIATION_REQUIRED'
        )
    ),
    ADD CONSTRAINT ck_transfer_plan_workflow_failure CHECK (
        (workflow_state IN ('CONFLICT', 'RECONCILIATION_REQUIRED'))
            = (workflow_failure_code IS NOT NULL)
    ),
    ADD CONSTRAINT ck_transfer_plan_trip_driver_assignment CHECK (
        (trip_driver_assignment_id IS NULL
            AND trip_driver_assignment_version IS NULL
            AND trip_driver_assignment_status IS NULL)
        OR (trip_driver_assignment_id IS NOT NULL
            AND trip_driver_assignment_version >= 0
            AND trip_driver_assignment_status IN (
                'PLANNED', 'IN_TRANSIT', 'ACTIVE', 'COMPLETED', 'CANCELLED'
            ))
    ),
    ADD CONSTRAINT ck_transfer_plan_repositioned_driver_assignment CHECK (
        (repositioned_driver_assignment_id IS NULL
            AND repositioned_driver_assignment_version IS NULL
            AND repositioned_driver_assignment_status IS NULL)
        OR (repositioned_driver_assignment_id IS NOT NULL
            AND repositioned_driver_assignment_version >= 0
            AND repositioned_driver_assignment_status IN (
                'PLANNED', 'IN_TRANSIT', 'ACTIVE', 'COMPLETED', 'CANCELLED'
            ))
    );

ALTER TABLE public.logistics_document
    DROP CONSTRAINT ck_logistics_document_transfer_driver,
    ADD CONSTRAINT ck_logistics_document_transfer_driver CHECK (
        document_type <> 'TRANSFER'
        OR (driver_snapshot IS NULL AND driver_worker_id IS NULL)
        OR (driver_snapshot IS NOT NULL
            AND btrim(driver_snapshot) <> ''
            AND driver_worker_id IS NOT NULL)
    );

ALTER TABLE public.transfer_plan
    DROP CONSTRAINT ck_transfer_plan_reservation,
    ADD CONSTRAINT ck_transfer_plan_reservation CHECK (
        reservation_readiness IN (
            'NOT_RESERVED', 'RESERVING', 'RESERVED', 'FAILED', 'RELEASING', 'RELEASED'
        )
    );

ALTER TABLE public.transfer_loose_furniture
    ADD COLUMN source_balance_id uuid,
    ADD COLUMN expected_source_balance_version bigint,
    ADD COLUMN reservation_id uuid,
    ADD COLUMN reservation_version bigint,
    ADD COLUMN reservation_state varchar(32) NOT NULL DEFAULT 'PENDING',
    ADD CONSTRAINT ck_transfer_loose_furniture_reservation_state CHECK (
        reservation_state IN (
            'PENDING',
            'RESERVED',
            'IN_TRANSIT',
            'EXECUTED',
            'RELEASED',
            'CONFLICT',
            'RECONCILIATION_REQUIRED'
        )
    ),
    ADD CONSTRAINT ck_transfer_loose_furniture_source CHECK (
        (source_balance_id IS NULL AND expected_source_balance_version IS NULL)
        OR (source_balance_id IS NOT NULL AND expected_source_balance_version >= 0)
    ),
    ADD CONSTRAINT ck_transfer_loose_furniture_reservation CHECK (
        (reservation_id IS NULL AND reservation_version IS NULL
            AND reservation_state = 'PENDING')
        OR (reservation_id IS NOT NULL AND reservation_version >= 0
            AND reservation_state <> 'PENDING')
    );

CREATE UNIQUE INDEX uk_transfer_loose_furniture_reservation
    ON public.transfer_loose_furniture (reservation_id)
    WHERE reservation_id IS NOT NULL;

ALTER TABLE public.logistics_document_line
    ADD COLUMN transfer_unit_reservation_id uuid,
    ADD COLUMN transfer_unit_reservation_version bigint,
    ADD COLUMN transfer_unit_reservation_state varchar(16),
    ADD CONSTRAINT ck_logistics_document_line_transfer_reservation CHECK (
        (transfer_unit_reservation_id IS NULL
            AND transfer_unit_reservation_version IS NULL
            AND transfer_unit_reservation_state IS NULL)
        OR (transfer_unit_reservation_id IS NOT NULL
            AND transfer_unit_reservation_version >= 0
            AND transfer_unit_reservation_state IN ('ACTIVE', 'RELEASED', 'CONSUMED'))
    );

CREATE UNIQUE INDEX uk_logistics_document_line_transfer_reservation
    ON public.logistics_document_line (transfer_unit_reservation_id)
    WHERE transfer_unit_reservation_id IS NOT NULL;
