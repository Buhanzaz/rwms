# F3 Stateless API Gateway Foundation

Date: 2026-07-13

## Scope And Ownership

F3 adds one deployable: `api-gateway-service`. It is a stateless Spring Cloud
Gateway Server MVC edge on port `8088`. It owns browser-facing route and edge
security policy only. It has no database, JPA mapping, reviewed SQL release,
RabbitMQ participation, outbox/inbox, OAuth client store, token exchange, or
business aggregation.

F3 does not implement Warehouse Service. Its warehouse public prefix is only a
fail-closed reservation for the following stage.

## Route Cutover

- `/auth/**` removes exactly one prefix and forwards to `auth-service` while
  preserving discovery, JWKS, authorization, token, login, logout, cookie, and
  CSRF behavior.
- The exact `/auth/callback` route is panel-owned and is not captured by the
  auth catch-all route.
- `/api/task-board/**` maps to the downstream `/api/**` contract and preserves
  the Bearer token for defense-in-depth validation.
- `/api/warehouse/**` is reserved and unavailable until Warehouse Service is
  implemented.
- Internal client-credentials traffic continues to use private service
  addresses rather than the public edge.

The panel now derives the auth authority and task-board API base from the same
browser origin. Its Vite development server proxies `/auth` and `/api` to the
gateway and explicitly retains SPA ownership of the callback. Missing or unsafe
production gateway configuration fails closed; there is no direct-service or
browser-mock fallback in this cutover.

## Edge Trust Boundary

The gateway validates Bearer signature, expiry, public issuer, and audience for
protected API routes. Owning services validate the JWT and domain authorization
again. Auth protocol endpoints remain public because the authorization server
owns their protocol/session/CSRF policy.

All incoming forwarded metadata is stripped, including every
`X-Forwarded-*` variant. The auth route receives canonical scheme, host, port,
and `/auth` prefix synthesized from the validated public base URL. The ingress
must therefore preserve the configured public `Host`. Spring Boot 4.1 HTTP
client timeouts use the plural `spring.http.clients.*` configuration namespace.

Exact-origin CORS, canonical correlation IDs, sanitized upstream 502/504
Problem Details, public-host validation, default-deny routing, and production
HTTPS/configuration validation are covered by the gateway tests.

## Verification

- Gateway suite: 26 tests, zero failures and errors.
- Gateway `bootJar` passed; architecture/dependency checks found no forbidden
  database, JPA, migration, RabbitMQ, or OAuth-client dependency.
- Auth regression: 38 tests, zero failures/errors, one conditional operator
  skip.
- Task-board regression: 76 tests, zero failures/errors, one conditional
  restored-F0 skip.
- Panel focused tests: 34 passed; final typecheck, lint, and build passed.
- Independent panel and edge reviews were clean.
- A disposable live flow verified discovery issuer, PKCE admin login/callback,
  auth current-user API, task-board settings through gateway and downstream JWT
  validation, logout, Back behavior, zero console errors, and the 390 px mobile
  layout without overflow. Temporary processes and database were removed.

## Residual Deployment UNKNOWNs

The verified local edge contract does not select or prove production ingress,
TLS termination, network isolation, service discovery, backend-port closure,
secret injection, observability backend, or deployment platform. Those remain
deployment work. In particular, production ingress must preserve the validated
public `Host` and prevent public access to downstream service ports.

## Evidence

- `services/api-gateway-service/`
- `panel/src/lib/gateway-config.ts`
- `panel/src/lib/gateway-routes.ts`
- `panel/src/features/auth/`
- `panel/src/features/settings/task-board/api/`
- `panel/vite.config.ts`
