# Contract Map And Evolution Rules

Status: Confirmed repository layout as of 2026-08-12.

## Canonical Locations

- HTTP: [`contracts/openapi/`](../../contracts/openapi/)
- Events: [`contracts/events/`](../../contracts/events/)
- Technical conventions:
  [`contracts/technical-contracts.md`](../../contracts/technical-contracts.md)
- Framework-neutral Java records:
  [`platform/technical-contracts/`](../../platform/technical-contracts/)

The handwritten schema is authoritative. Generated source and client-specific
DTOs are derived boundary artifacts and never shared persistence/domain models.

## HTTP Families

Each current domain service has one OpenAPI family named
`contracts/openapi/<service>.yaml`. `api-gateway-service` routes those APIs and
does not redefine or aggregate their business schema.

Interactive clients use public `/auth/**` and `/api/**` gateway routes. Private
`/api/internal/**` operations are for authenticated service-to-service calls
only.

### Rental clients, interactive search and selection

[`logistics-service.yaml`](../../contracts/openapi/logistics-service.yaml)
defines logistics-owned rental clients, their orders and delivery facts. Client
creation requires an `Idempotency-Key`; type, name and phone are mandatory,
while a legal entity additionally requires a contact person. Client and order
commands carry separate additional-contact lists. Order create/update carries
only the client, primary phone and optional comment. `NORMAL` public
presentation confirmation carries one to five distinct same-day
`desiredDeliveryWindows[{startDate,endDate}]`, positive `rentalMonths`, required
`deliveryAddress`, an optional complete latitude/longitude pair and nullable
`additionalContacts` normalized to an empty list. `REPLACEMENT` rejects all of
those normal-only fields and preserves existing order facts. Document scheduling
uses only `scheduledDate`; public requests and projections have no scheduled
time. Historical database time columns remain physical compatibility data and
are not transport fields. Detail exposes current date facts plus logistics
document movement evidence rather than a browser-owned history.

The same contract defines search `resultMode`, characteristic and
type-dimension facets, paged facts-only catalog lookup, and owner-scoped
selection GET/PUT. Selection PUT carries the complete requested identifier set
plus warehouse and `Idempotency-Key`; an empty set is an explicit release.
`CreateRentalInquiryRequest` has an optional conversation and optional target
order, and the order-filtered inquiry collection rediscovers both manual and
assistant flows. A presentation declares `NORMAL` or `REPLACEMENT`, exact
required selection count, per-cabin current contents, live equipment
availability and maximum per cabin. Normal confirmation submits equipment
quantities per selected cabin; replacement confirmation forbids furniture edits
and preserves the mapped old-cabin requirements. The explicit direct command is
`POST /api/logistics/v1/orders/{orderId}/units/{unitId}/replace`.
[`assistant-service.yaml`](../../contracts/openapi/assistant-service.yaml)
exposes durable clarification questions, exact button-answer turns, structured
clarification/selection SSE events and an owner-checked selection proxy. It
does not redefine availability or hold state. Asset private contracts carry
the exact result mode/facets and remain the hold-effect boundary.

Evidence:
[`logistics OpenAPI`](../../contracts/openapi/logistics-service.yaml),
[`assistant OpenAPI`](../../contracts/openapi/assistant-service.yaml),
and
[`asset OpenAPI`](../../contracts/openapi/asset-service.yaml).

### Driver Task Audience And Document Tasks

The logistics HTTP contract carries an optional opaque `driverWorkerId` only
on shipment and return scheduling commands and document responses. Transfer
creation has no driver identity. Shipment, return and transfer scheduling use
only the actual `scheduledDate`. Driver tasks and board cards expose
`UNASSIGNED`, `ASSIGNED_DRIVER` and `WAREHOUSE_DRIVERS`: shipment/return may be
unassigned or assigned to exactly one driver, while every transfer is
warehouse-shared and cannot carry an identity snapshot. The warehouse-scoped
`shipment-task-settings` GET/PUT contract owns the 1–100 cabin cap and its
optimistic version. It applies to every newly grouped shipment, return and
transfer: one local driver task is sourced from the document and carries a
stable trip number, immutable cabin members and client/cabin task text. New
document-line tasks are forbidden; pre-start historical line work converges to
the group, while started history is retained. The public logistics move command
contains no audience or member replacement and moves the whole group under
task/entry fences. Structured board/detail responses include contacts, client
wishes, actual schedule and per-cabin desired/actual furniture readiness.

The task-board private registration and movement boundary accepts the same
audience for owner-driven reconciliation, but rejects a worker identity on a
shared audience. Public board-entry, registration and worker-feed responses
always contain the nullable audience property. Board-task V1 facts add optional
`driverAudience` and `plannedDriverWorkerId` only: retained events without
either field remain valid, and the display-name snapshot never crosses the
event boundary.

