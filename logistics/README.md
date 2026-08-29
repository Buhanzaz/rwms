# RWMS Logistics Simulator

[Русская версия](README.ru.md)

An internal, standalone workspace for designing, planning and replaying cabin
delivery and pickup days. It remains a separate deployable with its own data,
but can use an explicitly enabled, OAuth-protected planning boundary to
automatically refresh orders from the active RWMS `logistics-service` and apply
the exact final plan when request acceptance closes. The lower-level sync and
apply operations remain recovery APIs; the normal operator flow has no manual
RWMS exchange action.
There is no customer checkout, billing, GPS tracking, 1C or Bitrix integration
inside the simulator.

The default deployment uses a private Valhalla 3.8.3 service built from
OpenStreetMap data and always requests `costing=truck`. Every road leg is
calculated for the vehicle, attached trailer and cargo remaining on that exact
leg. There is no silent car-route fallback. No API key is needed; internet is
needed only to download the Central and Northwestern Federal District extracts
and the optional map style. A source manifest rebuilds the derived admin,
routing-tile and tile-extract files whenever either PBF changes.
If the MapLibre style cannot load, the editor falls back to its coordinate grid
while zone editing and saved-route simulation remain available.
The map is one common canvas for every active canonical RWMS warehouse with an
owner-held coordinate pair. Directory reconciliation creates or updates its
planner projection under the same UUID; there is no second connect action or
required first polygon. Exceptional zones belong to exactly one warehouse and
only the selected warehouse's zones are shown. Selecting a warehouse or its
marker switches its zones, resources, work and plans without changing the
viewport. The explicit header action recentres the map when requested, so
several warehouse markers can be compared without forced zoom. A warehouse
without coordinates remains visible in RWMS but is explicitly not routable.
The **Warehouse isochrones** and **Task isochrones** map layers are off by
default. Enabling the first requests fixed 60, 120, 180 and 240 minute
`costing=truck` contours for all connected warehouses; enabling the second
requests them only for the explicitly selected request or slot-check point.
With a switch off, its contours are neither requested nor rendered. Equal
coordinates are requested once with depot precedence. The browser keeps at
most four contour requests in flight, aborts remaining siblings when one fails,
and renders every contour below operational route lines. The Compose-owned
[`valhalla/rwms-entrypoint.sh`](valhalla/rwms-entrypoint.sh) keeps Valhalla's
generated configuration intact while raising its isochrone ceiling from the
upstream 120-minute default to the required 240 minutes; startup fails if that
limit is not applied. Those GeoJSON polygons are a visual estimate and never
replace exact route legs. Separately, an imported CustomerApp booking
stores a positive informational `travelZoneHours` band from the source depot.
That band never participates in feasibility or ranking; exact directed legs
from each driver's previous delivery point decide whether the next delivery
fits. Slot availability returns the exact winning-day route before/after and
pickup candidates, but does not calculate or return isochrone/intersection
polygons. A failed enabled contour request is shown next to the layer switches
and never produces synthetic circles or changes persisted demand.

## Interface

```text
┌ warehouse selector / date / close acceptance / slot / validate / mode ┐
├───────────────┬─────────────────────────────────────┬─────────────────┤
│ warehouse     │       MapLibre or grid map          │ selected object │
│ exception zones│                                    │ form / warnings │
│ deliveries    │ contours · delivery/pickup · routes │ route metrics  │
│ slot check    │                                     │ explanation     │
│ drivers       │                                     │                 │
│ vehicles      │                                     │                 │
│ shifts        │                                     │                 │
│ deliveries    │                                     │                 │
│ routes        │                                     │                 │
│ unassigned    │                                     │                 │
│ settings      │                                     │                 │
├───────────────┴─────────────────────────────────────┴─────────────────┤
│ simulation: start · previous · play/pause · next · end · speed · time │
└───────────────────────────────────────────────────────────────────────┘
```

The date is selected through the shared Russian React calendar; shortcut
buttons for today and tomorrow are absent. One header icon button changes
between sun and moon together with the RWMS light/dark theme. The workspace has
no provider/map-mode footer and no manual **Save plan** or **RWMS exchange**
button. **Close delivery acceptance** is the explicit finalization boundary;
normal refresh and pre-planning are automatic.

The supported flow selects an automatically reconciled canonical RWMS warehouse,
creates warehouse-scoped resources and delivery/pickup work, checks dynamic slots,
maintains the date's pre-plan automatically,
finalizes it by closing request acceptance, validates manual changes and
replays vehicles at any timestamp. City-specific fixture workspaces, workspace
cloning and JSON workspace import/export do not exist.

The deterministic workload generator remains a test/development API, but the
warehouse inspector does not expose **Test for 3 days**, **Connect warehouse**
or **Refresh from WMS** controls. Normal warehouse and RWMS order refresh is
automatic.
The shared planning date filters both the request list and map markers. Until
the dispatcher selects one date, a request appears on every customer-approved
date; afterwards it appears only on the selected logistics date. Clicking a
marker opens a MapLibre popup anchored above that point with address, quantity,
zone, windows, date selection and the current driver/cycle assignment. Before
approval, selecting another cycle moves the task from its previous cycle under
the same backend version and route-validation fence.
A new date requires an explicit agreement action, an assignment can be cleared,
and the original accepted dates remain intact. To make return
pairing observable, routing settings remain editable for the selected
warehouse.

