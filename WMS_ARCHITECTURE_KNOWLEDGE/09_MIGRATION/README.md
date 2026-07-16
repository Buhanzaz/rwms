# 09 Migration

## Guiding Rules

- Approved current product requirements and target decisions are authoritative;
  `wms-panel-old` is read-only evidence where they do not decide behavior.
- Do not guess missing behavior. Write `UNKNOWN`.
- Preserve business state machines before redesigning UI.
- Do not carry legacy public-admin mobile security into the new architecture.
- Port behavioral tests before or alongside service migration.

Foundation correction evidence: [F1C auth warehouse-existence
contract](f1c_auth_warehouse_existence.md).

## Stage 5 asset event/recovery update (2026-07-16)

Asset Flyway V2 completes the service-local event-stream constraints for
committed allocation holds and classifier facts. The asset recovery suite now
uses real PostgreSQL/Kafka containers for broker outage/ack recovery, duplicate
inbox, version-gap quarantine and sanitized DLT, while deterministic shadow
replay proves parity of the asset event store and projections. This removes
the event/recovery implementation blockers. The migration suite also proves an
in-place V1-to-V2 upgrade without baseline or clean, and the affected responsive
Playwright flow passed all configured desktop/tablet/mobile projects, 3/3. The
reviewed scoped human commit remains required by the active Stage 5 exit gate.

Approved 2026-07-13 modernization target and F4K/F4MA/F4MT closure evidence: [F4
event-driven modernization governance](f4_event_driven_modernization.md), after
prerequisite F1C commit `d50922d`, F4K commit `52c0702`, F4MA commit `334576a`
and F4MT commit `01cb9c2`. Consult `ACTIVE_STAGE.md` for the currently
authorized application gate.

## Suggested Migration Order

This legacy-oriented list is retained as audit context. The approved staged
order and sole operational pointer are the decomposition roadmap and
`docs/plans/ACTIVE_STAGE.md`; this list does not authorize implementation.

## Service-only scope (2026-07-14)

The product direction supersedes F4I deployment readiness. RWMS maintains
service code, contracts, migrations and local development/test dependencies
only; Kubernetes, Helm, Kind, VPS/hosting, release pipelines and operations
runbooks are removed from the repository. The archived F4I candidate evidence
remains audit context and must not block W1 or reintroduce deployment work.

1. Security model and user/warehouse access contract.
2. Core database schema and seed data.
3. Core dictionaries: warehouses, rental category/subcategory/type, conditions, attributes, accessories.
4. Rental item registry and event history.
5. Mobile API contract and media upload contract.
6. Media storage and RabbitMQ/Go worker protocol.
7. Reservation services.
8. Repair catalog and estimate services.
9. Queue board and worker assignment services.
10. React shell and dictionary UI.
11. React rental item, reservation, repair, queue, after-repair, and catalog canvas screens.
12. AI search features.

## Complexity Matrix

| Area | Complexity | Main risk | Required verification |
|---|---:|---|---|
| Users/security/warehouse access | High | Legacy mixes Jmix resource roles and business roles; mobile API is public/admin. | Auth tests, warehouse-scope tests. |
| Dictionaries | Low/Medium | Seed data and unique constraints. | CRUD and migration seed tests. |
| Rental item registry | High | Dynamic attributes, tags, accessories, history/photos, grid settings. | Service tests and UI data contract tests. |
| Mobile API | High | DTO compatibility, anonymous admin legacy behavior, media owner resolution. | Contract tests from `MobileApiControllerInventoryTest`. |
| Media pipeline | High | DB/object/Rabbit consistency and async failures. | Local/MinIO tests, READY/FAILED result tests. |
| Reservations | High | Stock/accessory counters and expiration/payment statuses. | Reservation service tests. |
| Repair estimates | Very high | Line identity, task plans, catalog dependencies, totals, movement tasks, item status. | `RepairEstimateServiceTest` parity. |
| Queue board | Very high | REAL/SHADOW routing, worker interruption, route reorder, timers. | `QueueBoardServiceTest` parity plus UI DnD tests. |

## 2026-07-14 Target Media Amendment

The approved target no longer splits media metadata/API ownership from a
separate Stage 4 Go worker. One future stateful Go `media-service` owns the
PostgreSQL/Flyway metadata, signed MinIO URLs, Kafka outbox/inbox and in-process
transformation. The legacy Rabbit/Go path remains read-only migration evidence
until that combined service cuts over; it is not a target ownership boundary.
| Repair process/rework | High | Parent/child process acceptance and after-repair state. | `RepairReworkServiceTest`, after-repair tests. |
| AI search | Medium | Optional provider, parsing drift, cost/failure fallback. | Deterministic fallback tests and mocked AI tests. |
| Catalog canvas | High | Graph/canvas behavior and DB node/link constraints. | Catalog service and UI graph tests. |

