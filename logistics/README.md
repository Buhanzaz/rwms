# RWMS Logistics Simulator

[Русская версия](README.ru.md)

An internal, standalone workspace for designing, planning and replaying cabin
delivery and pickup days. It remains a separate deployable with its own data,
but can use an explicitly enabled, OAuth-protected planning boundary to import
orders from the active RWMS `logistics-service` and apply a reviewed plan back.
There is no customer checkout, billing, GPS tracking, 1C or Bitrix integration
inside the simulator.

The default deployment uses a private OSRM service built from OpenStreetMap
road data for real driving distance, duration and route geometry, without API
keys. It needs internet access only once to download the selected regional
road extract. The default MapLibre style is OpenFreeMap/OpenStreetMap; if it
cannot load, the editor falls back to its built-in coordinate grid while zone
editing and saved-route simulation remain available.

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
shifts and compatible delivery/pickup tasks.

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

This directory is a monorepo with six isolated runtime containers:

- `frontend/`: React, strict TypeScript, Vite, MapLibre GL JS, Terra Draw,
  TanStack Query, Zustand, React Hook Form/Zod and dnd-kit;
- `backend/`: FastAPI, Pydantic, SQLAlchemy 2, GeoAlchemy2/Shapely and Alembic;
- `db`: PostgreSQL with PostGIS and one simulator-only named volume.
- `osrm-download` and `osrm-prepare`: one-shot download and MLD graph preparation;
- `osrm`: private OpenStreetMap-backed driving router with no host port.

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
├── docker-compose.yml    db, private OSRM, backend, frontend and test tools
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
Polygon/MultiPolygon zones with interior rings and directed relations, drivers,
vehicles and dated shifts, logistics requests, date options and a separate
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
  rank ahead of weighted distance. The default 35-minute and 1.5 detour
  thresholds add `HIGH_DETOUR` and score cost rather than forcing a separate
  pickup cycle; a zone relation may set tighter warning thresholds. Capacity,
  hard windows, shift end and blocked transitions remain infeasible.
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

Every saved OSRM segment is shown on the map. Cycles receive separate colors,
the map fits the complete plan when it is opened, and the route legend selects
an entire cycle including its return to the warehouse. Repeated depot segments
therefore remain understandable instead of hiding later route legs.

Simulation state is a pure derivation from the saved plan, selected timestamp
and temporary overrides. Seeking backward therefore reproduces the same truck
position, load and event journal. A delay shifts subsequent ETA values without
changing the source plan; driver unavailability freezes the selected truck and
marks its remaining tasks as affected. An override is persisted only when the
request explicitly sets `persist=true`.

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
to loopback only. OSRM is internal to the Compose network and has no host port.
The first `up` downloads and prepares the configured OpenStreetMap extract, so
it can take several minutes; later starts reuse `logistics-osrm-data`. No
routing or map key is required.

On the current VPS, Nginx publishes the workspace at
<https://77-90-158-90.sslip.io/logistics/>. This path-based reverse proxy build
uses `VITE_APP_BASE_PATH=/logistics/` and
`VITE_API_BASE_URL=/logistics/api`; the backend remains loopback-only.

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
make down
```

`make reset-db` deletes only the Compose volume named for this simulator. It is
intentionally explicit because the operation removes all local simulator data.

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
| `ROUTING_PROVIDER` | `osrm` | Routing adapter selector (`mock` remains available for deterministic tests) |
| `OSRM_BASE_URL` | `http://osrm:5000` | Private OSRM service used when provider is `osrm` |
| `OSRM_PROFILE` | `driving` | OSRM routing profile |
| `OSRM_TIMEOUT_SECONDS` | `15` | Per-request OSRM timeout |
| `OSRM_DATA_URL` | Central Federal District extract | One-time OpenStreetMap PBF download for the OSRM volume |
| `DEFAULT_SCENARIO_TIMEZONE` | `Europe/Moscow` | Timezone for new scenarios |
| `PLANNER_DEFAULT_SEED` | `20260822` | Default deterministic tie-break seed |
| `VITE_MAP_STYLE_URL` | empty | Frontend build arg; optional MapLibre style, empty enables grid mode |
| `VITE_API_BASE_URL` | `/api` | Frontend build arg for the same-origin browser API prefix |
| `VITE_APP_BASE_PATH` | `/` | Vite base; use `/logistics/` on the VPS with API `/logistics/api` |
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

