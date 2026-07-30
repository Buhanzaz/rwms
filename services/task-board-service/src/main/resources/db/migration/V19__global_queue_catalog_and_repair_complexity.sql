-- One-time cutover from warehouse-owned queue definitions to a global catalog.
-- Existing work_queue rows remain warehouse bindings so queue_entry/history IDs
-- and each warehouse's ordering are preserved.

DO $$
DECLARE
  spb uuid := '00000000-0000-0000-0000-000000000001';
BEGIN
  IF EXISTS (
    SELECT 1
      FROM public.work_queue
     WHERE nullif(btrim(name), '') IS NULL
  ) THEN
    RAISE EXCEPTION 'Every legacy work queue must have a non-blank name before V19';
  END IF;

  IF EXISTS (
    SELECT 1
      FROM public.work_queue
     GROUP BY warehouse_id,
              lower(regexp_replace(btrim(name), '[[:space:]]+', ' ', 'g')),
              queue_type
    HAVING count(*) > 1
  ) THEN
    RAISE EXCEPTION
      'A warehouse contains duplicate normalized queue definitions; reconcile them before V19';
  END IF;

  IF EXISTS (
    SELECT 1
      FROM public.work_queue
     WHERE warehouse_id = spb
     GROUP BY lower(regexp_replace(btrim(name), '[[:space:]]+', ' ', 'g')),
              queue_type
    HAVING count(*) > 1
  ) THEN
    RAISE EXCEPTION 'SPB queue definitions are ambiguous; reconcile them before V19';
  END IF;
END
$$;

CREATE TABLE public.queue_definition (
  id uuid PRIMARY KEY,
  version bigint NOT NULL DEFAULT 0,
  revision_marker uuid NOT NULL,
  name varchar(128) NOT NULL,
  normalized_name varchar(128) NOT NULL,
  description varchar(1000),
  queue_type varchar(32) NOT NULL,
  CONSTRAINT uk_queue_definition_identity UNIQUE (normalized_name, queue_type),
  CONSTRAINT ck_queue_definition_name CHECK (nullif(btrim(name), '') IS NOT NULL),
  CONSTRAINT ck_queue_definition_normalized_name
    CHECK (normalized_name = lower(regexp_replace(btrim(name), '[[:space:]]+', ' ', 'g')))
);

-- SPB is the reviewed source. Reusing its work_queue UUIDs keeps the previous
-- catalog routing identifiers valid as global definition identifiers.
INSERT INTO public.queue_definition(
  id,version,revision_marker,name,normalized_name,description,queue_type)
SELECT queue.id,
       0,
       md5(queue.id::text || ':queue-definition:v19')::uuid,
       regexp_replace(btrim(queue.name), '[[:space:]]+', ' ', 'g'),
       lower(regexp_replace(btrim(queue.name), '[[:space:]]+', ' ', 'g')),
       queue.description,
       queue.queue_type
  FROM public.work_queue queue
 WHERE queue.warehouse_id = '00000000-0000-0000-0000-000000000001'
 ORDER BY queue.sort_order, queue.id;

-- A legacy definition absent from SPB is retained once, deterministically.
-- No UUID is synthesized and no permanent legacy fallback remains.
INSERT INTO public.queue_definition(
  id,version,revision_marker,name,normalized_name,description,queue_type)
SELECT source.id,
       0,
       md5(source.id::text || ':queue-definition:v19')::uuid,
       source.canonical_name,
       source.normalized_name,
       source.description,
       source.queue_type
  FROM (
    SELECT DISTINCT ON (
             lower(regexp_replace(btrim(queue.name), '[[:space:]]+', ' ', 'g')),
             queue.queue_type)
           queue.id,
           regexp_replace(btrim(queue.name), '[[:space:]]+', ' ', 'g') canonical_name,
           lower(regexp_replace(btrim(queue.name), '[[:space:]]+', ' ', 'g')) normalized_name,
           queue.description,
           queue.queue_type
      FROM public.work_queue queue
     ORDER BY
           lower(regexp_replace(btrim(queue.name), '[[:space:]]+', ' ', 'g')),
           queue.queue_type,
           queue.id
  ) source
 WHERE NOT EXISTS (
   SELECT 1
     FROM public.queue_definition definition
    WHERE definition.normalized_name = source.normalized_name
      AND definition.queue_type = source.queue_type
 )
 ORDER BY source.normalized_name, source.queue_type;

