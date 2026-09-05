# Logistics backend

## Purpose

This FastAPI/PostGIS application owns the standalone warehouse planning
workspace. `Warehouse` is the planning root; its identity and timezone come
from the authenticated RWMS warehouse directory. Isochrone tariffs, drivers,
vehicles, trailers, shifts, requests, runs and plans all belong to one local
warehouse UUID. The implementation sources are
[`app/models/domain.py`](app/models/domain.py),
[`app/api/catalog.py`](app/api/catalog.py) and
[`app/services/planner_runtime.py`](app/services/planner_runtime.py).

## Operator authentication and authorization

`/api/health`, OpenAPI and documentation remain public probes. Every catalogue,
planning, routing, geocoding, slot and RWMS operation requires an RS256 Bearer
issued by the configured RWMS issuer for audience `rwms-services`. FastAPI
accepts only an interactive `USER` token of client `rwms-panel`, requires
`rwms.read` for the API boundary and `rwms.write` for mutations, and resolves
local entity IDs back to the canonical warehouse UUID before checking the
signed `warehouse_access` level. A grouped workspace or plan requires access to
every represented warehouse; administrator roles retain their canonical global
warehouse access.

The browser reuses and silently renews the primary panel OIDC session. Ordinary
JSON requests and optimizer SSE connections send the token only in the
`Authorization` header; reconnecting SSE uses `Last-Event-ID`, never a token in
the URL. Missing sessions fail before transport. Manual-change, empty-positioning,
contractor and day-closure audit actors are derived from the verified username
and subject UUID; public request models contain no author field.

`AUTH_ISSUER` must exactly match the JWT `iss`; `AUTH_JWKS_URL` may point to a
backend-reachable JWKS endpoint. `AUTH_AUDIENCE`, `AUTH_PANEL_CLIENT_ID` and
`AUTH_JWKS_TIMEOUT_SECONDS` complete the verifier configuration. The VPS
overlay intentionally requires an explicit production issuer instead of using
the local-development default. Evidence:
[`app/security.py`](app/security.py),
[`app/api/authorization.py`](app/api/authorization.py), and
[`tests/test_security.py`](tests/test_security.py).

## Workspace and data rules

- `GET /api/warehouses/{warehouse_id}/workspace` returns the selected warehouse,
  all routable warehouse markers, member-owned resources and one exact local
  planning date. It is a side-effect-free persisted read: `planning_date`
  defaults in the selected warehouse timezone, and requests use bounded keyset
  pagination through `request_limit` (250 by default, 1000 maximum), UUID
  `request_cursor`, `request_total` and `request_next_cursor`.
  A fenced server worker separately reconciles the directory and imports a
  31-day RWMS horizon in bounded warehouse pages. Address-only rows use the
  existing server geocoder; valid siblings commit independently. Directory and
  demand state commit before support-network HTTP and before the separate
  automatic-planning transaction, so a later planning failure cannot roll back
  imported demand.
- Workspace vehicles embed their complete `load_profiles`; workspace trailers
  are the only trailer list. Vehicle configuration commands atomically replace
  vehicle fields and profiles, while trailer mutations remain separate.
- Warehouse binding accepts an RWMS external UUID plus depot timing and a
  one-to-twelve-entry contiguous hourly isochrone tariff list. The server loads
  canonical name/address/timezone and always prefers owner-held coordinates.
  When RWMS has only an address, the existing server geocoder qualifies a
  street-only value with the canonical city and stores a derived planning
  point. Automatic directory reads retain that point while the canonical
  address is unchanged; later RWMS coordinates replace it and invalidate
  affected mutable plans. There is no manual identity-refresh command.
- The tariff list starts at 60 minutes and advances in one-hour steps. Exact
  one-way Valhalla time selects its first covering price, and the last entry is
  the hard order-acceptance boundary. Ordinary delivery-zone CRUD and
  request-owned zone identity remain removed. Separate versioned,
  warehouse-owned `SPECIAL_PRICE`, `FORBIDDEN` and `NO_TRAILER` MultiPolygons
  are exceptional policies only; they cannot extend the normal boundary.
