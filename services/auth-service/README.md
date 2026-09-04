# RWMS Auth Service

[Русская версия](README.ru.md)

`auth-service` is RWMS's stateful identity and authorization authority. It owns
interactive users and workers, password credentials, global roles, warehouse
access grants, OAuth/OIDC client registration, token minting, and JWT/JWKS
publication.

It is deliberately not a general business service: cabins, warehouse lifecycle,
tasks, maintenance, inventory, logistics, and media remain owned by their
respective domain services. Those services validate issued Bearer JWTs locally
and make their own domain authorization decisions.

## Why this service exists

Identity and access policy must have one accountable owner. Letting each domain
service store its own passwords, clients, roles, and warehouse grants would
create duplicate accounts, contradictory permissions, inconsistent logout or
revocation behaviour, and a much larger credential attack surface.

| Problem | Auth-service responsibility | Result |
| --- | --- | --- |
| Clients need one trusted sign-in protocol | Run the standards-based OAuth 2.1/OIDC authorization server and publish JWKS | Panel and mobile clients integrate once; APIs validate signed JWTs without sharing passwords. |
| Users and workers need distinct access models | Own USER/WORKER subjects, credentials, principal type, and allowed client types | A worker token cannot be issued through a user-only client, and vice versa. |
| Services need stable authorization context | Mint subject, role, scope, audience, and warehouse-access claims | Downstream services can authorize locally without a synchronous call for every request. |
| Administrators need safe user changes | Fence mutable commands with `expectedVersion`, protect SYSTEM_ADMIN invariants, and revoke stored authorizations when access is removed | Stale updates return `409`; a user cannot silently retain a newly removed session/authorization. |
| Clients and secrets must evolve safely | Provision managed OAuth clients with explicit revisions and fail-closed validation | Security-relevant client configuration cannot drift silently between deployments. |
| Identity changes must reach other services reliably | Persist authorization events and use transactional outbox/inbox/replay controls | Kafka delivery is recoverable and does not become the system of record. |

## Ownership and boundaries

Auth-service owns:

- login, password verification and password changes for RWMS users and workers;
- OIDC sessions, OAuth authorization, token issuance, signing keys, JWKS, and
  registered clients;
- global roles, mobile/rental entitlements, and per-user warehouse grants;
- authorization events and their transactional delivery state.

It does not own:

- a warehouse's identity, status, timezone, or lifecycle — these belong to
  `warehouse-service`;
- business-level authorization inside another service's aggregate;
- the panel's OIDC PKCE transaction or long-lived browser token storage;
- public routing. Production clients reach it through
  [`api-gateway-service`](../api-gateway-service/README.md); private
  service-to-service calls use private addresses and service credentials.

This boundary prevents the authorization service from becoming a cross-domain
workflow engine or a shared database for product state.

## How sign-in and token issuance work

```text
Panel / manager app / ClientApp / WorkerApp / DriverApp
          |
          | Authorization Code + PKCE through public /auth/**
          v
API gateway (public edge; routing and transport policy only)
          |
          v
auth-service: login, consent/session, OAuth/OIDC authorization server
          |
          +--> signed access token / ID token
          +--> JWKS at the authorization-server endpoint
          |
          v
Owning API service: local JWT validation + domain authorization
```

The service uses the Authorization Code flow with PKCE for public panel and
mobile clients. Confidential internal clients use `client_credentials` with
their deployment-provided secret. Client policy explicitly defines grants,
authentication methods, scopes, audience, allowed principal type, redirect and
post-logout URIs, allowed origins, PKCE policy, and token lifetimes.

In production the public issuer is `<gateway>/auth`. The upstream service keeps
its native authorization-server paths (`/oauth2/**`, `/login`, `/logout`, and
`/api/**`); the gateway strips exactly one `/auth` prefix. The panel callback
remains `/auth/callback` and is panel-owned, not forwarded to auth-service.

