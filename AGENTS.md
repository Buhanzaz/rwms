# AGENTS.md

# RWMS Product Development Rules

## Current Objective

RWMS is an actively developed product. The objective is to finish, correct and
extend the supported product flows; there is no active migration program,
stage pointer, wave sequence or cutover gate.

The user's current command selects the scope. Implement that scope as a
complete, working change across every affected client, contract and owning
service. Do not start unrelated product work, commit, publish or deploy beyond
the requested scope.

`panel/` is the primary web panel. `app/` is the manager Android application and
`worker-app/` is the worker Android application. `wms-panel-old/`, browser
mocks, `old_db/`, `docs/plans/` and `WMS_ARCHITECTURE_KNOWLEDGE/` are historical
evidence only. They do not define current behavior and do not restrict current
development.

There is no general requirement to preserve compatibility with obsolete mock
state, old browser data, old schemas, fixtures or abandoned flows. This does
not authorize deletion of a live database, volume, backup or user data. Any
destructive data operation still requires an explicit target, impact analysis
and user authorization.

## Authority And Sources Of Truth

Use authority in this order:

1. the current direct user command;
2. this `AGENTS.md`;
3. canonical transport contracts under `contracts/openapi/` and
   `contracts/events/`;
4. the owning service's current domain code, Flyway schema and tests;
5. the current product knowledge in `docs/project-knowledge/`;
6. historical plans, migration notes, legacy code and old data as evidence
   only.

Canonical contracts define component boundaries. The owning service defines
business transitions and invariants that are not transport concerns. The
knowledge base is a verified index and explanation of those sources; it never
overrides them.

If the request, a canonical contract and the owning domain logic disagree, do
not silently choose one. Gather the exact evidence, explain the conflict and
ask the user the smallest decision needed before changing contract meaning,
ownership, identity, status semantics, money, time or destructive behavior.

## Mandatory Task Workflow

Every task follows this sequence. The depth is proportional to the risk, but no
step is skipped.

### 1. Analyze The Task

Before editing:

- state the intended result and measurable acceptance criteria;
- separate explicit requirements from assumptions;
- identify unknown product decisions, failure cases, security and data risks;
- check the current worktree and preserve user or concurrent changes;
- identify applicable skills and MCP sources, or explicitly record that none
  are needed.

### 2. Analyze Every Affected Place

Trace the full flow, not only the first matching screen or class. Search for:

- navigation, pages, components, state, query keys, cache and real-time
  invalidation;
- client ports, adapters, DTO mappers and same-origin gateway routes;
- OpenAPI operations, event schemas, producers and every active consumer;
- controllers, application services, domain transitions, repositories and
  authorization;
- JPA mappings, Flyway migrations, constraints, indexes and existing data;
- idempotency, optimistic concurrency, retries, outbox/inbox and recovery;
- panel, manager app, worker app and external integrations that consume the
  changed behavior;
- focused tests, contract checks, runtime configuration and observability.

Use `rg` or `rg --files` first for repository searches. Do not infer that a
flow is isolated until its callers and consumers have been checked.

### 3. Create A Plan Before Implementation

Publish a working plan in commentary or the plan tool before changing code.
For a small task, a concise two- or three-step plan is enough. A non-trivial
plan must name:

- the owning component and files or areas to change;
- contract and business-logic impact;
- persistence, migration and existing-data impact;
- active consumers and compatibility behavior;
- obsolete implementation to delete;
- focused tests and architecture checks;
- affected runtime services that may need an update;
- selected skills and MCP tools, including `none` when none apply.

Stop and ask a focused question when implementation would require an
unrequested breaking contract, a change of domain owner, an unresolved
business invariant or a destructive data decision. Do not stop for a detail
that current authoritative sources answer unambiguously.

### 4. Implement The Smallest Complete Change

- Deliver one production implementation of the requested behavior end to end.
- Do not concentrate independent use-case families in a god class, coordinator,
  adapter or shared base. A compatibility facade may preserve the public API,
  but it contains delegation rather than business decisions; each collaborator
  owns one cohesive workflow and receives only the dependencies it needs.
  Never disguise the same coupling as a universal `*Support`, dependency bag,
  inherited repository surface or single mega-coordinator.
