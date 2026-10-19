ALTER TABLE public.driver_logistics_task
    ADD COLUMN planner_membership_state varchar(16) NOT NULL DEFAULT 'ACTIVE',
    ADD COLUMN planner_removed_at timestamptz,
    ADD COLUMN planner_removed_source_plan_version bigint,
    ADD CONSTRAINT ck_driver_logistics_task_planner_membership CHECK (
        (planner_membership_state = 'ACTIVE'
            AND planner_removed_at IS NULL
            AND planner_removed_source_plan_version IS NULL)
        OR
        (planner_membership_state = 'REMOVED'
            AND source_plan_id IS NOT NULL
            AND planner_removed_at IS NOT NULL
            AND planner_removed_source_plan_version > source_plan_version)
    );

CREATE TABLE public.planning_published_reschedule_saga (
    id uuid NOT NULL,
    version bigint NOT NULL DEFAULT 0,
    order_id uuid NOT NULL,
    booking_id uuid NOT NULL,
    customer_subject_id uuid NOT NULL,
    source_plan_id uuid NOT NULL,
    expected_source_plan_version bigint NOT NULL,
    replacement_plan_version bigint NOT NULL,
    source_plan_warehouse_id uuid NOT NULL,
    source_plan_date date NOT NULL,
    removed_document_id uuid NOT NULL,
    removed_external_task_id uuid NOT NULL,
    request_sha256 varchar(64) NOT NULL,
    request_json jsonb NOT NULL,
    state varchar(24) NOT NULL,
    task_board_hold_id uuid,
    task_board_commit_json jsonb,
    response_json jsonb,
    attempt_count integer NOT NULL DEFAULT 0,
    next_attempt_at timestamptz NOT NULL,
    last_error_code varchar(96),
    last_error_message varchar(1000),
    created_at timestamptz NOT NULL,
    updated_at timestamptz NOT NULL,
    CONSTRAINT planning_published_reschedule_saga_pkey PRIMARY KEY (id),
    CONSTRAINT uk_planning_published_reschedule_revision
        UNIQUE (source_plan_id, replacement_plan_version),
    CONSTRAINT ck_planning_published_reschedule_versions CHECK (
        expected_source_plan_version >= 1
        AND replacement_plan_version > expected_source_plan_version
    ),
    CONSTRAINT ck_planning_published_reschedule_sha256 CHECK (
        request_sha256 ~ '^[0-9a-f]{64}$'
    ),
    CONSTRAINT ck_planning_published_reschedule_state CHECK (
        state IN (
            'PENDING',
            'PREPARED',
            'OWNER_COMMITTED',
            'BOARD_COMMITTED',
            'COMPLETE',
            'RELEASE_PENDING',
            'RELEASED',
            'QUARANTINED'
        )
    )
);

CREATE INDEX idx_planning_published_reschedule_recovery
    ON public.planning_published_reschedule_saga (state, next_attempt_at, id)
    WHERE state IN (
        'PENDING', 'PREPARED', 'OWNER_COMMITTED', 'BOARD_COMMITTED', 'RELEASE_PENDING'
    );

CREATE INDEX idx_planning_published_reschedule_order
    ON public.planning_published_reschedule_saga (order_id, created_at, id);
