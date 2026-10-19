-- Historical rental import owns an explicit audit identity; it never reuses worker evidence.
ALTER TABLE public.maintenance_repair
  ADD COLUMN historical_shipment_document_id uuid,
  ADD COLUMN historical_shipment_closure_state varchar(24);

ALTER TABLE public.maintenance_repair
  ADD CONSTRAINT ck_maintenance_repair_historical_shipment_closure CHECK (
    (historical_shipment_document_id IS NULL AND historical_shipment_closure_state IS NULL)
    OR (
      historical_shipment_document_id IS NOT NULL
      AND historical_shipment_closure_state IN ('CLOSING', 'CLOSED')
    )
  );

CREATE INDEX idx_maintenance_repair_historical_shipment
  ON public.maintenance_repair(historical_shipment_document_id)
  WHERE historical_shipment_document_id IS NOT NULL;
