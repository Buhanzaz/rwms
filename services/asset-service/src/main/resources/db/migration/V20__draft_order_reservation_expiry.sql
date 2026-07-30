ALTER TABLE public.order_unit_reservation
    ADD COLUMN draft_reservation_expires_at timestamptz;

CREATE INDEX idx_order_unit_reservation_draft_expiry
    ON public.order_unit_reservation (draft_reservation_expires_at, id)
    WHERE state = 'ACTIVE' AND draft_reservation_expires_at IS NOT NULL;
