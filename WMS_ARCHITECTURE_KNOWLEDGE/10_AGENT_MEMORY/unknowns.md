# Unknowns

## 2026-07-14 Service-Only Scope

- Hosting, Kubernetes/VPS topology, production operations, release pipelines
  and deployment-specific security/observability ownership are outside the
  RWMS repository scope. They are not active product unknowns or gates.
- Earlier F4I runtime and deployment unknowns remain historical audit records
  only; they cannot block W1 or justify restoring deployment artifacts.

## Product/Domain Unknowns

- Legacy production database vendor and data volume remain `UNKNOWN`; target
  stateful services are approved on PostgreSQL and do not retain HSQLDB
  compatibility.
- Exact intended ACL matrix per screen and action.
- Target mobile authentication model.
- Whether `Worker.appLogin`/`appPassword` should become real mobile credentials.
- Receiving workflow.
- Picking workflow.
- Shipping workflow.
- Bin/cell/location movement model.
- Scheduled reservation expiration policy.
- Accessory stock total formula/invariant.
- Real payment integration.
- Real email integration.
- Whether all Jmix menu screens should be exposed in React.
- Final target React route names and navigation grouping.
- Whether current `panel` route names should remain or be aligned to documented legacy screen names.
- Whether the `panel` warehouse location graph is product behavior or only prototype UI data.
- Whether direct rental-item accessory movement from an item back to warehouse stock should exist as target table behavior; current `panel` mock has item-to-stock movement, but direct legacy `RentalItemListView` evidence is incomplete.

## Technical Unknowns

- Whether Jmix framework security tables need data migration or should be replaced.
- Whether `RepairCatalogWorkbookImportRunner` is operationally used or only a bootstrap/import helper.
- RabbitMQ broker selection and the common topology/retry baseline are resolved
  by F0. Long-term retention, operational delivery SLA, historical replay, and
  production MinIO durability remain `UNKNOWN`.
- Media endpoint authorization after replacing Jmix security filter chains.
- AI provider/model choice for target app.
- Whether existing `panel` React scaffold should be treated as target contract or disposable prototype in future implementation.
- Final backend DTO/API contract for rental items, photos, equipment, reservations, repairs, queue board, and mobile workflows.

## 2026-07-12 Imported Rental Furniture Resolution Unknowns

- The production command/API and audit model for resolving a furniture
  discrepancy during return is `UNKNOWN`: allocating a surplus to another
  rented cabin, sending it to stock, and writing it off each alter different
  inventory facts.
- The responsible worker-task contract for those furniture actions is also
  `UNKNOWN`. The current browser target shows the relevant client cabins and
  routes extra-equipment conflicts to existing write-offs, but must not invent
  stock mutations or task-board commands without an approved contract.

## 2026-07-10 Repair Acceptance Target Unknowns

- Final Spring/database DTOs, transaction boundaries, and cross-service compensation for repair completion, acceptance, and cabin status changes remain `UNKNOWN`; the current implementation is a versioned browser mock behind ports/adapters.
- The authoritative production source for worker groups, active membership, queue eligibility, and authenticated decision author remains `UNKNOWN`. Current groups are legacy-informed mock snapshots; the second general worker is target-only test data.
- Per-worker versus per-group production media ownership and the mobile upload protocol remain `UNKNOWN`. The approved target UI currently stores result media at group-stage level.
- The production rework command, child repair/task semantics, selected-worker routing, deadlines, and audit contract remain `UNKNOWN`; the current `Переделать` dialog deliberately performs no mutation.
- Additional-equipment effects when a cabin is written off remain outside the accepted scope and are not implemented.

## 2026-07-09 Panel Rental Table Migration Unknown Updates

- Direct rental-item content/accessory movement from item back to warehouse stock remains `UNKNOWN` from direct `RentalItemListView` evidence. It is preserved in `panel` only as mock-only workflow support until legacy source or a target product decision confirms it.
- React table free-text search remains mock-only/target convenience. Legacy `RentalItemListView` evidence proves warehouse/category/subcategory/type/status filters, not a free-text table search.
- The current `panel` nearest-location graph used for finding nearby content sources remains mock-only/prototype behavior. Formal bin/location or distance-based movement behavior is still `UNKNOWN` in legacy.

## 2026-07-09 Panel Legacy Mock Pages Unknown Updates

- Final routing/navigation exposure for `panel/src/features/legacy-mock` is `UNKNOWN`; pages are intentionally exported but not wired into `App.tsx` or sidebar.
- Final Spring DTO/API contracts for legacy mock pages remain `UNKNOWN`; local mock state and seed IDs are not source of truth.
- Reservation accessory stock effects in the new legacy mock pages are mock-only until backend reservation/accessory contracts are derived from legacy services.
- Queue board persistence, drag/drop parity, worker interruption rules, route reorder constraints, and REAL/SHADOW promotion semantics remain `UNKNOWN` for React until Spring service contracts and UI tests are implemented.
- Repair catalog canvas drag/link validation and graph persistence in the new legacy mock page are mock-only approximations; legacy Java remains source of truth.
- AI provider/model behavior and deterministic fallback parsing for target React/Spring remain `UNKNOWN`; the new AI page only mocks token filtering and reservation actions.
- Current `panel` typecheck/build is blocked by existing `panel/src/api/legacy-mock-api.ts` errors outside the legacy mock pages folder.

## 2026-07-09 Full Domain Mock Registry Unknown Updates

- `panel/src/api/legacy-mock-domain-api.ts` is not synchronized with the existing rental table mock storage. Cross-screen consistency remains `UNKNOWN` until the target adapter strategy is reconciled.
- Queue interruption, precise time accounting, route planning, and KPI calculations in the new full-domain mock registry are simplified mock-only behavior. Real parity must come from legacy queue/repair service tests.
- Accessory stock total/available/reserved/in-rent/broken/written-off invariants remain `UNKNOWN`; the mock mutates counters for workflow testing only.
- Target AI provider/model and production fallback rules remain `UNKNOWN`; the new mock AI search is deterministic local parsing only.

