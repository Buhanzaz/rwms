ALTER TABLE public.driver_logistics_task
    ADD COLUMN source_plan_id uuid,
    ADD COLUMN source_plan_version bigint,
    ADD COLUMN source_plan_warehouse_id uuid,
    ADD COLUMN source_plan_date date,
    ADD CONSTRAINT ck_driver_logistics_task_planner_lineage CHECK (
        (source_plan_id IS NULL
            AND source_plan_version IS NULL
            AND source_plan_warehouse_id IS NULL
            AND source_plan_date IS NULL)
        OR
        (source_type = 'LOGISTICS_DOCUMENT'
            AND task_kind = 'SHIPMENT'
            AND source_plan_id IS NOT NULL
            AND source_plan_version >= 1
            AND source_plan_warehouse_id IS NOT NULL
            AND source_plan_date IS NOT NULL)
    );

CREATE INDEX idx_driver_logistics_task_source_plan
    ON public.driver_logistics_task (
        source_plan_id,
        source_plan_warehouse_id,
        source_plan_date,
        external_task_id
    )
    WHERE source_plan_id IS NOT NULL;

CREATE TABLE public.planning_assignment_replacement_receipt (
    idempotency_key uuid NOT NULL,
    source_plan_id uuid NOT NULL,
    expected_source_plan_version bigint NOT NULL,
    replacement_plan_version bigint NOT NULL,
    source_plan_warehouse_id uuid NOT NULL,
    source_plan_date date NOT NULL,
    request_sha256 varchar(64) NOT NULL,
    response_json jsonb NOT NULL,
    created_at timestamptz NOT NULL,
    CONSTRAINT planning_assignment_replacement_receipt_pkey PRIMARY KEY (idempotency_key),
    CONSTRAINT ck_planning_assignment_replacement_versions CHECK (
        expected_source_plan_version >= 1
        AND replacement_plan_version > expected_source_plan_version
    ),
    CONSTRAINT ck_planning_assignment_replacement_sha256 CHECK (
        request_sha256 ~ '^[0-9a-f]{64}$'
    )
);

CREATE UNIQUE INDEX uk_logistics_planning_assignment_replacement_revision
    ON public.planning_assignment_replacement_receipt (source_plan_id, replacement_plan_version);
