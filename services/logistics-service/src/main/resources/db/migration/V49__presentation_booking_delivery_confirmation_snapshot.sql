-- A pending public confirmation can resume after asset conversion, so its normalized client
-- delivery payload must survive independently of the request. These nullable receipt fields do not
-- rewrite or replace the order-owned delivery columns, and keep every historical booking readable.
ALTER TABLE public.presentation_booking
    ADD COLUMN delivery_address varchar(1000),
    ADD COLUMN latitude numeric(9, 6),
    ADD COLUMN longitude numeric(10, 6),
    ADD COLUMN additional_contacts_json jsonb,
    ADD CONSTRAINT ck_presentation_booking_delivery_address
        CHECK (
            delivery_address IS NULL
            OR length(btrim(delivery_address)) BETWEEN 1 AND 1000
        ),
    ADD CONSTRAINT ck_presentation_booking_delivery_coordinates
        CHECK (
            (latitude IS NULL AND longitude IS NULL)
            OR (
                latitude IS NOT NULL
                AND longitude IS NOT NULL
                AND latitude BETWEEN -90 AND 90
                AND longitude BETWEEN -180 AND 180
            )
        ),
    ADD CONSTRAINT ck_presentation_booking_additional_contacts_json
        CHECK (
            additional_contacts_json IS NULL
            OR jsonb_typeof(additional_contacts_json) = 'array'
        );