The **Deliveries** section is the dispatcher preparation board. Switching its
date shows only deliveries and pickups eligible for that day. Every READY item
must receive either a positive service interval or the soft **During the day**
choice, plus an explicit answer whether the address accepts the truck with its
trailer; plan generation fails with `PLANNING_INPUT_INCOMPLETE` while either
decision is absent. The full-day choice deliberately persists no invented time
bounds and lets the planner place the stop anywhere in that warehouse day. A
negative trailer answer splits that request into one-unit transport tasks and
excludes it from every trailer-attached cycle. A positive answer permits the
combination at the address but never overrides Valhalla/OSM truck safety
restrictions. The same section owns the mandatory delivery/pickup flag and can
create explicit one-unit subtasks. After a draft plan exists, changing these facts marks it as
stale and shows **Refresh routes** before the date field. Refresh retains stable
cycle identifiers and the operator's valid manual task order; it rejects an
order that has become physically or temporally invalid instead of silently
reordering it. **Plan day** contains only the resulting plan, metrics,
approval and a pre-approval **Undo changes** action. Dragging tasks between
driver cycles remains version-fenced and fully revalidated by the backend.
The right inspector has an accessible drag separator, is resizable up to half
the viewport and reflows its forms and metrics as its width changes. The former
per-leg truck diagnostic block is not part of the operator interface.
Header buttons, the date control and the three equal-width mode segments share
one control height. Warehouse actions use two three-column rows whose buttons
remain equal within each row while their labels wrap at the minimum inspector width.

The **Check new order** view offers debounced navigator-style address
suggestions. Selecting one resolves its canonical address and coordinates;
placing a point on the map reverse-geocodes and fills the address when the
provider knows it. Manual address text remains available when reverse
geocoding has no result. The view calls `POST /api/planning/slot-availability`
only for a complete point. Cabin count accepts any positive integer;
site receiving capacity remains an explicit `1|2` choice. For each of the three
customer windows the backend tests insertion into existing trips, a separate
trip between existing trips and every compatible driver, then resimulates the
complete day through warehouse unload/reload and the final finish. Pickups are
enumerated only on return legs and deferred whenever they threaten any
delivery. The panel displays structured reasons, arrival, detour, slack,
candidate count, stop timeline, load transitions, route before/after and
pickup candidates. Global map-layer switches own optional visual isochrones.
`POST /api/planning/slot-holds` retains one versioned result
for ten minutes by default; confirmation rechecks source/day-plan versions and
increments the plan version atomically. The matched zone contributes the
displayed delivery price only.

The before/after route layers cover the complete affected driver day. Exact
directed schedule simulation remains authoritative. Pickup markers expose
`SELECTED`, `DEFERRED`, and `CANDIDATE` states from that winning schedule.

Confirming a valid plan writes one idempotent **simulated** contact message per
assigned source request. The message contains the plan date, agreed window,
assigned quantity, arrival time, driver name, vehicle make/model and
registration number. Driver passport details are included only when the
request explicitly enables them, and confirmation fails if an assigned driver
then has no passport details. These records and ordinary action feedback appear
for eight seconds by default, then remain in the upper-right bell history with
an unread counter. The timeout is configurable in **Settings** and **Clear
all** clears the visible history. Settings also exposes **Allow overtime** as a
switch and **Maximum overtime, hours** as its dependent numeric limit. That
limit extends exact route and published customer-slot capacity only inside the
same calendar day. This MVP does not send SMS, messenger or push messages.

## Architecture

This directory is a monorepo with isolated runtime containers:

- `frontend/`: React, strict TypeScript, Vite, MapLibre GL JS, Terra Draw,
  TanStack Query, Zustand, React Hook Form/Zod and dnd-kit;
- `backend/`: FastAPI, Pydantic, SQLAlchemy 2, GeoAlchemy2/Shapely and Alembic;
- `db`: PostgreSQL with PostGIS and one simulator-only named volume.
- `osrm-download`: a one-shot downloader for both regional PBFs shared
  read-only with Valhalla;
- `valhalla-init` and `valhalla`: private tile preparation and truck routing;
- `truck-restrictions-indexer`: a one-shot, versioned Osmium import of
  truck-related OSM nodes/ways into the PostGIS viewport index;
- legacy `osrm-prepare`/`osrm` exist only behind the explicit
  `legacy-routing` Compose profile and are never a truck fallback.

Nginx serves the production frontend and proxies same-origin `/api` and SSE to
FastAPI. The browser never chooses authoritative request zones. The backend
stores warehouse-scoped resources and zone versions, plans, validation results, explanations,
optimization traces and manual-change audit entries. See
[`docs/ARCHITECTURE.md`](docs/ARCHITECTURE.md) and
[`docs/API.md`](docs/API.md).

## Repository layout

```text
logistics/
├── frontend/             React workspace, component tests and Playwright flow
├── backend/
│   ├── app/              API, persistence, routing, planner and simulation
│   ├── migrations/       simulator-owned Alembic/PostGIS history
│   └── tests/            unit, property and database integration tests
├── docs/                 architecture and transport conventions
├── docker-compose.yml    db, private Valhalla, backend, frontend and test tools
├── Makefile
├── .env.example
├── README.md
└── README.ru.md
```

