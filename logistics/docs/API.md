# API Conventions

- Public development base path: `/api`.
- Identifiers are UUID strings.
- Coordinates are GeoJSON order: `[longitude, latitude]`.
- Timestamps are timezone-aware ISO 8601 values; scenario-local dates remain
  `YYYY-MM-DD`.
- Request create and coordinate update payloads never accept `zone_id` as an
  authority. The backend stores the classified zone and its current version.
- Scenario settings cannot disable the route-shape invariant: vehicle capacity
  remains two, delivery/pickup stop limits stay within one or two, and
  `deliveries_before_pickups` is always `true`. Invalid settings fail at the
  transport boundary instead of being persisted for a later planner failure.
- `max_customer_wait_minutes` is a non-negative scenario setting (120 by
  default). The planner first absorbs slack by delaying the cycle at the depot;
  a combination that still needs a longer wait between customer stops is
  rejected so the work can be placed in another depot cycle.
- `POST /api/zones/{zone_id}/cutouts` accepts strictly contained `geometry` and
  required `inner_zone` metadata. One transaction subtracts the geometry from
  the unlocked source, increments its version once, creates the independent
  version-one inner zone with exactly that geometry and returns both as
  `{source_zone, inner_zone}`. Existing request snapshots remain unchanged until
  explicit reclassification; a duplicate inner-zone code returns
  `409 ZONE_CODE_CONFLICT` before either geometry changes.
- Zone create/read/update and cutout metadata carry non-negative integer-ruble
  `delivery_price` and `pickup_price`. Tariff-only edits do not increment the
  geometry `version`; existing zones receive zero through the additive
  migration.
- `POST /api/requests/{request_id}/schedule` sets or clears the authoritative
  logistics date. A date outside the accepted options fails unless
  `add_if_missing=true`, which records an explicit soft whole-day agreement in
  the same transaction for a manual request. RWMS-owned requests may select
  only source-advertised dates and reject a locally invented option.
- `POST /api/requests/{request_id}/planning-details` atomically selects one
  already accepted date and stores its required positive service window, hard
  flag, explicit trailer-access decision and contact-notification preferences.
  An exact hard RWMS/CustomerApp window is immutable locally; the command may
  still store trailer and notification decisions without replacing source
  time facts. Ordinary request reads expose `source_system` and `external_id`
  so the UI can preserve that ownership boundary and identify publishable RWMS
  tasks.
  Planning rejects every eligible READY request missing a window or trailer
  decision with `422 PLANNING_INPUT_INCOMPLETE` and structured missing fields.
- `POST /api/requests/{request_id}/split` accepts an optional
  `{part_quantities}` list. Values must each be one or two and sum to the source
  request quantity. An address which rejected trailer access cannot retain a
  two-unit task. Task regeneration and request deletion reject both assigned
  and explicitly unassigned saved-plan references with stable HTTP 409 errors
  instead of relying on a database foreign-key failure.
- `POST /api/scenarios/{scenario_id}/vehicle-configurations` creates one
  vehicle together with its complete set of operational axle-load profiles.
  `PUT /api/vehicles/{vehicle_id}/configuration` row-locks and replaces both
  parts atomically. Duplicate configuration types fail before mutation, so the
  browser never coordinates a partial-save saga. The lower-level trailer and
  individual load-profile CRUD remains available for catalog administration.
- Request/task transport carries cargo length, width, height and mass. All four
  values must be present together or absent together; the backend never fills a
  missing physical value with a routing guess. The current RWMS planning read
  contract has no physical cargo fields, so an imported request is explicitly
  incomplete for safe routing until the operator supplies the tuple through
  the request editor.
- Every Valhalla-built route segment returns `routing_profile_snapshot`,
  `routing_provider`, `osm_data_version` and `routed_at`. The snapshot is the
  effective vehicle/trailer/cargo state used on that exact leg. A browser
  mapper rejects malformed GeoJSON and missing task references instead of
  fabricating route or task data.
- `GET /api/routing/truck-restrictions` accepts a WGS84
  `west/south/east/north` viewport and a bounded `limit` (2000 by default,
  5000 maximum). It returns a GeoJSON FeatureCollection of indexed OSM
  node/way restrictions plus active data-version, count, truncation and import
  timestamp metadata. Missing index data for the configured OSM version is an
  explicit service error, never an invented empty map.
