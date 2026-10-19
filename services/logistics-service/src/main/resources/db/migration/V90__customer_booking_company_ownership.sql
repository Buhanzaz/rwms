-- Company ownership for the CustomerApp graph is derived exclusively from already fenced
-- logistics parents. The migration intentionally has no bootstrap-company fallback.

ALTER TABLE public.customer_profile
    ADD COLUMN company_id uuid;

ALTER TABLE public.customer_rental_session
    ADD COLUMN company_id uuid;

ALTER TABLE public.customer_delivery_slot
    ADD COLUMN company_id uuid;

ALTER TABLE public.customer_cabin_acceptance
    ADD COLUMN company_id uuid;

ALTER TABLE public.customer_cabin_problem
    ADD COLUMN company_id uuid;

UPDATE public.customer_profile child
SET company_id = parent.company_id
FROM public.order_client parent
WHERE parent.id = child.client_id
  AND child.company_id IS NULL;

UPDATE public.customer_rental_session child
SET company_id = parent.company_id
FROM public.rental_inquiry parent
WHERE parent.id = child.inquiry_id
  AND child.company_id IS NULL;

UPDATE public.customer_delivery_slot child
SET company_id = parent.company_id
FROM public.rental_inquiry parent
WHERE parent.id = child.inquiry_id
  AND child.company_id IS NULL;

UPDATE public.customer_cabin_acceptance child
SET company_id = parent.company_id
FROM public.rental_order parent
WHERE parent.id = child.order_id
  AND child.company_id IS NULL;

UPDATE public.customer_cabin_problem child
SET company_id = parent.company_id
FROM public.rental_order parent
WHERE parent.id = child.order_id
  AND child.company_id IS NULL;

DO $$
BEGIN
    IF EXISTS (
        SELECT 1
        FROM public.customer_profile child
        LEFT JOIN public.order_client parent ON parent.id = child.client_id
        WHERE child.company_id IS NULL
           OR parent.company_id IS DISTINCT FROM child.company_id
    ) OR EXISTS (
        SELECT 1
        FROM public.customer_rental_session child
        LEFT JOIN public.rental_inquiry inquiry ON inquiry.id = child.inquiry_id
        LEFT JOIN public.rental_order orders ON orders.id = child.order_id
        LEFT JOIN public.presentation_booking booking ON booking.id = child.booking_id
        LEFT JOIN public.customer_delivery_slot slot ON slot.id = child.delivery_slot_id
        WHERE child.company_id IS NULL
           OR inquiry.company_id IS DISTINCT FROM child.company_id
           OR (
               child.order_id IS NOT NULL
               AND (orders.id IS NULL OR orders.company_id IS DISTINCT FROM child.company_id)
           )
           OR (
               child.booking_id IS NOT NULL
               AND (booking.id IS NULL OR booking.company_id IS DISTINCT FROM child.company_id)
           )
           OR (
               child.delivery_slot_id IS NOT NULL
               AND (slot.id IS NULL OR slot.company_id IS DISTINCT FROM child.company_id)
           )
    ) OR EXISTS (
        SELECT 1
        FROM public.customer_delivery_slot child
        LEFT JOIN public.rental_inquiry inquiry ON inquiry.id = child.inquiry_id
        LEFT JOIN public.rental_order orders ON orders.id = child.order_id
        WHERE child.company_id IS NULL
           OR inquiry.company_id IS DISTINCT FROM child.company_id
           OR (
               child.order_id IS NOT NULL
               AND (orders.id IS NULL OR orders.company_id IS DISTINCT FROM child.company_id)
           )
    ) OR EXISTS (
        SELECT 1
        FROM public.customer_cabin_acceptance child
        LEFT JOIN public.rental_order orders ON orders.id = child.order_id
        LEFT JOIN public.presentation_booking booking ON booking.id = child.booking_id
        WHERE child.company_id IS NULL
           OR orders.company_id IS DISTINCT FROM child.company_id
           OR booking.company_id IS DISTINCT FROM child.company_id
    ) OR EXISTS (
        SELECT 1
        FROM public.customer_cabin_problem child
        LEFT JOIN public.rental_order orders ON orders.id = child.order_id
        LEFT JOIN public.presentation_booking booking ON booking.id = child.booking_id
        WHERE child.company_id IS NULL
           OR orders.company_id IS DISTINCT FROM child.company_id
           OR booking.company_id IS DISTINCT FROM child.company_id
    ) OR EXISTS (
        SELECT 1
        FROM public.customer_booking_mutation child
        LEFT JOIN public.rental_inquiry inquiry ON inquiry.id = child.inquiry_id
        LEFT JOIN public.rental_order orders ON orders.id = child.order_id
        LEFT JOIN public.presentation_booking booking ON booking.id = child.booking_id
        LEFT JOIN public.customer_delivery_slot old_slot ON old_slot.id = child.old_slot_id
        LEFT JOIN public.customer_delivery_slot new_slot ON new_slot.id = child.new_slot_id
        WHERE inquiry.company_id IS DISTINCT FROM child.company_id
           OR orders.company_id IS DISTINCT FROM child.company_id
           OR booking.company_id IS DISTINCT FROM child.company_id
           OR old_slot.company_id IS DISTINCT FROM child.company_id
           OR (
               child.new_slot_id IS NOT NULL
               AND new_slot.company_id IS DISTINCT FROM child.company_id
           )
    ) THEN
        RAISE EXCEPTION 'Cannot assign CustomerApp company from inconsistent parent lineage';
    END IF;
