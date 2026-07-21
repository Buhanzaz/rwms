-- Preserve media grouping metadata in the read-only dossier projection.
-- Facts written before folder grouping existed remain independent one-photo
-- folders because no stronger historical relationship is proven.

alter table dossier_media_projection
    add column folder_id uuid;

update dossier_media_projection
set folder_id = media_id
where folder_id is null;

alter table dossier_media_projection
    alter column folder_id set not null;

create index dossier_media_projection_cabin_folder_idx
    on dossier_media_projection (generation_id, cabin_id, folder_id, media_id);