The directory never shares a database or mutable model with active RWMS
deployables. With RWMS synchronization disabled it has no RWMS runtime
dependency. When explicitly enabled, its backend obtains a short-lived OAuth2
client-credentials token with the registered `client_secret_basic` method and
calls only the versioned planning API owned by `services/logistics-service`;
the browser and token form body do not receive the service secret.

## Domain model

The persisted model contains automatically reconciled canonical RWMS warehouses and their
settings, four isochrone tariff prices, warehouse-owned versioned Polygon/MultiPolygon exceptional
zones with interior rings, display colors and explicit policy, warehouse-scoped drivers, vehicles and
monthly shift ranges, logistics requests, date options and a separate
dispatcher-selected logistics date, split planning tasks, versioned
route plans/cycles/stops/legs, optimization runs/trace events and manual-change
audit, versioned day plans and expiring slot holds. All timestamps are
timezone-aware; a warehouse's canonical timezone is authoritative and
`Europe/Moscow` is only the configured fallback. Zones have a UUID identity,
not a business code or priority; relation/group/import models do not exist.

Request coordinates are classified server-side with PostGIS only against zones
owned by the same warehouse. `FORBIDDEN` rejects delivery, `NO_TRAILER` rejects
a trailer-attached route, and `SPECIAL_PRICE` overrides the ordinary price
derived from the exact road-time 60/120/180/240-minute band. The smallest
covering geometry of each policy wins; an equal-area overlap is resolved by the
stable zone UUID. A warehouse needs no covering zone and a request outside all
zones uses its isochrone tariff if the truck route is feasible. Editing a
zone increments its version and invalidates affected slots. The **Make
cutout** tool selects an unlocked source zone and then
draws a strictly internal ring. It opens the new inner-zone form; saving one
atomic backend command subtracts the ring from the source and creates a
version-one exceptional zone with exactly that geometry. Cancelling the form changes
neither zone. Exact truck routing remains authoritative after every policy classification.

## Logistics rules

- Vehicle capacity defaults to two cabins and every stop preserves
  `0 <= load <= capacity`.
- A request larger than capacity is deterministically split, for example
  `5 -> [2, 2, 1]`.
- Every cycle starts and returns to the warehouse. Both automatic and manual
  sequences keep all deliveries before every pickup. A manual insertion is
  clamped to the selected task's phase while preserving the operator's relative
  order among deliveries and among pickups; capacity, time-window, shift,
  depot-operation and truck-safe-leg validation still runs afterwards.
  Returning to the depot closes the cycle, so the same driver may load
  deliveries again in a later independent cycle.
- A cycle may carry one quantity-two delivery or pair two quantity-one
  deliveries, then attach one quantity-two pickup or pair two quantity-one
  pickups. Pickup-only cycles remain possible with an empty-run cost.
- While a date still accepts requests, its persisted pre-plan already considers
  mixed delivery/pickup and pickup-only cycles. It completes feasible deliveries
  first, then uses compatible return and remaining-shift capacity for pickups;
  this permits a singleton delivery to collect return work and permits distant
  pickup-only work when no delivery can use that time. The one-way **Close
  request acceptance** command persists the closure and recalculates the same
  delivery-priority model against the final request set. Closed dates are
  removed from newly published customer-slot capacity. When RWMS sync is
  enabled, the same idempotent close command automatically applies the assigned
  RWMS delivery slices from the exact final plan; retrying close retries that
  application and republishes the committed capacity generation without
  rebuilding a different plan. Deterministic warehouse, order, cabin and driver
  mapping is validated before capacity publishing can commit the pending
  closure. If a later remote call fails after the local snapshot was
  committed, the browser reloads the authoritative closed state and reports the
  external exchange as requiring a retry instead of showing a false open day.
  A newly loaded plan also replaces the previous plan-result notification, so
  the operator sees only the current route and unassigned counts.
- A driver's next cycle starts no earlier than the preceding depot return plus
  warehouse unload/turnaround and the configured route buffer. Cycles assigned
  to one driver therefore never overlap.
- `FORBIDDEN` zones reject their customer point and `NO_TRAILER` zones reject
  trailer-attached alternatives; `SPECIAL_PRICE` changes money only. Every
  surviving candidate still requires exact road routing and schedule feasibility.
- A customer stop's `planned_arrival` is the actual service start, never an
  early physical arrival hidden inside a long stop. For the first late window,
  depot loading and departure move closer to the appointment. For later
  windows, the complete routed prefix is shifted at the depot as far as earlier
  windows permit. Only a bounded residual wait may remain at the previous
  customer (`max_customer_wait_minutes`, 120 by default); a longer gap splits
  the work into another depot cycle. Exact Valhalla routing is requested again
  for the actual delayed departure time.