Evidence:
[`logistics-service.yaml`](../../contracts/openapi/logistics-service.yaml),
[`task-board-service.yaml`](../../contracts/openapi/task-board-service.yaml),
and
[`task-board-events-v1.schema.json`](../../contracts/events/task-board/task-board-events-v1.schema.json).

### Aggregate Ordinary Task Board

[`task-board-service.yaml`](../../contracts/openapi/task-board-service.yaml) defines one
warehouse-wide aggregate ordinary board with no date or shadow query dimensions. Each queue
definition/request, work queue and board column carries required `availableTaskLimit` (`1..50`,
default `6`). An ordinary column contains every real `IN_PROGRESS`/`PAUSED` card plus only that many
pinned/position-ordered real `WAITING` cards. Registration priority is already represented by the
persisted queue position and is not reapplied during reads. The same server-owned REAL window
fences `TAKE`; visible future `SHADOW` cards follow without consuming that limit and remain
non-actionable. Promotion restores the persisted shadow position ahead of a later unpinned REAL,
while pinning keeps that later REAL ahead. Public ordinary entry movement, date swapping,
maintenance daily-capacity scheduling and overdue rollover are absent. Dated driver and shipment
planning remains on the separate logistics surface.

For maintenance-owned ordinary routes
[`maintenance-service.yaml`](../../contracts/openapi/maintenance-service.yaml) and
[`task-board-service.yaml`](../../contracts/openapi/task-board-service.yaml) define the fixed phase
order SES, welding, exterior, interior, electrical and plumbing, regardless of submitted array
order. The first existing unfinished phase is `REAL`; every later phase is `SHADOW`, and absent or
completed phases do not block promotion. SES remains the exclusive holding phase rather than an extra
duplicated table even when an existing queue definition still carries the historical `REPAIR`
type. While a repair route has unfinished SES work, only that entry is real and visible; all
non-SES stages stay shadowed and are omitted from panel and WorkerApp feeds until the gate
completes. `WorkerFeedEntry`
now carries required `entryType` and `pinned`, so native clients render the same order and do not
infer actionability. The ordinary board contract does not absorb driver movement or external
capital-repair ownership.

The private source-owned pre-start replacement keeps optimistic concurrency for every changed
snapshot. Its one retry exception is an exact canonical fingerprint match across task metadata and
the complete route: that value-identical request returns the current registration without changing
versions, route-entry identities or events even when the supplied task version is stale. Any
differing snapshot still requires the current version and an entirely unstarted route. Maintenance
uses GET to observe that owner version before a presentation-only recovery PUT; ordinary repair-plan
commands continue using the version captured with their durable business intent. When task-board
returns replacement route-entry IDs, maintenance may rebind only already confirmed local
`QUEUED/GENERATED` stage mappings in that presentation recovery. Initial registration and every
started-stage mapping retain their immutable identity fence.

### Native Driver And Slinger Task Surfaces

[`task-board-service.yaml`](../../contracts/openapi/task-board-service.yaml)
defines two non-interchangeable native families. Canonical downstream
`/driver/v1/**` is exposed by the gateway as
`/api/task-board/driver/v1/**` and requires a WORKER JWT with
`driver.tasks`; `/worker/v1/**` becomes `/api/task-board/worker/v1/**` and
requires `worker.tasks`. Driver actions contain only `TAKE`, `PAUSE`, `RESUME`
and `COMPLETE`. Worker actions retain `JOIN`, but a logistics `TAKE` is rejected
on the worker surface and a logistics `JOIN` is rejected until the primary
driver has activated the task.

The driver feed contains primary bindings only. The worker feed contains
ordinary worker assignments plus only active/paused logistics entries for an
eligible secondary slinger; it never exposes waiting driver work. A slinger
`JOIN` supplies the current `workerGroupId`, allowing task-board to pause the
whole previous group entry and resume it after the shared task closes. Every
configured logistics secondary is optional: a driver may complete before a
slinger accepts, while a joined slinger may also complete. The visible WorkerApp
verb `Взять задание` still sends `JOIN` with the current `workerGroupId`.
`LOGISTICS_DRIVER` completion requires at least one result photo linked to a
`READY` media generation from either active participant and closes the same
entry for both.

The same Worker detail/action schemas carry one additive server-owned execution semantic for
`MAINTENANCE_REPAIR`. Consecutive route entries with the same physical queue are presented as one
package: detail arrays cover the complete segment, and the displayed duration/timer cover its
unfinished members. The representative entry ID and version remain the command fence. One COMPLETE
atomically closes that entry and every later unfinished shadow member, while the existing
`QUEUE_ENTRY_COMPLETED` event is still emitted separately for every route entry so maintenance can
advance its one-to-one repair-stage mappings. No HTTP or event field was added, and every other
source remains entry-scoped.

