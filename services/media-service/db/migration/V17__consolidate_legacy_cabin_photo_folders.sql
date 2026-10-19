-- Consolidate the one-photo folders invented for pre-library CABIN images into
-- one retained legacy folder per cabin. Inventory and runtime upload folders
-- keep their original boundaries, and no media or association row is removed.

alter table media_cabin_photo_library
    drop constraint media_cabin_photo_library_active_cover_folder_fk;

with legacy_folder as (
    select cabin_id,min(gallery_folder_id::text)::uuid as gallery_folder_id
    from media_cabin_photo
    where association_source='BACKFILL'
    group by cabin_id
)
update media_cabin_photo photo
set gallery_folder_id=legacy_folder.gallery_folder_id
from legacy_folder
where photo.cabin_id=legacy_folder.cabin_id
  and photo.association_source='BACKFILL'
  and photo.gallery_folder_id<>legacy_folder.gallery_folder_id;

update media_cabin_photo_library library
set active_gallery_folder_id=photo.gallery_folder_id
from media_cabin_photo photo
where photo.cabin_id=library.cabin_id
  and photo.media_id=library.cover_media_id
  and library.active_gallery_folder_id is distinct from photo.gallery_folder_id;

do $$
begin
    if exists (
        select 1
        from media_cabin_photo
        where association_source='BACKFILL'
        group by cabin_id
        having count(distinct gallery_folder_id)<>1
    ) then
        raise exception 'Cannot consolidate every legacy CABIN photo folder';
    end if;
end
$$;

alter table media_cabin_photo_library
    add constraint media_cabin_photo_library_active_cover_folder_fk
        foreign key (cabin_id,cover_media_id,active_gallery_folder_id)
        references media_cabin_photo (cabin_id,media_id,gallery_folder_id);
