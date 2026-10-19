# God-Class Decomposition Review

Status: Structural refactoring completed on 2026-08-08. Runtime defects and
contract changes are explicitly outside this change.

## Decision rule

File length alone does not make a god class. A production type is a mandatory
decomposition target when it owns three or more independently changing
use-case families and at least two of these signals are present:

- more than roughly 1,500 source lines;
- more than roughly 80 declared methods;
- a wide constructor, inherited dependency surface, or access to several
  unrelated repositories/remote owners;
- UI state and actions for several independently navigable workflows;
- transport logic for several remote services;
- business decisions, persistence mechanics, mapping, retry/recovery and
  scheduling in the same type.

A stable compatibility facade is allowed to keep existing callers unchanged.
It must contain delegation rather than the extracted decisions. Moving the
same fields and methods into one `*Support`, dependency bag, inherited base, or
mega-coordinator does not satisfy this rule.

## Confirmed baseline targets

| Original type | Baseline size | Independent responsibilities that made it a target |
| --- | ---: | --- |
| `MaintenanceApplicationService` | 8,755 lines | catalogue, estimates, repairs, acceptance/rework, inbound callbacks, reconciliation and transfers |
| `InventoryApplicationService` | 7,256 lines | session lifecycle, reads, findings, review, final planning, completion, statistics and publication |
| `ManagerViewModel` | 6,307 lines | account/workspace, inventory, logistics, media, maintenance reads/catalogue/editor and durable submission |
| `AssetService` | 3,836 lines | cabin lifecycle, logistics effects, equipment catalogue/balances/movements, holds, leases and classifiers |
| `TaskBoardService` | 3,744 lines | board reads, task registration/synchronization, worker execution, ordering, rollover and logistics tasks |
| `HttpLogisticsDependencyGateway` | 3,395 lines | warehouse, asset, maintenance, media and task-board private transports plus shared OAuth/error mapping |
| `InventoryPublicationReconciliationService` | 2,930 lines | publication preflight/apply, replacement saga and compensation, estimate/repair materialization, plan validation/mapping and transaction recovery |
| `LogisticsDocumentService` | 2,738 lines | return, shipment and transfer document commands plus order completion hooks |
| `RentalItemHtmlImportService` | 2,267 lines | import parsing/read projection, plan validation, commit materialization and media retry/recovery |
| asset `PropertyDispositionService` | 2,003 lines | cabin/equipment/custody preparation and effects, holds/leases, low-level persistence mapping and audit/event output |
| `RentalOrderService` | 1,822 lines | order reads, unit/equipment/term editing, reservation synchronization and shipment hand-off |
| `HttpMaintenanceDependencyGateway` | 1,634 lines | warehouse, asset, task-board, logistics and media private transports plus shared OAuth/error mapping |
| maintenance `PropertyDispositionApplicationService` | 1,549 lines | decision creation variants, review, recovery, processing callbacks, repair-chain validation and response mapping |
| asset `InventoryAssetService` | 1,385 lines | capture lifecycle, number/snapshot validation, furniture reconciliation, permanent source creation and cleanup |
| maintenance `InventoryMaintenanceService` | 1,371 lines | frozen-plan reads, source freeze/upsert, catalogue/routing/media validation and isolated recovery transactions |
| `WorkforceService` | 1,109 lines | worker registry, credential reconciliation, groups and qualification membership |

Baseline sizes were measured before the structural write lane. The sections
below record the final facade/collaborator metrics, dependency direction and
the strongest completed verification for every target.

## Completed decomposition evidence

### Inventory application workflow

[`InventoryApplicationService`](../../services/inventory-service/src/main/java/dev/buhanzaz/rwms/inventory/service/InventoryApplicationService.java)
is reduced from 7,256 to 298 lines and seven direct use-case collaborators.
Session, read, finding, validation, review,
planning, completion, statistics, publication and projection decisions now
live in named services. The largest cohesive owners are
[`InventoryFindingService`](../../services/inventory-service/src/main/java/dev/buhanzaz/rwms/inventory/service/InventoryFindingService.java)
(1,430 lines, 13 exact domain dependencies) and
[`InventoryPlanningService`](../../services/inventory-service/src/main/java/dev/buhanzaz/rwms/inventory/service/InventoryPlanningService.java)
(1,316 lines, 10); the completion transaction is
the only 15-dependency cluster. No use case exceeds that limit.