- Drivers use `ASSIGNED_DRIVER` with one validated worker UUID or
  `WAREHOUSE_DRIVERS` with a null worker UUID. Shifts repeat one daily interval
  over an inclusive range of at most 31 days, including a month boundary.
  `end < start` is an overnight interval ending on the next local day;
  `end == start` is invalid.
- Requests and their vehicle-sized tasks carry `mandatory`. Unassigned
  mandatory work blocks both plan confirmation and planning-day closing.
- An RWMS request retains the nullable confirmed amount in
  `delivery_price_rubles` and the optional explanatory tier in
  `price_isochrone_minutes`. Both fields are refreshed from the authoritative
  feed. Older or specially priced slots may have an amount without a tier;
  only an absent amount is rendered as “not calculated”, never as zero.
- Request preparation accepts either an explicit positive interval or a soft
  full-day option whose nullable bounds are not replaced with invented times.
- Automatic plan creation uses
  `POST /api/warehouses/{warehouse_id}/plans/ensure?date=...`. Mutable plan
  commands are version-fenced. Changed planning details mark an existing draft
  for explicit refresh; the ensure command rebuilds its timing while retaining
  stable cycle IDs and any still-valid manual task sequence. An invalid retained
  sequence is rejected instead of silently reordered. Manual move/reorder
  commands stably keep deliveries before pickups while preserving relative
  order inside each phase, then reschedule every affected cycle in that
  driver's day. A non-confirmed edited plan can be rebuilt with
  `POST /api/plans/{plan_id}/manual-changes/reset`; a retry returns the same
  replacement.

## Slots, routing and closing

Dynamic slot availability performs complete multi-driver/multi-trip day
resimulation and exact directed route checks. Tariff classification returns
`price_isochrone_minutes`; visual contours are not planning input. Current
exceptional policies classify the candidate address, mutable active demand and
unexpired holds in bounded batches; confirmed plan workload remains occupied
immutable history. A forbidden point is unavailable, a
no-trailer point is simulated with the solo profile, and a special price is
selected only after exact road reach is proven. Policy identity/version is part
of the slot revision, so a pre-change hold cannot confirm against new policy
facts.
A read-only `POST /api/routing/transfer-arrival-estimate` applies the same
physical vehicle/trailer snapshot and per-leg load profile to one planned
warehouse-to-warehouse departure. It accepts canonical external warehouse IDs,
an aware departure, one source-local vehicle and zero to two cabins, and returns
the exact road duration, distance and arrival without reserving resources or
changing inventory. Two cabins require the source vehicle's usable active
default trailer; routing failure is explicit and has no passenger-car fallback.
A ten-minute hold is version-fenced, confirmation re-simulates without the
hold itself, creates a mandatory request/tasks and is idempotent by confirmation
key. See [`app/slot_planning`](app/slot_planning) and
[`app/api/slot_planning.py`](app/api/slot_planning.py).

Valhalla uses truck costing for exact legs and read-only contours at every
configured hourly boundary. Provider failure is explicit; there is no passenger-car or
OSRM fallback. Open-day planning already considers compatible return and
pickup-only work after delivery-priority candidates. Configured soft overtime
extends exact routing and published shifts only by its bounded minute limit and
never past the same local day. Closing a warehouse date is one-way, builds the final plan,
rejects unassigned mandatory work and, when RWMS synchronization is enabled,
applies assigned RWMS deliveries with a plan-version idempotency key. See
[`app/services/planning_days.py`](app/services/planning_days.py) and
[`app/routing/valhalla.py`](app/routing/valhalla.py).

Plan confirmation locks current drivers, shifts, vehicles and assigned trailers before
reserving requests. It rechecks availability, windows, request points and exact per-leg
truck evidence against current cargo, equipment and the configured road-data version.
Proofs bind directed endpoints and departure instants. A stale or legacy draft returns
`PLAN_TRUCK_ROUTE_STALE` / `PLAN_REFRESH_REQUIRED` and requires regeneration; it is
never silently rerouted during confirmation. Confirmed history remains unchanged.

