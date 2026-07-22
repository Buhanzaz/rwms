-- Client and rental-order references are opaque service-local UUIDs.  The
-- canonical client/order aggregates live in this same service; keeping the
-- IDs as values avoids an accidental aggregate relationship on logistics
-- documents while preserving the evidence used for a return or shipment.
ALTER TABLE logistics_document
  ADD COLUMN client_id uuid;

ALTER TABLE logistics_document_line
  ADD COLUMN rental_order_id uuid,
  ADD COLUMN return_additional_contents_snapshot jsonb;

CREATE INDEX idx_logistics_document_client
  ON logistics_document(client_id)
  WHERE client_id IS NOT NULL;

CREATE INDEX idx_logistics_document_line_rental_order
  ON logistics_document_line(rental_order_id)
  WHERE rental_order_id IS NOT NULL;
