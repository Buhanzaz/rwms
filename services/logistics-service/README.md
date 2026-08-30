# RWMS Logistics Service

[Русская версия](README.ru.md)

## Purpose and ownership

`logistics-service` owns rental inquiries, client presentations, rental orders, returns, shipments,
transfers, driver work and logistics orchestration. It owns document/workflow state, not the cabin,
equipment, repair or warehouse aggregates. Effects on those owners use narrow private APIs and
durable logistics recovery work.

The authoritative HTTP and event contracts are
[`contracts/openapi/logistics-service.yaml`](../../contracts/openapi/logistics-service.yaml) and
[`contracts/events/logistics-events.yaml`](../../contracts/events/logistics-events.yaml). Start
with [`docs/project-knowledge/domain-logic.md`](../../docs/project-knowledge/domain-logic.md),
then verify ownership and invariants against current code and contracts.

## Public and private HTTP boundary

Authenticated operations are versioned below `/api/logistics/v1/**`. They expose returns,
shipments, transfers, equipment movements, driver board/tasks, orders and rental inquiries.
Commands use the contract-defined `Idempotency-Key` and expected-version field or parameter; callers
must handle a canonical conflict rather than send a changed retry.

### CustomerApp booking boundary

The dedicated `/api/logistics/customer/v1/**` boundary accepts only a `USER` JWT with role
`CUSTOMER`, scope `customer.rental` and client identity `rwms-customer-android`. A customer creates
one individual or legal-entity profile, selects an available warehouse, and works with a
logistics-owned rental session. A warehouse is available only while warehouse-service reports it
active with valid coordinates and it is either representative or an ordinary warehouse explicitly
enabled in the logistics delivery-depot registry. Representative warehouses need no duplicate
registry entry. Warehouse-service coordinates are authoritative for both the CustomerApp response
and route origin, so the initial map camera and slot routing start at the warehouse selected by that
session. Cabin availability,
holds, photos and positive equipment balances remain asset-owned; the customer catalog exposes only
`FREE` cabins or the same session's existing hold and never exposes the cabin dossier. The profile's
individual/legal kind and auth/client bindings are immutable; contact/display fields update under
its version fence and synchronize the logistics rental-client projection in the same transaction.
The first validated warehouse used for an avatar becomes an immutable media authorization scope,
not profile identity or warehouse access. Logistics publishes a deterministic subject-bound
`LOGISTICS_CUSTOMER_PROFILE/PROFILE_AVATAR` proof, validates one current `READY` generation before
binding it and retains only media identity/generation; media-service owns bytes and variants. Nullable
legacy passport facts are omitted from the least-privilege card rather than failing the complete
catalog page. The cart stores one initial rental duration per selected cabin and initializes a newly
selected cabin to one month. Replacing the complete term set or furniture set detaches an earlier
slot. Checkout applies that same one-month default to an unfinished older cart whose stored term set
is incomplete, then carries one exact duration per held cabin into the booking.
Every mutable step uses an idempotency key and the contract-defined profile, session,
asset or slot version fence. First profile and inquiry-session creation take a transaction-scoped
advisory lock before their unique-row lookup, so concurrent first requests converge instead of
turning the database uniqueness constraint into an API failure.
Once checkout makes a customer session `BOOKED`, its inquiry identity remains readable for order
history, but cart-scoped facets, selection and cart reads return canonical
`409 INQUIRY_ARCHIVED`. A client starts a separately idempotent inquiry for another cart; the terminal
session and its booking are never reopened or overwritten. See
[`CustomerController`](src/main/java/dev/buhanzaz/rwms/logistics/customer/api/CustomerController.java),
[`CustomerAuthorizer`](src/main/java/dev/buhanzaz/rwms/logistics/customer/security/CustomerAuthorizer.java),
[`V59`](src/main/resources/db/migration/V59__customer_app_booking_and_delivery_slots.sql),
[`V61`](src/main/resources/db/migration/V61__customer_terms_capacity_shifts_and_reception.sql) and
[`V63`](src/main/resources/db/migration/V63__customer_profile_edit_and_avatar.sql), plus the dynamic
slot/tariff extension in
[`V64`](src/main/resources/db/migration/V64__dynamic_delivery_slots_and_tariff_zones.sql) and the
explicit fixed/flexible slot kind in
[`V66`](src/main/resources/db/migration/V66__customer_delivery_slot_kind.sql).

For an ordinary warehouse, delivery offers are the fixed warehouse-local windows `09:00-12:00`,
`12:00-15:00` and `15:00-18:00`, plus one `DURING_DAY` choice spanning the complete configured
delivery day. A representative warehouse exposes only `DURING_DAY`: local published shifts still
use the same route-capacity planner, while a locally infeasible day may remain a flexible,
logistics-confirmed candidate only when a direct active incoming support link permits that date and
overlaps the customer day, and either permits both drivers and vehicles or contractor fallback.
Excluded dates override explicit dates and weekday recurrence. This fallback is re-evaluated during
hold, creates no synthetic shift or external resource reservation, and never opens a fixed window;
ordinary warehouses without local capacity continue to fail closed.
[`CustomerDeliverySlotService`](src/main/java/dev/buhanzaz/rwms/logistics/customer/service/CustomerDeliverySlotService.java)
persists offered and held capacity and uses a private Valhalla truck matrix to calculate exact road
time. `travelZoneHours` remains an informational unbounded depot band; it does not determine either
feasibility or price. The configured isochrone tiers cap delivery coverage only after an exact road
route is found. Every fixed or full-day choice is route-capacity
checked before it is offered. `kind`, `window_start` and `window_end` are persisted together:
fixed bounds remain the exact arrival promise, while full-day bounds remain non-null and fence the
hold-time recheck without promising a particular arrival hour. Feasibility simulates the complete local working day
against the exact anonymous shifts published by the simulator for that warehouse/date, including
each shift's local start/end, break and one- or two-cabin capacity, plus conservative travel/service
buffers, warehouse load/unload, every later trip, held/checkout-pending/confirmed customer windows,
generated delivery workload and active dated shipment/transfer work. No published shift or exact
truck route means no offered slot. Pickups are tested only after priority deliveries on a return
leg and are deferred whenever they threaten a current or later delivery. One driver may visit
multiple points in different windows, wait before an early window, return to the depot to unload
and reload, and perform multiple trips, but must finish the final warehouse operation by the
configured shift end (20:00 by default). Directed travel times and service-start window ends are
authoritative; a date-only active
shipment or transfer conservatively reserves one driver for the whole day, except an order shipment
already represented by its exact confirmed CustomerApp slot. Reported remaining capacity is the additional candidate-point cabin load
that the same day plan can still accept, not an arithmetic free-truck counter. For two or more
cabins CustomerApp sends `siteCabinCapacity=1|2`: one creates sequential solo-truck visits; two
permits a trailer only when its exact truck route exists. Any cabin, site-capacity or furniture
mutation detaches the old slot from the cart. Every offer also freezes the applicable solo or
truck-and-trailer height, width, length, weight, axle load and axle count used by Valhalla's truck
route. The one-way road time selects the first configured hourly isochrone tariff that covers it;
the greatest configured tier is the delivery boundary, so a point beyond it receives no offer. New
offers publish the selected tier through `priceIsochroneMinutes` and keep `priceZoneId=null`;
historical special-zone quotes retain their stored price and nullable zone ID. The transport
contract can retain `false` search-time attestations for compatible clients that only discover
provisional dates; the current CustomerApp collects both facts and, when needed, site capacity in a
modal after binding the address/point and before requesting slots. The final hold merges compatible search-time
attestations with its own recheck;
without both facts an offer cannot be held or checked out. Route calculation runs outside a database transaction;
the final hold transaction locks the cart, offer and current local workload under warehouse/day and
warehouse-capacity advisory locks, then accepts the route only if the canonical workload fingerprint is
unchanged. The same warehouse/day fence is acquired by simulator replacement and every
capacity-counted shipment/transfer create, replan, manual calendar move and lost-response status
recovery. A driver reservation therefore cannot commit inside the final fingerprint/hold window;
concurrent writers have one deterministic transaction order. Checkout replaces `HELD` with durable `CHECKOUT_PENDING` capacity using the stable command
key before any remote presentation/booking call, then atomically binds the durable booking receipt.
A terminal result confirms or releases that capacity. Checkout creates the ordinary saved rental order,
carries each cabin's own initial rental duration into it and creates deterministic per-cabin
furniture tasks. A transport retry with the same intent reuses the original
domain idempotency key and reconciles a lost response through
[`CustomerCheckoutService`](src/main/java/dev/buhanzaz/rwms/logistics/customer/service/CustomerCheckoutService.java),
not by a browser rollback. Scheduled recovery processes the oldest deterministic batch of at most
100 pending carts per pass.