- Within the same mandatory-date priority, the planner prefers a full outbound
  and return load: `2 -> 1 -> 0 -> 1 -> 2 -> 0`. Fewer depot cycles and returns
  rank ahead of weighted distance only while deliveries remain protected. The
  planner builds bounded earliest-deadline, longest-first and nearest-first
  delivery-only references and keeps the one covering the strongest hard-date,
  last-date and total delivery demand; if the mixed draft covers less, it is
  replaced by that reference. It then attaches a nearby pickup only when fully
  rescheduling that driver's remaining cycles preserves every delivery and
  shift limit. The default 35-minute detour and 1.5 ratio limit only extra road
  travel; pickup
  service still counts in ETA, windows, workload and shift-end feasibility. A
  special-price zone cannot make the limits tighter. An oversized return becomes a
  later pickup-only cycle or remains unassigned
  instead of displacing a delivery.
- Candidate ranking first packs compatible delivery and pickup stops, then
  reuses an already activated driver/vehicle shift while it remains feasible.
  Another shift pays `additional_resource_activation_penalty` (180 equivalent
  travel minutes by default) and is activated only when windows, shift limits,
  denser packing or a sufficiently better route justify it. Once every usable
  shift is active, an equally feasible current cycle uses the shift with the
  least remaining slack, preserving the later-finishing resource for work that
  the shorter shift cannot complete.
- Driver workload uses the elapsed duty span against the shift duration after
  its configured break. Work up to `preferred_shift_utilization_percent` (80%
  by default) stays consolidated; minutes above that soft target receive the
  increasing `driver_workload_weight` cost (3 by default). The optimizer
  compares that cost with activating another resource. The normal shift end is
  hard unless the overtime switch is enabled; then only the configured maximum
  overtime is accepted, penalized and surfaced as a warning. The same bounded
  extension is published for customer-slot capacity without crossing midnight.
- One driver can receive several warehouse-return cycles in one shift without
  the former artificial per-cycle imbalance penalty.
- Before a candidate becomes a plan, the backend validates cargo fit, payload,
  trailer availability and an operator-supplied axle-load profile. One unit is
  placed on the truck; a second requires a compatible trailer and is placed on
  it. Missing physical facts return `ROUTING_PROFILE_INCOMPLETE`; incompatible
  cargo is rejected before any routing request.
- Every leg gets a new `EffectiveTruckProfile`. Unloading changes actual mass
  and cargo placement, but does not detach a trailer. Effective height includes
  platform height, width includes the widest equipment/cargo, and combination
  length uses the configured exact value or validated coupling calculation.
  Saved segments retain the complete profile, provider and OSM data-version
  snapshot used for the route.
- Valhalla route failure returns `NO_SAFE_ROUTE`. The application never retries
  with `auto`, OSRM or another passenger-car profile.
- Soft-window and overtime warnings are recalculated from the final exact truck
  timings. A conservative matrix warning is removed when the routed cycle
  actually finishes inside its window and shift; real allowed overtime remains
  visible.
- Manual drag-and-drop submits the expected plan version and is revalidated on
  the backend. The current editor moves tasks between cycles, reorders tasks
  within a cycle and locks whole cycles. A stale mutation returns
  `409 PLAN_VERSION_CONFLICT`.

Unassigned tasks carry stable reason codes and Russian explanations rather
than a generic “route not found”. Plan cards expose the load sequence, distance,
travel/service/wait/detour time, score, warnings and persisted assignment
explanation. Each driver card also shows break-adjusted shift utilization. The
parallel-start summary reports how many activated resources truly need to begin
together; unused drivers and vehicles receive no route.
If no feasible cycle exists, the UI opens the unassigned reasons instead of
claiming routes were built, and simulation remains unavailable until the plan
contains at least one cycle.

Every saved road segment is shown on the map. Cycles receive separate colors,
the map fits the complete plan when it is opened, and the route legend selects
an entire cycle including its return to the warehouse. Repeated depot segments
therefore remain understandable instead of hiding later route legs. The
**Full routes** card is stacked above the optional layer menu, while route
lines are rendered above zones, restrictions and isochrone polygons.

Simulation state is a pure derivation from the saved plan, selected timestamp
and temporary overrides. Seeking backward therefore reproduces the same truck
position, load and event journal. A delay shifts subsequent ETA values without
changing the source plan; driver unavailability freezes the selected truck and
marks its remaining tasks as affected. An override is persisted only when the
request explicitly sets `persist=true`. Simulation mode keeps the full plan and
all cycle cards visible; each active driver status and truck popup shows the
current destination address, ETA and load. The timeline starts at the earliest
assigned shift, so a cycle delayed for a customer window remains seekable as a
truck waiting at the warehouse before loading.

## Start

Requirements: Docker with Compose v2.

```bash
cd logistics
cp .env.example .env
docker compose up --build
```

Navigator-style operator autocomplete and reverse geocoding require separate
server-side Yandex Geosuggest and Geocoder credentials in
`YANDEX_GEOSUGGEST_API_KEY` and `YANDEX_GEOCODER_API_KEY`. They are never sent
to the browser. If either credential is absent, its endpoint fails explicitly
and manual coordinates/address entry remains available; slot and zone logic do
not fabricate a provider result.

Open <http://localhost:5173>. The API documentation is available at
<http://localhost:8000/api/docs> and its schema at
<http://localhost:8000/api/openapi.json>. Compose binds both development ports
to loopback only. Valhalla stays private: Compose exposes its port only on
`127.0.0.1` so the co-located RWMS logistics-service can reuse the same truck
matrix without making routing public. The first `up` downloads and builds both
configured OpenStreetMap extracts and indexes their deduplicated truck
restrictions, so it can take tens of minutes for the combined graph; later
starts reuse `logistics-osrm-data`,
`logistics-valhalla-data` and skip an already imported `OSM_DATA_VERSION`. No
routing or basemap key is required; the optional address workflow uses the two
server-side keys described above.

