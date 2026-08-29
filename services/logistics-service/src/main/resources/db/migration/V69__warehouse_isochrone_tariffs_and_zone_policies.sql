ALTER TABLE public.customer_warehouse_capacity_snapshot
    ADD COLUMN isochrone_price_60_minutes bigint NOT NULL DEFAULT 10000,
    ADD COLUMN isochrone_price_120_minutes bigint NOT NULL DEFAULT 15000,
    ADD COLUMN isochrone_price_180_minutes bigint NOT NULL DEFAULT 20000,
    ADD COLUMN isochrone_price_240_minutes bigint NOT NULL DEFAULT 25000,
    ADD CONSTRAINT ck_customer_warehouse_capacity_snapshot_isochrone_prices CHECK (
        isochrone_price_60_minutes >= 0
        AND isochrone_price_120_minutes >= 0
        AND isochrone_price_180_minutes >= 0
        AND isochrone_price_240_minutes >= 0
    );

ALTER TABLE public.customer_warehouse_capacity_command_receipt
    ADD COLUMN restriction_zone_count integer NOT NULL DEFAULT 0,
    ADD CONSTRAINT ck_customer_warehouse_capacity_receipt_restriction_count CHECK (
        restriction_zone_count >= 0
    );

CREATE TABLE public.customer_warehouse_capacity_restriction_zone (
    id uuid NOT NULL,
    snapshot_id uuid NOT NULL,
    source_zone_id uuid NOT NULL,
    source_zone_version bigint NOT NULL,
    restriction_kind varchar(32) NOT NULL,
    geometry_json text NOT NULL,
    CONSTRAINT customer_warehouse_capacity_restriction_zone_pkey PRIMARY KEY (id),
    CONSTRAINT fk_customer_warehouse_capacity_restriction_zone_snapshot
        FOREIGN KEY (snapshot_id)
        REFERENCES public.customer_warehouse_capacity_snapshot(id) ON DELETE CASCADE,
    CONSTRAINT uk_customer_warehouse_capacity_restriction_zone_source UNIQUE (
        snapshot_id, source_zone_id
    ),
    CONSTRAINT ck_customer_warehouse_capacity_restriction_zone_values CHECK (
        source_zone_version >= 0
        AND restriction_kind IN ('FORBIDDEN', 'NO_TRAILER')
    ),
    CONSTRAINT ck_customer_warehouse_capacity_restriction_zone_geometry CHECK (
        jsonb_typeof(geometry_json::jsonb) = 'object'
        AND geometry_json::jsonb ->> 'type' = 'MultiPolygon'
        AND jsonb_typeof(geometry_json::jsonb -> 'coordinates') = 'array'
        AND jsonb_array_length(geometry_json::jsonb -> 'coordinates') > 0
    )
);

CREATE INDEX idx_customer_warehouse_capacity_restriction_zone_snapshot
    ON public.customer_warehouse_capacity_restriction_zone (snapshot_id, source_zone_id);

ALTER TABLE public.customer_delivery_slot
    ADD COLUMN price_isochrone_minutes integer;

UPDATE public.customer_delivery_slot
SET price_isochrone_minutes = CASE
        WHEN one_way_travel_seconds <= 3600 THEN 60
        WHEN one_way_travel_seconds <= 7200 THEN 120
        WHEN one_way_travel_seconds <= 10800 THEN 180
        ELSE 240
    END,
    delivery_price_rubles = COALESCE(
        delivery_price_rubles,
        CASE
            WHEN one_way_travel_seconds <= 3600 THEN 10000
            WHEN one_way_travel_seconds <= 7200 THEN 15000
            WHEN one_way_travel_seconds <= 10800 THEN 20000
            ELSE 25000
        END
    )
WHERE price_zone_id IS NULL;

ALTER TABLE public.customer_delivery_slot
    DROP CONSTRAINT ck_customer_delivery_slot_capacity,
    ADD CONSTRAINT ck_customer_delivery_slot_capacity CHECK (
        cabin_count > 0
        AND one_way_travel_seconds >= 0
        AND travel_zone_hours >= 1
        AND capacity_remaining >= 0
        AND site_cabin_capacity BETWEEN 1 AND 2
        AND delivery_price_rubles IS NOT NULL
        AND delivery_price_rubles >= 0
        AND (
            (price_zone_id IS NOT NULL AND price_isochrone_minutes IS NULL)
            OR
            (price_zone_id IS NULL AND price_isochrone_minutes IN (60, 120, 180, 240))
        )
    );
