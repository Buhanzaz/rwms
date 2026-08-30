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

## Workspace and data rules

- `GET /api/warehouses/{warehouse_id}/workspace` returns the selected warehouse,
  all routable warehouse markers and that warehouse's resources.
  With RWMS enabled it performs one automatic 31-day demand refresh. A partial
  refresh commits valid siblings and returns `RWMS_WORKSPACE_SYNC_INCOMPLETE`;
  `?refresh_rwms=false` is the explicit persisted-state recovery read.
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
  the hard order-acceptance boundary. No active zone CRUD or request-owned zone
  identity remains.
- Drivers use `ASSIGNED_DRIVER` with one validated worker UUID or
  `WAREHOUSE_DRIVERS` with a null worker UUID. Shifts repeat one daily interval
  over an inclusive, single-month range of at most 31 days.
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
`price_isochrone_minutes`; visual contours are not planning input.
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

## Integrations and configuration

RWMS OAuth uses only `logistics.planning`. Required runtime variables are
`RWMS_SYNC_ENABLED`, `RWMS_LOGISTICS_BASE_URL`, `RWMS_TOKEN_URL`,
`RWMS_CLIENT_ID` and `RWMS_CLIENT_SECRET`; anonymous capacity publication also
requires `RWMS_CAPACITY_PUBLISH_ENABLED`. Capacity PUT uses the RWMS external
warehouse UUID in the path. Shared-driver assignments explicitly send
`WAREHOUSE_DRIVERS` with a null worker UUID.

Task-board remains the contractor-profile owner. The simulator lists and
maintains warehouse-owned contractor profiles through the public task-board
API, but uses the private planning driver directory to recheck active employment
type and warehouse ownership before a handoff. Contractor profiles have no
availability range: the dispatcher selects a date in the logistics header and
submits it to `POST /api/warehouses/{warehouseId}/contractor-dispatches`.
`AUTO` selects all eligible requests still unassigned by the latest plan for
that warehouse day; `MANUAL` requires explicit request IDs and validates the
same warehouse, date, readiness and unassigned state on the server. A handoff
locks the requests, invalidates affected mutable plans and records
`CONTRACTOR_HANDOFF`; it creates no local vehicle, shift or cycle and therefore
never enters automatic route optimization. For real RWMS deliveries the backend
submits one explicit vehicle-free assignment command with a stable idempotency
identity. Generated delivery and pickup demand remains local and never mutates
RWMS. The current RWMS assignment contract does not expose direct pickup
handoff, so a real RWMS pickup is rejected with a Russian domain error.

Operator address search uses backend-only `YANDEX_GEOSUGGEST_API_KEY` and
`YANDEX_GEOCODER_API_KEY`. `DEFAULT_WAREHOUSE_TIMEZONE` supplies the local
fallback. Keys are never returned by the API. Configuration validation lives
in [`app/config.py`](app/config.py).

The warehouse workspace reads the canonical active support network and exposes
one non-transitive planning root plus its directly served representative
warehouses. Requests from those members are shown together; drivers, vehicles,
trailers and shifts remain owned by the root. A dated root plan admits a direct
representative only when the link calendar allows that date and the link allows
drivers, direct fulfilment or contractor fallback. See
[`app/services/planning_group.py`](app/services/planning_group.py) and
[`app/services/planner_runtime.py`](app/services/planner_runtime.py).
Plan application keeps the top-level warehouse as the route origin and carries
each regional order's canonical `serviceWarehouseId` on its assignment.

Generated workload is simulator-owned test demand. Its optional RWMS capacity
snapshot contains only anonymous capacity facts, never orders or assets. The
local create/delete command commits first and reports the later projection as
`NOT_REQUESTED`, `PUBLISHED` or `FAILED`; a remote projection failure is a
warning and does not turn the completed local command into an error. See
[`app/api/catalog.py`](app/api/catalog.py) and
[`app/services/capacity_projection.py`](app/services/capacity_projection.py).
The canonical capacity contract requires every `DELIVERY` job to be mandatory;
the projection normalizes legacy generator rows accordingly while retaining
optional `PICKUP` semantics. This prevents a locally valid generated delivery
from being rejected upstream with HTTP 400.

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
