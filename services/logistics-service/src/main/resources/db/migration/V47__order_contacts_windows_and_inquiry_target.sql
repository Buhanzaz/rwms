CREATE TABLE public.order_client_additional_contact (
    client_id uuid NOT NULL,
    position integer NOT NULL,
    contact_name varchar(255) NOT NULL,
    phone varchar(32) NOT NULL,
    CONSTRAINT order_client_additional_contact_pkey PRIMARY KEY (client_id, position),
    CONSTRAINT fk_order_client_additional_contact_client
        FOREIGN KEY (client_id) REFERENCES public.order_client(id) ON DELETE CASCADE,
    CONSTRAINT ck_order_client_additional_contact_position CHECK (position >= 0),
    CONSTRAINT ck_order_client_additional_contact_name
        CHECK (length(btrim(contact_name)) BETWEEN 1 AND 255),
    CONSTRAINT ck_order_client_additional_contact_phone
        CHECK (phone ~ '^\+[1-9][0-9]{6,14}$')
);

CREATE TABLE public.rental_order_additional_contact (
    order_id uuid NOT NULL,
    position integer NOT NULL,
    contact_name varchar(255) NOT NULL,
    phone varchar(32) NOT NULL,
    CONSTRAINT rental_order_additional_contact_pkey PRIMARY KEY (order_id, position),
    CONSTRAINT fk_rental_order_additional_contact_order
        FOREIGN KEY (order_id) REFERENCES public.rental_order(id) ON DELETE CASCADE,
    CONSTRAINT ck_rental_order_additional_contact_position CHECK (position >= 0),
    CONSTRAINT ck_rental_order_additional_contact_name
        CHECK (length(btrim(contact_name)) BETWEEN 1 AND 255),
    CONSTRAINT ck_rental_order_additional_contact_phone
        CHECK (phone ~ '^\+[1-9][0-9]{6,14}$')
);

ALTER TABLE public.rental_order_acceptable_delivery_date
    RENAME TO rental_order_desired_delivery_window;

ALTER TABLE public.rental_order_desired_delivery_window
    RENAME COLUMN delivery_date TO start_date;

ALTER TABLE public.rental_order_desired_delivery_window
    ADD COLUMN end_date date,
    ADD COLUMN time_from time,
    ADD COLUMN time_to time;

UPDATE public.rental_order_desired_delivery_window
SET end_date = start_date
WHERE end_date IS NULL;

ALTER TABLE public.rental_order_desired_delivery_window
    ALTER COLUMN end_date SET NOT NULL,
    DROP CONSTRAINT uq_rental_order_acceptable_delivery_date,
    DROP CONSTRAINT ck_rental_order_acceptable_delivery_date_position,
    ADD CONSTRAINT ck_rental_order_desired_delivery_window_position
        CHECK (position >= 0),
    ADD CONSTRAINT ck_rental_order_desired_delivery_window_dates
        CHECK (end_date >= start_date),
    ADD CONSTRAINT ck_rental_order_desired_delivery_window_times
        CHECK (
            (time_from IS NULL AND time_to IS NULL)
            OR (time_from IS NOT NULL AND time_to IS NOT NULL AND time_to > time_from)
        );

ALTER TABLE public.rental_order_desired_delivery_window
    RENAME CONSTRAINT rental_order_acceptable_delivery_date_pkey
    TO rental_order_desired_delivery_window_pkey;

ALTER TABLE public.rental_order_desired_delivery_window
    RENAME CONSTRAINT fk_rental_order_acceptable_delivery_date_order
    TO fk_rental_order_desired_delivery_window_order;

DROP INDEX public.idx_rental_order_acceptable_delivery_date;

CREATE INDEX idx_rental_order_desired_delivery_window
    ON public.rental_order_desired_delivery_window (start_date, end_date, order_id);

ALTER TABLE public.logistics_document
    ADD COLUMN scheduled_time time;

ALTER TABLE public.driver_logistics_task
    ADD COLUMN trip_number integer,
    ADD COLUMN scheduled_time time;

