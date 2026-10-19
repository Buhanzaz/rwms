# F0 Platform Foundation

Date: 2026-07-12.

F0 is a migration-safety and shared-technical foundation. It changes no
application deployable and does not authorize Stage 1.

## Completed facts

- Current auth/task-board PostgreSQL dumps, schema inventories, constraints,
  indexes, row counts, and JPA compatibility fixtures were captured outside Git.
- Both dumps restored into disposable PostgreSQL 17 containers with matching
  inventories and normalized schemas. Cleanup evidence confirms source temp
  files and restore containers were removed.
- Manifest checksums cover every backup artifact. The manifest digest for
  backup `20260712T203837Z` is stored in a detached operator-local index, not
  only in the backup subtree.
- Reviewed SQL release tooling uses immutable ordered directories, independent
  SQL checksums, PostgreSQL advisory locking, one transaction per release,
  `verify.sql`, and service-owned `rwms_schema_history`.
- `platform:technical-contracts` provides only immutable technical records and
  has an empty main runtime classpath.
- `platform:spring-boot-starter` provides conditional technical configuration,
  enforces production JPA `validate`, and never creates a security chain or
  shared persistence model.
- RabbitMQ `rwms.domain.v1`, exactly three retries, DLQ routing, confirms, and
  mandatory returns were verified against RabbitMQ 4.1 Testcontainers.
- Outbox and inbox are documented conventions only in F0; implementation and
  schema remain with each owning service.

## Schema cutover qualification

The target policy is JPA `update` for explicit local development, `create-drop`
for isolated tests, and `validate` for production, with reviewed SQL releases
applied by CI/CD. However, both existing services still load Liquibase today:

- `auth-service` is migrated only in F1;
- `task-board-service` is migrated only in F2.

Their `databasechangelog*` tables remain historical evidence and are not
deleted by F0.

Post-F0 update: F1 has since migrated `auth-service` to JPA plus reviewed SQL;
see `f1_auth_foundation.md`. The statement above remains the factual F0 closure
state. `task-board-service` still awaits F2.

## Stateless boundaries

- F3 gateway: stateless Spring edge, no DB or RabbitMQ participation.
- The former Stage 4 worker is superseded by one stateful combined Stage 3–4
  Go `media-service`: it owns PostgreSQL/Flyway media metadata, signed MinIO
  access, Kafka outbox/inbox and in-process transformations.

All other stateful services own one PostgreSQL database, their releases,
outbox/inbox, and domain state.

## Verification evidence

- technical contracts: 6 tests, zero failures/errors;
- common starter: 29 tests, zero failures/errors, including 3 RabbitMQ
  Testcontainers scenarios;
- ignored snapshot `summary.json`: `PASSED` for auth and task-board;
- both ignored `verification.json` files: stable source inventory, matching
  restored inventory/schema, and successful cleanup;
- SQL release tool tests cover validation, clean replay, upgrade, repeat,
  checksum drift, failed-verification rollback, and installed PowerShell hosts.

No credentials, private keys, database dumps, detached anchor values, or other
secrets are stored in this memory. Backup material remains ignored and the
detached anchor remains outside the repository.
