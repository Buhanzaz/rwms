-- Keep acceptance anti-joins and the direct-repair active-root guard bounded as repair history
-- grows. These indexes add no new invariant and do not rewrite lifecycle data.
CREATE INDEX idx_repair_source_rework
  ON public.maintenance_repair (source_repair_id)
  WHERE source_repair_id IS NOT NULL;

CREATE INDEX idx_repair_active_primary_item
  ON public.maintenance_repair (rental_item_id, created_at, id)
  INCLUDE (warehouse_id)
  WHERE kind = 'PRIMARY'
    AND execution_state <> 'CANCELLED'
    AND acceptance_state NOT IN ('ACCEPTED', 'WRITTEN_OFF');