My Orders derives each cabin's arrival only from the exact `LOGISTICS_DOCUMENT` shipment task,
matching document line/member and terminal `COMPLETED` state. An arrived cabin can be accepted once
with an idempotent full-screen drawn signature, or receive immutable missing-equipment,
unsuitable-cabin or other problem reports before or after acceptance. A problem may reference up to
20 READY media generations owned by that exact shipment line. Logistics stores acceptance/problem
facts; media-service owns the subject-bound photo/video bytes.

Shipment and return commands carry an optional opaque task-board `driverWorkerId` together with
the historical display snapshot; logistics never derives identity from the name. A transfer plan
keeps the trip driver's worker ID independently from optional post-arrival driver/vehicle
reposition intents. An unassigned transfer remains shared `WAREHOUSE_DRIVERS` work; an assigned
transfer is visible only to its trip driver. Merely performing the trip never changes the driver's
home or operational warehouse. Every newly scheduled shipment, return or transfer stores one durable
`LOGISTICS_DOCUMENT` driver task with an immutable client snapshot and ordered, immutable cabin
members. Its persisted `tripNumber` is stable within the rental order. Pre-start historical
document-line tasks are cancelled before one grouped task is created; a started historical member
prevents regrouping, and no new document-line task is created.

An interwarehouse transfer draft separates cargo requirements from allocated physical cabins,
loose furniture from furniture already attached to a cabin, the service warehouse from the
physical inventory source, and the trip resource from a resource being repositioned. Confirmation
uses asset-owned fenced reservations; departure moves exact property to in-transit custody, and
idempotent arrival records one destination receipt and releases the reservations. A transfer can be
furniture-only. The resulting task-board registration reuses the existing structured `taskText`,
`works`, `materials` and `comments` fields. Logistics freezes the exact route, cabin
characteristics, required/actual furniture difference and ordered load/travel/unload instructions
plus generation-aware source-cabin gallery references in
`driver_logistics_task.worker_content_json`, so a lost registration response or a pre-start content
correction converges on the same DriverApp/WorkerApp projection without a second mobile API. A
create command may also name active capital-repair cabins at the destination as independent
top-level return lines. Under stable asset/repair locks it atomically creates a linked ordinary
concrete-line transfer back to the source warehouse, assigns the same trip driver and keeps that
post-unload reverse cargo out of the outbound capacity calculation.

`POST /api/logistics/v1/historical-rental-movements` records one past shipment or return directly
from a cabin card. It accepts a visible logistics client, the current cabin version and a
non-future warehouse-local date. A shipment accepts one complete driver snapshot/worker-ID pair or
a null pair for an unknown driver; a return rejects driver data. Neither form creates a route or
driver task. The command creates a normal logistics document, line, event and durable effect
attempts. An imported shipment first asks maintenance to
finish eligible ordinary repair work or cancel eligible capital/movement work with the audit reason
`Автоматически закрыто в связи с отгрузкой.`. A released `FREE` cabin then completes the normal
fenced shipment effect. If maintenance proves that the cabin is already `RENTED` and has no repair
to close, logistics synchronizes the observed version and completes the imported document as
`SHIPPED` without acquiring a lease or repeating the asset/stock effects.
An imported return enters the ordinary fenced return-intake path and reaches
`INSPECTION_REQUIRED`, so its estimate and repair remain normal maintenance-owned work. The
`historicalRentalImport` document fact keeps either import out of driver planning and ordinary
manual shipment/return lifecycle commands; an optional shipment driver remains audit metadata only.
The command records `CREATE_HISTORICAL_RENTAL_MOVEMENT` in the same durable idempotency table as
other document creates. Its accepted response is frozen as JSON before the normal document saga can
advance, so an exact lost-response retry returns the original version/state before consulting
warehouse lifecycle or time. Both the domain validator and database constraint admit that exact
operation.
`PUT /api/logistics/v1/historical-rental-movements/{documentId}` corrects the client, optional
driver pair and warehouse-local date of that same user-entered shipment under its document version
and a separate idempotency key. It updates the document audit metadata and line client snapshot
under one next aggregate event, stores its own immutable response, and does not create another
document or repeat driver, route, lease, stock or asset effects.

The ordinary shipment cancel endpoint additionally accepts a user-entered historical shipment only
from `CONFLICT` or `RECONCILIATION_REQUIRED`. Before transition it proves from durable attempts that
no completed or unknown `SHIPMENT_ASSET_CONFIRM` exists. A
`SHIPMENT_ASSET_LEASE_ACQUIRE` with a lost response is reopened under its original operation ID,
so asset-service idempotently returns the exact lease instead of creating another effect; logistics
then releases that lease, accepting its matching `RELEASED` or naturally `EXPIRED` terminal state.
Known holds and leases are released, open reconciliation rows are retained as `RESOLVED`, and a
proven rejection with no acquired capability becomes `CANCELLED` immediately. Successful
historical shipments remain non-cancellable.

An equipment-movement worker task is still accepted by Task Board after its operational deadline.
When its authoritative `DONE` time is at or after the asset-reservation deadline, logistics records
the completion and makes the local movement `RECONCILIATION_REQUIRED` with
`TASK_BOARD_COMPLETED_AFTER_RESERVATION_EXPIRY`; it never rejects the worker fact or blindly applies
an expired source reservation that another workflow may have reused.

The public board and task detail expose the whole trip: operation, client, address and coordinates,
primary plus client/order additional contacts, comment, advisory delivery dates, actual assigned
date, cabins and per-cabin desired/actual furniture with movement-task and readiness facts. After
the ordinary manager `rwms.read` warehouse check, only a `WORKER` with `driver.tasks` whose
`worker_id` equals the frozen `plannedDriverWorkerId` may read an `ASSIGNED_DRIVER` task detail;
the session `sub` is not worker identity, and `worker.tasks` and `UNASSIGNED` grant no such access.
A same-warehouse DriverApp worker may preview a shared `WAREHOUSE_DRIVERS` task only when its date
is later than the warehouse-local current date, then reserve it through the dedicated claim
command. Task-board verifies the active driver qualification and fences concurrent claims; the
claim does not start execution and is never available for today's task. A board
move acts on the grouped task, never one member. It intentionally retains the locked local task and
document rows across task-board's version-fenced call so a locally started trip cannot race a stale
remote `WAITING` entry; the dependency boundary is limited by the configured connect/read timeouts
(`2s`/`5s` by default). After task-board accepts a pre-start date move,
logistics updates the owning document date in the recovery boundary, preserves its client delivery
date, and uses the same grouped behavior for shipment, return and transfer.

The public driver board exposes only warehouse-local current and future date columns. An overdue
active task-board card is folded into today's column, and public move or capital-scheduling commands
reject a target before that same warehouse-local date. The relay separately rechecks at most 100
`RECONCILIATION_REQUIRED` rows every 30 seconds only when their failure is a generic legacy or
stage-specific task-board dependency configuration/permanent-rejection checkpoint. Registration,
status and evidence failures use `TASK_BOARD_DEPENDENCY_*`; cover and maintenance effects use
`COVER_EFFECT_DEPENDENCY_*` and `REPAIR_PLACE_EFFECT_DEPENDENCY_*` and cannot be reopened from a
task-board snapshot. A matching authoritative task-board snapshot
binds an already-created remote registration when its response was lost, or restores the existing
scheduled, current, finalizing or cancelled workflow. Compensation and other business
reconciliation codes remain terminal and are never reopened by this pass. A full page advances the
bounded cursor, so retained business rows cannot indefinitely hide later recoverable work.

`GET` and `PUT /api/logistics/v1/warehouses/{warehouseId}/shipment-task-settings` own the
warehouse-scoped maximum cabin count for one newly created grouped trip. The lazily materialized
default is one cabin; GET requires read/VIEW scope and PUT requires write/MANAGE scope with an
`expectedVersion`. Shipment, return and transfer planning reject a unique selected set above the
current cap before creating a driver task; logistics never introduces a second cap or auto-splits
that request.

