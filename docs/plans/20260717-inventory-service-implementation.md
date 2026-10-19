# Stage 7 Inventory Service Implementation Plan

Status: `COMPLETE`

Authority: the approved
[`20260717-inventory-service-contract.md`](20260717-inventory-service-contract.md),
[`ACTIVE_STAGE.md`](ACTIVE_STAGE.md), and the staged roadmap. The user's
2026-07-17 instructions `Начинай Stage 7 все разрешаю` and `Продолжай` approve
the narrow prerequisites, media-runtime closure and panel cutover described
here. This plan does not authorize Stage 8.

## Overview

Deliver the server-owned Stage 7 inventory workflow from stable asset capture
through findings, immutable completion, exact statistics and independently
retryable maintenance publication. Close the already identified media runtime
gap first, preserve existing domain ownership, and replace the inventory browser
store only after every backend boundary and the stateless gateway route pass.

## Entrance context

- At Stage 7 entrance, `services/media-service` had schema, transformation and MinIO
  foundations but no production HTTP/JWT/PostgreSQL/outbox runtime.
- At Stage 7 entrance, no inventory OAuth client, private warehouse metadata endpoint, asset stable
  capture/source-create boundary, maintenance inventory plan/upsert boundary,
  inventory service/module/database/contracts or gateway route exists.
- At Stage 7 entrance, `panel/src/features/inventory` was a LocalStorage/IndexedDB/browser-coordinated
  implementation and UX evidence, not transport or persistence authority.
- Legacy HSQLDB contains no reconstructible inventory-session aggregates;
  inventory starts from a clean Flyway V1 database with no browser/legacy ETL.
- PostgreSQL event streams are canonical; Kafka is at-least-once transport
  through transactional outbox/inbox processing.

## Development approach

- Testing approach: **Regular** — implement one bounded unit, then add and run
  both success and negative tests before proceeding.
- Follow this exact order:
  `media 1A -> auth -> warehouse -> asset V3 -> maintenance V2 -> architecture ->`
  `inventory contracts/database/runtime -> media 1B -> gateway -> panel -> exit`.
- A subgate is not complete until its focused tests pass and its complete diff
  is reviewed. Do not begin the next subgate on partial green evidence.
- Give every implementation agent bounded, non-overlapping file ownership.
  Agents do not commit. The lead reviews accumulated findings as a stack and
  returns one grouped correction set rather than redeploying agents per defect.
- Any agent that adds or modifies JPA entities/repositories must first use the
  `spring-data-jpa` skill and follow RWMS rules: no `@Data`, entity builders,
  generated entity equality/string methods, or setters for IDs, versions,
  timestamps and invariants; Hibernate remains `ddl-auto=validate` and Flyway
  owns schema changes.
- Java/Kotlin/XML changes must use the project `codefmt` skill before handoff.
  Gradle verification must use the `run-tests` skill for the affected module.
- Go changes must run `gofmt`, `go test ./...` and a reproducible build. Panel
  work must reuse the existing shadcn/components and feature ports rather than
  introducing a second design system or direct browser persistence access.
- Update this plan when approved scope changes. Record blockers with `[!]`, new
  work with `[+]`, and completed items with `[x]` immediately.
- No separate plan commit is allowed. RWMS requires one reviewed scoped human
  commit only after the complete Stage 7 exit gate passes.

## Testing strategy

- Every task below includes successful behavior and fail-closed/error behavior.
- Stateful Spring tasks require PostgreSQL Testcontainers, Flyway and JPA
  validation in addition to focused domain/security tests.
- Messaging tasks cover acked outbox delivery, duplicate inbox delivery,
  ordering, bounded retry, sanitized DLT, version-gap quarantine and outage
  recovery where the task changes those paths.
- Cross-service tests use canonical schemas and exact service identities/scopes;
  they never rely on the gateway for private calls.
- Panel cutover requires affected Vitest plus desktop/tablet/mobile Playwright,
  in addition to typecheck, lint and production build.

## Solution overview

