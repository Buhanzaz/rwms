-- RWMS is a single-installation system. V88-V92 were released with a platform-company fence;
-- collapse that fence only when the historical rows still represent one installation, then keep
-- the same business and idempotency guarantees on their natural global keys.

DO $$
BEGIN
    IF (
        SELECT count(DISTINCT company_id)
        FROM (
            SELECT company_id FROM public.order_client
            UNION
            SELECT company_id FROM public.rental_order
            UNION
            SELECT company_id FROM public.rental_order_command_receipt
            UNION
            SELECT company_id FROM public.rental_order_mutation_command
            UNION
            SELECT company_id FROM public.customer_booking_mutation
            UNION
            SELECT company_id FROM public.rental_inquiry
            UNION
            SELECT company_id FROM public.rental_inquiry_search_attempt
            UNION
            SELECT company_id FROM public.rental_inquiry_selection_receipt
            UNION
            SELECT company_id FROM public.client_presentation
            UNION
            SELECT company_id FROM public.client_presentation_item
            UNION
            SELECT company_id FROM public.presentation_booking
            UNION
            SELECT company_id FROM public.rental_inquiry_outbox
            UNION
            SELECT company_id FROM public.customer_profile
            UNION
            SELECT company_id FROM public.customer_rental_session
            UNION
            SELECT company_id FROM public.customer_delivery_slot
            UNION
            SELECT company_id FROM public.customer_cabin_acceptance
            UNION
            SELECT company_id FROM public.customer_cabin_problem
            UNION
            SELECT company_id FROM public.customer_cabin_problem_action
        ) owned_company
        WHERE company_id IS NOT NULL
    ) > 1 THEN
        RAISE EXCEPTION
            'Cannot collapse platform company ownership for a multi-company logistics database';
    END IF;
END
$$;

DROP TRIGGER prevent_order_client_company_change ON public.order_client;
DROP TRIGGER prevent_rental_order_company_change ON public.rental_order;
DROP TRIGGER prevent_rental_inquiry_company_change ON public.rental_inquiry;
DROP TRIGGER prevent_rental_inquiry_search_attempt_company_change
    ON public.rental_inquiry_search_attempt;
DROP TRIGGER prevent_rental_inquiry_selection_receipt_company_change
    ON public.rental_inquiry_selection_receipt;
DROP TRIGGER prevent_client_presentation_company_change ON public.client_presentation;
DROP TRIGGER prevent_client_presentation_item_company_change ON public.client_presentation_item;
DROP TRIGGER prevent_presentation_booking_company_change ON public.presentation_booking;
DROP TRIGGER prevent_rental_inquiry_outbox_company_change ON public.rental_inquiry_outbox;
DROP TRIGGER prevent_customer_profile_company_change ON public.customer_profile;
DROP TRIGGER prevent_customer_rental_session_company_change ON public.customer_rental_session;
DROP TRIGGER prevent_customer_delivery_slot_company_change ON public.customer_delivery_slot;
DROP TRIGGER prevent_customer_cabin_acceptance_company_change ON public.customer_cabin_acceptance;
DROP TRIGGER prevent_customer_cabin_problem_company_change ON public.customer_cabin_problem;
DROP TRIGGER prevent_customer_booking_mutation_company_change ON public.customer_booking_mutation;

DROP FUNCTION public.prevent_order_client_company_change();
DROP FUNCTION public.prevent_rental_order_company_change();
DROP FUNCTION public.prevent_rental_inquiry_company_change();
DROP FUNCTION public.prevent_inquiry_child_company_change();
DROP FUNCTION public.prevent_customer_graph_company_change();