Source-media order is also meaningful at this boundary. When an owning source has a selected cover,
the registration places that reference first and task-board preserves it in
`WorkerTaskDetail.sourceMedia`. `WorkerWork.sourceMediaIds` is the canonical per-work association;
WorkerApp removes those references from the general gallery and renders them inside the matching
work card. No object-storage coordinate or duplicate media owner is introduced.

Native device registration remains wire-compatible: omitted `targetKind`
means legacy `TOKEN`, while current clients send `FID` and put the Firebase
Installation ID in the existing `token` property. Task-board binds each
installation to WORKER or DRIVER and persists `TASK_JOIN_AVAILABLE` before
attempting at-least-once WorkerApp delivery. The event is an invalidation, not
an authorization grant or a complete projection.

The public logistics rich-detail operation
`/api/logistics/v1/driver-tasks/{taskId}` accepts a driver only when the token
has `driver.tasks`, the WORKER identity equals the planned assigned worker and
the task audience is `ASSIGNED_DRIVER`. Media accepts a WORKER upload/read
token with exactly one of `worker.tasks` and `driver.tasks`; all existing
worker, warehouse and owner proofs still apply. The additive optional
`readerWorkerIds` property in
[`task-board-events-v1.schema.json`](../../contracts/events/task-board/task-board-events-v1.schema.json)
separates task read visibility from `allowedWorkerIds` upload authority. A
consumer receiving an older proof derives its initial reader set from
`allowedWorkerIds`; the producer always emits both fields after the compatible
media consumer and V14 projection are installed.

Evidence:
[`task-board OpenAPI`](../../contracts/openapi/task-board-service.yaml),
[`logistics OpenAPI`](../../contracts/openapi/logistics-service.yaml),
[`media OpenAPI`](../../contracts/openapi/media-service.yaml),
[`task-board reader projection`](../../services/task-board-service/src/main/java/dev/buhanzaz/rwms/taskboard/service/TaskBoardEntryOwnerProofService.java),
[`maintenance package owner`](../../services/task-board-service/src/main/java/dev/buhanzaz/rwms/taskboard/service/MaintenanceTaskExecutionPackageService.java),
[`maintenance worker snapshot`](../../services/maintenance-service/src/main/java/dev/buhanzaz/rwms/maintenance/service/MaintenanceTaskBoardSupport.java),
[`WorkerApp task detail`](../../worker-app/feature-task-detail/src/main/java/dev/buhanzaz/rwms/worker/feature/taskdetail/TaskDetailScreen.kt),
[`media V14`](../../services/media-service/db/migration/V14__task_board_reader_audience.sql),
[`MobileTaskSurfacePolicy.java`](../../services/task-board-service/src/main/java/dev/buhanzaz/rwms/taskboard/service/MobileTaskSurfacePolicy.java),
[`LogisticsAuthorizer.java`](../../services/logistics-service/src/main/java/dev/buhanzaz/rwms/logistics/security/LogisticsAuthorizer.java),
and
[`media validator`](../../services/media-service/internal/auth/validator.go).

### Media Upload Session Recovery

The public create-upload command in
[`media-service.yaml`](../../contracts/openapi/media-service.yaml) treats an
exact idempotent replay as the same logical media creation. While the original
session is open it returns that session. If the session expired before content
was finalized, it may return a replacement session for the same media ID.
Completed content is immutable and cannot be reopened through this recovery
path. Active panel and Android consumers already use the session returned by
each create response, so the change is compatible with their durable retries.

### Media Image Bundles And Orientation

For a new still image, `media-service.yaml` accepts either the compatible
single-source shape or exactly three declared `SMALL`, `MEDIUM` and `LARGE`
WebP parts whose aggregate content length is at most one MiB. A bundle create
omits the legacy top-level content fields, returns a nullable source URL plus
one authenticated same-origin PUT URL per part, and finalizes with the exact
object version, ETag and checksum of every part. The logical bundle checksum is
the SHA-256 of the contract-defined ordered `rwms-image-variants-v1` manifest.
No object-store key, MinIO origin or credential crosses the boundary.

Android clients physically orient captured or gallery pixels before encoding
the three parts. Media-service verifies and stores those supplied bytes without
EXIF rotation, decoding or recompression. A legacy source image remains
compatible by aliasing its one pinned object as the logical image variants.
The read-only `rotationDegrees` response field remains only so older persisted
assets can still be displayed compatibly; it is not a command for new media.

