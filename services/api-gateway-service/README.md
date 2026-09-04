# RWMS API Gateway

[Русская версия](README.ru.md)

`api-gateway-service` is the public, stateless edge of RWMS. It gives browser
and mobile clients one stable entry point for OIDC and public domain APIs while
keeping the service network private.

The gateway is deliberately a transport boundary, not a business service. It
does not own a database, JPA model, schema release, Kafka/RabbitMQ client,
token store, OAuth client, workflow, cache, or domain rule.

## Why it exists

Without an edge, every client would need to know every internal service address,
authentication detail, and network rule. That couples a released client to the
deployment topology and makes a service move, split, or security change much
more expensive.

The gateway solves the cross-cutting concerns that are genuinely shared at the
public boundary:

| Problem | Gateway responsibility | Result |
| --- | --- | --- |
| Clients must not reach private service hosts | Expose a small, allow-listed route table | Internal topology remains private and services can move independently. |
| Public calls need consistent access control | Validate Bearer JWTs locally and apply edge-level route policy | A request is rejected before it reaches a service when it is unauthenticated or targets a private path. |
| Browsers need a single same-origin API surface | Serve public `/auth/**` and `/api/**` paths | The panel does not contain internal origins or `localhost:<port>` configuration. |
| Proxies can carry spoofable forwarding headers | Discard incoming `Forwarded` and `X-Forwarded-*` headers; derive canonical auth metadata from configured public settings | The OIDC-facing host, scheme, port, and `/auth` prefix cannot be selected by a client. |
| Downstream outages should be understandable to clients | Convert connection and timeout failures to the RWMS Problem Details contract | Clients receive a safe, consistent `502` or `504`, without an internal destination or exception leak. |
| Long-lived or predictably slow operations differ from ordinary HTTP calls | Use dedicated bounded handlers for SSE, upload bytes, HTML-import commit, inventory outcome recalculation, and assistant turns | A normal proxy timeout does not incorrectly terminate supported long-running traffic. |
| Operators need one observable edge | Provide health/readiness, Prometheus metrics, tracing, and correlation IDs | Availability and request paths can be investigated without logging credentials or payloads. |

The domain service still owns its API meaning, authorization rules, state
transitions, and data. The canonical OpenAPI and event contracts remain the
source of truth for those concerns.

## What the gateway does not do

The following constraints are intentional architecture decisions:

- It does not aggregate responses from several services into a new business
  response. A client may combine independent public reads; a domain service
  owns an orchestration that must be consistent.
- It does not execute commands, run sagas, calculate business state, or retain
  workflow state.
- It does not store tokens or act as an OAuth client. It validates JWTs as a
  resource server using the private auth-service JWKS endpoint.
- It does not proxy internal service-to-service calls. Such calls use private
  addresses and service credentials directly.
- It does not make Kafka participation, a shared cache, or a database into an
  implicit gateway dependency.

Keeping this scope narrow is what makes the gateway disposable, horizontally
scalable, and safe to operate. It avoids creating a second, hidden monolith at
the network edge.

## How a request is handled

1. A panel, manager app, WorkerApp, DriverApp, or external public client calls the
   configured public host using `/auth/**` or `/api/**`.
2. The gateway validates the request host and removes client-supplied forwarding
   metadata. For the OIDC relay it reconstructs the public forwarding values
   from gateway configuration only.
3. Spring Security applies the edge policy: private namespaces are denied,
   explicitly public paths are permitted, the worker task stream requires its
   dedicated scope, and other public API routes require a valid Bearer JWT.
4. The route table accepts only a known public prefix and rejects unsafe path
   forms such as traversal or encoded slash/backslash variants.
5. The gateway forwards the request to the configured private target. Cookies
   are removed from domain API relays; OIDC routes retain the behavior required
   by the authorization flow.
6. The owning service processes the request. Its response remains the domain
   response; the gateway does not reshape it into an aggregate.
7. If the target cannot be reached or exceeds its transport deadline, the
   gateway returns a safe RWMS Problem Details response. Otherwise it relays the
   downstream response.

