# Panel Operational Repair Estimates

Date: 2026-07-10.

Scope:

- operational estimate route `/estimates` in `panel`;
- its read connection to estimate catalog settings at `/settings/estimates-repairs`;
- the current mock boundaries that are intended to be replaced by service clients in a future microservice architecture.

Status: implemented as a working React mock behind explicit DTO/command ports. This document does not define final HTTP contracts.

## Screen Boundary

The target keeps two different user concerns separate:

- `/estimates` is the operational estimate list/editor. It is implemented by `panel/src/features/repair-estimates/repair-estimates-page.tsx` and registered in `panel/src/App.tsx`.
- `/settings/estimates-repairs` is catalog and repair configuration. It is implemented by `panel/src/features/settings/estimates-repairs/estimates-repairs-settings-page.tsx`.

This mirrors the legacy separation between the operational `RepairEstimate.view` and the catalog/settings views. The operational screen reads the configured catalog; it does not own or copy catalog records.

Legacy evidence:

- `wms-panel-old/src/main/java/dev/buhanzaz/wmspanel/view/repairestimate/RepairEstimateView.java`
- `wms-panel-old/src/main/resources/dev/buhanzaz/wmspanel/view/repairestimate/repair-estimate-view.xml`
- `wms-panel-old/src/main/java/dev/buhanzaz/wmspanel/service/RepairEstimateService.java`
- `wms-panel-old/src/main/java/dev/buhanzaz/wmspanel/service/RepairEstimateTaskPlanService.java`
- `wms-panel-old/src/main/java/dev/buhanzaz/wmspanel/service/RepairEstimateTaskPlanGenerationService.java`
- `wms-panel-old/src/main/java/dev/buhanzaz/wmspanel/view/repairestimatecatalogcanvas/RepairEstimateCatalogCanvasView.java`
- `wms-panel-old/src/main/java/dev/buhanzaz/wmspanel/view/repairestimatecatalogsection/AbstractRepairEstimateCatalogSectionView.java`

Migration impact:

- do not merge operational estimate commands into catalog settings APIs;
- do not make the operational editor read the settings store directly when an HTTP client replaces the mock;
- keep settings mutations and operational catalog reads as separate contracts even if they are initially served by one catalog microservice.

## Implemented Operational Flow

The current `/estimates` feature provides:

- warehouse-scoped lists with `DRAFT` and `COMPLETED` tabs displayed as `Требуют доработки` and `Завершённые`;
- deep-link selection through `?estimateId=...`;
- creation and editing in a route-local fullscreen workspace, not in a modal editor dialog;
- a desktop 2-by-2 workspace with `Фото`/`Информация`/`Смета`/`Кнопки` zones and a responsive single-column flow on smaller screens;
- a paged, searchable rental-item picker backed by the inventory `search`/`resolveById` port;
- source (`От кого`), dispatch date, comment, structured `WORK`/`MATERIAL` lines, exact totals, and up to 20 photos;
- no editable `Кому` field in the new workspace or list. `destinationText` remains optional at the command/read boundary, and deprecated `destinationParty` remains only for old-record compatibility;
- a photo manager with add/file selection, desktop drag-and-drop, preview, delete, and staged `0`/`90`/`180`/`270` rotation metadata;
- manual lines and a graph-aware catalog picker;
- draft save with optimistic version checking;
- manual or automatic completion with ordered task plans;
- read-only display after completion.

Evidence:

- `panel/src/features/repair-estimates/repair-estimates-page.tsx`
- `panel/src/features/repair-estimates/repair-estimate-editor-workspace.tsx`
- `panel/src/features/repair-estimates/repair-estimate-workspace-layout.tsx`
- `panel/src/features/repair-estimates/repair-estimate-rental-item-picker.tsx`
- `panel/src/features/repair-estimates/repair-estimate-lines-editor.tsx`
- `panel/src/features/repair-estimates/repair-estimate-catalog-picker.tsx`
- `panel/src/features/repair-estimates/repair-estimate-completion-dialog.tsx`
- `panel/src/features/repair-estimates/repair-estimate-photos.tsx`
- `panel/src/features/repair-estimates/repair-estimate-photo-manager-dialog.tsx`

## Catalog Connection

The catalog integration is a shared read boundary, not a second operational copy:

