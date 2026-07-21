CREATE TABLE public.equipment_movement_task (
    id uuid NOT NULL,
    version bigint NOT NULL DEFAULT 0,
    warehouse_id uuid NOT NULL,
    external_task_id uuid NOT NULL,
    task_board_task_id uuid,
    task_board_task_version bigint,
    task_board_done_at timestamptz,
    task_board_cancelled boolean NOT NULL DEFAULT false,
    unit_number varchar(64),
    planned_duration_minutes integer,
    deadline_at timestamptz NOT NULL,
    state varchar(32) NOT NULL,
    terminal_state varchar(32),
    failure_code varchar(96),
    created_by_subject_id uuid NOT NULL,
    idempotency_key uuid NOT NULL,
    request_sha256 char(64) NOT NULL,
    cancellation_requested_by_subject_id uuid,
    cancellation_idempotency_key uuid,
    cancellation_request_sha256 char(64),
    retry_count integer NOT NULL DEFAULT 0,
    next_attempt_at timestamptz,
    created_at timestamptz NOT NULL,
    updated_at timestamptz NOT NULL,
    CONSTRAINT equipment_movement_task_pkey PRIMARY KEY (id),
    CONSTRAINT uk_equipment_movement_task_creator_key
        UNIQUE (created_by_subject_id, idempotency_key),
    CONSTRAINT uk_equipment_movement_task_cancellation_key
        UNIQUE (cancellation_requested_by_subject_id, cancellation_idempotency_key),
    CONSTRAINT uk_equipment_movement_task_external UNIQUE (external_task_id),
    CONSTRAINT ck_equipment_movement_task_version CHECK (version >= 0),
    CONSTRAINT ck_equipment_movement_task_board_version CHECK (
        task_board_task_version IS NULL OR task_board_task_version >= 0
    ),
    CONSTRAINT ck_equipment_movement_task_board_identity CHECK (
        (task_board_task_id IS NULL AND task_board_task_version IS NULL AND task_board_done_at IS NULL
            AND task_board_cancelled = false)
        OR (task_board_task_id IS NOT NULL AND task_board_task_version IS NOT NULL)
    ),
    CONSTRAINT ck_equipment_movement_task_unit_number CHECK (
        unit_number IS NULL OR length(btrim(unit_number)) BETWEEN 1 AND 64
    ),
    CONSTRAINT ck_equipment_movement_task_duration CHECK (
        planned_duration_minutes IS NULL OR planned_duration_minutes >= 1
    ),
    CONSTRAINT ck_equipment_movement_task_deadline CHECK (deadline_at > created_at),
    CONSTRAINT ck_equipment_movement_task_state CHECK (
        state IN (
            'RESERVING', 'REGISTERING_TASK', 'AWAITING_WORKER', 'EXECUTING', 'CANCELLING',
            'COMPLETED', 'CANCELLED', 'EXPIRED', 'CONFLICT', 'RECONCILIATION_REQUIRED'
        )
    ),
    CONSTRAINT ck_equipment_movement_task_terminal_state CHECK (
        terminal_state IS NULL OR terminal_state IN ('CANCELLED', 'EXPIRED', 'CONFLICT')
    ),
    CONSTRAINT ck_equipment_movement_task_failure_code CHECK (
        failure_code IS NULL OR length(btrim(failure_code)) BETWEEN 1 AND 96
    ),
    CONSTRAINT ck_equipment_movement_task_hash CHECK (
        request_sha256 ~ '^[0-9a-f]{64}$'
    ),
    CONSTRAINT ck_equipment_movement_task_cancellation_identity CHECK (
        (cancellation_requested_by_subject_id IS NULL
            AND cancellation_idempotency_key IS NULL
            AND cancellation_request_sha256 IS NULL)
        OR (cancellation_requested_by_subject_id IS NOT NULL
            AND cancellation_idempotency_key IS NOT NULL
            AND cancellation_request_sha256 ~ '^[0-9a-f]{64}$')
    ),
    CONSTRAINT ck_equipment_movement_task_retry_count CHECK (retry_count >= 0)
);

