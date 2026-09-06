ALTER TABLE public.outbox_event
  ADD COLUMN terminal_phase varchar(16),
  ADD COLUMN terminal_reason varchar(64),
  ADD COLUMN review_version bigint NOT NULL DEFAULT 0,
  ADD COLUMN reviewed_at timestamptz;

UPDATE public.outbox_event
   SET terminal_phase = CASE WHEN status = 'DLT' THEN 'VALIDATION' ELSE 'PUBLISH' END,
       terminal_reason = coalesce(last_error_code, 'LEGACY_TERMINAL')
 WHERE status IN ('DLT', 'QUARANTINED');

ALTER TABLE public.outbox_event
  ADD CONSTRAINT ck_inventory_outbox_terminal CHECK (
    (status IN ('DLT', 'QUARANTINED')
      AND terminal_phase IS NOT NULL AND terminal_reason IS NOT NULL
      AND terminal_phase IN ('VALIDATION', 'PUBLISH', 'LEASE')
      AND length(btrim(terminal_reason)) BETWEEN 1 AND 64)
    OR (status NOT IN ('DLT', 'QUARANTINED')
      AND terminal_phase IS NULL AND terminal_reason IS NULL)),
  ADD CONSTRAINT ck_inventory_outbox_review CHECK (
    review_version >= 0
    AND ((review_version = 0 AND reviewed_at IS NULL)
      OR (review_version > 0 AND reviewed_at IS NOT NULL)));

ALTER TABLE public.sanitized_dead_letter
  ADD COLUMN terminal_phase varchar(16),
  ADD COLUMN terminal_reason varchar(64),
  ADD COLUMN review_version bigint NOT NULL DEFAULT 0,
  ADD COLUMN reviewed_at timestamptz;

UPDATE public.sanitized_dead_letter
   SET terminal_phase = 'VALIDATION',
       terminal_reason = coalesce(last_error_code, 'LEGACY_TERMINAL')
 WHERE status = 'FAILED';

ALTER TABLE public.sanitized_dead_letter
  ADD CONSTRAINT ck_inventory_dlt_terminal CHECK (
    (status = 'FAILED'
      AND terminal_phase IS NOT NULL AND terminal_reason IS NOT NULL
      AND terminal_phase IN ('VALIDATION', 'PUBLISH', 'LEASE')
      AND length(btrim(terminal_reason)) BETWEEN 1 AND 64)
    OR (status <> 'FAILED' AND terminal_phase IS NULL AND terminal_reason IS NULL)),
  ADD CONSTRAINT ck_inventory_dlt_review CHECK (
    review_version >= 0
    AND ((review_version = 0 AND reviewed_at IS NULL)
      OR (review_version > 0 AND reviewed_at IS NOT NULL)));

CREATE TABLE public.inventory_eventing_recovery_review (
  record_kind varchar(16) NOT NULL,
  record_id uuid NOT NULL,
  expected_review_version bigint NOT NULL,
  review_version bigint NOT NULL,
  prior_status varchar(24) NOT NULL,
  prior_last_error_code varchar(64),
  prior_terminal_phase varchar(16) NOT NULL,
  prior_terminal_reason varchar(64) NOT NULL,
  prior_attempt_count integer NOT NULL,
  reviewer_subject_id uuid NOT NULL,
  review_reason varchar(2000) NOT NULL,
  request_fingerprint char(64) NOT NULL,
  reviewed_at timestamptz NOT NULL,
  CONSTRAINT inventory_eventing_recovery_review_pkey
    PRIMARY KEY (record_kind, record_id, expected_review_version),
  CONSTRAINT uq_inventory_eventing_recovery_review_version
    UNIQUE (record_kind, record_id, review_version),
  CONSTRAINT ck_inventory_eventing_recovery_kind CHECK (record_kind IN ('OUTBOX', 'DLT')),
  CONSTRAINT ck_inventory_eventing_recovery_expected_version CHECK (expected_review_version >= 0),
  CONSTRAINT ck_inventory_eventing_recovery_version CHECK (
    review_version = expected_review_version + 1),
  CONSTRAINT ck_inventory_eventing_recovery_prior_status CHECK (
    (record_kind = 'OUTBOX' AND prior_status IN ('DLT', 'QUARANTINED'))
    OR (record_kind = 'DLT' AND prior_status = 'FAILED')),
  CONSTRAINT ck_inventory_eventing_recovery_prior_phase CHECK (
    prior_terminal_phase IN ('VALIDATION', 'PUBLISH', 'LEASE')),
  CONSTRAINT ck_inventory_eventing_recovery_prior_reason CHECK (
    length(btrim(prior_terminal_reason)) BETWEEN 1 AND 64),
  CONSTRAINT ck_inventory_eventing_recovery_prior_attempts CHECK (prior_attempt_count >= 0),
  CONSTRAINT ck_inventory_eventing_recovery_reason CHECK (
    length(btrim(review_reason)) BETWEEN 1 AND 2000),
  CONSTRAINT ck_inventory_eventing_recovery_fingerprint CHECK (
    request_fingerprint ~ '^[0-9a-f]{64}$')
);

CREATE FUNCTION public.reject_inventory_eventing_recovery_review_truncate()
RETURNS trigger
LANGUAGE plpgsql
AS $$
BEGIN
  RAISE EXCEPTION 'inventory eventing recovery review audit cannot be truncated';
END;
$$;

CREATE TRIGGER trg_inventory_eventing_recovery_review_immutable
BEFORE UPDATE OR DELETE ON public.inventory_eventing_recovery_review
FOR EACH ROW EXECUTE FUNCTION public.reject_inventory_append_only_mutation();

CREATE TRIGGER trg_inventory_eventing_recovery_review_no_truncate
BEFORE TRUNCATE ON public.inventory_eventing_recovery_review
FOR EACH STATEMENT EXECUTE FUNCTION public.reject_inventory_eventing_recovery_review_truncate();