The former universal `InventoryWorkflowSupport` is absent.
[`InventoryTechnicalRuntimeSupport`](../../services/inventory-service/src/main/java/dev/buhanzaz/rwms/inventory/service/InventoryTechnicalRuntimeSupport.java)
retains only four private raw technical
dependencies and scoped JSON, actor/authorization, correlation and transaction
capabilities. One-use-case abstract supports are not Spring beans and expose
only their exact repository/integration surface; the dependency graph is
acyclic.

Verification: inventory `compileJava` succeeded. The Spring context and focused
read/idempotency/owner-proof suites passed 21 + 6 + 1 tests with no failures.
The bounded full module run timed out after 240 seconds without producing new
XML, so it is not claimed as a successful full test.

### Task-board application workflow

[`TaskBoardService`](../../services/task-board-service/src/main/java/dev/buhanzaz/rwms/taskboard/service/TaskBoardService.java)
is reduced from 3,744 to 280 lines and six direct collaborators while retaining
all 32 public operational signatures. Read projection, external registration,
source-authorized mutation, logistics integration, worker execution and manager
ordering are separate services. The largest collaborator is
[`TaskBoardExternalMutationService`](../../services/task-board-service/src/main/java/dev/buhanzaz/rwms/taskboard/service/TaskBoardExternalMutationService.java)
at 961 lines and 14 dependencies; no collaborator exceeds 15 dependencies.

[`TaskBoardQueuePositionCoordinator`](../../services/task-board-service/src/main/java/dev/buhanzaz/rwms/taskboard/service/TaskBoardQueuePositionCoordinator.java)
owns only the advisory-lock/stream-fence/persisted-position mechanics shared by
the exact workflows. Canonical route JSON and retry fingerprints have one
ObjectMapper-only codec. The resulting dependency graph is acyclic and contains
no common inherited base.

Verification: task-board `compileJava`, the focused integration suite and the
complete `:services:task-board-service:test` task all succeeded. The full
module run completed in 4 minutes 14 seconds.

### Workforce lifecycle

[`WorkforceService`](../../services/task-board-service/src/main/java/dev/buhanzaz/rwms/taskboard/service/WorkforceService.java)
is reduced from 1,109 to a 134-line, 3-collaborator facade. All 19 public and
package operational seams are preserved. Profile/qualification mutation,
durable credential lifecycle and auth reconciliation, group membership and
availability, and read projection now have separate owners.

The credential owner is the largest component at 684 lines and exactly 15
dependencies. It retains the per-worker lock across the local intent and
remote completion, stale-operation fencing and deletion recovery. The group
owner retains availability intervals and group event versions. The dependency
graph is acyclic, has no facade reverse reference and contains no inherited or
shared dependency context.

Verification: task-board `compileJava`, four focused workforce/credential/
evidence suites (19/19) and the complete module suite succeeded. The full run
executed 259 tests: 258 passed, none failed or errored and one restored-database
test was expectedly skipped. Static event/lock/SQL marker parity and the
protected JavaDoc snapshot also matched.

### Asset application workflow

[`AssetService`](../../services/asset-service/src/main/java/dev/buhanzaz/rwms/asset/service/AssetService.java)
is reduced from 3,836 task-start lines to a 422-line compatibility facade with
one 5-collaborator Spring constructor. Its 51 business methods, public result
record, 51 transaction annotations, scheduled expiry boundary and three
package seams are preserved. The unused historical 17-argument constructor and
its private component carrier are absent; repository-wide caller search proved
that constructor had no caller.

