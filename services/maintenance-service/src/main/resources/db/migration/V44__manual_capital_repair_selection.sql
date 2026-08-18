-- Persist the explicit manager choice independently from catalog WORK flags. Existing estimates,
-- revisions and repairs retain their previous threshold/catalog-derived classification.
ALTER TABLE public.maintenance_estimate
  ADD COLUMN force_capital_repair boolean NOT NULL DEFAULT false;

ALTER TABLE public.estimate_revision
  ADD COLUMN force_capital_repair boolean NOT NULL DEFAULT false;

ALTER TABLE public.maintenance_repair
  ADD COLUMN force_capital_repair boolean NOT NULL DEFAULT false;
