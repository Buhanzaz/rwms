# Logistics API

## Conventions

- Development base path: `/api`.
- Identifiers are UUID strings. Coordinates are WGS84; GeoJSON coordinate order
  is `[longitude, latitude]`.
- Timestamps are timezone-aware ISO 8601 values. Warehouse-local dates are
  `YYYY-MM-DD`, and warehouse-local daily times are `HH:MM:SS`.
- Mutating plan commands use `expected_version`. A stale command returns HTTP
  409 with `PLAN_VERSION_CONFLICT`.
- Errors use Problem Details plus a stable `code` and may contain structured
  `extra` fields.
- The generated authority is [`backend/openapi.json`](../backend/openapi.json),
  served at `GET /api/openapi.json`. The schemas originate in
  [`backend/app/schemas`](../backend/app/schemas).

## Warehouse workspace

`GET /api/warehouses` lists configured local planning roots.
`GET /api/warehouses/available` reads the authenticated RWMS directory and
returns `warehouse_id`, canonical `name`, `city`, nullable `address`,
`timezone` and nullable `local_warehouse_id`.

`POST /api/warehouses` accepts only:

```json
{
  "external_warehouse_id": "uuid",
  "loading_minutes": 30,
  "unloading_minutes": 30,
  "turnaround_minutes": 15,
  "working_day_start": "08:00:00",
  "working_day_end": "20:00:00",
  "isochrone_tariffs": [
    {"travel_minutes": 60, "price_rubles": 10000},
    {"travel_minutes": 120, "price_rubles": 15000},
    {"travel_minutes": 180, "price_rubles": 20000},
    {"travel_minutes": 240, "price_rubles": 25000}
  ]
}
```

The browser cannot supply warehouse name, address, timezone or coordinates.
The server loads the canonical RWMS identity and its owner-held coordinates;
ordinary warehouse-directory and workspace reads reconcile later identity
changes automatically. `PATCH /api/warehouses/{warehouse_id}` updates only
planning defaults/settings and depot timings. The authoritative implementation
is [`catalog.py`](../backend/app/services/catalog.py).

If that canonical identity has an address but no coordinates, `POST
/api/warehouses` resolves the owner-held address through the existing server
geocoder. The derived planning point remains valid only while the canonical
address is unchanged; owner-held coordinates always replace it. No manual WMS
identity-refresh endpoint exists.

The pre-address schema could preserve only an existing depot label and proven
coordinates. Migration uses that label as a non-canonical address placeholder;
the next automatic directory reconciliation replaces it with RWMS-owned facts.

`GET /api/warehouses/{warehouse_id}/workspace` returns:

```text
warehouse, warehouses, drivers, vehicles, trailers, shifts, requests
```

With RWMS synchronization enabled, the default `refresh_rwms=true` performs
exactly one server-owned refresh from the warehouse-local current date through
day +30 before reading the workspace. Valid sibling orders are committed even
when another row is invalid; the response then fails with
`RWMS_WORKSPACE_SYNC_INCOMPLETE` and warehouse-tagged failures. A client may
explicitly retry with `refresh_rwms=false` to read the persisted recovery state
without another remote call. Failures are never silently converted into an
apparently complete workspace. See
[`catalog.py`](../backend/app/api/catalog.py) and
[`rwms_sync.py`](../backend/app/integrations/rwms_sync.py).

Each workspace vehicle embeds `load_profiles` as
`[{configuration_type,max_actual_axle_load_kg}]`; workspace trailers are the
only trailer list. There are no separate list or mutation endpoints for axle
profiles and no separate trailer list endpoint.

## Warehouse isochrone tariffs

Warehouse create/update accepts `isochrone_tariffs`, an ordered list with one
to twelve entries. `travel_minutes` starts at 60 and advances in contiguous
60-minute steps; `price_rubles` is a non-negative integer. The first tier that
covers exact one-way Valhalla truck time supplies the price. The last tier is
also the hard order-acceptance boundary. The active API has no zone CRUD and no
client-selected zone identity. See
[`catalog.py`](../backend/app/services/catalog.py).

## Warehouse resources and requests

- `GET /api/warehouses/{warehouse_id}/available-drivers` reads canonical RWMS
  workers. `POST /api/warehouses/{warehouse_id}/drivers` creates either an
  `ASSIGNED_DRIVER` with a validated `external_worker_id`, or
  `WAREHOUSE_DRIVERS` with a null worker. `PATCH/DELETE /api/drivers/{driver_id}`
  mutates the local record. Canonical worker display names cannot be replaced
  by browser text.
