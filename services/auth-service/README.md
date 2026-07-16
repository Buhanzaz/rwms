# RWMS Auth Service

Spring Authorization Server for RWMS users, workers, OIDC clients, JWT/JWKS, and warehouse-access claims.

## Local development

Start PostgreSQL from the repository root, then run the service with the explicit dev profile:

```powershell
docker compose up -d auth-db
.\gradlew.bat :services:auth-service:bootRun --args="--spring.profiles.active=dev"
```

On an empty development database, application startup runs Flyway migration
V2 before JPA validation. A non-empty database without
`flyway_schema_history` is rejected; adopt it only through the documented
version-2 preflight and explicit baseline workflow.

The dev profile enables `admin` / `admin`, the local task-board client secret, and an ephemeral RSA signing key. These defaults are disabled in the base configuration.
It also overrides the public issuer to `http://localhost:9000`; production uses
the gateway issuer ending in `/auth`.

The Gradle `processResources` task runs `npm ci` and `npm run build` in `ui/`, then packages `ui/dist` into `BOOT-INF/classes/static`. The authorization server serves that build from `GET /login`; `POST /login` remains Spring Security's form-login processing endpoint.

## Production requirements

Set at least:

- `AUTH_DEV_DEFAULT_CREDENTIALS=false`
- `AUTH_BOOTSTRAP_ADMIN_USERNAME`
- `AUTH_BOOTSTRAP_ADMIN_PASSWORD` (at least 12 characters)
- `TASK_BOARD_CLIENT_SECRET`
- `AUTH_WAREHOUSE_CLIENT_SECRET` when Warehouse Service validation is activated
- `AUTH_SIGNING_KEY_STORE` (PKCS12)
- `AUTH_SIGNING_KEY_STORE_PASSWORD`
- `AUTH_SIGNING_KEY_ALIAS`
- database credentials and public HTTPS issuer/origins/redirect URIs

The service refuses to start outside dev/test when required bootstrap credentials, client secret, or the persistent signing keystore are absent.
Production session cookies are secure by default and forwarded headers are interpreted through Spring's framework strategy. The dev profile disables the secure-cookie flag for local HTTP only. The default worker UI origin is `http://localhost:8082`; port 8081 belongs to task-board-service.

OAuth clients are declared under `rwms.auth.oauth.clients`. Each client declares
its revision, grants, authentication methods, scopes, audience, allowed
principal type, redirect/logout URIs, CORS origins, PKCE policy and access-token
TTL. Confidential clients name an environment variable containing their secret;
the secret value is never part of configuration metadata or logs.

Provisioning validates the complete list before taking a PostgreSQL transaction
advisory lock. An unchanged configuration is byte-stable and preserves the
registered-client ID, issued timestamp and encoded secret. Security-relevant
configuration or secret changes require a monotonically increasing `revision`.
Set `revoke-authorizations: true` with a new revision only when stored
authorizations/consents must be deleted. `enabled: false` retains the client and
audit rows but makes lookup fail closed. Once a managed client exists, omitting
it is a startup error; disable it explicitly.

Self-contained JWTs minted before client/user disable or explicit revocation
remain valid until their five-minute expiry. Rotate
`TASK_BOARD_CLIENT_SECRET` with a revision increment and deploy auth-service and
task-board-service with the same new value.

## Warehouse grant validation

`rwms.auth.warehouse-validation.enabled` is `false` by default. In that state
auth-service creates a no-op local port: no Warehouse Service URL, token URL or
client secret is required, and login/token/read paths make no warehouse call.
Known `spb`/`msk` inputs are still stored as their canonical UUID; other
non-UUID grant and worker warehouse identifiers are rejected locally.

W1 activation is one coordinated deployment configuration change:

- set `AUTH_WAREHOUSE_CLIENT_ENABLED=true` and
  `AUTH_WAREHOUSE_CLIENT_REVISION=2`;
- provide `AUTH_WAREHOUSE_CLIENT_SECRET` from the deployment secret store;
- set `AUTH_WAREHOUSE_VALIDATION_ENABLED=true`,
  `WAREHOUSE_SERVICE_INTERNAL_BASE_URL`, and `AUTH_WAREHOUSE_TOKEN_URI`;
