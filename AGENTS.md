# AGENTS.md

# RWMS Product Development Rules

## 1. Mission

RWMS is an actively developed product.

The current direct user command defines the task scope.

Implement the requested behavior as a complete working change across every component that is genuinely affected by that behavior.

The goal is:

> Make the requested change correct with the smallest safe diff.

Do not turn a feature task into a general cleanup, migration, architecture rewrite or repository-wide repair.

Do not start unrelated work.

Do not push, publish or deploy unless the user explicitly requests it or the current command clearly includes that action.

Every completed implementation task must leave no WIP: before the final handoff, finish,
verify and commit the complete shared change set, leaving `git status --short` empty. Do not
leave task changes, generated source, partial fixes or deferred integration as an uncommitted
handoff. An exception requires an explicit user instruction to keep the worktree dirty and must
name the paths that remain unfinished.

---

# 2. Current Project

Primary active applications:

* `panel/` — primary web panel.
* `app/` — Manager Android application.
* `worker-app/` — Worker Android application.
* `services/` — backend services.
* `contracts/` — canonical transport contracts.
* `docs/project-knowledge/` — maintained map of current architecture and business logic.

Other active project directories may exist. Verify their current role from repository sources before changing them.

Historical evidence only:

* `wms-panel-old/`
* browser mocks
* `old_db/`
* `docs/plans/`
* `WMS_ARCHITECTURE_KNOWLEDGE/`

Historical code, plans, schemas, fixtures and browser state do not define current product behavior unless verified against current authoritative sources.

There is no general requirement to preserve compatibility with abandoned mocks, obsolete schemas, browser state, fixtures or old flows unless the user explicitly requests it.

This does not authorize destructive operations against real data.

Never delete or rewrite a live:

* database;
* volume;
* backup;
* object bucket;
* user data;

without explicit user authorization and a known target.

---

# 3. Authority And Sources Of Truth

Use authority in this order:

1. current direct user command;
2. this `AGENTS.md`;
3. canonical contracts under:

   * `contracts/openapi/`
   * `contracts/events/`
4. current domain code, Flyway schema and tests of the owning service;
5. current `docs/project-knowledge/`;
6. historical code, plans and migration notes as evidence only.

Canonical contracts define boundaries between components.

The owning service defines its business transitions and invariants.

`docs/project-knowledge/` is a verified map and explanation of the product. It does not override current contracts or owning domain code.

If the user request conflicts with authoritative sources on:

* ownership;
* contract meaning;
* identity;
* status semantics;
* money;
* authorization;
* time;
* destructive behavior;

collect the exact evidence and ask only for the smallest product decision required.

Do not ask the user to decide something that current authoritative sources already answer.

---

# 4. Core Scope Discipline

The task is not an invitation to fix every problem discovered in the repository.

A discovered problem may be fixed during the current task only when at least one is true:

1. the current task introduced it;
2. it directly prevents the requested behavior from working;
3. the requested change necessarily requires changing the affected contract or invariant;
4. leaving it unchanged would make the requested implementation knowingly incorrect, unsafe or internally inconsistent.

Otherwise:

* do not fix it;
* do not refactor around it;
* record it in the final remarks when useful.

## Forbidden Scope Expansion

Do not perform opportunistic:

* refactoring;
* renaming;
* directory reorganization;
* abstraction extraction;
* dependency replacement;
* style cleanup;
* architecture cleanup;
* test cleanup;
* legacy cleanup unrelated to the requested behavior.

Do not change code merely because it can be improved.

Do not generalize a local solution for hypothetical future requirements unless the current behavior requires that abstraction.

Prefer:

> smallest complete vertical change

over:

> broad theoretically cleaner change.

---

# 5. Regression Budget

The regression budget for every task is zero known regressions introduced by the task.

The task is complete when:

1. requested behavior works;
2. focused verification covering the changed behavior passes;
3. changed contracts and consumers are consistent;
4. the final diff introduces no known regression;
5. unrelated pre-existing problems remain outside scope and are reported rather than absorbed into the task.

Do not hide uncertainty behind a successful build.

A successful build proves only that the build passed.

---

# 6. Worktree Safety Gate

Before the first write:

1. read the current branch;
2. run `git status --short`;
3. inspect relevant existing diffs;
4. identify dirty and untracked files related to the task;
5. determine whether any intended target already contains protected work.