## 2026-07-09 Routed Full Legacy Mock Unknown Updates

- The earlier typecheck/build blockers in the legacy mock page/domain registry notes are obsolete after route integration fixes; `npm run typecheck`, `npm run lint`, and `npm run build` now pass in `panel`.
- Legacy screens are now exposed through routed mock pages, but final product navigation grouping and whether every Jmix menu item should remain visible in production React are still `UNKNOWN`.
- `panel/src/api/legacy-mock-api.ts` is the active route-facing facade, while `panel/src/api/legacy-mock-domain-api.ts` remains a separate subagent full-domain registry. The two stores are not reconciled; future agents must either merge them deliberately or remove the unused/sandbox path.
- Route-facing reservation, repair, queue, after-repair, catalog, AI, and mobile workflows are mock-only UI coverage. Final Spring service/API contracts, authorization, scheduling, payment/email, media processing, queue timing, and worker interruption parity remain `UNKNOWN`.

## 2026-07-09 Panel DTO Table And Photo Unknown Updates

- Final Spring DTO/API contract for rental item table schema is still `UNKNOWN`; current `panel` derives table columns/filters from mock DTO fields plus feature metadata for UI resilience.
- Final media storage and image-processing contract is still `UNKNOWN`; current `panel` assumes each rental item photo can expose a small preview variant and a large WebP fullscreen variant, with legacy `url` fallback preserved.
- Exact production dimensions/quality settings for small and large WebP variants are `UNKNOWN`; current mock uses small `w=360` and large `w=1800` URL parameters only for UI validation.

## 2026-07-09 Panel Legacy Cleanup Unknown Updates

- Previous unknowns about exposing `panel/src/features/legacy-mock` and reconciling `panel/src/api/legacy-mock*.ts` stores are obsolete for the current codebase because those files/routes are absent.
- Whether every Jmix menu screen should eventually be exposed in the production React target is still `UNKNOWN`; future implementation should be based on deliberate product/API contracts, not the removed legacy mock facade.

## 2026-07-09 Panel New Rental Item Creation Unknown Updates

- Final backend endpoint for creating a rental item from the new simple model remains `UNKNOWN`.
- Final MinIO/media endpoint for uploading and rotating rental item photos remains `UNKNOWN`; current `panel` behavior is mock adapter behavior that creates local small WebP and large WebP variants for UI testing.

## 2026-07-09 Panel Estimates And Repairs Settings Unknown Updates

- Full React behavior for estimate catalog canvas, work catalog, material catalog, and furniture catalog editors remains `UNKNOWN`/not implemented. The current page migrates only the settings menu entry points.
- Repair settings buttons under `/settings/estimates-repairs` are mock-only test actions. Final repair settings scope and backend API contracts remain `UNKNOWN`.

## 2026-07-09 Panel Estimate Catalog Mock Editors Unknown Updates

- The earlier unknown that estimate catalog editors were not implemented is obsolete for the current mock UI: constructor, work, material, and furniture category editors now exist in `panel`.
- Final backend API contracts for constructor, work catalog, material catalog, and furniture catalog remain `UNKNOWN`; current behavior is localStorage mock behind separated adapter modules.
- Real drag/drop canvas positioning, graph layout persistence, and concurrency behavior remain `UNKNOWN`; current canvas positions and link editing are mock UI behavior.
- Repair settings buttons under `/settings/estimates-repairs` remain mock-only test actions. Final repair settings scope and backend API contracts remain `UNKNOWN`.
- The earlier Liquibase-only assumption that no explicit estimate catalog links exist is obsolete for mock seeding: `old_db/hsqldb/wmspanel.script` contains 254 live `REPAIR_ESTIMATE_CATALOG_LINK` rows. Final backend API contracts remain `UNKNOWN`.

## 2026-07-10 Panel Operational Repair Estimates Unknown Updates

- Final estimate-service HTTP endpoints, request/response versioning, error envelope, optimistic-concurrency response, retry rules, and idempotency keys for draft save and completion are `UNKNOWN`.
- Ownership, freshness, and authorization of rental-item snapshots used by estimates are `UNKNOWN`; the final mapping between current `panel` statuses and legacy repair status transitions is also `UNKNOWN`.
- The final media-service upload, variant, authorization, reference, retention, compensation, and orphan-cleanup contract is `UNKNOWN`.
- The transactional outbox and delivery contracts between estimate, workflow, queue/task-board, repair-process, and movement services are `UNKNOWN`, including retry and `PENDING_GENERATION` to `GENERATED`/`FAILED` transitions.
- Real movement-to-repair behavior, rental-item status changes, inventory/accessory effects, and rollback rules are `UNKNOWN`; current `movementRequired` is only a mock estimate flag.
- Queue registry ownership, lifecycle, allowed explicit queue codes, and the management UI/API for `workQueueCode` are `UNKNOWN`.
- Final business semantics and validation rules for `От кого`, plus retention/migration rules for optional `destinationText` and deprecated `destinationParty`, are `UNKNOWN` beyond current compatibility fields.
- The final backend audit/logging schema for manual versus catalog estimate-line origin is `UNKNOWN`; the current UI hides origin while preserving `sourceLineKey` and catalog snapshot metadata.
- Delete, reopen, correction, cancellation, and version-history policy for completed estimates is `UNKNOWN`.

## 2026-07-10 Operational Estimate Review Unknown Updates

