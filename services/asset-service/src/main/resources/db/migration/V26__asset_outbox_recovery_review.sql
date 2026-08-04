ALTER TABLE public.outbox_event
  ADD COLUMN review_version bigint NOT NULL DEFAULT 0,
  ADD COLUMN reviewed_at timestamptz;

ALTER TABLE public.outbox_event
  ADD CONSTRAINT ck_asset_outbox_review_state CHECK (
    review_version >= 0
    AND (
      (review_version = 0 AND reviewed_at IS NULL)
      OR (review_version > 0 AND reviewed_at IS NOT NULL)
    )
  );

CREATE TABLE public.asset_outbox_recovery_review (
  event_id uuid NOT NULL,
  expected_review_version bigint NOT NULL,
  review_version bigint NOT NULL,
  prior_status varchar(24) NOT NULL,
  prior_last_error_code varchar(64),
  prior_attempt_count integer NOT NULL,
  reviewer_subject_id uuid NOT NULL,
  review_reason varchar(2000) NOT NULL,
  request_fingerprint char(64) NOT NULL,
  reviewed_at timestamptz NOT NULL,
  CONSTRAINT asset_outbox_recovery_review_pkey PRIMARY KEY (event_id, expected_review_version),
  CONSTRAINT uq_asset_outbox_recovery_review_event_version UNIQUE (event_id, review_version),
  CONSTRAINT fk_asset_outbox_recovery_review_event FOREIGN KEY (event_id)
    REFERENCES public.outbox_event(event_id),
  CONSTRAINT ck_asset_outbox_recovery_review_expected_version CHECK (expected_review_version >= 0),
  CONSTRAINT ck_asset_outbox_recovery_review_version CHECK (
    review_version = expected_review_version + 1
  ),
  CONSTRAINT ck_asset_outbox_recovery_review_prior_status CHECK (
    prior_status IN ('DLT', 'QUARANTINED')
  ),
  CONSTRAINT ck_asset_outbox_recovery_review_prior_attempt_count CHECK (prior_attempt_count >= 0),
  CONSTRAINT ck_asset_outbox_recovery_review_reason CHECK (
    length(btrim(review_reason)) BETWEEN 1 AND 2000
  ),
  CONSTRAINT ck_asset_outbox_recovery_review_fingerprint CHECK (
    request_fingerprint ~ '^[0-9a-f]{64}$'
  )
);

CREATE INDEX idx_asset_outbox_recovery_review_event_reviewed_at
  ON public.asset_outbox_recovery_review(event_id, reviewed_at DESC);

CREATE FUNCTION public.prevent_asset_outbox_recovery_review_truncate()
RETURNS trigger
LANGUAGE plpgsql
AS $$
BEGIN
  RAISE EXCEPTION 'asset outbox recovery review audit cannot be truncated';
END;
$$;

CREATE TRIGGER trg_asset_outbox_recovery_review_immutable
BEFORE UPDATE OR DELETE ON public.asset_outbox_recovery_review
FOR EACH ROW EXECUTE FUNCTION public.prevent_asset_append_only_mutation();

CREATE TRIGGER trg_asset_outbox_recovery_review_no_truncate
BEFORE TRUNCATE ON public.asset_outbox_recovery_review
FOR EACH STATEMENT EXECUTE FUNCTION public.prevent_asset_outbox_recovery_review_truncate();