Evidence: [`media-service.yaml`](../../contracts/openapi/media-service.yaml),
[`media upload API`](../../services/media-service/internal/api/server.go),
[`V16__client_image_variants.sql`](../../services/media-service/db/migration/V16__client_image_variants.sql),
[`ImageUploadBundleEncoder.kt`](../../app/src/main/java/dev/buhanzaz/rwms/manager/media/ImageUploadBundleEncoder.kt),
and
[`WorkerEvidenceBundlePreparer.kt`](../../worker-app/core-media/src/main/java/dev/buhanzaz/rwms/worker/core/media/WorkerEvidenceBundlePreparer.kt).

### Media Video Playback

The media OpenAPI accepts the declared MP4/WebM upload content types and
exposes `PLAYBACK` as the owner-scoped video read variant. A READY video has an
immutable original plus a generated MP4 playback object; image-only variants
remain invalid for video and a video remains invalid as an image cover. The
service contract exposes neither MinIO keys nor an ffmpeg command surface.

Evidence: [`media-service.yaml`](../../contracts/openapi/media-service.yaml),
[`video_transcoder.go`](../../services/media-service/internal/media/video_transcoder.go),
and [`V12__video_playback_variant.sql`](../../services/media-service/db/migration/V12__video_playback_variant.sql).

### Manual Capital-Repair Choice

Maintenance estimate and repair commands accept an optional
`forceCapitalRepair` boolean whose omitted value is `false`; an explicit JSON
`null` is rejected. Estimate, revision and repair responses, inventory frozen
and final plans always emit the boolean, as do newly produced maintenance
ESTIMATE/REPAIR v1 facts. Event consumers accept omission in historical v1
facts with the compatibility meaning `false`, while rejecting an explicit
non-boolean value. The additive field preserves the choice across inventory
publication and repair replacement; effective capital complexity is still
calculated by maintenance as explicit choice OR catalog-enforced capital work.
Inventory plan and maintenance freeze/snapshot schemas reject the combination
of `movementToRepair: true` and `forceCapitalRepair: true`. Each public
inventory frozen line also carries the nullable all-or-none routing queue ID,
name and type copied from its immutable maintenance source snapshot. These
fields let active clients reproduce the maintenance-owned one-line-to-one-stage
allocation without changing persistence ownership or inventing a route.

Evidence: [`maintenance-service.yaml`](../../contracts/openapi/maintenance-service.yaml),
[`inventory-service.yaml`](../../contracts/openapi/inventory-service.yaml),
[`maintenance-events.yaml`](../../contracts/events/maintenance-events.yaml),
and [`maintenance-events-v1.schema.json`](../../contracts/events/maintenance/maintenance-events-v1.schema.json).

### Media Processing Terminal DLT

The sanitized processing DLT contract in
[`media-processing-dlt-v1.schema.json`](../../contracts/events/media/media-processing-dlt-v1.schema.json)
includes `PROCESSING_ATTEMPT_EXHAUSTED` for an expired, already-fourth fenced
attempt that is terminalized without another processor call. This is a
compatible enum addition for the current repository: no active consumer outside
media-service was found. The payload remains hash-only and may not contain the
source record, object key, owner data or free-form dependency error. Any
out-of-repository strict enum consumer must accept the added value before a
runtime rollout.

### Inventory Session Refresh And Empty Preflight

[`inventory-service.yaml`](../../contracts/openapi/inventory-service.yaml) defines the additive
MANAGE command `POST /api/inventory/v1/sessions/{inventoryId}/refresh`. Its required body carries
`expectedSessionRevision`, and `Idempotency-Key` identifies an exact replay. A successful response
is the authoritative session detail after inventory-service has reconciled a fresh asset capture;
the command preserves stored finding/inspection evidence while invalidating derived furniture and
final-plan state. Capture departure applies only to automatic `EXPECTED` population; explicit
`ADDED_NEW`, `ADDED_USED` and `UNEXPECTED_EXISTING` observations remain in the result.

[`inventory-events.yaml`](../../contracts/events/inventory-events.yaml) and the canonical
[`inventory finding schema`](../../contracts/events/inventory/inventory-events-v1.schema.json) add
the finding lifecycle markers `inventory.finding.membership-departed.v1`,
`inventory.finding.membership-refreshed.v1` and
`inventory.finding.membership-restored.v1`, plus compatible optional `membershipActive` on the
shared v1 finding payload. New lifecycle markers require the matching boolean in active consumers;
historical added/inspection records may omit it. The corresponding
[`media consumer contract`](../../contracts/events/media-events.yaml) checkpoints the markers
without changing owner proof, while
[`dossier-consumers.yaml`](../../contracts/events/dossier-consumers.yaml) maps them to no activity.
Media migration
[`V15__inventory_finding_membership_markers.sql`](../../services/media-service/db/migration/V15__inventory_finding_membership_markers.sql)
widens only the existing inbox event/owner-revision check; it neither rewrites inbox evidence nor
changes media rows or objects.

