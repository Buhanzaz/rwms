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

### Customer registration and rental booking

[`auth-service.yaml`](../../contracts/openapi/auth-service.yaml) defines the
anonymous GET `/api/auth/csrf` bootstrap and CSRF-protected POST
`/api/customer/v1/registrations`. Through the gateway they are delegated under
`/auth/**`; registration creates only `USER/CUSTOMER` and the managed public
client `rwms-customer-android` exposes exact `customer.rental` with S256 PKCE.
`CUSTOMER` is an additive auth-event role, not a manager/worker capability.
The gateway forwards the cookie/header pair, strips untrusted forwarding
headers and derives an immediate-peer address, but stores no CSRF or rate-limit
state. Auth-service validates CSRF and atomically consumes durable per-source
and global registration budgets before password hashing. Exhaustion is a
contracted `429` Problem Details response with `Retry-After`; production ingress
throttling remains an independent defence-in-depth layer.

[`logistics-service.yaml`](../../contracts/openapi/logistics-service.yaml)
defines the public `/api/logistics/customer/v1/**` family for profile create/read/version-fenced
contact updates and avatar prepare/bind,
warehouses, inquiry/cart, facets, free cabin cards/photos, complete selection,
positive-stock equipment, complete per-cabin initial rental terms, delivery
offers/hold, checkout, booking history, arrived-cabin acceptance and problem
reports.
For an exact owned pre-start booking the same family also exposes version-fenced,
idempotent cancellation, booking-scoped replacement-slot search and atomic
reschedule. Cancellation may return a durable pending reconciliation state;
reschedule never releases the old confirmed slot unless the replacement slot,
order date and capacity swap all commit together.
Mutable creates and hold conversion use `Idempotency-Key`; profile, cart, asset
and delivery-slot versions are explicit fences. Profile kind and auth/client binding are immutable;
facets, selection and cart reads explicitly declare `409` because a terminal
`BOOKED` inquiry returns `INQUIRY_ARCHIVED`, while the inquiry identity remains
readable so the client can preserve its booking and open a separate cart;
avatar prepare fixes one warehouse as media authorization scope and avatar bind accepts only an
exact media-service-validated current `READY` generation. Each warehouse response carries
the logistics-owned depot latitude/longitude used by both the client map and
slot routing. The transport keeps `false` search-time attestations compatible with provisional-date
clients; the current CustomerApp collects private-site trailer access and possible failed-trip
charge acknowledgements in a modal before search. For two or more cabins the search and hold also
carry `siteCabinCapacity=1|2`; one requires sequential solo-truck visits and two permits a trailer
configuration without overriding road safety. The hold command rechecks and must supply any
attestation not already attached to an offer. Held slot responses freeze both true facts,
successful public-road truck routing, site capacity, independently classified delivery price and
the exact applicable height/width/length/weight/axle profile together with date, required
`kind=FIXED_WINDOW|DURING_DAY`, non-null display/hold bounds, informational `travelZoneHours`,
capacity remaining and expiry. Each date exposes the three fixed local windows
`09:00-12:00`, `12:00-15:00`, `15:00-18:00` plus one `DURING_DAY` choice spanning the configured
delivery day. The
remaining capacity is computed from the whole-day multi-point schedule over
warehouse-capacity period shifts, not per-window arithmetic; an
empty shift set or missing truck route fails closed. Ordinary price comes from the warehouse's
ordered hourly isochrone tariff list. It starts at 60 minutes, advances in contiguous
60-minute steps, contains at most twelve entries and uses the first entry that covers exact
one-way Valhalla travel time. The farthest configured entry is a hard delivery-acceptance
boundary; no polygon or straight-line check replaces exact truck routing.
Arrival requires the exact shipment document
line/member's `COMPLETED` grouped driver task. Acceptance stores one bounded
drawn signature, while a problem report can reference at most 20 exact READY
media generations for that shipment line. Only the exact
CUSTOMER/client/scope token is accepted.

[`media-service.yaml`](../../contracts/openapi/media-service.yaml) exposes the
ordinary upload/session/finalize/variant operations to that same exact
CustomerApp token only for `LOGISTICS_SHIPMENT/SHIPMENT` or
`LOGISTICS_CUSTOMER_PROFILE/PROFILE_AVATAR` owners whose logistics proof is bound to the token
subject. Profile media is image-only and its non-structured proof/validation carries the profile
UUID, warehouse, context and exact subject. Manager/worker profile access and another customer
subject fail closed; existing non-customer owner proofs remain unchanged.

[`asset-service.yaml`](../../contracts/openapi/asset-service.yaml) keeps the
customer cabin/equipment projection private to logistics. It exposes only
`FREE` or same-owner-held cabins and allowlisted card/photo facts; asset-service
still owns availability, holds, physical contents and equipment balance.
The authenticated `/api/asset/v1/rental-item-creation-intents` family separately
owns interactive cabin creation with mandatory photos. Create fixes an ordered
manifest, server folder/command identities and an availability hold; list/get
resume only authorized warehouse intents, while version-fenced complete and
abandon are idempotent commands. A browser cannot mark an intent complete from
local previews: asset-service verifies the exact current READY media generation.