`POST /api/logistics/v1/rental-inquiries/{inquiryId}/cabin-searches` is a write-authorized command
and requires `Idempotency-Key`. A short PREPARE transaction locks the inquiry, rechecks current
ownership/state and warehouse-edit authority, and stores a subject/operation/key request digest,
domain-separated downstream UUID, immutable actor snapshot, hold expiry and the exact asset JSON
text plus its digest. Warehouse and asset HTTP calls then run without a local transaction. A short
COMPLETE transaction repeats the current authorization checks, selects the inquiry warehouse only
after asset success and freezes the response; an identical completed retry returns it with
`Idempotency-Replayed: true` and no remote call. A changed request or another live key is `409`.
Only inactive warehouse and sanitized asset `400`/`409` domain rejection codes become `REJECTED`;
OAuth/configuration/transport/timeout, asset `401`/`403`/`404` and `5xx`, and a lost response remain
`PREPARED` for an exact retry. `EXPIRED` releases the one-PREPARED-per-inquiry slot only after the
stored hold expiry, while that expired public key remains terminal.

Cabin search defaults an omitted `resultMode` to `REPLACE`: asset-service atomically releases the
inquiry's previous chat holds before installing the new result. `APPEND` is accepted only when the
caller explicitly requests it. Facets include the available characteristic values and exact
type-to-dimension relations. `GET .../cabin-catalog` is a bounded facts-only lookup and has no hold
side effect. `GET .../cabin-selection` reads the authoritative asset-owned hold set; idempotent
`PUT .../cabin-selection` replaces the exact full cabin-ID list using `chatSelectionHoldMinutes`,
and an empty list releases every inquiry hold immediately. A short PREPARE transaction stores the
owner/key request digest, exact replace-or-release JSON, non-null command deadline and nullable
public hold expiry before the asset call; for replace the deadlines are equal, while release keeps
the public expiry null. A short COMPLETE transaction rechecks current inquiry ownership/state and
warehouse authority before freezing the validated response. An identical key retry reuses the exact
bytes and deadlines, while changed reuse or another live key is `409`; a transport failure or lost
response remains `PREPARED` for that exact retry only until its persisted command deadline, after
which the slot becomes recoverable. The remote calls run outside local transactions, and the receipt
is effect/replay evidence rather than a duplicate of the authoritative asset-owned selection.

`POST /api/logistics/v1/clients` creates a logistics rental client of type `INDIVIDUAL` or
`LEGAL_ENTITY`. Name/FIO and main phone are required; legal entities also require a contact person.
Any number of validated name/phone additional contacts can remain client-owned; order-owned
additional contacts are stored separately. Email, comment and source are optional, while the
responsible-manager identity and display-name snapshot come only from the authenticated write
actor. Historical clients keep the truthful creator-subject UUID as manager identity and may have
no display-name snapshot. Client search/detail and the paged `/clients/{clientId}/orders` route use
the ordinary visible-order rules and do not disclose inaccessible identities.

Rental-order drafts may be created for a preselected client and edited with idempotency and an
expected version. Create and ordinary update commands accept only the client, primary phone and
comment; delivery address, coordinate pair and order-owned additional contacts are deliberately
collected by the client in a normal presentation confirmation. Desired-delivery windows remain an
order-owned read projection: migrated legacy rows remain truthfully readable, while a current normal
confirmation replaces them with distinct client-selected calendar days (`startDate=endDate`) in
chronological order. New normal confirmations expose the four warehouse-local requestable dates
from day +2 through day +5 and accept one to four dates only from that list. These are client
preferences, not pre-reserved route capacity. A draft may be saved without delivery facts, but
shipment creation requires an address, primary phone and at least one desired delivery day. The
ordinary manager document schedule is its `scheduledDate` and carries no clock-time value; the
dedicated CustomerApp boundary above is the only source of a confirmed exact delivery window.
Human phone formatting is normalized to canonical E.164.

`CreateRentalInquiryRequest` can target an existing draft or saved-but-editable order. Assistant
inquiries retain their conversation ID; manual inquiries use the same entity without a hidden chat,
and `GET /api/logistics/v1/rental-inquiries?rentalOrderId=...` rediscovers both under order and
warehouse authorization. Repeated normal presentations append cabins to the same order and enforce
its fixed warehouse. Presentation reads combine live shared asset availability, the selected held
cabins' atomically captured contents and unassigned physical surplus already inside that order; zero
shared availability rows remain visible with their per-cabin maximum. Confirmation carries furniture
per cabin and atomically converts holds plus the authoritative all-order furniture composition.
Every `NORMAL` public presentation therefore requires one to four server-requestable client
delivery days, a required delivery address, an optional complete latitude/longitude pair, nullable
additional contacts normalized to an empty list, and a positive initial `rentalMonths`; none is
prefilled from the linked order. The durable booking receipt chronologically normalizes those facts,
and the local post-conversion transition stores the ordered order delivery days and creates a term
only for every newly converted cabin. Retry checks include every delivery fact and duration, so a
mismatched replay conflicts and can never rewrite an existing cabin term. A `REPLACEMENT` presentation exposes current order facts read-only
and rejects every normal-only field. Shipment assignment derives each cabin return date from its actual shipment
date plus its client-selected term; the only public term mutation is the existing extension command
for selected already shipped cabins. `OrderPermissions.canExtendRentalTerms` is the server-derived
affordance for that command, including `FULFILLED` orders where ordinary editing is unavailable.
The public order boundary has no direct warehouse-selection or cabin-add command: adding cabins and
fixing the first warehouse occur only through the existing inquiry/presentation flow.
`GET /api/logistics/v1/orders/{orderId}/available-units` remains replacement discovery; cabin
removal, replacement and desired-furniture editing keep their dedicated commands.

The standalone route simulator integrates only through the private
`/api/internal/logistics/v1/planning/**` boundary and an exact `logistics-planner` service token
whose sole scope is `logistics.planning`. The bounded request feed exports saved order identity,
version, address/coordinates, still-unplanned cabin IDs and every client-approved date; a confirmed
CustomerApp booking additionally carries its exact `windowStart`, `windowEnd` and
informational positive `travelZoneHours`, plus the site-derived trailer-access fact. It also carries
nullable `deliveryPriceRubles` and `priceIsochroneMinutes` fields. The amount is authoritative; the
tier is optional for older or specially priced slots, and an absent amount means that no
authoritative logistics price exists yet. The simulator
persists the band for explanation but never uses it or a tariff zone for candidate feasibility or
ranking. `FIXED_WINDOW` is exported as the existing hard interval; `DURING_DAY` is exported as a
soft date-only option with null planner-window fields so the optimizer chooses the feasible hour.
Exact route legs and any hard window remain decisive. It
excludes phone numbers and furniture details. Applying a plan reuses the
existing idempotent rental-shipment
command with exact order version, concrete cabin IDs and an opaque task-board worker ID. Automatic
application rejects today and tomorrow, while the existing manager command remains the deliberate
manual override. An operator may separately select an unassigned future delivery part for explicit
`WAREHOUSE_DRIVERS` publication: tomorrow is allowed, the warehouse-local current day is not, and
no concrete worker identity is stored. Hidden `UNASSIGNED` shipments remain hidden. The simulator
can read back only planner-created assignment status for one warehouse/date, so a shared part is
shown with the authoritative driver after claim; ordinary manually created documents and customer
contacts are excluded. It never reads or writes the RWMS database directly.

Each assignment has an additive `assignmentType`, defaulting to `ROUTE_PLAN` for
existing clients. A route-plan assignment keeps the warehouse-local today/tomorrow
automatic-application fence and may carry the reviewed `driverShiftPlans` below.
`CONTRACTOR_HANDOFF` is instead an explicit dispatcher decision for one concrete
`ASSIGNED_DRIVER`: tomorrow is allowed, and no internal vehicle, shift or route-cycle
snapshot is required or fabricated. Both paths reuse the rental-shipment owner,
order-version fence and idempotent command semantics.

The same apply command may carry `driverShiftPlans` for the concrete
simulator-assigned driver and work date. Logistics validates unique source-shift and
driver/work-date identities, then publishes every plan before applying individual shipment
assignments through idempotent
`PUT /api/internal/task-board/v1/driver-shift-plans/{sourceShiftId}`. The snapshot contains the
planner identity/version, warehouse, driver display snapshot, assigned vehicle and optional
trailer, start odometer, trip count and exact unrounded `routeDistanceMeters`. Task-board owns the
resulting daily shift; logistics stores no duplicate shift aggregate. Generated and manual
simulator jobs remain simulator-only because assignment apply still accepts only `RWMS`-sourced
deliveries. The private call uses the existing logistics service credentials with the dedicated
least-privilege scope `task-board.driver-shifts.plan`.