Every modified, deleted or untracked file that existed before the task is protected work.

It may belong to:

* the user;
* another Codex task;
* another agent;
* unfinished local work.

Do not discard it.

## Never use these to make the workspace convenient

Do not use:

* `git reset`
* `git clean`
* `git stash`
* `git restore` over protected changes
* `git checkout` over protected changes
* forced branch switching
* replacing a dirty file from `HEAD`
* broad formatter passes over dirty files

unless the user explicitly authorizes that exact destructive action.

If the current requested edit overlaps existing work:

* preserve both when safely possible;
* inspect exact hunks before editing;
* do not regenerate the whole file just to avoid merging.

If preserving both requires choosing which existing behavior to discard, stop and request the smallest necessary decision.

## One Shared Completion State

RWMS development has exactly one mutable working state: the current primary repository worktree
on its current branch. It may be dirty only while an active task is being completed. There is no
acceptable WIP after a task handoff.

Agents must not create or use a separate:

* Git worktree;
* clone or copied source tree;
* branch for task isolation;
* stash;
* patch queue;
* temporary release source checkout.

All implementation, verification and publication must use the same on-disk shared worktree. A
task may narrow the files it edits or the runtime it restarts, but it must not create an isolated
source snapshot that can omit shared work.

Before declaring a task complete:

1. inspect the full current diff and `git status --short`;
2. reconcile every changed or untracked path into the completed task, preserving valid existing
   work rather than silently dropping it;
3. run the required verification against that complete state;
4. create one or more coherent commits that include every remaining change; and
5. rerun `git status --short` and require empty output.

Do not split one requested behavior into an implemented part and a separate uncommitted WIP.
Do not start a new implementation task from unknown dirty state. If the existing changes cannot
be safely reconciled without discarding or choosing between competing work, stop and request the
smallest necessary direction from the user.

Existing immutable deployment directories under `/var/lib/rwms/releases/` are release records,
not development WIPs. Do not use them as source for a new build.

---

# 7. Task Workflow

Every implementation task uses the following workflow.

The amount of investigation should be proportional to the task.

Do not turn a small UI correction into a repository-wide investigation.

---

## 7.1 Understand The Requested Result

Before editing determine:

* intended behavior;
* acceptance criteria;
* explicit requirements;
* important assumptions;
* owning component;
* affected boundaries;
* meaningful failure cases;
* authorization/security implications;
* data implications.

State a concise working plan before making a non-trivial change.

A small task may need only two or three plan items.

---

## 7.2 Inspect The Affected Flow

Trace enough of the flow to know the change is complete.

Depending on the task inspect affected:

* route/navigation;
* page/component;
* state/query;
* cache/invalidation;
* client API;
* gateway route;
* DTO mapping;
* OpenAPI operation;
* event schema;
* producer/consumer;
* controller;
* application service;
* domain transition;
* authorization;
* repository;
* Flyway schema;
* concurrency;
* idempotency;
* tests.

Do not blindly inspect every layer if the task clearly does not touch it.

Use:

* `rg`
* `rg --files`

as the default repository search tools.

Search callers and consumers before assuming a changed type, operation or event is isolated.

---

## 7.3 Plan The Smallest Complete Change

For non-trivial work identify:

* owning component;
* files or areas expected to change;
* contract impact;
* persistence impact;
* active consumers;
* compatibility implications;
* tests required;
* documentation impact;
* runtime impact.

Do not create speculative work items outside the requested scope.

---

## 7.4 Implement Incrementally

Do not accumulate a large unverified diff.

Implement one meaningful boundary at a time.

Typical sequence:

1. smallest model/contract change;
2. verify;
3. owning business logic;
4. verify;
5. consumer/client;
6. verify;
7. UI behavior;
8. verify.

The exact sequence depends on the task.

Do not mechanically follow this list when fewer layers are affected.

---

# 8. Incremental Verification

Testing is part of implementation, not a single ceremony performed after dozens of edits.

After each meaningful boundary use the cheapest useful verification.

Examples:

### TypeScript / React

After an important type/state/API change run an affected:

* Vitest test;
* TypeScript check;
* focused build when appropriate.

### Spring

After domain/service changes run an affected:

* package test;
* module test;
* compile check.

### Contract

After changing a canonical contract validate:

