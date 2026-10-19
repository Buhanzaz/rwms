CREATE SEQUENCE public.rental_order_number_seq
    START WITH 1
    INCREMENT BY 1
    NO CYCLE;

CREATE TABLE public.order_client (
    id uuid NOT NULL,
    version bigint NOT NULL DEFAULT 0,
    client_type varchar(32) NOT NULL,
    display_name varchar(512) NOT NULL,
    normalized_name varchar(512) NOT NULL,
    created_by_subject_id uuid NOT NULL,
    creation_idempotency_key uuid NOT NULL,
    creation_request_sha256 char(64) NOT NULL,
    created_at timestamptz NOT NULL,
    updated_at timestamptz NOT NULL,
    CONSTRAINT order_client_pkey PRIMARY KEY (id),
    CONSTRAINT uk_order_client_type_normalized UNIQUE (client_type, normalized_name),
    CONSTRAINT uk_order_client_creator_idempotency
        UNIQUE (created_by_subject_id, creation_idempotency_key),
    CONSTRAINT ck_order_client_version CHECK (version >= 0),
    CONSTRAINT ck_order_client_type CHECK (
        client_type IN ('INDIVIDUAL', 'LEGAL_ENTITY')
    ),
    CONSTRAINT ck_order_client_display_name CHECK (
        length(btrim(display_name)) BETWEEN 1 AND 512
    ),
    CONSTRAINT ck_order_client_normalized_name CHECK (
        length(normalized_name) BETWEEN 1 AND 512
        AND normalized_name = lower(normalized_name)
        AND normalized_name = btrim(normalized_name)
    ),
    CONSTRAINT ck_order_client_creation_hash CHECK (
        creation_request_sha256 ~ '^[0-9a-f]{64}$'
    )
);

CREATE INDEX idx_order_client_search
    ON public.order_client (client_type, normalized_name, id);

CREATE TABLE public.rental_order (
    id uuid NOT NULL,
    version bigint NOT NULL DEFAULT 0,
    order_number varchar(32) NOT NULL,
    status varchar(24) NOT NULL,
    client_id uuid NOT NULL,
    manager_id uuid NOT NULL,
    manager_display_name varchar(255) NOT NULL,
    created_by_subject_id uuid NOT NULL,
    created_by_display_name varchar(255) NOT NULL,
    created_by_role varchar(32) NOT NULL,
    warehouse_id uuid,
    creation_idempotency_key uuid NOT NULL,
    creation_request_sha256 char(64) NOT NULL,
    created_at timestamptz NOT NULL,
    updated_at timestamptz NOT NULL,
    CONSTRAINT rental_order_pkey PRIMARY KEY (id),
    CONSTRAINT uk_rental_order_number UNIQUE (order_number),
    CONSTRAINT uk_rental_order_creator_idempotency
        UNIQUE (created_by_subject_id, creation_idempotency_key),
    CONSTRAINT fk_rental_order_client FOREIGN KEY (client_id)
        REFERENCES public.order_client(id),
    CONSTRAINT ck_rental_order_version CHECK (version >= 0),
    CONSTRAINT ck_rental_order_number CHECK (
        order_number ~ '^ORD-[0-9]{6,}$'
    ),
    CONSTRAINT ck_rental_order_status CHECK (status IN ('DRAFT', 'CANCELLED')),
    CONSTRAINT ck_rental_order_manager_name CHECK (
        length(btrim(manager_display_name)) BETWEEN 1 AND 255
    ),
    CONSTRAINT ck_rental_order_creator_name CHECK (
        length(btrim(created_by_display_name)) BETWEEN 1 AND 255
    ),
    CONSTRAINT ck_rental_order_creator_role CHECK (
        created_by_role IN (
            'SYSTEM_ADMIN', 'WMS_ADMIN', 'WAREHOUSE_MANAGER',
            'RENTAL_MANAGER', 'VIEWER'
        )
    ),
    CONSTRAINT ck_rental_order_creation_hash CHECK (
        creation_request_sha256 ~ '^[0-9a-f]{64}$'
    )
);

CREATE INDEX idx_rental_order_manager_updated
    ON public.rental_order (manager_id, updated_at DESC, id);