```text
Public client
    |
    v
public /auth/** or /api/**
    |
    v
API gateway: host/header validation, CORS, JWT, route policy, observability
    |
    +-- private auth-service
    +-- private domain services
    |
    v
Owning service: business authorization, invariant, transaction, data
```

## Public route policy

The executable route table in
[`GatewayRouteConfiguration`](src/main/java/dev/buhanzaz/rwms/gateway/config/GatewayRouteConfiguration.java)
is authoritative. The table below is a guide to its public shape, not a
replacement for the owning service's OpenAPI contract.

| Public entry point | Private target/path treatment | Important restriction |
| --- | --- | --- |
| `/auth/**` | auth-service; `/auth` is removed before forwarding | `/auth/callback` is panel-owned; `/auth/api/internal/**` is never forwarded. Customer CSRF bootstrap/registration remain anonymous at this stateless edge and are cookie-plus-header protected by auth-service after forwarding. |
| `/api/task-board/**` | task-board-service; external prefix becomes downstream `/api/**` | Internal paths are denied. WorkerApp and DriverApp event streams have separate SSE routes and exact scopes. |
| `/api/warehouse/**` | configured warehouse-service target, unchanged | Internal paths are denied; the current route is reserved for the W1 warehouse API. |
| `/api/asset/**` | asset-service, unchanged | Internal paths are denied. HTML-import commit uses a dedicated handler. |
| `/api/maintenance/**` | maintenance-service, unchanged | Internal paths are denied. |
| `/api/media/**` | media-service, unchanged | Internal and private paths are denied. Source/variant upload content and SSE use dedicated handlers. |
| `/api/inventory/**` | inventory-service, unchanged | Internal and private paths are denied. Completed-outcome recalculation uses a dedicated 60-second handler; every other inventory request keeps the ordinary timeout. |
| `/api/logistics/**` | logistics-service, unchanged | Internal and private paths are denied. Exact signed client-presentation, cabin-photo-presentation, and contractor-route capability operations are the only anonymous exceptions; CustomerApp routes remain authenticated and logistics enforces their exact CUSTOMER/client/scope combination. |
| `/api/assistant/**` | assistant-service, unchanged | Internal and private paths are denied. Conversation turns use a dedicated streaming handler. |
| `/api/dossier/**` | dossier-service, unchanged | `GET` only: dossier is a read projection and receives no public command route. |
| `/api/analytics/v1/**` | analytics-service; external prefix becomes downstream `/api/v1/**` | Authenticated `GET` only. |

The gateway remains stateless and does not keep a source-address rate-limit
store. It strips caller-supplied forwarding headers and supplies auth-service
with the canonical immediate TCP peer address; auth-service owns the durable
per-source and global registration budgets. Production ingress throttling
remains defence in depth. When a reverse proxy is introduced, its trusted-peer
boundary must be configured explicitly or multiple users behind that proxy
will intentionally share one immediate-peer budget. CSRF protection prevents
cross-site submission but is not abuse throttling.

The dedicated routes are intentionally more specific than their general service
routes:

- `GET /api/task-board/worker/v1/events`, `GET /api/task-board/driver/v1/events`,
  `GET /api/asset/v1/events`, and `GET /api/media/v1/events` use the bounded
  asynchronous SSE proxy.
- `POST /api/asset/v1/html-imports/*/commit` and
  both `PUT /api/media/v1/upload-sessions/*/content` and
  `PUT /api/media/v1/upload-sessions/*/variants/*/content` use specialized
  bounded forwarding for supported long-running traffic. Paths, bearer and
  idempotency headers, content bytes, and downstream responses pass through
  unchanged; cookies are removed, and neither MinIO addresses nor credentials
  are exposed.
- `POST /api/inventory/v1/sessions/*/outcome/recalculate` uses an isolated
  60-second downstream read deadline. The exact command is excluded from the
  generic inventory route, while its path, authorization and idempotency
  headers are forwarded unchanged and cookies are removed.
- `POST /api/assistant/v1/conversations/*/turns` uses the assistant streaming
  proxy so a valid streamed answer does not inherit the ordinary read deadline.
