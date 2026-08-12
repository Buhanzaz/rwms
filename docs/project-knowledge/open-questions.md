# Open Product And Architecture Questions

Use this file only for unresolved contradictions or product decisions that
block a safe implementation. This is not a backlog and does not authorize
work.

## Cabin Dossier Detail And Historical Snapshot Boundary

- Status: `Open`
- Affected owner and consumers: logistics-service, maintenance-service,
  inventory-service and task-board-service as fact owners; dossier-service and
  the panel cabin history as read-only consumers.
- Requested behavior: the cabin history should group booking, logistics,
  estimate, repair/acceptance and inventory operations and expand each group
  into the responsible people, destination, reason, materials, duration and
  owning workflow link.
- Conflicting contract or invariant: current logistics document facts publish
  only document state, warehouses and line count, while the booking fact
  publishes only conversation and order IDs. Maintenance repair facts publish
  stage identities and states but not material/work names, crew or actual
  duration. The dossier contract explicitly forbids inferring a cabin subject,
  free-form summary or href and exposes no detail snapshot. Hydrating current
  owner reads in the browser would not prove the historical state; publishing
  client or worker display data would additionally require an approved
  sanitized-data boundary.
- Evidence:
  [`dossier consumers`](../../contracts/events/dossier-consumers.yaml),
  [`logistics facts`](../../contracts/events/logistics/logistics-events-v1.schema.json),
  [`booking fact`](../../contracts/events/logistics/rental-inquiry-events-v1.schema.json),
  [`maintenance facts`](../../contracts/events/maintenance/maintenance-events-v1.schema.json),
  and [`dossier OpenAPI`](../../contracts/openapi/dossier-service.yaml).
- Smallest decision needed: choose immutable, sanitized per-cabin snapshots in
  new producer/dossier contract versions or explicitly accept expansion from
  current owner reads; approve the exact client/worker field allow-list and
  decide whether pre-existing logistics history must be backfilled.
- Resolution and date: none. The panel may render and group only canonical
  fields already supplied by dossier-service; it must not fabricate the absent
  booking or work-detail evidence.

## Resolved: Logistics Admission Evidence For Dependency-Free Replay

- Status: `Resolved`
- Affected owner and consumers: logistics-service, warehouse-service lifecycle
  API and every public logistics create workflow.
- Requested behavior: an exact idempotent replay may avoid a second warehouse
  admission call only when durable local evidence proves that the original
  operation was admitted for the same warehouse, direction, lifecycle version
  and command fingerprint.
- Conflicting contract or invariant: resolved without treating the idempotency
  row or historical operation marks as admission proof. New permanent marks
  store the admitted direction and exact warehouse lifecycle version; legacy
  marks retain null evidence and remain unproven.
- Evidence:
  [`LogisticsWarehouseLifecycle.java`](../../services/logistics-service/src/main/java/dev/buhanzaz/rwms/logistics/service/LogisticsWarehouseLifecycle.java),
  [`LogisticsDocumentWarehouseAdmission.java`](../../services/logistics-service/src/main/java/dev/buhanzaz/rwms/logistics/service/LogisticsDocumentWarehouseAdmission.java),
  [`LogisticsIdempotencyRecord.java`](../../services/logistics-service/src/main/java/dev/buhanzaz/rwms/logistics/domain/LogisticsIdempotencyRecord.java),
  [`LogisticsWarehouseLifecycleStore.java`](../../services/logistics-service/src/main/java/dev/buhanzaz/rwms/logistics/service/LogisticsWarehouseLifecycleStore.java),
  [`LogisticsWarehouseOperationMarkStore.java`](../../services/logistics-service/src/main/java/dev/buhanzaz/rwms/logistics/service/LogisticsWarehouseOperationMarkStore.java),
  [`V39__warehouse_admission_evidence.sql`](../../services/logistics-service/src/main/resources/db/migration/V39__warehouse_admission_evidence.sql).
- Smallest decision needed: none.
- Resolution and date: 2026-08-09 — a dependency-free replay candidate requires
  live durable owner identity, a live owner receipt and an exact complete set
  of permanent marks carrying warehouse, direction and admitted lifecycle
  version. The owner still validates the stored command checksum. Legacy null
  evidence, missing/extra/mismatched marks and expired receipts require remote
  re-admission and fail with `503` while that dependency is unavailable.

## Assistant Inquiry Recovery And Delegated Identity

- Status: `Open`
- Affected owner and consumers: assistant-service and logistics-service;
  panel assistant users, security audit and incident recovery.
- Requested behavior: creating a conversation must produce one explainable
  logistics inquiry and one caller-owned assistant conversation even when an
  HTTP response, database commit or process is lost. Internal authentication
  must have one explicit identity model.
