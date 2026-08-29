CREATE TABLE public.transfer_plan (
    id uuid NOT NULL,
    version bigint NOT NULL,
    document_id uuid NOT NULL,
    state varchar(16) NOT NULL,
    reservation_readiness varchar(24) NOT NULL,
    planned_departure_at timestamptz,
    planned_arrival_at timestamptz,
    logistics_comment varchar(2000),
    trip_driver_id uuid,
    trip_vehicle_id uuid,
    repositioned_driver_id uuid,
    driver_reposition_mode varchar(16) NOT NULL,
    driver_reposition_until timestamptz,
    repositioned_vehicle_id uuid,
    vehicle_reposition_mode varchar(16) NOT NULL,
    vehicle_reposition_until timestamptz,
    created_at timestamptz NOT NULL,
    updated_at timestamptz NOT NULL,
    CONSTRAINT transfer_plan_pkey PRIMARY KEY (id),
    CONSTRAINT fk_transfer_plan_document FOREIGN KEY (document_id)
        REFERENCES public.logistics_document(id),
    CONSTRAINT uk_transfer_plan_document UNIQUE (document_id),
    CONSTRAINT ck_transfer_plan_version CHECK (version >= 0),
    CONSTRAINT ck_transfer_plan_state CHECK (state IN ('DRAFT', 'CONFIRMED')),
    CONSTRAINT ck_transfer_plan_reservation CHECK (
        reservation_readiness IN ('NOT_RESERVED', 'RESERVED')
    ),
    CONSTRAINT ck_transfer_plan_timing CHECK (
        (planned_arrival_at IS NULL OR planned_departure_at IS NOT NULL)
        AND (planned_arrival_at IS NULL OR planned_arrival_at > planned_departure_at)
    ),
    CONSTRAINT ck_transfer_plan_comment CHECK (
        logistics_comment IS NULL OR length(btrim(logistics_comment)) BETWEEN 1 AND 2000
    ),
    CONSTRAINT ck_transfer_plan_driver_reposition CHECK (
        (driver_reposition_mode = 'NONE'
            AND repositioned_driver_id IS NULL
            AND driver_reposition_until IS NULL)
        OR (driver_reposition_mode = 'TEMPORARY'
            AND repositioned_driver_id IS NOT NULL
            AND driver_reposition_until IS NOT NULL)
        OR (driver_reposition_mode = 'PERMANENT'
            AND repositioned_driver_id IS NOT NULL
            AND driver_reposition_until IS NULL)
    ),
    CONSTRAINT ck_transfer_plan_vehicle_reposition CHECK (
        (vehicle_reposition_mode = 'NONE'
            AND repositioned_vehicle_id IS NULL
            AND vehicle_reposition_until IS NULL)
        OR (vehicle_reposition_mode = 'TEMPORARY'
            AND repositioned_vehicle_id IS NOT NULL
            AND vehicle_reposition_until IS NOT NULL)
        OR (vehicle_reposition_mode = 'PERMANENT'
            AND repositioned_vehicle_id IS NOT NULL
            AND vehicle_reposition_until IS NULL)
    ),
    CONSTRAINT ck_transfer_plan_driver_reposition_time CHECK (
        driver_reposition_until IS NULL
        OR planned_arrival_at IS NULL
        OR driver_reposition_until > planned_arrival_at
    ),
    CONSTRAINT ck_transfer_plan_vehicle_reposition_time CHECK (
        vehicle_reposition_until IS NULL
        OR planned_arrival_at IS NULL
        OR vehicle_reposition_until > planned_arrival_at
    )
);

CREATE TABLE public.transfer_cargo_group (
    id uuid NOT NULL,
    version bigint NOT NULL,
    plan_id uuid NOT NULL,
    position integer NOT NULL,
    rental_type_id uuid NOT NULL,
    dimension_id uuid,
    finishing_id uuid,
    linoleum boolean,
    quantity integer NOT NULL,
    CONSTRAINT transfer_cargo_group_pkey PRIMARY KEY (id),
    CONSTRAINT fk_transfer_cargo_group_plan FOREIGN KEY (plan_id)
        REFERENCES public.transfer_plan(id),
    CONSTRAINT uk_transfer_cargo_group_position UNIQUE (plan_id, position),
    CONSTRAINT ck_transfer_cargo_group_version CHECK (version >= 0),
    CONSTRAINT ck_transfer_cargo_group_values CHECK (position >= 1 AND quantity >= 1)
);

