# Driver Fixture Bridge

`driver-fixture-bridge` is a local development/test harness for AUD-048. It
makes an already confirmed, generated logistics route visible in the existing
DriverApp by registering canonical driver tasks in task-board. It is not a
runtime service, a production publication path, or an alternative logistics
domain.

The bridge only calls the existing source-owned endpoints:

- `POST /api/internal/task-board/v1/tasks` for idempotent registration;
- `GET /api/internal/task-board/v1/tasks/{externalTaskId}` before cleanup;
- `POST /api/internal/task-board/v1/tasks/{externalTaskId}/cancel-if-pre-start`
  for safe cleanup.

It never writes to a logistics database and never changes a confirmed plan.

## Safety boundary

The command fails before HTTP unless all of these conditions are true:

- `--environment` is exactly `dev` or `test`;
- `--fixture-mode` is present;
- the task-board hostname is loopback or has an explicit `dev`/`test` DNS
  label;
- the plan has `status=CONFIRMED`;
- the root and every exported request have
  `source_system=WAREHOUSE_WORKLOAD_GENERATOR`;
- every routed customer stop resolves to one exported generated planning task;
- every selected shift resolves to an active `ASSIGNED_DRIVER` driver with a
  real task-board `external_worker_id`.

The default is dry-run. HTTP is enabled only by `--execute`. The bearer token
is read from `DRIVER_FIXTURE_BRIDGE_TOKEN` (or the environment variable named
by `--token-env`); there is intentionally no token command-line argument and
the token is never printed.

Production-looking URLs and `production` environments are rejected. For a
remote dev/test service, use a dedicated hostname with a `dev` or `test` label;
for an internal service name without such a label, use a local port-forward.

## Fixture input

The UTF-8 JSON file has `schema_version=1` and combines current read models; it
does not invent a separate persistent schema:

- `plan`: the confirmed `RoutePlanRead`, including ordered cycles and stops;
- `drivers`, `shifts`, and `requests`: the corresponding arrays from the
  warehouse workspace used to calculate that plan;
- `driver_queue_definition_id`: the existing active `LOGISTICS_DRIVER` queue
  definition projected for the fixture warehouse;
- `source_system`: exactly `WAREHOUSE_WORKLOAD_GENERATOR`.

Only fields required for validation and canonical task construction are read;
extra fields from current API projections are ignored. Each request must keep
its nested `tasks`. A route stop's `task_id` must refer to one of those tasks.
Driver records must keep `external_worker_id` and `rwms_assignment_mode`.

The bridge creates one canonical task per route cycle. The deterministic
`externalTaskId` is UUIDv5 over source system, warehouse, confirmed plan, and
cycle. An exact replay therefore returns the same registration instead of a
duplicate. Route steps are sorted by the authoritative numeric stop sequence;
address, coordinates, cargo quantity/dimensions/weight, and planned timestamps
are retained in the existing task text/work snapshot fields. The original
warehouse ID and plan date become `warehouseId` and `scheduledDate`, and the
driver's task-board worker ID becomes an `ASSIGNED_DRIVER` audience.

## Commands

Dry-run prints the exact canonical registration requests and performs no HTTP:

```bash
python3 tools/driver-fixture-bridge/driver_fixture_bridge.py \
  --environment test \
  --base-url http://localhost:8080 \
  --fixture-mode \
  publish --fixture /path/to/confirmed-generated-route.json
```

Publish after reviewing the dry-run:

```bash
export DRIVER_FIXTURE_BRIDGE_TOKEN='<dev/test logistics-service token>'
python3 tools/driver-fixture-bridge/driver_fixture_bridge.py \
  --environment test \
  --base-url http://localhost:8080 \
  --fixture-mode --execute \
  publish --fixture /path/to/confirmed-generated-route.json
```

Multi-cycle registration is intentionally sequential. If the connection fails
after a subset was accepted, repeat the same command: stable external IDs make
accepted cycles idempotent and task-board rejects any changed immutable input.

Safe cleanup derives the same IDs, reads current task versions, and cancels
only tasks whose execution has not started:

```bash
python3 tools/driver-fixture-bridge/driver_fixture_bridge.py \
  --environment test \
  --base-url http://localhost:8080 \
  --fixture-mode --execute \
  cleanup --fixture /path/to/confirmed-generated-route.json
```

Without `--execute`, cleanup is also a dry-run. `STARTED`, `COMPLETED`, or
version-conflict outcomes are never rewritten locally; task-board remains the
execution owner.

## Required test identities

An executing environment must already provide:

- a dev/test service token whose authenticated client is the existing
  logistics-service identity and whose exact scope is `task-board.logistics`;
- the active logistics-driver queue definition in the target warehouse;
- active task-board worker identities and DriverApp credentials for every
  selected driver's `external_worker_id`.

The bridge does not create queues, workers, credentials, shifts, or production
orders. Missing identities are an explicit fixture setup error.

## Verification

```bash
python3 -m unittest discover -s tools/driver-fixture-bridge -p 'test_*.py' -v
```
