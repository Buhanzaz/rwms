# Current Architecture

Status: Confirmed repository structure as of 2026-08-30.

Primary evidence:

- [`settings.gradle.kts`](../../settings.gradle.kts)
- [`services/`](../../services/)
- [`contracts/`](../../contracts/)
- [`service-catalog.md`](service-catalog.md)
- [`panel/package.json`](../../panel/package.json)
- [`app/README.md`](../../app/README.md)
- [`client-app/README.md`](../../client-app/README.md)
- [`worker-app/README.md`](../../worker-app/README.md)
- [`driver-app/README.md`](../../driver-app/README.md)

## Runtime Context

```mermaid
flowchart LR
    Panel[Web panel] --> Gateway[API gateway]
    Manager[Manager Android app] --> Gateway
    Customer[Customer Android app] --> Gateway
    Worker[Worker Android app] --> Gateway
    Driver[Driver Android app] --> Gateway
    Planner[Warehouse logistics planner] -. OAuth2 planning boundary .-> Auth
    Planner -. private versioned planning API .-> Domain
    Gateway --> Auth[Auth service]
    Gateway --> Domain[Public domain APIs]
    Domain --> ServiceDB[(Service-owned PostgreSQL)]
    Domain <--> Kafka[Kafka facts]
    Media[Go media service] --> MediaDB[(Media PostgreSQL)]
    Media --> MinIO[(Private MinIO)]
    Domain <--> Media
    Domain <--> Kafka
    Media <--> Kafka
```