- Exact contractor-route capability paths allow anonymous route reads, entry
  actions, bounded evidence uploads, and scoped image reads. They strip cookies
  and authorization, preserve idempotency plus evidence checksum/capture-time
  headers, and do not authorize a broader `/api/logistics/public/**` subtree.
  Before proxying evidence bytes, an exact-path servlet boundary requires a
  declared length and accepts only JPEG up to 15 MiB or WebP up to 1 MiB;
  it replaces any caller-supplied private relay length with that validated
  value because the MVC proxy uses chunked downstream transfer. Logistics
  requires this relay assertion and repeats the check against the actual body
  and checksum.

An endpoint belongs in this route table only after its public contract and
owning service are clear. A new route must never expose `/internal/**` or
`/private/**` merely because a client needs a capability; the owning service
must define a public API for it.

## Security and trust boundary

[`GatewaySecurityConfiguration`](src/main/java/dev/buhanzaz/rwms/gateway/config/GatewaySecurityConfiguration.java)
implements the following policy:

- The service is stateless: sessions, request caching, and CSRF state are not
  used as a substitute for token-based API security.
- JWT timestamp, issuer, and audience are validated locally. The JWKS is read
  from the private auth-service target, never through the browser-visible
  gateway route.
- `/api/**` is authenticated by default. Private routes are denied explicitly;
  public OIDC, health, Android App Links, and the documented signed logistics
  capability operations are narrow exceptions.
- The WorkerApp task-board namespace requires exactly `SCOPE_worker.tasks` and
  the DriverApp namespace requires exactly `SCOPE_driver.tasks` at the edge;
  neither native token crosses into the other surface. Services still make
  their own domain authorization decisions.
- CORS uses an explicit configured origin allow-list, an explicit method/header
  set, and credentials where needed. Wildcard origins are rejected by startup
  safety validation.
- Production validation applies one policy to every downstream: each target is
  a path-free HTTP(S) origin, cannot reuse the public gateway host, and cannot
  use localhost or a loopback address. Public values must use HTTPS.

This division is important: the edge authenticates and protects the public
surface, while the owner service authorizes the business operation. Copying all
domain authorization into the gateway would produce two competing policy
implementations and make safe evolution harder.

## Streaming, failures, and observability

SSE is transport-only at the gateway. The gateway limits concurrent streams,
uses asynchronous I/O, filters forwarded headers, and cancels the upstream
subscription when the client disconnects. Event meaning, replay, ordering, and
projection state remain owned by the producer service.

For ordinary proxy calls, connectivity failures are mapped to the shared
Problem Details shape: unavailable targets become `502 Bad Gateway` and
timeouts become `504 Gateway Timeout`. The response intentionally omits an
upstream URL, exception message, and request body.

The gateway exposes:

- `/actuator/health/liveness` and `/actuator/health/readiness` as public
  transport-level health probes. They do not proxy to downstream services.
- `/actuator/prometheus` for authenticated RWMS Bearer JWT scraping.
- Micrometer/OpenTelemetry tracing with standard W3C HTTP trace context and
  `X-Correlation-Id` as the separate RWMS correlation contract.
- ECS JSON logs without custom fields for request headers, Bearer tokens,
  payloads, entity IDs, or PII.

## Why this design is preferable

| Alternative | Why it causes trouble | Chosen approach |
| --- | --- | --- |
| Let clients call every service directly | Leaks topology, expands the public attack surface, and ties releases to service addresses. | One stable public gateway with private upstream targets. |
| Turn the gateway into a business aggregator | Creates cross-domain coupling and a hidden owner for workflows, retries, and failures. | Keep it transport-only; let an owning domain service orchestrate business work. |
| Trust forwarded headers from clients or arbitrary ingress | A caller can influence public URL reconstruction and undermine OIDC assumptions. | Strip them and derive canonical values from validated configuration. |
| Use one generic proxy policy for all traffic | SSE, uploads, and streamed assistant responses have different lifetime and resource characteristics. | Route known long-lived traffic through narrow, bounded specialized handlers. |
| Put authorization only at the edge | A private call could bypass the gateway and domain rules drift from the actual aggregate owner. | Apply coarse public-edge policy here and business authorization in each owner service. |
| Make the gateway stateful | Adds sticky sessions, recovery state, and a database to the most exposed component. | Keep it stateless and scale it independently. |

