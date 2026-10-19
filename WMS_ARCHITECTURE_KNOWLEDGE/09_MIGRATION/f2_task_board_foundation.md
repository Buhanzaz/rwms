# F2 Task-Board Foundation

Date: 2026-07-13.

F2 changes exactly one application deployable: `task-board-service`. It closes
the task-board schema-authority cutover, adopts the F0 technical/messaging
foundation, and hardens current API concurrency and credential integration. It
does not implement the F3 gateway, W1 warehouse, or Stage 2 production schedule
and notification parity.

## Completed target facts

- Task-board consumes `platform:technical-contracts` and
  `platform:spring-boot-starter` without a shared JPA model, repository,
  `SecurityFilterChain`, or domain authorization policy.
- Liquibase dependency, runtime configuration, and changelog resources have
  been removed. Flyway is absent. Historical `databasechangelog*` remains
  untouched evidence on upgraded databases.
- Reviewed releases V0001-V0004 adopt the F0 domain schema, add task-board-owned
  integration persistence, enforce case-insensitive operational identities,
  and add durable worker-credential operation metadata.
- Dev uses JPA `update`, isolated PostgreSQL tests use `create-drop`, and
  base/production uses only `validate` under the common safety validator.
- Current API ownership is preserved. Every mutable command requires a
  non-negative version; stale commands return `409` through the common
  `ApiProblem` transport.
- Stable `externalTaskId` registration is idempotent only for the same canonical
  `task-board-create:v1` fingerprint. Changed, cross-warehouse, and legacy-null
  fingerprint reuse fails closed.
- Created and cancelled board-task facts commit atomically with their outbox
  rows. The relay uses confirms, mandatory returns, database-time leases,
  owner/token fencing, head-of-line ordering, bounded retry, and recovery.
- The task-board self-audit consumer validates the canonical event and uses its
  own inbox for deduplication; it does not mutate task-board domain state.
- Worker credential effects are serialized per worker with a PostgreSQL session
  advisory lock. Durable operation metadata and local operation-ID fencing
  support status convergence and orphan recovery.

## Migration and verification evidence

- The common release runner passed V0001-V0004 clean install, ordered upgrade,
  repeat, checksum drift rejection, failed-verification rollback, exact
  `verify.sql`, and JPA `validate`.
- The operator restored the real F0 task-board backup, applied all four releases,
  recorded four `rwms_schema_history` rows, and passed JPA `validate`.
- Historical `databasechangelog` and `databasechangeloglock` digests with
  prefixes `9dc7` and `ae466` remained unchanged. Backup contents and detached
  trust-anchor values remain outside Git.
- The final service suite reports 76 tests, zero failures, zero errors, and one
  conditional restored-F0 skip. The separately enabled real restored-F0 test
  passed.
- Redocly OpenAPI and AsyncAPI validation passed.
- Independent database, messaging/security, and credential reviews were clean
  within the approved F1 auth contract.

## F3 obligation, not implementation

F3 must create the stateless edge on port `8088`, route the public `/auth` and
`/api/task-board` prefixes, keep `/auth/callback` panel-owned, validate external
JWTs in addition to downstream validation, and keep internal
client-credentials traffic off the public gateway. F2 creates no gateway and
does not modify its future runtime.

## Deferred and UNKNOWN

- Retention, archival, replay, and cleanup for outbox, inbox,
  `rwms_schema_history`, and unused `databasechangelog*` remain `UNKNOWN`.
- The production schedule evaluator, authoritative time/zone/DST/downtime
  behavior, worker inbox/push/offline delivery, and final Stage 2 HTTP/database
  contracts remain `UNKNOWN`. Browser schedules and notifications are MOCK.
- Auth-service does not accept a task-board credential operation token. A remote
  auth effect that completes after the configured fifteen-minute task-board
  orphan timeout is therefore not fenced end-to-end and remains `UNKNOWN`.
- Production broker retention, replay window, delivery SLA, monitoring, and
  operational ownership remain `UNKNOWN`.

## Evidence paths

- `services/task-board-service/database/`
- `services/task-board-service/src/main/java/dev/buhanzaz/rwms/taskboard/`
- `services/task-board-service/src/test/java/dev/buhanzaz/rwms/taskboard/`
- `contracts/openapi/task-board-service.yaml`
- `contracts/events/task-board-events.yaml`
- `tools/migration/Apply-SchemaReleases.ps1`
