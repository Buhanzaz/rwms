# AGENTS.md

# RWMS Panel-to-Services Development Rules

## Current Objective

The current RWMS development objective is the migration described in
[`docs/plans/20260718-panel-mocks-to-services-transition.md`](docs/plans/20260718-panel-mocks-to-services-transition.md):
move the real user flows in `panel/` from browser mocks and browser-owned
business state to the existing RWMS services.

`panel/` is the only primary panel. Do not restore or run
`wms-panel-old/` as a second UI. Historical stages, roadmaps and completed
claims are context only; they do not block a user-directed panel or service
cutover.

The direct user command selects the flow or plan task to execute. Do not start
later waves, commit, advance a historical stage or implement an unrelated
service without a direct request.

While Wave 0 in the migration plan is incomplete, foundation work is
sequential: restore a green panel build, enforce gateway-only browser URLs and
make the local all-services runtime coherent before parallel coding.

## Authority

Use authority in this order:

1. the current direct user command;
2. this file;
3. current code and canonical schemas under `contracts/`;
4. the current migration plan;
5. safety, security and data-integrity constraints;
6. historical plans and architecture records as context only.

Do not edit `docs/plans/ACTIVE_STAGE.md` or rewrite historical stage claims
unless the user explicitly asks. `old_db/` is read-only evidence unless the
user explicitly authorizes a concrete data operation.

## Stack And Target Boundaries

- Panel: React 19, TypeScript, Vite 8, React Router, TanStack Query/Table,
  Tailwind CSS v4, shadcn/ui and Hugeicons.
- Spring services: Spring Boot 4.1, Java 25, Gradle Kotlin multi-module,
  Spring Data JPA and PostgreSQL.
- Schema authority: Flyway only. Liquibase is forbidden.
- Authentication: OAuth2/OIDC Authorization Code + PKCE for the panel and
  locally validated Bearer JWTs for APIs.
- Messaging and objects: Kafka 4.3.1 and MinIO.
- Edge: stateless Spring API gateway.
- Media: one stateful Go `media-service`; do not create a separate target
  photo-processing deployable.

Every stateful service owns one PostgreSQL database. Cross-database foreign
keys, joins, shared tables, shared JPA entities and shared mutable domain models
are forbidden.

## Flow Ownership

Use existing services rather than creating opportunistic replacements:

| Flow | Owner |
| --- | --- |
| Login, users, roles, warehouse access | `auth-service` |
| Warehouse identity and metadata | `warehouse-service` |
| Cabins, status, equipment, balances, holds and leases | `asset-service` |
| Queues, workforce and operational board | `task-board-service` |
| Catalog, estimates, repairs, acceptance and write-off decisions | `maintenance-service` |
| Inventory sessions, findings, completion and publication | `inventory-service` |
| Returns, shipments, transfers and their orchestration | `logistics-service` |
| Media metadata, upload/finalize, originals and transformations | `media-service` |
| Cross-domain cabin activity | read-only `dossier-service` projection |

`api-gateway-service` owns no database, Kafka participation, token storage or
business aggregation. `dossier-service` never owns commands.

## Definition Of A Cutover

Each requested flow is delivered as one vertical slice:

1. inspect the current panel behavior, its port/adapter/store and the owning
   service;
2. compare every required action with the canonical OpenAPI/event contract;
3. add the smallest owning-service contract/implementation change only when a
   real gap is proven;
4. connect the panel through the public same-origin gateway route;
5. remove the replaced browser adapter, storage, seed, mock selector,
   compatibility shim and mock-only tests in the same task;
6. run focused validation and report the exact result.

Keep one production implementation per flow. Do not introduce a compatibility
flag, dual-write, silent fallback or a second runtime merely to preserve old
code.

Browser `localStorage`/IndexedDB data is fixture or recovery evidence, not
trusted PostgreSQL migration input. Do not import it automatically.

Local storage is acceptable only for non-authoritative UI preferences such as
theme, the last service-issued warehouse UUID and table/grid presentation.

## Panel And Gateway Rules

- Browser requests use same-origin `/auth/**` and `/api/**` only.
- Do not put internal service origins or `localhost:<service-port>` into panel
  runtime configuration.
