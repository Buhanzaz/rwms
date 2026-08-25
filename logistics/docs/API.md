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
  the same transaction.
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
  effective vehicle/trailer/cargo state used on that exact leg.
- `GET /api/routing/truck-restrictions` accepts a WGS84
  `west/south/east/north` viewport and a bounded `limit` (2000 by default,
  5000 maximum). It returns a GeoJSON FeatureCollection of indexed OSM
  node/way restrictions plus active data-version, count, truncation and import
  timestamp metadata. Missing index data for the configured OSM version is an
  explicit service error, never an invented empty map.
- Mutable plan operations carry `expected_version`; stale writes return HTTP
  409 with code `PLAN_VERSION_CONFLICT`.
- Validation and manual edits return the complete updated plan with structured
  `validation_errors`, `validation_warnings`, schedule and metrics. A plan with
  errors cannot be confirmed.
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
`RWMS_SYNC_ENABLED=true` plus all service-authentication settings are present:

- `POST /api/scenarios/{scenario_id}/rwms/sync` reads the selected RWMS
  warehouse/date range with the sole OAuth scope `logistics.planning`, then
  upserts coordinate-bearing orders and reports per-order failures;
- `POST /api/plans/{plan_id}/rwms/apply` requires `expected_version`, maps only
  synchronized delivery tasks and returns every upstream applied/rejected
  result. Optional `publish_unassigned_task_ids` contains only explicitly
  selected future deliveries for `WAREHOUSE_DRIVERS`; omitted leftovers stay
  hidden;
- `GET /api/plans/{plan_id}/rwms/status?expected_version=...` reads the current
  publication/claim state for the exact plan version. Unknown RWMS documents
  are ignored, exact task-slice conflicts fail explicitly, and no status is
  persisted as a second driver-task owner.

The simulator calls the private RWMS operations
`GET /api/internal/logistics/v1/planning/requests` and
`GET`/`POST /api/internal/logistics/v1/planning/assignments` directly from
FastAPI;
the browser never receives the OAuth secret. A linked simulator warehouse
needs `external_warehouse_id`, and an applied route driver needs
`external_worker_id`. Coordinates win when RWMS supplies both coordinates and
an address. Address-only orders return `COORDINATES_REQUIRED` instead of being
sent to an undeclared public geocoder. A stale plan returns
`PLAN_VERSION_CONFLICT`; no partial local success is fabricated.

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

Every run is replacement-only for generator-owned, unplanned requests whose
primary date lies in the selected horizon. It returns the removed count as
`replaced_requests`; manual/RWMS requests and other dates are outside this
selection. `DELETE /api/scenarios/{scenario_id}/generated-workload?date=...`
performs the same ownership check for one exact date without generating a
replacement. Tasks referenced by any saved route or unassigned-plan result
fence either command with `409 GENERATED_REQUESTS_ALREADY_PLANNED`; no row is
deleted. Newly generated requests have stable source IDs so concurrent/retried
inserts remain protected by external-source uniqueness. Scenario clone and
JSON export/import preserve source identity plus truck/trailer/profile/segment
snapshots; older additive-field-free documents remain valid.

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