- Change a canonical contract and all affected producers/consumers together
  when the boundary must evolve.
- Keep commands and business orchestration in the owning service, not in a UI,
  gateway or read projection.
- Remove replaced legacy code, adapters, storage, fixtures, tests and
  configuration in the same task. Do not hide them behind a flag, unused
  export, unreachable route, CSS or fallback.
- Do not fabricate successful data or silently fall back to mocks when a
  service, token or contract is unavailable.
- Preserve unrelated dirty-worktree changes and avoid broad mechanical edits.

### 5. Test After Implementation

Run the narrowest checks that cover the final diff and relevant failure paths.
Tests happen after implementation and fixes continue until the focused gate is
green or an external blocker is proven.

Never claim a check that was not executed. If infrastructure prevents a test,
name the exact blocker and run the strongest honest replacement.

### 6. Review Architecture

Before handoff, review the final diff for:

- correct service and data ownership;
- dependency direction and absence of cross-service persistence coupling;
- consistency with OpenAPI and event contracts;
- authentication, authorization and warehouse isolation;
- transaction boundaries, concurrency, idempotency and retry safety;
- single source of truth and removal of duplicate business state;
- cache and real-time invalidation scope;
- observability and recoverable failure behavior;
- absence of new legacy paths, compatibility shims and browser-owned domain
  state.

Fix violations within scope. If fixing one requires a new product decision,
report it and ask before proceeding.

### 7. Update Documentation And Project Knowledge

Documentation is part of every implementation and refactoring task, not a
separate optional cleanup:

1. add meaningful JavaDoc/KDoc/GoDoc for every newly added real type declaration,
   including package-private, private, nested and local classes, interfaces,
   records, enums and objects; update it for every changed public or
   architecture-significant type and for every changed method whose ownership,
   authorization, transaction, fencing, idempotency, retry, time or recovery
   semantics are not obvious from its signature;
2. when behavior, dependencies, configuration, API, persistence, events,
   recovery, operational checks or internal component structure changes,
   update the owning component's `README.md` and `README.ru.md` together with
   matching section order and identical facts, warnings, commands and
   references;
3. update the relevant structure and flow descriptions in
   `docs/project-knowledge/` whenever deployables, packages, collaborators,
   ownership boundaries or cross-component flows change;
4. cite the authoritative repository paths that prove every durable statement;
5. append one concise entry to
   `docs/project-knowledge/change-log.md` when architecture, an owner, a
   business invariant, a contract or an important cross-component flow changes;
6. place unresolved decisions in
   `docs/project-knowledge/open-questions.md` instead of inventing an answer.

If a change has no documentation-visible effect, state that explicitly in the
handoff after checking the paired README and structure map. Do not add filler
comments or touch documentation merely to create a diff.

Do not copy complete schemas or implementation details that will immediately
drift. Link to canonical sources and record the durable meaning.

### 8. Report The Result

Every final response includes:

- what is now working;
- the main files or areas changed;
- exact tests and their results;
- contract and architecture review result;
- runtime update/status when relevant;
- skills and MCP tools actually used, or `none`;
- found remarks, risks, unresolved questions and known follow-up work.

Do not present a partial implementation as complete.

## Project Knowledge Base

`docs/project-knowledge/` is the canonical, maintained map of current RWMS
architecture and business logic. Start at
[`docs/project-knowledge/README.md`](docs/project-knowledge/README.md).

The folder contains:

- `architecture.md` — deployables, ownership and dependency boundaries;
- `service-catalog.md` — current component responsibilities and primary source
  locations;
- `runtime-flows.md` — confirmed request, command, event, saga and projection
  sequences;
- `cabin-lifecycle.md` / `cabin-lifecycle.ru.md` — the confirmed bilingual
  end-to-end cabin workflow and its supported variants;