- `RepairEstimateCatalogClient` exposes `getOperationalCatalog()`.
- `getOperationalRepairEstimateCatalog()` is the operational facade.
- `REPAIR_ESTIMATE_CATALOG_QUERY_KEY` is shared by settings and operational queries so a settings mutation can invalidate every catalog projection without exposing persistence details to consumers.
- the current `repairEstimateCatalogMockClient` maps the settings-backed snapshot into neutral operational DTOs;
- a future HTTP client can replace that mock client without changing the estimate picker, catalog selectors, query keys, or estimate command models.

Evidence:

- `panel/src/features/repair-estimate-catalog/api/repair-estimate-catalog-client.ts`
- `panel/src/features/repair-estimate-catalog/api/repair-estimate-catalog-api.ts`
- `panel/src/features/repair-estimate-catalog/api/repair-estimate-catalog-mock-adapter.ts`
- `panel/src/features/repair-estimate-catalog/model/repair-estimate-catalog.ts`
- `panel/src/features/settings/estimates-repairs/api/repair-estimate-catalog-store.ts`
- `panel/src/features/settings/estimates-repairs/estimates-repairs-settings-page.tsx`

The operational projection uses only active nodes and links. Parent hierarchy remains separate from explicit graph links. Only `WORK`, `MATERIAL`, and `OPTION` nodes marked `includeInEstimate` become estimate-line candidates; an `OPTION` is represented as a material line. `FOLLOW_UP` remains directed, while dependency-related selection can traverse a dependency in either direction.

Effective queue routing is resolved from the selected node through its catalog ancestry. An explicit `workQueueCode` has priority over a generic `routeQueueKind`; only if no explicit code is found is the kind used. This preserves a concrete queue binding for future workflow dispatch instead of reducing it to a broad queue class.

The current database-derived mock seed reports 232 nodes and 254 explicit links. These counts describe the current seed in `panel/src/features/settings/estimates-repairs/api/repair-estimate-catalog-seed.ts`; they are not an eternal business invariant or a final service contract.

## Microservice-Ready Frontend Boundaries

| Bounded context | Frontend port/facade | Current mock adapter | Future ownership impact |
|---|---|---|---|
| Repair estimates | `RepairEstimatesClient` (`list`, `getById`, `saveDraft`, `complete`) | `LocalStorageRepairEstimatesAdapter` | Replace with estimate-service HTTP client; keep commands and warehouse/version checks explicit. |
| Estimate media | `RepairEstimateMediaClient` (`upload`, `hydrate`, `dehydrate`, `updateRotation`, `discard`) | `IndexedDbRepairEstimateMediaAdapter` | Replace with media-service upload/reference contract without adding binary storage to estimate DTO persistence. |
| Rental inventory read | `EstimateRentalItemsClient` (`search`, paged result, `resolveById`) | `panelEstimateRentalItemsClient` | Replace with inventory/rental read API; estimate service still validates the selected item and warehouse on write. |
| Repair catalog read | `RepairEstimateCatalogClient` | settings-backed mock catalog adapter | Replace with repair-catalog service read client. |
| Workflow dispatch/status | `RepairEstimateWorkflowClient` is read-only in the frontend | estimate mock exposes stored request status | Estimate service owns outbox creation; UI must not dispatch workflow after completion. |

Evidence:

- `panel/src/features/repair-estimates/ports/repair-estimates-client.ts`
- `panel/src/features/repair-estimates/ports/repair-estimate-media-client.ts`
- `panel/src/features/repair-estimates/ports/estimate-rental-items-client.ts`
- `panel/src/features/repair-estimates/ports/repair-estimate-workflow-client.ts`
- `panel/src/features/repair-estimates/api/repair-estimates-api.ts`
- `panel/src/features/repair-estimates/adapters/panel-estimate-rental-items-client.ts`

React pages and components do not access browser persistence directly. `localStorage` and IndexedDB remain hidden behind adapter/facade layers. The estimate, media, rental inventory, catalog, and workflow boundaries are intentionally separate so future services can evolve and fail independently.

## Mock Persistence And Consistency

Current mock facts:

- estimates use the versioned envelope `rwms:repair-estimates:v1` with service/schema metadata and a store revision;
- **SUPERSEDED:** media no longer uses the `rwms:repair-estimate-media:v1` localStorage envelope as its current store. That key is read only by a best-effort one-time migration and is removed after successful migration or invalid legacy data; no new base64 media is written to localStorage;
- current media binary and metadata records use the IndexedDB database `rwms-repair-estimate-media`, object store `media`;
- catalog settings continue to use their own `rwms:repair-estimate-catalog:v2` store behind the catalog adapter;
- estimate mutations are serialized through an in-process promise queue and use an origin-wide Web Lock when supported; state is re-read immediately before commit;
- updates require `expectedVersion`, giving an `id + warehouseId + version` compare-and-set analogue;
- reads and writes are warehouse-scoped, and a rental item is resolved and checked against the estimate warehouse before persistence;
- a completed estimate is immutable in the mock;
- stable and unique `sourceLineKey` values are validated per estimate;
- money crosses estimate and catalog boundaries as decimal strings; estimate arithmetic uses integer minor units with `BigInt`, avoiding binary floating-point totals;
- media refs and pending uploads carry rotation metadata restricted to `0`, `90`, `180`, or `270` degrees. Rotation remains staged in the controlled draft; cancel does not persist it, while draft save/completion commits the estimate ref as the transform source of truth;
- estimate status, lines, task plans, and workflow request references are written in one estimate-envelope commit during completion;
- media is a separate boundary, so new uploads are compensated if estimate persistence fails; removal cleanup is post-commit and best effort. This is saga-like mock compensation, not a claim of one transaction across a future estimate service and media service.

Evidence:

- `panel/src/features/repair-estimates/adapters/local-storage-repair-estimates-adapter.ts`
- `panel/src/features/repair-estimates/adapters/indexed-db-repair-estimate-media-adapter.ts`
- `panel/src/features/repair-estimates/domain/repair-estimate-domain.ts`
- `panel/src/features/repair-estimates/model/repair-estimate.ts`
- `panel/src/features/repair-estimates/api/repair-estimates-api.ts`

Migration impact:

- HTTP commands must keep an optimistic version or equivalent ETag/precondition;
- the estimate service must own the atomic estimate-completion transaction;
- cross-service media cleanup and workflow dispatch require explicit retry/idempotency designs rather than UI-side chained writes;
- storage keys, schema versions, generated mock IDs, and mock error messages are implementation scaffolding, not API contract names.

## Workflow And Movement Limits

Completion stores plans as `PENDING_GENERATION`. A plan with a queue binding receives a workflow request reference with status `PENDING_EXTERNAL_DISPATCH`. The mock does not mark plans `GENERATED`, create board tasks, or claim that a queue/task service accepted the request.

`movementRequired` is only a stored estimate flag in the current mock. It does not move a rental item, change rental status, alter equipment/accessory balances, create a repair process, or create movement/board tasks.

The inventory adapter is read-only for the estimate feature. There is no cross-store mutation of rental-item, equipment, accessory, queue-board, or task-board state during draft save or completion.

Evidence:

- `panel/src/features/repair-estimates/model/repair-estimate.ts`
- `panel/src/features/repair-estimates/adapters/local-storage-repair-estimates-adapter.ts`
- `panel/src/features/repair-estimates/adapters/panel-estimate-rental-items-client.ts`
- `panel/src/features/repair-estimates/ports/repair-estimate-workflow-client.ts`

## UNKNOWN / Required Backend Decisions

- Final HTTP endpoints, DTO versioning, error envelope, optimistic-concurrency response, retry policy, and idempotency keys for draft save and completion are `UNKNOWN`.
- Ownership and freshness rules for rental-item snapshots, warehouse validation, and mapping between current `panel` rental statuses and legacy repair statuses are `UNKNOWN`.
- The final media upload, variant, authorization, reference, compensation, retention, and orphan-cleanup contract is `UNKNOWN`.
- The transactional outbox format and delivery contract between estimate, workflow, queue/task-board, repair-process, and movement services are `UNKNOWN`.
- Exactly when a plan changes from `PENDING_GENERATION` to `GENERATED` or `FAILED`, and how retries are observed, is `UNKNOWN`.
- Real movement-to-repair behavior, rental-item status transitions, inventory/accessory effects, and rollback rules are `UNKNOWN` in the target architecture.
- Queue registry ownership, lifecycle, allowed queue codes, and the settings UI/API for editing an explicit `workQueueCode` are `UNKNOWN`.
- Business meaning and validation of `От кого`, plus retention/migration rules for optional `destinationText` and deprecated `destinationParty`, are `UNKNOWN` beyond the current compatibility representation.
- Delete, reopen, correction, cancellation, and version-history policies for completed estimates are `UNKNOWN`.

## Fullscreen Workspace UI Polish

Current presentation facts:

- the four workspace cards use inset rings plus a one-pixel grid clearance so the outer rounded borders remain continuous when the desktop workspace owns scrolling;
- catalog cards are complete native-button hit targets with keyboard/focus semantics. The picker does not render a nested or visible `Открыть`/`Выбрать` button;
- the estimate-line section uses the headings `Смета` and `Комментарий`;
- `Источник` is not rendered. `sourceLineKey` and catalog snapshot metadata remain unchanged for persistence, logging, and future service commands;
- the photo-manager dialog title is `Добавить фото`;
- desktop photo management accepts multiple images through drag-and-drop or the file picker. On mobile, drag/drop handlers are not attached and only the multiple file picker is available;
- the photo dialog constrains child minimum widths and hides internal horizontal overflow without changing the desktop thumbnail grid.

Evidence:

- `panel/src/features/repair-estimates/repair-estimate-workspace-layout.tsx`
- `panel/src/features/repair-estimates/repair-estimate-catalog-picker.tsx`
- `panel/src/features/repair-estimates/repair-estimate-lines-editor.tsx`
- `panel/src/features/repair-estimates/repair-estimate-photo-manager-dialog.tsx`

Verification:

- `npm run typecheck`, `npm run lint`, and `npm run build` passed; Vite emitted only the existing chunk-size warning;
- `git diff --check` passed with line-ending warnings only;
- browser QA passed at desktop width `1353px`: clean zone edges, no document overflow, complete catalog-card click targets, no visible `Открыть`, one `Создать смету`, exact line labels, and no visible `Источник`;
- browser QA passed at mobile `390x844`: one-column zones, no page overflow, `Добавить фото` dialog without a visible horizontal scrollbar, no drag/drop text, and a working file-selection button;
- tested browser console logs contained no new errors for the verified estimate flows.

## 2026-07-10 Review Hardening

Catalog routing:

- `resolveEffectiveQueueBinding` now returns both fields from the first effective binding node. `workQueueCode` still determines grouping priority, while `routeQueueKind` is retained as fallback metadata instead of being replaced with `null`.
- Focused runtime verification used real seed descendants and confirmed task plans contain both `INTERNAL_WORKS + REPAIR` and `SANITARY_DISINFECTION + HOLDING`.

Picker navigation:

- Global search is cleared on selection, category/subcategory/option navigation, and mode reset. Selecting a searched work therefore reveals its linked materials immediately; selecting a linked material reveals locations without the old global result list masking the pending step.

Pending interaction safety:

- `readOnly || mutationPending` is propagated to information inputs, rental picker, line editor, catalog picker, and photo manager. Completion mode, movement flag, task-plan route/comment/order/duplicate/delete controls are also disabled during completion.
- This prevents edits made after a save/complete command snapshot from being discarded when the successful mutation closes the workspace.

Error semantics:

- `detailQuery.isError` renders a service/load alert. Only `detailQuery.isSuccess && data === null` renders the selected-warehouse not-found notice.
- Completion mutation errors render inside the still-open completion dialog with `role="alert"`; the dialog cannot be dismissed while the request is pending and becomes retryable/cancellable after failure.

Evidence:

- `panel/src/features/repair-estimate-catalog/api/repair-estimate-catalog-api.ts`
- `panel/src/features/repair-estimates/domain/repair-estimate-domain.ts`
- `panel/src/features/repair-estimates/repair-estimate-catalog-picker.tsx`
- `panel/src/features/repair-estimates/repair-estimate-editor-workspace.tsx`
- `panel/src/features/repair-estimates/repair-estimate-completion-dialog.tsx`
- `panel/src/features/repair-estimates/repair-estimates-page.tsx`

Verification:

- `npm run typecheck`, `npm run lint`, and `npm run build` passed; `git diff --check` reported no errors.
- Interactive browser regression was unavailable because no in-app browser tab was present; no browser pass is claimed for this review increment.

## 2026-07-10 IndexedDB Media And Draft Date Reconciliation

This section explicitly supersedes every earlier current-state statement that estimate media is persisted as a localStorage v1/base64 envelope.

Current media adapter facts:

- `IndexedDbRepairEstimateMediaAdapter` stores each media record in IndexedDB database `rwms-repair-estimate-media`, object store `media`. A record contains the `Blob` plus file name, MIME type, creation time, `contentVersion`, and rotation metadata.
- Upload, rotation update, discard, and legacy migration are serialized through one runtime-shared promise queue and the origin-wide Web Lock `rwms:repair-estimate-media:mutation` when supported. Each write set is committed by an IndexedDB `readwrite` transaction.
- Legacy key `rwms:repair-estimate-media:v1` is migration input only. Valid base64 records are converted to `Blob` records without overwriting existing IndexedDB IDs; the key is removed after success. Invalid legacy data is removed. A failed conversion/write keeps the key for another best-effort attempt. There are no new base64/localStorage media writes.
- Upload validation rejects files larger than 25 MB and batches larger than 250 MB. `navigator.storage.estimate()` is used when available, and quota failures are translated into operator-facing storage-capacity errors.
- Hydration uses `Blob` object URLs behind a 24-entry LRU cache. Concurrent hydration for the same media/content version shares one in-flight promise; `contentVersion` prevents a stale result from entering the cache.
- Cache invalidation is propagated across same-origin contexts through `BroadcastChannel` and all object URLs are released on non-persisted `pagehide`.
- `RepairEstimateMediaClient` remains the public frontend port. The persistence replacement did not expose IndexedDB, `Blob`, localStorage migration, or cache details to React components or estimate commands.

Draft date fact:

- A new draft initializes `dispatchDate` from the browser-local calendar (`Date#getFullYear/getMonth/getDate`) rather than UTC serialization. The authoritative warehouse-time-zone rule for creating/interpreting a date-only dispatch value remains `UNKNOWN` until the backend contract is defined.

Evidence:

- `panel/src/features/repair-estimates/adapters/indexed-db-repair-estimate-media-adapter.ts`
- `panel/src/features/repair-estimates/ports/repair-estimate-media-client.ts`
- `panel/src/features/repair-estimates/api/repair-estimates-api.ts`
- `panel/src/features/repair-estimates/domain/repair-estimate-domain.ts`

## 2026-07-10 Compact Estimate Workspace Refinement

Current presentation facts (supersedes only the earlier visible workspace-card labels/descriptions):

- The four 2-by-2 workspace cards no longer render the visible zone labels/descriptions `Фото`/`Медиа осмотра`, `Информация`/`Основные данные сметы`, `Смета`/`Работы, материалы и итог`, or `Кнопки`/`Каталог и управление сметой`.
- Information is a vertical form: `Бытовка`, `От кого`, and `Прибытие` use left labels with their control on the right; `Общий комментарий` is a large textarea below them.
- The estimate-lines area scrolls within its card. Its separator and total remain fixed at the lower edge of the card.
- Quantity uses explicit decrement/increment controls around a numeric text field rather than the browser's native number-input steppers. The calculated `Сумма` column spans the same desktop width as the line `Комментарий` column.
- Operational catalog cards render three per desktop row, at most nine per page. Their category/type badges are anchored at the bottom of the cards. The footer contains previous/next page controls beside the cancel/save/complete controls; an available direction uses the primary style, and an unavailable one is disabled with the outline style.
- Paging resets when search, catalog mode, hierarchy navigation, or linked material/location flow changes, so every catalog level uses the same nine-card limit.

Evidence:

- `panel/src/features/repair-estimates/repair-estimate-workspace-layout.tsx`
- `panel/src/features/repair-estimates/repair-estimate-editor-workspace.tsx`
- `panel/src/features/repair-estimates/repair-estimate-lines-editor.tsx`
- `panel/src/features/repair-estimates/repair-estimate-catalog-picker.tsx`

Verification:

- `npm run typecheck`, `npm run lint`, and `npm run build` passed in `panel`; Vite emitted only its existing chunk-size warning.
- Focused in-app browser verification at `/estimates` opened a new estimate and confirmed the renamed comment field, the absence of the four workspace-card labels/descriptions, nine visible catalog cards, disabled previous page, and enabled next page. The browser webview detached before a second-page click could be completed; the static page-size logic is covered by typecheck/lint/build.

## 2026-07-10 Estimate Catalog Breadcrumb Navigation And Layout Balance

Current presentation facts (supersedes only the earlier claim that every workspace-card heading is hidden):

- The information card deliberately shows the `Информация` heading, but still omits the former supporting description. The other workspace cards remain unlabeled.
- The wide-screen workspace uses a `12fr / 8fr` left/right grid: photo and estimate cards occupy the wider left column, and information/catalog controls occupy the narrower right column.
- The catalog path is an interactive breadcrumb. `Главное меню` and every entered category, subgroup, or option are buttons; selecting an ancestor returns directly to that level and clears search, pending linked selections, messages, and the local card-page index.

