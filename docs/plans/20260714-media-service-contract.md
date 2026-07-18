# Combined Media Service Contract

## Product decision

The product replaces the formerly separate Stage 3 `media-service` and Stage 4
`photo-processing-service` with one Go `media-service`. It owns upload sessions,
media metadata, PostgreSQL state, MinIO object keys, processing and the media
Kafka facts. It is a single deployable; there is no target Go worker that owns a
separate database.

`wms-panel-old/photo-worker-go` remains read-only legacy evidence. Its
`bimg/libvips` transformation pipeline is reused in the new service rather than
being edited in place.

## Browser boundary

The panel never receives MinIO credentials or object keys. It calls
`media-service` through the API gateway to create upload sessions and retrieve
short-lived signed URLs. The browser then transfers bytes directly to MinIO
using those scoped URLs. A signed URL is issued only after local JWT validation
and warehouse-access authorization.

## Media forms

| Form | Object | Default maximum long edge | Use |
| --- | --- | ---: | --- |
| `SMALL` | WebP | 96 px | compact preview |
| `MEDIUM` | WebP | 320 px | warehouse, city and cabin review |
| `LARGE` | WebP | 1280 px | full-page viewer |
| `ORIGINAL` | source/canonical original | n/a | estimates and authorized work contexts |

The sizes intentionally preserve the proven legacy `tiny/thumb/preview`
defaults, but are service configuration rather than a panel contract.

Images retain an immutable ingress object and generate a current canonical
original plus all three WebP derivatives. The canonical original is the object
that estimates can request. A manual rotation creates a new immutable MinIO
generation and atomically makes it current; stale signed URLs naturally expire.
EXIF orientation is corrected before derivative generation.

Videos keep only an original object. They have no preview, transcode or reduced
variant. A requested rotation uses FFmpeg to write a new current original
generation. The panel renders videos after every image, both in a gallery and
in the full-screen viewer.

## State, commands and events

- State: `UPLOADING`, `PROCESSING`, `READY`, `FAILED`, `DELETED`.
- Retried upload-session creation and finalization use `Idempotency-Key`.
- Rotation is a versioned command with `expectedVersion`; stale mutations return
  `409`.
- A successful image finalization creates a processing request in the service
  transactional outbox. The same Go service consumes it from Kafka through an
  inbox-deduplicated worker path.
- Committed lifecycle facts are published on `rwms.media.media.v1`, keyed by
  media UUID. Processing requests use `rwms.media.processing.v1`; validation
  and exhausted transient failures use the consumer-owned DLT.
- Notification is a downstream consumer only; it never receives a storage
  credential or takes metadata ownership.

## Explicit open decisions

- Retention, orphan-cleanup retention period, maximum upload size and supported
  video codec list remain `UNKNOWN` until product approval. The implementation
  must fail truthfully for an unsupported media type rather than silently
  transcode it.
- A downstream owner is represented only by opaque `ownerType`/`ownerId` plus
  the warehouse UUID used for authorization. There are no cross-service
  foreign keys or joins.
- The active-stage pointer still names W1/Stage 2 in the current working tree.
  This user-approved combined service contract must be reconciled into the
  roadmap and pointer without claiming Stage 2 completed.

## Stage 8 private logistics readiness boundary (2026-07-17)

`POST /api/internal/media/v1/logistics/references/validate` is a private,
read-only service-to-service boundary. It accepts only an issuer/audience-valid
`SERVICE` JWT where `sub=client_id=logistics-service` and the one scope is
`media.logistics`. A USER token, another client, a combined or foreign scope,
or a malformed service identity fails closed.

The caller supplies a declared logistics owner type, document/line/warehouse
UUIDs and one to twenty unique opaque media ID/generation pairs. The service
derives the owner ID from the document line, verifies the exact owner,
warehouse and current `READY` generation, and responds only with those opaque
identifiers. It does not reveal object keys, URLs, file metadata or retention
policy. The receiver neither creates uploads/bindings nor changes media state,
schema, outbox or Kafka topology; its direct base-asset query does not call
the concurrent Stage 7 inventory owner-proof projection.
