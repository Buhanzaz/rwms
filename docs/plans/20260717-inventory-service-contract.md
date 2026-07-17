# Stage 7 Inventory Service Contract

Status: `APPROVED`

This document consolidates the legacy/domain, backend/integration and QA audits.
The user's 2026-07-17 instruction `Начинай Stage 7 все разрешаю`, followed by
`Продолжай`, explicitly approves the complete contract, its narrow sequential
prerequisites, the separate media-runtime closure and the Stage 7 panel cutover.
It authorizes implementation under `ACTIVE_STAGE.md` and the accompanying
implementation plan. It does not authorize Stage 8.

## Authority and evidence classification

### Approved current product requirements

- `inventory-service` owns inventory sessions, the one-active-session invariant,
  frozen expected populations, findings, reconciliation/conflicts, completion
  statistics and downstream publication intents.
- Starting an inventory freezes the selected warehouse's approved active
  non-rented rental-item population. `RENTED` and `WRITTEN_OFF` are excluded.
- Empty inspection means `READY` only inside inventory and never changes the
  rental item's operational status.
- Completion may accept missing and conflicting findings only after explicit
  acknowledgement. Work, material, money and normative-time statistics remain
  separate and are frozen at completion.
- Repair publication is post-completion, independently retryable by stable
  session/finding source identity and may be partial. It reuses maintenance and
  task-board ownership rather than creating a second task ledger.
- Finding work/media and the selected `AUTO` or `MANUAL` ordered workflow are
  immutable publication snapshots.

These requirements and the transport, schema and integration decisions below
are approved Stage 7 authority.

### Legacy facts, not target authority

- Legacy mobile modes `INVENTORY_NEW` and `INVENTORY_EXISTING` can create rental
  items, mutate passport/category/dynamic attributes/accessories, update or
  create estimate drafts and attach photos to the latest event.
- `/api/mobile/**` is anonymous in the legacy security chain and executes under
  `SystemAuthenticator.begin("admin")`. That trust model is a migration-critical
  vulnerability and must not be preserved.
- Legacy inventory lookup is driven by caller-provided warehouse/number values;
  it does not prove a target warehouse grant, cross-warehouse reconciliation or
  optimistic-concurrency contract.
- Legacy media ownership checks are not proved. The mobile surface also exposes
  delete-by-QR behavior without a reconstructible target inventory-session
  lifecycle. Legacy passport mutation, media attachment and deletion behavior
  are evidence only, not target commands.

### Current target facts

- The inventory feature is still a browser aggregate. Sessions use
  `rwms:inventory:v1` LocalStorage, a browser lock/fallback lease and a
  process-local queue. Inventory media uses the shared repair-estimate
  IndexedDB store.
- The panel route `/inventory/*` exists, but it renders
  `DeferredWorkflowPage`; there is no production inventory feature route or
  HTTP adapter. The dormant feature directly selects
  `LocalStorageInventoryAdapter` and `IndexedDbRepairEstimateMediaAdapter`.
- Inventory-origin repair lookup/upsert is hard-wired to
  `LocalStorageRepairTasksAdapter`, even when the ordinary maintenance client is
  the production HTTP adapter. Inventory publication is therefore invisible to
  the implemented maintenance HTTP boundary.
- The browser inventory snapshot omits the canonical asset version even though
  the current asset response exposes one. Asset public list/create is USER-only,
  list pages are capped at 200, and separate requests cannot produce one stable
  expected-population capture for a larger warehouse.
- The browser inventory plan stores a queue code/kind but not the canonical
  queue identity. Its catalog-derived plan is mock/browser evidence and cannot
  be accepted as a maintenance publication command without server validation
  and an immutable catalog/queue snapshot.
- No exact inventory OAuth client, inventory-authorized warehouse endpoint/
  allowlist returning the required version/active/timezone shape, stable asset
  capture/global resolution/durable create surface, maintenance inventory
  upsert, inventory gateway route or inventory service/database exists. The
  existing auth- and asset-specific private warehouse endpoints do not authorize
  inventory and are not evidence of that missing allowlist.
- Media OpenAPI, event schema, Flyway V1 and transformation/storage foundations
  exist, but the tree has no production media HTTP/JWT/PostgreSQL/outbox runtime
  or gateway route. Media facts can support a safe projection; they do not prove
  upload/finalization/cutover.

### Legacy database evidence

The reviewed HSQLDB artifacts are identified by:

- `old_db/hsqldb/wmspanel.script` SHA-256
  `30c944cd6d56227a7010e424acbc75253676834bf989e7b6b1a447efcb3061d8`;
- `old_db/hsqldb/wmspanel.log` SHA-256
  `5451b4003fea6fb7c7078bd2b178cb7c83e6a489332b05120d5c422daf55cce2`.

The reviewed artifacts contain zero reconstructible inventory-session
aggregates. They cannot prove start/completion versions, acknowledgements,
finding histories, publication attempts or one-active-session state. Stage 7
therefore starts with an empty service database. No synthetic inventory session,
event history or browser/legacy ETL is allowed.

## Approved service boundary

`inventory-service` would own:

- warehouse-scoped inventory-session lifecycle and the one-active-session
  invariant;
- immutable expected-population capture copied from an asset-owned stable
  capture;
