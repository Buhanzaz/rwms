ALTER TABLE public.presentation_booking
    ADD COLUMN manager_action varchar(16),
    ADD COLUMN manager_action_idempotency_key uuid,
    ADD COLUMN manager_acted_at timestamptz;

-- Completed bookings before this cutover already had a draft order handed to their
-- manager. Mark them acknowledged so deployment never recreates historical alerts.
UPDATE public.presentation_booking
SET manager_action = 'KEEP_DRAFT',
    manager_action_idempotency_key = id,
    manager_acted_at = coalesce(completed_at, updated_at)
WHERE state = 'COMPLETED';

DO $$
BEGIN
  IF EXISTS (
    SELECT 1
    FROM public.presentation_booking
    WHERE state = 'COMPLETED'
      AND (
        manager_action IS NULL
        OR manager_action_idempotency_key IS NULL
        OR manager_acted_at IS NULL
      )
  ) THEN
    RAISE EXCEPTION 'Completed presentation bookings must be acknowledged during V22 backfill';
  END IF;
END;
$$;

ALTER TABLE public.presentation_booking
    ADD CONSTRAINT ck_presentation_booking_manager_action_fields CHECK (
        (
            manager_action IS NULL
            AND manager_action_idempotency_key IS NULL
            AND manager_acted_at IS NULL
        )
        OR (
            manager_action IN ('CONTINUE', 'KEEP_DRAFT')
            AND manager_action_idempotency_key IS NOT NULL
            AND manager_acted_at IS NOT NULL
        )
    );

CREATE INDEX idx_presentation_booking_manager_alert
    ON public.presentation_booking (completed_at ASC, id ASC)
    WHERE state = 'COMPLETED' AND manager_action IS NULL;
