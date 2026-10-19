ALTER TABLE public.presentation_booking
    ADD COLUMN rental_months bigint,
    ADD CONSTRAINT ck_presentation_booking_rental_months
        CHECK (rental_months IS NULL OR rental_months >= 1);
