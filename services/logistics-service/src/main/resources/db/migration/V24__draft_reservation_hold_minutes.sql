ALTER TABLE public.rental_settings
    ADD COLUMN draft_reservation_hold_minutes integer NOT NULL DEFAULT 1440;

ALTER TABLE public.rental_settings
    ALTER COLUMN draft_reservation_hold_minutes DROP DEFAULT;

ALTER TABLE public.rental_settings
    ADD CONSTRAINT ck_rental_settings_draft_reservation_hold_minutes CHECK (
        draft_reservation_hold_minutes BETWEEN 1440 AND 14400
    );