DROP INDEX public.uk_order_client_type_phone;
DROP INDEX public.idx_order_client_company_search;
DROP INDEX public.idx_order_client_company_manager_updated;
DROP INDEX public.idx_rental_order_company_manager_updated;
DROP INDEX public.idx_rental_order_company_warehouse_updated;
DROP INDEX public.idx_rental_order_company_client_updated;
DROP INDEX public.uk_rental_inquiry_conversation;
DROP INDEX public.idx_rental_inquiry_manager_state;
DROP INDEX public.idx_rental_inquiry_rental_order;
DROP INDEX public.uk_rental_inquiry_search_attempt_one_prepared;
DROP INDEX public.idx_rental_inquiry_search_attempt_inquiry_created;
DROP INDEX public.uq_rental_inquiry_selection_receipt_prepared;
DROP INDEX public.idx_rental_inquiry_selection_receipt_inquiry;
DROP INDEX public.idx_client_presentation_item_current;
DROP INDEX public.idx_presentation_booking_manager_alert;
DROP INDEX public.idx_customer_rental_session_subject_created;
DROP INDEX public.idx_customer_rental_session_state_updated;
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
DROP INDEX public.idx_customer_cabin_problem_company_order;
DROP INDEX public.idx_customer_cabin_problem_company_deadline;
DROP INDEX public.idx_customer_cabin_problem_action_problem_time;

ALTER TABLE public.rental_order
    DROP CONSTRAINT fk_rental_order_client;
ALTER TABLE public.rental_order_command_receipt
    DROP CONSTRAINT fk_rental_order_command_receipt_order;
ALTER TABLE public.rental_order_mutation_command
    DROP CONSTRAINT fk_rental_order_mutation_command_order;
ALTER TABLE public.customer_booking_mutation
    DROP CONSTRAINT fk_customer_booking_mutation_order,
    DROP CONSTRAINT fk_customer_booking_mutation_inquiry,
    DROP CONSTRAINT fk_customer_booking_mutation_booking,
    DROP CONSTRAINT fk_customer_booking_mutation_old_slot,
    DROP CONSTRAINT fk_customer_booking_mutation_new_slot;
ALTER TABLE public.rental_inquiry
    DROP CONSTRAINT fk_rental_inquiry_client,
    DROP CONSTRAINT fk_rental_inquiry_rental_order,
    DROP CONSTRAINT fk_rental_inquiry_booked_order;
ALTER TABLE public.rental_inquiry_search_attempt
    DROP CONSTRAINT fk_rental_inquiry_search_attempt_inquiry;
ALTER TABLE public.rental_inquiry_selection_receipt
    DROP CONSTRAINT fk_rental_inquiry_selection_receipt_inquiry;
ALTER TABLE public.client_presentation
    DROP CONSTRAINT fk_client_presentation_inquiry,
    DROP CONSTRAINT fk_client_presentation_booked_order;
ALTER TABLE public.client_presentation_item
    DROP CONSTRAINT fk_client_presentation_item_presentation;
ALTER TABLE public.presentation_booking
    DROP CONSTRAINT fk_presentation_booking_presentation,
    DROP CONSTRAINT fk_presentation_booking_order;
ALTER TABLE public.rental_inquiry_outbox
    DROP CONSTRAINT fk_rental_inquiry_outbox_inquiry,
    DROP CONSTRAINT fk_rental_inquiry_outbox_order;
ALTER TABLE public.customer_profile
    DROP CONSTRAINT fk_customer_profile_client;
ALTER TABLE public.customer_delivery_slot
    DROP CONSTRAINT fk_customer_delivery_slot_inquiry,
    DROP CONSTRAINT fk_customer_delivery_slot_order;
ALTER TABLE public.customer_rental_session
    DROP CONSTRAINT fk_customer_rental_session_inquiry,
    DROP CONSTRAINT fk_customer_rental_session_delivery_slot,
    DROP CONSTRAINT fk_customer_rental_session_booking,
    DROP CONSTRAINT fk_customer_rental_session_order;
ALTER TABLE public.customer_cabin_acceptance
    DROP CONSTRAINT fk_customer_cabin_acceptance_booking,
    DROP CONSTRAINT fk_customer_cabin_acceptance_order;
ALTER TABLE public.customer_cabin_problem
    DROP CONSTRAINT fk_customer_cabin_problem_booking,
    DROP CONSTRAINT fk_customer_cabin_problem_order;
ALTER TABLE public.customer_cabin_problem_action
    DROP CONSTRAINT fk_customer_cabin_problem_action_problem;

ALTER TABLE public.order_client
    DROP CONSTRAINT uk_order_client_creator_idempotency,
    DROP CONSTRAINT uk_order_client_company_id,
    ADD CONSTRAINT uk_order_client_creator_idempotency
        UNIQUE (created_by_subject_id, creation_idempotency_key);
