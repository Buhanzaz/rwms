# History

## 2026-07-14 Service-Only Delivery Boundary

Request:

- Remove repository material that exists for VPS, Kubernetes and other
  deployment targets; make service-only/no-deployment scope explicit for
  future agents.

Result:

- Removed the F4I Helm/Kind chart and smoke tooling, F4I validation scripts,
  deployment-readiness runbook, observability backend configuration, and
  readiness-only Compose profiles.
- Reduced `compose.yaml` to local PostgreSQL, Kafka and isolated
  media-compat Rabbit dependencies; removed Compose `deploy` resource blocks.
- Superseded F4I rather than claiming it passed, removed it from the active
  sequence, and set the operational pointer to service-scope-authorized W1
  work without claiming a concurrent implementation state.
- Updated service, roadmap, migration and durable-memory rules. Historical F4I
  candidate evidence is retained as audit history only.

## 2026-07-13 F4MT Task-Board Flyway Closure

- Implementation `01cb9c2` adopted Flyway 12.4.0 in
  `task-board-service` with cumulative versioned V4, explicit existing-database
  baseline 4, `baselineOnMigrate=false` and JPA validation in every profile.
- Exact preflight preserves historical V0001-V0004 schema/checksums, current
  rows, Rabbit outbox/inbox, `rwms_schema_history` and `databasechangelog*`.
- The independent historical/cumulative catalog digest is
  `2b204139b97ff46298b9818800cc3f9e`; all four release SHA-256 values match.
- Twelve targeted migration/JPA checks and the forced full 87-test task-board
  suite passed with no failures/errors and one expected environment skip.
  Flyway dependency insight and Amplicode rebuild/analysis passed.
- Independent review found no API, domain or Rabbit behavior change. F4MT adds
  no event sourcing or Kafka business publication; F4A is the next active gate.

## 2026-07-13 F4MA Auth Flyway Closure

- Governance correction `844e55d` selected cumulative versioned auth V2 after
  Flyway 12.4.0 baseline-drift runtime evidence.
- Implementation `334576a` adopted auth Flyway with explicit existing baseline
  2, `baselineOnMigrate=false`, JPA validation only and preserved old evidence.
- Dependency insight and Amplicode rebuild/analysis passed. The full auth suite
  passed 72 tests with no failures/errors and one expected environment skip;
  targeted clean/repeat/checksum/non-empty/preflight/baseline/digest/validate
  checks passed.
- One forced rerun was blocked by unrelated npm `ENOTEMPTY`; the successful
  normal suite replaced it. No event sourcing or F4MT implementation is claimed.

## 2026-07-13 F4MA Auth Migration Filename Correction

- Flyway `12.4.0` runtime evidence showed that changing an already applied
  `B2` baseline migration did not make `validate` reject checksum drift.
- Corrected the auth clean-database migration contract to cumulative
  `V2__auth_schema.sql`; existing non-empty auth databases still require an
  explicit baseline at version 2 with `baselineOnMigrate=false`.
- F4MA remains active and incomplete; no implementation commit/hash is claimed.

## 2026-07-13 Flyway Schema Authority Governance

Request:

- Replace the historical custom schema runner with Flyway for all current
  stateful services while preserving one deployable per gate.

Decision:

- Insert `F4MA` and `F4MT` between F4K and F4A.
- F4MA migrates only auth to cumulative V2 or explicit existing baseline 2.
- F4MT migrates only task-board to cumulative V4 or explicit existing baseline 4.
- F4A later owns auth V3 event sourcing; F4T owns task-board V5.
- Keep `baselineOnMigrate=false`, use JPA validation only, forbid Liquibase and
  retain old custom releases/history tables as read-only evidence.

No application code or schema was changed by this governance entry.

## 2026-07-13 F4K Kafka And Java Platform Foundation

Request:

- Modernize the existing backend foundation with Kafka/Cloud Stream,
  Lombok/MapStruct guardrails and event-store conventions without creating a
  new business service or changing application business behavior.

Actions:

- Added the version catalog and Gradle convention plugins for Java 25, Spring
  Cloud 2025.1.2, Cloud Stream 5.0.2, Kafka 4.3.1, Lombok 1.18.46 and MapStruct
  1.6.3.
- Added architecture tests for JPA/Lombok, MapStruct, technical-contract and
  stateless-gateway boundaries.
- Added framework-neutral `DomainEventEnvelopeV2`, canonical technical schemas,
  fail-closed payload/schema validation, Kafka delivery/retry/DLT and aggregate
  checkpoint/quarantine primitives.
- Added conditional Kafka/Cloud Stream starter configuration and Kafka 4.3.1
  KRaft to the Compose core profile while preserving the factual RabbitMQ
  runtime until F4R.
- Kept application deployables behaviorally unchanged; auth/task-board Kafka
  publication and event-sourced cutovers remain F4A/F4T work.

Evidence:

- implementation commit `52c0702`;
- `build-logic/`, `gradle/libs.versions.toml`, `lombok.config`;
- `platform/architecture-tests/`, `platform/spring-boot-starter/`;
- `platform/technical-contracts/src/main/java/dev/buhanzaz/rwms/platform/contracts/DomainEventEnvelopeV2.java`;
- `contracts/events/technical/` and `compose.yaml`.

Verification:

- full Gradle build passed 254 tests, zero failures and two documented skips;
- default and core Compose configurations validate;
- dependency convergence resolves Kafka client 4.3.1;
- gateway runtime contains no Kafka client and auth/task-board runtime contains
  no MapStruct dependency;
- schema validation, secret/PII scan, `git diff --check` and three independent
  platform/messaging/database reviews passed.

## 2026-07-12 Unified Browser MOCK Task Board And Group Schedules

Request:

- Unify task-board settings, the operational board, repair tasks, and logistics
  tasks in one versioned browser MOCK.
- Seed the approved SPB workforce and the SPB/MSK queue registry.
- Add group schedules, lunches, smoke breaks, warnings, automatic pauses,
  movement interruptions, three-minute return grace, worker notifications, and
  a visible simulation clock.

Actions:

- Added a schema-versioned `BrowserTaskBoardClient` behind existing feature
  ports. Development auth bypass selects it; production continues to select the
  Bearer HTTP clients and remains fail-closed.
- Added additive stable seeding for global worker classes, SPB workers/groups,
  SPB/MSK queues, group schedules, and marked DEMO tasks. Browser persistence
  contains no worker passwords.
- Added expected-version checks, idempotent `externalTaskId` registration,
  repair-source synchronization, pause-reason composition, interruption links,
  per-worker notifications, and simulation-clock state.
- Added the sixth development-only `График` settings tab with weekly group
  schedules, linked-day templates, copy operations, exact period editing,
  keyboard/pointer timeline manipulation, and simulation controls.
- Switched the development operational board and repair/logistics task producers
  to the unified mock projection while preserving the production HTTP boundary.

Evidence:

- `panel/src/features/task-board/mock/`
- `panel/src/features/task-board/api/task-board-api.ts`
- `panel/src/features/task-board/task-board-page.tsx`
- `panel/src/features/task-board/task-board-mock-toolbar.tsx`
- `panel/src/features/settings/task-board/schedule-settings.tsx`
- `panel/src/features/settings/task-board/schedule-timeline.tsx`
- `panel/src/features/logistics/adapters/browser-logistics-preparation-task-client.ts`
- `panel/src/features/logistics/warehouse-transfers/adapters/browser-warehouse-transfer-task-client.ts`
- `panel/src/features/rental-items/contents-transfer/adapters/browser-contents-transfer-task-client.ts`
- `panel/src/features/task-board/task-board-repair-coordinator.test.ts`
- `panel/src/features/task-board/mock/browser-task-board-client.test.ts`

Verification:

- Panel `typecheck` and full ESLint passed.
- Panel production build passed; Vite reported only the known chunk-size
  warning.
- Full Vitest suite passed: 32 files and 200 tests, without stderr warnings.
- `:services:task-board-service:test` completed with `BUILD SUCCESSFUL`; the
  final UP-TO-DATE run represents 26 tests in the XML reports.
- Browser QA at desktop `1280px` and mobile `390px` confirmed all six settings
  tabs, the schedule graph, `24x24px` timeline handles, and no document-level
  overflow or console warnings/errors.
- Browser board QA confirmed queue order as non-HOLDING queues, then
  `Без очереди`, then HOLDING. The movement scenario confirmed interruption,
  two worker notifications, `RETURNING`, and early group return.
- Scoped Prettier check and `git diff --check` passed on the final diff.

## 2026-07-12 MOCK Returns, Shipments, And Warehouse Transfers

Request:

- Complete return-from-rental and shipment-to-rental behavior in the MOCK.
- Add global cabin-number conflicts, factual furniture reconciliation, shared
  photos, multi-source furniture preparation, worker tasks, and inter-warehouse
  transfer/correction workflows.
- Remove the vehicle field and present transfer cabin selection as one vertical
  number-and-contents list.

Actions:

- Replaced imported-cabin orphan creation with a recoverable return intake.
- Added split furniture dispositions, estimate replacement seeds, IndexedDB
  media, proof-backed conflict continuation, and append-only dossier projection.
- Added shipment target/source reservations, multi-source task text, explicit
  preparation confirmation, cancellation, and phased recovery/finalization.
- Added multi-cabin warehouse transfers, departure/arrival tasks, durable photos,
  partial receipt, conflicts, cancellation, and two-warehouse correction.
- Added cross-flow guards and a task-board cancellation endpoint with dev-only
  `GENERAL_WORKER`/`MOVEMENT` bootstrap.
- Refined the transfer dialog to a one-column cabin/contents layout without a
  vehicle field and fixed combobox portal interaction inside the modal.

Evidence:

- `panel/src/features/logistics/`
- `panel/src/features/rental-items/dossier/`
- `services/task-board-service/src/main/java/dev/buhanzaz/rwms/taskboard/`
- `WMS_ARCHITECTURE_KNOWLEDGE/09_MIGRATION/panel_mock_logistics.md`

Verification:

- Panel typecheck and full ESLint passed.
- Panel production build passed with only the existing chunk-size warning.
- All 24 Vitest files and 146 tests passed on the final tree.
- Task-board tests passed with `--rerun-tasks`.
- Browser desktop/mobile validation confirmed mouse-operable cabin selection,
  vertical cards, contents display, contained dialog scroll, no document
  overflow, and no console errors.

## 2026-07-10 Panel Equipment Table Row Spacing

Request:

- Remove the visible spacing between equipment rows and match the other tables.

Actions:

- Removed the duplicate desktop bottom border from flex-grid cells.
- Kept one row-level divider and the shared table row height/padding.

Verification:

- TypeScript and targeted lint checks passed.
- Browser validation was blocked by concurrent OIDC integration requiring the unavailable local identity provider at `localhost:9000`; the table change is a shared-class-only presentation correction.

## 2026-07-10 Panel Warehouse Selector Alignment

Request:

- Align the expanded sidebar warehouse dropdown with neighboring page searches and actions.

Actions:

- Changed the warehouse selector's post-header top inset from `20px` to `24px`.
- Kept the existing shadcn combobox dimensions and behavior unchanged.

Verification:

- Playwright geometry checks confirmed the warehouse combobox and shared page toolbar now have the same top coordinate and `36px` height.
- Checked the expanded sidebar against warehouse, equipment, and task-board pages.

## 2026-07-10 Panel Shared List Toolbar Alignment

Request:

- Align search and action controls consistently across pages.
- Fix the awkward spacing around the sidebar collapse trigger.

Actions:

- Added a reusable `PageToolbar` composition for leading controls and right-aligned actions.
- Applied it to rental items, equipment, estimates, repairs, task board, and equipment write-offs.
- Removed the equipment search height override so list searches use the standard shadcn control height.
- Removed the negative margin from the site-header sidebar trigger and reduced its divider height.
- Preserved unrelated concurrent repair/rework and rental-item dialog changes in the working tree.

Verification:

- Project Prettier applied to the changed target files.
- `npm run typecheck` passed before concurrent repair/rework edits advanced.
- `npm run lint` passed.
- Playwright desktop checks confirmed common toolbar coordinates and `36px` height on the affected routes, with no browser console errors.
- `npm run build` is blocked by unrelated incomplete `IN_REWORK` and `RepairTaskDto` changes already being developed in the same working tree.

## 2026-07-09 Full Legacy Audit

Request:

- Read `AGENTS.md`.
- Execute `PROMPT_WMS_FULL_ANALYSIS_RWMS.md` because `PROMPT_WMS_FULL_ANALYSIS.md` was not present.
- Work as a team of subagents.
- Do not write code.
- Only audit `wms-panel-old`.
- Create `WMS_ARCHITECTURE_KNOWLEDGE`.

Actions:

- Read project instructions and prompt.
- Confirmed `WMS_ARCHITECTURE_KNOWLEDGE` was absent before the audit.
- Spawned read-only subagents for:
  - database/entities/repositories
  - UI/views/screens
  - business/security/integrations
- Main agent also inspected build files, resources, application config, menu, entity/service/controller/security files, Go worker, changelogs, tests.
- Created this knowledge folder.

Key findings:

- Legacy stack is Jmix 2.8.1, Java 21, HSQLDB by default.
- Main domains are rental assets, repair, queues, reservations, workers, accessories, media, and AI search.
- Mobile API is public and runs as admin in legacy.
- Formal receiving/picking/shipping/bin-location workflows were not found.
- Legacy source was not modified.

## 2026-07-09 Target Rename And Repair

Request:

- Rename `wms-panel` to `panel`.
- Check the renamed target using the same audit approach.
- Fix what is broken.

Actions:

- Renamed the target React application directory to `panel`.
- Updated package metadata and project instructions from `wms-panel` to `panel`.
- Audited `panel` with subagents for UI, API/domain mapping, and tooling.
- Fixed current TypeScript/build and ESLint failures in `panel`.
- Added target audit notes under `09_MIGRATION/panel_target_audit.md`.

Verification:

- `npm run typecheck` passes in `panel`.
- `npm run lint` passes in `panel`.
- `npm run build` passes in `panel`.

## 2026-07-09 Panel Table And Mock Planning Instructions

Request:

- Analyze `WMS_ARCHITECTURE_KNOWLEDGE` and the current `panel` project.
- Do not create new UI.
- Analyze the existing table.
- Identify React architecture, existing components, reusable pieces, required skills.
- Include that transfer and mock must be working for testing.
- Update `AGENTS.md` so future agents work with this plan.

Actions:

- Re-read project memory and the relevant UI/migration sections.
- Confirmed `panel` is a Vite React SPA using React Router, React Query, shadcn/radix-mira, TanStack Table, TanStack Virtual, and localStorage mock APIs.
- Confirmed the main reusable table is `panel/src/features/rental-items/rental-items-table-view.tsx`.
- Compared current table behavior with legacy `RentalItemListView` and `rental-item-list-view.xml`.
- Added `WMS_ARCHITECTURE_KNOWLEDGE/09_MIGRATION/panel_table_and_mock_plan.md`.
- Updated `AGENTS.md` with rules to reuse the current table UI and keep working mock behavior.

Verification:

- Documentation-only change.
- No `panel` source code was changed.

## 2026-07-09 Panel Rental Table Migration Prompt

Request:

- Write a prompt for the migration work.

Actions:

- Re-read project memory, including the panel table/mock plan.
- Reviewed existing prompt style in `PROMPT_WMS_FULL_ANALYSIS_RWMS.md`.
- Created `PROMPT_PANEL_RENTAL_TABLE_MIGRATION.md` for future agents implementing the existing React table migration with working mock behavior.

Verification:

- Documentation-only change.
- No `panel` source code was changed.

## 2026-07-09 Panel Rental Table Migration Implementation

Request:

- Use `PROMPT_PANEL_RENTAL_TABLE_MIGRATION.md`.
- Follow project memory and migration instructions.
- Preserve legacy behavior as source of truth, but do not copy architecturally poor legacy implementation shapes into the target.

Actions:

- Re-read required project memory and the rental table migration prompt.
- Re-read legacy evidence for `RentalItemListView`, `rental-item-list-view.xml`, `RentalItemService`, `RentalItemStatusSupport`, rental classifier CSV imports, and rental attribute changelogs.
- Used Context7 for current React, TanStack Table, and TanStack Query guidance.
- Used local shadcn skill/docs before changing existing shadcn-based React UI.
- Reworked `panel/src/types/rental-item.ts` around legacy statuses, filters, classifier attributes, dynamic columns, tags, history, and adapter-shaped DTOs.
- Reworked `panel/src/api/rental-items-api.ts` into a legacy-derived mock adapter with category-gated loading, dictionaries, dynamic columns, column settings, photos/history, and movement workflows.
- Updated existing React rental item page, filters, table, column settings flow, status/detail display, and detail history/passport rendering without replacing the table UI.
- Added `09_MIGRATION/panel_rental_table_migration.md`.

Source evidence:

- `wms-panel-old/src/main/java/dev/buhanzaz/wmspanel/view/rentalitem/RentalItemListView.java`
- `wms-panel-old/src/main/resources/dev/buhanzaz/wmspanel/view/rentalitem/rental-item-list-view.xml`
- `wms-panel-old/src/main/java/dev/buhanzaz/wmspanel/service/RentalItemService.java`
- `wms-panel-old/src/main/java/dev/buhanzaz/wmspanel/view/rentalitem/RentalItemStatusSupport.java`
- `wms-panel-old/src/main/resources/dev/buhanzaz/wmspanel/liquibase/data/rental-import/*.csv`
- `wms-panel-old/src/main/resources/dev/buhanzaz/wmspanel/liquibase/changelog/2026/06/12-090000-rental-item-attributes-tags.xml`

Verification:

- `npm run typecheck` passed in `panel`.
- `npm run lint` passed in `panel`.
- `npm run build` passed in `panel`; only the existing Vite chunk-size warning was emitted.
- Browser validation on `http://127.0.0.1:8080/warehouse` confirmed category-gated table loading, cascading filters, legacy status labels, sorting, dynamic attribute columns, hidden terminal attributes, persisted column visibility, photo dialog, detail/passport/history rendering, item-to-item movement, and stock-to-item movement.

## 2026-07-09 Panel Legacy Mock Pages

Request:

- Work as a coding subagent on full legacy mock migration in `panel`.
- Read required project memory and legacy UI/migration sections.
- Use shadcn skill/docs and Context7 before React changes.
- Do not change `wms-panel-old`.
- Implement only reusable React legacy pages under new folder `panel/src/features/legacy-mock`.
- Do not change App/sidebar/rental/equipment and do not add packages.

Actions:

- Re-read required memory files and `AGENTS.md`.
- Used local shadcn skill, `npx shadcn@latest info --json`, and shadcn docs for installed components.
- Used Context7 official React docs for reusable component/state guidance.
- Inspected legacy reservation, repair estimate, queue, after-repair, KPI, catalog, and AI search views/entities/services.
- Added self-contained reusable mock React pages and supporting model/data/UI files under `panel/src/features/legacy-mock`.
- Added `09_MIGRATION/panel_legacy_mock_pages.md`.
- Did not modify `wms-panel-old`.
- Did not modify `panel` routing/sidebar/rental/equipment files.

Verification:

- `npx eslint src/features/legacy-mock` passed.
- `npm run lint` passed in `panel`.
- `npm run typecheck` failed because of existing `panel/src/api/legacy-mock-api.ts` errors outside this task ownership.
- `npm run build` failed for the same existing `panel/src/api/legacy-mock-api.ts` TypeScript errors.

## 2026-07-09 Panel Full Legacy Mock Domain Registry

Request:

- Act as a coding subagent for full legacy mock migration in `panel`.
- Read project memory first.
- Do not modify `wms-panel-old`.
- Only propose or implement mock domain registry/types/API files under `panel/src/types` and `panel/src/api`, preferably new files.
- Do not touch `App`, sidebar, or existing rental files.
- Cover users, warehouses/access, dictionaries, accessories/stock, reservations, repair estimates, queues/workers, repair catalog, after-repair/KPI, and AI search.
- Keep adapter functions async with localStorage persistence for mutations.

Actions:

- Re-read required project memory and relevant entity/business/security/integration sections.
- Re-read selected legacy source for `User`, `Warehouse`, `UserWarehouseAccess`, `WarehouseAccessService`, `Reservation`, and `ReservationService`.
- Added `panel/src/types/legacy-mock-domain.ts`.
- Added `panel/src/api/legacy-mock-domain-api.ts`.
- Added `09_MIGRATION/panel_full_legacy_mock_domain_registry.md`.
- Did not edit `wms-panel-old`.
- Did not edit existing React UI, `App`, sidebar, or rental item files.

Verification:

- `npm run typecheck` in `panel` is blocked by unrelated pre-existing `panel/src/features/legacy-mock/legacy-mock-model.ts` import error for `IconSvgObject` from `@hugeicons/react`.
- `npm run lint` in `panel` is blocked by unrelated unused imports in `panel/src/features/legacy-mock/legacy-mock-pages.tsx`.
- `npm run build` in `panel` is blocked by unrelated existing untracked `panel/src/api/legacy-mock-api.ts` errors plus the `IconSvgObject` issue.
- New files had no reported TypeScript errors after fixing their initial literal-type issues.

## 2026-07-09 Panel Full Legacy Mock Route Integration

Request:

- Migrate all of `wms-panel-old` into `panel` with mock behavior.
- Use subagents and act as reviewer/team lead.
- Follow `AGENTS.md`, use skills and MCP.
- Do not be afraid to change architecturally poor legacy decisions.

Actions:

- Re-read required project memory and all relevant numbered sections.
- Used subagents for:
  - legacy UI/menu and route backlog
  - legacy business/service invariants
  - database/entities/seed/mock dataset audit
  - target `panel` gap audit
  - isolated full-domain mock registry implementation
  - isolated reusable legacy mock React pages
- Used Context7 for current React, React Router, and TanStack Query guidance.
- Used the local shadcn skill/docs before shadcn-based React work.
- Added active route-facing legacy mock types/API/page:
  - `panel/src/types/legacy-mock.ts`
  - `panel/src/api/legacy-mock-api.ts`
  - `panel/src/features/legacy-mock/legacy-mock-page.tsx`
- Updated `panel/src/App.tsx` to route registered legacy screens and aliases.
- Updated `panel/src/components/app-sidebar.tsx` to use legacy-derived navigation groups.
- Preserved existing `/warehouse`, `/warehouse/:rentalItemId`, and `/equipment` behavior.
- Left subagent sandbox artifacts isolated; active route source is documented in `09_MIGRATION/panel_full_legacy_mock_route_integration.md`.
- Did not modify `wms-panel-old`.

Verification:

