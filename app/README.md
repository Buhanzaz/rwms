# RWMS Manager for Android

`app/` is the Android application for RWMS managers and administrators. It is
a public-gateway client, not an offline owner of warehouse, inventory,
maintenance, or logistics state.

Russian version: [README.ru.md](README.ru.md).

## Public boundary, roles, and sign-in

- The app is configured with one absolute HTTPS public gateway origin through
  `RWMS_PUBLIC_BASE_URL`. It calls only `/auth/**` and `/api/**`; internal
  service origins, `localhost` ports, and `/api/internal/**` are forbidden.
- Eligible interactive users are `SYSTEM_ADMIN`, `WMS_ADMIN`, and
  `WAREHOUSE_MANAGER` when the server grants mobile access. The server remains
  the authorization authority for every warehouse and command.
- OAuth client `rwms-manager-android` uses Authorization Code with PKCE S256
  and refresh-token rotation. Session material is encrypted with a
  non-exportable Android Keystore key; a password is not retained by the app.
- A gateway `401` gets one process-wide serialized refresh attempt shared by
  the foreground UI and WorkManager. After acquiring that lock each repository
  rereads the encrypted state, so a concurrent request reuses the access and
  refresh tokens already rotated by its peer. A transient refresh transport
  failure keeps the encrypted session and is shown as a connectivity failure.
  Only a missing/revoked refresh token or terminal `invalid_grant`/
  `access_denied` response makes the session unusable.
- The redirect is the public-gateway HTTPS endpoint `/auth/manager/callback`
  and is consumed inside `NativeManagerLoginClient`. Neither the production nor
  Robolectric test manifest exposes AppAuth's custom-scheme
  `RedirectUriReceiverActivity`; no custom redirect-scheme placeholder is
  configured. See
  [`ManagerAuth.kt`](src/main/java/dev/buhanzaz/rwms/manager/auth/ManagerAuth.kt),
  the [main manifest](src/main/AndroidManifest.xml), and the
  [test manifest](src/test/AndroidManifest.xml).

### Transport contract gate

[`RwmsApiContractBoundaryTest.kt`](src/test/java/dev/buhanzaz/rwms/manager/network/RwmsApiContractBoundaryTest.kt)
pins all 64 declared `RwmsApi` methods to their canonical public OpenAPI source:
62 fixed gateway routes and exactly two allowlisted dynamic media routes. It
rejects internal/private namespaces and service origins, eagerly resolves every
Retrofit/Moshi request and response converter, and checks representative
decode/encode fixtures for all eight consumed contract owners: auth, warehouse,
inventory, asset, logistics, maintenance, task board, and media. Binary media
bodies are converter-checked and intentionally are not JSON fixtures. The
dynamic upload and variant-read tests also prove that their callers reject a
foreign origin before Retrofit is invoked.

Logistics document projections expose only the scheduled calendar date
(`scheduledDate`); the manager client does not consume a legacy scheduling-time
field.
When the transfer editor opens, that date is initialized from the selected
source warehouse's canonical IANA timezone at the injected server clock
instant. Submission validates against the same warehouse-local date, never
`LocalDate.now()` from the Android device; an unavailable warehouse identity or
invalid timezone fails explicitly.

Before opening per-cabin transfer arrival, Manager reads the version-fenced
arrival preflight. Missing destination repair queues or a failed preflight
block arrival. A continuing repair requires an explicit priority from 1 to 5;
without a repair the command sends `priority: null`. The selected priority,
original document/line versions, media and command key survive background-upload
recovery together. Closing arrival or changing the selected transfer invalidates
late preflight/media responses; a changed version requires opening arrival again.

Remote media uses one in-memory file index and an LRU budget of 256 files / 256 MiB.
Downloads, open editor snapshots and composed image/video readers retain their files;
the budget may be exceeded while those files are in use. Replacing or closing an
editor, disposing a reader, and failed or canceled batch downloads release ownership.
Inventory photos are copied to durable drafts before temporary claims are released;
cache eviction never deletes those draft or capture directories.

