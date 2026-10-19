ALTER TABLE public.customer_rental_session
    ADD COLUMN rental_terms_json text NOT NULL DEFAULT '[]';

ALTER TABLE public.customer_rental_session
    ADD CONSTRAINT ck_customer_rental_session_terms_json CHECK (
        jsonb_typeof(rental_terms_json::jsonb) = 'array'
    );

ALTER TABLE public.customer_delivery_slot
    ADD COLUMN road_route_confirmed boolean NOT NULL DEFAULT true,
    ADD COLUMN private_site_access_confirmed boolean NOT NULL DEFAULT false,
    ADD COLUMN failed_trip_charge_acknowledged boolean NOT NULL DEFAULT false,
    ADD COLUMN route_height_meters double precision NOT NULL DEFAULT 4.0,
    ADD COLUMN route_width_meters double precision NOT NULL DEFAULT 2.55,
    ADD COLUMN route_length_meters double precision NOT NULL DEFAULT 12.0,
    ADD COLUMN route_weight_tons double precision NOT NULL DEFAULT 18.0,
    ADD COLUMN route_axle_load_tons double precision NOT NULL DEFAULT 10.0,
    ADD COLUMN route_axle_count integer NOT NULL DEFAULT 3;

ALTER TABLE public.customer_delivery_slot
    ADD CONSTRAINT ck_customer_delivery_slot_route_attestations CHECK (
        road_route_confirmed
        AND private_site_access_confirmed = failed_trip_charge_acknowledged
    ),
    ADD CONSTRAINT ck_customer_delivery_slot_route_profile CHECK (
        route_height_meters > 0
        AND route_width_meters > 0
        AND route_length_meters > 0
        AND route_weight_tons > 0
        AND route_axle_load_tons > 0
        AND route_axle_count >= 2
    );

ALTER TABLE public.customer_scenario_capacity_command_receipt
    ADD COLUMN shift_count integer NOT NULL DEFAULT 0,
    ADD CONSTRAINT ck_customer_scenario_capacity_command_receipt_shift_count CHECK (
        shift_count >= 0
    );

CREATE TABLE public.customer_scenario_capacity_shift (
    id uuid NOT NULL,
    snapshot_id uuid NOT NULL,
    source_shift_id uuid NOT NULL,
    delivery_date date NOT NULL,
    shift_start time NOT NULL,
    shift_end time NOT NULL,
    break_minutes integer NOT NULL,
    cabin_capacity integer NOT NULL,
    CONSTRAINT customer_scenario_capacity_shift_pkey PRIMARY KEY (id),
    CONSTRAINT fk_customer_scenario_capacity_shift_snapshot FOREIGN KEY (snapshot_id)
        REFERENCES public.customer_scenario_capacity_snapshot(id) ON DELETE CASCADE,
    CONSTRAINT uk_customer_scenario_capacity_shift_source UNIQUE (snapshot_id, source_shift_id),
    CONSTRAINT ck_customer_scenario_capacity_shift_window CHECK (shift_start < shift_end),
    CONSTRAINT ck_customer_scenario_capacity_shift_capacity CHECK (
        break_minutes >= 0
        AND break_minutes < EXTRACT(EPOCH FROM (shift_end - shift_start)) / 60
        AND cabin_capacity BETWEEN 1 AND 2
    )
);

CREATE INDEX idx_customer_scenario_capacity_shift_date
    ON public.customer_scenario_capacity_shift (
        snapshot_id, delivery_date, shift_start, source_shift_id
    );

