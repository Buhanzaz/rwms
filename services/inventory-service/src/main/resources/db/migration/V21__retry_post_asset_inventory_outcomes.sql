-- V20 made completed-inventory furniture reconciliation retryable, after which publication could
-- reach the asset owner before a terminal logistics lease replay was supported. Those rows were
-- recorded as generic source conflicts even though their asset effect is already durable.
-- Requeue only that post-asset/no-maintenance shape; attempts and owner receipts remain immutable.
UPDATE public.inventory_publication_intent intent
SET state = 'TRANSIENT_FAILED',
    blocked_failure_code = NULL,
    publication_revision = publication_revision + 1,
    updated_at = clock_timestamp()
FROM public.inventory_session session
WHERE session.id = intent.inventory_id
  AND session.lifecycle = 'COMPLETED'
  AND intent.state = 'BLOCKED'
  AND intent.blocked_failure_code = 'SOURCE_PRECONDITION_CONFLICT'
  AND intent.effective_asset_version IS NOT NULL
  AND intent.asset_outcome_result IS NOT NULL
  AND intent.maintenance_result IS NULL;