On the current VPS, Nginx publishes the workspace at
<https://77-90-158-90.sslip.io/logistics-simulator/>. This path-based reverse
proxy build uses `VITE_APP_BASE_PATH=/logistics-simulator/` and
`VITE_API_BASE_URL=/logistics-simulator/api`; the backend remains loopback-only.
The `/logistics/**` namespace remains owned by the primary RWMS panel and must
not redirect to or be shadowed by the standalone simulator. The public Nginx
locations are recorded in `deploy/nginx-public-path.conf`.

### VPS-only RWMS bridge

The ordinary Compose file stays self-contained for local use. On the VPS, the
separate `docker-compose.vps.yml` overlay attaches only `backend` to the
persistent external `rwms-logistics-private` bridge. Create that bridge once
with fixed gateway `172.21.0.1`, deploy
`deploy/nginx-rwms-private-bridge.conf` as an Nginx configuration, and start
with both Compose files. The Nginx listeners are bound only to that Docker
gateway and proxy only `/oauth2/token` and
`/api/internal/logistics/v1/planning/` to loopback-bound RWMS services.

The host firewall must permit TCP from `172.21.0.0/16` only to those two gateway
listeners. Do not bind the RWMS service ports or this bridge to the public VPS
address. `deploy/nginx.service.d/rwms-logistics-private-bridge.conf` makes
Nginx start after Docker so the persistent bridge address exists after reboot.

Useful commands:

```bash
make up
make ps
make logs
make index-truck-restrictions
make down
```

`make reset-db` deletes only the Compose volume named for this simulator. It is
intentionally explicit because the operation removes all local simulator data.
`make index-truck-restrictions` is non-destructive for logistics data and skips
an OSM version that has already been imported.

## Test

The containerized full gate is:

```bash
make test
```

It runs backend tests, Ruff and strict mypy, then frontend Vitest, strict
TypeScript, ESLint and the production build. Equivalent local backend checks:

```bash
cd backend
python -m venv .venv
. .venv/bin/activate
pip install -e '.[dev]'
pytest
ruff check app tests
mypy app
```

Equivalent local frontend checks:

```bash
cd frontend
npm ci
npm run test:run
npm run typecheck
npm run lint
npm run build
```

With Compose running, execute the browser smoke flow:

```bash
cd frontend
npx playwright install chromium
npm run e2e
```

Regenerate the checked transport boundary after a backend API change:

```bash
make openapi
```

## Environment

| Variable | Default | Meaning |
| --- | --- | --- |
| `FRONTEND_PORT` | `5173` | Host port for the operator workspace |
| `BACKEND_PORT` | `8000` | Host port for FastAPI and Swagger |
| `DATABASE_URL` | Compose PostGIS URL | SQLAlchemy database connection |
| `ROUTING_PROVIDER` | `valhalla` | Runtime adapter; `mock` is test-only and `osrm` is an explicit legacy development option |
| `VALHALLA_ENABLED` | `true` | Fails configuration if Valhalla is selected but disabled |
| `VALHALLA_URL` | `http://valhalla:8002` | Private Valhalla endpoint; never exposed through Nginx |
| `VALHALLA_HOST_PORT` | `8002` | Loopback-only host port used by the co-located RWMS logistics-service |
| `VALHALLA_TIMEOUT_SECONDS` | `30` | Deadline for one truck-routing request |
| `VALHALLA_SERVER_THREADS` | `2` | Self-hosted Valhalla worker threads |
| `YANDEX_GEOSUGGEST_API_KEY` | empty | Server-only credential for operator address suggestions |
| `YANDEX_GEOCODER_API_KEY` | empty | Separate server-only credential for suggestion resolution and reverse geocoding |
| `YANDEX_GEOCODING_TIMEOUT_SECONDS` | `5` | Bounded provider deadline from 0 to 30 seconds |
| `OSM_DATA_VERSION` | configured extract identity | Immutable PBF/tile identity stored in route snapshots and cache keys |
| `OSM_RESTRICTIONS_BATCH_SIZE` | `1000` | Atomic PostGIS restriction-import batch size, from 1 to 10000 |
| `OSRM_DATA_URL` | Central Federal District extract | One-time OpenStreetMap PBF download shared with Valhalla |
| `OSM_NORTHWESTERN_DATA_URL` | Northwestern Federal District extract | Second PBF covering Saint Petersburg; its checksum participates in the routing-source manifest |
| `OSRM_BASE_URL`, `OSRM_PROFILE`, `OSRM_TIMEOUT_SECONDS` | legacy values | Used only with provider `osrm` and Compose profile `legacy-routing` |
| `DEFAULT_WAREHOUSE_TIMEZONE` | `Europe/Moscow` | Fallback timezone when a canonical warehouse omits one |
| `PLANNER_DEFAULT_SEED` | `20260822` | Default deterministic tie-break seed |
| `VITE_MAP_STYLE_URL` | empty | Frontend build arg; optional MapLibre style, empty enables grid mode |
| `VITE_API_BASE_URL` | `/api` | Frontend build arg for the same-origin browser API prefix |
| `VITE_APP_BASE_PATH` | `/` | Vite base; use `/logistics-simulator/` on the VPS with API `/logistics-simulator/api` |
| `RWMS_SYNC_ENABLED` | `false` | Explicitly enables authenticated RWMS import/apply operations |
| `RWMS_CAPACITY_PUBLISH_ENABLED` | `false` | Publishes generated delivery/pickup, shift and tariff facts for RWMS slot/price calculation; requires RWMS sync |
| `RWMS_LOGISTICS_BASE_URL` | empty | Private base URL of the RWMS logistics-service planning boundary |
| `RWMS_TOKEN_URL` | empty | Private OAuth2 token endpoint used for client credentials |
| `RWMS_CLIENT_ID` | `logistics-planner` | Dedicated client with only `logistics.planning` scope |
| `RWMS_CLIENT_SECRET` | empty | Runtime-only client secret; required when synchronization is enabled |
| `RWMS_TIMEOUT_SECONDS` | `15` | Timeout for token and RWMS planning requests |