- `domain-logic.md` — confirmed business responsibilities and invariants;
- `contracts.md` — contract locations and safe change procedure;
- `documentation-standard.md` — JavaDoc/KDoc/GoDoc and bilingual README rules;
- `change-log.md` — append-only record of durable architecture/logic changes;
- `open-questions.md` — unresolved product decisions and contradictions.

Read only the sections relevant to the task, then verify them against the
current contracts and code. If a knowledge document is stale, correct it as
part of the task.

Historical content under `docs/plans/` and `WMS_ARCHITECTURE_KNOWLEDGE/` may be
used to understand provenance. It must never be copied into current behavior
without verification.

## Skills And MCP

### Skills

- At task start, inspect the available skills. If the user names a skill or the
  task clearly matches one, read its complete `SKILL.md` before taking task
  actions and follow it.
- Use the smallest set of skills that covers the task. Announce selected skills
  and why; if no skill applies, say so.
- A skill does not override the current user command, canonical RWMS contracts
  or these repository rules.
- Record durable facts found through a skill in the project knowledge base only
  after validating them against current repository sources.

### MCP

- Prefer an installed MCP source when it is the direct authority for external
  context, such as a referenced issue, design, document or repository object.
- Prefer repository files for repository facts. Do not call MCP or the web only
  to repeat information already available locally.
- MCP discovery is read-only by default. External writes, messages, comments,
  tickets, uploads or document edits require user scope that authorizes them.
- Never send secrets, tokens, private keys, customer data or unnecessary source
  code to an external MCP.
- State which MCP was used and why in the plan and final report. State `none`
  when no MCP was needed.

## Stack And Deployable Boundaries

- Web panel: React, TypeScript, Vite, React Router, TanStack Query/Table,
  Tailwind CSS and shadcn-based components under `panel/`.
- Spring services: Java, Spring Boot, Gradle Kotlin multi-module, Spring Data
  JPA and PostgreSQL under `services/`.
- Android clients: the manager app under `app/` and worker app under
  `worker-app/`.
- Schema authority: service-local Flyway only. Liquibase and Hibernate schema
  mutation are forbidden.
- Authentication: OAuth2/OIDC Authorization Code with PKCE for interactive
  clients and locally validated Bearer JWTs for APIs.
- Messaging and objects: Kafka and private MinIO.
- Edge: stateless `api-gateway-service`.
- Media: one stateful Go `media-service`; do not create a second photo or media
  processing deployable.

Every stateful service owns one PostgreSQL database. Cross-database foreign
keys, joins, shared tables, shared repositories, shared JPA entities and shared
mutable domain models are forbidden.

## Android Download-Site Ownership

- `worker-download-site/` is the sole download surface for WorkerApp from
  `worker-app/` (`dev.buhanzaz.rwms.worker`). It publishes only WorkerApp
  release metadata and APKs. Its `release.json` is the rendered release source;
  a pending manifest must not expose a download link.
- `manager-download-site/` is the separate download surface for ManagerApp from
  `app/` (`dev.buhanzaz.rwms.manager`). It publishes only ManagerApp release
  metadata and APKs.
- Never place, link, mirror or describe a WorkerApp APK in
  `manager-download-site/`, or a ManagerApp APK in `worker-download-site/`.
  Do not use the panel or either Android app as a release-file host.
- Each site is deployed independently. Before an authorized publish, identify
  the exact source revision, reviewed APK, immutable public artifact URL,
  package/version/signing identity and SHA-256 for that application's own
  download site. A WorkerApp public URL does not exist until such a release is
  actually published and verified.

## Domain Ownership

