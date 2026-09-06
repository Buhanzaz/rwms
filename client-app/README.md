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

Every process launch first renders the exact imported BLOCK BOX vector for
three seconds. The start actions enter from opposite screen edges without a parent alpha
layer clipping their travel. The logo spans the action width and moves above the form;
the IME collapses its reserved space while the whole form remains scrollable. Login,
registration, and recovery use short page transitions in one signed-out screen. Login has
an explicit `Вход` heading and `Войти` action, and back navigation follows the same transition. Registration
collects login, email, confirmed password, and phone; the auth boundary still
receives only its contract fields, while email and phone prefill the mandatory
individual logistics profile after sign-in. `Продолжить без аккаунта` remains
visible but unavailable because the public contract has no guest catalog.
Recovery remains an explicit unavailable result rather than fabricated success.

One silent Media3 player renders the exact imported `background_caustic.webm`
behind every CustomerApp route. It uses crop/zoom, repeats the asset indefinitely,
pauses decoding outside the active lifecycle, and is released with the root
composition instead of being recreated between screens. Auth/profile fields and
app actions reuse translucent 12 dp fields and gradient buttons, with a restrained press motion.
Manrope headings and Golos Text body copy include Cyrillic glyphs and are packaged with their
SIL Open Font Licenses under `app/src/main/assets/licenses/`. Operational screens use clearer translucent surfaces, a distinct outlined secondary
action, and 48 dp action targets. Button shadows use Compose's cached shape-aware `dropShadow`, independently of the
translucent fill; text fields use a light rim without an elevation layer. Date and time selection shows the address, available
server offers and price without internal capacity counters or vehicle-profile dimensions; the
confirmed site-access requirement remains visible at checkout. The motion foreground is in
[`CustomerStoreWelcomeScreen.kt`](app/src/main/java/dev/buhanzaz/rwms/client/ui/CustomerStoreWelcomeScreen.kt),
the shared visual/player layer is in
[`CustomerStoreDesign.kt`](app/src/main/java/dev/buhanzaz/rwms/client/ui/CustomerStoreDesign.kt),
and the conditional binding is in
[`CustomerApp.kt`](app/src/main/java/dev/buhanzaz/rwms/client/ui/CustomerApp.kt).

When `Запомнить` is selected, access/refresh tokens are stored as AES-GCM ciphertext in DataStore;
otherwise the usable session remains process-memory only. The key is
non-exportable Android Keystore material. A rejected access token is refreshed
once; a repeated 401 signs the client out, while 409 reloads authoritative cart
state and invalidates stale delivery-slot data. A separate non-authoritative
DataStore pointer retains only the selected warehouse ID, the explicit
`remember warehouse` preference, inquiry ID and stable inquiry-create idempotency key. It is
cleared on sign-out and reloads the inquiry after process recreation only when that preference is
enabled; otherwise the customer returns to the separate warehouse step.
A same-process retry after an uncertain create response reuses that pending key
instead of opening another inquiry.
A separate bounded, non-authoritative DataStore ledger retains only stable UUIDs for unresolved
cabin-acceptance and problem-report request fingerprints. It survives process recreation, never
evicts an unresolved key, and removes a key only after the server returns or the booking list
reconciles the authoritative result; it stores neither command payloads nor server-owned results.
When the restored server session is `BOOKED` or `CANCELLED`, or a cart-scoped read returns the
stable `INQUIRY_ARCHIVED` problem code, CustomerApp keeps the completed booking
and selected warehouse but atomically rotates the pointer to a new durable
create key before opening the next cart. A booking from the earlier inquiry
does not fence mutations in that new cart.

## Customer flow

After checkout, My Orders shows the immutable server bill as a branded non-fiscal
receipt with item amounts aligned to the right and a prominent exact total: cabin rental, furniture quantity × unit/month price × that cabin's months, accepted
delivery once, and an exact whole-RUB total. A delivery not included in the bill is explicitly
unpriced, not free. No total uses floating point or an int64 accumulator. The displayed five-minute
payment countdown uses server time and monotonic elapsed time; only server confirmation changes
payment status. Test payment is explicit and does not charge real money. A lost response reuses
the durable booking/version command key and reconciles only the exact order and immutable bill.
Old bookings without an issued bill are not displayed as paid. For a saved order without a server
payment window, the receipt explains that no payment deadline or automatic timed cancellation was
assigned. Cancelled bookings show cancelled delivery and expose no reception actions from stale arrival data.

