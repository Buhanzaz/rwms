# F1 Auth Foundation

Date: 2026-07-13.

F1 changes exactly one application deployable: `auth-service`. It closes the
auth schema-authority cutover and prepares gateway-compatible public OAuth
behavior. It does not implement F2 task-board changes, the F3 gateway, W1
warehouse, or any later domain service.

## Completed target facts

- Auth consumes `platform:technical-contracts` and
  `platform:spring-boot-starter` without adopting a shared JPA model or shared
  `SecurityFilterChain`.
- Liquibase dependency, runtime configuration, and changelog resources have
  been removed from auth. Flyway is absent.
- Historical `databasechangelog` and `databasechangeloglock` tables remain
  unused, unmodified evidence on upgraded databases. Clean target databases do
  not create them.
- `database/baseline/schema.sql` describes the reviewed auth/OAuth PostgreSQL
  baseline. `V0001__adopt-auth-schema` installs or non-destructively adopts it,
  verifies physical invariants, and is recorded by the common runner in
  auth-owned `rwms_schema_history`.
- Dev uses JPA `update`, isolated PostgreSQL tests use `create-drop`, and
  base/production uses `validate`; the common starter rejects unsafe production
  DDL modes.
- OAuth clients are declarative, validated before mutation, reconciled under a
  transactional advisory lock, and protected by revision/fingerprint checks.
  Disable and explicit authorization revocation are distinct operations.
- The base issuer is the future gateway `/auth` URL; explicit development keeps
  direct localhost auth. The auth service itself retains its internal endpoint
  paths.

## Migration evidence

- F0 foundation commit: `a3da339`.
- Auth final suite: 38 tests, zero failures, zero errors, one conditional
  operator-only skip.
- Separate real restored-F0 run: one test, zero failures/errors/skips after the
  common runner applied the auth release.
- Auth release script: clean install, repeat, checksum drift rejection, and
  failed-verification rollback passed.
- Restored F0 data digests were unchanged across one subject, three registered
  clients, nineteen OAuth authorizations, four historical Liquibase change
  rows, and the other existing auth tables.
- JPA `validate` and JDBC deserialization read every restored client and
  authorization.
- Login UI typecheck, lint, and production build passed.
- Independent DB and security reviews were clean after unsafe issuer and weak
  schema-definition checks were corrected. Disposable containers were removed.

## F3 obligation, not implementation

F3 must route public `/auth/**` to auth-service while stripping exactly one
prefix and supplying trusted forwarded metadata. The panel already owns
`/auth/callback`; that exact route must be excluded from the auth catch-all or
given higher-priority panel routing. F1 tests the prefix contract but does not
create or authorize a gateway implementation.

## Deferred and UNKNOWN

- Production ingress topology, trusted proxy CIDRs, TLS termination, service
  discovery, and deployment platform remain `UNKNOWN` until their gates.
- Production secret-manager technology and operational rotation rollout remain
  `UNKNOWN`; Git stores only environment-variable names and policy metadata.
- Retention and eventual cleanup of unused `databasechangelog*` remains
  `UNKNOWN` and requires a separate reviewed release.
- Production retention/cleanup periods for expired OAuth authorizations,
  consents, web sessions, and disabled client records remain `UNKNOWN`; F1
  preserves current persistence behavior.
- Existing self-contained access tokens remain valid until their five-minute
  expiry after a subject/client disable. Real-time revocation infrastructure is
  not introduced by F1.

## Evidence paths

- `services/auth-service/database/`
- `services/auth-service/src/main/java/dev/buhanzaz/rwms/auth/config/`
- `services/auth-service/src/main/resources/application.yaml`
- `services/auth-service/src/main/resources/application-dev.yaml`
- `services/auth-service/src/test/`
- `services/auth-service/README.md`
- `tools/migration/Apply-SchemaReleases.ps1`