- Conflicting contract or invariant: the local transaction problem is now
  resolved for a caller-supplied stable conversation UUID. Assistant performs
  a short preflight, invokes the idempotent logistics create command with no
  local transaction open, and then creates or validates the local link under a
  short advisory lock; panel callers retain the same UUID across uncertain
  retries. The public contract still permits omission of that UUID, and the
  private adapter still forwards the interactive user's Bearer token while the
  architecture rule prefers service identity unless delegated identity is
  explicitly designed.
- Evidence:
  [`AssistantConversationService.java`](../../services/assistant-service/src/main/java/dev/buhanzaz/rwms/assistant/service/AssistantConversationService.java),
  [`HttpLogisticsClient.java`](../../services/assistant-service/src/main/java/dev/buhanzaz/rwms/assistant/integration/HttpLogisticsClient.java),
  [`assistant-service.yaml`](../../contracts/openapi/assistant-service.yaml),
  [`logistics-service.yaml`](../../contracts/openapi/logistics-service.yaml).
- Smallest decision needed: decide whether `conversationId` becomes mandatory
  for every external caller, and choose either service identity plus immutable
  end-user actor context or a canonical delegated-token contract with audience,
  lifetime, scope and audit rules.
- Resolution and date: partially resolved on 2026-08-09. Stable-ID panel retries
  no longer hold an assistant transaction across the remote create and can
  reconstruct the local link from logistics replay; optional-ID and delegated
  identity semantics remain open.

## Assistant Booking DLT Review Entry Point

- Status: `Open`
- Affected owner and consumers: assistant-service and an authorized operations
  client; logistics remains the producer and rental-inquiry owner.
- Requested behavior: an authenticated operator can inspect sanitized terminal
  booking evidence and explicitly approve, reject or resume the already staged
  canonical effect with a complete audit trail.
- Conflicting contract or invariant: assistant now has a version-fenced,
  idempotent internal review/replay service, but no approved public/private
  command, caller identity, authorization policy or operator UI. Exposing the
  repository or inventing automatic approval would bypass the recovery owner.
- Evidence:
  [`AssistantDltRecoveryService.java`](../../services/assistant-service/src/main/java/dev/buhanzaz/rwms/assistant/eventing/AssistantDltRecoveryService.java),
  [`AssistantEventDeadLetter.java`](../../services/assistant-service/src/main/java/dev/buhanzaz/rwms/assistant/domain/AssistantEventDeadLetter.java),
  [`V4__canonical_booking_inbox_and_recovery.sql`](../../services/assistant-service/src/main/resources/db/migration/V4__canonical_booking_inbox_and_recovery.sql).
- Smallest decision needed: choose the authenticated operator boundary and
  scopes, the review-list response shape and whether an approved replay is
  executed synchronously or by a separately bounded worker.
- Resolution and date: none. The internal capability remains unreachable from
  production callers and never auto-approves terminal evidence.

## Assistant LLM Data Boundary

- Status: `Open`
- Affected owner and consumers: assistant-service, auth/logistics data owners,
  privacy/security operations and the configured LLM provider.
- Requested behavior: send only the minimum approved conversation and tool
  context to the model, with bounded cost and an auditable retention policy.
- Conflicting contract or invariant: current prompt construction can replay an
  unbounded conversation and completed tool payload history. Contact-field
  filtering is name-based and there is no approved field allow-list, maximum
  context budget, retention period, provider region or redaction policy in the
  repository.
- Evidence:
  [`AssistantConversationService.java`](../../services/assistant-service/src/main/java/dev/buhanzaz/rwms/assistant/service/AssistantConversationService.java),
  [`AssistantTurnService.java`](../../services/assistant-service/src/main/java/dev/buhanzaz/rwms/assistant/service/AssistantTurnService.java),
  [`HttpChatCompletionClient.java`](../../services/assistant-service/src/main/java/dev/buhanzaz/rwms/assistant/integration/HttpChatCompletionClient.java).
- Smallest decision needed: approve an outbound field allow-list, redaction
  rules, token/turn budget, summarization authority, provider/region and
  conversation/tool retention period.
- Resolution and date: none. Until decided, implementation must fail closed on
  fields outside an explicit technical allow-list and must not log provider
  bodies.

## Analytics Gap Reconciliation And Completeness

- Status: `Open`
- Affected owner and consumers: analytics-service, every event producer that
  contributes KPI facts, dashboard clients and operators.
- Requested behavior: a missing aggregate version must be recoverable, and a
  `COMPLETE` dashboard period must mean one precise, testable coverage claim.
