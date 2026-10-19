# Panel Warehouse And Asset Cutover Contract

**Status:** `APPROVED FOR THE CORE CUTOVER`

## Authority

The user explicitly authorized replacing the new panel's mock authentication
and mock warehouse-to-equipment data path. The follow-up instruction preserves
warehouse administration, including creating a warehouse, in the new panel.

The current `panel/` is the approved UX starting point. Its browser stores,
fixture IDs and DTOs are not a production data-migration input or an implicit
cross-service domain contract. `wms-panel-old` remains read-only and is not a
source for this cutover.

## Outcome

The production panel uses the stateless gateway and Bearer JWTs for:

1. OIDC Authorization Code + PKCE login and `/api/users/me` identity;
2. canonical UUID warehouse selection and warehouse administration;
3. asset-service cabin and equipment read paths; and
4. only those existing asset write paths whose command, authorization,
   idempotency and concurrency semantics are already proved by the canonical
   OpenAPI contract.

Production must fail closed when token, gateway configuration or a required
service is unavailable. It must not silently fall back to `localStorage`,
`IndexedDB`, seed records or a browser-derived equipment balance.

## Ownership and routes

| UI capability | Owner | Canonical route |
| --- | --- | --- |
| Sign-in, callback, current USER | `auth-service` via gateway | `/auth/**`, `/api/users/me` |
| Warehouse identity and administration | `warehouse-service` via gateway | `/api/warehouse/v1/warehouses` |
| Cabin registry, passport, status and comments | `asset-service` via gateway | `/api/asset/v1/rental-items` |
| Equipment catalog, balances, transfers and dispositions | `asset-service` via gateway | `/api/asset/v1/equipment` |

The gateway remains stateless. It owns no panel session, token exchange,
database, data aggregation or browser fallback.

## Authentication contract

- The browser requests `openid profile rwms.read rwms.write warehouse.read`.
  `warehouse.read` is required for the canonical warehouse list.
- The panel client remains `rwms-panel`; callbacks use
  `${window.location.origin}/auth/callback` and logout returns to the panel
  origin.
- The development `SYSTEM_ADMIN` marker and the special no-Bearer token are
  removed from application runtime. Tests may inject an explicit test-only
  provider or HTTP fixture; no fixture is selected by a production build.
- The panel validates the authenticated identity through `/api/users/me` and
  accepts only `principalType=USER`.

## Warehouse selection and settings

- `WarehouseProvider` obtains active warehouses using a real Bearer request
  and persists only the returned canonical UUID as a convenience preference.
  A stale preference selects the first returned active warehouse; it never
  resurrects `spb`, `msk` or a synthetic identifier. While unrelated mock-only
  features are mechanically migrated, their typed panel view may expose a
  deprecated `serviceId` compatibility alias equal to that same UUID. It is
  neither persisted nor a second identity and must not be used by the cutover
  flows.
- The new panel exposes `/settings/warehouses` to `SYSTEM_ADMIN` users. It
  provides list/search/filter, create, full versioned edit, deactivate and
  reactivation using the current panel's shadcn/grid conventions.
- Create sends a UUID `Idempotency-Key`; edit embeds `expectedVersion`; delete
  carries `expectedVersion` as the documented query parameter. A `409`
  refreshes canonical data and reports a conflict instead of overwriting newer
  state.
- Warehouse topology, bin/location graphs and `serviceId` stay out of this
  contract. They are not invented to keep mock content flows working.

## Cabin and equipment cutover rules

- A rental item or equipment record shown in production has a service-issued
  UUID. Mock `spb-1`, equipment-name references and browser quantities are
  never sent to the service.
- A panel field maps only when the asset OpenAPI proves its meaning. The
  existing cabin create/status/passport/comment/manual-note commands retain
  their documented expected-version/idempotency requirements.
- The equipment screen reads asset-owned catalog and ledger balances. It does
  not derive totals from cabin browser state or subscribe to browser storage.
- The UI may keep its responsive shell, filtering, sorting and card layout,
  but it must label unavailable server data honestly rather than inventing a
  value.
- Existing mock-only callers outside this cutover may retain a typed temporary
  adapter export solely to keep the panel buildable. Such an export fails
  before a mutation with a clear unavailable-operation error; it never reads
  or writes a browser business store and never reports a fabricated success.

## Explicitly excluded mock behaviour

The following are not silently mapped during the core cutover because the
current canonical API does not prove matching semantics:

- browser photo/data-URL persistence for cabins;
- browser dossier writes and cross-domain joins;
- warehouse topology or location-node movement;
- translating local equipment names into catalog UUIDs;
- creating initial physical stock for a new catalog item;
- the three-way browser return-case resolution workflow; and
- mock usage rows that require a cabin/balance read model not returned by the
  current public equipment API.

Those controls must either receive a separately approved additive asset/media
contract with service-owned tests or be unavailable in production. They must
never mutate only browser state after the cutover.

## Delivery slices and verification

1. **OIDC and warehouses:** remove runtime bypass, request `warehouse.read`,
   connect UUID selection, and add the new-panel warehouse settings route with
   unit tests for mapping, mutation requests and conflict handling.
2. **Cabin registry:** replace the mock registry/detail read path with the
   asset HTTP adapter, preserving the new panel layout and adding success,
   unauthorized, malformed-response and conflict coverage.
3. **Equipment:** replace catalog/balance reads and write-off reads with the
   asset HTTP adapter; remove browser-derived totals; add analogous tests.
4. **Gap closure:** before enabling any excluded command, approve its service
   contract, implement it in its owning service, validate Flyway/JPA and API
   compatibility, then expose it through the panel.

Affected panel checks are `npm run typecheck`, `npm run lint`, `npm run build`
and targeted Vitest/browser coverage. Backend checks include the owning Gradle
module's focused API/security/concurrency tests. A final review must confirm
that neither runtime code nor production configuration selects a browser mock.