ALTER TABLE public.rental_order
    DROP CONSTRAINT uk_rental_order_number,
    DROP CONSTRAINT uk_rental_order_creator_idempotency,
    DROP CONSTRAINT uk_rental_order_company_id,
    ADD CONSTRAINT uk_rental_order_number UNIQUE (order_number),
    ADD CONSTRAINT uk_rental_order_creator_idempotency
        UNIQUE (created_by_subject_id, creation_idempotency_key);
ALTER TABLE public.rental_order_command_receipt
    DROP CONSTRAINT uk_rental_order_command_receipt,
    ADD CONSTRAINT uk_rental_order_command_receipt
        UNIQUE (actor_subject_id, operation_name, idempotency_key);
ALTER TABLE public.rental_order_mutation_command
    DROP CONSTRAINT uk_rental_order_mutation_command_actor_key,
    ADD CONSTRAINT uk_rental_order_mutation_command_actor_key
        UNIQUE (actor_subject_id, operation, idempotency_key);
ALTER TABLE public.customer_booking_mutation
    DROP CONSTRAINT uk_customer_booking_mutation_subject_key,
    ADD CONSTRAINT uk_customer_booking_mutation_subject_key
        UNIQUE (customer_subject_id, idempotency_key);
ALTER TABLE public.rental_inquiry
    DROP CONSTRAINT uk_rental_inquiry_creation_key,
    DROP CONSTRAINT uk_rental_inquiry_company_id,
    ADD CONSTRAINT uk_rental_inquiry_creation_key
        UNIQUE (manager_id, creation_idempotency_key);
ALTER TABLE public.rental_inquiry_search_attempt
    DROP CONSTRAINT uk_rental_inquiry_search_attempt_subject_operation_key,
    ADD CONSTRAINT uk_rental_inquiry_search_attempt_subject_operation_key
        UNIQUE (subject_id, operation_name, public_idempotency_key);
ALTER TABLE public.rental_inquiry_selection_receipt
    DROP CONSTRAINT uq_rental_inquiry_selection_receipt_subject_key,
    ADD CONSTRAINT uq_rental_inquiry_selection_receipt_subject_key
        UNIQUE (subject_id, public_idempotency_key);
ALTER TABLE public.client_presentation
    DROP CONSTRAINT uk_client_presentation_inquiry,
    DROP CONSTRAINT uk_client_presentation_company_id,
    ADD CONSTRAINT uk_client_presentation_inquiry UNIQUE (inquiry_id);
ALTER TABLE public.client_presentation_item
    DROP CONSTRAINT uk_client_presentation_item_revision_cabin,
    DROP CONSTRAINT uk_client_presentation_item_revision_order,
    ADD CONSTRAINT uk_client_presentation_item_revision_cabin
        UNIQUE (presentation_id, presentation_revision, rental_item_id),
    ADD CONSTRAINT uk_client_presentation_item_revision_order
        UNIQUE (presentation_id, presentation_revision, sort_order);
ALTER TABLE public.presentation_booking
    DROP CONSTRAINT uk_presentation_booking_idempotency,
    DROP CONSTRAINT uk_presentation_booking_revision,
    DROP CONSTRAINT uk_presentation_booking_company_id,
    ADD CONSTRAINT uk_presentation_booking_idempotency
        UNIQUE (presentation_id, idempotency_key),
    ADD CONSTRAINT uk_presentation_booking_revision
        UNIQUE (presentation_id, presentation_revision);
ALTER TABLE public.rental_inquiry_outbox
    DROP CONSTRAINT uk_rental_inquiry_outbox_inquiry,
    ADD CONSTRAINT uk_rental_inquiry_outbox_inquiry UNIQUE (inquiry_id);
ALTER TABLE public.customer_profile
    DROP CONSTRAINT uk_customer_profile_company_auth_subject,
    DROP CONSTRAINT uk_customer_profile_company_client,
    ADD CONSTRAINT uk_customer_profile_auth_subject UNIQUE (auth_subject_id),
    ADD CONSTRAINT uk_customer_profile_client UNIQUE (client_id);
ALTER TABLE public.customer_delivery_slot
    DROP CONSTRAINT uk_customer_delivery_slot_company_id;