Database credentials in `.env.example` are development-only defaults. Do not
reuse them outside the isolated local simulator.

## RWMS synchronization

RWMS integration is server-owned and automatic. To enable it, provision the
dedicated `logistics-planner` OAuth client in `auth-service`, configure the
`RWMS_*` variables above and expose the canonical warehouse directory. Every
active entry with coordinates is reconciled automatically under its RWMS UUID;
address is display metadata and a missing coordinate pair is an explicit
`COORDINATES_REQUIRED` routing state. Drivers, representative support links and
contractor availability are resolved through their private RWMS directories;
the panel does not ask the operator to connect or refresh a warehouse manually.
An address-only canonical entry can be selected through the single top-bar add
action: the server resolves that RWMS-owned address with the existing geocoder,
keeps the derived point while the address is unchanged, and yields immediately
to owner-held coordinates when RWMS later supplies them.

For a representative warehouse the same planner evaluates local resources,
active operational assignments, every calendar-eligible support warehouse and
confirmed contractor shifts. A support candidate retains its origin, exact
road arrival and availability-after-buffer. It may perform a one-day
cross-warehouse service route without changing its base, or an explicit
resource-reposition transfer may activate a destination assignment after
arrival. Potential resources produce confirmation-required slots; a guaranteed
fixed window still requires a feasible, reservable plan.

`GET /api/warehouses/{warehouse_id}/workspace` refreshes the authoritative
31-day demand horizon before returning the workspace whenever synchronization
is enabled. Orders are upserted by stable `(warehouse, RWMS, orderId)` identity
with their versions and cabin unit IDs. Coordinates are authoritative when
both coordinates and an address exist; an address-only row fails explicitly
with `COORDINATES_REQUIRED` instead of using a fabricated point. Valid sibling
orders remain durable if another row fails, while
`RWMS_WORKSPACE_SYNC_INCOMPLETE` prevents the partial result from being called
current. `refresh_rwms=false` reads that last durable projection for an
explicit recovery view.

Creating or changing an authoritative request invalidates only plans on its
old and new allowed dates. The backend then rebuilds missing draft plans for
complete dates; opening a date idempotently ensures its draft as well. There is
no manual route-build action for the initial draft. Changing planning details
on an existing draft exposes the explicit **Refresh routes** action described
above. Planning begins only when every eligible READY request has either a
valid service window or a soft full-day choice, a trailer-access answer and a
complete cargo profile, and at least one active driver/vehicle shift covers the
date.
Each cycle loads no more than two cabins and returns to the selected warehouse.
The automatic heuristic prioritizes deliveries. Validated manual ordering
preserves the operator's order within the delivery and pickup phases but never
places a pickup before a delivery.

The selected day is read through
`GET /api/warehouses/{warehouse_id}/planning-days/{date}`. **Close delivery
acceptance** calls the idempotent matching `/close` endpoint, recalculates that
day and, when synchronization is enabled, automatically applies assigned RWMS
deliveries from the final plan. The exact plan/version is the optimistic and
idempotency fence; a stale version fails instead of overwriting a newer plan.
Low-level plan apply/status endpoints remain available only for audited
recovery and diagnostics and are not exposed as a routine exchange dialog.

Capacity publication is a separate opt-in and requires synchronization.
Warehouse resource mutations and generated-workload replacement publish one
versioned complete snapshot of generated delivery/pickup demand, active
period-based shifts, vehicle capacity and only that warehouse's exceptional zones. Each committed
mutation advances a warehouse capacity generation, so retries are idempotent
and delayed older generations are rejected. Manual requests and imported RWMS
orders are not re-published as generated demand; replacing capacity never
deletes a real RWMS booking. `POST
/api/warehouses/{warehouse_id}/rwms/capacity` is an operational reconciliation
endpoint, not a panel button.

No local database transaction remains open across OAuth or RWMS HTTP I/O.
Import closes its read transaction before fetching the feed; apply freezes and
commits the exact command snapshot before the idempotent remote call.

## Valhalla and OpenStreetMap truck routing