ALTER TABLE public.driver_logistics_task
    DROP CONSTRAINT ck_driver_logistics_task_document_group,
    ADD CONSTRAINT ck_driver_logistics_task_document_group
        CHECK (
            source_type <> 'LOGISTICS_DOCUMENT'
            OR task_kind IN ('SHIPMENT', 'RETURN', 'TRANSFER')
        );

WITH ranked_order_trips AS (
    SELECT document.id,
           row_number() OVER (
               PARTITION BY document.rental_order_id
               ORDER BY document.created_at, document.id
           ) AS trip_number
    FROM public.logistics_document document
    WHERE document.rental_order_id IS NOT NULL
)
UPDATE public.driver_logistics_task task
SET trip_number = COALESCE(ranked.trip_number, 1)::integer
FROM public.logistics_document document
LEFT JOIN ranked_order_trips ranked ON ranked.id = document.id
WHERE task.source_type = 'LOGISTICS_DOCUMENT'
  AND task.source_id = document.id;

-- A V46 grouped task could predate or outlive its local document projection because source_id was
-- deliberately opaque. Preserve such standalone history as its first stable trip instead of
-- making the upgrade fail or inventing a new owner link.
UPDATE public.driver_logistics_task
SET trip_number = 1
WHERE source_type = 'LOGISTICS_DOCUMENT'
  AND trip_number IS NULL;

ALTER TABLE public.driver_logistics_task
    ADD CONSTRAINT ck_driver_logistics_task_trip_number
        CHECK (trip_number IS NULL OR trip_number >= 1),
    ADD CONSTRAINT ck_driver_logistics_task_group_trip_number
        CHECK (source_type <> 'LOGISTICS_DOCUMENT' OR trip_number IS NOT NULL);

ALTER TABLE public.rental_inquiry
    ADD COLUMN rental_order_id uuid,
    ADD CONSTRAINT fk_rental_inquiry_rental_order
        FOREIGN KEY (rental_order_id) REFERENCES public.rental_order(id);

ALTER TABLE public.rental_inquiry
    ADD COLUMN creation_idempotency_key uuid;

UPDATE public.rental_inquiry
SET creation_idempotency_key = id
WHERE creation_idempotency_key IS NULL;

ALTER TABLE public.rental_inquiry
    ALTER COLUMN conversation_id DROP NOT NULL,
    ALTER COLUMN creation_idempotency_key SET NOT NULL,
    DROP CONSTRAINT uk_rental_inquiry_conversation,
    ADD CONSTRAINT uk_rental_inquiry_creation_key
        UNIQUE (manager_id, creation_idempotency_key);

CREATE UNIQUE INDEX uk_rental_inquiry_conversation
    ON public.rental_inquiry (conversation_id)
    WHERE conversation_id IS NOT NULL;

CREATE INDEX idx_rental_inquiry_rental_order
    ON public.rental_inquiry (rental_order_id, state, created_at DESC, id)
    WHERE rental_order_id IS NOT NULL;

ALTER TABLE public.rental_inquiry_outbox
    DROP CONSTRAINT uk_rental_inquiry_outbox_order,
    ADD CONSTRAINT uk_rental_inquiry_outbox_inquiry UNIQUE (inquiry_id);

ALTER TABLE public.client_presentation
    ADD COLUMN mode varchar(16) NOT NULL DEFAULT 'NORMAL',
    ADD COLUMN replacement_unit_ids_json jsonb NOT NULL DEFAULT '[]'::jsonb,
    ADD CONSTRAINT ck_client_presentation_mode
        CHECK (mode IN ('NORMAL', 'REPLACEMENT')),
    ADD CONSTRAINT ck_client_presentation_replacement_targets
        CHECK (
            jsonb_typeof(replacement_unit_ids_json) = 'array'
            AND (
                (mode = 'NORMAL' AND jsonb_array_length(replacement_unit_ids_json) = 0)
                OR (mode = 'REPLACEMENT' AND jsonb_array_length(replacement_unit_ids_json) > 0)
            )
        );

ALTER TABLE public.presentation_booking
    ADD COLUMN desired_delivery_windows_json jsonb NOT NULL DEFAULT '[]'::jsonb,
    ADD CONSTRAINT ck_presentation_booking_desired_delivery_windows
        CHECK (jsonb_typeof(desired_delivery_windows_json) = 'array');

