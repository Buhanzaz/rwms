CREATE TABLE public.customer_warehouse_capacity_isochrone_tariff (
    id uuid NOT NULL,
    snapshot_id uuid NOT NULL,
    travel_minutes integer NOT NULL,
    price_rubles bigint NOT NULL,
    CONSTRAINT customer_warehouse_capacity_isochrone_tariff_pkey PRIMARY KEY (id),
    CONSTRAINT fk_customer_warehouse_capacity_isochrone_tariff_snapshot
        FOREIGN KEY (snapshot_id)
        REFERENCES public.customer_warehouse_capacity_snapshot(id) ON DELETE CASCADE,
    CONSTRAINT uk_customer_warehouse_capacity_isochrone_tariff_minutes UNIQUE (
        snapshot_id, travel_minutes
    ),
    CONSTRAINT ck_customer_warehouse_capacity_isochrone_tariff_values CHECK (
        travel_minutes BETWEEN 60 AND 720
        AND travel_minutes % 60 = 0
        AND price_rubles >= 0
    )
);

INSERT INTO public.customer_warehouse_capacity_isochrone_tariff (
    id, snapshot_id, travel_minutes, price_rubles
)
SELECT md5(snapshot.id::text || ':60')::uuid,
       snapshot.id,
       60,
       snapshot.isochrone_price_60_minutes
FROM public.customer_warehouse_capacity_snapshot AS snapshot
UNION ALL
SELECT md5(snapshot.id::text || ':120')::uuid,
       snapshot.id,
       120,
       snapshot.isochrone_price_120_minutes
FROM public.customer_warehouse_capacity_snapshot AS snapshot
UNION ALL
SELECT md5(snapshot.id::text || ':180')::uuid,
       snapshot.id,
       180,
       snapshot.isochrone_price_180_minutes
FROM public.customer_warehouse_capacity_snapshot AS snapshot
UNION ALL
SELECT md5(snapshot.id::text || ':240')::uuid,
       snapshot.id,
       240,
       snapshot.isochrone_price_240_minutes
FROM public.customer_warehouse_capacity_snapshot AS snapshot;

CREATE INDEX idx_customer_warehouse_capacity_isochrone_tariff_snapshot
    ON public.customer_warehouse_capacity_isochrone_tariff (snapshot_id, travel_minutes);

ALTER TABLE public.customer_warehouse_capacity_command_receipt
    ADD COLUMN isochrone_tariff_count integer NOT NULL DEFAULT 4,
    ADD CONSTRAINT ck_customer_warehouse_capacity_receipt_isochrone_tariff_count CHECK (
        isochrone_tariff_count BETWEEN 1 AND 12
    );

ALTER TABLE public.customer_warehouse_capacity_command_receipt
    ALTER COLUMN isochrone_tariff_count DROP DEFAULT,
    DROP CONSTRAINT ck_customer_warehouse_capacity_command_receipt_price_zone_count,
    DROP CONSTRAINT ck_customer_warehouse_capacity_receipt_restriction_count,
    DROP COLUMN price_zone_count,
    DROP COLUMN restriction_zone_count;

ALTER TABLE public.customer_warehouse_capacity_snapshot
    DROP CONSTRAINT ck_customer_warehouse_capacity_snapshot_isochrone_prices,
    DROP COLUMN isochrone_price_60_minutes,
    DROP COLUMN isochrone_price_120_minutes,
    DROP COLUMN isochrone_price_180_minutes,
    DROP COLUMN isochrone_price_240_minutes;

DROP TABLE public.customer_warehouse_capacity_restriction_zone;
DROP TABLE public.customer_warehouse_capacity_price_zone;

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
            (
                price_zone_id IS NULL
                AND price_isochrone_minutes BETWEEN 60 AND 720
                AND price_isochrone_minutes % 60 = 0
            )
        )
    );
