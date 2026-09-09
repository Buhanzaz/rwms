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
  [worker vector](app/src/main/res/drawable-v24/ic_launcher_worker_logo.xml), matching
  the root `Logo_app.svg` with Cyrillic С and Р,
  is centered by
  [`ic_launcher_worker_foreground.xml`](app/src/main/res/drawable/ic_launcher_worker_foreground.xml)
  inside the adaptive safe zone. Android 12+ uses that compact foreground on a
  worker-blue system splash instead of scaling the artwork across the screen.
  The pre-adaptive launcher uses a raster compiled from the same SVG. Android 6
  also uses it for the startup foreground because native vector gradients require API 24.

### Transport contract gate

[`WorkerGatewayApiContractBoundaryTest.kt`](core-network/src/test/java/dev/buhanzaz/rwms/worker/core/network/WorkerGatewayApiContractBoundaryTest.kt)
pins all 15 declared `WorkerGatewayApi` methods to their canonical public
OpenAPI source: thirteen fixed gateway routes and exactly two allowlisted dynamic
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

The navigation drawer contains `Моё задание` and Downloads; the app-bar
monogram opens Profile. A silent looping water video, the exact BLOCK BOX logo,
a three-second logo greeting, animated sign-in fields and animated gradient
buttons are shared with CustomerApp. The video remains behind every route, and
the task, notice and dialog surfaces stay lightly translucent.
The drawer logo is centered. The login logo retains its width when the keyboard
reduces the viewport height; it only changes position.

The home surface renders exactly one server-authorized card instead of a task
board: a joined active slinger task first, then the current worker/group active
or paused ordinary task, then the first waiting task in authoritative category
and queue order. A local pending action, including COMPLETE, remains the foreground
card until the server acknowledges it; an optimistic DONE never exposes the next
waiting task early. The card is centered and bounded to 620 dp on
larger windows. WorkerApp still owns no queue membership, assignment or task
transition and has no driver work surface or driver-trip read.

Task-board exposes a joint `LOGISTICS_DRIVER` task only after its primary
performer has taken it and only to an eligible secondary worker. Before JOIN,
that active task appears as one non-dismissible priority dialog over any
WorkerApp route. Selecting `Взять задание` opens its detail and queues the
existing durable `JOIN` action with the current group ID. The owning service
atomically pauses the previous assignment for the whole group and automatically
resumes it after the shared task closes; the restored card then becomes the one
home task again. Slinger participation is optional, so the driver may complete
with a ready result photo before anyone joins. Completion requires at least one
server-`READY` photo from either active participant and closes the same shared
task for both. WorkerApp never fabricates the pause/resume transition and does
not expose a manual pause action.

Task-board publishes only warehouse queues whose manager checkbox is enabled.
Within each published ordinary queue, active `REAL` work remains available and
is followed by the first configured number of waiting `REAL` entries in the
authoritative server order. `SHADOW` route stages are never sent to WorkerApp.
The native detail and `TAKE`/`JOIN` boundaries recheck the same publication
window, so a stale local card cannot bypass a changed plan. A newly promoted
earlier stage resumes its persisted position and enters the worker plan when
that position is inside the configured window; a pinned waiting REAL stays
ahead. A route whose first required stage is electricity can therefore arrive
directly in the electrician queue, while SES blocking and route promotion
remain server-owned.

The centered home header is `Моё задание`; it does not repeat a group-role
caption or routine sync-progress text. Its refresh icon rotates while a current
sync stage is active, while offline and blocked-evidence failures remain
explicit. A card header aligns the cabin number and status; the scheduled date
is absent. A waiting card shows `Выделенное время` and the bare
maintenance-owned difficulty (`Легкий ремонт`, `Средний ремонт` or
`Тяжелый ремонт`). An active card shows the same difficulty with `Время работы`
and KPI. The always-visible summary also shows `Этап X из Y`, `Приоритет N` and
one integer uploaded-photo count; it never renders a required-photo fraction,
technical maintenance title or `таймер остановлен` suffix. These behaviors are
owned by
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
has no pause or direct result-photo button. An active participant can select
`Сообщить о проблеме`; the layout is defined in
[`TaskDetailScreen.kt`](feature-task-detail/src/main/java/dev/buhanzaz/rwms/worker/feature/taskdetail/TaskDetailScreen.kt).
The entry/resume action stays in a safe-area-aware static footer rather than
scrolling with task content. Its button spans the available width, and the
ordinary TAKE label is `Взять задание`.