- `npm run typecheck` passed in `panel`.
- `npm run lint` passed in `panel`.
- `npm run build` passed in `panel`; only the existing Vite chunk-size warning was emitted.
- Playwright MCP validation on `http://127.0.0.1:8080` confirmed legacy sidebar rendering, `/rental-items` redirect, reservation creation, repair estimate generation, queue REAL/SHADOW action, after-repair accept, AI search, warehouse admin table, and absence of old empty placeholders on tested routes.
- Browser validation reset `wms:legacy-mock-state` afterward.

## 2026-07-09 Panel Rename, DTO-Driven Table, And Mobile Photos

Request:

- User deleted the old renamed `panel` project and stated the active target project should be renamed from `web-panel`/current web target back to `panel`.
- Do not migrate from `wms-panel-old`.
- Fix current errors.
- Make table columns depend on DTO fields so future DTO fields can appear.
- Make filters depend on DTO and table settings.
- Improve project architecture and move files; non-legacy code can be changed/deleted.
- Fix mobile fullscreen photo behavior: fullscreen on mobile, horizontal swipes change photos, swipe from bottom upward closes fullscreen.
- Account for two photo variants: small images and large WebP images.

Actions:

- Re-read required project memory and migration/UI notes.
- Used Context7 for React, TanStack Table, and TanStack Query guidance.
- Used local shadcn project context before changing shadcn-based UI.
- Renamed the active target folder from `wms-panel` back to `panel` in the workspace.
- Updated package metadata from `wms-panel` to `panel`.
- Moved rental item API/model files into the rental-items feature boundary:
  - `panel/src/features/rental-items/api/rental-items-api.ts`
  - `panel/src/features/rental-items/model/rental-item.ts`
- Reworked rental table architecture so `RentalItemsTableSchema` is built from actual DTO fields plus known field metadata; unknown scalar DTO fields become columns and filters automatically.
- Reworked filter options to depend on visible table column settings.
- Preserved special renderers for number, status, photo, contents, boolean, and currency columns.
- Removed unused duplicate `panel/src/features/rental-items/photo-fullscreen-viewer.tsx`.
- Added photo DTO variants for small preview images and large WebP fullscreen images.
- Updated `PhotoCarousel` to choose small variants for previews and large WebP variants for fullscreen/mobile dialog rendering.
- Added pointer-swipe navigation and upward swipe close behavior for fullscreen/mobile photo viewing.
- Fixed the warehouse grid/card photo entry point by allowing `RentalItemPhotoDialog` to use uncontrolled carousel state when no parent `activePhotoIndex` is provided.
- Fixed lint blockers in shadcn UI exports through scoped ESLint configuration and fixed current React hooks warnings where actionable.

Verification:

- `npm run typecheck` passed in `panel`.
- `npm run lint` passed in `panel`.
- `npm run build` passed in `panel`; Vite emitted only the chunk-size warning.
- Browser validation on `http://127.0.0.1:8080/warehouse` confirmed table headers and filters include extra DTO fields from mock state and column settings include those dynamic fields.
- Mobile viewport browser validation on `http://127.0.0.1:8080/warehouse/spb-2` confirmed:
  - preview image uses small WebP URL (`w=360`, `fm=webp`),
  - fullscreen dialog opens on tap,
  - fullscreen image uses large WebP URL (`w=1800`, `fm=webp`),
  - horizontal swipe changes to the next photo,
  - upward swipe closes fullscreen.
- Browser validation from `/warehouse` grid/card view confirmed:
  - grid preview images use small WebP fallback URLs,
  - opening a card photo uses large WebP fullscreen URL,
  - horizontal swipe changes fullscreen photos,
  - upward swipe closes fullscreen.

## 2026-07-09 Panel Legacy Cleanup

Request:

- Delete legacy files, folders, and methods.

Actions:

- Kept `wms-panel-old` untouched because it is the read-only legacy source of truth.
- Confirmed current `panel` has no active `legacy-mock` route folder/files and no `wms-panel` folder on disk.
- Removed unused rental item mock adapter mutation methods:
  - `createRentalItem(...)`
  - `updateRentalItem(...)`
  - `deleteRentalItem(...)`
  - `uploadRentalItemPhoto(...)`
  - `deleteRentalItemPhoto(...)`
- Removed unused mock reset methods:
  - `resetRentalItemsMockStorage()`
  - `resetEquipmentMockStorage()`
  - `resetWarehouseLocationMockStorage()`
- Removed unused rental item model exports:
  - `CreateRentalItemPayload`
  - `RENTAL_ITEMS_DEFAULT_COLUMNS`
- Removed stale IDE state that referenced the old `wms-panel` name:
  - `panel/.idea/wms-panel.iml`
  - `panel/.idea/modules.xml`
  - `panel/.idea/workspace.xml`
- Added `09_MIGRATION/panel_legacy_cleanup.md` and marked old legacy mock notes as historical/superseded for the active `panel` code shape.

Verification:

- `rg` found no remaining references to deleted API names in `panel/src`.
- `rg` found no `legacy-mock`, `wms-panel`, or `web-panel` references in current `panel` source/package/IDE project files.
- `npm run typecheck` passed in `panel`.
- `npm run lint` passed in `panel`.
- `npm run build` passed in `panel`; only the existing Vite chunk-size warning was emitted.

## 2026-07-09 Panel New Rental Item Creation Dialog

Request:

- Rename `Добавить бытовку` to `Добавить новую бытовку`.
- Implement a new creation form from scratch without legacy IDs or API-loaded characteristics.
- Use shadcn UI.
- Add static type/dimensions/finishing/characteristic configuration in frontend code.
- Add sanblock counters, photo upload/preview/rotate/delete, and drag-and-drop upload zone.
- Fix type selection so `БК-Пост охраны` sets dimensions to `2x2`.

Actions:

- Added shadcn `field`, `label`, `select`, and `native-select` components as source files.
- Added `panel/src/features/rental-items/model/rental-item-create.ts` for static creation configuration.
- Added `panel/src/features/rental-items/rental-item-create-dialog.tsx`.
- Added narrow mock adapter methods for creating rental items, preparing image variants, rotating creation photos, and reading stored photos.
- Switched the creation form dropdowns to shadcn `NativeSelect` to avoid stale custom select state.
- Added a rounded drag-and-drop zone with text `Перетащите несколько изображений`.
- Replaced OS-native dropdowns with local shadcn `Popover` + `Button` dropdowns so option menus match the rounded UI style and remain scrollable on mobile.
- Updated `БК-Пост охраны` dimensions to: `2x2`, `2.4x2`, `2.4x2.4`, `2.4x3`, `2.4x4`, `2.4x5`, `3x3`.
- Added `NEW` status and status badge color variables.

Verification:

- `npm run typecheck` passed in `panel`.
- `npm run lint` passed in `panel`.
- `npm run build` passed in `panel`; only the existing Vite chunk-size warning was emitted.
- Browser validation confirmed the dialog opens, the drag-and-drop zone is visible, `БК-Пост охраны` exposes all seven dimensions, and `БК-Санблок` sets `2.4x6` plus `ПВХ`.

## 2026-07-09 Panel New Rental Item Creation Dialog Follow-Up

Request:

- For `БК-Санблок`, automatically set linoleum to `Есть`.
- For `БК-Санблок`, automatically select `Пластиковое окно`.
- Add the `Мама-папа` characteristic.
- Hide the drag-and-drop photo upload component on mobile and tablet screens.
- Remove the visible technical text `Для таблицы создаётся маленький WebP, для просмотра — большой WebP.`
- Make mobile buttons and icons larger.

Actions:

- Updated the static rental item creation config with `Мама-папа` and sanblock default characteristics.
- Updated the creation dialog so choosing `БК-Санблок` sets dimensions, finishing, linoleum, and default characteristics in one form-state update.
- Disabled manual linoleum changes while `БК-Санблок` is selected and documented the automatic value in the field description.
- Kept the normal upload button on all breakpoints, but hid the drag-and-drop zone below `xl`.
- Removed the user-facing WebP technical description from the photo field while preserving the mock small/large image variant generation.
- Increased shadcn `Button` mobile touch targets and SVG sizes, with `md` reverting to the existing compact desktop/tablet density.
- Removed local fixed small heights from the creation dropdown buttons so mobile button sizing applies there too.

Verification:

- `npm run typecheck` passed in `panel`.
- `npm run lint` passed in `panel`.
- `npm run build` passed in `panel`; only the existing Vite chunk-size warning was emitted.
- Browser validation on `http://127.0.0.1:8080/warehouse` confirmed `БК-Санблок` sets `2.4x6`, `ПВХ`, linoleum `Есть`, and selected `Пластиковое окно`.
- Browser validation confirmed `Мама-папа` appears in the characteristics dialog.
- Browser validation confirmed the drag-and-drop zone is hidden at `390px` and `820px`, and visible at `1440px`.
- Browser validation at `390px` confirmed creation dialog buttons render at `40px` height with `20px` SVG icons, and the close icon button renders at `36px` with an `18px` icon.

## 2026-07-09 Panel Rotated Mobile Grid Format Clamp

Request:

- On rotated mobile, do not allow choosing a grid format larger than `1x1`.
- Remove the visible grid format selector on rotated mobile.

Actions:

- Re-read required project memory and the current rental item grid format implementation.
- Used Context7 React documentation for `useSyncExternalStore` external browser state subscription guidance.
- Used local shadcn context before changing the existing button-based toolbar.
- Updated `panel/src/features/rental-items/rental-items-page.tsx` so phone-like landscape viewports also count as mobile for the rental item grid.
- Kept tablet and desktop format behavior unchanged.
- Updated `09_MIGRATION/panel_rental_table_migration.md` and `10_AGENT_MEMORY/decisions.md`.

Verification:

- Pending in this session until `panel` checks and browser validation are run.

## 2026-07-09 Panel Mobile Menu Collapse

Request:

- On mobile, allow collapsing the rental item page menu by tapping the `Бытовки / N шт.` header area.
- Show a simple chevron without a tail: down when collapsed, up when expanded.

Actions:

- Re-read required project memory and the current rental item page UI state.
- Updated `panel/src/features/rental-items/rental-items-page.tsx` so the mobile title row becomes the collapse/expand toggle for the toolbar, search input, and mobile filters area.
- Used a simple chevron indicator that rotates between expanded `up` and collapsed `down`.
- Kept desktop/tablet title layout unchanged.

Verification:

- `npm run typecheck` passed in `panel`.
- `npm run lint` passed in `panel`.
- `npm run build` passed in `panel`; only the existing Vite chunk-size warning was emitted.
- Headless browser validation at `390x844` confirmed:
  - expanded mobile state shows the action row, add button, and search field;
  - tapping the `Бытовки / 120 шт.` header collapses the menu content to `0px` height;
  - the chevron is `180deg` while expanded and `0deg` while collapsed;
  - collapsed mobile state shows only the title row plus chevron, matching the requested layout.
- Headless browser validation at `844x390` confirmed landscape phone grid mode still has no visible `до NxN` format picker.

## 2026-07-09 Panel Sidebar Brand Toggle

Request:

- Remove the separate small collapsed sidebar button shown in the icon strip.
- Clicking the `WMS Panel` brand block with the blue warehouse icon should collapse and expand the sidebar.
- Collapsed sidebar should be the blue warehouse icon width plus side padding, with other visible icons matching that control size.

Actions:

- Used React documentation through Context7 for conditional UI/event-handler guidance.
- Used shadcn sidebar/button docs and existing local sidebar components.
- Removed the separate `PanelLeftClose`/`PanelLeftOpen` controls from `panel/src/components/app-sidebar.tsx`.
- Converted the sidebar brand block from a home link plus separate button into a single `SidebarMenuButton` toggle.
- Kept the collapsed width at `48px`, with a `32px` blue warehouse icon/control and `8px` side padding.

Verification:

- `npm run typecheck` passed in `panel`.
- `npm run lint` passed in `panel`.
- `npm run build` passed in `panel`; Vite emitted only the existing chunk-size warning.
- Browser validation on `http://127.0.0.1:8080/` confirmed:
  - there is one brand sidebar toggle;
  - separate `Свернуть меню` and `Развернуть меню` buttons are not visible;
  - clicking the brand block collapses the sidebar from `256px` to `48px`;
  - collapsed brand icon/control is `32px` wide and starts at `x=8`;
  - visible collapsed menu controls render as `32px` buttons;
  - clicking the brand block again expands the sidebar back to `256px`;
  - mobile viewport still shows the fixed mobile sidebar trigger.

## 2026-07-09 Panel Mobile Sidebar Button Removal

Request:

- The mobile sidebar trigger icon overlaps/looks wrong near the `WMS Panel` brand area.
- Remove that button from the mobile version.
- Show the selected warehouse code, for example `СПБ`, in the sidebar brand block; warehouse codes are expected to be three letters.

Actions:

- Removed the fixed mobile `SidebarTrigger` from `panel/src/App.tsx`.
- Removed the mobile-only top padding that existed only to clear the fixed trigger.
- Updated `panel/src/components/app-sidebar.tsx` so the brand subtitle renders `selectedWarehouse.code` instead of `Складская система`.
- Kept desktop brand-block collapse/expand behavior unchanged.

Verification:

- `npm run typecheck` passed in `panel`.
- `npm run lint` passed in `panel`.
- `npm run build` is blocked by unrelated existing edits in `panel/src/features/settings/estimates-repairs/estimates-repairs-settings-page.tsx`.
- Browser validation on `http://127.0.0.1:8080/` confirmed:
  - desktop brand text is `WMS Panel / СПБ`;
  - desktop brand toggle still collapses sidebar from `256px` to `48px` and expands back;
  - `button[data-sidebar="trigger"]` count is `0` on desktop and mobile;
  - mobile viewport `390x844` has no fixed top-left sidebar button and main content starts at the top without extra trigger padding.

## 2026-07-09 Panel Estimate Catalog Canvas Polish

Request:

- Make constructor toolbar controls equal size and visually aligned with the `Двери` category title.
- Hide `RepairEstimateCatalogCanvas.view` and similar legacy technical labels from the UI.
- Remove the duplicate bottom action row.
- Show an interactive connection preview while dragging from one point to another.
- Keep the delete overlay icon a solid destructive color on hover, not transparent.

Actions:

- Added a live SVG preview path for draft links while dragging between card connection points.
- Passed the selected link type into the canvas so preview style matches `Путь` or `Зависимость`.
- Set constructor toolbar button/select controls to consistent `w-52 h-9 md:h-9` sizing.
- Removed the legacy view-id badge from the estimate action header.
- Removed the bottom duplicate action row and the unused link editor dialog state.
- Changed the delete overlay trash icon hover from `text-destructive/55` to `text-destructive`.

Verification:

- `npm run typecheck` passed in `panel`.
- Targeted `npx eslint src/features/settings/estimates-repairs/estimates-repairs-settings-page.tsx` passed.
- `npm run lint` passed in `panel`.
- `npm run build` passed in `panel`; only the existing Vite chunk-size warning was emitted.
- Browser validation on `/settings/estimates-repairs` confirmed:
  - constructor controls render at equal `208x36` dimensions;
  - no `RepairEstimateCatalog*.view` text is visible;
  - bottom duplicate buttons `Редактировать блок`, `Удалить блок`, `Редактировать связь`, and `Удалить связь` are absent;
  - `path[data-catalog-link-preview="true"]` appears during point-to-point link dragging;
  - delete overlay icon hover uses non-transparent `text-destructive`.

## 2026-07-09 Panel Mobile Blue Sidebar Toggle

Request:

- Restore the blue mobile sidebar button.
- Keep it fixed in the upper-left corner.
- Make the left sidebar visually expand from the button and collapse back into it.
- Keep the interaction polished with a clean open/close animation.

Actions:

- Added a custom mobile-only blue warehouse-icon toggle in `panel/src/App.tsx` using the existing sidebar context.
- Kept the button visible above the mobile sheet so tapping it again closes the sidebar.
- Restored mobile content top padding so the fixed button does not cover page content.
- Hid the duplicate warehouse icon inside the mobile sidebar brand row and shifted `WMS Panel / СПБ` text to the right.
- Adjusted the mobile sidebar sheet animation to `300ms` `ease-out` left-side slide classes.
- Kept desktop brand-block collapse/expand behavior unchanged.

Verification:

- `npm run typecheck` passed in `panel`.
- `npm run lint` passed in `panel`.
- `npm run build` passed in `panel`; Vite emitted only the existing chunk-size warning.
- Browser validation on `http://127.0.0.1:8080/` confirmed:
  - mobile viewport `390x844` has one fixed blue toggle at `x=12`, `y=12`;
  - tapping it opens the sidebar sheet and changes the button state to open;
  - tapping it again closes the sheet and keeps the button visible;
  - open mobile sidebar shows `WMS Panel / СПБ` and no second visible blue warehouse icon in the brand row;
  - mobile sheet has `duration-300`, `ease-out`, and left slide open/close classes;
  - desktop viewport `1280x720` still collapses from `256px` to `48px` and expands back through the brand block.

## 2026-07-09 Panel Responsive Sidebar Morph

Request:

- Make the mobile sidebar collapse into the fixed blue button with an animation.
- Apply the same mobile behavior in phone landscape orientation, while keeping the open sidebar scrollable.
- Keep tablet and desktop navigation as a smooth icon-only rail when collapsed.

Actions:

- Added a mobile Sheet marker and CSS morph that transitions the panel to `40x40px` at `12px,12px` before Radix removes it.
- Switched the mobile Sheet to non-modal mode so the fixed blue toggle remains interactive above the open sidebar; outside clicks still close it through the Sheet overlay flow.
- Added explicit mobile closed-state coordinates to avoid the base Sheet `inset-y` rules moving the panel downward during the transition.
- Extended mobile detection to touch devices with a short landscape viewport: `(max-height: 500px) and (pointer: coarse)`.
- Kept the mobile sidebar content internally scrollable and made desktop/tablet width, gap, and label opacity transitions use the same eased timing.

Verification:

- `npm run typecheck` passed in `panel`.
- `npm run lint` passed in `panel`.
- `npm run build` passed in `panel`; Vite emitted only the existing chunk-size warning.
- Browser validation confirmed mobile close samples reach `x=12,y=12,width=40,height=40` and then unmount, the fixed button remains interactive, and desktop collapsed inner sidebar width is `48px`.
- Browser validation confirmed the mobile content region uses `overflow-y: auto`; touch landscape classification is covered by the shared media query and hook.

## 2026-07-09 Panel Mobile Sidebar Keyframe Morph

Request:

- Replace the mobile sidebar's abrupt hide/show behavior with a visually clear expansion from the fixed blue button and a matching collapse back into it.

Actions:

- Replaced the previous transition-only mobile Sheet styling with `360ms` keyframe animations that animate position, width, height, border radius, background color, and shadow between the button and the full sidebar.
- Added a marked mobile sidebar content wrapper so labels and controls fade/translate in after the panel begins expanding, then disappear before it contracts.
- Removed `!important` from animated geometry rules after browser verification showed it pinned the Sheet at its final size and bypassed the keyframes.
- Removed local left-side slide classes from the mobile Sheet; the keyframe morph is now the single owner of the transition.

Verification:

- `npm run typecheck` passed in `panel`.
- `npm run lint` passed in `panel`.
- `npm run build` passed in `panel`; Vite emitted only the existing chunk-size warning.
- Browser validation at `390x844` confirmed the mobile Sheet uses `mobile-sidebar-expand` and `mobile-sidebar-collapse`; its close animation passes through an intermediate `64x117px` frame at the toggle position before unmounting.

## 2026-07-10 Panel Mobile Sidebar Visual Cleanup

Request:

- Remove the visible white outline around the fixed mobile sidebar button.
- Do not recolor the collapsing mobile menu blue; let it gradually disappear instead.

Actions:

- Removed the active-state ring from the fixed blue toggle and explicitly set its border width to zero.
- Removed the left-side Sheet border for the marked mobile sidebar.
- Kept the panel background equal to the normal sidebar color throughout both keyframes and added parent opacity from `1` to `0` during collapse.

Verification:

- `npm run typecheck` passed in `panel`.
- `npm run lint` passed in `panel`.
- `npm run build` passed in `panel`; Vite emitted only the existing chunk-size warning.
- Browser validation at `390x844` confirmed the button and mobile Sheet have `0px` borders; during the close keyframe the Sheet remains the normal sidebar color and its opacity reaches zero.

## 2026-07-09 Panel Estimate Catalog Legacy DB Link Sync

Request:

- Transfer all estimate catalog constructor links from the old project into the mock, using database files as the source of truth.

Actions:

- Located `old_db/hsqldb/wmspanel.script` as the working legacy HSQLDB snapshot.
- Parsed live `REPAIR_ESTIMATE_CATALOG_NODE` and `REPAIR_ESTIMATE_CATALOG_LINK` rows from the script.
- Regenerated `panel` estimate catalog seed with 232 nodes and 254 explicit database links.
- Removed mock fallback that derived visible `FOLLOW_UP` links from `parentId`.
- Moved estimate catalog localStorage state to `rwms:repair-estimate-catalog:v2` to avoid stale `v1` data.

Verification:

- Parsed DB counts: 232 nodes, 254 links, 182 `FOLLOW_UP`, 72 `DEPENDENCY`, zero broken link references.
- `npm run typecheck` passed in `panel`.
- `npm run lint` passed in `panel`.
- `npm run build` passed in `panel`; Vite emitted only the existing chunk-size warning.
- Browser validation on `/settings/estimates-repairs` confirmed the constructor shows `232 узлов` and `254 связей`; the `Двери` category shows 46 records, renders 56 link hit paths, and has no `seed-parent-link:*` generated links.

## 2026-07-09 Estimate Catalog Code And Comment UI

Request:

- Hide technical catalog codes, constrain comments to 180 characters with a warning/error treatment, and keep complete comments visible in expandable catalog blocks.

Actions:

- Updated the estimate catalog editor, canvas cards, and catalog section table in `panel`.
- Hid all operator-facing code fields/descriptions while retaining legacy-compatible codes in the mock store; new nodes receive a generated technical code.
- Added a 180-character counter, invalid field treatment, warning text, disabled save state, and store-layer validation.
- Removed comment truncation and synchronized canvas link geometry/bounds with measured card heights.
- Documented the target-UI decision and final backend code-generation `UNKNOWN` in `09_MIGRATION` and durable agent memory.

Verification:

- `npm run typecheck`, `npm run lint`, and `npm run build` passed in `panel`; only the existing Vite chunk-size warning remained.
- Browser validation on `/settings/estimates-repairs` confirmed no code is shown in the block editor; 181 characters produces red invalid styling, warning text, and disabled save; 180 characters is valid and saveable; constructor blocks use a minimum height without a maximum cap.

## 2026-07-10 Estimate Catalog Section Tree Filtering

Request:

- Show the existing mock work, material, and furniture records in their settings categories without duplicating catalog data.

Actions:

- Changed the section DTO to expose the one complete catalog node tree instead of a separate leaf-item array.
- Derived each section's operator rows from that tree and used the same tree to traverse parent chains while filtering a category.
- Preserved the existing category-specific work/material/furniture filtering and the source node records.

Verification:

- `npm run typecheck`, `npm run lint`, and `npm run build` passed in `panel`; Vite emitted only the existing chunk-size warning.

## 2026-07-10 Mobile Landscape Content Clearance

Request:

- Prevent the persistent mobile sidebar toggle from covering the rental registry title after the phone rotates to landscape.

Actions:

- Added `data-mobile-main` to the app content container and applied a static `56px` top padding under the shared mobile media condition.
- The rule overrides the width-only `md:pt-4` utility for short coarse-pointer landscape phones without restoring any open/close padding animation.

Verification:

- Browser validation at `667 x 390` confirmed the mobile toggle ends at `y=52`, while the first content block begins at `y=56`; the main background remains at `y=0`.
- `npm run typecheck` and `npm run lint` passed in `panel`.
- `npm run build` is blocked by an unrelated nullability error in `src/features/repair-estimates/domain/repair-estimate-domain.ts`.

## 2026-07-10 Mobile Rental Heading Alignment

Request:

- Place the rental registry heading and item count beside the fixed mobile sidebar icon, with a gap matching the icon's outer left gutter.

Actions:

- Superseded the temporary global mobile top-padding treatment and restored the regular app-content padding.
- Added `data-mobile-page-heading` to the rental registry title row and applied a mobile-only `3rem` left margin.

Verification:

- Browser validation at `390 x 844` confirmed the icon occupies `x=12..52` and the title row begins at `x=64`, leaving a `12px` horizontal gap while sharing the same top row.
- `npm run typecheck`, `npm run lint`, and `npm run build` passed in `panel`; Vite emitted only the existing chunk-size warning.

## 2026-07-10 Mobile Sidebar Background Alignment

Request:

- Align the mobile sidebar and page background at the top and prevent the background from shifting during the sidebar animation.

Actions:

- Removed the dynamic mobile `main` top-padding transition and its open-sidebar offset from `panel/src/App.tsx` and `panel/src/index.css`.
- Kept the page inset, page background, and open mobile Sheet on the shared top coordinate (`0`) without a background divider.

Verification:

- Browser validation at `402 x 874` confirmed `main`, `SidebarInset`, and the open Sheet all start at `y=0`; no mobile background divider is present.
- `npm run typecheck` and `npm run lint` passed in `panel`.
- `npm run build` remains blocked by pre-existing nullability errors in `src/features/repair-estimates/repair-estimate-editor-dialog.tsx`.

## 2026-07-10 Mobile Landscape Sidebar Access

Request:

- Keep the mobile sidebar control available when a phone is rotated and allow the sidebar menu to scroll.

Actions:

- Replaced the width-only Tailwind visibility rule on the mobile toggle with `data-mobile-sidebar-toggle`, revealed by the same mobile media query used by the mobile sidebar.
- Made the mobile navigation content explicitly vertically scrollable with contained overscroll and vertical touch gestures.

Verification:

- Browser validation at `667 x 390` confirmed the toggle is visible at `12 x 12` with a `40px` square, opens the `288 x 390` Sheet, and stays available to close it.
- The navigation region has `270px` client height and `552px` scroll height; a scroll gesture changes its `scrollTop` to `240px`.
- `npm run typecheck`, `npm run lint`, and `npm run build` passed in `panel`; Vite emitted only the existing chunk-size warning.
- Browser validation confirmed `Работы → Окна/Витражи` displays 9 rows and `Мебель` displays 11 rows.

## 2026-07-10 Mobile Sidebar Border Consistency

Request:

- Make all mobile sidebar lines use a consistent visual treatment.

Actions:

- Updated the mobile sidebar CSS in `panel/src/index.css` so Sheet boundaries, header separators, and form controls resolve through `--sidebar-border`.
- Added a one-pixel outer Sheet edge for both left and right mobile sidebars, with matching border-color animation.
- Kept the collapsed Sheet border transparent so the fixed blue toggle stays free of a visible outline.

Verification:

- Browser validation at `390 x 844` confirmed the opened Sheet edge and header divider use the same resolved border token; during collapse the edge fades to transparent and the unmounted toggle has a `0px` border.
- `npm run typecheck`, `npm run lint`, and `npm run build` passed in `panel`; Vite emitted only the existing chunk-size warning.

## 2026-07-10 Mobile Sidebar Background Divider

Request:

- Continue the mobile sidebar header divider across the background behind the sidebar, at the line marked by the user.

Actions:

- Replaced the mobile Sheet outer edge with `MobileSidebarBackgroundDivider` in `panel/src/App.tsx`.
- The divider appears only while the mobile sidebar is open, sits behind the Sheet, and aligns at the `64px` bottom edge of the sidebar header across the full inset.

Verification:

- Browser validation at `390 x 844` confirmed the background divider is `1px` high at `y=64` across `390px`, matches the sidebar-header height, and the Sheet has no right border.
- Browser validation confirmed the divider unmounts together with the closed mobile Sheet.
- `npm run typecheck`, `npm run lint`, and `npm run build` passed in `panel`; Vite emitted only the existing chunk-size warning.

## 2026-07-10 Mobile Sidebar Content Offset

Request:

- Remove the background divider and keep the content card below the expanding mobile menu with the same gap it has below the collapsed toggle.

Actions:

- Removed `MobileSidebarBackgroundDivider` from `panel/src/App.tsx`.
- Added responsive mobile content-offset rules in `panel/src/index.css`: the card starts at `56px` with the closed `40px` toggle and moves to `68px` with the open `64px` sidebar header.
- Kept the `4px` gap synchronized with the `360ms` sidebar morph and applied the baseline mobile offset in touch landscape as well.

Verification:

- Browser validation at `390 x 844` confirmed `toggle.bottom=52`, `card.top=56` when closed and `header.bottom=64`, `card.top=68` when open; the background divider is absent in both states.
- `npm run typecheck`, `npm run lint`, and `npm run build` passed in `panel`; Vite emitted only the existing chunk-size warning.

## 2026-07-10 Panel Operational Repair Estimates And Mock Boundaries

Request:

- Analyze the legacy estimate/estimate-constructor behavior and migrate the operational estimate screen into the current `panel` without replacing newer target UI decisions with old framework shapes.
- Connect operational estimates to estimate catalog settings.
- Keep a working mock suitable for future microservice extraction.
- Use delegated implementation while the main agent performs analysis and review.

Actions:

- Kept operational `/estimates` separate from `/settings/estimates-repairs` and connected them through a neutral repair-catalog read port and shared catalog query-key prefix.
- Added the operational draft/completed list, deep-link fullscreen workspace, structured lines, catalog picker, photos, exact totals, draft save, and manual/automatic completion planning.
- Replaced the modal editor shape with a responsive workspace: four desktop zones (`Фото`, `Информация`, `Смета`, `Действия`) and one-column smaller-screen flow.
- Removed `Кому` from the new editor/list while preserving optional `destinationText` and deprecated `destinationParty` compatibility in the boundary model.
- Added a paged/searchable rental-item picker and a photo manager with file selection, desktop drag-and-drop, preview, delete, and staged persisted rotation metadata.
- Introduced separate estimate, media, rental-inventory read, catalog-read, and workflow-status ports.
- Hid estimate persistence and media persistence behind versioned mock adapters. The media localStorage implementation recorded at this point was later **SUPERSEDED** by the IndexedDB adapter documented on 2026-07-10; it is not the current media store.
- Added warehouse checks, optimistic version comparison, serialized mock mutations with an origin-wide Web Lock where available, completed-estimate immutability, stable line-key validation, and decimal-string/`BigInt` money arithmetic.
- Kept completion state and task-plan/outbox references in one estimate commit, used media compensation across the separate mock boundary, and represented workflow dispatch as `PENDING_EXTERNAL_DISPATCH` without fake task generation.
- Kept `movementRequired` as a mock flag and avoided cross-store rental-item, equipment/accessory, repair-process, queue-board, and task-board mutations.
- Added `09_MIGRATION/panel_repair_estimates.md` and updated migration index, durable decisions, and unknowns.

Evidence:

- `panel/src/App.tsx`
- `panel/src/features/repair-estimates`
- `panel/src/features/repair-estimate-catalog`
- `panel/src/features/settings/estimates-repairs`
- `wms-panel-old/src/main/java/dev/buhanzaz/wmspanel/view/repairestimate/RepairEstimateView.java`
- `wms-panel-old/src/main/java/dev/buhanzaz/wmspanel/service/RepairEstimateService.java`

Verification:

- Documentation subtask re-read the implemented ports, adapters, domain helpers, route wiring, catalog facade, settings invalidation, and current database-derived seed metadata.
- `rg` confirmed estimate/media `localStorage` access is confined to their adapter files and the estimate feature has no task-board/equipment mutation imports.
- `npm run typecheck`, `npm run lint`, and `npm run build` passed after the stable fullscreen workspace implementation; Vite emitted only the existing chunk-size warning.
- Browser regression verification remains owned by the main review before commit and is not asserted by this documentation subtask.

## 2026-07-10 Mobile Sidebar Compact Spacing

Request:

- Reduce the visible gap between the persistent mobile sidebar button and the rental registry heading, and remove the control collision visible when the sidebar is open.

Actions:

- Reduced the mobile-only rental heading offset from `3rem` to `2.5rem` in `panel/src/index.css`.
- Hid the desktop sidebar rail inside `[data-mobile-sidebar="true"]` so its `sm:flex` behavior cannot leak into the mobile Sheet on wide or landscape phones.

Verification:

- Browser check at `390x844`: the toggle occupies `x=12..52`, the registry heading starts at `x=56`, leaving a `4px` gap; the expanded Sheet reports the sidebar rail as `display: none`.
- `npm run typecheck`, `npm run lint`, and `npm run build` passed in `panel` (Vite retained its existing chunk-size warning).

## 2026-07-10 Global Mobile Page Title Alignment

Request:

- Apply the compact mobile alignment beside the persistent sidebar toggle to every page, not only the rental registry.

Actions:

- Generalized the rule in `panel/src/index.css` for the initial `h1` or `h2` of each routed page.
- Kept the rental registry's marked title from receiving a duplicate margin.
- Added a card-padding correction and empty-page fallback so all initial page content begins at the same horizontal coordinate.

Verification:

- Browser at `390x844`: rental registry, equipment, the initial estimates-settings title, and the empty home-page message all begin at `x=56`, leaving `4px` after the `x=12..52` toggle.
- `npm run typecheck`, `npm run lint`, and `npm run build` passed in `panel`; Vite retained its existing chunk-size warning.

## 2026-07-10 Mobile Estimates Settings Frame Removal

Request:

- Remove the rounded outer frames from mobile estimates/repairs settings and make the screen read like the unframed warehouse registry.

Actions:

- Removed border, corner radius, and card background from the direct settings sections only under the shared mobile viewport condition.
- Kept the first settings title aligned at `x=56` by accounting for the remaining inner header padding.

Verification:

- Browser at `390x844`: both settings sections report `0px` border radius and border width with transparent outer background; `Настройка смет` begins at `x=56`.
- The rental registry still begins at `x=56`; `npm run typecheck`, `npm run lint`, and `npm run build` passed in `panel` (existing Vite chunk-size warning only).

## 2026-07-10 Operational Estimate Fullscreen UI Polish

Request:

- Replace the operational estimate modal editor with a fullscreen route-local workspace based on the supplied 2-by-2 layout.
- Keep one top-level `Создать смету` action and preserve the list when the workspace is closed.
- Remove `Кому`, keep four information controls in one desktop row, and make the layout one-column on mobile.
- Add photo management with desktop multi-file drag-and-drop, file picker, previews, delete, and staged rotation; keep mobile button-only file selection.
- Make catalog cards complete click targets, simplify estimate-line labels, hide line source metadata, and remove border/overflow artifacts.

Actions:

- Added the non-modal workspace, responsive 2-by-2 shell, debounced paged rental picker, and responsive photo-manager dialog under `panel/src/features/repair-estimates`.
- Used the exact zones `Фото`, `Информация`, `Смета`, and `Кнопки`; used inset rings and grid clearance for clean outer edges.
- Removed the secondary empty-state create action and disabled the sole header create action while a workspace is active.
- Removed `Кому` from the workspace/list while retaining destination compatibility fields at the service boundary.
- Made every catalog card one keyboard-accessible button with no nested `Открыть` button.
- Changed visible line headings to `Смета` and `Комментарий`; hid `Источник` without removing origin metadata.
- Set the photo dialog title to `Добавить фото`; desktop attaches multi-file drag/drop handlers, while mobile attaches no drag/drop handlers and keeps the multiple file picker.
- Constrained dialog/grid minimum widths to remove mobile horizontal scrolling.

Verification:

- `npm run typecheck`, `npm run lint`, and `npm run build` passed; Vite retained only its existing chunk-size warning.
- `git diff --check` passed with line-ending warnings only.
- Browser QA passed at desktop `1353px` and mobile `390x844`: clean 2-by-2/single-column layouts, no page overflow, no visible mobile dialog scrollbar, exact labels/actions, working catalog-card navigation, rental search/select/save/reopen/completion lifecycle, and no new console errors.

## 2026-07-10 Operational Estimate Review Hardening

Request:

- Review and correct concurrent estimate-catalog settings writes, queue fallback propagation, catalog-search transitions, editor interaction during pending writes, and estimate load/completion error presentation.

Actions:

- Upgraded `rwms:repair-estimate-catalog:v2` to a backward-compatible service/schema/revision envelope while preserving legacy v2 payloads that contain only `nodes` and `links`.
- Serialized every catalog node/link save/delete/reset through one in-process queue and one origin-wide Web Lock when supported. Each mutation captures an expected revision, re-reads under the lock immediately before its synchronous commit, increments the revision once, and rejects a conflicting concurrent mutation instead of silently overwriting it.
- Preserved both `workQueueCode` and `routeQueueKind` from the effective catalog binding node. Explicit queue code remains the task-plan grouping priority; route kind remains available as fallback metadata.
- Cleared global catalog search when selecting or navigating through work, material, category, subcategory, or option results so linked material/location steps become visible immediately.
- Disabled every estimate editor mutation surface while draft save or completion is pending, including information fields, rental selection, lines, catalog selection, photos, and completion-plan controls.
- Separated detail-query failures from successful `null` results, and rendered completion mutation errors inside the still-open completion dialog for retry or cancel.

Evidence:

- `panel/src/features/settings/estimates-repairs/api/repair-estimate-catalog-store.ts`
- `panel/src/features/repair-estimate-catalog/api/repair-estimate-catalog-api.ts`
- `panel/src/features/repair-estimates/repair-estimate-catalog-picker.tsx`
- `panel/src/features/repair-estimates/repair-estimate-editor-workspace.tsx`
- `panel/src/features/repair-estimates/repair-estimate-completion-dialog.tsx`
- `panel/src/features/repair-estimates/repair-estimates-page.tsx`

Verification:

- `npm run typecheck`, `npm run lint`, and `npm run build` passed in `panel`; Vite retained only the existing chunk-size warning.
- `git diff --check` passed with line-ending warnings only.
- A focused Vite runtime scenario confirmed that two concurrent catalog writes produce one commit and one visible conflict, retry advances the revision, and an old v2 payload preserves its node count.
- A focused catalog/domain runtime scenario confirmed task plans retain `INTERNAL_WORKS + REPAIR` and `SANITARY_DISINFECTION + HOLDING`.
- Interactive browser regression was not asserted in this review because the in-app browser had no available tab.

## 2026-07-10 IndexedDB Estimate Media Memory Reconciliation

Request:

- Reconcile project memory after the media-domain fix without changing code.
- Explicitly supersede the prior active claim that estimate media uses a localStorage v1/base64 envelope.
- Record current IndexedDB consistency, capacity, cache/invalidation behavior and browser-local new-draft date semantics.

Actions:

- Corrected the operational estimate boundary table and current mock facts to identify `IndexedDbRepairEstimateMediaAdapter`, database `rwms-repair-estimate-media`, object store `media`, and Blob-plus-metadata records.
- Recorded atomic IndexedDB write transactions guarded by a runtime-shared mutation queue and origin Web Lock.
- Recorded best-effort migration/removal of `rwms:repair-estimate-media:v1` and the absence of new base64/localStorage media writes.
- Recorded the 25 MB per-file, 250 MB per-batch, capacity-estimate, and quota-error behavior.
- Recorded the 24-entry LRU object-URL cache, in-flight hydration deduplication, `contentVersion` stale-result guard, `BroadcastChannel` invalidation, and `pagehide` cleanup.
- Preserved `RepairEstimateMediaClient` as the public boundary and recorded browser-local calendar initialization for new-draft `dispatchDate` while leaving warehouse-time-zone ownership `UNKNOWN`.

Evidence:

- `panel/src/features/repair-estimates/adapters/indexed-db-repair-estimate-media-adapter.ts`
- `panel/src/features/repair-estimates/ports/repair-estimate-media-client.ts`
- `panel/src/features/repair-estimates/api/repair-estimates-api.ts`
- `panel/src/features/repair-estimates/domain/repair-estimate-domain.ts`

Verification:

- Documentation-only reconciliation; no application code or commit was created by this task.
- Source inspection confirmed the IndexedDB adapter contains no `localStorage.setItem`; the legacy key is used only for migration read/remove behavior.

## 2026-07-10 Compact Operational Estimate Workspace

Request:

- Remove the visible names/descriptions from the four estimate workspace zones.
- Use a compact information form with `Общий комментарий`, internal estimate scrolling with a pinned total, explicit quantity controls, matching amount/comment widths, and a nine-card catalog page with footer arrows.

Actions:

- Updated only `panel/src/features/repair-estimates`; `wms-panel-old` remained read-only.
- Removed visible workspace-zone headings/descriptions and rebuilt information into label/control rows with a large common-comment textarea.
- Kept line edits within a scrollable estimate area while pinning the separator and total to the bottom.
- Replaced native number steppers with minus/value/plus controls, widened the calculated amount to the comment-column span, and added UI-local catalog paging (nine cards per level) with stateful footer arrows.
- Used the local shadcn component rules for card/form/input/button composition and the in-app browser workflow for focused local UI verification.

Verification:

- `npm run typecheck`, `npm run lint`, and `npm run build` passed in `panel`; Vite retained only its existing chunk-size warning.
- Browser verification on `/estimates` confirmed the compact structure, nine visible catalog cards, disabled previous pager, and enabled next pager. The local browser webview detached before second-page interaction; no unverified browser claim is recorded for that click.

## 2026-07-10 Estimate Breadcrumb Navigation And Information Balance

Request:

- Make the operational estimate catalog path clickable so the operator can return to any previous group/subgroup, restore the `Информация` label, and make photo/estimate wider than the information/catalog column.

Actions:

- Added accessible, clickable catalog breadcrumbs starting with `Главное меню`; ancestor selection restores that hierarchy level and resets local search, pending selection, feedback, and pagination state.
- Restored `Информация` as the sole workspace-card heading and retained the compact form without the former description.
- Rebalanced the desktop workspace grid from `11fr / 9fr` to `12fr / 8fr` so the photo/estimate column is wider.

Evidence:

- `panel/src/features/repair-estimates/repair-estimate-catalog-picker.tsx`
- `panel/src/features/repair-estimates/repair-estimate-workspace-layout.tsx`

Verification:

- `npm run typecheck`, `npm run lint`, and `npm run build` passed in `panel`; Vite reported only its existing chunk-size warning.

## 2026-07-10 Estimate Rental Picker Lazy Paging

Request:

- Remove the `Показать ещё` button from the бытовка picker and use lazy loading.

Actions:

- Replaced the explicit next-page button with an end-of-scroll trigger in the existing popover list. Fetches begin within `48px` of the list end and are guarded against concurrent next-page requests.

Evidence:

- `panel/src/features/repair-estimates/repair-estimate-rental-item-picker.tsx`

Verification:

- Browser verification at `/estimates` confirmed that opening and scrolling the picker shows no `Показать ещё` button.
- `npm run typecheck`, `npm run lint`, and `npm run build` passed in `panel`; Vite reported only its existing chunk-size warning.
- Browser flow at `/estimates` entered `Окна/Витражи`, exposed click targets for `Главное меню` and the current group, then returned to the root via `Главное меню`.
- Computed CSS inspection verified a wider left desktop column (`586.797px`) than the right information/catalog column (`391.203px`).

## 2026-07-10 Estimate Context Copy And Form Alignment

Request:

- Add short supporting information under `Информация` and `Каталог`, align and stretch the information fields, remove the page-level `Сметы`/warehouse label, and add another cancellation action.

Actions:

- Added card descriptions for `Информация` and `Каталог`; moved the catalog heading and description into its workspace card header.
- Fixed the information form to a shared `8rem` label column with flexible controls, so all three short controls align and use the remaining card width.
- Removed the operational-page heading and selected warehouse label. The editor now has a top `Отмена` action in addition to the existing footer action.
- Preserved empty-estimate behavior: a line is added only through the existing explicit manual/catalog actions, so a new draft remains valid without an accidental blank line.

Evidence:

- `panel/src/features/repair-estimates/repair-estimate-workspace-layout.tsx`
- `panel/src/features/repair-estimates/repair-estimate-editor-workspace.tsx`
- `panel/src/features/repair-estimates/repair-estimate-catalog-picker.tsx`
- `panel/src/features/repair-estimates/repair-estimates-page.tsx`

Verification:

- `npm run typecheck`, `npm run lint`, and `npm run build` passed in `panel`; Vite reported only its existing chunk-size warning.
- Browser verification at `/estimates` confirmed both descriptions, no page-level `Сметы` or warehouse label, two `Отмена` buttons in the workspace, and short fields that share one left edge and end at the card edge.

## 2026-07-10 Estimate Catalog Header Action Alignment

Request:

- Correct the `Настроить каталог` button after it moved lower than the catalog heading.

Actions:

- Moved the existing settings link from the catalog-content flow into the shadcn card header's `CardAction` slot.

Verification:

- Browser geometry at `/estimates` confirmed matching title/action top coordinates (`372.5px`).
- `npm run typecheck`, `npm run lint`, and `npm run build` passed in `panel`; Vite reported only its existing chunk-size warning.

## 2026-07-10 Estimate List Header And Empty-State Border

Request:

- Move `Требуют доработки` and `Завершённые` higher into one line with the estimate creation action, and correct the broken border on `Смет пока нет`.

Actions:

- Rebuilt the list-state header so the status tabs and `Создать смету` are in the same responsive row.
- Preserved the horizontal tab pair on mobile and offset it from the fixed sidebar toggle; the create action wraps below where needed.
- Added an internal horizontal inset to the empty-state card, preventing its shared outer ring from being clipped by the scroll container.