`GET /api/internal/logistics/v1/planning/warehouses` exposes only active warehouse-service facts
`{warehouseId,name,city,address,timeZone}`; the owner-held address is nullable. After validating that
warehouse identity, `GET /api/internal/logistics/v1/planning/drivers?warehouseId=...` exposes
task-board's active primary-qualified staff and contractor directory. Contractor profiles are
on-demand resources and therefore carry a phone but no profile-level availability dates;
operational staff transfers retain their actual `availableFrom`/`availableUntil` interval. Ordinary
automatic route candidates remain restricted to `employmentType=STAFF`; contractors are consumed
only by the explicit `CONTRACTOR_HANDOFF` workflow. The cohesive
[`PlanningResourceDirectoryService`](src/main/java/dev/buhanzaz/rwms/logistics/planning/service/PlanningResourceDirectoryService.java)
fails closed on malformed, duplicate or unavailable owner data and stores no duplicate directory.
Every planning-feed read reloads `SAVED` orders and their confirmed CustomerApp slots from the
logistics-owned stores. A newly booked CustomerApp delivery therefore appears on the next feed read
with its order and cabin identities and `sourceRevision`; no planner-local task ID crosses this
boundary.

Each request separates `orderVersion`, which remains the rental-order assignment fence, from the
64-character deterministic `sourceRevision` of every exported planning fact. The revision also
covers confirmed-slot and current unplanned-cabin facts owned outside the order aggregate. An
independent slot or reservation change can therefore refresh the simulator without inventing an
order version, while the same revision with different payload is rejected as a source conflict.

`PUT /api/internal/logistics/v1/planning/capacity-snapshots/{warehouseId}` atomically replaces one
active anonymous capacity snapshot for the warehouse named by the authoritative path. The request
does not repeat a warehouse ID; the response does not expose an obsolete source identity. It accepts
typed generated delivery and pickup coordinates, hard local windows, cabin count, service duration,
priority, mandatory/trailer facts, anonymous dated shifts with local start/end, break and one- or
two-cabin capacity, plus one to twelve ordered isochrone tariffs. Tariffs start at 60 minutes and
remain contiguous in 60-minute increments through at most 720 minutes; each tier stores its actual
non-negative ruble price. Valhalla road time, not polygon membership or straight-line distance,
selects the price and enforces the farthest delivery boundary. Trailer feasibility remains part of
the exact vehicle route and per-job capacity facts, not a tariff-zone policy.

The snapshot carries no order, customer, cabin or driver personal identity. An idempotency key,
64-character source revision and warehouse-local monotonic `sourceGeneration` fence exact replay
and stale replacement. A delayed retry of an accepted generation returns its immutable response;
a valid `A -> B -> A` revision sequence across three increasing generations applies the third
snapshot. Real delivery slots remain separate, so capacity replacement never deletes or changes a
booking. Generated deliveries constrain CustomerApp slot routing; generated pickups are removable
return-leg work and never displace a delivery. Assignment apply accepts only `RWMS`-sourced
deliveries, leaving generated and manual planner jobs in the simulator. See
[`WarehouseCapacitySnapshotService`](src/main/java/dev/buhanzaz/rwms/logistics/customer/capacity/service/WarehouseCapacitySnapshotService.java)
and
[`V74__normalize_warehouse_isochrone_tariffs.sql`](src/main/resources/db/migration/V74__normalize_warehouse_isochrone_tariffs.sql).

Replacement reuses the same presentation/booking or direct order command. A warehouse manager can
replace only the requested pre-start cabins, with exact client-selection cardinality; direct replace
also records a nonblank reason. The ordered batch atomically swaps asset reservations and then updates
the same order, document members and furniture requirements. Existing movement-task checkpoints
cancel unfinished old-cabin filling before swap, replay exact old-to-new furniture holds when physical
contents must move, and keep readiness false until that ordinary movement task completes. Normal edits
stop once a final trip date or furniture task exists; replacement remains available per cabin
until that cabin's trip starts.

Interactive panel and Android clients reach this namespace only through the public
`api-gateway-service` `/api/logistics/**` route. They must not call this module host or an
`/api/internal/**` route directly.

`/api/internal/logistics/v1/maintenance/**` is the narrow private boundary for maintenance-owned
repair work requiring logistics driver/equipment orchestration. It uses service credentials and is
not a client route. Its driver-task intake accepts ordinary inbound `DELIVER_TO_REPAIR` work and a
separate outbound `CAPITAL_TO_PRODUCTION` task whose source is the external capital repair; both
remain logistics-owned scheduled work and never bypass the ordered driver queue.
When a normal return finishes physical intake, logistics records one immutable
`returnArrivedAt` on the document. The maintenance-only return-arrival read uses a bounded JPA
projection over this owner field; Flyway V52 backfills existing rows from their exact
`RETURN_INSPECTION_REQUIRED` event. Inventory-created historical returns bypass intake, keep this
field null and cannot become a synthetic estimate source.
When a fresh private maintenance `FIXED_DATE` request reaches logistics after that warehouse-local
day has passed, the scheduler preserves the fixed-date/source intent but persists the current local
day as the effective `scheduledDate`. This recovery applies only to service-owned maintenance
intake: public creates still reject past dates. Its checksum retains the originally requested day,
so only the exact request can replay the stored effective date without a duplicate.

`PUT /api/internal/logistics/v1/inventory/outcomes/{inventoryId}` applies the latest completed
inventory as authoritative logistics truth for exact canonical `assetId` values. It accepts only an
exact `inventory-service` SERVICE token with audience `rwms-services` and sole scope
`logistics.inventory`, plus a UUID `Idempotency-Key`. The complete batch is rejected with `409`
before mutation when it is stale, has an ambiguous equal-time source, crosses warehouse ownership,
or selects only part of a nonterminal document. An equal completion time is accepted only for an
exact source reassertion or when the same warehouse and inventory publish a strictly greater final
plan version; lower versions and a changed hash at the current version remain conflicts. Otherwise
selected document lines and rental terms remain historical but are marked inventory-superseded and
excluded from active rental/shipment reads. Fully selected nonterminal documents become `CANCELLED`;
`ACCEPTED`, `ESTIMATE_REQUESTED`,
`SHIPPED`, `COMPLETED` and already `CANCELLED` documents retain their terminal state. Orders with no
active terms move from `DRAFT`/`SAVED` to `CANCELLED` or from `FULFILLED` to `CLOSED`; existing
terminal orders retain their state. Unfinished logistics-owned driver/document tasks are cancelled
through task-board's source-owned general cancellation even after start, while completed work is
preserved. The batch does not create repair or capital-repair work: maintenance runs afterward
through the existing integrations, and a same-source reassertion protects the `INVENTORY` movement
whose `sourceId` is a current finding while still cancelling older inventory-source movement.

Each outcome has one strict disposition. `LOCAL` with frozen former-rental evidence creates or
reuses a terminal public `RETURN` (`ACCEPTED`/`ARRIVED`) without intake, estimate or task creation.
`SHIPMENT` creates or reuses a terminal public `SHIPMENT` (`SHIPPED`/`DEPARTED`) with no driver,
hold, task or stock allocation; its line exposes the exact nullable `inventoryShipmentFurniture`
snapshot. `WRITE_OFF` creates only a durable marker and releases predecessor logistics state: it
creates no document, no `FREE` or terminal asset outcome and leaves final disposition to
maintenance. V51 stores these exact source/disposition facts, and preparation saves the batch with
bounded flushes rather than flushing per outcome. It acquires the sorted per-asset advisory-lock
set in one database round trip and checks remaining active rental terms once for the complete order
set, so request query count does not grow by one lock or active-term query per outcome.
An inventory-displaced logistics guard enters reconciliation until asset-service confirms its exact
typed document-line lease as `RELEASED` or `EXPIRED`; the release runs outside the database
transaction with a stable dependency idempotency key, and an owner/fence mismatch fails closed.
Equipment work already `EXECUTING` or `RECONCILIATION_REQUIRED` fails closed; earlier
equipment work enters its existing durable cancellation path. A permanent receipt, per-asset source
watermark and task-action checkpoints retain every row and resume an uncertain remote result; no
inventory outcome path deletes logistics history.

