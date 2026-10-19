ALTER TABLE public.rental_settings
    ADD COLUMN manual_booking_hold_minutes integer NOT NULL DEFAULT 60;

ALTER TABLE public.rental_settings
    ALTER COLUMN manual_booking_hold_minutes DROP DEFAULT;

ALTER TABLE public.rental_settings
    ADD CONSTRAINT ck_rental_settings_manual_booking_hold_minutes CHECK (
        manual_booking_hold_minutes BETWEEN 5 AND 1440
    );