Rental-item, logistics, equipment, maintenance and classifier workflows have
separate owners. Projection, lease, equipment catalog, physical ledger, hold
and JSON mechanics are narrower one-purpose collaborators. The largest final
type is the logistics command owner at 1,185 lines and exactly 15 direct
dependencies; no type uses a shared base or dependency bag, and the graph is
acyclic.

Verification: asset `compileJava` and logistics boundary (10/10), terminal
integrity (2/2) and equipment authorization (2/2) suites succeeded. A bounded
full module run did not finish within 300 seconds, so it is not claimed green.
The facade public/transaction surface and protected task-start snapshot checks
succeeded.

### Asset inventory boundary

[`InventoryAssetService`](../../services/asset-service/src/main/java/dev/buhanzaz/rwms/asset/service/InventoryAssetService.java)
is reduced from 1,385 to a 102-line, 4-collaborator facade. All 12 public
non-constructor declarations, eight transaction annotations and the scheduled
capture cleanup entry remain unchanged. The former 17-dependency constructor
has no compatibility replacement.

Capture lifecycle, current/read projection, furniture reconciliation and
permanent source creation are separate owners. Canonical JSON and the isolated
repeatable-read snapshot transaction are two one-dependency technical leaves.
The largest component is furniture reconciliation at 693 lines and nine
dependencies; the graph is acyclic and has no inherited context or reverse
facade reference.

Verification: asset `compileJava` and the Stage 7 source boundary, capture,
current snapshot, furniture and selected source/concurrency suites succeeded
(23/23). The source policy now checks every extracted business owner for raw
SQL/JDBC and checks transaction configuration in its actual technical owner.
Both clean pre-edit snapshots passed their protected-diff comparisons.

### Asset HTML-import workflow

[`RentalItemHtmlImportService`](../../services/asset-service/src/main/java/dev/buhanzaz/rwms/asset/service/RentalItemHtmlImportService.java)
is reduced from 2,267 to a 96-line, 4-collaborator facade. All 12 public
business methods and eight facade transaction annotations are preserved. The
uncalled former 15-argument composition constructor is absent.

Raw intake/read projection, durable row-plan decisions, commit materialization
and media retry/replacement recovery have separate owners. A 123-line,
one-dependency codec owns only persisted JSON, bounded hashing, stable command
keys and media-key redaction. The largest component is the cohesive plan owner
at 1,003 lines and six dependencies; every type remains below 1,200/15, the
dependency graph is acyclic and no collaborator refers back to the facade.

Verification: asset `compileJava`, the complete HTML-import integration class
(14/14) and asset authorization suite (7/7) succeeded. Stable commit, media,
preflight, activation and failure-code markers matched the pre-edit source. A
bounded full asset module run produced no new test XML for 267 seconds and was
stopped, so it is not claimed green. Scoped whitespace and clean pre-edit
snapshot checks succeeded.

### Asset property-disposition workflow

Asset-side [`PropertyDispositionService`](../../services/asset-service/src/main/java/dev/buhanzaz/rwms/asset/disposition/PropertyDispositionService.java)
is reduced from 2,003 to a 56-line, 3-collaborator facade. Its three public
business signatures are unchanged. The former uncalled 10-argument constructor
is replaced by the narrow Spring composition constructor.

Eligibility snapshot, preparation and application are independent application
owners. Physical balances/holds/movements, lease/reservation/status proofs,
durable fence/effect replay and canonical JSON have narrow technical owners.
The largest component is the ledger at 787 lines and two dependencies; every
type remains below 1,200/15 and the graph is acyclic. Decision locks live in
the decision store, physical locks in the ledger, and no owner refers back to
the facade or calls another application owner.

Verification: asset `compileJava`, real disposition integration (3/3),
maintenance-identity controller authorization (2/2) and asset OpenAPI parity
(2/2) succeeded. Default transaction-template behavior, remote warehouse check
before preparation, record shapes and lock ownership matched the clean pre-edit
snapshot. The three concurrently dirty disposition repositories were not
touched; scoped import, whitespace, dependency and protected-diff checks
succeeded.

