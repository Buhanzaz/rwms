-- Client-produced still-image variants. Existing source upload sessions and
-- media objects are retained unchanged; no user object or row is deleted.

alter table media_upload_session
    add column upload_mode varchar(24) not null default 'SOURCE'
        check (upload_mode in ('SOURCE', 'IMAGE_VARIANTS'));

create table media_upload_image_variant_part (
    media_id uuid not null references media_asset (media_id),
    variant varchar(16) not null check (variant in ('SMALL', 'MEDIUM', 'LARGE')),
    expected_content_length bigint not null
        check (expected_content_length between 1 and 1048576),
    expected_checksum_sha256 char(64) not null
        check (expected_checksum_sha256 ~ '^[0-9a-f]{64}$'),
    width integer not null check (width > 0),
    height integer not null check (height > 0),
    object_key varchar(1024) not null unique,
    upload_idempotency_key uuid,
    object_version_id varchar(255),
    etag varchar(255),
    uploaded_checksum_sha256 char(64),
    uploaded_at timestamptz,
    primary key (media_id, variant),
    check (
        (upload_idempotency_key is null and object_version_id is null and etag is null
            and uploaded_checksum_sha256 is null and uploaded_at is null)
        or
        (upload_idempotency_key is not null and object_version_id is not null and etag is not null
            and uploaded_checksum_sha256 = expected_checksum_sha256 and uploaded_at is not null)
    )
);

create index media_upload_image_variant_incomplete_idx
    on media_upload_image_variant_part (media_id, variant)
    where uploaded_at is null;

-- A client-produced LARGE object is also the logical ORIGINAL read fallback.
-- Both immutable variant rows intentionally point at the same pinned object.
alter table media_variant
    drop constraint if exists media_variant_object_key_key;

create unique index media_variant_object_key_variant_key
    on media_variant (object_key, variant);