END
$$;

DROP INDEX public.idx_customer_rental_session_subject_created;
DROP INDEX public.idx_customer_delivery_slot_capacity;
DROP INDEX public.idx_customer_delivery_slot_inquiry;
DROP INDEX public.uk_customer_delivery_slot_confirmed_order;
DROP INDEX public.uk_customer_delivery_slot_booking;
DROP INDEX public.idx_customer_cabin_acceptance_booking;
DROP INDEX public.idx_customer_cabin_problem_booking;
DROP INDEX public.uk_customer_booking_mutation_open_booking;
DROP INDEX public.idx_customer_booking_mutation_open_order;
DROP INDEX public.idx_customer_booking_mutation_due;
DROP INDEX public.idx_customer_booking_mutation_booking_history;

ALTER TABLE public.customer_profile
    ALTER COLUMN company_id SET NOT NULL,
    DROP CONSTRAINT uk_customer_profile_auth_subject,
    DROP CONSTRAINT uk_customer_profile_client,
    DROP CONSTRAINT fk_customer_profile_client,
    ADD CONSTRAINT uk_customer_profile_company_auth_subject
        UNIQUE (company_id, auth_subject_id),
    ADD CONSTRAINT uk_customer_profile_company_client
        UNIQUE (company_id, client_id),
    ADD CONSTRAINT fk_customer_profile_client
        FOREIGN KEY (company_id, client_id)
        REFERENCES public.order_client (company_id, id);

ALTER TABLE public.customer_delivery_slot
    ALTER COLUMN company_id SET NOT NULL,
    DROP CONSTRAINT fk_customer_delivery_slot_inquiry,
    ADD CONSTRAINT uk_customer_delivery_slot_company_id UNIQUE (company_id, id),
    ADD CONSTRAINT fk_customer_delivery_slot_inquiry
        FOREIGN KEY (company_id, inquiry_id)
        REFERENCES public.rental_inquiry (company_id, id),
    ADD CONSTRAINT fk_customer_delivery_slot_order
        FOREIGN KEY (company_id, order_id)
        REFERENCES public.rental_order (company_id, id);