* schema;
* generated/boundary code when used;
* relevant producer;
* relevant consumer.

### Persistence

After JPA/Flyway changes validate:

* migration;
* JPA mapping;
* relevant repository/domain test.

### Go

After a Go change run affected:

* package tests;
* build.

### Android

After a meaningful Android boundary run affected:

* unit/contract tests;
* compile/build as appropriate.

---

# 9. Handling Test Failures

When verification fails, determine first whether the failure:

1. was introduced by the current diff;
2. is a pre-existing failure;
3. is unrelated infrastructure failure;
4. reveals that the current implementation is incomplete.

Fix failures introduced by the current change.

Fix a pre-existing failure only when it prevents correct implementation or verification of the requested behavior.

Do not begin repairing unrelated failures merely because a broad build exposed them.

Report them separately.

Never claim a test that was not executed.

Never claim E2E verification when only compilation was performed.

If infrastructure blocks the strongest intended verification:

* name the exact blocker;
* run the strongest honest replacement;
* do not fabricate success.

---

# 10. Implementation Rules

Deliver one production implementation of the requested behavior.

Do not create competing runtime paths.

Do not create a second implementation simply to avoid understanding the existing one.

When the current task genuinely replaces an obsolete implementation, remove that obsolete path if:

* the replacement is complete;
* no supported flow still references it;
* removing it does not expand the task into unrelated cleanup.

Do not retain an obsolete implementation as a silent fallback.

Do not fabricate successful data.

Do not silently fall back to:

* mocks;
* browser state;
* fixtures;
* hardcoded success;
* stale local data;

when a real service, token or contract is unavailable.

Return or surface the real error.

---

# 11. Business Logic Ownership

Commands and business orchestration belong to the owning service.

Do not put business sagas or authoritative state transitions into:

* React;
* Android UI;
* API gateway;
* read projections.

A compatibility facade may delegate to cohesive collaborators but must not become another business owner.

Avoid:

* god classes;
* universal `*Support` classes;
* giant dependency bags;
* mega coordinators;
* inheritance used only to expose unrelated repositories.

Each collaborator should own one cohesive workflow and receive only the dependencies it actually needs.

---

# 12. Current Domain Ownership

| Flow                                                                        | Owner                                  |
| --------------------------------------------------------------------------- | -------------------------------------- |
| Login, users, roles, OAuth/OIDC clients and warehouse access                | `auth-service`                         |
| Warehouse identity, metadata and timezone                                   | `warehouse-service`                    |
| Cabins, status, equipment, balances, holds and leases                       | `asset-service`                        |
| Queues, workforce, assignments and operational board                        | `task-board-service`                   |
| Catalog, estimates, repairs, acceptance and write-off decisions             | `maintenance-service`                  |
| Inventory sessions, findings, completion and publication                    | `inventory-service`                    |
| Rental counterparties, inquiries, returns, shipments, transfers and drivers | `logistics-service`                    |
| Media metadata, upload/finalize, originals and transformations              | `media-service`                        |
| Cross-domain cabin activity                                                 | read-only `dossier-service` projection |
| KPI and dashboard facts                                                     | `analytics-service` projections        |
| Assistant conversations and tool-call history                               | `assistant-service`                    |
| Rental availability                                                         | `logistics-service`                    |

`api-gateway-service` is stateless and owns no business aggregate.

It must not own:

* database state;
* business workflows;
* Kafka domain state;
* token storage;
* business aggregation.

`dossier-service` and analytics projections are read models.

They do not own commands for producer aggregates.

---

# 13. Stack And Deployable Boundaries

## Panel

`panel/`

Current stack:

* React
* TypeScript
* Vite
* React Router
* TanStack Query
* TanStack Table
* Tailwind CSS
* shadcn-based components

## Spring services

`services/`

Current stack:

* Java
* Spring Boot
* Gradle Kotlin multi-module
* Spring Data JPA
* PostgreSQL

## Android

Manager:

`app/`

Worker:

`worker-app/`

## Infrastructure

Authentication:

* OAuth2/OIDC Authorization Code with PKCE for interactive clients.
* Bearer JWT validation for APIs.

Messaging:

* Kafka.

Object storage:

* private MinIO.

Edge:

* stateless `api-gateway-service`.

Media:

* one stateful Go `media-service`.

Do not create a second photo/media processing service.