## Screens and ownership of local state

The navigation graph covers the manager home/menu, background uploads,
logistics returns/shipments/transfers, inventory, maintenance estimates and
repairs, acceptance, and camera/media steps. A screen never owns a durable
business transition: it sends the public command and then observes or refreshes
the server result.

The active inventory screen filters its existing cabin cards by canonical
number prefix as the manager types; it does not render a separate autocomplete
list. The add action appears only when no loaded warehouse cabin number starts
with the input. An inspection proceeds through passport and photos. On Photos,
“No furniture” goes directly to the work catalog, while “Add furniture” opens
the furniture composition actions; there is no intermediate furniture-presence
decision step. Characteristics, linoleum, sanitary counters, and inspection
comment belong to that late details step rather than the passport. A saved
inspection follows its stored choice through the same steps in read-only mode.
Every step offers “Edit inspection”: supplementing enables the
retained data on the current step, while replacing returns to the passport with
a clean inspection revision. The durable queue submits either repeat mode only
while the finding still has the exact freshly opened revision; it may refresh
the broader session fence after unrelated cabin changes, but never rebases over
a newer inspection of the same cabin. A non-empty inspection, estimate, or primary
repair plan can also retain an explicit “send to capital repair” choice; the
inventory editor places it after “create movement to repair”, and choosing one
destination clears the other. Custom work/material stages retain their selected
repair-board route; a frozen-plan read uses the line routing snapshot to assign
every line to one stage exactly once, even when catalog work IDs repeat. The
maintenance service remains responsible for the resulting calculated CAPITAL
classification and its existing logistics/acceptance cycle. See
[`InventoryScreens.kt`](src/main/java/dev/buhanzaz/rwms/manager/ui/screens/InventoryScreens.kt)
and
[`ManagerNavGraph.kt`](src/main/java/dev/buhanzaz/rwms/manager/navigation/ManagerNavGraph.kt).

After a queued estimate completion leaves the durable outbox, Manager first verifies that
the exact maintenance estimate is completed and then reads the inventory return-import
receipt. Only a confirmed receipt queues `Бытовка №… добавлена в инвентаризацию`, with
`Окей` as the sole action. Pending evidence is retried for a bounded interval; an
unavailable or still-pending confirmation is reported separately without repeating the
successful completion command. A changed account or warehouse cancels these reads and
clears unacknowledged notices.

The repair-cycle menu places “Capital repairs” immediately after “Repairs”. It
reads the maintenance-owned active-capital endpoint; calculated CAPITAL rows
are excluded from the ordinary repair table. Each capital card can expand the
authoritative ordered repair stages, naming their queues and showing works and
materials in separate columns with the exact quantity and unit. Expansion is
read-only presentation and does not create or advance a logistics task. See
[`MaintenanceScreen.kt`](src/main/java/dev/buhanzaz/rwms/manager/ui/screens/MaintenanceScreen.kt).

The ordinary repair queue reads exactly one public aggregate task-board
snapshot without date or shadow query dimensions. It renders one adaptive
vertical stream grouped by the server-ordered queues and never exposes or uses
an entry's `scheduledDate`: date selectors, horizontal date columns, drag and
drop, ordinary move, and date-swap commands have been removed. Task-board owns
the canonical order and returns the complete unfinished route. ManagerApp binds
only the current REAL maintenance stage into this compact list; the panel owns
the optional full-path highlight and daily-plan presentation. Card detail only
opens the maintenance-owned repair. `ManagerReadCache` retains one account-and-warehouse
scoped aggregate snapshot with its ETag for fail-soft recovery. See
[`RepairQueueView.kt`](src/main/java/dev/buhanzaz/rwms/manager/ui/screens/RepairQueueView.kt),
[`ManagerMaintenanceReadCoordinator.kt`](src/main/java/dev/buhanzaz/rwms/manager/ui/coordinator/ManagerMaintenanceReadCoordinator.kt),
and
[`RwmsApi.kt`](src/main/java/dev/buhanzaz/rwms/manager/network/RwmsApi.kt).

