-- Existing links retain unknown historical prices; never backfill them from today's tariff.
alter table client_presentation_item
    add column pricing_version bigint,
    add column monthly_price_rubles bigint,
    add constraint ck_client_presentation_item_rental_price_snapshot
        check ((pricing_version is null and monthly_price_rubles is null)
            or (pricing_version is not null and pricing_version >= 0
                and monthly_price_rubles is not null and monthly_price_rubles >= 0));