---

# 14. Database Ownership

Every stateful service owns its PostgreSQL database.

Forbidden:

* cross-database foreign keys;
* cross-database joins as domain integration;
* shared mutable tables;
* shared repositories;
* shared JPA entities;
* shared mutable domain models.

Cross-service integration uses explicit contracts.

---

# 15. Client And Gateway Rules

Interactive clients use the public gateway.

Browser requests use same-origin routes:

* `/auth/**`
* `/api/**`

Do not put internal service addresses or `localhost:<service-port>` into browser runtime configuration.

Clients do not call:

`/api/internal/**`

Missing:

* token;
* service;
* gateway;
* configuration;
* contract capability;

is an explicit error.

Do not replace it with mock success.

Use consistent Bearer authentication and Problem Details mapping.

---

# 16. Concurrency And Idempotency

Mutable commands use the concurrency mechanism defined by the owning contract, such as:

* `expectedVersion`;
* ETag;
* another fencing token.

Version conflicts must produce consistent conflict behavior, normally `409` when defined by the contract.

Retried creates or external effects use:

* `Idempotency-Key`; or
* a stable domain external identifier.

Do not create duplicate effects merely because a request may retry.

---

# 17. Client State

Server data is authoritative.

Client-side persistence is allowed only for explicitly non-authoritative concerns such as:

* UI preferences;
* encrypted session material where required;
* designed offline caches.

Do not make browser local storage the source of truth for server domain state.

Real-time messages are normally invalidation signals unless their contract explicitly defines them as a complete projection.

Invalidate or patch only affected queries/cache entries.

Avoid global cache clears unless genuinely required.

---

# 18. Contracts And Events

A service owns its aggregate transitions.

A browser does not coordinate business sagas.

Internal services communicate using private service endpoints and appropriate service credentials, not by routing internal orchestration through the public browser gateway.

Kafka is transport, not the authoritative database.

Where present preserve:

* transactional outbox;
* inbox/deduplication;
* aggregate ordering;
* version-gap handling;
* idempotent consumers.

Infinite retry is forbidden.

Distributed 2PC is forbidden.

The service that initiates a saga owns:

* saga state;
* recovery;
* retry policy;
* compensation.

UI rollback is not a consistency mechanism.

Each status and invariant has one authoritative owner.

Consumers store only what their contract permits:

* IDs;
* contract-defined immutable snapshots;
* projection data.

Generated transport DTOs are boundary types.

They are not shared JPA/domain entities.

---

# 19. Contract Changes

Change a canonical contract only when the requested behavior requires it.

When a canonical contract changes:

1. update the contract;
2. update the owning producer;
3. update affected active consumers;
4. validate compatibility;
5. remove obsolete contract behavior only when no supported flow requires it.

Do not casually add compatibility fields or fallbacks just to avoid updating a consumer.

Do not make unrelated contract cleanup part of the same task.

---

# 20. Spring Data JPA And Flyway

Use Spring Data JPA for stateful Spring persistence.

Flyway is the only schema mutation authority.

Hibernate must not create or update production schemas.

Target profiles use:

`hibernate.ddl-auto=validate`

Existing Flyway migrations are immutable.

`baselineOnMigrate` remains false unless an explicitly approved adoption process requires otherwise.

A destructive schema change requires:

* known target;
* impact analysis;
* explicit data handling;
* expand/contract when appropriate.

When changing schema or entity mappings validate the relevant migration and JPA model.

---

# 21. JPA Entity Rules

Do not use Lombok patterns that hide important entity semantics.

JPA entities must not use `@Data`.

Do not generate uncontrolled setters for:

* IDs;
* versions;
* timestamps;
* invariants.

Avoid generated entity-wide:

* `equals`;
* `hashCode`;
* `toString`;

when they can break persistence semantics.

Domain transitions belong in explicit methods/services rather than generic setters.

---

# 22. MapStruct

Use MapStruct at touched Spring boundaries where it provides useful explicit DTO mapping.

Use:

* Spring component model;
* constructor injection;
* `unmappedTargetPolicy=ERROR`.

Do not use MapStruct to implement:

* authorization;
* business transitions;
* request-to-entity mutation;
* entity version mutation;
* secret handling;
* checksums;
* outbox construction.

`platform:technical-contracts` remains framework-neutral.

