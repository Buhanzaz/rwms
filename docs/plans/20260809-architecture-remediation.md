# RWMS Architecture Remediation Execution Plan

Status: implementation in progress, verified against the repository on
2026-08-09. The first risk-critical batch covers Tasks 1–6; no commit, publish,
deployment or destructive data operation is part of that batch.

This document is not an architecture source of truth. The current user command,
[`AGENTS.md`](../../AGENTS.md), canonical contracts, owner code and
[`docs/project-knowledge/`](../project-knowledge/README.md) remain authoritative.
The confirmed defects and evidence are in the
[`full architecture audit`](../reviews/20260808-full-architecture-audit.md).

## Overview

The objective is to close all 24 confirmed audit findings without changing
domain ownership, hiding failures behind compatibility paths, bulk-unblocking
durable state, or publishing mixed uncommitted work. The implementation order
is risk-first:

1. isolate identity and fail closed before a new effect is accepted;
2. make remote effects and projections durably recoverable;
3. enforce contracts, release integrity and bounded client behavior;
4. remove obsolete runtime paths only after caller and data proof.

The plan is complete when every RWMS ID has production code, success and
failure-path tests, documentation, an architecture review and operational
evidence. A successful startup or compilation alone does not close an item.

## Context From Discovery

- Amplicode `get_project_summary` confirmed Java, Spring Boot 4.1.0 and all
  active Java deployables, including assistant, analytics and gateway.
- Stateful Spring services use Spring Data JPA and service-local Flyway;
  `hibernate.ddl-auto=validate` remains mandatory.
- The manager and worker clients are separate Android Gradle projects; the web
  panel uses React, TanStack Query and the public gateway.
- Media is the existing Go service and remains the only media-processing owner.
- The 16 confirmed god classes have already been decomposed. New remediation
  code must extend the current cohesive collaborators, not rebuild a universal
  support class or mega-coordinator. See the
  [`decomposition review`](../reviews/20260808-god-class-decomposition.md).
- The shared worktree contains extensive protected uncommitted work. Before
  each implementation task, record `git status --short`, assign one owner per
  touched file and save protected snapshots outside the repository.

## Explicit Requirements And Assumptions

### Explicit requirements

- Execute only the user-selected remediation scope and keep incomplete or
  decision-blocked acceptance items visibly open.
- Preserve canonical ownership and trace every active producer and consumer
  when a contract changes.
- Include persistence, existing-data, security, idempotency, recovery,
  observability, documentation and runtime verification.
- Do not commit, publish, deploy, delete live data or rewrite a published
  migration without a separate user command that authorizes the exact action.

### Working assumptions

- Testing approach: regular, smallest-complete implementation followed by
  focused tests in the same task. No task proceeds with a red focused gate.
- Active canonical compatibility is preserved. Obsolete mock, cutover and
  import compatibility is not retained once its removal is proven and
  authorized.
- Every new JPA table is flat and owner-local unless the owning aggregate proves
  a relation is required. Cross-service JPA relations and foreign keys remain
  forbidden.
- Remote HTTP calls never remain open inside a local database transaction.
- Existing ownerless or terminal records are quarantined or reviewed; they are
  never assigned, unblocked or deleted by inference.

## Decisions Required Before Blocked Tasks

Record approved choices in
[`open-questions.md`](../project-knowledge/open-questions.md) before changing
contract meaning or persistence semantics.

| Decision | Recommended direction | Blocks |
| --- | --- | --- |
| Orphaned assistant inquiry | Reconstruct and attach using a durable stable identity; compensate only through a separately authorized owner command | Task 7 |
| Assistant private identity | Service credential with narrow scope plus immutable end-user actor context; do not forward an interactive Bearer implicitly | Tasks 7 and 9 |
| LLM data boundary | Schema allow-list, explicit redaction, hard token/turn budget, approved provider region and retention; fail closed outside the allow-list | Task 9 |
| Analytics archive and `COMPLETE` | Owner-authoritative replay/snapshot plus reviewed recovery; `COMPLETE` proves every expected warehouse-local date and active group | Task 10 |
| Logistics replay admission proof | Resolved 2026-08-09: persist exact direction/version evidence on new permanent marks; legacy null evidence re-admits or fails closed | None |
| Worker reconnect semantics | Invalidation-only reconnect with mandatory authoritative refresh and no replay promise, unless durable replay is explicitly required | Task 16 |
| Android release trust | Select one site implementation, signing authority/key custody, immutable artifact store and manifest fields | Task 15 |
| Evidence retention | Define retention, legal hold, archive verification, restore and deletion authority before destructive cleanup | Task 19 |

## Development Approach

- Complete one task as a production-ready slice before starting a dependent
  task. Independent tasks may run in parallel only with explicit non-overlapping
  file ownership and frozen contracts.
- Every changed public or architecture-significant type receives meaningful
  JavaDoc/KDoc/GoDoc. Every newly added real Java/Kotlin type is documented.
- Update paired `README.md` and `README.ru.md` in the owning component whenever
  behavior, configuration, recovery, persistence or internal structure changes.
- Update project knowledge and append one change-log row for every durable
  architecture, owner, invariant, contract or cross-component-flow change.
- For JPA work, apply the `spring-data-jpa` workflow before editing, use explicit
  mappings, keep relationships lazy and narrow, avoid Lombok `@Data`/builders
  on entities, add immutable service-local Flyway migrations and validate JPA
  against both a clean schema and the supported upgrade path.
- Keep outbox/inbox ordering, payload hashes, idempotency and optimistic fences
  intact. Effects use stable identities and bounded retry.
- Run formatting only on owned files and use the project formatter; never run a
  broad formatter over protected work.
- Update this file immediately if scope or dependency order changes. Use `[+]`
  for a discovered task and `[!]` for a blocker.

## Progress Tracking

- Mark a checklist item `[x]` only after its production change, focused tests,
  architecture review and required documentation are complete.
- Prefix newly discovered in-scope work with `[+]` and record its owning task,
  files, risk and dependency before implementation.
- Prefix an impasse with `[!]`, link the exact open question or external
  blocker and leave the affected task incomplete.
