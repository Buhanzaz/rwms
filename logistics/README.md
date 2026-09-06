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
while warehouse selection and saved-route simulation remain available.
The workspace keeps one full-screen map underneath floating navigation and a
collapsible inspector. Slot checking replaces that inspector, and camera insets
follow the visible panels, header and simulation bar. Detailed plan statistics
and route explanations are expandable; errors and required operator decisions
remain visible. Translucency is limited to the main surfaces.
Warehouse marker types come from the canonical public warehouse directory:
`ПС` for representatives, `С` for main warehouses, `П` for production, and `Ц`
for combined main warehouse/production. Unknown or inconsistent metadata is
shown as `?`, never guessed from a name. Directory metadata adds no locations
to the planner workspace and is refreshed once per minute.
The map is one common canvas for every active canonical RWMS warehouse with an
owner-held coordinate pair. Directory reconciliation creates or updates its
planner projection under the same UUID; there is no second connect action or
required delivery polygon. Selecting a warehouse marker only selects that
point and leaves both the active workspace and viewport unchanged. The
contextual **Go to warehouse** action activates that exact warehouse UUID and
recentres the map without replacing a valid selection with a default depot, so
several warehouse markers can be compared without forced zoom. A warehouse
without coordinates remains visible in RWMS but is explicitly not routable.
The **Warehouse isochrones** and **Task isochrones** map layers are off by
default. Enabling the first requests every configured contiguous hourly
`costing=truck` contour for all connected warehouses; enabling the second
requests the same boundaries only for the explicitly selected request or slot-check point.
With a switch off, its contours are neither requested nor rendered. Equal
coordinates are requested once with depot precedence. The browser keeps at
most four contour requests in flight, aborts remaining siblings when one fails,
and renders every contour below operational route lines. The Compose-owned
[`valhalla/rwms-entrypoint.sh`](valhalla/rwms-entrypoint.sh) keeps Valhalla's
generated configuration intact while raising its isochrone ceiling from the
upstream 120-minute default to the supported 720 minutes and twelve contours; startup fails if that
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
┌ warehouse context / date / close acceptance / slot / validate / mode ┐
├───────────────┬─────────────────────────────────────┬─────────────────┤
│ warehouse     │       MapLibre or grid map          │ selected object │
│ deliveries    │ contours · delivery/pickup · routes │ route metrics  │
│ slot check    │                                     │ explanation     │
│ drivers       │                                     │                 │
│ contractors   │                                     │                 │
│ vehicles      │                                     │                 │
│ shifts        │                                     │                 │
│ deliveries    │                                     │                 │
│ day plan      │                                     │                 │
│ unassigned    │                                     │                 │
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
The left navigation is permanently visible and has no collapse control or
decorative **Workspace** heading. The selected warehouse label carries its
live local time and canonical IANA timezone; switching warehouses immediately
recalculates that clock from the selected warehouse, not from a browser-global
timezone.
The planning date is initialized from that same selected-warehouse metadata
before workspace, automatic planning or day-status queries are enabled. A
warehouse switch invalidates the previous context first, so a cached workspace
cannot mutate the former UTC/browser date.
Browser persistence is deliberately limited to presentation preferences: the
exact selected warehouse, one planning date and map viewport per warehouse,
the application and left-menu sections, the shift filter, the map tool and map
layers. Reload restores those choices, while plans, requests, shifts and every
operational decision remain server-owned. When a representative warehouse is
active, updates to its group's root plan do not recenter the map over the root
depot.

The supported flow selects an automatically reconciled canonical RWMS warehouse,
creates warehouse-scoped resources and delivery/pickup work, checks dynamic slots,
maintains the date's pre-plan automatically,
finalizes it by closing request acceptance, validates manual changes and
replays vehicles at any timestamp. City-specific fixture workspaces, workspace
cloning and JSON workspace import/export do not exist.

The deterministic workload generator remains a test/development API, but the
warehouse details view does not expose **Test for 3 days**, **Connect warehouse**
or **Refresh from WMS** controls. Normal warehouse and RWMS order refresh is
automatic. A separate fail-closed
[`driver-fixture-bridge`](../tools/driver-fixture-bridge/README.md) can publish
only an already confirmed, wholly generator-owned route into canonical
task-board DriverApp tasks in an explicit dev/test environment. It defaults to
dry-run and is not a production publication path.
The shared planning date filters both the request list and map markers. Until
the dispatcher selects one date, a request appears on every customer-approved
date; afterwards it appears only on the selected logistics date. Clicking a
marker opens a MapLibre popup anchored above that point with address, quantity,
windows, date selection and the current driver/cycle assignment. Before
approval, selecting another cycle moves the task from its previous cycle under
the same backend version and route-validation fence.
A new date requires an explicit agreement action, an assignment can be cleared,
and the original accepted dates remain intact. To make return
pairing observable, routing settings remain editable for the selected
warehouse.

