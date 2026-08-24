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
- `POST /api/requests/{request_id}/schedule` sets or clears the authoritative
  logistics date. A date outside the accepted options fails unless
  `add_if_missing=true`, which records an explicit soft whole-day agreement in
  the same transaction.
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