- Record exact commands and results in the final evidence review; do not replace
  a failed or timed-out result with a later unrelated green command.

## Solution Overview

The solution is a set of owner-local vertical slices joined only through
canonical contracts. Identity and startup safety prevent new unsafe work;
durable attempts and replay boundaries recover work already accepted; contract
and observability gates then make regressions visible. No shared remediation
database, browser saga, cross-service repository or new deployable is created.

## Dependency Order

This is ordering, not a migration stage or cutover pointer.

| Order | Tasks | Dependency rule |
| --- | --- | --- |
| Immediate | 0–6 | Tasks 1–6 may start after read-only baseline capture; they do not require unresolved product semantics |
| Durable recovery | 7–13 | Tasks 7, 9 and 10 wait for their recorded product decisions; 8, 11, 12 and 13 can proceed independently |
| Enforcement and clients | 14–18 | Contract gates follow the relevant owner fixes; release and SSE work wait for decisions |
| Controlled removal | 19 | Runtime removal waits for caller/data proof and retention authorization |
| Closure | 20 | Runs only after every selected task is green and documented |

## Risk-To-Task Coverage

| Task | Audit IDs |
| --- | --- |
| 1 | RWMS-001, RWMS-008 |
| 2 | RWMS-009 |
| 3 | RWMS-002 |
| 4 | RWMS-003 |
| 5 | RWMS-014 |
| 6 | RWMS-015 |
| 7 | RWMS-004 |
| 8 | RWMS-010 |
| 9 | RWMS-011 |
| 10 | RWMS-005, RWMS-013 |
| 11 | RWMS-006 |
| 12 | RWMS-007 |
| 13 | RWMS-012 |
| 14 | RWMS-016, RWMS-023 |
| 15 | RWMS-017 |
| 16 | RWMS-019, RWMS-020 |
| 17 | RWMS-021 |
| 18 | RWMS-022 |
| 19 | RWMS-018, RWMS-024 |

## Technical Details

### Persistence And Existing-Data Rules

- Migrations are expand-only during remediation. Do not rename/drop columns or
  tables in the same release that introduces the replacement.
- New durable attempts store stable command identity, payload hash, state,
  attempt/lease metadata, timestamps and sanitized terminal reason. They do not
  store raw tokens, prompts or customer payloads.
- Claiming uses stable ordering and `FOR UPDATE SKIP LOCKED` or an equivalent
  lease fence. HTTP runs after the claim transaction closes; completion checks
  the same lease/fingerprint in a new transaction.
- Production backlog inspection is read-only. Drain existing outbox in
  aggregate order after relays and alerts are active; do not recreate rows.
- Terminal analytics checkpoints, dossier unlinked facts, media attempts and
  ownerless Android records are handled individually with audit evidence.
- Any destructive migration, archive or cleanup requires an exact row/volume
  inventory, backup/restore proof, rollback plan and explicit user approval.

## What Goes Where

- `Implementation Steps` contains only repository work that can be completed
  and verified in RWMS. Each `Files` block is the prospective exclusive scope
  for that task and must be revalidated against the worktree before editing.
- `Post-Completion` contains external decisions, release actions, shared
  environment drills and destructive operations; those are not implied by
  checking an implementation task.
- Canonical contract edits live under `contracts/`; business transitions stay
  in their owning service; gateway and clients contain transport/UI policy
  only; service-local Flyway remains the sole schema authority.

## Implementation Steps

### Task 0: Freeze Decisions And Capture A Read-Only Production Baseline

**Files:**

- Modify after decisions: `docs/project-knowledge/open-questions.md`
- Modify after decisions: `docs/project-knowledge/change-log.md`
- Create per execution: a sanitized review under `docs/reviews/` with no
  credentials, personal data or raw payloads

- [ ] assign one engineering owner and reviewer to every RWMS ID
- [ ] record the seven decisions listed above; leave unresolved items marked
  `[!]` and do not start their blocked task
- [ ] inventory pending Android uploads by schema/owner state without opening
  retained media
- [ ] measure outbox count and oldest age in warehouse, asset, maintenance and
  logistics without updating rows
- [ ] inventory logistics deployments with disabled dependencies, terminal
  analytics gaps, unresolved dossier facts and oldest media jobs
- [ ] capture current alerts, runtime profiles and release artifact hashes
- [ ] verify the baseline procedure is read-only and reviewed before Task 1

### Task 1: Scope Manager Durable State To The Verified Account

**Audit IDs:** RWMS-001, RWMS-008
**Owner:** manager Android app

**Files:**

- Modify: `app/src/main/java/dev/buhanzaz/rwms/manager/uploads/BackgroundUploadModels.kt`
- Modify: `app/src/main/java/dev/buhanzaz/rwms/manager/uploads/BackgroundUploadStore.kt`
- Modify: `app/src/main/java/dev/buhanzaz/rwms/manager/uploads/BackgroundUploadCoordinator.kt`
- Modify: `app/src/main/java/dev/buhanzaz/rwms/manager/uploads/BackgroundUploadWorker.kt`
- Modify: `app/src/main/java/dev/buhanzaz/rwms/manager/ui/coordinator/ManagerWorkspaceCoordinator.kt`
- Modify: `app/src/main/java/dev/buhanzaz/rwms/manager/ui/MaintenanceCatalogCache.kt`
- Modify: `app/src/main/java/dev/buhanzaz/rwms/manager/ui/coordinator/ManagerMaintenanceCatalogCoordinator.kt`
- Modify: `app/README.md`, `app/README.ru.md`
- Modify/create: focused tests under `app/src/test/`

Contract impact: no server transport change. Persistence impact: version the
local queue/catalog formats and quarantine ownerless legacy records. Active
consumer: WorkManager and the authenticated manager workspace.

- [x] load `/me` and establish verified `accountId` before any durable resume
- [x] add immutable `ownerAccountId` and warehouse scope to operations, drafts,
  files, WorkManager input and unique work names
- [x] filter list/retry/resume strictly by the verified account and warehouse
- [x] await logout and replacement-login cancellation, close the auth snapshot
  and prevent an old worker from acquiring a new principal token