| Flow                                                                                 | Owner                                                            |
| ------------------------------------------------------------------------------------ | ---------------------------------------------------------------- |
| Login, users, roles, OAuth/OIDC clients and warehouse access                         | `auth-service`                                                   |
| Warehouse identity, metadata and timezone                                            | `warehouse-service`                                              |
| Cabins, status, equipment, balances, holds and leases                                | `asset-service`                                                  |
| Queues, workforce, assignments and operational board                                 | `task-board-service`                                             |
| Catalog, estimates, repairs, acceptance and write-off decisions                      | `maintenance-service`                                            |
| Inventory sessions, findings, completion and publication                             | `inventory-service`                                              |
| Rental counterparties, inquiries, returns, shipments, transfers and drivers          | `logistics-service`                                              |
| Media metadata, upload/finalize, originals and transformations                       | `media-service`                                                  |
| Cross-domain cabin activity                                                          | read-only `dossier-service` projection                           |
| KPI and dashboard facts                                                              | `analytics-service` projections                                  |
| Assistant conversations and tool-call history                                        | `assistant-service`; rental availability remains logistics-owned |

`api-gateway-service` owns no database, Kafka participation, token storage,
workflow, cache or business aggregation. `dossier-service` and analytics
projections never own commands for producer aggregates.

## Panel, Android And Gateway Rules

- Interactive clients use the public gateway only. Browser requests use
  same-origin `/auth/**` and `/api/**` routes.
- Do not put internal service origins or `localhost:<service-port>` into panel
  runtime configuration.
- Clients never call `/api/internal/**` or private service routes.
- Missing token, gateway configuration or service is an explicit error, not a
  mock fallback.
- Use the shared Bearer client and map Problem Details consistently.
- Mutable commands use `expectedVersion`, ETag or another contract-defined
  fencing token and show a consistent `409` conflict.
- Retried creates and effects use `Idempotency-Key` or a stable domain external
  identifier.
- The panel may combine independent public reads. The gateway must not
  aggregate business responses.
- Server data is authoritative. Local storage is allowed only for
  non-authoritative UI preferences, encrypted client session material where
  required and explicitly designed offline caches.
- Real-time messages are invalidation signals unless their contract declares a
  complete projection. Refresh or patch only affected query/cache entries;
  avoid global cache clears and stale image URLs after media revisions.
- Preserve the current supported UX when it matches domain semantics. If no
  contract exists for a control, make it unavailable and explain the gap; do
  not fabricate success.

## Contracts, Services And Events

- A service owns its aggregate transitions and server-side orchestration.
  Browser sagas are forbidden.
- Internal service calls use private addresses and service credentials, not the
  public gateway.
- PostgreSQL event stores and projections are authoritative; Kafka is
  at-least-once transport, not an archive or database.
- Preserve transactional outbox/inbox, aggregate-key ordering, deduplication
  and version-gap handling when a touched service uses them.
- Effects and consumers must be idempotent. Infinite retry and 2PC are
  forbidden.
- The initiating service owns saga state, recovery and compensation. A UI
  rollback is not a consistency mechanism.
- Each status and invariant belongs to one service. Consumers store only
  contract-defined IDs or immutable snapshots and tolerate compatible
  additions.
- Generated clients and transport DTOs are boundary types, never persistence
  entities or shared domain models.

## Spring Data, Flyway, Lombok And MapStruct

Use Spring Data JPA for stateful Spring persistence. When changing a JPA entity,
repository or projection, apply the available `spring-data-jpa` skill before
editing.

- JPA mappings define the application model; Hibernate never creates, updates
  or drops the target schema.
- Every target profile uses `hibernate.ddl-auto=validate`.
- Flyway migrations are immutable, ordered and service-local.
- `baselineOnMigrate` remains false. A non-empty unversioned schema requires an
  explicit proven adoption procedure.
- Destructive schema changes use expand/contract and explicit data handling.
- Run a clean install or affected upgrade path plus JPA validation for schema
  changes.
- JPA entities must not use Lombok `@Data`, generated builders,
  `equals/hashCode/toString`, or generated setters for IDs, versions,
  timestamps and invariants.
- Use MapStruct at touched Spring boundaries that map entities/projections to
  DTOs or sanitized integration payloads. Use Spring component model,
  constructor injection and `unmappedTargetPolicy=ERROR`.
- Do not use MapStruct for request-to-entity mutation, security or secret
  mapping, version mutation, checksums, outbox construction or domain
  transitions.
- `platform:technical-contracts` remains framework-neutral and contains no
  Spring, JPA, Kafka or business-domain model.