It must not become a shared Spring/JPA/business-domain module.

---

# 23. Documentation

Documentation should preserve durable project knowledge without turning every small task into a documentation project.

Update documentation when the task changes a durable fact.

Examples:

* API contract;
* event contract;
* service responsibility;
* ownership boundary;
* persistence behavior;
* runtime configuration;
* recovery behavior;
* important business invariant;
* cross-component flow.

Do not touch documentation merely to produce a documentation diff.

---

## 23.1 Code Documentation

Add meaningful JavaDoc/KDoc/GoDoc for newly introduced real types where the repository standard requires it.

Document changed public or architecture-significant behavior when semantics are not obvious from the signature.

Especially document non-obvious:

* ownership;
* authorization;
* transaction behavior;
* fencing;
* idempotency;
* retry;
* time semantics;
* recovery.

Do not add filler comments.

---

## 23.2 Component README

When a durable component behavior changes, update the owning:

* `README.md`
* `README.ru.md`

Keep both versions factually synchronized.

Do not update README files for purely internal changes with no documentation-visible effect.

---

## 23.3 Project Knowledge

`docs/project-knowledge/` is the maintained architecture/business map.

Start from:

`docs/project-knowledge/README.md`

Important files include:

* `architecture.md`
* `service-catalog.md`
* `runtime-flows.md`
* `cabin-lifecycle.md`
* `cabin-lifecycle.ru.md`
* `domain-logic.md`
* `contracts.md`
* `documentation-standard.md`
* `change-log.md`
* `open-questions.md`

Read only sections relevant to the task.

Verify important statements against current repository sources before relying on them.

If the task changes architecture, ownership, a durable invariant, contract or important cross-component flow:

* update the relevant knowledge document;
* append a concise entry to `change-log.md`.

Put unresolved product decisions in:

`open-questions.md`

Do not invent answers.

---

# 24. Historical And Legacy Code

Historical code may explain why the current implementation exists.

It does not automatically define supported behavior.

Do not restore old behavior merely because it exists in:

* old panel code;
* mocks;
* old DB dumps;
* abandoned plans;
* migration documents.

Do not automatically import:

* localStorage;
* IndexedDB;
* old exports;
* `old_db/`;

into the current server database.

If the requested change genuinely replaces a legacy runtime path, remove the replaced implementation when safe and directly in scope.

Do not begin a repository-wide legacy purge.

---

# 25. Focused Verification Matrix

Use the narrowest checks that prove the changed behavior.

## Panel

Normally use affected:

* Vitest tests;
* `npm run typecheck`.

Use lint/build when relevant to the boundary or needed to prove the final artifact.

## Spring

Use affected:

* module/package tests;
* compile checks.

## Contracts

Use:

* schema validation;
* affected producer checks;
* affected consumer checks.

## Flyway / JPA

Use affected:

* migration install/upgrade verification;
* JPA validation.

## Go

Use affected:

* package tests;
* build.

## Authentication / authorization / concurrency

Test the relevant success and failure path.

## Real-time/cache

Test affected invalidation behavior rather than clearing everything.

Do not automatically launch large cross-service suites unless:

* risk requires them;
* focused verification exposes a broader problem;
* the user explicitly asks.

---

# 26. Panel UI Verification

For a change to active `panel/` UI, focused UI verification must exercise the changed user flow.

When a separate `gpt-5.6-luna` verification agent is available, use it as the independent verifier for active panel UI changes.

Its role is verification, not uncontrolled repository review.

The verifier should receive:

* exact changed flow;
* expected behavior;
* relevant test command;
* read-only production/test source scope unless specifically assigned otherwise.

The primary agent remains responsible for the verdict.

Inspect actual:

* command output;
* test counts;
* failures;
* screenshot/trace/browser artifacts when generated.

A child-agent summary alone is not proof.

If Luna is unavailable, perform the strongest focused verification available and report that fact.

Do not create separate agents merely to:

* brainstorm;
* plan;
* write status reports;
* inspect unrelated architecture;
* search for extra bugs.

---

# 27. Single Coding Lane

Use one primary coding agent and one shared worktree for implementation and verification.

Do not create coding, inspection or verification subagents. A publication-only helper is allowed
only when the user explicitly requests it; it must publish the artifact built from the primary
shared worktree and must not create another branch, worktree, clone or source snapshot.