- The earlier estimate-catalog mock concurrency unknown is resolved for the current local adapter: node/link save/delete/reset use revision CAS plus an origin-wide Web Lock when supported. The final repair-catalog service ETag/version field, HTTP conflict status/envelope, retry policy, and behavior in clients without Web Locks remain `UNKNOWN`.

## 2026-07-10 IndexedDB Estimate Media Unknown Updates

- The earlier current-mock localStorage/base64 persistence description is obsolete: current estimate media uses IndexedDB Blob records. Final media-service upload/reference/retention/orphan-cleanup contracts and production size/quota limits remain `UNKNOWN`; the 25 MB/250 MB limits are current browser-mock safeguards only.
- The authoritative time zone for a new estimate's date-only `dispatchDate` remains `UNKNOWN`. Current `panel` initializes it from the browser-local calendar; whether the backend must interpret/produce it in the selected warehouse time zone requires an explicit contract.

## 2026-07-10 Separate Estimates And Repairs Unknown Updates

- The final authenticated-user mapping for the `Автор` column is `UNKNOWN`. Legacy `createdBy` is an audit-principal string; the target must decide whether to expose that username or resolve a separate display name.

## 2026-07-10 Equipment Write-Off And Furniture Mapping Unknown Updates

- Final equipment write-off event/ledger contract is `UNKNOWN`: the current target exposes only aggregate `writtenOffQuantity`, without disposal reason, author, timestamp, version, or immutable event identity.
- The final Spring/API contract for legacy `AccessoryItem.furnitureMaterial` is `UNKNOWN`, including concurrency, validation-error envelope, and transactional behavior across estimate, rental-item accessory assignment, and stock balance.
- Whether furniture-accessory synchronization should occur on every target draft save, as legacy does, or only on a later lifecycle transition requires an explicit migration decision.

## 2026-07-10 Rework Routing Unknown Updates

- Durable `TARGETED` versus `GENERAL` routing, requested group/worker snapshots, current-membership validation, and whether a target applies to every stage or only the first compatible queue remain `UNKNOWN` and are not persisted yet.
- Final production transaction/outbox behavior for source-child queue creation and cabin status compensation remains `UNKNOWN`; schema-v3 guarantees describe only the browser mock.
- Rework cancellation and restoration of the source from `IN_REWORK` to `PENDING` remain `UNKNOWN`; no cancellation command was added.
- The final Spring aggregate for direct `/repairs` creation is `UNKNOWN`. Legacy direct queue creation proves a standalone `BoardTask` with `repairProcess = null`, while the target UI currently models a cabin-root repair with subtasks.
- The backend projection from target mock statuses `DRAFT`/`QUEUED` to `RepairProcessStatus`, `BoardTaskStatus`, and `QueueEntryStatus` is `UNKNOWN`; these status families must not be merged without an explicit service contract.
- Final ownership and edit policy for hidden subtask `queueCode`, `routeQueueKind`, and `groupComment` metadata is `UNKNOWN`. The current UI preserves those values but deliberately exposes only subtask ordering and individual line comments.
- Atomicity and idempotency across the final estimate, rental-item, repair-process, board-task, queue-entry, movement, and media services remain `UNKNOWN`. The current browser coordinator and compensation are mock behavior, not a distributed transaction design.

## 2026-07-10 Repair Amendment And Ordering Unknown Updates

- Historical revision retention for completed-estimate amendments is `UNKNOWN`. The current browser mock keeps one estimate ID, increments its optimistic version, and synchronizes the same linked task; the final backend may require immutable revision rows or an audit event stream.
- The final backend status projection for target `RepairTaskStatus` and `RepairTaskSubtaskStatus` remains `UNKNOWN`; `startedAt` is the current monotonic start guard but must be mapped deliberately to legacy `QueueEntry.status == IN_PROGRESS`/`activeStartedAt` and Spring commands.
- Whether every repair always needs both movement stages, only one direction, or neither remains `UNKNOWN`. The current manual planner uses a single toggle that creates both.
- Cancellation semantics for amending a nonempty completed estimate down to zero lines remain `UNKNOWN`. The current target rejects an empty amendment when a linked task exists instead of silently deleting/cancelling that task.
- Final transactional/idempotency-key behavior for amendment plus task synchronization remains `UNKNOWN`. The local mock uses serialized locks, estimate/task versions, and estimate rollback compensation; this is not a distributed transaction contract.

## 2026-07-10 Operational Task Board Unknown Updates

- Final Spring board snapshot and command contracts remain `UNKNOWN`: queue/entry revisions, HTTP 409 envelope, idempotency keys, authorization, and server-side validation must replace the browser adapter deliberately.
- The authoritative warehouse queue registry, queue names/order, hidden/collapsed persistence, HOLDING period/threshold behavior, and `/settings/task-board` scope remain `UNKNOWN`. Current columns derive from catalog routing plus explicit synthetic fallbacks.
- The worker/group/class directory and eligibility contract remain `UNKNOWN`. Current take stores a required free-text assignee snapshot only for mock workflow validation.
- Legacy `stopTaskOnTake` interruption/resume behavior, background threshold notifications, planned minutes/progress, live board updates, and presence/concurrency UX are not implemented in the target mock and remain `UNKNOWN`.
- Whether non-repair standalone legacy `BoardTask` records share this repair-backed board projection or require a separate backend aggregate remains `UNKNOWN`; do not answer this by adding an unsynchronized frontend store.

## 2026-07-10 Dashboard Shell Unknown Updates

- The authenticated current-user/avatar/logout contract and any real right-side header actions remain `UNKNOWN`; the target header intentionally renders no demo user or fake action.
- Deeper breadcrumb behavior for entity details and query-selected workspaces remains `UNKNOWN`; the current header exposes the resolved route title only.
- Whether the final product should replace the retained semantic sky/mist primary palette with the neutral black New York demo palette remains `UNKNOWN`; current migration changes geometry and composition without inventing a new brand-color decision.

