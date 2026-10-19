alter table rental_settings
    add column late_change_notice_days integer not null default 2,
    add column late_change_fee_mode varchar(16),
    add column late_change_fee_value numeric(21, 2),
    add column rental_support_phone varchar(16),
    add constraint ck_rental_settings_late_notice check (late_change_notice_days >= 0),
    add constraint ck_rental_settings_late_fee check (
        (late_change_fee_mode is null and late_change_fee_value is null)
        or (late_change_fee_mode is not null and late_change_fee_value is not null
            and late_change_fee_value >= 0
            and ((late_change_fee_mode = 'FIXED'
                  and late_change_fee_value = trunc(late_change_fee_value)
                  and late_change_fee_value <= 9223372036854775807)
                 or (late_change_fee_mode = 'PERCENT' and late_change_fee_value <= 100)))
    ),
    add constraint ck_rental_settings_support_phone check (
        rental_support_phone is null or rental_support_phone ~ '^\+[1-9][0-9]{7,14}$'
    );
