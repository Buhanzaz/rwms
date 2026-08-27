CREATE TABLE public.customer_scenario_capacity_snapshot (
    id uuid NOT NULL,
    version bigint NOT NULL DEFAULT 0,
    warehouse_id uuid NOT NULL,
    source_scenario_id uuid NOT NULL,
    source_generation bigint NOT NULL,
    source_revision varchar(64) NOT NULL,
    created_at timestamptz NOT NULL,
    updated_at timestamptz NOT NULL,
    CONSTRAINT customer_scenario_capacity_snapshot_pkey PRIMARY KEY (id),
    CONSTRAINT uk_customer_scenario_capacity_snapshot_warehouse UNIQUE (warehouse_id),
    CONSTRAINT ck_customer_scenario_capacity_snapshot_version CHECK (version >= 0),
    CONSTRAINT ck_customer_scenario_capacity_snapshot_generation CHECK (source_generation >= 1),
    CONSTRAINT ck_customer_scenario_capacity_snapshot_source_revision CHECK (
        source_revision ~ '^[0-9a-f]{64}$'
    )
);

CREATE TABLE public.customer_scenario_capacity_command_receipt (
    idempotency_key uuid NOT NULL,
    warehouse_id uuid NOT NULL,
    source_scenario_id uuid NOT NULL,
    source_generation bigint NOT NULL,
    source_revision varchar(64) NOT NULL,
    request_sha256 varchar(64) NOT NULL,
    snapshot_version bigint NOT NULL,
    job_count integer NOT NULL,
    response_updated_at timestamptz NOT NULL,
    created_at timestamptz NOT NULL,
    CONSTRAINT customer_scenario_capacity_command_receipt_pkey PRIMARY KEY (idempotency_key),
    CONSTRAINT ck_customer_scenario_capacity_command_receipt_version CHECK (
        source_generation >= 1 AND snapshot_version >= 0 AND job_count >= 0
    ),
    CONSTRAINT ck_customer_scenario_capacity_command_receipt_source_revision CHECK (
        source_revision ~ '^[0-9a-f]{64}$'
    ),
    CONSTRAINT ck_customer_scenario_capacity_command_receipt_request_sha256 CHECK (
        request_sha256 ~ '^[0-9a-f]{64}$'
    )
);

CREATE INDEX idx_customer_scenario_capacity_command_revision
    ON public.customer_scenario_capacity_command_receipt (
        warehouse_id, source_scenario_id, source_generation, source_revision, created_at, idempotency_key
    );

CREATE TABLE public.customer_scenario_capacity_job (
    id uuid NOT NULL,
    snapshot_id uuid NOT NULL,
    source_job_id uuid NOT NULL,
    delivery_date date NOT NULL,
    latitude numeric(8,6) NOT NULL,
    longitude numeric(9,6) NOT NULL,
    cabin_count integer NOT NULL,
    window_start time NOT NULL,
    window_end time NOT NULL,
    service_minutes integer NOT NULL,
    CONSTRAINT customer_scenario_capacity_job_pkey PRIMARY KEY (id),
    CONSTRAINT fk_customer_scenario_capacity_job_snapshot FOREIGN KEY (snapshot_id)
        REFERENCES public.customer_scenario_capacity_snapshot(id) ON DELETE CASCADE,
    CONSTRAINT uk_customer_scenario_capacity_job_source UNIQUE (snapshot_id, source_job_id),
    CONSTRAINT ck_customer_scenario_capacity_job_coordinates CHECK (
        latitude BETWEEN -90 AND 90 AND longitude BETWEEN -180 AND 180
    ),
    CONSTRAINT ck_customer_scenario_capacity_job_window CHECK (window_start < window_end),
    CONSTRAINT ck_customer_scenario_capacity_job_capacity CHECK (
        cabin_count > 0 AND service_minutes > 0
    )
);

CREATE INDEX idx_customer_scenario_capacity_job_date
    ON public.customer_scenario_capacity_job (snapshot_id, delivery_date, window_start, source_job_id);