## 2026-07-10 Header And Workspace Navigation Unknown Updates

- The preceding breadcrumb unknown is resolved for the current target routes: rental-item detail, settings children, estimate detail/create, and repair detail/create now have explicit parent/current breadcrumb chains. Dynamic entity-specific breadcrumb naming beyond those approved labels remains `UNKNOWN`.
- Authenticated current-user/avatar/logout and real right-side header actions remain `UNKNOWN`; this change adds no demo identity or invented action.
- The final server-rendered/deployed deep-link environment remains `UNKNOWN`, but the React target behavior is explicit: unmarked estimate/repair deep links fall back to their list, while marked in-app entries use browser history.

## 2026-07-11 Auth And Task-Board Unknown Updates

- The previous UNKNOWNs for the target queue registry/settings API, workforce directory, optimistic 409 contract, USER current-user/logout contract, warehouse access enforcement, and `stopTaskOnTake` service behavior are resolved by `auth-service`, `task-board-service`, and the real settings adapters.
- The owner OAuth client for `queue-registry.write` remains `UNKNOWN`; the internal queue-reference endpoint must not be treated as reachable until that service client is selected.
- Delivery of an initial/reset WORKER password to the worker remains `UNKNOWN`; APIs never return stored password material.
- Background HOLDING notifications, live board push/presence, and polling strategy remain `UNKNOWN`.
- The production cutover of the operational repair-task board from its browser adapter to task-board-service remains `UNKNOWN`; only the settings screen currently uses the real service.
- Physical USER deletion remains intentionally fail-closed until absence of history and active sessions can be proven.
- Broader distributed outbox/reconciliation across estimates, repairs, rental items, media, and the task board remains `UNKNOWN`; only worker credential/deletion intents are currently implemented.

## 2026-07-11 Rental Logistics Unknown Updates

- Legacy proves rental statuses/events, reservations, drivers, and media building blocks, but no complete return/shipment command workflow. The current logistics store, return claim, ten-minute TTL, tenant-string validation, and shipment coordinator are target browser-mock behavior, not a proven backend contract.
- The final company/tenant directory, contract/reservation identity, driver assignment authority, logistics service ownership, HTTP commands, audit schema, authorization, idempotency keys, and distributed transaction/outbox behavior remain `UNKNOWN`.
- Shipment contents are currently a read-only snapshot of installed cabin contents. Allocation, reservation, stock decrement, accessory transfer, and rollback rules for a real shipment remain `UNKNOWN` and were not invented.
- The required production photo count, retention policy, mobile upload synchronization, offline retry, and server-side media ownership for rental returns remain `UNKNOWN`; the target prototype requires 1-20 photos.

- The browser v2 company search still derives options from current tenants and logistics history. A production company/client directory, legal identity, aliases, reservation versions, expiry, and authoritative company-to-cabin relationship remain `UNKNOWN`.
- The final logistics aggregate/service owner and durable database/outbox remain `UNKNOWN`. The browser envelope coordinates a real standalone task-board POST with a local write-ahead journal; production must replace that with a server-owned saga/outbox.
- Choosing among several valid `GENERAL_WORKER` queues currently uses the first active visible queue by configured order. A dedicated logistics queue reference/configuration contract remains `UNKNOWN`.
- Planned equipment changes do not reserve stock and do not execute physical movement. Reservation timing, worker completion callback, stock/cabin version CAS, cancellation, and release rules remain `UNKNOWN`.
## 2026-07-11 Warehouse Inventory Unknown Updates

- The production inventory aggregate owner, database schema, HTTP authorization
  contract, ETag/version envelope, idempotency-key lifetime, and distributed
  outbox/reconciliation with rental items and repairs remain `UNKNOWN`.
- A server-owned rental-item entity revision and authoritative historical tenant
  identity are `UNKNOWN`; the browser adapter therefore detects conflicts by
  comparing live warehouse/status/tenant values with the start snapshot.
- Production media ownership, retention, upload finalization, orphan collection,
  and sharing of one blob between inventory history and a published repair remain
  `UNKNOWN`. Current IndexedDB reference reuse is mock-only behavior.
- Final warehouse business-date service and time-zone registry remain `UNKNOWN`.
  Current SPB and MSK mock warehouses explicitly use `Europe/Moscow`.
- Policy for deleting inventory history, reopening/correcting a completed session,
  exporting statistics, and resolving a permanently blocked publication remains
  `UNKNOWN`; no such commands were invented.
- The production HTTP/database contract that will persist inventory completion
  modes, movement decisions, and task-plan snapshots has not been assigned. The
  current behavior is a browser-adapter target and must migrate deliberately to
  a service-owned contract.

## 2026-07-11 Strict Company Directory Mode

- The settings location, authorization, warehouse/global scope, default value,
  persistence owner, migration path, and validation contract for a future
  strict/hardcore company-selection mode remain `UNKNOWN`.
- Creation/deduplication of a typed company in a production company directory is
  also `UNKNOWN`; current soft mode stores only the logistics document snapshot.

## 2026-07-11 Returned Equipment Disposition Unknowns

- The production owner, HTTP/database schema, authorization, idempotency lifetime,
  outbox/reconciliation, and master-catalog identity for returned-equipment disposition
  remain `UNKNOWN`; the current ledger and journal are mock-only browser adapters.
- A literal replace/swap operation between two cabins remains `UNKNOWN`. The approved
  current command transfers and merges a quantity into an eligible target cabin.
- Final rules for which non-rented operational statuses may receive transferred
  equipment, and whether repair/estimate workflows must also be blocked, require a
  service-owned product contract.

