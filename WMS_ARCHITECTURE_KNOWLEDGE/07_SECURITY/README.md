# 07 Security

## Legacy Auth Model

Jmix security is enabled with:

- `jmix-security-starter`
- `jmix-security-flowui-starter`
- `jmix-security-data-starter`

Users are stored in `USER_`.
`DatabaseUserRepository` extends Jmix `AbstractDatabaseUserRepository<User>`.

The seeded admin user uses `{noop}admin` in `010-init-user.xml`.

## Jmix Resource Roles

Defined roles:

- `FullAccessRole`
  - code: `system-full-access`
  - grants all entity, attribute, view, menu, and specific policies.
- `UiMinimalRole`
  - code: `ui-minimal`
  - grants `MainView`, `LoginView`, and `ui.loginToUi`.

The system user is initialized with full access.

## Business Roles

`User.globalRole` values:

- `SYSTEM_ADMIN`
- `WMS_ADMIN`
- `WAREHOUSE_MANAGER`
- `RENTAL_MANAGER`
- `VIEWER`

Warehouse access values:

- `VIEW`
- `EDIT`
- `MANAGE`

Business authorization is mixed:

- Jmix resource roles control broad UI/entity access.
- `WarehouseAccessService` and other services apply business warehouse/global-role filtering.
- `UserWarehouseAccess` scopes users to warehouses.
- Reservation list behavior changes for rental manager role.

No central row-level security implementation was found. Row-level behavior is mostly service/view query logic.

## Mobile API Security Risk

Legacy security configuration:

- Matches `/public/**` and `/api/mobile/**`.
- Ignores CSRF for `/api/mobile/**`.
- Permits all matched requests.

`MobileApiController` wraps work in `SystemAuthenticator.begin("admin")`.

Implications:

- Mobile writes are anonymous in legacy.
- Mobile requests execute with admin privileges.
- No bearer token, API key, worker credential check, device registration, or per-warehouse auth was found.
- This must not be blindly preserved in Spring migration.

Migration requirement:

- Define explicit mobile/API authentication before implementing endpoints.
- Preserve DTO behavior, but replace trust model.
- Add tests proving anonymous writes are rejected unless intentionally allowed.

## Media Endpoint Security

`RepairMediaController` exposes:

- `GET /api/repair-media/{photoId}`
- `GET /api/rental-items/{rentalItemId}/latest-photos.zip`

These endpoints are not included in the custom permit-all matcher for `/api/mobile/**`.
Controller-level authorization checks were not found.
Effective access depends on the remaining Jmix/Spring filter chains and is `UNKNOWN`.

## UI Permissions

No explicit view/menu policies were found in view classes, XML descriptors, or `menu.xml`.
Effective screen visibility is likely determined by Jmix roles and data-level service filtering.

UNKNOWN:

- Exact intended ACL matrix per screen.
- Whether every menu item should remain visible for all authenticated UI users.
- How target React app should represent Jmix role assignment UI.

## Passwords And Worker Credentials

`User.password` uses the Jmix/Spring encoded password field.
`Worker` has `appLogin` and `appPassword`, but no authenticated worker API flow was found.

Migration warning:

- Treat `Worker.appPassword` as sensitive.
- Do not expose it through React or API without a deliberate credential design.

## Target OAuth2/OIDC Security (2026-07-11)

