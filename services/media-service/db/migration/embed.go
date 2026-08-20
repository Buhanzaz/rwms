package migration

import _ "embed"

// The executable embeds the immutable migration bytes only to verify the
// checksums recorded by external Flyway. It never executes these scripts.

// V1 contains the immutable initial media schema used for checksum verification.
//
//go:embed V1__media_schema.sql
var V1 []byte

// V2 contains the immutable media runtime-recovery migration bytes.
//
//go:embed V2__media_runtime_recovery.sql
var V2 []byte

// V3 contains the immutable inventory owner-proof migration bytes.
//
//go:embed V3__inventory_owner_proof.sql
var V3 []byte

// V4 contains the immutable cabin owner-binding migration bytes.
//
//go:embed V4__cabin_owner_bindings.sql
var V4 []byte

// V4_1 contains the immutable photo-folder backfill preparation migration bytes.
//
//go:embed V4_1__prepare_legacy_photo_folder_backfill.sql
var V4_1 []byte

// V5 contains the immutable media photo-folder migration bytes.
//
//go:embed V5__media_photo_folders.sql
var V5 []byte

// V5_1 contains the immutable runtime source-guard restoration migration bytes.
//
//go:embed V5_1__restore_runtime_source_guard.sql
var V5_1 []byte

// V6 contains the immutable service owner-proof and soft-delete migration bytes.
//
//go:embed V6__service_owner_proofs_and_soft_delete.sql
var V6 []byte

// V7 contains the immutable dynamic cabin owner-projection migration bytes.
//
//go:embed V7__dynamic_cabin_owner_projection.sql
var V7 []byte

// V8 contains the immutable task-board worker-media migration bytes.
//
//go:embed V8__task_board_worker_media.sql
var V8 []byte

// V9 contains the immutable asset-import worker migration bytes.
//
//go:embed V9__asset_import_worker.sql
var V9 []byte

// V10 contains the immutable canonical cabin photo-library migration bytes.
//
//go:embed V10__canonical_cabin_photo_library.sql
var V10 []byte

// V11 contains the immutable bounded media-processing recovery migration bytes.
//
//go:embed V11__bounded_media_processing_recovery.sql
var V11 []byte

// V12 contains the immutable video playback-variant migration bytes.
//
//go:embed V12__video_playback_variant.sql
var V12 []byte

// V13 contains the immutable authoritative inventory CABIN-photo migration bytes.
//
//go:embed V13__authoritative_inventory_cabin_photos.sql
var V13 []byte
