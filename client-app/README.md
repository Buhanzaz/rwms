# RWMS CustomerApp

CustomerApp is the native Android client for customer cabin booking. Its
application ID is `dev.buhanzaz.rwms.client`, minimum supported OS is Android
11 (API 30), and compile/target SDK is 36. It is independent from ManagerApp
and WorkerApp.

Russian version: [README.ru.md](README.ru.md).

## Ownership and boundaries

The app calls only the public gateway configured by `RWMS_PUBLIC_BASE_URL`.
Authentication uses `/auth/**`; customer rental calls use
`/api/logistics/customer/v1/**`; problem evidence and profile avatars use the same-origin
`/api/media/v1/**` upload-session/content/finalize flow. Server data remains authoritative: the app
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
DataStore pointer retains only the selected warehouse ID, the explicit
`remember warehouse` preference, inquiry ID and stable inquiry-create idempotency key. It is
cleared on sign-out and reloads the inquiry after process recreation only when that preference is
enabled; otherwise the customer returns to the separate warehouse step.
A same-process retry after an uncertain create response reuses that pending key
instead of opening another inquiry.
When the restored server session is `BOOKED`, or a cart-scoped read returns the
stable `INQUIRY_ARCHIVED` problem code, CustomerApp keeps the completed booking
and selected warehouse but atomically rotates the pointer to a new durable
create key before opening the next cart. A booking from the earlier inquiry
does not fence mutations in that new cart.

## Customer flow

Jetpack Compose Material 3 and Navigation 3 provide mutually exclusive
signed-out and signed-in graphs. The signed-in flow collects an individual or
legal profile, then presents warehouse selection as its own step with an optional
`Remember selected warehouse` checkbox, and only then pages through server-returned free cabins.
The catalog header keeps the selected warehouse geometrically centered between fixed menu and
profile actions. Tapping it grows the header downward with other available warehouses without
moving that fixed row, then creates or resumes the corresponding warehouse-bound inquiry. The
catalog has a full-card-width filter action for type, finish, dimensions, category, linoleum and
characteristics, but no text search; characteristic chips have explicit row spacing and every
one-row cabin card uses an `onSurface` outline, which remains legible in the dark theme.
Swipe/full-screen photos never navigate to a passport. Available warehouse furniture can be
assigned per cabin.

An existing individual/legal kind is immutable, while its name/company and contact fields are
editable under the logistics profile version fence. The circular profile affordance renders
initials or the current authenticated avatar. Android's system Photo Picker needs no storage
permission; after a warehouse is selected, the app prepares the exact subject-bound
`LOGISTICS_CUSTOMER_PROFILE/PROFILE_AVATAR` scope, uploads and finalizes one image, waits for its
current `READY` generation and binds it back to the profile. Media-service remains the byte owner.

The cart supports checkbox multi-selection, bulk rental duration, a different
duration per cabin and removal. The app persists the complete per-cabin term
set through the logistics-owned replacement endpoint. Any cabin, furniture or
term change invalidates stale slots. The confirmation screen uses the same
one-month default for both its displayed value and checkout eligibility, so a
temporarily absent local term entry cannot disable an otherwise valid held
slot. Delivery is a focused four-step Navigation
3 flow: full-screen Yandex map/address, server-returned dates, exact returned
fixed-window or full-day slots, then per-cabin term review and checkout. Every slot carries the
required `kind`: `FIXED_WINDOW` displays its exact interval, while `DURING_DAY` displays
`В течение дня` in the date preview, slot choice and confirmation without hiding its non-null
server bounds.

The map starts with Yandex's third-party raster `MapType.MAP` at the
logistics-owned depot, keeps pan/pinch inside MapKit and shows plus, minus and a
bottom-right current-location arrow. A tapped, suggested or device point is rendered immediately
with a bitmap-backed pin, and MapKit night mode follows Android's dark appearance together with
theme-safe controls. Tapping the arrow is the only action that requests Android approximate/precise
location permission; cancellable one-shot Android location requests race only enabled GPS,
network and passive providers, recenter the marker and reverse-geocode the same address/point
binding. Denial, disabled providers, timeout and a missing result are shown explicitly. There is no
layer toggle;
satellite and hybrid layers are not requested. The bottom panel contains only
address search, Yandex/Alice voice input, status and the continue arrow; it remains fixed above
the IME and cannot be dragged. Live Yandex suggestions appear while the customer types. Selecting
a suggestion, submitting typed text, tapping the map or using current location places the marker
and resolves one exact address/point without displaying a redundant confirmation banner.
Russian speech is accepted only from
an installed Yandex recognizer activity; it exposes neither manual coordinate
labels nor a coordinate-entry fallback and never falls back to Google or an
arbitrary default recognizer. If Yandex voice input is absent, the app reports
that through the `Install voice input` modal. Editing the address or point invalidates its binding,
private-site attestations and slots.