The private inventory-publication preflight in
[`maintenance-service.yaml`](../../contracts/openapi/maintenance-service.yaml) keeps `findings`
required and bounded to 5000, but permits an empty list. Empty means the valid final plan has no
maintenance candidates; maintenance returns the same inventory/plan identity with an empty result
instead of rejecting the request.

The same boundary fingerprints and stores the exact raw versioned snapshot. For schema version 1
only, maintenance recognizes the historical producer shape where the identical non-empty aggregate
media list was copied onto every plan line. It removes those copies only from the executable
in-memory representation after fingerprint verification, preserving the aggregate evidence and raw
source. No other version-1 shape is normalized, and version 2 retains strict current validation.
Inventory's read-only call to this preflight has one same-request retry only for transport failures
or HTTP `502`, `503` and `504`; semantic, authorization, other server and malformed-response
failures are not retried.

### Inventory Cabin Disposition Review

[`inventory-service.yaml`](../../contracts/openapi/inventory-service.yaml) defines a MANAGE-only,
version-fenced review before furniture reconciliation. The server derives immutable return and
missing candidate sets from current finding revisions. `RETURNS` requires the complete set of
physically found former-rental cabins with actual return date and existing client identity;
`SHIPMENTS` accepts only missing cabins confirmed as departed, with actual date, client snapshot
and zero or more unique catalog-versioned furniture quantities. Any missing candidate omitted from
that second command becomes `WRITE_OFF`; an empty shipment list is valid and means all missing
cabins. The completed decision is copied into each final-plan entry as `LOCAL`, `SHIPMENT` or
`WRITE_OFF`. Only `LOCAL` entries enter furniture review.

The private plan-wide operation in
[`logistics-service.yaml`](../../contracts/openapi/logistics-service.yaml) preserves those meanings.
A former-rental `LOCAL` row creates a terminal historical return without acceptance/estimate
creation. `SHIPMENT` creates a terminal driverless historical shipment whose line exposes nullable
`inventoryShipmentFurniture`; this frozen furniture never reserves or decrements `STOCK`.
`WRITE_OFF` records only a logistics release marker. The matching asset inventory outcome supports
`RENTED` plus required `shipmentContents` and exact-replaces the cabin contents without touching
stock.

Only after the exact logistics generation succeeds does inventory call
`POST /api/internal/maintenance/v1/inventory/cabin-write-offs`. The service-token-only command
creates or replays one ordinary `PENDING_APPROVAL` `WRITE_OFF` decision. Maintenance loads the
current asset snapshot and freezes its contents as `DISPOSE_WITH_CABIN`; the existing administrator
approval/recovery API remains the only terminal path.

### Authoritative Completed-Inventory Outcome Recovery

[`inventory-service.yaml`](../../contracts/openapi/inventory-service.yaml) defines the public
MANAGE command `POST /api/inventory/v1/sessions/{inventoryId}/outcome/recalculate`. Its request
requires the session revision plus the completed final-plan version and SHA-256, and its successful
`202` response reports the authoritative session/plan fences, furniture reconciliation state and
created/requeued publication counts. It intentionally does not embed the complete publication
batch; clients invalidate and reread that separate projection. The command only schedules
owner-local durable work; it does not report a downstream status or repair as applied before the
existing publication records settle. If the server restores an explicit finding
omitted by the obsolete membership rule, the same response identifies the strictly newer corrected
final-plan version/SHA; clients must accept that authoritative successor while rejecting a lower
version or same-version hash drift.

The private asset command
`PUT /api/internal/asset/v1/inventory/outcomes/{inventoryId}/findings/{findingId}` in
[`asset-service.yaml`](../../contracts/openapi/asset-service.yaml) accepts immutable completed-plan
identity and exactly one desired `FREE`, `REPAIR` or `CAPITAL_REPAIR` status from the
inventory-service credential. Its response identifies released bindings and the effective asset
version. The reviewed furniture snapshot SHA remains immutable source evidence, but asset-service
does not compare it with a later full live hash after that same authoritative status/version update;
warehouse, catalog and terminal-state guards still fail closed.

The private media command
`PUT /api/internal/media/v1/inventory/outcomes/{inventoryId}/findings/{findingId}/cabin-photos` in
[`media-service.yaml`](../../contracts/openapi/media-service.yaml) accepts exact `READY IMAGE`
references and their generations under the `media.inventory` service credential. It makes one
deterministic inventory folder current while retaining older gallery associations and objects. The
private command may reuse the completed finding's retained checkpointed identity without reopening
public finding reads or uploads; a later unresolved version gap is tolerated only when it begins
strictly after that proof checkpoint. Current-folder cover and logistics projections put the
explicit cover first and keep the remaining images in stable association order.

