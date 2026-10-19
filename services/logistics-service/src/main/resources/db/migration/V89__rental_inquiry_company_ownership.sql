-- Adopt every existing inquiry from its required company-owned client. This remains correct for
-- inquiries created after V88 for non-bootstrap companies and does not synthesize a fallback.

ALTER TABLE public.rental_inquiry
    ADD COLUMN company_id uuid;

UPDATE public.rental_inquiry inquiry
SET company_id = client.company_id
FROM public.order_client client
WHERE client.id = inquiry.client_id
  AND inquiry.company_id IS NULL;

DO $$
BEGIN
    IF EXISTS (
        SELECT 1
        FROM public.rental_inquiry inquiry
        LEFT JOIN public.order_client client
            ON client.id = inquiry.client_id
        LEFT JOIN public.rental_order target_order
            ON target_order.id = inquiry.rental_order_id
        LEFT JOIN public.rental_order booked_order
            ON booked_order.id = inquiry.booked_order_id
        WHERE inquiry.company_id IS NULL
           OR client.company_id IS DISTINCT FROM inquiry.company_id
           OR (
               inquiry.rental_order_id IS NOT NULL
               AND (
                   target_order.id IS NULL
                   OR target_order.company_id IS DISTINCT FROM inquiry.company_id
               )
           )
           OR (
               inquiry.booked_order_id IS NOT NULL
               AND (
                   booked_order.id IS NULL
                   OR booked_order.company_id IS DISTINCT FROM inquiry.company_id
               )
           )
    ) THEN
        RAISE EXCEPTION
            'Cannot assign rental inquiry company from inconsistent client/order lineage';
    END IF;
END
$$;

DROP INDEX public.uk_rental_inquiry_conversation;
DROP INDEX public.idx_rental_inquiry_manager_state;
DROP INDEX public.idx_rental_inquiry_rental_order;

ALTER TABLE public.rental_inquiry
    ALTER COLUMN company_id SET NOT NULL,
    DROP CONSTRAINT uk_rental_inquiry_creation_key,
    DROP CONSTRAINT fk_rental_inquiry_client,
    DROP CONSTRAINT fk_rental_inquiry_rental_order,
    ADD CONSTRAINT uk_rental_inquiry_creation_key
        UNIQUE (company_id, manager_id, creation_idempotency_key),
    ADD CONSTRAINT uk_rental_inquiry_company_id UNIQUE (company_id, id),
    ADD CONSTRAINT fk_rental_inquiry_client
        FOREIGN KEY (company_id, client_id)
        REFERENCES public.order_client (company_id, id),
    ADD CONSTRAINT fk_rental_inquiry_rental_order
        FOREIGN KEY (company_id, rental_order_id)
        REFERENCES public.rental_order (company_id, id),
    ADD CONSTRAINT fk_rental_inquiry_booked_order
        FOREIGN KEY (company_id, booked_order_id)
        REFERENCES public.rental_order (company_id, id);

CREATE UNIQUE INDEX uk_rental_inquiry_conversation
    ON public.rental_inquiry (company_id, conversation_id)
    WHERE conversation_id IS NOT NULL;

CREATE INDEX idx_rental_inquiry_manager_state
    ON public.rental_inquiry
        (company_id, manager_id, state, updated_at DESC, id);

CREATE INDEX idx_rental_inquiry_rental_order
    ON public.rental_inquiry
        (company_id, rental_order_id, state, created_at DESC, id)
    WHERE rental_order_id IS NOT NULL;

CREATE OR REPLACE FUNCTION public.prevent_rental_inquiry_company_change()
RETURNS trigger
LANGUAGE plpgsql
AS $$
BEGIN
    IF NEW.company_id IS DISTINCT FROM OLD.company_id THEN
        RAISE EXCEPTION 'rental inquiry company ownership is immutable'
            USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END
$$;

CREATE TRIGGER prevent_rental_inquiry_company_change
BEFORE UPDATE OF company_id ON public.rental_inquiry
FOR EACH ROW EXECUTE FUNCTION public.prevent_rental_inquiry_company_change();

-- Carry the immutable company snapshot through the inquiry-owned receipt/recovery graph. These
-- columns are not independent tenancy sources: every value is derived from its already fenced
-- parent before any constraint is replaced.