The **Deliveries** section is the dispatcher preparation board. Switching its
date shows only deliveries and pickups eligible for that day and keeps the
dispatcher in the same section instead of opening **Day plan**. Every READY item
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
Each delivery card shows the logistics-owned confirmed price formatted in
rubles, or **Not calculated** when no authoritative quote exists. The same fact
is visible on unassigned and map cards and refreshes with the request feed.
When every staff route is infeasible, the board distinguishes **No available
drivers** from a merely unassigned request and offers an explicit contractor
handoff. Contractor profiles are reusable and have no availability dates. The
dispatcher selects the planning date in the header and can either form a trip
automatically from eligible unassigned work or select requests manually. Each
real-RWMS handoff first persists a durable command and request reservation; its
remote apply runs outside the database transaction and is retried with the same
idempotency identity. Only complete success removes requests from automatic
planning. A mixed upstream result remains pending for reconciliation and never
pretends that every request was assigned. The handoff records the concrete
active worker but deliberately creates no simulator vehicle, shift or route
cycle. A successful canonical handoff also returns and projects its durable
command UUID, each request's zero-based immutable command sequence and exact
task-board task UUIDs. The **Contractors** section groups requests by one
command, requires a fully loaded projection with one unique contiguous
sequence, and restores task order from that sequence after reload. Missing,
duplicated or mixed identities leave the route visibly not ready and never
create a share; a generated-only local assignment has no share action. Only an
explicit **Copy route** click creates or idempotently replays the expiring
canonical logistics share with the current RWMS USER token. Its idempotency key
is the handoff command UUID and its deterministic expiry is derived from the
planning date; if clipboard access is unavailable, the same-origin URL remains
selectable for manual copying.
Selecting an unassigned list card highlights its marker and pans the map while
preserving the current zoom. Backend/domain failures pass through one
user-facing Russian error mapper; raw HTTP status text, exception bodies and
planner constraint dumps remain diagnostic-only.
If a returned plan references tasks absent from the visible request page, the
client reads the current workspace for that plan date and the necessary following
pages before displaying it. Cancellation propagates to those reads; a missing
task or service failure remains an explicit error. A planning timeout reports
the number of saved, verified trips and unfinished tasks instead of claiming no
route exists.
The right details pane has an accessible drag separator, is resizable up to half
the viewport and reflows its forms and metrics as its width changes. The former
per-leg truck diagnostic block is not part of the operator interface.
Header buttons, the date control and both equal-width mode segments share one
control height. Warehouse actions use one equal 2×2 grid whose labels wrap at
the minimum inspector width.

The **Check new order** view offers debounced navigator-style address
suggestions. Selecting one resolves its canonical address and coordinates;
placing a point on the map reverse-geocodes and fills the address when the
provider knows it. Editing the address, selecting another suggestion, moving the point or closing
the panel cancels the previous lookup; a late result cannot replace the current address or point.
Arrival, warehouse return and timeline timestamps use the selected warehouse's IANA timezone;
already-local `HH:mm` values and the fixed slot labels keep their warehouse clock meaning.
Manual address text remains available when reverse
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
increments the plan version atomically. The selected hourly isochrone tier
contributes the displayed delivery price. Separate warehouse-owned
`FORBIDDEN`, `NO_TRAILER` and `SPECIAL_PRICE` MultiPolygon policies respectively
exclude a point, force a solo-vehicle profile or replace the price only after
the exact road route remains within the farthest ordinary isochrone. Current
mutable demand and active holds are classified in bounded bulk queries, so a
policy edit participates in the next availability calculation rather than only
in newly entered addresses; confirmed plan workload remains occupied immutable
history.

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
FastAPI. The browser never calculates the authoritative tariff. The backend
stores warehouse-scoped resources and hourly isochrone tariffs, plans, validation results, explanations,
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
settings, one-to-twelve contiguous hourly isochrone tariff rows, warehouse-scoped drivers, vehicles and
monthly shift ranges, logistics requests, date options and a separate
dispatcher-selected logistics date, split planning tasks, versioned
route plans/cycles/stops/legs, optimization runs/trace events and manual-change
audit, versioned day plans and expiring slot holds. All timestamps are
timezone-aware; a warehouse's canonical timezone is authoritative and
`Europe/Moscow` is only the configured fallback. The tariff list starts at 60
minutes and advances in contiguous one-hour steps. Exact one-way truck time
selects the first covering price; the final configured tier is the hard order
acceptance boundary. Ordinary delivery polygons and request-to-zone ownership
do not return; exceptional access/price policies are separate versioned,
warehouse-owned MultiPolygons and never extend normal delivery reach.

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
  removed from newly published customer-slot capacity. Closing archives only a
  mutable preliminary head, preserves a confirmed or historical revision and
  never publishes an unconfirmed assignment to RWMS. With RWMS sync enabled,
  assignment publication is a separate version-fenced command for the exact
  `CONFIRMED` plan. Deterministic warehouse, order, cabin and driver mapping is
  validated before the remote effect; an exact retry reuses the same
  plan/version idempotency key. A later remote failure is reconciled through
  the read-only publication status and an explicit retry, not through another
  close command.
  A newly loaded plan also replaces the previous plan-result notification, so
  the operator sees only the current route and unassigned counts.