Evidence:
[`registration owner`](../../services/auth-service/src/main/java/dev/buhanzaz/rwms/auth/service/CustomerRegistrationService.java),
[`customer API owner`](../../services/logistics-service/src/main/java/dev/buhanzaz/rwms/logistics/customer/api/CustomerController.java),
[`customer booking lifecycle owner`](../../services/logistics-service/src/main/java/dev/buhanzaz/rwms/logistics/customer/service/CustomerBookingLifecycleService.java),
[`customer profile owner`](../../services/logistics-service/src/main/java/dev/buhanzaz/rwms/logistics/customer/service/CustomerProfileService.java),
and
[`customer asset projection`](../../services/asset-service/src/main/java/dev/buhanzaz/rwms/asset/api/LogisticsAssetController.java),
[`creation intent owner`](../../services/asset-service/src/main/java/dev/buhanzaz/rwms/asset/service/RentalItemCreationIntentService.java).

### Rental clients, interactive search and selection

[`logistics-service.yaml`](../../contracts/openapi/logistics-service.yaml)
defines logistics-owned rental clients, their orders and delivery facts. Client
creation requires an `Idempotency-Key`; type, name and phone are mandatory,
while a legal entity additionally requires a contact person. Client and order
commands carry separate additional-contact lists. Order create/update carries
only the client, primary phone and optional comment. `NORMAL` public
presentation confirmation carries one to four distinct same-day
`desiredDeliveryWindows[{startDate,endDate}]`, positive `rentalMonths`, required
`deliveryAddress`, an optional complete latitude/longitude pair and nullable
`additionalContacts` normalized to an empty list. The corresponding public
presentation contains required `requestableDeliveryDates`: `NORMAL` advertises
the four warehouse-local dates `today+2` through `today+5`, and confirmation
accepts at most four dates only from that current set. The list is requestable
preference policy, not capacity availability. `REPLACEMENT` exposes an empty
list, rejects all normal-only fields and preserves existing order facts.
Ordinary manager/presentation document scheduling uses only `scheduledDate`;
the dedicated CustomerApp family above is the one public source of a confirmed
exact delivery time. Historical database time columns remain physical compatibility data and
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
clarification/selection SSE events and a signed-manager/owner-checked selection proxy. It
does not redefine availability or hold state. Asset private contracts carry
the exact result mode/facets and remain the hold-effect boundary.

The logistics contract also owns user-entered historical rental movements.
Create accepts one visible client, cabin/version fence and non-future
warehouse-local date. A shipment accepts either one complete driver
snapshot/worker-ID pair or a null pair meaning unknown; a return rejects driver
data, and neither operation creates route/driver work. An already `RENTED`,
repair-free cabin is a provenance-recovery branch: the maintenance private
response proves `RENTED/ALREADY_RENTED`, and logistics completes the imported
shipment without repeating lease, hold or asset effects. The public
version-fenced PUT on that historical document changes only its client,
optional driver pair and physical date under a separate idempotency key. The
same document identity is retained. The existing shipment-cancel operation
also accepts an imported shipment only from `CONFLICT` or
`RECONCILIATION_REQUIRED` when durable attempts prove there is no completed or
unknown asset-confirm effect. An ambiguous lease acquisition is not guessed:
logistics reopens the same durable request with its original operation ID,
records the idempotent asset replay, then releases the exact `RELEASED` or
`EXPIRED` lease. Known capabilities are compensated and the open reconciliation
audit is resolved rather than deleted.

Evidence:
[`logistics OpenAPI`](../../contracts/openapi/logistics-service.yaml),
[`assistant OpenAPI`](../../contracts/openapi/assistant-service.yaml),
and
[`asset OpenAPI`](../../contracts/openapi/asset-service.yaml).

### Cabin photo presentations

[`media-service.yaml`](../../contracts/openapi/media-service.yaml) keeps the
authenticated CABIN asset list as the complete retained folder archive, while
the bounded cabin-cover projection exposes only the media-owned active/latest
folder. Its `photoCount`, cover and `SMALL` previews therefore form one batch;
passport and warehouse-card consumers must not reconstruct a mixed gallery
from archived assets. The private logistics snapshot exposes the full logical
active-folder `photoCount` separately from its at-most-100 READY references.
Logistics accepts an immutable presentation only when that count is within
one to 100 and equals the returned reference count, so processing gaps and
overflow fail closed rather than freeze a partial presentation.

[`asset-service.yaml`](../../contracts/openapi/asset-service.yaml) owns the
least-privilege private photo-presentation snapshot used by logistics. It carries the exact cabin
identity/version/warehouse fence, number, dimensions, finishing, category, ordered characteristic
names and nullable linoleum; status, rental type, passport JSON, comments, tags and equipment do
not cross that boundary.