CREATE INDEX idx_equipment_movement_task_due
    ON public.equipment_movement_task (state, next_attempt_at, id)
    WHERE state IN ('RESERVING', 'REGISTERING_TASK', 'AWAITING_WORKER', 'EXECUTING', 'CANCELLING');

CREATE TABLE public.equipment_movement_task_line (
    id uuid NOT NULL,
    version bigint NOT NULL DEFAULT 0,
    task_id uuid NOT NULL,
    line_number integer NOT NULL,
    equipment_id uuid NOT NULL,
    source_warehouse_id uuid NOT NULL,
    source_rental_item_id uuid,
    source_location_kind varchar(32) NOT NULL,
    expected_source_balance_version bigint NOT NULL,
    target_warehouse_id uuid NOT NULL,
    target_rental_item_id uuid,
    target_location_kind varchar(32) NOT NULL,
    quantity bigint NOT NULL,
    reservation_id uuid,
    reservation_version bigint,
    equipment_code varchar(128),
    equipment_name varchar(512),
    state varchar(32) NOT NULL,
    created_at timestamptz NOT NULL,
    updated_at timestamptz NOT NULL,
    CONSTRAINT equipment_movement_task_line_pkey PRIMARY KEY (id),
    CONSTRAINT fk_equipment_movement_line_task FOREIGN KEY (task_id)
        REFERENCES public.equipment_movement_task(id),
    CONSTRAINT uk_equipment_movement_task_line_number UNIQUE (task_id, line_number),
    CONSTRAINT uk_equipment_movement_task_line_reservation UNIQUE (reservation_id),
    CONSTRAINT ck_equipment_movement_task_line_version CHECK (version >= 0),
    CONSTRAINT ck_equipment_movement_task_line_number CHECK (line_number >= 1),
    CONSTRAINT ck_equipment_movement_task_line_source_version CHECK (
        expected_source_balance_version >= 0
    ),
    CONSTRAINT ck_equipment_movement_task_line_quantity CHECK (quantity >= 1),
    CONSTRAINT ck_equipment_movement_task_line_reservation_version CHECK (
        reservation_version IS NULL OR reservation_version >= 0
    ),
    CONSTRAINT ck_equipment_movement_task_line_reservation_identity CHECK (
        (reservation_id IS NULL AND reservation_version IS NULL)
        OR (reservation_id IS NOT NULL AND reservation_version IS NOT NULL)
    ),
    CONSTRAINT ck_equipment_movement_task_line_equipment_code CHECK (
        equipment_code IS NULL OR length(btrim(equipment_code)) BETWEEN 1 AND 128
    ),
    CONSTRAINT ck_equipment_movement_task_line_equipment_name CHECK (
        equipment_name IS NULL OR length(btrim(equipment_name)) BETWEEN 1 AND 512
    ),
    CONSTRAINT ck_equipment_movement_task_line_source_location CHECK (
        (source_location_kind = 'STOCK' AND source_rental_item_id IS NULL)
        OR (source_location_kind IN ('CABIN_NON_RENTED', 'CABIN_RENTED') AND source_rental_item_id IS NOT NULL)
    ),
    CONSTRAINT ck_equipment_movement_task_line_target_location CHECK (
        (target_location_kind = 'STOCK' AND target_rental_item_id IS NULL)
        OR (target_location_kind IN ('CABIN_NON_RENTED', 'CABIN_RENTED') AND target_rental_item_id IS NOT NULL)
    ),
    CONSTRAINT ck_equipment_movement_task_line_state CHECK (
        state IN ('PENDING_RESERVATION', 'RESERVED', 'EXECUTED', 'RELEASED')
    )
);

CREATE INDEX idx_equipment_movement_task_line_task
    ON public.equipment_movement_task_line (task_id, line_number);