ALTER TABLE public.customer_rental_session
    ALTER COLUMN company_id SET NOT NULL,
    DROP CONSTRAINT uk_customer_rental_session_inquiry,
    DROP CONSTRAINT fk_customer_rental_session_inquiry,
    ADD CONSTRAINT uk_customer_rental_session_company_inquiry
        UNIQUE (company_id, inquiry_id),
    ADD CONSTRAINT uk_customer_rental_session_company_id
        UNIQUE (company_id, id),
    ADD CONSTRAINT fk_customer_rental_session_inquiry
        FOREIGN KEY (company_id, inquiry_id)
        REFERENCES public.rental_inquiry (company_id, id),
    ADD CONSTRAINT fk_customer_rental_session_delivery_slot
        FOREIGN KEY (company_id, delivery_slot_id)
        REFERENCES public.customer_delivery_slot (company_id, id),
    ADD CONSTRAINT fk_customer_rental_session_booking
        FOREIGN KEY (company_id, booking_id)
        REFERENCES public.presentation_booking (company_id, id),
    ADD CONSTRAINT fk_customer_rental_session_order
        FOREIGN KEY (company_id, order_id)
        REFERENCES public.rental_order (company_id, id);

ALTER TABLE public.customer_cabin_acceptance
    ALTER COLUMN company_id SET NOT NULL,
    DROP CONSTRAINT fk_customer_cabin_acceptance_booking,
    DROP CONSTRAINT fk_customer_cabin_acceptance_order,
    DROP CONSTRAINT uk_customer_cabin_acceptance_booking_cabin,
    DROP CONSTRAINT uk_customer_cabin_acceptance_subject_key,
    ADD CONSTRAINT fk_customer_cabin_acceptance_booking
        FOREIGN KEY (company_id, booking_id)
        REFERENCES public.presentation_booking (company_id, id),
    ADD CONSTRAINT fk_customer_cabin_acceptance_order
        FOREIGN KEY (company_id, order_id)
        REFERENCES public.rental_order (company_id, id),
    ADD CONSTRAINT uk_customer_cabin_acceptance_booking_cabin
        UNIQUE (company_id, booking_id, cabin_unit_id),
    ADD CONSTRAINT uk_customer_cabin_acceptance_subject_key
        UNIQUE (company_id, customer_subject_id, idempotency_key);

ALTER TABLE public.customer_cabin_problem
    ALTER COLUMN company_id SET NOT NULL,
    DROP CONSTRAINT fk_customer_cabin_problem_booking,
    DROP CONSTRAINT fk_customer_cabin_problem_order,
    DROP CONSTRAINT uk_customer_cabin_problem_subject_key,
    ADD CONSTRAINT fk_customer_cabin_problem_booking
        FOREIGN KEY (company_id, booking_id)
        REFERENCES public.presentation_booking (company_id, id),
    ADD CONSTRAINT fk_customer_cabin_problem_order
        FOREIGN KEY (company_id, order_id)
        REFERENCES public.rental_order (company_id, id),
    ADD CONSTRAINT uk_customer_cabin_problem_subject_key
        UNIQUE (company_id, customer_subject_id, idempotency_key);

ALTER TABLE public.customer_booking_mutation
    DROP CONSTRAINT fk_customer_booking_mutation_inquiry,
    DROP CONSTRAINT fk_customer_booking_mutation_old_slot,
    DROP CONSTRAINT fk_customer_booking_mutation_new_slot,
    ADD CONSTRAINT fk_customer_booking_mutation_inquiry
        FOREIGN KEY (company_id, inquiry_id)
        REFERENCES public.rental_inquiry (company_id, id),
    ADD CONSTRAINT fk_customer_booking_mutation_booking
        FOREIGN KEY (company_id, booking_id)
        REFERENCES public.presentation_booking (company_id, id),
    ADD CONSTRAINT fk_customer_booking_mutation_old_slot
        FOREIGN KEY (company_id, old_slot_id)
        REFERENCES public.customer_delivery_slot (company_id, id),
    ADD CONSTRAINT fk_customer_booking_mutation_new_slot
        FOREIGN KEY (company_id, new_slot_id)
        REFERENCES public.customer_delivery_slot (company_id, id);