One task therefore has one ordered diff and one owner. Do not represent parts of the same task as
separate WIPs that are merged or selected later.

---

# 28. Android Applications

Manager application:

* source: `app/`
* package: `dev.buhanzaz.rwms.manager`

Worker application:

* source: `worker-app/`
* package: `dev.buhanzaz.rwms.worker`

Download surfaces:

* Manager → `manager-download-site/`
* Worker → `worker-download-site/`

Never mix Manager and Worker APKs or metadata.

A normal Android implementation task should:

* run affected tests;
* build the exact APK when required for verification.

Do not publish an APK merely because code changed.

Publish/update the download site when the user explicitly asks to:

* publish;
* release;
* deploy;
* update the downloadable application.

When publishing verify:

* exact source state;
* package;
* version;
* signing identity;
* APK hash;
* correct application download site.

Do not claim an APK is released until the actual released file is verified.

---

# 29. Cabin CAD Diagnostics

For `cabin-cad`, runtime diagnostics must remain durable and searchable.

Use the current diagnostic facilities including:

* browser journal under `cabin-cad/diagnostics/v1`;
* explicit NDJSON export;
* local Vite sink:
  `cabin-cad/.runtime/diagnostics.ndjson`

For crash investigation:

1. inspect current and rotated NDJSON;
2. reproduce the requested flow;
3. correlate records by:

   * `sessionId`
   * `sequence`
   * `scope`
   * `event`

If the file sink was unavailable, inspect the browser journal or exported NDJSON.

An empty console does not prove no error occurred.

Never log:

* CAD document contents;
* STEP bytes;
* geometry payloads;
* embedded texture data;
* complete serialized storage;
* credentials;
* tokens;
* customer data.

Runtime diagnostic files must not be committed.

---

# 30. Current VPS Runtime

The development/runtime environment is already running on the RWMS VPS.

The agent is operating on that VPS.

Do not SSH into the same VPS to perform normal RWMS deployment or publication.

Specifically:

> When the user asks to publish, deploy or update the running RWMS system, perform the operation locally on the current VPS.

Do not attempt an additional SSH connection to:

* the VPS public IP;
* `localhost`;
* the same machine through another hostname;

just to deploy code that is already available in the current VPS worktree.

Use the current local shell and existing runtime tooling.

---

# 31. Public RWMS Entry Point

The public RWMS entry point is:

`https://77-90-158-90.sslip.io/`

Public traffic is served through Nginx.

Treat Nginx as the public edge.

Do not bypass the configured Nginx/public gateway when verifying the externally visible product unless debugging an internal service specifically requires local inspection.

When a published user-facing change is complete, verify it through:

`https://77-90-158-90.sslip.io/`

or the appropriate public route below that origin.

---

# 32. Publication Is Explicit Scope

Do not publish simply because implementation succeeded.

Publication becomes part of the task when the user explicitly says or clearly means:

* publish;
* deploy;
* update the VPS;
* roll out;
* make it available;
* put it online;
* release it.

When publication is requested, do not ask whether deployment is intended.

It is intended.

---

# 33. VPS Publication Workflow

When publication is requested:

## 33.1 Determine The Exact Changed Runtime

Identify which runtime component is affected.

Examples:

* panel;
* API gateway;
* one Spring service;
* Go media service;
* Android download site;
* Nginx configuration.

Do not restart or rebuild unrelated services.

---

## 33.2 Inspect Existing Deployment Topology

Use the current VPS configuration as authority.

Inspect existing:

* Nginx configuration;
* running services;
* systemd units;
* containers;
* existing deployment scripts;
* build output locations;

as relevant.

Do not invent a new deployment architecture when an existing one already works.

Do not replace systemd with Docker, Docker with systemd, or existing Nginx routing with a different approach merely for convenience.

---

## 33.3 Build And Publish The Complete State

A published artifact must contain the complete current source state of every runtime component
and dependency affected by the requested behavior. A release is never a selection of task hunks,
files copied from another checkout, or an artifact rebuilt from an earlier revision.

Before publishing confirm:

* current branch/revision;
* `git status --short` is empty after completion commits;
* the exact revision currently running on the VPS (the release base);
* the full repository diff from that release base, not only the current task's hunk list;
* every changed deployable, runtime component and dependency in that full diff;
* exact source revision represented by every built artifact.

