ALTER TABLE public.logistics_document
  ADD COLUMN return_arrived_at timestamptz;

UPDATE public.logistics_document document
SET return_arrived_at = arrival.arrived_at
FROM (
  SELECT aggregate_id, max(occurred_at) AS arrived_at
  FROM public.domain_event
  WHERE aggregate_type = 'RETURN'
    AND event_type = 'logistics.return.inspection-required.v1'
    AND occurred_at IS NOT NULL
  GROUP BY aggregate_id
) arrival
WHERE document.document_type = 'RETURN'
  AND document.id::text = arrival.aggregate_id;

ALTER TABLE public.logistics_document
  ADD CONSTRAINT ck_logistics_document_return_arrival
  CHECK (return_arrived_at IS NULL OR document_type = 'RETURN');

CREATE INDEX ix_logistics_document_return_arrival
  ON public.logistics_document (warehouse_id, return_arrived_at DESC, id DESC)
  WHERE document_type = 'RETURN' AND return_arrived_at IS NOT NULL;

COMMENT ON COLUMN public.logistics_document.return_arrived_at IS
  'Immutable physical intake time for a normal return; inventory historical returns bypass it';