Foreground lifecycle polling reloads bookings, pending payment and the durable unread inbox.
Changing the authentication session cancels and joins the previous UI mutation before admitting
the new profile/warehouse bootstrap. Until cleanup completes the app stays loading; a late old
result cannot finish or invalidate the replacement session.
Only an explicit `Read` action acknowledges a message on the server. Android notifications use
an immutable explicit app intent with no order payload or credentials; a bounded local delivery-ID
cache prevents repeated alerts without becoming domain state. WorkManager schedules a connected
check after the server deadline and periodic 15-minute catch-up, with at most eight attempts per
execution. Android can delay background work; the server releases reservations independently.
Notifications require OS permission, and background access after process death requires the user's
encrypted remembered session. Logout cancels work, removes notifications and fences late responses.

Jetpack Compose Material 3 and Navigation 3 provide mutually exclusive
signed-out and signed-in graphs. Every newly registered customer completes an
individual profile. Existing individual/legal profile kinds remain immutable;
the drawer exposes `Доступ для юрлиц` as an intentionally non-functional future-access placeholder.
The signed-in flow presents city selection with an optional `Remember selected city` checkbox before
loading server-returned free cabins. Menu and profile remain available at this step. City labels come
from the warehouse service's `city` field; warehouses in the same city retain separate identities and
show an address/name to distinguish them. The inset glass header centers the city between menu and
profile actions. Tapping it expands the alternatives below the fixed row, then creates or resumes the
selected warehouse's inquiry. A background-free filter action opens an opaque two-column facet form directly
below the fixed header, replacing the cards until applied or closed. The form uses the content
scroll area and keeps the catalog scroll position when closed; option menus have opaque fills.
It filters by type, finish, dimensions, category, linoleum and characteristics, but has no text
search. A selected type narrows dimensions to the server-returned `typeDimensions` relation and
clears an incompatible size before the request. Cabin cards lead with an unframed accounting
number followed by the type at the same title scale; they omit provisional availability and
delivery copy. Swipe/full-screen photos never navigate to a passport. Available warehouse
furniture can be assigned per cabin as `+ Additional`.

Catalog and cart cabin cards show the current logistics-owned monthly rental price, keyed by
the cabin's asset type/category. Exact whole-ruble strings are decoded without rounding, including
an explicit zero default. Missing or malformed prices are errors, not free rent. Monthly rent is
separate from delivery and fees; these catalog/cart reads do not freeze booked commercial terms.

An existing individual/legal kind is immutable, while its name/company and contact fields are
editable under the logistics profile version fence. The circular profile affordance renders
initials or the current authenticated avatar, with a camera button on the circle. Android's system
Photo Picker opens a local circular crop editor on the shared app background. Pan/pinch and a zoom
slider select a square; Android decodes source orientation with a 2048px edge limit. Done exports a
1024×1024 sRGB JPEG without source EXIF/GPS, and only then starts the upload. Cancel sends no media
command; a profile version/avatar update preserves unsaved contact edits. No storage permission is
needed. The profile can prepare the exact subject-bound
`LOGISTICS_CUSTOMER_PROFILE/PROFILE_AVATAR` scope before catalog selection by using the existing
avatar warehouse, the current catalog warehouse, or the first authorized warehouse; the server
still validates that immutable scope. The app validates/copies the bounded JPEG before preparation,
uses its exact bytes/checksum for the stable upload identity, uploads and finalizes it, waits for its current
`READY` generation and binds it back to the profile. Media-service remains the byte owner.

The cart presents each selected cabin as a card with its own rental-term control, additional
furniture/equipment sheet and removal action; it has no checkbox or bulk-duration UI. The app
persists the complete per-cabin term set through the logistics-owned replacement endpoint. Any
cabin, furniture or term change invalidates stale slots. The confirmation screen uses the same
one-month default for both its displayed value and checkout eligibility, so a
temporarily absent local term entry cannot disable an otherwise valid held
slot. Delivery is a focused four-step Navigation
3 flow: full-screen Yandex map/address, server-returned dates, exact returned
fixed-window or full-day slots, then per-cabin term review and checkout. Every slot carries the
required `kind`: `FIXED_WINDOW` displays its exact interval, while `DURING_DAY` displays
`В течение дня. Точное время подтвердит логист` in the date preview, slot choice and confirmation
without hiding its non-null server bounds. Flexible arrival is listed last within each date, including
rescheduling offers; fixed windows retain their time ordering.

