-- A terminal warehouse outbox event can be returned to the bounded relay only
-- through a reviewed, version-fenced command.  The event envelope itself
-- remains protected by the original immutable-envelope trigger.

ALTER TABLE public.outbox_event
  ADD COLUMN review_version bigint NOT NULL DEFAULT 0,
  ADD COLUMN last_reviewed_by_subject_id uuid,
  ADD COLUMN last_recovery_reason varchar(2000),
  ADD COLUMN last_recovered_at timestamptz;

ALTER TABLE public.outbox_event
  ADD CONSTRAINT ck_outbox_event_review_version
    CHECK (review_version >= 0),
  ADD CONSTRAINT ck_outbox_event_review_metadata
    CHECK (
      (review_version = 0
        AND last_reviewed_by_subject_id IS NULL
        AND last_recovery_reason IS NULL
        AND last_recovered_at IS NULL)
      OR
      (review_version > 0
        AND last_reviewed_by_subject_id IS NOT NULL
        AND last_recovery_reason IS NOT NULL
        AND char_length(btrim(last_recovery_reason)) BETWEEN 1 AND 2000
        AND last_recovered_at IS NOT NULL)
    );

CREATE TABLE public.warehouse_outbox_recovery_audit (
  id uuid NOT NULL,
  event_id uuid NOT NULL,
  aggregate_type varchar(64) NOT NULL,
  aggregate_id varchar(128) NOT NULL,
  aggregate_version bigint NOT NULL,
  review_version bigint NOT NULL,
  prior_status varchar(24) NOT NULL,
  prior_attempt_count integer NOT NULL,
  prior_last_error_code varchar(64),
  envelope_sha256 char(64) NOT NULL,
  reviewed_by_subject_id uuid NOT NULL,
  reason varchar(2000) NOT NULL,
  reviewed_at timestamptz NOT NULL,
  CONSTRAINT warehouse_outbox_recovery_audit_pkey PRIMARY KEY (id),
  CONSTRAINT uk_warehouse_outbox_recovery_audit_event_review
    UNIQUE (event_id, review_version),
  CONSTRAINT ck_warehouse_outbox_recovery_audit_identity CHECK (
    aggregate_type = 'WAREHOUSE'
    AND aggregate_id ~ '^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$'
    AND aggregate_version >= 0
  ),
  CONSTRAINT ck_warehouse_outbox_recovery_audit_review CHECK (
    review_version > 0
    AND prior_status IN ('DLT', 'QUARANTINED')
    AND prior_attempt_count >= 0
    AND envelope_sha256 ~ '^[0-9a-f]{64}$'
    AND char_length(btrim(reason)) BETWEEN 1 AND 2000
  )
);

CREATE INDEX idx_warehouse_outbox_recovery_audit_event
  ON public.warehouse_outbox_recovery_audit(event_id, review_version);

CREATE FUNCTION public.prevent_warehouse_outbox_recovery_audit_mutation()
RETURNS trigger
LANGUAGE plpgsql
AS $$
BEGIN
  RAISE EXCEPTION 'warehouse outbox recovery audit is append-only';
END;
$$;

CREATE TRIGGER trg_warehouse_outbox_recovery_audit_append_only
BEFORE UPDATE OR DELETE ON public.warehouse_outbox_recovery_audit
FOR EACH ROW
EXECUTE FUNCTION public.prevent_warehouse_outbox_recovery_audit_mutation();