[`logistics-service.yaml`](../../contracts/openapi/logistics-service.yaml) owns creation and public
resolution of the immutable non-expiring presentation. Logistics freezes the allowlisted asset
snapshot together with the ordered media references. Its anonymous response exposes only those
five display fields, cabin number, creation time and presentation-scoped image URLs; old rows use
null catalog values, an empty characteristic list and null linoleum. Warehouse, status, rental
type, actor, client, passport, versions and object-storage locators remain private.

The paired private media operation accepts only the `logistics-service` service
principal after logistics has validated signed presentation membership. It
matches the retained cabin/warehouse/media association and the exact frozen
generation/variant row, then streams that row's pinned MinIO object version.
This presentation-only read survives a later generation advance or soft delete;
ordinary gallery and new-presentation reads remain active-folder, `READY` and
current-generation only. A tuple mismatch is always an opaque `404` and no
object-storage locator crosses the boundary.

Evidence:
[`asset private controller`](../../services/asset-service/src/main/java/dev/buhanzaz/rwms/asset/api/LogisticsAssetController.java),
[`logistics presentation owner`](../../services/logistics-service/src/main/java/dev/buhanzaz/rwms/logistics/photo/CabinPhotoPresentationService.java),
and
[`V56 metadata snapshot`](../../services/logistics-service/src/main/resources/db/migration/V56__cabin_photo_presentation_metadata.sql).

### Driver Task Audience And Document Tasks

The logistics HTTP contract carries an optional opaque `driverWorkerId` only
on shipment/return scheduling commands and document responses; the transfer-plan contract keeps
its trip driver separately from any post-arrival resource-reposition intent. Shipment, return and transfer scheduling use
only the actual `scheduledDate`. Driver tasks and board cards expose
`UNASSIGNED`, `ASSIGNED_DRIVER` and `WAREHOUSE_DRIVERS`: shipment/return may be
unassigned or assigned to exactly one driver, while an unassigned transfer is
warehouse-shared and an assigned transfer is visible only to its trip driver. The warehouse-scoped
`shipment-task-settings` GET/PUT contract owns the 1–100 cabin cap and its
optimistic version. It applies to every newly grouped shipment, return and
transfer: one local driver task is sourced from the document and carries a
stable trip number, immutable cabin members and client/cabin task text. Transfer registration also
uses the existing route-step `works`, `materials` and `comments` snapshots, so native offline sync
does not need a second transport. Logistics persists their canonical JSON in its own driver-task
row and includes it in the task checksum; an exact retry is a no-op, while a changed pre-start
snapshot uses task-board's existing version-fenced full-task replacement before the normal move.
Historical rows decode the additive `{}` default as empty content. This persistence behavior is
defined by
[`DriverTaskWorkerContentCodec.java`](../../services/logistics-service/src/main/java/dev/buhanzaz/rwms/logistics/driver/service/DriverTaskWorkerContentCodec.java),
[`DocumentDriverTaskPlanner.java`](../../services/logistics-service/src/main/java/dev/buhanzaz/rwms/logistics/driver/service/DocumentDriverTaskPlanner.java),
and
[`V73__driver_task_worker_content.sql`](../../services/logistics-service/src/main/resources/db/migration/V73__driver_task_worker_content.sql),
without adding a second public or native contract. New
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

### Warehouse Logistics Planning Boundary

[`logistics-service.yaml`](../../contracts/openapi/logistics-service.yaml)
defines private planning operations that are not gateway routes. `GET
/api/internal/logistics/v1/planning/warehouses` returns active canonical
warehouse identity, display metadata, owner-held address, coordinates,
representative characteristic and timezone. The planner reconciles that list
by stable UUID; there is no public “connect warehouse” command. `GET
/api/internal/logistics/v1/planning/drivers?warehouseId=...` returns only active
task-board workers with an active primary qualification for that warehouse's
active logistics-driver queue. It exposes only `workerId` and `displayName`.
Both directories fail closed and never use a planner-local identity fallback.

`GET /api/internal/logistics/v1/planning/requests` returns the bounded
warehouse/date feed of SAVED order remainders. It carries order/version,
minimal address/coordinate facts, unshipped cabin IDs and accepted dates. A
confirmed fixed CustomerApp option carries its exact hard window; a confirmed
`DURING_DAY` option is exported as a soft date-only choice with null planning
bounds. Both carry `travelZoneHours` and site-derived `trailerAccessAllowed`.
Every request also carries required-but-nullable `deliveryPriceRubles` and
`priceIsochroneMinutes` fields from its confirmed logistics slot. The amount is
authoritative; the tier remains optional for older or specially priced slots.
A null amount means that no authoritative price has been calculated. Coordinates are
authoritative over address. `orderVersion` fences rental-order assignment;
`sourceRevision` fingerprints the complete exported planning snapshot, so a
changed payload under the same revision is a conflict.