ALTER TABLE public.customer_rental_session
    DROP CONSTRAINT uk_customer_rental_session_company_inquiry,
    DROP CONSTRAINT uk_customer_rental_session_company_id,
    ADD CONSTRAINT uk_customer_rental_session_inquiry UNIQUE (inquiry_id);
ALTER TABLE public.customer_cabin_acceptance
    DROP CONSTRAINT uk_customer_cabin_acceptance_booking_cabin,
    DROP CONSTRAINT uk_customer_cabin_acceptance_subject_key,
    ADD CONSTRAINT uk_customer_cabin_acceptance_booking_cabin
        UNIQUE (booking_id, cabin_unit_id),
    ADD CONSTRAINT uk_customer_cabin_acceptance_subject_key
        UNIQUE (customer_subject_id, idempotency_key);
ALTER TABLE public.customer_cabin_problem
    DROP CONSTRAINT uk_customer_cabin_problem_subject_key,
    DROP CONSTRAINT uk_customer_cabin_problem_company_id,
    ADD CONSTRAINT uk_customer_cabin_problem_subject_key
        UNIQUE (customer_subject_id, idempotency_key);

ALTER TABLE public.rental_order
    ADD CONSTRAINT fk_rental_order_client FOREIGN KEY (client_id)
        REFERENCES public.order_client(id);
ALTER TABLE public.rental_order_command_receipt
    ADD CONSTRAINT fk_rental_order_command_receipt_order FOREIGN KEY (order_id)
        REFERENCES public.rental_order(id);
ALTER TABLE public.rental_order_mutation_command
    ADD CONSTRAINT fk_rental_order_mutation_command_order FOREIGN KEY (order_id)
        REFERENCES public.rental_order(id);
ALTER TABLE public.customer_booking_mutation
    ADD CONSTRAINT fk_customer_booking_mutation_order FOREIGN KEY (order_id)
        REFERENCES public.rental_order(id),
    ADD CONSTRAINT fk_customer_booking_mutation_inquiry FOREIGN KEY (inquiry_id)
        REFERENCES public.rental_inquiry(id),
    ADD CONSTRAINT fk_customer_booking_mutation_booking FOREIGN KEY (booking_id)
        REFERENCES public.presentation_booking(id),
    ADD CONSTRAINT fk_customer_booking_mutation_old_slot FOREIGN KEY (old_slot_id)
        REFERENCES public.customer_delivery_slot(id),
    ADD CONSTRAINT fk_customer_booking_mutation_new_slot FOREIGN KEY (new_slot_id)
        REFERENCES public.customer_delivery_slot(id);
ALTER TABLE public.rental_inquiry
    ADD CONSTRAINT fk_rental_inquiry_client FOREIGN KEY (client_id)
        REFERENCES public.order_client(id),
    ADD CONSTRAINT fk_rental_inquiry_rental_order FOREIGN KEY (rental_order_id)
        REFERENCES public.rental_order(id),
    ADD CONSTRAINT fk_rental_inquiry_booked_order FOREIGN KEY (booked_order_id)
        REFERENCES public.rental_order(id);
ALTER TABLE public.rental_inquiry_search_attempt
    ADD CONSTRAINT fk_rental_inquiry_search_attempt_inquiry FOREIGN KEY (inquiry_id)
        REFERENCES public.rental_inquiry(id);
ALTER TABLE public.rental_inquiry_selection_receipt
    ADD CONSTRAINT fk_rental_inquiry_selection_receipt_inquiry FOREIGN KEY (inquiry_id)
        REFERENCES public.rental_inquiry(id);
ALTER TABLE public.client_presentation
    ADD CONSTRAINT fk_client_presentation_inquiry FOREIGN KEY (inquiry_id)
        REFERENCES public.rental_inquiry(id),
    ADD CONSTRAINT fk_client_presentation_booked_order FOREIGN KEY (booked_order_id)
        REFERENCES public.rental_order(id);
ALTER TABLE public.client_presentation_item
    ADD CONSTRAINT fk_client_presentation_item_presentation FOREIGN KEY (presentation_id)
        REFERENCES public.client_presentation(id);
