-- Automatic delivery retries stay in generation zero until an explicit history recovery
-- advances the completed plan. Existing publications are backfilled without changing audit rows.
ALTER TABLE public.inventory_publication_intent
  ADD COLUMN outcome_reapplication_no bigint;

UPDATE public.inventory_publication_intent
SET outcome_reapplication_no = 0
WHERE outcome_reapplication_no IS NULL;

ALTER TABLE public.inventory_publication_intent
  ALTER COLUMN outcome_reapplication_no SET DEFAULT 0,
  ALTER COLUMN outcome_reapplication_no SET NOT NULL,
  ADD CONSTRAINT ck_inventory_publication_outcome_reapplication_no CHECK (
    outcome_reapplication_no >= 0
  );

-- The asset owner no longer uses the full live-snapshot equality check that made this exact
-- completed-session conflict circular. Requeue only that obsolete terminal failure; all
-- request/snapshot evidence, attempt counters and append-only history remain untouched.
UPDATE public.inventory_furniture_reconciliation_intent intent
SET state = 'PENDING',
    intent_revision = intent.intent_revision + 1,
    failure_code = NULL,
    next_attempt_at = clock_timestamp(),
    completed_at = NULL,
    updated_at = clock_timestamp()
FROM public.inventory_session session
WHERE session.id = intent.inventory_id
  AND session.lifecycle = 'COMPLETED'
  AND intent.state = 'BLOCKED'
  AND intent.failure_code = 'ASSET_SNAPSHOT_CONFLICT';