### Maintenance application workflow

[`MaintenanceApplicationService`](../../services/maintenance-service/src/main/java/dev/buhanzaz/rwms/maintenance/service/MaintenanceApplicationService.java)
is reduced from the 8,755-line task-start source (8,753 in `HEAD`) to 349 lines
and six use-case collaborators. All 50 public workflow signatures and their
transaction annotations remain present. Catalog, estimate, repair, transfer,
inbound and reconciliation families have separate owners; the facade is the
only adapter for its unchanged public nested `CreateResult`.

The reconciliation coordinator is now a 145-line dispatcher. Asset, task and
repair-lifecycle effects are independent branches; the largest extracted type
is the cohesive repair-lifecycle reconciliation owner at 970 lines and 15
dependencies. Every constructor/direct dependency surface is at most 15, the
field dependency graph is acyclic, and no collaborator refers back to the
facade. Transitional universal support/dependency types are absent.

Verification: maintenance `compileJava`, inbound effects (3/3), media owner
proof (4/4), and the nearest Spring furniture-accounting path succeeded. The
complete final module run executed 395 tests with one failure:
`furnitureSnapshotCannotQueueWithoutTheCanonicalEquipmentLink`. The test does
not stub the dependency snapshot used by its non-empty furniture branch; the
same failure reproduced in an isolated 108-test class run, while the extracted
short-circuit/guard and original transaction order were compared as
semantically identical. This is recorded as an evidence-backed fixture-gap
inference, not claimed as a green full suite and not fixed in this structural
task.

### Maintenance inventory publication

[`InventoryPublicationReconciliationService`](../../services/maintenance-service/src/main/java/dev/buhanzaz/rwms/maintenance/service/InventoryPublicationReconciliationService.java)
is reduced from 2,930 to a 46-line, 2-collaborator facade with unchanged
preflight/apply methods, read-only transaction annotation and public result
record. The uncalled 28-parameter injection constructor is absent.

Preflight projection, apply orchestration, pre-start replacement saga, source
lifecycle, target selection, estimate/repair materialization, plan validation/
materialization and the isolated transaction primitive have separate owners.
The largest is the pre-start saga at 790 lines and 14 exact dependencies; every
component remains below 1,200/15, and the derived dependency graph is acyclic.
Remote preflights remain outside local locks, and compensation/replay state
ordering remains with the pre-start owner.

Verification: maintenance `compileJava`, 15 direct publication/pre-start/
replay/idempotency scenarios, the Stage 7 source policy and inventory
authorization succeeded (17/17). An earlier whole 28-test boundary class also
passed. A later rerun produced one unrelated Mockito wrong-return-type fixture
failure; its isolated scenario immediately passed, so the broad rerun is not
reported as a second green class run. Protected-scope and API/transaction/DAG
checks succeeded.

### Maintenance inventory boundary

[`InventoryMaintenanceService`](../../services/maintenance-service/src/main/java/dev/buhanzaz/rwms/maintenance/service/InventoryMaintenanceService.java)
is reduced from 1,371 to a 65-line facade with three exact collaborators and
the same three public business methods/two public result records. Both former
19/20-argument constructors are absent. The one unit test that had directly
constructed the entire service now tests the read-only snapshot projection it
actually exercises.

Freeze, upsert and snapshot projection are separate owners. A 3-dependency
plan-validation component owns only frozen-plan/routing/media/stage validation
and allocation; a 1-dependency transaction boundary owns the isolated local
transaction primitive. The largest component is 832 lines, the largest
command has 14 exact dependencies, and the five-component dependency graph is
acyclic with no facade reverse reference.

Verification: maintenance `compileJava` and the inventory boundary, Stage 7
source policy, inventory authorization and snapshot suites all succeeded
(33/33). Public surface comparison and the protected pre-edit snapshot check
also succeeded.

### Maintenance property-disposition workflow