## 2026-07-11 Factual Return Contents Unknowns

- The production owner, API/database contract, authorization, idempotency, and
  transaction/outbox boundary for factual returned-content snapshots and the
  estimate-confirmed equipment-loss event remain `UNKNOWN`. The current
  `FACTUAL` flow is a browser-adapter target behavior.
- The `LEGACY_QUARANTINE` disposition ledger remains only for migrated/historical
  records. Its eventual migration, archival, or replacement policy is `UNKNOWN`.

## 2026-07-12 Dossier And Media Backend Unknowns

- Production ownership, database schema, API versioning, authorization, event
  idempotency, retention, and migration for the append-only cabin activity stream
  remain `UNKNOWN`; the current dossier envelope is browser-adapter scaffolding.
- Production media upload finalization, IO/Go deployment boundary, MinIO policy,
  media-specific event routing, signed original access, cache expiry, orphan
  collection, and deletion/retention policy remain `UNKNOWN`. The common
  RabbitMQ broker/retry/DLQ baseline itself is resolved by F0.
- Whether original-media authorization must include a document/stage capability
  token in addition to the current viewer context is `UNKNOWN` and must be fixed
  in the production service contract.
- Backfilling incomplete historical cabin events is `UNKNOWN`. Do not infer
  missing actors, dates, stages, or status transitions during migration.

## 2026-07-12 Dossier Search And Contents-Transfer Unknowns

- The final server owner and query contract for fuzzy dossier search remain
  `UNKNOWN`; current scoring is deterministic browser-only search over proven
  projection fields.
- The production owner, API/database schema, authorization, idempotency
  lifetime, task cancellation policy, and distributed transaction/outbox for
  cabin contents transfer remain `UNKNOWN`.
- Browser attempt phases make task registration and cabin mutation recoverable,
  but they are not a distributed transaction. A conflict after task creation
  remains explicit and requires a future service reconciliation/cancellation
  contract.
- The final policy deciding when workflow comments replace or merely accompany
  the mutable cabin general comment remains `UNKNOWN`. Current source comments
  remain immutable provenance entries; the one cabin general comment remains
  separately editable.

## 2026-07-12 MOCK Logistics Unknown Updates

- The production owner and service boundary for returns, shipments,
  inter-warehouse transfers, stock reservations, and location corrections remain
  `UNKNOWN`; the implemented envelopes are browser-only adapters.
- Production transaction/outbox behavior between rental items, equipment,
  logistics, media, and task-board remains `UNKNOWN`. Browser phased journals
  define recovery semantics but are not a distributed transaction design.
- The authoritative company, contract, driver, vehicle, and transport-document
  directories remain `UNKNOWN`. The current transfer form intentionally does not
  collect a vehicle.
- The server callback or event that proves worker completion remains `UNKNOWN`.
  The approved MOCK uses explicit operator confirmation after task registration.
- Media retention, object ownership, upload finalization, and signed access for
  return and transfer photos remain `UNKNOWN`; current storage uses the shared
  IndexedDB media adapter.

## 2026-07-12 Unified Task-Board Runtime Unknowns

- Supersede the 2026-07-10 statement that worker interruption and the operational
  board were absent from the target mock: the development browser prototype now
  implements them. Their production scheduler, delivery, persistence, and HTTP
  semantics remain `UNKNOWN` as detailed below.
- Production ownership and execution semantics for the schedule evaluator,
  authoritative clock, warehouse time zones, DST transitions, warning catch-up,
  missed events after downtime, and horizontally scaled scheduler instances
  remain `UNKNOWN`. The current evaluator and simulation clock are browser MOCK.
- The final HTTP/database contract for group schedules, rest periods,
  notifications, pause reasons, interruption links, return grace, idempotent
  external-task synchronization, and their optimistic-conflict envelopes remains
  `UNKNOWN`. Browser DTOs and the schema-v2 envelope must not be promoted to that
  contract.
- Production worker notification delivery, acknowledgement, push/polling,
  offline worker-app behavior, unread synchronization, and retry/deduplication
  policy remain `UNKNOWN`. Current inbox and sonner notices are local MOCK UI.
- Final service-side reconciliation/outbox behavior between repair, logistics,
  rental-item, and task-board owners remains `UNKNOWN`. Browser
  `externalTaskId` registration and repair synchronization demonstrate desired
  idempotency but are not a distributed transaction.
- Migration of browser-seeded workforce/schedules and DEMO tasks into production
  data is `UNKNOWN`. DEMO tasks must never be interpreted as domain history or
  automatically copied into production storage.

## 2026-07-13 F1 Auth Foundation Unknown Updates

- Supersede the F0 qualification that auth still uses Liquibase: F1 removed the
  auth Liquibase runtime and verified JPA plus reviewed SQL against the restored
  F0 database. Task-board remains on Liquibase until F2.
- Retention duration, archival, and eventual removal of unused auth
  `databasechangelog*` tables remain `UNKNOWN`; F1 intentionally preserves them.
- Retention and cleanup periods for expired OAuth authorizations, consents, web
  sessions, and disabled managed-client records remain `UNKNOWN`; F1 proves
  compatibility and explicit revocation behavior, not a production purge job.
- The production secret-manager product, secret injection mechanism, operator
  ownership, coordinated client rollout, and emergency rotation procedure
  remain `UNKNOWN`. The implemented contract proves external secret-source
  names, revisions, rotation behavior, and fail-closed validation only.
- F3 production ingress topology, trusted proxy CIDRs, TLS termination, service
  discovery, and exact deployment configuration remain `UNKNOWN`. The required
  `/auth` prefix and panel callback exclusion are fixed interface obligations,
  not proof of a deployed gateway.
