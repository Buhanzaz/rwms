# RWMS Logistics Simulator

[Русская версия](README.ru.md)

An internal, standalone workspace for designing, planning and replaying cabin
delivery and pickup days. It remains a separate deployable with its own data,
but can use an explicitly enabled, OAuth-protected planning boundary to import
orders from the active RWMS `logistics-service` and apply a reviewed plan back.
There is no customer checkout, billing, GPS tracking, 1C or Bitrix integration
inside the simulator.

The default deployment uses a private Valhalla 3.8.3 service built from
OpenStreetMap data and always requests `costing=truck`. Every road leg is
calculated for the vehicle, attached trailer and cargo remaining on that exact
leg. There is no silent car-route fallback. No API key is needed; internet is
needed only to download the selected regional extract and optional map style.
If the MapLibre style cannot load, the editor falls back to its coordinate grid
while zone editing and saved-route simulation remain available.

## Interface

```text
┌ scenario / date / generate / validate / save / editor-plan-simulation ┐
├───────────────┬─────────────────────────────────────┬─────────────────┤
│ scenario      │                                     │ selected object │
│ warehouse     │       MapLibre or grid map          │ form / warnings │
│ zones         │  zones · requests · routes · trucks │ route metrics   │
│ relations     │                                     │ explanation     │
│ drivers       │                                     │                 │
│ vehicles      │                                     │                 │
│ shifts        │                                     │                 │
│ requests      │                                     │                 │
│ routes        │                                     │                 │
│ unassigned    │                                     │                 │
│ settings      │                                     │                 │
├───────────────┴─────────────────────────────────────┴─────────────────┤
│ simulation: start · previous · play/pause · next · end · speed · time │
└───────────────────────────────────────────────────────────────────────┘
```

The supported flow creates or resets a scenario, places a warehouse, draws
zones, manages directed relations, creates resources and requests, generates a
plan, validates manual changes and replays vehicles at any timestamp. The
built-in demo supplies four zones, three drivers, three capacity-two vehicles,
shifts and compatible delivery/pickup tasks. It deliberately uses a 60-minute,
3.0-ratio detour allowance so both mock routing and the local Valhalla graph produce
a minimum-cycle plan without delivery-only returns; ordinary scenarios keep the
default 35-minute, 1.5-ratio policy. Demo vehicles also contain complete
physical, platform, trailer and operational axle-load profiles, so their
routes can be verified by Valhalla without invented dimensions.

The **Test for 3 days** action creates a separate, non-destructive workload:
six zones, exactly three simultaneously available vehicles/drivers, and three
shift dates. Each date exposes exactly ten requests: some first-day clients are
also available on day two, and two day-two requests are available on day three.
The shared planning date filters both the request list and map markers. Until
the dispatcher selects one date, a request appears on every customer-approved
date; afterwards it appears only on the selected logistics date. Clicking a
marker opens a MapLibre popup anchored above that point with address, quantity,
zone, windows and date selection.
A new date requires an explicit agreement action, an assignment can be cleared,
and the original accepted dates remain intact. To make return
pairing observable, this fixture uses a wider detour limit of 60 minutes and a
3.0 ratio; the scenario settings remain editable.

## Architecture

This directory is a monorepo with isolated runtime containers:

- `frontend/`: React, strict TypeScript, Vite, MapLibre GL JS, Terra Draw,
  TanStack Query, Zustand, React Hook Form/Zod and dnd-kit;
- `backend/`: FastAPI, Pydantic, SQLAlchemy 2, GeoAlchemy2/Shapely and Alembic;
- `db`: PostgreSQL with PostGIS and one simulator-only named volume.
- `osrm-download`: a one-shot PBF downloader shared read-only with Valhalla;
- `valhalla-init` and `valhalla`: private tile preparation and truck routing;
- `truck-restrictions-indexer`: a one-shot, versioned Osmium import of
  truck-related OSM nodes/ways into the PostGIS viewport index;
- legacy `osrm-prepare`/`osrm` exist only behind the explicit
  `legacy-routing` Compose profile and are never a truck fallback.

