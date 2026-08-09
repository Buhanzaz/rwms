# RWMS Panel

`panel/` is the React/TypeScript web panel for RWMS operational users. It is a
browser client, not a domain-service facade: business commands, authorization,
state transitions, and persistence remain with the owning backend services.

Russian version: [README.ru.md](README.ru.md).

## Public boundary and authentication

- The browser talks only to the public API gateway on its own origin:
  `/auth/**` for OAuth/OIDC and `/api/**` for public APIs. It must never be
  configured with an internal service address, `localhost` service port, or
  `/api/internal/**` route.
- Interactive sign-in uses the gateway's OIDC flow. The panel accepts the
  human `USER` principal; the gateway and owning service still enforce every
  role and warehouse-access check. Hiding a UI control is not authorization.
- The Vite proxy is a local-development convenience only. Production browser
  requests remain same-origin and must be served behind the public gateway.

## Product surface and state ownership

The router exposes the operational panel for warehouse/equipment, rentals and
orders, inventory, maintenance/acceptance, logistics, task board, assistant,
write-offs, and settings. The current user profile determines which warehouse
contexts and screens are usable.

- TanStack Query holds server reads; query keys include the relevant warehouse,
  entity, and filter inputs. The server stays authoritative.
- The selected warehouse is a non-authoritative local UI preference. It is
  revalidated against the profile returned after authentication.
- A verified authenticated subject and its bearer-grant revision own a fresh
  nested TanStack Query client. A logout, subject change, or grant change
  remounts the protected subtree so effect-owned realtime streams stop, cancels
  and removes protected query and mutation state, and clears media blob URLs
  before the next protected subtree mounts. The root client retains public
  offer and bootstrap state.
- Asset and media SSE streams are invalidation signals, not a second domain
  projection. Their handlers refresh or patch only affected query entries and
  evict media blobs whose revision changed; they do not globally clear the
  cache.
- The rental-item dossier consumes the complete canonical activity vocabulary.
  Maintenance repair transfer preparation and completion are shown in the
  repair history using the immutable warehouse snapshot returned by
  `dossier-service`; the exact boundary is the [dossier OpenAPI](../contracts/openapi/dossier-service.yaml)
  and the strict [panel activity model](src/features/rental-items/dossier/model/dossier-service.ts).
- The panel may combine independent public reads for a screen, but it must not
  orchestrate cross-service business workflows in the browser.

## Rental assistant, clients, and orders

- Assistant clarification cards are durable server data. Independent branches
  such as OSB and LDSP remain separately answerable in either order, while the
  panel renders only friendly question kinds and exact server-provided options.
  Search cards require both cabin type and finish, and exact follow-up filters
  come from the search result rather than browser guesses. See the
  [assistant OpenAPI](../contracts/openapi/assistant-service.yaml), the strict
  [assistant adapter](src/features/assistant/api/assistant-api.ts), and the
  [conversation page](src/features/assistant/pages/assistant-page.tsx).
- The assistant selection and its expiry are logistics-owned. Checkbox removal
  sends the complete remaining ID set with an idempotency key; an empty set
  releases the hold, while partial removal applies the authoritative renewed
  expiry. Streamed LLM removal is reconciled immediately and followed by the
  terminal conversation refetch. The browser never treats a local checkbox set
  as authoritative state.
- Rental navigation exposes `/clients/new`, `/clients`, and
  `/clients/:clientId`. The create form requires the client type, display name,
  and phone, additionally requires a contact person for a sole proprietor or
  legal entity, and visibly shows the authenticated responsible manager as a
  read-only server-owned value. The client grid is server-paginated; a client
  detail reads that client's orders and links to chat, warehouse selection, or
  manual order creation with only `clientId` as prefill. A truthful null phone
  on a historical client is displayed as not specified and is never backfilled
  in the browser. See the
  [logistics OpenAPI](../contracts/openapi/logistics-service.yaml) and the
  [client feature](src/features/clients/).
- Order create/edit screens persist the delivery address, latitude, longitude,
  editable contact phone, comment, and concrete acceptable delivery dates.
  Lists and details render those server projections. Order detail shows
  logistics-owned planned/actual delivery and return movements, then performs
  independent public dossier reads for selected cabins. Estimate or repair
  absence is shown as proven only after a complete dossier projection has been
  paged to exhaustion; partial, failed, or still-paged reads remain explicitly
  inconclusive. See the [order adapter](src/features/orders/api/orders-api.ts)
  and [dossier evidence](src/features/orders/components/order-unit-dossier-evidence.tsx).

## Failures, concurrency, and commands

The shared bearer client turns gateway Problem Details into `ApiError` with the
HTTP status and machine-readable code. A missing session, unreachable gateway,
or unsupported public operation is shown as an explicit failure; there is no
mock-success fallback.

Ordinary assistant HTTP requests and assistant turn streams use the same safe
Problem Details conversion. `401` remains an authentication-renewal/login
signal, `403` requires refreshed grants or user action, and `409` requires an
authoritative refetch/conflict path. A malformed Problem Details body still
returns a status-bearing `ApiError` with a safe fallback message.

For mutable operations, use the fencing mechanism specified by the canonical
OpenAPI operation (`expectedVersion`, ETag, or equivalent) and show a consistent
refresh/retry path for `409 Conflict`. Retried creates and effects require the
contract's idempotency key or stable external identifier. Do not infer command
success from a cache update or SSE notification.

## Local development and checks

Use a current Node.js LTS installation from this directory:

```bash
npm ci
npm run dev
npm run typecheck
npm test
npm run lint
npm run build
```

`npm run dev` listens on `localhost:8080`; its proxy target is for local
development only. Configure and test a real public gateway origin before an
integration or release claim.

## Release rules

Build only from the intended reviewed source scope. A release bundle must be
tested through same-origin `/auth/**` and `/api/**`, including first sign-in,
profile/workspace read, an authorized command, a `409` path where applicable,
and an invalidation-driven refresh. Do not publish an artifact built from a
different checkout or a mixed unreviewed worktree.

## Current limitations

- Protected server data and media blobs must remain inside the revision-scoped
  client boundary. Do not add a browser store, an unscoped object-URL cache, or
  a direct root-client write for protected state; those bypass the principal and
  grant fence.
- The assistant streaming path shares the normal bearer client's Problem
  Details conversion; do not add a second status or error-body policy.

## Source of truth

Public operation semantics live under `contracts/openapi/`; event semantics
live under `contracts/events/`. The gateway owns routing and token validation,
not business aggregation. Verify UI changes against the owning service and
canonical contract together.

The shared failure boundary is implemented in
[`src/lib/api-client.ts`](src/lib/api-client.ts) and the assistant stream uses it
from [`src/features/assistant/api/assistant-api.ts`](src/features/assistant/api/assistant-api.ts).