Build directly in the primary shared worktree at that completed revision. Do not build from
another checkout, a temporary release worktree, an older
`/var/lib/rwms/releases/*/source` directory, or a clean `HEAD` that omits completed changes.

If a canonical contract, shared library or cross-component flow changed, publish every affected
runtime component together in dependency order. No changed deployable may be omitted from a VPS
release unless its changed files are demonstrably documentation or non-runtime-only. Do not
publish only the panel, only one service, or only a copied task patch when the full release diff
requires matching changes elsewhere.

Inspect the full release diff before publication. Do not selectively reconstruct or copy only
task hunks into an artifact: that produces a truncated release. If the worktree is dirty, the
release is blocked until the active work is completed, verified and committed, unless the user
explicitly authorizes a named dirty-state exception.

---

## 33.4 Publish Locally

Execute deployment commands directly on the VPS.

No SSH hop is required.

Use the existing deployment mechanism for that component.

Examples may include:

* replacing a built panel bundle;
* restarting the affected systemd unit;
* rebuilding/restarting the affected container;
* updating the owned Android download site.

Use the repository/runtime configuration to determine the actual mechanism instead of guessing.

---

## 33.5 Nginx

Nginx is the public edge.

Do not modify or restart Nginx for ordinary application deployment unless necessary.

If Nginx configuration changes:

1. inspect the existing configuration;
2. make the smallest required change;
3. run configuration validation such as `nginx -t`;
4. reload rather than unnecessarily stop/start when the existing setup supports it;
5. verify the public route afterwards.

Preserve existing:

* TLS;
* proxy routing;
* headers;
* upload limits;
* WebSocket/SSE behavior;
* static routes;

unless the requested change requires modifying them.

Never publish internal administration ports directly to the internet.

---

## 33.6 Verify The Running Artifact

A successful build or restart does not prove the requested change is live.

After publication verify the running system itself.

For a panel change:

* open/check the public UI through `https://77-90-158-90.sslip.io/`;
* exercise the changed flow when possible;
* verify the served artifact is the new one.

For API/backend changes:

* verify the affected public API or user flow through Nginx/public gateway when possible;
* inspect the affected service status/logs for startup/runtime errors.

For Android release publication:

* verify release metadata;
* verify APK URL;
* verify hash/package/version.

Do not report:

> deployed

until runtime verification has succeeded or a precise external blocker is known.

---

# 34. Runtime Failure During Publication

If the newly published component fails:

1. inspect the affected component only;
2. collect the actual error;
3. determine whether the current diff caused it;
4. fix the current change when responsible;
5. rebuild/redeploy only the affected component;
6. verify again.

Do not respond to one failed service by beginning unrelated cleanup across the VPS.

Do not hide a failed deployment behind an HTTP `200` from some other route.

---

# 35. VPS Build Resource Safety

The VPS is also a shared runtime host.

Do not leave task-owned build/test processes consuming resources after verification.

For memory-intensive work inspect the current process/memory state before starting.

For Gradle on the VPS prefer:

`--no-daemon --max-workers=2`

For Kotlin/Android builds also prefer:

`-Pkotlin.compiler.execution.strategy=in-process`

unless the repository proves that configuration unsuitable.

After the task stop task-owned:

* emulators;
* QEMU processes;
* browsers;
* local development servers;
* compiler daemons;
* test servers.

Do not kill processes that:

* predate the task;
* belong to another build;
* belong to production runtime;
* cannot be confidently identified as task-owned.

Do not clear shared Gradle caches, Linux page cache or swap merely to make memory statistics look better.

---

# 36. Runtime Security

Never expose publicly:

* PostgreSQL;
* Kafka;
* MinIO administration;
* internal service ports;
* management endpoints;

unless the architecture explicitly defines a secure public surface for them.

Never commit:

* passwords;
* access tokens;
* private keys;
* runtime credentials;
* generated keystores;
* secrets;
* diagnostic logs containing sensitive information.

Never send secrets to external tools or agents unnecessarily.

---

# 37. Git Rules

Preserve user and concurrent changes.

Commit the complete verified task state before handoff so that no WIP remains. A user may
explicitly authorize named paths to remain dirty; otherwise a clean worktree is mandatory.

Do not push unless the user asks.

Do not open a pull request unless the user asks.