The problem sheet saves its comment and up to ten encrypted photos as a Room
draft. Its paperclip opens the camera or Android's single/multiple gallery
picker. Closing the sheet or app preserves the draft. Sending atomically
freezes the encrypted declaration and enables its photo uploads; sync posts
the complete report before uploading photos or completing that task. Report
photos are separate from result evidence and never satisfy a completion gate.
An own-report read refreshes them even after the task closes. Downloads retains
failed reports and their comment, with explicit immutable retry; queued is not
displayed as delivered. Room 11→12 adds the nullable report association without
changing existing result photos.

A canonical `LOGISTICS_DRIVER_TASK` transfer is marked
`Межскладское перемещение` on the task card and opens as `Межскладской рейс`.
Logistics freezes the source-to-destination route, exact cabin numbers and
characteristics, actual/required furniture comparison, loose furniture and the
dispatcher comment into task-board's existing `taskText`, `works`, `materials`
and `comments` snapshots. The ordered works cover source arrival, loading and
verification, travel, destination unload and unload confirmation; WorkerApp
renders those server-owned facts without a second transfer transport or local
inventory state. All TAKE/JOIN/evidence/COMPLETE effects continue through the
ordinary encrypted offline outbox, and the completion dialog labels the same
evidence-gated COMPLETE command as unload confirmation. The generic contract
does not add a separate mutable command for every displayed checkpoint, so the
app never claims an intermediate operation has completed merely because its
instruction is visible. Canonical transfer recognition and the detail
projection are implemented by
[`WorkerLabels.kt`](core-ui/src/main/java/dev/buhanzaz/rwms/worker/core/ui/WorkerLabels.kt)
and
[`TaskDetailPresentation.kt`](feature-task-detail/src/main/java/dev/buhanzaz/rwms/worker/feature/taskdetail/TaskDetailPresentation.kt).

Both task-card and detail stage labels use the required zero-based
`routeStepIndex` plus one over `routeStepCount`, the authoritative ordinal and
count of worker execution packages. They never derive presentation from the raw
persisted `routeIndex`: that separate value remains the identity supplied to
camera/evidence reservation. Room 10→11 adds the separate package ordinal with
a fail-safe zero for legacy rows and upgrades cached detail JSON with its known
package count until the next authoritative feed/detail refresh.

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
recycling evicted bitmaps. The transient CameraX thumbnail is decoded to at
most 256,000 pixels, while its user-opened capture gallery uses a separate
two-million-pixel preview.
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
camera batch and every picker selection is physically oriented, converted to a
local WebP original plus SMALL/MEDIUM/LARGE WebP upload parts, encrypted, and
durably queued in capture or selection order. The last successfully confirmed photo
and its COMPLETE command enter the same Room transaction; the screen callback only
navigates and cannot lose completion on process death. A partial batch never closes
the task automatically. Confirming an already saved subset after removing failed
frames queues completion directly in the durable store. The three upload parts total
at most 1 MiB; only the original is visible in the UI. The final
saved evidence ID is attached only where the logistics completion contract
requires a selected photo. The sync coordinator never sends `COMPLETE` until
the queued evidence has been processed and required evidence is server-`READY`.
The gate merges the feed's server count with exact READY IDs from cached detail
and local finalized rows, without counting the same evidence twice.
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
  It strictly bounds a new offline TAKE/JOIN/PAUSE/RESUME and new capture. For
  an already assigned task, its original result photo and COMPLETE retain their
  stable IDs and actual timestamps even after that window; task-board rechecks
  the current assignment, state, version, evidence gate and future-time bound.
  A passed task `deadlineAt` does not block this completion; once task-board
  accepts it, the canonical completion fact advances the mapped repair to
  acceptance.
  Expiring the lease removes unrelated reusable feed data but retains the current
  assigned result task and pending-command recovery rows. New result capture and
  COMPLETE still require an intact monotonic server-time anchor and the cached
  current assignment; other new transitions keep their active-lease gate.
  Unique connected WorkManager work preserves prerequisite → reservation →
  upload/finalize → COMPLETE ordering independently for each entry, so evidence
  waiting or failure on one task does not block another task from progressing.
  It refreshes the feed after every pass. A historical
  `Действие создано вне срока offline lease` result-photo failure is recognized
  from either retained review or upload-error state. After the next authenticated
  context, the same reservation outbox row is reset with the fresh lease while
  preserving the encrypted bytes, evidence identity and capture time; no photo is
  fabricated. If task-board proves that the task is already `DONE` or `CANCELLED`,
  WorkerApp retains those encrypted bytes as local `SUPERSEDED` recovery data and
  removes only the unfulfillable active replay. Downloads hides that archived row,
  while a manual retry immediately reports that sync was queued and then displays
  persisted sync progress.
