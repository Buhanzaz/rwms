-- A recovery worker holds only a short-lived, fenced capability. Existing attempts begin unleased
-- (fence zero), so the first V40 claimant obtains fence one without changing request identity or
-- retry history.
ALTER TABLE public.logistics_external_attempt
  ADD COLUMN lease_token uuid,
  ADD COLUMN lease_fence bigint NOT NULL DEFAULT 0,
  ADD COLUMN lease_expires_at timestamptz,
  ADD CONSTRAINT ck_logistics_external_attempt_lease_fence CHECK (lease_fence >= 0),
  ADD CONSTRAINT ck_logistics_external_attempt_lease_pair CHECK (
    (lease_token IS NULL AND lease_expires_at IS NULL)
    OR (lease_token IS NOT NULL AND lease_expires_at IS NOT NULL)
  );

-- The ordinary due path is ordered by its next eligible time, creation time and UUID tie-breaker.
-- It is partial because completed/reconciled rows can never be claimed again.
CREATE INDEX idx_logistics_external_attempt_due_claim
  ON public.logistics_external_attempt (
    operation_type,
    (COALESCE(next_attempt_at, created_at)),
    created_at,
    id
  )
  WHERE result IN ('PENDING', 'RETRY')
    AND lease_expires_at IS NULL;

-- Expired leases are indexed separately so recovery does not starve behind a growing set of live
-- leased rows. The same stable ordering is retained after the expiry predicate narrows the page.
CREATE INDEX idx_logistics_external_attempt_expired_lease_claim
  ON public.logistics_external_attempt (
    operation_type,
    lease_expires_at,
    (COALESCE(next_attempt_at, created_at)),
    created_at,
    id
  )
  WHERE result IN ('PENDING', 'RETRY')
    AND lease_expires_at IS NOT NULL;