Inventory copies an immutable 30-minute asset capture into its own V1 database,
then releases the technical capture immediately. Findings keep independent
inspection/reconciliation state and maintenance-issued plan snapshots.
Completion uses fresh point-in-time asset validation and freezes exact server
statistics without a session-wide asset lease. Post-completion publication uses
the permanent `inventoryId:findingId` source identity; maintenance retains
repair lease/fencing and task-board ownership. Media remains owner-bound through
opaque finalized generation references.

## Implementation steps

### Task 1A: Close the authorized media HTTP/runtime foundation gap

**File ownership:**

- Create/modify: `services/media-service/cmd/media-service/`
- Create/modify: `services/media-service/internal/{api,auth,persistence,eventing,worker}/`
- Read-only immutable authority: `services/media-service/db/migration/V1__media_schema.sql`
- Create: `services/media-service/db/migration/V2__media_runtime_recovery.sql`
- Modify: `services/media-service/internal/{media,storage}/`
- Modify: `services/media-service/{go.mod,go.sum,README.md}`
- Modify: `contracts/openapi/media-service.yaml`
- Modify: `contracts/events/media-events.yaml`
- Read-only compatibility authority:
  `contracts/events/media/media-events-v1.schema.json`
- Create: `contracts/events/media/media-processing-requests-v1.schema.json`
- Modify: `compose.yaml` only for isolated local/test `media-db` and MinIO
  dependencies while preserving `rwms-media-compat` RabbitMQ unchanged
- Bounded gateway ownership for the prior media closure only — modify:
  `services/api-gateway-service/src/main/java/dev/buhanzaz/rwms/gateway/config/{GatewayProperties,GatewayRouteConfiguration}.java`
- Bounded gateway ownership for the prior media closure only — modify:
  `services/api-gateway-service/src/main/resources/application{,-dev}.yaml`
- Modify/create tests under: `services/media-service/**/_test.go` and
  `services/api-gateway-service/src/test/`

- [x] implement one stateful Go runtime for upload/finalize, metadata,
  generation state, owner/warehouse authorization, access and in-process image/
  video transformation without creating another deployable;
- [x] keep Flyway outside the Go application: never modify V1 and never let the
  service apply or repair migrations. Add V2 only; on startup the service must
  fail closed unless `flyway_schema_history` proves the exact approved V1+V2
  versions, descriptions, success flags and checksums;
- [x] configure external/local-test Flyway with `baselineOnMigrate=false` and
  prove clean V1+V2 install, in-place V1-to-V2 upgrade, repeat safety, checksum-
  drift rejection and non-empty-unversioned rejection before runtime startup;
- [x] persist media streams, stream heads, projection checkpoints, quarantine,
  sanitized DLT, transactional outbox with leases and broker-ack ordering, plus
  processing-worker leases/fencing in media-owned PostgreSQL;
- [x] validate configured Bearer JWT issuer, audience and JWKS fail closed.
  Public principals must be USER with a UUID subject: reads require exact
  `rwms.read` plus warehouse VIEW; upload/finalize require exact `rwms.write`
  plus warehouse EDIT. No hidden media SERVICE client or credential is allowed;
- [x] return RFC 7807 Problem Details with canonical correlation IDs, expose
  bounded liveness/readiness and perform graceful shutdown that stops ingress,
  drains or releases owned leases and never acknowledges unfinished work;
- [x] accept only the Stage 7 owner contexts `INVENTORY_FINDING` and
  `INSPECTION`. Never trust caller-supplied `viewerContext`, arbitrary owner ID
  or warehouse. Add only the service-owned projection/inbox/checkpoint/
  quarantine foundation: an owner UUID must equal the source aggregate UUID,
  aggregate versions must be contiguous and owner revisions must never regress
  or conflict. Until Task 1B connects the approved inventory fact, every public
  owner operation with absent, quarantined or mismatched proof fails closed;
- [x] eliminate mutable reusable-PUT TOCTOU: issue constrained single-ingress
  upload capability and finalize only an immutable/version-pinned object. Stat
  and verify exact length, declared type, ETag/version, checksum and content
  sniff before allocating processing work;
- [x] sanitize extensions and object keys; bound image/video reads, temporary
  space, processing time and decoded resources. Allocate generations
  monotonically across every failure and persist video dimensions as SQL NULL
  when unproved. Make upload maximum required configuration with no invented
  production default; add no destructive cleanup/delete/retention behavior;
