-- Furniture and cabin tariffs share one logistics-owned revision. Asset owns equipment identities.
create table rental_pricing_equipment_rate (
    settings_id uuid not null references rental_pricing_settings(id),
    equipment_id uuid not null,
    monthly_price_rubles bigint not null,
    primary key (settings_id, equipment_id),
    constraint ck_rental_pricing_equipment_positive_rate check (monthly_price_rubles > 0)
);

-- No backfill: a missing override means zero per unit per month, as in the cabin tariff table.
