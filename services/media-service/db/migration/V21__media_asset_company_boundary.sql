-- Media assets become explicitly company-owned. Existing production data was
-- created before multi-company support while every warehouse belonged to the
-- initial company, so that immutable company is the only safe legacy backfill.
alter table media_asset
    add column company_id uuid;

update media_asset
set company_id = 'ae0d6f97-f0c5-576a-9ea7-1ddcc1a03b48'::uuid
where company_id is null;

alter table media_asset
    alter column company_id set not null;

alter table media_asset_import_job
    add column company_id uuid;

update media_asset_import_job
set company_id = 'ae0d6f97-f0c5-576a-9ea7-1ddcc1a03b48'::uuid
where company_id is null;

alter table media_asset_import_job
    alter column company_id set not null;

create function prevent_media_asset_company_change()
returns trigger
language plpgsql
as $$
begin
    if new.company_id <> old.company_id then
        raise exception 'media asset company is immutable';
    end if;
    return new;
end;
$$;

create trigger media_asset_company_immutable
    before update of company_id on media_asset
    for each row execute function prevent_media_asset_company_change();

create function prevent_media_asset_import_company_change()
returns trigger
language plpgsql
as $$
begin
    if new.company_id <> old.company_id then
        raise exception 'media asset import company is immutable';
    end if;
    return new;
end;
$$;

create trigger media_asset_import_company_immutable
    before update of company_id on media_asset_import_job
    for each row execute function prevent_media_asset_import_company_change();

create index media_asset_company_owner_order_idx
    on media_asset (company_id, warehouse_id, owner_type, owner_id, media_kind,
        sort_order, created_at)
    where deleted_at is null;

create index media_asset_import_company_job_idx
    on media_asset_import_job (company_id, job_id);