- finding identity, origin, inspection observations, reconciliation state and
  conflicts;
- frozen work/material/media/catalog/route snapshots and exact statistics;
- completion acknowledgement evidence;
- per-finding maintenance-publication intent, attempt ledger and reconciliation;
- service-local event streams, projections, idempotency, outbox, inbox,
  consumer checkpoints, quarantine and sanitized DLT.

It would not own or mutate canonical existing rental-item passport/status/
warehouse/equipment balances, maintenance repairs, task-board tasks, media
objects, credentials, warehouse identity/timezone, tenants, reservations,
logistics, dossier or analytics.

## Approved aggregate and state model

### Inventory session

Lifecycle:

`ACTIVE -> COMPLETED | CANCELLED`

- A database partial unique constraint permits only one `ACTIVE` session per
  warehouse.
- Start is idempotent. The same subject/command/key and canonical payload replay
  the original session; a changed payload conflicts. A different start while a
  session is active returns the existing-session conflict and never merges
  populations.
- Stable asset capture happens before the local start transaction. The public
  start operation is keyed by `Idempotency-Key`; each technical capture attempt
  has its own attempt identity beneath that operation and is idempotent at
  asset-service. Under a concurrent start race, the database winner owns the
  session; every loser explicitly releases its technical external capture and
  returns a truthful conflict/reconciliation response.
- `CANCELLED` is allowed only from `ACTIVE`, requires USER `MANAGE`, a nonblank
  reason and expected session version. It freezes actor/time/reason. No capture
  is retained by an active session, so cancel/complete have no capture to
  release. Cancellation does not delete findings or an asset already created
  from a finding.
- `COMPLETED` and `CANCELLED` are immutable. Reopen, correction, hard delete and
  export remain `UNKNOWN` and are not v1 commands.

The session stream/version owns lifecycle, warehouse/start snapshots, expected
population and structural finding membership. Each finding has a separate
revision for inspection edits. Each publication intent has a separate revision
and append-only attempt ledger so a late delivery update never reopens or
silently advances the completed session.

### Expected population

The approved active expected-population set is exactly:

`NEW`, `BOOKED`, `REPAIR`, `WAITING_REPAIR_CHECK`, `CAPITAL_REPAIR`,
`AFTER_RENT`, `SALE`, `USED_SALE`, `RESERVED`, `FREE`, `WAREHOUSE`,
`OWN_NEEDS`.

`RENTED`, `WRITTEN_OFF`, `WAITING_ESTIMATE_CONFIRMATION` and `IN_TRANSFER` are
excluded. Asset returns one immutable, stable, paged capture with an opaque
capture ID, fixed membership, total count, membership digest and deterministic
item order. Every copied item includes asset ID/version, warehouse, status,
display number, identity match key and the approved safe passport/contents
snapshot needed for reconciliation. Every page repeats the capture identity
and digest; a cursor can advance only within that capture and replay returns
the same page and order even while canonical assets change.

The approved capture protocol is:

- inventory sends the public start operation ID, a monotonically allocated
  technical attempt identity and canonical request fingerprint. Replay of one
  attempt/fingerprint recovers that capture, while a changed fingerprint
  conflicts;
- asset retains each capture for exactly 30 minutes from creation. The TTL is
  non-sliding: page reads and retries never extend it. Asset expires abandoned
  captures itself after crashes or lost clients;
- inventory copies every page, verifies page order, total count and final
  membership digest, then persists the complete expected set in the same local
  transaction that creates the session; a dependency, expiry or digest failure
  before that commit creates no local session;
- immediately after the inventory start transaction successfully commits the
  full copied snapshot, inventory explicitly releases that capture. Concurrent
  losers release immediately. No capture remains attached to an `ACTIVE`
  session, and cancellation/completion have nothing to release;
- if a capture expires or fails before local commit, a retry of the still-
  uncommitted public start may append a new technical capture attempt beneath
  the same public `Idempotency-Key`. After local commit every retry returns the
  durable session and never creates another capture;
- asset-side 30-minute expiry is only the crash/orphan fallback. It never
  changes a copied expected set or acts as a business session lease.

The exact 30-minute non-sliding TTL and immediate-release lifecycle are
approved target facts; implementation and verification evidence remain pending.

### Finding dimensions

Origin is one of:

- `EXPECTED`;
- `ADDED_NEW`;
- `ADDED_USED`;
- `UNEXPECTED_EXISTING`.

Inspection is one of:

- `NOT_INSPECTED`;
- `READY`;
- `WORK_STAGED`.

Reconciliation is one of:

- `MATCHED`;
- `MISSING`;
- `CONFLICT`.

The dimensions are independent. An expected uninspected item remains
`MISSING`; a found item can simultaneously contain staged work and a conflict.
A global match in another warehouse is recorded as `CONFLICT`; inventory never
moves it, rewrites its warehouse or creates a duplicate.

Approved database invariants are:

- one expected-population row per `(inventoryId, assetId)` and exactly one
  expected finding for each copied expected row;
- at most one finding per `(inventoryId, assetId)` after an asset is known and
  at most one finding per `(inventoryId, identityMatchKey)` throughout number
  resolution and create/attach;
