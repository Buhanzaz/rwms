ALTER TABLE public.logistics_document
    DROP CONSTRAINT ck_logistics_document_schedule_scope;

-- A transfer used to keep a user-entered timestamp. The calendar date is the
-- only business value now, so preserve it and make timestamp persistence
-- impossible for every logistics document type.
UPDATE public.logistics_document
SET scheduled_date = COALESCE(scheduled_date, (scheduled_at AT TIME ZONE 'UTC')::date),
    scheduled_at = NULL
WHERE scheduled_at IS NOT NULL;

ALTER TABLE public.logistics_document
    ADD CONSTRAINT ck_logistics_document_schedule_scope CHECK (
        (scheduled_date IS NULL
         OR document_type IN ('SHIPMENT', 'RETURN', 'TRANSFER'))
        AND scheduled_at IS NULL
    );