CREATE TABLE public.customer_cabin_acceptance (
    id uuid NOT NULL,
    version bigint NOT NULL DEFAULT 0,
    customer_subject_id uuid NOT NULL,
    booking_id uuid NOT NULL,
    order_id uuid NOT NULL,
    warehouse_id uuid NOT NULL,
    cabin_unit_id uuid NOT NULL,
    shipment_document_id uuid NOT NULL,
    shipment_line_id uuid NOT NULL,
    driver_task_id uuid NOT NULL,
    idempotency_key uuid NOT NULL,
    request_sha256 varchar(64) NOT NULL,
    signature_json text NOT NULL,
    signature_point_count integer NOT NULL,
    accepted_at timestamptz NOT NULL,
    CONSTRAINT customer_cabin_acceptance_pkey PRIMARY KEY (id),
    CONSTRAINT fk_customer_cabin_acceptance_booking FOREIGN KEY (booking_id)
        REFERENCES public.presentation_booking(id),
    CONSTRAINT fk_customer_cabin_acceptance_order FOREIGN KEY (order_id)
        REFERENCES public.rental_order(id),
    CONSTRAINT fk_customer_cabin_acceptance_document FOREIGN KEY (shipment_document_id)
        REFERENCES public.logistics_document(id),
    CONSTRAINT fk_customer_cabin_acceptance_line FOREIGN KEY (shipment_line_id)
        REFERENCES public.logistics_document_line(id),
    CONSTRAINT fk_customer_cabin_acceptance_driver_task FOREIGN KEY (driver_task_id)
        REFERENCES public.driver_logistics_task(id),
    CONSTRAINT uk_customer_cabin_acceptance_booking_cabin UNIQUE (booking_id, cabin_unit_id),
    CONSTRAINT uk_customer_cabin_acceptance_subject_key UNIQUE (
        customer_subject_id, idempotency_key
    ),
    CONSTRAINT ck_customer_cabin_acceptance_version CHECK (version >= 0),
    CONSTRAINT ck_customer_cabin_acceptance_request_sha256 CHECK (
        request_sha256 ~ '^[0-9a-f]{64}$'
    ),
    CONSTRAINT ck_customer_cabin_acceptance_signature CHECK (
        jsonb_typeof(signature_json::jsonb) = 'object'
        AND signature_point_count BETWEEN 1 AND 8192
    )
);

CREATE INDEX idx_customer_cabin_acceptance_booking
    ON public.customer_cabin_acceptance (booking_id, accepted_at, id);

CREATE TABLE public.customer_cabin_problem (
    id uuid NOT NULL,
    customer_subject_id uuid NOT NULL,
    booking_id uuid NOT NULL,
    order_id uuid NOT NULL,
    warehouse_id uuid NOT NULL,
    cabin_unit_id uuid NOT NULL,
    shipment_document_id uuid NOT NULL,
    shipment_line_id uuid NOT NULL,
    driver_task_id uuid NOT NULL,
    category varchar(32) NOT NULL,
    phase varchar(32) NOT NULL,
    description varchar(2000) NOT NULL,
    media_references_json text NOT NULL,
    idempotency_key uuid NOT NULL,
    request_sha256 varchar(64) NOT NULL,
    reported_at timestamptz NOT NULL,
    CONSTRAINT customer_cabin_problem_pkey PRIMARY KEY (id),
    CONSTRAINT fk_customer_cabin_problem_booking FOREIGN KEY (booking_id)
        REFERENCES public.presentation_booking(id),
    CONSTRAINT fk_customer_cabin_problem_order FOREIGN KEY (order_id)
        REFERENCES public.rental_order(id),
    CONSTRAINT fk_customer_cabin_problem_document FOREIGN KEY (shipment_document_id)
        REFERENCES public.logistics_document(id),
    CONSTRAINT fk_customer_cabin_problem_line FOREIGN KEY (shipment_line_id)
        REFERENCES public.logistics_document_line(id),
    CONSTRAINT fk_customer_cabin_problem_driver_task FOREIGN KEY (driver_task_id)
        REFERENCES public.driver_logistics_task(id),
    CONSTRAINT uk_customer_cabin_problem_subject_key UNIQUE (
        customer_subject_id, idempotency_key
    ),
    CONSTRAINT ck_customer_cabin_problem_category CHECK (
        category IN ('MISSING_EQUIPMENT', 'UNSUITABLE_CABIN', 'OTHER')
    ),
    CONSTRAINT ck_customer_cabin_problem_phase CHECK (
        phase IN ('BEFORE_ACCEPTANCE', 'AFTER_ACCEPTANCE')
    ),
    CONSTRAINT ck_customer_cabin_problem_description CHECK (
        length(btrim(description)) BETWEEN 1 AND 2000
    ),
    CONSTRAINT ck_customer_cabin_problem_media CHECK (
        jsonb_typeof(media_references_json::jsonb) = 'array'
        AND jsonb_array_length(media_references_json::jsonb) <= 20
    ),
    CONSTRAINT ck_customer_cabin_problem_request_sha256 CHECK (
        request_sha256 ~ '^[0-9a-f]{64}$'
    )
);

CREATE INDEX idx_customer_cabin_problem_booking
    ON public.customer_cabin_problem (booking_id, reported_at, id);