The default `ROUTING_PROVIDER=valhalla` uses a bounded Haversine matrix only to
prefilter candidate combinations. A candidate is not feasible until every leg
has been independently requested from private Valhalla with `costing=truck`
and the leg's `EffectiveTruckProfile`. Exact Valhalla distance and duration are
then used to reschedule windows, shift finish and objective cost. The saved
GeoJSON and profile snapshot power route cards, map lines, diagnostics and
simulation. The browser rejects an invalid segment geometry or a plan reference
to a missing task; it never invents a straight line, zero coordinate or
placeholder task for incomplete authoritative state.

The profile contains actual effective height, width, full combination length,
gross weight, axle count and configured maximum actual axle load. It is derived
from the persisted vehicle/trailer/platform/cargo placement state, not copied
from a generic vehicle category. Cache identity includes endpoints, profile,
departure time, provider version and `OSM_DATA_VERSION`. See the audited
capability table in
[`docs/osm-truck-restrictions.md`](docs/osm-truck-restrictions.md); unsupported
conditional tags are explicitly identified there rather than claimed as safe.

The map layer menu has an optional **Ограничения грузового транспорта** switch.
At zoom 8 or closer it loads a bounded PostGIS viewport from the same
`OSM_DATA_VERSION`, draws road restrictions and point signs with textual
markers, and opens tag/support diagnostics on click. Partial and unsupported
tags stay visibly labelled; the overlay never decides route feasibility and
never substitutes for Valhalla truck costing. OSM turn-restriction relations
remain routing-graph behavior and are not fabricated as clickable lines.

The graph has no live traffic feed: durations reflect the built OSM graph, not
Yandex traffic. The normal runtime combines `OSRM_DATA_URL` and
`OSM_NORTHWESTERN_DATA_URL`; their source manifest forces a rebuild of derived
Valhalla admin/tiles/extract files when either PBF changes. Update
`OSM_DATA_VERSION` with that source set so route snapshots, cache keys and the
deduplicated restriction index agree. This never deletes the separate logistics
PostGIS volume. The map's working-area footer is disabled; deployments remain
responsible for OpenStreetMap attribution under the applicable licence. Do not
expose the private router publicly.

For a backwards-compatibility development run only, use
`docker compose --profile legacy-routing up osrm`; selecting `osrm` does not
provide truck safety and is never an automatic fallback.

## MockRoutingProvider

The explicit test provider needs no network. It computes Haversine distance, applies
the selected warehouse road factor, selects city/region average speed, applies a simple
departure-time traffic multiplier and returns distances, durations, per-leg
facts and GeoJSON LineStrings. Stable inputs and seed produce stable results.
Matrix caching keys include points, routing settings, departure bucket and
warehouse settings version.

## Vehicle, trailer and cargo configuration

Open **Vehicles**, create or edit a vehicle and fill the **Truck routing**
section: tare/gross mass, dimensions, axle capability, platform geometry,
cargo limits and safety margins. Create a trailer in the same workspace,
select it as the vehicle's default compatible trailer and enter either the
exact combined length or the coupling length used to derive it. Finally provide
the measured `maxActualAxleLoadKg` for every operational state used by the
vehicle; the application deliberately never estimates it as total mass divided
by axle count. For a combination this scalar is the measured peak among all
truck and trailer axles; it is not a per-component axle-load vector, so each
component's limits still require an operational measurement. The vehicle
fields and complete axle-profile set are saved by one
backend transaction, so a failed validation cannot leave a half-edited truck.

The form previews one-unit and two-unit configurations before save. Generated
requests use the cargo dimensions entered in **Request generator**; the manual
request editor accepts the same complete length/width/height/weight tuple. The
current RWMS planning contract does not expose those physical cargo fields, so
the simulator applies the warehouse's explicit standard-cargo settings to a new
RWMS delivery when the source has no measured profile. The shipped defaults are
`6000 x 2400 x 2400 mm` and `1200 kg`; an administrator must keep them aligned
with the operation's actual standard cabin. An already enriched request is not
overwritten by this fallback. In a built plan, open a vehicle/cycle and expand
**Route diagnostics** to see the immutable profile used on every leg. A legacy
segment without a snapshot is visibly unverified and cannot stay locked during
Valhalla reoptimization.

The real-engine acceptance test builds a disposable tagged OSM graph and proves
that a 9 m vehicle takes the short road while an 18 m combination avoids its
`maxlength=12` restriction. The same graph checks height, width, gross weight,
axle load and `hgv=no`:

```bash
make test-valhalla-truck
```

## Heuristic planner

`HeuristicPlanner` intentionally models the operational delivery-then-pickup
pattern instead of pretending to be a universal VRP solver. It:

1. validates dated shifts/resources and splits ready requests;
2. builds the deterministic travel matrix;
3. prioritizes deliveries by hard/last-date, manual priority, scarce dates and
   narrow windows, then batches equal-priority work nearest-first;
4. schedules every candidate just in time from the depot, shifts its routed
   prefix as far as earlier windows permit, and rejects combinations whose
   residual customer wait exceeds `max_customer_wait_minutes`;
5. considers bounded delivery pairs and both orders;
6. considers at most `max_candidate_neighbors` detour-ranked pickup groups per
   delivery group and keeps only groups inside both detour limits;