## Legacy Removal And Data Safety

- Delete an obsolete runtime path when its replacement is delivered. Also
  delete its mock selectors, browser stores, seeds, fixtures, compatibility
  adapters, tests and configuration once no supported flow references them.
- Do not retain old code as a fallback or automatically import localStorage,
  IndexedDB, `old_db/` or legacy exports into PostgreSQL.
- Old data may be disregarded when designing current behavior unless the user
  explicitly requests import or backward compatibility.
- Never delete or rewrite an actual database, volume, backup, object bucket or
  user data merely because it is old. Resolve exact targets and obtain explicit
  authorization for destructive operations.

## Focused Verification

- Panel change: affected Vitest tests plus `npm run typecheck`; add lint/build
  when the boundary requires it.
- Spring change: affected module/package tests or compile check.
- Contract change: schema validation plus focused producer and consumer
  compatibility tests.
- Flyway/JPA change: affected clean install or upgrade path and JPA validation.
- Go media change: affected Go package tests and build.
- Auth, authorization, concurrency, cache, SSE and integration changes: cover
  the smallest relevant success and failure paths.
- Documentation-only change: validate links/references and run whitespace or
  formatting checks available in the repository.
- Android or Android-consumed contract change: run affected unit/contract
  tests, build the exact APK and update only its owned download site:
  `worker-download-site/` for WorkerApp or `manager-download-site/` for
  ManagerApp. Before claiming end-to-end validation, install that APK on an
  emulator or device, authenticate against the intended public gateway and
  verify the first workspace request plus the changed flow. Capture a UI tree
  or screenshot and filtered logcat. If the gateway or credentials are
  unavailable, report the blocker and do not claim end-to-end validation.

Use full Testcontainers, browser automation, Kafka outage/replay or
cross-service suites when risk requires them, focused checks reveal a wider
issue or the user asks.

## Agent Coordination

Use one primary agent by default. Additional agents are allowed only for
concrete parallel coding lanes with explicit, non-overlapping file ownership,
frozen contracts and independent focused verification. Do not create separate
discovery, planning, documentation, reviewer, status or QA agents, except for
the required read-only Luna web UI verification agent below and the lightweight
operational delegation described next. Additional agents must not create their
own subagents.

### Lightweight Operational Delegation To Luna

For a bounded, low-risk operational action, start a separate
`gpt-5.6-luna` agent instead of consuming primary-agent context. This includes
restarting or starting an already understood service, running a known focused
command, collecting status or logs, and moving a known file when the source and
destination are explicit. Give Luna the exact command or file operation,
expected result, permitted scope and any required verification.

This delegation does not transfer product decisions or safety authority. The
primary agent must retain actions that change code or contracts, resolve
conflicts, touch protected dirty files, choose runtime targets, perform
destructive operations, handle secrets or external writes, or require
investigation beyond the specified operation. Luna reports the raw command
output and changed paths; the primary agent validates the result before
proceeding.

### Web UI Verification With Luna

For every change to the active web panel UI, the focused web UI verification
must be executed by a separate agent explicitly started with the
`gpt-5.6-luna` model. This includes component or interaction Vitest suites and
Playwright or other real-browser flows; panel type checking, linting and builds
may be included in the same serialized verification gate. The implementation
agent may write the UI and its tests, but it must not be the only executor or
approver of those tests.

The primary agent remains responsible for the verdict. Before the Luna run it
must review and freeze the exact source scope, test inputs, expected user flow,
commands and required artifacts. After the run it must inspect the raw output,
test counts, timestamps and any screenshots, traces or reports, compare them
with the requested behavior, and direct any required fix and rerun. A Luna
summary alone is not proof that the UI is correct. The Luna verification agent
is read-only for production and test sources unless it receives a separate,
explicit coding assignment. Backend-only, contract-only and native Android
checks continue to follow their own focused-verification rules.

Before starting another coding agent, state its exact outcome, exclusive files
or directories, frozen inputs, files it must not touch and required checks.
One owner at a time is mandatory for `AGENTS.md`, shared navigation, a service's
OpenAPI family, Flyway directory, JPA aggregate and other high-conflict files.

