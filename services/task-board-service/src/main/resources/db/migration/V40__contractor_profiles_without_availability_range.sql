-- Contractor profiles are an on-demand address book; an exact planning day belongs to assignments.

DROP INDEX IF EXISTS public.idx_worker_contractor_availability;

ALTER TABLE public.worker
  DROP CONSTRAINT ck_worker_contractor_profile,
  DROP COLUMN contract_available_from,
  DROP COLUMN contract_available_until;

ALTER TABLE public.worker
  ADD CONSTRAINT ck_worker_contractor_profile
    CHECK (
      (employment_type = 'STAFF' AND phone IS NULL)
      OR
      (employment_type = 'CONTRACTOR'
        AND phone IS NOT NULL
        AND length(btrim(phone)) BETWEEN 1 AND 64)
    );

CREATE INDEX idx_worker_active_contractor
  ON public.worker(warehouse_id, display_name, id)
  WHERE employment_type = 'CONTRACTOR' AND active = true;