- `auth-service` is a Spring Authorization Server with issuer/JWKS discovery, RSA-signed JWT, Authorization Code + mandatory PKCE, five-minute access tokens, and an eight-hour authorization session.
- `rwms-panel` is a public USER-only client with `openid profile rwms.read rwms.write`. OIDC state and user data use `sessionStorage`; the browser receives no refresh token.
- `rwms-worker` is a separate public WORKER-only client with `worker.tasks`.
- `task-board-service` uses `client_credentials` and `worker-credentials.manage` for the internal worker-credential API. Service tokens carry `principal_type=SERVICE` and never inherit a database user's role by matching a client ID.
- USER JWT claims include `global_role`, effective `warehouse_access`, and `warehouse_access_all`; WORKER claims include `worker_id` and `warehouse_id`.
- `task-board-service` validates issuer, audience `rwms-services`, scopes, principal type, and warehouse access locally. It does not call auth-service for each user request.
- Browser API chains are stateless Bearer-only. Login remains session-based and CSRF-protected on the auth origin. OIDC logout uses a separate issuer/signature decoder, while resource APIs use an audience-restricted decoder so an ID token cannot substitute for an access token.
- Production startup requires HTTPS origins/redirects, secure cookies, an external PKCS12 signing key, bootstrap credentials, and explicit client/database secrets. Dev/test profiles provide local-only defaults.
- `SYSTEM_ADMIN` alone may manage another `SYSTEM_ADMIN`; `WMS_ADMIN` may manage non-system USER accounts. Self-disable and disabling the last active system administrator are rejected.
- Disabling an account prevents an existing web session from minting another JWT. Already-issued browser JWTs remain valid for at most five minutes.

Worker passwords are encoded and owned only by auth-service. Task-board stores `appLogin` plus `NOT_CONFIGURED|PENDING|ACTIVE|ERROR`; credential update/delete uses retryable auth-first sagas and never returns a password or password hash.

## CloudPub Mobile Development Auth (2026-07-11)

- The fixed development endpoint is
  `https://furiously-steadfast-crayfish.cloudpub.ru` and forwards to the panel
  Vite server on local port `8080`.
- For that exact host, the panel uses its own public origin as the OIDC authority.
- Vite proxies OIDC discovery/authorization/token/logout, login assets, and auth
  APIs to local auth-service port `9000`; no second public tunnel is required.
- Auth-service must run with profiles `dev,cloudpub`. The `cloudpub` profile sets
  the public issuer, panel origin, redirect URI, post-logout URI, and secure
  session cookie.
- A failed `signinRedirect()` is caught and rendered as an authentication error
  instead of leaving the panel indefinitely at `Проверяем сессию…`.

Evidence:

- `panel/vite.config.ts`
- `panel/src/features/auth/auth-config.ts`
- `panel/src/features/auth/auth-provider.tsx`
- `services/auth-service/src/main/resources/application-cloudpub.yaml`
- `services/auth-service/README.md`

Evidence:

- `services/auth-service/src/main/java/dev/buhanzaz/rwms/auth/config/AuthorizationServerConfiguration.java`
- `services/auth-service/src/main/java/dev/buhanzaz/rwms/auth/service/UserAdministrationService.java`
- `services/auth-service/src/main/java/dev/buhanzaz/rwms/auth/service/WorkerCredentialService.java`
- `services/task-board-service/src/main/java/dev/buhanzaz/rwms/taskboard/security/WarehouseAccessAuthorizer.java`
- `services/task-board-service/src/main/java/dev/buhanzaz/rwms/taskboard/service/OAuthWorkerCredentialGateway.java`

## Localhost Development Auth Restored (2026-07-11)

- The CloudPub OIDC proxy/profile experiment above is superseded at the user's
  request. Development login again uses auth-service issuer
  `http://localhost:9000` and panel redirect URI `http://localhost:8080/auth/callback`.
- Auth-service is running with the `dev` profile only.

Verification:

- Local OIDC discovery returns the localhost issuer, authorization endpoint,
  and token endpoint.

## Temporary Local Content Development Bypass (2026-07-11)

- The tracked `panel/.env.development` sets `VITE_DEV_AUTH_BYPASS=true`. The panel activates the bypass only when Vite exposes `import.meta.env.DEV`; a normal production build therefore retains the OIDC provider.
- In that local mode, `AuthProvider` supplies an in-memory `SYSTEM_ADMIN` USER with `warehouseAccessAll=true`. The API adapter recognizes its local marker and deliberately sends no `Authorization` header.
- `task-board-service` honors unauthenticated requests only when both the active Spring profile is `dev` and `rwms.security.dev-auth-bypass=true`. The latter property exists only in `application-dev.yaml`; all other profiles retain issuer/audience JWT validation and warehouse authorization.
- `auth-service` is intentionally unchanged: direct auth-administration endpoints still require OIDC. This bypass exists only to unblock local panel and task-board content work.

