# RWMS DriverApp

`driver-app/` is the standalone Android client for warehouse drivers. Its
package is `dev.buhanzaz.rwms.driver`; it never shares local state with
ManagerApp or WorkerApp.

## Product surface

The main menu has exactly three destinations:

- **Warehouse work** (`Работа на складе`) shows the server-filtered driver
  board for repair/KPP, internal movement and shared `WAREHOUSE_DRIVERS`
  movement work. Personally assigned logistics is not duplicated here.
- **Logistics** (`Логистика`) shows only the driver's `ASSIGNED_DRIVER`
  shipments and returns for the selected date. It opens on the device-local
  current date; a fixed Russian month heading follows the centered date in the
  horizontal neighboring-date carousel. A card opens the existing rich trip,
  media and action detail.
- **Uploads** (`Загрузки`) shows durable pending actions and photo uploads and
  allows a failed operation to be retried.

Profile and logout are a secondary action above the menu, not a fourth
destination. Driver actions are `TAKE`, `PAUSE`, `RESUME` and `COMPLETE`.
DriverApp never offers `JOIN`; that belongs to the slinger flow in WorkerApp.
For a `LOGISTICS_DRIVER` task, completion requires one READY result photo. The
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
uses `/api/logistics/v1/driver-tasks/{taskId}` only for exact
`ASSIGNED_DRIVER` work; shared `WAREHOUSE_DRIVERS` work remains on the
task-board detail authorized for the warehouse driver pool. Media uses
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
