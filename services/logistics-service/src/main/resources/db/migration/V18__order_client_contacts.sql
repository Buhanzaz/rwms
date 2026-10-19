ALTER TABLE public.order_client
    ADD COLUMN phone varchar(32),
    ADD COLUMN normalized_phone varchar(32),
    ADD COLUMN email varchar(320),
    ADD COLUMN normalized_email varchar(320);

ALTER TABLE public.order_client
    DROP CONSTRAINT uk_order_client_type_normalized;

ALTER TABLE public.order_client
    ADD CONSTRAINT ck_order_client_phone_projection CHECK (
        (phone IS NULL AND normalized_phone IS NULL)
        OR (
            phone IS NOT NULL
            AND normalized_phone IS NOT NULL
            AND normalized_phone ~ '^\+[1-9][0-9]{6,14}$'
        )
    ),
    ADD CONSTRAINT ck_order_client_email_projection CHECK (
        (email IS NULL AND normalized_email IS NULL)
        OR (
            email IS NOT NULL
            AND normalized_email IS NOT NULL
            AND length(normalized_email) BETWEEN 3 AND 320
            AND normalized_email = lower(normalized_email)
            AND normalized_email = btrim(normalized_email)
        )
    );

CREATE UNIQUE INDEX uk_order_client_type_phone
    ON public.order_client (client_type, normalized_phone)
    WHERE normalized_phone IS NOT NULL;
CREATE INDEX idx_order_client_contact_search
    ON public.order_client (client_type, normalized_phone, normalized_email, id);
