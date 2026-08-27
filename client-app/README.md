# RWMS CustomerApp

CustomerApp is the native Android client for customer cabin booking. Its
application ID is `dev.buhanzaz.rwms.client`, minimum supported OS is Android
11 (API 30), and compile/target SDK is 36. It is independent from ManagerApp
and WorkerApp.

Russian version: [README.ru.md](README.ru.md).

## Ownership and boundaries

The app calls only the public gateway configured by `RWMS_PUBLIC_BASE_URL`.
Authentication uses `/auth/**`; customer rental calls use
`/api/logistics/customer/v1/**`. Server data remains authoritative: the app
does not create cabin availability, inventory balance, delivery slots, or
booking success locally.

## Authentication

Login performs OAuth 2.0 Authorization Code with S256 PKCE for client
`rwms-customer-android`. Username/password and Spring session cookies exist
only during one native exchange and are never persisted. The callback
`https://localhost/auth/customer/callback` is parsed and validated in-process,
not opened. Registration first obtains CSRF metadata, posts the confirmed
password, and then runs the same PKCE login.

Access/refresh tokens are stored as AES-GCM ciphertext in DataStore. The key is
non-exportable Android Keystore material. A rejected access token is refreshed
once; a repeated 401 signs the client out, while 409 reloads authoritative cart
state and invalidates stale delivery-slot data. A separate non-authoritative
DataStore pointer retains only the selected warehouse ID, inquiry ID and stable
inquiry-create idempotency key. It is cleared on sign-out and lets process
recreation replay or reload the inquiry before reading the authoritative cart.
A same-process retry after an uncertain create response reuses that pending key
instead of opening another inquiry.

## Customer flow

Jetpack Compose Material 3 and Navigation 3 provide mutually exclusive
signed-out and signed-in graphs. The signed-in flow collects an individual or
legal profile, chooses a warehouse, pages through every server-returned free
cabin as one card per row, and filters by type, finish, dimensions, category,
linoleum and characteristics. It supports swipe/full-screen photos without a
passport route, reserves available furniture per cabin, shows a cart, and
checks out to a durable booking. Delivery is a focused four-step Navigation 3
flow: full-screen map and address, server-returned dates, exact server-returned
slots, then rental duration and confirmation. Changing a cabin or furniture
selection clears the previous slot choice and requires a fresh server capacity
calculation.
The warehouse response carries the logistics-owned depot coordinates. The
delivery map opens on that depot as a full-screen Yandex vector map, keeps
pan/pinch gestures inside MapKit, and provides permitted vector/raster-layer,
plus, minus and selected-point recenter controls. Satellite and hybrid types
are restricted by Yandex to Yandex applications, so CustomerApp does not use
them; it also does not enable routes, traffic or weather. The bottom search
sheet accepts typed input, an exact map tap, a
manual address/coordinate fallback, or Russian speech returned by the Android
device recognizer. Speech results go through the same Yandex geocoder and do
not bypass address/point confirmation. The continue chevron remains disabled
until the address, coordinates and non-empty server cart are jointly valid.
The following date screen groups only the exact returned offers; it never
creates local calendar availability. A completed empty slot search is shown as
no current availability rather than as an instruction to repeat the same
search.
After inquiry creation succeeds, the selected warehouse and inquiry ID become
the active workflow before facets and the first catalog page are requested. A
later catalog dependency error therefore remains on the catalog destination
and cannot turn a repeated warehouse tap into another inquiry.
Customer commands are serialized without queuing duplicate gestures. Checkout
status is reconciled with the newer durable booking list; a rejected checkout
discards its released slot, while pending and completed checkout states fence
later cart mutations.

Phone layouts use bottom navigation and larger windows use a navigation rail;
the focused delivery flow hides both. All screens are edge-to-edge, IME-aware,
and expose content descriptions for interactive icons/photos. Yandex MapKit
Full is isolated in the delivery-map adapter. A typed or spoken address is
geocoded near the selected depot; a map tap keeps the exact tapped coordinates
and reverse-geocodes its address. Editing either the address or manual
coordinate draft invalidates the previous binding and clears stale slots.
Manual address/latitude/longitude confirmation remains an explicit fallback.
Slot search is enabled only after one address and point are confirmed together
and the server-owned cart contains a cabin.

## Dependency graph

Hilt provides one application graph. Retrofit with Kotlin serialization owns
typed API transport; OkHttp owns Bearer/refresh handling; Coil loads images;
Yandex MapKit `4.42.0-full` owns map rendering plus forward/reverse geocoding.
The Android speech-recognition activity owns optional Russian voice capture,
so CustomerApp requests no microphone permission and stores no audio. This is
not a guaranteed Yandex Alice/SpeechKit integration: that requires a separate
approved Yandex Cloud credential and a server-side proxy; a service secret must
never be embedded in the APK. UI policy objects remain pure and covered by unit
tests. The app sends only the confirmed address and coordinates; logistics
reads the profile and cabin count from its authoritative customer session and
cart.

## Build and verification

Use Gradle 9.1 and JDK 17. On the VPS, keep the build bounded:

    ./gradlew testDebugUnitTest assembleDebug \
      --no-daemon --max-workers=2 \
      -Pkotlin.compiler.execution.strategy=in-process

Every build requires a root-readable properties file outside the repository:

    mapkitApiKey=<Yandex MapKit key>

Pass a non-default location with
`-PmapkitPropertiesFile=/protected/path/mapkit.properties`. The key is exposed
to the app only through generated `BuildConfig`; it must never be committed or
printed in build logs. Because a client SDK key is necessarily present in the
APK, restrict it in the Yandex developer dashboard to the exact application ID
and release signing certificate.

The debug artifact is
`app/build/outputs/apk/debug/app-debug.apk` and has package
`dev.buhanzaz.rwms.client.debug`. A production publication passes an external,
root-readable `signingPropertiesFile` and builds `assembleRelease`; the keystore
and its passwords must never enter this repository. The reviewed release APK
must update only `client-download-site/`.