- `POST /api/warehouses/{warehouse_id}/vehicle-configurations` atomically
  creates a vehicle plus its complete axle-load profile set.
  `PUT /api/vehicles/{vehicle_id}/configuration` replaces both under one row
  lock. Trailer create/update/delete commands remain separately
  addressable.
- `POST /api/warehouses/{warehouse_id}/shifts` accepts `driver_id`,
  `vehicle_id`, inclusive `date_from`/`date_to`, daily `start_time`/`end_time`,
  `break_minutes` and `active`. The range must stay in one calendar month and
  contain at most 31 days. Active driver or vehicle ranges cannot overlap.
  `PATCH/DELETE /api/shifts/{shift_id}` changes the period.
- `POST /api/warehouses/{warehouse_id}/requests` creates only `DELIVERY` or
  `PICKUP`. `mandatory` is copied to every vehicle-sized planning task.
  Coordinates are classified server-side. Cargo length, width, height and mass
  must be supplied together or omitted together.

Request mutations live below `/api/requests/{request_id}`. The scheduling
command selects or clears one accepted date; planning details store the exact
window, hard flag, trailer-access decision and notification fields; split
accepts one-or-two-cabin part quantities whose sum equals request quantity.
RWMS source facts remain writable only by authenticated synchronization.
Request and task response schemas are defined in
[`domain.py`](../backend/app/schemas/domain.py).

## Test workload and automatic planning

`POST /api/warehouses/{warehouse_id}/generate-workload` supports a bounded
1–31-day horizon, seeded delivery/pickup counts, accepted alternative dates and
one complete cargo profile. Points are sampled inside the selected warehouse's
farthest isochrone and must snap to the configured truck-road provider. The command replaces only generator-owned
requests in its horizon, deletes affected saved plans first and rolls back the
whole transaction on failure. `DELETE
/api/warehouses/{warehouse_id}/generated-workload?date=...` removes one exact
generated date. See
[`workload_generator.py`](../backend/app/services/workload_generator.py).

`POST /api/warehouses/{warehouse_id}/plans/ensure?date=...` idempotently creates
a missing automatic plan from current authoritative inputs. Planning-detail
changes on an existing draft mark that plan for explicit route refresh. The
same ensure operation then recalculates timings while retaining stable cycle
IDs and a still-valid manual task order; an order that no longer passes load,
time, shift, depot or truck-route validation is rejected rather than silently
reordered. There is no separate browser-owned save step. Plan reads and commands are:

- `GET /api/plans/{plan_id}`;
- `POST /api/plans/{plan_id}/validate`;
- `POST /api/plans/{plan_id}/confirm` with `expected_version` and explicit
  warning acceptance;
- `PATCH /api/plans/{plan_id}/cycles/{cycle_id}` and
  `POST /api/plans/{plan_id}/manual-change` for supported validated edits;
- `POST /api/plans/{plan_id}/reoptimize`;
- `POST /api/plans/{plan_id}/manual-changes/reset` with `expected_version`.

Only a non-confirmed, manually changed plan can reset. Reset rebuilds from the
current warehouse resources/requests, archives the edited plan and returns its
automatic replacement. A retry of the same version returns the same
replacement. Confirmed plans reject manual edits and reset. Unassigned
mandatory tasks block confirmation with `MANDATORY_TASKS_UNASSIGNED`.
Unassigned alternatives are typed as
`nearest_option: {"possible_at": "timezone-aware timestamp"}` rather than a
JSON string. See [`plans.py`](../backend/app/services/plans.py) and
[`planner_runtime.py`](../backend/app/services/planner_runtime.py).

## Planning-day closing

`GET /api/warehouses/{warehouse_id}/planning-days/{date}` returns whether the
date accepts demand. `POST .../close` persists a one-way close and rebuilds the
final plan under closing rules. Any unassigned mandatory delivery or pickup
returns `MANDATORY_TASKS_UNASSIGNED` without committing the close.

When RWMS synchronization is enabled, closing freezes and automatically applies
the assigned RWMS deliveries from the exact final plan. The plan-version-derived
idempotency key makes a repeated close the recovery path after an uncertain or
failed remote response. Shared drivers publish explicitly as
`WAREHOUSE_DRIVERS` with a null worker UUID. See
[`planning_days.py`](../backend/app/services/planning_days.py) and
[`plans.py`](../backend/app/api/plans.py).