ALTER TABLE public.rental_inquiry_search_attempt
    ADD COLUMN company_id uuid;

ALTER TABLE public.rental_inquiry_selection_receipt
    ADD COLUMN company_id uuid,
    ADD COLUMN downstream_idempotency_key uuid;

ALTER TABLE public.client_presentation
    ADD COLUMN company_id uuid;

ALTER TABLE public.client_presentation_item
    ADD COLUMN company_id uuid;

ALTER TABLE public.presentation_booking
    ADD COLUMN company_id uuid;

ALTER TABLE public.rental_inquiry_outbox
    ADD COLUMN company_id uuid;

UPDATE public.rental_inquiry_search_attempt child
SET company_id = parent.company_id
FROM public.rental_inquiry parent
WHERE parent.id = child.inquiry_id
  AND child.company_id IS NULL;

UPDATE public.rental_inquiry_selection_receipt child
SET company_id = parent.company_id,
    downstream_idempotency_key = child.public_idempotency_key
FROM public.rental_inquiry parent
WHERE parent.id = child.inquiry_id
  AND (child.company_id IS NULL OR child.downstream_idempotency_key IS NULL);

UPDATE public.client_presentation child
SET company_id = parent.company_id
FROM public.rental_inquiry parent
WHERE parent.id = child.inquiry_id
  AND child.company_id IS NULL;

UPDATE public.client_presentation_item child
SET company_id = parent.company_id
FROM public.client_presentation parent
WHERE parent.id = child.presentation_id
  AND child.company_id IS NULL;

UPDATE public.presentation_booking child
SET company_id = parent.company_id
FROM public.client_presentation parent
WHERE parent.id = child.presentation_id
  AND child.company_id IS NULL;

UPDATE public.rental_inquiry_outbox child
SET company_id = parent.company_id
FROM public.rental_inquiry parent
WHERE parent.id = child.inquiry_id
  AND child.company_id IS NULL;

DO $$
BEGIN
    IF EXISTS (
        SELECT 1
        FROM public.rental_inquiry_search_attempt child
        LEFT JOIN public.rental_inquiry parent ON parent.id = child.inquiry_id
        WHERE child.company_id IS NULL
           OR parent.company_id IS DISTINCT FROM child.company_id
    ) OR EXISTS (
        SELECT 1
        FROM public.rental_inquiry_selection_receipt child
        LEFT JOIN public.rental_inquiry parent ON parent.id = child.inquiry_id
        WHERE child.company_id IS NULL
           OR child.downstream_idempotency_key IS NULL
           OR parent.company_id IS DISTINCT FROM child.company_id
    ) OR EXISTS (
        SELECT 1
        FROM public.client_presentation child
        LEFT JOIN public.rental_inquiry parent ON parent.id = child.inquiry_id
        LEFT JOIN public.rental_order booked_order ON booked_order.id = child.booked_order_id
        WHERE child.company_id IS NULL
           OR parent.company_id IS DISTINCT FROM child.company_id
           OR (
               child.booked_order_id IS NOT NULL
               AND (
                   booked_order.id IS NULL
                   OR booked_order.company_id IS DISTINCT FROM child.company_id
               )
           )
    ) OR EXISTS (
        SELECT 1
        FROM public.client_presentation_item child
        LEFT JOIN public.client_presentation parent ON parent.id = child.presentation_id
        WHERE child.company_id IS NULL
           OR parent.company_id IS DISTINCT FROM child.company_id
    ) OR EXISTS (
        SELECT 1
        FROM public.presentation_booking child
        LEFT JOIN public.client_presentation parent ON parent.id = child.presentation_id
        LEFT JOIN public.rental_order booked_order ON booked_order.id = child.order_id
        WHERE child.company_id IS NULL
           OR parent.company_id IS DISTINCT FROM child.company_id
           OR (
               child.order_id IS NOT NULL
               AND (
                   booked_order.id IS NULL
                   OR booked_order.company_id IS DISTINCT FROM child.company_id
               )
           )
    ) OR EXISTS (
        SELECT 1
        FROM public.rental_inquiry_outbox child
        LEFT JOIN public.rental_inquiry parent ON parent.id = child.inquiry_id
        LEFT JOIN public.rental_order booked_order ON booked_order.id = child.order_id
        WHERE child.company_id IS NULL
           OR parent.company_id IS DISTINCT FROM child.company_id
           OR booked_order.id IS NULL
           OR booked_order.company_id IS DISTINCT FROM child.company_id
    ) THEN
        RAISE EXCEPTION
            'Cannot assign company to inconsistent rental inquiry child lineage';
    END IF;