- immutable finding ID and permanent source key `inventoryId:findingId`; neither
  is reused after cancellation, conflict or a failed attach;
- a match in another warehouse or with an excluded current status remains a
  conflict. Inventory neither moves that asset nor creates a duplicate under a
  different display-number spelling.

These aggregate, revision and uniqueness boundaries are approved target facts;
they are not claims about the current browser envelope.

Tenant/customer data is excluded from the inventory v1 aggregate, snapshots,
HTTP and event payloads. The current browser `TENANT_CHANGED` comparison is not
a target rule because no approved canonical tenant revision is available.

### Observation semantics

Passport/equipment observation fields distinguish:

- `ABSENT`: the operator did not observe or submit the dimension;
- `EXPLICIT_EMPTY`: the operator explicitly observed no values;
- `PRESENT`: the submitted immutable value list/snapshot.

This prevents absent input from being reinterpreted as a destructive clear.
Inventory v1 stores observations as evidence only and never applies them to an
existing canonical asset passport or equipment balance. A future canonical
apply command requires a separate contract, a fresh asset version and a short
operation lease/fence.

Creating an explicitly new asset is different: asset-service performs one
asset-local atomic create, persists the permanent source key
`inventoryId:findingId`, request fingerprint and response, and emits its normal
asset fact. Retry recovers the same asset even after ordinary idempotency rows
expire. The approved initial operational status is `FREE`; `NEW`/`USED` remains
an inventory origin/condition snapshot, not a later asset status transition.
Cancellation never hard-deletes or reuses the new asset number.

## Approved rental-number model

Current target rules conflict: the browser inventory display canonicalization
preserves hyphens/spaces while asset-service currently strips every
non-alphanumeric character. The approved v1 resolution is:

- `displayCanonicalNumber`: trim, collapse internal spaces, uppercase
  deterministically with the `ru-RU` locale and preserve only ASCII hyphen
  `U+002D` as punctuation;
- `identityMatchKey`: remove spaces and ASCII hyphen `U+002D` from the display
  canonical value, so `AB-12`, `AB 12` and `AB12` resolve to the same current
  identity;
- global uniqueness and lookup use `identityMatchKey`; presentation and frozen
  evidence retain `displayCanonicalNumber`;
- other punctuation is rejected rather than silently removed.

Asset V3 must introduce the match key and migrate existing stripped values
without inventing lost hyphens. Existing `AB12` remains displayable as `AB12`;
no automatic migration may guess `AB-12`. Collision detection, stable global
resolution and future create all run in the asset-local transaction.

## Approved warehouse time and actor model

- Inventory validates one active canonical warehouse through a private
  inventory-specific warehouse endpoint returning ID, version, active flag and
  IANA timezone.
- `businessDate` is the warehouse-local calendar date at successful session
  start and is then immutable. The browser cannot submit or override it.
- `startedAt`, `completedAt`, `cancelledAt`, publication times and attempt times
  are server UTC timestamps.
- USER identity, scopes and warehouse grants are taken from a locally validated
  JWT and fail closed. Commands do not trust client-supplied actor, permissions,
  warehouse access, date, statistics or signatures.
- Durable actor evidence contains only opaque subject ID, principal type and
  optional profile revision. Login, display name and email are not persisted in
  inventory events or Kafka.

## Approved maintenance plan authority and freeze timing

`maintenance.inventory` is the only authority that resolves and freezes an
inventory repair plan. Inventory calls it while saving a finding as
`WORK_STAGED`, before completion:

- `AUTO` resolves against the then-active maintenance catalog for the canonical
  warehouse; `MANUAL` validates the operator's selected nodes and order against
  that same catalog authority;
- maintenance returns one immutable snapshot and fingerprint containing exact
  catalog-version, catalog-node and queue IDs plus codes/kinds, normalized
  lines, units, quantities, unit prices, normative durations, ordered stages,
  movement flags, group comments and photo requirements;
- inventory stores the returned snapshot/fingerprint on the finding. A later
  catalog activation or queue change never rewrites, upgrades or reroutes it;
- completion preview validates the stored fingerprint and referenced historical
  snapshot. It fails visibly if that source is unavailable or inconsistent and
  never substitutes the then-current catalog;
- post-completion maintenance source upsert accepts that exact frozen snapshot
  and fingerprint, verifies the permanent source identity plus historical
  source/current-asset preconditions, and never regenerates or reroutes the
  plan. Maintenance then owns its repair lease/fence and task synchronization.

Saving `READY` requires no plan. Saving `WORK_STAGED` cannot commit a locally
invented or partly resolved plan when maintenance is unavailable. The exact
private resolve/freeze and source-upsert rules are approved and will be encoded
in canonical schemas; browser catalog/queue values are evidence only.

## Approved preview and completion contract

Completion preview is a server command/read model, not a trusted client
calculation. It:

1. reloads every local finding and revision;
2. calls the asset inventory boundary for one point-in-time validation snapshot
   containing current `{assetId, version, status, warehouseId}` values,
   `validatedAt` and a deterministic `validationDigest`, then refreshes
   reconciliation without mutating the asset;
3. validates the frozen catalog version, node and queue identities and ordered
   plan;
