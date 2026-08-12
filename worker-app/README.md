# RWMS Worker for Android

'worker-app/' is the standalone Android application for RWMS workers. It is
built separately from the root Java multi-module build and consumes only the
public gateway contracts.

Russian version: [README.ru.md](README.ru.md).

## Public boundary, role, and sign-in

- Configure one absolute HTTPS public gateway origin with
  'RWMS_PUBLIC_BASE_URL'. The client uses only '/auth/**' and '/api/**'; it
  must never call an internal service origin, a localhost service port, or
  '/api/internal/**'.
- OAuth client 'rwms-worker-android' uses Authorization Code with PKCE S256
  and scopes 'openid profile offline_access worker.tasks'. The password is
  never retained. The browser/deep-link activity is not exported.
- The worker principal and the server-issued worker context define visible
  assignments, warehouse scope, groups, and permissions. Client filtering or an
  offline cache is not authorization.
- The application and round launcher icon resolve to the adaptive
  [`ic_launcher_worker.xml`](app/src/main/res/mipmap-anydpi-v26/ic_launcher_worker.xml)
  resource on Android 8+. Its
  [full-bleed artwork](app/src/main/res/drawable-nodpi/ic_launcher_worker_artwork.png)
  contains no baked-in mask or outer white margin, so the operating system alone
  applies the final circle or squircle. Earlier Android versions use the
  full-bleed bitmap alias under `mipmap-anydpi`.

### Transport contract gate

[`WorkerGatewayApiContractBoundaryTest.kt`](core-network/src/test/java/dev/buhanzaz/rwms/worker/core/network/WorkerGatewayApiContractBoundaryTest.kt)
pins all 11 declared `WorkerGatewayApi` methods to their canonical public
OpenAPI source: nine fixed gateway routes and exactly two allowlisted dynamic
media routes. It rejects internal/private namespaces and service origins,
eagerly resolves every Retrofit/kotlinx.serialization request and response
converter, and checks every active worker/task-board and media JSON root
fixture. Binary request/response bodies and the `Unit` unregister response are
converter-checked and intentionally are not JSON fixtures. Dynamic media calls
are rejected before Retrofit unless they satisfy the exact public same-origin
path guards.

The canonical seven-field worker action command requires `workerGroupId` and
`evidenceId` to be present even when their values are null. The targeted
[`WorkerActionRequestDtoSerializer.kt`](core-network/src/main/java/dev/buhanzaz/rwms/worker/core/network/WorkerActionRequestDtoSerializer.kt)
emits those explicit null keys and rejects missing or additional command
properties. The application's global JSON policy remains unchanged for cached
projections, Problem Details, media payloads, and every other DTO.

## Screens and server-owned work

The root menu contains Work, Downloads, and Profile. Work displays only the
categories, groups, assignments, KPI palette, task identifiers, materials,
works, comments, and media references supplied in the worker feed. WorkerApp
has no driver work surface or driver trip read. Task-board exposes a joint
`LOGISTICS_DRIVER` task here only after its primary performer has taken it and
only to an eligible secondary worker; a stale cached waiting entry is hidden
locally as an additional fail-closed guard.

The eligible slinger joins an active joint task with the current group ID when
one exists. The owning service pauses the previous assignment timer for that
group and later resumes it according to the server workflow. Once joined, the
slinger can pause, resume, or complete the shared task. Completion requires at
least one server-`READY` photo; either joined participant may complete, and the
server closes the same task for both. Ordinary group-bound roles and
qualification-only categories keep their own panels; queues and task cards can
also collapse. Panels stack on a narrow screen and use a two-pane horizontal
board from 720 dp. A card shows cabin, scheduled date and authoritative elapsed
time; its expanded state adds status, optional budget timer and photo count.
“Photos and details” opens the existing task detail, CameraX evidence capture
and durable upload flow. The Downloads screen remains the recovery surface for
failed or pending uploads and explicit retry. The task screen records a work
result and JPEG evidence but never decides a task transition locally.

Server-provided KPI ranges determine colors; no local green/yellow/red policy
is invented. Worker-facing work data does not expose price/cost fields.

## Offline store, outbox, sync, and realtime

- Room is the UI source of truth for account-scoped feed projections, task
  detail, sync progress, conflicts, invalidations, and the outbox. An action and
  its outbox row are written in one local transaction.
- Sensitive unsent command/conflict bodies are separately encrypted; captured
  evidence is encrypted in app-private files. These records are client recovery
  state, not backend persistence.
- A 24-hour offline lease is anchored to server time and 'elapsedRealtime'.
  Unique connected WorkManager work sends actions, reserves/uploads/finalizes
  evidence, waits for 'READY', then refreshes the feed.
