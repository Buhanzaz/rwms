-- Representative is an additive warehouse characteristic and does not alter lifecycle admission.
ALTER TABLE public.warehouse
  ADD COLUMN representative boolean NOT NULL DEFAULT false;

-- Durable create replays must satisfy the current public response contract after the upgrade.
UPDATE public.idempotency_record AS record
SET response_body = record.response_body
  || jsonb_build_object('representative', warehouse.representative)
FROM public.warehouse AS warehouse
WHERE warehouse.id = record.warehouse_id
  AND NOT (record.response_body ? 'representative');