Maintenance refresh traverses every server-advertised page of estimates,
ordinary repairs, and active capital repairs. Page zero retains its ETag and
cache fallback semantics, while tail pages are read sequentially. Acceptance
requests every server-filtered `PENDING` page and hydrates repair details in
stable projection order with at most four requests in flight.

Acceptance review resolves one complete owner-authorized preview snapshot before
the repair editor becomes visible and keeps three presentation scopes separate.
The cabin header matches WorkerApp's general source set: it removes every
work-line `mediaReferences` item from the aggregate references and tries the
ordered stage `TASK_BOARD_ENTRY`/`WORK_RESULT` proofs before the original
estimate, inventory-finding or repair proof. This remains valid for a completed
inventory repair whose historical response no longer carries `inventorySource`.
A work card embeds only that work line's planned `mediaReferences`; if it has
none, the card goes directly to the “Accepted” and “Rework” controls. The end of
a stage embeds only the worker result photos projected in that stage's
`evidence`. Streaming bodies are copied on the IO dispatcher into a complete
app-private cache file; a verified copy fallback handles Android devices that
refuse the final same-directory rename. A cached preview or original is reused
only after its public content endpoint freshly authorizes the exact owner, media
identity, generation, warehouse, and context. Bounded striped promotion locks
preserve an already complete immutable cache file when a concurrent copy fails. If any
referenced photo still cannot be resolved, the editor is not published with a
partial slider. Empty scopes render no photo button, warning or placeholder.
Every slider has arrows and a counter, and tapping a frame opens the same set at
that exact index in the full-screen viewer. The separate acceptance-photo
capture remains mandatory because the canonical accept command requires at
least one acceptance media reference. See
[`MaintenanceAcceptanceScreen.kt`](src/main/java/dev/buhanzaz/rwms/manager/ui/screens/MaintenanceAcceptanceScreen.kt),
[`AcceptanceMediaPolicies.kt`](src/main/java/dev/buhanzaz/rwms/manager/ui/AcceptanceMediaPolicies.kt),
[`ManagerMaintenanceReadCoordinator.kt`](src/main/java/dev/buhanzaz/rwms/manager/ui/coordinator/ManagerMaintenanceReadCoordinator.kt),
and
[`maintenance-service.yaml`](../contracts/openapi/maintenance-service.yaml).

When a work is first added from the maintenance catalog, newly captured,
gallery-picked, and reused condition photos are rendered immediately as
removable thumbnails, matching the edit flow. The work-photo block has three
direct actions: the full-width “Add photo” action opens the in-app CameraX
screen, while “From taken” and “From gallery” share the row below it. “From
taken” includes both local and already loaded photos from the preceding
condition step; selecting one moves it to the work without a duplicate upload.
“From gallery” opens the Android document picker without a preceding
source-choice prompt. The shared estimate, repair, and inventory pull-down photo
carousel snaps open when pulled beyond 50%; otherwise it closes; its
selector travels to the Add row, and tapping a frame opens the full-screen
swipe-and-zoom viewer. In the camera either volume key triggers the shutter once per press and
is consumed by the active activity or the owning dialog window, including when
the task camera is hosted in a full-screen dialog. Ordinary `PHOTO` capture uses the
low-latency CameraX policy, automatic resolution near 12 MP, and defaults HDR
off; Night, explicitly selected full resolution, and explicitly enabled HDR
retain their quality-oriented processing. An upgrade turns the legacy automatic
HDR preference off once, after which the manager may enable HDR again. See
[`MaintenanceScreen.kt`](src/main/java/dev/buhanzaz/rwms/manager/ui/screens/MaintenanceScreen.kt),
[`MainActivity.kt`](src/main/java/dev/buhanzaz/rwms/manager/MainActivity.kt), and
[`ManagerCameraScreen.kt`](src/main/java/dev/buhanzaz/rwms/manager/ui/components/ManagerCameraScreen.kt).

