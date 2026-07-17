# Stage 6 Maintenance Service Implementation Plan

Status: `IMPLEMENTED_AWAITING_CLOSURE_COMMIT`

Authority: the approved
[`20260716-maintenance-service-contract.md`](20260716-maintenance-service-contract.md),
[`ACTIVE_STAGE.md`](ACTIVE_STAGE.md), and the staged roadmap. This plan does not
authorize Stage 7 work.

## Delivery boundaries

- Create only `maintenance-service` as the Stage 6 application deployable.
- Apply only the four approved narrow prerequisite changes to `auth-service`,
  `asset-service`, `task-board-service`, and `api-gateway-service`.
- Cut over only the approved maintenance panel ports/adapters; no unrelated UI
  redesign or browser-store migration is in scope.
- Keep PostgreSQL as the service-local event-store authority and Kafka as
  transport through transactional outbox/inbox processing.
- Preserve exact optimistic-concurrency, idempotency, lease/fencing, replay,
  retry/DLT, quarantine, and recovery requirements from the approved contract.

## Work packages

### 1. Contract and prerequisite gate

- [x] Approve ownership, state machines, lease policy, OpenAPI/event shapes,
  scopes, errors, idempotency, concurrency, replay, and recovery matrix.
- [x] Record explicit approval of the four prerequisites and maintenance panel
  cutover.
- [x] Add disabled-by-default maintenance OAuth client/scopes to `auth-service`.
- [x] Add exact lease-bound maintenance status-transition authority to
  `asset-service` with allowlisted source/target transitions.
- [x] Add source-bound task registration/reconciliation/pre-start update/cancel
  operations to `task-board-service` and reconcile its canonical Kafka facts.
- [x] Add the stateless `/api/maintenance/**` gateway route.
- [x] Run focused auth, asset, task-board, and gateway regression/security tests.

### 2. Canonical contracts and database

- [x] Add canonical maintenance OpenAPI and versioned event schemas.
- [x] Register the `maintenance-service` Gradle module and local-only PostgreSQL
  development dependency.
- [x] Add cumulative Flyway `V1` schema with domain event store, stream heads,
  snapshots, synchronous projections, outbox, inbox, checkpoints,
  idempotency, catalog, estimate, repair, stage, media-reference, lease, and
  integration-reconciliation data.
- [x] Prove clean install, repeat safety, checksum rejection, explicit handling
  of a non-empty unversioned schema, and JPA `validate` startup.

### 3. Maintenance domain and HTTP implementation

- [x] Implement versioned catalog drafts, controlled legacy import, activation,
  and supersession with count/hash reconciliation.
- [x] Implement estimate draft, completion, amendment, line and planned-stage
  invariants.
- [x] Implement repair execution and acceptance axes, direct repair, rework,
  terminal acceptance, and write-off decisions.
- [x] Enforce stream CAS, request `expectedVersion`, UUID idempotency keys,
  seven-day response retention, and RFC 7807 errors.
- [x] Validate locally issued JWTs and exact maintenance scopes with warehouse
  authorization.

### 4. Distributed consistency and recovery

- [x] Acquire, renew, reconcile, and release asset operation leases without an
  unfenced status mutation path.
- [x] Register and reconcile task-board movement/stage tasks using stable
  external task IDs.
- [x] Consume task-board and media facts through inbox deduplication and
  aggregate-version checkpoints.
- [x] Publish sanitized aggregate-family facts only through the transactional
  outbox and broker acknowledgement.
- [x] Implement bounded retry at 1s/2s/4s, validation-to-DLT routing,
  consumer-owned DLT, aggregate-gap quarantine, and operator reconciliation.
- [x] Prove deterministic full replay, snapshot replay, shadow-projection parity,
  CAS/concurrency, multi-stream atomicity, duplicate safety, outage recovery,
  and absence of secrets/PII in events.

### 5. Approved panel cutover

- [x] Replace production maintenance catalog, estimate, repair, acceptance, and
  write-off browser adapters with the versioned HTTP adapter.
- [x] Keep mocks only as explicit development fixtures and fail closed when
  production service configuration or authentication is absent.
- [x] Preserve current desktop/mobile layouts and shared component behavior.
- [x] Run affected unit tests, typecheck, lint, build, and Playwright desktop,
  tablet, and mobile flows.

### 6. Exit gate

- [x] Run the `maintenance-service` unit/integration/security/concurrency/
  migration/event-sourcing/Kafka recovery suite and affected prerequisite-
  service regressions.
- [x] Validate OpenAPI and event schemas against implementation payloads.
- [x] Reconcile numbered architecture memory, migration map, decisions,
  history, and remaining `UNKNOWN`s without erasing audit history.
- [x] Complete independent QA review of the final diff and address findings.
- [ ] Create one scoped human Stage 6 implementation commit.
- [ ] Advance `ACTIVE_STAGE.md` to Stage 7 only after every Stage 6 exit item is
  evidenced and the commit exists.

## Verification record

Final Java 25/Testcontainers verification ran 18 test classes and passed all
116 tests with zero failures, errors or skips in 3m20s. The controlled catalog
class passed 11/11, including exact evidence/hash validation, fail-closed
rejection and import-to-activation-to-estimate use. The previously recorded
full prerequisite suites passed for asset (45), task-board (102, one expected
skip), auth (131, one expected skip) and gateway (34).

Panel ESLint, TypeScript, the production build and all 46 Vitest files/261
tests passed. The affected Playwright cutover passed desktop, tablet and mobile,
3/3, using bundled Chromium in the official Playwright container. The Stage 6
implementation and memory gates are complete; the scoped implementation commit
and subsequent `ACTIVE_STAGE.md` transition remain deliberately unchecked.
