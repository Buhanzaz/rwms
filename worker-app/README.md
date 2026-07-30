# RWMS Рабочий

`worker-app/` is an independent Android Gradle build for the private RWMS worker
APK. It is deliberately not included in the repository's Java 25 multi-module
build.

## Configuration

The app only talks to the public HTTPS gateway. The default build targets the
running test environment:

```bash
./gradlew assembleDebug
```

This produces an APK configured for `https://77-90-158-90.sslip.io`. Another
public HTTPS gateway can be selected explicitly:

```bash
./gradlew -PRWMS_PUBLIC_BASE_URL=https://rwms.example.org assembleDebug
```

The registered public client is `rwms-worker-android`, using Authorization Code
+ PKCE S256 and scopes `openid profile offline_access worker.tasks`. The native
client consumes the validated authorization response in memory. It does not
open a Custom Tab and the APK exposes no OAuth browser/deep-link activity.
Non-HTTPS origins and origins containing paths, credentials, queries or
fragments are rejected at build/runtime.

For a signed release, create a secret properties file outside Git using
[`signing.properties.example`](signing.properties.example), then run:

```bash
./gradlew -PsigningPropertiesFile=/secure/path/rwms-worker-signing.properties assembleRelease
```

Without that property `assembleRelease` produces an unsigned release APK; no
key, Firebase file, token or service origin is stored in this repository.

## Security and offline behavior

- The launch screen accepts the worker's **«Логин приложения»** and password
  created in task-board settings. A memory-only cookie session performs the
  authorization server's CSRF-protected login and Authorization Code + PKCE
  exchange without opening a browser. The password is never logged or
  persisted and leaves memory with the login screen/session attempt.
- AppAuth state is AES-GCM encrypted by an Android Keystore key before it is
  persisted in DataStore. Backup, data extraction and clear-text traffic are
  disabled. Room and captured photos live in app-private/no-backup storage.
- Room is the UI source of truth. A task action updates its projection and
  inserts a stable outbox operation in one Room transaction.
- The manager-selected current group and operational availability come from
  the worker context. A worker cannot choose another group while taking a task.
- Task time is rendered from the task-board service's schedule-aware timer
  snapshot. Local display progress is capped at the next server transition and
  stays frozen during breaks, off-shift periods and manual pauses.
- A server-issued 24-hour lease is evaluated from a server-time/
  `elapsedRealtime` anchor. Expired visible cache is removed, while encrypted
  unsent commands and evidence remain scoped to their original user until
  connection is restored.
- Synchronization runs as a unique, connected WorkManager job in this order:
  actions, evidence reservation, a fresh media session, upload/finalize,
  `READY` polling, then a refreshed feed. Upload sessions are not persisted as
  offline authority.

FCM is optional. The dependency compiles without `google-services.json`; it is
only used as an invalidation trigger once Firebase configuration is supplied
outside Git. Foreground SSE invalidations and a 15-second polling fallback are
always available.

## Local checks

Use JDK 17 (the build declares Java/Kotlin toolchains 17):

```bash
./gradlew testDebugUnitTest lintDebug assembleDebug assembleRelease
```

Compose UI behavior is covered by regular unit/instrumented tests. Screenshot
recording is deliberately deferred: the available Android screenshot Gradle
plugin is pre-release and this private APK keeps stable dependencies only.

Live FCM delivery, CameraX hardware behavior, API 23/28/36 device checks, and
baseline-profile generation remain device/VPS checks; this repository does not
contain a Firebase configuration, release key, or physical-device runner.
