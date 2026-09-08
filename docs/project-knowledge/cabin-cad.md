# Cabin CAD Master Template Flow

Status: Published runtime recorded as of 2026-09-09. `cabin-cad/` has separate
local and network-project modes. The public `/cabin-cad/` route returns the new
index with HTTP 200, and a disposable browser E2E check has passed. The public
authenticated OIDC journey has also passed.

`cabin-cad/` remains an independent browser engineering prototype, with an
explicit boundary between its two CAD paths. Local `Master Setup` and `CAD`
continue to use prototype-local browser state. `Network Projects` authenticates
and loads a server-owned project through the public RWMS gateway. Creating a
network project captures the current local `CADDocument` and saved Master
Template once; opening an existing project installs its server document in an
in-memory editor projection and restores the local draft on exit. Remote
geometry is never written into the local browser draft as authoritative state.
The current network UI opens the server template as a render input; Master
Template geometry editing remains in the local Master Setup path.

Primary evidence:

- [`cabin-cad/src/app/App.tsx`](../../cabin-cad/src/app/App.tsx)
- [`cabin-cad/src/app/ErrorDiagnostics.tsx`](../../cabin-cad/src/app/ErrorDiagnostics.tsx)
- [`cabin-cad/src/diagnostics/runtimeDiagnostics.ts`](../../cabin-cad/src/diagnostics/runtimeDiagnostics.ts)
- [`cabin-cad/src/master-template/`](../../cabin-cad/src/master-template/)
- [`cabin-cad/src/master-template/domain/materialAppearance.ts`](../../cabin-cad/src/master-template/domain/materialAppearance.ts)
- [`cabin-cad/src/master-setup/`](../../cabin-cad/src/master-setup/)
- [`cabin-cad/src/master-setup/WallFramingLayoutPanel.tsx`](../../cabin-cad/src/master-setup/WallFramingLayoutPanel.tsx)
- [`cabin-cad/src/master-setup/ParametricBehaviorPanel.tsx`](../../cabin-cad/src/master-setup/ParametricBehaviorPanel.tsx)
- [`cabin-cad/src/master-template/engine/layoutEngine.ts`](../../cabin-cad/src/master-template/engine/layoutEngine.ts)
- [`cabin-cad/src/viewer3d/ParametricTemplateMeshes.tsx`](../../cabin-cad/src/viewer3d/ParametricTemplateMeshes.tsx)
- [`cabin-cad/src/viewer3d/InstancedCabinTemplateMeshes.tsx`](../../cabin-cad/src/viewer3d/InstancedCabinTemplateMeshes.tsx)
- [`cabin-cad/src/viewer3d/masterTemplateRenderCache.ts`](../../cabin-cad/src/viewer3d/masterTemplateRenderCache.ts)
- [`cabin-cad/src/viewer3d/materialTexture.ts`](../../cabin-cad/src/viewer3d/materialTexture.ts)
- [`cabin-cad/src/viewer3d/Viewer3D.tsx`](../../cabin-cad/src/viewer3d/Viewer3D.tsx)
- [`cabin-cad/src/collaboration/OnlineProjects.tsx`](../../cabin-cad/src/collaboration/OnlineProjects.tsx)
- [`cabin-cad/src/collaboration/projectSession.ts`](../../cabin-cad/src/collaboration/projectSession.ts)
- [`cabin-cad/src/collaboration/editorBinding.ts`](../../cabin-cad/src/collaboration/editorBinding.ts)
- [`cabin-cad/src/collaboration/auth.ts`](../../cabin-cad/src/collaboration/auth.ts)
- [`cabin-cad/src/collaboration/CollaborationBar.tsx`](../../cabin-cad/src/collaboration/CollaborationBar.tsx)
- [`cabin-cad/vite.config.ts`](../../cabin-cad/vite.config.ts)
- [`cabin-cad/README.md`](../../cabin-cad/README.md)

## Network Collaboration