Evidence:

- `panel/src/features/auth/auth-config.ts`
- `panel/src/features/auth/auth-provider.tsx`
- `panel/src/lib/api-client.ts`
- `services/task-board-service/src/main/java/dev/buhanzaz/rwms/taskboard/config/SecurityConfiguration.java`
- `services/task-board-service/src/main/java/dev/buhanzaz/rwms/taskboard/security/WarehouseAccessAuthorizer.java`

## F1 Declarative OAuth And Gateway-Prefix Foundation (2026-07-13)

F1 preserves the USER/WORKER separation, PKCE browser flows, service
client-credentials flow, JWT/JWKS, five-minute access tokens, login session,
CSRF, logout, and local issuer/audience validation while making OAuth clients
configuration-driven.

Each managed client declares its enabled state, monotonic revision,
authentication/grant contract, scopes, audiences, allowed principal types,
redirect/logout URIs, allowed origins, PKCE policy, token TTL, and external
secret source. Reconciliation validates the complete configuration before a
transactional PostgreSQL advisory lock is taken:

- unchanged configuration is byte-stable and preserves the registration ID,
  issued-at timestamp, encoded secret, authorizations, and consents;
- security-relevant configuration or secret changes require a higher revision;
- `enabled=false` preserves the stored client and audit rows but makes public
  lookup fail closed;
- explicit `revoke-authorizations=true` at a new revision removes stored
  authorizations and consents;
- omitted managed clients, revision rollback, an invalid/missing production
  secret, invalid audience/scope/grant combinations, and unsafe origins fail
  startup before partial provisioning.

The base issuer is the future public gateway URL `http://localhost:8088/auth`
unless production overrides it with HTTPS. The explicit `dev` profile retains
direct `http://localhost:9000`. Prefix-aware login, CSRF, static assets,
authorization, token, JWKS, discovery, and logout behavior is tested without
trusting arbitrary forwarded host/prefix values.

F3 still owns the actual edge. It must strip exactly one `/auth` prefix, accept
forwarded headers only from trusted infrastructure, and route the panel's
existing `/auth/callback` to the panel rather than the auth-service catch-all.
This obligation is not evidence that the gateway exists.

Verification evidence:

- final auth suite: 38 tests, zero failures/errors, one operator-only conditional
  skip;
- separate restored-F0 test: one test, zero failures/errors/skips after applying
  the common schema runner;
- login UI typecheck, lint, and production build passed;
- independent security review resolved unsafe issuer/forwarded-header findings.

Evidence:

- `services/auth-service/src/main/java/dev/buhanzaz/rwms/auth/config/OAuthClientProperties.java`
- `services/auth-service/src/main/java/dev/buhanzaz/rwms/auth/config/OAuthClientProvisioner.java`
- `services/auth-service/src/main/java/dev/buhanzaz/rwms/auth/config/ConfiguredRegisteredClientRepository.java`
- `services/auth-service/src/main/java/dev/buhanzaz/rwms/auth/config/AuthorizationServerConfiguration.java`
- `services/auth-service/src/test/java/dev/buhanzaz/rwms/auth/AuthGatewayPrefixIntegrationTest.java`
- `services/auth-service/src/test/java/dev/buhanzaz/rwms/auth/config/OAuthClientProvisionerIntegrationTest.java`

## F2 Task-Board Security Hardening (2026-07-13)

F2 keeps task-board APIs stateless and fail-closed outside the explicit dev
bypass. The service locally validates RSA signature, issuer, audience
`rwms-services`, expiry, scopes, `principal_type`, and warehouse claims. Tests
reject wrong signatures, issuer/audience, expired tokens, panel ID-token
audience, malformed warehouse grants, and invalid worker identity claims.

Authorization remains service-owned rather than part of the common starter:

- USER operations require the documented read/write scope and effective
  warehouse access; malformed claim shapes deny access instead of producing a
  server error;
- WORKER operations require `worker.tasks` and a valid `worker_id`;
- the internal queue-reference API requires `principal_type=SERVICE`,
  `queue-registry.write`, and an explicitly configured client-ID allowlist;