## Entity To Target Mapping

| Legacy Jmix area | Spring entity/API | DTO/API boundary | React page/component |
|---|---|---|---|
| `Warehouse`, `UserWarehouseAccess`, `User` | `Warehouse`, `User`, `WarehouseAccess` with explicit auth model | `WarehouseDto`, `UserDto`, `WarehouseAccessDto` | Warehouse settings, user admin, access management |
| Rental classifiers | `RentalCategory`, `RentalSubcategory`, `RentalType`, `RentalItemCondition` | dictionary DTOs with active/sort fields | Dictionary admin pages |
| Dynamic attributes | `RentalAttributeDefinition`, `RentalAttributeOption`, `RentalClassifierAttribute`, `RentalAttributeValue` | passport definition/value DTOs | Attribute admin, rental item detail dynamic fields |
| `RentalItem` | `RentalItem` aggregate | `RentalItemListDto`, `RentalItemDetailDto`, passport DTOs | Rental item registry, detail drawer |
| Rental events/photos | `RentalItemEvent`, `RentalItemEventPhoto` | timeline/photo DTOs, media URLs | History modal, photo gallery |
| Accessories | accessory catalog, stock balance, item assignment entities | accessory option/balance/assignment DTOs | Accessory catalog, stock balances, item accessories |
| Reservations | reservation header/lines/accessories | reservation search/detail/action DTOs | Reservation search, list, detail |
| Repair catalog | catalog node/link entities | catalog tree/canvas DTOs | Catalog picker, catalog canvas/settings |
| Repair estimates | estimate, line, task plan entities | estimate editor DTOs, line DTOs, task plan DTOs | Estimate list/editor/task plan dialog |
| Repair process | process/task/photo link entities | process dossier/status/action DTOs | After-repair, process dossier, queue card details |
| Queue board | queue, task, entry, assignment, time event entities | board state DTO, queue/task action DTOs | Kanban board, queue order/settings |
| Workers | worker, class, group, membership entities | worker/group/class DTOs | Workforce settings, task take dialog |
| Media pipeline | media storage service and worker protocol | upload request/result DTOs | Upload controls, galleries |
| AI search | AI parser/interpreter services | search request/result DTOs | AI search page, reservation smart search |

## Backend API Boundaries To Preserve

Create explicit Spring service/API boundaries around:

- Warehouse access and current user.
- Rental item registry and passport.
- Event history and photos.
- Reservation search and reservation lifecycle actions.
- Repair estimate editor and task plan generation.
- Queue board state and queue entry actions.
- Repair process after-repair and rework actions.
- Media storage/upload/variant serving.
- AI search providers.

Avoid exposing raw entity graphs directly to React.

## Test Migration Checklist

Port or recreate coverage for:

- Mobile inventory new/existing behavior.
- Duplicate rental item number rejection.
- Mobile photo owner resolution and storage path shape.
- Accessory keep/clear semantics in mobile passport.
- Queue REAL/SHADOW promotion and blocked future queues.
- Queue route reorder.
- Holding queues at end.
- Worker interruption/pause/resume rules.
- Estimate line identity and structured comments.
- Task plan generation and generated board task uniqueness.
- Movement to/from repair status transitions.
- Empty estimate behavior.
- Furniture/accessory stock effects.
- Rework child process and source acceptance.
- Photo processing READY/FAILED behavior.
- Rental item hard delete cascade and number reuse.

## Panel Rental Table Notes

- Current implementation note: `09_MIGRATION/panel_rental_table_migration.md`.
- Existing table/mock planning note: `09_MIGRATION/panel_table_and_mock_plan.md`.
- Reusable legacy mock React pages note: `09_MIGRATION/panel_legacy_mock_pages.md`.
- Full-domain mock registry note: `09_MIGRATION/panel_full_legacy_mock_domain_registry.md`.
- Active routed full legacy mock integration note: `09_MIGRATION/panel_full_legacy_mock_route_integration.md`.
- DTO-driven table and photo variant note: `09_MIGRATION/panel_dto_table_and_photo_architecture.md`.
- Current cleanup/supersession note: `09_MIGRATION/panel_legacy_cleanup.md`.
- Settings submenu navigation note: `09_MIGRATION/panel_settings_submenu_navigation.md`.
- Equipment mobile layout note: `09_MIGRATION/panel_equipment_mobile_layout.md`.
- Rental grid format settings note: `09_MIGRATION/panel_rental_grid_format_settings.md`.
- Estimates and repairs settings note: `09_MIGRATION/panel_estimates_repairs_settings.md`.
- Operational repair estimates and microservice-ready mock boundaries: `09_MIGRATION/panel_repair_estimates.md`.
- Operational task-board projection, state commands, and dnd-kit boundary: `09_MIGRATION/panel_task_board.md`.
- Implemented auth/task-board microservices, OIDC panel integration, and settings contracts: `09_MIGRATION/services_auth_task_board.md`.
- Temporary local content development bypass: `panel/.env.development` and the `dev`-only task-board security flag; it is not a production authentication mode.
- Repair acceptance, worker execution media, and cabin write-off projection: `09_MIGRATION/panel_acceptance_writeoffs.md`.
- Shared list toolbar and sidebar-trigger alignment: `09_MIGRATION/panel_shared_list_toolbar.md`.
- Estimate catalog code/comment presentation note: `09_MIGRATION/panel_estimates_repairs_settings.md#2026-07-09-catalog-code-and-comment-presentation`.
- Warehouse inventory browser aggregate, reconciliation, statistics, and deferred
  repair publication: `09_MIGRATION/panel_warehouse_inventory.md`.
