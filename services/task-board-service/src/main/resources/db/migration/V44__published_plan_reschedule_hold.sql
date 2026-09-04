ALTER TABLE public.task_sync_source
    ADD COLUMN planner_membership_state varchar(16) NOT NULL DEFAULT 'ACTIVE',
    ADD COLUMN removed_at timestamptz,
    ADD COLUMN removed_source_plan_version bigint,
    ADD CONSTRAINT ck_task_sync_source_planner_membership CHECK (
        (planner_membership_state = 'ACTIVE'
            AND removed_at IS NULL
            AND removed_source_plan_version IS NULL)
        OR
        (planner_membership_state = 'REMOVED'
            AND source_plan_id IS NOT NULL
            AND removed_at IS NOT NULL
            AND removed_source_plan_version >= source_plan_version)
    );

ALTER TABLE public.driver_shift_plan
    ADD COLUMN withdrawn_at timestamptz,
    ADD COLUMN withdrawn_source_plan_version bigint,
    ADD COLUMN active_driver_key uuid;

UPDATE public.driver_shift_plan
SET active_driver_key = driver_id;

ALTER TABLE public.driver_shift_plan
    ADD CONSTRAINT ck_driver_shift_plan_withdrawal CHECK (
        (withdrawn_at IS NULL
            AND withdrawn_source_plan_version IS NULL
            AND active_driver_key = driver_id)
        OR
        (withdrawn_at IS NOT NULL
            AND withdrawn_source_plan_version IS NOT NULL
            AND active_driver_key IS NULL
            AND withdrawn_source_plan_version >= source_plan_version)
    );

ALTER TABLE public.driver_shift_plan
    DROP CONSTRAINT uk_driver_shift_plan_driver_date,
    ADD CONSTRAINT uk_driver_shift_plan_driver_date
        UNIQUE (active_driver_key, work_date) DEFERRABLE INITIALLY DEFERRED;

CREATE TABLE public.planning_replan_hold (
    id uuid NOT NULL,
    idempotency_key uuid NOT NULL,
    source_plan_id uuid NOT NULL,
    expected_source_plan_version bigint NOT NULL,
    replacement_plan_version bigint NOT NULL,
    source_plan_warehouse_id uuid NOT NULL,
    source_plan_date date NOT NULL,
    removed_external_task_id uuid NOT NULL,
    request_sha256 varchar(64) NOT NULL,
    request_json jsonb NOT NULL,
    state varchar(16) NOT NULL,
    commit_response_json jsonb,
    created_at timestamptz NOT NULL,
    updated_at timestamptz NOT NULL,
    CONSTRAINT planning_replan_hold_pkey PRIMARY KEY (id),
    CONSTRAINT uk_planning_replan_hold_idempotency UNIQUE (idempotency_key),
    CONSTRAINT uk_planning_replan_hold_revision
        UNIQUE (source_plan_id, replacement_plan_version),
    CONSTRAINT ck_planning_replan_hold_versions CHECK (
        expected_source_plan_version >= 1
        AND replacement_plan_version > expected_source_plan_version
    ),
    CONSTRAINT ck_planning_replan_hold_sha256 CHECK (
        request_sha256 ~ '^[0-9a-f]{64}$'
    ),
    CONSTRAINT ck_planning_replan_hold_state CHECK (
        state IN ('PREPARED', 'COMMITTED', 'RELEASED')
    )
);

CREATE INDEX idx_planning_replan_hold_active_source
    ON public.planning_replan_hold (source_plan_id, state)
    WHERE state = 'PREPARED';