The goal is not to put more intelligence at the edge. The goal is a predictable,
secure public boundary whose behavior can change independently of domain state.

## Local development

The browser-visible issuer is the Vite origin, because Vite proxies `/auth` to
the gateway. Start both `auth-service` and `task-board-service` with this same
explicit issuer so the authorization server emits, and the downstream resource
server accepts, the browser-visible value:

```powershell
$env:AUTH_ISSUER = "http://localhost:8080/auth"
```

Keep task-board's client-credentials token URI and worker-credentials URI on
the direct internal auth address (`http://localhost:9000`); those calls must
not pass through the gateway. The gateway derives its JWKS endpoint from that
same private auth target, never from the public `/auth` route. Then start this
module with the `dev` profile before Vite:

```powershell
$env:SPRING_PROFILES_ACTIVE = "dev"
.\gradlew.bat :services:api-gateway-service:bootRun
```

Development defaults route auth to `9000`, task-board to `8081`, reserve the
warehouse target at `8083`, derive JWKS from the internal auth target on
`9000`, and expose the gateway on `8088`. Start the panel/Vite server last on
`8080`; it proxies `/auth` and `/api` to `8088` while keeping `/auth/callback`
in the SPA. Production has no localhost or secret-bearing defaults and must
provide every target, public issuer/base URI, and allowed panel origin.

## Executable route and security parity

[`GatewayRouteSecurityParityTest`](src/test/java/dev/buhanzaz/rwms/gateway/config/GatewayRouteSecurityParityTest.java)
parses every canonical service OpenAPI and evaluates the real ordered router functions. Its domain
inventory contains every canonical public `/api/**` operation; auth-service operations delegated
under `/auth/**` and media-service-local `/health/**` probes are explicit separate partitions. The
gate verifies owner-specific task-board and analytics path rewrites, dedicated SSE/upload/import/
inventory-recalculation/assistant route precedence, unambiguous exclusion of the recalculation
command from the generic inventory route, zero routing for every canonical internal operation and reserved
private/internal alias, and the real edge security classification. All public domain operations
require Bearer authentication except the explicitly classified signed logistics capability
operations. The delegated auth partition separately validates both Bearer and the exact
`csrfCookie + csrfHeader` OpenAPI requirement; CSRF remains an auth-service check rather than
duplicated state in the gateway.

Run the focused gate from the repository root:

```bash
bash ./gradlew :services:api-gateway-service:test --tests 'dev.buhanzaz.rwms.gateway.config.GatewayRouteSecurityParityTest'
```

## Safe change rules

When changing the gateway:

1. Start with the owning service's canonical OpenAPI contract. Do not invent a
   gateway-only public operation.
2. Add or change the explicit public route, then cover its positive and
   rejected/private path cases with focused gateway tests.
3. Preserve the public/private split, local JWT validation, correlation, and
   Problem Details behavior.
4. Keep domain commands, aggregation, persistence, Kafka, and business retry
   decisions out of this module.
5. For an SSE or long-lived route, specify ownership of replay and recovery in
   the producer contract; the gateway may relay the stream but must not retain
   its state.

Useful verification commands from the repository root are:

```bash
bash ./gradlew :services:api-gateway-service:test
bash ./gradlew :services:api-gateway-service:javadoc
```

## Primary implementation references

- [Route table](src/main/java/dev/buhanzaz/rwms/gateway/config/GatewayRouteConfiguration.java)
- [Edge security and CORS policy](src/main/java/dev/buhanzaz/rwms/gateway/config/GatewaySecurityConfiguration.java)
- [Production configuration safety checks](src/main/java/dev/buhanzaz/rwms/gateway/config/GatewayProductionSafetyValidator.java)
- [Forwarded-header and public-host handling](src/main/java/dev/buhanzaz/rwms/gateway/config/GatewayWebConfiguration.java)
- [SSE transport handler](src/main/java/dev/buhanzaz/rwms/gateway/config/SseProxyHandler.java)
- [Gateway architecture boundary](../../docs/project-knowledge/architecture.md)
- [Contract ownership and change procedure](../../docs/project-knowledge/contracts.md)