4. recalculates quantities, normative minutes and exact decimal money;
5. returns server statistics, risks and an acknowledgement hash binding session
   version, all finding revisions, the asset validation digest/snapshot and
   catalog/plan snapshot identity.

Preview is advisory and does not reserve canonical assets. Complete first
checks the expected local session/finding revisions and preview acknowledgement,
then performs a fresh asset-side point-in-time validation immediately before
the local commit. If its values/digest differ from the preview snapshot, the
command returns `409 INVENTORY_ACKNOWLEDGEMENT_STALE` and requires a new preview
and acknowledgement. When the fresh validation matches, the local transaction
atomically freezes that validated snapshot/digest/time, acknowledgement hash,
opaque actor, completion time, final finding snapshots, exact statistics and
initial publication intents while applying CAS only to local session/finding
revisions.

This protocol does not claim that a remote asset stayed unchanged between
`validatedAt` and the inventory PostgreSQL commit. A canonical change after
`validatedAt` is a later fact and does not retroactively invalidate a
`COMPLETED` session. Maintenance publication rechecks current asset
preconditions; if they are unsafe it records a `BLOCKED` reconcilable intent
without rewriting the completed point-in-time snapshot. No validation token,
lease or fence is introduced for preview/completion.

The completion CAS also proves all of the following invariants:

- no unresolved source-create attach, plan resolve/freeze or other in-flight
  finding mutation exists;
- every added, unexpected or observed finding is `READY` or `WORK_STAGED`; only
  an unobserved `EXPECTED` finding may remain `NOT_INSPECTED`, and its
  reconciliation must be `MISSING` or `CONFLICT` and included in the current
  acknowledgement hash. This includes an expected asset whose canonical
  status, warehouse or version changed after capture;
- every referenced media generation is projected `READY`, and every
  `WORK_STAGED` finding has the immutable maintenance-issued plan fingerprint;
- the acknowledgement hash covers the current session version, finding
  revisions, asset observations, risks, media generations and plan
  fingerprints.

Cancel, complete and new-asset create/attach race through session/finding CAS.
Only one terminal session transition wins. If asset creation committed before a
losing attach or terminal race, the asset and its permanent source audit remain
recoverable; inventory never deletes or silently reuses it.

Completion is irreversible even when every downstream publication fails. It
does not synchronously require Kafka, task-board or a maintenance response.

## Approved exact statistics contract

Completed statistics are server-owned frozen values. Finding counters use
these exact predicates:

| Counter | Predicate |
|---|---|
| `expectedCount` | `origin == EXPECTED` |
| `inspectedCount` | `inspection in {READY, WORK_STAGED}` |
| `missingCount` | `reconciliation == MISSING` |
| `readyCount` | `inspection == READY` |
| `withWorkCount` | `inspection == WORK_STAGED` |
| `addedCount` | `origin in {ADDED_NEW, ADDED_USED}` |
| `unexpectedExistingCount` | `origin == UNEXPECTED_EXISTING` |
| `conflictCount` | `reconciliation == CONFLICT` |

Work/material line counts count frozen line rows, not summed quantities.
Quantities use validated decimal strings and exact decimal arithmetic; binary
floating point is forbidden. Input quantity is positive, has at most 14 integer
digits and 3 fractional digits, and is persisted exactly at scale 6 by padding
zeros, never by binary conversion or truncation. A finding contains at most
2,000 frozen lines, 1,000 frozen plan stages and 100 media references.

Let `q` be an exact quantity and `p` a nonnegative integer minor-unit price.
Raw work/material category sums are exact decimal minor-unit sums of every
`q * p` product. `workTotalMinor` and `materialTotalMinor` each apply `HALF_UP`
once to their raw category sum. `grandTotalMinor` independently applies
`HALF_UP` once to the combined raw work-plus-material sum, matching the current
maintenance header calculation; it is not derived by adding the rounded
category totals. Persist
`roundingAdjustmentMinor = grandTotalMinor - workTotalMinor -
materialTotalMinor`, validate that it is one of `-1`, `0` or `1`, and expose it
to UI/audit rather than hiding the difference.

`normativeMinutes` is the exact sum of durations in the immutable frozen plan
snapshots. `durationSeconds` is
`max(0, floor((terminalAt - startedAt) / 1 second))`. Aggregate line rows group
catalog lines by `(catalogVersionId, catalogNodeId, type, unit,
unitPriceMinor)` and manual lines by `(normalizedDescription, type, unit,
unitPriceMinor)`; grouped quantities are summed exactly. Each aggregate-row
total applies `HALF_UP` once to that row's combined raw `q * p` sum. Rounded
aggregate rows are presentation/audit values and are not resummed to replace
the independently calculated category/header totals.

Manual aggregation normalizes description by trimming, collapsing whitespace
and lower-casing with deterministic `ru-RU`. Every exact product, raw category
sum, rounded category total and rounded grand total must fit a signed 64-bit
minor-unit value. Normative minutes have exact scale at most 3; each value and
their sum must fit signed 64-bit at that scale. Negative price, zero/negative or
over-scale quantity, count-limit breach and any overflow return `422` rather
than truncating, wrapping or applying extra rounding. These concrete limits and
normalization rules are approved. Preview, completion, history/detail and
statistics queries must expose the same persisted formulas.

## Approved publication contract

