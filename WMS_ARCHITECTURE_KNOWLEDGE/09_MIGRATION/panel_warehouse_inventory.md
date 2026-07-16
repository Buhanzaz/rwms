# Panel Warehouse Inventory

Date: 2026-07-11

## Target Scope

The current panel implements warehouse inventory behind frontend ports and
versioned browser adapters. This is an approved target workflow, not evidence of
a legacy or production service contract.

An inventory session is bound to one warehouse and one start snapshot. It records
expected cabins, added or unexpected cabins, inspections, conflicts, frozen line
and media snapshots, completion statistics, and independent repair-publication
state. Only one active session is allowed for a warehouse.

## Coordination Boundary

Inventory, rental-item creation, and repair-task publication do not hold locks
from different stores simultaneously. Commands use optimistic inventory versions,
source identities, and resumable operation keys. Browser Web Locks are used when
available; expiring storage leases provide the current fallback. Publication
first recovers an existing repair task by inventory source key, preventing a
reload or late response from duplicating work.

The repair task receives `origin=INVENTORY`, `sourceInventoryId`, and
`sourceInventoryFindingId`. It remains the only operational repair/board record;
the inventory keeps an immutable historical copy of the proposed work.

Each repaired finding also stores its completion choice (`AUTO` or `MANUAL`),
movement requirement, and ordered plan snapshots. The browser adapter validates
automatic planning against the catalogue, while manual planning preserves selected
route, queue kind, group comment, normative duration, and photo requirement. Late
publication reads these snapshots, including move-to-repair and move-from-repair
stages, rather than regenerating all stages as generic repair work.

## Media Boundary

Inventory uses the shared operational IndexedDB blob client and stores references
in its envelope, not data URLs. A completed history and published repair may
reference the same media. Only new uncommitted uploads are eligible for cleanup.

## Production Gap

No production inventory API or database owner exists yet. Rental items and
operational repairs also remain browser-backed in this panel flow. Production
service ownership, transactions/outbox, server entity revisions, authorization,
media retention, and conflict/error envelopes remain `UNKNOWN` and must replace
the adapters deliberately.

## Evidence

- `panel/src/features/inventory/`
- `panel/src/features/rental-items/api/rental-items-api.ts`
- `panel/src/features/repair-tasks/`
- `panel/src/api/warehouse-api.ts`