- The browser never calls `/api/internal/**` or private service routes.
- Missing token, gateway configuration or service is an error; production does
  not fall back to a mock.
- Use the shared Bearer client and map Problem Details consistently.
- Mutable commands use `expectedVersion` or ETag and show a consistent
  conflict result for `409`.
- Retried creates/effects use `Idempotency-Key` or a stable domain external
  ID.
- The panel may combine independent public read projections. The gateway must
  not aggregate business responses.
- Preserve the transferred panel UX where it matches proved domain semantics.
  Hide or mark unavailable a control whose service contract is undefined;
  never fabricate success or data.

## Spring Data, Flyway, Lombok And MapStruct

Use Spring Data JPA for stateful Spring service persistence. When a task changes
an entity, repository or projection, apply the `spring-data-jpa` rules before
editing.

- JPA mappings define the application model; Hibernate never creates, updates
  or drops the target schema.
- Every target profile uses `hibernate.ddl-auto=validate`.
- Flyway migrations are immutable, ordered and service-local.
- `baselineOnMigrate` remains false; non-empty legacy schemas require an
  explicit proven baseline.
- Destructive schema changes use expand/contract. Never delete data or volumes
  as an implicit migration step.
- Run a clean install or the affected upgrade path plus JPA validation when a
  migration changes.

Lombok is encouraged for safe boilerplate reduction, but JPA entities must not
use `@Data`, Lombok builders, generated `equals/hashCode/toString`, or
generated setters for IDs, versions, timestamps and domain invariants. Records
remain records.

MapStruct is required where a touched Spring boundary maps entity/projection
reads to DTOs or sanitized integration payloads. Use Spring component model,
constructor injection and `unmappedTargetPolicy=ERROR`. Do not use MapStruct
for request-to-entity mutation, security/secret mapping, version mutation,
outbox/checksum construction or domain transitions.

`platform:technical-contracts` remains framework-neutral and contains no JPA,
Spring, Kafka or business-domain model. Canonical transport schemas live under
`contracts/openapi` and `contracts/events`.

## Service And Event Rules

- A service owns its aggregate transitions and server-side orchestration.
  Browser sagas must be removed during their flow cutover.
- Internal service-to-service calls use private addresses and appropriate
  service credentials, not the public gateway.
- PostgreSQL event stores and projections are authoritative; Kafka is
  transport, not an archive or database.
- Preserve transactional outbox/inbox, aggregate-key ordering, deduplication
  and version-gap handling when a touched service already uses them.
- Delivery is at-least-once and effects must be idempotent.
- 2PC is forbidden. The initiating service owns saga state and compensation.
- Remove RabbitMQ compatibility runtime/config/tests when its directed Kafka
  or combined media replacement is complete; do not keep a parallel path.

## Agent Policy: One By Default, Additional Agents For Parallel Waves

Use one primary agent by default.

For a genuinely parallel implementation wave, the primary agent may start
additional coding agents. Additional agents must not create their own
subagents. Do not add agents when the work is sequential or shares files.

Start each additional agent only when all of the following are true:

- the new task and all active tasks are concrete coding tasks, not discovery
  or planning;
- their file ownership is explicit and non-overlapping;
- neither task waits on an unfinished contract from the other;
- both can run useful focused verification independently;
- parallel work materially shortens the requested delivery.

Never create separate discovery, reviewer, QA, memory, status, documentation or
commit agents. The coding agent that changes a flow writes its tests and runs
its focused checks. The primary agent owns integration and the final report.

Before starting any additional agent, state:

- its exact outcome;
- exclusive files/directories;
- frozen inputs/contracts;
- files it must not touch;
- required validation.

One owner at a time is mandatory for:

- `AGENTS.md`, the migration plan, `App.tsx`, `app-sidebar.tsx`,
  `gateway-config.ts` and root `compose.yaml`;
- one service's OpenAPI file, Flyway directory or JPA aggregate;
- the current monolithic logistics API until it is split into non-overlapping
  flow directories.

Safe parallel lanes after Wave 0 include:

- warehouse/asset core;
- task-board settings and operational board;
- media integration;
- maintenance catalog, provided its files do not overlap the selected first
  media consumer.

These lanes may run in one wave when their prerequisites and exclusive
ownership are already fixed. In the next wave, inventory and maintenance
lifecycle may run together, along with any other dependency-ready vertical or
primary-agent integration work.

Logistics decomposition and contract ownership remain sequential. After its
code is split and the OpenAPI is frozen, returns, shipments and transfers may
run as three independent panel tasks while one backend owner retains exclusive
ownership of logistics-service/OpenAPI/Flyway. Dossier final integration comes
after real producer facts.

Agents work in the shared worktree, preserve user/concurrent edits, never
revert another agent's changes and do not commit unless explicitly assigned.
Each additional agent reports changed files, checks run and remaining risks.

## Focused Verification

Run the narrowest checks that cover the final diff. Never claim a check that
was not executed.

- Panel change: affected Vitest tests plus `npm run typecheck`; add lint/build
  when the changed boundary requires them.
- Spring change: affected module/package tests or compile check.
- Contract change: owning contract validation and focused controller/client
  compatibility test.
- Migration change: affected Flyway install/upgrade path and JPA validation.
- Go media change: affected package tests/build.
- Auth, authorization, concurrency and integration changes: add the smallest
  targeted success and failure checks.
- Android app change or a service-contract change consumed by `app/`: run the
  affected unit/contract tests and build the exact APK to be distributed. Before
  calling it tested or publishing it, install that APK on an emulator or
  physical device, authenticate against the intended public RWMS gateway (not
  MockWebServer), and verify the first authenticated workspace request plus the
  changed flow. Capture a UI tree or screenshot and filtered logcat; a JSON
  parsing, HTTP-contract, crash, or connection error fails the gate. If real
  gateway credentials or runtime are unavailable, report that blocker and do
  not claim end-to-end APK validation. For every change in `app/`, update the
  `/download` site with the newly built application version before handoff.

Use full Testcontainers, Playwright, Kafka outage/retry or cross-service suites
only when the change requires them, focused checks reveal a wider issue or the
user asks. If infrastructure blocks a check, name the exact blocker and run the
narrowest honest replacement.

## Deferred Decisions

Do not invent or opportunistically implement:

- warehouse location/bin topology;
- final old-panel-to-current cabin status mapping;
- company/contract and customer-reservation ownership;
- formal stock reservation invariants;
- task-board schedule timezone/DST/downtime semantics;
- worker push/offline notification delivery;
- media retention/orphan cleanup policy;
- compensation after irreversible shipment/transfer departure;
- KPI formulas, periods, historical backfill or analytics-service;
- production hosting/deployment topology.

Keep the related production controls unavailable until a direct command and
domain decision define them.

## Local Runtime And VPS

Work in this repository is performed on the VPS. At the end of each completed
task, update the affected running test services on the VPS so they reflect the
delivered changes; inspect their status and logs after the update.

`compose.yaml` is for isolated local development/test dependencies only. Do
not add Kubernetes, Helm, Terraform, Ansible, Swarm, production ingress/TLS or
release pipelines unless explicitly requested.

When the user asks for the temporary VPS demo, Codex may start the repository's
databases, Kafka, MinIO, services, gateway and panel; bind only the approved
panel/gateway port; inspect logs; and restart failed demo processes.

Do not expose PostgreSQL, Kafka, MinIO administration, internal service ports
or management endpoints to the Internet. Do not commit VPS credentials,
private keys, generated keystores, logs or runtime secrets. A temporary HTTP
demo is not production-ready.

## Git And Data Safety

- Preserve all user and concurrent changes; stage only intended files/hunks.
- Never use destructive reset/checkout to clean the worktree.
- Never delete databases, volumes, backups or user data without explicit scope
  and exact-target verification.
- Do not commit secrets, generated binaries, build output, browser artifacts or
  IDE state.
- Do not commit, push or open a PR unless the user asks.
- Before a requested commit, verify identity; use `buhanzaz` and the user's
  configured email with a short human message.
- Do not put agent/model/tool names in branches, folders, commits or PR
  metadata, and do not use a `codex/` branch prefix.