[`PropertyDispositionApplicationService`](../../services/maintenance-service/src/main/java/dev/buhanzaz/rwms/maintenance/disposition/application/PropertyDispositionApplicationService.java)
is reduced from 1,549 to a 222-line, 5-collaborator facade. Its public/package
business methods, nested processing records and transaction annotations are
preserved. Repository-wide search found no direct construction caller for the
removed wide constructor.

Decision creation, furniture materialization, review/recovery, read projection
and processing callbacks have separate owners. Transaction/hash/advisory-lock
mechanics, actor snapshots, decision/event persistence, repair-chain rules and
repair finalization are narrow leaves. The largest component is the creation
owner at 559 lines and eight dependencies; every type remains below 1,200/15,
the dependency graph is acyclic and no collaborator refers back to the facade.

Verification: maintenance `compileJava`, the disposition processor/domain
tests, three real PostgreSQL custody/rework/race scenarios and the controller
OpenAPI binding test succeeded. Snapshot comparison also confirmed preserved
nullable replay, remote-before-final-lock ordering, mandatory furniture seams,
mixed stream-lock ordering and lease-release recovery. No full module suite
was run because of the separately documented maintenance fixture failure.
There is no direct real-service test for manual or inventory-loss creation; the
inventory endpoint binding and aggregate invariants are covered separately.

### Maintenance private HTTP transport

[`HttpMaintenanceDependencyGateway`](../../services/maintenance-service/src/main/java/dev/buhanzaz/rwms/maintenance/integration/HttpMaintenanceDependencyGateway.java)
is reduced from 1,633 to a 308-line, 5-client facade. Its 35 override methods
and exact existing 3-argument package constructor are preserved. The facade no
longer stores the HTTP or OAuth technical clients.

Warehouse, asset, logistics, task-board and media transports have independent
owners. A 125-line, 2-dependency transport leaf owns only exact-scope Bearer
acquisition, HTTP exchange, idempotent verbs, URL normalization and established
error mapping. The largest owner client is the asset boundary at 679 lines and
two dependencies; every type remains below 1,200/15. Owner clients depend only
on the transport, never on peers or the facade, so the graph is acyclic.

Verification: maintenance `compileJava` and the complete dependency-gateway
suite succeeded (34/34). Compiled-surface inspection and source comparison
preserved the constructor, every URL, registration/scope pair, header,
idempotency path, fence validator and error marker. Scoped whitespace, DAG and
clean pre-edit snapshot checks succeeded. The existing deprecated task-board
HTTP API use remains a compiler warning and was deliberately not changed in
this structural task.

### Manager Android orchestration

[`ManagerViewModel`](../../app/src/main/java/dev/buhanzaz/rwms/manager/ui/ManagerViewModel.kt)
is reduced from 6,307 task-start lines to a 463-line class in a 761-line state
and facade file. Its normalized 96-method UI API is unchanged. The facade owns
the one `ManagerUiState`, Android lifecycle and explicit construction of ten
workflow collaborators; it does not own their decisions.

Authentication/workspace, inventory, shipment, return, transfer, maintenance
catalog, reads, editor, durable persistence and media are separate
coordinators. The former universal `ManagerWorkflowCoordinator`, late-bound
`ManagerCoordinatorHost` registry and combined logistics coordinator are
absent. Cross-workflow calls use narrow ports, and the dependency graph is
acyclic. The largest final coordinator is the maintenance editor at 844 lines
and seven exact dependencies; every coordinator stays below 1,000 lines and
seven exact dependencies. `ManagerCommandRuntime` has four technical
dependencies and no domain workflow.

Verification: `compileDebugKotlin`, the five focused editor/routing/furniture/
inventory policy suites (27/27), and `assembleDebug` succeeded. This is build
and unit evidence only; no APK was published and no emulator or live-gateway
claim is made.

### Logistics private HTTP transport

[`HttpLogisticsDependencyGateway`](../../services/logistics-service/src/main/java/dev/buhanzaz/rwms/logistics/integration/HttpLogisticsDependencyGateway.java)
is reduced from 3,395 to an 803-line facade with six exact owner clients. All
67 override signatures and the existing 3-argument package constructor remain
unchanged. The facade stores no `RestClient` or OAuth manager and contains only
interface delegation plus its existing base-URL normalization helper.