[`cabin-cad/server/`](../../cabin-cad/server/) now contains a Node.js 22+
backend that owns CAD project membership, role assignment, control fencing,
accepted snapshot revisions, annotations, invitation state and durable
operation receipts in its own PostgreSQL database. It owns no RWMS cabin,
warehouse, lifecycle, STEP-source or other-service database fact. Its canonical
public HTTP boundary is
[`cad-service.yaml`](../../contracts/openapi/cad-service.yaml); there is no
second browser-defined contract.

The implemented interactive path is deliberately same-origin:

```text
Cabin CAD browser -> /api/cad/v1/**
    -> API gateway (private CAD_SERVICE_URL) -> cad-service
```

The browser must never receive an internal service address, `CAD_SERVICE_URL`,
or a direct `/api/internal/**` route. `/health` is a local service check, not a
browser collaboration endpoint. [`CadApi`](../../cabin-cad/src/collaboration/api.ts)
uses only the same-origin `/api/cad/v1/**` paths, `no-store` reads and Bearer
authentication. The development Vite proxy is opt-in through
`CAD_DEV_GATEWAY_URL`; it preserves those public paths rather than supplying a
browser service address. Nginx includes
`/etc/nginx/snippets/rwms-cad-api.conf` before generic API routing; its CAD
location allows 80 MiB bodies and uses 50-second proxy read/send timeouts. The
gateway receives the private CAD upstream through `CAD_SERVICE_URL`.

The interactive OIDC client is `rwms-cad`, with callback
`/cabin-cad/auth/callback`. Every collaboration operation needs a verified USER
Bearer JWT with `cad.project`; issuer, audience, signature, `exp`, `iat` and
`sub` are checked before current project membership is resolved. Missing,
revoked or inaccessible membership deliberately appears as a project-not-found
response rather than client-side cached access. The browser uses Authorization
Code with PKCE through `oidc-client-ts`; its OIDC user and transaction state
are tab-scoped `sessionStorage`, and refresh failure requires a new login.

After login, `OnlineProjects` lists memberships, opens a chosen server project,
or creates one from the captured local snapshots. A creator can issue the
one-client invitation from the collaboration bar. An invitation arriving before
login is retained only across that login in session storage, then accepted for
the authenticated subject; it never selects a role in the browser. The project
list and invitation acceptance always fetch their result from cad-service.

A project creator is assigned `designer`. A signed invitation is valid for 24
hours, may be accepted once by a signed-in recipient, and always assigns that
recipient `client`; neither create nor accept lets a caller choose the
designer role. The raw invitation capability is HMAC-derived after its durable
receipt is stored and is not retained in project state or the operation-receipt
table.

Project, document, template, annotation and control have separate positive
revisions. A controller writes the document under both document and control
fences; only a controlling designer writes the template. A non-controller may
request hand-off, while the designer may reclaim control only after the holder
has not refreshed presence for 60 seconds. Any member may add an annotation
without changing the document revision; the author or designer may resolve or
delete it, and only the author can change its text. Ordinary mutable operations
use a project-scoped `operationId` and return the durable result only for the
exact same actor, operation kind and body.

`ProjectSession` polls project revision metadata every 1.5 seconds while the
session is ready. It fetches the document, template and annotations separately
only when their corresponding revision changes, so a comment update does not
reload CAD geometry. Presence refresh is separately throttled to ten seconds.
Three consecutive non-authorisation read failures stop automatic polling and
leave an explicit recovery action; `401`, `403` and `404` also stop polling
immediately. This is polling, not a WebSocket or browser-owned projection.

At most one network mutation is pending. A document save carries its original
`operationId`, document revision and control-version fences; control, membership
and annotation changes carry their own expected version fence. An uncertain
submission retains the exact request identity and payload for retry, and the
session refuses exit or sign-out while its outcome is unknown. A rejected stale
document fence restores the last server-accepted editor snapshot instead of
making a local change authoritative. Project creation retains the same captured
snapshot and project ID for an uncertain retry.

