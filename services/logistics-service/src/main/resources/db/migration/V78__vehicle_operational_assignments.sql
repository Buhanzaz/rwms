CREATE TABLE public.vehicle_operational_assignment (
    id uuid NOT NULL,
    version bigint NOT NULL DEFAULT 0,
    transfer_id uuid NOT NULL,
    vehicle_id uuid NOT NULL,
    source_warehouse_id uuid NOT NULL,
    destination_warehouse_id uuid NOT NULL,
    mode varchar(16) NOT NULL,
    status varchar(16) NOT NULL,
    travel_starts_at timestamptz NOT NULL,
    effective_from timestamptz NOT NULL,
    effective_until timestamptz,
    created_at timestamptz NOT NULL,
    updated_at timestamptz NOT NULL,
    CONSTRAINT vehicle_operational_assignment_pkey PRIMARY KEY (id),
    CONSTRAINT fk_vehicle_operational_assignment_transfer
        FOREIGN KEY (transfer_id) REFERENCES public.logistics_document(id),
    CONSTRAINT uk_vehicle_operational_assignment_transfer_vehicle
        UNIQUE (transfer_id, vehicle_id),
    CONSTRAINT ck_vehicle_operational_assignment_version CHECK (version >= 0),
    CONSTRAINT ck_vehicle_operational_assignment_warehouses CHECK (
        source_warehouse_id <> destination_warehouse_id
    ),
    CONSTRAINT ck_vehicle_operational_assignment_mode CHECK (
        mode IN ('TRIP_ONLY', 'TEMPORARY', 'PERMANENT')
    ),
    CONSTRAINT ck_vehicle_operational_assignment_status CHECK (
        status IN ('PLANNED', 'IN_TRANSIT', 'ACTIVE', 'COMPLETED', 'CANCELLED')
    ),
    CONSTRAINT ck_vehicle_operational_assignment_interval CHECK (
        travel_starts_at < effective_from
        AND (
            (mode = 'TRIP_ONLY' AND effective_until = effective_from)
            OR
            (mode = 'TEMPORARY' AND effective_until > effective_from)
            OR
            (
                mode = 'PERMANENT'
                AND (
                    (effective_until IS NULL AND status <> 'COMPLETED')
                    OR
                    (effective_until > effective_from AND status = 'COMPLETED')
                )
            )
        )
    ),
    CONSTRAINT ck_vehicle_operational_assignment_mode_status CHECK (
        (
            mode = 'TRIP_ONLY'
            AND status IN ('PLANNED', 'IN_TRANSIT', 'COMPLETED', 'CANCELLED')
        )
        OR
        (
            mode IN ('TEMPORARY', 'PERMANENT')
            AND status IN ('PLANNED', 'IN_TRANSIT', 'ACTIVE', 'COMPLETED', 'CANCELLED')
        )
    ),
    CONSTRAINT ck_vehicle_operational_assignment_timestamps CHECK (created_at <= updated_at)
);

CREATE INDEX idx_vehicle_operational_assignment_transfer
    ON public.vehicle_operational_assignment (transfer_id, id);

CREATE INDEX idx_vehicle_operational_assignment_vehicle_interval
    ON public.vehicle_operational_assignment (vehicle_id, travel_starts_at, effective_until)
    WHERE status <> 'CANCELLED';

CREATE UNIQUE INDEX uk_vehicle_operational_assignment_active_vehicle
    ON public.vehicle_operational_assignment (vehicle_id)
    WHERE status = 'ACTIVE';

CREATE INDEX idx_vehicle_operational_assignment_source_window
    ON public.vehicle_operational_assignment (
        source_warehouse_id,
        travel_starts_at,
        effective_until
    );

CREATE INDEX idx_vehicle_operational_assignment_destination_window
    ON public.vehicle_operational_assignment (
        destination_warehouse_id,
        travel_starts_at,
        effective_until
    );

ALTER TABLE public.logistics_idempotency_record
    DROP CONSTRAINT ck_logistics_idempotency_operation;

ALTER TABLE public.logistics_idempotency_record
    ADD CONSTRAINT ck_logistics_idempotency_operation CHECK (
        operation_name IN (
            'CREATE_RETURN',
            'CREATE_SHIPMENT',
            'CREATE_RENTAL_ORDER_SHIPMENT',
            'CREATE_HISTORICAL_RENTAL_MOVEMENT',
            'UPDATE_HISTORICAL_RENTAL_MOVEMENT',
            'CREATE_TRANSFER',
            'UPDATE_TRANSFER_PLAN',
            'CONFIRM_TRANSFER_PLAN',
            'REGISTER_RETURN',
            'ACCEPT_RETURN',
            'REQUEST_RETURN_ESTIMATE',
            'START_RETURN_ESTIMATES',
            'PLAN_SHIPMENT',
            'CONFIRM_SHIPMENT',
            'CANCEL_SHIPMENT',
            'DEPART_TRANSFER_LINE',
            'ARRIVE_TRANSFER_LINE',
            'DEPART_TRANSFER',
            'ARRIVE_TRANSFER',
            'CANCEL_TRANSFER',
            'RECONCILE_DOCUMENT'
        )
    );