Sparse-matrix preparation and candidate optimization each receive one bounded
interval equal to `max_optimization_seconds`. A completed matrix therefore
leaves the full configured interval for mandatory exact-candidate routing;
interrupted candidates are still discarded and only fully verified cycles are
persisted. Candidate combinations are reused only across shifts that share the
same physical depot and eligible task set; scheduling and exact loaded-truck
checks remain per shift. See [`app/planner/heuristic.py`](app/planner/heuristic.py).

## Offline native-solver comparison

Install `.[dev,solver-benchmark]`, then run
`python -m scripts.compare_planner_candidates --sizes 5 10 20 50 100 --repeat 3`.
The optional pinned OR-Tools CP-SAT library compares immutable, already routed candidates;
it is not a second production planner and never writes assignments or product data.
Synthetic travel is explicitly labelled. `--valhalla-url` requires `--osm-data-version`
and fails without a synthetic fallback. OPTIMAL means optimal only within the collected
pool; inspect `mandatory_unassigned` before treating any result as operationally complete.
See [`../../logistic-update.md`](../../logistic-update.md) for measured results and limits.

## Integrations and configuration

RWMS OAuth uses only `logistics.planning`. Required runtime variables are
`RWMS_SYNC_ENABLED`, `RWMS_LOGISTICS_BASE_URL`, `RWMS_TOKEN_URL`,
`RWMS_CLIENT_ID` and `RWMS_CLIENT_SECRET`; anonymous capacity publication also
requires `RWMS_CAPACITY_PUBLISH_ENABLED`. Capacity PUT uses the RWMS external
warehouse UUID in the path. Shared-driver assignments explicitly send
`WAREHOUSE_DRIVERS` with a null worker UUID.
`RWMS_DEMAND_SYNC_INTERVAL_SECONDS` (5–3600) and
`RWMS_DEMAND_SYNC_BATCH_SIZE` (1–100) bound the independent ingestion worker.
`RWMS_CONTRACTOR_HANDOFF_RETRY_INTERVAL_SECONDS` (1–300) and
`RWMS_CONTRACTOR_HANDOFF_RETRY_BATCH_SIZE` (1–100) bound contractor-command
reconciliation.

Every shift must retain positive working capacity: `breakMinutes` must be
shorter than its daytime or overnight interval. The API, partial-update
service, capacity transport and database constraint enforce the same invariant.

Task-board remains the contractor-profile owner. The simulator lists and
maintains warehouse-owned contractor profiles through the public task-board
API, but uses the private planning driver directory to recheck active employment
type and warehouse ownership before a handoff. Contractor profiles have no
entry in the exact planner-driver selector: that catalog exposes only `STAFF`,
and the catalog application service repeats the same invariant on create and
update so an API caller cannot inject `CONTRACTOR` into staff optimization.
Contractor profiles have no
availability range: the dispatcher selects a date in the logistics header and
submits it to `POST /api/warehouses/{warehouseId}/contractor-dispatches`.
`AUTO` selects all supported requests still unassigned by the latest plan for
that warehouse day; `MANUAL` requires explicit request IDs and validates the
same warehouse, date, readiness and unassigned state on the server. For real
RWMS deliveries the backend first persists an immutable command and temporary
request reservations, then releases its transaction before directory and apply
HTTP calls. A leased worker retries the same payload and command UUID after a
lost response. Only a complete upstream success invalidates mutable plans and
records `CONTRACTOR_HANDOFF`; a typed all-rejected result releases the
reservations, while a mixed applied/rejected result remains pending for
reconciliation because an already-created upstream shipment cannot be rolled
back locally. No local vehicle, shift or cycle is created, so the contractor
never enters automatic route optimization. Generated delivery and pickup demand
remains local and never mutates RWMS. The current RWMS assignment contract does not expose direct pickup
handoff. `AUTO` therefore excludes real RWMS pickups instead of aborting a
mixed delivery batch; `MANUAL` and direct handoff reject them before any local
or remote effect, and the UI explains that an internal route is required.

Operator address search uses backend-only `YANDEX_GEOSUGGEST_API_KEY` and
`YANDEX_GEOCODER_API_KEY`. `DEFAULT_WAREHOUSE_TIMEZONE` supplies the local
fallback. Keys are never returned by the API. Configuration validation lives
in [`app/config.py`](app/config.py).

