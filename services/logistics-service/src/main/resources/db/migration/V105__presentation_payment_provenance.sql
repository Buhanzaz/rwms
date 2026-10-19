ALTER TABLE public.rental_order
    ADD COLUMN payment_confirmed_by_booking_id uuid
        REFERENCES public.presentation_booking(id);

ALTER TABLE public.rental_order DROP CONSTRAINT ck_rental_order_payment;

ALTER TABLE public.rental_order
    ADD CONSTRAINT ck_rental_order_payment CHECK ((
        (
            payment_state IS NULL
            AND payment_started_at IS NULL
            AND payment_expires_at IS NULL
            AND payment_resolved_at IS NULL
            AND payment_source IS NULL
            AND payment_confirmed_by_subject_id IS NULL
            AND payment_confirmed_by_booking_id IS NULL
        ) OR (
            payment_state IS NOT NULL
            AND payment_started_at IS NOT NULL
            AND payment_expires_at IS NOT NULL
            AND payment_expires_at = payment_started_at + interval '5 minutes'
            AND (
                (
                    payment_state IN ('PENDING', 'EXPIRING')
                    AND status = 'SAVED'
                    AND payment_resolved_at IS NULL
                    AND payment_source IS NULL
                    AND payment_confirmed_by_subject_id IS NULL
                    AND payment_confirmed_by_booking_id IS NULL
                ) OR (
                    payment_state = 'CONFIRMED'
                    AND status IN ('SAVED', 'FULFILLED', 'CLOSED', 'CANCELLED')
                    AND payment_resolved_at IS NOT NULL
                    AND payment_resolved_at >= payment_started_at
                    AND payment_resolved_at < payment_expires_at
                    AND (
                        (
                            payment_source IN ('CUSTOMER_TEST', 'MANAGER_CONFIRMATION')
                            AND payment_confirmed_by_subject_id IS NOT NULL
                            AND payment_confirmed_by_booking_id IS NULL
                        ) OR (
                            payment_source = 'PRESENTATION_TEST'
                            AND payment_confirmed_by_subject_id IS NULL
                            AND payment_confirmed_by_booking_id IS NOT NULL
                        )
                    )
                ) OR (
                    payment_state IN ('EXPIRED', 'CANCELLED')
                    AND status = 'CANCELLED'
                    AND payment_resolved_at IS NOT NULL
                    AND payment_resolved_at >= payment_started_at
                    AND (payment_state <> 'EXPIRED' OR payment_resolved_at >= payment_expires_at)
                    AND payment_source IS NULL
                    AND payment_confirmed_by_subject_id IS NULL
                    AND payment_confirmed_by_booking_id IS NULL
                )
            )
        )
    ) IS TRUE);