- worker-credential calls obtain only `worker-credentials.manage`; missing or
  insufficient granted scope fails closed before the credential request;
- filter-level 401/403 and controller errors use the shared `ApiProblem`
  transport with correlation IDs, while task-board retains its domain-specific
  authorization decisions.

Mutable JSON and query commands require present, non-negative optimistic
version tokens. Stale state returns the canonical `409`; missing/null/negative
tokens return `400`. CORS allows the configured panel/worker origins and exposes
the correlation header, not arbitrary origins.

Worker credential calls are serialized per worker with a PostgreSQL session
advisory lock. Durable operation metadata and local operation-ID fencing prevent
a stale local completion from clearing a replacement operation. Auth-service
does not yet accept the operation ID, so an auth effect that completes after the
configured fifteen-minute orphan timeout is still an explicit cross-service
fencing `UNKNOWN`.

Evidence:

- `services/task-board-service/src/main/java/dev/buhanzaz/rwms/taskboard/config/SecurityConfiguration.java`
- `services/task-board-service/src/main/java/dev/buhanzaz/rwms/taskboard/security/`
- `services/task-board-service/src/main/java/dev/buhanzaz/rwms/taskboard/service/OAuthWorkerCredentialGateway.java`
- `services/task-board-service/src/main/java/dev/buhanzaz/rwms/taskboard/service/WorkerCredentialOperationCoordinator.java`
- `services/task-board-service/src/test/java/dev/buhanzaz/rwms/taskboard/TaskBoardJwtValidationIntegrationTest.java`
- `services/task-board-service/src/test/java/dev/buhanzaz/rwms/taskboard/TaskBoardSecurityPolicyTest.java`
- `services/task-board-service/src/test/java/dev/buhanzaz/rwms/taskboard/OAuthWorkerCredentialGatewayIntegrationTest.java`

## F3 Edge Security And OIDC Cutover (2026-07-13)

The F1 gateway-prefix obligation is now implemented. The panel uses the public
gateway origin for OIDC Authorization Code with PKCE and protected API calls;
the gateway routes auth protocol traffic while leaving the exact
`/auth/callback` path to the panel.

- `/api/**` is stateless Bearer-only at the edge. JWT signature, expiry,
  configured public issuer, and `rwms-services` audience are checked before
  routing; downstream resource servers repeat their own authorization checks.
- Missing, malformed, expired, wrong-signature, wrong-issuer, wrong-audience,
  and panel ID-token requests fail closed. Gateway authentication/authorization
  failures use the shared `ApiProblem` shape with the canonical correlation ID.
- Auth cookies and CSRF form data are preserved only on the auth route. Cookies
  are not forwarded to protected API routes, and internal auth administration
  or task-board internal paths are not exposed by public prefixing.
- Incoming `Forwarded`, every `X-Forwarded-*` variant, and equivalent scheme
  headers are stripped. The auth route receives only canonical forwarded
  metadata synthesized from the validated public base URL. Production requires
  HTTPS configuration and ingress preservation of the expected public `Host`.
- CORS uses configured exact origins with credentials and the required OIDC/API
  headers. Correlation values are validated/replaced at the edge rather than
  blindly propagated.
- The gateway owns no login session, token storage, refresh/token exchange, or
  domain authorization. Login session, CSRF, authorization consent, JWKS, and
  logout remain owned by `auth-service`.

The disposable browser flow proved discovery issuer, PKCE admin login and
callback, authenticated `/api/users/me`, task-board settings through both JWT
validation layers, logout, browser Back, an empty console, and a 390 px layout
without horizontal overflow. This is runtime evidence for the edge contract,
not evidence of a production ingress/network deployment.

Evidence:

- `services/api-gateway-service/src/main/java/dev/buhanzaz/rwms/gateway/config/`
- `services/api-gateway-service/src/main/java/dev/buhanzaz/rwms/gateway/web/`
- `services/api-gateway-service/src/test/java/dev/buhanzaz/rwms/gateway/`
- `panel/src/lib/gateway-config.ts`
- `panel/src/features/auth/auth-config.ts`