`POST /api/internal/logistics/v1/planning/assignments` applies one exact
external plan/version with `Idempotency-Key`; the matching `GET` returns only
planner-created assignment audience, worker and task state. Applying rechecks
warehouse, order version, date, cabin membership and uniqueness, qualified
driver identity and the assignment-specific date fence. Only `RWMS` deliveries may enter
the command. The additive `assignmentType` defaults to `ROUTE_PLAN`, which keeps
the automatic today/tomorrow protection and reviewed shift-plan semantics.
`CONTRACTOR_HANDOFF` is an explicit dispatcher decision, requires one concrete
`ASSIGNED_DRIVER`, permits tomorrow and carries no required internal vehicle,
shift or cycle snapshot. `ASSIGNED_DRIVER` requires an exact worker UUID;
`WAREHOUSE_DRIVERS` deliberately has no worker UUID and exposes a future job to
the qualified pool. Manual/generated work and pickups stay local.

`GET /api/internal/logistics/v1/planning/vehicle-assignments` is the private
logistics-owned vehicle availability boundary. It requires the exact
`logistics-planner` service identity and a positive aware half-open window. The
response is the complete live ancestry chain for vehicles that ever touched the
requested warehouse: only `PLANNED`, `IN_TRANSIT` and `ACTIVE` rows are emitted,
while terminal history is used only to close ancestry. The standalone planner
must reject terminal rows, duplicate identities and contradictory chains; it
must not rewrite catalog ownership from this projection.

Public zero-cabin transfers use `POST /api/logistics/v1/transfers/{documentId}/depart`
and `/arrive` with `expectedVersion` and `Idempotency-Key`. These operations are
valid only for a confirmed plan with no physical lines and advance the same
driver, vehicle and loose-furniture workflow used around line-scoped cabin
commands.

The same apply request may add `driverShiftPlans`. Each item is unique by source-shift and
driver/work-date and carries source plan/version, warehouse, driver snapshot, vehicle, optional
trailer, trip count and exact `routeDistanceMeters` int64. Logistics registers every item first
through idempotent
`PUT /api/internal/task-board/v1/driver-shift-plans/{sourceShiftId}` using only
`task-board.driver-shifts.plan`; task-board creates no planner API and logistics creates no shift
table. The path owns `sourceShiftId`, and a changed replay under the same plan/fence is a conflict.

`PUT /api/internal/logistics/v1/planning/capacity-snapshots/{warehouseId}`
idempotently replaces one warehouse's active anonymous capacity projection.
Warehouse identity exists only in the URL. The body carries generated delivery
and pickup jobs, mandatory/trailer-access facts, period-shift windows and
vehicle capacity, plus the owning warehouse's complete ordered
`isochroneTariffs` list. Each tariff carries `travelMinutes` and `priceRubles`;
the list starts at 60 minutes, remains contiguous in one-hour steps and its
last item is the delivery boundary. Optional additive `priceZones` and
`restrictionZones` lists carry at most 500 `SPECIAL_PRICE` polygons and at most
500 combined `FORBIDDEN`/`NO_TRAILER` polygons. Older producers may omit both
lists and are interpreted as empty; a producer that sends them preserves the
source zone UUID, version and valid GeoJSON MultiPolygon geometry. Exceptional
policies can override an in-boundary price or tighten access but never extend
normal coverage. The snapshot carries no order, customer, cabin or driver
personal identity. Monotonic
`sourceGeneration` rejects an older unaccepted command, while an immutable
receipt replays the original result for an exact retry.
Every capacity `DELIVERY` job is mandatory by contract; optional capacity work
is represented only by `PICKUP`. A producer must normalize legacy local
delivery flags before publication instead of sending a structurally valid but
domain-invalid snapshot.

All private integration operations require subject/client `logistics-planner`, audience
`rwms-services` and sole scope `logistics.planning`; interactive and mixed-scope
tokens are rejected. The standalone operator API is warehouse-rooted and its
generated OpenAPI declares HTTP Bearer security. Operator calls accept only
signed `USER` tokens issued for `rwms-panel`, audience `rwms-services`, with
`rwms.read` and sufficient `warehouse_access` for every warehouse represented
by the resource or group plan. Health and generated API documentation remain
anonymous. Command request DTOs do not accept audit-user fields; the verified
subject supplies the server-side actor. The browser endpoints are:
`GET /api/warehouses/{warehouseId}/workspace` reads one exact persisted
warehouse-local date with bounded UUID-cursor pagination; a fenced server
worker, not the GET, owns 31-day directory/demand ingestion.
`/planning-days/{date}` and `/close` own day finalization, and
`/rwms/capacity` is an audited reconciliation endpoint. Plan apply/status
operations remain explicit recovery tools, not routine browser controls. Close
only freezes acceptance and rebuilds a final draft; it never publishes an
unconfirmed assignment. Apply requires the exact expected version of a
`CONFIRMED` plan and derives one stable idempotency key from that immutable
plan/version. Request create/update DTOs expose only client-settable planning
facts; lifecycle status is server-owned. `RoutePlanRead.supersedes_plan_id`
links a replacement to its archived predecessor, while the planner persistence
boundary enforces one non-archived revision per warehouse-local date.
Warehouse update owns the complete one-to-twelve-entry hourly isochrone tariff
list. The standalone API has no ordinary delivery-zone CRUD or zone-owned
request fields. Its warehouse-scoped `/policy-zones` resource owns only
versioned `SPECIAL_PRICE`, `FORBIDDEN` and `NO_TRAILER` exceptions: create is
idempotent, update/delete are version-fenced, and warehouse VIEW/EDIT grants are
checked at the server boundary. Calculated prices and policy provenance remain
stored on slot/request history.
Stable catalog creates require an actor-scoped `Idempotency-Key`; mutable
catalog commands carry `expectedVersion`. The persistence boundary retains
durable create receipts and serializes shift/shared-pool conflicts under
PostgreSQL advisory locks.