The warehouse workspace reads the canonical active support network and exposes
one non-transitive planning root plus its directly served representative
warehouses. Requests and eligible local resources from every admitted member
are considered together. The planner also evaluates every calendar-eligible
support link instead of choosing only the first link. Each shift carries its
physical depot, each regional task keeps its service warehouse, and shared
driver/vehicle overlap remains a hard constraint. A dated root plan admits a
direct representative only when the link calendar allows that date and the
link allows drivers, direct fulfilment or contractor fallback. See
[`app/services/planning_group.py`](app/services/planning_group.py) and
[`app/services/planner_runtime.py`](app/services/planner_runtime.py).
Vehicle identity and immutable home ownership remain in the local catalog, but planning overlays
the logistics-owned live assignment chain from
`GET /api/internal/logistics/v1/planning/vehicle-assignments`. Travel reservations fail closed,
`ACTIVE` reposition places a vehicle at the destination, an expired temporary placement returns it
to its source, and sequential trip-only reservations never mutate either warehouse catalog. The
same half-open policy filters workspace vehicles, shift create/update and local/support candidates;
conflicting or terminal upstream chains reject planning instead of falling back to the stale catalog
warehouse.
Plan application carries each regional order's canonical `serviceWarehouseId`
plus one exact `unitId -> inventorySourceWarehouseId` mapping. Every indivisible
task slice must use one physical source; mixed-source slices fail before an
RWMS command is sent. Only a `CONFIRMED` plan can be published. Its shift
snapshot carries the actual `routeOriginWarehouseId` and exact
`supportWarehouseLinkId`; the adapter validates persisted origin/link evidence
and includes inbound plus return positioning distance exactly once per shift.
Anonymous customer capacity and exact slot equipment use the same effective capacity:
one cabin without an explicitly supported, assigned, active trailer; at most two with it.
An inactive trailer is excluded from both slot and day-route physical profiles.
The vehicle snapshot carries the existing effective truck/trailer cabin capacity. The standalone
planner emits only its own depot/customer/positioning operations and never selects or mutates
transfer cargo; logistics-service may append canonical `sourceTransferId` load/unload pairs only
after matching an already confirmed and reserved transfer to that exact route.
Contractor command identity includes both `orderVersion` and `sourceRevision`,
so a revised reservation mapping cannot replay a terminal command for an older
snapshot.
For a confirmed contractor apply, the backend accepts only a complete bijection
between submitted assignment slices and upstream `orderId` results with unique,
non-null `documentId` and `externalTaskId` values. It then keeps the handoff
command UUID, each request's position in the immutable command snapshot and its
exact task UUIDs. The request read model exposes that position; the
contractor-dispatch response sorts by it, so a reload recreates the same scoped
route task order even if database rows arrive in another order. The canonical
transport has no assignment-slice correlator inside one order, so task UUIDs for
multiple slices of that order use deterministic UUID order rather than claiming
an unprovable unit-to-task association. Generated-only workload never receives
fabricated external task identities or a remote command identity.

Generated workload is simulator-owned test demand. Its optional RWMS capacity
snapshot contains only anonymous capacity facts, never orders or assets. The
local mutation commits together with a durable publication generation and
reports the later projection as `NOT_REQUESTED`, `PENDING`, `PUBLISHED` or
`FAILED`; a remote projection failure is a warning and does not turn the
completed local command into an error. A leased multi-instance-safe worker
retries the latest due generation with bounded backoff configured by
`RWMS_CAPACITY_RETRY_INTERVAL_SECONDS`; the explicit capacity endpoint remains
an operational reconciliation boundary. See
[`app/api/catalog.py`](app/api/catalog.py),
[`app/services/capacity_projection.py`](app/services/capacity_projection.py) and
[`app/services/capacity_publication_worker.py`](app/services/capacity_publication_worker.py).
The canonical capacity contract requires every `DELIVERY` job to be mandatory;
the projection normalizes legacy generator rows accordingly while retaining
optional `PICKUP` semantics. This prevents a locally valid generated delivery
from being rejected upstream with HTTP 400.

