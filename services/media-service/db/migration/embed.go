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