ALTER TABLE public.presentation_booking
    ADD CONSTRAINT fk_presentation_booking_presentation FOREIGN KEY (presentation_id)
        REFERENCES public.client_presentation(id),
    ADD CONSTRAINT fk_presentation_booking_order FOREIGN KEY (order_id)
        REFERENCES public.rental_order(id);
ALTER TABLE public.rental_inquiry_outbox
    ADD CONSTRAINT fk_rental_inquiry_outbox_inquiry FOREIGN KEY (inquiry_id)
        REFERENCES public.rental_inquiry(id),
    ADD CONSTRAINT fk_rental_inquiry_outbox_order FOREIGN KEY (order_id)
        REFERENCES public.rental_order(id);
ALTER TABLE public.customer_profile
    ADD CONSTRAINT fk_customer_profile_client FOREIGN KEY (client_id)
        REFERENCES public.order_client(id);
ALTER TABLE public.customer_delivery_slot
    ADD CONSTRAINT fk_customer_delivery_slot_inquiry FOREIGN KEY (inquiry_id)
        REFERENCES public.rental_inquiry(id),
    ADD CONSTRAINT fk_customer_delivery_slot_order FOREIGN KEY (order_id)
        REFERENCES public.rental_order(id);
ALTER TABLE public.customer_rental_session
    ADD CONSTRAINT fk_customer_rental_session_inquiry FOREIGN KEY (inquiry_id)
        REFERENCES public.rental_inquiry(id),
    ADD CONSTRAINT fk_customer_rental_session_delivery_slot FOREIGN KEY (delivery_slot_id)
        REFERENCES public.customer_delivery_slot(id),
    ADD CONSTRAINT fk_customer_rental_session_booking FOREIGN KEY (booking_id)
        REFERENCES public.presentation_booking(id),
    ADD CONSTRAINT fk_customer_rental_session_order FOREIGN KEY (order_id)
        REFERENCES public.rental_order(id);
ALTER TABLE public.customer_cabin_acceptance
    ADD CONSTRAINT fk_customer_cabin_acceptance_booking FOREIGN KEY (booking_id)
        REFERENCES public.presentation_booking(id),
    ADD CONSTRAINT fk_customer_cabin_acceptance_order FOREIGN KEY (order_id)
        REFERENCES public.rental_order(id);
ALTER TABLE public.customer_cabin_problem
    ADD CONSTRAINT fk_customer_cabin_problem_booking FOREIGN KEY (booking_id)
        REFERENCES public.presentation_booking(id),
    ADD CONSTRAINT fk_customer_cabin_problem_order FOREIGN KEY (order_id)
        REFERENCES public.rental_order(id);
ALTER TABLE public.customer_cabin_problem_action
    ADD CONSTRAINT fk_customer_cabin_problem_action_problem FOREIGN KEY (problem_id)
        REFERENCES public.customer_cabin_problem(id);

CREATE UNIQUE INDEX uk_order_client_type_phone
    ON public.order_client (client_type, normalized_phone)
    WHERE normalized_phone IS NOT NULL;
CREATE UNIQUE INDEX uk_rental_inquiry_conversation
    ON public.rental_inquiry (conversation_id)
    WHERE conversation_id IS NOT NULL;
CREATE INDEX idx_rental_inquiry_manager_state
    ON public.rental_inquiry (manager_id, state, updated_at DESC, id);
CREATE INDEX idx_rental_inquiry_rental_order
    ON public.rental_inquiry (rental_order_id, state, created_at DESC, id)
    WHERE rental_order_id IS NOT NULL;
CREATE UNIQUE INDEX uk_rental_inquiry_search_attempt_one_prepared
    ON public.rental_inquiry_search_attempt (inquiry_id)
    WHERE state = 'PREPARED';
CREATE INDEX idx_rental_inquiry_search_attempt_inquiry_created
    ON public.rental_inquiry_search_attempt (inquiry_id, created_at DESC, id);
CREATE UNIQUE INDEX uq_rental_inquiry_selection_receipt_prepared
    ON public.rental_inquiry_selection_receipt (inquiry_id)
    WHERE state = 'PREPARED';
CREATE INDEX idx_rental_inquiry_selection_receipt_inquiry
    ON public.rental_inquiry_selection_receipt (inquiry_id, created_at DESC, id);