- [x] migrate maintenance catalog storage to account+warehouse scope using the
  existing encrypted/authenticated local-storage pattern
- [x] quarantine legacy ownerless queue/cache entries without guessing an owner
- [ ] [!] add deterministic A→logout→B tests for pending, running, retry, process
  restart, same-warehouse catalog restore and grant downgrade
- [ ] [!] run manager JVM tests, `compileDebugKotlin` and `assembleDebug`;
  install the exact APK and verify A→B on an emulator before claiming
  end-to-end success

Current evidence: focused store/cache/policy tests are 15/15 and the exact
debug APK builds. A real WorkManager A→B integration and device/emulator login
remain open because two manager accounts and an intended public gateway were
not available; no end-to-end claim or publication was made.

### Task 2: Tear Down Protected Panel State On Principal Or Grant Change

**Audit ID:** RWMS-009
**Owner:** panel authentication boundary

**Files:**

- Modify: `panel/src/main.tsx`
- Modify: `panel/src/features/auth/auth-provider.tsx`
- Create: `panel/src/features/auth/protected-client-state.ts`
- Modify: `panel/src/features/media/media-preview-cache.ts`
- Create/modify: focused auth/cache tests under `panel/src/features/auth/`
- Modify: `panel/README.md`, `panel/README.ru.md`

Contract and persistence impact: none. Active consumers: all protected TanStack
queries, media blob URLs and real-time subscriptions.

- [x] define one authenticated subject/grant revision boundary
- [x] cancel in-flight protected requests before removing protected queries
- [x] clear protected query/mutation caches, media blobs and subscriptions on
  logout, subject change or grant-revision change
- [x] prevent old in-flight responses from repopulating the new principal cache
- [x] keep public/bootstrap queries explicitly outside the protected registry
- [x] add two-principal tests covering `staleTime: Infinity`, silent grant
  refresh, logout/login in one tab and late response completion
- [x] run affected Vitest tests and `npm run typecheck` before Task 3

### Task 3: Make Logistics Warehouse Admission Fail Closed

**Audit ID:** RWMS-002
**Owner:** logistics-service, consuming warehouse-service lifecycle API

**Files:**

- Modify: `services/logistics-service/src/main/java/dev/buhanzaz/rwms/logistics/service/LogisticsDocumentWarehouseAdmission.java`
- Modify: `services/logistics-service/src/main/java/dev/buhanzaz/rwms/logistics/service/LogisticsWarehouseLifecycle.java`
- Modify: `services/logistics-service/src/main/java/dev/buhanzaz/rwms/logistics/integration/DisabledLogisticsDependencyGateway.java`
- Create: `services/logistics-service/src/main/java/dev/buhanzaz/rwms/logistics/config/LogisticsProductionSafetyValidator.java`
- Modify: `services/logistics-service/src/main/resources/application.yaml`
- Modify: `services/logistics-service/README.md`, `services/logistics-service/README.ru.md`
- Create/modify: logistics production-safety and lifecycle integration tests

Contract impact: preserve current lifecycle API; return existing Problem Details
`503` semantics for dependency unavailability. Persistence impact: prefer no
schema change; add durable admission evidence only if an existing document
cannot prove an admitted replay.

- [x] reject production startup when real dependencies, service auth, Kafka or
  `productionReady()` are unavailable
- [x] reject a first create before document/outbox persistence on disabled,
  timeout, `DRAINING` or `INACTIVE`
- [x] allow `bypassed` only for an already persisted, fingerprint-matching
  operation whose admission was previously proven
- [x] preserve lost-response replay and permanent warehouse operation marks
- [x] add disabled/timeout/status/replay/mark/readiness tests with PostgreSQL
- [x] run the focused logistics suite and module compile before Task 4

Current result (2026-08-09): migration V39 stores exact direction/version
evidence only for new accepted operations. A dependency-free replay candidate
requires a live domain identity, live owner receipt and exact complete mark
vector; the owner remains checksum authority. Legacy/null evidence re-admits or
returns `503`. Compile/Javadoc, lifecycle 17/17 and the full logistics module
308/308 across 55 suites are green.

### Task 4: Require Kafka Delivery Capability In Every Event-Owner Production Profile

**Audit ID:** RWMS-003
**Owners:** warehouse, asset, maintenance and logistics services

**Files:**

- Create: `services/warehouse-service/src/main/java/dev/buhanzaz/rwms/warehouse/config/WarehouseProductionSafetyValidator.java`
- Modify: `services/asset-service/src/main/java/dev/buhanzaz/rwms/asset/config/AssetProductionSafetyValidator.java`
- Modify: `services/maintenance-service/src/main/java/dev/buhanzaz/rwms/maintenance/config/MaintenanceProductionSafetyValidator.java`
- Modify: `services/logistics-service/src/main/java/dev/buhanzaz/rwms/logistics/config/LogisticsProductionSafetyValidator.java`
- Modify: the four service-local `application.yaml` files and paired READMEs
- Create/modify: service-local production-safety and outbox-delivery tests

Contract and schema impact: runtime configuration contract only; do not rewrite
outbox rows. Existing consumers retain at-least-once semantics.

- [ ] require Kafka enabled, non-empty approved destinations, safe broker
  properties and relay beans in every production profile
- [ ] keep disabled Kafka only in explicitly identified dev/test profiles
- [ ] expose outbox count, oldest pending age and DLT/quarantine signals before
  draining existing backlog
- [ ] add negative startup matrix tests for each owner service
- [ ] test commit→outbox→relay, broker outage, restart and duplicate consumer
  delivery for each event owner
- [ ] drain measured backlog in aggregate order and verify consumer convergence
  only under separately authorized runtime scope

Current verified slice: warehouse, asset and maintenance production safety,
destination and producer-policy validation, relay/binding presence and backlog
metrics are implemented. Warehouse delivery/recovery is 6/6; asset
validator/metrics/config is 20/20, with Kafka outage/recovery 1/1 and PostgreSQL
outbox recovery 2/2. Maintenance validator/metrics/topic/channel/relay checks
are 45/45 and its real Kafka outage/recovery suite is 3/3; its canonical output
set now includes property disposition and sanitized DLT. The remaining
logistics event-owner checks stay open, as do runtime alert wiring and any
authorized backlog drain.

