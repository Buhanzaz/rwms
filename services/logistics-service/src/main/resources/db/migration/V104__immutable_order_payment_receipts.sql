create table rental_order_payment_receipt (
    id uuid primary key,
    order_id uuid not null unique,
    issued_at timestamptz not null,
    receipt_json text not null,
    constraint fk_rental_order_payment_receipt_order foreign key (order_id) references rental_order (id),
    constraint ck_rental_order_payment_receipt_json check ((
        jsonb_typeof(receipt_json::jsonb) = 'object'
        and receipt_json::jsonb ->> 'schemaVersion' = '1'
        and receipt_json::jsonb ->> 'orderId' = order_id::text
        and receipt_json::jsonb ->> 'currency' = 'RUB'
    ) is true)
);