CREATE INDEX idx_customer_rental_session_subject_created
    ON public.customer_rental_session
        (company_id, customer_subject_id, created_at DESC, id DESC);

CREATE INDEX idx_customer_rental_session_state_updated
    ON public.customer_rental_session (company_id, state, updated_at, id);

CREATE INDEX idx_customer_delivery_slot_capacity
    ON public.customer_delivery_slot
        (company_id, warehouse_id, delivery_date, window_start, state, expires_at, id);

CREATE INDEX idx_customer_delivery_slot_warehouse_capacity
    ON public.customer_delivery_slot
        (warehouse_id, delivery_date, window_start, state, expires_at, id);

CREATE INDEX idx_customer_delivery_slot_inquiry
    ON public.customer_delivery_slot
        (company_id, inquiry_id, state, created_at DESC, id DESC);

CREATE UNIQUE INDEX uk_customer_delivery_slot_confirmed_order
    ON public.customer_delivery_slot (company_id, order_id)
    WHERE state = 'CONFIRMED';

CREATE UNIQUE INDEX uk_customer_delivery_slot_booking
    ON public.customer_delivery_slot (company_id, booking_id)
    WHERE state IN ('CHECKOUT_PENDING', 'CONFIRMED');

CREATE INDEX idx_customer_cabin_acceptance_booking
    ON public.customer_cabin_acceptance
        (company_id, booking_id, accepted_at, id);

CREATE INDEX idx_customer_cabin_problem_booking
    ON public.customer_cabin_problem
        (company_id, booking_id, reported_at, id);

CREATE UNIQUE INDEX uk_customer_booking_mutation_open_booking
    ON public.customer_booking_mutation (company_id, booking_id)
    WHERE state IN ('PENDING', 'QUARANTINED');

CREATE INDEX idx_customer_booking_mutation_open_order
    ON public.customer_booking_mutation (company_id, order_id)
    WHERE operation = 'CANCEL' AND state IN ('PENDING', 'QUARANTINED');

CREATE INDEX idx_customer_booking_mutation_due
    ON public.customer_booking_mutation (next_attempt_at, created_at, id)
    WHERE operation = 'CANCEL' AND state = 'PENDING';

CREATE INDEX idx_customer_booking_mutation_booking_history
    ON public.customer_booking_mutation
        (company_id, booking_id, created_at DESC, id DESC);

CREATE OR REPLACE FUNCTION public.prevent_customer_graph_company_change()
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

CREATE TRIGGER prevent_customer_profile_company_change
BEFORE UPDATE OF company_id ON public.customer_profile
FOR EACH ROW EXECUTE FUNCTION public.prevent_customer_graph_company_change();

CREATE TRIGGER prevent_customer_rental_session_company_change
BEFORE UPDATE OF company_id ON public.customer_rental_session
FOR EACH ROW EXECUTE FUNCTION public.prevent_customer_graph_company_change();

CREATE TRIGGER prevent_customer_delivery_slot_company_change
BEFORE UPDATE OF company_id ON public.customer_delivery_slot
FOR EACH ROW EXECUTE FUNCTION public.prevent_customer_graph_company_change();

CREATE TRIGGER prevent_customer_cabin_acceptance_company_change
BEFORE UPDATE OF company_id ON public.customer_cabin_acceptance
FOR EACH ROW EXECUTE FUNCTION public.prevent_customer_graph_company_change();

CREATE TRIGGER prevent_customer_cabin_problem_company_change
BEFORE UPDATE OF company_id ON public.customer_cabin_problem
FOR EACH ROW EXECUTE FUNCTION public.prevent_customer_graph_company_change();

CREATE TRIGGER prevent_customer_booking_mutation_company_change
BEFORE UPDATE OF company_id ON public.customer_booking_mutation
FOR EACH ROW EXECUTE FUNCTION public.prevent_customer_graph_company_change();