Stage complete coherent change sets. Do not omit a related changed file or hunk merely to make a
smaller-looking commit or release; that creates a truncated state.

Do not commit:

* generated build output;
* IDE state;
* browser artifacts;
* temporary diagnostics;
* credentials;
* secrets;

unless a particular generated release artifact is intentionally versioned by the project.

For a requested commit:

* verify Git identity;
* use the user's configured identity;
* use a short human commit message.

Do not put agent, model or tool names in:

* branch names;
* commit messages;
* directory names;
* PR metadata.

---

# 38. Skills And External Tools

At task start inspect available skills only when relevant.

If the task clearly matches an available skill, read and follow it.

Use the smallest useful set.

A skill does not override:

1. the current user command;
2. canonical RWMS contracts;
3. this project's ownership rules.

Prefer repository files for repository facts.

Use external MCP/tools when they are the direct authority for external information such as:

* referenced design;
* issue;
* document;
* external repository object.

Do not call external tools merely to rediscover information already present locally.

Do not send:

* secrets;
* private keys;
* customer information;
* unnecessary source code;

to external systems.

---

# 39. Architecture Review

Final architecture review is diagnostic.

Its primary question is:

> Did the current task introduce an architecture or ownership regression?

Review the affected diff for:

* service ownership;
* data ownership;
* dependency direction;
* contract consistency;
* authorization;
* warehouse isolation;
* transaction boundaries;
* concurrency;
* idempotency;
* retry/recovery;
* duplicate authoritative state;
* cache invalidation.

Do not turn final review into a repository-wide refactoring pass.

A pre-existing architecture weakness remains outside scope unless:

* it prevents the requested behavior;
* the current diff depends on it in a knowingly incorrect way;
* the current diff makes it worse.

Otherwise report it and stop.

---

# 40. Final Diff Review

Before declaring completion inspect the final diff.

Specifically check:

* every changed file is necessary;
* no unrelated file was modified accidentally;
* no protected hunk disappeared;
* no debugging code remains;
* no mock fallback was introduced;
* no obsolete competing runtime path remains when replacement was directly in scope;
* no generated secret or runtime artifact was accidentally added;
* no known regression was introduced.

Do not perform speculative improvements during this review.

If review discovers a defect introduced by the current task, fix it and rerun the affected verification.

---

# 41. Definition Of Done

An implementation task is done when all applicable conditions are true:

1. requested behavior exists;
2. intended user flow works;
3. the owning component contains the business logic;
4. relevant contracts are consistent;
5. affected consumers are updated;
6. focused tests/checks pass;
7. current diff has no known regression;
8. protected pre-existing work remains preserved;
9. durable documentation is updated when necessary;
10. no task-owned build/test processes remain;
11. publication has been verified when publication was explicitly requested; and
12. `git status --short` is empty after the completed work has been committed, unless the user
    explicitly authorized named remaining WIP paths.

Do not require unrelated repository problems to be solved before completing the task.

---

# 42. Final Handoff

Every final implementation report must be concise and factual.

Include:

### Working

What requested behavior now works.

### Changed

Main files/components changed.

### Verification

Exact tests/checks actually executed and their results.

### Contracts / Architecture

Contract impact and whether ownership/architecture remains valid.

### Runtime

One of:

* not published because publication was not requested;
* published and verified;
* publication attempted but blocked, with the exact blocker.

When published, include verification through the public RWMS entry point when relevant:

`https://77-90-158-90.sslip.io/`

### Existing Work

State whether touched files had pre-existing changes and whether they were preserved.

### Tools

Skills/MCP/agents actually used, or `none`.

### Remarks

Only meaningful:

* remaining risks;
* unrelated pre-existing failures;
* blockers;
* unresolved product decisions.

Do not turn the final report into another audit.

Do not list dozens of unrelated improvement ideas unless the user asked for an audit.

---

# 43. Stop Rule

Once all requested acceptance criteria pass:

> Stop changing the repository.

Do not search for additional cleanup opportunities.

Do not start another refactor.

Do not continue changing architecture because the current solution suggests a cleaner future design.

Do not convert completion into a new audit.

If unrelated problems were noticed, mention them briefly in the handoff and leave them untouched.

The successful end state is:

> requested behavior works, focused verification passes, no known regression was introduced, and unrelated project state remains preserved.
