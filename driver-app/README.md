# RWMS DriverApp

`driver-app/` is the standalone Android client for warehouse drivers. Its
package is `dev.buhanzaz.rwms.driver`; it never shares local state with
ManagerApp or WorkerApp.

## Product surface

The main menu has exactly three destinations:

- **Warehouse work** (`Работа на складе`) shows the server-filtered driver
  board for repair/KPP, internal movement and shared `WAREHOUSE_DRIVERS`
  movement work. Personally assigned logistics is not duplicated here.
- **Logistics** (`Логистика`) shows the driver's `ASSIGNED_DRIVER` shipments and
  returns for the selected date. For dates strictly after the device-local
  current date, eligible `WAREHOUSE_DRIVERS` trips appear in a separate
  **Additional tasks** section. These can be identity-free movements or future
  deliveries explicitly published by a logistics operator; ordinary hidden
  `UNASSIGNED` shipments are not exposed. The driver may preview a rich trip and reserve
  it with **Take additional task**. This online claim assigns future work but
  does not start task-board execution; the service repeats the date, warehouse
  and qualification checks. Today's shared work cannot be previewed or claimed
  through this flow. The screen opens on the current date; a fixed Russian
  month heading follows the centered date in the horizontal neighboring-date
  carousel. A card opens the existing rich trip, media and action detail. The
  rich trip detail offers **Open in Yandex Maps**:
  it hands the driver-selected destination to the installed Maps app, or to its
  HTTPS web fallback. Confirmed coordinates take priority over the address;
  RWMS does not call a Yandex routing API or transmit device location. The APK
  declares only the Yandex Maps package and HTTPS VIEW capability needed for
  Android package visibility, so the browser fallback remains available on
  Android 11+ without broad installed-application access.
  An assigned or eligible shared interwarehouse transfer uses that same detail
  and offline cache. Logistics supplies the source-to-destination route, exact
  cabin numbers and characteristics, actual/required furniture comparison,
  loose furniture, dispatcher comment and ordered load/travel/unload
  instructions through task-board's existing `taskText`, `works`, `materials`
  and `comments` fields. DriverApp renders those server-owned snapshots and
  does not create a parallel transfer API or local inventory state. Displayed
  checkpoints are instructions; the existing evidence-gated `COMPLETE`
  remains the one server command that confirms completion.
- **Uploads** (`Загрузки`) shows durable pending actions and photo uploads and
  allows a failed operation to be retried.

Profile and logout are a secondary action above the menu, not a fourth
destination. Driver actions are `TAKE`, `PAUSE`, `RESUME` and `COMPLETE`.
DriverApp never offers `JOIN`; that belongs to the slinger flow in WorkerApp.
For a `LOGISTICS_DRIVER` task, completion requires one READY result photo. The
driver does not wait for a slinger: an eligible slinger may join after the
driver's take, and either active participant may close the same task. The
camera is enabled only while the task is effectively `IN_PROGRESS` and the
current driver is its active participant (or a durable local `TAKE` is waiting
to synchronize). The detail screen reports server-READY and locally retained
non-ready photos separately, so a captured file is never presented as proof
that completion is already allowed.

The navigation and menu are owned by
[`DriverApp.kt`](app/src/main/java/dev/buhanzaz/rwms/driver/DriverApp.kt) and
[`DriverDownloads.kt`](app/src/main/java/dev/buhanzaz/rwms/driver/DriverDownloads.kt).
The dated projection is owned by
[`LogisticsScreen.kt`](feature-tasks/src/main/java/dev/buhanzaz/rwms/driver/feature/tasks/LogisticsScreen.kt).

## Authentication and public APIs

The public PKCE client is `rwms-driver-android` with scopes
`openid profile offline_access driver.tasks`. The HTTPS callback is
`<public gateway>/auth/driver/callback`; the native login client validates and
consumes it in memory, and the APK exposes no OAuth deep-link receiver.

Task-board calls use only `/api/task-board/driver/v1/**`. Rich logistics detail
uses `/api/logistics/v1/driver-tasks/{taskId}` for exact `ASSIGNED_DRIVER` work
and for a same-warehouse future shared preview. Reserving that shared trip uses
`POST /api/logistics/v1/driver-tasks/{taskId}/claim`; it is intentionally a
connected server command rather than an offline task-board `TAKE`, and a
successful claim schedules an authoritative projection refresh. Media uses
`/api/media/v1/**`. All traffic goes through the configured HTTPS public
gateway. See
[`DriverAuthConfiguration.kt`](core-auth/src/main/java/dev/buhanzaz/rwms/driver/core/auth/DriverAuthConfiguration.kt)
and
[`DriverGatewayApi.kt`](core-network/src/main/java/dev/buhanzaz/rwms/driver/core/network/DriverGatewayApi.kt).

## Offline work, camera and uploads

Room stores the authorized projection and a transactional local outbox.
WorkManager retries permitted transient failures with stable operation IDs.
Captured JPEG files remain encrypted in app-private storage until
reservation, upload and finalize have completed; process or device restart
does not discard them. CameraX capture normalizes EXIF orientation and applies
the existing size/resolution limits. Server state remains authoritative after
every reconnect or conflict.

If an older client captured a photo before `TAKE` and the server therefore
rejected its reservation, a later `TAKE` or `RESUME` atomically requeues that
same encrypted JPEG with its stable operation IDs when the original capture is
still covered by the active 24-hour offline lease. An expired capture is not
silently redated or uploaded; the driver must take a new photo.

The implementation lives in `core-database/`, `core-sync/`, `core-media/` and
`feature-camera/`. The Uploads screen is the recovery surface; it never exposes
encrypted payloads or private file paths.

## Realtime and Firebase

SSE and optional FCM data messages are invalidation signals only. DriverApp
registers a Firebase Installation ID (`targetKind=FID`) rather than a legacy
registration token, then refreshes authoritative REST state. It deliberately
rejects `TASK_JOIN_AVAILABLE`, which is intended for slingers.

Firebase is enabled only when
`driver-app/app/google-services.json` is supplied outside source control. The
file is ignored by Git and must describe the Firebase Android application for
`dev.buhanzaz.rwms.driver`; no credentials are bundled in this repository.

## Build and focused checks

Use JDK 17 and Android SDK 36:

`core-database` enables the same core-library desugaring as the other modules
that parse server instants, preserving the API 23 minimum while using
`java.time` for offline lease and evidence recovery fences.

```bash
cd driver-app
JAVA_HOME=/usr/lib/jvm/java-17-openjdk-amd64 ./gradlew \
  :core-auth:testDebugUnitTest \
  :core-network:testDebugUnitTest \
  :feature-task-detail:testDebugUnitTest \
  :core-sync:testDebugUnitTest \
  :app:testDebugUnitTest
JAVA_HOME=/usr/lib/jvm/java-17-openjdk-amd64 ./gradlew :app:assembleDebug
```

Without `google-services.json`, the APK still builds and uses SSE/polling, but
an end-to-end FCM delivery test is unavailable. End-to-end gateway validation
also requires a provisioned driver account and the matching OAuth client.
