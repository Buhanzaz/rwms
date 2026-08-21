-- Ordinary task-board queues expose only a bounded priority-ordered waiting window.
ALTER TABLE public.queue_definition
  ADD COLUMN available_task_limit integer NOT NULL DEFAULT 6,
  ADD CONSTRAINT ck_queue_definition_available_task_limit
    CHECK (available_task_limit BETWEEN 1 AND 50);

ALTER TABLE public.work_queue
  ADD COLUMN available_task_limit integer NOT NULL DEFAULT 6,
  ADD CONSTRAINT ck_work_queue_available_task_limit
    CHECK (available_task_limit BETWEEN 1 AND 50);

-- Lets the lateral per-queue availability query stop from the persisted priority order
-- without materializing the warehouse backlog in the application JVM.
CREATE INDEX idx_board_task_ordinary_availability
  ON public.board_task(warehouse_id, priority, id)
  WHERE status = 'ACTIVE';

-- Ordinary queue positions used to restart from zero for every scheduled date. Preserve their
-- historic relative order once, but turn every unfinished ordinary queue into one aggregate
-- sequence. Logistics queues retain their date/lane partitions.
WITH ranked AS (
  SELECT entry.id,
         (ROW_NUMBER() OVER (
           PARTITION BY entry.queue_id
           ORDER BY task.priority,
                    task.scheduled_date,
                    entry.queue_position,
                    task.id,
                    entry.route_index,
                    entry.id
         ) - 1)::integer AS aggregate_position
    FROM public.queue_entry entry
    JOIN public.board_task task ON task.id = entry.task_id
    JOIN public.work_queue queue ON queue.id = entry.queue_id
    JOIN public.queue_definition definition ON definition.id = queue.definition_id
   WHERE task.status = 'ACTIVE'
     AND entry.status IN ('WAITING', 'IN_PROGRESS', 'PAUSED')
     AND definition.queue_purpose <> 'LOGISTICS_DRIVER'
)
UPDATE public.queue_entry entry
   SET queue_position = ranked.aggregate_position
  FROM ranked
 WHERE entry.id = ranked.id
   AND entry.queue_position IS DISTINCT FROM ranked.aggregate_position;

-- A fully waiting active route can be corrected without rewriting any started or paused history.
-- Its first unfinished HOLDING step becomes the only REAL gate; every other unfinished step stays
-- durable route truth as SHADOW until the quarantine step is completed.
WITH holding_gate AS (
  SELECT task.id AS task_id, MIN(entry.route_index) AS route_index
    FROM public.board_task task
    JOIN public.queue_entry entry ON entry.task_id = task.id
    JOIN public.work_queue queue ON queue.id = entry.queue_id
    JOIN public.queue_definition definition ON definition.id = queue.definition_id
   WHERE task.status = 'ACTIVE'
     AND entry.status = 'WAITING'
     AND definition.queue_type = 'HOLDING'
     AND NOT EXISTS (
       SELECT 1
         FROM public.queue_entry started
        WHERE started.task_id = task.id
          AND started.status IN ('IN_PROGRESS', 'PAUSED')
     )
   GROUP BY task.id
), corrected AS (
  SELECT entry.id,
         CASE WHEN entry.route_index = gate.route_index THEN 'REAL' ELSE 'SHADOW' END AS entry_type
    FROM public.queue_entry entry
    JOIN holding_gate gate ON gate.task_id = entry.task_id
   WHERE entry.status = 'WAITING'
)
UPDATE public.queue_entry entry
   SET entry_type = corrected.entry_type
  FROM corrected
 WHERE entry.id = corrected.id
   AND entry.entry_type IS DISTINCT FROM corrected.entry_type;