Evidence:

- `panel/src/features/repair-estimates/repair-estimates-page.tsx`
- Legacy status-filter evidence: `wms-panel-old/src/main/java/dev/buhanzaz/wmspanel/view/repairestimate/RepairEstimateView.java:129-131, 194-213`.

Verification:

- Playwright at `/estimates` verified the desktop header alignment, a visible complete empty-card border, and tab switching.
- Playwright at `390x844` verified horizontal tabs, clearance from the mobile menu button, and no page-width overflow.
- `npm run typecheck`, `npm run lint`, and `npm run build` passed in `panel`; Vite reported only its existing chunk-size warning.

## 2026-07-10 Task-Plan Group Comment Alignment

Request:

- Align `Комментарий группы` with the task-plan route field in one desktop row.

Actions:

- Aligned the route field to the start of the existing desktop grid row, keeping its label and selector level with the group-comment label and textarea.
- Preserved the legacy-derived multi-line group-comment field and the mobile single-column order.

Evidence:

- `panel/src/features/repair-estimates/repair-estimate-completion-dialog.tsx`
- `wms-panel-old/src/main/java/dev/buhanzaz/wmspanel/view/repairestimate/RepairEstimateView.java:2536-2548`

Verification:

- Playwright opened a manual completion plan and measured equal desktop label top coordinates (`553.75px`).
- At `390x844`, the same fields stacked correctly and `document.body.scrollWidth` remained `390px`.
- `npm run typecheck`, `npm run lint`, and `npm run build` passed in `panel`; Vite reported only its existing chunk-size warning.

## 2026-07-10 Separate Estimates And Repairs

Request:

- Add the operational `Ремонты` flow while keeping estimates and repairs separate, reuse the estimate workspace for direct repair creation, add author/list-column changes, and implement empty/nonempty estimate transitions.
- Follow-up: remove queue/route and the group-level subtask comment from repair detail, and show each work/material comment opposite its own line.

Actions:

- Added the separate `/repairs` list, direct repair editor, repair aggregate/port/mock adapter, and queued-repair subtask detail. Kept `/task-board` separate.
- Reused estimate presentation components while keeping direct repair commands and persistence separate from estimates.
- Added the required estimate/repair column orders and author snapshots.
- Coordinated empty estimate completion to `FREE` without a repair and nonempty completion to one cabin-root repair with snapshot subtasks, including material-only fallback.
- Removed queue/route and group-comment controls from repair detail. Added work/material comment pairs, responsive wrapping, and reorder-only persistence for multiple subtasks.
- Recorded legacy evidence and target/backend unknowns in `05_UI`, `06_BUSINESS`, `09_MIGRATION`, decisions, and unknowns.

Evidence:

- `panel/src/features/repair-estimates/`
- `panel/src/features/repair-tasks/`
- `panel/src/features/repairs/repairs-page.tsx`
- `panel/src/features/rental-items/api/rental-items-api.ts`
- `panel/src/App.tsx`
- `wms-panel-old/src/main/java/dev/buhanzaz/wmspanel/entity/CreateAuditEntity.java`
- `wms-panel-old/src/main/java/dev/buhanzaz/wmspanel/service/RepairEstimateTaskPlanService.java`
- `wms-panel-old/src/main/java/dev/buhanzaz/wmspanel/view/queueworkboard/QueueWorkBoardView.java`

Verification:

- `npm run typecheck`, `npm run lint`, `npm run build`, and `git diff --check` passed; Vite retained only its existing chunk-size warning.
- Playwright verified direct repair creation without an estimate, nonempty estimate-to-repair creation, empty estimate-to-`FREE` without another repair, exact list columns, author, and separate `/task-board` routing.
- Focused desktop/mobile subtask QA verified line comments, absence of queue/group-comment controls, conditional reorder actions, no horizontal overflow at `390x844`, preserved hidden routing/group values after reorder, and zero browser-console errors.

## 2026-07-10 Repair Detail, Ordered Movement, And Estimate Amendment

Request:

- Show repair task photos and static information above a full-width ordered-subtask section.
- Include movement stages, allow arbitrary pre-start reordering, and use the same ordered planning concept for manual estimate completion.
- Keep completed-estimate cabin and totals static, allow guarded supplementation before the linked task starts, and add durable links in both directions.

Actions:

- Added a shared responsive detail workspace: photos top-left, information top-right, and full-width lower content; mobile order is photos, information, lower content.
- Added task/subtask kinds and statuses, `startedAt`, source-estimate version tracking, movement snapshots, ID-only reorder commands, and compatibility normalization for existing localStorage records.
- Added URL-backed `repairId`/`estimateId` selection and estimate/task cross-links.
- Added a static completed-estimate snapshot and a dedicated amendment command. Amendment locks the cabin, captures estimate and linked-task versions, preserves the task's accepted order and stable IDs, and synchronizes the same linked task only while it remains queued and unstarted.
- Added adapter guards preventing started/terminal repairs from being rewritten as drafts or queued tasks.
- Rejected a proposed fixed movement-position constraint because the product request explicitly permits arbitrary movement order and legacy route editing permits arbitrary movement reordering except for an in-progress entry.

Verification:

- `npm run typecheck`, `npm run lint`, `npm run build`, and `git diff --check` passed; Vite retained only its existing chunk-size warning.
- Playwright verified manual movement ordering, persisted task reordering with hidden metadata preserved, URL reloads and cross-links, static completed fields, successful amendment without order loss, version conflict after task start without partial estimate change, mobile `390x844` order/no overflow, exact repair columns, zero console errors, and empty estimate to `FREE` without a task.

## 2026-07-10 Operational Task Board Migration

Request:

- Analyze the legacy `QueueWorkBoardView` UI and migrate its main operational behavior into the current React `/task-board` using dnd-kit, while integrating with the existing repair workflow.

Actions:

- Audited the legacy board UI, `QueueBoardService`, entities, and service tests. Confirmed queue columns, REAL/SHADOW routing, queue-order take, pause/resume/complete, active-time accounting, cross-queue route swap, HOLDING ordering, worker assignment, and the absence of proven responsive behavior.
- Replaced the `/task-board` placeholder with a responsive multi-column board projected from the existing `RepairTaskDto` aggregate. No second task store was introduced.
- Added compatible subtask execution metadata: `PAUSED`, independent `queuePosition`, active timing, and assignee snapshot. All board mutations use the existing serialized/Web Lock boundary and task-version CAS.
- Implemented queue-scoped take, pause, resume, completion/promotion, catalog plus synthetic queues, SHADOW filtering, search, collapse, live elapsed time, repair/detail links, and direct-repair creation reuse.
- Implemented classic dnd-kit multi-container sorting with pointer/touch/keyboard sensors, `DragOverlay`, pointer-within collision filtering, exact before/after insertion, empty-column drops, one mutation on drop, rollback, and safe same-task REAL/future-stage route swap.
- Preserved board routing and priority through completed-estimate amendment synchronization.

Evidence:

- `panel/src/features/task-board/`
- `panel/src/features/repair-tasks/`
- `panel/src/features/repair-estimates/repair-estimate-completed-workspace.tsx`
- `panel/src/features/repairs/repairs-page.tsx`
- `panel/src/App.tsx`
- `wms-panel-old/src/main/java/dev/buhanzaz/wmspanel/view/queueworkboard/QueueWorkBoardView.java`
- `wms-panel-old/src/main/java/dev/buhanzaz/wmspanel/service/QueueBoardService.java`
- `wms-panel-old/src/test/java/dev/buhanzaz/wmspanel/service/QueueBoardServiceTest.java`

Verification:

- `npm run typecheck`, `npm run lint`, `npm run build`, targeted Prettier check, and `git diff --check` passed; Vite retained only its existing chunk-size warning.
- Playwright verified desktop and `390x844` layouts without document overflow, SHADOW/search behavior, create/detail navigation, line-level comments, take/pause/resume/complete with monotonic timing, promotion of the next stage, cross-queue DnD persistence, outside-drop cancellation, queue-order rejection through the API, safe duplicate-route swap, and zero console errors.

## 2026-07-10 Panel New York Dashboard Shell And Shared UI

Request:

- Add an application header, redesign the sidebar, and bring shared forms, buttons, cards, dialogs, tables, tabs, and related controls to the official shadcn New York v4 dashboard visual language.
- Reuse installed shadcn primitives instead of creating duplicate UI components.
- Keep the main panel workflows and domain navigation intact.

Actions:

- Audited the official `new-york-v4/dashboard-01` page, registry block, current Context7 sidebar guidance, the existing `radix-mira` primitives, and the target shell on desktop/mobile.
- Added a route-aware `SiteHeader`, switched the application to the inset dashboard composition, retained the real warehouse selector/navigation/settings, and preserved desktop icon collapse.
- Replaced the fixed blue mobile toggle and custom morph/title-offset CSS with the standard modal shadcn mobile `Sheet` controlled from the header.
- Adapted existing shared primitives to New York v4 geometry while preserving Hugeicons, semantic sky/mist/status colors, `Card size="sm"`, and the existing popover flex-column composition contract.
- Fixed integration regressions found by independent review: missing `TooltipProvider`, inset height clipping, settings-submenu collapse after navigation, stale mobile offsets, catalog min-content overflow, and popover gap loss.

Evidence:

- `panel/src/App.tsx`
- `panel/src/components/site-header.tsx`
- `panel/src/components/app-sidebar.tsx`
- `panel/src/components/ui/`
- `panel/src/features/repair-estimates/repair-estimate-catalog-picker.tsx`
- `panel/src/index.css`
- `https://ui.shadcn.com/view/new-york-v4/dashboard-01`

Verification:

- `npm run typecheck`, `npm run lint`, `npm run build`, and `git diff --check` passed; Vite retained only the existing chunk-size warning.
- Browser QA covered expanded/collapsed desktop sidebar, modal mobile navigation and close-on-route behavior, persistent settings submenu, task board, warehouse grid/table, rental-item dialog, estimate list/editor, responsive controls, console errors, catalog overflow, and exact inset top/bottom margins.

## 2026-07-10 Full Offcanvas Sidebar, Nested Header, And Workspace Back

Request:

- Align the `WMS Panel / СПБ` brand with the application header, replace the dotted sidebar icon, remove the exposed `Toggle Sidebar` control, and collapse the whole sidebar instead of leaving an icon rail.
- Make the brand navigate to the home page, render a nested breadcrumb header, and replace the top estimate/repair `Отмена` action with history-aware `Назад` behavior.

Actions:

- Replaced the header trigger icon with Hugeicons `PanelLeftIcon`, removed the rendered `SidebarRail`, localized sidebar accessibility copy, changed desktop/tablet collapse to shadcn `offcanvas`, and aligned the brand/header on the shared `3rem` height.
- Added the official shadcn breadcrumb primitive and route/query-aware parent/current chains for rental-item detail, settings children, estimate detail/create, and repair detail/create.
- Added URL-backed `?create=1` estimate/repair workspaces and a shared explicit history-state marker. Internal list, cross-entity, and task-board entries use browser back; direct deep links replace to the corresponding list.
- Kept footer/editor cancellation semantics unchanged and added explicit unavailable states for unknown estimate/repair IDs.

Verification:

- Independent review reported no P0-P3 findings.
- `npm run typecheck`, `npm run lint`, `npm run build`, and focused `git diff --check` passed; Vite retained only the existing chunk-size warning.
- Browser QA measured the brand/header center at `32px`, verified complete offcanvas removal at desktop and `820px`, verified nested/mobile breadcrumb behavior without horizontal overflow at `390x844`, exercised list/create/deep-link/task-board Back flows, and found no browser-console errors.

## 2026-07-10 Header Separator Height

Request:

- Make the gray vertical line between the header menu icon and the route title shorter, from `h-4` to `h-3`.

Actions:

- Read the required project memory and local shadcn skill.
- Confirmed the existing `SiteHeader` uses the installed shadcn `Separator` component.
- Changed only `panel/src/components/site-header.tsx`, setting the separator class from `h-4` to `h-3`.
- Kept unrelated working-tree changes untouched.

Verification:

- `npm run typecheck` passed in `panel`.
- `npm run lint` passed in `panel`.
- `npm run build` passed in `panel`; Vite emitted only the existing chunk-size warning.
- `git diff --check` passed.
- Browser DOM/computed-style check confirmed one header separator with `height: 12px` and `width: 1px`.

## 2026-07-10 Header Separator Visual Follow-Up

Request:

- The header separator still appeared unchanged after the `h-3` adjustment.

Actions:

- Verified the running `/warehouse` route: `h-3` was correctly applied and computed to `12px`; its difference from the old `16px` height was only four CSS pixels.
- Reduced the same existing shadcn `Separator` in `panel/src/components/site-header.tsx` from `h-3` to `h-2` for a visibly compact `8px` line.
- Kept unrelated working-tree changes untouched.

Verification:

- Browser DOM/computed-style check on `/warehouse` confirmed `h-2`, `height: 8px`, and `width: 1px`.
- Visual browser screenshot confirmed the shorter line after collapsing the sidebar.

## 2026-07-10 Header Separator Centering Follow-Up

Request:

- The separator must have equal spacing from the header's top and bottom edges.

Actions:

- Measured the `h-3` separator and found that the primitive's `data-vertical:self-stretch` aligned its 12px line to the top of its 32px flex parent instead of centering it.
- Restored `h-3` and added `data-vertical:self-center` on the existing `SiteHeader` separator.

Verification:

- Browser DOM/computed-style check on `/warehouse` confirmed `align-self: center`, a `12px` line, `17.5px` top gap, and `18.5px` bottom gap in the 48px header.

## 2026-07-10 Header Separator Double Height

Request:

- Make the centered header separator twice as tall.

Actions:

- Changed the existing `SiteHeader` separator from `h-3` (`12px`) to `h-6` (`24px`), preserving `data-vertical:self-center`.

Verification:

- Browser DOM/computed-style check on `/warehouse` confirmed `align-self: center`, `height: 24px`, `topGap: 11.5px`, and `bottomGap: 12.5px` in the 48px header.

## 2026-07-10 Header Separator Guide Alignment

Request:

- Align the header separator to the supplied blue horizontal guides.

Actions:

- Set the centered `SiteHeader` separator from `h-6` (`24px`) to `h-8` (`32px`).

Verification:

- Browser DOM/computed-style check on `/equipment` confirmed `align-self: center`, `height: 32px`, `topGap: 7.5px`, and `bottomGap: 8.5px` in the 48px header.

## 2026-07-10 Rental-Item Breadcrumb De-duplication

Request:

- Replace the generic duplicate `Склад > Бытовка` detail breadcrumb with `Склад / Москва / БЫТ-001`-style context.

Actions:

- Made `SiteHeader` resolve the current rental item and its assigned warehouse, then render `Склад / <city> / <item number>` for item-detail routes.
- Removed the duplicate breadcrumb from the rental-item detail content.

Verification:

- `npm run typecheck`, `npm run lint`, and `npm run build` pass in `panel`.
- Playwright check on `/warehouse/msk-1` confirms items `Склад`, `Москва`, `БЫТ-001` with slash separators and one route breadcrumb.

## 2026-07-10 Sidebar Navigation Alignment

Request:

- Make sidebar menu entries larger and align them to the top-left brand icon guide lines.

Actions:

- Increased primary navigation and Settings rows to 40px and wrapped their icons in 32px alignment cells.
- Kept group labels, warehouse selection, submenu styling, active state, and navigation behavior intact.

Verification:

- `npm run typecheck`, `npm run lint`, and `npm run build` pass in `panel`.
- Playwright confirms brand/menu icon cells both span `24px`–`56px`; primary menu labels begin at `64px` and rows are `40px` tall.

## 2026-07-10 Equipment Header Count And Cabin Label

Request:

- Move the gray equipment-position count from the page body to the header and shorten `В бытовках на складе` to `В бытовках`.

Actions:

- Removed the duplicate `/equipment` page heading and count.
- Added a selected-warehouse equipment count badge to the shared route header and preserved mock-data invalidation.
- Updated the desktop and mobile cabin-availability labels.

Verification:

- `npm run typecheck`, `npm run lint`, and `npm run build` pass in `panel`.
- Playwright checks on `/equipment` at `1280x720` and `390x844` confirm the header badge, both shortened labels, and zero browser-console errors.

## 2026-07-10 Sidebar Warehouse Selector Alignment

Request:

- Remove the separate `Склад` label and align the warehouse dropdown with the first page heading.

Actions:

- Removed the visual sidebar group label while retaining the existing shadcn combobox and warehouse-selection behavior.
- Adjusted the selector group's top offset and added the non-visual accessible name `Выбор склада`.

Verification:

- `npm run typecheck`, `npm run lint`, and `npm run build` pass in `panel`.
- Playwright geometry check on `/warehouse` confirms both selector and `Бытовки` heading centres are `y=94px`; the label list does not contain `Склад`.
# 2026-07-10 — Full-height sidebar brand surface

- Request: extend the sidebar brand-menu background to the full height marked by the header boundaries.
- Actions: made the brand `SidebarMenu`, its item, and its `SidebarMenuButton` fill the existing `--header-height` (`3rem`) without changing the shared menu-button size for ordinary navigation rows.
- Verification: `npm run typecheck`, `npm run lint`, and `npm run build` pass in `panel`. Playwright confirms the header and brand link both span `8px`–`56px` (`48px`); the hovered background is also `48px` tall.

## 2026-07-10 Operational Estimate And Repair List Grid

Request:

- Display estimates and repairs in a grid styled like the supplied compact, muted-header table.

Actions:

- Re-read required UI/migration memory and legacy/current list evidence.
- Added reusable `panel/src/components/operations-list-grid.tsx` using the installed shadcn table primitive and Hugeicons sort indicators.
- Replaced only the desktop list markup in `/estimates` and `/repairs`; retained existing columns, routing, row-opening actions, status presentation, mock APIs, and mobile cards.
- Added local, stable-ID sorting for every visible grid column.

Verification:

- `npm run typecheck`, `npm run lint`, `npm run build`, and `git diff --check` passed in `panel`; Vite emitted only its existing chunk-size warning.

## 2026-07-11 Microservice Auth And Task-Board Settings

Request:

- Split the Spring target into auth and task-board microservices, implement OIDC/PKCE login-04, migrate user/warehouse access and worker/queue settings from legacy, and connect the React settings pages.
- Use subagents for implementation while the main agent reviews and verifies.

Actions:

- Converted the root Gradle project into an aggregator with `services/auth-service` and `services/task-board-service`; added separate PostgreSQL Compose databases.
- Implemented Spring Authorization Server, USER/WORKER/SERVICE separation, JWT/JWKS, PKCE clients, client credentials, admin/current-user/worker-credential APIs, CSRF login, production safety, optimistic user/access updates, and the packaged shadcn login-04 UI.
- Connected panel OIDC/sessionStorage/callback/logout/Bearer clients and added real `/settings/users` and `/settings/task-board` pages using the shared grid, Field, AlertDialog, sonner, and dnd-kit.
- Implemented the task-board queue/workforce/task/history domain, Resource Server warehouse authorization, optimistic locking, delete rules, worker credential/deletion sagas, HOLDING/REAL/SHADOW behavior, and automatic interruption/resume relations.
- Performed an independent adversarial review and fixed service-token role confusion, stale versions, disabled-session issuance, CSRF masking, worker credential convergence, CORS, production secrets, logout token decoding/serialization, and frontend password-version refresh.

Verification:

- Clean root `gradlew clean build`: success; auth 20 tests and task-board 18 tests, including PostgreSQL 17 Testcontainers.
- Panel `typecheck`, full `lint`, and production `build`: success; only the existing chunk-size warning remains.
- Auth UI `typecheck`, `lint`, and `build`: success and packaged in the auth boot JAR.
- Real isolated browser E2E: PKCE login/callback/return path, users/access/password/deactivate, class/worker/credential/group/queue CRUD, DnD order, HOLDING-last, fail-closed deletes, cleanup, logout, and mandatory credential re-entry all passed with zero console errors.

Evidence:

- `services/auth-service/`
- `services/task-board-service/`
- `panel/src/features/auth/`
- `panel/src/features/settings/users/`
- `panel/src/features/settings/task-board/`
- `WMS_ARCHITECTURE_KNOWLEDGE/09_MIGRATION/services_auth_task_board.md`
- Browser check on `/estimates` confirmed a continuous gray five-column header with sort icons, completed estimate rows, and an ascending `Статус` sort state after interaction.
- `/repairs` had no seeded rows during browser verification; static checks confirm it uses the same shared grid component.

## 2026-07-10 Empty Estimate And Repair Grids

Request:

- Remove the empty-list message block and show an empty grid instead.

Actions:

- Removed the `Смет пока нет` and `Ремонтов пока нет` Card branches.
- Rendered the existing desktop `OperationsListGrid` for empty arrays, preserving the five column headers with zero table rows.
- Suppressed empty mobile card containers.

Verification:

- Browser checks on `/estimates` and `/repairs` each found one table, five headers, zero rows, and no prior empty-state wording.
- `npm run typecheck`, `npm run lint`, `npm run build`, and `git diff --check` passed; Vite emitted only its existing chunk-size warning.

## 2026-07-10 Full-Height Empty Estimate And Repair Grids

Request:

- Make the empty grid fill the page with equal bottom, left, and right spacing.

Actions:

- Added an optional layout class to `OperationsListGrid` and made both desktop list containers flex-fill their remaining workspace.
- Applied a minimum full workspace height to each desktop grid and removed the repair page's former one-pixel container offset.

Verification:

- Browser checks on empty `/estimates` and `/repairs` found five headers and zero rows, with exactly `24px` gaps at the left, right, and bottom of each main content area.
- `npm run typecheck`, `npm run lint`, `npm run build`, and `git diff --check` passed; Vite emitted only its existing chunk-size warning.

## 2026-07-10 Estimate And Repair Completion Notices

Request:

- Remove the `Смета завершена` and `Ремонт создан` success messages from the project UI.

Actions:

- Removed page-local notice state and all estimate/repair save-success messages.
- Preserved editor close, list navigation, and estimate-status selection behavior.

Verification:

- `npm run typecheck`, `npm run lint`, and `npm run build` pass in `panel`.
- Browser checks on `/estimates` and `/repairs` confirm both phrases are absent from the DOM and browser-console errors are zero.

## 2026-07-10 Settings Submenu Alignment

Request:

- Make the two settings sub-items match the style and spacing of primary sidebar rows.

Actions:

- Gave each submenu link the primary row height and icon-cell composition.
- Removed the inherited nested rail, indentation, and horizontal offset while preserving the submenu's expand and active-state behavior.

Verification:

- `npm run typecheck`, `npm run lint`, and `npm run build` pass in `panel`.
- Browser geometry check confirms the primary and submenu link coordinates and dimensions match exactly; browser-console errors are zero.

## 2026-07-10 Rental Registry Heading Removal

Request:

- Remove the `Бытовки` heading and its quantity badge from the warehouse registry.

Actions:

- Removed both desktop and mobile heading/count render paths.
- Kept the mobile parameter-collapse control with generic accessible text, preserving filter interaction without showing the removed copy.

Verification:

- `npm run typecheck`, `npm run lint`, and `npm run build` pass in `panel`.
- Browser checks on `/warehouse` at desktop and `390x844` confirm the removed text is absent and browser-console errors are zero.

## 2026-07-10 Task Board Empty Queue Presentation

Request:

- Remove the repeated `Очередь свободна / Перетащите сюда доступное подзадание.` blocks from the task board and rename the queue action to `Начать`.

Actions:

- Removed the empty-queue Card branch from `TaskBoardColumn`, retaining the surrounding dnd-kit droppable section and `SortableContext`.
- Renamed the existing take action from `Взять следующую` to `Начать`; preserved the `onTake(nextWaiting)` command and disabled-state conditions.

Verification:

- Legacy `QueueWorkBoardView` contains no per-column empty placeholder, and its take command remains `QueueBoardService.takeNextTask`.
- `npm run typecheck`, `npm run lint`, `npm run build`, and `git diff --check` passed in `panel`; Vite emitted only its existing chunk-size warning.
- Browser check on `/task-board` found empty queue columns with no removed text, a `Начать` button in each column, and zero console errors.

## 2026-07-10 Task Board Toolbar And Inactive Stages

Request:

- Prevent the horizontal scrollbar from covering the first queue's outline; remove manual reload; place creation immediately after search; replace the future checkbox with a smoky `Неактивные` button and show future stages as gray translucent cards.

Actions:

- Removed the refresh icon, callback, and import from `TaskBoardPage`.
- Reordered the toolbar to search, `Создать задание`, `Неактивные`, `Текущих`, `Будущих`, using matched 36px control heights.
- Replaced the checkbox with an `aria-pressed` button that toggles the existing local `showFuture` state. The off and active variants retain translucent muted styling.
- Styled SHADOW cards and their drag preview with a muted gray background, dashed border, and opacity. Increased the scroller bottom padding from `8px` to `16px`.

Verification:

- `npm run typecheck`, `npm run lint`, `npm run build`, and `git diff --check` passed in `panel`; Vite emitted only its existing chunk-size warning.
- Browser check on `/task-board`: zero `Обновить доску` controls; one `Неактивные` control switches `aria-pressed` `false → true → false`; console errors are zero. The queue-to-scroller bottom gap is `31px` with a `15px` scrollbar, up from the previous `23px` gap.
- The browser profile used for verification contained no SHADOW rows, so the fog styling is source-verified on the SHADOW-only card branch; visibility toggling itself is browser-verified.

## 2026-07-10 Task Board Counter Alignment

Request:

- Restore standard button colors and move `Текущих` / `Будущих` to the right without allowing queue scrolling to move them.

Actions:

- Restored the default primary Button variant for `Создать задание`.
- Made `Неактивные` use standard outline/default Button variants for inactive/active state; removed opacity overrides.
- Applied `ml-auto shrink-0` to the counter-badge group in the non-scrolling toolbar.

Verification:

- `npm run typecheck`, `npm run lint`, `npm run build`, and `git diff --check` passed in `panel`; Vite emitted only its existing chunk-size warning.
- Browser check on `/task-board`: counters stay at `x=1060.67px…1248px` while the queue scroller moves from `scrollLeft=1` to `665`; browser-console errors are zero.

## 2026-07-10 Estimate And Repair Editor Fields, Scroll, And Media

Request:

- Make estimate-line comments match neighbouring field height while remaining expandable; make the common comment fixed with matching panel insets; keep `Итого` static while only estimate lines scroll; fix photo preview, carousel, and full-screen display in estimates and repairs.

Actions:

- Set line comments to 36px with vertical resize and the common comment to a fixed 128px non-resizable field.
- Constrained the application workspace to the viewport and contained the estimate-line scrollers in estimate create, completed-estimate amendment, and repair-task workspaces.
- Passed media variants through the estimate/repair photo adapter, retained `blob:` preview URLs, made the carousel source type independent of rental items, and restored its full-screen viewer for repair media.

Verification:

- `npm run typecheck`, `npm run lint`, `npm run build`, and `git diff --check` passed in `panel`; Vite emitted only its existing chunk-size warning.
- Browser checks at a 720px viewport on `/repairs?create=1` and `/estimates?create=1` confirmed a fixed document height, a 36px line comment, a 128px common comment, vertical comment resize, static total while the lines scroller moved, an uploaded non-broken preview, and a working full-screen photo viewer.

## 2026-07-10 Unified Operational Grids And Registry Controls

Request:

- Reduce the warehouse toolbar controls from 40px to 36px and make estimate, repair, and supplemental-equipment grid styles consistent without changing the equipment grid's existing appearance.

Actions:

- Removed `h-10` overrides from warehouse toolbar controls so they inherit shadcn's 36px default height.
- Restored supplemental equipment to its original grid styling and aligned the shared estimate/repair grid to its header and cell-inset treatment.

Verification:

- `npm run typecheck`, `npm run lint`, and `npm run build` pass in `panel`.
- Browser checks confirm 36px warehouse controls and matching grid-header background, shadow, and 12px cell inset across `/equipment`, `/estimates`, and `/repairs`.

## 2026-07-10 Transparent Inactive Task-Board Button

Request:

- Make the background of the unpressed `Неактивные` task-board button transparent.

Actions:

- Kept the standard shadcn outline/default variants and added transparent fill only to the unpressed outline state.

Verification:

- `npm run typecheck`, `npm run lint`, `npm run build`, and `git diff --check` passed in `panel`; Vite emitted only its existing chunk-size warning.
- Browser check on `/task-board`: unpressed state returned `rgba(0, 0, 0, 0)` and `aria-pressed=false`; pressed state returned the primary `oklch(0.5 0.134 242.749)` fill and `aria-pressed=true`; toggling off restored the transparent fill.

## 2026-07-10 Future Task Cards Match Drag Appearance

Request:

- Make the visible inactive/future task cards look like the card while it is being dragged.

Actions:

- Extracted one SHADOW card presentation class and applied it to both the in-column card and `DragOverlay` preview, adding the matching elevated shadow and removing the distinct in-column hover-opacity override.

Verification:

- `npm run lint`, targeted ESLint for `task-board-card.tsx`, and `git diff --check` passed in `panel`.
- `npm run typecheck` and `npm run build` remain blocked by the pre-existing user change at `panel/src/features/rental-items/rental-items-table-view.tsx:154` (`((event: unknown) => void) | undefined` is incompatible with `() => void`).
- Browser check reached `/task-board`, but the selected warehouse had `Текущих: 0` and `Будущих: 0`, so no live future card was available for a visual overlay comparison. No test task was created.

## 2026-07-10 Warehouse Grid As The Shared Standard

Request:

- Use the warehouse grid as the exact visual and sorting standard for every current tabular grid, with matching arrows, fonts, shadows, heights, and natural-width headers.

Actions:

- Added one reusable grid sort button and shared header/cell presentation constants; migrated warehouse, estimates, repairs, and equipment to it.
- Added local three-state sorting to supplemental equipment for all visible desktop columns.
- Matched the browser-rendered warehouse geometry: 41px headers and 39px rows. Preserved existing column widths and natural sort-button widths.

Verification:

- `npm run typecheck`, `npm run lint`, `npm run build`, and `git diff --check` passed in `panel`; Vite emitted only its existing chunk-size warning.
- Browser checks on `/warehouse`, `/equipment`, `/estimates`, and `/repairs` confirmed shared Lucide arrows and grid typography. On `/equipment`, `Всего` sorts in both directions and updates `aria-sort` to `ascending` and `descending`.

## 2026-07-10 Unified Grid Row Height

Request:

- Make every primary grid row the same height as the 49px estimate-grid cell.

Actions:

- Replaced the prior 39px flex/table row sizing with shared exact 49px table and flex row constants.
- Applied the common row height to warehouse, equipment, and the shared estimates/repairs grid without changing column widths or cell padding.

Verification:

- Browser checks: warehouse first `tr`/`td` are 49px with 8px/12px padding; the first equipment row is 49px. Targeted ESLint and `git diff --check` passed.
- `npm run typecheck` passed. `npm run build` is currently blocked by an unrelated in-progress rental-item status addition: `rental-item-status-badge.tsx` lacks mappings for `WAITING_REPAIR_CHECK` and `WRITTEN_OFF`.

## 2026-07-10 Repair Acceptance, Rework Placeholder, And Write-Offs

Request:

- Complete the target repair cycle from estimate/direct repair through the task board to cabin acceptance or write-off; add worker-group assignments, execution timing, result photographs, acceptance/write-off dossiers, and responsive operational lists.
- Keep the main agent as reviewer and delegate product implementation to three subagents.

Actions:

- Delegated and reconciled three implementation areas: repair-task v2 domain/persistence, shared completion planner plus board execution, and acceptance/write-off UI.
- Added repair origin and acceptance lifecycle snapshots, stage/assignment timing, worker groups, result media, v1-to-v2 local-storage normalization, and compensated rental-item status transitions.
- Reused one completion planner for estimates and direct repairs; direct repairs now expose automatic/manual planning, movement stages, queue selection, and ordered task plans.
- Replaced free-text board assignment with queue-compatible worker-group/member selection. Stage completion uses the existing media manager, enforces 20-photo maximum and the conditional three-photo minimum, and compensates uploaded media after a failed command.
- Added `/acceptance` and `/write-offs`, responsive `OperationsListGrid`/cards, nested cabin breadcrumbs, history-aware Back, shared dossiers, time badges, group/worker selection, the test-only rework dialog, acceptance confirmation, and mandatory write-off reason.
- Added `WAITING_REPAIR_CHECK` and `WRITTEN_OFF` rental-item statuses; written-off cabins remain visible in the warehouse registry but are excluded from new estimate/repair pickers.

Verification:

- `npm run typecheck`, `npm run lint`, `npm run build`, and `git diff --check` passed; Vite emitted only the existing chunk-size warning.
- Browser QA covered direct repair creation through the shared manual planner, a two-worker group take/finish flow, board-to-acceptance promotion, selection and the `Тест` rework dialog, required write-off validation, write-off dossier/direct URL, desktop, tablet, and `390×844`; console errors were zero.
- The UI subagent also verified the acceptance action independently: the item leaves active acceptance and returns to rental status `FREE`.

Evidence:

- `panel/src/features/repair-tasks/`
- `panel/src/features/repair-estimates/repair-work-completion-dialog.tsx`
- `panel/src/features/task-board/`
- `panel/src/features/acceptance/`
- `panel/src/features/write-offs/`
- `panel/src/features/rental-items/model/rental-item.ts`

Follow-up correction:

- Added the destructive `Списать` action directly to the bottom-right action groups of the editable estimate and direct-repair workspaces.
- Both forms reuse one required-reason dialog and a separate versioned early-write-off command. The command records the available form/line/media snapshot, sets the cabin `WRITTEN_OFF`, and opens the new read-only write-off dossier without requiring board completion or acceptance.
- A new unsaved estimate does not create a fake estimate: its terminal task keeps `origin=ESTIMATE` with nullable source identifiers and renders no source link. Saved estimate drafts retain their real source identifiers; direct-repair drafts retain optimistic task id/version.
- Browser QA covered both lower actions, empty-reason validation, reload-safe estimate origin, missing fake estimate link, working direct-repair link, and exclusion of written-off cabins from subsequent pickers; console errors were zero.

## 2026-07-10 Write-Off Subsections And Legacy Furniture Mapping

Request:

- Split write-offs into `Склад` and `Доп. оборудование` subsections and verify the legacy estimate-to-accessory furniture mapping.

Actions:

- Kept cabin write-offs at `/write-offs` and added a sibling aggregate equipment projection at `/write-offs/equipment` under one collapsible sidebar parent.
- Confirmed the legacy `AccessoryItem.furnitureMaterial` setting and traced its estimate-save synchronization into rental-item accessory assignments and accessory stock balances.
- Documented that the current React equipment mock has only aggregate `writtenOffQuantity`; event-level reason, author, date, and dossier data remain unavailable.

Evidence:

- `panel/src/components/app-sidebar.tsx`
- `panel/src/features/write-offs/equipment-write-offs-page.tsx`
- `wms-panel-old/src/main/java/dev/buhanzaz/wmspanel/entity/AccessoryItem.java`
- `wms-panel-old/src/main/java/dev/buhanzaz/wmspanel/view/accessoryitem/AccessoryItemDetailView.java`
- `wms-panel-old/src/main/java/dev/buhanzaz/wmspanel/service/RepairEstimateService.java`
- `wms-panel-old/src/main/java/dev/buhanzaz/wmspanel/service/RentalItemService.java`

## 2026-07-10 Acceptance Rework Wizard And Child Lifecycle

Request:

- Replace the acceptance `Тест` placeholder with a guided rework flow: preliminary recipient scope, optional work/material selection, then the standard task editor with reason `Переделка`.

Actions:

- Added a two-step shadcn wizard and reused the existing repair editor and completion planner as the final step.
- Added `REWORK` task kind, `IN_REWORK` acceptance state, source-task snapshots, schema-v3 normalization, atomic source/child queue persistence, and acceptance/write-off cascading.
- Kept group/worker routing as explicit UI-only intent for now; final queue planning remains in the standard completion dialog and no worker assignment is persisted.
- Preserved explicit group selection separately from individual selection, including one-person groups; locked the cabin selector for rework tasks.

Verification:

- Targeted ESLint, typecheck, browser desktop and `390x844`, full queue transition, group/worker distinction, immutable cabin, and zero console errors passed.
- Full repository lint/build became blocked by unrelated concurrent auth/user-management changes; target files remained clean.

## 2026-07-10 Sidebar Submenu Hierarchy

Request:

- Improve the visually flat and oversized sidebar submenus.

Actions:

- Restored shadcn's nested menu composition for both `Списание` and `Настройки`.
- Made child links compact, inset, and connected with the primitive's guide rail; direct child icons now use the primitive's standard compact icon treatment.
- Replaced the collapsed upward chevron with a right-pointing indicator while retaining the down-pointing expanded indicator.

Verification:

- Browser checks at `1280x720` opened both submenus and confirmed their 32px child rows, inset guide rail, working expanded state, and zero application console errors.
- `npm run typecheck`, `npm run lint`, `npm run build`, and `git diff --check` passed in `panel`; Vite emitted only its existing chunk-size warning.

## 2026-07-11 Mobile CloudPub Development Access

Request:

- Fix the React panel failing to open from a mobile device through the active
  CloudPub tunnel.

Actions:

- Added the exact active CloudPub hostname to Vite `server.allowedHosts` while
  preserving host validation for all other domains.

Verification:

- A real local Vite request with the CloudPub `Host` header returned `200`, while
  an untrusted host remained blocked with `403`.
- Panel typecheck, lint, TypeScript build, Vite production build, and targeted
  `git diff --check` passed. Vite emitted only its existing chunk-size warning.

Evidence:

- `panel/vite.config.ts`

## 2026-07-11 CloudPub Mobile OIDC Session Fix

Request:

- Fix the mobile CloudPub page remaining indefinitely at `Проверяем сессию…`.

Actions:

- Confirmed the panel was using loopback issuer `http://localhost:9000`, which
  points to the phone rather than the development PC.
- Added a same-origin Vite proxy for OIDC, login assets, and auth APIs; selected
  the public CloudPub origin as the panel authority on the configured host.
- Added an auth-service `cloudpub` profile with matching issuer, redirects,
  origin, and secure session cookie.
- Made failed sign-in redirects transition to a visible retryable error instead
  of remaining in the loading state.
- Restarted auth-service with active profiles `dev,cloudpub`.

Verification:

- Panel typecheck, lint, TypeScript build, and Vite build passed; Vite emitted
  only its existing chunk-size warning.
- The full auth-service test task passed.
- Public CloudPub discovery returned the HTTPS issuer and public authorization
  and token endpoints.
- Public authorization returned `302` to the public `/login`, emitted a secure
  session cookie, and the login page plus its static asset returned `200`.

Evidence:

- `panel/vite.config.ts`
- `panel/src/features/auth/auth-config.ts`
- `panel/src/features/auth/auth-provider.tsx`
- `services/auth-service/src/main/resources/application-cloudpub.yaml`

## 2026-07-11 Localhost OIDC Restoration

Request:

- Restore the login modification for localhost port `8080`.

Actions:

- Reverted commit `d478d24` (`mobile auth`), removing the CloudPub OIDC proxy
  and profile while preserving the prior CloudPub host allow-list change.
- Restarted auth-service with the `dev` profile only.

Verification:

- Local OIDC discovery returns issuer, authorization endpoint, and token
  endpoint on `http://localhost:9000`.
- Panel Prettier, typecheck, lint, and Vite build passed; Vite emitted only its
  existing chunk-size warning.

## 2026-07-11 Mobile Sidebar, Registry, And Task-Board UI Fixes

Request:

- Prevent the mobile warehouse selector from opening the keyboard or selecting editable text.
- Restore a horizontally scrollable, equal-height task board on mobile and make its two primary controls equal.
- Replace mobile rental-registry filter/column text controls with matching icons; move toolbar collapse to the header.

Actions:

- Replaced the sidebar warehouse Combobox input with a shadcn Select trigger and grouped options.
- Moved rental-toolbar collapse state to the `/warehouse` header and URL (`?toolbar=collapsed`), removed the local chevron, and rendered the hidden controls conditionally.
- Built the mobile registry controls as icon buttons plus a labelled shadcn ToggleGroup; made `Добавить новую бытовку` compact.
- Wrapped the task-board columns in an explicit horizontal touch scroll viewport, retained per-column vertical scrolling, and used a mobile two-column action grid.

Verification:

- `cd panel && npm run typecheck`, `npm run lint`, `npm run build`, and `git diff --check` passed; Vite emitted only its existing chunk-size warning.
- Playwright at `390x844` confirmed no editable warehouse input, an active button/option rather than an input, working header collapse/expand, the mobile icon controls, `175×36px` equal task-board actions, and horizontal board scrolling (`358px` viewport / `3974px` scroll width) with equal `607px` queue heights.

## 2026-07-11 Temporary Local Content Authentication Bypass

Request:

- Temporarily disable authentication while panel content is being developed.

Actions:

- Added a tracked Vite development flag that renders the panel with a local `SYSTEM_ADMIN` user and does not initiate OIDC.
- Kept production panel OIDC unchanged: the bypass additionally requires Vite development mode.
- Added a `dev`-profile-only task-board bypass for public/content APIs. Its authorizer safely accepts the local no-JWT path.
- Kept `/api/internal/**` authenticated in development and retained the resource-server filter chain; auth-service was not changed.

Verification:

- `cd panel && npm run typecheck && npm run lint` passed.
- Focused task-board tests proved `401` without a token outside the bypass, `200` for a content API without Bearer in `dev,test`, and `401` for an internal API in `dev,test`.

## 2026-07-11 Registry Toolbar And Equipment Grid Refinement

Request: separate mobile registry actions, narrow the create action, and reuse the project Grid for additional equipment.

Actions:

- Replaced the combined toolbar shell with independent 32px outlined filter and settings buttons plus an outlined shadcn ToggleGroup for list/grid view; kept 8px gaps and narrowed the create action to 190px with its icon aligned to the normal left inset.
- Extended the reusable `OperationsListGrid` with an optional expandable detail row, then moved desktop additional equipment to that Grid. Mobile equipment remains the established card layout.
- Inspected the mobile sidebar at 390x844: the `Списание` submenu already renders both `Склад` (`/write-offs`) and `Доп. оборудование` (`/write-offs/equipment`), so no concurrent sidebar source was overwritten.

Verification: Playwright confirmed the separate controls, desktop equipment sorting, and expanded usage rows. `npx prettier --write`, `npm run typecheck`, `npm run lint`, `npm run build`, and `git diff --check` passed; Vite emitted only its existing chunk-size warning.

## 2026-07-11 Warehouse View Switcher Restoration

Request: restore the missing table/card-grid switcher on the `/warehouse` desktop toolbar.

Actions:

- Investigated the toolbar history and confirmed that `b724112` restricted the existing ToggleGroup to `lg:hidden` during the mobile interface change.
- Added a distinct desktop shadcn `ToggleGroup` immediately after `Столбцы`; it uses the pre-existing `viewMode` state, local persistence, labels, and 32px item geometry.
- Preserved the concurrent mobile-toolbar edits and did not change grid widths or standard table behavior.

Verification:

- Playwright at `1280x720` found the `Вид реестра` radiogroup beside `Столбцы`; selecting `Сетка` rendered card mode and remained selected after reload.
- Playwright at `390x844` found the mobile radiogroup and switched it back to the table. Browser console: 0 errors and 0 warnings.
- `cd panel && npm run typecheck` and `npm run lint` passed.

## 2026-07-11 Rental Logistics Prototype

Request:

- Add `Логистика` with return-from-rent and shipment-to-rent workflows, mandatory return photos, estimate handoff, date filtering, and full-height empty grids.
- Update the local agent configuration for the current Terra-based multi-agent project.

Actions:

- Added `/logistics/returns` and `/logistics/shipments`, sidebar/header navigation, warehouse-scoped return and shipment adapters, return validation, shipment snapshots, driver selection, and lifecycle transitions.
- Reused IndexedDB estimate media. Return audit photos are cloned to independent estimate uploads so estimate cleanup cannot delete return evidence.
- Added a versioned return-to-estimate claim with a mock-only ten-minute stale-claim recovery window, exact estimate linkage idempotency, and a shared rental-item mutation boundary with source-status CAS.
- Kept shipment contents as a read-only snapshot; no accessory stock mutation was inferred.
- Updated ignored local `AGENTS.md` for the actual React/Spring service layout and `GPT-5.6-Terra` subagent roles.

Evidence:

- `panel/src/features/logistics/`
- `panel/src/features/repair-estimates/repair-estimates-page.tsx`
- `panel/src/features/rental-items/api/rental-items-api.ts`
- `panel/src/features/repair-tasks/adapters/panel-repair-task-rental-items-client.ts`
- `wms-panel-old/src/main/java/ru/ponomarenko/wmspanel/entity/RentalItemStatus.java`
- `wms-panel-old/src/main/java/ru/ponomarenko/wmspanel/entity/RentalItemEventType.java`

Verification:

- Final `npm run typecheck`, `npm run lint`, `npm run build`, and `git diff --check` passed; Vite emitted only the existing chunk-size warning.
- Playwright verified both direct routes, full-height empty desktop grids, the mobile return form at `390x844`, and zero console errors.

