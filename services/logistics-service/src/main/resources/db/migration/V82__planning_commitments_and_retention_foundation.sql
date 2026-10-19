ALTER TABLE public.driver_logistics_task
    ADD COLUMN provisional_eta timestamptz,
    ADD COLUMN provisional_eta_source_plan_id uuid,
    ADD COLUMN provisional_eta_source_plan_version bigint,
    ADD CONSTRAINT ck_driver_logistics_task_provisional_eta CHECK (
        (provisional_eta IS NULL
            AND provisional_eta_source_plan_id IS NULL
            AND provisional_eta_source_plan_version IS NULL)
        OR
        (provisional_eta IS NOT NULL
            AND provisional_eta_source_plan_id IS NOT NULL
            AND provisional_eta_source_plan_version >= 1)
    );

ALTER TABLE public.customer_booking_mutation
    ADD COLUMN decision_code varchar(64),
    ADD COLUMN decision_actor_subject_id uuid,
    ADD COLUMN decision_reason varchar(2000);

UPDATE public.customer_booking_mutation
SET decision_code = 'CUSTOMER_SELECTED_SLOT',
    decision_actor_subject_id = customer_subject_id
WHERE operation = 'RESCHEDULE';

ALTER TABLE public.customer_booking_mutation
    ADD CONSTRAINT ck_customer_booking_mutation_decision CHECK (
        (operation = 'CANCEL'
            AND decision_code IS NULL
            AND decision_actor_subject_id IS NULL
            AND decision_reason IS NULL)
        OR
        (operation = 'RESCHEDULE'
            AND decision_code ~ '^[A-Z][A-Z0-9_]{0,63}$'
            AND decision_actor_subject_id IS NOT NULL)
    );

CREATE TABLE public.logistics_retention_legal_hold (
    id uuid NOT NULL,
    version bigint NOT NULL DEFAULT 0,
    dataset varchar(32) NOT NULL,
    scope_key varchar(256),
    reason varchar(2000) NOT NULL,
    placed_by_subject_id uuid NOT NULL,
    placed_at timestamptz NOT NULL,
    released_by_subject_id uuid,
    released_at timestamptz,
    release_reason varchar(2000),
    CONSTRAINT logistics_retention_legal_hold_pkey PRIMARY KEY (id),
    CONSTRAINT ck_logistics_retention_legal_hold_version CHECK (version >= 0),
    CONSTRAINT ck_logistics_retention_legal_hold_dataset CHECK (
        dataset IN ('BUSINESS_AUDIT_PROOF', 'EVENT_OUTBOX', 'EVENT_INBOX', 'GPS_TELEMETRY')
    ),
    CONSTRAINT ck_logistics_retention_legal_hold_release CHECK (
        (released_at IS NULL
            AND released_by_subject_id IS NULL
            AND release_reason IS NULL)
        OR
        (released_at IS NOT NULL
            AND released_by_subject_id IS NOT NULL
            AND release_reason IS NOT NULL)
    )
);

CREATE INDEX idx_logistics_retention_legal_hold_active
    ON public.logistics_retention_legal_hold (dataset, scope_key, placed_at, id)
    WHERE released_at IS NULL;

CREATE TABLE public.logistics_archive_manifest (
    id uuid NOT NULL,
    version bigint NOT NULL DEFAULT 0,
    dataset varchar(32) NOT NULL,
    period_start timestamptz NOT NULL,
    period_end timestamptz NOT NULL,
    object_key varchar(1000) NOT NULL,
    sha256 varchar(64) NOT NULL,
    row_count bigint NOT NULL,
    state varchar(16) NOT NULL,
    created_by_subject_id uuid NOT NULL,
    created_at timestamptz NOT NULL,
    verified_by_subject_id uuid,
    verified_at timestamptz,
    CONSTRAINT logistics_archive_manifest_pkey PRIMARY KEY (id),
    CONSTRAINT uk_logistics_archive_manifest_object UNIQUE (object_key),
    CONSTRAINT ck_logistics_archive_manifest_version CHECK (version >= 0),
    CONSTRAINT ck_logistics_archive_manifest_dataset CHECK (
        dataset IN ('BUSINESS_AUDIT_PROOF', 'EVENT_OUTBOX', 'EVENT_INBOX', 'GPS_TELEMETRY')
    ),
    CONSTRAINT ck_logistics_archive_manifest_period CHECK (period_start < period_end),
    CONSTRAINT ck_logistics_archive_manifest_sha256 CHECK (sha256 ~ '^[0-9a-f]{64}$'),
    CONSTRAINT ck_logistics_archive_manifest_rows CHECK (row_count >= 0),
    CONSTRAINT ck_logistics_archive_manifest_state CHECK (state IN ('RECORDED', 'VERIFIED')),
    CONSTRAINT ck_logistics_archive_manifest_verification CHECK (
        (state = 'RECORDED' AND verified_by_subject_id IS NULL AND verified_at IS NULL)
        OR
        (state = 'VERIFIED' AND verified_by_subject_id IS NOT NULL AND verified_at IS NOT NULL)
    )
);

CREATE INDEX idx_logistics_archive_manifest_period
    ON public.logistics_archive_manifest (dataset, period_end, period_start, id);