ALTER TABLE public.shipment_furniture_movement_task
    ALTER COLUMN document_id DROP NOT NULL,
    ALTER COLUMN equipment_movement_task_id DROP NOT NULL,
    DROP CONSTRAINT ck_shipment_furniture_movement_task_line_count,
    ADD COLUMN order_id uuid,
    ADD COLUMN old_rental_item_id uuid,
    ADD COLUMN replacement_reason varchar(2000),
    ADD COLUMN replacement_actor_subject_id uuid,
    ADD COLUMN replacement_actor_role varchar(32),
    ADD COLUMN replacement_idempotency_key uuid,
    ADD COLUMN replacement_batch_idempotency_key uuid,
    ADD COLUMN replacement_pair_index integer,
    ADD COLUMN replacement_request_sha256 char(64),
    ADD COLUMN replacement_presentation_id uuid,
    ADD COLUMN replacement_source_reservation_id uuid,
    ADD COLUMN replacement_completed_at timestamptz,
    ADD COLUMN replacement_rejected_at timestamptz,
    ADD CONSTRAINT fk_shipment_furniture_movement_task_order
        FOREIGN KEY (order_id) REFERENCES public.rental_order(id),
    ADD CONSTRAINT ck_shipment_furniture_movement_task_line_count
        CHECK (line_count >= 0),
    ADD CONSTRAINT ck_shipment_furniture_movement_task_replacement
        CHECK (
            (
                replacement_idempotency_key IS NULL
                AND replacement_batch_idempotency_key IS NULL
                AND replacement_pair_index IS NULL
                AND old_rental_item_id IS NULL
                AND replacement_actor_subject_id IS NULL
                AND replacement_actor_role IS NULL
                AND replacement_request_sha256 IS NULL
                AND replacement_presentation_id IS NULL
                AND replacement_source_reservation_id IS NULL
                AND replacement_completed_at IS NULL
                AND replacement_rejected_at IS NULL
                AND document_id IS NOT NULL
                AND equipment_movement_task_id IS NOT NULL
                AND line_count > 0
            )
            OR (
                replacement_idempotency_key IS NOT NULL
                AND replacement_batch_idempotency_key IS NOT NULL
                AND replacement_pair_index >= 0
                AND order_id IS NOT NULL
                AND old_rental_item_id IS NOT NULL
                AND old_rental_item_id <> rental_item_id
                AND replacement_actor_subject_id IS NOT NULL
                AND replacement_actor_role IS NOT NULL
                AND replacement_request_sha256 ~ '^[0-9a-f]{64}$'
                AND NOT (
                    replacement_completed_at IS NOT NULL
                    AND replacement_rejected_at IS NOT NULL
                )
                AND (
                    equipment_movement_task_id IS NOT NULL
                    OR replacement_source_reservation_id IS NULL
                )
                AND (
                    replacement_completed_at IS NULL
                    OR equipment_movement_task_id IS NULL
                    OR replacement_source_reservation_id IS NOT NULL
                )
                AND (
                    (line_count = 0 AND equipment_movement_task_id IS NULL)
                    OR (line_count > 0 AND equipment_movement_task_id IS NOT NULL)
                )
            )
        ),
    ADD CONSTRAINT uk_shipment_furniture_movement_task_replacement_key
        UNIQUE (order_id, replacement_idempotency_key),
    ADD CONSTRAINT uk_shipment_furniture_movement_task_replacement_batch_pair
        UNIQUE (order_id, replacement_batch_idempotency_key, replacement_pair_index);

UPDATE public.shipment_furniture_movement_task link
SET order_id = document.rental_order_id
FROM public.logistics_document document
WHERE document.id = link.document_id
  AND link.order_id IS NULL;

CREATE INDEX idx_shipment_furniture_movement_task_pending_replacement
    ON public.shipment_furniture_movement_task
        (order_id, replacement_batch_idempotency_key, replacement_pair_index)
    WHERE replacement_idempotency_key IS NOT NULL
      AND replacement_completed_at IS NULL
      AND replacement_rejected_at IS NULL;

ALTER TABLE public.equipment_movement_task_line
    ADD COLUMN source_balance_id uuid;