[`task-board-service.yaml`](../../contracts/openapi/task-board-service.yaml)
owns the public warehouse-scoped contractor catalog at
`/warehouses/{warehouseId}/logistics-drivers/contractors`. GET returns the
complete catalog, including inactive profiles; POST creates a reusable on-demand
contractor, PATCH replaces editable fields under `expectedVersion`, and DELETE
removes only an unused version-matched profile. The profile has no availability
dates and never claims a shift, moves warehouse ownership, reclassifies staff or
requires a vehicle/cycle model. The simulator uses this human-authorized public
boundary for profile management and its own server-side dispatch command to bind
an active profile to eligible work on the date selected in the header.

The generated standalone contract at [`logistics/backend/openapi.json`](../../logistics/backend/openapi.json)
also owns planning-day incident intake. Truck/trailer incidents accept explicit
`recovery_mode` (`AUTO` or backward-compatible default `MANUAL`); trailer failures
require `trailer_id`. AUTO uses the authenticated subject and the existing
proposal-apply saga, not a client-coordinated sequence. A retry returns durable
event/proposal state; a resource-capacity failure never implies a successful plan
or a customer-approved date change.

That generated standalone contract
owns `POST /api/warehouses/{warehouseId}/contractor-dispatches`. `AUTO` accepts no
request IDs and selects eligible unassigned work for that exact warehouse/date;
`MANUAL` requires a unique explicit request set and revalidates the same facts.
Candidate selection and generated demand stay local. Only real RWMS deliveries
cross the canonical logistics assignment boundary. The public request/response
shape did not change when the simulator added its internal durable handoff
command: uncertain or mixed upstream results return the safe
`CONTRACTOR_HANDOFF_PENDING` problem code while the same immutable command UUID
and payload remain the retry identity. The private task-board assignment contract
remains the sole effect boundary.

The canonical logistics contract additionally owns authenticated
`/api/logistics/v1/warehouses/{warehouseId}/contractor-route-shares` create and
version-fenced revoke plus the exact anonymous
`/api/logistics/public/v1/contractor-route-shares/{token}/**` read, action,
evidence and media surface. One capability binds one active contractor to 1–50
ordered exact `externalTaskId` values and exposes no general board or credential.
Every public read/action is no-store and re-proves the live task-board binding;
invalid, expired, revoked or reassigned links are indistinguishable 404s.
`START`/`COMPLETE` keep task-board's entry version and caller idempotency fences.
Anonymous evidence accepts only an exact JPEG/WebP body with stable evidence ID,
capture time, SHA-256 and bounded length; the gateway validates the public length
before its chunked proxy hop and overwrites the single private relay assertion,
while logistics still verifies actual bytes. Task-board's private contractor
execution operations remain the transition/evidence owner and media-service's
private contractor-task operations remain the byte/variant owner.

The test-only [`tools/driver-fixture-bridge`](../../tools/driver-fixture-bridge/README.md)
does not define a new transport contract. It consumes a confirmed generated-only
plan and uses the existing private task-board create/read/cancel operations with
stable external task identity. Its production guard, dry-run default and
pre-start cleanup fence are tooling constraints, not supported public API behavior.

The same standalone OpenAPI exposes server-keyed Yandex
suggestion/resolve/reverse operations for operator input and automatic
address-only canonical warehouse projection. This does not permit address-only customer-order
feed geocoding. A normal new slot identifies its selected hourly tier through
`price_isochrone_minutes` and leaves `priceZoneId` null. An in-boundary
`SPECIAL_PRICE` result instead returns the exact source zone UUID and a null
tier. Both values remain nullable for historical compatibility.

The standalone transfer dialog does not add a FastAPI transport contract. It
uses the existing public gateway operation
`POST /api/logistics/v1/transfers` from the canonical logistics OpenAPI with a
renewable human `USER` token, warehouse authorization and `Idempotency-Key`.
Planning integration continues to use only its separate private
`logistics.planning` client-credentials boundary.