- Real-time invalidation of already-issued self-contained access tokens remains
  deferred. F1 preserves the approved maximum five-minute validity window after
  subject/client disable or authorization revocation.

## 2026-07-13 F2 Task-Board Foundation Unknown Updates

- Supersede the F1 qualification that task-board still uses Liquibase: F2
  removed its Liquibase runtime and verified JPA plus reviewed SQL V0001-V0004
  against the real restored F0 database. Historical `databasechangelog*`
  remains untouched evidence.
- Retention, archival, replay, and eventual cleanup periods for task-board
  outbox, inbox, `rwms_schema_history`, and unused `databasechangelog*` remain
  `UNKNOWN`; F2 implements reliable delivery and deduplication, not purge jobs.
- Production RabbitMQ delivery SLA, monitoring/alert ownership, replay window,
  disaster recovery, and capacity/retention sizing remain `UNKNOWN`.
- Auth-service does not accept a task-board credential operation token. Local
  completions are fenced by durable operation ID, but a remote auth effect that
  completes after the configured fifteen-minute task-board orphan timeout is
  not fenced end-to-end. The required auth contract/version and reconciliation
  rule remain `UNKNOWN`.
- Production schedule evaluation, authoritative clock, warehouse timezone/DST,
  downtime catch-up, horizontal scheduler coordination, pause/interruption
  parity, and worker notification push/poll/offline delivery remain `UNKNOWN`.
  Browser schedules, simulator, inbox, and DEMO state remain MOCK-only.
- F3 ingress topology, trusted proxy CIDRs, TLS termination, direct-backend
  network policy, service discovery, and deployment platform remain `UNKNOWN`.
  Gateway route/security obligations are fixed by the active gate but do not
  prove a production topology.

## 2026-07-13 F3 Gateway Foundation Unknown Updates

- Supersede the F1/F2 qualification that the gateway itself is future work: the
  stateless edge, `/auth` prefix, panel callback exclusion, task-board route,
  local JWT validation, forwarded-header boundary, and single-origin panel
  cutover are implemented and verified.
- Supersede the earlier trusted-proxy-CIDR question at the application boundary:
  the gateway trusts no incoming forwarded metadata and synthesizes auth
  metadata from its validated public base. Production ingress product/topology,
  TLS termination, network isolation, service discovery, deployment platform,
  and observability backend remain `UNKNOWN`; public-base/host validation is not
  proof of a deployed ingress.
- Production firewall or orchestration rules that prevent direct public access
  to auth, task-board, and future resource-service ports remain `UNKNOWN` and
  must be proven by deployment tests. Internal client-credentials bypassing the
  gateway is implemented as an application routing rule, not a network policy.
- The production mechanism that preserves the configured public `Host` from
  ingress to gateway while removing untrusted client-forwarded metadata remains
  deployment-specific and `UNKNOWN`.

## 2026-07-13 F1C Auth Warehouse-Existence Unknown Updates

- Supersede the pre-W1 finding that auth has no warehouse-existence client:
  F1C implements the default-disabled, least-privilege, fail-closed client and
  canonical alias release. Warehouse Service itself is not yet implemented.
- The production secret-manager product, concrete `auth-service` client secret,
  deployment activation order, and operational rollback owner remain `UNKNOWN`.
  F1C fixes the configuration contract; the existing F0/F1 verified
  backup-restore artifact remains the approved rollback policy, but its
  conditional integration test was not re-executed in this F1C run.
- Resolution of any production warehouse-access value other than the approved
  `spb`/`msk` aliases or an already valid UUID remains `UNKNOWN`. V0002 aborts
  rather than inferring or deleting such data.
- End-to-end enabled validation against the real internal W1 endpoint remains a
  W1 exit check. F1C HTTP contract tests do not prove a Warehouse Service or
  gateway warehouse-route deployment.

## 2026-07-13 F4 Modernization Unknowns

- F4K resolves framework versions, technical envelope/schema rules, local KRaft
  startup and bounded delivery/quarantine primitives. It does not resolve a
  production Kafka topology, auth/task-board topic provisioning, application
  replay operations or cutover runbooks; those remain owning-gate/deployment
  evidence.

- Production Kafka topology remains `UNKNOWN`: broker count, partitions,
  replication factor, `min.insync.replicas`, storage/retention sizing, ACL
  operator, certificates, quotas, capacity, upgrade and disaster-recovery
  procedures require deployment evidence.
- The production PII vault/KMS product, encryption/key rotation, subject-erasure
  workflow, backup treatment and privileged operator model remain `UNKNOWN`.
  The approved rule proves exclusion/reference boundaries, not a selected
  vendor or deployed vault.
- DLT ownership, incident SLA, replay approval, event-store retention/archival,
  projection rebuild windows and irreversible corruption recovery remain
  `UNKNOWN` until their F4 sub-gate contracts and runbooks pass.
- Redis 8, Elasticsearch, ClickHouse and Camunda 8 have no approved RWMS data
  ownership or business client in F4I. Their production licensing, sizing,
  retention, HA, backup and DR remain `UNKNOWN`.
- Prometheus, Grafana, Loki, Tempo and OpenTelemetry collector/backend products,
  sampling, cardinality budgets, retention, alert ownership and production
  credentials remain `UNKNOWN`. Readiness manifests are not runtime proof.
- Helm/Kind readiness does not decide the production Kubernetes distribution,
  ingress, service discovery, secret manager, storage classes, network policy,
  autoscaling, multi-zone topology or operations ownership.
- Final Kafka/Event Store performance limits for task-board multi-stream
  commands and auth replay with external PII references remain `UNKNOWN` until
  measured under F4A/F4T concurrency and replay tests.