The plan-wide logistics command
`PUT /api/internal/logistics/v1/inventory/outcomes/{inventoryId}` in
[`logistics-service.yaml`](../../contracts/openapi/logistics-service.yaml) carries every final-plan
asset and desired status under one immutable plan identity and the exact `logistics.inventory`
service credential. A complete selected non-terminal document/order can be superseded, but a
document with an unrelated active line rejects the whole batch before local or remote effects. Its
outcomes carry exact `LOCAL`, `SHIPMENT` or `WRITE_OFF` disposition evidence; the corresponding
desired statuses are `FREE`/`REPAIR`/`CAPITAL_REPAIR`, `RENTED` and `WRITE_OFF_PENDING`
respectively, with the last value remaining logistics evidence rather than an asset status.
Inventory freezes this command once per final-plan version and reapplication generation in
[`InventoryPlanLogisticsEffect`](../../services/inventory-service/src/main/java/dev/buhanzaz/rwms/inventory/domain/InventoryPlanLogisticsEffect.java).
Per-finding publication retries consume the shared result and never resend the full plan after it
has succeeded.

The private maintenance work request in
[`maintenance-service.yaml`](../../contracts/openapi/maintenance-service.yaml) requires both the
inventory completion timestamp and the effective `authoritativeAssetVersion`; the frozen manual
capital choice remains part of the new repair plan. A `FREE` finding instead uses
`PUT /api/internal/maintenance/v1/inventory/outcomes/{inventoryId}/findings/{findingId}/no-work`,
which supersedes non-terminal maintenance work and its external effects without creating a new
repair. These are separate owner commands with permanent idempotency receipts and per-owner latest
inventory watermarks, not one distributed transaction. No boundary transfers media bytes, object
keys or permission to delete inventory photos.

For the same inventory/finding and completion instant, these existing private contracts permit a
strictly higher corrected final-plan version only under owner-local compatibility checks. Asset and
logistics advance their ordering fence; maintenance requires the previous outcome to be applied,
adopts the current repair when the executable finding content is equivalent, and otherwise runs its
existing supersession workflow so changed work/no-work, priority, movement or capital routing leaves
one current route. It never rewrites terminal accepted or written-off work. Media requires the exact
previous photo set and retains the stable folder. The same private completed-outcome operation may
reuse a retained checkpointed finding proof across a strictly later `VERSION_GAP`, but it neither
resolves that quarantine nor changes public media authorization; every other owner/quarantine check
still fails closed. This is a compatible use of the existing
version/hash and payload fields, not a new transport shape. Lower versions, equal-version drift,
another finding/inventory or a changed completion instant remain `409`.

The reapplication generation is inventory-service persistence, not a public
request field. Automatic retries retain its exact downstream idempotency keys;
the public history command advances one generation for the whole frozen plan.
A same-source asset reassertion does not release the active operation lease,
because that lease can already be owned by the exact maintenance successor.
The maintenance and logistics commands remain responsible for releasing an
unrelated predecessor lease with its recorded owner and fencing token.

### Inventory-Created Assets

The private source-asset operation in
[`asset-service.yaml`](../../contracts/openapi/asset-service.yaml) accepts one
of two explicit representations: canonical catalogue UUIDs, or the supported
manager-client name fields. They cannot be mixed. Name resolution belongs to
asset-service and is completed against active catalogue data before any
permanent source identity, cabin-number claim or rental item is stored. The
stored source fingerprint uses the resolved UUID selection, so an equivalent
retry in the other representation is the same idempotent command.

### Driver-Board Repair Places

The public `DriverBoard` contract in
[`logistics-service.yaml`](../../contracts/openapi/logistics-service.yaml)
separates physical repair-place occupancy (`occupiedRepairPlaceCount`) from
the capacity-aware placement value (`usedRepairPlaceCount`). Its `repairPlaces`
cards are read-only progress facts only for cabins physically in the repair
zone (`OCCUPIED` or `READY_TO_RELEASE`); a `RESERVED` delivery remains in the
driver queue. Each card carries its allocation state, priority, and an optional
earliest unfinished repair stage with its state.

`maintenance-service` is the sole producer of allocation and stage truth via
its private logistics projection in
[`maintenance-service.yaml`](../../contracts/openapi/maintenance-service.yaml).
The panel must not call that private route or infer status from browser state;
`logistics-service` validates and republishes the needed fields through the
public driver-board response.

### Estimate Creation Window

[`maintenance-service.yaml`](../../contracts/openapi/maintenance-service.yaml) exposes
warehouse-MANAGE GET/PUT settings at
`/api/maintenance/v1/settings/estimate-creation-window/{warehouseId}`. The version-fenced `days`
value is bounded to `1..3650`; a missing row is the non-persisted version-0 default of seven days.
For manual creation, maintenance obtains the latest physical return arrival from the private
logistics boundary. Both manual and logistics-origin automatic estimate creation evaluate an
inclusive warehouse-local deadline; expiration returns the exact Problem Details code
`ESTIMATE_CREATION_WINDOW_EXPIRED`. The contract does not remove or redirect the separate direct
repair operation.

