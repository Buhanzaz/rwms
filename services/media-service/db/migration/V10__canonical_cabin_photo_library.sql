-- Canonical cabin photo history and one explicit cover pointer.
-- Existing CABIN media is imported once; runtime reads must not fall back to
-- media_asset owner/sort-order after this migration.

create table media_cabin_photo (
    cabin_id uuid not null,
    media_id uuid not null references media_asset (media_id),
    warehouse_id uuid not null,
    media_generation integer not null check (media_generation >= 0),
    task_board_entry_id uuid,
    association_source varchar(24) not null
        check (association_source in ('DIRECT','TASK_EVIDENCE','BACKFILL')),
    sort_order bigint not null,
    attached_at timestamptz not null default clock_timestamp(),
    primary key (cabin_id,media_id),
    unique (media_id),
    check (
        (association_source = 'TASK_EVIDENCE' and task_board_entry_id is not null
            and media_generation > 0)
        or (association_source <> 'TASK_EVIDENCE' and task_board_entry_id is null)
    )
);

create index media_cabin_photo_gallery_idx
    on media_cabin_photo (cabin_id,sort_order,attached_at,media_id);

create table media_cabin_photo_library (
    cabin_id uuid primary key,
    warehouse_id uuid not null,
    cover_media_id uuid,
    version bigint not null default 0 check (version >= 0),
    updated_at timestamptz not null default clock_timestamp(),
    foreign key (cabin_id,cover_media_id)
        references media_cabin_photo (cabin_id,media_id)
);

create table media_cabin_cover_history (
    cabin_id uuid not null,
    version bigint not null check (version > 0),
    warehouse_id uuid not null,
    previous_media_id uuid,
    cover_media_id uuid not null,
    task_board_entry_id uuid,
    idempotency_key uuid,
    changed_at timestamptz not null,
    primary key (cabin_id,version),
    unique (idempotency_key)
);

create table media_cabin_cover_command (
    idempotency_key uuid primary key,
    request_sha256 char(64) not null check (request_sha256 ~ '^[0-9a-f]{64}$'),
    cabin_id uuid not null,
    task_board_entry_id uuid not null,
    evidence_media_id uuid not null,
    cover_version bigint not null check (cover_version >= 0),
    created_at timestamptz not null default clock_timestamp()
);

-- Preserve the existing presentation order and associate every extant logical
-- CABIN image, including one that is still processing at upgrade time.
insert into media_cabin_photo (
    cabin_id,media_id,warehouse_id,media_generation,association_source,sort_order,attached_at)
select asset.owner_id::uuid,asset.media_id,asset.warehouse_id,asset.current_generation,
       'BACKFILL',asset.sort_order,asset.created_at
from media_asset asset
where asset.owner_type='CABIN' and asset.media_kind='IMAGE'
  and asset.deleted_at is null
  and asset.owner_id ~ '^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$';

insert into media_cabin_photo_library (cabin_id,warehouse_id,cover_media_id,version,updated_at)
select distinct on (photo.cabin_id)
       photo.cabin_id,photo.warehouse_id,cover.media_id,
       case when cover.media_id is null then 0 else 1 end,
       coalesce(cover.created_at,clock_timestamp())
from media_cabin_photo photo
left join lateral (
    select candidate.media_id,candidate.created_at
    from media_cabin_photo candidate_photo
    join media_asset candidate on candidate.media_id=candidate_photo.media_id
    where candidate_photo.cabin_id=photo.cabin_id
      and candidate.processing_status='READY'
      and candidate.current_generation>0
      and candidate.deleted_at is null
      and media_asset_is_available(candidate.media_id)
    order by candidate_photo.sort_order,candidate.created_at,candidate.media_id
    limit 1
) cover on true
order by photo.cabin_id;

insert into media_cabin_cover_history (
    cabin_id,version,warehouse_id,previous_media_id,cover_media_id,
    task_board_entry_id,idempotency_key,changed_at)
select cabin_id,version,warehouse_id,null,cover_media_id,null,null,updated_at
from media_cabin_photo_library
where cover_media_id is not null;

-- Cover changes are media-owned facts keyed and ordered by cabin ID.
alter table media_transport_outbox
    drop constraint media_transport_outbox_contract_check;
alter table media_transport_outbox
    add constraint media_transport_outbox_contract_check check (
        (event_type in (
            'media.media.uploaded.v1','media.media.ready.v1','media.media.failed.v1',
            'media.media.rotated.v1','media.media.deleted.v1'
         ) and aggregate_type='MEDIA' and topic='rwms.media.media.v1'
            and record_key=aggregate_id and depends_on_event_id is null)
        or (event_type='media.processing.request.v1'
            and aggregate_type='PROCESSING_JOB' and aggregate_version=1
            and topic='rwms.media.processing.v1' and record_key=aggregate_id
            and depends_on_event_id is not null)
        or (event_type='media.processing.dlt.v1'
            and aggregate_type='PROCESSING_DLT' and aggregate_version=1
            and topic='rwms.media.processing.v1.media-service-processing-v1.dlt'
            and record_key=aggregate_id)
        or (event_type='media.inventory-owner.dlt.v1'
            and aggregate_type='INVENTORY_OWNER_DLT' and aggregate_version=1
            and topic='rwms.inventory.session.v1.media-service-inventory-owner-v1.dlt'
            and depends_on_event_id is null)
        or (event_type='media.cabin.cover-changed.v1'
            and aggregate_type='CABIN_PHOTO_LIBRARY'
            and topic='rwms.media.cabin-photo.v1'
            and record_key=aggregate_id and depends_on_event_id is null)
    );