7. compares the mixed draft with bounded deadline-first, longest-first and
   nearest-first delivery-only references and
   restores it if mandatory/last-date/total delivery coverage fell, then tries
   pickup attachments only through a fully valid reschedule of the shift suffix;
8. allows a later depot-loaded cycle for the same driver and creates penalized
   pickup-only cycles after no remaining delivery candidate fits, whether
   request acceptance is open or closed;
9. globally assigns the best next feasible cycle to an available driver;
10. compares resource activation against break-adjusted driver workload;
11. performs bounded, fully revalidated local improvements;
12. persists metrics, explanations, reasons and bounded trace events.

The engine stops predictably at the configured time/iteration limit and
returns the best valid plan found. The optimization seed is stored with the
run.

## Development workload APIs and reproducibility

For a configurable load, choose **Request generator**. Set the first date, a
one-to-31-day horizon, zero-to-ten deliveries and pickups per day, up to three
additional accepted dates and a seed. One transaction removes saved plans in
that horizon, replaces only generator-owned requests whose preferred date is in
the horizon and creates the new batch. Manual and RWMS requests, generated work
for other dates and plans outside the horizon remain unchanged. A routing or
road-snap failure restores the previous workload.

Generated deliveries receive hard windows round-robin `09:00-12:00`,
`12:00-15:00` and `15:00-18:00`; pickups use the warehouse workday window.
Every candidate starts inside a tariff zone owned by the selected warehouse and, with Valhalla, must
snap to an in-zone road point or the complete run fails with
`422 NO_ROUTABLE_POINT_IN_ZONE`. Equal warehouse-zone geometry, routing graph, input
and seed reproduce the same business values. Stable external identities prevent
a repeated generator run from creating duplicate logical visits.

**Delete workload** removes generated requests and saved plans only for the
selected warehouse date. The date control, request list and map display the
same filtered work; delivery and pickup markers open their request details.
Warehouse settings store independent non-negative whole-ruble prices for the
60/120/180/240-minute isochrone bands. A `SPECIAL_PRICE` zone has its explicit
override; `FORBIDDEN` and `NO_TRAILER` carry no price. The resolved tariff is
shown in the request and dynamic-slot views and published through the existing
capacity boundary.

The Playwright mutation smoke is disabled unless
`RWMS_E2E_DISPOSABLE_DATABASE=true`; without that explicit guard the browser
suite is read-only.

## MVP limitations

This is an internal planning laboratory, not a production dispatch system. It
has a local `local-admin` audit actor and no interactive production identity
model, real traffic/GPS, embedded driver application, push messages, customer
checkout or payment flows. Its optional machine-to-machine RWMS access is
limited to the dedicated planning scope. Valhalla uses the pinned static OSM
extract and therefore does not know live traffic or closures unless a separate
feed is configured. Its supported and unsupported truck tags are documented;
the system does not claim complete support for arbitrary conditional numeric
restrictions. Mock geometry is a straight GeoJSON polyline and remains
available only for deterministic tests.

The delivered P1 subset includes persisted planner phases/candidate events and
driver-unavailability simulation. The following extensions
remain deliberately outside this MVP:

- a side-by-side saved-plan comparison screen;
- a dedicated multi-contour drawing workflow for a new MultiPolygon (import,
  storage, rendering and editing of existing MultiPolygon values are supported);
- manual split/merge of cycles and a full remaining-day replan from a truck's
  interpolated current position; current reoptimization preserves locked
  cycles and rebuilds unlocked work from the warehouse;
- the complete cross-cycle local-search operator set (automatic cycle
  move/swap, pickup replacement and cycle merge/split). The bounded post-pass
  currently retries both delivery and pickup orders; task move/reorder between
  cycles remains available through the backend-validated manual editor;
- coordinate-rich animated candidate lines in every trace event; real phases
  and candidate decisions are persisted, while the UI draws a candidate line
  only when an event contains coordinates;
- an external optimization worker with interruptible live cancellation. The
  current bounded heuristic executes in the API process and returns a terminal
  run, so the persisted cancel flag is an integration boundary rather than a
  reliable way to interrupt an already executing request;
- a Yandex/other traffic-aware routing adapter, OR-Tools/CP-SAT engine,
  production auth, high-volume benchmark certification and additional incidents.

The production frontend currently emits Vite's large-chunk warning (about
1.70 MB before gzip); route-level code splitting is a follow-up optimization,
not a functional startup blocker.

## Add another real routing provider

Implement `RoutingProvider.get_matrix()` and `get_route()` in
`backend/app/routing`, return the existing typed distance/duration/leg/geometry
contract, add provider-specific configuration and select it through
`ROUTING_PROVIDER`. Keep credentials server-side, add timeout/rate-limit/cache
handling and run the shared provider contract tests. A future Yandex adapter
must never be required for mock-mode startup and cannot replace final domain
validation.

## Add OR-Tools or CP-SAT

Implement the `PlannerEngine.generate_plan()` protocol in
`backend/app/planner`, map the same immutable planning input and settings, and
return the same validated cycles, explanations, metrics, reasons and progress
events. Enable the engine through explicit configuration and keep the
heuristic as the deterministic zero-extra-dependency implementation. No solver
result may bypass the shared hard-invariant validator.
