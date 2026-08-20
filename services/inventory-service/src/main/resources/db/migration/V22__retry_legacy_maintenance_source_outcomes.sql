-- Earlier maintenance publication bound some findings to an estimate or only to an unpublished
-- source registration. One blocked row proves that the completed plan was partially processed by
-- the legacy compatibility path. Reapply the whole immutable plan in one shared next generation:
-- current authoritative targets replay, legacy-adopted targets are superseded, and untouched rows
-- cannot remain on an older generation. Attempts and downstream receipts remain immutable history.
UPDATE public.inventory_publication_intent intent
SET state = 'READY',
    outcome_reapplication_no = outcome_reapplication_no + 1,
    target_id = NULL,
    maintenance_repair_id = NULL,
    maintenance_estimate_id = NULL,
    maintenance_outcome = NULL,
    maintenance_result = NULL,
    effective_asset_version = NULL,
    asset_outcome_result = NULL,
    request_sha256 = NULL,
    current_precondition_sha256 = NULL,
    blocked_failure_code = NULL,
    closed_reason = NULL,
    closed_actor_ref = NULL,
    closed_at = NULL,
    publication_revision = publication_revision + 1,
    updated_at = clock_timestamp()
FROM public.inventory_session session
WHERE session.id = intent.inventory_id
  AND session.lifecycle = 'COMPLETED'
  AND EXISTS (
    SELECT 1
    FROM public.inventory_publication_intent blocked
    WHERE blocked.inventory_id = intent.inventory_id
      AND blocked.state = 'BLOCKED'
      AND blocked.blocked_failure_code = 'SOURCE_PRECONDITION_CONFLICT'
      AND blocked.effective_asset_version IS NOT NULL
      AND blocked.asset_outcome_result IS NOT NULL
      AND blocked.maintenance_result IS NULL
  );
