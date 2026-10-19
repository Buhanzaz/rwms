ALTER TABLE public.rental_settings
    ADD COLUMN chat_selection_hold_minutes integer NOT NULL DEFAULT 10;

ALTER TABLE public.rental_settings
    ALTER COLUMN chat_selection_hold_minutes DROP DEFAULT;

ALTER TABLE public.rental_settings
    ADD CONSTRAINT ck_rental_settings_chat_hold_minutes CHECK (
        chat_selection_hold_minutes BETWEEN 1 AND 1440
    );
