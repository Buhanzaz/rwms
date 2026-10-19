alter table queue_entry
    add column worker_works jsonb not null default '[]'::jsonb;

alter table queue_entry
    drop constraint if exists ck_queue_entry_worker_content;

alter table queue_entry
    add constraint ck_queue_entry_worker_content
        check (
            jsonb_typeof(worker_works) = 'array'
            and jsonb_typeof(worker_materials) = 'array'
            and jsonb_typeof(worker_comments) = 'array'
            and jsonb_typeof(source_media_references) = 'array'
        );