## F1C Warehouse-Grant Validation Boundary (2026-07-13)

Auth warehouse-grant mutation now has an opt-in fail-closed existence check.
Disabled mode makes no Warehouse Service call and preserves pre-W1 login and
administration. Enabled mode obtains a service token with only
`warehouse.read`, validates each distinct canonical UUID before persistence,
and rejects malformed IDs, unknown/inactive warehouses, token/upstream failure,
extra or missing response fields, and response-ID mismatch without partial
grant changes.

`rwms-panel` may be provisioned with `warehouse.read`, but F1C does not change
the panel's requested scopes. The confidential `auth-service` client is
declarative and disabled until W1 deployment activation. Warehouse Service is
never consulted while reading users or minting JWTs.

Evidence:

- `services/auth-service/src/main/java/dev/buhanzaz/rwms/auth/integration/warehouse/`
- `services/auth-service/src/test/java/dev/buhanzaz/rwms/auth/AuthWarehouseServiceClientIntegrationTest.java`
- `services/auth-service/src/test/java/dev/buhanzaz/rwms/auth/service/UserAdministrationServiceWarehouseValidationTest.java`

## F4 Event And PII Security Boundary (Approved Target, 2026-07-13)

The F4 governance approved on 2026-07-13, after prerequisite F1C closure commit
`d50922d`, permits full event sourcing only for non-secret aggregates in the
owning service. Event-store rows, outbox envelopes, Kafka records, DLT records,
logs and traces must never contain passwords or hashes, OAuth tokens/codes,
client secrets, signing/private keys, session material, raw authorization
records, or unrestricted PII.

- PII required by a projection is held behind an opaque reference in the
  owning service's local PII vault/operational store. Kafka consumers receive
  only contract-approved snapshots or references.
- Password/OAuth/cryptographic state remains transactional operational state;
  a redacted audit fact may prove that a change happened but cannot expose or
  reconstruct the secret.
- Consumer groups use service identities and least-privilege topic ACLs.
  Producer identity, topic, key, schema and aggregate version are validated
  before inbox side effects.
- DLT access is privileged because rejected records retain business payloads.
  Automatic infinite replay is forbidden.
- Correlation and tracing baggage are allowlisted and must not transport JWTs,
  cookies, credentials or arbitrary request headers.

Kafka and event-store work uses the side-by-side `DomainEventEnvelopeV2`. Its
actor field is a sanitized opaque reference and must not contain the V1
`ActorSnapshot.displayName` or other raw PII; `recordedAt` is required while
`occurredAt` is nullable for migration baselines with no proven event time. At
F4R the existing V1 `EventEnvelope` and `ActorSnapshot` leave target Spring
integration and become isolated media-compat RabbitMQ legacy contracts for the
unchanged Go worker until the combined Stage 3–4 media-service cutover; they
are never silently reinterpreted as V2.

Exact production KMS/vault product, key rotation and deletion/erasure workflow,
Kafka ACL administration, DLT operator ownership and audit retention remain
`UNKNOWN`. Deployment security controls are outside the repository scope; the
superseded F4I candidate is not active security evidence.

F4A enforces typed allowlisted authorization payloads and recursively rejects
PII/credential/token/secret fields and values. Consumer DLT records contain
only a failure code, message SHA-256 and recorded time. OAuth clients, grants,
sessions, tokens, signing keys, password hashes and profile PII never become
event-store or Kafka payloads. The full auth security regression passed in the
129-test F4A suite.

## F4G Gateway Management Boundary (2026-07-14)

Gateway health, liveness and readiness are public so platform probes do not
need a user token. `/actuator/prometheus` remains behind Bearer JWT validation
and the configured public-host boundary. No other management endpoint is
exposed. Tests reject token, email and UUID-shaped high-cardinality values in
the rendered scrape/log evidence.

W3C trace headers are forwarded independently from `X-Correlation-Id`; neither
channel may contain authorization headers, cookies, credentials, PII or event
payloads. The gateway remains stateless and owns no token storage, database,
message-bus client or domain authorization state.

## W1 Warehouse Authorization (2026-07-14)