The map uses the stylable `MapType.VECTOR_MAP` in flat 2D mode at the logistics-owned depot, keeps pan/pinch inside MapKit and shows plus, minus and a
bottom-right current-location arrow. A tapped, suggested or device point is rendered immediately
with a blue bitmap-backed cube pin. Its blue palette and night mode follow the app's explicit
light/dark appearance. The shared header and glass controls use the same palette; map controls hide
while the keyboard is open. The native map uses MapKit's movable TextureView mode so it follows
Compose page transitions. The required Yandex attribution stays in the reserved gap below the address
panel at the bottom left, above system UI or the keyboard, using the SDK's logo alignment and padding API. Tapping the arrow is the only action that requests Android approximate/precise
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
pair of equal-width `1`/`2` options where `2` means a truck with a trailer. Capacity changes invalidate prior offers,
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
profile and returns that profile with offers. The date step displays the server price after the
heading and explanatory text. Isochrone tiers and special-price polygons remain transport metadata;
they are not shown to customers. A missing or contradictory tariff is shown as `Не рассчитана` and is never replaced by local zero. Known
whole-ruble prices are locale-grouped from the server integer without floating-point or local price
calculation. Forbidden and
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
Acceptance and problem retries reuse their durable request identity after process death; an
uncertain response is reconciled from the authoritative booking before local recovery state is
cleared.
My Orders also supports service-owned cancellation and delivery rescheduling only for an identified
`COMPLETED` booking. Both commands carry the exact booking version; cancellation and atomic slot
replacement additionally use a process-durable `Idempotency-Key`. Replacement offers are searched
against that booking alone and confirmation sends the selected server slot ID/version and a required
change-quote ID/version, never the original cart, cabin, or equipment payload. Before applying either
change, CustomerApp displays the service quote, including its exact string-encoded whole-ruble fee
and the replacement date/window snapshot. Test payment is explicit consent in that same command;
only an exact quote GET reporting `TEST_PAID`/`APPLIED` displays the non-financial test marker, with
no second confirmation. `APPLYING` permits status checks, not another payment. The workflow store
retains only quote/booking identities and retry fingerprints across process death, never payment
state or money. Valid quoted replacements, including manager waivers, recover without fabricating
slot offers. Expired/unconfigured offered terms can be recalculated; an expired waiver is retained
with a manager-contact explanation rather than silently replaced by paid terms. The supplied support
phone opens Android `ACTION_DIAL`, never an automatic call; missing phone/dialer is explicit.
The command response is reconciled from the authoritative booking list, so a failed replacement leaves the old delivery
projection intact. `CANCELLATION_PENDING` and `CANCELLED` have customer-facing Russian labels,
stable domain codes become actionable Russian messages, and an absent `cancellationFeeRubles` is
not rendered as zero. The transport and recovery behavior is owned by
[`CustomerApi.kt`](app/src/main/java/dev/buhanzaz/rwms/client/data/CustomerApi.kt) and
[`CustomerRepository.kt`](app/src/main/java/dev/buhanzaz/rwms/client/data/CustomerRepository.kt);
the presentation policy is in
[`CustomerPolicies.kt`](app/src/main/java/dev/buhanzaz/rwms/client/ui/CustomerPolicies.kt).

Navigation uses the drawer with the BLOCK BOX logo on all window sizes. A floating cart shortcut at
the bottom right of catalog, orders and profile shows the selected cabin count when nonzero. The cart
and delivery flow have no persistent shortcut. Forward, back and predictive-back transitions are
synchronized full-width slides with no crossfade between translucent screens. Back from the cart or
profile restores the preceding screen; drawer destinations retain the rental catalog (or city
selection) as their root. The opaque, 296-dp-wide drawer has a wider logo and closes before changing
destinations. It exposes a single one-tap explicit
light/dark appearance toggle; system and battery appearance sources are not supported. Screens and full-screen dialogs are
edge-to-edge and IME-aware. Customer commands are serialized, 409 reloads
authoritative cart state, and checkout is reconciled with the durable booking
list.

## Dependency graph

Hilt provides one application graph. Retrofit with Kotlin serialization owns
typed API transport; OkHttp owns Bearer/refresh handling; Coil loads images; Media3 owns the
single lifecycle-aware silent background loop;
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
must update only `client-download-site/`. Every release artifact task validates
the four required signing properties and referenced keystore before execution;
the property names are documented in
[`signing.properties.example`](signing.properties.example). The download site
then independently requires the `PRODUCTION` package identity, immutable APK
hash, source provenance, and a signer certificate pinned in
`client-download-site/release-trust-policy.json`.