- A driver's next cycle starts no earlier than the preceding depot return plus
  warehouse unload/turnaround and the configured route buffer. Cycles assigned
  to one driver therefore never overlap.
- Every candidate requires exact road routing and schedule feasibility within
  the warehouse's farthest configured isochrone.
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
  tariff tier cannot make the limits tighter. An oversized return becomes a
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
When a `TIME_WINDOW_CONFLICT` has a same-day nearest option, its card exposes
**Change time window** and opens the existing request editor without changing the date.
The separate **Move to another day** action remains available for other scheduling decisions.
If no feasible cycle exists, the UI opens the unassigned reasons instead of
claiming routes were built, and simulation remains unavailable until the plan
contains at least one cycle.

Every saved road segment is shown on the map. Cycles receive separate colors,
the map fits the complete plan when it is opened, and the route legend selects
an entire cycle including its return to the warehouse. Repeated depot segments
therefore remain understandable instead of hiding later route legs. The
**Full routes** card is stacked above the optional layer menu, while route
lines are rendered above restrictions and isochrone polygons.

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
and manual coordinates/address entry remains available; slot logic does
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
<https://77-90-158-90.sslip.io/logistics-panel/>. This path-based reverse
proxy build uses `VITE_APP_BASE_PATH=/logistics-panel/` and
`VITE_API_BASE_URL=/logistics-panel/api`; the backend remains loopback-only.
The frontend container serves the same prefixed SPA and API paths directly, and
returns `404` for a missing hashed asset instead of falling back to HTML. This
keeps direct runtime smoke tests equivalent to the URLs embedded in the bundle.
The `/logistics/**` namespace remains owned by the primary RWMS panel and must
not redirect to or be shadowed by the standalone simulator. The public Nginx
locations are recorded in `deploy/nginx-public-path.conf`.

Loopback binding is not an authentication boundary. The browser restores the
existing `rwms-panel` OIDC `USER` session and sends a fresh Bearer token to
every operator API request and planning-event stream. FastAPI validates the
RS256 signature through JWKS, exact issuer and audience, expiry, panel client,
`rwms.read` scope and warehouse grants before loading or mutating operational
data. Only health and generated API documentation remain anonymous. On the VPS
`AUTH_ISSUER` is mandatory and must equal the public issuer in signed tokens;
the backend-reachable `AUTH_JWKS_URL` may remain private.

If a separately opened tab has no session-scoped token copy, the workspace
immediately starts the normal `rwms-panel` authorization redirect. The shared
RWMS Auth session can complete SSO and the common callback returns to the exact
standalone logistics path; access and refresh tokens are not copied to
`localStorage` merely to share them between tabs.

### VPS-only RWMS bridge