Public warehouse reads require a `USER` JWT and `warehouse.read`; mutation also
requires `SYSTEM_ADMIN` and `rwms.write`. The dev bypass is limited to the
public surface in the `dev` profile and production remains fail-closed. The
private existence endpoint never bypasses JWT: it accepts only a `SERVICE`
principal with `client_id=auth-service` and exactly one scope,
`warehouse.read`.

POST idempotency keys are UUIDs bound to the authenticated subject. The service
stores only a SHA-256 of the normalized command and the original successful
response for seven days; a same-key differing command conflicts. Kafka payloads
exclude name, city and address, and temporary local smoke secrets were passed
only through process environment and were not persisted in the repository.

## Asset authorization and fact hygiene (implementation record, 2026-07-16)

Asset read uses `rwms.read` plus warehouse `VIEW`; cabin changes use
`rwms.write` plus `EDIT`; equipment movements/dispositions use `MANAGE` in each
affected warehouse. `WMS_ADMIN` and `SYSTEM_ADMIN` are global, while catalog
and classifier mutation is limited to global administrators. Mutable commands
use expected versions and return the same `409` problem family for version,
idempotency, invariant and fencing conflicts.

The warehouse registry accepts only the exact `SERVICE` credential
`client_id=asset-service` with exactly `warehouse.read`; auth configuration
declares that disabled client without a repository secret. Asset Kafka facts,
outbox envelopes and DLT metadata reject comments, note text, tenant/media URL
fields and PII; manual-note text never leaves the asset database.

## Stage 7 inventory security proposal (2026-07-17)

Approval resolution: the user's `Начинай Stage 7 все разрешаю` response,
followed by `Продолжай`, approves the public VIEW/EDIT/MANAGE matrix, exact
separate service scopes, forbidden broad credentials and sanitized actor/event/
DLT policy below. Implementation and negative verification remain pending.

Legacy `/api/mobile/**` anonymous admin execution is explicitly rejected as a
target trust model. Proposed inventory public reads require USER, `rwms.read`
and warehouse VIEW; start/resolve/add/save require USER, `rwms.write` and EDIT;
complete/cancel/publish/close require USER, `rwms.write` and MANAGE. The server
derives actor, grants, warehouse access and business time from validated state;
client actor/date/statistics/signature fields are never authoritative.

The proposed disabled `inventory-service` client receives separate exact-scope
tokens only for `warehouse.read`, `asset.inventory` or
`maintenance.inventory`, with `principal_type=SERVICE` and
`sub == client_id == inventory-service`. Combined/omitted/foreign scopes fail
closed. It receives no USER, generic `asset.internal`, equipment-hold,
fenced-status, task-board, queue, worker, media or logistics credential.

Inventory events use opaque actor references and exclude tenant, display/login/
email, comments/reasons, passport values, media URLs/object keys, JWTs, secrets
and raw dependency errors. These rules and service allowlists are proposed,
not implemented or approved, until the Stage 7 contract entrance gate closes.

### Stage 7 verified security resolution (2026-07-17)

Public USER VIEW/EDIT/MANAGE authorization and exact single-scope inventory
service credentials now fail closed. Gateway routing remains stateless;
downstream JWT validation is local and event/DLT evidence is sanitized.
Warehouse metadata uses a concealment matrix: absent, inactive and a valid ID
outside the caller's warehouse scope are indistinguishable `404` responses.
Media event-ID/body conflicts quarantine the affected owner aggregate and make
authorization fail closed; public metadata and signed-URL reads validate and
lock the current owner proof in the same SQL statement, preventing stale proof
reuse during concurrent revocation.

The final Stage 7-only security candidate evidence passed inventory 46/46,
asset 57/57, maintenance 131/131, Stage 7 architecture 27/27, auth 11/11,
warehouse 12/12, gateway 36/36 and media 73/73, all with zero failures, errors
or skips. Earlier shared asset 64/64, maintenance 136/136 and architecture
33/34 runs mixed in Stage 8 diagnostics and are not Stage 7 closure totals.
Closure is recorded by the containing scoped Stage 7 commit without inventing
a SHA.