Nginx serves the production frontend and proxies same-origin `/api` and SSE to
FastAPI. The browser never chooses authoritative request zones. The backend
stores scenario facts, zone versions, plans, validation results, explanations,
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

The persisted model contains scenarios and settings, warehouses, versioned
Polygon/MultiPolygon zones with interior rings, direction-specific test prices
and directed relations, drivers, vehicles and dated shifts, logistics requests,
date options and a separate
dispatcher-selected logistics date, split planning tasks, versioned
route plans/cycles/stops/legs, optimization runs/trace events and manual-change
audit. All timestamps are timezone-aware; the default scenario timezone is
`Europe/Moscow`.

Request coordinates are classified server-side with PostGIS. The highest
priority covering zone wins; an equal-priority overlap selects the smallest
geometry. A point outside all zones is retained as `OUTSIDE_ZONES`. Editing a
zone increments its version but does not silently rewrite old request
membership. The **Make cutout** tool selects an unlocked source zone and then
draws a strictly internal ring. It opens the new inner-zone form; saving one
atomic backend command subtracts the ring from the source and creates a
version-one operational zone with exactly that geometry. Cancelling the form
changes neither zone. Request reclassification remains explicit after geometry
changes: **Build routes** detects visible ready requests with missing/stale zone
snapshots and asks the dispatcher to confirm reclassification before starting
the optimizer.

## Logistics rules

- Vehicle capacity defaults to two cabins and every stop preserves
  `0 <= load <= capacity`.
- A request larger than capacity is deterministically split, for example
  `5 -> [2, 2, 1]`.
- Every cycle starts and returns to the warehouse. Inside that cycle, all
  warehouse-loaded deliveries precede all pickups. Returning to the depot
  closes the cycle, so the same driver may load deliveries again in a later
  independent cycle.
- A cycle may carry one quantity-two delivery or pair two quantity-one
  deliveries, then attach one quantity-two pickup or pair two quantity-one
  pickups. Pickup-only cycles remain possible with an empty-run cost.
- Zone relations are a candidate filter, not a geographic hard-code. A
  `BLOCKED` relation or disabled pickup transition is hard; both stop orders,
  actual time windows, travel length, shift end and capacity are validated.
- Within the same mandatory-date priority, the planner prefers a full outbound
  and return load: `2 -> 1 -> 0 -> 1 -> 2 -> 0`. Fewer depot cycles and returns
  rank ahead of weighted distance only while deliveries remain protected. The
  planner builds bounded longest-first and nearest-first delivery-only
  references and keeps the one covering the strongest hard-date, last-date and
  total delivery demand; if the mixed draft covers less, it is replaced by that
  reference. It then attaches a nearby pickup only when fully rescheduling that
  driver's remaining cycles preserves every delivery and shift limit. The
  default 35-minute detour and 1.5 ratio limit only extra road travel; pickup
  service still counts in ETA, windows, workload and shift-end feasibility. A
  zone relation may make the travel limits tighter. An
  oversized return becomes a later pickup-only cycle or remains unassigned
  instead of displacing a delivery.
- Driver route groups are soft preferences. Hard dates/windows, task
  uniqueness, driver/vehicle overlap and hard shift limits remain infeasible
  constraints.
- Candidate ranking first packs compatible delivery and pickup stops, then
  reuses an already activated driver/vehicle shift while it remains feasible.
  Another shift pays `additional_resource_activation_penalty` (180 equivalent
  travel minutes by default) and is activated only when windows, shift limits,
  denser packing or a sufficiently better route justify it.
- Driver workload uses the elapsed duty span against the shift duration after
  its configured break. Work up to `preferred_shift_utilization_percent` (80%
  by default) stays consolidated; minutes above that soft target receive the
  increasing `driver_workload_weight` cost (3 by default). The optimizer
  compares that cost with activating another resource, while the actual shift
  end remains a hard constraint.
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
therefore remain understandable instead of hiding later route legs.

