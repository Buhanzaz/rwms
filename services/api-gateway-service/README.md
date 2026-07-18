# RWMS API Gateway

Stateless Spring Cloud Gateway Server Web MVC edge for public OIDC and service
APIs. The gateway owns no database, JPA model, schema release, Kafka/RabbitMQ
client, token store, OAuth client, workflow, cache, or domain rule.

## Observability

Actuator exposes liveness/readiness health groups and Prometheus metrics at
`/actuator/health/liveness`, `/actuator/health/readiness`, and
`/actuator/prometheus`. Health probes are public transport-level infrastructure
endpoints; Prometheus scraping requires a valid RWMS Bearer JWT. Neither path
proxies to downstream services.

Micrometer uses OpenTelemetry with OTLP export and the default W3C HTTP trace
context. Configure the standard OpenTelemetry OTLP environment variables for
the collector and `API_GATEWAY_TRACING_SAMPLING_PROBABILITY` for sampling.
Domain correlation remains the separate `X-Correlation-Id` contract. Logs use
ECS JSON; request headers, Bearer tokens, payloads, entity IDs, and PII are not
added as custom tags or log fields.

## Local development

The browser-visible issuer is the Vite origin, because Vite proxies `/auth` to
the gateway. Start both `auth-service` and `task-board-service` with this same
explicit issuer so the authorization server emits, and the downstream resource
server accepts, the browser-visible value:

```powershell
$env:AUTH_ISSUER = "http://localhost:8080/auth"
```

Keep task-board's client-credentials token URI and worker-credentials URI on the
direct internal auth address (`http://localhost:9000`); those calls must not pass
through the gateway. Then start this module with the `dev` profile before Vite:

```powershell
$env:SPRING_PROFILES_ACTIVE = "dev"
.\gradlew.bat :services:api-gateway-service:bootRun
```

The dev defaults route auth to `9000`, task-board to `8081`, reserve warehouse
at `8083`, use the internal JWKS endpoint on `9000`, and expose the gateway on
`8088`. Start the panel/Vite server last on `8080`; it proxies `/auth` and `/api`
to `8088` while keeping `/auth/callback` in the SPA. Production has no localhost
or secret-bearing defaults and must provide every target, public issuer/base,
JWKS URI, and allowed panel origin.

Public mappings are:

- `/auth/{path}` -> auth-service `/{path}`; `/auth/callback` stays panel-owned;
- `/api/task-board/{path}` -> task-board-service `/api/{path}`;
- `/api/warehouse/{path}` -> the reserved W1 warehouse route.
- `/api/dossier/{path}` -> dossier-service unchanged; only public read paths are forwarded.

Internal service-to-service client-credentials calls never use this gateway.
All incoming `Forwarded` and `X-Forwarded-*` metadata is discarded. The gateway
synthesizes the auth-facing host, scheme, port and `/auth` prefix only from the
validated public base URI, so an ingress must preserve the configured public
`Host` rather than supply client-controlled forwarding metadata.
