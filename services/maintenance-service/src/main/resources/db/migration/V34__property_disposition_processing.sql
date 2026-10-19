-- Mutable claims coordinate workers; every attempt remains immutable operational evidence.
-- The decision aggregate itself remains the source of business state and event history.

CREATE TABLE public.property_disposition_processing_claim (
  decision_id uuid NOT NULL,
  claim_token uuid,
  lease_owner varchar(128),
  lease_until timestamptz,
  status varchar(24) NOT NULL,
  attempt_count integer NOT NULL DEFAULT 0,
  next_attempt_at timestamptz NOT NULL DEFAULT clock_timestamp(),
  last_error_code varchar(128),
  last_error_detail varchar(2000),
  updated_at timestamptz NOT NULL DEFAULT clock_timestamp(),
  CONSTRAINT property_disposition_processing_claim_pkey PRIMARY KEY (decision_id),
  CONSTRAINT fk_property_disposition_processing_claim_decision
    FOREIGN KEY (decision_id)
    REFERENCES public.property_disposition_decision(id)
    ON DELETE RESTRICT,
  CONSTRAINT ck_property_disposition_processing_claim_status CHECK (
    status IN ('PENDING', 'IN_FLIGHT', 'COMPLETED', 'QUARANTINED')),
  CONSTRAINT ck_property_disposition_processing_claim_attempt_count CHECK (attempt_count >= 0),
  CONSTRAINT ck_property_disposition_processing_claim_lease CHECK (
    (status = 'IN_FLIGHT'
      AND claim_token IS NOT NULL
      AND lease_owner IS NOT NULL
      AND btrim(lease_owner) <> ''
      AND lease_until IS NOT NULL)
    OR (status <> 'IN_FLIGHT'
      AND claim_token IS NULL
      AND lease_owner IS NULL
      AND lease_until IS NULL)),
  CONSTRAINT ck_property_disposition_processing_claim_error CHECK (
    (last_error_code IS NULL OR btrim(last_error_code) <> '')
    AND (last_error_detail IS NULL OR btrim(last_error_detail) <> ''))
);

CREATE INDEX idx_property_disposition_processing_claim_ready
  ON public.property_disposition_processing_claim(status, next_attempt_at, decision_id)
  WHERE status = 'PENDING';

CREATE TABLE public.property_disposition_processing_attempt (
  id uuid NOT NULL,
  decision_id uuid NOT NULL,
  claim_token uuid NOT NULL,
  phase varchar(64) NOT NULL,
  outcome varchar(32) NOT NULL,
  failure_code varchar(128),
  failure_detail varchar(2000),
  created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
  CONSTRAINT property_disposition_processing_attempt_pkey PRIMARY KEY (id),
  CONSTRAINT fk_property_disposition_processing_attempt_decision
    FOREIGN KEY (decision_id)
    REFERENCES public.property_disposition_decision(id)
    ON DELETE RESTRICT,
  CONSTRAINT ck_property_disposition_processing_attempt_phase CHECK (btrim(phase) <> ''),
  CONSTRAINT ck_property_disposition_processing_attempt_outcome CHECK (
    outcome IN ('CLAIMED', 'SUCCEEDED', 'WAITING', 'RETRYABLE_FAILURE', 'QUARANTINED', 'COMPLETED')),
  CONSTRAINT ck_property_disposition_processing_attempt_failure CHECK (
    (failure_code IS NULL OR btrim(failure_code) <> '')
    AND (failure_detail IS NULL OR btrim(failure_detail) <> ''))
);

CREATE INDEX idx_property_disposition_processing_attempt_decision
  ON public.property_disposition_processing_attempt(decision_id, created_at, id);
