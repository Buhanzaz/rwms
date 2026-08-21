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
  resource on Android 8+. The complete
  [worker artwork](app/src/main/res/drawable-nodpi/ic_launcher_worker_artwork.png)
  is centered by
  [`ic_launcher_worker_foreground.xml`](app/src/main/res/drawable/ic_launcher_worker_foreground.xml)
  inside the adaptive safe zone. Android 12+ uses that compact foreground on a
  worker-blue system splash instead of scaling the artwork across the screen.
  Earlier Android versions keep the bitmap alias under `mipmap-anydpi`.

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

The navigation drawer contains Task Board and Downloads; the app-bar monogram
opens Profile. The board displays only categories, groups, assignments, the KPI
palette, and task summaries supplied in the worker feed. WorkerApp has no driver
work surface or driver trip read. Task-board exposes a joint `LOGISTICS_DRIVER`
task here only after its primary performer has taken it and only to an eligible
secondary worker; a stale cached waiting entry is hidden locally as an
additional fail-closed guard.

The eligible slinger takes an active joint task with the current group ID when
one exists: the visible `Взять задание` control sends the `JOIN` wire action.
The owning service pauses the previous assignment timer for that group and
later resumes it according to the server workflow. Slinger participation is
optional, so the driver may complete with a ready result photo before anyone
joins. WorkerApp does not present a pause action; a joined slinger can resume a
server-paused task or complete the shared task. Completion requires at least
one server-`READY` photo from either active participant; the server closes the
same task for both. Ordinary group-bound roles and qualification-only
categories keep their own panels; queues and task cards can also collapse.
Panels stack on a narrow screen and use a two-column horizontal board from
720 dp.

Each queue persists the task-board `entryType` and `pinned` fields. Active REAL
work is shown first, followed by every pinned and then ordinary waiting REAL entry
in the authoritative server queue position; SHADOW entries remain visible afterwards in
their stored queue position. A newly promoted earlier shadow therefore moves
ahead of an unpinned waiting entry but never ahead of a pinned one. SHADOW cards
are subdued, explicitly labelled as waiting for a previous stage, and fail
closed without TAKE or any other worker action. A route whose first required
stage is electricity arrives as REAL in the electricity queue and can be taken
immediately; SES blocking and route promotion remain server-owned.
The manager daily-plan count never truncates WorkerApp's REAL backlog or TAKE;
while SES is unfinished, the worker feed still omits that task's later shadows.

The centered board header is `Доска задач`; it does not repeat a group-role
caption or routine sync-progress text. Its refresh icon rotates while a current
sync stage is active, while offline and blocked-evidence failures remain
explicit. Group and queue headers show their disclosure controls without an
aggregate task count. A task-card header vertically aligns the cabin number,
status and disclosure icon; the scheduled date is absent. A waiting card shows
`Выделенное время` and the bare maintenance-owned difficulty (`Легкий ремонт`,
`Средний ремонт` or `Тяжелый ремонт`) once. An active card shows the same
difficulty together with `Время работы` and KPI on the right, without requiring
expansion. Expansion shows `Этап X из Y`, `Приоритет N`, and one
integer uploaded-photo count; it never renders a required-photo fraction or a
`таймер остановлен` suffix. A task card never renders the technical maintenance
title. These behaviors are owned by
[`TasksScreen.kt`](feature-tasks/src/main/java/dev/buhanzaz/rwms/worker/feature/tasks/TasksScreen.kt).

`Открыть задание` opens a dedicated full-screen task destination, including on
tablets and unfolded devices. Its app bar contains only the cabin number. The
content starts with a swipeable general-photo pager; when the task source
defines a cover, that title image is the first frame. A full-width
elapsed/remaining/KPI timer is followed immediately by `Этап X/Y`, priority and
the bare maintenance-owned repair complexity without a `Ремонт:` prefix; the result-photo collection
follows. Every work is a single card with quantity opposite its name and only that work's
`sourceMediaIds` photos directly below it under `Фото к работе`; materials
follow in the same readable form. Task descriptions and manager comments are
not rendered. Selecting a photo opens that exact item in the full-screen paged
and zoomable viewer, whose title also identifies the work for work-linked
photos. Breaks, off-shift time and pauses remain server-owned, but the screen
has no pause or direct result-photo button. The unsupported
`Сообщить о проблеме` action is not shown until a server command exists; the layout is defined in
[`TaskDetailScreen.kt`](feature-task-detail/src/main/java/dev/buhanzaz/rwms/worker/feature/taskdetail/TaskDetailScreen.kt).
The entry/resume action stays in a safe-area-aware static footer rather than
scrolling with task content. Its button spans the available width, and the
ordinary TAKE label is `Взять задание`.