CREATE INDEX idx_client_presentation_item_current
    ON public.client_presentation_item
        (presentation_id, presentation_revision, sort_order, id);
CREATE INDEX idx_presentation_booking_manager_alert
    ON public.presentation_booking (completed_at ASC, id ASC)
    WHERE state = 'COMPLETED' AND manager_action IS NULL;
CREATE INDEX idx_customer_rental_session_subject_created
    ON public.customer_rental_session (customer_subject_id, created_at DESC, id DESC);
CREATE INDEX idx_customer_rental_session_state_updated
    ON public.customer_rental_session (state, updated_at, id);
CREATE INDEX idx_customer_delivery_slot_capacity
    ON public.customer_delivery_slot
        (warehouse_id, delivery_date, window_start, state, expires_at, id);
CREATE INDEX idx_customer_delivery_slot_inquiry
    ON public.customer_delivery_slot
        (inquiry_id, state, created_at DESC, id DESC);
CREATE UNIQUE INDEX uk_customer_delivery_slot_confirmed_order
    ON public.customer_delivery_slot (order_id)
    WHERE state = 'CONFIRMED';
CREATE UNIQUE INDEX uk_customer_delivery_slot_booking
    ON public.customer_delivery_slot (booking_id)
    WHERE state IN ('CHECKOUT_PENDING', 'CONFIRMED');
CREATE INDEX idx_customer_cabin_acceptance_booking
    ON public.customer_cabin_acceptance (booking_id, accepted_at, id);
CREATE INDEX idx_customer_cabin_problem_booking
    ON public.customer_cabin_problem (booking_id, reported_at, id);
CREATE UNIQUE INDEX uk_customer_booking_mutation_open_booking
    ON public.customer_booking_mutation (booking_id)
    WHERE state IN ('PENDING', 'QUARANTINED');
CREATE INDEX idx_customer_booking_mutation_open_order
    ON public.customer_booking_mutation (order_id)
    WHERE operation = 'CANCEL' AND state IN ('PENDING', 'QUARANTINED');
CREATE INDEX idx_customer_booking_mutation_due
    ON public.customer_booking_mutation (next_attempt_at, created_at, id)
    WHERE operation = 'CANCEL' AND state = 'PENDING';
CREATE INDEX idx_customer_booking_mutation_booking_history
    ON public.customer_booking_mutation (booking_id, created_at DESC, id DESC);
CREATE INDEX idx_customer_cabin_problem_order
    ON public.customer_cabin_problem (order_id, reported_at, id);
CREATE INDEX idx_customer_cabin_problem_deadline
    ON public.customer_cabin_problem (resolution_deadline, id);
CREATE INDEX idx_customer_cabin_problem_action_problem_time
    ON public.customer_cabin_problem_action (problem_id, occurred_at DESC, id DESC);

ALTER TABLE public.order_client DROP COLUMN company_id;
ALTER TABLE public.rental_order DROP COLUMN company_id;
ALTER TABLE public.rental_order_command_receipt DROP COLUMN company_id;
ALTER TABLE public.rental_order_mutation_command DROP COLUMN company_id;
ALTER TABLE public.customer_booking_mutation DROP COLUMN company_id;
ALTER TABLE public.rental_inquiry DROP COLUMN company_id;
ALTER TABLE public.rental_inquiry_search_attempt DROP COLUMN company_id;
ALTER TABLE public.rental_inquiry_selection_receipt DROP COLUMN company_id;
ALTER TABLE public.client_presentation DROP COLUMN company_id;
ALTER TABLE public.client_presentation_item DROP COLUMN company_id;
ALTER TABLE public.presentation_booking DROP COLUMN company_id;
ALTER TABLE public.rental_inquiry_outbox DROP COLUMN company_id;
ALTER TABLE public.customer_profile DROP COLUMN company_id;
ALTER TABLE public.customer_rental_session DROP COLUMN company_id;
ALTER TABLE public.customer_delivery_slot DROP COLUMN company_id;
ALTER TABLE public.customer_cabin_acceptance DROP COLUMN company_id;
ALTER TABLE public.customer_cabin_problem DROP COLUMN company_id;
ALTER TABLE public.customer_cabin_problem_action DROP COLUMN company_id;
