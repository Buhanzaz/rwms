-- Queue positions are human-facing ordinal numbers.  V25 normalized the
-- global standard using technical gaps; convert the existing standard to
-- contiguous positions while preserving its reviewed order and terminal
-- holding queues.  The application-ready projection reconciler propagates
-- this single source of truth to the retained per-warehouse work_queue rows.
WITH ordered AS (
  SELECT definition.id,
         row_number() OVER (
           ORDER BY
             CASE WHEN definition.queue_type = 'HOLDING' THEN 1 ELSE 0 END,
             definition.sort_order,
             definition.normalized_name,
             definition.queue_type,
             definition.id
         ) AS normalized_order
    FROM public.queue_definition definition
   WHERE definition.queue_purpose = 'GENERAL'
)
UPDATE public.queue_definition definition
   SET sort_order = ordered.normalized_order
  FROM ordered
 WHERE definition.id = ordered.id
   AND definition.sort_order IS DISTINCT FROM ordered.normalized_order;
