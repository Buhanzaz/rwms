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

Signed-in content opens immediately. Signed-out entry uses a 1.5-second procedural Compose reveal:
crossing waves refract the SVG contours, its two parts settle together and a soft light sweeps across
them, followed by staggered fading and sliding of the native entry controls.
The same logo remains in its measured layout throughout: refraction settles to zero before the
controls appear, with no greeting video or separate overlaid logo. System animation duration settings
apply, and a completed greeting is not replayed on configuration changes.
Authentication follows the supplied 404 × 874 canvases: full-screen caustic artwork,
a field-width BLOCK BOX wordmark, bottom-aligned forms, translucent borderless fields and soft
shadows. The wordmark keeps its full size when the keyboard opens and uses the worker app's
centered position, moving only as needed to fit above the measured form. Short forms keep their actions at the
bottom of the available canvas; the longer registration remains scrollable. Logo size and form
position share the same layout constraints on every keyboard-animation frame. Registration submits with
`Зарегистрироваться`, recovery uses `Отправить`, and login keeps `Вход`. System Back returns
from recovery to login, and from login/registration to entry.
Registration collects first name, last name, login, email, confirmed password and phone once.
After authentication the app saves the individual logistics profile through its existing API,
then opens city selection. There is no separate customer-data form. An interrupted save keeps
the encrypted, account-bound registration draft and offers a retry; the profile edits those saved
values. Accounts without a profile or registration draft show an explicit error.
The recovery form matches the supplied reference; submitting it reports that recovery is unavailable
because the current auth contract has no recovery operation. It never reports a sent email or SMS.

The shared design follows the active Panel and Logistics interfaces: cool white surfaces,
restrained borders and shadows, and neutral graphite dark surfaces. The caustic artwork remains a
full-strength animated background behind signed-in screens. Cyrillic Geist is used for
headings and body copy; the approved gradient actions retain their Golos Text typography.
The SIL Open Font Licenses are packaged under
`app/src/main/assets/licenses/`. The approved 12 dp gradient buttons keep their shadow and press
states; secondary actions, focus borders and password visibility controls share the same type scale
and accessible touch targets. The wordmark has a contrasting dark appearance. The adaptive launcher
icon fills the system mask with the supplied gradient and preserves both complete glyphs from [`Logo_App.svg`](../Logo_App.svg), without an inset card around the artwork.
The silent caustic Media3 loop is the background of every application screen, pauses outside the foreground and
is released when its screen composition leaves. The signed-out flow keeps its own full-strength loop; the
application shell supplies the same full-strength loop for loading, guest and signed-in content, so no route has two
players. Its light palette is retained independently of the catalog appearance. A poster extracted from the same
video at the supplied form reference phase supports initial drawing and deterministic native rendering. Catalog
content and dialogs use opaque surfaces. The visual system lives in
[`CustomerStoreDesign.kt`](app/src/main/java/dev/buhanzaz/rwms/client/ui/CustomerStoreDesign.kt) and
[`CustomerTheme.kt`](app/src/main/java/dev/buhanzaz/rwms/client/ui/CustomerTheme.kt).

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

The entry action `Продолжить без входа` opens a separate, ephemeral guest catalog: city selection,
server filters, prices, paging and photo galleries. Reads and public photos use
`/api/logistics/public/v1/catalog/**` with a credential-free HTTP client. A guest has no profile,
inquiry, cart or holds; `Войти для заказа` opens the real login form. Leaving, changing city or
filters, and authenticating cancel and fence pending reads. Errors remain visible; returning to the
foreground or restoring connectivity retries reads without replaying commands or fabricating data.

Delivery-slot searches use a dedicated 70-second read timeout and 90-second total call limit,
allowing the gateway's bounded 55-second routing response to arrive. Other API calls retain their limits.

## Customer flow

