# Open Product And Architecture Questions

Use this file only for unresolved contradictions or product decisions that
block a safe implementation. This is not a backlog and does not authorize
work.

## Retiring Simulator Requests Missing From A Full RWMS Feed

- Status: `Open`
- Affected owner and consumers: logistics-service as rental-demand owner;
  standalone `logistics/` simulator as a read projection and planner; saved
  route plans and later apply commands.
- Requested behavior: a synchronized simulator scenario must stop planning an
  order after it is assigned, cancelled or otherwise disappears from the
  logistics-owned still-unplanned demand feed.
- Conflicting contract or invariant: the canonical planning response is named
  a complete warehouse/date demand snapshot, but it contains only the current
  still-unplanned remainder and no tombstone or removal reason. The simulator
  currently upserts returned order IDs and leaves an absent local request
  `READY`. Deleting it would break saved-plan references and erase history;
  retaining it as active leaves planner state stale. A later apply fails at
  the authoritative logistics fence, but that does not correct the local
  projection.
- Evidence:
  [`planning feed contract`](../../contracts/openapi/logistics-service.yaml),
  [`simulator synchronization`](../../logistics/backend/app/integrations/rwms_sync.py),
  and
  [`simulator request aggregate`](../../logistics/backend/app/models/domain.py).
- Smallest decision needed: define whether omission from a successful complete
  refresh is an authoritative inactive transition, and specify how existing
  unassigned tasks, saved plan members and history are retained or invalidated.
  If omission is not sufficient evidence, extend the canonical feed with a
  lifecycle/tombstone fact and update both producer and consumer together.
- Resolution and date: none.

## Clearing Published Simulator Capacity On Local Lifecycle Changes

- Status: `Open`
- Affected owner and consumers: standalone `logistics/` simulator as capacity
  publisher; logistics-service as the active warehouse projection owner;
  CustomerApp slot searches.
- Requested behavior: the active anonymous generated-delivery snapshot must
  stop constraining a warehouse after its linked scenario is deleted, reset or
  relinked.
- Conflicting contract or invariant: logistics owns a full-replacement
  projection keyed by warehouse and keeps it active until another accepted
  replacement. The simulator republishes after generated-workload changes, but
  scenario deletion, demo reset and an `external_warehouse_id` relink currently
  have no durable clear receipt. If publication is disabled or temporarily
  unavailable after the local mutation, the last successful capacity can
  remain active indefinitely. Silently deleting local evidence or making a
  best-effort browser call would violate recovery and ownership rules.
- Evidence:
  [`capacity replacement contract`](../../contracts/openapi/logistics-service.yaml),
  [`simulator capacity projection`](../../logistics/backend/app/services/capacity_projection.py),
  [`scenario lifecycle API`](../../logistics/backend/app/api/scenarios.py),
  [`scenario lifecycle owner`](../../logistics/backend/app/services/scenarios.py),
  and
  [`warehouse link editor`](../../logistics/frontend/src/components/EntityDialogs.tsx).
- Smallest decision needed: either fail closed and refuse delete/reset/relink
  until an empty replacement is acknowledged, or persist a server-side local
  clear intent/receipt and permit the lifecycle mutation only with an explicit
  retry/recovery state. A projection TTL would instead be a separate canonical
  logistics contract change.
- Resolution and date: none.

## Guaranteed Alice/SpeechKit Voice Search In CustomerApp

- Status: `Open`
- Affected owner and consumers: CustomerApp delivery search, a future
  credential-owning backend boundary, and Yandex Cloud SpeechKit.
- Requested behavior: make the map search microphone a guaranteed Yandex Alice
  voice recognizer rather than depending on the recognizer installed on the
  Android device.
- Conflicting contract or invariant: SpeechKit authorization needs a Yandex
  Cloud API key or IAM token. Embedding that service credential in a public APK
  would disclose it, while no approved RWMS backend proxy, audio retention
  policy, consent text, rate limit or failure contract currently exists.
  CustomerApp therefore safely invokes the Android system Russian speech
  activity, requests no microphone permission, stores no audio, and sends only
  the returned text through its existing Yandex geocoder.
- Evidence:
  [`delivery voice entry`](../../client-app/app/src/main/java/dev/buhanzaz/rwms/client/ui/DeliveryFlowScreens.kt),
  [`Android manifest`](../../client-app/app/src/main/AndroidManifest.xml),
  and
  [`CustomerApp dependency boundary`](../../client-app/README.md).
