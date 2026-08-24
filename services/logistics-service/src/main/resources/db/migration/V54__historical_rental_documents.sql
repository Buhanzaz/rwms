-- User-entered migration facts remain normal logistics documents and therefore retain the
-- canonical fenced asset workflows. The flag only records that the former physical movement had
-- no RWMS driver task and must never be offered back to driver planning.

ALTER TABLE public.logistics_document
    ADD COLUMN historical_rental_import boolean NOT NULL DEFAULT false;

CREATE INDEX idx_logistics_document_historical_rental_import
    ON public.logistics_document(warehouse_id, document_type, scheduled_date DESC, id DESC)
    WHERE historical_rental_import;
