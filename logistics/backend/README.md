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

Operator address search uses backend-only `YANDEX_GEOSUGGEST_API_KEY` and
`YANDEX_GEOCODER_API_KEY`. `DEFAULT_WAREHOUSE_TIMEZONE` supplies the local
fallback. Keys are never returned by the API. Configuration validation lives
in [`app/config.py`](app/config.py).

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