Evidence:
[`planning API models`](../../services/logistics-service/src/main/java/dev/buhanzaz/rwms/logistics/planning/api/PlanningIntegrationApiModels.java),
[`planning controller`](../../services/logistics-service/src/main/java/dev/buhanzaz/rwms/logistics/planning/api/PlanningIntegrationController.java),
[`planning directory`](../../services/logistics-service/src/main/java/dev/buhanzaz/rwms/logistics/planning/service/PlanningResourceDirectoryService.java),
[`planning owner`](../../services/logistics-service/src/main/java/dev/buhanzaz/rwms/logistics/order/service/RentalOrderPlanningIntegrationService.java),
[`planning revision`](../../services/logistics-service/src/main/java/dev/buhanzaz/rwms/logistics/order/service/PlanningRequestRevision.java),
[`capacity owner`](../../services/logistics-service/src/main/java/dev/buhanzaz/rwms/logistics/customer/capacity/service/WarehouseCapacitySnapshotService.java),
[`capacity policy classifier`](../../services/logistics-service/src/main/java/dev/buhanzaz/rwms/logistics/customer/service/CustomerDeliveryPriceClassifier.java),
[`planner adapter`](../../logistics/backend/app/integrations/rwms.py),
[`standalone policy API`](../../logistics/backend/app/api/policy_zones.py),
[`planner OpenAPI`](../../logistics/backend/openapi.json), and
[`dynamic-slot design`](../isochrone-slot-planning.md).

### Aggregate Ordinary Task Board

[`task-board-service.yaml`](../../contracts/openapi/task-board-service.yaml) defines one
warehouse-wide aggregate ordinary board with no date or shadow query dimensions. Each queue
definition/request carries the initial `availableTaskLimit` (`1..50`, default `6`) copied when a
warehouse projection is created. Each work queue and board column carries its own required
`availableTaskLimit`, `workerFeedEnabled` and version. An ordinary manager column contains every
unfinished `REAL` and `SHADOW` entry in canonical order; the local WorkerApp controls do not
truncate this response. Registration priority is already represented by the persisted queue
position and is not reapplied during reads. `PUT
/warehouses/{warehouseId}/work-queues/{queueId}/worker-plan` requires `MANAGE`, changes the local
switch/count under the queue version. WorkerApp omits every card from disabled queues, including
active work; in enabled queues it omits all shadows, retains active real work, publishes only the
first configured waiting real cards and repeats that fence for detail and `TAKE`/`JOIN`.

Every current `REAL` route gate remains actionable on manager boundaries; every `SHADOW` is
read-only. Promotion restores the persisted shadow position ahead of later unpinned work, while
pinning keeps a later real card ahead. `POST
/warehouses/{warehouseId}/task-board/entries/{entryId}/reorder` moves only an unpinned waiting real
card within its existing ordinary queue under entry and queue versions plus the observed target
entry identity. Active, pinned and shadow entries cannot move. Cross-queue movement, date swapping,
maintenance daily-capacity scheduling and overdue rollover are absent. Dated driver and shipment
planning remains on the logistics surface.

For maintenance-owned ordinary routes
[`maintenance-service.yaml`](../../contracts/openapi/maintenance-service.yaml) and
[`task-board-service.yaml`](../../contracts/openapi/task-board-service.yaml) define the fixed phase
order SES, welding, exterior, interior, electrical and plumbing, regardless of submitted array
order. The first existing unfinished phase is `REAL`; every later phase is `SHADOW`, and absent or
completed phases do not block promotion. SES remains the exclusive holding phase rather than an extra
duplicated table even when an existing queue definition still carries the historical `REPAIR`
type. While a repair route has unfinished SES work, only that entry is real and executable. The
manager snapshot retains its non-SES shadows for explicit complete-route inspection; WorkerApp
omits them entirely and receives the promoted real stage only after the gate completes and the
target queue's publication window admits it. `WorkerFeedEntry` still carries required `entryType`
and `pinned`, but WorkerApp receives only server-selected real entries. The ordinary board contract
does not absorb driver movement or external capital-repair ownership. Work-queue event facts add
optional historical-compatible `availableTaskLimit` and `workerFeedEnabled`; new facts always emit
both fields, while immutable earlier facts remain valid.

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

The driver feed contains visible primary bindings from both `SCHEDULED` and
`CURRENT`, allowing a dated DriverApp screen to show assigned future work and
shared future candidates. The worker feed contains ordinary worker assignments
plus only active/paused `CURRENT` logistics entries for an eligible secondary
slinger; it never exposes scheduled or waiting driver work. A slinger
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
advance its one-to-one repair-stage mappings. The HTTP feed/detail schemas now separate raw
`routeIndex` from required zero-based `routeStepIndex` and positive `routeStepCount`; the latter two
describe maximal consecutive execution packages, not distinct queue identities. No event field was
added, and every other source remains entry-scoped with one package per route row.

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

The WorkerApp and DriverApp SSE operations are likewise invalidation-only. They
do not declare or consume `Last-Event-ID` replay: every reconnect opens a fresh
subscription and schedules an authoritative REST feed refresh. Event IDs remain
local deduplication/audit identities, and foreground polling is an additional
recovery path rather than event replay.