### Return Estimate Sources And Legacy Furniture

[`logistics-service.yaml`](../../contracts/openapi/logistics-service.yaml)
defines `POST /returns/{documentId}/start-estimates`. Its request contains
only immutable inspection-photo references for every return line: it does not
select shortages or furniture. Logistics settles each line and calls the
private maintenance `estimate-source` boundary with the permanent
`returnId:lineId` identity. Maintenance creates exactly one empty `DRAFT`
estimate for that source, and its public `GET /estimates/return-sources` read
returns the separate estimate IDs to panel and Android clients.

`CompleteEstimateRequest.allowUnaccountedFurniture` in
[`maintenance-service.yaml`](../../contracts/openapi/maintenance-service.yaml)
is an explicit confirmation. It is accepted only when the canonical
cabin contents are empty; otherwise the server returns
`MAINTENANCE_UNACCOUNTED_FURNITURE_CONFIRMATION_REQUIRED` or fails closed.
The accepted legacy path creates separately approved `UNACCOUNTED` property
decisions with `NOT_REQUIRED` asset effect, so it never decrements a warehouse
additional-equipment balance.

### Property Disposition And Asset Effects

The public write-off/loss decision boundary belongs to
[`maintenance-service.yaml`](../../contracts/openapi/maintenance-service.yaml).
One list row represents one decision and root asset; repair/rework nodes are
detail history, not duplicate decisions. Warehouse managers and global
administrators may propose a mandatory-reason decision, while only
`WMS_ADMIN` or `SYSTEM_ADMIN` may approve, reject or recover it. A cabin with
non-zero contents must carry an exact versioned per-line plan; an empty cabin
must carry no contents plan. Selected positive quantities become a
logistics-owned furniture movement, and every unselected quantity is part of
the terminal asset effect.

[`asset-service.yaml`](../../contracts/openapi/asset-service.yaml) exposes only
the narrow maintenance prepare/apply effects. It owns balances, terminal
movement and permanent furniture custody; it never accepts the business
decision itself. Inventory shortage publication creates an idempotent
maintenance `LOSS` proposal and does not directly mutate a terminal balance.
Decision, effect and recovery commands are version/idempotency fenced and
return their honest pending, effective or quarantined state.

### Warehouse Lifecycle And Time

[`warehouse-service.yaml`](../../contracts/openapi/warehouse-service.yaml)
defines directional admission, exact-version readiness confirmation, durable
operation marks and an as-of timezone read. Incoming work is admitted only for
`ACTIVE`; existing outgoing work may drain in `DRAINING`; no new owner-local
blocker may commit behind a readiness fence. A timezone correction is immediate
only before the first operation. Once used, a change has an `effectiveFrom` and
historical operations keep the zone effective at their own timestamp.

Actual inter-warehouse movement is represented by
[`logistics-service.yaml`](../../contracts/openapi/logistics-service.yaml).
When an arrived line continues an active repair, logistics reads the exact
post-`TRANSFER_ARRIVE` asset version from its released guard, includes it in the
durable maintenance-attempt fingerprint and sends it as required
`rentalItemVersion` in `CompleteTransferRepairRequest`. Maintenance must use
that original value for replayed lease/status commands; a fresh read cannot
silently replace the version fence.
The separate asset administrative-correction command is administrator-only,
requires `expectedVersion`, reason and HTTPS evidence, rejects active
workflow/reservation/lease blockers, and records immutable correction evidence.

## Event Families

Domain event indexes and JSON/YAML schemas live under `contracts/events/`.
Events describe committed facts. The technical envelope, delivery and
event-store conventions live under `contracts/events/technical/`.

Kafka is at-least-once transport. Producer outbox, consumer inbox,
aggregate-version handling and sanitized DLT behavior remain service-owned
implementations constrained by these contracts.

## Canonical Integrity Gate

The root `verifyCanonicalContracts` task is the deterministic repository gate
for canonical transport sources. It parses every YAML and JSON document under
`contracts/`, resolves only bounded local file references and JSON Pointers,
compiles every declared JSON Schema draft, and rejects missing, escaping or
remote references. Within each owning document or catalog, it also requires
unique non-blank OpenAPI `operationId` values and unique lowercase namespaced
AsyncAPI message names ending in `.vN`.

Schema identity is path-bound. Schemas under `contracts/events/technical/`
retain the established
`https://rwms.example/contracts/events/technical/` namespace; every other
event schema uses `https://rwms.local/contracts/events/`. In both cases the
complete `$id` must equal the schema's relative repository path, so a valid
host with a wrong path is still rejected. This narrow legacy namespace rule
does not authorize another host or a new exception.