Plan comments, freehand ink, arrows and rectangles are independent annotation
resources in canonical plan millimetres. Their review UI remains available to a
member who cannot control CAD geometry; they increment only the annotation
revision and never mutate `CADDocument`. The service remains authoritative for
author, resolution and deletion permissions.

`GET /api/cad/v1/projects/{id}/report-source` supplies public metadata and both
accepted geometry resources from one PostgreSQL statement, with membership
checked against that same state. It excludes annotations and invitation data.
Optional `MasterTemplate.manufacturing` schema-version 1 stores authored material
bindings, cutting parameters, surface layers, extra parts and drawing title
fields. It shares the existing designer-only template revision/control fences,
shared validation and JSONB persistence; no new database is involved.

The CAD toolbar's **Материалы и чертежи** dialog calculates an explicit frozen
source in a cancellable Worker. Designer-controlled template saves author
material/profile bindings, cut axes/allowances/stock/kerf, surface layers and
extras; members may read/export. Physical occurrences are evaluated once per
size variant, regardless of renderer hiding. The report subtracts CAD openings,
counts shared walls once and apportions crossing partitions/services to modules.
Stairs and landings remain shared town assemblies. Missing materials or complete
surface constructions are issues, never guessed successful quantities.

A3 approval sheets include local cabin plans, A–D wall elevations, measured
object/part coordinates and material tables; town sheets include floor plans,
module placement and stair/landing dimensions. Frames and 185×55-mm form-1
principal inscriptions follow the referenced ESKD layout, with blank signatures,
selected scales and embedded Cyrillic Osifont. These are approval documents, not
structural calculations or completed manufacturing process plans. Each sheet
has its own designation and a separate package index. PDF/SVG/CSV ZIP exports
include module BOMs, bar cuts/offcuts, authored sheet-piece/offcut dimensions and
revision/content hashes. Area-based sheet purchasing is an estimate; exact
nesting requires an authored layout. Imported STEP wall parts are not newly cut
by CAD openings; the report explicitly flags that unresolved manufacturing case.
The optional ПР-БК-01 layer preset records the [published Block Box build-up](https://block-box.ru/iz-proflista/blok-konteynery/standartnye/id-blok-konteyner-universalnyy-1-iz-proflista)
without inferring frame profiles, board recipes or unplaced equipment.

Implementation: [manufacturing projector](../../cabin-cad/src/manufacturing/report.ts),
[drawing projector](../../cabin-cad/src/manufacturing/drawings.ts),
[export package](../../cabin-cad/src/manufacturing/exportPackage.ts),
[dialog](../../cabin-cad/src/manufacturing/ManufacturingDialog.tsx).
Drawing-layout reference: [ГОСТ Р 2.104-2023, form 1](https://files.stroyinf.ru/Data/816/81679.pdf).

`CADDocument` schema-version 2 and `MasterTemplate` schema-version 1 remain
opaque versioned snapshots. `server/src/validation.ts` directly imports the
framework-free shared `src/cad` and `src/master-template` validators and
evaluator; it does not copy or redefine either domain model. The service
accepts at most 1,000 CAD modules/8 MiB, a 64-MiB template with at most 10,000
occurrences and 10,000 geometry resources, 20 members, 100 invitations and
1,000 annotations. CPU validation uses a bounded `worker_threads` pool with
two workers by default, a maximum of four, a bounded queue and a 30-second hard
deadline. PostgreSQL schema changes are Flyway-only under
[`server/db/migration/`](../../cabin-cad/server/db/migration/); the Node process
does not perform DDL.

The published runtime uses the `block-box-cad` Compose project with `cad-db` and
`cad-service`. Flyway V1 completed successfully on the new `cad-data` volume;
`cad-service` is private on `127.0.0.1:8097`, and its runtime environment file
`/var/lib/rwms/runtime/cad-service.env` has mode `0600`. The repository's
[`nginx-cad.conf.example`](../../cabin-cad/server/nginx-cad.conf.example)
documents the same 80 MiB and 50-second CAD Nginx settings.

The auth-service and API-gateway artifacts were built from clean primary
revision `823c3cc2`; the CAD source revision was `c88f7e8`. The release record
is `/var/lib/rwms/releases/rwms-cad-20260908T233144Z-823c3cc2/provenance/release.json`.
The public route and disposable browser E2E are verified as above. The public
authenticated OIDC journey also passed: discovery, token exchange and a
no-mutation public-project list each returned HTTP 200.

## Ownership And Layering

The existing `CADDocument` remains the ordinary editor's single source for
module dimensions, walls, openings, filling, floors, history, and 2D/3D
selection. Master-template preparation is a separate browser-owned snapshot;
it does not duplicate or replace the CAD document.

```text
Fusion STEP
    -> STEP adapter and tessellation
    -> MasterTemplate geometry, hierarchy and rules
    -> renderer-neutral parametric engine
    -> existing ViewerCanvas in Master Setup or ordinary CAD
```

The start screen mounts neither editor until the user selects `Master Setup`
or `CAD`. Ordinary CAD is unavailable until one valid Master Template has been
saved. Master-only behavior fields never enter the ordinary CAD UI.

The former structural-reinforcement aggregate and its automatic middle-module
generation are no longer part of `CADDocument`, commands, persistence,
validation, 2D/3D projections, settings or selection. `SectionGroup` and
`ComponentGroup` remain the supported serializable grouping objects. This is a
prototype-local domain deletion and changes no RWMS service or transport
contract.

## STEP Import And Identity

The MVP accepts one `.step` or `.stp` assembly exported from Fusion 360.
`occt-import-js` performs browser-side OpenCascade tessellation. The adapter
caps source bytes, mesh count, and vertex count, verifies the STEP envelope,
records a SHA-256 checksum and importer version, and converts Fusion Z-up
geometry to canonical `X = Length`, `Y = Width`, `Z = Height` axes.

Each imported physical mesh becomes a distinct occurrence with its own stable
snapshot ID, geometry resource, imported transform, placement behavior, and
hierarchy node. Sanitized Fusion names group equal occurrences into component
types, from which the setup BOM is derived. A BOM type selection highlights all
linked occurrences; a scene or hierarchy occurrence selection resolves back to
the same BOM type.

Each imported or generated BOM type retains one stable render identity. Its
eye control stores that identity in the optional schema-version 1 hidden list.
Hiding is presentation-only: the row, occurrences, layout ownership and
parametric evaluation remain intact, while the shared Master Setup/ordinary
CAD renderer omits matching physical objects. Sheet offcut outlines resolve to
their physical sheet type, so they follow the same visibility decision rather
than becoming an independent BOM line.

The Fusion hierarchy and BOM share that renderer visibility instead of owning
separate scene filters. Each assembly, nested assembly, and occurrence row has
a leftmost eye. An assembly expands its action to all descendant physical
occurrences and reports `mixed` when only some descendants are visible; the
optional schema-version 1 `hiddenOccurrenceIds` list stores those stable leaf
decisions. Showing one leaf beneath a BOM-hidden type converts the remaining
hidden siblings to occurrence-level entries, so it does not reveal unrelated
instances. Generated procedural or wall-framing geometry follows its managed
source occurrences, and a sheet layout plus its offcuts follow its material
zone. Visibility remains presentation-only and is evaluated by the same
renderer in Master Setup and ordinary CAD.

An assembly row has independent expand and select controls. A selected
structure node may own `isWall`; descendants inherit the role. This metadata is
template presentation state, not a second CAD wall aggregate. Once a saved
template contains wall geometry, ordinary 3D suppresses only the generated
module-owned exterior wall faces and applies the exterior-wall opacity to the
tagged template occurrences. `CADDocument.walls` remains authoritative for
constraints, openings, shared spans, elevations and internal walls, so the
existing engineering flow and opening ownership are not duplicated.
At zero opacity both generated wall faces and tagged template occurrences lose
their visible faces, outlines and shadows; opening visuals keep their separate
opacity. Returning to 100% crosses to a newly keyed opaque Three.js material so
blending state cannot survive and depth writing is restored. Camera-side
cutaway transforms the live camera point into each module's normalized template
footprint, unions evaluated post-deformation bounds by the outermost authored
wall structure block, and omits all imported or layout-generated occurrences
of a facing block together. Non-wall floor layouts are unaffected, and the
corresponding CAD exterior-wall opening visuals are omitted only from the
renderer. Far walls and all persisted CAD entities remain unchanged. When a
Master Template is present, ordinary CAD also suppresses its generated module
floor, roof and footprint-highlight meshes so the template is the sole module
shell representation; engineering entities remain document-derived.

## Ordinary CAD Renderer Scaling

An assembly structure node may own the optional schema-version 1
`optimizeRendering` role. It is inherited positively by all physical
descendants; an older snapshot without the flag evaluates it as false. The
separate `isWallInterior` role is also assembly-only and is inherited by
imported and layout-generated geometry. Its authoring control is disabled until
the owning node directly enables optimization, and disabling optimization
clears the node's direct wall-interior role. The authoring controls and
immutable updates live in
[`ParametricBehaviorPanel.tsx`](../../cabin-cad/src/master-setup/ParametricBehaviorPanel.tsx)
and [`model.ts`](../../cabin-cad/src/master-template/domain/model.ts); strict
validation remains in
[`validation.ts`](../../cabin-cad/src/master-template/domain/validation.ts).

Ordinary CAD groups modules by saved template object, revision and resolved
parameter values, including Length, Width and Height. The renderer cache
evaluates each combination once and reuses the same immutable occurrence
arrays. For every optimized occurrence in a dimension variant,
[`InstancedCabinTemplateMeshes.tsx`](../../cabin-cad/src/viewer3d/InstancedCabinTemplateMeshes.tsx)
creates one shared `BufferGeometry` and one `InstancedMesh` batch per required
material/opacity/support state; each cabin supplies only its composed world
matrix. A 250-module equal-dimension projection therefore retains one main draw
batch per optimized authored part instead of one draw call per part per cabin.
If current opacity and cutaway rules submit no instance of a part, the renderer
creates neither its `BufferGeometry` nor an `InstancedMesh` batch.
Unmarked occurrences keep the detailed path. The selected cabin, or the
nearest cabin when none is selected, keeps detailed part shadows and outlines;
overview cabins omit those edges and use one instanced shell-shadow pass.

Wall interiors are a render role, not new CAD wall data. Exactly 100% effective
exterior-wall opacity enables wall optimization and submits no wall-interior
occurrences. Every value from 99% through 0% keeps full wall-interior geometry
eligible while applying that opacity directly to exterior-wall occurrences;
at 0% the exterior wall produces no face, outline, shadow or instance batch.
Any active camera cutaway submits no wall-interior occurrence at any opacity,
then independently omits the camera-facing exterior wall block. The ordinary
and instanced projections share the same pure visibility predicate in
[`ParametricTemplateMeshes.tsx`](../../cabin-cad/src/viewer3d/ParametricTemplateMeshes.tsx).

Camera-side state is presentation-only. Orbit movement invalidates Three.js
frames continuously, but reports a React cutaway update only when the camera
crosses to another footprint side. By default only modules on the active floor
receive the camera point. The dependent `Применить для всех этажей` checkbox
extends the same rule to every currently rendered floor; it is disabled and
cleared while cutaway itself is off. Neither option is persisted into
`CADDocument`.

The current generic STEP library supplies tessellated occurrence geometry but
does not expose every Fusion-native identity or timeline fact. The schema keeps
source provenance, occurrence IDs, quaternion, scale, and local geometry
separate so a later Fusion add-in can populate richer metadata without moving
parametric decisions into the importer.

The adapter derives source/mapped/model bounds with bounded scans rather than
concatenating every coordinate, maps each mesh into one owned normalized
position array, reuses the file buffer for SHA-256, validates every triangle
index, and discards a rejected WASM initialization promise so a later import
can retry. These changes bound application-side peak duplication after OCCT
returns. `ReadStepFile` is still synchronous and produces its result before the
mesh/vertex guard can inspect it; a pathological accepted-size STEP may still
terminate a browser tab, in which case the persisted `tessellation.started`
event identifies the unfinished phase.

## Material Appearance

Schema-version 1 accepts optional material appearance without changing its
version. Appearance may be assigned to one physical occurrence, one assembly
structure node, one component/BOM type, or one sheet layout. Imported
occurrences resolve these scopes in this order: direct occurrence, deepest
matching ancestor assembly, component type, imported geometry colour. This is
presentation metadata; visibility still determines whether an object is
rendered and does not erase its material.

Every appearance retains a `#RRGGBB` colour fallback and a positive physical
tile size. Its optional raster is an embedded PNG, JPEG, or WebP data URL with
file name, decoded dimensions, byte size, MIME type, and SHA-256. Validation
checks the actual signature, encoded dimensions, decoded byte count and digest;
it rejects external URLs, payloads above 1 MiB, and dimensions above 4096 px.
The renderer caches one decoded texture per SHA in the mounted tree, configures
sRGB and two-axis repeat wrapping, derives planar UVs from local millimetre
coordinates and the physical tile size, and disposes retired GPU textures. A
missing or failed image decode renders the fallback colour. Generated
procedural parts resolve their source component type; generated sheets,
offcuts, and guides resolve their sheet layout, while guides and offcuts remain
texture-free authoring outlines.

## Parametric Rules

Template parameters are an extensible collection. The current UI requires the
semantic `Length`, `Width`, and `Height` parameters but the evaluator and UI
iterate all declared parameters.

Geometry behavior belongs to a component type:

- `rigid` leaves source vertices unchanged;
- `linearStretch` identifies a semantic driver, a local geometry axis, and
  optional minimum fixed-start/fixed-end lengths; zero selects automatic end
  protection;
- `multiAxisStretch` composes two or three `linearStretch` rules with unique
  local axes inside the same type-owned behavior value. Existing single-rule
  schema-version 1 snapshots remain valid.

Linear deformation never applies a parameter-derived Three.js object scale.
The topology analyser welds coincident STEP vertices, distinguishes longitudinal
tessellation seams from physical sloped features, and chooses the widest safe
vertex-free axial interval allowed by the configured minima. Vertices before
that interval remain unchanged and vertices after it translate by the full
delta. Multi-axis evaluation resolves every safe interval against the original
source topology, then composes independent coordinate changes and recomputes
normals once; duplicate local axes are rejected. Cross-section coordinates are
never scaled. Complete mitres, holes, fillets, and other end geometry therefore
remain rigid. A geometry with no safe interval on any configured axis fails
closed instead of falling back to whole-mesh scale or angle distortion.
The current 46-occurrence `CABIN.step` preserves frame, floor/ceiling framing,
sheet zones and four separate wall-frame assemblies. The earlier channel-only
topology regressions remain covered by focused tests; new wall rules use the
same topology-safe component behavior rather than object scale.

Placement behavior belongs to an occurrence. Each canonical axis is either
fixed or driven by a parameter with a `start`, `center`, or `end` anchor. This
keeps shared type geometry independent from instance-specific corner
placement. Imported anchors are inferred from assembly bounds and remain
editable in Master Setup.

Changing a Local Stretch Axis in Master Setup also replaces its semantic
driver with the canonical parameter for that axis (`X = Length`, `Y = Width`,
`Z = Height`) and clears fixed-region values captured for the former axis. A
candidate template or test-parameter set is evaluated before state commit. If
evaluation fails, the prior geometry and parameter values remain active.

## Assembly Layout Rules

Assembly layouts are evaluated after imported type geometry and occurrence
placement. They are not component-type geometry variants and do not create a
second CAD document or scene.

A `proceduralLayout` owns two real boundary occurrences, the imported preview
occurrences from their one Fusion assembly, a semantic driver, one canonical
pattern axis, one active distribution rule, and two signed edge constraints.
Each constraint retains the selected target/reference min-or-max edge offset,
so a 50 mm board-to-angle distance remains 50 mm after the referenced frame
part moves. Imported interior preview boards are excluded and deterministic
copies of the already evaluated boundary-board geometry fill the remaining
span; cross-section and perpendicular linear stretch therefore remain shared
with the source component type. The active distribution is mutually exclusive:
either a requested clear edge-to-edge spacing adds/removes copies as the span
changes, or an exact internal-board count remains fixed while equal clear gaps
are recalculated. A schema-version 1 rule without the mode field retains the
original clear-spacing meaning.

A `sheetLayout` owns one imported rectangular guide-zone occurrence, its
reference world bounds, physical stock-sheet dimensions, material appearance
with a colour fallback, a default
0/90-degree orientation, and optional per-row orientation overrides. The
smallest positive world-bounds span is the guide's thickness axis; the other
two axes form the material plane and resolve their semantic drivers from the
canonical `X = Length`, `Y = Width`, `Z = Height` mapping. Runtime projection
uses this effective plane even when an older schema-version 1 layout contains
the former hard-coded X/Y axis metadata. Sheet thickness is any finite positive
millimetre value, including decimals, and is independent from the guide
occurrence's authoring depth; stock width and length remain positive whole
millimetres. Only the two in-plane zone maxima receive parameter deltas. One
renderer-neutral projection flows rows across the current zone, tiles stock
along each row, clips physical pieces to the exact guide boundary, and
partitions stock-minus-used rectangles without overlap. It also derives used
area, cut-away area/percentage, and cut-sheet count.

Master Setup can split its existing central preview into linked 2D and 3D
halves. A row in the accessible 2D SVG toggles its own orientation and causes
the same projection to reflow following rows. Physical sheet boundaries are
dashed. Cut-away stock is hidden by default and, when explicitly enabled, is
shown in 2D and 3D only as dashed outline geometry with no fill. Guide and
offcut occurrences are authoring-only, excluded from BOM, and hidden in
ordinary CAD; the clipped physical sheets remain driven by the saved template.
Procurement optimization, offcut reuse, and arbitrary-polygon nesting remain
outside this stage.

A `wallFramingLayout` owns one selected Fusion assembly with exactly four
distinct closing boundary occurrences. The authoring layer classifies two
uprights and the bottom/top boards from evaluated world bounds, derives the
pattern, height and thickness axes, and writes the needed type-level stretch
and per-occurrence placement rules without overwriting independent axes. The
pure layout evaluator retains the four imported boundaries, removes other
managed preview occurrences, and fills the clear rectangle with vertical studs
using either maximum clear spacing or an exact equal-gap count. Horizontal
rows are individual box segments inside each bay between adjacent verticals.
They support the same two automatic distributions or stable manual bottom
offsets with optional mirrored top counterparts. Duplicate mirrored positions
are deduplicated; overlapping, out-of-zone, inverted or open topology and more
than 10,000 generated occurrences fail closed.

The accessible material SVG draws dashed physical sheet borders first and a
separate final hit/outline layer afterwards. Hover or keyboard focus therefore
shows a closed four-sided rectangle above neighbouring fills instead of losing
a shared edge.

The layout collection is optional inside schema-version 1 snapshots, so
previously saved frame-only templates remain readable. New STEP imports start
with an empty collection and explicit Master Setup edits remain the only way to
create layout metadata.

## Persistence And Failure Behavior

An explicit save validates the complete cross-reference graph and stores one
geometry-plus-rules snapshot under `cabin-cad/master-template/v1`. Unreadable
bytes are preserved under a recovery key and disable ordinary CAD instead of
falling back to fabricated geometry. Unsaved Master Setup work is protected by
confirmation before leaving or replacing it with another STEP file.

The evaluator exposes a fail-closed discriminated result with a readable
message and non-empty stack. Master Setup retains the last successful scene,
rejects an invalid candidate before commit, and renders the operation scope,
selection, Geometry Behavior, active dimensions, and full stack inside an
expandable alert. Texture decode errors use the same diagnostic while keeping
the colour fallback. A top-level React plus browser error boundary catches an
otherwise-unhandled render, event, or promise failure and replaces a blank
screen with a copyable stack trace and recovery action.

Ordinary CAD derives MEP clearance paths without mutating the persisted route.
If one derived branch has no collision-free solution, the layout returns a
typed conflict, omits only that branch from 3D, keeps the rest of the editor
available, identifies the route in the status bar, and journals
`mep-routing/layout.conflict`. The source route remains available for a user to
move or remove. The Konva plan canvas also retains a non-zero minimum backing
size until the viewport is measured, preventing fractional browser zoom from
creating a zero-sized draw source. These recovery boundaries are implemented
by [`mep.ts`](../../cabin-cad/src/cad/commands/mep.ts),
[`App.tsx`](../../cabin-cad/src/app/App.tsx), and
[`Editor2D.tsx`](../../cabin-cad/src/editor2d/Editor2D.tsx).

Runtime diagnostics are installed before the lazy application graph. The
single structured boundary records application modes, store commands and
recovery, STEP-import phases, template evaluation, texture decoding, WebGL
context loss/restoration, uncaught browser and React failures, CSP violations,
connectivity changes and long tasks without storing model payloads. Entries
retain stable `sessionId`, `sequence`, `scope`, `event`, duration/count context
and original stacks. Browser storage keeps a bounded 400-entry/384-KiB journal
under `cabin-cad/diagnostics/v1`; the fatal UI can copy or export it as NDJSON.
The browser remains the only diagnostic persistence available in a static
production deployment.

The Vite development server accepts only bounded same-origin diagnostic
entries and appends them with mode `0600` to
`cabin-cad/.runtime/diagnostics.ndjson`. One 5-MiB rotation is retained as
`diagnostics.1.ndjson`; the directory is ignored by Git. This file sink is a
local recovery aid, not RWMS business persistence or an externally exposed
service. Searches correlate both files by session and event before a crash is
reproduced.

The ordinary CAD maps every `CabinModule` length, width, and height to the
saved semantic parameters and evaluates the same engine used by Master Setup.
The retired hard-coded frame GLB and its mesh-scale path are not a runtime
fallback.

The shared Master Setup/CAD canvas and one shared component-library preview
render on demand. Every canvas reports initialization and WebGL context
loss/restoration. Native SVG catalog cards share that one preview; built-in
model details use stable catalog IDs and shared geometry/material resources.
The 3D camera fits town bounds on entry and on an explicit fit request, while
ordinary dragging preserves the camera. Plan rendering culls offscreen module,
wall and opening shapes with a margin while the full document stays authoritative.
Shared-wall discovery checks footprint contacts before examining wall details.
Opacity-specific GLB material clones are disposed when replaced without
disposing cached GLTF geometry, and texture cache retirement remains explicit.
The shared canvas requests the browser's `high-performance` WebGL adapter, but
a browser application cannot select a named integrated or discrete GPU; the
browser, operating system and graphics driver retain that decision
([`ViewerCanvas.tsx`](../../cabin-cad/src/viewer3d/ViewerCanvas.tsx)).

## Verification

Focused evidence lives in:

- [`master-template tests`](../../cabin-cad/src/master-template/__tests__/)
- [`Master Setup tests`](../../cabin-cad/src/master-setup/)
- [`mode-selection tests`](../../cabin-cad/src/app/ModeSelectionScreen.test.ts)
- [`parametric renderer tests`](../../cabin-cad/src/viewer3d/__tests__/parametricTemplateMeshes.test.ts)
- [`instanced renderer tests`](../../cabin-cad/src/viewer3d/__tests__/instancedCabinTemplateMeshes.test.ts)
- [`renderer cache tests`](../../cabin-cad/src/viewer3d/__tests__/masterTemplateRenderCache.test.ts)

The component gate is `npm test`, `npm run typecheck`, and `npm run build` from
`cabin-cad/`, followed by a real-browser STEP import and ordinary-CAD resize
flow against the built artifact.