Authenticated full-screen media first loads the SMALL preview for the selected
page and its immediate neighbours, then progressively replaces the selected
preview with the original. Per-path jobs are cancelled independently during a
swipe. A 16-entry preview LRU keeps viewed pages available for back-swipes,
while at most three originals remain decoded; downloads are capped at three in
parallel, previews at 256,000 pixels, and originals at four million pixels.
Evicted and route-cleared bitmaps are recycled after the published UI state no
longer references them. Task-detail thumbnails
are decoded to at most 256,000 pixels, at most three are fetched/decoded in
parallel, and the ViewModel retains no more than 16 recent entries while
recycling evicted bitmaps. Camera confirmation uses a separate two-million-pixel preview.
These bounds prevent a large retained inventory archive from becoming an
Android heap-sized bitmap cache. Evidence:
[`PhotoViewModel.kt`](app/src/main/java/dev/buhanzaz/rwms/worker/PhotoViewModel.kt),
[`TaskMediaThumbnailViewModel.kt`](feature-task-detail/src/main/java/dev/buhanzaz/rwms/worker/feature/taskdetail/TaskMediaThumbnailViewModel.kt), and
[`CameraScreen.kt`](feature-camera/src/main/java/dev/buhanzaz/rwms/worker/feature/camera/CameraScreen.kt).

For a maintenance repair, the task-board detail already combines every work and material from the
current consecutive same-queue package. WorkerApp renders those server-ordered arrays and sends one
TAKE/COMPLETE for the representative entry; it does not split, repeat, or locally close individual
repair-stage lines. The same COMPLETE closes every remaining member of that package on the server.
Other task sources retain entry-level execution.

Completing a task opens three equal-width, vertically stacked actions: CameraX,
Android photo picker, and cancel. The picker accepts up to ten images. Every
selected image is physically oriented, converted to a local WebP original plus
SMALL/MEDIUM/LARGE WebP upload parts, encrypted, and durably queued in selection
order before the completion callback is emitted. The three upload parts total
at most 1 MiB; only the original is visible in the UI. The final
saved evidence ID is attached only where the logistics completion contract
requires a selected photo. The sync coordinator never sends `COMPLETE` until
the queued evidence has been processed and required evidence is server-`READY`.
Downloads remains the recovery surface for failed or pending uploads and
explicit retry. The task screen records a work result and WebP evidence but
never decides a task transition locally.

Server-provided KPI ranges determine colors; no local green/yellow/red policy
is invented. Worker-facing work data does not expose price/cost fields.

## Offline store, outbox, sync, and realtime

- Room is the UI source of truth for account-scoped feed projections, task
  detail, sync progress, conflicts, invalidations, and the outbox. An action and
  its outbox row are written in one local transaction.
- Sensitive unsent command/conflict bodies are separately encrypted; the local
  original and three upload parts are encrypted in app-private files. They are
  deleted only after task-board authoritatively reports evidence `READY`.
  These records are client recovery state, not backend persistence.
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
reservation → upload/finalize → 'READY' flow. Reservations keep outbox order;
up to two evidence photos and up to three WebP parts are transferred
concurrently with stable per-part keys. Upload paths are exact same-origin media
API paths and never MinIO credentials or internal storage URLs. Retries reuse
durable operation identity; the client must not duplicate an effect just
because a network response was lost.

## Camera and evidence

Worker evidence is client-produced WebP. Camera/gallery input is bounded to 8 MP
and 15 MB, then physical orientation is applied to pixels before WebP encoding.
One encrypted original remains locally visible while three encrypted
SMALL/MEDIUM/LARGE variants form the upload payload; their deterministic
manifest and aggregate length are reserved with task-board, and their total is
at most 1 MiB. Ultra HDR is not used.
An encrypted JPEG captured before the Room 8→9 upgrade retains its original
reservation and may finish once through the compatible source-upload shape;
media-service pins that exact object without rotating, decoding or compressing
it. New evidence never enters this recovery path.
Gallery images use Android's multi-select photo picker with a ten-image limit.
They are copied sequentially into transient private cache and pass through the
same orientation, 8 MP, 15 MB, WebP and encrypted evidence pipeline before each
temporary copy is removed. A partial batch keeps and schedules already durable
evidence while reporting the exact saved count; it is never reported as full
success. The capture/import behavior is owned by
[`CameraScreen.kt`](feature-camera/src/main/java/dev/buhanzaz/rwms/worker/feature/camera/CameraScreen.kt)
and
[`CameraViewModel.kt`](feature-camera/src/main/java/dev/buhanzaz/rwms/worker/feature/camera/CameraViewModel.kt).
The WorkerApp CameraX surface carries the ManagerApp photo controls needed in
the field: rear/front selection, supported optical zoom, focus/metering, night
mode, flash/torch, framing grid and exposure. While the camera is active, either
hardware volume key triggers exactly one photo per press; holding a key does not
create duplicate evidence and normal volume handling returns after the camera
closes.
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
