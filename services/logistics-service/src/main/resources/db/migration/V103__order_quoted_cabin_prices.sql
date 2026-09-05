-- Preserve the exact presentation offer when its selected cabin enters an order.
-- Historical terms have no reconstructable offer and intentionally remain unknown.
alter table rental_order_unit_term
    add column pricing_version bigint,
    add column monthly_price_rubles bigint,
    add constraint ck_rental_order_unit_term_quoted_price check (
        (pricing_version is null and monthly_price_rubles is null)
        or (pricing_version is not null and pricing_version >= 0
            and monthly_price_rubles is not null and monthly_price_rubles >= 0)
    );