- The technical retirement/verification mechanism, drain window and removal
  date for the isolated media-compat RabbitMQ runtime after the combined Stage
  3–4 media-service cutover remain `UNKNOWN`; F4R only forbids it from target
  Spring and new business integration, and the active pointer's user
  confirmation is not reconstructed runtime evidence.
- The production operator/CI mechanism that performs the explicit Flyway
  baseline for already populated auth/task-board databases remains `UNKNOWN`.
  The application contract fixes versions 2/4 and `baselineOnMigrate=false` but
  does not select deployment credentials or approval ownership.

## 2026-07-14 F4T Candidate Unknown Updates

- The production protected carrier for task-board worker names/profile PII,
  encryption/key rotation, subject erasure and privileged access remains
  `UNKNOWN`. F4T deliberately leaves those fields in the existing operational
  projection and emits only opaque IDs/revision markers.
- The production owner and runbook for task-board replay approval, resolving a
  quarantined aggregate, replaying sanitized DLT records and retaining/purging
  event-store/outbox/inbox/audit data remain `UNKNOWN`.
- Topic partition counts, replication, ACLs, quotas, lag thresholds and alert
  ownership for the seven task-board aggregate-family topics remain
  deployment `UNKNOWN`s.
- The exact write-freeze window, old-instance shutdown, Rabbit outbox drain,
  unpublished-row retargeting and operator sign-off used for production F4R
  remain `UNKNOWN` until the cutover rehearsal and F4R gate complete.
- Final performance limits for stable multi-stream locks, shadow replay and
  large `QUEUE_ENTRY` child snapshots remain `UNKNOWN` until measured. Current
  DTO/schema shapes and tests are not capacity evidence.

## 2026-07-14 F4I Runtime Prerequisite Updates

- The authorized maintenance window and operator responsible for enabling
  Docker Desktop cgroup v2 through WSL configuration remain `UNKNOWN`. That
  operation interrupts active Docker and WSL workloads and is not a smoke-script
  side effect.
- The network or proxy condition that makes the official Camunda `14.6.1`
  release asset return `EOF` remains an active `UNKNOWN`: one full catalog run
  succeeded, while the final fresh run exhausted three bounded attempts. A
  matching local cache archive is not an approved substitute. The default
  raw-GitHub Kubernetes-schema endpoint also returns `EOF`; the public
  repository revision used through jsDelivr is pinned for validation only.
- Full-profile health and live Kind installation remain `UNKNOWN`. A fresh
  observability pull and two bounded retries failed at `registry-1.docker.io`
  with `TLS handshake timeout` before container creation, and Kind still needs
  the separately authorized cgroup-v2 Docker Desktop/WSL preflight. No
  unresolved runtime check creates RWMS business ownership in readiness-only
  systems.

## 2026-07-14 W1 Warehouse Unknown Updates

- Supersede the historical F1C note that Warehouse Service is not implemented:
  W1 now provides the tested registry and internal existence contract. The
  production secret manager, auth client-secret rotation owner and deployment
  activation/runbook remain `UNKNOWN`.
- Warehouse location/bin topology, graph ownership, legacy location migration
  and any location API remain `UNKNOWN`; W1 deliberately provides none.
- Production Kafka partitioning, ACLs, retention, DLT operator ownership and
  capacity remain `UNKNOWN`. W1's local broker/outbox evidence is not a
  deployed topology decision.
- F4I's Helm/Kind/observability and external runtime blockers remain historical
  deferred work, not passed readiness evidence and not a reason to begin future
  service stages before their own gates.

## 2026-07-14 Combined Media-Service Unknowns

- Media retention period, orphan cleanup trigger/owner, legal hold and
  deletion/audit semantics remain `UNKNOWN`; the V1 schema must not silently
  choose a destructive cleanup policy.
- The maximum upload size, exact raster-format allowlist and supported video
  codec/container list remain `UNKNOWN`. The initial processor recognizes MP4
  and WebM video originals only and must fail truthfully outside that set.
- Original-media authorization beyond the approved estimate/inspection/work
  viewer contexts, including video access in warehouse/history surfaces,
  remains `UNKNOWN` until the owning UI and authorization contracts are
  approved.
- Stage 2 remains the next gate in `ACTIVE_STAGE.md`. Combining later media
  stages does not mark Stage 2 complete or authorize an active-stage advance.

## 2026-07-16 Asset-service unknowns and execution blockers

- The authoritative command/source for receiving initial physical equipment
  stock is `UNKNOWN`. Asset-service therefore starts production-empty and does
  not invent a logistics or inventory workflow merely to seed balances.
- Attribute-definition, option and rental-tag administration contracts are
  `UNKNOWN`; V1 preserves their owned structures while the approved settings
  cutover is limited to classifiers and equipment catalog CRUD.
- The approved `media-service` endpoint and gateway exposure for
  `ownerType=RENTAL_ITEM` upload/read are absent from the current target
  surface. Photo functionality remains fail-closed pending that contract.
- The repository policy currently requires a Java 25 compiler toolchain while
  the supplied default runtime is Java 26.0.1. A temporary non-repository Java
  25 compiler toolchain was used only to compile Gradle modules, and the
  affected JUnit/Testcontainers tasks were launched on Java 26.0.1. This does
  not replace the remaining full Stage 5 broker/outage and browser exit matrix.
- The workspace metadata does not expose a valid Git repository, so the
  requested reviewed scoped human commit cannot be created here.
- The configured Playwright Chrome executable at `/opt/google/chrome/chrome` is
  absent. The Stage 5 responsive asset browser flow remains unexecuted until a
  supported browser is made available; no browser package was installed as an
  unapproved environment mutation.

## 2026-07-16 Stage 5 Readiness Audit Blockers

- The approved fenced cabin-write-off transition is not implemented
  end-to-end: the internal fenced command validates a lease and then invokes
  the public manual transition, which rejects `WRITTEN_OFF`.