Per-finding state is:

- `NOT_REQUIRED`: no publishable work/material plan;
- `READY`: completed finding is eligible for a publication command;
- `PENDING`: an attempt is durably recorded before dispatch/reconciliation;
- `SUCCEEDED`: maintenance confirmed the stable source upsert;
- `TRANSIENT_FAILED`: retryable dependency/timeout failure;
- `BLOCKED`: permanent validation, source conflict or unresolved finding
  conflict;
- `CLOSED_BLOCKED`: a MANAGE operator explicitly closes a permanently blocked
  intent with reason, actor and time.

Aggregate publication status is derived from finding states and never stored as
an independent authority: `NOT_REQUESTED`, `PENDING`, `PARTIAL`, `SUCCEEDED` or
`BLOCKED`. Let the required set contain every finding intent except
`NOT_REQUIRED`. The fold evaluates these rules in exact precedence order:

| Precedence | Predicate over the required set | Aggregate result |
|---|---|---|
| 1 | required set is empty | `NOT_REQUESTED` |
| 2 | every required intent is `SUCCEEDED` | `SUCCEEDED` |
| 3 | at least one is `SUCCEEDED` and at least one is not | `PARTIAL` |
| 4 | none is `SUCCEEDED` and at least one is `READY`, `PENDING` or `TRANSIENT_FAILED` | `PENDING` |
| 5 | none is succeeded/retryable and at least one is `BLOCKED` or `CLOSED_BLOCKED` | `BLOCKED` |

Rule 4 wins even when blocked peers are present. A successful finding is never
relabelled failed/closed. Retry accepts `TRANSIENT_FAILED`. A `BLOCKED` intent
can retry only through a MANAGE reconcile-and-retry transition that stores the
reason/current precondition fingerprint and atomically advances
`BLOCKED -> PENDING` with a new append-only attempt. `CLOSED_BLOCKED` is terminal
in v1 and cannot take that transition; reopening it remains `UNKNOWN`.

The maintenance source key is permanently `inventoryId:findingId`. The request
fingerprint covers asset identity/version, frozen lines/media/plan/catalog/
queue snapshots and the source revision. Identical replay returns the existing
maintenance repair; a changed immutable source payload returns a visible
conflict and never overwrites started work.

## Approved no-session-lease interpretation

Roadmap wording mentions an asset lease around a conflicting cabin workflow.
The approved v1 interpretation is intentionally narrow:

- inventory observation/reconciliation is read-only and acquires no
  session-wide or finding-wide asset operation lease;
- preview and complete use fresh point-in-time asset validation snapshots, not
  a validation token, lease or fence. Local CAS proves only inventory session/
  finding revisions and never asserts remote stability through local commit;
- inventory-origin asset creation is one atomic asset-local command and needs
  no external lease;
- inventory does not expose a fenced existing-asset status/passport/equipment
  mutation;
- maintenance-service acquires/owns its existing maintenance lease and fenced
  status authority only after it accepts an inventory publication;
- a future apply-to-existing-asset feature must obtain a new, short,
  action-specific lease contract and cannot reuse generic `asset.internal`.

This avoids holding cabin leases for a long-running inventory and keeps repair
fencing with the command owner. Generic `asset.internal`, equipment holds and
raw fenced-status authority are forbidden to `inventory-service`.

## Approved HTTP outline

Public endpoints are rooted at `/api/inventory/v1`:

- `GET /sessions?warehouseId=&status=&businessDateFrom=&businessDateTo=&`
  `startedFrom=&startedTo=&terminalFrom=&terminalTo=&page=&size=&sort=` —
  server-paged session history summaries;
- `GET /sessions/active?warehouseId=`;
- `POST /sessions` — idempotent start;
- `GET /sessions/{inventoryId}` — detail with a frozen statistics summary only
  when the session is `COMPLETED`;
- `GET /sessions/{inventoryId}/findings?page=&size=&sort=` — server-paged
  findings when detail does not embed a bounded page;
- `GET /statistics/sessions?warehouseId=&businessDateFrom=&`
  `businessDateTo=&startedFrom=&startedTo=&terminalFrom=&terminalTo=&page=&`
  `size=&sort=` — server-paged per-session frozen statistics for `COMPLETED`
  sessions only;
- `GET /statistics/summary?warehouseId=&businessDateFrom=&`
  `businessDateTo=&startedFrom=&startedTo=&terminalFrom=&terminalTo=` — exact
  server aggregate over `COMPLETED` sessions matching the same time filter;
- `POST /sessions/{inventoryId}/number-resolutions` — global resolve/refresh;
- `POST /sessions/{inventoryId}/findings/{findingId}/assets` — durable
  inventory-origin create and finding attach;
- `PUT /sessions/{inventoryId}/findings/{findingId}/inspection`;
- `POST /sessions/{inventoryId}/completion-preview`;
- `POST /sessions/{inventoryId}/complete`;
- `POST /sessions/{inventoryId}/cancel`;
- `POST /sessions/{inventoryId}/publications` — selected/all eligible findings;
- `POST /sessions/{inventoryId}/findings/{findingId}/publication/retry`;
- `POST /sessions/{inventoryId}/findings/{findingId}/publication/close`.

