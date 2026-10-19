create table customer_booking_change_alert_ack (
    id uuid primary key,
    mutation_id uuid not null references customer_booking_mutation(id),
    manager_id uuid not null,
    idempotency_key uuid not null,
    expected_version bigint not null check (expected_version >= 0),
    acknowledged_at timestamptz not null,
    unique (manager_id, mutation_id),
    unique (manager_id, idempotency_key)
);
create index ix_completed_customer_changes on customer_booking_mutation(completed_at, id)
    where state = 'COMPLETED';
