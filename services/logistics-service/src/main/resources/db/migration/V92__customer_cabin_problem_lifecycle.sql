ALTER TABLE public.customer_cabin_problem
    ADD COLUMN lifecycle_status varchar(32) NOT NULL DEFAULT 'OPEN',
    ADD COLUMN resolution_deadline timestamptz,
    ADD COLUMN version bigint NOT NULL DEFAULT 0,
    ADD COLUMN resolution_kind varchar(32),
    ADD COLUMN resolved_by_subject_id uuid,
    ADD COLUMN resolution_comment varchar(2000),
    ADD COLUMN resolved_at timestamptz;

UPDATE public.customer_cabin_problem
SET resolution_deadline = reported_at + interval '3 days'
WHERE resolution_deadline IS NULL;

ALTER TABLE public.customer_cabin_problem
    ALTER COLUMN resolution_deadline SET NOT NULL,
    ADD CONSTRAINT uk_customer_cabin_problem_company_id UNIQUE (company_id, id),
    ADD CONSTRAINT ck_customer_cabin_problem_version CHECK (version >= 0),
    ADD CONSTRAINT ck_customer_cabin_problem_lifecycle_status CHECK (
        lifecycle_status IN ('OPEN', 'IN_PROGRESS', 'RESOLVED')
    ),
    ADD CONSTRAINT ck_customer_cabin_problem_resolution_deadline CHECK (
        resolution_deadline = reported_at + interval '3 days'
    ),
    ADD CONSTRAINT ck_customer_cabin_problem_resolution_comment CHECK (
        resolution_comment IS NULL
        OR length(btrim(resolution_comment)) BETWEEN 1 AND 2000
    ),
    ADD CONSTRAINT ck_customer_cabin_problem_resolution CHECK (
        (lifecycle_status IN ('OPEN', 'IN_PROGRESS')
            AND resolution_kind IS NULL
            AND resolved_by_subject_id IS NULL
            AND resolution_comment IS NULL
            AND resolved_at IS NULL)
        OR
        (lifecycle_status = 'RESOLVED'
            AND resolution_kind IN ('DISCOUNT', 'REPLACEMENT', 'RETURN')
            AND resolved_by_subject_id IS NOT NULL
            AND resolution_comment IS NOT NULL
            AND resolved_at IS NOT NULL)
    );

CREATE INDEX idx_customer_cabin_problem_company_order
    ON public.customer_cabin_problem (company_id, order_id, reported_at, id);

CREATE INDEX idx_customer_cabin_problem_company_deadline
    ON public.customer_cabin_problem (company_id, resolution_deadline, id);

CREATE TABLE public.customer_cabin_problem_action (
    id uuid NOT NULL,
    company_id uuid NOT NULL,
    problem_id uuid NOT NULL,
    action_kind varchar(32) NOT NULL,
    previous_status varchar(32) NOT NULL,
    lifecycle_status varchar(32) NOT NULL,
    resolution_kind varchar(32),
    actor_subject_id uuid NOT NULL,
    comment_text varchar(2000),
    occurred_at timestamptz NOT NULL,
    CONSTRAINT customer_cabin_problem_action_pkey PRIMARY KEY (id),
    CONSTRAINT fk_customer_cabin_problem_action_problem
        FOREIGN KEY (company_id, problem_id)
        REFERENCES public.customer_cabin_problem (company_id, id),
    CONSTRAINT ck_customer_cabin_problem_action_lifecycle CHECK (
        (action_kind = 'STATUS_TRANSITION'
            AND previous_status = 'OPEN'
            AND lifecycle_status = 'IN_PROGRESS'
            AND resolution_kind IS NULL
            AND comment_text IS NULL)
        OR
        (action_kind = 'RESOLUTION_DECISION'
            AND previous_status = 'IN_PROGRESS'
            AND lifecycle_status = 'RESOLVED'
            AND resolution_kind IN ('DISCOUNT', 'REPLACEMENT', 'RETURN')
            AND length(btrim(comment_text)) BETWEEN 1 AND 2000)
    )
);

CREATE INDEX idx_customer_cabin_problem_action_problem_time
    ON public.customer_cabin_problem_action
        (company_id, problem_id, occurred_at DESC, id DESC);

CREATE FUNCTION public.prevent_customer_cabin_problem_action_mutation()
RETURNS trigger
LANGUAGE plpgsql
AS $$
BEGIN
    RAISE EXCEPTION 'customer cabin problem actions are append-only';
END;
$$;

CREATE TRIGGER trg_customer_cabin_problem_action_immutable
BEFORE UPDATE OR DELETE ON public.customer_cabin_problem_action
FOR EACH ROW EXECUTE FUNCTION public.prevent_customer_cabin_problem_action_mutation();