- FCM and SSE carry invalidation/revision signals only. They trigger a focused
  refresh; they never replace the authoritative feed. FCM device registration
  uses `targetKind=FID` with the Firebase Installation ID, never a messaging
  registration token. A reconnect opens a fresh SSE subscription and triggers
  an authoritative REST refresh; the client does not send a replay cursor.
  Foreground polling also periodically refreshes the feed.

## Errors, concurrency, and retries

Gateway Problem Details are mapped to explicit safe failures. A missing token,
invalid session, unreachable gateway, or unsupported operation is not
represented as mock success. An expired lease remains a conflict for a new
offline transition, but not for the current worker's delayed result photo or
COMPLETE. Conflicts are persisted so that the task UI can show the server state.
A full-feed refresh never acknowledges them: the worker must explicitly select
`Принять состояние RWMS и обновить`, which closes the visible conflict and requests
a new authoritative feed.

After the authenticator's one refresh opportunity, `401` stops sync for login;
`403` stops it for a grant refresh or worker action. A `409` persists the
conflict (and any supplied server snapshot) before the authoritative feed is
refreshed. Because the conflict is already durable and requires user input, its
WorkManager run succeeds instead of becoming a terminal scheduler failure.
Automatic failure retry is limited to `429`, `502`, `503`, `504`,
and proven transport faults; malformed Problem Details retain the actual HTTP
status with a safe fallback.

Worker-visible failure text never uses a Problem Details `title`, `detail`,
violation message, or arbitrary exception message. `core-network` maps the
authoritative status and recognized problem code to a fixed, action-oriented
Russian message; an unknown code or status receives a generic safe fallback.
The raw transport DTO remains attached to the typed exception only for typed
metadata and structured diagnostics. Sync uses the mapped text for outcomes,
progress, conflicts, outbox retry reasons, and evidence review state.

One unique WorkManager job performs at most four attempts: permitted transient
failures and pending evidence processing return `Result.retry()`, using a persisted
jittered exponential-backoff seed. Pending processing therefore receives bounded
background follow-up without requiring the task screen to remain open. Cancellation escapes
without scheduling another attempt. A later explicit foreground, FCM, or user
trigger may enqueue a new job with the same durable operation identities.
Foreground refresh and invalidation requests coalesce with existing work. A newly
committed command or photo instead appends a follow-up, so an already running sync
cannot consume and lose the trigger after reading its earlier outbox snapshot.

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
mode, flash/torch, framing grid and exposure. Every shutter action immediately
returns to the live preview without an accept/retake screen. A circular
bottom-left thumbnail and count open the swipeable transient batch gallery,
where individual frames can be removed; the bottom-right arrow persists the
whole ordered batch. If persistence partially fails, already durable frames
leave the transient gallery and a retry sends only the remaining files. If the
worker removes the last failed frame, the arrow completes the already durable
subset; in both cases the completion callback receives all evidence IDs exactly
once. While the
camera is active, either
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

Every APK or bundle release task fails before artifact creation when the
properties file, any of its four required values, or the referenced keystore is
missing or unreadable. Debug tasks remain independent from production keys.

## Release integrity

Publish only the exact reviewed APK. Record its package name, 'versionCode',
'versionName', signing certificate, and SHA-256, then install that same file on a
device/emulator. `worker-download-site/release-trust-policy.json` separately
pins the only certificate accepted for the `PRODUCTION` channel and rejects a
debug package, debug version or cross-channel signer. An end-to-end claim requires authentication through the intended
public gateway, the first worker-context request, and the changed task/offline
flow; a successful build or an HTTP 200 alone is insufficient.

## Known limitations

- The current Room projection does not persist qualification display names or
  queue-to-worker-class bindings. A qualification-only panel therefore uses the
  authoritative category name rather than guessing a role label.
- Worker SSE reconnect is intentionally invalidation-only: local event IDs are
  retained for deduplication and audit, not cursor replay. A reconnect's
  authoritative REST refresh and foreground polling reduce the stale window.
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