### Task 5: Validate Every Gateway Downstream Target Symmetrically

**Audit ID:** RWMS-014
**Owner:** api-gateway-service

**Files:**

- Modify: `services/api-gateway-service/src/main/java/dev/buhanzaz/rwms/gateway/config/GatewayProductionSafetyValidator.java`
- Modify: `services/api-gateway-service/src/test/java/dev/buhanzaz/rwms/gateway/config/GatewayProductionSafetyValidatorTest.java`
- Modify: gateway route integration tests
- Modify: `services/api-gateway-service/README.md`, `services/api-gateway-service/README.ru.md`

Contract and persistence impact: no route meaning or database change.

- [x] model every downstream as a typed target with an explicit public/private
  topology rule
- [x] reject loopback, userinfo, query, fragment, invalid scheme and wrong
  public/private split for every target
- [x] cover HTTP and SSE routes with one table-driven target matrix
- [x] avoid DNS reachability checks that make startup depend on transient
  network state; validate configuration shape and topology instead
- [x] run gateway startup-policy and route integration tests before Task 6

### Task 6: Remove Auth Production Datasource Defaults

**Audit ID:** RWMS-015
**Owner:** auth-service

**Files:**

- Modify: `services/auth-service/src/main/resources/application.yaml`
- Modify/create: an explicit dev-only auth configuration profile
- Modify: `services/auth-service/src/main/java/dev/buhanzaz/rwms/auth/config/AuthProductionSafetyValidator.java`
- Modify: auth production-safety tests
- Modify: `services/auth-service/README.md`, `services/auth-service/README.ru.md`

Contract and schema impact: configuration only; Flyway ownership is unchanged.

- [x] remove the base password default or make every local default dev-only
- [x] reject default/loopback datasource URL, username and password before
  production DataSource initialization without logging secret values
- [x] retain an explicit local-development bootstrap path
- [x] add negative startup tests for every missing/default combination and a
  positive production-shaped configuration test
- [x] run auth configuration, Flyway and startup-policy tests

### Task 7: Add A Durable Assistant Inquiry Creation Saga

**Audit ID:** RWMS-004
**Owner:** assistant-service; logistics remains inquiry owner
**Blocker:** approved orphan and identity decisions from Task 0

**Files:**

- Modify: `services/assistant-service/src/main/java/dev/buhanzaz/rwms/assistant/service/AssistantConversationService.java`
- Create: `services/assistant-service/src/main/java/dev/buhanzaz/rwms/assistant/domain/AssistantInquiryCreationAttempt.java`
- Create: `services/assistant-service/src/main/java/dev/buhanzaz/rwms/assistant/repository/AssistantInquiryCreationAttemptRepository.java`
- Create: `services/assistant-service/src/main/java/dev/buhanzaz/rwms/assistant/service/AssistantInquiryCreationCoordinator.java`
- Create: `services/assistant-service/src/main/java/dev/buhanzaz/rwms/assistant/service/AssistantInquiryCreationReconciler.java`
- Modify: `services/assistant-service/src/main/java/dev/buhanzaz/rwms/assistant/integration/HttpLogisticsClient.java`
- Create: next immutable migration under `services/assistant-service/src/main/resources/db/migration/`
- Modify together if required: assistant/logistics OpenAPI and auth service-client scopes
- Create/modify: assistant saga integration tests

Persistence: `PREPARED -> REMOTE_CONFIRMED -> FINALIZED`, plus reviewed
quarantine; stable `conversationId` is the remote idempotency identity. No
cross-service foreign key.

- [ ] persist prepared intent and actor snapshot in a short local transaction
- [ ] call logistics outside the local transaction using the approved identity
- [ ] persist response fingerprint before finalizing the conversation link
- [ ] claim recovery with a lease and reconcile lost responses across replicas
- [ ] quarantine conflicting replay without attaching or compensating by guess
- [ ] add Flyway clean/upgrade and JPA validation tests
- [ ] add fault injection after prepare, remote commit, response receipt and
  before/after finalize, plus restart and two-replica tests
- [ ] run assistant and affected logistics/auth contract suites

### Task 8: Canonicalize Assistant Booking Events And Search Idempotency

**Audit ID:** RWMS-010
**Owner:** assistant-service; logistics owns search effects

**Files:**

- Modify: `services/assistant-service/src/main/java/dev/buhanzaz/rwms/assistant/eventing/RentalInquiryBookedKafkaConsumer.java`
- Modify: `services/assistant-service/src/main/java/dev/buhanzaz/rwms/assistant/eventing/RentalInquiryBookedEventParser.java`
- Modify: `services/assistant-service/src/main/java/dev/buhanzaz/rwms/assistant/repository/AssistantEventInboxRepository.java`
- Modify: assistant inbox entity and create the next service-local Flyway migration
- Modify: `services/assistant-service/src/main/java/dev/buhanzaz/rwms/assistant/integration/HttpLogisticsClient.java`
- Modify together if required: canonical booking event schema and logistics OpenAPI
- Create/modify: consumer, inbox conflict and search retry tests

- [ ] use the canonical V2 envelope, schema registry reference and delivery
  policy instead of a raw string-only path
- [ ] persist canonical payload hash and treat same ID/different payload as a
  conflict, never as a duplicate success
- [ ] implement bounded DLT/reviewed replay with source coordinates
- [ ] derive one stable search idempotency key per durable assistant tool call
- [ ] ensure logistics verifies identical-key payload fingerprints
- [ ] test duplicate, conflicting duplicate, malformed envelope, lost HTTP
  response, restart and concurrent retry
- [ ] run assistant eventing and affected logistics integration suites

### Task 9: Enforce Assistant Service Identity And LLM Data Minimization

**Audit ID:** RWMS-011
**Owner:** assistant-service and auth service-client policy
**Blocker:** approved identity, provider, allow-list and retention decisions

**Files:**

