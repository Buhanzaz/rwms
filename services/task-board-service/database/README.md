# Task-board database releases

Flyway is the sole active schema/version/checksum mechanism. New databases
install cumulative `src/main/resources/db/migration/V4__task_board_schema.sql`;
an existing verified database is explicitly baselined at version 4 using
`database/flyway/verify-version-4.sql`. Hibernate validates every profile and
never creates, updates or drops this target schema.

`V0001__adopt-task-board-schema` adopts the exact fourteen-table F0 domain
schema. `V0002__add-integration-messaging` adds the immutable request
fingerprint and service-owned transactional outbox/inbox. Historical
`V0003__enforce-case-insensitive-identities` adds release-only PostgreSQL
functional unique indexes for class codes, worker logins and warehouse queue
codes after a fail-closed duplicate preflight. Hibernate `create-drop` cannot
express these functional/partial indexes; release-schema validation and the
operator release test are their canonical verification.

`V0004__add-worker-credential-operation` adds nullable durable fencing metadata
to `worker`: an operation UUID, the exact operation type and its start time.
The three values are either all null or all present. Supported types are
`CONFIGURE`, `RESET`, `DISABLE`, `CLEAR` and `RECONCILE_DISABLE`. Existing rows,
including legacy `PENDING` credentials, are not rewritten and no operation
metadata is synthesized. A legacy `PENDING` row with all-null metadata therefore
remains explicit manual-reconciliation evidence rather than being assigned a
guessed operation identity or timestamp.

Historical
`databasechangelog*` tables are never created, modified, or removed.

The historical custom runner and `rwms_schema_history` remain immutable
migration evidence; they are no longer the active runtime migration path.

`Test-TaskBoardSchemaRelease.ps1` remains a historical release-proof tool. The
Flyway integration suite proves clean V4 installation, repeat, checksum drift,
non-empty refusal, exact preflight, explicit baseline, retained data and JPA
validation. The conditional restored-F0 test uses operator-provided
`TASK_BOARD_F0_RESTORED_*` variables; backup contents never enter Git.

Outbox and inbox retention periods remain `UNKNOWN`; monitor growth and do not
purge either table without a reviewed release and replay/audit policy.

External task command idempotency uses the explicit canonical fingerprint
schema `task-board-create:v1`: fixed-order, length-prefixed normalized scalar
values and route steps hashed as UTF-8 SHA-256. This format is independent of
Jackson configuration; changing its fields or encoding requires a new schema
prefix and a compatibility decision.