Warehouse, asset operations, asset order/presentation, maintenance, media and
task-board transports have separate owners. A 350-line, 2-dependency transport
leaf is the sole owner of client-credential Bearer acquisition, exact-scope
validation, HTTP/list/binary exchange and the established dependency-error
mapping. The largest owner client is the order/presentation boundary at 917
lines; every type remains below 1,200/15. Owner clients do not depend on peers
or refer back to the facade, and the graph is acyclic.

Verification: logistics `compileJava`, the transport unit suite (29/29) and
the order, transfer-saga, capital-repair promotion and equipment-movement
integration suites (39/39) succeeded. Normalized API signatures, constructor,
URL fragments, scopes, idempotency headers and error-marker strings matched the
clean pre-edit snapshot. A bounded full logistics module run exceeded 300
seconds without a new completed result and was stopped, so it is not claimed
green. Scoped whitespace and protected-snapshot checks succeeded.

### Logistics document workflows

[`LogisticsDocumentService`](../../services/logistics-service/src/main/java/dev/buhanzaz/rwms/logistics/service/LogisticsDocumentService.java)
is reduced from 2,738 to a 578-line, 7-delegate facade. All 35 public
declarations, 28 transaction annotations and package-level static workflow
aliases remain compatible with the pre-edit source.

Return, shipment and transfer state machines, rental-order shipment/completion,
reconciliation and read projection have separate owners. Warehouse admission,
idempotency records, external-attempt writes and active-order binding are narrow
technical leaves. The largest component is the transfer coordinator at 754
lines and 11 dependencies; every type remains below 1,200/15. Coordinators do
not call one another or refer back to the facade, and the graph is acyclic.

Verification: logistics `compileJava` and 14 focused architecture,
authorization, document-state, concurrency, reconciliation, media, order and
event/recovery suites succeeded (69/69). Effect markers, transfer digest, hold
wrappers, signatures and transaction placement matched the protected pre-edit
snapshot, including the known warehouse bypass. A bounded full module run
reached 300 seconds without a final Gradle result and is not claimed green.
Scoped whitespace and protected-JavaDoc checks succeeded.

### Logistics rental-order workflow

[`RentalOrderService`](../../services/logistics-service/src/main/java/dev/buhanzaz/rwms/logistics/order/service/RentalOrderService.java)
is reduced from 1,822 to a 215-line, 6-collaborator facade. All 17 public
business signatures and the original facade transaction annotations are
preserved; no collaborator introduces a new transaction boundary. The former
wide construction surface has no compatibility replacement.

Read projection, creation, client/warehouse lifecycle, unit/equipment
reservation, rental terms and shipment hand-off have separate owners. The
command store alone owns order-row/receipt/advisory-lock persistence, and the
editability service alone coordinates the saved shipment-draft lock. The
largest component is the reservation owner at 810 lines and ten dependencies;
every type remains below 1,200/15, the graph is acyclic and no collaborator
refers back to the facade.

Verification: logistics `compileJava` and the complete order API integration
suite succeeded (23/23), covering authorization, warehouse visibility, version
fences, unit/equipment reservations, replay/unknown outcomes, shipment locks
and rental-term extension. The god-class source policy succeeded (7/7).
Compiled/public-surface, transaction placement, scoped whitespace and the
protected class-JavaDoc snapshot matched. The combined architecture command
also exposes ten existing low-level SQL/native-query source-policy violations
in frozen logistics repositories/stores; none is in this facade or its new
collaborators, and those violations remain for the next remediation step.

## Final repository gate

All 16 confirmed targets are decomposed into thin stable facades and cohesive,
acyclic collaborators. The dedicated source regression policy succeeds 7/7.
The complete central architecture suite succeeds 47/49; its only failures are
the three frozen inventory SQL boundaries and ten frozen logistics
SQL/native-query boundaries already recorded in the architecture audit. They
were not allow-listed or corrected by this structural task.