`/api/logistics/public/v1/client-presentations/**` is intentionally anonymous, but a signed
presentation token, its revision and current viewability constrain access. Media access also verifies
the requested item/generation/variant belongs to that presentation; this is not a general media proxy.

An EDIT-authorized manager creates a cabin photo presentation through
`POST /api/logistics/v1/cabins/{cabinId}/photo-presentations` with the current asset version and a
stable idempotency key. Logistics rechecks warehouse access and the asset-owned cabin fence through
the dedicated asset photo-presentation snapshot, then freezes dimensions, finishing, category,
ordered characteristic names, nullable linoleum and one to 100 READY image IDs/generations in order
from media-service's active gallery folder before returning a non-expiring
signed public path. Older retained gallery folders remain in the CABIN archive
and are not mixed into the new immutable presentation. Media also returns the full logical
non-deleted IMAGE `photoCount` for that active folder, including processing entries. Creation fails
closed when it is outside `1..100` or differs from the bounded READY/current-generation list, so a
101st logical image or incomplete processing can never produce a silently truncated presentation.
READY positions must be unique and contiguous `0..N-1`; duplicate or gapped ordering fails closed.
Anonymous metadata and SMALL/LARGE media reads are limited to
`/api/logistics/public/v1/cabin-photo-presentations/{token}/**`; the response contains only the cabin
number, those five allowlisted display fields, creation time and immutable photo references. It never
contains warehouse, status, rental type or unrestricted passport data. Pre-V56 presentations remain
readable with null catalog fields, an empty characteristics list and null linoleum. Media bytes remain
private and are proxied only after token, snapshot membership, generation and variant validation.
Exact command replay returns the same presentation, while reusing its idempotency key for different
input is a conflict.

## Internal application structure

`HttpLogisticsDependencyGateway` is the stable implementation of the private
dependency port. Its unchanged constructor composes six owner clients and the
facade delegates every interface operation:

`LogisticsDependencyGateway` is now only the source-compatible composition of
six cohesive ports: `LogisticsWarehouseDependencyPort`,
`LogisticsAssetOperationsDependencyPort`, `LogisticsMaintenanceDependencyPort`,
`LogisticsMediaDependencyPort`, `LogisticsTaskBoardDependencyPort` and
`LogisticsOrderPresentationDependencyPort`. Existing nested transport types and
call sites keep their names; business decisions remain in the owning workflow
services and leaf clients.

| Owner client | Private boundary |
| --- | --- |
| `LogisticsWarehouseDependencyClient` | Warehouse identity/directory including nullable owner address, admission, timezone and lifecycle |
| `LogisticsAssetOperationsDependencyClient` | Rental and photo-presentation snapshots, leases, fenced effects, equipment holds and movements |
| `LogisticsAssetOrderPresentationDependencyClient` | Order units, reservations, cabin availability/search and presentation holds |
| `LogisticsMaintenanceDependencyClient` | Transfer repair, estimate source, capital repair and repair-place calls |
| `LogisticsMediaDependencyClient` | Media validation, owner proof, evidence, snapshots and binary presentation media |
| `LogisticsTaskBoardDependencyClient` | Movement tasks, driver queue/task, active qualified driver directory, board and completion calls |
| `LogisticsOAuthHttpTransport` | Exact-scope client credentials, HTTP exchange and established dependency error mapping only |
| `RentalInquiryCabinSearchService` | Non-transactional warehouse/asset call sequence over one frozen downstream command |
| `RentalInquiryCabinSearchStore` | Locked PREPARE/COMPLETE/REJECTED/EXPIRED receipt transactions and frozen-response replay |
| `RentalInquiryCabinSelectionService` | Owner-scoped authoritative hold reads and exact full-selection replace/release calls outside local transactions |
| `RentalInquiryCabinSelectionStore` | Locked PREPARE/COMPLETE/REJECTED/EXPIRED selection receipt transactions, exact-byte retry and frozen-response replay |
| `RentalInquiryCabinCatalogService` | Bounded facts-only cabin lookup with inquiry, warehouse and owner authorization |
| `CustomerRentalService`, `CustomerRentalTermCodec` | Version-fenced complete per-cabin cart terms, canonical JSON and exact selected-cabin cardinality |
| `CustomerBookingService`, `CustomerReceptionResponseMapper` | Exact completed shipment-member arrival, subject-owned booking reads, signed acceptance and immutable problem reports |
| `LogisticsDocumentService` | Stable return/shipment/transfer and rental-order hook facade over seven exact owners |
| `HistoricalRentalMovementCoordinator` | One-client, one-cabin historical shipment/return intake plus version-fenced correction of an existing imported shipment; retains optional shipment-driver audit metadata while creating normal documents and durable owner effects without driver work or a browser-owned saga |
| `CabinPhotoPresentationService`, `CabinPhotoPresentationStore`, `CabinPhotoPresentationTokenService` | Version-fenced immutable READY-photo snapshot, exact idempotent replay and non-expiring signed public capability; no general media proxy or private cabin data |
| Return, shipment and transfer document coordinators | Independent document state machines with their existing transaction and recovery order |
| `LogisticsShipmentCancellationRecovery` | Shipment-cancellation evidence checks, historical reconciliation audit closure and exact lost-response lease-attempt reopening; the shipment coordinator retains the document state machine and compensation ordering |
| `DocumentDriverTaskPlanner` | One idempotent document-owned task with ordered cabin members for each new scheduled shipment, return or transfer; waiting legacy line tasks converge to the group and started ones fence replanning |
| `TransferPlanService`, `TransferPlanWorkflowStore` | Versioned transfer draft/confirmation/departure/arrival/cancellation owner; coordinates fenced asset reservations, trip commitments and explicit resource reposition without changing stock during draft evaluation |
| `TransferDriverTaskContentService`, `CapitalRepairDriverTaskContentService`, `DriverTaskWorkerContentCodec` | Deterministic exact transfer/return route, cargo, source-media and furniture instructions over the existing task-board DriverApp/WorkerApp projection, durably retained for idempotent retries and pre-start replacement |
| `DriverTripProjectionService` | Structured task/board trip facts with one asset read per distinct order and explicit unavailable readiness on dependency failure |
| `ShipmentTaskSettingsService` | Warehouse-scoped, version-fenced cap reused by every grouped trip; materializes default one atomically and rejects over-limit planning |
| Rental-order shipment/completion and reconciliation coordinators | Document hooks for rental shipment, terminal return and reconciliation request commands |
| Document admission, idempotency, attempts, reads and binding policies | Narrow warehouse, replay, external-attempt, projection and active-order leaves |
| `RentalOrderService` | Stable order facade over reads, creation, lifecycle, reservations, terms and shipment hand-off |
| `RentalOrderUnitReplacementService` | Direct and presentation replacement over ordered batch checkpoints, pre-start driver-task cancellation and same-order document/member convergence |
| `RentalOrderPlanningIntegrationService` | Versioned planner feed with confirmed price facts plus idempotent route-plan or explicit contractor-handoff application through the existing rental-shipment owner; no cross-database state |
| `PlanningResourceDirectoryService` | Validated least-privilege warehouse and active primary-driver resources composed from their exact domain owners without a local projection |
| `WarehouseCapacitySnapshotService` | Per-warehouse idempotent capacity replacement, monotonic generation fencing and canonical ordered hourly isochrone-tariff persistence |
| `FutureDriverTaskClaimService` | Future-only shared-task preview/claim with task-board qualification and version fencing; it never starts work |
| `ClientDeliveryDatePolicy` | Warehouse-local day +2 through day +5 request horizon for ordinary public confirmations |
| `ShipmentFurnitureTaskService` | All-active-order furniture composition, existing movement-task readiness and replacement recovery checkpoints |
| Rental-order command store, editability and problem/outcome leaves | Row/receipt replay, saved-draft synchronization and canonical local problem mapping; `LogisticsTransactionLock` owns the narrow transaction advisory-lock access |
| `InventoryOutcomeService` | Non-transactional completed-inventory orchestration and frozen successful replay |
| `InventoryOutcomePreparationStore`, `InventoryOutcomeTaskStore`, `InventoryOutcomeTaskProcessor` | Atomic warehouse/document/order supersession, durable task/asset-lease checkpoints and task-board/asset/repair-place reconciliation outside database transactions |

Owner clients depend only on the shared transport and their configured private
base URL; they do not depend on peers or refer back to the facade. Domain and
saga decisions remain in logistics application services and durable stores,
not in the transport layer.