Evidence:

- `panel/src/features/repair-estimates/repair-estimate-workspace-layout.tsx`
- `panel/src/features/repair-estimates/repair-estimate-catalog-picker.tsx`

Verification:

- `npm run typecheck`, `npm run lint`, and `npm run build` passed in `panel`; Vite retained only its existing chunk-size warning.

## 2026-07-10 Estimate Rental Picker Lazy Paging

- The бытовка picker no longer displays a `Показать ещё` control. The next server-style page is requested automatically when the list is within `48px` of its lower edge.
- A next-page request requires both `hasNextPage` and `!isFetchingNextPage`, preventing overlapping pagination requests. The picker shows a non-interactive `Загрузка бытовок...` status only while that request is active.

Evidence:

- `panel/src/features/repair-estimates/repair-estimate-rental-item-picker.tsx`

Verification:

- Browser verification at `/estimates` opened the бытовка picker, scrolled its list, and confirmed `Показать ещё` is absent.
- `npm run typecheck`, `npm run lint`, and `npm run build` passed in `panel`; Vite retained only its existing chunk-size warning.
- In-app browser verification at `/estimates` opened `Окна/Витражи`, confirmed both `Главное меню` and `Окна/Витражи` as clickable breadcrumb controls, then returned to the root through `Главное меню` and confirmed the root category was visible again.
- Computed layout inspection confirmed the wide-screen left/right columns measure `586.797px / 391.203px` for the tested viewport.

## 2026-07-10 Estimate Workspace Context And Field Alignment

Current presentation facts (supersedes only the earlier statement that information has no supporting description and catalog has no card heading):

- The `Информация` and `Каталог` cards each use a card title plus an operator-facing supporting description. Their descriptions explain the required information and the catalog selection purpose without restoring the removed legacy zone descriptions.
- The `Бытовка`, `От кого`, and `Прибытие` rows use a fixed `8rem` label column and a flexible field content column. Each control therefore starts on one shared vertical boundary and stretches to the right edge of the information card.
- The operational estimate page no longer renders the page-level `Сметы` heading or selected-warehouse label. A new/editor workspace has an additional top `Отмена` action; the footer cancellation action remains available.
- A blank estimate remains an allowed state; the editor does not silently create an invalid manual line. The existing `Добавить строку` and catalog selection paths create lines explicitly.

Evidence:

- `panel/src/features/repair-estimates/repair-estimate-workspace-layout.tsx`
- `panel/src/features/repair-estimates/repair-estimate-editor-workspace.tsx`
- `panel/src/features/repair-estimates/repair-estimate-catalog-picker.tsx`
- `panel/src/features/repair-estimates/repair-estimates-page.tsx`

Verification:

- `npm run typecheck`, `npm run lint`, and `npm run build` passed in `panel`; Vite retained only its existing chunk-size warning.
- In-app browser verification at `/estimates` confirmed both new descriptions, the absence of the page heading/warehouse label, two visible `Отмена` actions in the editor, and input bounds ending at the information-card edge.

## 2026-07-10 Estimate Catalog Header Action

- The `Настроить каталог` action belongs in the `Каталог` card header's right-side action slot, aligned with the title, rather than in the catalog content above search and mode controls.

Evidence:

- `panel/src/features/repair-estimates/repair-estimate-workspace-layout.tsx`
- `panel/src/features/repair-estimates/repair-estimate-editor-workspace.tsx`
- `panel/src/features/repair-estimates/repair-estimate-catalog-picker.tsx`

Verification:

- Browser geometry at `/estimates` confirmed the `Каталог` title and `Настроить каталог` action share the same top coordinate (`372.5px`) at the tested viewport.
- `npm run typecheck`, `npm run lint`, and `npm run build` passed in `panel`; Vite retained only its existing chunk-size warning.

## 2026-07-10 Estimate List Header And Empty-State Border

Current presentation facts:

- On the operational estimate list, the `Требуют доработки` / `Завершённые` status tabs and `Создать смету` action share one top-level responsive header. This removes the former empty vertical row between the action and status filter.
- On mobile, the tab group receives a `40px` left offset so it clears the fixed `40px` sidebar toggle while the two status controls remain on one horizontal line. The create action wraps beneath it when the viewport is narrow.
- The empty-state card has a one-pixel horizontal inset inside its scrolling parent. The shared card component renders its border as an outer ring; without that inset, the scrolling parent's clipping boundary cuts the left and right ring edges.