- Smallest decision needed: approve the backend owner and public contract for
  streaming audio, provision a restricted SpeechKit credential there, and
  define consent, retention, quota and fallback behavior. The credential must
  never enter CustomerApp or its download artifact.
- Resolution and date: none. On 2026-08-27 the device-recognizer fallback was
  implemented without adding an APK secret or audio storage.

## Non-Expiring Cabin Presentation Versus Current Media Generation

- Status: `Open`
- Affected owner and consumers: media-service as retained-object and scoped-read
  owner; logistics-service as public cabin-presentation owner; public panel
  presentation viewers.
- Requested behavior: a created cabin-photo presentation is an immutable,
  non-expiring snapshot whose exact image URLs remain readable.
- Conflicting contract or invariant: logistics freezes only
  `{mediaId,generation}` and proxies every later image read to media-service.
  The private media operation currently authorizes only a `READY` asset's exact
  current generation. A later soft delete or generation advance therefore
  makes the non-expiring public URL return `404`, even though media retains the
  immutable object and variant rows. Allowing arbitrary historical reads would
  broaden media authorization and cannot be inferred from retention alone.
- Evidence:
  [`logistics public presentation contract`](../../contracts/openapi/logistics-service.yaml),
  [`media private presentation contract`](../../contracts/openapi/media-service.yaml),
  [`logistics presentation proxy`](../../services/logistics-service/src/main/java/dev/buhanzaz/rwms/logistics/photo/CabinPhotoPresentationService.java),
  and
  [`media variant read`](../../services/media-service/internal/persistence/cabin_photo_library.go).
- Smallest decision needed: either authorize the exact historically frozen
  generation only when it is named by a valid logistics presentation, with a
  durable owner-proof boundary, or weaken the public contract so a presentation
  remains structurally immutable but its images are readable only while those
  media generations are current.
- Resolution and date: none.

## Retiring The Former Saint Petersburg Warehouse

- Status: `Open`
- Affected owner and consumers: warehouse-service as warehouse-lifecycle owner;
  asset-service and every operational owner retaining the former warehouse UUID.
- Requested behavior: remove the former `СПБ` after making the active `СПБ2`
  identity the canonical `СПБ` warehouse.
- Conflicting contract or invariant: the former UUID has durable asset and
  operation references and is already `DRAINING`. Warehouse identity is an
  opaque historical reference, and the canonical lifecycle permits
  `DRAINING -> INACTIVE` only after every contract-defined owner confirms
  readiness. Physical row deletion or reassignment of its assets would bypass
  those owners and make retained history unresolvable.