END
$$;

DROP INDEX public.uk_rental_inquiry_search_attempt_one_prepared;
DROP INDEX public.idx_rental_inquiry_search_attempt_inquiry_created;
DROP INDEX public.uq_rental_inquiry_selection_receipt_prepared;
DROP INDEX public.idx_rental_inquiry_selection_receipt_inquiry;
DROP INDEX public.idx_client_presentation_item_current;
DROP INDEX public.idx_presentation_booking_manager_alert;

ALTER TABLE public.rental_inquiry_search_attempt
    ALTER COLUMN company_id SET NOT NULL,
    DROP CONSTRAINT fk_rental_inquiry_search_attempt_inquiry,
    DROP CONSTRAINT uk_rental_inquiry_search_attempt_subject_operation_key,
    ADD CONSTRAINT fk_rental_inquiry_search_attempt_inquiry
        FOREIGN KEY (company_id, inquiry_id)
        REFERENCES public.rental_inquiry (company_id, id),
    ADD CONSTRAINT uk_rental_inquiry_search_attempt_subject_operation_key
        UNIQUE (company_id, subject_id, operation_name, public_idempotency_key);

ALTER TABLE public.rental_inquiry_selection_receipt
    ALTER COLUMN company_id SET NOT NULL,
    ALTER COLUMN downstream_idempotency_key SET NOT NULL,
    DROP CONSTRAINT fk_rental_inquiry_selection_receipt_inquiry,
    DROP CONSTRAINT uq_rental_inquiry_selection_receipt_subject_key,
    ADD CONSTRAINT fk_rental_inquiry_selection_receipt_inquiry
        FOREIGN KEY (company_id, inquiry_id)
        REFERENCES public.rental_inquiry (company_id, id),
    ADD CONSTRAINT uq_rental_inquiry_selection_receipt_subject_key
        UNIQUE (company_id, subject_id, public_idempotency_key),
    ADD CONSTRAINT uk_rental_inquiry_selection_receipt_downstream_key
        UNIQUE (downstream_idempotency_key);

ALTER TABLE public.client_presentation
    ALTER COLUMN company_id SET NOT NULL,
    DROP CONSTRAINT uk_client_presentation_inquiry,
    DROP CONSTRAINT fk_client_presentation_inquiry,
    ADD CONSTRAINT uk_client_presentation_inquiry UNIQUE (company_id, inquiry_id),
    ADD CONSTRAINT uk_client_presentation_company_id UNIQUE (company_id, id),
    ADD CONSTRAINT fk_client_presentation_inquiry
        FOREIGN KEY (company_id, inquiry_id)
        REFERENCES public.rental_inquiry (company_id, id),
    ADD CONSTRAINT fk_client_presentation_booked_order
        FOREIGN KEY (company_id, booked_order_id)
        REFERENCES public.rental_order (company_id, id);

ALTER TABLE public.client_presentation_item
    ALTER COLUMN company_id SET NOT NULL,
    DROP CONSTRAINT fk_client_presentation_item_presentation,
    DROP CONSTRAINT uk_client_presentation_item_revision_cabin,
    DROP CONSTRAINT uk_client_presentation_item_revision_order,
    ADD CONSTRAINT fk_client_presentation_item_presentation
        FOREIGN KEY (company_id, presentation_id)
        REFERENCES public.client_presentation (company_id, id),
    ADD CONSTRAINT uk_client_presentation_item_revision_cabin
        UNIQUE (company_id, presentation_id, presentation_revision, rental_item_id),
    ADD CONSTRAINT uk_client_presentation_item_revision_order
        UNIQUE (company_id, presentation_id, presentation_revision, sort_order);

