-- Restore the V2 runtime source-state invariant after V5 has assigned folders
-- to legacy quarantined rows. NOT VALID preserves those immutable legacy rows
-- while every new or subsequently updated runtime row remains checked.

alter table media_asset
    add constraint media_asset_runtime_source_state check (
        (processing_status = 'UPLOADING' and source_version_id is null)
        or (processing_status in ('PROCESSING','READY','FAILED','DELETED')
            and source_version_id is not null and source_checksum_sha256 is not null)
    ) not valid;
