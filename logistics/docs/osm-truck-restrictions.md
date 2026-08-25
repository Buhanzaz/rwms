# OSM truck restrictions in Valhalla 3.8.3

This capability matrix applies only to the pinned self-hosted
`ghcr.io/valhalla/valhalla-scripted:3.8.3` engine. It deliberately does not
claim that every OpenStreetMap conditional tag is enforced.

The working route always uses `costing=truck` and receives the effective
height, width, total combination length, actual gross weight, configured
operational axle load, and axle count. Failure to find that route is
`NO_SAFE_ROUTE`; the application must not silently retry with `auto`/car
costing.

| OSM tag or restriction | Support | Valhalla 3.8.3 notes |
| --- | --- | --- |
| `hgv=*`, `access=*`, `motor_vehicle=*`, `vehicle=*` | Supported | Truck access masks, designated routes, and destination access participate in truck costing. |
| `maxheight=*`, `maxheight:physical=*` | Supported | Static height is enforced; the physical tag is the parsed fallback. |
| `maxwidth=*`, `maxwidth:physical=*` | Supported | Static width is enforced; the physical tag is the parsed fallback. |
| `maxlength=*` | Supported | Checked against the effective full vehicle/combination length. |
| `maxweight=*` | Supported | Checked against actual gross routed weight, not payload capacity. |
| `maxaxleload=*`, `maxaxles=*` | Supported | Checked against the configured operational axle load and axle count; RWMS does not invent axle distribution from total mass. |
| `oneway=*` | Supported | Directional graph traversal is enforced. |
| turn restrictions | Supported | Static and parsed conditional turn restrictions are applied by graph search. |
| road classification | Supported | Road class participates in truck costing. |
| closed roads | Supported when present in graph/feed | OSM access/closure state in the built tiles is honored. Live closures require a separately configured traffic feed; none is fabricated by this project. |
| `hgv:conditional=*`, `access:conditional=*`, `motor_vehicle:conditional=*` | Supported for parsed time rules | Evaluation requires a timezone-aware departure time; the adapter sends the local wall-clock minute. |
| destination/delivery access and dimensional exemptions | Partial | General access semantics work. Dimensional exemptions are limited to the exact destination/delivery forms parsed by 3.8.3. |
| directional dimension/weight variants | Partial | Applied where the corresponding forward/backward form is parsed into the graph. Coverage is not claimed for arbitrary suffix combinations. |
| `maxweight:conditional=*`, `maxheight:conditional=*`, `maxwidth:conditional=*`, `maxlength:conditional=*`, `maxaxleload:conditional=*` | Not guaranteed / unsupported | General time-varying numeric restriction values are not fully parsed and enforced by 3.8.3. They must not be presented in the UI as checked. |
| `maxweightrating:hgv=*` | Unsupported | Not parsed into a truck-costing restriction by the pinned engine. |
| `hgv_articulated=*`, `trailer=*` | Unsupported as dedicated semantics | Valhalla still receives and enforces the effective dimensions and weight of the attached combination, but these OSM tags have no dedicated 3.8.3 behavior. |

## Operational data identity

OSM PBF files and generated Valhalla tiles live only in Docker volumes and are
not committed. `OSM_DATA_VERSION` must be changed to the actual extract/build
identity whenever those volumes are rebuilt. Every persisted route profile
snapshot and cache partition includes this identity and the provider version.

The `/locate` endpoint may snap generated test points to an OSM graph edge. A
successful snap is only a candidate location; it is not evidence that a safe
truck route exists. The final leg must still pass `/route` with its own
`EffectiveTruckProfile`.

## Verification boundary

Unit tests validate the exact truck payload, response parsing, error mapping,
timezone behavior, and the absence of an `auto` retry. The checked fixture
[`backend/tests/fixtures/valhalla_maxlength.osm`](../backend/tests/fixtures/valhalla_maxlength.osm)
contains independent tagged road pairs for `maxlength`, `maxheight`,
`maxwidth`, `maxweight`, `maxaxleload` and `hgv=no`. The reproducible command
below converts it to PBF, builds an isolated Valhalla 3.8.3 graph and executes
the real HTTP integration suite:

```bash
make test-valhalla-truck
```

The primary proof uses a short `maxlength=12` path and an unrestricted longer
path: a 9 m profile selects the short path while an 18 m trailer combination
is forced onto the long path. The disposable graph and container are removed
after the check; neither PBF nor generated tiles are committed.

## Authoritative sources

- [Valhalla 3.8.3 route and truck API reference](https://github.com/valhalla/valhalla/blob/3.8.3/docs/docs/api/route/api-reference.md)
- [Valhalla 3.8.3 matrix API reference](https://github.com/valhalla/valhalla/blob/3.8.3/docs/docs/api/matrix.md)
- [Valhalla 3.8.3 truck costing checks](https://github.com/valhalla/valhalla/blob/3.8.3/src/sif/truckcost.cc)
- [Valhalla 3.8.3 OSM tag parsing](https://github.com/valhalla/valhalla/blob/3.8.3/lua/graph.lua)
- [Open issue: conditional maximum-weight restrictions](https://github.com/valhalla/valhalla/issues/4683)
- [Open issue: destination exemptions for dimensional limits](https://github.com/valhalla/valhalla/issues/5287)
