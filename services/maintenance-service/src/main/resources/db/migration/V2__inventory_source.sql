-- Stage 7 maintenance-owned immutable inventory plan/source boundary.

ALTER TABLE public.maintenance_repair DROP CONSTRAINT ck_repair_origin;
ALTER TABLE public.maintenance_repair
  ADD CONSTRAINT ck_repair_origin
  CHECK (origin IN ('ESTIMATE','DIRECT_REPAIR','INVENTORY'));

ALTER TABLE public.maintenance_repair DROP CONSTRAINT ck_repair_origin_estimate;
ALTER TABLE public.maintenance_repair
  ADD CONSTRAINT ck_repair_origin_estimate CHECK (
    (kind = 'PRIMARY' AND origin = 'ESTIMATE' AND estimate_id IS NOT NULL)
    OR (kind = 'PRIMARY' AND origin IN ('DIRECT_REPAIR','INVENTORY') AND estimate_id IS NULL)
    OR (kind = 'REWORK' AND estimate_id IS NULL));

CREATE TABLE public.inventory_repair_source_operation (
  inventory_id uuid NOT NULL,
  finding_id uuid NOT NULL,
  version bigint NOT NULL DEFAULT 0,
  request_sha256 varchar(64) NOT NULL,
  created_at timestamptz NOT NULL,
  CONSTRAINT inventory_repair_source_operation_pkey PRIMARY KEY (inventory_id, finding_id),
  CONSTRAINT ck_inventory_repair_source_operation_version CHECK (version >= 0),
  CONSTRAINT ck_inventory_repair_source_operation_hash CHECK (
    request_sha256 ~ '^[0-9a-f]{64}$')
);

CREATE TABLE public.inventory_repair_source (
  row_id uuid NOT NULL,
  version bigint NOT NULL DEFAULT 0,
  inventory_id uuid NOT NULL,
  finding_id uuid NOT NULL,
  source_revision bigint NOT NULL,
  warehouse_id uuid NOT NULL,
  catalog_version_id uuid NOT NULL,
  plan_request_sha256 varchar(64) NOT NULL,
  plan_fingerprint varchar(64) NOT NULL,
  plan_snapshot jsonb NOT NULL,
  media_snapshot jsonb NOT NULL,
  source_fingerprint varchar(64),
  rental_item_id uuid,
  rental_item_version_snapshot bigint,
  repair_id uuid,
  created_at timestamptz NOT NULL,
  repair_bound_at timestamptz,
  CONSTRAINT inventory_repair_source_pkey PRIMARY KEY (row_id),
  CONSTRAINT uk_inventory_repair_source UNIQUE (inventory_id, finding_id),
  CONSTRAINT uk_inventory_repair_source_repair UNIQUE (repair_id),
  CONSTRAINT fk_inventory_repair_source_catalog
    FOREIGN KEY (catalog_version_id) REFERENCES public.catalog_version(id),
  CONSTRAINT fk_inventory_repair_source_operation
    FOREIGN KEY (inventory_id, finding_id)
    REFERENCES public.inventory_repair_source_operation(inventory_id, finding_id),
  CONSTRAINT fk_inventory_repair_source_repair
    FOREIGN KEY (repair_id) REFERENCES public.maintenance_repair(id),
  CONSTRAINT ck_inventory_repair_source_version
    CHECK (version >= 0 AND source_revision >= 1),
  CONSTRAINT ck_inventory_repair_source_hashes CHECK (
    plan_request_sha256 ~ '^[0-9a-f]{64}$'
    AND plan_fingerprint ~ '^[0-9a-f]{64}$'
    AND (source_fingerprint IS NULL OR source_fingerprint ~ '^[0-9a-f]{64}$')),
  CONSTRAINT ck_inventory_repair_source_json CHECK (
    jsonb_typeof(plan_snapshot) = 'object' AND jsonb_typeof(media_snapshot) = 'array'),
  CONSTRAINT ck_inventory_repair_source_binding CHECK (
    (repair_id IS NULL AND source_fingerprint IS NULL AND rental_item_id IS NULL
      AND rental_item_version_snapshot IS NULL AND repair_bound_at IS NULL)
    OR (repair_id IS NOT NULL AND source_fingerprint IS NOT NULL AND rental_item_id IS NOT NULL
      AND rental_item_version_snapshot >= 0 AND repair_bound_at IS NOT NULL))
);
CREATE INDEX idx_inventory_repair_source_warehouse
  ON public.inventory_repair_source(warehouse_id, inventory_id, finding_id);

CREATE FUNCTION public.enforce_inventory_repair_source_immutability()
RETURNS trigger
LANGUAGE plpgsql
AS $$
BEGIN
  IF ROW(
      NEW.row_id, NEW.inventory_id, NEW.finding_id, NEW.source_revision,
      NEW.warehouse_id, NEW.catalog_version_id, NEW.plan_request_sha256,
      NEW.plan_fingerprint, NEW.plan_snapshot, NEW.media_snapshot, NEW.created_at)
    IS DISTINCT FROM ROW(
      OLD.row_id, OLD.inventory_id, OLD.finding_id, OLD.source_revision,
      OLD.warehouse_id, OLD.catalog_version_id, OLD.plan_request_sha256,
      OLD.plan_fingerprint, OLD.plan_snapshot, OLD.media_snapshot, OLD.created_at) THEN
    RAISE EXCEPTION 'inventory repair plan/source fields are immutable'
      USING ERRCODE = '23514';
  END IF;

  IF OLD.repair_id IS NULL AND NEW.repair_id IS NOT NULL THEN
    IF OLD.source_fingerprint IS NOT NULL
      OR OLD.rental_item_id IS NOT NULL
      OR OLD.rental_item_version_snapshot IS NOT NULL
      OR OLD.repair_bound_at IS NOT NULL
      OR NEW.source_fingerprint IS NULL
      OR NEW.rental_item_id IS NULL
      OR NEW.rental_item_version_snapshot IS NULL
      OR NEW.repair_bound_at IS NULL
      OR NEW.version <> OLD.version + 1 THEN
      RAISE EXCEPTION 'inventory repair source binding must be one complete versioned transition'
        USING ERRCODE = '23514';
    END IF;
  ELSIF ROW(
      NEW.version, NEW.source_fingerprint, NEW.rental_item_id,
      NEW.rental_item_version_snapshot, NEW.repair_id, NEW.repair_bound_at)
    IS DISTINCT FROM ROW(
      OLD.version, OLD.source_fingerprint, OLD.rental_item_id,
      OLD.rental_item_version_snapshot, OLD.repair_id, OLD.repair_bound_at) THEN
    RAISE EXCEPTION 'inventory repair source binding is immutable after creation'
      USING ERRCODE = '23514';
  END IF;

  RETURN NEW;
END;
$$;

CREATE TRIGGER trg_inventory_repair_source_immutable
BEFORE UPDATE ON public.inventory_repair_source
FOR EACH ROW
EXECUTE FUNCTION public.enforce_inventory_repair_source_immutability();
