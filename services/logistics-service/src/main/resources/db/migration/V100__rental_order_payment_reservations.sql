ALTER TABLE public.rental_order
    ADD COLUMN payment_state varchar(24),
    ADD COLUMN payment_started_at timestamptz,
    ADD COLUMN payment_expires_at timestamptz,
    ADD COLUMN payment_resolved_at timestamptz,
    ADD COLUMN payment_source varchar(32),
    ADD COLUMN payment_confirmed_by_subject_id uuid;

ALTER TABLE public.rental_order
    ADD CONSTRAINT ck_rental_order_payment CHECK (
        (
            payment_state IS NULL
            AND payment_started_at IS NULL
            AND payment_expires_at IS NULL
            AND payment_resolved_at IS NULL
            AND payment_source IS NULL
            AND payment_confirmed_by_subject_id IS NULL
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
                ) OR (
                    payment_state = 'CONFIRMED'
                    AND status IN ('SAVED', 'FULFILLED', 'CLOSED', 'CANCELLED')
                    AND payment_resolved_at IS NOT NULL
                    AND payment_resolved_at >= payment_started_at
                    AND payment_resolved_at < payment_expires_at
                    AND payment_source IN ('CUSTOMER_TEST', 'MANAGER_CONFIRMATION')
                    AND payment_confirmed_by_subject_id IS NOT NULL
                ) OR (
                    payment_state IN ('EXPIRED', 'CANCELLED')
                    AND status = 'CANCELLED'
                    AND payment_resolved_at IS NOT NULL
                    AND payment_resolved_at >= payment_started_at
                    AND (payment_state <> 'EXPIRED' OR payment_resolved_at >= payment_expires_at)
                    AND payment_source IS NULL
                    AND payment_confirmed_by_subject_id IS NULL
                )
            ) IS TRUE
        )
    );

CREATE INDEX idx_rental_order_pending_payment
    ON public.rental_order(payment_expires_at, id)
    WHERE payment_state = 'PENDING' AND status = 'SAVED';