The document facade retains every controller/order-facing method and outer
transaction annotation. Return, shipment and transfer coordinators do not call
one another; rental-order shipment/completion and reconciliation are separate
owners. Shared leaves contain only warehouse admission, idempotency records,
external-attempt writes, read projection or active-order binding, and none
refers back to the facade.

The rental-order facade retains its complete controller/presentation API and
outer transaction annotations. Read projection, creation, client/warehouse
lifecycle, reservations/equipment, rental terms and shipment hand-off have
separate owners. The command store is the sole order-row/receipt/advisory-lock
owner, while editability alone coordinates the saved document-draft lock; no
owner calls back into the facade.

## Warehouse isolation, fencing and orchestration

A user action requires the appropriate warehouse grant. A new physical logistics operation first
obtains warehouse admission and local date information through the private warehouse boundary, then
commits only local logistics state. Asset, equipment-hold and task-board effects use stable operation
IDs, expected versions and owner-side fencing, so an uncertain remote result is replayed instead of
guessed.

Warehouse admission is fail closed. Disabled or unavailable dependencies return the existing
`503 LOGISTICS_DEPENDENCY_UNAVAILABLE` response before a document, domain event, outbox event or
operation mark is written. Warehouse-service rejects an incoming operation for `DRAINING` or
`INACTIVE`, and logistics leaves no reserved intent or domain write after that rejection.

Every fresh remote-admission ticket carries the exact direction and non-negative warehouse lifecycle
version returned for each sorted requirement. The owning document, equipment or driver transaction
stores that vector on its permanent warehouse operation marks together with the new domain work; a
rollback leaves neither work nor admission evidence. If a timezone response is lost after admission,
logistics re-reads the same admitted-intent versions instead of asking warehouse-service to admit
another version.

Before any remote dependency call, a public create retry may receive an
`EVIDENCED_REPLAY_CANDIDATE` ticket only when local SQL finds a live durable domain identity and a
complete, exact set of evidenced operation marks. A document candidate requires a live, unexpired
subject/operation/key receipt that still references its document; equipment and driver candidates
require the matching actor/key domain row. SQL reconstructs the stored warehouse/direction vector
from that domain row, then requires permanent marks with the same warehouses and directions,
non-negative versions, and no missing or additional warehouses. This candidate does not prove that
the incoming payload is identical. The owning create path remains the checksum authority: changed
warehouse/direction or any other payload change returns the existing `409` without a dependency call
or mutation, while an exact match returns the stored operation before ticket consumption. A replay
candidate is rejected if it reaches any new-create consumption path. Driver replay checksums use the
originally requested fixed date before any current warehouse-local date gate and return the stored
effective date; only a fresh driver task derives an `AUTO` date from its remote ticket.

Historical marks remain deliberately unproven because migration `V39` does not backfill evidence.
Legacy null evidence, an expired document receipt, a missing domain row or mark, a subset, a
superset, and a direction/version mismatch never authorize dependency-free replay. Test-only and
parent-owned continuation marks also store null evidence. When candidate evidence is absent, a retry
follows the fresh remote-admission path and returns `503 LOGISTICS_DEPENDENCY_UNAVAILABLE` if that
dependency is not ready; an exact owner-verified candidate replay makes no warehouse
admission/timezone call and creates no short admission intent.

No user JWT crosses `LogisticsDependencyGateway`. The gateway obtains client-credential tokens for
asset, warehouse, task-board, maintenance and media. Remote effects become durable attempts and are
relayed after local commit. The sole deliberate remote-under-lock exception is a public whole-trip
board move: it retains the task/document pre-start locks across task-board's bounded version-fenced
call to close the local-start/remote-`WAITING` race, and the existing status poll converges a remote
success followed by local rollback.

The driver relay handles at most 100 due tasks per pass. An unchanged `SCHEDULED` or `CURRENT`
task-board snapshot defers its fallback poll for 30 seconds without advancing the business
aggregate version; operator commands still trigger immediate processing. This prevents an idle
fleet from generating one cross-service HTTP request per task every second.

## Persistence, events and recovery

Flyway migrations under `src/main/resources/db/migration/` own the logistics schema. JPA uses
`ddl-auto=validate`; service databases remain isolated and cross-service foreign keys/JPA entities
are forbidden.

Migration
[`V42__clients_order_delivery_and_acceptable_dates.sql`](src/main/resources/db/migration/V42__clients_order_delivery_and_acceptable_dates.sql)
adds contact/manager/comment/source client fields, initial order delivery facts, the predecessor
date collection later migrated losslessly to desired windows by V47, and durable exact-command
receipts for inquiry cabin selection. It
backfills only the truthful responsible manager UUID from
`created_by_subject_id`; it does not invent a historical display name. Legacy phone/contact rows
remain readable while new writes are constrained, and V39-V41 remain immutable.

Migration
[`V43__remove_sole_proprietor_client_type.sql`](src/main/resources/db/migration/V43__remove_sole_proprietor_client_type.sql)
reclassifies historical `SOLE_PROPRIETOR` rows to `LEGAL_ENTITY` before restricting client types to
`INDIVIDUAL` and `LEGAL_ENTITY`. It stops before the update when that reclassification would collide
with an existing legal entity by normalized phone, preserving every record for manual resolution.

Migration
[`V44__driver_task_audience_and_document_driver.sql`](src/main/resources/db/migration/V44__driver_task_audience_and_document_driver.sql)
adds the document's opaque driver ID, the three audience fields on durable
driver tasks, and the document-line source plus shipment/return/transfer kinds.
Existing driver tasks retain the former warehouse-shared behavior. The schema
uses IDs and snapshots only; there is no cross-service foreign key.

Migration
[`V45__correct_driver_task_audience.sql`](src/main/resources/db/migration/V45__correct_driver_task_audience.sql)
removes transfer driver snapshots/IDs and all shared-task responsibility hints. It maps shipment and
return work to `ASSIGNED_DRIVER` when an ID exists and `UNASSIGNED` otherwise, maps every movement
kind to identity-free `WAREHOUSE_DRIVERS`, and adds constraints that preserve those rules.

Migration
[`V46__shipment_task_grouping.sql`](src/main/resources/db/migration/V46__shipment_task_grouping.sql)
adds warehouse-local shipment-task settings, the `LOGISTICS_DOCUMENT` driver-task source and ordered
shipment-member cover checkpoints. It expands only: no historical
`LOGISTICS_DOCUMENT_LINE` task is regrouped or rewritten.

Migration
[`V47__order_contacts_windows_and_inquiry_target.sql`](src/main/resources/db/migration/V47__order_contacts_windows_and_inquiry_target.sql)
adds ordered additional-contact collections, losslessly renames legacy acceptable dates to inclusive
desired windows (`startDate=endDate`, null legacy times), and retains the historical physical
scheduled-time columns. It adds
stable grouped-trip numbers with an order-wide historical backfill, nullable manual-inquiry
conversation linkage plus an order target and creation key, replacement presentation metadata, and
reuses the existing shipment-furniture link as the durable ordered replacement checkpoint. The
outbox stays conversation-only and becomes unique per inquiry so repeated additions to one order do
not collide.

Migration
[`V48__presentation_booking_client_rental_terms.sql`](src/main/resources/db/migration/V48__presentation_booking_client_rental_terms.sql)
adds the nullable, positive client-selected initial `rental_months` receipt field to existing
`presentation_booking` rows. Historical and replacement receipts remain null; normal confirmation
persists a positive value before its local order reconciliation creates terms for newly converted
cabins.

Migration
[`V49__presentation_booking_delivery_confirmation_snapshot.sql`](src/main/resources/db/migration/V49__presentation_booking_delivery_confirmation_snapshot.sql)
additively stores the normalized normal-confirmation delivery address, optional coordinate pair and
additional-contact JSON in the existing `presentation_booking` idempotency receipt. It does not
create a second order source of truth, rewrite historical rows or remove historical desired-window
and scheduled-time columns; those legacy values remain physically readable only for persistence and
old receipt comparison, never for public commands or projections.

Migration
[`V50__authoritative_inventory_outcomes.sql`](src/main/resources/db/migration/V50__authoritative_inventory_outcomes.sql)
adds nullable supersession markers to retained document, line, guard, rental-order, rental-term and
driver-task rows; active-read indexes for unsuperseded lines/terms; permanent command receipts;
per-asset completed-source watermarks; and recoverable task/asset-lease action checkpoints. It is
additive and contains no historical-row backfill, rewrite or deletion.