The ordinary Compose file stays self-contained for local use. On the VPS, the
separate `docker-compose.vps.yml` overlay attaches only `backend` to the
persistent external `rwms-logistics-private` bridge. Create that bridge once
with fixed gateway `172.21.0.1`, deploy
`deploy/nginx-rwms-private-bridge.conf` as an Nginx configuration, and start
with both Compose files. The Nginx listeners are bound only to that Docker
gateway and proxy only `/oauth2/token`, `/oauth2/jwks` and
`/api/internal/logistics/v1/planning/` to loopback-bound RWMS services.
The VPS overlay defaults `AUTH_JWKS_URL` to the exact private JWKS route
`http://172.21.0.1:19002/oauth2/jwks`; `host.docker.internal` is not a supported
VPS route for token verification.

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
| `VITE_APP_BASE_PATH` | `/` | Vite base; use `/logistics-panel/` on the VPS with API `/logistics-panel/api` |
| `AUTH_ISSUER` | `http://localhost:8088/auth` | Exact trusted JWT `iss`; mandatory explicit value in the VPS overlay |
| `AUTH_JWKS_URL` | issuer `/oauth2/jwks` (VPS overlay: private bridge) | Backend-reachable JWKS used for RS256 signature verification |
| `AUTH_AUDIENCE` | `rwms-services` | Required operator-token audience |
| `AUTH_PANEL_CLIENT_ID` | `rwms-panel` | Only interactive panel USER tokens from this client are accepted |
| `AUTH_JWKS_TIMEOUT_SECONDS` | `5` | Bounded JWKS fetch timeout from 0 to 30 seconds |
| `RWMS_SYNC_ENABLED` | `false` | Explicitly enables authenticated RWMS import/apply operations |
| `RWMS_DEMAND_SYNC_INTERVAL_SECONDS` | `60` | Server-owned demand-ingestion cadence, validated from 5 to 3600 seconds |
| `RWMS_DEMAND_SYNC_BATCH_SIZE` | `25` | Number of routing-ready warehouses processed per fenced ingestion page, from 1 to 100 |
| `RWMS_CAPACITY_PUBLISH_ENABLED` | `false` | Publishes generated delivery/pickup, shift and tariff facts for RWMS slot/price calculation; requires RWMS sync |
| `RWMS_CAPACITY_RETRY_INTERVAL_SECONDS` | `15` | Poll interval for durable retry of due capacity generations; validated from 1 to 300 seconds |
| `RWMS_REQUEST_RESCHEDULE_RETRY_INTERVAL_SECONDS` | `15` | Poll interval for due owner-backed unassigned-delivery recovery, from 1 to 300 seconds |
| `RWMS_REQUEST_RESCHEDULE_RETRY_BATCH_SIZE` | `10` | Maximum leased reschedule holds claimed in one `SKIP LOCKED` batch, from 1 to 100 |
| `RWMS_REQUEST_RESCHEDULE_RETRY_MAX_ATTEMPTS` | `8` | Failed owner-phase attempts before the persisted hold is quarantined, from 1 to 100 |
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
active entry with coordinates is reconciled automatically under its RWMS UUID.
An address-only canonical entry is resolved automatically during the same
directory read with the existing server geocoder; the derived point is retained
while its address and city are unchanged and yields immediately to later
owner-held coordinates. A failed address resolution leaves that entry visibly
unavailable without hiding routable siblings. Drivers, representative support
links and contractor availability are resolved through their private RWMS
directories; the panel does not ask the operator to connect or refresh a
warehouse manually.

The selected warehouse is resolved to one non-transitive planning root. A main
warehouse and its directly served, routing-ready representative warehouses share
the same dated plan, while every regional request retains its canonical
`serviceWarehouseId`. The planner evaluates local resources owned by every
admitted member and all calendar-eligible support warehouses. Each candidate
retains its physical route origin; exact link exclusions, allowed dates and
recurring weekdays are applied for the planning date. Selecting a representative
changes the visible map context without creating a duplicate day plan.
The header selector renders the current root first, its direct representatives
as indented rows, and then all other authorized routable warehouses, including
representatives outside the current planning group. Selecting a representative
keeps planning commands on the root while opening that warehouse's own context.
Depot stops and driver timelines are always labelled from the persisted plan's
`warehouse_id`, so opening a representative cannot relabel a physical root-depot
start as an arrival at the representative warehouse.
The first successful workspace read establishes the notification baseline for
that exact planning root and member set; it never calls existing regional RWMS
requests “new”. Later reads notify only request IDs that appeared after that
baseline. Changing to a different group establishes a new baseline instead of
replaying its historical demand as alerts.
The shift view keeps its **All / Active / Inactive** filter across reloads. The
editor mirrors the server's recurring and overnight overlap rule to identify
an existing driver or vehicle conflict before submission; backend version,
locking and overlap fences remain authoritative. A shift retained by plan
history cannot be deleted; the conflict directs the operator to make it
inactive instead.