- [x] preserve the existing maintenance-compatible media fact V1 contract
  without a breaking mutation. Define processing requests as a separate schema,
  aggregate identity and version; AsyncAPI must reference facts and requests
  separately with their exact topic, aggregate-family key and ordering rules;
- [x] order publication so the `UPLOADED` fact is broker-acknowledged before its
  processing request can dispatch, and `READY` cannot precede either. Kafka uses
  aggregate-family keys, `acks=all`, producer idempotence and Zstd. Broker
  failures remain indefinitely retryable in the PostgreSQL outbox; only corrupt
  exact bytes fail permanently. Processing dependency failures receive 1s/2s/
  4s retries, validation failures do not retry, and final processing failures
  enter the sanitized consumer-owned DLT;
- [x] add only the stateless media gateway route needed by the already approved
  media cutover; reject cookies, encoded/private/internal paths and preserve
  downstream JWT validation;
- [x] add success tests through the validated owner-projection foundation for
  upload/finalize/access, immutable/version-pinned
  MinIO finalization, monotonically allocated generations, nullable unproved
  video dimensions, worker fencing, graceful restart, exact V1 facts/separate
  processing request schema and `UPLOADED`-ack-before-request-before-`READY`;
- [x] add negative tests for startup migration version/checksum mismatch,
  anonymous/SERVICE/non-UUID/cross-warehouse/wrong-owner access, absent owner
  proof, reusable/changed object, length/type/ETag/checksum/sniff mismatch,
  unsafe extension/key, every resource bound, duplicate mismatch, invalid
  transitions, dependency outage, outbox lease/ack races and stale worker fence;
- [x] add Kafka tests for aggregate key/order, duplicate safety, `acks=all`/
  idempotence/Zstd configuration, broker recovery after more than four outbox
  attempts, 1s/2s/4s processing recovery, validation with no retry, sanitized
  DLT and PostgreSQL/MinIO/Kafka outage recovery;
- [x] prove Compose adds only local/test `media-db` and MinIO and does not alter
  or remove the isolated legacy Rabbit media-compat runtime;
- [x] run `gofmt`, `go test ./...`, a reproducible Go build and focused gateway
  route/security/failure tests; all migration, runtime, contract and recovery
  checks above must pass before Task 2. Task 1A does not invent or require the
  future inventory owner-proof consumer; that integration is the explicit Task
  1B gate after Task 8.

### Task 2: Provision the exact inventory OAuth client

**File ownership:**

- Modify: `services/auth-service/src/main/java/dev/buhanzaz/rwms/auth/config/{OAuthClientProperties,OAuthClientProvisioner,ConfiguredRegisteredClientRepository}.java`
- Modify: `services/auth-service/src/main/resources/application{,-dev}.yaml`
- Create: `services/auth-service/src/test/resources/application-inventory-client.yaml`
- Modify/create tests under: `services/auth-service/src/test/java/dev/buhanzaz/rwms/auth/`

- [x] register a disabled-by-default `inventory-service` client for audience
  `rwms-services` without storing a repository secret;
- [x] allow only separate exact-scope tokens for `warehouse.read`,
  `asset.inventory` or `maintenance.inventory` with SERVICE principal and
  `sub == client_id == inventory-service`;
- [x] add success tests for each single scope and disabled/default provisioning;
- [x] add negative tests for omitted, combined, foreign, USER, `asset.internal`,
  task-board, media and logistics scopes plus wrong subject/client/audience;
- [x] run focused auth tests and the full affected auth suite; all must pass
  before Task 3.

### Task 3: Add the inventory-only warehouse metadata boundary

**File ownership:**

- Modify/create: `services/warehouse-service/src/main/java/dev/buhanzaz/rwms/warehouse/api/InventoryWarehouseController.java`
- Modify: `services/warehouse-service/src/main/java/dev/buhanzaz/rwms/warehouse/security/WarehouseAuthorizer.java`
- Modify/create tests under: `services/warehouse-service/src/test/java/dev/buhanzaz/rwms/warehouse/`
- Modify only if parity requires it: `contracts/openapi/warehouse-service.yaml`