Pressing continue with a confirmed address/point and non-empty server cart first opens a modal for
site receiving capacity, private-site truck access and failed-trip responsibility. One selected
cabin fixes capacity to `1` and a truck without a trailer; two or more selected cabins expose a
`1`/`2` dropdown where `2` means a truck with a trailer. Capacity changes invalidate prior offers,
holds and attestations. Both checkboxes are required before the slot request is sent, and the same
capacity is fenced again on hold. Navigation advances to dates only after a newer server search
generation succeeds; failures cannot open an empty date destination. While
that request is active, a non-dismissible overlay blocks repeated input and
shows a spinner above `Идёт расчёт свободных слотов`; it disappears on success
or error. The later hold revalidates the same two frozen facts with capacity and rejects a stale or
incomplete offer. Changing either answer preserves returned offers and the selected slot but clears
an existing hold. Logistics,
not the APK, checks
public-road feasibility for its frozen height, width, length, weight and axle
profile and returns that profile with offers. The date step displays the server price and its
mutually exclusive source: ordinary 60/120/180/240-minute isochrone tier through
`priceIsochroneMinutes`, or a special-price polygon through nullable UUID `priceZoneId`. A missing
or contradictory tariff is shown as undefined and is never replaced by local zero. Forbidden and
no-trailer polygons remain server routing constraints; the APK does not use a price source as route
feasibility. Dates and
slots are grouped only from exact server offers, never fabricated locally. A `DURING_DAY` offer is
still held and rechecked by logistics before checkout; the APK does not choose an arrival hour.

My Orders expands durable bookings into cabins. Acceptance is available only
after logistics marks the exact shipment driver task completed; the customer
signs on a full-screen bounded vector canvas and accepts each cabin separately.
An arrived cabin can be reported before or after acceptance with a category and
description. CameraX captures sequential photos and silent MP4 video into
app-private cache without a system confirmation screen; the document picker
can import supported gallery images/videos, and a mini-gallery supports removal.
Evidence is uploaded to the exact `LOGISTICS_SHIPMENT` line, finalized, polled
until `READY`, and only then referenced by the immutable problem command.

Phone layouts use bottom navigation and larger windows use a navigation rail;
the focused delivery flow hides both. Screens and full-screen dialogs are
edge-to-edge and IME-aware. Customer commands are serialized, 409 reloads
authoritative cart state, and checkout is reconciled with the durable booking
list.

## Dependency graph

Hilt provides one application graph. Retrofit with Kotlin serialization owns
typed API transport; OkHttp owns Bearer/refresh handling; Coil loads images;
Yandex MapKit `4.42.0-full` owns map rendering, suggestions and forward/reverse geocoding; Android
`LocationManager` owns cancellable foreground current-location acquisition;
CameraX owns the in-app photo/silent-video session. An installed Yandex
speech-recognition activity owns optional Russian voice capture, so CustomerApp
requests no microphone permission and stores no audio. Location permissions are requested only by
the explicit map arrow and are not background permissions. The APK contains no
cloud SpeechKit credential: guaranteed server-side Alice/SpeechKit recognition
requires a separately approved Yandex Cloud credential and proxy; a secret must
never be embedded in the APK. UI policy objects remain pure and covered by unit
tests. The app sends the confirmed address, coordinates and customer-declared per-arrival capacity
(`1` without trailer or `2` with trailer); logistics reads the profile and ordered cabin count from
its authoritative customer session and cart.

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

Release APKs intentionally include only the Android ARM ABIs `arm64-v8a` and
`armeabi-v7a`. Debug APKs additionally include `x86_64`, so they can be
installed on an API 30 or newer Google APIs x86_64 AVD; 32-bit `x86` remains
excluded. Hardware acceleration for that AVD on Linux requires a guest-visible
`vmx` or `svm` CPU flag and `/dev/kvm`. A VPS that is itself hosted by KVM still
needs nested virtualization exposed by its provider; otherwise the emulator
runs in slow software mode.

The debug artifact is
`app/build/outputs/apk/debug/app-debug.apk` and has package
`dev.buhanzaz.rwms.client.debug`. A production publication passes an external,
root-readable `signingPropertiesFile` and builds `assembleRelease`; the keystore
and its passwords must never enter this repository. The reviewed release APK
must update only `client-download-site/`.