- Modify: `services/assistant-service/src/main/java/dev/buhanzaz/rwms/assistant/integration/HttpLogisticsClient.java`
- Modify: `services/assistant-service/src/main/java/dev/buhanzaz/rwms/assistant/service/AssistantTurnService.java`
- Modify: `services/assistant-service/src/main/java/dev/buhanzaz/rwms/assistant/service/AssistantToolExecutor.java`
- Modify: `services/assistant-service/src/main/java/dev/buhanzaz/rwms/assistant/integration/HttpChatCompletionClient.java`
- Create: an explicit outbound LLM DTO/redaction policy in assistant-service
- Modify: auth service-client configuration and canonical actor-context contract
- Modify: assistant/auth paired READMEs and project knowledge
- Create/modify: security, redaction and context-budget tests

- [ ] replace implicit interactive-token forwarding with the approved private
  identity model and immutable actor context
- [ ] enforce schema-level outbound field allow-list and deterministic redaction
- [ ] enforce hard token/turn/tool-history budgets before provider invocation
- [ ] prevent prompts, Bearer tokens and raw provider bodies from logs/metrics
- [ ] fail closed on unknown fields or an unapproved provider/region
- [ ] test scope/audience/actor audit, prohibited fields, truncation boundaries,
  provider errors and log redaction
- [ ] run assistant security/integration and auth service-client tests

### Task 10: Recover Analytics Gaps And Prove Period Completeness

**Audit IDs:** RWMS-005, RWMS-013
**Owner:** analytics-service; producer owners expose only the approved replay boundary
**Blocker:** approved archive/replay and completeness decisions

**Files:**

- Modify: `services/analytics-service/src/main/java/dev/buhanzaz/rwms/analytics/domain/AnalyticsAggregateCheckpoint.java`
- Modify: `services/analytics-service/src/main/java/dev/buhanzaz/rwms/analytics/service/AnalyticsGapRecoveryService.java`
- Modify: `services/analytics-service/src/main/java/dev/buhanzaz/rwms/analytics/service/AnalyticsInboxProcessor.java`
- Modify: `services/analytics-service/src/main/java/dev/buhanzaz/rwms/analytics/service/AnalyticsProjectionService.java`
- Modify: `services/analytics-service/src/main/java/dev/buhanzaz/rwms/analytics/service/AnalyticsQueryService.java`
- Create: owner-local recovery-audit and expected-coverage persistence plus next
  analytics Flyway migration
- Modify together: technical checkpoint policy, analytics OpenAPI and approved
  producer replay/snapshot contracts and implementations
- Create/modify: analytics gap, completeness, Flyway and contract tests

- [ ] acquire missing owner-authoritative fact/snapshot through the approved
  reviewed boundary; Kafka retention is not the archive
- [ ] verify aggregate ID, continuous version and checksum before applying
- [ ] apply the missing fact and held facts strictly in order, then clear the
  terminal block with recovery audit
- [ ] represent expected warehouse-local dates and active groups explicitly
- [ ] expose `PARTIAL/BLOCKED/STALE` reasons and return `COMPLETE` only when
  expected coverage and checkpoints are both proven
- [ ] inventory and recover existing terminal checkpoints individually
- [ ] test N+2/N+1, duplicate, payload conflict, restart, two replicas, internal
  date gap, timezone change, group lifecycle and delayed fact
- [ ] run analytics clean/upgrade, JPA, contract and integration suites

### Task 11: Scope Dossier Visibility Failures To The Affected Cabin

**Audit ID:** RWMS-006
**Owner:** dossier-service

**Files:**

- Modify: `services/dossier-service/src/main/java/dev/buhanzaz/rwms/dossier/service/DossierQueryService.java`
- Modify: `services/dossier-service/src/main/java/dev/buhanzaz/rwms/dossier/repository/DossierUnlinkedFactRepository.java`
- Modify: `services/dossier-service/src/main/java/dev/buhanzaz/rwms/dossier/repository/DossierSanitizedDeadLetterRepository.java`
- Create: next dossier Flyway migration for cabin/generation unresolved lookup
- Modify: dossier integration, rebuild and API parity tests
- Modify: `services/dossier-service/README.md`, `services/dossier-service/README.ru.md`

Contract impact: keep public visibility meaning; change only incorrect global
contamination. Existing data: backfill a cabin link only from proven owner
evidence, never from heuristic matching.

- [x] calculate visibility from hidden/failed evidence linked to the requested
  cabin and active generation only
- [x] keep globally unlinked facts in operational queues/metrics without
  degrading unrelated reads
- [x] add the exact cabin/generation/state indexes used by the query
- [x] preserve generation rebuild isolation and DLT audit evidence
- [x] test A failure vs B read, hidden warehouse evidence, unresolved unlinked
  fact, DLT, generation swap and API reasons
- [ ] run dossier Flyway/JPA, QA and contract suites

Current result (2026-08-09): compile/Javadoc and every RWMS-006 focused case
pass, including generation activation/rejection, processed-unlinked replay,
order isolation, all eight relay tests and PostgreSQL relay/coverage
serialization. The forced full module run is 106/113: the seven remaining
failures are a fixed external port, frozen maintenance contract fixtures and
an external asset-V5 numbering expectation, so the broad-suite checkbox stays
open until those owners are corrected.

### Task 12: Bound Media Processing And Offset Commit Recovery

**Audit ID:** RWMS-007
**Owner:** media-service

**Files:**

- Modify: `services/media-service/internal/worker/consumer.go`
- Modify: `services/media-service/internal/persistence/worker.go`
- Create: next migration under `services/media-service/db/migration/`
- Modify: `services/media-service/internal/worker/consumer_test.go`
- Modify/create: persistence and Kafka integration tests
- Modify: `services/media-service/README.md`, `services/media-service/README.ru.md`

Event meaning remains unchanged. Persist only safe attempt/terminal metadata;
never store raw Kafka payload merely for debugging.

- [x] classify transient dependency outage, poison input, terminal processor
  failure and broker commit failure
