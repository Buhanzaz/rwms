# Dynamic delivery slots and road isochrones

## Purpose and ownership

A dynamic slot is a customer delivery window that the system can still fulfil
after inserting the new order into a complete driver-day schedule. The three
standard windows are `09:00-12:00`, `12:00-15:00`, and `15:00-18:00` in the
warehouse timezone.

`logistics-service` owns real CustomerApp slot offers, holds, confirmations,
orders, and driver-capacity fencing. The standalone `logistics/` deployable owns
the planning simulator and its scenario data. It publishes an anonymous,
versioned capacity snapshot to the private logistics planning boundary; it
never reads or writes the RWMS database directly. The public gateway remains a
stateless transport boundary.

Authoritative sources:

- the customer and private planning contracts in
  [`contracts/openapi/logistics-service.yaml`](../contracts/openapi/logistics-service.yaml);
- the live customer calculation in
  [`CustomerDeliverySlotService.java`](../services/logistics-service/src/main/java/dev/buhanzaz/rwms/logistics/customer/service/CustomerDeliverySlotService.java)
  and
  [`CustomerRouteCapacityPlanner.java`](../services/logistics-service/src/main/java/dev/buhanzaz/rwms/logistics/customer/routing/CustomerRouteCapacityPlanner.java);
- the simulator calculation in
  [`app/slot_planning/`](../logistics/backend/app/slot_planning/);
- the versioned simulator hold boundary in
  [`app/api/slot_planning.py`](../logistics/backend/app/api/slot_planning.py).

## Source of truth

An isochrone is a GeoJSON road-reachability polygon for a truck profile and a
specific departure time. It is useful only for optional map explanation in the
current product. Slot candidate filtering and feasibility do not request or
consume it, and it is not proof that an order fits between two stops.

Availability is decided only by a full directed truck-road simulation. For an
insertion `A -> X -> B`, the simulator calculates arrival, allowed waiting,
service start, service end, and the exact `X -> B` leg, then recalculates every
later stop, warehouse turnaround, later trip, and final return. Service start
must be inside the promised window (`START_WITHIN_SLOT`); service may finish
after the window when the rest of the day remains feasible.

RWMS input freshness uses two independent values. `orderVersion` is preserved as the optimistic
fence for a later assignment command. `sourceRevision` is a deterministic SHA-256 of the complete
exported planning snapshot, including confirmed-slot and current unplanned-cabin facts owned by
other aggregates. A new revision can safely refresh those facts without changing the order fence;
different payload under one revision is rejected.

The following are hard constraints:

- every existing promised delivery still starts in its window;
- locked driver, trip, stop order, and exact-time facts remain fixed;
- `0 <= currentLoad <= vehicleCapacity` on every leg;
- capacity two requires a compatible truck and trailer;
- a site capacity of one uses solo-truck visits, even for a multi-cabin order;
- all deliveries of a trip precede its return-leg pickups;
- warehouse unload and the next load fit before a later trip;
- the driver completes the final warehouse operation by the configured shift
  end;
- every road leg has an exact `costing=truck` result.

Isochrone or special-price containment, route group, straight-line distance,
or an ordinary geometric circle cannot prove that a slot is available.
Exceptional forbidden-delivery and forbidden-trailer polygons can reject the
affected candidate, but every remaining candidate still needs an exact road
and day-plan calculation.

## Day, trip, stop, and load model

A `DriverDayPlan` contains any number of depot-to-depot trips. Each trip has a
warehouse load, one or more deliveries up to its initial capacity, optional
return-leg pickups, warehouse unload, and either the next load or the final
warehouse finish. The timeline records arrival, service start/end, departure,
waiting, service duration, load before/after, lock state, and a structured
infeasibility reason.

The simulator's immutable planning types are in
[`models.py`](../logistics/backend/app/slot_planning/models.py). The live service
receives dated deliveries, pickups, shifts, vehicle capacity, trailer state,
truck dimensions, isochrone tariffs, and exceptional policy polygons through
the scenario capacity snapshot;
Flyway `V64` stores the additive fields in the owning logistics database.

