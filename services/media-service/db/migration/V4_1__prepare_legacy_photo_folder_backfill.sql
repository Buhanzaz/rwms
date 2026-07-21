-- V2 deliberately retained legacy unpinned assets behind recovery quarantine.
-- Its NOT VALID source-state constraint still rejects an otherwise unrelated
-- UPDATE of those rows. V5 must backfill folder_id for every retained asset, so
-- suspend only that runtime constraint while the service is offline between
-- Flyway migrations. V5_1 restores the exact guard before runtime can start.

alter table media_asset
    drop constraint media_asset_runtime_source_state;
