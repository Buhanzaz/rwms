# RWMS Rental Manager for Android

`rental-manager-app/` is the standalone Android client for rental managers. It is deliberately
separate from the warehouse ManagerApp in `app/` and never uses that application's package,
OAuth client, scopes, download surface, or warehouse workflows.

Russian version: [README.ru.md](README.ru.md).

## Current working slice

- Native login through the public gateway with OAuth Authorization Code + PKCE S256.
- Server verification of `USER`, `RENTAL_MANAGER`, `rentalAccess=true`, and explicit warehouse
  grants.
- Warehouse directory for the single RWMS installation with a visible empty-assignment state.
- Real logistics-service client search, pagination, detail, and idempotent creation.
- Real order search, pagination, detail, draft creation, and version-fenced editable fields.
- Existing assistant-service conversation history, existing-client conversation creation,
  archiving, text turns and the single authoritative pending clarification flow. SSE events are
  validated and bounded; a turn POST is never replayed after a transport failure.
- Authoritative cabin search results, structured shortage notices and the current logistics-owned
  held selection. A manager can remove exact cabins or release the complete hold, then publish the
  exact grouped selection through logistics-service and explicitly copy or share its public link.
  Selection and presentation retries keep stable actor-scoped idempotency keys and reread server
  state after an uncertain result or conflict.
- An editable draft or saved order opens the one assistant conversation linked to that exact order.
  Reopening it reuses the server-owned conversation instead of creating a duplicate inquiry.
- Order detail renders the authoritative selected cabins, requested equipment and rental terms from
  logistics-service with Russian status/date labels; transport UUIDs and enum values stay hidden.
- An editable draft can be cancelled only after explicit confirmation. Android sends the current
  order version and a retained idempotency key; logistics-service releases the active cabin
  reservations, records history and returns the authoritative cancelled projection.
- A readiness card exposes the exact server facts still missing from a draft. Save stays disabled
  until contact, client-confirmed address and dates, warehouse, selected cabins and every rental
  term are present; the version-fenced save command then accepts only the returned server state.
- Compact bottom navigation and automatic wide-window navigation rail using Material 3 adaptive
  navigation for Chat, Clients and Orders. The UI exposes neither RWMS operations, logistics
  dispatch, nor administration.

Claims require a canonical owning API before an Android consumer can be added. Android marks a
draft as saved only after logistics-service accepts the version-fenced command; manager fields
remain draft-only, while cabins, rental terms and the client-confirmed address and delivery dates
enter through the existing linked inquiry/presentation workflow owned by backend services.

## Security and transport

- Package: `dev.buhanzaz.rwms.rentalmanager`.
- OAuth client: `rwms-rental-manager-android`.
- Exact application scope: `rental.manage` (with `openid profile offline_access`).
- HTTPS callback: `/auth/rental-manager/callback` on `RWMS_PUBLIC_BASE_URL`.
- Browser and internal service origins are never embedded. Runtime calls stay under `/auth/**` and
  `/api/**` on the public gateway origin.
- AuthState is encrypted with Android Keystore AES-GCM. Final `401` invalidates the local session;
  transient refresh failures preserve it.
- Retriable commands retain a stable UUID while only a SHA-256 request fingerprint enters Android
  saved state. Fingerprints are scoped to the verified manager subject; phone, email, names, and
  comments are not stored in them. Conversation creation retains its server identity across a
  failed retry for the same actor and input; cabin-selection replacement and client-presentation
  publication, draft save and draft cancellation retain their exact idempotency key until the
  server outcome is known.
- The assistant turn client spans the service's bounded 120-second SSE window and disables both
  connection replay and token-refresh replay for the non-idempotent turn POST. A final `401`
  invalidates the shared encrypted session instead.
- Release tasks fail closed without an external protected signing properties file. The existing
  `manager-download-site/` belongs to the warehouse ManagerApp and must not be overwritten.

## Verification

From the repository root:

```bash
./app/gradlew -p rental-manager-app --no-daemon --max-workers=2 \
  -Pkotlin.compiler.execution.strategy=in-process \
  testDebugUnitTest assembleDebug
```

The debug APK is written to:

`build/outputs/apk/debug/rwms-rental-manager-app-debug.apk`

No APK publication is implied by a successful build.