ALTER TABLE public.presentation_booking
    ALTER COLUMN company_id SET NOT NULL,
    DROP CONSTRAINT fk_presentation_booking_presentation,
    DROP CONSTRAINT uk_presentation_booking_idempotency,
    DROP CONSTRAINT uk_presentation_booking_revision,
    ADD CONSTRAINT fk_presentation_booking_presentation
        FOREIGN KEY (company_id, presentation_id)
        REFERENCES public.client_presentation (company_id, id),
    ADD CONSTRAINT fk_presentation_booking_order
        FOREIGN KEY (company_id, order_id)
        REFERENCES public.rental_order (company_id, id),
    ADD CONSTRAINT uk_presentation_booking_idempotency
        UNIQUE (company_id, presentation_id, idempotency_key),
    ADD CONSTRAINT uk_presentation_booking_revision
        UNIQUE (company_id, presentation_id, presentation_revision),
    ADD CONSTRAINT uk_presentation_booking_company_id UNIQUE (company_id, id);

ALTER TABLE public.rental_inquiry_outbox
    ALTER COLUMN company_id SET NOT NULL,
    DROP CONSTRAINT uk_rental_inquiry_outbox_inquiry,
    ADD CONSTRAINT uk_rental_inquiry_outbox_inquiry UNIQUE (company_id, inquiry_id),
    ADD CONSTRAINT fk_rental_inquiry_outbox_inquiry
        FOREIGN KEY (company_id, inquiry_id)
        REFERENCES public.rental_inquiry (company_id, id),
    ADD CONSTRAINT fk_rental_inquiry_outbox_order
        FOREIGN KEY (company_id, order_id)
        REFERENCES public.rental_order (company_id, id);

CREATE UNIQUE INDEX uk_rental_inquiry_search_attempt_one_prepared
    ON public.rental_inquiry_search_attempt (company_id, inquiry_id)
    WHERE state = 'PREPARED';

CREATE INDEX idx_rental_inquiry_search_attempt_inquiry_created
    ON public.rental_inquiry_search_attempt
        (company_id, inquiry_id, created_at DESC, id);

CREATE UNIQUE INDEX uq_rental_inquiry_selection_receipt_prepared
    ON public.rental_inquiry_selection_receipt (company_id, inquiry_id)
    WHERE state = 'PREPARED';

CREATE INDEX idx_rental_inquiry_selection_receipt_inquiry
    ON public.rental_inquiry_selection_receipt
        (company_id, inquiry_id, created_at DESC, id);

CREATE INDEX idx_client_presentation_item_current
    ON public.client_presentation_item
        (company_id, presentation_id, presentation_revision, sort_order, id);

CREATE INDEX idx_presentation_booking_manager_alert
    ON public.presentation_booking (company_id, completed_at ASC, id ASC)
    WHERE state = 'COMPLETED' AND manager_action IS NULL;

CREATE OR REPLACE FUNCTION public.prevent_inquiry_child_company_change()
RETURNS trigger
LANGUAGE plpgsql
AS $$
BEGIN
    IF NEW.company_id IS DISTINCT FROM OLD.company_id THEN
        RAISE EXCEPTION '% company ownership is immutable', TG_TABLE_NAME
            USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END
$$;

CREATE TRIGGER prevent_rental_inquiry_search_attempt_company_change
BEFORE UPDATE OF company_id ON public.rental_inquiry_search_attempt
FOR EACH ROW EXECUTE FUNCTION public.prevent_inquiry_child_company_change();

CREATE TRIGGER prevent_rental_inquiry_selection_receipt_company_change
BEFORE UPDATE OF company_id ON public.rental_inquiry_selection_receipt
FOR EACH ROW EXECUTE FUNCTION public.prevent_inquiry_child_company_change();

CREATE TRIGGER prevent_client_presentation_company_change
BEFORE UPDATE OF company_id ON public.client_presentation
FOR EACH ROW EXECUTE FUNCTION public.prevent_inquiry_child_company_change();

CREATE TRIGGER prevent_client_presentation_item_company_change
BEFORE UPDATE OF company_id ON public.client_presentation_item
FOR EACH ROW EXECUTE FUNCTION public.prevent_inquiry_child_company_change();

CREATE TRIGGER prevent_presentation_booking_company_change
BEFORE UPDATE OF company_id ON public.presentation_booking
FOR EACH ROW EXECUTE FUNCTION public.prevent_inquiry_child_company_change();

CREATE TRIGGER prevent_rental_inquiry_outbox_company_change
BEFORE UPDATE OF company_id ON public.rental_inquiry_outbox
FOR EACH ROW EXECUTE FUNCTION public.prevent_inquiry_child_company_change();