After checkout, My Orders shows the immutable server bill as a compact non-fiscal receipt.
The total, status, payment countdown and action stay above expandable details. The detail rows
retain right-aligned amounts: cabin rental, furniture quantity × unit/month price × that cabin's months, accepted
delivery once, and an exact whole-RUB total. A delivery not included in the bill is explicitly
unpriced, not free. No total uses floating point or an int64 accumulator. The displayed five-minute
payment countdown uses server time and monotonic elapsed time; only server confirmation changes
payment status. Test payment is explicit and does not charge real money. A lost response reuses
the durable booking/version command key and reconciles only the exact order and immutable bill.
Old bookings without an issued bill are not displayed as paid. For a saved order without a server
payment window, the receipt explains that no payment deadline or automatic timed cancellation was
assigned. Cancelled bookings show cancelled delivery and expose no reception actions from stale arrival data.

Foreground lifecycle polling reloads bookings, pending payment and the durable unread inbox.
There are no manual refresh actions. Failed read cycles use bounded exponential backoff and pause
after five consecutive failures; returning to the foreground, restoring a validated connection or
finishing an explicit user action renews the read budget. Busy-lane skips consume no attempt.
Exact pending change quotes are read independently when the booking list fails. Polling and process
restoration never renew fee terms, search replacement slots or repeat a payment. Polling does not
reopen a hidden dialog. Expired/stale offers remain unavailable; starting a fresh change requires a customer action,
and a manager waiver is never replaced automatically.
Changing the authentication session cancels and joins the previous UI mutation before admitting
the new profile/warehouse bootstrap. Until cleanup completes the app stays loading; a late old
result cannot finish or invalidate the replacement session.
Only an explicit `Read` action acknowledges a message on the server. Android notifications use
an immutable explicit app intent with no order payload or credentials; a bounded local delivery-ID
cache prevents repeated alerts without becoming domain state. WorkManager schedules a connected
check after the server deadline and periodic 15-minute catch-up, with at most eight attempts per
execution. Android can delay background work; the server releases reservations independently.
Notification permission lives in profile settings, with a system-settings path after denial and
permission status refreshed on return. The order list and server inbox remain available without it.
Background access after process death requires the user's
encrypted remembered session. Logout cancels work, removes notifications and fences late responses.

Jetpack Compose Material 3 and Navigation 3 provide mutually exclusive
signed-out and signed-in graphs. Every newly registered customer completes an
individual profile. Existing individual/legal profile kinds remain immutable;
`Доступ для юрлиц` is explained in profile settings, with no unsupported action in the drawer.
The signed-in flow presents city selection with an optional `Remember selected city` checkbox between the header and cities before
loading server-returned free cabins. Menu and profile remain available at this step. City labels come
from the warehouse service's `city` field; warehouses in the same city retain separate identities and
show an address/name to distinguish them. The inset header centers the city between menu and
profile actions. Tapping it expands the alternatives below the fixed row, then creates or resumes the
selected warehouse's inquiry. A filter action expands an opaque, scrollable facet panel over the cabins without shifting them.
The cabin list is clipped below the fixed header, fades into that boundary and extends to the bottom screen edge. Each change
and reset immediately requests server results; rapid changes retain the latest choice until the
current request finishes. Each single-choice filter occupies one full row; its options open
downward as one attached surface, push the following filters down and close after selection.
Characteristics remain visible as checkboxes. Facet values and card characteristics retain the
asset-owned global order, and values disabled for customers never enter either surface. There is
no text search or apply button. A selected
type narrows dimensions to the server-returned `typeDimensions` relation and clears an incompatible
size before the request. Catalog and cart cards share photo proportions, surfaces and a full-width
header with type on the left and accounting number on the right. They share labelled dimension/finish
facts and monthly-price typography. The catalog title and filter action sit inside the fixed header;
category and characteristic values use rounded colored pills, while selection is marked locally
instead of outlining the whole card. The cart has a labelled `Удалить`
action alongside additional equipment. Full-screen photos use a black background and system areas with an unframed white close
control; they never navigate to a passport. Photos distinguish loading from failure and support
pinch zoom, panning and an explicit reset; one-finger swipes change pages at the original scale.
Available warehouse furniture can be assigned per cabin as `+ Additional` in bounded scrolling
sheets. An empty cart links directly back to cabin selection. Filter options open below their
field, scroll internally and keep their labels centered.

Screen loading uses a thin inline progress bar. Empty and error messages occupy the same compact
content area once loading finishes; existing content remains available, with affected commands
disabled while pending. Payment verification is a neutral pending message, separate from errors.

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
rescheduling offers; fixed windows retain their time ordering. Date/time steps show a shared
server delivery price once when all offers have the same price; differing prices remain explicit
on their rows. Confirmation shows the hold deadline in the selected warehouse’s timezone. The
server remains responsible for hold expiry and the final bill.