- Conflicting contract or invariant: analytics terminally blocks a checkpoint
  after bounded local retries but has no producer replay, snapshot or reviewed
  reconciliation boundary. Current completeness checks prove received facts,
  not necessarily interior date/group continuity across the requested period.
- Evidence:
  [`AnalyticsAggregateCheckpoint.java`](../../services/analytics-service/src/main/java/dev/buhanzaz/rwms/analytics/domain/AnalyticsAggregateCheckpoint.java),
  [`AnalyticsGapRecoveryService.java`](../../services/analytics-service/src/main/java/dev/buhanzaz/rwms/analytics/service/AnalyticsGapRecoveryService.java),
  [`AnalyticsProjectionService.java`](../../services/analytics-service/src/main/java/dev/buhanzaz/rwms/analytics/service/AnalyticsProjectionService.java),
  [`aggregate-checkpoint-policy-v1.schema.yaml`](../../contracts/events/technical/aggregate-checkpoint-policy-v1.schema.yaml).
- Smallest decision needed: nominate the authoritative replay/snapshot source
  and reviewed recovery actor, and define whether `COMPLETE` requires every
  warehouse-local date and active group or a different explicit coverage
  model.
- Resolution and date: none. Kafka retention alone is not an acceptable
  archive or reconciliation source.

## Worker And Driver Invalidation Replay Semantics

- Status: `Open`
- Affected owner and consumers: task-board-service, WorkerApp, DriverApp and API
  gateway SSE transport.
- Requested behavior: reconnecting either native task client must have one
  documented recovery rule after missed invalidations.
- Conflicting contract or invariant: OpenAPI tells clients to reconnect with
  `Last-Event-ID`, while task-board currently emits a new in-memory
  `FEED_CHANGED` invalidation on connection and does not replay the supplied
  cursor. Both native clients also perform periodic authoritative refreshes.
- Evidence:
  [`task-board-service.yaml`](../../contracts/openapi/task-board-service.yaml),
  [`WorkerTaskBoardController.java`](../../services/task-board-service/src/main/java/dev/buhanzaz/rwms/taskboard/api/WorkerTaskBoardController.java),
  [`DriverTaskBoardController.java`](../../services/task-board-service/src/main/java/dev/buhanzaz/rwms/taskboard/api/DriverTaskBoardController.java),
  [`WorkerInvalidationHub.java`](../../services/task-board-service/src/main/java/dev/buhanzaz/rwms/taskboard/service/WorkerInvalidationHub.java),
  [`WorkerRealtimeCoordinator.kt`](../../worker-app/core-sync/src/main/java/dev/buhanzaz/rwms/worker/core/sync/WorkerRealtimeCoordinator.kt),
  [`DriverRealtimeCoordinator.kt`](../../driver-app/core-sync/src/main/java/dev/buhanzaz/rwms/driver/core/sync/DriverRealtimeCoordinator.kt).
- Smallest decision needed: choose invalidation-only reconnect with mandatory
  full refresh and no replay promise, or a durable ordered replay log with
  cursor expiry and resync semantics.
- Resolution and date: none. The current periodic refresh reduces data-loss
  impact but does not make the contract statement accurate.

## Android Release Publication Trust

- Status: `Open`
- Affected owner and consumers: manager Android app, WorkerApp, DriverApp,
  manager-download-site, worker-download-site, release engineering and device
  administrators.
- Requested behavior: every published APK must be traceable to one reviewed
  source revision and expose a verifiable version, signature and checksum.
- Conflicting contract or invariant: the manager download site has two manually
  kept HTML implementations and publishes a mutable debug APK with hand-entered
  version/date/size metadata. The worker site now separately publishes a
  reviewed signed 0.1.11 APK through one validated manifest, a versioned
  Worker-owned download route and a SHA-256 check, but there is still no shared
  release registry, documented signing authority/retention policy or
  organization-wide checksum-verification flow. DriverApp has no assigned
  download-site or published release contract yet.
- Evidence:
  [`manager app build`](../../app/build.gradle.kts),
  [`download preparation`](../../manager-download-site/scripts/prepare-sites-worker.mjs),
  [`Next page`](../../manager-download-site/app/page.jsx),
  [`static page`](../../manager-download-site/public/index.html),
  [`worker manifest`](../../worker-download-site/release.json),
  [`worker route`](../../worker-download-site/scripts/build-site.mjs),
  [`worker app build`](../../worker-app/app/build.gradle.kts),
  [`driver app build`](../../driver-app/app/build.gradle.kts).
- Smallest decision needed: choose the supported page implementation, release
  signing authority and key custody, artifact repository/retention policy, and
  manifest fields used as the only rendered version source.
- Resolution and date: the WorkerApp publication portion was completed on
  2026-08-10 under an explicit reviewed release scope. The overall question
  remains open for ManagerApp, DriverApp and the organization-wide release
  policy.

