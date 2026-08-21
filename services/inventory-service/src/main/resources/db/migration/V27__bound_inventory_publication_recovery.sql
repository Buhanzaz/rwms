-- Bound automatic owner delivery per explicit outcome-reapplication generation. The lifetime
-- attempt counter remains append-only audit evidence while this generation-local counter can be
-- reset only by the explicit completed-history recalculation command.
ALTER TABLE public.inventory_publication_intent
  ADD COLUMN generation_attempt_count integer NOT NULL DEFAULT 0,
  ADD COLUMN next_attempt_at timestamptz NOT NULL DEFAULT clock_timestamp(),
  ADD CONSTRAINT ck_inventory_publication_generation_attempt_count CHECK (
    generation_attempt_count >= 0
    AND generation_attempt_count <= attempt_count
  );

-- READY and transient rows are selected only when due. The original dispatch index remains the
-- recovery path for abandoned PENDING claims.
CREATE INDEX ix_inventory_publication_due
  ON public.inventory_publication_intent (state, next_attempt_at, id)
  WHERE state IN ('READY', 'TRANSIENT_FAILED');