All affected production modules compile. Javadoc generation for all 13 Java
modules succeeds; manager and worker Android Kotlin compilation succeeds. The
documentation gate contains 34 synchronized EN/RU README pairs, 93 active
Markdown files with 965 valid local links. A declaration-aware lexical scan of
all 167 newly added, untracked Java/Kotlin source files found 490 real type
declarations (454 Java and 36 Kotlin), all immediately documented; eight
declaration-shaped snippets inside synthetic test text blocks were deliberately
excluded. The corrective pass added 236 meaningful JavaDoc/KDoc blocks.
Target-focused results and every inconclusive bounded full-suite run are
recorded above; none was promoted to a successful result.

The refactor changed no OpenAPI/event contract, JPA mapping, Flyway migration,
stored data, deployment or runtime service. Known defects remain frozen for
the separately authorized remediation step.

## Large types reviewed separately

The following types crossed a size or method-count signal, but require a
cohesion review rather than an automatic rename/split:

| Type or module | Why it is not classified by line count alone | Final gate |
| --- | --- | --- |
| Java/Kotlin API model and gateway-port files | Declarative boundary/state vocabulary and method signatures rather than one decision-making class; this includes `MaintenanceApiModels`, task-board `ApiModels`, Android `ApiModels`, `MaintenanceDependencyGateway` and `LogisticsDependencyGateway` | Keep behavior and mutable workflow state out of declarative containers |
| `TransferWorkflowStore`, `ShipmentWorkflowStore` and `ReturnCompletionWorkflowStore` | Each is one persisted document/saga state machine with its own replay, fencing and terminal checks; the largest has seven direct stores rather than unrelated application owners | Preserve one transactionally reviewable state-machine owner; split only if a second independently changing workflow appears |
| `WorkerTaskBoardService` | One worker-facing application boundary combines context/feed/detail, device registration, evidence and fenced actions, but remains below the combined size/method/dependency target signals and has eight direct dependencies | Re-review when worker commands or projection ownership grows; do not turn it into a second universal task-board facade |
| `MaintenanceFurnitureCustodyService` | Selection, return and terminal application are transitions of one custody lifecycle backed by four technical dependencies | Keep the custody invariant and its advisory-lock transaction together |
| large Compose/React screen files | The reviewed files contain several local leaf composables/components or several exported pages rather than one class with a shared dependency surface. The HTML-import workspace is one wizard state machine and the camera experience is one capture lifecycle | Split when one state owner controls independent workflows, not solely for rendered line count or file organization |
| media `Repository` in Go | Two-field PostgreSQL adapter with cohesive media persistence receiver methods | Keep aggregate transitions and technical stores reviewable; file length alone is not a class metric |

This table is not an exemption list. The final scan must promote any row that
still meets the decision rule after the primary refactor.

## Frozen behavior boundary

This task may change packages, constructors, forwarding structure and internal
collaborators. It must not change:

- OpenAPI or event schemas;
- JPA mappings, Flyway migrations or stored data;
- authorization, warehouse admission or identity semantics;
- status transitions, money, time, expected-version or idempotency meaning;
- retry, outbox/inbox, saga or reconciliation outcomes;
- the known defects and violations already listed in the
  [full architecture audit](20260808-full-architecture-audit.md).

Every existing public facade signature and transaction boundary is retained
unless a current caller proves that the method is not part of the supported
surface. Runtime remediation remains a separate next step.

## Required handoff evidence

The structural refactor is complete only when the repository contains:

1. a thin stable facade for every confirmed target;
2. cohesive collaborators with narrow direct dependencies and an acyclic
   dependency graph;
3. JavaDoc/KDoc on every new architecture-significant type and non-obvious
   transaction/recovery boundary;
4. synchronized English/Russian component READMEs and current structure maps;
5. before/after LOC, method and dependency metrics;
6. focused compile/tests for every affected module and the architecture suite;
7. a protected-worktree comparison proving that earlier documentation and user
   changes survived.