Run the gate from the repository root:

```bash
bash ./gradlew verifyCanonicalContracts
```

Evidence:
[`root task`](../../build.gradle.kts),
[`architecture test task`](../../platform/architecture-tests/build.gradle.kts),
[`integrity coordinator`](../../platform/architecture-tests/src/test/java/dev/buhanzaz/rwms/contracts/CanonicalContractIntegrityGate.java),
and
[`negative and checked-in fixtures`](../../platform/architecture-tests/src/test/java/dev/buhanzaz/rwms/contracts/CanonicalContractIntegrityTest.java).

## Implementation Parity Gates

Canonical structure is supplemented by executable implementation inventories.
Inventory-service and logistics-service parse their owning OpenAPI documents,
compare every method/path pair with merged Spring controller mappings, and send
unauthenticated probes through the real owner security filter chain. Inventory
contains 28 bearer operations. Logistics route parity derives its complete
operation count from the current canonical file and permits exactly four
anonymous client-presentation operations; every other route is bearer
protected.

The gateway inventory resolves every canonical domain-public operation through
the current functional routers and the real edge security chain. It covers 274
domain-public operations, proves the four logistics presentation operations are
the only anonymous domain routes, and proves canonical internal operations and
reserved internal/private aliases are not public gateway routes. Auth callbacks
and media health remain explicit owner-specific exclusions rather than hidden
route gaps. The completed-inventory recalculation POST has one dedicated 60-second
transport timeout and is excluded from the generic inventory route; this changes
neither its canonical path/security nor inventory-service ownership.

Manager, worker and driver Retrofit gates inventory all declared client
methods, eagerly validate their converters and exercise representative
encode/decode fixtures for every consumed JSON root family. Manager has 62
methods (60 fixed public gateway paths and two media-only guarded `@Url`
methods); worker has 11 (nine fixed and two guarded media methods); driver has
12 (ten fixed and two guarded media methods). The worker and driver action
serializers emit all seven required contract properties, including explicit
`null` for required nullable `workerGroupId` and `evidenceId`, without enabling
global explicit-null serialization.

Evidence:
[`inventory parity`](../../services/inventory-service/src/test/java/dev/buhanzaz/rwms/inventory/config/InventoryRouteSecurityParityTest.java),
[`logistics parity`](../../services/logistics-service/src/test/java/dev/buhanzaz/rwms/logistics/config/LogisticsRouteSecurityParityTest.java),
[`gateway parity`](../../services/api-gateway-service/src/test/java/dev/buhanzaz/rwms/gateway/config/GatewayRouteSecurityParityTest.java),
[`manager boundary`](../../app/src/test/java/dev/buhanzaz/rwms/manager/network/RwmsApiContractBoundaryTest.kt),
[`worker boundary`](../../worker-app/core-network/src/test/java/dev/buhanzaz/rwms/worker/core/network/WorkerGatewayApiContractBoundaryTest.kt),
[`driver boundary`](../../driver-app/core-network/src/test/java/dev/buhanzaz/rwms/driver/core/network/DriverGatewayApiContractBoundaryTest.kt),
[`worker action serializer`](../../worker-app/core-network/src/main/java/dev/buhanzaz/rwms/worker/core/network/WorkerActionRequestDtoSerializer.kt),
and
[`driver action serializer`](../../driver-app/core-network/src/main/java/dev/buhanzaz/rwms/driver/core/network/DriverActionRequestDtoSerializer.kt).

## Safe Change Procedure

1. Identify the owning service and canonical schema.
2. Find every producer and active panel, Android, service or external consumer.
3. Compare the requested meaning with existing identities, statuses,
   nullability, units, money/time rules, authorization, errors and concurrency.
4. Decide whether the change is compatible. New optional syntax is not safe
   until all consumers are proven tolerant.
5. If meaning or ownership must break and the user did not decide it, stop and
   ask a focused question.
6. Change the canonical schema, owner and consumers in one task.
7. Run `verifyCanonicalContracts`, then focused producer/consumer compatibility
   tests including relevant failure behavior.
8. Remove the obsolete version/path once no supported consumer remains.
9. Update this knowledge base and append the durable change to the log.

## Contract Review Checklist

- Owner and audience are explicit.
- Authentication, scopes and warehouse isolation are explicit.
- IDs are opaque and do not imply cross-database relationships.
- Mutable commands define optimistic concurrency and idempotency.
- Errors use the shared Problem Details convention.
- Pagination, filtering, units, timezone and date/time semantics are explicit.
- Event payloads contain facts, not remote commands or secrets.
- Producer and every active consumer agree on versions and enum handling.
- No UI mock, local-storage shape or old database row was promoted into a
  contract without an explicit product decision.
