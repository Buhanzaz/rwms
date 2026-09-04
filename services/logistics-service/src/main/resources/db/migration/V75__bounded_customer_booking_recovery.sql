ALTER TABLE public.presentation_booking
    ADD COLUMN recovery_next_attempt_at timestamptz,
    ADD COLUMN recovery_lease_token uuid,
    ADD COLUMN recovery_lease_until timestamptz,
    ADD COLUMN recovery_quarantined_at timestamptz;

UPDATE public.presentation_booking
SET recovery_next_attempt_at = COALESCE(updated_at, created_at)
WHERE state = 'PENDING'
  AND attempt_count < 8;

UPDATE public.presentation_booking
SET recovery_quarantined_at = COALESCE(updated_at, created_at)
WHERE state = 'PENDING'
  AND attempt_count >= 8;

ALTER TABLE public.presentation_booking
    ADD CONSTRAINT ck_presentation_booking_recovery_lease CHECK (
        (recovery_lease_token IS NULL) = (recovery_lease_until IS NULL)
    ),
    ADD CONSTRAINT ck_presentation_booking_recovery_state CHECK (
        (
            state = 'PENDING'
            AND (
                (
                    recovery_quarantined_at IS NULL
                    AND recovery_next_attempt_at IS NOT NULL
                )
                OR
                (
                    recovery_quarantined_at IS NOT NULL
                    AND recovery_next_attempt_at IS NULL
                    AND recovery_lease_token IS NULL
                    AND recovery_lease_until IS NULL
                )
            )
        )
        OR
        (
            state IN ('COMPLETED', 'REJECTED')
            AND recovery_next_attempt_at IS NULL
            AND recovery_lease_token IS NULL
            AND recovery_lease_until IS NULL
            AND recovery_quarantined_at IS NULL
        )
    );

DROP INDEX public.idx_presentation_booking_pending;

CREATE INDEX idx_presentation_booking_recovery_due
    ON public.presentation_booking (
        recovery_next_attempt_at ASC,
        created_at ASC,
        id ASC
    )
    WHERE state = 'PENDING' AND recovery_quarantined_at IS NULL;

ALTER TABLE public.customer_rental_session
    ADD COLUMN recovery_attempt_count integer NOT NULL DEFAULT 0,
    ADD COLUMN recovery_next_attempt_at timestamptz,
    ADD COLUMN recovery_lease_token uuid,
    ADD COLUMN recovery_lease_until timestamptz,
    ADD COLUMN recovery_quarantined_at timestamptz,
    ADD COLUMN recovery_last_error_code varchar(64);

UPDATE public.customer_rental_session
SET recovery_next_attempt_at = updated_at
WHERE state = 'CHECKOUT_PENDING'
  AND booking_id IS NOT NULL
  AND presentation_token IS NOT NULL;

ALTER TABLE public.customer_rental_session
    ADD CONSTRAINT ck_customer_rental_session_recovery_attempts CHECK (
        recovery_attempt_count >= 0
    ),
    ADD CONSTRAINT ck_customer_rental_session_recovery_lease CHECK (
        (recovery_lease_token IS NULL) = (recovery_lease_until IS NULL)
    ),
    ADD CONSTRAINT ck_customer_rental_session_recovery_owner CHECK (
        (
            recovery_next_attempt_at IS NULL
            AND recovery_lease_token IS NULL
            AND recovery_lease_until IS NULL
            AND recovery_quarantined_at IS NULL
            AND recovery_last_error_code IS NULL
            AND recovery_attempt_count = 0
        )
        OR
        (
            state = 'CHECKOUT_PENDING'
            AND booking_id IS NOT NULL
            AND presentation_token IS NOT NULL
        )
    ),
    ADD CONSTRAINT ck_customer_rental_session_recovery_state CHECK (
        NOT (
            state = 'CHECKOUT_PENDING'
            AND booking_id IS NOT NULL
            AND presentation_token IS NOT NULL
        )
        OR
        (
            recovery_quarantined_at IS NULL
            AND recovery_next_attempt_at IS NOT NULL
        )
        OR
        (
            recovery_quarantined_at IS NOT NULL
            AND recovery_next_attempt_at IS NULL
            AND recovery_lease_token IS NULL
            AND recovery_lease_until IS NULL
        )
    );

DROP INDEX public.idx_customer_rental_session_state_updated;

CREATE INDEX idx_customer_rental_session_recovery_due
    ON public.customer_rental_session (
        recovery_next_attempt_at ASC,
        updated_at ASC,
        id ASC
    )
    WHERE state = 'CHECKOUT_PENDING'
      AND booking_id IS NOT NULL
      AND presentation_token IS NOT NULL
      AND recovery_quarantined_at IS NULL;