- Rental-item dossier, event photo folders, lazy original access, and typed
  estimate/repair actions: `09_MIGRATION/panel_rental_item_dossier.md`.
- Recoverable browser MOCK returns, multi-source shipments, inter-warehouse
  transfers, task cancellation, and cross-flow guards:
  `09_MIGRATION/panel_mock_logistics.md`.
- Unified development browser task-board runtime, workforce seeds, group
  schedules, interruptions, local notifications, and simulation clock:
  `09_MIGRATION/panel_mock_task_board_schedules.md`.
- F0 database safety evidence, reviewed SQL runner, technical platform modules,
  RabbitMQ baseline, and the explicit F1/F2 non-cutover qualification:
  `09_MIGRATION/f0_platform_foundation.md`.
- F1 auth schema-authority cutover, declarative OAuth clients, and the explicit
  F3 gateway-prefix obligation: `09_MIGRATION/f1_auth_foundation.md`.
- F2 task-board schema-authority cutover, external-task idempotency, service-owned
  outbox/inbox, worker-credential coordination, and explicit Stage 2 deferrals:
  `09_MIGRATION/f2_task_board_foundation.md`.
- F3 stateless gateway, public OIDC/API cutover, defense-in-depth JWT,
  canonical forwarded metadata, and production deployment qualifications:
  `09_MIGRATION/f3_api_gateway_foundation.md`.

## Data Migration Notes

- Preserve UUIDs if existing mobile/photo/history links depend on them.
- Preserve seed codes and business codes exactly.
- Preserve string enum values exactly.
- Derive final schema after all changelogs, not from early table creation files.
- Decide whether to keep Jmix security tables or migrate to a new auth schema.
- Legacy production DB vendor and data volume are `UNKNOWN`; target stateful
  services use PostgreSQL.

## Panel New York V4 Shell And Primitive Migration (2026-07-10)

- The React target now has a shared dashboard shell derived from the official shadcn `new-york-v4/dashboard-01` block. This is a target UI composition change and does not alter legacy business workflows or backend contracts.
- Reuse existing shadcn primitives and preserve their project-facing API extensions instead of copying whole registry files or adding parallel component families.
- Keep the current `radix-mira` registry configuration, Hugeicons integration, and semantic application colors. The migration adopts New York v4 dimensions, typography, focus treatment, border/shadow composition, and `0.625rem` radius.
- The prior custom mobile toggle/morph implementation is superseded. The target uses the header `SidebarTrigger` and the standard modal shadcn mobile Sheet.
- Full-height feature pages must flex inside the header/inset shell with `min-h-0`; they own their internal table, board, and workspace scrolling.
- Current-user/header actions and deep breadcrumbs remain outside the mock UI until an authenticated backend/current-user contract exists.

Target evidence:

- `panel/src/App.tsx`
- `panel/src/components/site-header.tsx`
- `panel/src/components/app-sidebar.tsx`
- `panel/src/components/ui/`
- `panel/src/index.css`

## Panel Full Offcanvas And Workspace Navigation Refinement (2026-07-10)

- Supersede the target icon-rail collapse: desktop/tablet use the existing shadcn offcanvas mode and render no rail. Mobile continues to use the standard modal Sheet.
- Keep shell navigation presentation-only: the brand links to `/`, the header trigger owns menu visibility, and the official shadcn breadcrumb maps known route/query nesting without adding backend data or demo identity.
- Estimate/repair create state is URL-backed by `?create=1`. Detail state remains `estimateId`/`repairId`.
- React Router location state is an explicit client-navigation marker, not a backend contract. Marked entries may use `navigate(-1)`; unmarked deployed/deep URLs must replace to their cleaned list route.
- Unknown IDs remain detail workspaces with an unavailable state and working Back action; they must not be interpreted as create commands.