## Dynamic operations

`20260901_0031_dynamic_logistics_operations.py` adds the standalone durable
operations projection: root planning-day policy, facts, notices, dispatcher
actions/decisions and recovery proposals. The API accepts only dispatcher
events, keeps `MANUAL_PLAN_CHANGE` and `PLANNING_MODE_CHANGED` service-owned,
and fences each mutation with idempotency and the applicable mode/action/proposal
version. It reuses planner/capacity/RWMS adapters; it does not create a second
route engine or an alternate order owner. See
[`app/api/dynamic_operations.py`](app/api/dynamic_operations.py) and
[`app/services/dynamic_recovery.py`](app/services/dynamic_recovery.py).

Truck and trailer breakdowns support `recovery_mode: AUTO | MANUAL` (API default
`MANUAL`; the incident dialog explicitly selects `AUTO`). `TRAILER_BREAKDOWN`
requires `trailer_id` and disables the trailer, not the healthy tractor. Impact
uses the trailer identity saved in routed segments rather than a later catalog
attachment. Capacity publication is invalidated for the planning group.
AUTO commits the incident before running the same version-fenced recovery saga
as manual application. Event replay never duplicates a completed or failed attempt.
Replacement departures cannot precede either the incident or apply time; original
shift hours and locked route history are retained. Exact truck/load/road checks
and delivery priority remain mandatory. Insufficient replacement capacity leaves
the source active with an actionable failure and the precise unassigned task IDs;
it never silently drops a delivery or changes a customer's date. Contractor handoff
and rescheduling remain explicit request workflows. Started cargo and affected
manually locked cycles require dispatcher control; no automatic custody transfer,
customer cancellation, or fee is fabricated.

Applying a cancellation for an already published RWMS delivery never uses the ordinary direct
replacement command. The planner stages the newer local revision, sends an exact version-fenced
withdrawal to the logistics owner, waits for the owner/task-board saga receipt, and activates the
local revision only after that receipt is `COMPLETE`. A lost response reuses the stored command and
idempotency key. The removed assignment must already be owner-confirmed `CANCELLED`; the planner
cannot turn a recommendation into a customer cancellation.

For an existing unassigned RWMS delivery,
`POST /api/requests/{request_id}/reschedule-options` accepts only a date and the
local request version, then returns every fresh slot offered by the order owner.
`POST /api/requests/{request_id}/reschedule` accepts the selected `slot_id`, the
source plan ID/version, request, order, customer-session, and slot versions,
plus `Idempotency-Key`.
This contract contains neither a manually entered time window nor a local slot
confirmation shortcut.

Before the external effect, the standalone service persists a durable hold with
the source plan revision and exact owner fences. At most one active hold exists
for both the request and source plan revision; competing catalog, plan,
contractor-assignment, and recovery mutations receive `409`. The owner call
runs outside the database transaction. A leased worker claims due commands with
`SKIP LOCKED`, replays the original owner `Idempotency-Key`, applies bounded
backoff, and quarantines an exhausted command. The narrow
`POST /api/requests/{request_id}/reschedule-retry` operation resumes only that
persisted hold under its exact ID and quarantine generation, so another
dispatcher can recover it without choosing a new date or slot and without
creating a second business effect.
`RWMS_REQUEST_RESCHEDULE_RETRY_INTERVAL_SECONDS` (1–300),
`RWMS_REQUEST_RESCHEDULE_RETRY_BATCH_SIZE` (1–100), and
`RWMS_REQUEST_RESCHEDULE_RETRY_MAX_ATTEMPTS` (1–100) bound the polling cadence,
leased batch, and quarantine threshold respectively.

Migration `20260901_0032_request_reschedule_holds.py` is the byte-frozen
already-applied hold schema. Additive migration
`20260901_0033_reschedule_superseded_state.py` recreates only its state/phase
checks so databases that observed an earlier `0032` shape converge on the
terminal `SUPERSEDED` receipt without rewriting migration history.

## Schema, contract and checks

