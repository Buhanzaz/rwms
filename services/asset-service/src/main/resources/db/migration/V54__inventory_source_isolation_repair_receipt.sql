CREATE TABLE public.inventory_source_isolation_repair (
  inventory_id uuid PRIMARY KEY,
  warehouse_id uuid NOT NULL,
  manifest_sha256 varchar(64) NOT NULL CHECK (manifest_sha256 ~ '^[0-9a-f]{64}$'),
  source_count integer NOT NULL CHECK (source_count > 0),
  created_at timestamptz NOT NULL
);

CREATE TRIGGER trg_inventory_source_isolation_repair_no_update
BEFORE UPDATE OR DELETE ON public.inventory_source_isolation_repair
FOR EACH ROW EXECUTE FUNCTION public.prevent_asset_append_only_mutation();