This approved outline is not yet canonical OpenAPI. Implementation must encode exact request/
response schemas must keep warehouse scope explicit, use UUID
`Idempotency-Key` for retried creates/effects and carry the relevant expected
session/finding/publication versions. Ordinary subject/command idempotency
records are retained for seven days; permanent asset/maintenance source keys do
not expire with them. Time ranges are server interpreted, use inclusive `from`
and exclusive `to`, and reject inverted or over-broad ranges. Business-date
ranges use the frozen warehouse-local date; instant ranges use UTC. History,
detail and statistics are server projections: a browser envelope or locally
recalculated total is never query authority. `ACTIVE` and `CANCELLED` sessions
remain visible in history/detail but have no completed-statistics row and are
excluded from statistics summaries.

Errors use RFC 7807 Problem Details with correlation ID and stable codes. Status
classes are `400` malformed input, `401/403` authentication/authorization,
`404` warehouse-scoped absence, `409` version/idempotency/active-session/
number/source/acknowledgement conflicts, `422` domain/plan/media validation and
`503` only when a mandatory dependency fails before local commit. Approved code
families are `INVENTORY_VALIDATION_FAILED`, `INVENTORY_NOT_FOUND`,
`INVENTORY_VERSION_CONFLICT`, `INVENTORY_IDEMPOTENCY_CONFLICT`,
`INVENTORY_ACTIVE_SESSION_CONFLICT`, `INVENTORY_NUMBER_CONFLICT`,
`INVENTORY_ACKNOWLEDGEMENT_STALE`, `INVENTORY_MEDIA_NOT_READY`,
`INVENTORY_PUBLICATION_CONFLICT`, `INVENTORY_DEPENDENCY_UNAVAILABLE` and
`INVENTORY_FORBIDDEN`.

## Approved authorization and least-privilege prerequisites

Public authorization:

- queries: USER + `rwms.read` + warehouse `VIEW`;
- start, scan/resolve, add and save: USER + `rwms.write` + warehouse `EDIT`;
- complete, cancel, publish/retry and close blocked publication: USER +
  `rwms.write` + warehouse `MANAGE`.

Private calls use direct service addresses, never the gateway. Auth provisions
a disabled-by-default `inventory-service` client for audience `rwms-services`.
Every token contains exactly one requested downstream scope, with
`principal_type=SERVICE` and `sub == client_id == inventory-service`:

- `warehouse.read`;
- `asset.inventory`;
- `maintenance.inventory`.

No token may combine those scopes. Inventory receives no `rwms.write`,
`asset.internal`, `asset.maintenance`, equipment-hold, task-board, queue,
worker, media or logistics credential.

Prerequisites are limited to:

1. `auth-service`: disabled inventory client, exact-scope issuance and negative
   tests for omitted/combined/foreign scopes.
2. `warehouse-service`: a separate inventory endpoint, exact client/scope/sub
   allowlist and safe `{id, version, active, timeZone}` response. It must not
   widen existing auth/asset paths.
3. `asset-service`: idempotent inventory stable capture keyed by start operation
   ID/technical attempt with fixed digest/order/pages, exact non-sliding
   30-minute TTL, immediate release after copied commit or concurrent loss and
   orphan expiry; global resolve and permanent source-bound create only. No
   inventory lease/hold/fenced-status endpoint.
4. `maintenance-service`: exact `maintenance.inventory` plan resolve/freeze
   while a finding is saved as `WORK_STAGED`, followed after completion by a
   permanent source upsert creating or returning a repair with
   `origin=INVENTORY`. Upsert accepts the exact historical frozen
   snapshot/fingerprint and never regenerates/reroutes it. Maintenance owns its
   asset lease/fence and performs task-board synchronization using its existing
   maintenance credential.
5. `api-gateway-service`: only after inventory implementation, add the public
   `/api/inventory/**` route with cookie removal and encoded/internal-path
   rejection. No private route is exposed.

No task-board change or inventory task-board client/scope is authorized. No
media service credential is authorized.

## Approved media-reference policy and prerequisite closure

Inventory accepts only a finalized `READY` media generation found in its
inbox-deduplicated media projection with `ownerType=INVENTORY_FINDING`, matching
finding owner ID and warehouse. It persists `{mediaId, generation}` plus safe
immutable kind/status metadata; raw bytes, object keys, signed URLs and upload
headers never enter inventory PostgreSQL events or Kafka.

Maintenance publication may retain the same source-owned opaque references
after independently verifying the media facts and source identity. It does not
transfer media ownership or duplicate the blob.

The current media facts are sufficient for this validation model, but the
missing media HTTP/JWT/persistence/outbox runtime prevents production upload,
finalization and panel cutover. The user has separately authorized closing that
previous-stage runtime as the first sequential subgate. It remains a distinct
bounded package with its own verification; inventory implementation cannot
claim upload/cutover evidence until that package passes.

## Approved event, recovery and privacy contract

PostgreSQL service-local streams are authoritative. Integration facts use
`DomainEventEnvelopeV2`, event version 1 and aggregate-family topics keyed by
aggregate ID:

- `rwms.inventory.session.v1` for session/finding lifecycle facts;
- `rwms.inventory.publication.v1` for publication-intent outcomes.

