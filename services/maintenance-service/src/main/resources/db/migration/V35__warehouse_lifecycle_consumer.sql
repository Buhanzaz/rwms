-- Maintenance records warehouse operation boundaries only after its local transaction commits.
-- The outbox is retained as audit/recovery evidence; warehouse-service is the canonical marker.

CREATE TABLE public.warehouse_operation_mark_outbox (
  operation_id uuid NOT NULL,
  warehouse_id uuid NOT NULL,
  occurred_at timestamptz NOT NULL,
  state varchar(16) NOT NULL,
  attempt_count integer NOT NULL DEFAULT 0,
  next_attempt_at timestamptz NOT NULL,
  claim_token uuid,
  claim_until timestamptz,
  last_error_code varchar(64),
  recovery_version bigint NOT NULL DEFAULT 0,
  recovered_by_subject_id uuid,
  recovery_reason varchar(2000),
  recovered_at timestamptz,
  created_at timestamptz NOT NULL,
  updated_at timestamptz NOT NULL,
  CONSTRAINT warehouse_operation_mark_outbox_pkey PRIMARY KEY (warehouse_id, operation_id),
  CONSTRAINT ck_warehouse_operation_mark_outbox_state CHECK (
    state IN ('PENDING', 'IN_FLIGHT', 'RETRY_PENDING', 'CONFIRMED', 'QUARANTINED')
  ),
  CONSTRAINT ck_warehouse_operation_mark_outbox_attempts CHECK (
    attempt_count >= 0 AND recovery_version >= 0
  ),
  CONSTRAINT ck_warehouse_operation_mark_outbox_claim CHECK (
    (state = 'IN_FLIGHT' AND claim_token IS NOT NULL AND claim_until IS NOT NULL)
    OR (state <> 'IN_FLIGHT' AND claim_token IS NULL AND claim_until IS NULL)
  ),
  CONSTRAINT ck_warehouse_operation_mark_outbox_recovery CHECK (
    (recovery_version = 0
      AND recovered_by_subject_id IS NULL
      AND recovery_reason IS NULL
      AND recovered_at IS NULL)
    OR (recovery_version > 0
      AND recovered_by_subject_id IS NOT NULL
      AND recovery_reason IS NOT NULL
      AND btrim(recovery_reason) <> ''
      AND recovered_at IS NOT NULL)
  )
);

CREATE INDEX idx_warehouse_operation_mark_outbox_due
  ON public.warehouse_operation_mark_outbox(
    state, next_attempt_at, claim_until, warehouse_id, operation_id)
  WHERE state IN ('PENDING', 'IN_FLIGHT', 'RETRY_PENDING');

-- Every reviewed recovery remains immutable even though the outbox row keeps only the latest
-- recovery fence for efficient optimistic concurrency.
CREATE TABLE public.warehouse_operation_mark_recovery_audit (
  id uuid NOT NULL,
  warehouse_id uuid NOT NULL,
  operation_id uuid NOT NULL,
  recovery_version bigint NOT NULL,
  reviewed_by_subject_id uuid NOT NULL,
  reason varchar(2000) NOT NULL,
  reviewed_at timestamptz NOT NULL,
  CONSTRAINT warehouse_operation_mark_recovery_audit_pkey PRIMARY KEY (id),
  CONSTRAINT fk_warehouse_operation_mark_recovery_audit_outbox
    FOREIGN KEY (warehouse_id, operation_id)
    REFERENCES public.warehouse_operation_mark_outbox(warehouse_id, operation_id)
    ON DELETE RESTRICT,
  CONSTRAINT uq_warehouse_operation_mark_recovery_audit_version
    UNIQUE (warehouse_id, operation_id, recovery_version),
  CONSTRAINT ck_warehouse_operation_mark_recovery_audit_version CHECK (recovery_version > 0),
  CONSTRAINT ck_warehouse_operation_mark_recovery_audit_reason CHECK (btrim(reason) <> '')
);

CREATE FUNCTION public.reject_warehouse_operation_mark_recovery_audit_mutation()
RETURNS trigger
LANGUAGE plpgsql
AS $$
BEGIN
  RAISE EXCEPTION 'warehouse operation mark recovery audit rows are immutable';
END;
$$;

CREATE TRIGGER warehouse_operation_mark_recovery_audit_immutable
BEFORE UPDATE OR DELETE ON public.warehouse_operation_mark_recovery_audit
FOR EACH ROW
EXECUTE FUNCTION public.reject_warehouse_operation_mark_recovery_audit_mutation();

-- One truthful historical mark per warehouse is enough to prevent an unsafe immediate timezone
-- correction. Future operations are recorded individually by the transactional application path.
WITH candidates AS (
  SELECT warehouse_id, id AS operation_id, created_at AS occurred_at
  FROM public.maintenance_estimate
  UNION ALL
  SELECT warehouse_id, id, created_at
  FROM public.maintenance_repair
  UNION ALL
  SELECT warehouse_id, id, created_at
  FROM public.property_disposition_decision
), earliest AS (
  SELECT DISTINCT ON (warehouse_id)
    warehouse_id,
    operation_id,
    occurred_at
  FROM candidates
  ORDER BY warehouse_id, occurred_at, operation_id
)
INSERT INTO public.warehouse_operation_mark_outbox(
  operation_id,
  warehouse_id,
  occurred_at,
  state,
  attempt_count,
  next_attempt_at,
  created_at,
  updated_at
)
SELECT
  operation_id,
  warehouse_id,
  occurred_at,
  'PENDING',
  0,
  clock_timestamp(),
  clock_timestamp(),
  clock_timestamp()
FROM earliest;
