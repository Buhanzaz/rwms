-- A category in an ACTIVE catalog may be re-routed when board settings change.
-- The stable idempotency key remains the command uniqueness boundary; retaining
-- history for each queue makes failed legacy routes auditable without blocking
-- the corrected registration for the same catalog node.
DROP INDEX IF EXISTS public.uk_reconciliation_catalog_operation;

CREATE INDEX idx_reconciliation_catalog_operation_history
  ON public.integration_reconciliation(
    catalog_version_id,
    catalog_node_id,
    operation_type,
    created_at DESC)
  WHERE catalog_version_id IS NOT NULL;