Planning reads and commands remain on the simulator's same-origin FastAPI. The
in-map **Create transfer** action is the narrow exception: it opens a local
dialog and submits one authoritative draft to the existing public
`POST /api/logistics/v1/transfers` gateway operation using the renewable panel
`USER` OIDC session. It does not navigate to the panel transfer page or create a
second transfer aggregate. If the shared session is absent, the panel OIDC
callback returns the operator to the standalone logistics URL after sign-in.

The dialog can persist an empty resource-only trip or, behind **Carry cabins**,
one or more independent cabin requirement groups. Type, size, finishing,
characteristics and furniture come from the canonical asset-service catalogs;
the browser never invents BK/finishing/furniture values. Furniture is entered
per cabin and the dialog shows the multiplied total. A draft intentionally has
no physical cabin allocation, reservation or stock movement. In the primary
panel the **Prepare cabins and contents** action opens that same canonical plan;
confirmation atomically reserves the selected stock and uses the existing
furniture-difference task workflow.

The dialog can also select active capital-repair cabins at the destination as
independent return cargo. The canonical create command locks their exact repair,
asset and asset-version facts and atomically creates a linked concrete-line
reverse transfer. Those cabins are loaded only after the outbound cargo is
unloaded, so they do not consume outbound capacity; their numbers and existing
gallery photos flow through the same offline-capable task-board worker content.

The dispatcher selects a local source date and departure time. When the source
has an active physical vehicle projection, the dialog calls the read-only
`POST /api/routing/transfer-arrival-estimate` endpoint. That endpoint uses the
shared vehicle/trailer snapshot, per-leg load state and configured exact truck
routing provider to derive travel time and arrival; it neither reserves a
resource nor mutates a transfer. If no exact calculation is currently possible,
the draft remains explicit with no fabricated arrival and can be completed at
the existing confirmation stage.

Planning settings have moved to `/admin/logistics`; the operating sidebar
keeps day actions and history. Notification duration is a presentation preference
in the bell menu. The isolated administration API exposes planning settings, the contiguous
isochrone tariff ladder and exceptional map policies under
`/api/logistics-planner/v1/admin/warehouses/{canonicalWarehouseId}`. It requires
the `rwms-admin-web` client, `SYSTEM_ADMIN` and `admin.manage`. Settings and tariffs
share the observed warehouse version; policy commands retain their own version
and idempotent create receipts. Representatives retain their own settings.
The existing planner owner publishes capacity after committed mutations and
invalidates mutable plans after policy edits; a publication failure is reported.

Planner settings submit only fields accepted by the strict backend
`PlanningSettings` schema. The obsolete browser-only `trace_enabled` switch was
removed; trace event bounds and sampling remain supported settings.

For a representative warehouse the same planner evaluates local staff resources,
active operational assignments and every calendar-eligible support warehouse.
A support candidate retains its origin, exact
road arrival and availability-after-buffer. It may perform a one-day
cross-warehouse service route without changing its base, or an explicit
resource-reposition transfer may activate a destination assignment after
arrival. Potential resources produce confirmation-required slots; a guaranteed
fixed window still requires a feasible, reservable plan.
Contractors are a separate dispatcher fallback: the optimizer never fabricates
their vehicle, capacity, shift or internal route. The **Contractors** section
uses task-board's warehouse-owned catalog to create, edit, deactivate or delete
unused reusable profiles. Profile dialogs contain no dates. **Form trip** assigns
eligible supported work for the date currently selected in the header;
**Distribute manually** submits an explicit set of that day's unassigned work.
Generated/local pickups remain eligible. A real RWMS pickup is excluded from
automatic selection and disabled in manual selection because the canonical
assignment contract does not yet support contractor pickup handoff; supported
deliveries in the same batch are still assigned.

The same section groups drivers under hired companies with INN, contact person,
clickable phone/email, address and notes. Companies and independent drivers belong
to one selected city. A driver can be added from the company card or its context
menu; profile editing can change company membership within that city or clear it.
Company contacts use the public task-board API and observed versions. A company
with drivers cannot be deleted. Trips continue to use the selected individual
driver and the planning date in the header; changing city clears open catalog forms.

