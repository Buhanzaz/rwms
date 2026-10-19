# Panel MOCK Logistics

Date: 2026-07-12

## Target Scope

The panel now owns approved browser-adapter workflows for rental returns,
shipments, and inter-warehouse transfers. These are working MOCK contracts,
not evidence of a production logistics service or legacy aggregate.

Returns use one recoverable intake command. Global cabin-number lookup
classifies new, current-warehouse, other-warehouse, rented, and written-off
records before mutation. The receipt stores separate outbound and arrival
dates, receiver, passport, expected and factual contents, media references,
conflicts, furniture dispositions, and append-only audit snapshots. A conflict
from another warehouse resumes only after a proven received transfer or applied
two-warehouse accounting correction. Tenant mismatch is not resolved by a
warehouse movement.

Shipment plans reserve both targets and furniture sources. One composite
`MOVEMENT` task is created for each target cabin. Furniture from warehouse stock
and multiple source cabins is applied only after explicit preparation
confirmation. Browser journals make preparation and finalization recoverable,
and optimistic checks prevent cycles, over-allocation, stale sources, active
transfers, and duplicate active shipment targets.

Inter-warehouse transfer documents contain multiple independently versioned
cabins. Source departure changes a cabin to `IN_TRANSFER`; the warehouse changes
only after destination acceptance with matching contents and durable photos.
Departure, arrival, cancellation, and accounting correction use persisted
intent phases and exact external task identities. Partial arrival and explicit
conflicts remain visible and retryable.

## Task-Board Integration

Development bootstraps an active `GENERAL_WORKER` class and one `MOVEMENT` queue
binding for the two configured service warehouse IDs only when absent. Existing
administrator-editable queue fields and additional bindings are preserved.
Logistics fails closed when no active visible `MOVEMENT` queue bound to
`GENERAL_WORKER` exists.

`task-board-service` exposes a versioned operator cancellation command by
`externalTaskId`. It cancels unfinished entries, active/paused assignments,
timers, and interruption links, and records the reason. Repeated cancellation is
idempotent; stale active and completed tasks return conflict.

## Dossier And Cross-Flow Guards

The cabin dossier projects only persisted return, shipment, transfer, correction,
and applied furniture-allocation events. Pending task attempts and unaudited
legacy intermediate steps are not shown as completed facts.

Active transfer and shipment guards block competing repair, estimate, write-off,
manual status, contents, return, shipment, and transfer mutations while their
browser intent is active.

## Verification

- Panel typecheck, full ESLint, production build, and all 146 Vitest tests pass.
- Task-board Testcontainers suite passes with `--rerun-tasks`.
- Browser QA at desktop and 390x844 confirms the transfer create dialog uses one
  vertical cabin column, mouse-operable comboboxes, contained scrolling, no
  document overflow, and no console errors.

## Production Gap

Production logistics ownership, database/API schema, authorization, audit
retention, stock transactions, worker-completion callbacks, and distributed
outbox/reconciliation remain `UNKNOWN`. Browser journals and IndexedDB media are
replaceable adapters, not production persistence contracts.