CREATE TABLE public.transfer_cargo_group_characteristic (
    group_id uuid NOT NULL,
    characteristic_id uuid NOT NULL,
    CONSTRAINT fk_transfer_cargo_group_characteristic_group FOREIGN KEY (group_id)
        REFERENCES public.transfer_cargo_group(id),
    CONSTRAINT uk_transfer_cargo_group_characteristic UNIQUE (group_id, characteristic_id)
);

CREATE TABLE public.transfer_cargo_group_furniture (
    id uuid NOT NULL,
    version bigint NOT NULL,
    group_id uuid NOT NULL,
    position integer NOT NULL,
    furniture_catalog_item_id uuid NOT NULL,
    quantity_per_cabin bigint NOT NULL,
    CONSTRAINT transfer_cargo_group_furniture_pkey PRIMARY KEY (id),
    CONSTRAINT fk_transfer_cargo_group_furniture_group FOREIGN KEY (group_id)
        REFERENCES public.transfer_cargo_group(id),
    CONSTRAINT uk_transfer_cargo_group_furniture_position UNIQUE (group_id, position),
    CONSTRAINT uk_transfer_cargo_group_furniture_catalog
        UNIQUE (group_id, furniture_catalog_item_id),
    CONSTRAINT ck_transfer_cargo_group_furniture_version CHECK (version >= 0),
    CONSTRAINT ck_transfer_cargo_group_furniture_values CHECK (
        position >= 1 AND quantity_per_cabin >= 1
    )
);

CREATE TABLE public.transfer_cargo_group_allocation (
    id uuid NOT NULL,
    version bigint NOT NULL,
    group_id uuid NOT NULL,
    position integer NOT NULL,
    asset_id uuid NOT NULL,
    asset_version bigint NOT NULL,
    CONSTRAINT transfer_cargo_group_allocation_pkey PRIMARY KEY (id),
    CONSTRAINT fk_transfer_cargo_group_allocation_group FOREIGN KEY (group_id)
        REFERENCES public.transfer_cargo_group(id),
    CONSTRAINT uk_transfer_cargo_group_allocation_position UNIQUE (group_id, position),
    CONSTRAINT uk_transfer_cargo_group_allocation_asset UNIQUE (group_id, asset_id),
    CONSTRAINT ck_transfer_cargo_group_allocation_version CHECK (version >= 0),
    CONSTRAINT ck_transfer_cargo_group_allocation_values CHECK (
        position >= 1 AND asset_version >= 0
    )
);

CREATE TABLE public.transfer_loose_furniture (
    id uuid NOT NULL,
    version bigint NOT NULL,
    plan_id uuid NOT NULL,
    position integer NOT NULL,
    furniture_catalog_item_id uuid NOT NULL,
    quantity bigint NOT NULL,
    CONSTRAINT transfer_loose_furniture_pkey PRIMARY KEY (id),
    CONSTRAINT fk_transfer_loose_furniture_plan FOREIGN KEY (plan_id)
        REFERENCES public.transfer_plan(id),
    CONSTRAINT uk_transfer_loose_furniture_position UNIQUE (plan_id, position),
    CONSTRAINT uk_transfer_loose_furniture_catalog UNIQUE (
        plan_id, furniture_catalog_item_id
    ),
    CONSTRAINT ck_transfer_loose_furniture_version CHECK (version >= 0),
    CONSTRAINT ck_transfer_loose_furniture_values CHECK (position >= 1 AND quantity >= 1)
);

CREATE INDEX idx_transfer_cargo_group_plan
    ON public.transfer_cargo_group(plan_id, position);
CREATE INDEX idx_transfer_cargo_group_allocation_asset
    ON public.transfer_cargo_group_allocation(asset_id, group_id);
CREATE INDEX idx_transfer_loose_furniture_plan
    ON public.transfer_loose_furniture(plan_id, position);

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
            'CANCEL_TRANSFER',
            'RECONCILE_DOCUMENT'
        )
    );
