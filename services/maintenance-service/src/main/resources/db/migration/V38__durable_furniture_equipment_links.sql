-- A furniture catalog node is the permanent external reference used by asset-service.
-- Maintenance commits this intent before the first ensure call and retains the confirmed
-- mapping independently of short-lived HTTP/idempotency replay windows.

CREATE TABLE public.furniture_equipment_link_intent (
  node_id uuid NOT NULL,
  warehouse_id uuid NOT NULL,
  source_catalog_version_id uuid NOT NULL,
  source_catalog_expected_version bigint NOT NULL,
  requested_name varchar(255) NOT NULL,
  state varchar(24) NOT NULL,
  equipment_id uuid,
  equipment_name varchar(255),
  observed_equipment_id uuid,
  observed_equipment_name varchar(255),
  attempt_count integer NOT NULL DEFAULT 0,
  next_attempt_at timestamptz NOT NULL,
  claim_token uuid,
  claim_until timestamptz,
  last_error_code varchar(64),
  last_error_detail varchar(2000),
  review_version bigint NOT NULL DEFAULT 0,
  reviewed_by_subject_id uuid,
  review_action varchar(16),
  review_reason varchar(2000),
  reviewed_at timestamptz,
  confirmed_at timestamptz,
  abandoned_at timestamptz,
  created_at timestamptz NOT NULL,
  updated_at timestamptz NOT NULL,
  CONSTRAINT furniture_equipment_link_intent_pkey PRIMARY KEY (node_id),
  CONSTRAINT fk_furniture_link_source_catalog
    FOREIGN KEY (source_catalog_version_id)
    REFERENCES public.catalog_version(id)
    ON DELETE RESTRICT,
  CONSTRAINT ck_furniture_link_versions CHECK (
    source_catalog_expected_version >= 0
    AND attempt_count >= 0
    AND review_version >= 0),
  CONSTRAINT ck_furniture_link_state CHECK (
    state IN (
      'PENDING', 'IN_FLIGHT', 'RETRY_PENDING', 'CONFIRMED',
      'REVIEW_REQUIRED', 'ABANDONED')),
  CONSTRAINT ck_furniture_link_requested_name CHECK (btrim(requested_name) <> ''),
  CONSTRAINT ck_furniture_link_claim CHECK (
    (state = 'IN_FLIGHT' AND claim_token IS NOT NULL AND claim_until IS NOT NULL)
    OR (state <> 'IN_FLIGHT' AND claim_token IS NULL AND claim_until IS NULL)),
  CONSTRAINT ck_furniture_link_confirmation CHECK (
    (state = 'CONFIRMED'
      AND equipment_id IS NOT NULL
      AND equipment_name IS NOT NULL
      AND btrim(equipment_name) <> ''
      AND confirmed_at IS NOT NULL)
    OR (state <> 'CONFIRMED'
      AND equipment_id IS NULL
      AND equipment_name IS NULL
      AND confirmed_at IS NULL)),
  CONSTRAINT ck_furniture_link_observed CHECK (
    (observed_equipment_id IS NULL) = (observed_equipment_name IS NULL)
    AND (observed_equipment_name IS NULL OR btrim(observed_equipment_name) <> '')),
  CONSTRAINT ck_furniture_link_review CHECK (
    (review_version = 0
      AND reviewed_by_subject_id IS NULL
      AND review_action IS NULL
      AND review_reason IS NULL
      AND reviewed_at IS NULL)
    OR (review_version > 0
      AND reviewed_by_subject_id IS NOT NULL
      AND review_action IN ('RETRY', 'ABANDON')
      AND review_reason IS NOT NULL
      AND btrim(review_reason) <> ''
      AND reviewed_at IS NOT NULL)),
  CONSTRAINT ck_furniture_link_abandoned CHECK (
    (state = 'ABANDONED' AND abandoned_at IS NOT NULL)
    OR (state <> 'ABANDONED' AND abandoned_at IS NULL))
);

CREATE INDEX idx_furniture_equipment_link_due
  ON public.furniture_equipment_link_intent(
    state, next_attempt_at, claim_until, warehouse_id, node_id)
  WHERE state IN ('PENDING', 'IN_FLIGHT', 'RETRY_PENDING');

CREATE INDEX idx_furniture_equipment_link_review
  ON public.furniture_equipment_link_intent(
    warehouse_id, state, updated_at DESC, node_id);

CREATE TABLE public.furniture_equipment_link_review_audit (
  id uuid NOT NULL,
  node_id uuid NOT NULL,
  review_version bigint NOT NULL,
  action varchar(16) NOT NULL,
  previous_state varchar(24) NOT NULL,
  reviewed_by_subject_id uuid NOT NULL,
  reason varchar(2000) NOT NULL,
  reviewed_at timestamptz NOT NULL,
  CONSTRAINT furniture_equipment_link_review_audit_pkey PRIMARY KEY (id),
  CONSTRAINT fk_furniture_link_review_intent
    FOREIGN KEY (node_id)
    REFERENCES public.furniture_equipment_link_intent(node_id)
    ON DELETE RESTRICT,
  CONSTRAINT uq_furniture_link_review_version UNIQUE (node_id, review_version),
  CONSTRAINT ck_furniture_link_review_version CHECK (review_version > 0),
  CONSTRAINT ck_furniture_link_review_action CHECK (action IN ('RETRY', 'ABANDON')),
  CONSTRAINT ck_furniture_link_review_previous_state CHECK (
    previous_state IN ('PENDING', 'RETRY_PENDING', 'REVIEW_REQUIRED')),
  CONSTRAINT ck_furniture_link_review_reason CHECK (btrim(reason) <> '')
);

CREATE FUNCTION public.reject_furniture_link_review_audit_mutation()
RETURNS trigger
LANGUAGE plpgsql
AS $$
BEGIN
  RAISE EXCEPTION 'furniture equipment link review audit rows are immutable';
END;
$$;

CREATE TRIGGER furniture_equipment_link_review_audit_immutable
BEFORE UPDATE OR DELETE ON public.furniture_equipment_link_review_audit
FOR EACH ROW
EXECUTE FUNCTION public.reject_furniture_link_review_audit_mutation();