## 2026-07-11 Logistics Header Alignment

Request: align the SiteHeader navigation icon with the returns registry edge and keep header/content insets consistent.

Actions:

- Measured the actual desktop geometry instead of applying local visual offsets. The header/content and toolbar/grid already used matching `24px` inset, while the trigger SVG was centred eight pixels inside its correct 32px hit target.
- Start-aligned the existing sidebar trigger icon within its unchanged 32px shadcn Button target. No grid/table classes, widths, route-local margins, transforms, or absolute positioning changed.

Verification:

- Before at `/logistics/returns` `1280x720`: trigger `x=312..344`, SVG `x=320..336`, grid `x=312`.
- After: trigger remains `x=312..344`, SVG `x=312..328`, toolbar/grid `x=312`. `/logistics/shipments` reports the same alignment.
- At `390x844`, trigger SVG and logistics toolbar both start at `x=16`; console has zero errors/warnings.
- `cd panel && npm run typecheck`, `npm run lint`, and `git diff --check` passed.

## 2026-07-11 Grouped Rental Returns And Shipment Preparation

Request:

- Expand return-from-rent to select a client and several cabins, derive cabin stages instead of a form status, and show separate damage/estimate facts.
- Expand shipment-to-rent with searchable or freely entered companies, reservation priority, several cabin selectors, planned contents, furniture quantity editing, and real general-worker preparation tasks.

Actions:

- Migrated the browser logistics envelope from v1 per-cabin returns to v2 receipt headers with multiple item lines while preserving media, estimate links, technical claims, and shipment snapshots.
- Added client-list versus manual-return audit, original-tenant snapshots, an atomic multi-cabin `RENTED -> AFTER_RENT` command, and a return grid with separate `Этап`, `Повреждения`, `Смета`, and actions columns.
- Added `WAITING_ESTIMATE_CONFIRMATION`. It is excluded from ordinary estimate/direct-repair selection and can be completed only by the exact estimate linked from the return.
- Added searchable/free-text company and client inputs, reserved-first shipment candidates, multiple number-search cabin slots, planned contents, zero-stock guards, and deterministic bring/take deltas.
- Added a real task-board preparation port. Each changed cabin creates one standalone task in an active visible queue bound to `GENERAL_WORKER`; task-board snapshots now expose the additive `externalTaskId` used for exact conflict recovery.
- Added a durable write-ahead shipment lifecycle: `PREPARING/FAILED/SHIPPED`, per-task `DRAFT/PENDING/FAILED/DISPATCHED`, persisted attempt IDs/timestamps, retry after a mock lease, exact callback CAS, and final cabin `RENTED` transition only after all tasks are confirmed.

Verification:

- Independent domain and UI reviews approved the final target changes after concurrency fixes.
- Panel typecheck, targeted ESLint, Vite build, scoped diff-check, and the full task-board-service test task passed before unrelated concurrent inventory changes made the shared full panel build temporarily fail.
- Playwright confirmed the new desktop return columns, manual company input behavior, responsive routes, and zero browser-console errors.
## 2026-07-11 Warehouse Inventory Workflow

Request:

- Implement warehouse inventory sessions in the current panel, including start,
  inspection, reconciliation, history, frozen statistics, and deferred repair
  publication.

Actions:

- Added the versioned browser inventory feature boundary with warehouse-scoped
  optimistic commands, cross-tab synchronization, fallback leases, normalized
  cabin numbers, immutable completion snapshots, and resumable publication.
- Added the inventory sidebar submenu and routes for start/continue, workspace,
  finish review, history, and history detail with explicit access guards and
  breadcrumbs.
- Reused rental-item creation, repair presentation primitives, catalog snapshots,
  exact-decimal aggregation, repair-task storage, and shared IndexedDB media.
- Extended repair tasks with the `INVENTORY` origin and source-finding identity so
  queued publication is idempotent and visible on the existing repairs/board
  projections without creating another board store.
- Added Vitest/jsdom/Testing Library and corrected the panel typecheck script to
  compile the application project rather than the empty root references project.

Evidence:

- `panel/src/features/inventory/`
- `panel/src/features/rental-items/api/rental-items-api.ts`
- `panel/src/features/repair-tasks/`
- `panel/src/components/app-sidebar.tsx`
- `panel/src/App.tsx`
- `WMS_ARCHITECTURE_KNOWLEDGE/09_MIGRATION/panel_warehouse_inventory.md`

Verification:

- `npm run test`: 7 files, 20 tests passed.
- `npm run typecheck`, `npm run lint`, and `npm run build` passed; Vite emitted
  only the existing large-chunk warning.
- Browser QA covered desktop, tablet, portrait mobile, and coarse landscape;
  start, inspection, unknown-number choice, finish, history, warehouse guard,
  Back/navigation, and responsive overflow were checked with zero console errors.
  The start dialog was also verified to label the field simply `Дата` and to
  render warehouse, current author, and date as read-only snapshot values.

## 2026-07-11 Soft Company Comboboxes In Rental Logistics

Request:

- Make the return `От кого` field selectable from suggestions and allow arbitrary
  company/client text to be applied with Enter during the current test phase.
- Keep the same soft behavior for shipment `Компания`; defer a future strict mode.

Actions:

- Separated draft input text from the applied company/client snapshot in both
  logistics dialogs.
- Kept arbitrary applied values controlled even when they are absent from the
  current suggestion list.
- Added explicit custom-value Enter handling without overriding highlighted-item
  keyboard selection, and kept explicit clear separate from Base UI sync events.
- Added an optional portal container to the existing shadcn Combobox composition
  and mounted logistics dropdowns inside their modal Dialog content.

Evidence:

- `panel/src/components/ui/combobox.tsx`
- `panel/src/features/logistics/logistics-returns-page.tsx`
- `panel/src/features/logistics/logistics-shipments-page.tsx`

Verification:

- `npm run typecheck` passed.
- Targeted ESLint passed for all three changed files.
- Browser QA passed suggestion selection by mouse, ArrowDown + Enter, arbitrary
  text + Enter, blur persistence, explicit clear, dependent-field enablement,
  and zero console errors.
## 2026-07-11 Non-interactive Inventory Start Snapshot

Request:

- Make the inventory start warehouse, author, and date completely static so an
  operator cannot focus, select, copy, or invoke a date control from them.

Actions:

- Replaced the three read-only inputs with named static text surfaces while
  preserving the visible labels and screen-reader group names.
- Removed every input/date-picker affordance and excluded the values from pointer
  interaction, text selection, and keyboard tab order.

Verification:

- Full Vitest suite: 9 files, 29 tests passed; focused RTL verifies no textbox or
  input remains and all three values are non-focusable/non-selectable.
- Targeted ESLint and Prettier passed.
- Browser inspection confirmed `tabIndex=-1`, `user-select:none`, and
  `pointer-events:none` for warehouse, author, and date; console had zero errors.
- Full typecheck/build and full lint were blocked by concurrent unfinished changes
  in `panel/src/api/equipment-api.ts`; the changed inventory files have no reported
  TypeScript or ESLint errors.

## 2026-07-11 Shipment Furniture Workflow Validation

Request:

- Add functional furniture/position addition and movement to the shipment preparation flow, matching the provided cabin-content example.

Evidence and verification:

- The current shared worktree already contains uncommitted implementation in `panel/src/features/logistics/logistics-shipments-page.tsx`, `panel/src/features/rental-items/add-contents-dialog.tsx`, and `panel/src/features/rental-items/move-contents-to-rental-item-dialog.tsx`; those files were not overwritten or committed by this validation task.
- Browser validation at `1280x900` on `/logistics/shipments`: selected a company and `БЫТ-001`, opened `Добавить позицию`, selected `Конвектор`, applied it to `Планируемое наполнение`, and observed `Принести 1 × Конвектор` plus `Создать задачу`. Browser console had zero application errors.
- The approved target behavior remains that shipment content edits plan a preparation task; they do not directly mutate physical inventory before task execution.

## 2026-07-11 Return Editing And Equipment Disposition

Request:

- Allow an unprocessed rental return to be corrected after creation and route the
  returned cabin contents to the additional-equipment action queue.

Actions:

- Added optimistic, versioned return correction for sender, return date, and the
  still-pristine cabin membership. Child workflow progress or equipment-resolution
  history freezes membership while header metadata remains correctable.
- Registered returned cabin contents as quarantined disposition cases and exposed
  three audited actions: return/merge into stock, write off with a mandatory reason,
  or transfer/merge into another eligible cabin.
- Blocked undamaged acceptance, shipment, and generic inventory movement paths while
  a cabin has unresolved returned equipment. Added compensation and recovery for the
  browser-adapter multi-store commands.

Evidence:

- `panel/src/features/logistics/api/logistics-api.ts`
- `panel/src/features/logistics/logistics-returns-page.tsx`
- `panel/src/api/equipment-api.ts`
- `panel/src/features/write-offs/equipment-write-offs-page.tsx`
- `panel/src/features/equipment/return-equipment-disposition-guard.ts`

Verification:

- Focused Vitest: 21 tests passed.
- `npm run typecheck`, `npm run lint`, and `npm run build` passed; build emitted only
  the existing large-chunk warning.
- Browser QA verified independent prefilled edit forms, unresolved-equipment rows,
  disabled undamaged acceptance, the disposition dialog, and zero console errors.

## 2026-07-11 Inventory Repair Workflow Snapshot

Request:

- Preserve work planning and repair automation when a new inventory inspection is saved.

Actions and evidence:

- Added an immutable inventory finding snapshot for completion mode, movement
  decision, stage kind, ordered route, duration, and photo requirement; the
  browser envelope migrates legacy inventory entries without those fields.
- Saving a non-empty finding opens the existing repair-work completion dialog.
  `AUTO` is validated and regenerated from the current catalog; `MANUAL` keeps
  the selected route/order. Publication maps the saved stage kinds instead of
  converting moves to generic repair work.
- Browser flow at `1280x900`: started an inventory, added `Монтаж кондиционера`,
  selected manual completion and movement, saved, reopened the finding, and
  observed the persisted three-stage sequence `move to repair -> repair work ->
  move from repair`; browser console reported zero errors.

Verification:

- Focused Vitest: 4 files, 20 tests passed.
- `npm run typecheck`, `npm run lint`, and `npm run build` passed. The Vite
  build emitted only its existing large-chunk warning.

## 2026-07-11 Return Cabin Card Parity

Request:

- Make `Возврат из аренды -> Добавить возврат` use the same repeatable cabin-selection menu as `Отгрузка в аренду -> Создать задание`.

Actions:

- Replaced the checkbox list and separate manual-cabin rows with identical `Бытовка N` cards, Combobox selection, removal controls, and `Добавить ещё бытовку` action.
- Preserved the return domain mapping: client cabins stay `CLIENT_LIST`; other rented cabins remain explicit `MANUAL` selections. Empty added cards block submission, and selected cabins are excluded from later cards.

Verification:

- `npm run typecheck`, `npm run lint`, `npm run build`, targeted ESLint, Prettier, and `git diff --check` passed; Vite emitted only its existing chunk-size warning.
- Browser QA at 1280x900 and 390x844 selected a client and cabin, rendered a second matching cabin card, excluded the selected cabin from the second menu, blocked an empty second card, reported no console errors, and kept `documentWidth === viewportWidth === 390` on mobile.

## 2026-07-11 Factual Rental Return Contents

Request:

- Make `Возврат из аренды -> Добавить возврат` use the same cabin and contents
  workflow as shipment creation, including the driver; factual furniture must
  remain in the cabin, while confirmed missing furniture is accounted for from
  the estimate.

Actions:

- Introduced a shared cabin-contents editor for shipment planning and factual
  return registration. The return form now has sender, date, movement driver,
  repeatable cabin cards, and per-cabin factual contents, without a shipment
  preparation task section.
- New factual return lines preserve both expected and received snapshots. A
  no-damage acceptance applies the received snapshot to the cabin. If a
  received snapshot is short, acceptance is blocked until an estimate is
  created; linking that estimate applies the received snapshot and records the
  missing delta as `lostQuantity` in the equipment register.
- Preserved the older quarantine/disposition behavior for legacy return rows so
  historical browser data is not silently reinterpreted.

Evidence:

- `panel/src/features/logistics/logistics-cabin-contents-editor.tsx`
- `panel/src/features/logistics/logistics-returns-page.tsx`
- `panel/src/features/logistics/api/logistics-api.ts`
- `panel/src/api/equipment-api.ts`

Verification:

- Focused logistics Vitest: 11 tests passed.
- `npm run typecheck`, `npm run lint`, and `npm run build` passed; build emitted
  only the existing large-chunk warning.
- Browser QA on `/logistics/returns` at `390x844` showed the return dialog with
  sender/date/driver/cabin contents fields, no horizontal overflow, and zero
  application console errors. Desktop QA also exercised selecting a returned
  position and confirmed that no preparation-task UI appears.

## 2026-07-11 Return Contents Spacing Parity

Request:

- Align the factual return-content action with shipment creation: the add-position
  button must not attach to the cabin-number field.

Actions and evidence:

- Applied the same `flex flex-col gap-4` CardContent layout that shipment uses
  to the return cabin card in
  `panel/src/features/logistics/logistics-returns-page.tsx`.

Verification:

- Browser QA at `1280x900` selected a returned cabin and confirmed the factual
  contents editor begins after the same 16px field gap as shipment; console
  reported zero application errors.
- `npm run typecheck`, `npm run lint`, and `npm run build` passed. The build
  emitted only the existing large-chunk warning.

## 2026-07-11 Rental Creation Dialog Copy, Actions, And Nested Close

Request:

- Refine the warehouse `Добавить новую бытовку` form: simplify its copy, align
  action sizes, and ensure the characteristics close control returns to creation.

Actions:

- Changed the title to `Создание новой бытовки`; removed the legacy
  category/dictionary description and the dimensions hint.
- Created a single 260px desktop/tablet action-column token for characteristics,
  photo upload, and the lower cancel/create group; mobile remains full width.
- Rendered the characteristics chooser within the creation dialog and prevented
  parent close events while it is open.

Verification:

- Full Vitest suite: 9 files, 46 tests passed.
- Full panel ESLint, typecheck, and Vite build passed; the build emitted only
  the existing chunk-size warning.
- Browser QA at 1280x900 and 834x900 measured all three action surfaces at
  260px with a common right edge; at 390x844 both section buttons were 308px
  wide with no horizontal overflow.
- Browser QA confirmed both the nested close control and backdrop return to the
  still-open creation dialog; console errors were zero.

Evidence:

- `panel/src/features/rental-items/rental-item-create-dialog.tsx`

## 2026-07-11 Equipment Quantity Left Alignment

Request:

- Align equipment quantities to the left so each value is visibly associated
  with its column.

Actions:

- Changed the six quantity columns in the primary equipment desktop register
  from `text-right` to `text-left`, without changing the shared grid or
  expanded usage-detail layout.

Verification:

- Targeted Prettier formatting, full panel typecheck, ESLint, and Vite build
  passed. The build emitted only the existing chunk-size warning.
- Browser QA at 1440x900 confirmed every first-row quantity computes to
  `text-align: left` and starts 12px from its cell's left edge, matching its
  heading. At 390x844 the card view had no horizontal overflow; browser console
  errors were zero.

Evidence:

- `panel/src/features/equipment/equipment-page.tsx`

## 2026-07-12 Rental Item Dossier, Media, And Actions

Request:

- Turn the warehouse cabin detail into a complete dossier with event photo
  folders, proven history, inspections, estimates, repairs, rental movements,
  comments, working actions, responsive tabs, and a reliable shared carousel.
- Keep this phase frontend/contracts/browser adapters only. Add lazy original
  quality in approved work viewers and leave production media/history services
  for a later phase.

Actions:

- Added `RentalItemDossierClient`, append-only activity/photo-group contracts,
  browser aggregation, optimistic cabin versioning, safe status commands,
  photo/comment mutations, and typed estimate/repair seeds.
- Rebuilt the cabin page with URL-backed Tabs, dedicated projections, square
  event folders, nested repair-stage history, comments, deep links, and explicit
  reserve/return empty states.
- Rebuilt `PhotoCarousel` on shadcn Carousel/Embla and fixed arrow, drag, swipe,
  keyboard, fullscreen, and accessibility behavior.
- Added per-user original preference and lazy context-scoped resolution for
  estimate, inspection, repair work, and repair acceptance. Common dossier and
  history hydration remains preview-only.
- Independent review corrected accepted logistics inspection projection,
  original URL leakage, exact return-line deep links, accessibility labels, and
  mobile flex shrinking that clipped the hero aside.

Evidence:

- `panel/src/features/rental-items/dossier/`
- `panel/src/features/rental-items/rental-item-detail-page.tsx`
- `panel/src/components/media/photo-carousel.tsx`
- `panel/src/features/media/`
- `panel/src/features/repair-estimates/adapters/indexed-db-repair-estimate-media-adapter.ts`
- `panel/src/features/acceptance/repair-acceptance-dossier.tsx`

Verification:

- Prettier check passed on all touched frontend files.
- Full frontend tests passed: 15 files / 66 tests.
- `npm run typecheck`, `npm run lint`, and `npm run build` passed; build emitted
  only the known large-chunk warning.
- Playwright browser QA at 1440x900, 820x1180, and 390x844 verified URL tabs,
  responsive non-overlap/no page overflow, folder thumbnail selection,
  fullscreen keyboard navigation, mouse drag, typed seeds, Back behavior, and
  empty states. Console errors and warnings were zero.

## 2026-07-12 Rental Item Dossier Layout And Registers

Request:

- Reduce the oversized cabin photo, restore the factual overview and full
  passport, render characteristics as semantic tags, and standardize every
  dossier register as a desktop grid with mobile/tablet cards and filters.

Actions:

- Capped the dossier hero at 420px from tablet upward and made the image column
  flexible beside a 288-420px internally scrolling passport.
- Restored factual status, warehouse, inspection/repair, client/document,
  characteristic, and cabin-content summaries without reviving the old fake
  location, readiness, reservation dates, or planned inspection.
- Added gray characteristic tags and primary-blue sanitary tags, preserving the
  compound `Металлическая дверь, кондиционер` characteristic as one value.
- Added shared filtered registers using `OperationsListGrid` at `lg` and cards
  below it for photos, inspections, estimates, repairs, movements, history, and
  comments. Photos retain their table/gallery toggle and in-tab upload action.
- Kept reservation and return data empty, but gave both tabs the same filter,
  empty desktop-grid header, and mobile empty-card presentation.
- Reconnected the existing add/move cabin-content dialogs and invalidated the
  dossier query after they close.

Evidence:

- `panel/src/features/rental-items/rental-item-detail-page.tsx`
- `panel/src/features/rental-items/rental-item-dossier-registers.tsx`
- `panel/src/features/rental-items/rental-item-dossier-registers.test.tsx`

Verification:

- Final frontend suite passed: 16 files / 69 tests.
- `npm run typecheck`, `npm run lint`, `npm run build`, and `git diff --check`
  passed; build emitted only the known large-chunk warning.
- Browser QA at 1440x900 and 900x1000 verified the 420px hero, visible tabs,
  no horizontal overflow, card fallback below `lg`, and structured empty
  reservation/return registers. Final 390x844 QA verified exact viewport/document
  width, photo folders/mobile cards with desktop grids hidden, and zero console
  errors or warnings.

## 2026-07-12 Mobile Dossier Filters And Empty States

Request:

- Replace framed mobile empty-state cards with the same muted text treatment as
  logistics, make mobile photo folders full width, and support both one-day and
  inclusive date-range filtering with visible calendar fields.

Actions:

- Replaced mobile empty Cards across dossier registers with centered muted
  `... не найдены` text while retaining wide-screen empty grid headers.
- Rebuilt the common register filters as full-width shadcn Fields with visible
  `Дата с`, `Дата по`, source/status, and author labels. Date controls use the
  existing InputGroup and Hugeicons calendar treatment.
- A single populated date filters that exact locally displayed calendar day;
  two dates form an inclusive range. The controls constrain reverse ranges with
  reciprocal `min`/`max` values.
- Changed the phone photo-folder register from two columns to one full-width
  folder per row and separated filters from the photo action row.
- Corrected date comparison to derive local year/month/day from the event Date,
  matching the timezone used by the visible dossier date instead of slicing the
  UTC ISO prefix.

Evidence:

- `panel/src/features/rental-items/rental-item-dossier-registers.tsx`
- `panel/src/features/rental-items/rental-item-dossier-registers.test.tsx`

Verification:

- Final frontend suite passed: 16 files / 71 tests.
- Typecheck, ESLint, Prettier, production build, and diff checks passed. Build
  emitted only the known large-chunk warning.
- Independent static review found no remaining blocker after the local-date
  correction. In-app browser discovery returned no available browser, so this
  incremental mobile pass was not re-measured in browser automation.

## 2026-07-12 Mobile Fullscreen Photo Viewer Alignment

Request:

- Center the fullscreen mobile photo and move the active-photo count above the
  navigation/rotation panel.

Actions:

- Replaced the fullscreen slide's asymmetric right-only padding with equal
  horizontal padding.
- Grouped the live photo counter above the previous, next, and rotate controls.

Verification:

- Targeted carousel tests: 2 files, 7 tests passed. Panel typecheck, scoped
  ESLint, and Vite build passed; the build emitted only the known chunk-size
  warning.
- Browser QA at 390x844 measured image bounds of 48px and 342px in a 390px
  viewport (zero center offset); the counter ended at 752px and the toolbar
  began at 760px. Browser console errors were zero.

Evidence:

- `panel/src/components/media/photo-carousel.tsx`

## 2026-07-12 Imported Rental Cabin Return Entry

Request:

- Add an import path for a cabin discovered in a client's rental, simplify the
  return register, and rename/clarify return-conflict actions.

Actions:

- Added `Добавить бытовку из аренды` before `Добавить возврат`. The dialog
  creates a `RENTED` browser-mock cabin with counterparty, shipment date,
  driver, furniture contents, optional photos, and a duplicate-number guard.
- On successful creation, the normal return dialog opens with counterparty,
  date, driver, cabin, and factual furniture contents prefilled.
- Removed `Способ приёмки`; renamed return actions to `Конфликт мебели`,
  `Конфликт оборудования`, and `Создать смету`. Furniture conflict review
  lists deltas and the counterparty's other rented cabins.

Verification:

- Targeted rental-item/logistics tests: 2 files, 13 tests passed. Typecheck,
  full ESLint, Vite build, and diff checks passed; the build emitted only the
  known chunk-size warning.
- Browser QA at 1440x900 created a test cabin, verified the prefilled return,
  created the return, and observed `Ожидает осмотра`. At 390x844 there was no
  horizontal overflow; console errors were zero.