- [x] expose a private inventory endpoint returning only warehouse ID, version,
  active state and IANA timezone;
- [x] require exact SERVICE identity `inventory-service` and exactly
  `warehouse.read` without widening existing auth/asset allowlists;
- [x] add success tests for an active warehouse and canonical timezone/version;
- [x] add negative tests for wrong client/principal/scope, combined scopes,
  inactive/absent warehouses and cross-boundary information leakage;
- [x] run focused warehouse security/API/parity tests and the affected service
  suite; all must pass before Task 4.

### Task 4: Implement asset V3 stable capture and permanent source creation

**File ownership:**

- Create: `services/asset-service/src/main/resources/db/migration/V3__inventory_boundary.sql`
- Create/modify: `services/asset-service/src/main/java/dev/buhanzaz/rwms/asset/{api,domain,repository,service}/`
- Modify: `services/asset-service/src/main/java/dev/buhanzaz/rwms/asset/security/AssetAuthorizer.java`
- Modify: `contracts/openapi/asset-service.yaml`
- Modify: `contracts/events/asset/asset-events-v1.schema.json`
- Modify/create tests under: `services/asset-service/src/test/java/dev/buhanzaz/rwms/asset/`

- [x] use `spring-data-jpa`; add display canonical number and identity match key,
  collision-safe V3 migration and JPA mappings without guessing lost hyphens;
- [x] implement fixed capture membership/count/digest/order/pages keyed by public
  operation and technical attempt, with non-sliding 30-minute TTL, immediate
  explicit release and crash/orphan expiry;
- [x] implement exact-scope global number resolution, point-in-time validation
  and atomic permanent `inventoryId:findingId` source-create returning `FREE`;
- [x] enforce exact `asset.inventory` SERVICE authority; expose no inventory
  hold, session lease, fenced status or generic internal mutation;
- [x] add success tests for >200 stable members under mutation, deterministic
  replay/release/expiry, aliases, validation digest and source replay after
  ordinary idempotency expiry;
- [x] add negative tests for capture fingerprint/page/digest mismatch, expiry,
  punctuation/collision/concurrent create, cross-warehouse/excluded status,
  wrong scope/client and crash-after-create/before-attach recovery;
- [x] prove clean V1+V2+V3 install, V2-to-V3 in-place upgrade, repeat,
  checksum-drift rejection, non-empty-unversioned rejection and JPA validation;
  run the full affected asset suite before Task 5.

### Task 5: Implement maintenance V2 inventory plan and repair upsert

**File ownership:**

- Create: `services/maintenance-service/src/main/resources/db/migration/V2__inventory_source.sql`
- Modify: `services/maintenance-service/src/main/java/dev/buhanzaz/rwms/maintenance/domain/RepairOrigin.java`
- Create/modify: `services/maintenance-service/src/main/java/dev/buhanzaz/rwms/maintenance/{api,domain,repository,service}/`
- Modify: `services/maintenance-service/src/main/java/dev/buhanzaz/rwms/maintenance/security/`
- Modify: `contracts/openapi/maintenance-service.yaml`
- Modify: `contracts/events/maintenance/maintenance-events-v1.schema.json`
- Modify/create tests under: `services/maintenance-service/src/test/java/dev/buhanzaz/rwms/maintenance/`

- [x] use `spring-data-jpa`; add `INVENTORY` origin, permanent source mapping/
  fingerprint and immutable inventory plan/media source snapshot in Flyway V2;
- [x] implement exact `maintenance.inventory` AUTO resolve/MANUAL validation
  against the active warehouse catalog and return immutable catalog/node/queue/
  line/price/duration/order/photo fingerprint;
- [x] implement permanent `inventoryId:findingId` repair upsert that accepts the
  exact historical snapshot, never regenerates/reroutes it, and returns the
  existing repair on identical replay;
- [x] keep asset lease/fencing and task-board synchronization inside maintenance;
  inventory receives neither credential nor command authority;
- [x] add success tests for AUTO/MANUAL freeze, later catalog activation without
  rewrite, exact upsert replay and lease/task reconciliation;
- [x] add negative tests for stale/unknown catalog identities, changed source
  fingerprint, unsafe current asset state, wrong media fact, wrong client/scope
  and dependency/recovery failure;
