CREATE TABLE public.customer_notification (
    id uuid PRIMARY KEY,
    customer_subject_id uuid NOT NULL,
    order_id uuid NOT NULL REFERENCES public.rental_order(id),
    booking_id uuid REFERENCES public.presentation_booking(id),
    kind varchar(32) NOT NULL CHECK (kind = 'PAYMENT_EXPIRED'),
    message text NOT NULL CHECK (length(btrim(message)) > 0),
    created_at timestamptz NOT NULL,
    read_at timestamptz,
    CONSTRAINT uk_customer_notification_effect UNIQUE (customer_subject_id, order_id, kind),
    CONSTRAINT ck_customer_notification_read_at CHECK (read_at IS NULL OR read_at >= created_at)
);

CREATE INDEX ix_customer_notification_subject_created
    ON public.customer_notification (customer_subject_id, created_at DESC, id DESC);