- [x] persist bounded processing attempts and terminal/reviewed-retry state
- [x] keep the media job idempotent so a record can be reread after DB success
- [x] bound commit retry by context/deadline and permit shutdown/rebalance
- [x] add circuit-breaker and fixed-cardinality typed
  job-age/attempt/offset telemetry
- [x] export that telemetry through a separate loopback-only scrape listener
- [ ] configure measured alerts and runtime rollout under Task 18
- [x] test transient recovery, poison record followed by valid work, broker
  outage after DB effect, duplicate delivery, shutdown and restart
- [x] run `go test ./...`, `go vet ./...` and the media build

Current result (2026-08-09): V11 adds a fenced four-attempt cycle, append-only
terminal/review evidence and deterministic legacy backfill. Persistence retry
is bounded to four and offset commit to three deadline-bound attempts. A
crashed fourth attempt records `PROCESSING_ATTEMPT_EXHAUSTED` without a fifth
processor call. Real PostgreSQL clean/upgrade/recovery gates and all Go gates
are green. Review has no authenticated production caller and does not execute a
new cycle. The bounded OpenMetrics exporter is green and is not part of the
public API; measured alert thresholds and runtime rollout remain open under
Task 18.

### Task 13: Add Bounded Claiming To Logistics Recovery

**Audit ID:** RWMS-012
**Owner:** logistics-service

**Files:**

- Modify: `services/logistics-service/src/main/java/dev/buhanzaz/rwms/logistics/domain/LogisticsExternalAttempt.java`
- Modify: `services/logistics-service/src/main/java/dev/buhanzaz/rwms/logistics/repository/LogisticsExternalAttemptRepository.java`
- Create: `services/logistics-service/src/main/java/dev/buhanzaz/rwms/logistics/service/LogisticsExternalAttemptClaimService.java`
- Modify: logistics relay/scheduler owners under `services/logistics-service/src/main/java/dev/buhanzaz/rwms/logistics/service/`
- Create: next logistics Flyway migration for claim/next-attempt indexes
- Modify: logistics configuration, metrics and paired READMEs
- Create/modify: claim, replica, backlog and shutdown integration tests

- [x] claim bounded pages in stable order with a lease token and
  `FOR UPDATE SKIP LOCKED` or equivalent fencing
- [x] close the claim transaction before any remote HTTP call
- [x] verify lease, attempt version and payload fingerprint when completing
- [x] define one bounded scheduler executor and per-owner concurrency budgets
- [x] add next-attempt/lease-expiry indexes and starvation-safe ordering
- [x] test 10k attempts, two replicas, lease expiry, slow dependency, starvation,
  duplicate completion and shutdown
- [x] run logistics clean/upgrade, JPA and focused workflow suites

Current result (2026-08-09): `V40` adds the lease token, monotonic fence,
expiry and stable due/expired indexes. Five workflow owners claim one bounded
page through short database-only transactions, execute HTTP only after the
claim transaction closes, and complete through the exact token/fence/version/
request-hash capability. A dedicated trigger plus bounded worker pool preserves
per-owner capacity and the normal Boot scheduler. PostgreSQL claim/scheduler,
five saga and Flyway/JPA gates are green; the final logistics module run is
325/325 across 58 suites.

### Task 14: Enforce Architecture And Implementation-To-Contract Parity

**Audit IDs:** RWMS-016, RWMS-023
**Owners:** platform architecture tests and each contract-owning service

**Files:**

- Modify: `platform/architecture-tests/build.gradle.kts`
- Modify: `platform/architecture-tests/src/test/java/dev/buhanzaz/rwms/architecture/PlatformArchitectureTest.java`
- Modify: inventory/logistics source policies after proving each low-level adapter role
- Create: root contract-validation task in build logic
- Create/modify: complete route/security parity tests in inventory, logistics
  and api-gateway modules
- Create: a separate structural Go gate for media-service
- Modify: contracts/platform paired READMEs and documentation standard if the
  developer gate changes

Persistence impact: none. This task validates boundaries and must not add a
blanket package allow-list to make current failures green.

- [x] include assistant, analytics and gateway in the central Java architecture
  dependency graph
- [x] enforce stateless gateway, read-model command prohibition, JPA ownership,
  MapStruct boundary and framework-neutral technical contracts
- [x] move the three inventory SQL responsibilities into two exact technical
  adapters and prove the resulting source boundary
- [x] move the ten logistics SQL/native-query responsibilities into exact
  technical adapters without weakening transaction, clock or fencing semantics
- [x] validate OpenAPI/event schema references, duplicate operation/event IDs
  and producer/consumer compatibility from one root task
- [x] generate route/method/security inventories for inventory, logistics and
  gateway and compare them to canonical OpenAPI
- [x] validate Android-consumed DTO fixtures and private namespace isolation
- [x] run full architecture, contract, approved-dependency and Go structural gates

Current central-rule result (2026-08-09): focused graph/boundary/mapper tests
are 36/36. Inventory now has one exact inbox persistence owner and one exact
PostgreSQL JSONB canonicalizer; its service-focused gate is 52/52 and the
central `InventorySourcePolicyTest` is 7/7. The deterministic root
`verifyCanonicalContracts` task validates 43 contract sources, 23 declared
JSON Schemas, 384 OpenAPI operations and 85 event message IDs; its negative and
checked-in fixtures are 7/7. The ten logistics SQL/native-query findings are
resolved through four exact persistence paths and one narrow advisory-lock
capability; the source policy is 9/9, focused persistence/constructor coverage
is 58/58 and the full logistics module is 330/330. Database time, the atomic
blocker snapshot, skip-locked claims and conditional fences remain intact; no
broad package allow-list was added. The final central architecture module is
82/82 and `verifyApprovedDependencyVersions` is green. The route/security
inventories are exact for inventory (27 bearer operations), logistics (73
bearer plus four intentionally anonymous presentation operations) and the
gateway's 265 domain-public operations; their focused gate is 9/9. Manager and
worker Retrofit boundaries inventory all 62 and 11 methods, respectively,
including only two owner-checked media `@Url` methods in each client. Their
focused gates are 11/11 and 12/12, and the worker request serializer now
preserves contract-required nullable keys without changing global JSON
behavior. The worker debug APK assembles, but mixed protected work was not
published or installed. The media structural gate, full Go test/vet/build,
full central architecture suite and approved-dependency gate are green, so the
Task 14 combined checklist is complete.

