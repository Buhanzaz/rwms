package migration

import _ "embed"

// The executable embeds the immutable migration bytes only to verify the
// checksums recorded by external Flyway. It never executes these scripts.

//go:embed V1__media_schema.sql
var V1 []byte

//go:embed V2__media_runtime_recovery.sql
var V2 []byte

//go:embed V3__inventory_owner_proof.sql
var V3 []byte

//go:embed V4__cabin_owner_bindings.sql
var V4 []byte

//go:embed V4_1__prepare_legacy_photo_folder_backfill.sql
var V4_1 []byte

//go:embed V5__media_photo_folders.sql
var V5 []byte

//go:embed V5_1__restore_runtime_source_guard.sql
var V5_1 []byte

//go:embed V6__service_owner_proofs_and_soft_delete.sql
var V6 []byte

//go:embed V7__dynamic_cabin_owner_projection.sql
var V7 []byte

//go:embed V8__task_board_worker_media.sql
var V8 []byte

//go:embed V9__asset_import_worker.sql
var V9 []byte