## Media Terminal Retry Authorization

- Status: `Open`
- Affected owner and consumers: media-service processing operations, security
  administrators and every client waiting for terminal media.
- Requested behavior: an authorized operator may review a terminal processing
  cycle and, where policy permits, start one new bounded cycle without losing
  immutable terminal evidence or duplicating derivatives/facts.
- Conflicting contract or invariant: V11 stores a versioned, idempotent review
  receipt but no authenticated production API calls it, and the receipt does
  not reset or requeue the job. Treating a database receipt as authorization or
  silently reopening `FAILED` work would invent role, audit and retry-budget
  semantics.
- Evidence:
  [`worker.go`](../../services/media-service/internal/persistence/worker.go),
  [`V11__bounded_media_processing_recovery.sql`](../../services/media-service/db/migration/V11__bounded_media_processing_recovery.sql),
  [`media-service README`](../../services/media-service/README.md).
- Smallest decision needed: define the authenticated role/scope, mandatory
  reason and evidence, stable command identity, whether approval starts a new
  cycle, the new cycle's attempt budget and which terminal codes are eligible.
- Resolution and date: none. `FAILED` remains terminal; review is append-only
  audit evidence only and cannot execute retry.

## Resolved: Return Estimate Furniture Loss Accounting

- Status: `Resolved`
- Affected owner and consumers: logistics-service, maintenance-service and
  asset-service; panel and manager Android app return/estimate flows.
- Requested behavior: the return action should open a new linked estimate
  without collecting shortages first. Furniture selected in that individual
  estimate must enter a separately approved loss decision; for a legacy cabin
  with no recorded composition, an operator may explicitly proceed without
  decrementing warehouse additional-equipment stock.
- Conflicting contract or invariant: resolved without changing ownership.
  Logistics owns the per-line return workflow and maintenance owns estimate
  completion and loss decisions; asset-service remains the only owner of a
  normal custody/balance effect.
- Evidence:
  [`logistics-service.yaml`](../../contracts/openapi/logistics-service.yaml),
  [`maintenance-service.yaml`](../../contracts/openapi/maintenance-service.yaml),
  [`asset-service.yaml`](../../contracts/openapi/asset-service.yaml),
  [`LogisticsDocumentService.java`](../../services/logistics-service/src/main/java/dev/buhanzaz/rwms/logistics/service/LogisticsDocumentService.java),
  [`MaintenanceApplicationService.java`](../../services/maintenance-service/src/main/java/dev/buhanzaz/rwms/maintenance/service/MaintenanceApplicationService.java),
  [`PropertyDispositionApplicationService.java`](../../services/maintenance-service/src/main/java/dev/buhanzaz/rwms/maintenance/disposition/application/PropertyDispositionApplicationService.java).
- Smallest decision needed: none.
- Resolution and date: 2026-08-07 — start one `DRAFT` estimate per return
  line from photo proof only. A normal cabin uses fenced asset custody and a
  later approval; an empty legacy cabin requires an explicit completion retry,
  then creates `UNACCOUNTED` maintenance decisions that become effective
  without an asset-service or warehouse-balance effect.

## Retention And Archive Policy For Immutable Operational Evidence

- Status: `Open`
- Affected owner and consumers: warehouse, asset, maintenance, inventory,
  logistics and task-board services; operations/compliance readers.
- Requested behavior: bound database growth without weakening reconstructable
  write-off, correction, recovery, movement and warehouse-lifecycle history.
- Conflicting contract or invariant: current event, outbox, inbox, snapshot and
  recovery-audit rows are intentionally immutable, while no approved retention
  duration, legal hold, archive target or deletion authority exists.
- Evidence:
  [`20260804-property-warehouse-equipment-write-off-audit.md`](../reviews/20260804-property-warehouse-equipment-write-off-audit.md),
  [`V33__administrative_asset_corrections.sql`](../../services/asset-service/src/main/resources/db/migration/V33__administrative_asset_corrections.sql),
  [`V6__warehouse_outbox_reviewed_recovery.sql`](../../services/warehouse-service/src/main/resources/db/migration/V6__warehouse_outbox_reviewed_recovery.sql).
- Smallest decision needed: retention period per record family, legal/audit hold
  rules, archive storage and verification format, restore procedure, and the
  role allowed to authorize irreversible removal.
- Resolution and date: none. Operational backlog/age metrics are present, but
  this implementation deletes or archives no production evidence.

## Question Template

### Short question title

- Status: `Open` or `Resolved`
- Affected owner and consumers:
- Requested behavior:
- Conflicting contract or invariant:
- Evidence:
- Smallest decision needed:
- Resolution and date:
