# `warehouse-service` API

Status: reference guide for the current API. The canonical machine-readable
specification is the [OpenAPI contract](../../contracts/openapi/warehouse-service.yaml);
when this guide disagrees with it or with the service code, the contract and
code take precedence.

## Why this service exists

`warehouse-service` is the sole owner of warehouse identity, display metadata,
lifecycle, and timezone history. Other services retain a warehouse UUID and
request only the projection they need. They neither read its database nor
mutate warehouse state directly.

That solves four operational problems:

- A warehouse cannot acquire different names, states, or timezones in `asset`,
  `inventory`, `logistics`, `maintenance`, and `task-board`.
- New inbound work cannot start at a warehouse that is closing, while already
  admitted outbound work can finish safely.
- A timezone correction cannot retroactively change the local date or time of
  an operation or report that already happened.
- Retrying an HTTP command or a Kafka delivery cannot create a second
  warehouse, confirmation, or published fact.

## Why it is designed this way

A warehouse is a shared directory entry, not a shared mutable object. A single
owner avoids distributed transactions across services and prevents directory
state from drifting apart.

- **UUID rather than name.** `id` is the stable integration reference. A name
  is for people, is normalized and unique, but is mutable and therefore not a
  foreign-key substitute.
- **One-way lifecycle.** Transitions are only
  `ACTIVE -> DRAINING -> INACTIVE`. This prevents reopening a warehouse with
  undrained obligations.
- **Directional admission.** The compatibility field `active` cannot express
  outbound work during `DRAINING`; operation owners ask the dedicated private
  admission endpoint instead.
- **Every owner confirms readiness.** `asset-service`, `inventory-service`,
  `logistics-service`, `maintenance-service`, and `task-board-service` each
  write immutable evidence before `INACTIVE`. A timeout, empty cache, or a
  missed event is never evidence of readiness.
- **Effective-dated timezones.** Before the first operation a timezone may be
  corrected immediately. Afterwards the service appends a future
  `effectiveFrom` decision, so a historical operation always resolves the zone
  valid at its own instant.
- **Transactional outbox.** The warehouse mutation and its event record are
  committed in one local transaction. Kafka is at-least-once transport and
  consumers deduplicate on `eventId`.

## How to connect

Interactive clients (the panel and both Android applications) use the public
gateway only: same-origin `/api/**` in the browser and its public address on
mobile. Do not put `/api/internal/**` into a client, expose it through the
gateway, or call service ports directly.

The private API is exclusively for authenticated service-to-service calls. It
is deliberately split by recipient: that is not URL decoration, but a limit on
both token authority and returned data.

All calls use `Authorization: Bearer <JWT>`. Token values and runtime URLs are
not part of this documentation.

## Warehouse model

| Field            | Meaning                                                                                                                          |
| ---------------- | -------------------------------------------------------------------------------------------------------------------------------- |
| `id`             | Immutable UUID and the only integration identity.                                                                                |
| `version`        | Aggregate version for optimistic concurrency. Mutating metadata/lifecycle commands send it as `expectedVersion`.                 |
| `name`           | Display name. Whitespace is collapsed, the value is trimmed, then Unicode-aware lower-case normalization is used for uniqueness. |
| `timeZone`       | Currently effective canonical IANA zone, for example `Europe/Moscow`. Use the private as-of read for a historical operation.     |
| `active`         | Compatibility projection: `true` only in `ACTIVE`; it is not a directional admission decision.                                   |
| `lifecycleState` | `ACTIVE`, `DRAINING`, or terminal `INACTIVE`.                                                                                    |
| `sortOrder`      | Optional non-negative directory order. Results sort by `sortOrder`, then `name`, then UUID.                                      |

### Lifecycle rules

| State      | New inbound work | Outbound draining work | Next transition                     |
| ---------- | ---------------- | ---------------------- | ----------------------------------- |
| `ACTIVE`   | allowed          | allowed                | `DRAINING`                          |
| `DRAINING` | denied           | allowed                | `INACTIVE` after five confirmations |
| `INACTIVE` | denied           | denied                 | none                                |

The shutdown sequence is:

1. An administrator moves the warehouse to `DRAINING` with the current
   `expectedVersion`.
2. Every owner reads its durable `readiness-work` backlog, blocks new local
   work, drains its blockers, and confirms readiness.
3. The administrator completes `INACTIVE` with the new version. The service
   returns `409` while any owner confirmation is missing.

The pull backlog is intentional even when lifecycle events are published to
Kafka. After a restart or missed notification an owner can recover correctly,
without assuming that a notification will eventually be redelivered.

## Authorization

For private routes, “exactly one scope” means the token contains only the
listed scope, not a broad collection of permissions.

| Surface                                          | Requirement                                                                                                                                          |
| ------------------------------------------------ | ---------------------------------------------------------------------------------------------------------------------------------------------------- |
| Public directory read                            | `principal_type=USER` and `warehouse.read`. This is an approved global directory; `warehouse_access` grants do not filter it.                        |
| Create, replace, lifecycle, timezone             | `USER`, `rwms.write`, and global role exactly `SYSTEM_ADMIN`.                                                                                        |
| Outbox recovery                                  | `USER`, exactly `rwms.write`, and `SYSTEM_ADMIN` or `WMS_ADMIN`.                                                                                     |
| Auth existence                                   | `SERVICE`, `client_id=auth-service`, exactly `warehouse.read`.                                                                                       |
| Asset existence                                  | `SERVICE`, `client_id=asset-service`, exactly `warehouse.read`.                                                                                      |
| Inventory metadata                               | `SERVICE`, both `sub` and `client_id` are `inventory-service`, exactly `warehouse.read`.                                                             |
| Logistics identity                               | `SERVICE`, both `sub` and `client_id` are `logistics-service`, exactly `warehouse.logistics`.                                                        |
| Timezone, admission, readiness work/confirmation | A recognized lifecycle owner with matching `sub`/`client_id`: asset, inventory, logistics, maintenance, or task-board; route-specific scope applies. |
| Operation mark                                   | A recognized operation owner with matching `sub`/`client_id`: asset, inventory, logistics, or maintenance; exactly `warehouse.operation.mark`.       |

## Public API

Prefix: `/api/warehouse/v1`. Full fields and JSON Schema are in the
[OpenAPI contract](../../contracts/openapi/warehouse-service.yaml).

| Method and path                                | Purpose                                          | Important rules                                                                                                                                                                                    |
| ---------------------------------------------- | ------------------------------------------------ | -------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| `GET /warehouses`                              | Directory of `ACTIVE` and `DRAINING` warehouses. | `includeInactive=true` is `SYSTEM_ADMIN` only.                                                                                                                                                     |
| `POST /warehouses`                             | Create a warehouse.                              | UUID header `Idempotency-Key` is required. An exact retry returns `201` and `Idempotency-Replayed: true`; the same key with a different body returns `409`.                                        |
| `GET /warehouses/{id}`                         | Resolve by UUID.                                 | Also returns `INACTIVE` so historical references remain resolvable.                                                                                                                                |
| `PUT /warehouses/{id}`                         | Fully replace mutable metadata.                  | Requires `expectedVersion`; cannot change lifecycle. A timezone is directly correctable only before the first operation mark. A semantic no-op does not increment the version or publish an event. |
| `POST /warehouses/{id}/draining`               | Start draining.                                  | Requires `expectedVersion`; permitted only from `ACTIVE`.                                                                                                                                          |
| `POST /warehouses/{id}/inactivation`           | Complete deactivation.                           | Requires `expectedVersion` and all five immutable readiness confirmations.                                                                                                                         |
| `POST /warehouses/{id}/time-zone-changes`      | Schedule a timezone.                             | Requires `expectedVersion`, an existing operation mark, and a future `effectiveFrom`.                                                                                                              |
| `POST /admin/outbox-events/{eventId}/recovery` | Return a terminal outbox event to the relay.     | Only `DLT`/`QUARANTINED`, only the aggregate's first unpublished event, with `expectedReviewVersion` and a reason. The envelope is never edited.                                                   |

### Create a warehouse

```http
POST /api/warehouse/v1/warehouses
Authorization: Bearer <JWT>
Idempotency-Key: 7492f17a-800b-4f4c-8b43-f9e4e7d6a064
Content-Type: application/json

{
  "name": "North warehouse",
  "city": "Saint Petersburg",
  "address": "Example Street, 1",
  "timeZone": "Europe/Moscow",
  "sortOrder": 10
}
```

Success is `201 Created` with a `Warehouse` object. Retain the returned `id`
and `version`; do not build integrations on `name`.

### Replace metadata and fence concurrent changes

```http
PUT /api/warehouse/v1/warehouses/{id}
Authorization: Bearer <JWT>
Content-Type: application/json

{
  "expectedVersion": 4,
  "name": "North warehouse",
  "city": "Saint Petersburg",
  "address": "new address",
  "timeZone": "Europe/Moscow",
  "sortOrder": 20
}
```

On `409`, read the warehouse again, surface the conflict to the user, and
retry deliberately with a newly chosen version. Do not silently substitute the
latest version for a command based on a stale decision.

### Schedule a timezone

```http
POST /api/warehouse/v1/warehouses/{id}/time-zone-changes
Authorization: Bearer <JWT>
Content-Type: application/json

{
  "expectedVersion": 7,
  "timeZone": "Asia/Yekaterinburg",
  "effectiveFrom": "2026-11-01T00:00:00Z"
}
```

This does not change `Warehouse.timeZone` until `effectiveFrom`. For an
existing operation, another service asks for the timezone at the operation's
exact timestamp instead of using the current directory value.

## Private API

Prefix: `/api/internal/warehouse/v1`. Every route below is private; neither a
browser nor an Android client calls it.

| Method and path                                     | Recipient         | Returned value or effect                                                                                               |
| --------------------------------------------------- | ----------------- | ---------------------------------------------------------------------------------------------------------------------- |
| `GET /warehouses/{id}/existence`                    | auth-service      | Minimal `id`, `version`, and `active` validation projection.                                                           |
| `GET /warehouses/asset/{id}/existence`              | asset-service     | Separate legacy inbound-admission projection. Use the admission endpoint for outbound work in `DRAINING`.              |
| `GET /warehouses/inventory/{id}/metadata`           | inventory-service | Only active `id`, `version`, and `timeZone`; `DRAINING` and `INACTIVE` are hidden as `404`.                            |
| `GET /warehouses/logistics/{id}/identity`           | logistics-service | Identity without topology or location data; historical identity remains readable.                                      |
| `GET /warehouses/logistics`                         | logistics-service | Only `ACTIVE` identities in canonical order for availability facets.                                                   |
| `GET /warehouses/{id}/time-zone?at=...`             | Lifecycle owners  | IANA zone effective at one instant, including historical and scheduled changes. Scope: `warehouse.timezone.read`.      |
| `POST /warehouses/{id}/operation-marks`             | Operation owners  | Persist operation evidence; `operationId` is the idempotency identity. Scope: `warehouse.operation.mark`.              |
| `GET /warehouses/{id}/admission?direction=INCOMING` | Lifecycle owners  | Exact admission decision; `INCOMING` and `OUTGOING` are valid. Scope: `warehouse.lifecycle.read`.                      |
| `POST /warehouses/{id}/lifecycle-readiness`         | Lifecycle owners  | Immutable caller readiness confirmation. Scope: `warehouse.lifecycle.confirm`.                                         |
| `GET /lifecycle/readiness-work?after=&limit=`       | Lifecycle owners  | Keyset backlog of unconfirmed `DRAINING` warehouses. Scope: `warehouse.lifecycle.read`; `limit` is 1–500, default 100. |

### Safely perform an internal operation

1. Obtain a service JWT with exactly the needed scope and matching
   `sub`/`client_id`.
2. Before creating a local fact, call `admission` with the correct direction.
   `active=false` does not by itself mean that outbound work is forbidden.
3. In the same local transaction that writes the first warehouse-bound business
   fact, persist a durable intent/guard and call or recover `operation-marks`
   with a stable `operationId`.
4. Once draining starts, regularly read `readiness-work`, drain local blockers,
   and call `lifecycle-readiness` with the observed version. An exact replay is
   safe.

An owner must not hold its database transaction open while doing a synchronous
HTTP call to warehouse-service. Use short local transactions, a durable
recovery intent, and retries with the same identity.

## Errors, concurrency, and retries

Errors are `application/problem+json` and contain at least `status` and
`code`.

| Status | Expected when                                                                                               | Client action                                                                  |
| ------ | ----------------------------------------------------------------------------------------------------------- | ------------------------------------------------------------------------------ |
| `400`  | UUID, timezone, timestamp, required field, or body format is invalid.                                       | Correct the command; do not blindly retry it.                                  |
| `401`  | Bearer token is absent or invalid.                                                                          | Obtain a valid token.                                                          |
| `403`  | Principal type, role, scope, or service identity is insufficient.                                           | Use the contract-specific credential; do not broaden a token speculatively.    |
| `404`  | Warehouse is unknown or intentionally hidden by an active-only private projection.                          | Verify UUID and the appropriate endpoint; do not create a replacement by name. |
| `409`  | Version is stale, normalized name is duplicate, lifecycle rule fails, or idempotency/review replay differs. | Read current state and retry only deliberately.                                |

Creation uses `Idempotency-Key` scoped to the subject. Metadata and lifecycle
commands use `expectedVersion`. Outbox recovery uses a separate
`expectedReviewVersion`. These mechanisms address different races; none is a
replacement for another.

## Events

After a mutation the service adds a fact to its transactional outbox, and the
bounded relay publishes it to Kafka topic `rwms.warehouse.warehouse.v1`.

- Event types are `warehouse.warehouse.created.v1`,
  `warehouse.warehouse.changed.v1`, and
  `warehouse.warehouse.deactivated.v1`.
- The Kafka key is `aggregateId`; `eventId` is the consumer-deduplication
  identity. At-least-once delivery is expected.
- The payload intentionally excludes name, city, and address. This limits
  replicated data and keeps warehouse-service as the owner of the directory
  projection.
- A changed event can carry an immutable `timeZoneDecision` with a future
  `effectiveFrom`; a consumer with calendar semantics retains it and resolves
  the zone at the operation instant.
- A terminal event is not “fixed in Kafka”. An administrator can only review
  and requeue the unchanged validated envelope through the recovery API.

Schemas: [AsyncAPI](../../contracts/events/warehouse-events.yaml) and
[v1 JSON Schema](../../contracts/events/warehouse/warehouse-events-v1.schema.json).

## What the API intentionally does not do

- It has no `DELETE /warehouses` and cannot return a warehouse to `ACTIVE`.
- It does not accept a warehouse name as a foreign key or integration identity.
- It gives no UI access to private routes.
- It does not accept `readinessOwner` or `operationSource` in a request body:
  those values are inferred from the service credential.
- It does not rewrite historical timezone decisions or an outbox envelope.
- It does not make the gateway a business aggregator or workflow store.

Those constraints make the API predictable during retries, restarts, and
parallel work across services — exactly the situations where a conventional
CRUD directory commonly loses consistency.

## Authoritative sources

- [OpenAPI contract](../../contracts/openapi/warehouse-service.yaml)
- [Warehouse application service](../../services/warehouse-service/src/main/java/dev/buhanzaz/rwms/warehouse/service/WarehouseService.java)
- [Authorization boundary](../../services/warehouse-service/src/main/java/dev/buhanzaz/rwms/warehouse/security/WarehouseAuthorizer.java)
- [Lifecycle migration](../../services/warehouse-service/src/main/resources/db/migration/V5__warehouse_lifecycle.sql)
- [Effective-timezone migration](../../services/warehouse-service/src/main/resources/db/migration/V4__warehouse_effective_time_zones.sql)
- [Event contract](../../contracts/events/warehouse-events.yaml)