The public logistics rich-detail operation
`/api/logistics/v1/driver-tasks/{taskId}` requires `driver.tasks`. It accepts
the planned driver for `ASSIGNED_DRIVER`, or a same-warehouse DriverApp worker
for an unstarted `WAREHOUSE_DRIVERS` task whose scheduled date is strictly in
the future. Task-board's driver feed discovers that shared candidate only for
a currently qualified driver. `POST /api/logistics/v1/driver-tasks/{taskId}/claim`
is available only for that future shared case; task-board revalidates the active
qualification, version-fences the existing entry, changes its audience to this
driver and logistics confirms the local projection without performing `TAKE`.
Same-driver replay is idempotent, while today's shared work and another driver's
concurrent claim fail explicitly. Media accepts a WORKER upload/read
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
[`FutureDriverTaskClaimService.java`](../../services/logistics-service/src/main/java/dev/buhanzaz/rwms/logistics/driver/service/FutureDriverTaskClaimService.java),
and
[`media validator`](../../services/media-service/internal/auth/validator.go).

### Driver Up Daily Shift Boundary

[`task-board-service.yaml`](../../contracts/openapi/task-board-service.yaml) adds the daily shift
family below downstream `/driver/v1/**`, exposed unchanged by the existing gateway prefix as
`/api/task-board/driver/v1/**`. `GET /shift/today` is one startup projection: it returns feature
availability, shift/version/status, required `nextRequiredAction`, warehouse/vehicle snapshots,
briefing/weather, inspection progress and items, task summary, photos/defects and required positive
`suspiciousOdometerJumpKm`. It is the server navigation contract; clients must tolerate unavailable
weather/traffic without treating those projections as business gates.

The private logistics plan request and public `TodayDriverShift` projection
share the additive `DriverShiftRouteOperation` shape. An omitted request list is
accepted as empty for older/local producers; every response returns a list. A
non-empty list is bounded to 1000, contiguous from one, ordered in time and
load, and separates customer operations (`sourceTaskId`) from warehouse or
positioning operations (`warehouseId`). The vehicle snapshot additively carries nullable exact
`cabinCapacity`. Paired `TRANSFER_LOAD`/`TRANSFER_UNLOAD` operations use a nullable
`sourceTransferId` that is mutually exclusive with customer task identity; omission preserves old
plans, but transfer-cargo operations require known capacity. A zero cabin-load delta is valid for
furniture-only cargo, while reversed load direction, unbalanced identity, wrong endpoint or a
per-leg overload is rejected. Cross-warehouse publication carries
origin start, inbound positioning and return positioning around the exact
service-warehouse/customer stops. Planned arrival/departure are aware instants;
the driver client converts them for display but never changes their meaning.
Task-board V41 persists the immutable operation children below the shift plan
and permits replacement only before that plan is frozen by a real shift. V42 adds effective
capacity and transfer identity without rewriting existing rows.

The command family under `/shifts/{shiftId}/**` covers briefing seen, medical check, inspection
item result, inspection completion, shift start, closing start, warehouse return, closing report,
photo reservation and close. Every command requires a UUID `Idempotency-Key` and expected aggregate
version; exact replay returns the frozen result, stale/different intent conflicts, and authoritative
audit timestamps are server values. The explicit statuses and actions prevent a client from opening
two incompatible workflow steps. Task completion still uses the existing entry API; task-board
derives closing eligibility from exact-driver required task state instead of a mobile array.

[`warehouse-service.yaml`](../../contracts/openapi/warehouse-service.yaml) adds private
`GET /api/internal/warehouse/v1/warehouses/{id}/identity`, available only to exact
`task-board-service` with sole `warehouse.identity.read`. It returns owner-held metadata,
coordinate pair and canonical timezone; it is not gateway-routable.

[`media-service.yaml`](../../contracts/openapi/media-service.yaml) additively admits only the
canonical `DRIVER_SHIFT/SHIFT_EVIDENCE` pair. Upload/finalize/read require exact `driver.tasks`,
matching worker and warehouse, and a current task-board owner proof; the shift reservation
`evidenceId` is the required stable `clientReferenceId`. The task-board event contract publishes
`task-board.driver-shift-owner-proof.changed.v1` on
`rwms.task-board.driver-shift-owner-proof.v1`; media facts retain the reference so task-board can
advance the exact reservation to READY. Invalid, gapped or conflicting proof streams fail closed.

Evidence:
[`task-board OpenAPI`](../../contracts/openapi/task-board-service.yaml),
[`warehouse OpenAPI`](../../contracts/openapi/warehouse-service.yaml),
[`media OpenAPI`](../../contracts/openapi/media-service.yaml),
[`task-board events`](../../contracts/events/task-board-events.yaml),
[`task-board route-operation migration`](../../services/task-board-service/src/main/resources/db/migration/V41__driver_shift_route_operations.sql),
[`media events`](../../contracts/events/media-events.yaml),
[`DriverShiftController.java`](../../services/task-board-service/src/main/java/dev/buhanzaz/rwms/taskboard/api/DriverShiftController.java),
and
[`media driver-shift consumer`](../../services/media-service/internal/worker/driver_shift_owner_consumer.go).

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

