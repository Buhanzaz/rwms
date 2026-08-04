CREATE TABLE public.equipment_external_reference (
  source_system varchar(64) NOT NULL,
  external_reference_id uuid NOT NULL,
  equipment_id uuid NOT NULL,
  created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
  CONSTRAINT equipment_external_reference_pkey
    PRIMARY KEY (source_system, external_reference_id),
  CONSTRAINT fk_equipment_external_reference_catalog
    FOREIGN KEY (equipment_id) REFERENCES public.equipment_catalog_item(id),
  CONSTRAINT ck_equipment_external_reference_source
    CHECK (source_system IN ('MAINTENANCE_CATALOG_NODE'))
);

CREATE INDEX idx_equipment_external_reference_equipment
  ON public.equipment_external_reference(equipment_id, source_system, external_reference_id);

CREATE OR REPLACE FUNCTION public.reject_equipment_external_reference_mutation()
RETURNS trigger
LANGUAGE plpgsql
AS $$
BEGIN
  RAISE EXCEPTION 'equipment external references are immutable';
END
$$;

CREATE TRIGGER trg_equipment_external_reference_immutable
BEFORE UPDATE OR DELETE OR TRUNCATE ON public.equipment_external_reference
FOR EACH STATEMENT EXECUTE FUNCTION public.reject_equipment_external_reference_mutation();
