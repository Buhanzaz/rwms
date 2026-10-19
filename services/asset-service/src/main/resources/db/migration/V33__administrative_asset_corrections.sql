-- A correction repairs an erroneous recorded location only. It is durable
-- evidence, not a physical movement ledger row: an actual transfer belongs to
-- logistics and must retain its logistics document.

CREATE TABLE public.administrative_asset_correction (
  id uuid NOT NULL,
  version bigint NOT NULL DEFAULT 0,
  asset_kind varchar(16) NOT NULL,
  asset_id uuid NOT NULL,
  source_warehouse_id uuid NOT NULL,
  target_warehouse_id uuid NOT NULL,
  quantity bigint,
  reason varchar(2000) NOT NULL,
  evidence_link varchar(2000) NOT NULL,
  request_sha256 varchar(64) NOT NULL,
  actor_subject_id uuid NOT NULL,
  idempotency_key uuid NOT NULL,
  applied_at timestamptz NOT NULL,
  created_at timestamptz NOT NULL,
  CONSTRAINT administrative_asset_correction_pkey PRIMARY KEY (id),
  CONSTRAINT ck_administrative_asset_correction_version CHECK (version >= 0),
  CONSTRAINT ck_administrative_asset_correction_kind CHECK (
    asset_kind IN ('CABIN','EQUIPMENT')),
  CONSTRAINT ck_administrative_asset_correction_warehouses CHECK (
    source_warehouse_id <> target_warehouse_id),
  CONSTRAINT ck_administrative_asset_correction_quantity CHECK (
    (asset_kind = 'CABIN' AND quantity IS NULL)
    OR (asset_kind = 'EQUIPMENT' AND quantity IS NOT NULL AND quantity > 0)),
  CONSTRAINT ck_administrative_asset_correction_reason CHECK (
    length(btrim(reason)) BETWEEN 1 AND 2000),
  CONSTRAINT ck_administrative_asset_correction_evidence_link CHECK (
    length(btrim(evidence_link)) BETWEEN 1 AND 2000),
  CONSTRAINT ck_administrative_asset_correction_request_sha256 CHECK (
    request_sha256 ~ '^[0-9a-f]{64}$')
);

-- Subject-bound permanent replay is intentionally separate from the expiring
-- generic idempotency store: an administrator can always prove what the key
-- originally corrected.
CREATE UNIQUE INDEX uk_administrative_asset_correction_actor_key
  ON public.administrative_asset_correction(actor_subject_id, idempotency_key);
CREATE INDEX idx_administrative_asset_correction_asset_applied
  ON public.administrative_asset_correction(asset_kind, asset_id, applied_at DESC, id DESC);

CREATE TABLE public.administrative_asset_correction_event (
  event_id uuid NOT NULL,
  correction_id uuid NOT NULL,
  event_type varchar(16) NOT NULL,
  actor_subject_id uuid NOT NULL,
  request_sha256 varchar(64) NOT NULL,
  event_body jsonb NOT NULL,
  event_sha256 varchar(64) NOT NULL,
  occurred_at timestamptz NOT NULL,
  CONSTRAINT administrative_asset_correction_event_pkey PRIMARY KEY (event_id),
  CONSTRAINT uk_administrative_asset_correction_event_correction_type
    UNIQUE (correction_id, event_type),
  CONSTRAINT ck_administrative_asset_correction_event_type CHECK (
    event_type = 'APPLIED'),
  CONSTRAINT ck_administrative_asset_correction_event_request_sha256 CHECK (
    request_sha256 ~ '^[0-9a-f]{64}$'),
  CONSTRAINT ck_administrative_asset_correction_event_sha256 CHECK (
    event_sha256 ~ '^[0-9a-f]{64}$'),
  CONSTRAINT ck_administrative_asset_correction_event_body CHECK (
    jsonb_typeof(event_body) = 'object'),
  CONSTRAINT fk_administrative_asset_correction_event_correction
    FOREIGN KEY (correction_id)
    REFERENCES public.administrative_asset_correction(id)
);

CREATE INDEX idx_administrative_asset_correction_event_correction_occurred
  ON public.administrative_asset_correction_event(correction_id, occurred_at, event_id);

CREATE FUNCTION public.prevent_administrative_asset_correction_truncate()
RETURNS trigger
LANGUAGE plpgsql
AS $$
BEGIN
  RAISE EXCEPTION 'administrative correction evidence cannot be truncated';
END;
$$;

CREATE TRIGGER trg_administrative_asset_correction_immutable
BEFORE UPDATE OR DELETE ON public.administrative_asset_correction
FOR EACH ROW EXECUTE FUNCTION public.prevent_asset_append_only_mutation();

CREATE TRIGGER trg_administrative_asset_correction_event_immutable
BEFORE UPDATE OR DELETE ON public.administrative_asset_correction_event
FOR EACH ROW EXECUTE FUNCTION public.prevent_asset_append_only_mutation();

CREATE TRIGGER trg_administrative_asset_correction_no_truncate
BEFORE TRUNCATE ON public.administrative_asset_correction
FOR EACH STATEMENT EXECUTE FUNCTION public.prevent_administrative_asset_correction_truncate();

CREATE TRIGGER trg_administrative_asset_correction_event_no_truncate
BEFORE TRUNCATE ON public.administrative_asset_correction_event
FOR EACH STATEMENT EXECUTE FUNCTION public.prevent_administrative_asset_correction_truncate();
