ALTER TABLE public.logistics_document
    ADD COLUMN scheduled_date date;

-- Existing return and shipment timestamps represented a calendar appointment.
-- Preserve that UTC calendar date and discard the no-longer-authoritative time.
UPDATE public.logistics_document
SET scheduled_date = (scheduled_at AT TIME ZONE 'UTC')::date,
    scheduled_at = NULL
WHERE document_type IN ('RETURN', 'SHIPMENT')
  AND scheduled_at IS NOT NULL;

ALTER TABLE public.logistics_document
    DROP CONSTRAINT ck_logistics_document_schedule_scope;

ALTER TABLE public.logistics_document
    ADD CONSTRAINT ck_logistics_document_schedule_scope CHECK (
        (scheduled_date IS NULL OR document_type IN ('SHIPMENT', 'RETURN'))
        AND (scheduled_at IS NULL OR document_type = 'TRANSFER')
    );

CREATE INDEX idx_logistics_document_schedule_date
    ON public.logistics_document (warehouse_id, document_type, scheduled_date, id);
