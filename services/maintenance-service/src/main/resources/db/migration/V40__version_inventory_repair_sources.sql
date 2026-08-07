-- A finding can be supplemented before inventory completion.  Keep every frozen plan immutable
-- under the finding revision that produced it instead of letting the first plan block later
-- evidence.  Revision 0 is reserved only for historical orphaned operation registrations that
-- predate this migration; real inventory source revisions always start at 1.

ALTER TABLE public.inventory_repair_source_operation
  ADD COLUMN source_revision bigint;

UPDATE public.inventory_repair_source_operation AS operation
SET source_revision = source.source_revision
FROM public.inventory_repair_source AS source
WHERE source.inventory_id = operation.inventory_id
  AND source.finding_id = operation.finding_id;

UPDATE public.inventory_repair_source_operation
SET source_revision = 0
WHERE source_revision IS NULL;

ALTER TABLE public.inventory_repair_source
  DROP CONSTRAINT fk_inventory_repair_source_operation;

ALTER TABLE public.inventory_repair_source
  DROP CONSTRAINT uk_inventory_repair_source;

ALTER TABLE public.inventory_repair_source_operation
  DROP CONSTRAINT inventory_repair_source_operation_pkey;

ALTER TABLE public.inventory_repair_source_operation
  ALTER COLUMN source_revision SET NOT NULL;

ALTER TABLE public.inventory_repair_source_operation
  ADD CONSTRAINT inventory_repair_source_operation_pkey
    PRIMARY KEY (inventory_id, finding_id, source_revision),
  ADD CONSTRAINT ck_inventory_repair_source_operation_source_revision
    CHECK (source_revision >= 0);

ALTER TABLE public.inventory_repair_source
  ADD CONSTRAINT uk_inventory_repair_source
    UNIQUE (inventory_id, finding_id, source_revision),
  ADD CONSTRAINT fk_inventory_repair_source_operation
    FOREIGN KEY (inventory_id, finding_id, source_revision)
    REFERENCES public.inventory_repair_source_operation(inventory_id, finding_id, source_revision);