Simulation state is a pure derivation from the saved plan, selected timestamp
and temporary overrides. Seeking backward therefore reproduces the same truck
position, load and event journal. A delay shifts subsequent ETA values without
changing the source plan; driver unavailability freezes the selected truck and
marks its remaining tasks as affected. An override is persisted only when the
request explicitly sets `persist=true`. Simulation mode keeps the full plan and
all cycle cards visible; each active driver status and truck popup shows the
current destination address, ETA and load.

## Start

Requirements: Docker with Compose v2.

```bash
cd logistics
cp .env.example .env
docker compose up --build
```

Open <http://localhost:5173>. The API documentation is available at
<http://localhost:8000/api/docs> and its schema at
<http://localhost:8000/api/openapi.json>. Compose binds both development ports
to loopback only. Valhalla is internal to the Compose network and has no host
port. The first `up` downloads and builds the configured OpenStreetMap extract
and indexes its truck restrictions, so it can take tens of minutes for a
regional graph; later starts reuse `logistics-osrm-data`,
`logistics-valhalla-data` and skip an already imported `OSM_DATA_VERSION`. No
routing or map key is required.

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
`make index-truck-restrictions` is non-destructive for scenario data and skips
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
| `VALHALLA_TIMEOUT_SECONDS` | `30` | Deadline for one truck-routing request |
| `VALHALLA_SERVER_THREADS` | `2` | Self-hosted Valhalla worker threads |
| `OSM_DATA_VERSION` | configured extract identity | Immutable PBF/tile identity stored in route snapshots and cache keys |
| `OSM_RESTRICTIONS_BATCH_SIZE` | `1000` | Atomic PostGIS restriction-import batch size, from 1 to 10000 |
| `OSRM_DATA_URL` | Central Federal District extract | One-time OpenStreetMap PBF download shared with Valhalla |
| `OSRM_BASE_URL`, `OSRM_PROFILE`, `OSRM_TIMEOUT_SECONDS` | legacy values | Used only with provider `osrm` and Compose profile `legacy-routing` |
| `DEFAULT_SCENARIO_TIMEZONE` | `Europe/Moscow` | Timezone for new scenarios |
| `PLANNER_DEFAULT_SEED` | `20260822` | Default deterministic tie-break seed |
| `VITE_MAP_STYLE_URL` | empty | Frontend build arg; optional MapLibre style, empty enables grid mode |
| `VITE_API_BASE_URL` | `/api` | Frontend build arg for the same-origin browser API prefix |
| `VITE_APP_BASE_PATH` | `/` | Vite base; use `/logistics-simulator/` on the VPS with API `/logistics-simulator/api` |
| `RWMS_SYNC_ENABLED` | `false` | Explicitly enables authenticated RWMS import/apply operations |
| `RWMS_LOGISTICS_BASE_URL` | empty | Private base URL of the RWMS logistics-service planning boundary |
| `RWMS_TOKEN_URL` | empty | Private OAuth2 token endpoint used for client credentials |
| `RWMS_CLIENT_ID` | `logistics-planner` | Dedicated client with only `logistics.planning` scope |
| `RWMS_CLIENT_SECRET` | empty | Runtime-only client secret; required when synchronization is enabled |
| `RWMS_TIMEOUT_SECONDS` | `15` | Timeout for token and RWMS planning requests |

Database credentials in `.env.example` are development-only defaults. Do not
reuse them outside the isolated local simulator.

## RWMS synchronization

RWMS integration is disabled by default. To enable it, provision the dedicated
`logistics-planner` OAuth client in `auth-service`, set the six `RWMS_*`
variables above, and link the simulator warehouse through
`external_warehouse_id` to the RWMS warehouse UUID. Drivers that may receive
an applied route must similarly have `external_worker_id` set to their RWMS
worker UUID.

The operator explicitly synchronizes a scenario and date range through
`POST /api/scenarios/{scenario_id}/rwms/sync`. The simulator upserts orders by
the stable `(scenario, RWMS, orderId)` identity, keeps RWMS order versions and
cabin unit IDs, and reclassifies coordinates through its own versioned zones.
If both an address and coordinates exist, coordinates are authoritative.
Address-only orders are reported as `COORDINATES_REQUIRED` and are not imported:
no public geocoder or fabricated coordinate fallback is used.

