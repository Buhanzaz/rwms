ALTER TABLE public.customer_delivery_slot
    ADD COLUMN site_cabin_capacity integer NOT NULL DEFAULT 2,
    ADD COLUMN delivery_price_rubles bigint,
    ADD COLUMN price_zone_code varchar(128);

ALTER TABLE public.customer_delivery_slot
    DROP CONSTRAINT ck_customer_delivery_slot_capacity,
    ADD CONSTRAINT ck_customer_delivery_slot_capacity CHECK (
        cabin_count > 0
        AND one_way_travel_seconds >= 0
        AND travel_zone_hours >= 1
        AND capacity_remaining >= 0
        AND site_cabin_capacity BETWEEN 1 AND 2
        AND (delivery_price_rubles IS NULL OR delivery_price_rubles >= 0)
        AND (
            (delivery_price_rubles IS NULL AND price_zone_code IS NULL)
            OR (delivery_price_rubles IS NOT NULL AND length(btrim(price_zone_code)) BETWEEN 1 AND 128)
        )
    );

ALTER TABLE public.customer_scenario_capacity_job
    ADD COLUMN task_type varchar(16) NOT NULL DEFAULT 'DELIVERY',
    ADD COLUMN trailer_access_allowed boolean NOT NULL DEFAULT true,
    ADD COLUMN priority integer NOT NULL DEFAULT 0,
    ADD COLUMN mandatory boolean NOT NULL DEFAULT true,
    ADD CONSTRAINT ck_customer_scenario_capacity_job_task_type CHECK (
        task_type IN ('DELIVERY', 'PICKUP')
    ),
    ADD CONSTRAINT ck_customer_scenario_capacity_job_priority CHECK (priority >= 0),
    ADD CONSTRAINT ck_customer_scenario_capacity_job_delivery_mandatory CHECK (
        task_type <> 'DELIVERY' OR mandatory
    );

ALTER TABLE public.customer_scenario_capacity_command_receipt
    ADD COLUMN price_zone_count integer NOT NULL DEFAULT 0,
    ADD CONSTRAINT ck_customer_scenario_capacity_command_receipt_price_zone_count CHECK (
        price_zone_count >= 0
    );

CREATE TABLE public.customer_scenario_capacity_price_zone (
    id uuid NOT NULL,
    snapshot_id uuid NOT NULL,
    source_zone_id uuid NOT NULL,
    source_zone_version bigint NOT NULL,
    code varchar(128) NOT NULL,
    priority integer NOT NULL,
    delivery_price_rubles bigint NOT NULL,
    pickup_price_rubles bigint NOT NULL,
    geometry_json text NOT NULL,
    CONSTRAINT customer_scenario_capacity_price_zone_pkey PRIMARY KEY (id),
    CONSTRAINT fk_customer_scenario_capacity_price_zone_snapshot FOREIGN KEY (snapshot_id)
        REFERENCES public.customer_scenario_capacity_snapshot(id) ON DELETE CASCADE,
    CONSTRAINT uk_customer_scenario_capacity_price_zone_source UNIQUE (
        snapshot_id, source_zone_id
    ),
    CONSTRAINT ck_customer_scenario_capacity_price_zone_values CHECK (
        source_zone_version >= 0
        AND length(btrim(code)) BETWEEN 1 AND 128
        AND delivery_price_rubles >= 0
        AND pickup_price_rubles >= 0
    ),
    CONSTRAINT ck_customer_scenario_capacity_price_zone_geometry CHECK (
        jsonb_typeof(geometry_json::jsonb) = 'object'
        AND geometry_json::jsonb ->> 'type' = 'MultiPolygon'
        AND jsonb_typeof(geometry_json::jsonb -> 'coordinates') = 'array'
        AND jsonb_array_length(geometry_json::jsonb -> 'coordinates') > 0
    )
);

CREATE INDEX idx_customer_scenario_capacity_price_zone_snapshot
    ON public.customer_scenario_capacity_price_zone (
        snapshot_id, priority DESC, source_zone_id
    );