Evidence:

- `panel/src/features/logistics/import-rented-cabin-dialog.tsx`
- `panel/src/features/logistics/logistics-returns-page.tsx`

## 2026-07-12 Dossier Registers And Cabin Contents Transfer

Request:

- Bring dossier searches and filters to the warehouse-grid visual grammar,
  add the shared 1/3/5 photo density rules, replace raw version display with
  the latest proven action, expand truncated warehouse cells, and move
  furniture between same-warehouse cabins through a worker task.

Actions:

- Added fuzzy local search plus compact date/category/author filter chips to
  every dossier register. Photo folders share the warehouse density selector:
  phone is fixed to one column, tablet is capped at three, and desktop at five;
  the desktop/tablet preference survives tab remounts and reloads.
- Replaced the passport's raw version with the date and actor of the newest
  proven dossier activity. General-comment edits append immutable audit
  entries, including an explicit `Комментарий очищен` state.
- Type, characteristics, and comment cells in the warehouse table open their
  full value in a keyboard-accessible popover. Empty cabins expose only the
  add-contents action.
- Replaced nearest-cabin furniture lookup with a same-warehouse filled-cabin
  selector. Both incoming and outgoing cabin-transfer dialogs dispatch a
  `MOVEMENT` task, validate both cabin versions and quantities, update both
  versions, and project mirrored `CONTENTS_TRANSFERRED` activities only after
  an `APPLIED` result.
- Browser transfer attempts persist `PENDING_DISPATCH`, `TASK_REGISTERED`,
  `CONFLICT`, and `APPLIED` phases. Retries recover by `externalTaskId`; a
  crash after the cabin mutation can be recognized from saved before-snapshots.

Verification:

- Final panel typecheck, full ESLint, 18 test files / 93 tests, production
  build, and `git diff --check` passed. Build emitted only the known large
  chunk warning.
- Browser QA at 1440x1000 and 390x844 verified 3x3 persistence, forced one
  mobile photo column, no horizontal overflow, truthful ineligible-transfer
  messaging, labelled controls, keyboard Combobox selection, expandable
  warehouse values, and zero console errors.

Evidence:

- `panel/src/features/rental-items/rental-item-dossier-registers.tsx`
- `panel/src/features/rental-items/contents-transfer/`
- `panel/src/features/rental-items/add-contents-dialog.tsx`
- `panel/src/features/rental-items/move-contents-to-rental-item-dialog.tsx`
- `panel/src/features/rental-items/rental-items-table-view.tsx`

## 2026-07-12 Return Equipment Conflict Gate

Request:

- Make equipment conflict replace normal return actions until it is resolved,
  with equal action dimensions.

Actions:

- Changed the equipment-conflict state from a third parallel action to the
  single visible return decision. Standard acceptance and estimate creation
  return only after resolution.
- Gave return-decision buttons a common `208px` width.

Verification:

- Scoped ESLint and diff checks passed. Full typecheck/build are blocked by
  unrelated concurrent `contents-transfer` changes: iterable misuse in
  `browser-contents-transfer-client.ts` and obsolete `saveApplied` test usage
  in the dossier adapter test.
- Browser QA seeded an unresolved equipment case and observed only
  `Конфликт оборудования` at 208px; its click navigated to
  `/write-offs/equipment`. Browser console errors were zero.

Evidence:

- `panel/src/features/logistics/logistics-returns-page.tsx`

## 2026-07-12 Mobile Warehouse And Dossier Refinement

Request:

- Align the mobile `Добавить новую бытовку` action with the search and grid;
  collapse mobile photo controls behind a filter icon; stop the dossier tabs
  moving on tab changes; and make grid cards show finishing, category, and
  characteristics compactly.

Actions:

- Replaced the fixed mobile create-action width with a flexible remaining
  toolbar width. The full label remains accessible and truncates only if a
  narrower viewport physically cannot accommodate it.
- Added a phone-only funnel control for photo registers. It expands the smart
  search, add-photo action, date, event, and author filters; individual event
  and author chips now use the same funnel symbol. Tablet and desktop keep the
  expanded controls.
- Reserved the dossier tab-content height on phones so a shorter or empty tab
  cannot clamp the internal detail scroll position and shift the sticky tab
  row.
- Redesigned the detailed grid-card metadata: type/dimensions/category and
  finishing use two left lines, and split characteristics wrap in a compact
  two-row right area beneath the number/status row. The image gives up height
  when needed so metadata remains visible.

Verification:

- `npm run typecheck`, `npm run lint`, targeted dossier/table Vitest suites,
  `npm run build`, and `git diff --check` passed; Vite emitted only its known
  large-chunk warning.
- Playwright at `390x844` measured equal `374px` right edges for search and
  create action, no document horizontal overflow in grid mode, a `32px`
  photo-filter button which expanded all controls, and a constant tab-row
  position (`73px`) through `Фото → Осмотры → Обзор`. Tablet at `1024x768`
  kept the fully expanded photo controls. Browser console errors were zero.

Evidence:

- `panel/src/features/rental-items/rental-items-page.tsx`
- `panel/src/features/rental-items/rental-items-grid-view.tsx`
- `panel/src/features/rental-items/rental-item-dossier-registers.tsx`
- `panel/src/features/rental-items/rental-item-detail-page.tsx`

## 2026-07-12 Dossier Register Filter Parity

Request:

- Extend the successful mobile photo-filter disclosure to inspections,
  estimates, repairs, reserves, shipments, returns, history, and the related
  dossier registers. Keep the expanded search beside the funnel button.

Actions:

- Made the shared `RegisterControls` use the mobile disclosure by default, so
  every dossier register receives it without parallel per-tab UI branches.
- Kept the photo-specific accessible label while other registers use the
  generic search-and-filters label. On phones the open state has a single
  funnel-plus-search row, followed by the add-photo action (when present) and
  the filter chips.
- Added a component test for the collapsed state and the open-state search row.

Verification:

- Targeted dossier-register Vitest suite passed: 13 tests.
- Scoped ESLint and `git diff --check` passed. Playwright at `390x844`
  confirmed the one-row open layout for photos and inspections and found one
  collapsed control on each of inspections, estimates, repairs, reserves,
  shipments, returns, history, and comments; browser console errors were zero.
- Full panel `typecheck` and production build were attempted but are blocked by
  concurrent, unrelated logistics shipment work: syntax/unused-symbol errors
  in `logistics-shipments-page.tsx` and shipment DTO mismatches in
  `logistics-api.ts` / `browser-shipment-client.ts`.

Evidence:

- `panel/src/features/rental-items/rental-item-dossier-registers.tsx`
- `panel/src/features/rental-items/rental-item-dossier-registers.test.tsx`
## 2026-07-11 Responsive Task Board Columns

Request:

- Stop the first task-board queue from moving back out of view after dragging the bottom horizontal scrollbar left.
- Keep horizontal columns on tablet/desktop, stack them vertically on phones, and show no more than three complete tasks in a mobile queue before internal scrolling.

Actions:

- Removed mandatory CSS scroll snapping from the horizontal queue viewport.
- Reused the shared phone viewport classification to switch the board, loading state, expanded queues, and collapsed queues to a full-width vertical layout.
- Gave mobile task cards a stable `320px` minimum height and capped populated mobile queues at exactly three complete cards before their card-list scrollbar takes over; empty queues now stay compact.

Evidence and verification:

- Playwright at `390x844` measured `documentWidth === viewportWidth === 390`, equal `358px` board `clientWidth`/`scrollWidth`, a vertical track, four `320px` cards, exactly three complete visible cards, and working internal scroll to the fourth card.
- Playwright at `768x900` and `1440x900` measured horizontal tracks, `scroll-snap-type: none`, free `scrollLeft` movement, and the first queue returning to a stable `1px` viewport inset. Browser console had zero errors.
- Final `npm run typecheck`, `npm run lint`, `npm run build`, `npm run test` (9 files / 46 tests), and `git diff --check` passed. Vite emitted only the existing large-chunk warning.

## 2026-07-12 Warehouse Status Filter Colours

Request:

- Give the warehouse status-filter options the same colours as their statuses
  use in the table, so they are easier to recognise.

Actions:

- Rendered each known status option as a semantic badge with the matching
  foreground/background token pair. Filtering behaviour and option values were
  not changed.
- Added a focused component test for the blue rented, green free, and dark
  written-off options.

Verification:

- Focused Vitest suite passed (1 test), as did scoped ESLint and
  `git diff --check`.
- Playwright at `390x844` opened the warehouse filter and confirmed the
  colour-token classes for `Аренда` and `Свободна`; browser console errors were
  zero.
- Final `npm run typecheck`, `npm run lint`, and `npm run build` passed in
  `panel`. Vite emitted only its existing large-chunk warning.

Evidence:

- `panel/src/features/rental-items/rental-items-filters.tsx`
- `panel/src/features/rental-items/rental-items-filters.test.tsx`

## 2026-07-12 Dossier Comment And Passport Layout

Request:

- Put the editable cabin-wide comment and the append-only history-note form in
  the same desktop row, as in the overview's two-card layout.
- Keep the right-side cabin passport entirely visible in one column and leave
  later content below it, giving photos more room.

Actions:

- Grouped the two comment forms into the responsive desktop two-card grid while
  retaining the comment register below and phone stacking.
- Kept every passport fact in one compact vertical list, removed its internal
  scroll, and increased the desktop photo/hero space to 560px so the complete
  identity, actions, and facts remain together.

Verification:

- Prettier and scoped ESLint passed; the focused dossier-register Vitest suite
  passed (13 tests).
- Playwright at `1440x900` measured a 560px hero, a single 480px-wide
  passport column without overflow, and two equal-width comment forms in one
  row. At `390x844` the passport and forms stacked without console errors.

Evidence:

- `panel/src/features/rental-items/rental-item-detail-page.tsx`

## 2026-07-12 Logistics Quantity And Photo Intake

Request and actions:

- Made plus select a furniture/equipment row at quantity one and made a return
  to zero clear its checkbox.
- Made the return estimate's add-photo button invoke the native image picker;
  desktop accepts image drag-and-drop into the empty preview, while phone and
  tablet show only the picker action until photos exist.

Verification:

- Prettier, scoped ESLint, full panel typecheck, and production build passed.
- Browser opened the returns route at `1280x900` without console errors.

Evidence:

- `panel/src/features/logistics/logistics-cabin-contents-editor.tsx`
- `panel/src/features/logistics/logistics-photo-dialog.tsx`
- `panel/src/features/repair-estimates/repair-estimate-photos.tsx`

## 2026-07-12 Panel Microservice Decomposition Proposal

Request:

- Analyze the current `panel` implementation and prepare a phased plan for
  restructuring its browser-backed domains into services, with one service per
  implementation stage.

Actions:

- Audited the routed panel domains, feature ports/adapters, browser stores, and
  cross-domain workflow dependencies.
- Reconciled the proposal with the existing `auth-service` and
  `task-board-service` ownership boundaries and with legacy evidence.
- Created the proposal
  `docs/plans/20260712-panel-microservices-decomposition.md`, including service
  ownership, dependency order, saga/outbox boundaries, acceptance gates,
  migration hazards, and explicit `UNKNOWN` decisions.
- Marked the document as a proposal rather than an approved product or
  architecture decision. Implementation has not started.

Evidence:

- `panel/src/App.tsx`
- `panel/src/features/`
- `panel/src/api/`
- `services/auth-service/`
- `services/task-board-service/`
- `docs/plans/20260712-panel-microservices-decomposition.md`

Verification:

- Documentation-only analysis; no product code or service implementation was
  changed, so compilation and automated tests were not run.
- Amplicode MCP was unavailable in the current tool environment.

## 2026-07-12 F0 Migration And Shared Platform Foundation

Request:

- Prepare the project for the first staged service extraction without Liquibase
  or Flyway, using JPA plus reviewed SQL releases, a common technical Spring
  platform, a RabbitMQ integration bus, and a future stateless API gateway.
- Include a recoverable database-migration pre-step before changing existing
  services.

Actions:

- Captured ignored custom-format backups, schema dumps, canonical inventories,
  JPA compatibility fixtures, cleanup evidence, and artifact checksums for the
  current auth/task-board PostgreSQL databases. Stored the manifest digest in a
  detached operator-local index outside the backup subtree and repository.
- Added migration tooling and rollback guidance for reviewed ordered SQL
  releases, advisory locking, checksum drift rejection, transactional
  `verify.sql`, and service-owned `rwms_schema_history`.
- Added `platform:technical-contracts` with immutable technical records and an
  architecture test that rejects Spring/JPA/Hibernate/domain dependencies.
- Added the conditional common Spring starter for UTC/Jackson, correlation,
  Problem Details, JWT audience validation, observability, RabbitMQ, and
  production JPA safety without a shared security chain or persistence model.
- Added RabbitMQ 4.1 to local Compose and the `rwms.domain.v1` exchange,
  consumer queue/DLQ factory, exactly-three-retry policy, confirms, and mandatory
  returns. Outbox/inbox remain documented service-owned conventions, not F0
  shared implementations.
- Updated the staged roadmap and project delivery rules. F0 leaves
  `auth-service` and `task-board-service` on their current Liquibase runtimes;
  their schema cutovers belong to F1 and F2.

Evidence and verification:

- Ignored backup `20260712T203837Z` reports `PASSED` for both services; restored
  inventories and normalized schemas match their stable sources, and disposable
  restore resources were cleaned.
- A detached manifest anchor exists under the operator-local RWMS migration
  index; its value and backup contents remain outside Git.
- `platform:technical-contracts` test results: 6 tests, zero failures/errors;
  main runtime classpath has no dependencies.
- `platform:spring-boot-starter` test results: 29 tests, zero failures/errors,
  including 3 RabbitMQ Testcontainers cases for DLQ routing, initial plus three
  retries, confirms, and mandatory return.
- Migration tooling covers unit validation plus clean SQL replay, ordered
  upgrade, repeat safety, checksum drift rejection, failed-verification rollback,
  and installed PowerShell-host matrix execution.
- Secret-pattern review found no committed credentials, private keys, database
  dumps, or detached anchor values in the architecture memory. Backup artifacts
  remain ignored.

Status:

- F0 implementation evidence is complete, but the active-stage pointer and
  human commit are reconciled by the lead after final independent review.
- No auth/task-board schema migration or application-runtime change is claimed.

## 2026-07-13 F1 Auth Foundation

Request:

- Migrate `auth-service` from Liquibase to JPA plus reviewed PostgreSQL SQL
  releases without losing current users, warehouse grants, OAuth clients,
  authorizations, or historical migration evidence.
- Adopt the F0 common technical platform, make OAuth service clients
  configuration-driven, and prepare the public issuer/login flow for the future
  `/auth` gateway prefix.

Actions:

- Removed auth-only Liquibase build/runtime/configuration and changelog
  resources while retaining existing `databasechangelog*` tables untouched.
- Added the reviewed baseline and `V0001__adopt-auth-schema`, exact monotonic
  PostgreSQL verification, checksum manifest, and auth release test script.
- Set explicit schema modes: dev `update`, PostgreSQL tests `create-drop`, and
  base/production `validate` with common-starter production safety.
- Integrated `platform:technical-contracts` and
  `platform:spring-boot-starter` without sharing persistence or security chains.
- Replaced hard-coded OAuth client construction with validated declarative
  clients, per-client audiences/scopes/principal types/origins, stable
  registration identity, byte-stable reconciliation, revision/fingerprint
  rotation control, fail-closed disable, explicit revocation, and transactional
  advisory locking.
- Made the base issuer gateway-prefixed while preserving direct localhost auth
  in the explicit dev profile; documented the F3 callback exclusion obligation.

Evidence:

- `services/auth-service/database/`
- `services/auth-service/src/main/java/dev/buhanzaz/rwms/auth/config/`
- `services/auth-service/src/main/resources/application.yaml`
- `services/auth-service/src/main/resources/application-dev.yaml`
- `services/auth-service/src/test/`
- `services/auth-service/README.md`
- `WMS_ARCHITECTURE_KNOWLEDGE/09_MIGRATION/f1_auth_foundation.md`

Verification:

- F0 foundation commit is `a3da339`.
- Final auth suite: 38 tests, zero failures/errors, one conditional operator-only
  skip.
- Separate restored-F0 run after the common release runner: one test, zero
  failures/errors/skips; JPA validation and JDBC reads covered all three clients
  and nineteen authorizations.
- Auth SQL release script passed clean install, repeat, checksum drift rejection,
  and failed-verification rollback.
- Real F0 pre/post digests stayed unchanged for all pre-existing auth tables,
  including one subject, three clients, nineteen authorizations, and four
  historical Liquibase change rows.
- Login UI typecheck, lint, and production build passed.
- Independent DB/security reviews were clean after schema-definition and unsafe
  issuer findings were fixed; no disposable verification containers remained.

Status:

- F1 is complete. The sole active-stage pointer advances to
  `F2_TASK_BOARD_FOUNDATION`; no F2/F3/W1 implementation is claimed by F1.

## 2026-07-13 F2 Task-Board Foundation

Request:

- Migrate `task-board-service` from Liquibase to JPA plus reviewed PostgreSQL
  SQL releases without losing current queue, workforce, task, assignment,
  timing, interruption, cancellation, or idempotency behavior.
- Adopt the F0 common technical/RabbitMQ platform, preserve current APIs and
  F1 client-credentials integration, and defer Stage 2 schedule/notification
  parity.

Actions:

- Removed task-board-only Liquibase build/runtime/configuration and changelog
  resources while retaining historical `databasechangelog*` tables untouched.
- Added reviewed baseline and V0001-V0004 releases: exact F0 schema adoption,
  external-command fingerprint/outbox/inbox, case-insensitive identity indexes,
  and durable worker-credential operation metadata.
- Set explicit schema modes: dev `update`, PostgreSQL tests `create-drop`, and
  base/production `validate` with common-starter safety.
- Integrated the common technical contracts/starter, canonical `ApiProblem`,
  correlation handling, and task-board-specific security without sharing
  domain persistence or a security chain.
- Made stable external-task registration fingerprint-idempotent and protected
  external registration and queue ordering with PostgreSQL advisory locks.
  Required present non-negative concurrency tokens and preserved `409` for
  stale commands.
- Added task-board-owned transactional outbox/inbox and created/cancelled event
  contracts, including exact-body hashing, confirms/returns, database-time
  lease fencing, per-aggregate ordering, bounded retry/DLQ, deduplication, and
  broker/database recovery.
- Serialized worker credential effects with a per-worker PostgreSQL session
  advisory lock; added durable operation ID/type/start metadata, local
  completion fencing, orphan recovery, and status convergence.
- Advanced the sole active-stage pointer to the stateless gateway gate without
  creating the gateway or warehouse deployables in F2.

Evidence:

- `services/task-board-service/database/`
- `services/task-board-service/src/main/java/dev/buhanzaz/rwms/taskboard/`
- `services/task-board-service/src/test/java/dev/buhanzaz/rwms/taskboard/`
- `contracts/openapi/task-board-service.yaml`
- `contracts/events/task-board-events.yaml`
- `WMS_ARCHITECTURE_KNOWLEDGE/09_MIGRATION/f2_task_board_foundation.md`
- F0 operator backup `20260712T203837Z` and its detached manifest anchor

Verification:

- Final task-board suite: 76 tests, zero failures/errors, one conditional
  restored-F0 skip.
- Task-board SQL runner passed V0001-V0004 clean install, ordered upgrade,
  repeat, checksum drift rejection, failed-verification rollback, and JPA
  validation.
- Separate real F0 restore applied four releases and passed JPA `validate`;
  historical `databasechangelog` and `databasechangeloglock` digests beginning
  `9dc7` and `ae466` remained unchanged.
- Redocly OpenAPI and AsyncAPI validation passed.
- Independent database, messaging/security, and credential reviews were clean
  within the approved F1 auth contract.

Status:

- F2 is complete. The next authorized gate is defined only by
  `docs/plans/ACTIVE_STAGE.md`; no W1 or Stage 2 parity implementation is claimed
  by F2.

## 2026-07-13 F3 Stateless API Gateway Foundation

Request:

- Add a single stateless Spring API gateway, route the existing auth and
  task-board capabilities through one panel origin, and preserve downstream
  ownership and JWT validation.

Actions:

- Added Spring Cloud Gateway Server MVC on port `8088` without database, JPA,
  migration, RabbitMQ, outbox/inbox, token exchange, or business aggregation.
- Added exact auth/task-board routes, a fail-closed reserved warehouse prefix,
  panel-owned callback precedence, canonical correlation and Problem Details,
  exact-origin CORS, route timeout/error handling, and production safety checks.
- Made forwarded metadata a strict trust boundary: all incoming variants are
  removed and canonical auth scheme/host/port/prefix are synthesized from the
  validated public base. Boot 4.1 timeouts use `spring.http.clients.*`.
- Switched panel OIDC and task-board HTTP clients to the same browser gateway
  origin and added Vite `/auth` and `/api` proxies without production fallback.
- Reconciled the architecture/security/migration memory and moved only the
  operational stage pointer to Warehouse Service readiness; no warehouse
  implementation is claimed.

Evidence:

- `services/api-gateway-service/`
- `panel/src/lib/gateway-config.ts`
- `panel/src/lib/gateway-routes.ts`
- `panel/src/features/auth/auth-config.ts`
- `panel/src/features/settings/task-board/api/http-task-board-settings-client.ts`
- `panel/vite.config.ts`
- `WMS_ARCHITECTURE_KNOWLEDGE/09_MIGRATION/f3_api_gateway_foundation.md`

Verification:

- Gateway: 26 tests, zero failures/errors, and `bootJar` passed; forbidden
  stateful/integration dependencies are absent.
- Auth regression: 38 tests, zero failures/errors, one conditional skip.
- Task-board regression: 76 tests, zero failures/errors, one conditional skip.
- Panel: 34 focused tests plus typecheck, lint, and build passed.
- Independent edge and panel reviews were clean.
- Disposable live E2E passed discovery issuer, PKCE login/callback, auth current
  user, task-board settings through both JWT layers, logout, Back, empty console,
  and mobile 390 px overflow/image checks; temporary resources were cleaned.

Status:

- F3 implementation and verification are complete. The human commit containing
  this transition closes F3. Production ingress and network closure remain
  deployment `UNKNOWN`s; Warehouse Service is not implemented by F3.

## 2026-07-13 F1C Auth Warehouse-Existence Correction

Request:

- Close the auth contract gap discovered before W1 without implementing or
  rebuilding Warehouse Service.

Actions:

- Added an auth-local, default-disabled warehouse-existence client. Enabled
  validation uses a private client-credentials token with only
  `warehouse.read`, validates exact `{id, version, active}` responses, and
  completes all distinct UUID checks before USER grant persistence.