Auth-service permits development credentials only with a literal loopback issuer
and an explicit `dev`/`test` profile. A public issuer requires external bootstrap
credentials and persistent signing keys even under those profiles. The task-board
machine client always uses external credentials: both producer and consumer reject
the retired repository secret. Auth owns revision-fenced rotation and atomically
revokes stored machine grants on a secret change; locally validated JWTs retain
their existing expiry. See [auth configuration and rotation](../../services/auth-service/README.md#oauth-client-lifecycle)
and [task-board security](../../services/task-board-service/README.md).

The gateway is a stateless transport edge. It does not aggregate business
responses or own workflow state. Each downstream service validates its token
and owns its commands. Anonymous customer registration is forwarded through
the same edge, but auth-service owns its cookie/header CSRF check. The gateway
deliberately keeps no source-address rate-limit store: it removes untrusted
forwarding headers and supplies the immediate TCP peer address. Auth-service
owns durable per-source and global fixed-window registration budgets before
password hashing, while production-ingress throttling remains an independent
defence-in-depth control. A future reverse-proxy trust boundary must be explicit;
otherwise callers behind that proxy share its immediate-peer budget.

Long-lived SSE routes remain transport-only. The gateway bounds their
concurrency, relays the producer's bytes with Servlet asynchronous I/O, flushes
each complete heartbeat/event item and cancels the upstream subscription when
the client disconnects. Heartbeat timing and event meaning stay with the
producing service; the gateway does not create domain events or retain stream
state.

Evidence:
[`gateway registration boundary`](../../services/api-gateway-service/README.md),
[`auth registration owner`](../../services/auth-service/src/main/java/dev/buhanzaz/rwms/auth/service/CustomerRegistrationService.java),
[`registration throttle owner`](../../services/auth-service/src/main/java/dev/buhanzaz/rwms/auth/service/CustomerRegistrationThrottle.java),
[`canonical forwarded address`](../../services/api-gateway-service/src/main/java/dev/buhanzaz/rwms/gateway/web/CanonicalAuthForwardedHeadersFilter.java),
[`SseProxyHandler.java`](../../services/api-gateway-service/src/main/java/dev/buhanzaz/rwms/gateway/config/SseProxyHandler.java),
[`GatewaySseConcurrencyIntegrationTest.java`](../../services/api-gateway-service/src/test/java/dev/buhanzaz/rwms/gateway/GatewaySseConcurrencyIntegrationTest.java).

### Standalone engineering prototypes

`cabin-cad/` is a separate browser engineering prototype rather than an RWMS
domain client. It currently keeps its CAD document and Fusion-derived Master
Template in browser storage, calls no gateway or domain service, and owns no
authoritative cabin lifecycle fact. Its Master Setup imports separate STEP
occurrences, assigns type geometry and occurrence placement rules, and feeds
the same renderer-neutral evaluator to Master Setup and ordinary CAD. See the
[`Cabin CAD flow`](cabin-cad.md) and
[`application shell`](../../cabin-cad/src/app/App.tsx).

`logistics/` is a separate React/FastAPI/PostGIS warehouse-planning deployable
with one common map and warehouse-scoped resources, requests, plans and ordered
hourly isochrone tariffs. It automatically reconciles active canonical
RWMS warehouses under the same UUID: owner-held coordinates win, while an
address-only identity is automatically resolved by the existing server
geocoder and retained until its address or city changes. One failed resolution
leaves that identity unavailable without hiding routable siblings. There is no
second create/connect lifecycle or required first polygon. Selecting a
warehouse scopes drivers, vehicles, shifts, requests and plans without
moving the viewport. An explicit “go to warehouse” control recentres the map;
markers for other routable warehouses remain available for comparison.

A private Valhalla truck graph is built from a source-manifested Central plus
Northwestern Federal District OpenStreetMap PBF set. A one-shot Osmium importer
derives a same-version diagnostic PostGIS restriction overlay; exact per-leg
Valhalla costing remains the route-safety authority. Customer slot routing
identifies that graph with an explicit operator-supplied data version, combines
it with endpoint, truck profile and departure bucket in a bounded 512-entry LRU
matrix cache, and expires each entry under a positive at-most-24-hour TTL.
Changing tiles or restrictions requires advancing the version; no URL change
or process restart is treated as a substitute. Visual depot/request
isochrones are optional display layers and never replace exact directed route
matrices.

The planner never reads an RWMS database. When enabled, it authenticates as the
dedicated `logistics-planner` client with sole scope `logistics.planning`, reads
canonical warehouses and warehouse-qualified drivers, refreshes the selected
warehouse's 31-day demand horizon before returning its workspace, and
closes request acceptance without publishing an unconfirmed plan. An
authoritative request revision archives only mutable heads for the affected
dates; confirmed revisions and their audit history are immutable. RWMS remains
the owner of warehouses, orders, shipments, worker identity and assignment
validation.

One non-archived `RoutePlan` revision may exist for one warehouse-local date.
Every replacement names the exact predecessor, archives it under a warehouse
row lock and creates a linked successor; confirmed revisions cannot be
replaced. Confirmation atomically claims the involved requests, fixes the date
of flexible demand and archives competing mutable date candidates. Publication
to RWMS is a separate, version-fenced command and accepts only a confirmed
revision. Test workload generation can replace only plans whose complete
request set is generator-owned; real, mixed and confirmed plans fail closed.

Plan apply also carries one exact driver-shift snapshot per assigned
driver/work date. Logistics validates and relays those snapshots with stable keys to task-board
only after every route assignment for that driver/date was applied or replayed without rejection;
task-board, not the planner or logistics, owns the resulting daily shift. The snapshot retains the
physical route origin, exact support-link evidence, vehicle/trailer, start odometer, trip count,
unrounded `routeDistanceMeters`, and one ordered executable operation list. Task-board persists the
operation list under the replaceable-until-frozen plan, and DriverApp renders its planned
arrival/departure and load transitions from the server snapshot. Generated and manual simulator
jobs are not transferred.
An explicit contractor handoff is the separate exception: task-board owns the
reusable on-demand contractor profile, the simulator never includes it in staff
route optimization, and logistics applies concrete assignments for the planning
date selected in the header without requiring an internal vehicle, shift or cycle
snapshot. Profile dates are not planning state; the dispatcher chooses either
automatic assignment of eligible unassigned work or an explicit manual request set.

A separate opt-in publishes one active anonymous workload, period-shift and
warehouse-scoped isochrone-tariff snapshot per warehouse. The URL path owns
warehouse
identity; the body contains no duplicate warehouse or workspace identity.
The tariff list starts at one hour, advances in contiguous one-hour steps and
contains at most twelve entries. Exact Valhalla road time selects the first
covering entry; the last entry is also the hard delivery-acceptance boundary.
Immutable receipts and a monotonic per-warehouse generation make exact retries
idempotent and reject delayed older state. Replacement can change slot
feasibility but never deletes a real booking. See the
[`planner architecture`](../../logistics/docs/ARCHITECTURE.md),
[`planning controller`](../../services/logistics-service/src/main/java/dev/buhanzaz/rwms/logistics/planning/api/PlanningIntegrationController.java),
[`planning directory`](../../services/logistics-service/src/main/java/dev/buhanzaz/rwms/logistics/planning/service/PlanningResourceDirectoryService.java),
[`capacity owner`](../../services/logistics-service/src/main/java/dev/buhanzaz/rwms/logistics/customer/capacity/service/WarehouseCapacitySnapshotService.java),
[`V60`](../../services/logistics-service/src/main/resources/db/migration/V60__customer_scenario_capacity_projection.sql),
[`V61`](../../services/logistics-service/src/main/resources/db/migration/V61__customer_terms_capacity_shifts_and_reception.sql),
[`V64`](../../services/logistics-service/src/main/resources/db/migration/V64__dynamic_delivery_slots_and_tariff_zones.sql),
[`V65`](../../services/logistics-service/src/main/resources/db/migration/V65__warehouse_capacity_identity_and_tariff_zones.sql),
[`V74`](../../services/logistics-service/src/main/resources/db/migration/V74__normalize_warehouse_isochrone_tariffs.sql),
[`RWMS adapter`](../../logistics/backend/app/integrations/rwms.py), and
[`dynamic-slot design`](../isochrone-slot-planning.md).

The standalone operator slot checker has a separate server-side Yandex address
boundary for autocomplete, suggestion resolution and reverse geocoding. Its
Geosuggest and Geocoder credentials never enter the browser. This does not
authorize the RWMS feed synchronizer to disclose address-only customer orders;
those rows continue to fail with `COORDINATES_REQUIRED` until the distinct open
product decision is resolved. Evidence:
[`geocoding boundary`](../../logistics/backend/app/api/geocoding.py),
[`runtime settings`](../../logistics/backend/app/config.py), and
[`address-only decision`](open-questions.md#address-only-order-geocoding-for-planning).

### Android release surfaces

`downloads-site/` is a static aggregate page at `/downloads/`; it has no
Android package identity, OAuth client, database or mutable release state. It
renders four cards from the separate ManagerApp, CustomerApp, DriverApp and WorkerApp
release records and links only to their immutable versioned APK URLs. The
aggregate page never copies an APK between application-owned roots. Nginx
serves the Manager, Customer, Driver and Worker artefacts from separate filesystem roots;
each release record supplies the exact public URL, package identity, version
and SHA-256 before its card can render. Each application build fails closed for
release artifacts without a complete readable external signing configuration.
Each application-owned download surface additionally validates a checked-in
channel policy, immutable manifest provenance and the exact APK package,
version, SHA-256 and single signing certificate. A certificate is accepted only
when pinned for `PRODUCTION` or `INTERNAL_TEST`; rotation is an explicit policy
change. ManagerApp and DriverApp currently have no production signer allowlist,
so their production publication remains blocked rather than falling back to a
debug key.

Evidence:
[`aggregate builder`](../../downloads-site/scripts/build-site.mjs),
[`ManagerApp trust policy`](../../manager-download-site/release-trust-policy.json),
[`CustomerApp release record`](../../client-download-site/release.json),
[`CustomerApp trust policy`](../../client-download-site/release-trust-policy.json),
[`DriverApp release record`](../../driver-download-site/release.json),
[`DriverApp trust policy`](../../driver-download-site/release-trust-policy.json),
[`ManagerApp release record`](../../manager-download-site/release.json),
[`WorkerApp release record`](../../worker-download-site/release.json), and
[`WorkerApp trust policy`](../../worker-download-site/release-trust-policy.json).

## Deployables And Ownership

| Deployable            | Shape                      | Owner responsibility                                                       | Canonical API                                                                  |
| --------------------- | -------------------------- | -------------------------------------------------------------------------- | ------------------------------------------------------------------------------ |
| `auth-service`        | Stateful Spring            | Login, user/worker credentials, roles, OAuth clients and warehouse grants  | [`auth-service.yaml`](../../contracts/openapi/auth-service.yaml)               |
| `api-gateway-service` | Stateless Spring           | Public routing and edge transport policy                                   | Downstream contracts                                                           |
| `warehouse-service`   | Stateful Spring            | Warehouse identity, metadata and timezone                                  | [`warehouse-service.yaml`](../../contracts/openapi/warehouse-service.yaml)     |
| `asset-service`       | Stateful Spring            | Cabins, status, equipment, balances, holds and leases                      | [`asset-service.yaml`](../../contracts/openapi/asset-service.yaml)             |
| `task-board-service`  | Stateful Spring            | Queues, workforce, task execution, contractor execution and the Driver Up daily-shift state machine | [`task-board-service.yaml`](../../contracts/openapi/task-board-service.yaml) |
| `maintenance-service` | Stateful Spring            | Catalog, estimates, repairs, acceptance and write-off decisions            | [`maintenance-service.yaml`](../../contracts/openapi/maintenance-service.yaml) |
| `inventory-service`   | Stateful Spring            | Sessions, findings, completion and publication                             | [`inventory-service.yaml`](../../contracts/openapi/inventory-service.yaml)     |
| `logistics-service`   | Stateful Spring            | Rental counterparties, inquiries, orders, returns, shipments, transfers and scoped contractor route capabilities | [`logistics-service.yaml`](../../contracts/openapi/logistics-service.yaml)     |
| `media-service`       | Stateful Go                | Media metadata, private upload/read, originals and transformations, including contractor task evidence | [`media-service.yaml`](../../contracts/openapi/media-service.yaml)             |
| `dossier-service`     | Stateful Spring read model | Cross-domain cabin activity projection                                     | [`dossier-service.yaml`](../../contracts/openapi/dossier-service.yaml)         |
| `analytics-service`   | Stateful Spring read model | KPI and dashboard projections                                              | [`analytics-service.yaml`](../../contracts/openapi/analytics-service.yaml)     |
| `assistant-service`   | Stateful Spring            | Assistant conversations, messages, clarifications and tool-call history    | [`assistant-service.yaml`](../../contracts/openapi/assistant-service.yaml)     |

`assistant-service` does not own cabin availability or rental inquiries; those
remain logistics-owned. Read models do not issue commands for producer-owned
aggregates.

An external contractor route is not a second route aggregate or a contractor
account. Standalone planning selects and durably hands off exact real work;
logistics-service owns the expiring/revocable capability and its immutable task
bindings; task-board owns ordered entry transitions and evidence readiness;
media-service owns private bytes and variants. The stateless gateway exposes
only the exact anonymous capability routes, strips ambient credentials and
enforces the binary ingress limit before proxying. No browser or gateway owns
workflow state.

`dossier-service` scopes `PARTIAL` to the requested cabin and active projection
generation. Hidden activity/media and unresolved unlinked or DLT evidence can
degrade that read only when service-local proof binds the evidence to that
cabin and generation; global, raw and legacy-unproven rows remain operational
recovery evidence. Rebuild transfers unresolved DLT coverage before activating
the target generation. Row-level locks serialize coverage changes with relay
status changes on the same audit row, while transport delivery and projection
coverage remain separate state dimensions.

The media-service cabin-photo fact is one additional read-model input. A fact
whose `taskBoardEntryId` is non-null becomes a task-evidence activity with an
opaque media revision and task-entry reference; direct cover changes remain in
the dossier journal only. Dossier does not resolve a photo or authorize a media
read: the panel calls the existing public task-entry media boundary with the
caller bearer token.

Evidence:
[`DossierQueryService.java`](../../services/dossier-service/src/main/java/dev/buhanzaz/rwms/dossier/service/DossierQueryService.java),
[`DossierProjectionService.java`](../../services/dossier-service/src/main/java/dev/buhanzaz/rwms/dossier/service/DossierProjectionService.java),
[`DossierVisibilityCoverageResolver.java`](../../services/dossier-service/src/main/java/dev/buhanzaz/rwms/dossier/service/DossierVisibilityCoverageResolver.java),
[`DossierRelayTransactions.java`](../../services/dossier-service/src/main/java/dev/buhanzaz/rwms/dossier/eventing/DossierRelayTransactions.java),
[`V3__dossier_cabin_visibility_scope.sql`](../../services/dossier-service/src/main/resources/db/migration/V3__dossier_cabin_visibility_scope.sql).

## Client Boundaries

- `panel/`, `app/`, `client-app/`, `worker-app/` and `driver-app/` use the public gateway.
- The standalone logistics planner uses its same-origin FastAPI for planning.
  That browser boundary is not anonymous: it reuses the renewable `rwms-panel`
  `USER` session, sends Bearer authorization on every operational request and
  planning-event stream, and FastAPI independently verifies RS256/JWKS,
  issuer, audience, expiry, panel client, `rwms.read` and warehouse grants.
  Only health and generated API documentation are public. The authenticated
  subject, not a browser field, supplies the audit actor for mutable commands.
  Its transfer-draft and contractor-catalog dialogs are explicit
  interactive-gateway clients:
  it reuses the renewable `rwms-panel` `USER` session and calls the canonical
  public logistics transfer-create or task-board contractor-catalog operation.
  The panel OIDC callback performs
  a full-page return to `/logistics-panel/**`; no transfer aggregate or
  command is duplicated in FastAPI.
- Browser requests are same-origin `/auth/**` and `/api/**` only.
- Clients do not call `/api/internal/**` or direct service database/storage
  endpoints.
- Server responses are authoritative. Local state is limited to UI preferences,
  secure sessions and explicitly designed offline caches.
- Mutable operations use contract-defined optimistic concurrency and
  idempotency.

The panel's rental workspace exposes logistics-owned clients, their orders and
complete delivery facts. A client can start a manual order, warehouse booking
or assistant conversation without copying the client record into browser
state. The panel collects a new client through one shared individual/legal-
entity field surface; its visible responsible-manager value is session-derived
and read-only, while logistics remains the command owner. Assistant
clarification cards and result-group tabs are presentation of
assistant/logistics state: exact button answers are persisted by the assistant,
while selected cabin identifiers, expiry and hold effects remain authoritative
in logistics and asset services.

Evidence:
[`client routes`](../../panel/src/features/clients/clients-routes.tsx),
[`assistant page`](../../panel/src/features/assistant/pages/assistant-page.tsx),
and
[`order detail`](../../panel/src/features/orders/pages/order-detail-page.tsx).

### Android transport contract boundary

CustomerApp is an independent Android 11+ package and OAuth client. It uses
anonymous CSRF-protected registration through the delegated `/auth/**` route,
PKCE S256 login as `rwms-customer-android`, and only the public
`/api/logistics/customer/v1/**` rental boundary plus subject-bound public media operations. Its
encrypted session is local client state; profiles/avatar references, availability, holds, furniture balances, per-cabin
rental terms, delivery slots, bookings, arrivals, acceptances and problems
remain authoritative in auth-, asset-, logistics- and media-service. It
also retains one non-authoritative remember-warehouse/inquiry/create-key pointer: an
uncertain inquiry create reuses that key in-process and auto-resumes after recreation only when the
preference is enabled, then reloads the authoritative cart rather than storing a cart projection. It
has no cabin-dossier or internal-service route. Its focused delivery UI is a
four-destination Navigation 3 flow: a full-screen Yandex map, grouped
server dates, exact server slots, and held-slot rental confirmation. The map
uses the permitted vector `MapType.VECTOR_MAP` with the application palette and explicit light/dark
appearance, enables zoom and current-location controls, and keeps provider attribution above the
address panel. It exposes no satellite/hybrid, route, traffic, weather or layer toggle. Yandex MapKit Full is a client-only
rendering, suggestion, one-shot location and geocoding dependency: its API key comes from a
protected build file outside the repository, while an address edit, suggestion, map tap, speech
result or current-location result invalidates the previous address-to-point confirmation before
another server slot search. Android location permission is requested only by the explicit map
arrow; there is no background location. The
optional Russian voice path delegates capture to the installed Android speech
recognition activity, requests no microphone permission and retains no audio;
a missing recognizer produces an installation modal. A guaranteed Alice/SpeechKit path still
requires a separately approved secure backend credential boundary. Logistics still derives profile and cabin count
from its own session/cart and owns every capacity decision. Slot search also
requires explicit private-site trailer access and possible failed-trip charge attestations collected
in a modal before the current client sends the request; Valhalla's public-road route profile is
frozen with the offer. Existing profiles update only mutable contact/display fields. Avatar upload
uses Android Photo Picker and local pan/zoom cropping before any upload command. It exports a
1024×1024 JPEG without source EXIF/GPS, then uses a logistics-created
`LOGISTICS_CUSTOMER_PROFILE/PROFILE_AVATAR` owner scope and one media-service-validated `READY`
generation; no storage permission or media bytes enter
logistics.
My Orders admits reception only after the exact grouped shipment member is
completed. Full-screen vector signatures are sent to logistics; CameraX
photo/video and gallery imports remain app-private drafts until the exact
subject-bound shipment media upload reaches READY.

Manager, worker and driver Retrofit declarations are executable inventories
rather than implicit conventions. The manager inventory contains 62 methods:
60 fixed public-gateway routes plus two media-only dynamic URLs whose callers
enforce the same public media origin. The worker inventory contains 11 methods:
nine fixed public-gateway routes and the same two guarded media URL families.
The driver inventory contains 13 methods: eleven fixed public-gateway routes
and two guarded media URL families. Converter construction and representative
request/response fixtures cover every consumed JSON root family.

The worker and driver action DTOs have targeted serializers because their
canonical requests require nullable `workerGroupId` and `evidenceId`
properties to be present. They emit both keys explicitly while each app keeps
its established `explicitNulls = false` behavior. No client may call an
internal/private service path or a direct service origin.

Evidence:
[`CustomerApp API`](../../client-app/app/src/main/java/dev/buhanzaz/rwms/client/data/CustomerApi.kt),
[`CustomerApp auth`](../../client-app/app/src/main/java/dev/buhanzaz/rwms/client/auth/CustomerAuth.kt),
[`CustomerApp recovery pointer`](../../client-app/app/src/main/java/dev/buhanzaz/rwms/client/data/CustomerWorkflowStore.kt),
[`CustomerApp delivery destinations`](../../client-app/app/src/main/java/dev/buhanzaz/rwms/client/ui/DeliveryFlowScreens.kt),
[`CustomerApp reception`](../../client-app/app/src/main/java/dev/buhanzaz/rwms/client/ui/CustomerReceptionScreens.kt),
[`CustomerApp map adapter`](../../client-app/app/src/main/java/dev/buhanzaz/rwms/client/ui/YandexDeliveryMap.kt),
[`CustomerApp build boundary`](../../client-app/app/build.gradle.kts),
[`manager contract boundary`](../../app/src/test/java/dev/buhanzaz/rwms/manager/network/RwmsApiContractBoundaryTest.kt),
[`worker contract boundary`](../../worker-app/core-network/src/test/java/dev/buhanzaz/rwms/worker/core/network/WorkerGatewayApiContractBoundaryTest.kt),
[`driver contract boundary`](../../driver-app/core-network/src/test/java/dev/buhanzaz/rwms/driver/core/network/DriverGatewayApiContractBoundaryTest.kt),
[`worker action serializer`](../../worker-app/core-network/src/main/java/dev/buhanzaz/rwms/worker/core/network/WorkerActionRequestDtoSerializer.kt),
and
[`driver action serializer`](../../driver-app/core-network/src/main/java/dev/buhanzaz/rwms/driver/core/network/DriverActionRequestDtoSerializer.kt).

### Authenticated browser state boundary

The panel keeps public/bootstrap data on its root TanStack Query client. A
verified `/me` subject and the current bearer-grant revision own a separate
protected client. Logout, subject change or bearer-grant change cancels and
removes protected queries and mutations, clears media object URLs, and remounts
the protected subtree so its effect-owned realtime streams close. A late
response retains only a reference to the retired client and therefore cannot
populate the next subject's cache.

Evidence:
[`AuthProvider`](../../panel/src/features/auth/auth-provider.tsx),
[`ProtectedClientState`](../../panel/src/features/auth/protected-client-state.ts),
[`media preview cache`](../../panel/src/features/media/media-preview-cache.ts).

### Rental-manager Android boundary

`rental-manager-app/` is the native Android consumer for the dedicated
`rwms-rental-manager-android` OAuth client. It uses only the public gateway and
the exact `rental.manage` application scope. Before exposing client or order
data it validates the authoritative `USER`/`RENTAL_MANAGER` subject,
`rentalAccess=true` and live warehouses present in explicit active
`EDIT`/`MANAGE` grants. Server
`permissions.canEdit`, document versions and idempotency receipts remain
authoritative; the app retains only encrypted OAuth state and a hashed command
fingerprint scoped to the verified manager subject. Its adaptive Navigation 3
shell exposes Clients, Orders and the existing assistant-service Chat, but no
RWMS, logistics or administrative route. The chat retains a stable create
identity across an explicit retry, validates bounded typed SSE events, never
replays a non-idempotent turn POST, and rereads authoritative conversation
history after completion, conflict or interruption. The same chat projection
renders structured search shortages and the logistics-owned current selection;
an exact complete-set mutation can only release currently held cabins. Public
client-presentation publication delegates to logistics-service, keeps a stable
actor-scoped idempotency key through an uncertain outcome, rereads authoritative
state after conflict, and exposes explicit copy/share actions without claiming
that the link was sent. An editable order card enters that same workflow by
opening the single assistant conversation linked to its exact order and client;
the server reuses the active conversation on repeated entry. The order detail
also consumes the existing logistics `OrderDetail.units` projection directly,
showing selected cabins, requested equipment and rental terms without copying
their ownership or exposing transport identities. The draft-readiness card is
projection-only: contact, client-confirmed address and dates, warehouse, cabin
selection and every rental term must be present before Android enables the
existing version-fenced save command, and only its server response advances the
displayed state. Cancelling an editable draft
uses the existing version-fenced logistics command only after explicit Android
confirmation; the client retains the actor-scoped idempotency identity until
the server returns the cancelled projection, and never releases reservations
locally. Claims are absent until an owning aggregate and canonical lifecycle
contract exist.

Evidence:
[`rental-manager auth`](../../rental-manager-app/src/main/java/dev/buhanzaz/rwms/rentalmanager/auth/RentalManagerAuth.kt),
[`rental-manager repository`](../../rental-manager-app/src/main/java/dev/buhanzaz/rwms/rentalmanager/data/RentalManagerRepository.kt),
[`rental-manager chat repository`](../../rental-manager-app/src/main/java/dev/buhanzaz/rwms/rentalmanager/data/AssistantRepository.kt),
[`rental-manager presentation boundary`](../../rental-manager-app/src/main/java/dev/buhanzaz/rwms/rentalmanager/data/RentalPresentationRepository.kt),
[`rental-manager chat state`](../../rental-manager-app/src/main/java/dev/buhanzaz/rwms/rentalmanager/ui/RentalManagerChatViewModel.kt),
[`rental-manager navigation`](../../rental-manager-app/src/main/java/dev/buhanzaz/rwms/rentalmanager/ui/RentalManagerApp.kt),
and
[`rental-manager manifest`](../../rental-manager-app/src/main/AndroidManifest.xml).

### Warehouse ManagerApp durable client state boundary

The manager app resolves the authoritative `/me` account and its live warehouse
grants before exposing a durable upload queue or catalog snapshot. Every upload
row, draft, retained original, WorkManager input and unique work name carries
one immutable account-and-workspace-warehouse scope. A worker resolves `/me`
again and proves that owner and every retained warehouse grant before its first
media or domain effect. Logout, a replacement login and warehouse rebinding
cancel and join the prior scope before its authentication snapshot can be
replaced.

Maintenance catalog snapshots and refresh slots are partitioned by the same
verified account and warehouse and encrypted with AES-GCM using a non-exportable
Android Keystore key; the storage key is authenticated as associated data.
Ownerless version-1 uploads and plaintext catalog values are quarantined without
being assigned to the next account. These stores remain client recovery/read
optimizations; server state and authorization are authoritative.

The active inventory editor follows that same account-and-warehouse boundary.
Its route and structured draft metadata are encrypted with AES-GCM, while
selected photo/video source files are copied into an app-private scoped
directory before the UI adopts them. Relaunch restores the exact editor step
and retained local media; a different login or warehouse cannot see or resume
that draft. The recovered draft remains uncommitted client work until the
normal version-fenced inventory command succeeds.

Manager OAuth uses the public HTTPS `/auth/manager/callback`, which the native
login client consumes inside the app. The production, release and local-test
manifests do not expose AppAuth's custom-scheme redirect receiver, and the
Gradle configuration has no custom redirect-scheme placeholder.

Manager bearer retry carries the rejected access token into one serialized
refresh. If another request has already persisted a different valid token, it
is reused without a second refresh-token rotation. A transient refresh failure
remains a transport failure and preserves the encrypted session; only an absent
or terminally rejected refresh grant clears it. This client recovery rule does
not weaken gateway or owner authorization.

Evidence:
[`BackgroundUploadStore`](../../app/src/main/java/dev/buhanzaz/rwms/manager/uploads/BackgroundUploadStore.kt),
[`BackgroundUploadCoordinator`](../../app/src/main/java/dev/buhanzaz/rwms/manager/uploads/BackgroundUploadCoordinator.kt),
[`BackgroundUploadWorker`](../../app/src/main/java/dev/buhanzaz/rwms/manager/uploads/BackgroundUploadWorker.kt),
[`ManagerWorkspaceCoordinator`](../../app/src/main/java/dev/buhanzaz/rwms/manager/ui/coordinator/ManagerWorkspaceCoordinator.kt),
[`MaintenanceCatalogCache`](../../app/src/main/java/dev/buhanzaz/rwms/manager/ui/MaintenanceCatalogCache.kt),
[`InventoryDraftStore`](../../app/src/main/java/dev/buhanzaz/rwms/manager/ui/InventoryDraftStore.kt),
[`ManagerAuth`](../../app/src/main/java/dev/buhanzaz/rwms/manager/auth/ManagerAuth.kt),
[`manager bearer transport`](../../app/src/main/java/dev/buhanzaz/rwms/manager/network/Backend.kt),
and the [manager manifest](../../app/src/main/AndroidManifest.xml).

WorkerApp and DriverApp are separate packages, OAuth clients, encrypted stores
and Room/WorkManager recovery domains. WorkerApp uses the HTTPS
`/auth/worker/callback` and exact `worker.tasks` scope; DriverApp uses
`/auth/driver/callback` and exact `driver.tasks` scope. Both native login
clients validate and consume the callback in memory without exporting a
custom-scheme redirect receiver. Their task-board namespaces and device
registrations are surface-bound, so a token for one app cannot read or mutate
the other surface.

DriverApp exposes exactly three main destinations: warehouse work, personal
dated logistics and durable uploads. The dated surface selects only
`ASSIGNED_DRIVER` work; shared `WAREHOUSE_DRIVERS` movements remain on the
warehouse board. WorkerApp contains ordinary worker work plus active secondary
logistics collaboration, but no driver board, driver trip read or driver take
surface. For ordinary work, task-board publishes only enabled warehouse queues,
active real work and each queue's bounded waiting-real plan; shadows remain in
the manager projection. Feed, detail, TAKE and media-reader authorization share
that owner-side policy, so the Android client owns no plan state. Both retain
encrypted JPEG evidence and durable outbox state across a
process or device restart; server projections remain authoritative. WorkerApp
keeps selected task execution full-screen at every window width because its
general/work-bound media and result capture require the complete surface. Its
header projects only the task-board timer snapshot, and its existing
Manager-derived CameraX surface routes either volume key to one foreground
capture without moving evidence ownership into the client.

Before those existing task destinations become executable, DriverApp follows task-board's
server-owned `nextRequiredAction`. The additive daily flow covers the one-time briefing,
test-mode medical confirmation, template-snapshot vehicle inspection and defects, explicit shift
start, last-required-task closing transition, warehouse return, end vehicle condition, odometer,
fuel, evidence and final close. Warehouse-service remains the timezone/coordinates owner; task-board
derives the 06:00 work date and owns one shift per driver/date. Android persists only a resumable
projection, drafts and ordered outbox, and never presents a server-authorized transition as
completed before confirmation. Weather from task-board's cached MET Norway adapter and traffic
from the replaceable Yandex MapKit provider fail open without changing shift business state.
Driver-shift photos reuse the existing encrypted CameraX/media pipeline under the dedicated
`DRIVER_SHIFT/SHIFT_EVIDENCE` media proof.

DriverApp authentication remains anchored to the worker's immutable home warehouse. Task-board is
the operational execution-scope owner: it resolves an active temporary or completed permanent
assignment before creating a shift at the destination warehouse, uses that warehouse's IANA
timezone, and resumes the exact unfinished shift even after the assignment interval ends. This
keeps token issuance independent from resource movement while preventing a home claim from being
used as an arbitrary warehouse selector.

The same stateful task-board owner keeps local plan changes and same-queue card
reordering in narrow collaborators rather than in the panel or gateway:
[`WorkerQueuePlanService`](../../services/task-board-service/src/main/java/dev/buhanzaz/rwms/taskboard/service/WorkerQueuePlanService.java),
[`WorkerQueuePlanPolicy`](../../services/task-board-service/src/main/java/dev/buhanzaz/rwms/taskboard/service/WorkerQueuePlanPolicy.java) and
[`TaskBoardEntryOrderingService`](../../services/task-board-service/src/main/java/dev/buhanzaz/rwms/taskboard/service/TaskBoardEntryOrderingService.java).

Evidence:
[`WorkerAuthConfiguration.kt`](../../worker-app/core-auth/src/main/java/dev/buhanzaz/rwms/worker/core/auth/WorkerAuthConfiguration.kt),
[`DriverAuthConfiguration.kt`](../../driver-app/core-auth/src/main/java/dev/buhanzaz/rwms/driver/core/auth/DriverAuthConfiguration.kt),
[`WorkerGatewayApi.kt`](../../worker-app/core-network/src/main/java/dev/buhanzaz/rwms/worker/core/network/WorkerGatewayApi.kt),
[`WorkerApp navigation`](../../worker-app/app/src/main/java/dev/buhanzaz/rwms/worker/WorkerApp.kt),
[`Worker task detail`](../../worker-app/feature-task-detail/src/main/java/dev/buhanzaz/rwms/worker/feature/taskdetail/TaskDetailScreen.kt),
[`Worker CameraX`](../../worker-app/feature-camera/src/main/java/dev/buhanzaz/rwms/worker/feature/camera/CameraScreen.kt),
[`DriverGatewayApi.kt`](../../driver-app/core-network/src/main/java/dev/buhanzaz/rwms/driver/core/network/DriverGatewayApi.kt),
[`DriverApp.kt`](../../driver-app/app/src/main/java/dev/buhanzaz/rwms/driver/DriverApp.kt),
[`DriverShiftScreen.kt`](../../driver-app/feature-shift/src/main/java/dev/buhanzaz/rwms/driver/feature/shift/DriverShiftScreen.kt),
and
[`LogisticsScreen.kt`](../../driver-app/feature-tasks/src/main/java/dev/buhanzaz/rwms/driver/feature/tasks/LogisticsScreen.kt).

### Client Problem Details and retry boundary

Panel HTTP and assistant-stream failures use the same status-bearing `ApiError`.
Malformed or non-JSON upstream bodies retain the actual HTTP status but are not
shown verbatim. The worker uses one closed disposition set: an exhausted `401`
requires login, `403` requires user/grant action, `409` records the conflict and
refreshes authoritative state, and only `429`, `502`, `503`, `504` or a proven
transport failure may enter automatic retry. Cancellation always propagates.

Worker automatic retry is owned by WorkManager, not an in-process delay. One
unique sync request uses a persisted jittered exponential backoff and returns
`retry` for at most four total runs; pending media/evidence state completes the
current run and waits for a later explicit invalidation or refresh trigger.

Evidence:
[`api-client.ts`](../../panel/src/lib/api-client.ts),
[`assistant-api.ts`](../../panel/src/features/assistant/api/assistant-api.ts),
[`GatewayFailure.kt`](../../worker-app/core-network/src/main/java/dev/buhanzaz/rwms/worker/core/network/GatewayFailure.kt),
[`WorkerSyncCoordinator.kt`](../../worker-app/core-sync/src/main/java/dev/buhanzaz/rwms/worker/core/sync/WorkerSyncCoordinator.kt),
and
[`WorkerSyncWork.kt`](../../worker-app/core-sync/src/main/java/dev/buhanzaz/rwms/worker/core/sync/WorkerSyncWork.kt).

### Production endpoint and datasource safety

The API gateway applies the same origin-shape, public-host separation and
production loopback rejection to every configured downstream route. Auth-service
keeps local PostgreSQL values only in its explicit `dev` profile. Outside
`dev`/`test`, an environment post-processor requires a non-loopback PostgreSQL
JDBC URL and deployment-specific username/password before the application
context can initialize Flyway, JPA or a datasource; diagnostics never include
the supplied values.

Warehouse-service, asset-service, maintenance-service and logistics-service
also fail non-local startup unless Kafka publication is explicitly enabled with
the owner-approved destinations, non-empty brokers and safe producer
acknowledgement/idempotence settings. Their relay/binding beans are part of the
startup invariant, so a command cannot silently accumulate an outbox behind a
disabled publisher. Maintenance uses one ordered five-topic authority for
catalog, estimate, repair, property-disposition and sanitized-DLT output.
Logistics uses one ordered four-topic authority for return, shipment, transfer
and rental-inquiry facts; its canonical rental topic retains the explicit
`.events` segment. Service-local fixed-name gauges expose pending age/count,
terminal evidence where the owner has such a state, version gaps and bounded
external-attempt claims without making Kafka the source of truth.

Evidence:
[`GatewayProductionSafetyValidator`](../../services/api-gateway-service/src/main/java/dev/buhanzaz/rwms/gateway/config/GatewayProductionSafetyValidator.java),
[`AuthDatasourceEnvironmentPostProcessor`](../../services/auth-service/src/main/java/dev/buhanzaz/rwms/auth/config/AuthDatasourceEnvironmentPostProcessor.java),
[`auth base configuration`](../../services/auth-service/src/main/resources/application.yaml),
[`auth dev configuration`](../../services/auth-service/src/main/resources/application-dev.yaml),
[`WarehouseProductionSafetyValidator`](../../services/warehouse-service/src/main/java/dev/buhanzaz/rwms/warehouse/config/WarehouseProductionSafetyValidator.java),
[`WarehouseEventingMetrics`](../../services/warehouse-service/src/main/java/dev/buhanzaz/rwms/warehouse/eventing/WarehouseEventingMetrics.java),
[`AssetProductionSafetyValidator`](../../services/asset-service/src/main/java/dev/buhanzaz/rwms/asset/config/AssetProductionSafetyValidator.java),
[`LogisticsProductionSafetyValidator`](../../services/logistics-service/src/main/java/dev/buhanzaz/rwms/logistics/config/LogisticsProductionSafetyValidator.java),
[`LogisticsTransportTopics`](../../services/logistics-service/src/main/java/dev/buhanzaz/rwms/logistics/eventing/LogisticsTransportTopics.java),
[`LogisticsRecoveryMetrics`](../../services/logistics-service/src/main/java/dev/buhanzaz/rwms/logistics/eventing/LogisticsRecoveryMetrics.java),
[`MaintenanceProductionSafetyValidator`](../../services/maintenance-service/src/main/java/dev/buhanzaz/rwms/maintenance/config/MaintenanceProductionSafetyValidator.java),
[`MaintenanceTransportTopics`](../../services/maintenance-service/src/main/java/dev/buhanzaz/rwms/maintenance/eventing/transport/MaintenanceTransportTopics.java),
[`MaintenanceEventingMetrics`](../../services/maintenance-service/src/main/java/dev/buhanzaz/rwms/maintenance/eventing/transport/MaintenanceEventingMetrics.java).

## Data And Dependency Boundaries

- Every stateful service owns one PostgreSQL database and Flyway history.
- Cross-database foreign keys, joins, shared tables and direct repository access
  are forbidden.
- Shared libraries contain technical types only, never mutable business models
  or JPA entities.
- Services exchange versioned APIs, versioned facts, opaque identifiers and
  immutable snapshots.
- Kafka provides at-least-once transport. Producers use transactional outbox;
  consumers use inbox deduplication and version checks.
- MinIO is private and media ownership stays in `media-service`.
- Multi-service workflows are owned and persisted by the initiating service;
  there is no 2PC and no browser saga.

Completed-inventory recovery is an inventory-owned saga over narrow commands,
not a shared aggregate. The immutable finding first updates asset status and
guards, then selects the current media folder, applies one plan-wide logistics
supersession, and finally applies maintenance work or no-work cleanup. Asset,
media, logistics and maintenance each own permanent replay receipts and a
latest-completed-inventory watermark in their own database; superseded rows and
MinIO objects remain historical. The inventory OAuth client uses exact
`asset.inventory`, `media.inventory`, `logistics.inventory` and
`maintenance.inventory` owner scopes, and no interactive client can call these
private routes.

Inventory also persists one plan-wide reapplication generation. Automatic
retries, including recovery after a lost response, reuse the same downstream
keys; an explicit completed-history recalculation advances the generation once
for every finding in that plan. A same-source reassertion preserves the active
asset lease that may belong to the exact maintenance successor, while the
maintenance or logistics owner releases only its unrelated predecessor lease
under the recorded owner and fencing token.

Evidence:
[`InventoryPublicationService`](../../services/inventory-service/src/main/java/dev/buhanzaz/rwms/inventory/service/InventoryPublicationService.java),
[`asset outcome`](../../services/asset-service/src/main/java/dev/buhanzaz/rwms/asset/service/InventoryAssetOutcomeService.java),
[`media outcome`](../../services/media-service/internal/persistence/inventory_cabin_photos.go),
[`logistics outcome`](../../services/logistics-service/src/main/java/dev/buhanzaz/rwms/logistics/inventory/service/InventoryOutcomeService.java),
and
[`maintenance outcome`](../../services/maintenance-service/src/main/java/dev/buhanzaz/rwms/maintenance/service/InventoryAuthoritativeOutcomeService.java).

Inventory keeps low-level PostgreSQL mechanics behind two exact technical
owners. `InventoryAssetInboxStore` is the only asset-consumer owner of
`inbox_message` SQL, while `InventoryPostgresJsonbCanonicalizer` owns only the
PostgreSQL JSONB canonical-text operation used for stable fingerprints. Domain
processors, retry policy and frozen-plan hashing depend on those capabilities
and no longer carry `JdbcTemplate`, JDBC types or arbitrary table access. The
source policy names these two files exactly; it does not permit a package-wide
persistence escape.

Evidence:
[`InventoryAssetInboxStore`](../../services/inventory-service/src/main/java/dev/buhanzaz/rwms/inventory/eventing/InventoryAssetInboxStore.java),
[`InventoryPostgresJsonbCanonicalizer`](../../services/inventory-service/src/main/java/dev/buhanzaz/rwms/inventory/persistence/InventoryPostgresJsonbCanonicalizer.java),
and
[`InventorySourcePolicy`](../../platform/architecture-tests/src/test/java/dev/buhanzaz/rwms/architecture/InventorySourcePolicy.java).

Logistics keeps provider-specific SQL behind four exact persistence paths:
the rental-inquiry booked outbox store and the warehouse admission,
single-statement blocker and operation-mark adapters. They preserve database
time, conflict-safe insert, atomic blocker snapshots, `FOR UPDATE SKIP LOCKED`
and conditional fencing writes; their application stores retain workflow and
replay decisions. `LogisticsTransactionLock` is the only application-facing
capability over the one approved native transaction advisory-lock query. The
source policy names the exact paths and rejects arbitrary JDBC or native JPA
SQL even inside the same persistence package.

Evidence:
[`RentalInquiryBookedOutboxStore`](../../services/logistics-service/src/main/java/dev/buhanzaz/rwms/logistics/inquiry/eventing/RentalInquiryBookedOutboxStore.java),
[`LogisticsWarehouseAdmissionPersistence`](../../services/logistics-service/src/main/java/dev/buhanzaz/rwms/logistics/service/persistence/LogisticsWarehouseAdmissionPersistence.java),
[`LogisticsWarehouseLifecycleBlockerReader`](../../services/logistics-service/src/main/java/dev/buhanzaz/rwms/logistics/service/persistence/LogisticsWarehouseLifecycleBlockerReader.java),
[`LogisticsWarehouseOperationMarkPersistence`](../../services/logistics-service/src/main/java/dev/buhanzaz/rwms/logistics/service/persistence/LogisticsWarehouseOperationMarkPersistence.java),
[`LogisticsTransactionLock`](../../services/logistics-service/src/main/java/dev/buhanzaz/rwms/logistics/repository/LogisticsTransactionLock.java),
and
[`LogisticsSourcePolicy`](../../platform/architecture-tests/src/test/java/dev/buhanzaz/rwms/architecture/LogisticsSourcePolicy.java).

Rental-inquiry cabin search is a logistics-owned recoverable remote effect.
V41 stores a subject-scoped public key, canonical request digest, exact asset
request text, domain-separated downstream UUID, expiry and frozen completed
response. PREPARE and COMPLETE/REJECT are short local transactions; warehouse
and asset calls run between them without holding a logistics transaction. An
unknown remote outcome stays `PREPARED`, while an exact completed replay makes
no remote call. The booking transaction also persists the strict V2 booking
envelope in its service-owned outbox; Kafka remains transport rather than saga
state.

Evidence:
[`RentalInquirySearchAttempt`](../../services/logistics-service/src/main/java/dev/buhanzaz/rwms/logistics/inquiry/domain/RentalInquirySearchAttempt.java),
[`RentalInquiryCabinSearchStore`](../../services/logistics-service/src/main/java/dev/buhanzaz/rwms/logistics/inquiry/service/RentalInquiryCabinSearchStore.java),
[`RentalInquiryCabinSearchService`](../../services/logistics-service/src/main/java/dev/buhanzaz/rwms/logistics/inquiry/service/RentalInquiryCabinSearchService.java),
and
[`V41`](../../services/logistics-service/src/main/resources/db/migration/V41__rental_inquiry_search_receipts_and_booked_envelope_v2.sql).

V42 adds a separate durable receipt for replacing or releasing the current
chat selection. It freezes the public request hash, exact downstream bytes and
a non-null command expiry before the asset call. The hold expiry is present
only for a non-empty selection; an empty selection releases immediately. A
lost response remains retryable with the same key and bytes until the persisted
command expiry, after which the one-PREPARED-per-inquiry slot is released.
Logistics stores only response evidence, while asset-service remains the owner
of live holds. The same migration expands logistics-owned rental counterparties
and order delivery facts without coupling to the auth database.

Evidence:
[`RentalInquiryCabinSelectionService`](../../services/logistics-service/src/main/java/dev/buhanzaz/rwms/logistics/inquiry/service/RentalInquiryCabinSelectionService.java),
[`RentalInquiryCabinSelectionStore`](../../services/logistics-service/src/main/java/dev/buhanzaz/rwms/logistics/inquiry/service/RentalInquiryCabinSelectionStore.java),
[`OrderClientService`](../../services/logistics-service/src/main/java/dev/buhanzaz/rwms/logistics/order/service/OrderClientService.java),
and
[`V42`](../../services/logistics-service/src/main/resources/db/migration/V42__clients_order_delivery_and_acceptable_dates.sql).

Read-model recovery metrics remain read-only projections of each projection's
own operational state. Analytics observes gap and sanitized-DLT aggregates.
Dossier observes blocked checkpoints, retained unresolved evidence,
activity-outbox work and sanitized-DLT work. Neither component changes a
checkpoint, visibility result, generation, relay status or producer command;
both expose fixed metric names without identity/payload/error labels and return
`NaN` when the database observation itself is unavailable.

Evidence:
[`AnalyticsRecoveryMetrics`](../../services/analytics-service/src/main/java/dev/buhanzaz/rwms/analytics/eventing/AnalyticsRecoveryMetrics.java)
and
[`DossierRecoveryMetrics`](../../services/dossier-service/src/main/java/dev/buhanzaz/rwms/dossier/eventing/DossierRecoveryMetrics.java).

Assistant recovery metrics are likewise observational. They count durable
tool calls still in `STARTED` and expose the oldest displayed age through two
fixed-name, read-only gauges. `COMPLETED` and `FAILED` are terminal history,
not retry backlog; a scrape neither changes a tool call nor resolves the open
cross-service recovery decision for assistant-initiated logistics work.

Evidence:
[`AssistantRecoveryMetrics`](../../services/assistant-service/src/main/java/dev/buhanzaz/rwms/assistant/eventing/AssistantRecoveryMetrics.java)
and
[`assistant recovery decision`](open-questions.md).

Assistant booking-fact recovery is owner-local as well. A strict canonical
inbox records event identity, hash and broker coordinates before applying the
conversation archive effect. Bounded failures create sanitized DLT/review
evidence without copying raw records or credentials. Reviewed replay can apply
only the already staged canonical envelope; it neither creates logistics state
nor provides a public operator command.

Evidence:
[`AssistantEventInbox`](../../services/assistant-service/src/main/java/dev/buhanzaz/rwms/assistant/domain/AssistantEventInbox.java),
[`AssistantEventDeadLetter`](../../services/assistant-service/src/main/java/dev/buhanzaz/rwms/assistant/domain/AssistantEventDeadLetter.java),
and
[`AssistantDltRecoveryService`](../../services/assistant-service/src/main/java/dev/buhanzaz/rwms/assistant/eventing/AssistantDltRecoveryService.java).

Media state and processing remain entirely inside the existing
`media-service`. Its PostgreSQL job/inbox/outbox state is authoritative before
Kafka acknowledgement.
The worker claims a four-attempt fenced cycle, records append-only terminal and
review evidence, and treats a successful database replay as an idempotent
duplicate. Persistence and offset-commit retries are bounded; shutdown or
exhaustion releases the Kafka rebalance instead of retaining a partition
forever. The same typed recovery snapshot fans out to structured logs and a
separate loopback-only OpenMetrics listener; it never becomes a route on the
public media API. The exporter caps partition series and exposes no topic,
identity, payload or free-form error labels. No second media deployable or
cross-service recovery table is added.

ManagerApp and WorkerApp physically orient a still image and encode exactly
`SMALL`, `MEDIUM` and `LARGE` WebP parts with an aggregate one-MiB ceiling. The
phone-only original is never uploaded. Each authenticated same-origin PUT is
streamed through the gateway and `media-service` into private MinIO; the Go
service verifies immutable metadata and promotes the supplied parts without
decoding or transforming image bytes. A compatible legacy source upload is
represented by aliases to that same immutable object rather than a second
image-processing path. The deployable still derives a bounded MP4 `PLAYBACK`
object for accepted video originals. Both ffmpeg and ffprobe dependencies are
resolved at startup, transformation time and output bytes are explicitly
capped, and the video original remains immutable in private object storage.
Client-side parallel transfer limits do not change media ownership or add
another scheduler.

Evidence:
[`consumer.go`](../../services/media-service/internal/worker/consumer.go),
[`worker.go`](../../services/media-service/internal/persistence/worker.go),
[`media upload API`](../../services/media-service/internal/api/server.go),
[`V16__client_image_variants.sql`](../../services/media-service/db/migration/V16__client_image_variants.sql),
[`Manager image bundle encoder`](../../app/src/main/java/dev/buhanzaz/rwms/manager/media/ImageUploadBundleEncoder.kt),
[`Worker image bundle preparer`](../../worker-app/core-media/src/main/java/dev/buhanzaz/rwms/worker/core/media/WorkerEvidenceBundlePreparer.kt),
[`video_transcoder.go`](../../services/media-service/internal/media/video_transcoder.go),
[`video_probe.go`](../../services/media-service/internal/media/video_probe.go),
[`media-service startup`](../../services/media-service/cmd/media-service/main.go),
[`V12__video_playback_variant.sql`](../../services/media-service/db/migration/V12__video_playback_variant.sql),
[`processing_metrics.go`](../../services/media-service/internal/observability/processing_metrics.go),
[`metrics_runtime.go`](../../services/media-service/cmd/media-service/metrics_runtime.go),
[`V11__bounded_media_processing_recovery.sql`](../../services/media-service/db/migration/V11__bounded_media_processing_recovery.sql).

## Internal Application Orchestration

Controller- and UI-facing application surfaces remain stable facades, but they
do not own unrelated workflow families. Each facade delegates to cohesive,
constructor-injected owners through an acyclic graph. Existing transaction
annotations stay on the public boundary when that is where callers previously
entered; extracted collaborators do not introduce a second transaction policy.

| Boundary | Current internal owners |
| --- | --- |
| Manager `ManagerViewModel` | Workspace/auth, inventory, shipment, return, transfer, maintenance catalog/read/editor/persistence and media coordinators connected through narrow ports |
| Asset application | Rental items, logistics effects, equipment, maintenance and classifiers; separate inventory capture/projection/furniture/source, HTML-import and property-disposition facades |
| Task board | Read projection, external registration/mutation, logistics tasks, driver-audience validation/visibility, non-overlapping native mobile surfaces, transactional slinger push, worker execution and ordering; workforce profile, credential and group owners |
| Inventory | Session, read, finding/validation/review, planning, completion, statistics, projection, history-outcome recovery and publication-saga owners |
| Maintenance | Catalog, estimate, repair, transfer, inbound and reconciliation owners; separate inventory publication, authoritative supersession and property-disposition workflows |
| Logistics | Return, shipment, transfer, reconciliation and rental-order document hooks; one grouped document driver-intent planner; rental-order read/create/lifecycle/reservation/terms/shipment/replacement owners; separate completed-inventory supersession workflow |
| Worker task UI | Ordinary group/qualification work plus active optional logistics-secondary work; the visible `Взять задание` action sends JOIN with the current group and reuses task-detail/camera/durable-upload navigation; there is no driver board or driver-trip surface |
| Driver task UI | Driver-only primary warehouse work plus durable uploads; TAKE/PAUSE/RESUME/COMPLETE and CameraX evidence, with no JOIN or slinger notification handling |
| Assistant | Conversation creation store, one durable ordered clarification queue, cabin search/reference tools and selection delegation; only the visible `PENDING` head is actionable and logistics/asset remain the command owners |
| Private HTTP adapters | Logistics and maintenance gateway facades delegate by remote owner to warehouse, asset, task-board, logistics, maintenance or media clients over one technical OAuth/HTTP transport each |

The assistant queue invariant is owned by
[`AssistantClarificationService.java`](../../services/assistant-service/src/main/java/dev/buhanzaz/rwms/assistant/service/AssistantClarificationService.java)
and its lossless schema transition is
[`V6__order_linked_sequential_conversations.sql`](../../services/assistant-service/src/main/resources/db/migration/V6__order_linked_sequential_conversations.sql).

Compatibility facades contain delegation and small public-result adapters, not
repository bags or hidden workflow bases. Extracted types do not refer back to
their facade, and the source policy caps named facades, constructors, support/
use-case/coordinator types and manager coordinators. Large declarative boundary
containers and cohesive persisted state-machine stores are reviewed by
responsibility rather than line count alone.

This structure changes neither deployable/domain ownership nor transport
meaning. The detailed before/after evidence, focused checks and explicitly
frozen runtime defects are recorded in the
[`god-class decomposition review`](../reviews/20260808-god-class-decomposition.md).

Evidence:
[`GodClassSourcePolicy.java`](../../platform/architecture-tests/src/test/java/dev/buhanzaz/rwms/architecture/GodClassSourcePolicy.java),
[`AssetService.java`](../../services/asset-service/src/main/java/dev/buhanzaz/rwms/asset/service/AssetService.java),
[`TaskBoardService.java`](../../services/task-board-service/src/main/java/dev/buhanzaz/rwms/taskboard/service/TaskBoardService.java),
[`InventoryApplicationService.java`](../../services/inventory-service/src/main/java/dev/buhanzaz/rwms/inventory/service/InventoryApplicationService.java),
[`MaintenanceApplicationService.java`](../../services/maintenance-service/src/main/java/dev/buhanzaz/rwms/maintenance/service/MaintenanceApplicationService.java),
[`LogisticsDocumentService.java`](../../services/logistics-service/src/main/java/dev/buhanzaz/rwms/logistics/service/LogisticsDocumentService.java),
[`RentalOrderService.java`](../../services/logistics-service/src/main/java/dev/buhanzaz/rwms/logistics/order/service/RentalOrderService.java),
[`ManagerViewModel.kt`](../../app/src/main/java/dev/buhanzaz/rwms/manager/ui/ManagerViewModel.kt).

## Warehouse Lifecycle Coordination

`warehouse-service` is the only owner of warehouse lifecycle
`ACTIVE -> DRAINING -> INACTIVE`, directional operation admission and
effective-dated timezone history. Deactivation is a version-fenced distributed
readiness protocol, not a direct Boolean update. Each stateful operation owner
keeps its own durable readiness/admission intent, uses a service-local
PostgreSQL advisory lock and commit guard, calls warehouse-service outside its
database transaction, and confirms the exact warehouse lifecycle version only
after local blockers have drained. Pending, indeterminate and quarantined work
remains a blocker; a timeout is never interpreted as readiness.

An accepted operation records a durable service-local operated-boundary mark
in the same transaction as its first warehouse-bound fact. Logistics records
the admitted direction and exact warehouse lifecycle version on each new
permanent mark. A dependency-free replay candidate requires a live durable
domain identity, a live owner idempotency receipt and the complete exact mark
vector; the owning command still verifies its payload checksum before returning
the stored result. Historical or test/parent-owned marks with null evidence are
never upgraded by inference and must use remote re-admission or fail with an
explicit dependency error. Delivery to warehouse-service is idempotent and
recoverable, so a later timezone change cannot mistake an already operating
warehouse for an unused one. Physical inter-warehouse movement remains
logistics-owned. `asset-service` exposes only a separate administrator
correction for a record that was wrong without a physical movement; that
correction is not a replacement for a retrospective logistics document.

Evidence:
[`warehouse-service.yaml`](../../contracts/openapi/warehouse-service.yaml),
[`WarehouseLifecycleController.java`](../../services/warehouse-service/src/main/java/dev/buhanzaz/rwms/warehouse/api/WarehouseLifecycleController.java),
[`V5__warehouse_lifecycle.sql`](../../services/warehouse-service/src/main/resources/db/migration/V5__warehouse_lifecycle.sql),
[`AssetWarehouseLifecycleStore.java`](../../services/asset-service/src/main/java/dev/buhanzaz/rwms/asset/service/AssetWarehouseLifecycleStore.java),
[`LogisticsWarehouseLifecycle.java`](../../services/logistics-service/src/main/java/dev/buhanzaz/rwms/logistics/service/LogisticsWarehouseLifecycle.java),
[`LogisticsWarehouseLifecycleStore.java`](../../services/logistics-service/src/main/java/dev/buhanzaz/rwms/logistics/service/LogisticsWarehouseLifecycleStore.java),
[`V39__warehouse_admission_evidence.sql`](../../services/logistics-service/src/main/resources/db/migration/V39__warehouse_admission_evidence.sql),
[`WarehouseLifecycleOperations.java`](../../services/maintenance-service/src/main/java/dev/buhanzaz/rwms/maintenance/service/WarehouseLifecycleOperations.java),
[`TaskBoardWarehouseLifecycleFence.java`](../../services/task-board-service/src/main/java/dev/buhanzaz/rwms/taskboard/service/TaskBoardWarehouseLifecycleFence.java).

## Bounded Logistics External Effects

Logistics external-attempt relays never scan and execute an unbounded owner
backlog in one scheduler thread. Each of the five owners claims only the page
permitted by its configured budget in a short database-only transaction. The
claim stores a unique token, monotonic fence, expiry, post-flush row version and
request hash; its transaction closes before any remote HTTP call. Completion,
failure and deferral re-lock and verify that complete capability before an
owner mutation.

A dedicated lightweight trigger submits claims to a bounded worker executor
with per-owner permits, while the normal Boot task scheduler remains available
to unrelated scheduled work. Database time governs due, lease and defer
decisions. An expired lease can be reclaimed with a higher fence; a stale or
duplicate completion cannot change either the attempt or its owner workflow.
`V40` supplies stable due/expiry indexes and lease constraints without changing
the external transport contract.

Evidence:
[`LogisticsExternalAttemptClaimService`](../../services/logistics-service/src/main/java/dev/buhanzaz/rwms/logistics/service/LogisticsExternalAttemptClaimService.java),
[`LogisticsExternalAttemptRelayExecutor`](../../services/logistics-service/src/main/java/dev/buhanzaz/rwms/logistics/service/LogisticsExternalAttemptRelayExecutor.java),
[`LogisticsExternalAttemptSchedulerConfiguration`](../../services/logistics-service/src/main/java/dev/buhanzaz/rwms/logistics/service/LogisticsExternalAttemptSchedulerConfiguration.java),
and
[`V40__bounded_logistics_external_attempt_claims.sql`](../../services/logistics-service/src/main/resources/db/migration/V40__bounded_logistics_external_attempt_claims.sql).

## Maintenance Remote Effects

`maintenance-service` never waits for asset, task-board, logistics or warehouse
HTTP while holding its local transaction. Commands use short local
prepare/finalize transactions around an immutable remote plan; background work
uses durable claims with exact lease fencing. A retry replays the same derived
idempotency key and original expected version even when a diagnostic read sees
the post-effect state. For transfer arrival, logistics supplies the exact asset
version persisted by its released guard, and that version is part of the
durable attempt fingerprint.

Evidence:
[`MaintenanceApplicationService.java`](../../services/maintenance-service/src/main/java/dev/buhanzaz/rwms/maintenance/service/MaintenanceApplicationService.java),
[`MaintenanceReconciliationStore.java`](../../services/maintenance-service/src/main/java/dev/buhanzaz/rwms/maintenance/service/MaintenanceReconciliationStore.java),
[`TransferWorkflowStore.java`](../../services/logistics-service/src/main/java/dev/buhanzaz/rwms/logistics/service/TransferWorkflowStore.java),
[`maintenance-service.yaml`](../../contracts/openapi/maintenance-service.yaml).

## Enforced Java Service Boundaries

The central Java architecture graph includes all 11 active Spring services,
including assistant, analytics and api-gateway. It enforces constructor
injection and MapStruct placement across that graph, prevents command/read-model
ownership from crossing inventory, logistics, dossier, assistant and analytics
packages, and treats technical-contracts as framework-neutral.

The gateway is structurally stateless: production gateway code cannot depend on
service implementation packages, JPA/Spring Data, repositories, JDBC/DataSource
or Kafka. Analytics and dossier controllers are read-only; method-level
`POST/PUT/PATCH/DELETE`, a write-capable `RequestMapping`, and a bare
method-level `RequestMapping` are rejected. A path-only class-level mapping is
allowed because it does not itself expose a write method.

Canonical OpenAPI, AsyncAPI catalogs and JSON Schemas have a separate root
integrity gate. It resolves only repository-local references, compiles declared
schema drafts, enforces path-bound schema IDs, and rejects duplicate or blank
OpenAPI operation IDs and event message names within their owning documents.
The gate validates transport
structure and reference integrity; it does not replace owner-specific
producer/consumer compatibility tests.

Owner-specific route gates close that second half. Inventory and logistics
compare their canonical method/path sets with merged Spring controller mappings
and exercise the production security chains. The gateway resolves all 295
canonical domain-public operations through its 17 domain routers, keeps exactly
six logistics presentation operations anonymous (four client-presentation plus
two cabin-photo read routes), and rejects canonical
internal operations plus reserved private/internal aliases. The current focused
route/security gate also proves that WorkerApp and DriverApp SSE routes win over
the generic task-board route and that their scopes are not interchangeable.

Media-service has an independent Go structural gate. It requires one module and
one `cmd/media-service` executable, builds an acyclic role-directed module-local
import graph, rejects foreign RWMS implementation imports and direct datastore
access from API/worker/observability, and restricts operational telemetry to
fixed non-sensitive fields and keys. New production package families fail until
their role is reviewed explicitly.

Evidence:
[`ArchitectureRules.java`](../../platform/architecture-tests/src/test/java/dev/buhanzaz/rwms/architecture/ArchitectureRules.java),
[`PlatformArchitectureTest.java`](../../platform/architecture-tests/src/test/java/dev/buhanzaz/rwms/architecture/PlatformArchitectureTest.java),
[`ServiceBoundaryArchitectureTest.java`](../../platform/architecture-tests/src/test/java/dev/buhanzaz/rwms/architecture/ServiceBoundaryArchitectureTest.java),
[`CanonicalContractIntegrityGate.java`](../../platform/architecture-tests/src/test/java/dev/buhanzaz/rwms/contracts/CanonicalContractIntegrityGate.java),
[`GatewayRouteSecurityParityTest.java`](../../services/api-gateway-service/src/test/java/dev/buhanzaz/rwms/gateway/config/GatewayRouteSecurityParityTest.java),
and
[`media architecture gate`](../../services/media-service/internal/architecture/architecture_test.go).

## Architecture Change Rule

Before moving responsibility between components, changing dependency direction
or adding a deployable, update the relevant canonical contracts and obtain an
explicit product decision. Then update this document and the change log after
focused architecture tests pass.

## Current Conformance Review

The intended ownership and dependency direction above remain the current
architecture. A repository-wide review on 2026-08-08 found implementation and
operational deviations that must not be mistaken for approved architecture,
including account-unscoped client work/cache, dependency-disable paths that
accept domain work without required lifecycle/event delivery, projection gaps
without authoritative reconciliation, and a remote assistant effect without a
durable saga boundary.

The expanded central rules are green. Inventory's three low-level SQL findings
were moved into two exact technical adapters; its focused service checks are
52/52 and the central inventory source policy is 7/7. Logistics' ten findings
were moved into four exact persistence paths plus one narrow advisory-lock
capability; its source policy is 9/9 and full module is 330/330. The
deterministic root contract gate is 7/7 over the checked-in corpus and negative
fixtures. The resulting full central architecture run is 82/82, and the
approved-dependency-version gate is green without a broad SQL allow-list.
Owner route/security parity is 9/9; manager and worker contract boundaries are
11/11 and 12/12; the media architecture package, full Go tests, vet and build
are green. The exact worker debug APK was assembled from the reviewed source,
but it was not installed or published from the mixed protected worktree.

Evidence, severity, implementation slices and acceptance gates are maintained
in the
[`2026-08-08 full architecture audit`](../reviews/20260808-full-architecture-audit.md).
Decisions that cannot be inferred safely are isolated in
[`open-questions.md`](open-questions.md).
