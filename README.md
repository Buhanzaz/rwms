# RWMS

[Русская версия](README.ru.md)

RWMS is a warehouse and rental-operations management product. It coordinates
warehouse identity and access, cabins and equipment, operational queues,
maintenance, inventory, logistics, media, read projections, analytics, and an
assistant through independently owned services and three active clients.

This repository is active product code. Historical plans, browser mocks, old
database exports, and the retired panel are evidence only; they are not runtime
sources of truth and do not define the current release sequence.

## Why the system is split this way

RWMS has several workflows that change at different rates and have different
consistency boundaries. Each domain therefore owns its state and transitions,
while clients see one public edge:

```text
panel / manager Android / worker Android
                    |
                    v
          stateless API gateway
                    |
       +------------+-------------+
       |                          |
       v                          v
  auth-service             public domain APIs
                                  |
                         service-owned PostgreSQL
                                  |
                     transactional outbox / Kafka
                                  |
                   other owners and read projections

media-service additionally owns private MinIO objects and their derivatives.
```

The split protects four important properties:

- one service owns each business invariant and status;
- every stateful service owns one PostgreSQL database and Flyway history;
- cross-domain communication uses versioned HTTP or event contracts rather
  than shared tables or Java domain models;
- the gateway remains a transport and security boundary, not a hidden business
  orchestrator.

## Active components

