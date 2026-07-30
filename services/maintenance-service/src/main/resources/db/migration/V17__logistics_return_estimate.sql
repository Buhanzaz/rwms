-- Each newly accepted immutable logistics return-line shortage owns one maintenance estimate.
-- The NOT VALID check is an expand/contract guard: it enforces the invariant for every new or
-- changed row without making an upgrade fail if an older environment still has a pre-V17 source
-- that must be reconciled explicitly.
ALTER TABLE public.logistics_return_shortage
  ADD COLUMN estimate_id uuid,
  ADD CONSTRAINT uk_logistics_return_shortage_estimate UNIQUE (estimate_id),
  ADD CONSTRAINT fk_logistics_return_shortage_estimate
    FOREIGN KEY (estimate_id) REFERENCES public.maintenance_estimate(id),
  ADD CONSTRAINT ck_logistics_return_shortage_estimate_required
    CHECK (estimate_id IS NOT NULL) NOT VALID;

COMMENT ON COLUMN public.logistics_return_shortage.estimate_id IS
  'The sole DRAFT maintenance estimate atomically created for this immutable return line source.';