`GET /api/warehouses/{warehouse_id}/workspace` is a side-effect-free read of
one exact warehouse-local planning date. It returns at most `request_limit`
requests (250 by default, 1000 maximum), a stable UUID cursor and the total;
the browser accumulates pages only within the same response generation and
renders their map points through one clustered GeoJSON source. A separate
server-owned worker holds a PostgreSQL advisory fence, reconciles the warehouse
directory and imports each routing-ready warehouse's authoritative 31-day
horizon in bounded keyset pages. Orders are upserted by stable
`(warehouse, RWMS, orderId)` identity with versions and cabin unit IDs.
Address-only rows use the existing server geocoder, valid siblings commit, and
directory/import transactions finish before support-network HTTP and automatic
planning. A later group-resolution or planner failure therefore cannot roll
back already imported demand.

The successful warehouse/date response is a complete snapshot of still-unplanned
demand. A previously imported `READY` or `UNASSIGNED` RWMS request omitted from
that inclusive range is retained with its dates, payload and task identities but
is moved to `CANCELLED`; only mutable plan heads are archived. Confirmed plan
references and requests that already crossed the planning boundary are never
rewound. If the same source identity reappears, its retained request and tasks
return to `READY` without duplication. A source row that is present but fails
local validation is not mistaken for an omission.

Creating or changing an authoritative request archives only mutable plan heads
on its old and new allowed dates. Confirmed plans and historical revisions are
never deleted by request mutation. The backend then rebuilds missing draft plans for
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
acceptance** calls the idempotent matching `/close` endpoint and recalculates
that day, but it never publishes an unconfirmed plan. One warehouse/date has a
single non-archived revision head; a successful reoptimization archives its
expected predecessor and records `supersedesPlanId`. Confirming a plan
atomically fixes each assigned flexible request to that date and archives
competing mutable plans on alternative dates. Only the separately confirmed
exact plan/version may cross `/rwms/apply`; a stale version fails instead of
overwriting a newer plan.

Every `/rwms/apply` attempt also rechecks the current driver, shift, truck,
trailer, request state, day mode and warehouse restrictions under a local
NOWAIT resource fence. Load-specific route proofs must still match the current
physical facts and road-data version. Unrelated new requests cannot block this
validation of an accepted route. Stale facts reject the attempt before sending
assignments; historical plan and task states are not rewritten. Local locks are
released before the mutating owner call. If an earlier response was lost, a new
local rejection does not prove the earlier command had no effect: check RWMS
status and use the existing recovery workflow, rather than blindly replaying
an unsafe route. Prepared dynamic-recovery commands remain a separate workflow.

Capacity publication is a separate opt-in and requires synchronization. A
daytime or overnight shift is publishable only when its break is shorter than
its complete interval; periods may cross a month boundary but contain at most
31 dates. `end < start` ends on the next local day and equality is invalid. The
form, API, application service, capacity DTO and DB enforce these invariants
before the snapshot is built.
Warehouse resource mutations and generated-workload replacement publish one
versioned complete snapshot of generated delivery/pickup demand, active
period-based shifts, vehicle capacity and that warehouse's complete hourly tariff list. Each committed
mutation advances a warehouse capacity generation, so retries are idempotent
and delayed older generations are rejected. Manual requests and imported RWMS
orders are not re-published as generated demand; replacing capacity never
deletes a real RWMS booking. Regeneration deletes a saved plan only when every
referenced request is generator-owned. A real, mixed or confirmed plan causes
an explicit conflict before any test request or plan changes. The simulator
commits local work and a durable `PENDING` publication record in one
transaction. An unavailable optional RWMS capacity projection becomes
`FAILED` with a safe error code and operator warning; it never rolls back local
work or converts test demand into an RWMS order. A leased worker automatically
retries only the latest due generation with bounded backoff, while a delayed
older success cannot hide newer pending work. Completion refreshes the locked generation before
advancing its monotone delivery cursor; only a matching generation clears retry and lease fields.
The newer generation retains its due time, attempts, error and lease. `POST
/api/warehouses/{warehouse_id}/rwms/capacity` is an operational reconciliation
endpoint, not a panel button.

No local database transaction remains open across OAuth or RWMS HTTP I/O.
Import closes its read transaction before fetching the feed; apply freezes and
commits the exact command snapshot before the idempotent remote call.

## Valhalla and OpenStreetMap truck routing