- The exact exclusivity policy and contract for an active operation lease need
  approval and implementation. Passport, status, warehouse, comment/note and
  cabin-equipment mutations do not consistently reject or carry the active
  lease/fencing token.
- The asset-owned allocation lifecycle required by the approved logistics
  integration has no commit command/state; only acquire, renew/expiry and
  release exist. Stage 8 must not invent or back-edit this ownership contract.
- Classifier/attribute/tag event-stream ownership and deterministic replay are
  unresolved in code. Classifier SQL writes currently bypass domain events,
  and no stream reader, rebuild/shadow projection or parity test proves replay.
- Real Kafka duplicate, version-gap, retry 1s/2s/4s, DLT/quarantine recovery,
  outbox acknowledgement/outage and inbox atomicity evidence remains missing.
  Existing unit/SQL storage tests do not close this gate.
- For the first cumulative asset V1 schema, no approved earlier target version
  is known. Product/migration authority must either supply a real upgrade
  fixture or approve a documented not-applicable result; no legacy/browser
  shape may be inferred for the test.
- The final sequential root test currently stops in the closed shared Spring
  starter. Its AMQP isolation test reads an obsolete `networks.default` Compose
  path, while the compatibility service is correctly isolated on
  `rwms-media-compat`; the test correction needs an authorized platform scope.
- The real Kafka broker test proves a deterministic closed-platform defect:
  `RwmsKafkaOutboundEventPublisher` supplies a `byte[]` aggregate key while
  `RwmsKafkaBinderAutoConfiguration` enforces `StringSerializer`. Fixing and
  revalidating that cross-service runtime is outside the current Stage 5-only
  deployable scope and remains a root-suite blocker.

## 2026-07-16 Stage 5 blocker resolution update

The preceding audit blockers are retained as audit history. Current source and
the final Java 25 `:services:asset-service:test` run (31 tests, 0 failures,
errors or skips) resolve the fenced write-off, active-lease exclusivity,
asset-local hold commit, classifier event sourcing, deterministic replay and
real asset Kafka recovery gaps. The common binder serializer now matches the
byte-array aggregate key. This update does not claim a successful full root
suite or Stage 5 exit.

Resolved Stage 5 gate evidence:

- The previous-version migration gate is now concrete: the asset suite installs
  V1 alone, then applies V2 in place without baseline or clean and validates the
  resulting constraints and Flyway history. No legacy/browser schema was
  invented.
- The responsive asset flow passed the configured `desktop`, `tablet` and
  `mobile` Playwright projects, 3/3, using bundled Chromium in an isolated test
  container. The earlier absent-system-Chrome observation is historical only.
- The obsolete AMQP isolation assertion and byte-array Kafka serializer mismatch
  are fixed and focused tests pass. The final root `test` graph is green; the
  earlier forced root repeat's two auth recovery timing failures were not
  reproduced by either the isolated recovery class or the complete 129-test
  auth-service repeat.
- Git inspection proved a valid repository with empty history. The user approved
  creating an initial reviewed baseline; root commit `3c509d6` now establishes
  `HEAD`, and scoped commit `4e473ac` closes Stage 5. The earlier “no valid Git
  repository” conclusion is superseded.

No Stage 5 technical `UNKNOWN` remains. Stage 6 contract questions below remain
owned by the Stage 6 evidence/contract gate and are not silently answered by
the Stage 5 closure.

## 2026-07-16 Stage 6 maintenance contract gate

- `docs/plans/20260716-maintenance-service-contract.md` is a proposal awaiting
  explicit product approval. Its aggregate/status boundaries, catalog version
  activation, zero-line amendment rule, movement-stage semantics, general-only
  routing, asset source/target allowlist, seven-day idempotency retention,
  event names and actor/media PII boundary are not approved facts yet.
- The four proposed cross-service prerequisites remain unauthorized:
  disabled-by-default maintenance OAuth client; least-privilege
  `asset.maintenance`; source-bound `task-board.task-sync` with pre-start
  update; and the stateless public maintenance gateway route.
- The canonical task-board AsyncAPI still needs reconciliation with already
  implemented Kafka V2 board-task/queue-entry facts. SERVICE task registration
  and pre-start amendment sync are not implemented.
- The media OpenAPI/JWT runtime is not proved by current source. Stage 6 can
  validate opaque media through canonical events, but end-to-end upload,
  owner-type authorization and panel cutover evidence remain unavailable until
  the owning prior-stage runtime or equivalent external evidence is supplied.
- Exact public OpenAPI request/response schemas and event payload schemas must
  be generated only after the proposal is approved; paths and field policies in
  the proposal are not yet canonical contracts.
- Panel cutover is outside the currently authorized service-only boundary.
  Stage 6 cannot satisfy its production-route exit evidence without a later
  explicit panel-scope expansion.
- Display-name resolution for the operator-facing author column remains
  `UNKNOWN`; the proposal deliberately persists only opaque actor ID/type.
- Stage 7 and later evidence/contracts/implementation remain forbidden until
  the complete Stage 6 exit gate and reviewed commit.

### Stage 6 approval resolution

The preceding contract-gate UNKNOWNs are retained as audit history. The user's
`Продолжай` response approves the combined contract, its four prerequisites and
the Stage 6 panel cutover. Aggregate/status, catalog activation/import,
zero-line amendment, movement/routing, asset transition allowlist,
idempotency/event and actor/media policies are no longer product UNKNOWNs.

Implementation evidence remains outstanding: exact OpenAPI/AsyncAPI files,
Flyway V1, service/runtime behavior, prerequisite tests, media event projection,
panel cutover, recovery matrix and reviewed scoped commit must still be built
and verified. Stage 7 remains forbidden until that exit gate closes.