CREATE INDEX idx_rental_order_warehouse_updated
    ON public.rental_order (warehouse_id, updated_at DESC, id)
    WHERE warehouse_id IS NOT NULL;
CREATE INDEX idx_rental_order_client_updated
    ON public.rental_order (client_id, updated_at DESC, id);

CREATE TABLE public.rental_order_audit_event (
    id uuid NOT NULL,
    order_id uuid NOT NULL,
    event_type varchar(48) NOT NULL,
    actor_subject_id uuid NOT NULL,
    actor_role varchar(32) NOT NULL,
    subject_type varchar(48) NOT NULL,
    subject_id varchar(128) NOT NULL,
    previous_values jsonb,
    new_values jsonb,
    occurred_at timestamptz NOT NULL,
    CONSTRAINT rental_order_audit_event_pkey PRIMARY KEY (id),
    CONSTRAINT fk_rental_order_audit_order FOREIGN KEY (order_id)
        REFERENCES public.rental_order(id),
    CONSTRAINT ck_rental_order_audit_event_type CHECK (
        event_type IN (
            'ORDER_CREATED', 'CLIENT_SELECTED', 'CLIENT_CREATED',
            'WAREHOUSE_SELECTED', 'UNIT_ADDED', 'UNIT_ADD_CONFLICT',
            'UNIT_REMOVED', 'RESERVATION_CREATED', 'RESERVATION_RELEASED',
            'EQUIPMENT_ADDED', 'EQUIPMENT_INCREASED', 'EQUIPMENT_DECREASED',
            'WAREHOUSE_OPERATION_CREATED', 'ORDER_CHANGED', 'ORDER_CANCELLED'
        )
    ),
    CONSTRAINT ck_rental_order_audit_actor_role CHECK (
        actor_role IN (
            'SYSTEM_ADMIN', 'WMS_ADMIN', 'WAREHOUSE_MANAGER',
            'RENTAL_MANAGER', 'VIEWER'
        )
    ),
    CONSTRAINT ck_rental_order_audit_subject CHECK (
        subject_type ~ '^[A-Z][A-Z0-9_]{0,47}$'
        AND length(btrim(subject_id)) BETWEEN 1 AND 128
    ),
    CONSTRAINT ck_rental_order_audit_previous_values CHECK (
        previous_values IS NULL OR jsonb_typeof(previous_values) = 'object'
    ),
    CONSTRAINT ck_rental_order_audit_new_values CHECK (
        new_values IS NULL OR jsonb_typeof(new_values) = 'object'
    )
);

CREATE INDEX idx_rental_order_audit_order_time
    ON public.rental_order_audit_event (order_id, occurred_at DESC, id DESC);

CREATE TABLE public.rental_order_command_receipt (
    id uuid NOT NULL,
    order_id uuid NOT NULL,
    actor_subject_id uuid NOT NULL,
    operation_name varchar(96) NOT NULL,
    idempotency_key uuid NOT NULL,
    request_sha256 char(64) NOT NULL,
    completed_at timestamptz NOT NULL,
    CONSTRAINT rental_order_command_receipt_pkey PRIMARY KEY (id),
    CONSTRAINT fk_rental_order_command_receipt_order FOREIGN KEY (order_id)
        REFERENCES public.rental_order(id),
    CONSTRAINT uk_rental_order_command_receipt
        UNIQUE (actor_subject_id, operation_name, idempotency_key),
    CONSTRAINT ck_rental_order_command_receipt_operation CHECK (
        length(btrim(operation_name)) BETWEEN 1 AND 96
    ),
    CONSTRAINT ck_rental_order_command_receipt_hash CHECK (
        request_sha256 ~ '^[0-9a-f]{64}$'
    )
);

CREATE INDEX idx_rental_order_command_receipt_order
    ON public.rental_order_command_receipt (order_id, completed_at DESC, id DESC);

CREATE FUNCTION public.prevent_rental_order_audit_mutation()
RETURNS trigger
LANGUAGE plpgsql
AS $$
BEGIN
    RAISE EXCEPTION 'rental order audit evidence is append-only';
END;
$$;

CREATE TRIGGER trg_rental_order_audit_immutable
BEFORE UPDATE OR DELETE ON public.rental_order_audit_event
FOR EACH ROW EXECUTE FUNCTION public.prevent_rental_order_audit_mutation();