## Dynamic slots and geocoding

`POST /api/planning/slot-availability` accepts warehouse UUID, date, address,
coordinates, cabin count, site capacity and optional service duration. It
returns exactly the three standard windows `09:00–12:00`, `12:00–15:00` and
`15:00–18:00`. Each result contains availability, candidate count, structured
reasons/explanation and the best exact route insertion when available. The
calculated tariff is exposed as `delivery_price_rubles` and
`price_isochrone_minutes`.

Availability performs complete multi-driver/multi-trip day resimulation,
including delivery-first ordering, return pickups, site/trailer capacity,
later trips and final warehouse work. Its route evidence contains only the
selected trip before/after plus pickup candidates. Visual contours never enter
this decision.

`POST /api/planning/slot-holds` recalculates and holds an available insertion
for the configured ten-minute TTL. `POST
/api/planning/slot-holds/{hold_id}/confirm` locks the hold/day plan,
re-simulates without the hold itself, rejects expiry or version drift and
atomically creates a mandatory request and mandatory planning tasks. The UUID
`confirmation_key` makes exact replay idempotent. The implementation is
[`app/slot_planning`](../backend/app/slot_planning).

`GET /api/geocoding/suggestions` provides navigator-style suggestions with an
optional point bias. `/resolve` resolves the selected opaque provider URI and
`/reverse` maps a selected point back to an address when available. Separate
Yandex Geosuggest and Geocoder credentials remain backend-only. Missing keys,
provider failures, malformed responses and no result are explicit errors; no
fake address is produced. See [`geocoding.py`](../backend/app/api/geocoding.py).

## Routing reads

`GET /api/routing/travel-time-contours` requests truck-costed Valhalla
Polygon/MultiPolygon contours for 60, 120, 180 and 240 minutes and returns
provider/OSM provenance. Disabled, timed-out, malformed or incomplete output is
an explicit error. The UI requests warehouse contours and the explicitly
selected request/slot point only when their independent layers are enabled;
both layers default off. At most four calls run concurrently and the first
failure cancels siblings. Contours are ephemeral display data, never pricing,
slot, routing or capacity authority.

`GET /api/routing/truck-restrictions` reads the bounded viewport from the
versioned PostGIS restriction index. Exact plan/slot legs use Valhalla truck
costing and persist the effective vehicle/trailer/cargo profile, provider,
`OSM_DATA_VERSION` and routed timestamp. Provider failure never falls back to a
passenger-car profile. See [`valhalla.py`](../backend/app/routing/valhalla.py).

## RWMS private integration

RWMS operations require `RWMS_SYNC_ENABLED=true` and the configured OAuth
client with the sole `logistics.planning` scope:

- `POST /api/warehouses/{warehouse_id}/rwms/sync` imports one explicitly
  bounded feed; the body carries the authoritative external warehouse UUID and
  at most 31 inclusive days.
- `POST /api/warehouses/{warehouse_id}/rwms/refresh` refreshes the server-owned
  current 31-day horizon and reports incomplete rows after committing valid
  siblings.
- `POST /api/plans/{plan_id}/rwms/apply` is the lower-level audited recovery
  command. It verifies `expected_version`, maps exact RWMS delivery unit slices
  and returns every applied/rejected outcome. Selected future unassigned
  deliveries may be explicitly sent to the warehouse-wide driver audience.
- `GET /api/plans/{plan_id}/rwms/status?expected_version=...` reads current
  assignment/claim state without storing a second owner.
- `POST /api/warehouses/{warehouse_id}/rwms/capacity` advances and publishes a
  complete anonymous capacity replacement when
  `RWMS_CAPACITY_PUBLISH_ENABLED=true`.

The private directory contract is
`GET .../planning/warehouses -> [{warehouseId,name,city,address,timeZone}]` and
`GET .../planning/drivers?warehouseId=... -> [{workerId,displayName}]`.
Capacity uses `PUT .../capacity-snapshots/{externalWarehouseId}`; the path UUID
is authoritative. Its request contains generation/revision, jobs, shifts and
the complete ordered `isochroneTariffs` list. A tariff item contains only
`travelMinutes` and `priceRubles`; the final item is the delivery boundary. The browser
never receives OAuth credentials. See [`rwms.py`](../backend/app/integrations/rwms.py)
and [`capacity_projection.py`](../backend/app/services/capacity_projection.py).