Evidence:

- `panel/src/features/repair-estimates/repair-estimates-page.tsx`

Verification:

- Playwright at `/estimates` confirmed at `1560px` that both tabs and `Создать смету` are on the top row (`19.5px` / `18px`) and that the empty-card ring stays within the scroll area.
- Playwright at `390x844` confirmed both tabs remain horizontal, clear the mobile menu toggle, and introduce no horizontal page overflow.
- `npm run typecheck`, `npm run lint`, and `npm run build` passed in `panel`; Vite retained only its existing chunk-size warning.

## 2026-07-10 Task-Plan Group Comment Alignment

Current presentation facts:

- In the completion dialog's desktop task-plan row, `Маршрут очереди` and `Комментарий группы` align from the same top edge. The short route selector no longer drops to the lower edge of the multi-line comment field.
- `Комментарий группы` remains a multi-line textarea, consistent with the legacy `RepairEstimateView` `TextArea` with a `4rem` minimum height. On mobile, the existing single-column form order remains unchanged.

Evidence:

- `panel/src/features/repair-estimates/repair-estimate-completion-dialog.tsx`
- `wms-panel-old/src/main/java/dev/buhanzaz/wmspanel/view/repairestimate/RepairEstimateView.java:2536-2548`

Verification:

- Playwright desktop geometry confirmed both field labels begin at `553.75px` in an opened completion dialog.
- Playwright at `390x844` confirmed the fields stack without horizontal overflow.
- `npm run typecheck`, `npm run lint`, and `npm run build` passed in `panel`; Vite retained only its existing chunk-size warning.

## 2026-07-10 Separate Estimate And Repair Workflows

Current target implementation:

- `/estimates` and `/repairs` are separate operational routes. `/estimates` owns estimate drafts/completion; `/repairs` owns direct repair creation and the queued-repair projection. `/task-board` remains separate.
- The shared `RepairEstimateWorkspaceLayout`, lines editor, photo manager, catalog picker, and `RepairWorkInformationFields` keep both editors visually consistent. Estimate and repair commands/models remain separate instead of treating a direct repair as a disguised estimate.
- `RepairTaskDto` is a target frontend aggregate with a cabin root and snapshot subtasks. The mock adapter is behind `RepairTasksClient`, uses the versioned `rwms:repair-tasks:v1` envelope, serializes mutations, applies optimistic version checks, and makes estimate-derived repair creation idempotent by `sourceEstimateId`.
- Estimate completion is coordinated in the application API. Empty completion writes the completed estimate and updates the cabin to `FREE` without a repair. Nonempty completion writes the completed estimate and upserts one queued repair containing every work/material line. If the downstream workflow effect fails, the local estimate adapter restores the previous estimate state.
- Direct repair completion rejects an empty line set. Draft save remains available for incomplete work. Direct repairs have `sourceEstimateId = null` and do not write the estimate store.
- The queued-repair detail hides queue/route and group-level comment controls. Work and material rows render their own comments in an adjacent desktop column and an associated mobile field. Multiple subtasks retain reorder/save-order behavior; hidden `queueCode`, `routeQueueKind`, and `groupComment` data is preserved during reorder persistence.
- Estimate and repair list projections expose the author snapshot and the product-defined field orders documented in `05_UI/README.md`.

Verification:

- `npm run typecheck`, `npm run lint`, `npm run build`, and `git diff --check` passed; Vite emitted only the existing chunk-size warning.
- Playwright on `/repairs` verified `Создать задание`, the `Причина` field, shared `Смета` workspace, direct repair completion, exact five-column order, author, and queued subtask detail.
- Playwright verified a direct repair leaves the estimate store unchanged and has `sourceEstimateId = null`.
- Playwright completed a nonempty estimate and observed a second repair under `/repairs`; the estimate list used `Номер бытовки`, `От кого`, `Автор`, `Статус`, `Создана`.
- Playwright completed an empty estimate and observed unchanged repair count plus cabin status `FREE`.
- A focused subtask fixture verified work/material comments, absence of queue/group-comment controls, reorder persistence, and byte-preservation of hidden routing/group metadata. At `390x844` there was no horizontal overflow; a single subtask exposed neither arrows nor a save-order action. Browser console had no errors.

Important migration boundary:

- Current `DRAFT`/`QUEUED` repair statuses and the root-with-subtasks projection are target mock contracts, not proven Spring DTO names. Legacy separates `RepairProcess`, `BoardTask`, and `QueueEntry`; final backend ownership and status projection remain `UNKNOWN`.

## 2026-07-10 Repair Detail, Movement Order, And Completed Amendment

Current target implementation:

- `RepairWorkDetailWorkspaceLayout` is shared by completed-estimate and repair-task detail. It keeps the existing 12fr/8fr desktop balance for photos/information and spans lower content across both columns; mobile is a single ordered column.
- `RepairTaskDto` now distinguishes root status, monotonic `startedAt`, source-estimate version, subtask kind, and subtask status. Storage readers normalize older mock rows to `REPAIR_WORK`, `WAITING`, and `startedAt = null`.
- Manual estimate completion can create `MOVE_TO_REPAIR` and `MOVE_FROM_REPAIR` plans and persist their arbitrary order with repair work. Material-only/unassigned lines receive deterministic fallback plan IDs.
- Task reorder accepts only the complete ordered subtask ID set. The adapter rereads the stored snapshot under its mutation lock, checks version/start guards, updates sort order only, preserves hidden metadata, and does not increment version for an unchanged order.
- Completed estimates render a static snapshot. Dedicated `amendCompleted` persistence keeps status `COMPLETED`, increments estimate version, locks rental item/cabin identity, and synchronizes the same linked task ID.
- Amendment captures nullable linked-task version and task-ordered plan IDs when editing begins. The repair-cycle API compares the captured version with the current task before estimate persistence and passes it into task sync CAS; a later reorder/start produces a conflict or guarded rollback rather than last-write-wins.
- `/estimates?estimateId=...` and `/repairs?repairId=...` provide durable bidirectional navigation.

Verification:

- Independent `npm run typecheck`, `npm run lint`, `npm run build`, and `git diff --check` passed. Vite emitted only its existing large-chunk warning.
- Playwright verified an intentionally noncanonical movement order during manual completion, task reorder persistence/reload, hidden metadata preservation, static cabin/amount display, task-order preservation through amendment, stable task ID with estimate/task version increments, conflict after a simulated task start with no partial estimate update, mobile `390x844` no-overflow order, exact repair columns, and empty estimate to `FREE` with no task.

Migration boundary:

- Legacy proves arbitrary route-entry reorder except an `IN_PROGRESS` entry (`QueueBoardService` and `QueueWorkBoardView`). The target's free movement-stage ordering is therefore evidence-backed and also an explicit product decision.
- The browser mock's same-row completed amendment is not a final revision-history design. Immutable historical revisions, HTTP preconditions/idempotency keys, and distributed estimate/task atomicity remain `UNKNOWN` for Spring services.

## 2026-07-10 Operational List Grid Presentation

- `/estimates` and `/repairs` now share `OperationsListGrid` for desktop list presentation. The component owns visual table chrome and client-side ordering only; estimate/repair queries, DTOs, routes, commands, and mock persistence remain unchanged.
- The grid maintains the existing target column orders and uses stable record IDs as row keys. Each header provides an accessible sort cycle: ascending, descending, then source order.
- Existing mobile cards remain the small-screen representation so the desktop five-column grid does not cause horizontal page overflow.

Migration impact:

- Treat this as target UI presentation. Do not derive a final server sort/query contract or alter legacy estimate/repair business transitions from this client-side sorting behavior.

Evidence:

- `panel/src/components/operations-list-grid.tsx`
- `panel/src/features/repair-estimates/repair-estimates-page.tsx`
- `panel/src/features/repairs/repairs-page.tsx`

## 2026-07-10 Empty Operational Grid Presentation

- Empty operational estimate and repair queries render the desktop `OperationsListGrid` with its column header and no body rows. The prior explanatory Card UI is removed by target product decision.
- This changes only the React presentation layer; query behavior, errors, commands, DTOs, persistence, and legacy-derived workflow semantics remain unchanged.
- Mobile has no empty placeholder card for these lists.

## 2026-07-10 Full-Height Operational Grid Surface

- The shared desktop grid surface now has a `min-height` equal to its available list workspace, so a short or empty result preserves the same framed working area as a populated registry.
- The list containers use the same layout on `/estimates` and `/repairs`; browser geometry confirms equal 24px left, right, and bottom insets from the app main surface.

Migration impact:

- This is a target layout decision. It changes no list query, item projection, command, or backend contract.