- [x] prove clean V1+V2 install, V1-to-V2 in-place upgrade, repeat, checksum,
  unversioned rejection and JPA validation; run the full affected maintenance
  suite before Task 6.

### Task 6: Extend architecture and dependency policy before inventory code

**File ownership:**

- Modify: `platform/architecture-tests/src/test/java/dev/buhanzaz/rwms/architecture/`
- Modify only as required: `build-logic/`, `gradle/libs.versions.toml`, root
  `build.gradle.kts`
- Modify later in Task 7 only: `settings.gradle.kts`

- [x] extend JPA/Lombok/MapStruct/source/dependency policy to cover the current
  maintenance module and prospective inventory package conventions;
- [x] assert technical-contracts remains framework/domain free and inventory
  cannot depend on another service's JPA/domain package;
- [x] add positive fixtures/tests for approved read mapping and constructor
  injection patterns;
- [x] add negative fixtures/tests for unsafe entity Lombok, request-to-entity
  mutation mapping, shared business models and forbidden service dependencies;
- [x] run architecture and approved-dependency tests; all must pass before
  creating the inventory module in Task 7.

### Task 7: Add canonical inventory contracts and clean Flyway V1

**File ownership:**

- Create: `contracts/openapi/inventory-service.yaml`
- Create: `contracts/events/inventory-events.yaml`
- Create: `contracts/events/inventory/inventory-events-v1.schema.json`
- Create: `services/inventory-service/build.gradle.kts`
- Create: `services/inventory-service/src/main/resources/application{,-dev}.yaml`
- Create: `services/inventory-service/src/main/resources/db/migration/V1__inventory_schema.sql`
- Create: `services/inventory-service/src/test/resources/application-test.yaml`
- Modify: `settings.gradle.kts`
- Modify: `compose.yaml` only for an isolated local/test `inventory-db`
- Create tests under: `services/inventory-service/src/test/`

- [x] encode the approved HTTP/errors, session/finding/publication revisions,
  UUID idempotency, exact statistics, the media owner-proof lifecycle fact and
  sanitized event families in canonical OpenAPI/event schemas;
- [x] use `spring-data-jpa`; create inventory-owned JPA projections/repositories
  and V1 constraints for one active session, finding/source uniqueness,
  event-store/snapshot/outbox/inbox/checkpoint/quarantine/DLT and attempts;
- [x] configure every profile with `ddl-auto=validate` and
  `baselineOnMigrate=false`; add no browser/legacy importer or production seed;
- [x] add success tests for clean install, repeat validation, canonical schema
  parsing and JPA validation;
- [x] add negative tests for checksum drift, non-empty unversioned schema,
  constraint violations, forbidden payload fields and implementation/schema
  mismatch;
- [x] run inventory migration/parity and architecture tests; all must pass
  before Task 8.

### Task 8: Implement inventory domain, HTTP and recovery runtime

**File ownership:**

- Create/modify: `services/inventory-service/src/main/java/dev/buhanzaz/rwms/inventory/{api,config,domain,repository,service,security,integration,eventing}/`
- Modify: `services/inventory-service/src/main/resources/application{,-dev}.yaml`
- Create/modify tests under: `services/inventory-service/src/test/`

- [x] implement idempotent start/cancel, stable capture copy/release, finding
  origins/inspection/reconciliation, observation presence semantics, source
  create/attach recovery and independent CAS boundaries;
- [x] implement maintenance-issued plan freeze, media READY projection,
  server-owned preview/fresh completion validation, acknowledgement hash and
  exact persisted statistics/limits/rounding;
- [x] implement publication intents/attempts, exact aggregate fold,
  retry/reconcile/terminal close, maintenance source upsert and immutable
  completed history/statistics queries;
- [x] enforce USER VIEW/EDIT/MANAGE and warehouse grants, exact outbound service
  tokens, RFC 7807 errors, opaque actors and PII/secret-safe payload policies;
- [x] implement deterministic event replay/snapshots, stream CAS, transactional
  outbox, inbox/checkpoint atomicity, broker ack, 1s/2s/4s retry, sanitized DLT,
  aggregate-gap quarantine and operator recovery;