Migration
[`V53__future_shipment_driver_pool.sql`](src/main/resources/db/migration/V53__future_shipment_driver_pool.sql)
adds the false-by-default `warehouse_driver_pool` shipment intent. Its database constraint permits
the flag only for a shipment without a concrete worker, so existing hidden unassigned and assigned
documents retain their meaning and are not reclassified. The task-audience constraint is widened
only for shipment `WAREHOUSE_DRIVERS`; returns keep their former assigned-or-hidden modes.

Migration
[`V54__historical_rental_documents.sql`](src/main/resources/db/migration/V54__historical_rental_documents.sql)
adds the false-by-default `historical_rental_import` fact to logistics documents and a narrow
read index. Existing documents remain unchanged; the flag only identifies a user-entered past
physical shipment or return that intentionally has no RWMS driver task.

Migration
[`V55__cabin_photo_presentations.sql`](src/main/resources/db/migration/V55__cabin_photo_presentations.sql)
adds the logistics-owned immutable photo snapshot, subject-scoped idempotency uniqueness and bounded
JSON/photo-count checks. It contains no expiry column and neither copies media bytes nor changes
existing cabin, client, order or document rows.

Migration
[`V56__cabin_photo_presentation_metadata.sql`](src/main/resources/db/migration/V56__cabin_photo_presentation_metadata.sql)
adds one required immutable metadata JSON snapshot. Existing presentation rows receive the empty
object and therefore remain readable with null/empty public metadata; the migration does not query
asset-service, rewrite photo membership or introduce a live mutable projection.

Migration
[`V57__historical_rental_movement_idempotency.sql`](src/main/resources/db/migration/V57__historical_rental_movement_idempotency.sql)
admits `CREATE_HISTORICAL_RENTAL_MOVEMENT` and adds a nullable object-shaped `response_json` to the
existing command receipt. Older command families remain nullable; no receipt or logistics document
is rewritten and every previously admitted operation stays unchanged.

Migration
[`V58__historical_rental_movement_update_idempotency.sql`](src/main/resources/db/migration/V58__historical_rental_movement_update_idempotency.sql)
adds `UPDATE_HISTORICAL_RENTAL_MOVEMENT` to that same check and requires immutable response JSON for
both historical operations. It does not rewrite historical receipts, documents, lines or events.

Migration
[`V65__warehouse_capacity_identity_and_tariff_zones.sql`](src/main/resources/db/migration/V65__warehouse_capacity_identity_and_tariff_zones.sql)
renames all five capacity tables, constraints and indexes to their warehouse-owned names, preserves
every snapshot/job/shift/receipt/zone row, removes the duplicate source identity, and drops tariff
zone code and priority. Customer slot quotes now persist `price_zone_id` as UUID: parseable UUIDs and
codes that still match exactly one active warehouse zone are backfilled before the old column is removed;
an unmatched retained price remains truthful with a null zone identity instead of receiving a
fabricated UUID. V74 later removes active zone calculation while preserving those historical quote
fields.

Migrations
[`V67__interwarehouse_transfer_planning.sql`](src/main/resources/db/migration/V67__interwarehouse_transfer_planning.sql)
and
[`V68__transfer_plan_reservation_workflow.sql`](src/main/resources/db/migration/V68__transfer_plan_reservation_workflow.sql)
add the transfer plan, configuration groups, exact allocations, per-cabin and loose furniture,
trip/reposition resource intents and durable reservation/workflow checkpoints. They keep draft
evaluation side-effect free and store only opaque IDs for asset- and task-board-owned aggregates.
[`V69__warehouse_isochrone_tariffs_and_zone_policies.sql`](src/main/resources/db/migration/V69__warehouse_isochrone_tariffs_and_zone_policies.sql)
adds configurable 60/120/180/240-minute warehouse prices, exact quoted isochrone bands and
warehouse-scoped `FORBIDDEN`/`NO_TRAILER` restriction snapshots; special-price zones retain their
existing explicit quote role.
[`V70__furniture_only_transfer_driver_tasks.sql`](src/main/resources/db/migration/V70__furniture_only_transfer_driver_tasks.sql)
admits a transfer driver task without a cabin and an exact assigned trip driver without widening
other movement audiences.
[`V71__shipment_inventory_source_warehouse.sql`](src/main/resources/db/migration/V71__shipment_inventory_source_warehouse.sql)
backfills and freezes the physical source per document line while preserving the document's service
warehouse.
[`V72__replacement_inventory_source_checkpoint.sql`](src/main/resources/db/migration/V72__replacement_inventory_source_checkpoint.sql)
keeps that source in the existing replacement/furniture recovery checkpoint.
[`V73__driver_task_worker_content.sql`](src/main/resources/db/migration/V73__driver_task_worker_content.sql)
adds an object-shaped, false-effect structured worker snapshot with `{}` as the backward-compatible
default for all historical tasks.
[`V74__normalize_warehouse_isochrone_tariffs.sql`](src/main/resources/db/migration/V74__normalize_warehouse_isochrone_tariffs.sql)
backfills the four fixed snapshot prices into normalized hourly tariff children, permits contiguous
fifth through twelfth tiers, and drops fixed price columns plus active price/restriction zone
tables. Historical customer slot price and `price_zone_id` remain intact; new quotes always use a
60-to-720-minute configured tier.

Logistics commits facts, projection checkpoints and a transactional outbox together. Kafka delivery
is at-least-once: aggregate IDs are record keys, event IDs are dedupe identities, and consumers retain
local replay/version-gap handling. Durable stores and relays recover external attempts, owner proofs,
warehouse operation marks, driver task work and sanitized failure paths.

The rental-inquiry booked producer stores the strict `DomainEventEnvelopeV2` in the booking
transaction: aggregate identity/version come from the post-flush inquiry, correlation is the
conversation with the booking as causation, actorRef is the manager USER reference, and payload is
exactly `conversationId` plus `orderId`. Kafka still uses the exact conversation UUID as record key;
the generated eventId and persisted JSON do not change across relay retries. Migration
[`V41__rental_inquiry_search_receipts_and_booked_envelope_v2.sql`](src/main/resources/db/migration/V41__rental_inquiry_search_receipts_and_booked_envelope_v2.sql)
canonicalizes both pending and already published legacy rows without changing event IDs, keys or
delivery statuses; published rows never become relayable again.

Provider-specific persistence is limited to the
[`RentalInquiryBookedOutboxStore`](src/main/java/dev/buhanzaz/rwms/logistics/inquiry/eventing/RentalInquiryBookedOutboxStore.java)
and the narrow warehouse
[`admission`](src/main/java/dev/buhanzaz/rwms/logistics/service/persistence/LogisticsWarehouseAdmissionPersistence.java),
[`single-statement blocker`](src/main/java/dev/buhanzaz/rwms/logistics/service/persistence/LogisticsWarehouseLifecycleBlockerReader.java)
and [`operation-mark`](src/main/java/dev/buhanzaz/rwms/logistics/service/persistence/LogisticsWarehouseOperationMarkPersistence.java)
adapters. They preserve PostgreSQL transaction time, conflict-safe insert, the one-statement blocker
snapshot, `FOR UPDATE SKIP LOCKED`, and conditional fencing writes; lifecycle stores retain all
business decisions. [`LogisticsTransactionLock`](src/main/java/dev/buhanzaz/rwms/logistics/repository/LogisticsTransactionLock.java)
is the sole application caller of the approved transaction advisory-lock query.

[`LogisticsRecoveryObservationStore`](src/main/java/dev/buhanzaz/rwms/logistics/eventing/LogisticsRecoveryObservationStore.java)
is a separate read-only technical SQL adapter for recovery metrics. It reads only scalar counts and
oldest timestamps from logistics-owned outbox, DLT, warehouse-mark and inbound-gap tables; it never
claims, retries, publishes, resolves or returns an identifier, payload, topic or error text.

[`LogisticsExternalAttemptClaimService`](src/main/java/dev/buhanzaz/rwms/logistics/service/LogisticsExternalAttemptClaimService.java)
leases one due external attempt at a time through a stable, bounded pessimistic skip-locked page.
PostgreSQL transaction time defines due and expiry checks; the claim transaction commits before any
remote call. The immutable, payload-free claim carries the attempt/operation IDs, lease token and
fence, claimed row version and request digest. Each workflow store locks and verifies that exact
capability before recording completion or failure, so expired or duplicate workers cannot overwrite a
newer result. [`V40__bounded_logistics_external_attempt_claims.sql`](src/main/resources/db/migration/V40__bounded_logistics_external_attempt_claims.sql)
stores the fence, token and expiry and adds due and expired-lease indexes.

