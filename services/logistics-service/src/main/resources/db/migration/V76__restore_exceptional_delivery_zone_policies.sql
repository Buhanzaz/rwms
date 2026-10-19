ALTER TABLE public.customer_warehouse_capacity_command_receipt
    ADD COLUMN price_zone_count integer NOT NULL DEFAULT 0,
    ADD COLUMN restriction_zone_count integer NOT NULL DEFAULT 0,
    ADD CONSTRAINT ck_customer_warehouse_capacity_receipt_price_zone_count CHECK (
        price_zone_count BETWEEN 0 AND 500
    ),
    ADD CONSTRAINT ck_customer_warehouse_capacity_receipt_restriction_zone_count CHECK (
        restriction_zone_count BETWEEN 0 AND 500
    );

CREATE TABLE public.customer_warehouse_capacity_price_zone (
    id uuid NOT NULL,
    snapshot_id uuid NOT NULL,
    source_zone_id uuid NOT NULL,
    source_zone_version bigint NOT NULL,
    delivery_price_rubles bigint NOT NULL,
    pickup_price_rubles bigint NOT NULL,
    geometry_json text NOT NULL,
    CONSTRAINT customer_warehouse_capacity_price_zone_pkey PRIMARY KEY (id),
    CONSTRAINT fk_customer_warehouse_capacity_price_zone_snapshot
        FOREIGN KEY (snapshot_id)
        REFERENCES public.customer_warehouse_capacity_snapshot(id) ON DELETE CASCADE,
    CONSTRAINT uk_customer_warehouse_capacity_price_zone_source UNIQUE (
        snapshot_id, source_zone_id
    ),
    CONSTRAINT ck_customer_warehouse_capacity_price_zone_values CHECK (
        source_zone_version >= 0
        AND delivery_price_rubles >= 0
        AND pickup_price_rubles >= 0
    ),
    CONSTRAINT ck_customer_warehouse_capacity_price_zone_geometry CHECK (
        jsonb_typeof(geometry_json::jsonb) = 'object'
        AND geometry_json::jsonb ->> 'type' = 'MultiPolygon'
        AND jsonb_typeof(geometry_json::jsonb -> 'coordinates') = 'array'
        AND jsonb_array_length(geometry_json::jsonb -> 'coordinates') > 0
    )
);

CREATE INDEX idx_customer_warehouse_capacity_price_zone_snapshot
    ON public.customer_warehouse_capacity_price_zone (snapshot_id, source_zone_id);

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