Alembic is the only schema mutation mechanism. Migration
[`20260828_0016_warehouse_workspaces.py`](migrations/versions/20260828_0016_warehouse_workspaces.py)
preserves unambiguous one-warehouse live roots, retains route-referenced shift
rows, folds safe consecutive daily shifts into periods and aborts instead of
guessing when ownership is ambiguous. It discards only exact built-in generator
fingerprints; a city or warehouse name is never a deletion criterion. Because
the old schema had no address, its depot label is only a migration placeholder;
the ordinary automatic RWMS directory reconciliation replaces it before the
address is treated as canonical. The generated contract is
[`openapi.json`](openapi.json).
Migration
[`20260828_0017_warehouse_owned_zones.py`](migrations/versions/20260828_0017_warehouse_owned_zones.py)
assigns each legacy zone to its unique nearest warehouse, validates all
existing zone references and warehouse coverage, then makes ownership
non-null with a foreign key. It aborts on missing warehouses, a nearest-distance
tie or inconsistent existing data instead of inventing ownership.
Migration
[`20260830_0021_request_price_and_contractor_handoff.py`](migrations/versions/20260830_0021_request_price_and_contractor_handoff.py)
adds the atomic confirmed-price pair and direct-contractor assignment snapshot,
including consistency constraints and a safe enrichment of stored RWMS feed
JSON with nullable price members required by the strict transport model.
Migration
[`20260831_0023_authenticated_audit_actor.py`](migrations/versions/20260831_0023_authenticated_audit_actor.py)
widens day-closure actor snapshots so the verified 128-character username and
subject UUID cannot overflow persistence.
Migration
[`20260831_0024_route_plan_revision_head.py`](migrations/versions/20260831_0024_route_plan_revision_head.py)
adds explicit `supersedes_plan_id` lineage and a partial unique index for one
non-archived plan head per warehouse/date. Safe duplicate mutable heads are
archived during upgrade; more than one confirmed head fails migration instead
of guessing. Request create/update DTOs accept only the initial `READY` value;
later request/task statuses are server-owned. Confirmation locks assigned
requests, fixes their selected date and archives competing mutable alternative
plans. RWMS apply rejects every plan that is not `CONFIRMED`.
Migration
[`20260831_0027_catalog_command_fences.py`](migrations/versions/20260831_0027_catalog_command_fences.py)
adds optimistic versions to mutable catalog rows, durable actor-scoped create
receipts and database-level daytime/overnight shift constraints. Application
advisory locks serialize overlapping shift writes and concurrent shared-driver
pool creation; existing duplicate legacy pools remain readable rather than
being destructively guessed away.
Migration
[`20260831_0029_warehouse_policy_zones.py`](migrations/versions/20260831_0029_warehouse_policy_zones.py)
creates the warehouse-scoped PostGIS policy aggregate, deterministic
classification indexes and version/price invariants. It intentionally starts
empty: migration 0020 deleted the old geometry, so an existing environment can
restore it only from an independently retained backup. Policy mutations record
the next capacity-publication generation and archive mutable plan heads in the
same transaction; confirmed plans remain immutable history.
Migration
[`20260901_0030_contractor_route_task_identities.py`](migrations/versions/20260901_0030_contractor_route_task_identities.py)
adds the backward-safe ordered JSON array used for exact contractor task UUIDs
and a nullable request position within its handoff command. Existing task arrays
start empty; positions are backfilled only from immutable successful command
snapshots, while a newly confirmed complete RWMS apply populates both values.

Workload generation is isolated test state. Automatic regeneration may delete
a plan only after proving that every referenced request belongs to the
generator; a mixed or confirmed plan produces
`WORKLOAD_GENERATOR_PLAN_CONFLICT` before any row is changed. The separate
explicit delete command may remove an unconfirmed mixed derived revision for
one exact date, but it deletes only generator-owned requests and preserves
manual and RWMS demand for replanning. A confirmed plan always fences that
command. Ordinary request mutations archive mutable plan heads and preserve
confirmed and historical revisions.

Run the project gates from this directory:

```bash
pytest -q
ruff check app tests migrations scripts
mypy app
alembic check
python scripts/export_openapi.py
```

The exporter prepends this checked-out backend root before importing `app`, so
the generated document cannot silently come from an older installed package.