The same CameraX surface records MP4 video with optional microphone audio.
Inventory, estimate, repair, and work evidence pickers accept JPEG, PNG, WebP,
MP4, and WebM; local and owner-authorized downloaded videos play with explicit
Media3 controls and never become an image cover. The app requests the
compressed `PLAYBACK` representation for READY videos while retaining the
server-authorized `ORIGINAL` path only as a compatibility fallback for older
video generations. See
[`ManagerPhotos.kt`](src/main/java/dev/buhanzaz/rwms/manager/ui/components/ManagerPhotos.kt)
and
[`ManagerMediaCoordinator.kt`](src/main/java/dev/buhanzaz/rwms/manager/ui/coordinator/ManagerMediaCoordinator.kt).

A still image remains one app-owned original in the UI. The durable worker
resolves its EXIF orientation before encoding exactly `SMALL`, `MEDIUM`, and
`LARGE` WebP parts; deterministic quality and resolution fallback keeps their
aggregate at or below 1 MiB, and only one high-resolution original is decoded
at a time. The original is not uploaded. The create request
declares only those variants, each part uses its own stable idempotency key and
same-origin `/api/media/v1/upload-sessions/{id}/variants/{kind}/content` PUT,
and finalize sends all three object receipts. MP4/WebM and explicit
compatibility source uploads retain the legacy `/content` request shape. See
[`ImageUploadBundleEncoder.kt`](src/main/java/dev/buhanzaz/rwms/manager/media/ImageUploadBundleEncoder.kt),
[`MediaUploadPayloads.kt`](src/main/java/dev/buhanzaz/rwms/manager/media/MediaUploadPayloads.kt),
and [`MediaUploader.kt`](src/main/java/dev/buhanzaz/rwms/manager/media/MediaUploader.kt).

Local persistence is deliberately limited:

- encrypted OAuth state and the selected warehouse preference;
- account-scoped, server-verified read snapshots in `ManagerReadCache` for
  maintenance, repair queue, and inventory recovery after process death;
- one AES-GCM-authenticated inventory editor snapshot per verified account and
  warehouse, including its current navigation step and app-private copies of
  selected image/video originals; it is removed only after explicit close or
  durable enqueue, so process death resumes the same inspection instead of
  assigning a draft to another account or warehouse;
- an account-and-workspace-warehouse-scoped background-upload document,
  app-private original media files, restart-safe generated WebP parts, and
  WorkManager identity so an already-created upload can resume only for its
  immutable owner; after the READY reference is durably recorded, the outbox
  deletes its scoped original and generated parts but never a user-owned
  gallery URI;
- an AES-GCM-encrypted maintenance catalog snapshot partitioned by the verified
  account and warehouse, using a non-exportable Android Keystore key.

The maintenance catalog cache is a local read optimization only. It is not a
domain source of truth, and all screens must still honor server revisions and
authorization. Ownerless queue version 1 and plaintext catalog entries are
moved to local quarantine and ignored; the app never guesses an owner, restores
them into a workspace, or sends their retained command/media data.
The implementing client boundaries are
[`BackgroundUploadStore.kt`](src/main/java/dev/buhanzaz/rwms/manager/uploads/BackgroundUploadStore.kt),
[`BackgroundUploadCoordinator.kt`](src/main/java/dev/buhanzaz/rwms/manager/uploads/BackgroundUploadCoordinator.kt),
[`BackgroundUploadWorker.kt`](src/main/java/dev/buhanzaz/rwms/manager/uploads/BackgroundUploadWorker.kt),
and
[`MaintenanceCatalogCache.kt`](src/main/java/dev/buhanzaz/rwms/manager/ui/MaintenanceCatalogCache.kt).

## Internal orchestration structure

`ManagerViewModel` is a stable UI facade over one authoritative
`ManagerUiState`; it forwards actions and contains no multi-domain workflow.
The internal coordinators are split by independently changing responsibility:

| Collaborator | Responsibility |
| --- | --- |
| `ManagerCommandRuntime` | Shared state reduction, coroutine scope, Problem Details mapping and session invalidation mechanics only |
| `ManagerWorkspaceCoordinator` | Authentication/workspace, connectivity and background lifecycle |
| `ManagerInventoryCoordinator` | Inventory reads, crash-safe editor state, scoped cache and upload outbox |
| `ManagerShipmentCoordinator` | Shipment list, detail, scheduling, furniture-task readiness and confirmation |
| `ManagerReturnCoordinator` | Return inspection evidence, undamaged acceptance and estimate-start flow |
| `ManagerTransferCoordinator` | Transfer creation, departure, arrival evidence, reconciliation and cancellation |
| `ManagerMaintenanceCatalogCoordinator` | Catalog memory/disk cache, revision and refresh jobs |
| `ManagerMaintenanceReadCoordinator` | Maintenance reads, repair board and acceptance reads |
| `ManagerMaintenanceEditorCoordinator` | The single estimate/repair/rework form state machine |
| `ManagerMaintenancePersistenceCoordinator` | Draft persistence, submission, replacement and background upload work |
| `ManagerMediaCoordinator` | Owner-scoped media reads |

Pure inventory and maintenance editor policies contain deterministic reducers,
not network or persistence effects. Cross-workflow calls use narrow ports such
as catalog access, maintenance refresh and editor close; coordinators do not
call the facade or a late-bound registry. They may keep only jobs, mutexes,
cache metadata and request-generation fences; server state remains authoritative.
Primary sources are
[`ManagerViewModel.kt`](src/main/java/dev/buhanzaz/rwms/manager/ui/ManagerViewModel.kt)
and the [`coordinator/`](src/main/java/dev/buhanzaz/rwms/manager/ui/coordinator/)
package.

## Uploads, failures, and concurrency

Media and command effects use the public media/API flow and stable operation
identifiers. The upload queue records progress and failure for explicit retry;
it does not invent a successful command while offline. Problem Details are
mapped to user-visible errors by the shared backend client. A failed queue write
when retrying an operation or photo is reported through the same error handler; the
original operation and photos remain durable and no new worker is scheduled.
A terminal authentication failure clears the unusable session; a temporary refresh outage
does not. A `409 Conflict` requires the screen to refresh/rebase the server
version before retrying. Streamed preview and original response bodies are
copied into the app-private cache on the IO dispatcher before a local URI is
exposed to Compose; the UI dispatcher never reads a streaming network body.
Before confirming a new inventory cabin, Manager durably saves the complete form and
original photos, then reads the current session directly from the gateway without replacing
the editor. One recognized stale-revision conflict permits one fresh read and one retry only
if the session revision changed. Account, warehouse, session and editor identity are rechecked;
terminal sessions and other conflicts fail without clearing the draft. The original finding ID
remains the source-creation identity across retries and process restarts, including a lost
create response; a revised request gets its own idempotency key. During confirmation, failure
retains the draft and successful durable upload enqueue clears it; explicit editor closure
still discards the draft.
Repeated confirmation taps are coalesced while the first save is in flight, so one confirmation
cannot create multiple upload operations. The upload and draft cleanup retain their original
account-and-warehouse scope even if navigation changes during a suspended request.
Before a durable first inventory inspection is sent, the worker rereads its
active finding, then its session fence, and persists a
newer finding revision when a live asset-status/snapshot update advanced it
during media upload. If that preflight still receives the inventory service's
`409 INVENTORY_VERSION_CONFLICT` with detail `Inventory revision is stale`, it
performs one more read/rebase/save cycle. Both passes are allowed only for
`NOT_INSPECTED` findings in `IDLE` or `SOURCE_CREATED`; a departed finding, in-flight source
creation, or saved/repeated inspection stays fail-closed and cannot be
overwritten by a queued retry. Before rebasing retained media or checking that fence, the worker
reconciles a possibly lost save response against the complete persisted inspection: observations,
media generations and cover, all selected plan choices, ordered lines and frozen stage identities.
An exact match durably completes only the inspection step; any pending idempotent furniture task
still runs. Conflicting or unprovable results remain queued with an explicit recovery message,
without overwriting server data. The same check also runs after a failed save or revision preflight.

