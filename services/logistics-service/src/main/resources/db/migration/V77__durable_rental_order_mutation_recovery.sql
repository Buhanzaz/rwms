CREATE TABLE public.rental_order_mutation_command (
    id uuid NOT NULL,
    version bigint NOT NULL DEFAULT 0,
    order_id uuid NOT NULL,
    operation varchar(32) NOT NULL,
    state varchar(24) NOT NULL,
    step varchar(32) NOT NULL,
    target_unit_id uuid,
    expected_order_version bigint NOT NULL,
    actor_subject_id uuid NOT NULL,
    actor_role varchar(32) NOT NULL,
    idempotency_key uuid NOT NULL,
    request_sha256 char(64) NOT NULL,
    warehouse_id uuid,
    release_units_idempotency_key uuid NOT NULL,
    release_equipment_idempotency_key uuid NOT NULL,
    equipment_release_required boolean NOT NULL,
    intent_json text NOT NULL,
    released_units_receipt_json text,
    equipment_receipt_json text,
    attempt_count integer NOT NULL DEFAULT 0,
    next_attempt_at timestamptz,
    lease_token uuid,
    lease_until timestamptz,
    last_error_code varchar(64),
    quarantined_at timestamptz,
    completed_at timestamptz,
    created_at timestamptz NOT NULL,
    updated_at timestamptz NOT NULL,
    CONSTRAINT rental_order_mutation_command_pkey PRIMARY KEY (id),
    CONSTRAINT fk_rental_order_mutation_command_order
        FOREIGN KEY (order_id) REFERENCES public.rental_order(id),
    CONSTRAINT uk_rental_order_mutation_command_actor_key UNIQUE (
        actor_subject_id, operation, idempotency_key
    ),
    CONSTRAINT uk_rental_order_mutation_command_release_units_key UNIQUE (
        release_units_idempotency_key
    ),
    CONSTRAINT uk_rental_order_mutation_command_release_equipment_key UNIQUE (
        release_equipment_idempotency_key
    ),
    CONSTRAINT ck_rental_order_mutation_command_version CHECK (version >= 0),
    CONSTRAINT ck_rental_order_mutation_command_operation CHECK (
        operation IN ('CANCEL_ORDER', 'REMOVE_UNIT')
    ),
    CONSTRAINT ck_rental_order_mutation_command_state CHECK (
        state IN ('PENDING', 'COMPLETED', 'QUARANTINED')
    ),
    CONSTRAINT ck_rental_order_mutation_command_step CHECK (
        step IN ('RELEASE_UNITS', 'RELEASE_EQUIPMENT', 'FINALIZE_LOCAL', 'COMPLETED')
    ),
    CONSTRAINT ck_rental_order_mutation_command_target CHECK (
        (operation = 'CANCEL_ORDER' AND target_unit_id IS NULL)
        OR
        (
            operation = 'REMOVE_UNIT'
            AND target_unit_id IS NOT NULL
            AND warehouse_id IS NOT NULL
        )
    ),
    CONSTRAINT ck_rental_order_mutation_command_equipment CHECK (
        equipment_release_required = (warehouse_id IS NOT NULL)
    ),
    CONSTRAINT ck_rental_order_mutation_command_versions CHECK (
        expected_order_version >= 0 AND attempt_count >= 0
    ),
    CONSTRAINT ck_rental_order_mutation_command_hash CHECK (
        request_sha256 ~ '^[0-9a-f]{64}$'
    ),
    CONSTRAINT ck_rental_order_mutation_command_step_keys CHECK (
        release_units_idempotency_key <> release_equipment_idempotency_key
    ),
    CONSTRAINT ck_rental_order_mutation_command_intent CHECK (
        length(btrim(intent_json)) > 0
        AND (
            released_units_receipt_json IS NULL
            OR length(btrim(released_units_receipt_json)) > 0
        )
        AND (
            equipment_receipt_json IS NULL
            OR length(btrim(equipment_receipt_json)) > 0
        )
    ),
    CONSTRAINT ck_rental_order_mutation_command_lease CHECK (
        (lease_token IS NULL) = (lease_until IS NULL)
    ),
    CONSTRAINT ck_rental_order_mutation_command_lifecycle CHECK (
        (
            state = 'PENDING'
            AND step <> 'COMPLETED'
            AND next_attempt_at IS NOT NULL
            AND quarantined_at IS NULL
            AND completed_at IS NULL
        )
        OR
        (
            state = 'QUARANTINED'
            AND step <> 'COMPLETED'
            AND next_attempt_at IS NULL
            AND lease_token IS NULL
            AND lease_until IS NULL
            AND quarantined_at IS NOT NULL
            AND completed_at IS NULL
        )
        OR
        (
            state = 'COMPLETED'
            AND step = 'COMPLETED'
            AND next_attempt_at IS NULL
            AND lease_token IS NULL
            AND lease_until IS NULL
            AND quarantined_at IS NULL
            AND completed_at IS NOT NULL
        )
    ),
    CONSTRAINT ck_rental_order_mutation_command_receipts CHECK (
        (
            step = 'RELEASE_UNITS'
            AND released_units_receipt_json IS NULL
            AND equipment_receipt_json IS NULL
        )
        OR
        (
            step = 'RELEASE_EQUIPMENT'
            AND equipment_release_required
            AND released_units_receipt_json IS NOT NULL
            AND equipment_receipt_json IS NULL
        )
        OR
        (
            step IN ('FINALIZE_LOCAL', 'COMPLETED')
            AND released_units_receipt_json IS NOT NULL
            AND (
                (
                    equipment_release_required
                    AND equipment_receipt_json IS NOT NULL
                )
                OR
                (
                    NOT equipment_release_required
                    AND equipment_receipt_json IS NULL
                )
            )
        )
    )
);

CREATE UNIQUE INDEX uk_rental_order_mutation_command_open_order
    ON public.rental_order_mutation_command (order_id)
    WHERE state IN ('PENDING', 'QUARANTINED');

CREATE INDEX idx_rental_order_mutation_command_due
    ON public.rental_order_mutation_command (next_attempt_at, created_at, id)
    WHERE state = 'PENDING';