### Task 15: Publish One Immutable, Verifiable Manager APK

**Audit ID:** RWMS-017
**Owners:** manager Android app and manager-download-site
**Blocker:** approved page, signing, key-custody and artifact-retention decisions

**Files:**

- Modify: `app/build.gradle.kts`
- Modify: `manager-download-site/scripts/prepare-sites-worker.mjs`
- Keep one and delete the other after decision:
  `manager-download-site/app/page.jsx`,
  `manager-download-site/public/index.html`
- Create: `manager-download-site/public/releases/manager.json`
- Create/modify: reviewed release workflow under `.github/workflows/`
- Modify: app/download-site paired READMEs and release documentation

The four existing download-site files are protected dirty work and require an
exact pre-edit snapshot. No artifact may be built from another checkout or
published from the mixed worktree.

- [ ] make Gradle version metadata the source for an immutable release filename
- [ ] build a signed release APK using approved secret/key custody
- [ ] generate one manifest with source revision, version, build time, size,
  SHA-256 and signing-certificate fingerprint
- [ ] render only the manifest from the single supported page implementation
- [ ] verify the served artifact hash/signature against the manifest
- [ ] install the exact downloaded APK, authenticate through the intended public
  gateway and verify first workspace request plus a smoke flow
- [ ] retain immutable artifacts according to the approved policy and never
  describe an unverified upload as deployed

### Task 16: Align Worker Reconnect Semantics And Bound Invalidation Storage

**Audit IDs:** RWMS-019, RWMS-020
**Owners:** task-board-service and worker Android
**Blocker:** approved invalidation-only or durable-replay decision

**Files:**

- Modify together: `contracts/openapi/task-board-service.yaml`
- Modify: `services/task-board-service/src/main/java/dev/buhanzaz/rwms/taskboard/api/WorkerTaskBoardController.java`
- Modify: `services/task-board-service/src/main/java/dev/buhanzaz/rwms/taskboard/service/WorkerInvalidationHub.java`
- Modify: `worker-app/core-sync/src/main/java/dev/buhanzaz/rwms/worker/core/sync/WorkerRealtimeCoordinator.kt`
- Modify: `worker-app/core-database/src/main/java/dev/buhanzaz/rwms/worker/core/database/WorkerDatabaseEntities.kt`
- Modify: `worker-app/core-database/src/main/java/dev/buhanzaz/rwms/worker/core/database/WorkerDatabaseDaos.kt`
- Modify: worker Room database/migrations and exported schemas
- Modify: task-board/worker paired READMEs and focused tests

- [ ] for the recommended invalidation-only option, remove the replay promise
  and dead cursor path, then require an authoritative refresh on every reconnect
- [ ] if durable replay is selected instead, add an ordered server store, cursor
  expiry and explicit resync response before changing the client
- [ ] replace append-only local rows with current cursor plus a short deduplicated
  recent-ID window
- [ ] add `(userId, revision DESC)` or the selected equivalent lookup index
- [ ] prune only after successful authoritative sync and preserve the current
  cursor through Room migration
- [ ] test disconnect/reconnect, cursor expiry/resync, SSE+FCM duplicate,
  process restart, long-running retention and periodic refresh
- [ ] run task-board contract/service tests and worker database/sync builds

### Task 17: Standardize Client Failure And Retry Policies

**Audit ID:** RWMS-021
**Owners:** panel, worker Android and manager Android client boundaries

**Files:**

- Modify: `panel/src/features/assistant/api/assistant-api.ts`
- Modify: the shared panel Problem Details mapper and fixtures
- Modify: `worker-app/core-sync/src/main/java/dev/buhanzaz/rwms/worker/core/sync/WorkerSyncCoordinator.kt`
- Modify: worker network error taxonomy/tests
- Verify before removing: `app/build.gradle.kts`, `app/src/main/AndroidManifest.xml`
- Modify: affected paired client READMEs

- [x] use one typed taxonomy: 401 refresh/login, 403 grant refresh or user
  action, 409 authoritative refetch, and bounded retry only for
  429/502/503/504 plus proven transport failures
- [x] route assistant streaming and ordinary HTTP through equivalent Problem
  Details mapping
- [x] stop worker full-sync retry on terminal authorization failures
- [x] use jitter, retry budget and cancellation for transient failures
- [x] inspect main and test merged manifests before removing the unused AppAuth
  placeholder/receiver configuration
- [x] test every status, malformed Problem Details, cancellation, retry budget
  and grant refresh in panel and worker modules
- [x] run affected Vitest/typecheck and Android unit/compile checks

Current result (2026-08-09): panel Problem Details and assistant-stream tests
are 13/13 with typecheck green. Worker failure, cancellation, evidence and retry
policy tests are 14/14 and the affected network/sync/app Kotlin compiles pass on
JDK 17. Manager auth tests are 7/7; fresh debug, release and Robolectric merged
manifests contain neither the custom-scheme receiver nor its placeholder, and
the reviewed debug APK assembles. No client transport contract changed.

### Task 18: Add Low-Cardinality Recovery And Backlog Observability

**Audit ID:** RWMS-022
**Owners:** each durable workflow owner; platform provides technical primitives only

**Files:**

- Create/modify: service-local operational metrics in warehouse, maintenance,
  logistics, assistant, analytics and dossier services
- Modify: media worker metrics
- Modify: service-local runtime configuration and paired READMEs
- Modify: `docs/project-knowledge/runtime-flows.md` and operational references
- Create/modify: metrics integration tests and sanitized failure-drill scripts

- [ ] expose count and oldest age by bounded state for outbox, durable attempt,
  gap, quarantine and terminal work
- [ ] expose claim latency, attempt count, scheduler active/queue, remote
  latency/status, publish lag and projection completeness
- [ ] prohibit aggregate, user, media, payload and free-form exception labels
- [ ] define alert thresholds from the Task 0 baseline before production rollout
- [ ] run broker/dependency outage, poison record, lease expiry and backlog-drain
  drills in an isolated environment
