-- A completed inventory review can reconcile its full furniture count only once.
-- Its source identity outlives ordinary idempotency retention so retries never
-- repeat a balance mutation or silently accept a changed inventory payload.

CREATE TABLE public.inventory_furniture_reconciliation (
  inventory_id uuid NOT NULL,
  version bigint NOT NULL DEFAULT 0,
  request_sha256 varchar(64) NOT NULL,
  idempotency_key uuid NOT NULL,
  completed_at timestamptz,
  created_at timestamptz NOT NULL,
  updated_at timestamptz NOT NULL,
  CONSTRAINT inventory_furniture_reconciliation_pkey PRIMARY KEY (inventory_id),
  CONSTRAINT ck_inventory_furniture_reconciliation_version CHECK (version >= 0),
  CONSTRAINT ck_inventory_furniture_reconciliation_hash CHECK (
    request_sha256 ~ '^[0-9a-f]{64}$'),
  CONSTRAINT ck_inventory_furniture_reconciliation_completion CHECK (
    completed_at IS NULL OR completed_at >= created_at)
);

CREATE FUNCTION public.protect_inventory_furniture_reconciliation_identity()
RETURNS trigger
LANGUAGE plpgsql
AS $$
BEGIN
  IF (NEW.inventory_id, NEW.request_sha256, NEW.idempotency_key)
     IS DISTINCT FROM
     (OLD.inventory_id, OLD.request_sha256, OLD.idempotency_key) THEN
    RAISE EXCEPTION 'inventory furniture reconciliation source identity is immutable';
  END IF;
  RETURN NEW;
END;
$$;

CREATE TRIGGER trg_inventory_furniture_reconciliation_identity
BEFORE UPDATE ON public.inventory_furniture_reconciliation
FOR EACH ROW EXECUTE FUNCTION public.protect_inventory_furniture_reconciliation_identity();

CREATE TRIGGER trg_inventory_furniture_reconciliation_no_delete
BEFORE DELETE ON public.inventory_furniture_reconciliation
FOR EACH ROW EXECUTE FUNCTION public.prevent_asset_hard_delete();