Target evidence:

- `panel/src/components/app-sidebar.tsx`
- `panel/src/components/site-header.tsx`
- `panel/src/components/ui/breadcrumb.tsx`
- `panel/src/hooks/use-workspace-back.ts`
- `panel/src/features/repair-estimates/repair-estimates-page.tsx`
- `panel/src/features/repairs/repairs-page.tsx`

## Rental Creation Dialog Target Copy And Nesting (2026-07-11)

- This is a target UI-only refinement to the browser-mock rental-item creation
  workflow. It does not define a Spring DTO/API contract or alter the hidden
  category/status adapter values.
- The target dialog uses operator-facing copy only and keeps the characteristics
  chooser nested within the create dialog so closing it preserves unsaved form
  state.

Evidence:

- `panel/src/features/rental-items/rental-item-create-dialog.tsx`

## Equipment Register Quantity Alignment (2026-07-11)

- The target panel aligns quantities in the primary `/equipment` desktop grid
  to the left, matching the start of each quantitative heading. This is a
  presentational target refinement only; it changes no equipment DTO, mock
  adapter, or service contract.

Evidence:

- `panel/src/features/equipment/equipment-page.tsx`

## Fullscreen Photo Viewer Alignment (2026-07-12)

- The target `/warehouse/:rentalItemId` fullscreen photo viewer centers every
  slide in the mobile viewport and places the active-photo counter directly
  above its navigation/rotation controls. This is a presentation-only change;
  it does not alter media variants, adapters, or backend contracts.

Evidence:

- `panel/src/components/media/photo-carousel.tsx`

## Imported Rental Cabin Return Entry (2026-07-12)

- The target panel has a browser-adapter import entry for a cabin discovered as
  already rented during data migration. It records the temporary rental fact
  before creating the separate return receipt, rather than treating a missing
  cabin as pre-existing warehouse stock.
- This flow is mock-only. It does not create a Spring data-import, rental,
  media, stock, or worker-task contract.

Evidence:

- `panel/src/features/logistics/import-rented-cabin-dialog.tsx`
- `panel/src/features/logistics/logistics-returns-page.tsx`

## Return Equipment Conflict Gate (2026-07-12)

- In the target return UI, unresolved extra-equipment disposition is an
  exclusive presentation and workflow gate. It routes the operator to
  `/write-offs/equipment` before standard acceptance or estimate actions are
  available. This is a browser-UI guard over mock disposition state, not a new
  backend contract.

Evidence:

- `panel/src/features/logistics/logistics-returns-page.tsx`

## W1 Warehouse Registry Introduction (2026-07-14)

W1 introduces a separate `warehouse-db` local Compose dependency and a clean
Flyway V1 service schema; it performs no legacy database ETL and does not
rewrite historical `old_db`, releases, Liquibase evidence or browser data.
The two approved legacy registry facts are explicit seed rows only. No past
warehouse lifecycle history is inferred, and no Kafka baseline event is
published for those rows.

Browser selection migration is limited to the known persisted aliases `spb`
and `msk`; unknown local values are discarded rather than inferred. The legacy
location/topology shape remains `UNKNOWN` and W1 creates neither location
tables nor production location endpoints.

## Asset-service introduction (implementation record, 2026-07-16)

Asset introduction starts from a clean Flyway V1 database boundary with no
legacy ETL, no browser-data import and no seeded production cabins, catalog or
balances. V2 subsequently adds the committed-hold and completed event-stream
constraints without replacing or baselining V1.
`compose.yaml` adds only the isolated local `asset-db` dependency. Rental
numbers are not reused and legacy status values are not migrated or guessed;
the approved mapping is an explicit API/domain rule for callers that possess a
known legacy value.

The panel cutover reads/writes only the new asset HTTP API for its approved
surfaces. Future workflow ownership and a source command for initial physical
stock are deliberately not smuggled into this migration. Final Java 25
Testcontainers checks exercise clean V1+V2 install, repeat safety, checksum
drift rejection, non-empty unversioned rejection, JPA validation and an in-place
V1-to-V2 upgrade without baseline or clean. The final focused asset suite passed
32 tests after the upgrade case was added. A fabricated legacy or browser
baseline remains forbidden and was not used.

### Final Stage 5 verification reconciliation

The affected asset cutover Playwright flow passed the configured `desktop`,
`tablet` and `mobile` projects, 3/3. The shared AMQP isolation check now reads
the actual `rwms-media-compat` network, and its focused repeat passed. A final
root `test` graph completed successfully after the full auth-service suite and
its Kafka recovery class passed independently.

Git inspection proves a valid worktree on `develop`, but there is no commit
history or `HEAD`. An approved baseline/history strategy must therefore exist
before the required Stage 5 commit can be reviewed as scoped.
