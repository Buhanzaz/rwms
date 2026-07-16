# RWMS Media Service

The target Go media service owns media upload sessions, metadata, MinIO object
generations, image processing, video rotation and published media facts. It is
not a storage proxy: browsers upload and download directly from MinIO using
short-lived URLs that this service authorizes and signs.

The initial foundation preserves the proven legacy processor's `bimg/libvips`
behavior while changing the names to product semantics:

- `SMALL` WebP (`96px` default)
- `MEDIUM` WebP (`320px` default)
- `LARGE` WebP (`1280px` default)
- `ORIGINAL` for authorized estimate/work access

Videos are original-only. They follow images in presentation ordering and use
FFmpeg only when an operator asks to rotate them.

`db/migration/V1__media_schema.sql` is a Flyway migration. It must be applied
explicitly before this service starts; the Go process never creates or mutates
its schema outside versioned Flyway migrations.

The same migration includes subject-bound command idempotency records. An
`Idempotency-Key` may be retried only with the same request fingerprint; a
reused key with a different upload or rotation payload is a conflict.
