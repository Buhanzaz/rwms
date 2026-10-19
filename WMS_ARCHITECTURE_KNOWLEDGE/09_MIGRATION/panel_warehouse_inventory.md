# Panel Warehouse Inventory

Original browser-evidence date: 2026-07-11

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

At the 2026-07-11 evidence point, no production inventory API or database owner
existed and this complete flow used browser-backed rental items and repairs.
That paragraph is retained as historical browser evidence; the dated correction
below is the current target reality.

## Stage 7 migration correction (2026-07-17)

Approval resolution: the user's `Начинай Stage 7 все разрешаю` response,
followed by `Продолжай`, approves the capture, maintenance plan/source, exact
statistics, zero-source V1 and panel-cutover boundaries below. The media runtime
is authorized as the first separate subgate; no legacy/browser evidence becomes
production migration input and Stage 8 remains forbidden.

Stage 5 now provides the canonical asset HTTP service and Stage 6 provides the
canonical maintenance HTTP service. Inventory has not cut over to either
boundary: `/inventory/*` exists but renders `DeferredWorkflowPage`, the dormant
feature has no production HTTP adapter, sessions/publication remain
LocalStorage-backed, media remains IndexedDB-backed, and inventory-origin repair
upsert still targets the local repair adapter. The old browser coordination and
DTO/envelope shapes above remain UX/recovery evidence only.

The reviewed legacy HSQLDB sources contain zero reconstructible inventory
sessions or finding/publication histories:

- `old_db/hsqldb/wmspanel.script` SHA-256
  `30c944cd6d56227a7010e424acbc75253676834bf989e7b6b1a447efcb3061d8`;
- `old_db/hsqldb/wmspanel.log` SHA-256
  `5451b4003fea6fb7c7078bd2b178cb7c83e6a489332b05120d5c422daf55cce2`.

Inventory Flyway V1 therefore has a zero-source entrance: clean empty schema,
no synthetic session/finding/acknowledgement/publication/event ETL, and no
LocalStorage/IndexedDB import. Any reviewed evidence script must emit source
path, input hash, script hash, result/log hash and a zero-row proof; it must not
become a runtime importer or read `old_db` from a service.

The proposed asset prerequisite persists only technical stable-capture state
and permanent source-create mappings: capture requests are keyed by start
operation plus technical attempt ID, membership/digest/order/pages are immutable
for an exact non-sliding 30-minute TTL, and inventory releases immediately after
its copied start transaction commits or loses a concurrent race. No capture is
retained on `ACTIVE`; cancel/complete have none to release, and asset-side expiry
only cleans crash/orphan captures. Inventory may commit a session only after all
pages, count and digest are copied and verified. No pre-commit dependency,
expiry or digest failure leaves a local session. The same still-uncommitted
public key may append a new technical attempt after expiry; after commit it
replays the local session without another capture.

The proposed maintenance prerequisite freezes `AUTO`/`MANUAL` plans during
`WORK_STAGED` save and stores exact historical catalog/node/queue/line/price/
duration/order/photo fingerprints. Post-completion source upsert accepts that
snapshot without regeneration or rerouting; maintenance retains lease and task
ownership. These migration boundaries remain unapproved.

Proposed completed-statistics projections preserve separate `addedCount`
(`ADDED_NEW|ADDED_USED`) and `unexpectedExistingCount`, exact decimal-string
quantities and deterministic maintenance-parity category/grand/aggregate-row
rounding with an explicit header adjustment. Only `COMPLETED` sessions populate
statistics projections; `ACTIVE`/`CANCELLED` remain history/detail records.

The production media HTTP/JWT/PostgreSQL/outbox runtime and gateway route are
still absent. This blocks upload, panel cutover and the full Stage 7 exit; Stage
7 may not invent that previous-stage runtime. The consolidated unapproved
contract and approval checklist are in
`docs/plans/20260717-inventory-service-contract.md`.

Stage 8 evidence, contract and implementation remain forbidden until Stage 7
passes its exit gate and commit.

## Stage 7 production cutover resolution (2026-07-17)

The browser-gap record above is retained as audit history. Production inventory
now uses the versioned HTTP adapter through the stateless gateway and fails
closed without service/config/token; browser stores remain development fixtures
and are not migration inputs.

Panel typecheck, lint and build passed, with affected Vitest 48 and Playwright
desktop/tablet/mobile 9/9. The final Stage 7-only candidate suites passed
inventory 46/46, asset 57/57, maintenance 131/131, Stage 7 architecture 27/27,
auth 11/11, warehouse 12/12, gateway 36/36 and media's canonical real
PostgreSQL, drift-PostgreSQL, Kafka and MinIO matrix 73/73, all with zero
failures, errors or skips. Media also passed a reproducible build. Earlier
shared asset 64/64, maintenance 136/136 and architecture 33/34 runs mixed in
Stage 8 diagnostics and are not Stage 7 closure totals. The production cutover
is complete; closure is recorded by the containing scoped Stage 7 commit
without inventing a SHA.

## Evidence

- `panel/src/features/inventory/`
- `panel/src/features/rental-items/api/rental-items-api.ts`
- `panel/src/features/repair-tasks/`
- `panel/src/api/warehouse-api.ts`