- Evidence:
  [`warehouse-service contract`](../../contracts/openapi/warehouse-service.yaml),
  [`warehouse lifecycle`](runtime-flows.md#warehouse-lifecycle-coordination),
  [`WarehouseService`](../../services/warehouse-service/src/main/java/dev/buhanzaz/rwms/warehouse/service/WarehouseService.java),
  and [`asset-service contract`](../../contracts/openapi/asset-service.yaml).
- Smallest decision needed: decide whether the former warehouse's remaining
  property must be transferred through supported workflows, completed in
  place, or retired by a separately designed audited process before owner
  readiness may complete.
- Resolution and date: none. On 2026-08-26 the old identity was renamed
  `СПБ (выводится)` and left non-active in `DRAINING`; the active `СПБ2`
  identity was renamed `СПБ`. No warehouse, asset or historical row was
  deleted or reassigned.

## Cabin Creation With Durable Photo Completion

- Status: `Open`
- Affected owner and consumers: asset-service as cabin-creation owner;
  media-service as photo owner; panel administrators creating cabins.
- Requested behavior: creating a cabin with selected photos should produce one
  recoverable outcome even if the browser closes, the network response is lost
  or one photo upload fails after the cabin already exists.
- Conflicting contract or invariant: the public asset create command and every
  media upload are individually idempotent, but the panel currently invokes the
  asset command first and then uploads in-memory browser files to media-service.
  No owning service persists that cross-service intent, and the architecture
  forbids a browser-owned saga. A tab or process loss after asset creation can
  therefore leave the cabin without the selected photos; inventing a temporary
  media owner or making asset-service write media state would also violate the
  current ownership contracts.
- Evidence:
  [`RentalItemCreationDialog`](../../panel/src/features/rental-items/rental-item-create-dialog.tsx),
  [`asset create client`](../../panel/src/features/rental-items/api/asset-rental-items-api.ts),
  [`media upload client`](../../panel/src/features/media/api/http-media-client.ts),
  [`asset-service contract`](../../contracts/openapi/asset-service.yaml), and
  [`media-service contract`](../../contracts/openapi/media-service.yaml).
- Smallest decision needed: choose an owning service and contract for a durable
  creation-with-photos intent, including how staged bytes are identified before
  the cabin exists and how an administrator resumes or abandons an incomplete
  intent without deleting retained media.
- Resolution and date: none. The current panel reports the already-created
  cabin and upload failure honestly and can retry while its in-memory files
  remain available; it must not claim atomic completion or persist a browser
  saga.

## Explicitly Empty Inventory Passport

- Status: `Open`
- Affected owner and consumers: inventory-service as evidence owner;
  asset-service as cabin-passport owner; panel and ManagerApp as clients.
- Requested behavior: a completed inventory makes a `PRESENT` passport the
  complete warehouse truth, while `ABSENT` deliberately leaves the asset
  passport unchanged.
- Conflicting contract or invariant: inventory also permits
  `EXPLICIT_EMPTY`, but asset-service requires active catalog identities for
  type, dimensions, finishing and category. Clearing those required identities
  would create a cabin that cannot satisfy the current asset model; interpreting
  it as `ABSENT` would discard an explicit field decision.
- Evidence:
  [`InventoryFinding.java`](../../services/inventory-service/src/main/java/dev/buhanzaz/rwms/inventory/domain/InventoryFinding.java),
  [`InventoryPublicationService.java`](../../services/inventory-service/src/main/java/dev/buhanzaz/rwms/inventory/service/InventoryPublicationService.java),
  [`InventoryAssetOutcomeService.java`](../../services/asset-service/src/main/java/dev/buhanzaz/rwms/asset/service/InventoryAssetOutcomeService.java),
  and [`asset-service.yaml`](../../contracts/openapi/asset-service.yaml).
- Smallest decision needed: decide whether a future `EXPLICIT_EMPTY` passport
  should be rejected at inventory completion, mapped to a dedicated
  incomplete/unknown asset state, or assigned explicit catalog placeholder
  values.
- Resolution and date: none. Publication fails closed for `EXPLICIT_EMPTY`;
  the current completed plan contains zero such observations.

## Missing Cabin At Inventory: Rental Or Write-Off

- Status: `Resolved` on 2026-08-21.
- Affected owner and consumers: inventory-service as session/finding owner;
  logistics-service as rental/order/shipment owner; maintenance-service as
  write-off-decision owner; asset-service as cabin/custody owner; panel and
  ManagerApp as clients.
- Resolved behavior: the shipment review records a historical direct shipment
  with the actual departure date, an existing client identity/snapshot and any
  physical furniture quantities. It deliberately creates no order, address,
  driver, hold or stock allocation and does not decrement warehouse `STOCK`.
  Every missing cabin omitted from that shipment submission automatically
  becomes `WRITE_OFF`; after logistics has released predecessor rental leases,
  inventory asks maintenance to create the normal `PENDING_APPROVAL` cabin
  write-off decision. Maintenance freezes the current cabin contents as
  `DISPOSE_WITH_CABIN`; only the existing global-administrator approval saga
  may make the asset terminal.
- Evidence:
  [`inventory-service.yaml`](../../contracts/openapi/inventory-service.yaml),
  [`logistics-service.yaml`](../../contracts/openapi/logistics-service.yaml),
  [`maintenance-service.yaml`](../../contracts/openapi/maintenance-service.yaml),
  [`InventoryCabinDispositionService.java`](../../services/inventory-service/src/main/java/dev/buhanzaz/rwms/inventory/service/InventoryCabinDispositionService.java),
  [`InventoryOutcomeService.java`](../../services/logistics-service/src/main/java/dev/buhanzaz/rwms/logistics/inventory/service/InventoryOutcomeService.java),
  and [`PropertyDispositionCreationUseCases.java`](../../services/maintenance-service/src/main/java/dev/buhanzaz/rwms/maintenance/disposition/application/PropertyDispositionCreationUseCases.java).
- Smallest decision needed: none.
- Resolution and date: direct historical shipment and ordinary pending
  write-off approval were confirmed by the user on 2026-08-21.

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

## Stable Identity For Canonical Repair Phases

- Status: `Open`
- Affected owner and consumers: maintenance-service, task-board-service, repair
  catalogue administrators, panel, ManagerApp and WorkerApp.
- Requested behavior: the six canonical ordinary repair phases must retain
  their order if a physical queue is renamed or localized.
- Conflicting contract or invariant: both owners currently recognize a phase
  by the normalized Russian queue display name. Physical `queueId` correctly
  owns stage grouping, but it does not declare whether that queue is SES,
  welding, exterior, interior, electrical or plumbing. Silently replacing the
  names with locally invented IDs would change catalogue and route meaning.
- Evidence:
  [`RepairPhaseSequence.java`](../../services/maintenance-service/src/main/java/dev/buhanzaz/rwms/maintenance/service/RepairPhaseSequence.java),
  [`RepairRoutePhaseOrder.java`](../../services/task-board-service/src/main/java/dev/buhanzaz/rwms/taskboard/service/RepairRoutePhaseOrder.java), and
  [`task-board-service.yaml`](../../contracts/openapi/task-board-service.yaml).
- Smallest decision needed: approve one stable phase code and its owning
  contract, including how existing physical queues receive that code and how
  unknown/custom queues are ordered.
- Resolution and date: none. Current exact names remain supported; this repair
  task does not rename phases or infer a new identity.

## Common Fence For Every Primary Repair Creator

- Status: `Open`
- Affected owner and consumers: maintenance-service direct repair, estimate,
  inventory publication, legacy inventory upsert and pre-start replacement
  workflows.
- Requested behavior: concurrent creators for one rental item must not produce
  unintended competing active PRIMARY repair chains.
- Conflicting contract or invariant: direct/direct creation is serialized on
  the maintenance-owned rental-item fact and exact retries replay, but other
  PRIMARY creators do not all use that fence. A global partial unique index is
  invalid because the durable pre-start replacement saga intentionally keeps a
  compensated predecessor and its successor non-terminal before finalizing the
  replacement.
- Evidence:
  [`MaintenanceRepairUseCases.java`](../../services/maintenance-service/src/main/java/dev/buhanzaz/rwms/maintenance/service/MaintenanceRepairUseCases.java),
  [`InventoryPublicationPrestartReplacementUseCases.java`](../../services/maintenance-service/src/main/java/dev/buhanzaz/rwms/maintenance/service/InventoryPublicationPrestartReplacementUseCases.java), and
  [`V49`](../../services/maintenance-service/src/main/resources/db/migration/V49__repair_acceptance_and_creation_indexes.sql).
- Smallest decision needed: approve one common creator-fence protocol and the
  explicit durable replacement exception, including treatment of already
  duplicated active roots.
- Resolution and date: none. V49 adds lookup indexes only and rewrites no data;
  the narrow direct/direct and lost-response paths are protected now.

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
  manager-download-site, driver-download-site, worker-download-site,
  downloads-site, release engineering and device administrators.
- Requested behavior: every published APK must be traceable to one reviewed
  source revision and expose a verifiable version, signature and checksum.
- Conflicting contract or invariant: the manager download site still has two
  manually kept page implementations and currently distributes a debug-signed
  test APK. Its active release now has a Manager-owned immutable route and one
  integrity record covering source, package, version, signer and SHA-256. The
  worker site separately publishes through its own validated manifest and
  Worker-owned route. DriverApp now has its own release record and immutable
  route; the static `/downloads/` page reads all three records rather than
  duplicating APKs or mutable metadata. There is still no documented
  production-signing authority/retention policy or organization-wide
  checksum-verification flow.
- Evidence:
  [`manager app build`](../../app/build.gradle.kts),
  [`manager release record`](../../manager-download-site/release.json),
  [`download preparation`](../../manager-download-site/scripts/prepare-sites-worker.mjs),
  [`Next page`](../../manager-download-site/app/page.jsx),
  [`static page`](../../manager-download-site/public/index.html),
  [`worker manifest`](../../worker-download-site/release.json),
  [`worker route`](../../worker-download-site/scripts/build-site.mjs),
  [`worker app build`](../../worker-app/app/build.gradle.kts),
  [`driver app build`](../../driver-app/app/build.gradle.kts),
  [`Driver release record`](../../driver-download-site/release.json), and
  [`aggregate Downloads builder`](../../downloads-site/scripts/build-site.mjs).
- Smallest decision needed: choose the supported page implementation, release
  signing authority and key custody, artifact repository/retention policy, and
  manifest fields used as the only rendered version source.
- Resolution and date: the WorkerApp publication portion was completed on
  2026-08-10. On 2026-08-13 the ManagerApp `0.3.37-debug` publication replaced
  its mutable external artifact link with a versioned Manager-owned asset and
  recorded the exact reviewed source, signing certificate and checksum. The
  overall question remains open for production signing, the duplicate Manager
  page implementation and the organization-wide release policy. On 2026-08-24
  a dedicated DriverApp release record and the aggregate `/downloads/` page
  resolved the missing DriverApp download surface without mixing application
  artefacts; all three current APKs are still test/distribution artefacts, not
  a production-signing policy.

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

## Address-Only Order Geocoding For Planning

- Status: `Open`
- Affected owner and consumers: logistics-service, standalone logistics
  simulator and logistics operators.
- Requested behavior: allow an RWMS order with an address but no coordinates to
  enter route planning, while coordinates remain authoritative when both are
  present.
- Conflicting contract or invariant: no approved private geocoder, data-sharing
  policy, result-confidence threshold or operator correction owner exists. The
  current simulator therefore returns `COORDINATES_REQUIRED` and never sends a
  customer address to an undeclared public service or fabricates coordinates.
- Evidence:
  [`sync orchestration`](../../logistics/backend/app/integrations/rwms_sync.py),
  [`planning contract`](../../contracts/openapi/logistics-service.yaml).
- Smallest decision needed: select and approve the geocoding provider/runtime
  boundary, address-data policy, confidence/error behavior and explicit manual
  correction workflow.
- Resolution and date: none. Coordinate-bearing orders synchronize now;
  address-only orders fail per order without partially corrupting the scenario.

## Capacity-Backed Client Dates

- Status: `Open`
- Affected owner and consumers: logistics-service, standalone planner, public
  client presentation and manager logistics panel.
- Requested behavior: show clients dates that are actually free for delivery,
  not only dates permitted by the five-day service policy.
- Conflicting contract or invariant: the current `requestableDeliveryDates`
  list is the warehouse-local `today+2...today+5` request horizon and explicitly
  makes no capacity promise. The standalone plan is independently versioned and
  does not reserve RWMS driver/vehicle capacity while a client is choosing.
- Evidence:
  [`ClientDeliveryDatePolicy.java`](../../services/logistics-service/src/main/java/dev/buhanzaz/rwms/logistics/inquiry/service/ClientDeliveryDatePolicy.java),
  [`public presentation`](../../panel/src/features/assistant/pages/public-client-presentation-page.tsx),
  [`simulator planner`](../../logistics/backend/app/planner/heuristic.py).
- Smallest decision needed: choose the capacity owner, freshness/version fence,
  reservation lifetime and fallback shown when the planner is unavailable.
- Resolution and date: none. The UI labels current values as requested dates,
  and final logistics scheduling remains authoritative.

## Planned Arrival Time In DriverApp

- Status: `Open`
- Affected owner and consumers: standalone planner, logistics-service and
  DriverApp.
- Requested behavior: show the driver an approximate arrival time before
  claiming an extra trip.
- Conflicting contract or invariant: current RWMS shipment/task contracts are
  intentionally date-only, while planned arrival/route segment timestamps live
  only in a versioned simulator plan. Applying a plan currently assigns date,
  driver and cabin IDs but does not establish which service owns ETA updates,
  delay propagation or stale-plan display.
- Evidence:
  [`planning assignment schema`](../../contracts/openapi/logistics-service.yaml),
  [`RouteStopRead`](../../logistics/backend/app/schemas/domain.py),
  [`DriverTripDetailsBlock`](../../driver-app/feature-task-detail/src/main/java/dev/buhanzaz/rwms/driver/feature/taskdetail/TaskDetailScreen.kt).
- Smallest decision needed: approve an additive planned-arrival snapshot owner,
  its timezone/version semantics and whether DriverApp labels it as the last
  applied estimate or obtains a live planner read.
- Resolution and date: none. DriverApp currently shows the assigned date and a
  prefilled Yandex Maps route, but does not invent an ETA.

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