WorkerApp and DriverApp share the same WORKER credential identity but use two
non-interchangeable public clients; both authorization requests require PKCE
`S256`. `rwms-worker-android` redirects to the
query-free HTTPS `/auth/worker/callback` and receives `worker.tasks`;
`rwms-driver-android` redirects to `/auth/driver/callback` and receives only
`driver.tasks`. Their native login clients validate and consume the redirect
Location in memory, so neither Android manifest exposes a custom-scheme or App
Link receiver. Both client IDs select the worker credential login surface.

ClientApp first obtains a CSRF cookie/header pair from `GET /api/auth/csrf` and
submits `POST /api/customer/v1/registrations`. Registration creates one active
`CUSTOMER` user, its private encoded credential, initial authorization fact,
and outbox row atomically. Before password hashing, auth-service consumes both
a durable per-source and a durable global fixed-window registration budget.
The account has no warehouse grants, manager-mobile
access, or rental-manager entitlement. It can use only `rwms-customer-android`,
whose query-free HTTPS callback is `/auth/customer/callback` and whose only
business scope is `customer.rental`; other users cannot use that client.

User access and ID tokens include the canonical claims `sub`,
`preferred_username`, `principal_type=USER`, `global_role`, and camel-case
`rentalAccess`, plus the managed `client_id` that minted the token. The public
contract, not this README, is authoritative for the exact claim and endpoint
shape.

## Public API and access rules

The canonical contract is
[`contracts/openapi/auth-service.yaml`](../../contracts/openapi/auth-service.yaml).
It defines the public administration and current-user projection API; OAuth/OIDC
authorization endpoints are standards-based.

| Public endpoint | Purpose | Access rule |
| --- | --- | --- |
| `GET /api/auth/csrf` | Bootstrap the registration CSRF cookie/header pair | Anonymous read. |
| `POST /api/customer/v1/registrations` | Create a customer-only credential and authorization stream | Anonymous with the exact CSRF cookie/header pair; login 3–64 portable characters, password 8–128 characters, and matching confirmation. |
| `GET /api/admin/users` | List administrable users | USER JWT with `SYSTEM_ADMIN` or `WMS_ADMIN`. |
| `POST /api/admin/users` | Create an administrable user | Same role; only `SYSTEM_ADMIN` may create a `SYSTEM_ADMIN` account. |
| `GET /api/admin/users/{id}` | Read one administrative user projection | USER JWT with `SYSTEM_ADMIN` or `WMS_ADMIN`. |
| `PUT /api/admin/users/{id}` | Version-fenced profile and authorization update | Same role; `expectedVersion` is required and `WMS_ADMIN` cannot manage `SYSTEM_ADMIN`. |
| `GET /api/users/me` | Read the active caller's current access projection | USER Bearer JWT. |

The service returns shared Problem Details for invalid, unauthenticated,
forbidden, not-found, conflict, and registration-rate-limit cases. A registration
`429` includes `Retry-After`; a missing throttle database fails closed instead
of spending password-hash capacity. Administrative user profile, password,
and warehouse-access mutations use optimistic concurrency. A `409` means the
caller must refresh authoritative state before retrying its command.

`SYSTEM_ADMIN` and `WMS_ADMIN` have all-warehouse access in the current-user
projection; other roles receive their active grants with their effective access
level. `rentalAccess` is a persisted entitlement, not a fact that callers should
infer from a role. When omitted during creation it defaults by role; an omitted
update preserves its current persisted value.

## Safety properties

- Passwords and password hashes are never returned in public responses or
  events. Delivering an initial or reset worker credential to a physical worker
  is a separate operational contract.
- Disabling a user blocks new form login and new token minting from its session.
  Already minted self-contained access tokens remain valid only until their
  short configured lifetime (five minutes in the current managed-client policy).
- Stored authorizations and consents are removed when a login or password is
  changed, or when a relevant access entitlement or client access is revoked.
  Client-specific revocation is used where a change affects only one client
  audience.
- Physical user deletion is deliberately fail-closed. Disabling is the supported
  operation until the absence of external audit history and active sessions can
  be proven.
- The last active `SYSTEM_ADMIN` cannot be disabled, demoted, or deleted; a
  non-system administrator cannot create or manage a system-administrator
  account.
- Incoming names cannot collide with reserved OAuth client identifiers.
- Case-insensitive self-registration of one login is serialized with a
  transaction-scoped advisory lock before password hashing and unique writes.
  Independently, auth-service atomically enforces configurable per-source and
  global fixed-window budgets before hashing. It stores only a SHA-256 source
  key, expires old counters, returns Russian Problem Details with `Retry-After`
  on `429`, and records rejection/unavailable metrics. Production ingress rate
  limiting remains defence in depth rather than the only protection.
- `CUSTOMER` users and `rwms-customer-android` are mutually exclusive with all
  other user clients during authorization-code and refresh-token exchange.
- `RENTAL_MANAGER` users can mint interactive tokens only through
  `rwms-rental-manager-web` or `rwms-rental-manager-android`. Both clients have
  only `rental.manage`; they cannot mint panel, logistics, or administration
  tokens. `rwms-admin-web` is restricted to `SYSTEM_ADMIN` and `WMS_ADMIN` and
  has only `admin.manage`.

These rules put durable access invariants where the credential and token owner
can enforce them transactionally, rather than relying on UI checks or every
downstream service to repeat them.

## OAuth client lifecycle

Managed clients are declared under `rwms.auth.oauth.clients`. Provisioning first
validates the complete configuration and then takes a PostgreSQL advisory lock.
An unchanged configuration is byte-stable: the registered-client ID, issued
timestamp, and encoded secret remain intact.

Security-relevant configuration or secret changes require a monotonically
increasing `revision`. `revoke-authorizations: true` with a new revision is the
explicit choice when stored authorizations and consents must be removed.
`enabled: false` retains the client and audit rows but makes lookup fail closed.
Once a managed client exists, removing it from configuration is a startup error;
disable it explicitly instead.

This is preferable to recreating clients at every startup: stable client IDs
preserve valid state, while a deliberate revision makes security changes
reviewable and prevents accidental reactivation after a deployment rollback.

The managed interactive inventory keeps the existing operations-manager client,
the dedicated rental-manager web and Android clients, the administration web
client, the CUSTOMER-only ClientApp client, and the two WORKER-only
WorkerApp/DriverApp clients separate. Dedicated clients require S256 PKCE, a
five-minute access token and a rotating 30-day refresh token. Changing a
callback, scope, or principal type requires its own revision and coordinated
client release; one client's refresh token cannot be exchanged through another
client ID.

The managed `inventory-service` machine client requests exactly one downstream
scope per token. Revision 5 added `media.inventory` for the completed-inventory
cabin-photo hand-off; revision 6 adds only `logistics.inventory` for authoritative
plan-wide supersession of logistics work. Every token keeps subject and `client_id`
equal to `inventory-service` and the `rwms-services` audience. Asset, maintenance,
media, logistics and warehouse scopes remain separate token requests.

The managed `asset-service` machine client revision 5 adds the existing
`media.asset` scope for an exact private READY-photo proof used by durable cabin
creation. It retains the separate `media.asset-import` scope for HTML-import
media; each downstream call still requests exactly one scope and keeps
`asset-service` as both subject and `client_id`.

The managed `task-board-service` machine client revision 4 adds only
`warehouse.identity.read` so the Driver Up shift owner can resolve the current warehouse name,
city, timezone and coordinates through the private warehouse boundary. The managed
`logistics-service` client revision 7 adds only `task-board.driver-shifts.plan` for idempotent
planner-to-shift snapshot publication. Logistics continues to request its existing downstream
scopes separately; neither addition is granted to mobile or browser clients.

The disabled-by-default `logistics-planner` machine client is reserved for the standalone route
simulator. When explicitly enabled, it uses only `client_credentials`, the `rwms-services`
audience, `client_secret_basic`, and the sole `logistics.planning` scope. Its runtime secret is read from
`LOGISTICS_PLANNER_CLIENT_SECRET`; enabling or changing the security shape requires the managed
client revision. It grants no user, warehouse-administration, or general read/write scope.

## Warehouse-grant validation

Warehouse identity belongs to `warehouse-service`. Auth-service therefore does
not create or alter warehouse records. It accepts only canonical UUIDs plus the
reviewed `spb` and `msk` aliases, then optionally verifies distinct requested
warehouses against Warehouse Service before persisting a grant or worker
credential.

`rwms.auth.warehouse-validation.enabled` is `false` by default. In that state,
the no-op port introduces no Warehouse Service URL, token URL, client secret, or
network call; known aliases are still canonicalized locally.

When enabled, the adapter obtains a bounded `client_credentials` token with
exactly `warehouse.read` and calls only the private singular existence endpoint.
It requires strict JSON, an exact matching canonical ID, and an active
warehouse. OAuth, network, malformed-response, missing, inactive, and invalid
identifier failures reject the complete mutation. JWT reads and token issuance
do not call Warehouse Service.

The adapter has no redirect following and no token cache. This keeps the default
deployment independent of Warehouse Service while making the enabled integration
explicit, bounded, and fail-closed.

## Eventing and recovery

PostgreSQL authorization projections and the event store are authoritative;
Kafka is at-least-once transport. Authorization and worker-access changes are
recorded together with their event stream and transactional outbox. Consumers
use inbox/deduplication and version-aware replay semantics so a duplicate,
out-of-order, or temporarily unavailable broker cannot become a second source
of truth.

The service contains replay, shadow-reconciliation, quarantine, and audit
components to prove or recover projection parity. These are operational recovery
mechanisms, not public command APIs and not a licence to repair business state
from an arbitrary Kafka message.

## Why this design is preferable

| Alternative | Problem | Chosen design |
| --- | --- | --- |
| Each service stores its own users and passwords | Duplicated credentials, conflicting roles, and no single revocation owner. | One identity authority; other services validate JWTs and own their domain authorization. |
| A gateway owns login, tokens, and clients | Makes the public transport edge stateful and couples it to identity persistence. | Gateway routes `/auth/**`; auth-service owns OIDC state and signing material. |
| Introspect every token at every API call | Adds latency and makes each domain request depend on auth-service availability. | Locally validate signed, short-lived JWTs using JWKS. |
| Infer permissions only from roles | Cannot represent an explicit persisted rental entitlement or scoped warehouse grants. | Include role and entitlement claims plus effective warehouse access. |
| Accept stale administrative commands | One administrator can silently overwrite another's change. | Require `expectedVersion` and reject stale writes with `409`. |
| Publish directly to Kafka in the business transaction | A database commit and broker publish can diverge during failure. | Persist event and outbox state transactionally; relay and recover delivery separately. |

## Local development

Start PostgreSQL from the repository root, then run the service with the
explicit development profile:

```powershell
docker compose up -d auth-db
.\gradlew.bat :services:auth-service:bootRun --args="--spring.profiles.active=dev"
```

On an empty development database, startup runs Flyway migration V2 before JPA
validation. A non-empty database without `flyway_schema_history` is rejected;
adopt it only through the documented version-2 preflight and explicit baseline
workflow.

The development profile enables the local `rwms_auth` PostgreSQL connection,
`admin` / `admin`, the local task-board client secret, and an ephemeral RSA
signing key. These defaults are disabled in base configuration. It also sets the
direct local issuer to `http://localhost:9000`; production uses the gateway
issuer ending in `/auth`.

`processResources` runs `npm ci` and `npm run build` in `ui/`, then packages
`ui/dist` under `BOOT-INF/classes/static`. The authorization server serves the
result at `GET /login`; `POST /login` remains Spring Security form-login
processing.

## Production requirements

Provide at least:

- `AUTH_DEV_DEFAULT_CREDENTIALS=false`;
- `AUTH_BOOTSTRAP_ADMIN_USERNAME` and a 12+-character
  `AUTH_BOOTSTRAP_ADMIN_PASSWORD`;
- `TASK_BOARD_CLIENT_SECRET` and every enabled client secret;
- `AUTH_SIGNING_KEY_STORE`, `AUTH_SIGNING_KEY_STORE_PASSWORD`, and
  `AUTH_SIGNING_KEY_ALIAS` for the persistent PKCS12 signing key;
- `AUTH_DB_URL`, `AUTH_DB_USERNAME`, and `AUTH_DB_PASSWORD` with a non-loopback
  PostgreSQL endpoint and deployment-specific credentials;
- a public HTTPS issuer/base, allowed origins, and registered redirect URIs;
- `CUSTOMER_ORIGIN` and `CUSTOMER_REDIRECT_URI` as same-origin HTTPS values,
  with the latter ending in the query-free `/auth/customer/callback` path;
- `WORKER_ORIGIN`, `WORKER_REDIRECT_URI`, `WORKER_POST_LOGOUT_REDIRECT_URI`,
  `DRIVER_ORIGIN`, `DRIVER_REDIRECT_URI`, and
  `DRIVER_POST_LOGOUT_REDIRECT_URI`, all same-origin HTTPS values with dedicated
  query-free callbacks.

Outside dev/test, startup refuses missing bootstrap credentials, client secrets,
signing material, and missing or development-default datasource settings. The
datasource guard runs after profile configuration is loaded and before the
application context can initialize Flyway, JPA, or a `DataSource`; its errors
name only the required variable and never print credential values. Production
session cookies are secure by default. The framework processes trusted
forwarding metadata; the public gateway is responsible for deriving it from
configured public values rather than accepting client-supplied forwarding
headers.

To activate Warehouse Service validation in one coordinated deployment:

1. Set `AUTH_WAREHOUSE_CLIENT_ENABLED=true` and increase
   `AUTH_WAREHOUSE_CLIENT_REVISION`.
2. Provide `AUTH_WAREHOUSE_CLIENT_SECRET` from the deployment secret store.
3. Set `AUTH_WAREHOUSE_VALIDATION_ENABLED=true`,
   `WAREHOUSE_SERVICE_INTERNAL_BASE_URL`, and `AUTH_WAREHOUSE_TOKEN_URI`.
4. Optionally set positive `AUTH_WAREHOUSE_CONNECT_TIMEOUT` and
   `AUTH_WAREHOUSE_READ_TIMEOUT` values; both are bounded to 30 seconds.

## Schema and safe changes

Flyway is the only active schema migration and checksum authority. Hibernate is
configured with `ddl-auto=validate`; it never creates, updates, or drops the
schema. Use the immutable service-local migrations under
`src/main/resources/db/migration/`. The detailed existing-database adoption
procedure is in [database/flyway/README.md](database/flyway/README.md).

When changing this service:

1. Start with the canonical OpenAPI or event contract and the owner rule; do not
   invent a gateway-only or client-only identity transition.
2. Keep passwords, password hashes, client secrets, refresh tokens, and
   signing-key material out of public DTOs, events, logs, and documentation.
3. Preserve the public/private address split: browser traffic uses gateway
   `/auth/**`; internal client-credentials traffic uses private service routes.
4. For a state or authorization change, preserve optimistic concurrency,
   event-stream ordering, transactional outbox/inbox, and replay safety.
5. Evolve a database change through immutable Flyway migration and JPA
   validation; never rely on Hibernate schema mutation.

Useful verification commands from the repository root are:

```bash
bash ./gradlew :services:auth-service:test
bash ./gradlew :services:auth-service:javadoc
```

## Primary references

- [Canonical public API](../../contracts/openapi/auth-service.yaml)
- [Authorization event schemas](../../contracts/events/)
- [Service configuration](src/main/resources/application.yaml)
- [Authorization-server configuration](src/main/java/dev/buhanzaz/rwms/auth/config/AuthorizationServerConfiguration.java)
- [User administration owner](src/main/java/dev/buhanzaz/rwms/auth/service/UserAdministrationService.java)
- [Eventing implementation](src/main/java/dev/buhanzaz/rwms/auth/eventing/)
- [Current project architecture](../../docs/project-knowledge/architecture.md)
- [Identity and access ownership](../../docs/project-knowledge/domain-logic.md)
