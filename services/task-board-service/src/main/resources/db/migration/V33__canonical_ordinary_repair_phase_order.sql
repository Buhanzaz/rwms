-- Canonical ordinary repair phases are the shared presentation standard.  Custom GENERAL
-- definitions retain their existing relative order after the reviewed phases; logistics remains
-- outside this catalog ordering.
CREATE TABLE public.task_board_projection_migration (
  aggregate_type varchar(64) NOT NULL,
  aggregate_id uuid NOT NULL,
  migration_version integer NOT NULL,
  migrated_at timestamptz NOT NULL DEFAULT clock_timestamp(),
  CONSTRAINT pk_task_board_projection_migration
    PRIMARY KEY (aggregate_type, aggregate_id, migration_version),
  CONSTRAINT ck_task_board_projection_migration_version
    CHECK (migration_version > 0)
);

WITH phase_order(normalized_name, phase_rank) AS (
  VALUES
    ('сэс и санитария', 1),
    ('сварка', 2),
    ('внешние работы', 3),
    ('внутренние работы', 4),
    ('электрика', 5),
    ('сантехника', 6)
), canonical AS (
  SELECT definition.id,
         row_number() OVER (
           ORDER BY phase_order.phase_rank,
                    definition.sort_order,
                    definition.normalized_name,
                    definition.queue_type,
                    definition.id
         )::integer AS normalized_order
    FROM public.queue_definition definition
    JOIN phase_order
      ON phase_order.normalized_name = definition.normalized_name
   WHERE definition.queue_purpose = 'GENERAL'
), canonical_count AS (
  SELECT count(*)::integer AS total FROM canonical
), custom AS (
  SELECT definition.id,
         (canonical_count.total + row_number() OVER (
           ORDER BY definition.sort_order,
                    definition.normalized_name,
                    definition.queue_type,
                    definition.id
         ))::integer AS normalized_order
    FROM public.queue_definition definition
   CROSS JOIN canonical_count
   WHERE definition.queue_purpose = 'GENERAL'
     AND NOT EXISTS (
       SELECT 1 FROM canonical
        WHERE canonical.id = definition.id
     )
), ordered AS (
  SELECT id, normalized_order FROM canonical
  UNION ALL
  SELECT id, normalized_order FROM custom
)
UPDATE public.queue_definition definition
   SET sort_order = ordered.normalized_order
  FROM ordered
 WHERE definition.id = ordered.id
   AND definition.sort_order IS DISTINCT FROM ordered.normalized_order;

-- Work queues are warehouse-local projections of the global definition order.
CREATE TEMP TABLE rwms_v33_work_queue_order (queue_id uuid PRIMARY KEY) ON COMMIT DROP;

WITH changed AS (
  UPDATE public.work_queue queue
     SET sort_order = definition.sort_order
    FROM public.queue_definition definition
   WHERE queue.definition_id = definition.id
     AND definition.queue_purpose = 'GENERAL'
     AND queue.sort_order IS DISTINCT FROM definition.sort_order
   RETURNING queue.id
)
INSERT INTO rwms_v33_work_queue_order(queue_id)
SELECT id FROM changed;

INSERT INTO public.task_board_projection_migration(
  aggregate_type, aggregate_id, migration_version)
SELECT 'WORK_QUEUE', queue_id, 33
  FROM rwms_v33_work_queue_order;

-- A maintenance-owned active route can be corrected only while every persisted route entry is
-- still WAITING.  Canonical phases move first, unknown legacy GENERAL stages retain their source
-- order afterward, and logistics entries opt out of this repair-specific correction.  Started,
-- paused, completed, and non-maintenance projections are deliberately untouched.
CREATE TEMP TABLE rwms_v33_route_order (
  entry_id uuid PRIMARY KEY,
  new_route_index integer NOT NULL,
  new_entry_type varchar(16) NOT NULL
) ON COMMIT DROP;

WITH phase_order(normalized_name, phase_rank) AS (
  VALUES
    ('сэс и санитария', 1),
    ('сварка', 2),
    ('внешние работы', 3),
    ('внутренние работы', 4),
    ('электрика', 5),
    ('сантехника', 6)
), candidate_tasks AS (
  SELECT task.id
    FROM public.board_task task
    JOIN public.task_sync_source source
      ON source.board_task_id = task.id
    JOIN public.queue_entry entry
      ON entry.task_id = task.id
    JOIN public.work_queue queue
      ON queue.id = entry.queue_id
    JOIN public.queue_definition definition
      ON definition.id = queue.definition_id
   WHERE task.status = 'ACTIVE'
     AND source.source_client_id = 'maintenance-service'
     AND source.source_type = 'MAINTENANCE_REPAIR'
   GROUP BY task.id
  HAVING bool_and(entry.status = 'WAITING')
     AND bool_and(definition.queue_purpose <> 'LOGISTICS_DRIVER')
), ranked AS (
  SELECT entry.id,
         entry.task_id,
         entry.route_index,
         entry.entry_type,
         row_number() OVER (
           PARTITION BY entry.task_id
           ORDER BY CASE WHEN phase_order.phase_rank IS NULL THEN 1 ELSE 0 END,
                    phase_order.phase_rank,
                    entry.route_index,
                    entry.id
         ) AS phase_position
    FROM public.queue_entry entry
    JOIN candidate_tasks candidate
      ON candidate.id = entry.task_id
    JOIN public.work_queue queue
      ON queue.id = entry.queue_id
    JOIN public.queue_definition definition
      ON definition.id = queue.definition_id
    LEFT JOIN phase_order
      ON phase_order.normalized_name = definition.normalized_name
     AND definition.queue_purpose = 'GENERAL'
   WHERE entry.status = 'WAITING'
), mismatched_tasks AS (
  SELECT DISTINCT task_id
    FROM ranked
   WHERE route_index <> (phase_position - 1)::integer
      OR entry_type <> CASE WHEN phase_position = 1 THEN 'REAL' ELSE 'SHADOW' END
)
INSERT INTO rwms_v33_route_order(entry_id, new_route_index, new_entry_type)
SELECT id,
       (phase_position - 1)::integer,
       CASE WHEN phase_position = 1 THEN 'REAL' ELSE 'SHADOW' END
  FROM ranked
  JOIN mismatched_tasks
    ON mismatched_tasks.task_id = ranked.task_id;

-- Route indexes are unique per task, so move the selected rows through a temporary high range
-- before installing the canonical indexes.
UPDATE public.queue_entry entry
   SET route_index = entry.route_index + 1000000
  FROM rwms_v33_route_order corrected
 WHERE entry.id = corrected.entry_id;

UPDATE public.queue_entry entry
   SET route_index = corrected.new_route_index,
       entry_type = corrected.new_entry_type
  FROM rwms_v33_route_order corrected
 WHERE entry.id = corrected.entry_id;

INSERT INTO public.task_board_projection_migration(
  aggregate_type, aggregate_id, migration_version)
SELECT 'QUEUE_ENTRY', entry_id, 33
  FROM rwms_v33_route_order;
