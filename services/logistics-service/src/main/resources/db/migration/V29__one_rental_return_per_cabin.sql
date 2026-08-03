-- A rental shipment can contain several cabins.  Each cabin needs an
-- independently schedulable return task, so the link to the originating
-- shipment is no longer unique at document level.
DROP INDEX IF EXISTS public.uk_logistics_document_return_shipment;

CREATE INDEX idx_logistics_document_return_shipment
    ON public.logistics_document (rental_shipment_id, created_at, id)
    WHERE document_type = 'RETURN' AND rental_shipment_id IS NOT NULL;
