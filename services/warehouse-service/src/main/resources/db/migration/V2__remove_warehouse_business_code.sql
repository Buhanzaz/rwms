-- Warehouse identity is the UUID primary key.  V1 remains immutable evidence;
-- this migration contracts the live schema and sanitizes durable replay bodies.

ALTER TABLE public.outbox_event DISABLE TRIGGER trg_outbox_event_immutable;

WITH sanitized AS (
  SELECT event_id, envelope_body #- '{payload,code}' AS envelope_body
  FROM public.outbox_event
  WHERE envelope_body #> '{payload,code}' IS NOT NULL
)
UPDATE public.outbox_event AS event
SET envelope_body = sanitized.envelope_body,
    envelope_sha256 = encode(
      sha256(convert_to(sanitized.envelope_body::text, 'UTF8')),
      'hex')
FROM sanitized
WHERE event.event_id = sanitized.event_id;

ALTER TABLE public.outbox_event ENABLE TRIGGER trg_outbox_event_immutable;

UPDATE public.idempotency_record
SET response_body = response_body - 'code'
WHERE response_body ? 'code';

ALTER TABLE public.warehouse
  DROP CONSTRAINT IF EXISTS uk_warehouse_code,
  DROP CONSTRAINT IF EXISTS ck_warehouse_code;

DROP INDEX IF EXISTS public.idx_warehouse_active_order;

ALTER TABLE public.warehouse
  DROP COLUMN code;

CREATE INDEX idx_warehouse_active_order
  ON public.warehouse(active, sort_order, name, id);
