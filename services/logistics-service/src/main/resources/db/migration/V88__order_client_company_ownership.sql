-- Adopt the existing single-company order/client installation while introducing an immutable
-- company fence for every public order command and its durable idempotency state.

ALTER TABLE public.order_client
    ADD COLUMN company_id uuid NOT NULL
        DEFAULT 'ae0d6f97-f0c5-576a-9ea7-1ddcc1a03b48'::uuid;

ALTER TABLE public.order_client
    ALTER COLUMN company_id DROP DEFAULT,
    DROP CONSTRAINT uk_order_client_creator_idempotency,
    ADD CONSTRAINT uk_order_client_creator_idempotency
        UNIQUE (company_id, created_by_subject_id, creation_idempotency_key),
    ADD CONSTRAINT uk_order_client_company_id UNIQUE (company_id, id);

DROP INDEX public.uk_order_client_type_phone;

CREATE UNIQUE INDEX uk_order_client_type_phone
    ON public.order_client (company_id, client_type, normalized_phone)
    WHERE normalized_phone IS NOT NULL;

ALTER TABLE public.rental_order
    ADD COLUMN company_id uuid NOT NULL
        DEFAULT 'ae0d6f97-f0c5-576a-9ea7-1ddcc1a03b48'::uuid;

ALTER TABLE public.rental_order
    ALTER COLUMN company_id DROP DEFAULT,
    DROP CONSTRAINT uk_rental_order_number,
    DROP CONSTRAINT uk_rental_order_creator_idempotency,
    DROP CONSTRAINT fk_rental_order_client,
    ADD CONSTRAINT uk_rental_order_number UNIQUE (company_id, order_number),
    ADD CONSTRAINT uk_rental_order_creator_idempotency
        UNIQUE (company_id, created_by_subject_id, creation_idempotency_key),
    ADD CONSTRAINT uk_rental_order_company_id UNIQUE (company_id, id),
    ADD CONSTRAINT fk_rental_order_client
        FOREIGN KEY (company_id, client_id)
        REFERENCES public.order_client (company_id, id);

ALTER TABLE public.rental_order_command_receipt
    ADD COLUMN company_id uuid NOT NULL
        DEFAULT 'ae0d6f97-f0c5-576a-9ea7-1ddcc1a03b48'::uuid;

ALTER TABLE public.rental_order_command_receipt
    ALTER COLUMN company_id DROP DEFAULT,
    DROP CONSTRAINT uk_rental_order_command_receipt,
    DROP CONSTRAINT fk_rental_order_command_receipt_order,
    ADD CONSTRAINT uk_rental_order_command_receipt
        UNIQUE (company_id, actor_subject_id, operation_name, idempotency_key),
    ADD CONSTRAINT fk_rental_order_command_receipt_order
        FOREIGN KEY (company_id, order_id)
        REFERENCES public.rental_order (company_id, id);

ALTER TABLE public.rental_order_mutation_command
    ADD COLUMN company_id uuid NOT NULL
        DEFAULT 'ae0d6f97-f0c5-576a-9ea7-1ddcc1a03b48'::uuid;

ALTER TABLE public.rental_order_mutation_command
    ALTER COLUMN company_id DROP DEFAULT,
    DROP CONSTRAINT uk_rental_order_mutation_command_actor_key,
    DROP CONSTRAINT fk_rental_order_mutation_command_order,
    ADD CONSTRAINT uk_rental_order_mutation_command_actor_key
        UNIQUE (company_id, actor_subject_id, operation, idempotency_key),
    ADD CONSTRAINT fk_rental_order_mutation_command_order
        FOREIGN KEY (company_id, order_id)
        REFERENCES public.rental_order (company_id, id);

ALTER TABLE public.customer_booking_mutation
    ADD COLUMN company_id uuid NOT NULL
        DEFAULT 'ae0d6f97-f0c5-576a-9ea7-1ddcc1a03b48'::uuid;

ALTER TABLE public.customer_booking_mutation
    ALTER COLUMN company_id DROP DEFAULT,
    DROP CONSTRAINT uk_customer_booking_mutation_subject_key,
    DROP CONSTRAINT fk_customer_booking_mutation_order,
    ADD CONSTRAINT uk_customer_booking_mutation_subject_key
        UNIQUE (company_id, customer_subject_id, idempotency_key),
    ADD CONSTRAINT fk_customer_booking_mutation_order
        FOREIGN KEY (company_id, order_id)
        REFERENCES public.rental_order (company_id, id);

CREATE INDEX idx_order_client_company_search
    ON public.order_client (company_id, client_type, normalized_name, id);

CREATE INDEX idx_order_client_company_manager_updated
    ON public.order_client (company_id, responsible_manager_id, updated_at DESC, id);

CREATE INDEX idx_rental_order_company_manager_updated
    ON public.rental_order (company_id, manager_id, updated_at DESC, id);

CREATE INDEX idx_rental_order_company_warehouse_updated
    ON public.rental_order (company_id, warehouse_id, updated_at DESC, id)
    WHERE warehouse_id IS NOT NULL;

CREATE INDEX idx_rental_order_company_client_updated
    ON public.rental_order (company_id, client_id, updated_at DESC, id);

CREATE OR REPLACE FUNCTION public.prevent_order_client_company_change()
RETURNS trigger
LANGUAGE plpgsql
AS $$
BEGIN
    IF NEW.company_id IS DISTINCT FROM OLD.company_id THEN
        RAISE EXCEPTION 'order client company ownership is immutable'
            USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END
$$;

CREATE TRIGGER prevent_order_client_company_change
BEFORE UPDATE OF company_id ON public.order_client
FOR EACH ROW EXECUTE FUNCTION public.prevent_order_client_company_change();

CREATE OR REPLACE FUNCTION public.prevent_rental_order_company_change()
RETURNS trigger
LANGUAGE plpgsql
AS $$
BEGIN
    IF NEW.company_id IS DISTINCT FROM OLD.company_id THEN
        RAISE EXCEPTION 'rental order company ownership is immutable'
            USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END
$$;

CREATE TRIGGER prevent_rental_order_company_change
BEFORE UPDATE OF company_id ON public.rental_order
FOR EACH ROW EXECUTE FUNCTION public.prevent_rental_order_company_change();
