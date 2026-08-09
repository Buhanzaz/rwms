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
pins all 62 declared `RwmsApi` methods to their canonical public OpenAPI source:
60 fixed gateway routes and exactly two allowlisted dynamic media routes. It
rejects internal/private namespaces and service origins, eagerly resolves every
Retrofit/Moshi request and response converter, and checks representative
decode/encode fixtures for all eight consumed contract owners: auth, warehouse,
inventory, asset, logistics, maintenance, task board, and media. Binary media
bodies are converter-checked and intentionally are not JSON fixtures. The
dynamic upload and variant-read tests also prove that their callers reject a
foreign origin before Retrofit is invoked.

## Screens and ownership of local state

The navigation graph covers the manager home/menu, background uploads,
logistics returns/shipments/transfers, inventory, maintenance estimates and
repairs, acceptance, and camera/media steps. A screen never owns a durable
business transition: it sends the public command and then observes or refreshes
the server result.

Local persistence is deliberately limited:

- encrypted OAuth state and the selected warehouse preference;
- account-scoped, server-verified read snapshots in `ManagerReadCache` for
  maintenance, repair queue, and inventory recovery after process death;
- an account-and-workspace-warehouse-scoped background-upload document,
  app-private original media files, and WorkManager identity so an
  already-created upload can resume only for its immutable owner;
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
| `ManagerInventoryCoordinator` | Inventory reads, editor state, scoped cache and upload outbox |
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
mapped to user-visible errors by the shared backend client. Authentication
failure clears the unusable session, and a `409 Conflict` requires the screen
to refresh/rebase the server version before retrying.

The workspace resolves authoritative `/me`, eligible role, live warehouse
grants, and the selected warehouse before it exposes or resumes durable work.
Queue listing, retry, cancellation, originals, WorkManager input/tags, and
unique work names all carry the same account-and-warehouse scope. A worker
checks `/me` against that immutable owner and every warehouse retained by its
command/media before its first side effect. Logout and warehouse replacement
cancel and join running upload work, including closure of the worker's auth
snapshot, before the OAuth session can be cleared or replaced.

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

Build the exact reviewed source scope, then record the APK's package name,
`versionCode`, `versionName`, signing certificate, and SHA-256 before it is
published. Install that exact APK on a device/emulator, authenticate through the
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