The map uses the stylable `MapType.VECTOR_MAP` in flat 2D mode at the logistics-owned depot, keeps pan/pinch inside MapKit and shows plus, minus and a
bottom-right current-location arrow. A tapped, suggested or device point is rendered immediately
with the supplied `map_icon.svg` pin rendered at display density and anchored at its tip. Its blue palette and night mode follow the app's explicit
light/dark appearance. The shared header and glass controls use the same palette; map controls hide
while the keyboard is open. The native map uses MapKit's movable TextureView mode so it follows
Compose page transitions. The required Yandex attribution stays above the complete address panel at the bottom left,
including its suggestions and the keyboard, using the SDK's logo alignment and padding API. Tapping the arrow is the only action that requests Android approximate/precise
location permission; cancellable one-shot Android location requests race only enabled GPS,
network and passive providers, recenter the marker and reverse-geocode the same address/point
binding. Denial, disabled providers, timeout and a missing result are shown explicitly. There is no
layer toggle;
satellite and hybrid layers are not requested. The bottom panel contains only
address search, Yandex/Alice voice input, status and the continue arrow; it remains fixed above
the IME and cannot be dragged. Its closed row matches the header’s 60 dp height and 16 dp side
gutters. Native state-based text editing keeps the caret and horizontal scroll when a long address
is edited; background order reads do not discard those edits. Live Yandex suggestions open downward
in a separate bounded panel while the customer types. Selecting
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
pair of stable full-width `1`/`2` rows where `2` means a truck with a trailer. Capacity changes invalidate prior offers,
holds and attestations. Both checkboxes are required before the slot request is sent, and the same
capacity is fenced again on hold. Navigation advances to dates only after a newer server search
generation succeeds; failures cannot open an empty date destination. While
that request is active, the address panel shows an inline progress bar and
`Идёт расчёт свободных слотов`; address edits and repeated commands are disabled until it finishes. The later hold revalidates the same two frozen facts with capacity and rejects a stale or
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
imports supported gallery images/videos on a cancellable IO job with visible progress.
The form opens fully and scrolls to keep controls reachable in small windows.
It accepts at most 20 attachments of up to 200 MiB each and disables new
capture, selection and submission during import. Cancellation retains completed
drafts; closing the form removes its drafts and cancels the unfinished copy.
A blocked provider read is cleaned up when that read returns. Removing a draft
deletes its private file; a failed deletion stays visible for retry. Removal is
disabled while the report uploads.
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

Navigation uses the drawer with the BLOCK BOX logo on all window sizes. A full-width cart action
below the cabin catalog shows the selected cabin count when nonzero and reserves its own space so
that it cannot cover the list. Other screens
have no persistent cart shortcut. Forward, back and predictive-back transitions are
synchronized full-width slides between opaque screens. Back from the cart or
profile restores the preceding screen; drawer destinations retain the rental catalog (or city
selection) as their root. The opaque, 264-dp-wide drawer centers its logo and rental subtitle and closes before changing
destinations. Its one-tap appearance toggle shows the current mode, `Светлая тема` or `Темная тема`;
system and battery appearance sources are not supported. Screens and full-screen dialogs are
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

The focused native-graphics review renders production Compose screens at 360 dp width
with test-owned data. To export PNGs outside the repository:

    RWMS_CUSTOMER_VISUAL_OUTPUT=/tmp/rwms-customer-visuals \
      ./gradlew :app:testDebugUnitTest --rerun --tests '*CustomerVisualReviewTest' \
      --no-daemon --max-workers=2 \
      -Pkotlin.compiler.execution.strategy=in-process

These renders check layout and appearance; they do not run the installed APK, live
customer workflows, the system keyboard, MapKit or live animation playback. Keep device
verification separate and never commit generated review images.

`CustomerGreetingPreview` in the debug source set renders six fixed phases of the production
Compose greeting, from empty artwork through SVG refraction to the settled logo and entry controls.
Compose Preview uses the existing caustic poster without starting Media3; rendering these previews
does not require running the test suite.

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