No simulator database transaction remains open across OAuth or RWMS HTTP I/O.
Sync closes its read transaction before fetching the feed; apply freezes the
exact plan/version command under a short local lock, commits that read snapshot,
and only then performs the idempotent remote call.

After review and validation, the operator applies the exact route-plan version
through `POST /api/plans/{plan_id}/rwms/apply`. The adapter maps split planning
parts back to non-overlapping cabin unit IDs and sends an idempotent, optimistic
assignment command. RWMS remains the order/shipment owner and returns every
applied or rejected assignment; a stale simulator version fails instead of
silently overwriting a newer plan. The automatic boundary rejects assignments
for today and tomorrow; those urgent changes remain an explicit manual
logistics decision in RWMS.

Unassigned deliveries remain private to the operator by default. In the RWMS
exchange dialog the operator may explicitly select individual delivery parts
to publish as future `WAREHOUSE_DRIVERS` work. That deliberate publication may
target tomorrow but never the warehouse-local current day, carries no invented
driver identity, and is revalidated by RWMS before DriverApp can preview and
claim it. Pickup/return publication is not part of this boundary yet.

The same dialog reads `GET /api/plans/{plan_id}/rwms/status` for the exact plan
version. It shows whether each future delivery is still unpublished, visible to
all warehouse drivers, or already claimed by a named driver. Published and
claimed parts cannot be selected again; if the authoritative status cannot be
read, shared publication is disabled instead of guessing from local state. The
simulator releases its database transaction before this read-only RWMS call.

## Valhalla and OpenStreetMap truck routing

The default `ROUTING_PROVIDER=valhalla` uses a bounded Haversine matrix only to
prefilter candidate combinations. A candidate is not feasible until every leg
has been independently requested from private Valhalla with `costing=truck`
and the leg's `EffectiveTruckProfile`. Exact Valhalla distance and duration are
then used to reschedule windows, shift finish and objective cost. The saved
GeoJSON and profile snapshot power route cards, map lines, diagnostics and
simulation.

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
Yandex traffic. To change regions, update `OSRM_DATA_URL` and
`OSM_DATA_VERSION`, then explicitly rebuild the routing-data volumes; this does
not delete the separate PostGIS scenario volume. OpenStreetMap attribution
remains visible through MapLibre. Do not expose the private router publicly.

For a backwards-compatibility development run only, use
`docker compose --profile legacy-routing up osrm`; selecting `osrm` does not
provide truck safety and is never an automatic fallback.

## MockRoutingProvider

The explicit test provider needs no network. It computes Haversine distance, applies
the scenario road factor, selects city/region average speed, applies a simple
departure-time traffic multiplier and returns distances, durations, per-leg
facts and GeoJSON LineStrings. Stable inputs and seed produce stable results.
Matrix caching keys include points, routing settings, departure bucket and
scenario version.

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
a newly imported RWMS request remains explicitly
`ROUTING_PROFILE_INCOMPLETE` until an operator enters the measured values; the
simulator never guesses them. In a built plan, open a vehicle/cycle and expand
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
4. considers bounded delivery pairs and both orders;
5. considers at most `max_candidate_neighbors` detour-ranked pickup groups per
   delivery group and keeps only groups inside both detour limits;
6. compares the mixed draft with one bounded delivery-only reference and
   restores it if mandatory/last-date/total delivery coverage fell, then tries
   pickup attachments only through a fully valid reschedule of the shift suffix;
7. allows a later depot-loaded cycle for the same driver and creates penalized
   pickup-only cycles only after no delivery candidate remains;
8. globally assigns the best next feasible cycle to an available driver;
9. compares resource activation against break-adjusted driver workload;
10. performs bounded, fully revalidated local improvements;
11. persists metrics, explanations, reasons and bounded trace events.

The engine stops predictably at the configured time/iteration limit and
returns the best valid plan found. The optimization seed is stored with the
run.

## Demo and reproducibility

