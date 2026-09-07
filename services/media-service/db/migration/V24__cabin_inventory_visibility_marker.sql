-- Inventory visibility is an ordered CABIN-stream marker only. It must not
-- provide a warehouse, status, revision, or active owner proof to media.

alter table media_cabin_owner_inbox
    drop constraint media_cabin_owner_inbox_check2;

alter table media_cabin_owner_inbox
    add constraint media_cabin_owner_inbox_check2 check (
        (event_type in (
            'asset.rental-item.created.v1',
            'asset.rental-item.passport-changed.v1',
            'asset.rental-item.status-changed.v1',
            'asset.rental-item.warehouse-changed.v1',
            'asset.rental-item.logistics-effect-applied.v1'
        ) and warehouse_id is not null and rental_status is not null
          and owner_revision = aggregate_version and active is not null)
        or (event_type in (
            'asset.rental-item.inventory-visibility-changed.v1',
            'asset.rental-item.general-comment-changed.v1',
            'asset.rental-item.manual-note-added.v1'
        ) and warehouse_id is null and rental_status is null
          and owner_revision is null and active is null)
    );
