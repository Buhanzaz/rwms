create table customer_registration_throttle (
    scope_key varchar(16) not null,
    subject_fingerprint varchar(64) not null,
    window_started_at timestamptz not null,
    request_count bigint not null,
    expires_at timestamptz not null,
    updated_at timestamptz not null,
    constraint pk_customer_registration_throttle primary key (scope_key, subject_fingerprint),
    constraint ck_customer_registration_throttle_scope
        check (scope_key in ('CLIENT', 'GLOBAL')),
    constraint ck_customer_registration_throttle_fingerprint
        check (length(subject_fingerprint) = 64),
    constraint ck_customer_registration_throttle_count
        check (request_count > 0),
    constraint ck_customer_registration_throttle_window
        check (expires_at > window_started_at)
);

create index ix_customer_registration_throttle_expiry
    on customer_registration_throttle (expires_at);