Orders may contain more cabins than one vehicle trip. They are split into
deterministic batches that respect both vehicle and site capacity. For example,
three cabins at a site capacity of one become three solo-truck visits. Two
cabins at a site capacity of two may use one trailer trip when its exact truck
route exists.

## Candidate search

For each client window the planner checks, in deterministic order:

1. insertion before, between, or after the delivery stops of an existing trip;
2. a new trip before, between, or after existing trips of the same driver;
3. every other working driver with compatible equipment;
4. every required batch of a split multi-cabin delivery.

An insertion never occurs after a pickup in the same trip. Every candidate is
fully resimulated through the end of the day. Infeasible candidates are removed
before ranking. Remaining candidates are compared lexicographically by minimum
slack, incremental travel, avoiding an extra depot trip, useful pickups,
distance, waiting, final return time, and stable IDs. The response keeps
explainable metrics rather than a single opaque score.

## Return-leg pickups

The default policy is `RETURN_LEG_ONLY`: delivery is always primary. After the
last delivery, the planner tests zero, one, and—where capacity permits—two
pickup candidates, including both orders for a pair. Each variant includes
pickup service, travel to the warehouse, unload, the next warehouse load, all
later deliveries, and the final shift deadline.

If a pickup threatens a current or later delivery, it is deferred. A pickup
that merely fits before 20:00 is still rejected when it would delay the next
trip. Existing mandatory pickups are honoured only when doing so does not make
an already promised delivery invalid; optional pickups never consume a slot
that could otherwise be offered to a delivery.

## Truck routing, cache, and visual isochrones

The slot-planning routing ports separate travel time and selected-route
geometry from domain planning. The production adapters use Valhalla truck costing and pass the
effective vehicle dimensions, weight, axle load, trailer, and load state. They
do not fall back to car costing or a straight line. Missing or timed-out truck
routes fail closed with `TRUCK_ROUTE_NOT_FOUND`.

Directed matrices are requested before route geometry. Geometry is requested
only for the selected feasible candidate. Process-local bounded caches include
ordered coordinates, departure bucket, routing/OSM version, vehicle profile,
trailer state, and load state; solo and trailer results therefore cannot be
mixed. The simulator adapter is
[`routing_adapter.py`](../logistics/backend/app/slot_planning/routing_adapter.py),
and the live customer matrix adapter is
[`ValhallaCustomerTravelTimeClient.java`](../services/logistics-service/src/main/java/dev/buhanzaz/rwms/logistics/customer/routing/ValhallaCustomerTravelTimeClient.java).

The global map has separate default-off 60-, 120-, 180-, and 240-minute GeoJSON
layers for visible scenario warehouses and for one explicitly selected request
or slot-check point. A disabled layer makes no request. These polygons are
loaded through the independent read-only contour endpoint, rendered below
operational routes and never included in a slot response.

The slot route-before and route-after layers contain exact truck-road geometry
for the winning candidate's selected trip only. Pickup point features are
labelled `SELECTED`, `DEFERRED`, or `CANDIDATE` according to the winning exact
schedule. The response has no isochrone or insertion-lens fields, eliminating
provider calls that do not affect availability, price or zone classification.

## Availability, hold, and confirmation

The simulator API is:

- `POST /api/planning/slot-availability` — calculate all three windows;
- `POST /api/planning/slot-holds` — recalculate and retain one insertion;
- `POST /api/planning/slot-holds/{holdId}/confirm` — version-check,
  re-simulate, and atomically create the confirmed delivery.

A hold records the date, selected window, insertion snapshot, source/day-plan
version, client session, tariff snapshot, and expiry. The default TTL is ten
minutes. Confirmation locks the hold and day plan, rejects expiry or a changed
source revision, repeats exact feasibility, creates the delivery, increments
the plan version, and replays only the same idempotency key. A concurrent buyer
therefore receives a version conflict and must calculate again.

CustomerApp uses the canonical public
`/api/logistics/customer/v1/delivery-slots` boundary. The live service performs
the corresponding capacity calculation and final transaction fence; the app
does not derive availability from a local calendar or map polygon.

## Isochrone prices and exceptional zones