- [ ] test metric registration, label cardinality and state transitions
- [ ] verify affected service status/logs after an authorized runtime update

Current media slice (2026-08-09): the worker's bounded typed snapshot now fans
out to structured logging and a separate loopback-only OpenMetrics listener.
Focused config/exporter/runtime tests, the race detector, full Go tests, vet and
the media build are green. The exporter caps partition series at 64 and has no
dynamic topic, identity, payload or exception labels. Task 18 remains open for
the other durable owners, baseline-derived alert thresholds, outage/backlog
drills and authorized runtime verification.

Current analytics slice (2026-08-09): seven lazy, fixed-name gauges expose
active/terminal projection gaps, oldest gap age, maximum gap attempt, pending
or retry sanitized-DLT backlog, its oldest age and terminal DLT count. They use
read-only JPQL, add no dynamic labels, return zero for empty/future-age state
and `NaN` on a database read failure. Unit, real PostgreSQL/Flyway/JPA and
persistence-architecture tests are green; the final analytics module run is
41/41.

Current dossier slice (2026-08-09): ten lazy, fixed-name gauges expose blocked
checkpoints, retained unresolved unlinked and exact DLT coverage, plus backlog,
oldest age and terminal rows for both activity outbox and sanitized DLT. The
retained-generation operational counts do not participate in cabin visibility,
and every query is read-only JPA/JPQL with the same zero/future/`NaN` behavior.
Unit, real PostgreSQL/Flyway/JPA and persistence-architecture gates are 7/7.

Current assistant slice (2026-08-09): two lazy, fixed-name gauges expose the
count and oldest age of durable tool calls still in `STARTED`. `COMPLETED` and
`FAILED` are terminal history rather than retry backlog, and scraping cannot
mutate or retry a call. Compile/Javadoc plus unit, real PostgreSQL/Flyway/JPA,
persistence and migration gates are 16/16. The remaining service owners,
alert thresholds, drills and runtime rollout are still active work.

### Task 19: Remove Proven-Obsolete Runtime Paths And Repository Debris

**Audit IDs:** RWMS-018, RWMS-024
**Owners:** each path's current service owner
**Blocker:** caller/data proof and retention authorization for destructive cleanup

**Files:**

- Verify/remove: task-board Kafka cutover rehearsal runner and its owned persistence
- Verify/remove: maintenance one-time task-board import surface
- Verify/remove: RabbitMQ compatibility profile in `compose.yaml`
- Verify/remove: the manager `RwmsApi.createShipment` declaration after proving
  its current zero production callers and confirming no supported flow needs it
- Modify: `platform/spring-boot-starter/src/test/java/dev/buhanzaz/rwms/platform/isolation/AmqpRetirementArchitectureTest.java`
- Modify: current descriptions/server variables under `contracts/events/`
- Verify/remove: `imege_1.png`, `panel/test-results/.last-run.json`
- Modify: affected paired READMEs and project knowledge; preserve historical reviews

- [ ] search code, contracts, configuration, tests, deploy manifests and current
  data for every proposed removal
- [ ] delete implementation, configuration and tests together only after zero
  active caller/release dependency is proven
- [ ] keep old tables during the expand/contract interval; drop/archive only
  after approved retention, backup, restore and rollback evidence
- [ ] replace current stage/cutover wording with present product semantics without
  changing event meaning
- [ ] replace hard-coded broker promise with a variable/example in contract metadata
- [ ] remove generated/test debris only after confirming it is not an intentional asset
- [ ] run affected clean install/upgrade, contract, compose and full module gates

### Task 20: Verify Closure And Update Canonical Documentation

**Files:**

- Modify: affected service/client `README.md` and `README.ru.md` pairs
- Modify: relevant files under `docs/project-knowledge/`
- Append: `docs/project-knowledge/change-log.md`
- Modify: this plan as tasks complete; move to `docs/plans/completed/` only when
  every selected task is actually closed
- Create: final evidence review under `docs/reviews/`

- [ ] verify every RWMS-001…024 has code, tests, documentation and operational
  evidence or remains explicitly blocked by a named decision
- [ ] rerun declaration-aware JavaDoc/KDoc/GoDoc coverage and paired README/link checks
- [ ] run all affected Spring module tests, full architecture/contract gates,
  panel tests/typecheck, both Android builds and Go tests/vet/build
- [ ] run Flyway clean and supported upgrade paths with JPA validation for every
  service whose persistence changed
- [ ] review authentication, warehouse isolation, transactions, locks,
  idempotency, retry, cache invalidation and observability across the final diff
- [ ] update only affected running test services when the environment and exact
  reviewed release scope are available; inspect health and logs
- [ ] record inconclusive infrastructure runs honestly and do not mark a task
  complete from compilation, HTTP 200 or deployment command alone
- [ ] confirm no commit, push, publish, destructive data action or production
  rollout occurred without the corresponding explicit user authorization

## Testing Strategy

- Unit tests cover policy, state transitions, payload hashes, retry taxonomy and
  mapping boundaries.
- PostgreSQL/Testcontainers tests cover Flyway, JPA validation, locks, leases,
  replica races, idempotency, recovery and upgrade paths.
- Contract tests validate canonical OpenAPI/event schema plus every changed
  producer and active consumer.
- Android tests include two-account local-state isolation, Room migration,
  process restart and exact-APK emulator evidence when client behavior changes.
- Panel tests use two principals and late responses in one tab, followed by
  `npm run typecheck`.
- Media tests cover poison/transient/broker failure, shutdown and duplicate
  delivery, followed by Go test/vet/build.
- Full suites are used after focused gates or when a boundary is shared. A
  timeout or unavailable dependency is reported as inconclusive, never green.

## Post-Completion

The following require explicit external authorization and are not performed by
this plan:

- production or VPS rollout from a reviewed source scope;
- draining production outbox or reconciling terminal records;
- signing-key access and publication of an APK;
- irreversible archive/deletion of operational evidence;
- security/privacy approval for delegated identity, LLM provider and retention;
- load, failover and incident-response exercises against shared environments.
