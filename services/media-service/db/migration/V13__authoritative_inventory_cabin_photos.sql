-- Promote one completed inventory photo batch to the current CABIN gallery
-- folder while retaining every prior association and every media/object row.

alter table media_cabin_photo
    add column gallery_folder_id uuid,
    add column inventory_id uuid,
    add column inventory_finding_id uuid,
    add column inventory_completed_at timestamptz,
    add column inventory_source_revision bigint,
    add column inventory_final_plan_version bigint,
    add column inventory_final_plan_sha256 char(64);

update media_cabin_photo photo
set gallery_folder_id=asset.folder_id
from media_asset asset
where asset.media_id=photo.media_id
  and photo.gallery_folder_id is null;

do $$
begin
    if exists (select 1 from media_cabin_photo where gallery_folder_id is null) then
        raise exception 'Cannot backfill every CABIN gallery folder from media_asset.folder_id';
    end if;
end
$$;

alter table media_cabin_photo
    alter column gallery_folder_id set not null,
    drop constraint media_cabin_photo_association_source_check,
    drop constraint media_cabin_photo_check,
    add constraint media_cabin_photo_association_source_check
        check (association_source in ('DIRECT','TASK_EVIDENCE','BACKFILL','INVENTORY')),
    add constraint media_cabin_photo_inventory_revision_check
        check (inventory_source_revision is null or inventory_source_revision >= 1),
    add constraint media_cabin_photo_inventory_plan_version_check
        check (inventory_final_plan_version is null or inventory_final_plan_version >= 1),
    add constraint media_cabin_photo_inventory_plan_sha256_check
        check (inventory_final_plan_sha256 is null
            or inventory_final_plan_sha256 ~ '^[0-9a-f]{64}$'),
    add constraint media_cabin_photo_source_audit_check check (
        (association_source = 'TASK_EVIDENCE'
            and task_board_entry_id is not null and media_generation > 0
            and inventory_id is null and inventory_finding_id is null
            and inventory_completed_at is null and inventory_source_revision is null
            and inventory_final_plan_version is null
            and inventory_final_plan_sha256 is null)
        or (association_source = 'INVENTORY'
            and task_board_entry_id is null and media_generation > 0
            and inventory_id is not null and inventory_finding_id is not null
            and inventory_completed_at is not null
            and inventory_source_revision is not null
            and inventory_final_plan_version is not null
            and inventory_final_plan_sha256 is not null)
        or (association_source in ('DIRECT','BACKFILL')
            and task_board_entry_id is null
            and inventory_id is null and inventory_finding_id is null
            and inventory_completed_at is null and inventory_source_revision is null
            and inventory_final_plan_version is null
            and inventory_final_plan_sha256 is null)
    ),
    add constraint media_cabin_photo_cover_folder_unique
        unique (cabin_id,media_id,gallery_folder_id);

create index media_cabin_photo_folder_gallery_idx
    on media_cabin_photo (cabin_id,gallery_folder_id,sort_order,attached_at,media_id);

alter table media_cabin_photo_library
    add column active_gallery_folder_id uuid;

update media_cabin_photo_library library
set active_gallery_folder_id=photo.gallery_folder_id
from media_cabin_photo photo
where photo.cabin_id=library.cabin_id
  and photo.media_id=library.cover_media_id
  and library.cover_media_id is not null;

do $$
begin
    if exists (
        select 1 from media_cabin_photo_library
        where cover_media_id is not null and active_gallery_folder_id is null
    ) then
        raise exception 'Cannot backfill active CABIN gallery folder from the current cover';
    end if;
end
$$;

alter table media_cabin_photo_library
    add constraint media_cabin_photo_library_active_folder_check check (
        (cover_media_id is null and active_gallery_folder_id is null)
        or (cover_media_id is not null and active_gallery_folder_id is not null)
    ),
    add constraint media_cabin_photo_library_active_cover_folder_fk
        foreign key (cabin_id,cover_media_id,active_gallery_folder_id)
        references media_cabin_photo (cabin_id,media_id,gallery_folder_id);

create table media_inventory_cabin_photo_receipt (
    idempotency_key uuid primary key,
    request_sha256 char(64) not null check (request_sha256 ~ '^[0-9a-f]{64}$'),
    inventory_id uuid not null,
    finding_id uuid not null,
    cabin_id uuid not null,
    warehouse_id uuid not null,
    completed_at timestamptz not null,
    source_revision bigint not null check (source_revision >= 1),
    final_plan_version bigint not null check (final_plan_version >= 1),
    final_plan_sha256 char(64) not null check (final_plan_sha256 ~ '^[0-9a-f]{64}$'),
    gallery_folder_id uuid not null,
    cover_media_id uuid not null,
    photo_count integer not null check (photo_count between 1 and 100),
    library_version bigint not null check (library_version >= 0),
    correlation_id uuid not null,
    applied_at timestamptz not null default clock_timestamp()
);

create index media_inventory_cabin_photo_receipt_source_idx
    on media_inventory_cabin_photo_receipt (
        cabin_id,completed_at,inventory_id,finding_id,source_revision,final_plan_version
    );

create table media_inventory_cabin_photo_watermark (
    cabin_id uuid primary key,
    warehouse_id uuid not null,
    completed_at timestamptz not null,
    request_sha256 char(64) not null check (request_sha256 ~ '^[0-9a-f]{64}$'),
    inventory_id uuid not null,
    finding_id uuid not null,
    source_revision bigint not null check (source_revision >= 1),
    final_plan_version bigint not null check (final_plan_version >= 1),
    final_plan_sha256 char(64) not null check (final_plan_sha256 ~ '^[0-9a-f]{64}$'),
    gallery_folder_id uuid not null,
    cover_media_id uuid not null,
    applied_at timestamptz not null default clock_timestamp(),
    foreign key (cabin_id,cover_media_id,gallery_folder_id)
        references media_cabin_photo (cabin_id,media_id,gallery_folder_id)
);