Approved session fact families are started, finding-added,
inspection-saved, completed and cancelled. Approved publication facts are
ready, requested, succeeded, transient-failed, blocked and closed-blocked.
Preview is not a committed business fact.

Kafka payloads contain only opaque IDs, versions, warehouse ID, state codes,
counts, exact sanitized totals and safe source references. They exclude actor
display/login/email, tenant, comments/reasons, passport values, media URLs/
object keys, raw dependency errors, JWTs, credentials and arbitrary request
bodies. Service-local state may keep bounded approved business text required
for replay; DLT records contain only failure code, message SHA-256 and recorded
time.

Every local domain append, synchronous projection change and outbox row commits
in one PostgreSQL transaction with stream CAS. Consumers combine inbox event-ID
deduplication, effect and aggregate checkpoint in one transaction. Version gaps
quarantine that aggregate. Transient failures receive the initial attempt plus
three retries at 1s/2s/4s; validation failures do not retry. Outbox rows become
published only after broker acknowledgement.

## Approved persistence and migration contract

Inventory Flyway V1 owns:

- session, expected-item, finding/observation/conflict and completion
  projections;
- partial one-active-session uniqueness plus expected-item/finding asset and
  identity-match-key uniqueness constraints;
- separate session, finding and publication revisions and durable in-flight
  source-create/attach reconciliation state;
- publication intent and append-only attempt ledger;
- append-only `domain_event`, stream heads, snapshots and projection
  checkpoints;
- subject/command idempotency;
- transactional outbox, inbox, consumer aggregate checkpoints, quarantine and
  sanitized DLT.

JPA uses `ddl-auto=validate`; Flyway `baselineOnMigrate=false`. There is no
previous target inventory schema and no reconstructible legacy session data, so
the only legitimate V1 entrance is a clean empty database. Browser LocalStorage,
IndexedDB and legacy HSQLDB are not production migration inputs.

Approved narrow prerequisite migrations:

- asset V3: display number/match key and collision checks, stable capture
  records/pages and permanent inventory source-create mapping/fingerprint;
- maintenance V2: `INVENTORY` origin plus permanent source mapping/fingerprint
  and immutable inventory plan/media source snapshot;
- no auth, warehouse, task-board or gateway database migration;
- architecture guards must include maintenance and the new inventory module
  before implementation can pass the platform policy gate.

Flyway implementation is authorized only in the sequential subgate that owns
the applicable service and after all earlier subgates pass.

## Approved verification matrix

### Domain and concurrency

- one-active-session database race and idempotent start replay/conflict;
- public-start and technical-attempt replay/mismatch, stable page order/digest/
  count, immediate release after copied commit or concurrent loss, no capture
  retained through `ACTIVE`, crash recovery and non-sliding 30-minute asset-side
  TTL/orphan expiry;
- exact expected-set capture across more than 200 assets while assets mutate,
  with no local session on dependency/expiry/digest failure before commit, a
  new technical attempt beneath the same uncommitted public key after expiry,
  and local-session replay without a new capture after commit;
- global number alias/collision races including `AB-12` versus `AB12`;
- deterministic `ru-RU` uppercase, whitespace collapse, ASCII-hyphen aliasing
  and rejection of every other punctuation form;
- permanent source create replay after ordinary idempotency expiry, request
  mismatch and crash after asset commit/before inventory attach;
- expected-item/finding asset/identity-key uniqueness plus finding/session/
  publication CAS isolation and multi-stream atomicity;
- absent versus explicit-empty versus present observation semantics;
- cross-warehouse, rented, written-off, missing and changed-version conflicts;
- cancellation reason/audit, terminal immutability, cancel/complete/create-
  attach races and retained source-created asset audit;
- server-only business date, actor, statistics and acknowledgement hash;
- asset change before complete's fresh validation produces a different digest,
  `409` and a new preview; asset change after `validatedAt` preserves the frozen
  completed snapshot while publication blocks/reconciles unsafe current
  preconditions;
- completion rejection for in-flight attach/mutation, invalid observed states,
  non-ready media and absent/stale plan fingerprints;
- completion acceptance for acknowledged unobserved expected
  `NOT_INSPECTED + MISSING|CONFLICT`, with added/unexpected/observed findings
  required to be `READY|WORK_STAGED`;
- exact decimal-string quantities, browser-compatible added/unexpected counters,
  aggregation keys, fixed scale/count/overflow limits, independent single
  `HALF_UP` category/grand/aggregate-row rounding, explicit adjustment,
  normative minutes, nonnegative floored duration and frozen plan ordering;
- completion with missing/conflicts, stale acknowledgement rejection and no
  rollback on dependency outage;
- per-finding partial success, retry/reconciliation, permanent block and
  MANAGE close without relabelling a succeeded finding.
- exhaustive aggregate-publication fold/precedence over `NOT_REQUIRED`, empty,
  all-success, mixed success, retryable-plus-blocked and blocked-only sets,
  including MANAGE `BLOCKED -> PENDING` reconcile-and-retry and terminal
  `CLOSED_BLOCKED`.

### Security and contracts

- anonymous/WORKER/SERVICE rejection on public commands;
- VIEW/EDIT/MANAGE warehouse matrix and cross-warehouse scoped absence;
- exact single-scope service tokens, `sub == client_id`, omitted/combined/
  foreign-scope and generic `asset.internal` rejection;
