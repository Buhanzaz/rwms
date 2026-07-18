-- Stage 8 maintenance-owned immutable source boundary. This table deliberately
-- has no foreign key to logistics, asset or inventory persistence.

CREATE TABLE public.logistics_return_shortage (
  return_id uuid NOT NULL,
  line_id uuid NOT NULL,
  version bigint NOT NULL DEFAULT 0,
  warehouse_id uuid NOT NULL,
  rental_item_id uuid NOT NULL,
  rental_item_version_snapshot bigint NOT NULL,
  source_sha256 varchar(64) NOT NULL,
  snapshot_sha256 varchar(64) NOT NULL,
  shortage_snapshot jsonb NOT NULL,
  created_at timestamptz NOT NULL,
  CONSTRAINT logistics_return_shortage_pkey PRIMARY KEY (return_id, line_id),
  CONSTRAINT ck_logistics_return_shortage_version CHECK (
    version >= 0 AND rental_item_version_snapshot >= 0),
  CONSTRAINT ck_logistics_return_shortage_hashes CHECK (
    source_sha256 ~ '^[0-9a-f]{64}$' AND snapshot_sha256 ~ '^[0-9a-f]{64}$'),
  CONSTRAINT ck_logistics_return_shortage_snapshot CHECK (
    jsonb_typeof(shortage_snapshot) = 'array')
);
CREATE INDEX idx_logistics_return_shortage_warehouse
  ON public.logistics_return_shortage(warehouse_id, created_at DESC, return_id, line_id);