Inventory cards display the saved session result: `READY` is free, while a frozen
`WORK_STAGED` plan is ordinary or capital repair according to its explicit capital-repair
choice. This does not change warehouse status. When an upload leaves the active account,
warehouse and inventory queue, Manager rereads the session and findings without a cached
ETag; a pending upload is never presented as a saved inspection.

The manager processes up to three cabin upload operations concurrently. Retries
of the same scoped operation remain serialized, and final domain command reads
and writes run one at a time to preserve shared inventory session fences.
All workers share one image-encoding slot and at most four logical byte-heavy
media uploads at once, preserving each batch's source order. Each batch queues
only a bounded number of photos on that shared limit so other cabins can send
before a large batch finishes. Image bundles additionally share a global limit
of six concurrent part PUTs, so the three parts of one photo can transfer in
parallel without unbounded fan-out across photos. Once a
create/upload/finalize sequence is accepted, its READY polling no longer
occupies a logical upload permit, so later files can use the uplink while the
earlier media projection becomes visible.
Exhausted media owner-proof retries display the specific recovery message and
confirm that the photos remain in the queue; internal exception text stays hidden.

The workspace resolves authoritative `/me`, eligible role, live warehouse
grants, and the selected warehouse before it exposes or resumes durable work.
Queue listing, retry, cancellation, originals, WorkManager input/tags, and
unique work names all carry the same account-and-warehouse scope. A worker
checks `/me` against that immutable owner and every warehouse retained by its
command/media before its first side effect. Logout and warehouse replacement
cancel and join all running and waiting upload work, including closure of each
worker's auth snapshot, before the OAuth session can be cleared or replaced.

Use the contract-defined `expectedVersion`, ETag, or other fencing token for a
mutable command, and the contract-defined idempotency key or stable external ID
for a retryable create/effect. The app currently has no business SSE projection:
reads and durable upload work revalidate through the gateway.

## Build and focused checks

JDK 17 and an installed Android SDK are required. From `app/`:

```bash
bash ./gradlew compileDebugKotlin
bash ./gradlew testDebugUnitTest --tests 'dev.buhanzaz.rwms.manager.network.RwmsApiContractBoundaryTest'
bash ./gradlew testDebugUnitTest
bash ./gradlew -PRWMS_PUBLIC_BASE_URL=https://rwms.example.test assembleDebug
```

`RWMS_PUBLIC_BASE_URL` must be an HTTPS origin without a path, query, fragment,
or user info. The default is a test public gateway; it is not an authorization
to claim an end-to-end production integration.

## Release integrity

Every release artifact task fails closed unless `-PsigningPropertiesFile`
points to a readable external file containing `storeFile`, `storePassword`,
`keyAlias`, and `keyPassword`, and the referenced keystore is readable. Use
[`signing.properties.example`](signing.properties.example) only as a key-name
template; real signing material stays outside Git:

```bash
bash ./gradlew \
  -PsigningPropertiesFile=/secure/path/rwms-manager-signing.properties \
  assembleRelease
```

Build the exact reviewed source scope, then record the APK's package name,
`versionCode`, `versionName`, signing certificate, and SHA-256 before it is
published. `manager-download-site/release-trust-policy.json` additionally pins
which signing certificates are accepted for each `PRODUCTION` or
`INTERNAL_TEST` channel; an empty production allowlist deliberately blocks a
production publication. Install that exact APK on a device/emulator, authenticate through the
intended public gateway, and verify the first profile/workspace request plus the
changed manager flow. A debug APK, a successful Gradle task, or an HTTP 200 is
not release evidence by itself.

## Source of truth

Canonical public operations live in `contracts/openapi/`; the owning service
defines the business invariant behind each operation. Generated/network DTOs,
local caches, and WorkManager records are boundary/client state, never shared
domain models or persistence for the backend.

The cross-service business sequence rendered by the app is indexed in the
[complete cabin lifecycle](../docs/project-knowledge/cabin-lifecycle.md).