On first launch create a scenario, open **Scenario** in the left panel and
choose **Demo scenario**. Alternatively, reset any selected scenario to its
demo state after confirming the scenario-scoped replacement. Select the demo
planning date and press **Build routes**. The demo is safe to edit and can be
recreated at any time. Its primary mixed cycle is
`depot -> delivery -> delivery -> pickup -> pickup -> depot` with the load
sequence `2 -> 1 -> 0 -> 1 -> 2 -> 0`. This reproducibility fixture uses the
scenario-local 60-minute/3.0 detour allowance described above. Mock routing
reproduces that exact illustrative sequence; Valhalla selects the truck-safe
equivalent on its OSM graph. In either case every cycle
delivers before collecting, respects load `0..2`, and returns to the depot. The
fixture does not change the defaults of newly created scenarios.

For date and capacity experiments, use **Test for 3 days** instead. It never
resets the scenario currently open. The header date, **Requests** date chips,
and map show the same date-filtered requests. Click a **Д** (delivery) or **В**
(return) marker to inspect its details in a popup above the point and assign one
concrete logistics date directly on the map. To test nested geography, choose
**Make cutout**, click the source zone and draw the inner area. Complete the
form to name the new zone; the source cutout and inner-zone creation are saved
together. Both actions record a dispatcher decision in the simulator; neither
confirms a production dispatch.

For a configurable workload, choose **Request generator** in the current
scenario. It opens in one-day mode with no alternative dates. Set the first
date, a one-to-31-day horizon, an exact daily count of zero-to-ten deliveries
and zero-to-ten pickups, zero-to-three additional accepted dates per request,
and a seed. Additional dates are selected only inside the horizon. Cargo
defaults are 6000×2400×2400 mm and 1200 kg; they remain editable before
generation. Every run first deletes all saved plans whose date is inside the
selected horizon, then removes requests previously owned by this generator
whose preferred date is inside that horizon and creates the replacement batch
in the same database transaction. It never appends a second generated batch to
the same date. The response shows the number of deleted plans and replaced
requests. Every generated request is explicitly assigned to its preferred date;
alternative accepted dates remain visible for a later manual dispatcher
decision but never move work to another day automatically. Manual and RWMS
requests, generated requests for other dates and plans on other dates are never
selected. If road snapping or later generation fails, the transaction restores
the previous plans and workload. Use **Delete workload** to remove both the
generated workload and all saved plans of the date currently selected in the
header without creating a replacement. Every generated candidate starts inside
a current Polygon or MultiPolygon. With
Valhalla it is snapped through `/locate` and accepted only when the
resulting road point is still covered by that zone; an unavailable in-zone road
fails the complete atomic run with `422 NO_ROUTABLE_POINT_IN_ZONE`. The mock
provider keeps the deterministic candidate for offline tests. Normal backend
classification then creates the request and its transport parts. Equal
geometry, routing graph, inputs and seed reproduce the same business values.
New generated requests also receive stable source identities. Until a dated
workload is replaced, automatic planning schedules only the oldest legacy
duplicate logical source and reports repeats as
`DUPLICATE_ASSIGNMENT_CONFLICT`. A replacement removes those generator-owned
legacy rows together with plans of the affected dates. New rows use their
stable external ID; legacy generator rows without one are matched by direction,
exact generated point and primary logistics date rather than mutable display
numbers, notes or quantities. Renumbering a displayed `№3` as `№7` therefore
cannot create another visit.
Real manual and RWMS requests retain their authoritative identities even when
two customers intentionally use the same address. Scenario clone and JSON
export/import preserve that source system, external identity, source version
and source payload, so reproducibility does not disable duplicate protection or
RWMS synchronization lineage.

Each zone also stores independent non-negative whole-ruble test prices for a
delivery and a pickup. The editor, zone list and request popup show the
applicable price. A tariff change does not alter geometry version or planner
feasibility and is preserved by clone and JSON import/export.

Use **Export JSON** in the scenario actions. The single file can include
settings, geography, resources, requests/date options, seed and selected saved
plans, including request source identity. **Import JSON** validates the complete
document in one transaction; a
failure creates no partial scenario. Exported scenarios are intended for bug
reports and seed-based planner reproduction and must not contain customer
production data.

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

The delivered P1 subset includes persisted planner phases/candidate events,
plan cloning and driver-unavailability simulation. The following extensions
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