Every agent preserves concurrent changes, does not commit unless assigned and
reports changed files, checks and remaining risks.

### Shared-Worktree Handoff Protocol

The shared worktree is not a scratch space. Every modified, deleted or
untracked file that predates an agent's first write is protected work owned by
the user or another task, even when its author is unknown.

- Before assigning a coding lane or editing a file, the primary agent records
  `git status --short` and identifies every dirty or untracked target in the
  task plan/commentary. It must name the active owner of each touched file.
- A child agent may not touch a protected file unless the primary explicitly
  assigns that exact file and confirms its pre-existing changes are part of
  the frozen input. Two agents may never edit the same file concurrently.
- Before the first write to a protected file, save a local, task-scoped
  pre-edit diff/content snapshot outside the repository. At handoff, compare
  the final diff with that snapshot. A pre-existing hunk or untracked file
  must not disappear, be reverted, be reformatted wholesale or be replaced
  from `HEAD` unless the user explicitly authorized that exact loss.
- If a requested edit overlaps an unknown protected hunk, stop and report the
  conflict. Do not "resolve" it by regenerating the file, applying a broad
  rewrite, choosing the current branch version, or silently dropping either
  change.
- A handoff report must distinguish files changed by that agent from files
  that were already dirty, and must state whether the protected-diff check
  passed. The primary performs this check before declaring the task complete.

## Local Runtime And VPS

`compose.yaml` is for isolated local development and test dependencies. Do not
add production orchestration, ingress, TLS or release infrastructure unless the
user explicitly requests it.

After a runtime code task, update only the affected running test services on
the VPS when the environment is available, then inspect their status and logs.
Documentation-only tasks require no service restart.

### Publication Guard For Uncommitted Work

Never treat a successful build, a new file timestamp, or an HTTP 200 as proof
that the intended product change was published. A deployment can otherwise
replace a working WIP screen with the older `HEAD` version.

- Do not build or publish a panel bundle, APK, service image or other runtime
  artifact from a clean checkout, another worktree, `HEAD`, or a generated
  directory when the requested source change exists only as protected
  uncommitted work in the shared worktree.
- Before publishing, state the exact source revision and dirty-file scope that
  the artifact must contain. If the worktree also contains unrelated WIP, stop
  and ask whether to create an isolated, reviewed release scope; never publish
  the entire mixed worktree by accident.
- After publishing, verify the running artifact itself contains the requested
  behavior (a focused UI/API check or an unambiguous release marker), not just
  that the deploy command succeeded. For a panel, inspect the served bundle or
  run the affected UI flow; for an APK, inspect its version/hash and install
  the exact file tested.
- If this verification fails, do not describe the change as deployed. Preserve
  the source WIP, report the mismatch between source and runtime, and request
  the smallest release decision needed to correct it.

Never expose PostgreSQL, Kafka, MinIO administration, internal service ports or
management endpoints publicly. Do not commit runtime credentials, private
keys, generated keystores, logs or secrets.

## Git And Workspace Safety

- Preserve all user and concurrent changes; stage only intended files or
  hunks.
- Never use `git reset`, `git checkout`, `git restore`, `git clean`, `git
  stash`, forced branch switching, broad code formatters or bulk rewrites to
  clean, hide or reconcile a shared worktree. This includes commands limited
  to one path: a dirty path is protected until the user explicitly identifies
  the exact change to discard.
- Never delete, rename, overwrite or recreate a dirty/untracked file merely
  because it is unrelated, old, incomplete or conflicts with the task. The
  user is the only default authority to discard another task's uncommitted
  work.
- Do not commit generated binaries, build output, browser artifacts, IDE state
  or secrets.
- Do not commit, push or open a pull request unless the user asks.
- Before a requested commit, verify identity and use `buhanzaz` with the user's
  configured email and a short human message.
- Do not put agent, model or tool names in branches, directories, commits or
  pull-request metadata, and do not use a `codex/` branch prefix.