- [x] publish the approved committed inventory-finding owner lifecycle fact,
  keyed and versioned by finding aggregate UUID, through the inventory outbox;
- [x] add success tests for every state transition, >200 expected items, exact
  business date/statistics, partial publication, duplicate delivery, replay and
  PostgreSQL/Kafka/dependency outage recovery;
- [x] add negative tests for concurrency/idempotency mismatch, stale preview,
  cross-warehouse/unsafe asset/media/plan states, quantity/count/overflow,
  unauthorized principals/scopes, unsafe facts and validation-to-DLT behavior;
- [x] run the complete inventory unit/integration/security/concurrency/Flyway/
  replay/Kafka matrix; all must pass before Task 1B.

### Task 1B: Connect the approved inventory owner-proof consumer

**File ownership:**

- Modify: `services/media-service/internal/{persistence,eventing,worker}/`
- Modify only if contract parity requires it:
  `contracts/events/inventory-events.yaml` and
  `contracts/events/inventory/inventory-events-v1.schema.json`
- Modify/create tests under: `services/media-service/**/_test.go`

- [x] consume only the canonical inventory-finding owner lifecycle fact emitted
  by Task 8; require the exact topic/event/schema, aggregate family, UUID key,
  `aggregateId == ownerId`, warehouse UUID and contiguous aggregate version;
- [x] atomically apply inbox deduplication, aggregate checkpoint and owner
  binding revision. Equal identical revision is harmless; revision regression,
  equal-revision conflict and aggregate gaps quarantine the aggregate and make
  all affected public media operations fail closed;
- [x] use first delivery plus 1s/2s/4s bounded retry for transient dependency
  failures, no retry for invalid facts, sanitized consumer-owned DLT and an
  explicit operator reconciliation path. Do not add a public proof injection or
  administrative bypass;
- [x] add real Kafka/PostgreSQL tests for ordered apply, duplicate safety,
  inactive/reactivated owners, wrong UUID/key/warehouse, gaps, regression,
  conflict, invalid-to-DLT, outage recovery and fail-closed authorization;
- [x] run the complete affected media runtime/replay/contract/Kafka matrix; all
  must pass before Task 9.

### Task 9: Add the stateless inventory gateway route

**File ownership:**

- Modify: `services/api-gateway-service/src/main/java/dev/buhanzaz/rwms/gateway/config/{GatewayProperties,GatewayRouteConfiguration}.java`
- Modify: `services/api-gateway-service/src/main/resources/application{,-dev}.yaml`
- Modify/create tests under: `services/api-gateway-service/src/test/java/dev/buhanzaz/rwms/gateway/`

- [x] add only `/api/inventory/**` routing and preserve the external Bearer token
  for downstream revalidation;
- [x] keep the gateway free of inventory state, database, Kafka and business
  aggregation;
- [x] add success tests for canonical inventory routing, correlation and
  upstream Problem Details propagation;
- [x] add negative tests for cookie forwarding, encoded/private/internal paths,
  host/header spoofing and unavailable upstream behavior;
- [x] run the complete affected gateway suite; all must pass before Task 10.

### Task 10: Cut over the approved inventory panel surfaces

**File ownership:**

- Create/modify: `panel/src/features/inventory/{ports,api,adapters,model}/`
- Modify: `panel/src/features/inventory/*.tsx`
- Modify: `panel/src/{App.tsx,lib/gateway-routes.ts}`
- Modify only approved adjacent ports: `panel/src/api/rental-item-inventory-api.ts`
- Create/modify inventory Vitest files under: `panel/src/features/inventory/`
- Create: `panel/e2e/inventory-cutover.spec.ts`
- Create/modify: `panel/e2e/fixtures/inventory-route-fixture.ts`

- [x] add a versioned HTTP inventory client through existing feature ports and
  replace the production LocalStorage/IndexedDB/repair-task coordination path;
- [x] activate inventory routes, preserve existing shell/responsive behavior
  and reuse current shadcn primitives and shared components;
- [x] keep browser adapters only as explicit development fixtures; production
  missing gateway URL/token/config must fail closed without mock fallback;
- [x] render server versions, preview risks, exact statistics and publication
  partial/retry/closed states without trusting browser actor/date/totals;