- Added the declarative `auth-service` client and allowed `warehouse.read` for
  `rwms-panel` without changing the panel's requested scopes.
- Added reviewed auth release V0002 for the approved `spb`/`msk` to UUID
  mapping. It preserves source row IDs and `created_at`, deletes or merges no
  grant, increments `version` and `updated_at` on changed rows, and aborts on
  collisions or unmapped opaque IDs. The SQL mapping, schema history and
  checksum provide audit evidence.
- Retained the existing verified pre-migration backup-restore artifact as the
  approved rollback policy; no compensating SQL is claimed.

Verification:

- Auth: 59 tests, zero failures/errors, one conditional skip.
- Focused V0002: 7 passes; SQL runner passed clean apply, repeat, checksum
  rejection, transactional rollback of a deliberately failed verification, and
  JPA validation. The conditional restored-backup test remained the one skip
  and was not re-executed.
- Gateway: 26 passes. Task-board: 76 tests, zero failures/errors, one
  conditional skip.
- Final database and security reviews were clean after correcting the duplicate
  fixture secret.

Status:

- F1C implementation and verification are complete. The human commit containing
  this transition closes F1C and returns the sole active pointer to W1. No
  Warehouse Service, panel cutover, gateway warehouse activation, or Stage 2
  implementation is claimed.

## 2026-07-13 F4 Event-Driven Modernization Governance

Request:

- Modernize the existing backend with Kafka, Spring Cloud Stream, full
  event-sourcing discipline for safe aggregates, Lombok/MapStruct and production
  observability/readiness without adding business services prematurely.

Decision record:

- Recorded prerequisite F1C closure commit `d50922d` and the F4 governance
  approved on 2026-07-13 with ordered sequence
  `F4K -> F4A -> F4T -> F4R -> F4G -> F4I -> W1`.
- Fixed PostgreSQL event-store/projection/outbox/inbox responsibilities,
  aggregate-family Kafka topics, `aggregateId` partition keys, replayable
  baseline rules, bounded retry/DLT and staged RabbitMQ cutover.
- Recorded side-by-side `DomainEventEnvelopeV2`: nullable `occurredAt`, required
  `recordedAt` and sanitized actor reference. At F4R existing V1
  `EventEnvelope` and `ActorSnapshot` leave target Spring integration and remain
  isolated to media-compat RabbitMQ for the unchanged legacy Go worker until
  the Stage 4 photo-processing cutover.
- Restricted event sourcing to non-secret aggregates. PII vault references and
  password/OAuth/cryptographic/delivery operational exclusions are explicit.
- Restricted Lombok/MapStruct to safe boilerplate/mapping roles.
- Assigned auth telemetry to F4A, task-board telemetry to F4T and gateway-only
  telemetry plus cross-deployable verification to F4G.
- Limited F4R retirement to target Spring RabbitMQ integration and retained an
  isolated media-compat RabbitMQ path for the unchanged legacy Go worker until
  Stage 4. Classified Redis 8, Elasticsearch, ClickHouse, Camunda 8,
  Prometheus, Grafana, Loki, Tempo and OpenTelemetry infrastructure as phased
  readiness until their owning gate supplies runtime proof.

Status:

- Governance and durable memory only. No F4 source, schema, dependency,
  container, manifest or runtime implementation is claimed by this entry.
  Historical RabbitMQ implementation and its verification remain true.

## 2026-07-14 F4A Kafka Broker-Recovery Correction

Request:

- Resolve the auth outbox recovery failure directly, without delegating it and
  without weakening the bounded `initial + 3 retries` delivery policy.

Finding and correction:

- The recovered producer was not failing because another retry was required.
  The Kafka binder was configured with `ByteArraySerializer`, while the common
  publisher supplied `KafkaHeaders.KEY` as a `String`; the first due attempt
  therefore failed with a `String` to `byte[]` cast error.
- Auth now creates its two producer bindings at startup without publishing a
  synthetic event. Committed business publication still uses Cloud Stream
  `StreamBridge` through the PostgreSQL outbox.
- The common publisher supplies the aggregate key as UTF-8 bytes. Topic order
  remains keyed by aggregate ID and the delivery policy remains one initial
  attempt plus retries at 1s, 2s and 4s before DLT.
- Recovery verification accepts a raw at-least-once duplicate when a
  client-side timeout races a broker acknowledgement, but requires exactly two
  unique event IDs, aggregate versions `0, 1` in increasing offsets, and exactly
  two inbox effects after deduplication.

Verification:

- `AuthKafkaBrokerRecoveryIntegrationTest.brokerOutageLeavesOutboxRecoverableThenPublishesOrderedFactsAndSanitizedDlt`
  passed against a real Kafka Testcontainer.
- `AuthKafkaOutputBindingInitializerTest` passed.
- Common publisher and Cloud Stream test-binder tests passed with the byte-array
  aggregate key.
- Focused auth authorization-safety and event-delivery tests passed.

Status:

- The reported broker-recovery defect is resolved. This is intermediate F4A
  evidence only; it does not close the full F4A exit matrix or authorize F4T.

## 2026-07-14 F4A Auth Event-Sourcing Implementation

- Event-sourced `USER_AUTHORIZATION` and `WORKER_ACCESS` with stream CAS,
  synchronous projections, snapshots, transactional Kafka outbox, consumer
  inbox/checkpoints, version-gap quarantine and sanitized DLT.
- Kept PII, password hashes, OAuth JDBC state, tokens and signing material in
  operational/vault storage outside events and transport payloads.
- Added expand-only Flyway V3, deterministic non-published baselines and shadow
  replay parity without deleting legacy/OAuth data.
- Prebound two aggregate topics and their two consumer-owned sanitized DLT
  topics. Kafka aggregate keys are UTF-8 bytes matching `ByteArraySerializer`;
  retry remains one initial attempt plus 1s/2s/4s retries.
- Full auth verification passed 129 tests with zero failures/errors and one
  environment skip. Task-board passed 87 tests with one environment skip;
  gateway passed 26 tests. Independent database, security and messaging reviews
  found no P0/P1 implementation defect.

Status:

- F4A implementation and verification are ready for the scoped human commit.
  Task-board/gateway dirty changes and IDE/browser artifacts are excluded from
  that commit.

Closure:

- Scoped implementation commit `cd0dfd9` closes F4A. `ACTIVE_STAGE.md` now
  authorizes only F4T task-board event sourcing. The pre-existing task-board and
  gateway dirty changes were not included in the F4A commit.

## 2026-07-14 F4T Task-Board Event Sourcing

Implementation diff:

- Added expand-only Flyway V5 and deterministic, non-published baselines for
  seven task-board aggregate families while preserving V4 rows, Rabbit tables
  and historical migration evidence.
- Added stream CAS/stable multi-stream locking, projection checkpoints,
  snapshots, replay/shadow parity support, transactional Kafka outbox,
  consumer inbox/checkpoints, sanitized DLT and version-gap quarantine.
- Added the canonical task-board event schema. Transport facts exclude names,
  comments, descriptions, task text and credential state; worker PII remains
  in the operational projection behind an opaque revision marker in events.
- Added the transaction-mandatory projection writer and an architecture guard
  that forbids event-sourced repository mutations outside that writer.
- Preserved Rabbit alongside feature-gated Kafka so F4R can perform the actual
  drain/parity/retirement separately.

Verification:

- Flyway V5 focused tests, projection/security/payload checks and the cutover
  runtime Testcontainers test passed. The first full 105-test run found one
  shared Testcontainers-cleanup regression and one fixture-count assertion;
  after test-only fixes, all 61 tests in the affected four classes passed.
  The other 50 tests had already passed and one infrastructure-conditional test
  was skipped.
- Independent final database/security/messaging review found no actionable
  P0-P3 findings. Scoped implementation commit `0c0957e` closes F4T; the active
  stage now authorizes F4R Rabbit retirement only.

## 2026-07-14 F4R Target Spring Rabbit Retirement

- Removed Spring AMQP from task-board and the shared starter, including Rabbit
  topology, relay, listener, legacy outbox recorder and runtime tests.
- Kept historical Rabbit tables, rows, migrations and V1 contracts unchanged.
- Isolated the broker behind Compose profile `media-compat-rabbit` for the
  unchanged Go worker; documented exact launch command, URL variable and queues.
- Replaced legacy Rabbit assertions with V2 event-store/Kafka-outbox assertions
  while preserving API, optimistic concurrency and idempotency tests.
- Passed 51 focused task-board tests, 2 starter retirement tests and both
  Compose config validations. Independent review found no actionable P0-P3
  runtime defect. Commit `c58c9fb` closes F4R; the active stage now authorizes
  F4G gateway observability only.

## 2026-07-14 F4G Gateway Observability

- Added public health/liveness/readiness and JWT-protected Prometheus endpoints
  to the stateless gateway, together with OTLP tracing and ECS JSON logging.
- Proved downstream W3C trace propagation while keeping `X-Correlation-Id`
  separate; the Kafka hop stays owned by existing F4A/F4T instrumentation.
- Added architecture bans for persistence, migrations, brokers, Redis,
  workflow, domain and eventing state in the gateway.
- Gateway compilation passed. The focused batch produced 31 passing unchanged
  tests; after correcting one false test-only assertion, both affected
  configuration tests passed. Independent review found no actionable P0-P3
  issue. Auth and task-board code were unchanged. Scoped commit `08d4037`
  closes F4G; the active stage now authorizes F4I infrastructure readiness.

## 2026-07-14 F4I Infrastructure Readiness Candidate

- Added all seven isolated Compose profiles, observability backend configs, a
  pinned readiness Helm umbrella, bounded Kind smoke and deterministic safety
  tooling without changing an application deployable.
- Hardened the smoke path against native-command false positives, dependency
  leakage into bounded installs, concurrent temp-file races, inline credentials
  and accidental RWMS business-use markers.
- Final static evidence: all Compose configs valid; bounded Helm lint/template,
  kubeconform and safety checks report `13 PASS`, `0 FAIL`, `0 BLOCKED`.
- Runtime health passed for core, cache and isolated media-compatible Rabbit.
  Full Helm/profile/Kind runtime is externally blocked by chart/image endpoint
  EOF/TLS timeouts and Docker Desktop bootstrap. F4I remains active and W1 was
  deliberately not started.

## 2026-07-14 F4I Runtime Revalidation

- Moved the pinned Strimzi `1.1.0` dependency to the official
  `oci://quay.io/strimzi-helm` registry; the archive pull succeeds. Full
  dependency validation now runs only from a GUID-scoped temporary chart copy,
  retries a failed retrieval at most three times and leaves no downloaded chart
  or `Chart.lock` in the worktree.
- Added render-only validation values required by the currently pinned Camunda,
  Loki and OpenTelemetry Collector charts. They select no secondary Camunda
  storage, a single-binary filesystem-backed Loki test schema and the collector
  image metadata; they create no RWMS business data, client or telemetry
  pipeline.
- Replaced the three vendor-generated Secret resources discovered by the full
  catalog with the supported safety modes: no ClickHouse operator credential
  generation, no ECK webhook certificate generation, and an external
  Alertmanager configuration reference. This validates chart contracts without
  embedding generated credentials or certificates.
- Suppressed the ClickHouse vendor sample-file ConfigMap during the render-only
  catalog check because it contains a literal default password. The safety scan
  accepts quoted environment-variable references, but not literal credentials;
  vendor CRD schemas are excluded because they only describe possible fields.
- Removed the Camunda chart's literal starter users and roles from the
  contract-only render, and disabled the ClickHouse CRD hook ConfigMaps, which
  carry schema documents rather than executable readiness resources. Production
  identity and CRD-install ownership remain deferred.
- The current external catalog blocker is the official Camunda `14.6.1` GitHub
  release asset, which returned `EOF` on all three bounded attempts. No
  unapproved mirror or vendored third-party artifact was introduced.
- The default Kind node image now pulls. Docker Desktop reports cgroup v1, and
  a diagnostic `kubeadm` bootstrap did not complete in that runtime. The live
  smoke consequently checks for cgroup v2 before cluster creation and documents
  the WSL/Docker Desktop operator preflight. F4I remains active; W1 remains
  deliberately unstarted.

## 2026-07-14 F4I Full Static Catalog Revalidation

- All eight pinned third-party Helm dependencies resolved from their declared
  sources in the GUID-scoped temporary catalog. `helm lint --with-subcharts`
  passed 9 charts; no downloaded chart or `Chart.lock` entered the worktree.
- Full rendering passed the secret/business-use guard. Strict kubeconform
  validated 119 Kubernetes built-in resources; Helm lint/render processed 36
  CRD documents and 53 vendor custom-resource documents.
- The default raw-GitHub schema source returned `EOF` even for `Namespace`.
  Validation instead used the same public schema repository at immutable
  revision `6575cbe6397e3c1cb0946a41a200c37377fafe29` through jsDelivr, with an
  ephemeral cache and 90-second process ceiling.
- Bounded chart validation passed 16/16 resources, and
  `Test-F4IReadiness.ps1 -RequireCompleteToolchain` again reported `13 PASS`,
  `0 FAIL`, `0 BLOCKED` and the expected non-mutating Kind skip.
- A fresh observability image pull and two bounded retries failed at
  `registry-1.docker.io` with `TLS handshake timeout` before a container was
  created. It adds no runtime-health evidence. Live Kind still blocks at the
  Docker cgroup-v1 preflight; F4I remains active and W1 remains unstarted.
- A final independent full-catalog retry exhausted all three bounded downloads
  of the official Camunda `14.6.1` release asset with `EOF`. The earlier full
  static pass proves manifest correctness but is not repeatable closure; a
matching local Helm-cache archive remains diagnostic only and was not used as
a substitute source.

## 2026-07-14 W1 Warehouse Service

- The explicit user service-only exception superseded F4I as an active
  deployment gate without marking it passed. W1 adds the single
  `warehouse-service` Spring/Flyway module, its canonical OpenAPI/event
  schemas, and `warehouse-db` in Compose `core`; no Helm, Kind, observability
  or application image was added.
- Flyway V1 has only registry, outbox and idempotency state. It seeds the two
  confirmed warehouse UUID records and keeps topology/location, event store and
  inbox absent.
- The panel now uses the gateway Bearer warehouse client, UUID selection
  migration and a `SYSTEM_ADMIN` warehouse settings page. The location mock is
  unchanged and the Playwright HTTP fixture is test code only.
- Warehouse Testcontainers/binder tests passed. Focused auth F1C tests,
  gateway routing, approved dependency versions, full panel typecheck/lint,
  238 Vitest tests, production build and three-viewport Playwright smoke passed.
- A bounded real local smoke started auth and warehouse services plus Kafka.
  The active grant succeeded; unknown (`422`) and inactive (`409`) warehouse
  grants failed with no partial auth mutation. Temporary DB and client-secret
  state were process-local and are not repository artifacts.

## 2026-07-14 Combined Go Media-Service Foundation

- Product decision: merge the formerly separate Stage 3 `media-service` and
  Stage 4 `photo-processing-service` into one stateful Go `media-service`.
  The active-stage pointer remains W1/Stage 2; this records a later-stage
  boundary change and does not claim Stage 2 complete.
- Added canonical media OpenAPI/AsyncAPI/JSON-schema contracts, a Flyway V1
  PostgreSQL schema for assets, variants, upload sessions, processing jobs,
  subject-bound command idempotency, outbox and inbox, plus an implementation contract at
  `docs/plans/20260714-media-service-contract.md`.
- Added Go processing foundation: opaque generation-scoped MinIO keys,
  EXIF-aware JPEG canonicalization, 96/320/1280 WebP image variants, original-
  only video copying/FFmpeg rotation, checksums, duplicate-safe object writes
  and a MinIO adapter that emits signed URLs without exposing credentials.
- Updated the shared panel carousel and estimate/acceptance media mapping for
  images and videos; videos sort after images and open in the native fullscreen
  player. The carousel accepts a server rotation callback; browser IndexedDB
  behavior remains an explicit development fixture.
- Verification: Docker Go 1.25 with `libvips-dev` passed
  `go test ./...` in `services/media-service`; panel typecheck, ESLint,
  production build and 8 focused media Vitest tests passed. The V1 SQL also
  applied cleanly to a temporary PostgreSQL 17 database; this is not yet a
  Flyway baseline/upgrade/repeat gate. No production HTTP/PostgreSQL/Kafka
  runtime or active-stage cutover is claimed by this foundation entry.

## 2026-07-16 Asset-service implementation and partial verification

- Added the `asset-service` Gradle module, Flyway V1 ownership schema, canonical
  rental/equipment/ledger/hold/lease model, service-local event store and
  sanitized Kafka outbox/inbox/quarantine/DLT implementation.
- Added asset OpenAPI/event schemas, scoped warehouse registry credential and
  endpoint access, a stateless gateway asset route, a local `asset-db`, and the
  authorized panel HTTP adapters/routes.
- Preserved the fail-closed media gap: photo upload/read is not emulated because
  the required media endpoint is not presently available at the approved edge.
- The focused Java 26.0.1 repeat passed `:services:asset-service:test` (25
  tests) and `:platform:architecture-tests:test` (16 tests). It includes the
  Flyway/JPA Testcontainers checks, event/contract policy, ownership rules and
  the exact OAuth warehouse-registry client path.
- The narrow cross-service checks changed for this cutover also passed on Java
  26.0.1: declarative auth client registration (1 test), warehouse registry
  endpoint (3 tests) and gateway route (18 tests). They are focused checks, not
  a claim that every pre-existing service test ran green.
- Panel typecheck, ESLint and production build passed. The focused asset/gateway
  Vitest set passed 18 tests, and the final full Vitest run passed all 41 files
  / 243 tests. OpenAPI and event-schema files also parse successfully.
- `panel/e2e/asset-cutover.spec.ts` supplies a test-only HTTP fixture and
  desktop/tablet/mobile coverage source. The runtime attempt was blocked before
  page execution because Playwright is configured for Chrome at
  `/opt/google/chrome/chrome`, which is absent in this environment.
- The complete broker-outage/upgrade matrix, executable browser evidence,
  approved media rental-item API and reviewed scoped commit are still not
  claimed as complete.

## 2026-07-16 Stage and repository consolidation readiness audit

- Reconciled the already approved combined Stage 3–4 boundary across
  `AGENTS.md`, `ACTIVE_STAGE.md`, the roadmap, services registry and durable
  architecture memory: it is one stateful Go `media-service`, not a separate
  stateless photo worker. User-confirmed completion is preserved without
  inventing runnable HTTP/PostgreSQL/Kafka exit evidence. Java platform and
  services remain one Gradle multi-project; the Go module intentionally remains
  an independent build. No other deployable or database ownership was merged.
- Added actual Stage 5 MapStruct use for the equipment-catalog entity-read to
  DTO path and a generated-implementation test. Replaced manual constructor
  injection in five asset API classes with Lombok `@RequiredArgsConstructor`;
  JPA entities and domain transitions remain explicit. Current MapStruct 1.6.3
  and Lombok 1.18.46 processor/binding guidance was checked against current
  documentation.
- Expanded shared architecture policy coverage to import warehouse mappers and
  scan warehouse JPA sources. The final sequential policy repeat passed 16 of
  16 architecture tests and all root plus seven subproject approved-dependency
  verifiers (`BUILD SUCCESSFUL`, 33 tasks). The focused asset suite passed 26 of
  26 tests, including the new generated mapper test.
- Panel verification passed direct TypeScript project/type build, ESLint,
  production Vite build and all 41 Vitest files / 243 tests. The normal npm
  shims are not executable in this workspace, so their underlying Node CLIs
  were invoked directly where required. All three affected Playwright projects
  reached browser launch and stopped before page execution because configured
  Chrome `/opt/google/chrome/chrome` is absent.
- The combined Go media foundation passed `go test ./...` in a disposable Go
  1.25 Bookworm container with `libvips-dev`; both current packages passed. This
  remains foundation evidence, not reconstructed combined-stage runtime exit.
- A forced sequential root Gradle test is not green. It stopped in
  `platform:spring-boot-starter` after 54 of 56 tests passed: the AMQP isolation
  test expects obsolete `networks.default` instead of `rwms-media-compat`, and
  the real Kafka broker test proves `byte[]` message keys are incompatible with
  the enforced `StringSerializer`. The Kafka container started successfully;
  this is a code/configuration mismatch, not a broker outage. Later service
  suites did not execute in that root run.
- Independent backend/domain review keeps Stage 5 open. Fenced cabin write-off,
  operation-lease exclusivity, hold commit, classifier event sourcing,
  deterministic replay, real Kafka recovery, prior-version migration policy,
  executable responsive browser evidence and a reviewed scoped commit remain
  incomplete. Stage 6 was not started.

## 2026-07-16 Stage 5 asset blocker implementation and verification

- Replaced the fenced write-off delegation to the public manual status command
  with a lease-validated internal transition. `WRITTEN_OFF` remains forbidden
  on the public manual API. Public passport, status, warehouse, comment/note
  and contents/disposition mutations now fail while an operation lease is
  active; the fenced operation remains the only write-off path.
- Added Flyway `V2__asset_event_stream_completion.sql`, `COMMITTED` allocation
  hold state and commit command/event. Commit retains the hold against available
  stock but deliberately creates no logistics movement or future-stage owner.
- Added `CLASSIFIER` event-stream/outbox support and `AssetReplayVerifier`.
  Replay validates ordered stream versions, canonical payload hashes/outbox
  rows, stream heads and live-projection parity, then writes a named shadow
  checkpoint. The replay test executes twice with stable checksum/count.
- Corrected Cloud Stream/Kafka transport: outbound aggregate keys are byte
  arrays and the common binder now uses `ByteArraySerializer`; asset producer
  bindings are synchronous. Replaced seven same-group topic listeners with one
  multiplexed functional consumer that verifies received-topic/aggregate-family
  correspondence before the atomic inbox/checkpoint effect.
- Added/updated focused tests for fenced write-off, lease exclusivity, hold
  commit/availability, classifier replay, retry (initial plus 1s/2s/4s), and
  real PostgreSQL/Kafka outage recovery. The latter proves pending outbox/no
  timestamp during pause, recovery publication, duplicate inbox safety,
  version-gap quarantine/checkpoint block and sanitized validation DLT.
- Verification on the repository-supplied temporary Java 25 toolchain:
  `JAVA_HOME=/tmp/jdk-25.0.3+9 PATH=/tmp/jdk-25.0.3+9/bin:$PATH bash gradlew
  --no-daemon :services:asset-service:test` completed successfully on
  2026-07-16. JUnit XML reports 31 tests, 0 failures, 0 errors and 0 skipped.
  The event schema JSON also parsed successfully. This is not a Stage 5 exit:
  the approved pre-V1 upgrade fixture, executable browser matrix and reviewed
  scoped human commit remain outstanding; no Stage 6 work began.