- `GET /api/routing/travel-time-contours` accepts one WGS84 origin point
  and returns exactly four validated GeoJSON Polygon/MultiPolygon features for
  60, 120, 180 and 240 minutes. The private adapter always requests Valhalla
  `costing=truck` with polygons enabled and echoes provider/OSM provenance.
  Disabled, timed-out, malformed or incomplete provider output returns an
  explicit service error. The browser treats polygon geometry only as a visual
  estimate. The browser requests each visible depot plus the latest delivery
  point of every driver; pickup/depot-return points are excluded and exact
  duplicate coordinates prefer the depot. It runs at most four requests
  concurrently and cancels sibling work after the first provider failure.
  CustomerApp slot capacity and simulator planning instead consume
  the persisted numeric `travel_zone_hours` band and still validate exact
  directed route legs; polygon containment is never a business command.
- Mutable plan operations carry `expected_version`; stale writes return HTTP
  409 with code `PLAN_VERSION_CONFLICT`.
- Validation and manual edits return the complete updated plan with structured
  `validation_errors`, `validation_warnings`, schedule and metrics. A plan with
  errors cannot be confirmed.
- Successful confirmation appends one idempotent `SIMULATED_DELIVERED`
  notification log per assigned source request and returns those logs inside
  the plan. This is a local test journal, not an external delivery adapter.
  Passport text is rendered only for an opted-in request; missing passport data
  for any assigned driver returns `422 DRIVER_PASSPORT_REQUIRED` without
  confirming the plan or creating partial logs.
- Scenario export/import retains simulated notification logs for included
  plans and remaps every log to the imported source request atomically.
- Optimization progress is available both as a bounded event list and an SSE
  stream. The final plan is fetched separately.
- Plan generation currently completes inside the bounded API request and then
  exposes its terminal run. Run/event resources are already separate so a
  future worker can make live cooperative cancellation meaningful without
  changing the plan contract.

The generated schema is served at `/api/openapi.json` and exported to
`backend/openapi.json`. Frontend declarations are generated from that artifact,
not handwritten as a second transport contract.

RWMS integration is an explicit operator boundary and is unavailable unless
`RWMS_SYNC_ENABLED=true` plus all service-authentication settings are present.
Generated-capacity publication additionally requires
`RWMS_CAPACITY_PUBLISH_ENABLED=true`:

- `POST /api/scenarios/{scenario_id}/rwms/sync` reads the selected RWMS
  warehouse/date range (at most 31 inclusive days) with the sole OAuth scope
  `logistics.planning`, then
  upserts coordinate-bearing orders and reports per-order failures. A
  CustomerApp date option persists its hard window and one-to-four-hour
  `travel_zone_hours`; the band requires that complete hard window and is
  returned by the ordinary request read API. The upstream snapshot has no
  tombstone/absence reason, so current synchronization does not deactivate a
  previously imported request omitted from a later feed;
- `POST /api/scenarios/{scenario_id}/rwms/refresh` has no request body. The
  backend discovers linked warehouses and owns the current UTC day-through-day
  +30 horizon; the browser performs one command rather than a cross-warehouse
  write saga. A per-order failure returns
  `422 RWMS_WORKSPACE_SYNC_INCOMPLETE` with warehouse-tagged failures after
  committing valid sibling imports, making retry and partial state explicit;
- `POST /api/plans/{plan_id}/rwms/apply` requires `expected_version`, maps only
  synchronized delivery tasks whose source is exactly `RWMS` and returns every upstream applied/rejected
  result. Optional `publish_unassigned_task_ids` contains only explicitly
  selected future deliveries for `WAREHOUSE_DRIVERS`; omitted leftovers stay
  hidden;
- `GET /api/plans/{plan_id}/rwms/status?expected_version=...` reads the current
  publication/claim state for the exact plan version. Unknown RWMS documents
  are ignored, exact task-slice conflicts fail explicitly, and no status is
  persisted as a second driver-task owner;
- `POST /api/scenarios/{scenario_id}/rwms/capacity` publishes one deterministic
  complete snapshot containing only active generated deliveries for exactly
  one linked warehouse. The generator/delete mutation commits before remote
  I/O; a remote failure remains explicit and is reconciled through this same
  endpoint without rolling back local state. Generated pickups never consume
  delivery-slot capacity. Every outbound snapshot carries the persisted,
  simulator-wide monotonic `sourceGeneration`; explicit reconciliation advances
  it before publishing, so RWMS can reject a never-accepted stale request while
  replaying an already accepted command exactly.

Scenario deletion, demo reset and warehouse relinking are not remote capacity
deactivation commands. They currently do not clear a snapshot already accepted
by RWMS; operators must not use those local lifecycle actions as a substitute
for explicitly resolving the remote projection.

