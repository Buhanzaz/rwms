create table customer_booking_change_charge (
    id uuid primary key,
    version bigint not null default 0,
    customer_subject_id uuid not null,
    booking_id uuid not null,
    booking_version bigint not null check (booking_version >= 0),
    order_id uuid not null references rental_order(id),
    warehouse_id uuid not null,
    warehouse_version bigint not null,
    warehouse_time_zone varchar(64) not null,
    old_slot_id uuid not null references customer_delivery_slot(id),
    slot_id uuid references customer_delivery_slot(id),
    slot_version bigint,
    target_delivery_date date,
    target_window_start time,
    target_window_end time,
    operation varchar(32) not null check (operation in ('CANCEL', 'RESCHEDULE')),
    idempotency_key uuid not null,
    request_sha256 varchar(64) not null,
    policy_version bigint not null,
    notice_days integer not null check (notice_days >= 0),
    notice_date date not null,
    delivery_date date not null,
    original_delivery_price_rubles bigint check (original_delivery_price_rubles >= 0),
    fee_mode varchar(16),
    fee_value numeric(21,2),
    amount_rubles bigint check (amount_rubles >= 0),
    settlement varchar(32) not null check (settlement in
        ('POLICY_UNCONFIGURED','PAYMENT_REQUIRED','NOT_REQUIRED','TEST_PAID','WAIVED')),
    application_state varchar(16) not null check (application_state in ('OFFERED','APPLYING','APPLIED')),
    support_phone varchar(16),
    mutation_id uuid unique references customer_booking_mutation(id),
    test_payment_requested boolean not null default false,
    waived_by uuid,
    waiver_reason varchar(2000),
    waiver_key uuid,
    waiver_expected_version bigint,
    expires_at timestamptz not null,
    created_at timestamptz not null,
    applied_at timestamptz,
    unique (customer_subject_id, idempotency_key),
    unique (waived_by, waiver_key),
    check ((operation='CANCEL' and slot_id is null and slot_version is null)
        or (operation='RESCHEDULE' and slot_id is not null and slot_version is not null and slot_version >= 0)),
    check ((operation='CANCEL' and target_delivery_date is null and target_window_start is null and target_window_end is null)
        or (operation='RESCHEDULE' and target_delivery_date is not null and target_window_start is not null
            and target_window_end is not null and target_window_start < target_window_end)),
    check ((application_state='OFFERED' and mutation_id is null and applied_at is null)
        or (application_state='APPLYING' and mutation_id is not null and applied_at is null)
        or (application_state='APPLIED' and mutation_id is not null and applied_at is not null)),
    check (settlement <> 'TEST_PAID' or (application_state='APPLIED' and test_payment_requested)),
    check (settlement <> 'WAIVED' or (waived_by is not null and waiver_key is not null
        and waiver_expected_version is not null and waiver_expected_version >= 0
        and waiver_reason is not null and length(trim(waiver_reason)) > 0)),
    check ((settlement in ('POLICY_UNCONFIGURED','WAIVED')) or amount_rubles is not null)
);
create index ix_customer_change_charge_order on customer_booking_change_charge(order_id, created_at desc);
