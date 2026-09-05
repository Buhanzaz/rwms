create table rental_pricing_settings (
    id uuid primary key,
    version bigint not null default 0,
    updated_by_subject_id uuid,
    updated_at timestamptz not null,
    constraint ck_rental_pricing_singleton check (id = '00000000-0000-0000-0000-000000000001'),
    constraint ck_rental_pricing_version check (version >= 0)
);

-- Asset-service owns these catalog identities. They intentionally have no cross-database FK.
create table rental_pricing_rate (
    settings_id uuid not null references rental_pricing_settings(id),
    rental_type_id uuid not null,
    category_id uuid not null,
    monthly_price_rubles bigint not null,
    primary key (settings_id, rental_type_id, category_id),
    constraint ck_rental_pricing_positive_rate check (monthly_price_rubles > 0)
);

-- Missing pairs mean exactly zero. GET never creates or repairs this singleton.
insert into rental_pricing_settings (id, version, updated_at)
values ('00000000-0000-0000-0000-000000000001', 0, current_timestamp);