## OSRM and OpenStreetMap routing

The default `ROUTING_PROVIDER=osrm` sends the heuristic's full directed matrix
to the private OSRM Table API and uses OSRM Route API geometry for every saved
route segment. Thus route cards, map polylines and truck interpolation use the
road graph rather than straight candidate lines. OSRM currently has no live
traffic feed: durations come from the prepared driving profile, not Yandex
traffic. The graph for the Central Federal District covers the built-in Moscow
demo. To select another region, set `OSRM_DATA_URL`, explicitly remove only the
`logistics-osrm-data` volume, then start Compose again; this discards routing
cache data but not the PostGIS scenario volume.

OpenStreetMap attribution remains visible through the MapLibre base style.
Use OpenStreetMap-derived data under its applicable ODbL attribution and
share-alike terms; do not expose the private OSRM service as a public router.

## MockRoutingProvider

The default provider needs no network. It computes Haversine distance, applies
the scenario road factor, selects city/region average speed, applies a simple
departure-time traffic multiplier and returns distances, durations, per-leg
facts and GeoJSON LineStrings. Stable inputs and seed produce stable results.
Matrix caching keys include points, routing settings, departure bucket and
scenario version.

## Heuristic planner

`HeuristicPlanner` intentionally models the operational delivery-then-pickup
pattern instead of pretending to be a universal VRP solver. It:

1. validates dated shifts/resources and splits ready requests;
2. builds the deterministic travel matrix;
3. prioritizes hard/last-date, manual priority, scarce dates, narrow windows,
   distance and creation time;
4. considers bounded delivery pairs and both orders;
5. packs return pickups into every compatible delivery cycle before comparing
   travel/detour cost, minimizing warehouse returns for equal hard priority;
6. allows a later depot-loaded cycle for the same driver and creates penalized
   pickup-only cycles only after no delivery candidate remains;
7. globally assigns the best next feasible cycle to an available driver;
8. compares resource activation against break-adjusted driver workload;
9. performs bounded, fully revalidated local improvements;
10. persists metrics, explanations, reasons and bounded trace events.

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
sequence `2 -> 1 -> 0 -> 1 -> 2 -> 0`.

For date and capacity experiments, use **Test for 3 days** instead. It never
resets the scenario currently open. The header date, **Requests** date chips,
and map show the same date-filtered requests. Click a **Д** (delivery) or **В**
(return) marker to inspect its details in a popup above the point and assign one
concrete logistics date directly on the map. To test nested geography, choose
**Make cutout**, click the source zone and draw the inner area. Complete the
form to name the new zone; the source cutout and inner-zone creation are saved
together. Both actions record a dispatcher decision in the simulator; neither
confirms a production dispatch.

Use **Export JSON** in the scenario actions. The single file can include
settings, geography, resources, requests/date options, seed and selected saved
plans. **Import JSON** validates the complete document in one transaction; a
failure creates no partial scenario. Exported scenarios are intended for bug
reports and seed-based planner reproduction and must not contain customer
production data.

## MVP limitations

This is an internal planning laboratory, not a production dispatch system. It
has a local `local-admin` audit actor and no interactive production identity
model, real traffic/GPS, embedded driver application, push messages, customer
checkout or payment flows. Its optional machine-to-machine RWMS access is
limited to the dedicated planning scope. OSRM uses static OpenStreetMap data
and therefore does not model live traffic, road closures or vehicle-specific
restrictions; Mock geometry is a straight GeoJSON polyline and remains
available for deterministic tests.

The delivered P1 subset includes persisted planner phases/candidate events,
plan cloning and driver-unavailability simulation. The following extensions
remain deliberately outside this MVP:

- a side-by-side saved-plan comparison screen and a configurable random
  scenario generator (the deterministic four-zone demo is available now);
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