The simulator calls the private RWMS operations
`GET /api/internal/logistics/v1/planning/requests` and
`GET`/`POST /api/internal/logistics/v1/planning/assignments`, plus
`PUT /api/internal/logistics/v1/planning/capacity-snapshots/{scenarioId}`,
directly from
FastAPI;
the browser never receives the OAuth secret. A linked simulator warehouse
needs `external_warehouse_id`, and an applied route driver needs
`external_worker_id`. Coordinates win when RWMS supplies both coordinates and
an address. Address-only orders return `COORDINATES_REQUIRED` instead of being
sent to an undeclared public geocoder. A stale plan returns
`PLAN_VERSION_CONFLICT`; no partial local success is fabricated. Manual
request mutation/deletion and accepted-date CRUD reject RWMS source facts with
`RWMS_REQUEST_SOURCE_IMMUTABLE`; synchronization is the only source-fact writer.

`POST /api/scenarios/generate-multi-day-demo` creates and returns a new,
independent three-day test scenario. Unlike
`POST /api/scenarios/{scenario_id}/generate-demo`, it does not reset an
existing scenario. It creates three drivers, three vehicles, three shift dates
and ten date-eligible requests for each date; requests can be eligible on more
than one date. Request date negotiation uses the dedicated scheduling command:
`scheduled_date=null` leaves every accepted option eligible, while a selected
date makes that request eligible only for the selected day. Explicitly adding
a negotiated date retains all prior options.

The reset-style four-zone demo uses scenario-local detour limits of 60 minutes
and a 3.0 ratio so both mock routing and the local Moscow Valhalla graph produce
a minimum-cycle plan without delivery-only returns. Its vehicles, trailers,
cargo and axle profiles are complete enough for exact truck routing. Mock
routing preserves the illustrative two-delivery/two-pickup cycle; Valhalla
selects the truck-safe road equivalent. New ordinary scenarios retain the
35-minute and 1.5-ratio defaults.

`POST /api/scenarios/{scenario_id}/generate-workload` creates a deterministic
test workload in the selected scenario. `days` defaults to one and
`alternative_dates_count` defaults to zero; the supported ranges remain one to
31 days, zero to ten deliveries and pickups per day, and zero to three
additional accepted dates. Every additional date must fit inside the horizon.
The command also carries one positive cargo length, width, height and mass for
the batch (defaults 6000×2400×2400 mm and 1200 kg). It fails explicitly with
`NO_ZONES` when no polygon exists; otherwise it samples strictly interior
points, snaps each through the configured road provider while retaining zone
coverage, and creates each request through the normal server-side classifier.

Every run is replacement-only for generator-owned requests whose primary date
lies in the selected horizon. In the same transaction it first deletes every
saved route plan in that horizon, then removes the previous generated requests
and creates their replacement. The response reports `deleted_plans` and
`replaced_requests`; manual/RWMS requests, generated requests outside the
horizon and plans on other dates are preserved. A later generation failure
rolls the complete transaction back, including the old plans and workload.
`DELETE /api/scenarios/{scenario_id}/generated-workload?date=...` applies the
same rule to one exact date without generating a replacement. Newly generated
requests have stable source IDs so concurrent/retried inserts remain protected
by external-source uniqueness. Scenario clone and JSON export/import preserve
source identity plus truck/trailer/profile/segment snapshots; older
additive-field-free documents remain valid.
Generated deliveries receive hard windows round-robin `09:00-12:00`,
`12:00-15:00`, `15:00-18:00`; generated pickups receive `09:00-18:00` and
remain backhaul rather than published CustomerApp delivery capacity.

`POST /api/scenarios/{scenario_id}/plans/generate` plans unscheduled requests
whose `date_options` contain the requested date, plus scheduled requests whose
`scheduled_date` equals it. Requests belonging only to other dates are not
emitted as `NO_ALLOWED_DATE` unassigned tasks for that plan.

Simulation overrides use dedicated request shapes:

- `POST /api/plans/{plan_id}/simulation/delay` validates a positive delay from
  an aware `effective_at` timestamp;
- `POST /api/plans/{plan_id}/simulation/driver-unavailable` validates the
  remaining route after a driver becomes unavailable.

Both commands carry `expected_version`. With `persist=false`, the backend
checks the plan version and override target while the browser keeps the
override outside the authoritative plan and derives the same state for any
selected timestamp. With `persist=true`, delay propagation or driver
unavailability updates the plan through an audited versioned mutation.

Supported manual `change_type` values are `MOVE_TASK`, `REORDER_TASK` and
`LOCK_TASK`; cycle locking uses the dedicated cycle patch endpoint. Every
accepted mutation increments the plan version and reruns the shared hard-rule
validator. Other structural edit names fail explicitly with
`MANUAL_CHANGE_NOT_SUPPORTED`.