### Repair Collection And Acceptance Reads

The maintenance repair collection accepts an optional exact `estimateId` in
addition to its existing warehouse, state, rental-item and bounded repair-ID
filters; all filters and pagination are applied before DTO mapping. The
acceptance projection is also database-paged and accepts an optional exact
`repairId`. It exposes only `PENDING`/`IN_REWORK` rows that are not blocked by
unresolved child rework; inventory-origin rows additionally require complete
task-board execution identity on every persisted stage. These are read filters,
not a new workflow owner or a browser-side acceptance invariant.

Evidence: [`maintenance-service.yaml`](../../contracts/openapi/maintenance-service.yaml),
[`MaintenanceRepairController.java`](../../services/maintenance-service/src/main/java/dev/buhanzaz/rwms/maintenance/api/MaintenanceRepairController.java), and
[`MaintenanceRepairRepository.java`](../../services/maintenance-service/src/main/java/dev/buhanzaz/rwms/maintenance/repository/MaintenanceRepairRepository.java).

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

### Inventory Planning Calendar Boundary

`GET`/`PUT /api/inventory/v1/planning-settings/{warehouseId}` in
[`inventory-service.yaml`](../../contracts/openapi/inventory-service.yaml) expose only
`warehouseId`, `settingsRevision`, `updatedAt` and explicit `holidays`; PUT is fenced by
`expectedSettingsRevision`. Ordinary callers require the established USER `rwms.write` and
warehouse-MANAGE policy; the isolated `rwms-admin-web` alternative is an exact `admin.manage`
token for a `SYSTEM_ADMIN` or `WMS_ADMIN`. Legacy movement/repair daily capacities and weekdays
remain inert transitional storage, not transport or operational settings.

Inventory reads task-board's private
`GET /api/internal/task-board/v1/inventory/warehouses/{warehouseId}/work-calendar` only with the
exact `inventory-service` SERVICE credential and sole
`task-board.inventory-calendar.read` scope. The bounded inclusive response supplies each local
date's effective schedule version, timezone fact and a fingerprint. Inventory adds its holidays,
uses no per-day capacity reservation, and fences new plan generations by the returned immutable
calendar evidence; a changed fingerprint is a `409` stale-plan conflict rather than a silent
reschedule.

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

The public shipment and return lists accept optional `assetId` alongside the required document
`warehouseId`. Logistics filters by cabin before deterministic pagination, without hiding completed
or cancelled documents or depending on the cabin's current rental state. This does not broaden
warehouse VIEW access. The array body and `X-RWMS-*` page headers are unchanged.

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

The same warehouse contract carries nullable `address`, an all-or-none
latitude/longitude pair, independent `production` and `mainWarehouse` flags,
and `representativeParentWarehouseId` on create, replace and reads. The
derived `representative` discriminator is `true` exactly when
`representativeParentWarehouseId` is non-null. A regular object selects at
least one of the two flags; a representative selects neither and requires one
production or main parent. Only the `WarehouseType`/`warehouseType` and
`productionWarehouseId` aliases are removed. The schema and owner reject
inconsistent classification/parent combinations. A version-fenced support-link
collection represents the directed many-to-many service graph; each edge has
independent capabilities, priority and recurring/date-exception availability.
Public commands reject self-links, duplicate support warehouses and links on a
non-representative served warehouse. The representative parent does not imply
an operational support edge, and a support edge does not change the
organizational parent. The private logistics directory and
date-filtered support-link read reuse those owner-held identities and
coordinates. The adjacent `support-network` read returns active incoming and
outgoing edges without evaluating a date so the standalone planner can resolve
one direct, non-transitive planning group and apply the same calendar to each
candidate date.

Actual inter-warehouse movement is represented by
[`logistics-service.yaml`](../../contracts/openapi/logistics-service.yaml).
Planning assignments may carry a nullable `serviceWarehouseId`; omission keeps
the request warehouse for compatible callers, while a regional assignment can
retain its representative owner inside the main warehouse's root plan. Transfer
creation may also carry top-level `returnCapitalRepairLines`. Those exact repair,
asset and asset-version fences create one linked reverse concrete-line transfer
atomically instead of adding reverse cargo to the outbound plan.
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
operation count from the current canonical file and permits exactly six
anonymous operations: four client-presentation routes plus cabin-photo
metadata and media reads. Every other route is bearer protected.

The gateway inventory resolves every canonical domain-public operation through
the current functional routers and the real edge security chain. It covers 295
domain-public operations, proves the six logistics presentation operations are
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
13 (eleven fixed and two guarded media methods). The worker and driver action
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