- no inventory access to equipment holds, fenced status, task-board or media
  credentials;
- RFC 7807/OpenAPI implementation parity and public gateway internal/encoded
  path denial;
- VIEW authorization, warehouse isolation, paging/sort bounds and inclusive-
  from/exclusive-to semantics for history/detail/findings/statistics queries;
- completed-only statistics rows/summaries while `ACTIVE`/`CANCELLED` remain
  available only through history/detail;
- actor/tenant/comment/passport/media URL/object key/JWT/secret/raw-error
  exclusion from integration facts and DLT.

### Persistence, replay and messaging

- inventory V1 clean/repeat/checksum/non-empty-unversioned/JPA validation;
- asset V2-to-V3 and maintenance V1-to-V2 in-place upgrade, repeat, checksum and
  collision/source replay evidence;
- deterministic replay/shadow parity, snapshots and aggregate CAS;
- outbox atomicity/ack, duplicate inbox, aggregate ordering, version-gap
  quarantine, bounded retry, DLT and PostgreSQL/Kafka outage recovery;
- media fact duplicate/wrong-owner/wrong-warehouse/stale-generation/failed/
  deleted rejection;
- maintenance AUTO then-active-catalog resolution, MANUAL selection validation,
  immutable IDs/codes/kinds/lines/prices/durations/order/photo fingerprint,
  historical preview validation and no silent upgrade after catalog activation;
- maintenance stable upsert replay/fingerprint conflict, no regeneration/
  rerouting and maintenance-owned asset lease/task sync recovery.

Panel typecheck/lint/build/Vitest and desktop/tablet/mobile Playwright are exit
checks under the explicitly approved Stage 7 panel-cutover authority.

## Approved sequential delivery subgates

Preserve one-deployable ownership by completing and reviewing each bounded
subgate before the next:

1. close and verify the separately authorized prior media runtime;
2. auth exact inventory client;
3. warehouse exact inventory registry/timezone endpoint;
4. asset idempotent stable capture with digest/order/paging, non-sliding
   30-minute TTL, immediate post-copy/loser release and orphan expiry, global
   resolve/durable create and number V3, with no inventory business lease;
5. maintenance inventory plan resolve/freeze on `WORK_STAGED` save and exact
   historical-snapshot upsert V2; maintenance keeps lease/task-board ownership;
6. architecture guards for maintenance/inventory;
7. inventory V1 service, canonical OpenAPI/event schemas and isolated local
   `inventory-db` Compose dependency;
8. stateless public inventory gateway route;
9. explicitly authorized Stage 7 panel cutover;
10. full verification, memory reconciliation, independent review and one
    scoped human commit.

Stage 8 remains entirely forbidden until Stage 7 exit and commit. No logistics
client, scope, route, event, service, database, equipment hold or media owner
type may be introduced by these subgates.

## Approval resolution and retained UNKNOWNs

The user's 2026-07-17 `Начинай Stage 7 все разрешаю` response, followed by
`Продолжай`, explicitly approves all of the following choices for implementation:

- this complete aggregate/HTTP/event/security contract and its narrow
  prerequisites;
- asset public-start/technical-attempt identity and fingerprint, fixed
  membership/digest/order/paging, non-sliding 30-minute TTL, immediate release,
  retry-before-commit/replay-after-commit and orphan-expiry semantics;
- session/finding/publication revision boundaries, expected/finding uniqueness,
  permanent source identities and completion/cancel/create-attach invariants;
- point-in-time asset preview/fresh-complete validation, local-only CAS,
  post-`validatedAt` fact handling and publication-time current-precondition
  blocking without a validation token/lease/fence;
- the exact required-intent aggregate publication fold and MANAGE
  `BLOCKED -> PENDING` reconcile-and-retry transition;
- maintenance-owned plan resolve/freeze timing, exact immutable snapshot/
  fingerprint contents, historical preview validation and no rerouting at
  publication;
- server-paged history/findings/completed-only statistics queries, time-filter
  semantics and exact finding/line/quantity/money/normative/duration formulas,
  including the concrete scale/count/overflow/normalization limits and
  category/grand/aggregate-row rounding adjustment;
- deterministic `ru-RU` display uppercase with collapsed whitespace, preserved
  ASCII hyphen `U+002D`, rejection of other punctuation and a match key that
  removes spaces/that hyphen;
- no session-wide inventory asset lease and maintenance-owned repair fencing;
- warehouse-local `businessDate` fixed at successful start;
- MANAGE `CLOSED_BLOCKED` terminal publication policy;
- seven-day ordinary idempotency with non-expiring asset/maintenance source
  identities;
- the exact active-population status set and initial `FREE` status for an
  inventory-created asset.

Still `UNKNOWN` and excluded from v1 are reopen/correct/delete/export, recovery
of a closed-blocked publication, applying observations to existing asset
passport/equipment, authoritative tenant history, retention/orphan/legal-hold
policy, exact media upload allowlists/limits, production operations and every
Stage 8 logistics rule.

Media runtime closure and panel cutover have the required explicit authority,
but must pass their bounded sequential gates before later work may rely on them.