- FCM and SSE carry invalidation/revision signals only. They trigger a focused
  refresh; they never replace the authoritative feed. FCM device registration
  uses `targetKind=FID` with the Firebase Installation ID, never a messaging
  registration token. Foreground polling also periodically refreshes the feed.

## Errors, concurrency, and retries

Gateway Problem Details are mapped to explicit safe failures. A missing token,
invalid session, expired lease, unreachable gateway, or unsupported operation is
not represented as mock success. Conflicts are persisted so that the task UI can
show the server state and require an intentional refresh/retry.

After the authenticator's one refresh opportunity, `401` stops sync for login;
`403` stops it for a grant refresh or worker action. A `409` persists the
conflict (and any supplied server snapshot) before the authoritative feed is
refreshed. Automatic failure retry is limited to `429`, `502`, `503`, `504`,
and proven transport faults; malformed Problem Details retain the actual HTTP
status with a safe fallback.

One unique WorkManager job performs at most four attempts: only the permitted
transient failures return `Result.retry()`, and WorkManager uses a persisted
jittered exponential-backoff seed. A server-confirmed pending evidence state
completes this run and awaits a later explicit trigger. Cancellation escapes
without scheduling another attempt. A later explicit foreground, FCM, or user
trigger may enqueue a new job with the same durable operation identities.

Every mutable action uses the contract's version fence and a stable operation
ID/idempotency key where defined. Evidence uses the ordered
reservation → upload/finalize → 'READY' flow. Retries reuse durable operation
identity; the client must not duplicate an effect just because a network
response was lost.

## Camera and evidence

Worker evidence is JPEG-only. The camera normalizes physical orientation into
pixels and writes EXIF 'Orientation=1' before encryption; it limits normalized
images to 8 MP and the evidence contract to 15 MB. Ultra HDR is not used.
The visible Video tab intentionally does not produce MP4 because the public
worker evidence contract does not accept it.

## Build and focused checks

JDK 17 and an installed Android SDK are required. From 'worker-app/':

~~~bash
bash ./gradlew :app:compileDebugKotlin
bash ./gradlew :core-network:testDebugUnitTest --tests 'dev.buhanzaz.rwms.worker.core.network.WorkerGatewayApiContractBoundaryTest'
bash ./gradlew testDebugUnitTest lintDebug assembleDebug
bash ./gradlew -PRWMS_PUBLIC_BASE_URL=https://rwms.example.test assembleDebug
~~~

For a signed release, keep signing material outside Git and supply an external
properties file modeled on [signing.properties.example](signing.properties.example):

~~~bash
bash ./gradlew -PsigningPropertiesFile=/secure/path/rwms-worker-signing.properties assembleRelease
~~~

## Release integrity

Publish only the exact reviewed APK. Record its package name, 'versionCode',
'versionName', signing certificate, and SHA-256, then install that same file on a
device/emulator. An end-to-end claim requires authentication through the intended
public gateway, the first worker-context request, and the changed task/offline
flow; a successful build or an HTTP 200 alone is insufficient.

## Known limitations

- The current Room projection does not persist qualification display names or
  queue-to-worker-class bindings. A qualification-only panel therefore uses the
  authoritative category name rather than guessing a role label.
- The worker SSE endpoint currently treats a new subscription as a fresh
  invalidation and does not implement a usable 'Last-Event-ID' replay path.
  Foreground polling reduces the stale window but is not event replay.
- Local invalidation rows are append-only in the current schema and lack a
  retention/pruning policy and a '(userId, revision)' index. Long-lived installs
  can accumulate unnecessary local data.
- The baseline-profile module remains intentionally inactive while the Android
  plugin/toolchain compatibility is stabilized; startup/performance claims need
  measurement on the actual release configuration.

## Source of truth

The public worker operations and payloads are defined by 'contracts/openapi/';
event semantics are defined by 'contracts/events/'. The owning service remains
responsible for authorization, task transitions, and recovery invariants. The
gateway routes and validates; it does not own a worker workflow.

The worker failure boundary is implemented by
[GatewayFailure.kt](core-network/src/main/java/dev/buhanzaz/rwms/worker/core/network/GatewayFailure.kt),
[WorkerSyncCoordinator.kt](core-sync/src/main/java/dev/buhanzaz/rwms/worker/core/sync/WorkerSyncCoordinator.kt),
and [WorkerSyncWork.kt](core-sync/src/main/java/dev/buhanzaz/rwms/worker/core/sync/WorkerSyncWork.kt).