The five owner relays use one lightweight trigger scheduler and submit only currently permitted work
to a separate bounded remote-call executor. `LOGISTICS_EXTERNAL_ATTEMPT_LEASE_DURATION`,
`LOGISTICS_EXTERNAL_ATTEMPT_MAXIMUM_PAGE_SIZE`, worker pool/queue/shutdown variables and the five
`*_WORKER_BUDGET` variables in `application.yaml` bound recovery capacity; each owner budget must
remain lower than the worker pool to preserve another owner's remote-call slot. Fixed-name gauges
expose backlog/oldest/terminal state for the main outbox, sanitized DLT and warehouse marks;
backlog/oldest for the rental-inquiry outbox; open/oldest inbound gaps and blocked checkpoints; and
active/oldest/max-retry/reconciliation-required external attempts plus executor active/queue state.
Empty or future-age state is zero and a database access failure is `NaN`. Claim counters and latency
timers use only the closed `owner` and `state` labels; no metric contains attempt IDs, topics,
payloads or free-form exceptions. The rental-inquiry outbox currently has only `PENDING` and
`PUBLISHED`: it still lacks a terminal/reviewed recovery state, which remains follow-up work rather
than a fabricated terminal gauge.

The base configuration requires an explicit `LOGISTICS_KAFKA_ENABLED` value; only the `dev` profile
keeps the explicit optional `false` default. Canonical primary outputs are ordered exactly as return,
shipment, transfer and `rwms.logistics.rental-inquiry.events.v1`; sanitized DLT bindings remain a
separate exact set. Outside explicit `dev`/`test`, startup requires Kafka enabled, explicit
non-loopback brokers, topic auto-creation disabled, synchronous `acks=all`, producer idempotence,
positive request/delivery/max-block timeouts with delivery not shorter than request, combined
max-block plus delivery shorter than the outbox lease, the rental-inquiry outbox enabled, and all
main-outbox, sanitized-DLT, rental-inquiry and output-binding beans. A `prod` or `production` profile
wins over a simultaneous local profile and additionally requires valid private dependency URLs and
client credentials, a ready dependency gateway, and `LOGISTICS_DEV_AUTH_BYPASS=false`.
`LOGISTICS_DEPENDENCIES_ENABLED` disabled mode remains limited to isolated local/test work. Fresh and
unproven warehouse-bound creates then fail with `503`; only an exact replay backed by durable
evidence can succeed without the dependency. Direct fixtures under `test` may receive a test-only
ticket, but its null-evidence marks never authorize public replay.

## Runtime configuration

The default HTTP port is `8090`. Configure the logistics database, `AUTH_ISSUER`, CORS origin,
client-presentation token secret, and, for real integrations, token URI, client ID/secret and private
base URLs. See `src/main/resources/application.yaml` for exact variable names; never commit live
credentials or presentation secrets. Non-local Kafka settings and, in production,
`LOGISTICS_DEPENDENCIES_ENABLED` plus `LOGISTICS_DEV_AUTH_BYPASS` are validated together by the
startup guard without including configured secrets in failures.

Driver-shift plan publication reuses `TASK_BOARD_SERVICE_URL`, `AUTH_TOKEN_URI`,
`LOGISTICS_CLIENT_ID` and `LOGISTICS_CLIENT_SECRET`; no additional credential or direct database
connection is introduced. Auth-service must grant the same service client the exact
`task-board.driver-shifts.plan` scope.

The presentation-token secret must be at least 32 characters, and production rejects the known local
default. The general API is stateless OAuth2/JWT; dev auth bypass is limited to the `dev` profile.

Customer delivery is fail-closed until `LOGISTICS_CUSTOMER_DELIVERY_ENABLED=true`, private
`LOGISTICS_CUSTOMER_VALHALLA_URL` and at least one enabled indexed ordinary depot are configured.
The current two registry positions use `LOGISTICS_CUSTOMER_DEPOT_0_*` and
`LOGISTICS_CUSTOMER_DEPOT_1_*`; each requires `ENABLED=true`, `WAREHOUSE_ID`, `LATITUDE` and
`LONGITUDE`. Disabled positions are ignored, while an empty registry, duplicate warehouse UUID or
invalid configuration coordinates fail closed. These indexed entries enable ordinary warehouses
and validate shared routing configuration; runtime route origins always come from warehouse-service.
An active representative warehouse with valid owner-held coordinates is discovered automatically
and requires no additional registry position. The former
single-depot `LOGISTICS_CUSTOMER_WAREHOUSE_ID`/`LOGISTICS_CUSTOMER_DEPOT_LATITUDE`/
`LOGISTICS_CUSTOMER_DEPOT_LONGITUDE` settings are not read.
`LOGISTICS_CUSTOMER_SERVICE_MINUTES`, booking horizon, offer/hold lifetimes and the truck
dimensions/weights define the shared capacity and route profile; current driver availability and
vehicle capacity come only from simulator-published shifts. All exact names and defaults are in
`application.yaml`. Valhalla must remain private and is bound to host
loopback by the logistics simulator Compose file.

## Observability and operations

Actuator exposes `health`, `info` and `prometheus`. Logs use ECS and tracing sampling is set by
`LOGISTICS_TRACING_SAMPLING_PROBABILITY`. The recovery gauges are registered without dynamic labels;
the existing common `application=logistics-service` tag is added by runtime configuration. For
delayed work, inspect the document, external-attempt/recovery record, outbox status and correlation
ID before manually retrying an effect. Do not repair another service's state directly from
logistics.

## Local development

From the repository root:

```bash
./gradlew :services:logistics-service:bootRun
```

Use disabled dependencies only for isolated development/test scenarios; fresh and unproven
warehouse-bound public creates deliberately remain unavailable in that mode. Live integrations use
private URLs and service credentials, never the public gateway or a browser token.

## Executable route and security parity

[`LogisticsRouteSecurityParityTest`](src/test/java/dev/buhanzaz/rwms/logistics/config/LogisticsRouteSecurityParityTest.java)
parses the canonical OpenAPI operation inventory, discovers every active `@RestController` mapping
through Spring's merged annotations, and requires exact method/path set equality without duplicates.
Only placeholder names and an optional trailing slash are normalized. The same test executes the
real owner security filter chain with dev auth bypass disabled: Bearer operations must reject an
unauthenticated request, while only the four contract operations below
`/api/logistics/public/v1/client-presentations/**` may pass anonymously.

Run the focused gate from the repository root:

```bash
bash ./gradlew :services:logistics-service:test --tests 'dev.buhanzaz.rwms.logistics.config.LogisticsRouteSecurityParityTest'
```

## Safe change rules

- Change the OpenAPI/AsyncAPI boundary and every affected producer/consumer together.
- Keep return, shipment, transfer, order and driver workflow state in logistics, not a UI or gateway saga.
- Preserve expected-version fencing, stable idempotency keys, outbox/inbox dedupe and durable recovery.
- Add immutable service-local Flyway migrations and verify affected JPA mappings.
- Test success, conflict, timeout/retry and replay paths for the changed owner boundary.

## Primary implementation references

- `src/main/java/dev/buhanzaz/rwms/logistics/service/LogisticsDocumentService.java`
- `src/main/java/dev/buhanzaz/rwms/logistics/order/service/RentalOrderService.java`
- `src/main/java/dev/buhanzaz/rwms/logistics/service/LogisticsWarehouseLifecycle.java`
- `src/main/java/dev/buhanzaz/rwms/logistics/repository/LogisticsTransactionLock.java`
- `src/main/java/dev/buhanzaz/rwms/logistics/service/persistence/LogisticsWarehouseAdmissionPersistence.java`
- `src/main/java/dev/buhanzaz/rwms/logistics/service/persistence/LogisticsWarehouseLifecycleBlockerReader.java`
- `src/main/java/dev/buhanzaz/rwms/logistics/service/persistence/LogisticsWarehouseOperationMarkPersistence.java`
- `src/main/java/dev/buhanzaz/rwms/logistics/eventing/LogisticsEventStore.java`
- `src/main/java/dev/buhanzaz/rwms/logistics/integration/LogisticsDependencyGateway.java`
- `src/main/java/dev/buhanzaz/rwms/logistics/integration/HttpLogisticsDependencyGateway.java`
- `src/main/java/dev/buhanzaz/rwms/logistics/inquiry/service/ClientPresentationService.java`