The ordinary delivery price comes from the smallest configured 60-, 120-,
180-, or 240-minute warehouse road isochrone that contains the destination.
The default bands are 10,000, 15,000, 20,000, and 25,000 rubles; each warehouse
capacity snapshot carries its own values, so neither client hardcodes them.
The selected band and price are retained in the offer and hold.

Polygons are exceptional policies only: delivery forbidden, trailer forbidden,
or special price. A forbidden polygon makes the matching candidate infeasible;
a trailer-forbidden polygon removes only trailer configurations; a special-price
polygon overrides the isochrone price without making an otherwise impossible
road route feasible. Overlaps are resolved deterministically by policy,
priority, and geometry specificity. Editing an isochrone tariff or exceptional
zone changes the capacity-source revision, invalidates stale holds, and forces
confirmation to repeat the authoritative checks.

Neither an isochrone nor a polygon creates a road or opens a slot. Exact truck
routing, capacity, shifts, reservations, and the full remaining day continue
to decide availability after the policy checks.

## Warehouse configuration

The live defaults are defined under
`rwms.logistics.customer.delivery` in
[`application.yaml`](../services/logistics-service/src/main/resources/application.yaml)
and validated by
[`CustomerDeliveryProperties.java`](../services/logistics-service/src/main/java/dev/buhanzaz/rwms/logistics/customer/config/CustomerDeliveryProperties.java):

| Setting | Default |
| --- | --- |
| Warehouse timezone | warehouse-owned; test scenarios use `Europe/Moscow` |
| Driver work start | `08:00` |
| Client delivery interval | `09:00-18:00` |
| Final warehouse finish | `20:00` |
| Client slot length | 180 minutes |
| Delivery/pickup service | 60 / 60 minutes |
| Warehouse load, one/two cabins | 30 / 45 minutes |
| Warehouse unload | 30 minutes |
| Travel multiplier | `1.15` |
| Fixed travel buffer | 5 minutes |
| Hold lifetime | 10 minutes |

Times and durations are configuration, not planner literals. All local dates
are interpreted in the warehouse timezone.

## UI behaviour

The simulator slot panel debounces navigator-style server suggestions, resolves
the selected suggestion into canonical address/coordinates, and reverse-
geocodes a placed map point when configured. Missing provider credentials or a
missing result stay explicit while manual entry remains available. It shows
exactly three slot cards, arrival, detour, slack, candidate count, structured
Russian failure reasons, route layers, stop order, timeline, and load
transitions. CustomerApp
shows a loading overlay with “идёт расчёт свободных слотов”, marks a tapped or
located Yandex point, requests Android location permission only from the
location action, and sends address plus coordinates, selected cabins, and site
capacity to logistics.

For two or more selected cabins CustomerApp asks whether the site accepts one
or two cabins per visit. Value one means solo-truck visits; value two means the
site permits a trailer, but the truck routing provider may still reject that
configuration for the road network.

## Current bounded simplifications

- The planners are deterministic bounded searches, not a global fleet
  optimizer and not machine learning.
- Vehicle capacity is currently one or two cabins; the model retains truck and
  trailer placement so later loading-order constraints can be added.
- Pickup combination search is bounded to the vehicle capacity.
- Routing caches are process-local and bounded; they are not shared across
  instances.
- Isochrones explain reachability and select the ordinary price band; they do
  not prove route feasibility. Exact matrix simulation is intentionally
  repeated at hold and confirmation boundaries.

## Verification

Run the focused simulator gate without external routing by using fake travel
times:

```bash
cd logistics/backend
pytest -q tests/test_dynamic_slot_planner.py tests/test_slot_planning_api.py tests/test_slot_routing_adapter.py
ruff check app tests
mypy app
```

Run the live-service domain and adapter gate from the repository root:

```bash
./gradlew --no-daemon --max-workers=2 \
  :services:logistics-service:test \
  -Pkotlin.compiler.execution.strategy=in-process
```

The mandatory scenarios cover insertion before/after/between deliveries,
waiting, capacity exhaustion, multiple trips and drivers, one versus two
pickups, next-trip protection, locked assignments, no truck route, solo versus
trailer cache separation, arbitrary cabin counts, TTL, idempotent confirmation,
version conflict, invalid coordinates, timezone handling, and price-only zones.