- [x] add success Vitest for start/findings/preview/complete/history/statistics/
  partial publication and desktop/tablet/mobile Playwright cutover flows;
- [x] add negative Vitest/Playwright for unauthenticated/forbidden, stale 409,
  dependency Problem Details, wrong warehouse, offline/retry and production
  mock-fallback rejection;
- [x] run `npm run typecheck`, `npm run lint`, `npm run build`, affected/all
  Vitest and Playwright desktop/tablet/mobile; all must pass before Task 11.

### Task 11: Verify, reconcile memory and close Stage 7

**File ownership:**

- Modify: `docs/plans/{ACTIVE_STAGE.md,20260717-inventory-service-implementation.md}`
- Modify: `WMS_ARCHITECTURE_KNOWLEDGE/{04_ENTITIES,06_BUSINESS,07_SECURITY,08_INTEGRATIONS,09_MIGRATION}/`
- Modify: `WMS_ARCHITECTURE_KNOWLEDGE/10_AGENT_MEMORY/{history,decisions,unknowns}.md`
- No Stage 8 file, contract, client, route, event, database or implementation.

- [x] run every completed service's affected regression plus inventory's full
  matrix and validate OpenAPI/event payload parity across all changed owners;
- [x] run cross-service security, capture/source recovery, media/MinIO/Kafka/
  PostgreSQL outage and panel desktop/tablet/mobile success scenarios;
- [x] run negative cross-service review for scope widening, PII/secrets,
  concurrency/idempotency, migration drift, gateway state and Stage 8 leakage;
- [x] independently review the complete diff, group all findings, return one
  correction stack to bounded owners, then rerun every affected check;
- [x] reconcile numbered memory, migration evidence, history, decisions and only
  genuinely remaining `UNKNOWN`s without deleting audit history;
- [x] verify Git identity is `buhanzaz` with the user's configured email and
  record closure in the containing scoped Stage 7 commit without inventing a
  SHA in advance;
- [x] keep `ACTIVE_STAGE.md` on `STAGE_7_INVENTORY_SERVICE` with status
  `COMPLETE`; `next_state` remains metadata and starts no later-stage work.

## Verified completion record (2026-07-17)

Tasks 1A through 11 are implemented, independently reviewed and verified.
Inventory uses JPA for business persistence and Flyway V1 for schema authority.
Low-level SQL is limited to exactly
`InventoryDeadLetterRelay`, `InventoryDeadLetterStore`, `InventoryEventStore`,
`InventoryMediaInboxProcessor`, `InventoryMediaRetryStore` and
`InventoryOutboxStore`; both service and shared policies reject it elsewhere.
Idempotency stores the exact response under a lease-locked reservation. Start
captures use an inner repeatable-read asset snapshot, and Inventory releases
the capture only from transaction `afterCompletion`. Warehouse authorization
returns the same `404` for absent, inactive and foreign-scoped IDs. Maintenance
reconciliation is JPA end-to-end. Media owner event-ID conflicts quarantine and
fail closed, while public reads lock and validate proof in the same statement.

The final Stage 7-only candidate suites passed inventory 46/46, asset 57/57,
maintenance 131/131, Stage 7 architecture 27/27, auth 11/11, warehouse 12/12,
gateway 36/36 and media's canonical real PostgreSQL, drift-PostgreSQL, Kafka
and MinIO matrix 73/73, all with zero failures, errors or skips. Media also
passed a reproducible build. Panel typecheck, lint and build passed with Vitest
48 and Playwright 9/9. Earlier shared asset 64/64, maintenance 136/136 and
architecture 33/34 runs mixed in Stage 8 diagnostics and are not Stage 7
closure totals. Closure is recorded by the containing scoped Stage 7 commit;
this plan invents no SHA.

## Post-completion

RWMS ends at the application-service boundary. Deployment, hosting, Kubernetes,
Helm, Terraform, CI/CD release configuration, ingress/TLS and production
operations remain outside this repository and are not Stage 7 deliverables.
Unresolved media retention/orphan/legal-hold policy and excluded inventory v1
commands remain `UNKNOWN`; completion of this plan must not invent them.