- optionally set positive bounded `AUTH_WAREHOUSE_CONNECT_TIMEOUT` and
  `AUTH_WAREHOUSE_READ_TIMEOUT` values (both must be at most 30 seconds).

The confidential client uses only `client_secret_basic`, `client_credentials`,
scope `warehouse.read`, and audience `rwms-services`. Enabling it without the
revision increase fails provisioning. Enabled mutation requests obtain that
token and call only singular
`GET /api/internal/warehouse/v1/warehouses/{warehouseId}/existence`. All distinct
warehouses are checked before any grant insert/delete/touch. Invalid IDs,
inactive/missing warehouses, malformed strict JSON, OAuth failures and network
failures reject the complete mutation. JWT reads and token issuance never call
Warehouse Service.

The adapter deliberately uses a conditional JDK `HttpClient` rather than adding
unconditional OAuth client auto-configuration to the Authorization Server. It
performs the standard client-credentials exchange, does not follow redirects,
caches no token, and exists only when validation is enabled. This keeps the
pre-W1 process free of URL, secret and startup dependencies.

## Schema migrations

Flyway is the only active auth schema migration, version and checksum authority.
Hibernate uses only `ddl-auto=validate` in base, development and test profiles;
it never creates, updates or drops the schema. Liquibase is not a runtime
dependency.

A new empty database is installed by the immutable cumulative migration
`src/main/resources/db/migration/V2__auth_schema.sql`. Start the application or
run `flyway migrate`; do not baseline a new database.

An existing post-F1C database must first pass
`database/flyway/verify-version-2.sql`, then an operator explicitly runs Flyway
`baseline` with `baselineVersion=2`, followed by `migrate` and `validate`.
`baselineOnMigrate` remains false in every profile. The exact operator sequence
is documented in `database/flyway/README.md`.

The old repository-level custom runner, `database/baseline`,
`database/releases`, `rwms_schema_history` and `databasechangelog*` are retained
unchanged as read-only migration evidence. They are no longer the active
production migration path.

Historical `V0001__adopt-auth-schema` supports both an empty PostgreSQL database and the
verified F0 schema. It does not create, alter or delete `databasechangelog*`;
those historical tables remain unused evidence. The runner owns only
`public.rwms_schema_history`.

Historical `V0002__canonicalize-warehouse-identifiers` maps only the reviewed
case-insensitive aliases `spb` and `msk`, and normalizes exact UUID text for USER
grants and WORKER subjects. It locks tables in a fixed order and performs all
unmapped-value, collision and version-overflow checks before updates. Changed
rows retain IDs, increment optimistic versions and update timestamps. There is
no reverse-alias compensating SQL; rollback is the verified pre-migration F0/F1
backup because reverse reconstruction would be ambiguous.

## Gateway prefix contract

The public production issuer is `<gateway>/auth`, while auth-service keeps its
internal endpoint paths (`/oauth2/**`, `/login`, `/logout`, `/api/**`). The
gateway must strip exactly one `/auth` prefix and provide trusted forwarded
headers including `X-Forwarded-Prefix: /auth`. Login UI form, CSRF, static image
and bundled asset paths are relative so they work both through the prefix and
directly in the dev profile.

The existing panel callback is `/auth/callback`. F3 must register this exact
callback route with higher priority to the panel (or otherwise exclude it from
the auth-service `/auth/**` route). It must never forward the callback to
auth-service. This explicit exclusion is the compatibility choice for F1; a
callback rename requires a later coordinated panel transition.

## Deliberate constraints

- Physical user deletion is fail-closed because this service cannot yet prove the absence of external audit history and all active sessions. Disable the user instead.
- Worker passwords and password hashes are never returned. Delivery of initial/reset credentials to the physical worker is a separate operational contract and remains `UNKNOWN`.
- Disabling a user prevents form login and prevents an existing session from minting another token. Already issued browser access tokens remain valid until their five-minute expiry; public browser clients receive no refresh token.
- `WMS_ADMIN` can administer ordinary users but cannot create, assign, or manage `SYSTEM_ADMIN` accounts. Only `SYSTEM_ADMIN` can do that; this is a fail-safe target hardening because the exact legacy privilege hierarchy is `UNKNOWN`.
- Admin profile, password, and warehouse-access mutations require `expectedVersion`; stale commands return HTTP 409.