The default `ROUTING_PROVIDER=valhalla` builds sparse exact directed submatrices
of at most 32 points for deterministic time-window and overlapping spatial
partitions; it never allocates one full `N×N` day matrix. A candidate is not
feasible until every used leg has been requested from private Valhalla with
`costing=truck` and the leg's `EffectiveTruckProfile`. Sparse-matrix preparation
and candidate optimization have consecutive bounded intervals of the configured
optimization duration. Successful matrix preparation therefore cannot consume
the first exact candidate's complete search interval. An interrupted candidate
is discarded and only a fully validated best-known plan may be returned. Exact
Valhalla distance and duration reschedule windows, shift finish and objective
cost. The saved
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
2. builds bounded exact sparse travel submatrices for stable temporal and
   spatial candidate partitions;
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

## Dynamic day operations

The existing planner is also the recovery engine for a selected warehouse-local
day. A versioned `PlanningDayPolicy` persists `DELIVERIES_AND_PICKUPS`,
`DELIVERIES_ONLY`, or `PICKUPS_ONLY` at the root of a planning group; the
constraint applies to every represented warehouse and to slot/capacity
publication. A mode change on an empty day creates no artificial transfer. A
conflict with planned work creates a durable `LogisticsEvent`, structured
`LogisticsNotice`, human action, and versioned `RecoveryProposal`; it does not
silently alter a customer commitment.

Breakdowns, delays, unavailable drivers, cancellations, blocked tasks, and
mode changes use one sequence: fact, impact analysis, recommendation,
dispatcher decision, and partial replan. Truck/trailer incidents offer automatic
application of a safe replacement or manual review; missing capacity and cargo
already in transit require explicit dispatcher action. Delay contacts use only
acceptance, rejection, or unreachable outcomes and fence the shared proposal.
The dispatcher enters an event time explicitly: its date comes from the opened
day and it is interpreted in the planning-root warehouse IANA timezone, never
in the browser timezone or a global Moscow default.
Existing RWMS base tasks are read-only, low-priority return-to-base candidates.
Day plan provides direct incident buttons for a truck/trailer breakdown, delay,
or unavailable driver. The event list shows actual warehouse-local time,
affected resources, recorded reason and server recommendations, with older
events available on demand. Switching the root warehouse or date closes an
unfinished incident form. An `APPLIED` automatic-recovery receipt refreshes the
plan and reports the applied change; failed or pending proposals remain explicit.
See [`dynamic_operations.py`](backend/app/api/dynamic_operations.py),
[`operations.py`](backend/app/models/operations.py),
[`dynamic_impacts.py`](backend/app/services/dynamic_impacts.py), and
[`dynamic_recovery.py`](backend/app/services/dynamic_recovery.py).

The day-operations workspace has no separate “System journal”. Structured
notices and immutable dispatcher decisions remain durable on the backend. The
UI merges them chronologically into the existing notification center and a
read-only **Day plan → Day journal** view, loaded on demand. Unfinished human decisions remain in the
action queue and calculated changes remain in proposals. The UI shows an
explicit warning when the server truncates the available history window.

Rescheduling an existing unassigned RWMS delivery never opens manual time
entry. The dispatcher selects a date, the backend asks the order owner for all
currently feasible slots on that date, and exactly one returned slot can then
be selected and confirmed as agreed with the customer. Apply rechecks the
local request, source plan, order, customer session, and slot versions; it never silently
changes the customer commitment.

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
Every candidate must snap to a truck-road point and have exact one-way travel
covered by the selected warehouse's farthest configured isochrone; otherwise
the complete run fails explicitly. Equal tariff settings, routing graph, input
and seed reproduce the same business values. Stable external identities prevent
a repeated generator run from creating duplicate logical visits.

**Delete workload** removes generated requests and saved unconfirmed plan
revisions only for the selected planning-group root and exact date. An
unconfirmed mixed plan is disposable derived state: the command removes that
revision but preserves every manual or RWMS request so the remaining demand can
be planned again. A confirmed plan fences the command with
`WORKLOAD_GENERATOR_PLAN_CONFLICT`. Automatic regeneration is stricter and
refuses any mixed plan instead of treating business demand as generator-owned.
This is the same root-owned plan shown while a representative warehouse is
open. The date control, request list and map display the same filtered work;
delivery and pickup markers open their request details.
Warehouse settings store one to twelve independent non-negative whole-ruble
prices for contiguous hourly isochrone bands. The last band is the maximum
delivery distance by exact road time. The resolved tariff is
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