ALTER TABLE public.work_queue
  ADD COLUMN definition_id uuid;

UPDATE public.work_queue queue
   SET definition_id = definition.id
  FROM public.queue_definition definition
 WHERE definition.normalized_name =
       lower(regexp_replace(btrim(queue.name), '[[:space:]]+', ' ', 'g'))
   AND definition.queue_type = queue.queue_type;

ALTER TABLE public.queue_usage_reference
  ADD COLUMN queue_definition_id uuid;

UPDATE public.queue_usage_reference reference
   SET queue_definition_id = queue.definition_id
  FROM public.work_queue queue
 WHERE queue.id = reference.queue_id;

ALTER TABLE public.queue_usage_reference
  DROP CONSTRAINT fk_queue_usage_queue,
  ADD CONSTRAINT fk_queue_usage_definition
    FOREIGN KEY (queue_definition_id) REFERENCES public.queue_definition(id),
  ALTER COLUMN queue_definition_id SET NOT NULL,
  DROP COLUMN queue_id;

DO $$
BEGIN
  IF EXISTS (SELECT 1 FROM public.work_queue WHERE definition_id IS NULL) THEN
    RAISE EXCEPTION 'Every work queue must resolve to one global definition';
  END IF;
END
$$;

ALTER TABLE public.work_queue
  DROP CONSTRAINT ck_work_queue_holding,
  ADD CONSTRAINT fk_work_queue_definition
    FOREIGN KEY (definition_id) REFERENCES public.queue_definition(id),
  ADD CONSTRAINT uk_work_queue_warehouse_definition
    UNIQUE (warehouse_id, definition_id),
  ALTER COLUMN definition_id SET NOT NULL;

DROP INDEX public.idx_work_queue_order;

ALTER TABLE public.work_queue
  DROP COLUMN name,
  DROP COLUMN description,
  DROP COLUMN queue_type;

CREATE INDEX idx_work_queue_order
  ON public.work_queue(warehouse_id, sort_order, definition_id, id);

ALTER TABLE public.warehouse_kpi_settings
  ADD COLUMN repair_light_boundary_minutes integer NOT NULL DEFAULT 60,
  ADD COLUMN repair_medium_boundary_minutes integer NOT NULL DEFAULT 180,
  ADD COLUMN repair_complex_boundary_minutes integer NOT NULL DEFAULT 360,
  ADD CONSTRAINT ck_warehouse_kpi_repair_complexity_boundaries
    CHECK (
      repair_light_boundary_minutes > 0
      AND repair_light_boundary_minutes < repair_medium_boundary_minutes
      AND repair_medium_boundary_minutes < repair_complex_boundary_minutes
    );

CREATE TABLE public.task_relocation_receipt (
  source_client_id varchar(128) NOT NULL,
  external_task_id uuid NOT NULL,
  target_warehouse_id uuid NOT NULL,
  expected_task_version bigint NOT NULL,
  resulting_task_version bigint NOT NULL,
  processed_at timestamptz NOT NULL,
  CONSTRAINT pk_task_relocation_receipt PRIMARY KEY (
    source_client_id, external_task_id, target_warehouse_id, expected_task_version),
  CONSTRAINT ck_task_relocation_receipt_source
    CHECK (source_client_id = 'maintenance-service'),
  CONSTRAINT ck_task_relocation_receipt_versions
    CHECK (
      expected_task_version >= 0
      AND resulting_task_version >= expected_task_version
    )
);
