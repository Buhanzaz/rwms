create table login_attempt_budget (
    scope_key varchar(24) not null,
    subject_fingerprint varchar(64) not null,
    window_generation uuid not null,
    window_started_at timestamptz not null,
    attempt_count bigint not null,
    expires_at timestamptz not null,
    updated_at timestamptz not null,
    constraint pk_login_attempt_budget primary key (scope_key, subject_fingerprint),
    constraint ck_login_attempt_budget_scope
        check (scope_key in ('SOURCE_INGRESS', 'SOURCE_AUTH', 'ACCOUNT_AUTH')),
    constraint ck_login_attempt_budget_fingerprint
        check (length(subject_fingerprint) = 64),
    constraint ck_login_attempt_budget_count
        check (attempt_count >= 0),
    constraint ck_login_attempt_budget_window
        check (expires_at > window_started_at)
);

create index ix_login_attempt_budget_expiry
    on login_attempt_budget (expires_at);
