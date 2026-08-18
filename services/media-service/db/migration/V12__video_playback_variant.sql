-- Expand the immutable media generation vocabulary with the compressed MP4
-- playback derivative. Existing originals and image variants are unchanged.

alter table media_variant
    drop constraint media_variant_variant_check;

alter table media_variant
    add constraint media_variant_variant_check
    check (variant in ('SMALL', 'MEDIUM', 'LARGE', 'PLAYBACK', 'ORIGINAL'));