| Component | Runtime shape | Responsibility |
| --- | --- | --- |
| [`panel/`](panel/) | React/TypeScript web client | Main manager and operations panel |
| [`app/`](app/) | Android manager client | Mobile manager workflows |
| [`worker-app/`](worker-app/) | Android worker client | Worker authentication, assignments, task execution, and media capture |
| [`worker-download-site/`](worker-download-site/) | Independent public OpenNext/Sites release surface | [Signed WorkerApp 0.1.14 APK download page](https://rwms-worker-download.lebaagatabx.chatgpt.site) |
| [`api-gateway-service`](services/api-gateway-service/) | Stateless Spring edge | Public `/auth/**` and `/api/**` routing, JWT edge policy, bounded streaming, and transport failures |
| [`auth-service`](services/auth-service/) | Stateful Spring service | OIDC/OAuth2, users, workers, roles, clients, and warehouse grants |
| [`warehouse-service`](services/warehouse-service/) | Stateful Spring service | Warehouse identity, lifecycle, metadata, and effective-dated timezone |
| [`asset-service`](services/asset-service/) | Stateful Spring service | Cabins, equipment, balances, holds, leases, and asset facts |
| [`task-board-service`](services/task-board-service/) | Stateful Spring service | Queues, workforce, assignments, task board, and worker stream |
| [`maintenance-service`](services/maintenance-service/) | Stateful Spring service | Catalog, estimates, repairs, acceptance, and write-off decisions |
| [`inventory-service`](services/inventory-service/) | Stateful Spring service | Inventory sessions, findings, reconciliation, completion, and publication |
| [`logistics-service`](services/logistics-service/) | Stateful Spring service | Rental inquiries, returns, shipments, transfers, drivers, and logistics sagas |
| [`media-service`](services/media-service/) | Stateful Go service | Media metadata, uploads, originals, derivatives, and private MinIO access |
| [`dossier-service`](services/dossier-service/) | Stateful Spring read model | Cross-domain cabin activity projection |
| [`analytics-service`](services/analytics-service/) | Stateful Spring read model | KPI and dashboard projections |
| [`assistant-service`](services/assistant-service/) | Stateful Spring service | Assistant conversations and tool-call history; logistics retains rental ownership |
| [`platform/`](platform/) | Java libraries and policy tests | Framework-neutral technical contracts, shared Spring infrastructure, and architecture checks |

The authoritative ownership table and boundary evidence live in
[`docs/project-knowledge/architecture.md`](docs/project-knowledge/architecture.md).

## How requests and facts move

1. An interactive client obtains an OIDC session using Authorization Code with
   PKCE and calls only the configured public gateway.
2. The gateway validates the public route and Bearer token, strips unsafe
   forwarding metadata, and forwards to one owning service.
3. The owning service repeats resource-server validation, applies warehouse
   and domain authorization, checks optimistic concurrency or idempotency, and
   commits its own transaction.
4. When other domains need the resulting fact, the same transaction records a
   versioned outbox item. A bounded relay publishes it to Kafka.
5. Consumers deduplicate through an inbox, enforce aggregate ordering and
   version policy, and update only their own state or read projection.
6. SSE messages are transport or invalidation signals unless their contract
   explicitly declares a complete projection. Clients refresh only affected
   cached data.

Cross-service workflows are persisted by their initiating domain as sagas or
reconciliation work. The browser is never the consistency coordinator, Kafka
is not the database, and there is no cross-service two-phase commit.

## Sources of truth

Use repository authority in this order:

1. the current product request and [`AGENTS.md`](AGENTS.md);
2. canonical OpenAPI and event contracts in [`contracts/`](contracts/);
3. the owning service's domain code, Flyway migrations, and tests;
4. the maintained map in
   [`docs/project-knowledge/`](docs/project-knowledge/);
5. historical plans and old implementations as evidence only.

Do not resolve a conflict about ownership, identity, status, money, time, or
destructive data handling by guessing. Record the exact evidence and obtain the
smallest product decision needed.

## Repository layout

| Path | Purpose |
| --- | --- |
| [`contracts/openapi/`](contracts/openapi/) | Canonical public and internal HTTP boundaries |
| [`contracts/events/`](contracts/events/) | Canonical event envelopes, schemas, and AsyncAPI descriptions |
| [`services/`](services/) | Independently owned Spring and Go deployables |
| [`platform/`](platform/) | Technical Java libraries and architecture-policy tests |
| [`panel/`](panel/) | Primary web panel |
| [`app/`](app/) | Manager Android app |
| [`worker-app/`](worker-app/) | Worker Android app |
| [`worker-download-site/`](worker-download-site/) | Public WorkerApp 0.1.14 APK download page |
| [`docs/project-knowledge/`](docs/project-knowledge/) | Maintained architecture and domain navigation layer |
| [`docs/reviews/`](docs/reviews/) | Evidence-backed audits and remediation plans |
| [`compose.yaml`](compose.yaml) | Local/test dependencies only, never production orchestration |

## Local development

The root Compose file starts only isolated development dependencies: one
PostgreSQL database per stateful service, Kafka, MinIO, and an explicitly
separate media-compatibility RabbitMQ profile. It does not run or deploy the
product services.

```bash
docker compose --profile core up -d
```

Start only the services needed for a flow and use their `dev` profiles and
READMEs for environment variables. A typical browser flow starts auth, the
owning domain services, the gateway, and finally Vite:

```bash
bash ./gradlew :services:auth-service:bootRun --args='--spring.profiles.active=dev'
bash ./gradlew :services:api-gateway-service:bootRun --args='--spring.profiles.active=dev'
npm --prefix panel run dev
```

Do not copy development credentials, loopback URLs, topic auto-creation, or
Compose settings into a production environment.

## Verification

Run the narrowest checks that cover a change. Useful repository-wide policy
gates are:

```bash
bash ./gradlew verifyApprovedDependencyVersions
bash ./gradlew verifyCanonicalContracts
bash ./gradlew :platform:architecture-tests:test
npm --prefix panel run typecheck
```

Each service or client README lists its focused commands. Contract changes also
require the root canonical-contract gate and focused producer/consumer
compatibility checks; Android contract changes require building and testing the
exact APK.

## Safe change rules

1. Identify the owning service and canonical contract before changing a flow.
2. Trace all active clients, gateway routes, producers, consumers, persistence,
   cache invalidation, authorization, and failure recovery.
3. Change a boundary and every active producer/consumer together.
4. Keep state and orchestration in the owner; do not create browser or gateway
   sagas.
5. Preserve Flyway immutability, outbox/inbox guarantees, optimistic fencing,
   idempotency, and warehouse isolation.
6. Remove a replaced runtime path in the same scoped change, but never delete
   live data or another task's uncommitted work without explicit authority.
7. Update the maintained knowledge map when ownership, architecture, a durable
   invariant, or a contract changes.

## Architecture health

The current full-project audit, confirmed weaknesses, evidence, and sequenced
remediation backlog are recorded in
[`docs/reviews/20260808-full-architecture-audit.md`](docs/reviews/20260808-full-architecture-audit.md).
The structural god-class inventory, decomposition metrics, preserved behavior
boundary, and verification evidence are maintained in
[`docs/reviews/20260808-god-class-decomposition.md`](docs/reviews/20260808-god-class-decomposition.md).
The documentation contract for future source and README changes is
[`docs/project-knowledge/documentation-standard.md`](docs/project-knowledge/documentation-standard.md).

## Primary references

- [Current architecture](docs/project-knowledge/architecture.md)
- [Current component catalog](docs/project-knowledge/service-catalog.md)
- [End-to-end runtime flows](docs/project-knowledge/runtime-flows.md)
- [Complete cabin lifecycle and variants](docs/project-knowledge/cabin-lifecycle.md)
- [Confirmed domain logic](docs/project-knowledge/domain-logic.md)
- [Contract index and evolution rules](docs/project-knowledge/contracts.md)
- [Open questions](docs/project-knowledge/open-questions.md)
- [Service registry](services/README.md)
- [Contributor and workspace safety rules](AGENTS.md)
