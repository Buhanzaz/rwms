# Decisions

## 2026-07-14 Service-Only Delivery Boundary

- Supersede F4I deployment readiness without marking it complete. RWMS work
  stops at service code, contracts, migrations and local development/test
  dependencies; no VPS/VM, Kubernetes, Helm, Kind, Terraform, Ansible,
  deployment Compose settings, release pipeline or operations work is owned.
- Keep `compose.yaml` only for local service dependencies and tests. It is not
  a deployment manifest or a service exit gate.
- Remove existing F4I Helm/Kind/readiness tooling, observability backend
  configs and runbook. Preserve their documented candidate results as
  historical audit evidence, not as active instructions.
- Authorize W1 service-side evidence/contract work through `ACTIVE_STAGE.md`.
  This policy change does not itself implement Warehouse Service or authorize
  panel work.

## 2026-07-11 Responsive Task Board Columns

- Supersede the earlier phone-horizontal task-board decision. Phone viewports use the project's shared mobile classification and stack full-width queue columns vertically; tablet and desktop retain horizontal fixed-width columns.
- Mobile task cards use a stable `320px` minimum height. A queue displays at most three complete cards before its internal vertical card scroller takes over; empty queues remain compact.
- Do not use mandatory CSS scroll snapping on the tablet/desktop queue viewport. Free horizontal scrolling must keep the first queue anchored when the scrollbar returns to the left.

## 2026-07-11 CloudPub Mobile Development Authentication

- Superseded: the user requested restoration of the standard localhost login.
- Keep CloudPub mobile development on one public panel origin. Route auth paths
  through the Vite dev proxy to auth-service rather than exposing a second auth
  tunnel or weakening OIDC validation.
- Run auth-service with `dev,cloudpub` for this workflow so issuer, registered
  redirects, CORS origin, and secure cookies agree with the public HTTPS origin.
- Never leave a failed OIDC redirect as an unhandled promise; surface a retryable
  authentication error in the panel.

## 2026-07-10 Equipment Table Row Divider Ownership

- Expandable flex-grid rows own their single divider at the row wrapper; cells provide padding/alignment only and must not duplicate the bottom border.

## 2026-07-10 Warehouse Selector Vertical Alignment

- Keep the expanded sidebar warehouse selector on the same `24px` post-header top inset as the routed page toolbar.

## 2026-07-10 Shared List Toolbar

- Use `panel/src/components/page-toolbar.tsx` for the first-row composition of list-page searches, tabs, counters, and actions.
- Keep standard shadcn control height in this row; do not add page-specific search height overrides.
- Keep the site-header sidebar trigger aligned to the regular content inset without negative margins.

## Audit Documentation Decisions

- Keep all migration knowledge under `WMS_ARCHITECTURE_KNOWLEDGE`.
- Treat `wms-panel-old` as the only source of truth until a specific target decision overrides it.
- Use `UNKNOWN` for missing behavior instead of inferring.
- Document public/admin mobile API behavior as a risk, not as a target design.
- Preserve inconsistent enum storage details for migration. Do not normalize enum values without a deliberate data migration.
- Treat seed data as behavior because rental catalog, warehouses, queues, access, workers, and repair catalog depend on it.
- Treat programmatic FlowUI screens as business workflow sources, not merely UI code.
- Port behavioral service tests before implementing equivalent React workflows.

## Migration Strategy Decisions

- Start migration with security and warehouse access before public APIs.
- Migrate backend contracts before React screens for complex domains.
- Keep media pipeline protocol explicit: DB photo row, storage object keys, RabbitMQ task/result, Go worker variants.
- Keep queue board state machine explicit: REAL/SHADOW, route order, task status, assignment timing, worker interruption.
- Mark receiving, picking, shipping, and formal locations as absent/UNKNOWN unless future source evidence is found.
- Treat `panel/src/api` and `panel/src/types` as prototype UI scaffolding until backend migration contracts are deliberately defined from `wms-panel-old`.
- Keep `panel` build and lint green before adding migration work, so scaffold breakage does not hide domain regressions.
- Reuse the existing `panel` rental item table UI for migration work unless the user explicitly requests a redesign.
- Keep a working mock adapter for `panel` table workflows until Spring backend contracts are complete, so rental item filtering, dynamic columns, photos/history, column settings, and accessory movement can be tested.
- Mark mock-only table behavior as `UNKNOWN`/`mock-only` when legacy evidence is incomplete; do not promote prototype behavior to source of truth.
- Preserve legacy business semantics for migration decisions, but do not copy legacy framework-specific or architecturally poor implementation shapes into the target. Target React/Spring code should use explicit contracts, adapters, service boundaries, and documented deviations.
- For the rental item table, keep column/filter/settings behavior behind adapter-style functions in `panel/src/api` so the same React components can later call real Spring endpoints.
- Keep `panel/src/features/legacy-mock` as an isolated reusable React feature module until a deliberate routing/navigation and Spring API contract decision is made. Do not treat its local mock state, seed IDs, or mock-only actions as backend source of truth.
- Keep `panel/src/api/legacy-mock-domain-api.ts` and `panel/src/types/legacy-mock-domain.ts` as a full-domain mock registry boundary only. Do not treat it as the final Spring API contract, and do not silently replace the existing rental table adapter with it until cross-screen state reconciliation is deliberately planned.
- For active full-legacy mock routing, use `panel/src/api/legacy-mock-api.ts`, `panel/src/types/legacy-mock.ts`, and `panel/src/features/legacy-mock/legacy-mock-page.tsx` as the current route-facing facade. This facade is for UI workflow coverage only; do not derive final Spring DTOs from it without a separate legacy service/API contract pass.
- Keep legacy route aliases (`/rental-items`, `/repair-estimates`, `/queue-work-board`, etc.) available in React while preserving current working target routes (`/warehouse`, `/equipment`) unless a deliberate product navigation decision changes them.
- Keep rental item table schema generation inside the rental-items feature boundary. `panel/src/features/rental-items/model/rental-item.ts` owns DTO field metadata, automatic scalar DTO field discovery, column config normalization, filter definitions, and display/sort formatting helpers.
- Keep rental item adapter functions inside `panel/src/features/rental-items/api/rental-items-api.ts`; cross-feature mock APIs may import its read/write adapter while backend contracts are incomplete.
- For rental item photos, prefer explicit DTO variants over a single URL: `variants.small` for previews/thumbnail-sized UI and `variants.largeWebp` for fullscreen display. Preserve `url` only as a fallback/backward-compatible field until the final media API contract is defined.
- Fullscreen rental item photo UI must support pointer/touch horizontal swipes for previous/next and upward swipe to close on mobile.
- Supersede earlier active legacy mock route decisions for the current `panel` code shape: `panel/src/features/legacy-mock`, `panel/src/api/legacy-mock*.ts`, and `panel/src/types/legacy-mock*.ts` are not active target architecture and must not be recreated unless the user explicitly asks for full legacy mock pages again.
- Keep current rental item mock adapters narrow. Do not expose unused generic CRUD, photo mutation, or reset methods until a real UI workflow or Spring contract requires them.
- In the current `panel` sidebar, footer `Настройки` is a submenu toggle, not a direct navigation link. Keep `Настройка смет и ремонтов` and `Настройка Доски задач` as submenu entries unless product navigation is deliberately changed.
- Rental item grid format is target UI behavior, not legacy-derived behavior: hide format selection on phones, always use phone format `1x1`, cap tablet selection at `3x3`, cap desktop/PC selection at `5x5`, and preserve only manual tablet/desktop choices in warehouse-scoped localStorage.
- In the current `panel` sidebar, navigation clicks should auto-close compact navigation only on smaller breakpoints: close the mobile `Sheet` below `768px`, collapse the sidebar below `1024px`, and keep the wide desktop sidebar state unchanged.
- For the current `panel` rental item table, technical DTO metadata fields such as warehouse name, category/subcategory/type IDs, subcategory, audit fields, and internal media IDs are not user-facing columns. Default visible columns are the simplified business set: number, type, dimensions, finishing, category, characteristics, linoleum, and status.
- For the current `panel` rental item mobile toolbar, keep `Добавить новую бытовку` full-width like the search input, keep `Фильтры` and `Столбцы` equal-width, and keep the table/grid toggle size unchanged while aligning its right edge with the search input.
- For the current `panel` rental item card grid, keep the format selector bounded by viewport class: mobile stays fixed compact, tablet is limited to maximum `3 x 3`, and desktop/PC is limited to maximum `5 x 5`.
- For the current `panel` rental item card grid, split card height approximately `75%` for the photo/carousel/placeholder area and `25%` for the description/action area.
- For the current `panel` rental item card grid, treat the selected `N x N` format as an upper bound. The rendered grid must reduce columns/rows when available width or height would make cards too small.
- For the current `panel` rental item card grid, force TanStack Virtual to re-measure when the adaptive grid row height or row/column count changes so the initial page load uses the same layout measurements as later format switches.
- Supersede the previous rental item header-collapse behavior: the current `panel` app shell should not render a top app header above pages.
- Desktop/tablet sidebar collapse must use the existing `collapsible="icon"` rail behavior so the sidebar never disappears completely and the active icon remains highlighted.
- Because the app header is removed, desktop sidebar collapse/expand control belongs to the sidebar brand block itself: clicking the `WMS Panel`/blue warehouse icon block toggles expanded and collapsed states. Do not render a separate collapse/expand icon button in the collapsed strip.
- On mobile, render a fixed top-left blue warehouse-icon sidebar toggle. It must stay visible while the mobile sidebar is open, and the sidebar should visually slide out from and collapse back toward that button.
- Mobile sidebar behavior is shared by portrait and touch landscape phones: classify `(max-height: 500px) and (pointer: coarse)` as mobile in addition to the width breakpoint. The fixed toggle remains interactive while the Sheet is open, the Sheet content scrolls internally, and the closing transition shrinks it to the button's `40px` square before unmounting.
- The mobile sidebar morph is owned by paired `360ms` keyframes, not the default Sheet slide/fade classes: animate the panel between the `40px` toggle and the expanded sidebar, then stage sidebar-content fade/translation separately. Do not use `!important` on keyframe-controlled geometry, because it prevents the browser from interpolating the transition.
- Keep the blue mobile toggle visually clean: no persistent active-state ring or border. During sidebar collapse, keep the Sheet at the normal sidebar color and fade it to transparent; do not transition the Sheet itself to `sidebar-primary`.
- Tablet and desktop sidebar collapse remains the existing `collapsible="icon"` rail, with eased width/gap/label-opacity transitions and a fixed `48px` inner rail.
- For the current `panel` rental item grid, rotated phone-sized viewports must keep the same mobile behavior as portrait phones: fixed `1x1` and no visible grid format picker. Do not classify those viewports by width alone.
- For the current `panel` rental item page on mobile, the `Бытовки / N шт.` title row is the collapse toggle for the toolbar/search/filter block. Use a simple chevron: up while expanded, down while collapsed.
- In the current `panel` rental item detail route `/warehouse/:rentalItemId`, mobile and tablet users should be able to return with a left-edge horizontal swipe to the previous history entry; if no previous entry exists, fall back to `/warehouse`.
- Keep `panel` estimates/repairs settings APIs separated by future service/class boundary: estimate catalog canvas, work catalog, material catalog, furniture catalog, and repair settings must stay as separate adapter modules until real backend contracts supersede them.
- In estimate catalog settings, keep legacy technical catalog `code` and `sortOrder` fields in the model/mock for persistence and future backend compatibility, but do not display them in default category cards, catalog tables, or item forms. Codes may be shown inside deliberate detail/edit dialogs.
- In estimate catalog section tables, user-facing sorting by visible columns is target UI state and should be persisted per section/category. Do not use this persisted UI sorting as a backend ordering contract.
- In estimate catalog item forms, show `Длительность, мин` only for `WORK` nodes. `MATERIAL` nodes, including furniture items, do not display duration/time.
- In estimate catalog item forms, show `Мебельная категория` only while editing/creating `CATEGORY` nodes. It must not appear for work, material, or furniture item rows.
- In the estimate catalog constructor, keep categories visible on the canvas, but never offer `Категория` inside the `+ Блок` type selector. Top-level categories are created from the category menu.
- In the estimate catalog constructor, links are created through top/bottom card connection points. The separate `Связь` creation button/menu should stay removed.
- Display `RepairEstimateCatalogLinkType.DEPENDENCY` as `Зависимость` in React while preserving the stored enum value `DEPENDENCY`.
- Store constructor link anchor sides in `RepairEstimateCatalogLink.comment` using the legacy `__anchors__:SOURCE->TARGET` format.
- For the current estimate catalog mock, seed constructor links from `old_db/hsqldb/wmspanel.script` explicit `REPAIR_ESTIMATE_CATALOG_LINK` rows. Do not derive extra visible links from `parentId`; parent hierarchy remains separate node metadata.
- In the estimate catalog constructor, block dragging must persist positions through node `canvasX`/`canvasY` using the same node save adapter as editing, not through a separate mock-only layout store.
- In the estimate catalog constructor, deleting a selected link should be available directly on the canvas as an overlay action on the selected SVG link, while still using the same link delete adapter.
- In the estimate catalog constructor, point-to-point link creation should display a live foreground SVG preview while dragging between card connection points.
- Estimate catalog UI should not display legacy technical view IDs such as `RepairEstimateCatalogCanvas.view`; keep them only in mock metadata/source evidence.
- Supersede the earlier permission to show estimate catalog technical codes in detail/edit dialogs: codes stay in the model/mock for compatibility but are hidden from the current operator UI. New mock nodes receive a generated technical code through the catalog store until a final Spring API rule is defined.
- Estimate catalog node comments have a target UI and adapter limit of 180 characters. Invalid values must use the existing shadcn invalid field treatment, state the limit, and block saving; catalog cards and section-table rows must wrap and show the complete saved comment.

- Estimate catalog section DTOs must expose one complete node tree. Derive work, material, and furniture rows from it and traverse it for category membership; do not maintain a second copied leaf-item collection.
- Mobile sidebar lines must use `--sidebar-border` consistently for the Sheet edge, header divider, and controls. Keep the Sheet edge transparent in its collapsed animation state so the fixed blue mobile toggle has no visible border.
- Supersede the prior mobile Sheet-edge treatment: continue the header divider as a background line across `SidebarInset` while the menu is open. The mobile Sheet itself has no outer border.
- Supersede the mobile background-divider treatment: do not render a line behind the mobile Sheet. Keep the content card `4px` below the collapsed toggle and `4px` below the expanded sidebar header, animating its mobile top padding with the `360ms` menu morph.
- Supersede the mobile content-offset treatment: the mobile page background and open Sheet must share top coordinate `0`. Do not animate `main` top padding while the sidebar opens or closes, because the moving page layer is visually distracting.
- Mobile sidebar control visibility must use the same viewport condition as `useIsMobile`, including short coarse-pointer landscape phone screens. Do not rely on the width-only `md:hidden` utility for it.
- In mobile landscape, keep the sidebar header and settings footer fixed while the central navigation region scrolls vertically with contained overscroll and `pan-y` touch handling.
- The static `4px` content clearance below the closed mobile toggle applies to every mobile viewport classification, including short coarse-pointer landscape screens. Keep it as a static `main` padding rule; do not tie it to the open/closed sidebar animation.
- Supersede the global mobile content-clearance rule for the rental registry: preserve the normal `main` padding, and align the registry's title row beside the toggle using a mobile-only `3rem` left margin. The resulting `12px` gap after the 40px icon matches its 12px outer left gutter.

## 2026-07-10 Panel Operational Repair Estimates

- Keep operational estimates at `/estimates` separate from catalog/repair configuration at `/settings/estimates-repairs`; operational screens consume a catalog read contract and do not own catalog mutations.
- Keep estimate, estimate-media, rental-inventory read, repair-catalog read, and workflow-status boundaries as separate frontend ports. Replace mock adapters with service clients instead of exposing transport or persistence details to React components.
- Keep `localStorage` confined to mock adapters. Its keys, envelope revisions, generated IDs, and mock error text are not backend contract names.
- Serialize money as exact decimal strings at estimate and catalog boundaries and calculate estimate totals in integer minor units; do not use binary floating-point arithmetic for persisted money.
- Keep estimate writes warehouse-scoped and concurrency-controlled by `expectedVersion` or an equivalent HTTP precondition. Completed estimates remain immutable until a deliberate correction/reopen product policy is defined.
- Estimate completion owns the atomic write of completed status, lines, task plans, and workflow outbox references. The UI must not complete the estimate and then separately create workflow/board tasks.
- New task plans remain `PENDING_GENERATION`; `PENDING_EXTERNAL_DISPATCH` is an outbox/request state, not proof that workflow or task-board generation succeeded. Do not create fake `GENERATED` states or board tasks in the frontend mock.
- Keep media persistence outside the estimate aggregate boundary and compensate newly uploaded media when estimate persistence fails. Do not describe this cross-boundary compensation as a distributed transaction.
- Resolve catalog routing with explicit `workQueueCode` before generic `routeQueueKind`; preserve both fields until the final queue registry and workflow contracts are defined.
- Treat `movementRequired` as a mock estimate flag only. Draft save and completion must not mutate rental-item, equipment/accessory, repair-process, queue-board, or task-board stores.
- Render estimate create/edit as a route-local workspace inside `/estimates`, not as a modal editor dialog. Use the four desktop zones `Фото`, `Информация`, `Смета`, and `Кнопки`, collapsing to a single-column flow on smaller screens.
- Do not expose `Кому` in the new estimate workspace or list. Keep optional `destinationText` and deprecated `destinationParty` only as transitional read/write compatibility until a backend migration policy supersedes them.
- Keep rental-item selection behind paged server-style `search` and `resolveById`; do not load an unbounded inventory collection into the estimate editor.
- Treat photo rotation as staged estimate-ref metadata (`0`/`90`/`180`/`270`). Cancel must not persist a staged rotation; draft save/completion makes the committed estimate media ref authoritative.
- In the operational estimate catalog picker, make each catalog card one accessible button and do not render a nested `Открыть`/`Выбрать` button.
- Keep estimate-line origin metadata (`sourceLineKey` and catalog snapshot) for persistence, logging, and commands, but hide the operator-facing `Источник` field. Use the visible headings `Смета` and `Комментарий`.
- The estimate photo manager uses `Добавить фото` as its dialog title. Desktop supports multi-file drag-and-drop plus the file picker; mobile removes drag/drop handlers and keeps the multiple file picker only.
- Keep workspace zone rings inset and leave a one-pixel inner grid clearance so rounded zone edges are not clipped by the fullscreen workspace overflow boundary.

## 2026-07-10 Mobile Sidebar Compact Spacing

- Supersede the `3rem` rental-heading offset: use a `2.5rem` offset under the shared mobile media condition. This leaves a compact `4px` gap after the fixed `40px` toggle while retaining its `12px` outer gutter.
- Always hide `[data-sidebar="rail"]` within `[data-mobile-sidebar="true"]`. The rail is a desktop control; allowing its `sm:flex` utility in the mobile Sheet causes an overlapping edge control on wide or landscape phones.

## 2026-07-10 Global Mobile Page Title Alignment

- The initial `h1` or `h2` in each routed page must align at `56px` from the mobile viewport's left edge, preserving a `4px` gap after the fixed `40px` sidebar toggle at `12px`.
- Apply this through the shared sidebar-inset CSS rule instead of adding page-specific React markers. Exempt existing `data-mobile-page-heading` descendants because their container already owns the offset.
- When the initial title is inside a first-page card or section with `16px` internal padding and a `1px` border, use `calc(1.5rem - 1px)` instead of `2.5rem` so its rendered left edge still lands at `56px`.

## 2026-07-10 Mobile Estimates Settings Frame Removal

- On the mobile estimates/repairs settings route, render direct configuration sections as unframed page bands: no outer border, corner radius, or card background. This matches the warehouse registry's unframed page surface.
- Preserve the sections' internal spacing. With the outer `1px` border removed, the first title uses a `1.5rem` margin so it stays at `56px` from the viewport edge.

## 2026-07-10 Operational Estimate Review Hardening

- Catalog settings mock mutations must share one in-process queue and one origin-wide Web Lock when supported, then compare the revision captured at mutation invocation with a re-read made under the lock immediately before commit. A mismatch is a user-visible conflict, not last-write-wins.
- Preserve `rwms:repair-estimate-catalog:v2` data written before the revision envelope. Missing service/schema/revision metadata is a migratable legacy mock payload, while each successful new mutation increments exactly one revision.
- Explicit `workQueueCode` remains the catalog-routing and task-plan grouping priority, but it must not erase `routeQueueKind` from the same effective binding node; keep the kind as fallback metadata for a future queue-registry failure path.
- Selecting any global catalog search result that advances navigation must leave global search mode immediately, including category, subcategory, and option navigation and work-to-material-to-location selection.
- While draft save or completion is pending, prevent all estimate draft and task-plan state changes, not only footer actions. Completion failures remain visible inside the open dialog so the operator can retry or cancel.
- A failed estimate detail query is not a not-found result. Show not-found only for a successful `null` response; keep service/load errors in a distinct alert branch.

## 2026-07-10 IndexedDB Estimate Media

- Supersede the estimate-media localStorage/base64 mock. Current browser-local estimate media uses IndexedDB `rwms-repair-estimate-media` with Blob-plus-metadata records; `rwms:repair-estimate-media:v1` is only best-effort migration input and must never receive new writes.
- Keep IndexedDB implementation details behind the unchanged `RepairEstimateMediaClient` port so a future media-service client can replace the adapter without changing React components or estimate command boundaries.
- Serialize media mutations with the runtime-shared queue and origin Web Lock, and keep each mutation's IndexedDB write set inside one `readwrite` transaction.
- Enforce current mock upload limits of 25 MB per file and 250 MB per batch, with explicit capacity/quota errors rather than failed base64 serialization or silent partial writes.
- Keep hydrated object URLs bounded and coherent: LRU size 24, per-content-version in-flight deduplication, cross-context invalidation, and page lifecycle cleanup.
- New drafts use the browser-local calendar date. Do not infer warehouse-local or server-local date semantics until the backend warehouse-time-zone contract is defined.

## 2026-07-10 Compact Operational Estimate Workspace

- Keep the operational estimate workspace compact: zone-card headings/descriptions remain hidden, while the card layout and semantic `aria-label` stay intact.
- Keep estimate catalog pagination UI-local. It limits every root/subcategory/linked-selection level to nine cards; it must not change the catalog adapter, catalog graph semantics, or estimate command model.
- Keep the estimate total and its separator outside the scrolling lines list so they remain visible at the card bottom. Quantity must use explicit minus/plus controls rather than native number steppers.

## 2026-07-10 Estimate Breadcrumb Navigation And Information Balance

- Keep `Главное меню` and all entered catalog ancestors as direct interactive breadcrumbs. Returning to an ancestor must clear lower-level transient catalog state and restore the first nine-card page for that level.
- The compact estimate workspace intentionally shows `Информация` as the only visible zone title. Keep the old zone descriptions hidden.
- Preserve the wide-screen `12fr / 8fr` workspace balance, with photo/estimate left and information/catalog controls right, unless a deliberate responsive-layout redesign supersedes it.

## 2026-07-10 Estimate Context Copy And Form Alignment

- Keep concise supporting text below the `Информация` and `Каталог` workspace-card titles. Do not restore the retired generic zone descriptions.
- Keep the three short information rows on a fixed `8rem` label column with flexible, full-remaining-width controls.
- Do not render the operational page title or selected warehouse label above estimates. While an estimate workspace is open, retain both a top cancellation action and the existing footer cancellation action.
- Preserve a blank estimate as a valid draft state; do not auto-create an empty manual line merely to fill the editor.

## 2026-07-10 Estimate Catalog Header Action

- Keep `Настроить каталог` in the `CardAction` area of the `Каталог` card header. It must remain aligned to the card title and outside the catalog's scrollable search/card content.

## 2026-07-10 Estimate Rental Picker Lazy Paging

- The estimate бытовка picker must use automatic next-page loading near the list end, never a visible `Показать ещё` pagination button. Guard the request with both `hasNextPage` and `isFetchingNextPage` state.

## 2026-07-10 Estimate List Header And Empty-State Border

- Keep the operational estimate status tabs and `Создать смету` action in one responsive list-header row; do not reintroduce a separate action row above the status filter.
- The empty estimate card must retain a one-pixel horizontal inset within its scrolling parent so the shared Card outer ring is not clipped at either edge.
- On mobile, offset the list-status tabs from the fixed menu toggle while preserving both status choices on one line; action wrapping is preferable to toggle overlap or horizontal page overflow.

## 2026-07-10 Task-Plan Group Comment Alignment

- In the completion dialog's desktop task-plan grid, align the route field to the row start so `Маршрут очереди` and `Комментарий группы` labels share one baseline. Keep the route actions bottom-aligned independently.
- Retain `Комментарий группы` as a multi-line textarea; it represents legacy `groupComment` data and must not be reduced to a short single-line input merely for visual alignment.

## 2026-07-10 Separate Estimates And Repairs

- Keep `/estimates`, `/repairs`, and `/task-board` as separate operational routes. Estimate creation belongs to `/estimates`; direct repair creation and the repair projection belong to `/repairs`; do not move the direct-repair flow onto `/task-board`.
- Keep the two list projections aligned to the exact product order. Estimates: `Номер бытовки`, `От кого`, `Автор`, `Статус`, `Создана`. Repairs: `Номер бытовки`, `Причина`, `Автор`, `Статус`, `Создана`.
- Reuse presentation components between estimate and direct-repair editors, but keep estimate and repair models, commands, persistence ports, and stores separate. A direct repair must never create an estimate as a side effect.
- Completing an empty estimate always sets the cabin to `FREE` and creates no repair or movement. Completing any nonempty estimate creates one cabin-root repair with snapshot subtasks, including material-only/unassigned lines.
- Keep completed estimates immutable. The repair detail may reorder multiple snapshot subtasks, but it does not expose queue/route or a group-level comment. Display each work/material `lineComment` with its own line, and preserve hidden routing/group metadata during reorder writes.
- Capture an author snapshot on estimate/repair creation. The current mock display values are transitional; do not promote `Текущий пользователь` or `Не указан` into backend contract values.

## 2026-07-10 Ordered Repair Detail And Guarded Amendment

- Supersede the blanket rule that a completed estimate is forever immutable. It is read-only by default, but a dedicated amendment command may update it only when there is no linked task or the linked task is `QUEUED`, has `startedAt = null`, and every subtask is `WAITING`.
- Do not implement amendment through ordinary draft save/reopen. Capture both the completed estimate version and nullable linked-task version when amendment begins, recheck them under the mutation lock, keep the cabin immutable, and reject conflicts without partial estimate changes.
- The linked task is authoritative for accepted execution order. Amendment must initialize existing plan IDs in current task order and preserve that order unless the operator deliberately changes it in the amendment planner.
- Model subtask kind independently from status. Current kinds are `REPAIR_WORK`, `MOVE_TO_REPAIR`, and `MOVE_FROM_REPAIR`; current statuses are `WAITING`, `IN_PROGRESS`, `PAUSED`, `DONE`, and `CANCELLED`.
- Permit arbitrary movement-stage order before work starts. This is an explicit target product decision and matches legacy route-editor behavior for non-`IN_PROGRESS` entries; do not impose move-to-first/move-from-last validation unless a later product decision supersedes it.
- Reorder through a full ordered ID set only. The adapter rereads stored subtasks, changes only order/sort values, preserves hidden queue/route/group metadata, uses version CAS, and treats an unchanged order as a no-op.
- Keep task/estimate links addressable through `repairId` and `estimateId` query parameters so reload and browser navigation preserve selection.

## 2026-07-10 Operational Task Board

- Keep `/task-board` as an operational projection over the existing repair aggregate. Do not create a second browser task store that can diverge from `/repairs`.
- Keep route execution order (`sortOrder`) independent from a card's position among different tasks in a queue (`queuePosition`). A board reorder must never rewrite another task's route order.
- Keep execution status independent from REAL/SHADOW. The first unfinished route stage is REAL; later unfinished stages are SHADOW. `PAUSED` is an execution status, not a route type.
- Taking work is queue-scoped: only the minimum REAL+WAITING entry in the full warehouse/queue projection may start. Search and card-level shortcuts must not bypass queue order.
- Board mutations share the repair adapter's Web Lock/runtime serialization and expected task version. Client DnD is optimistic presentation only; one command is sent after drop, conflicts roll back and refetch.
- For a REAL stage dropped onto a queue already occupied by its own future stage, preserve the legacy-safe route-role swap shape; reject other same-task unfinished duplicates.
- Pointer/touch DnD uses pointer-within collisions excluding the transformed active card, so leaving the board cancels. Keyboard DnD keeps closest-center navigation.
- Task creation remains owned by `/repairs?create=1`; board details remain addressable through `/repairs?repairId=...`.

## 2026-07-10 New York Dashboard Shell And Shared UI

- Supersede the earlier target decisions that prohibited a top application header, used the brand block as the primary desktop toggle, kept a fixed blue mobile toggle, animated the mobile Sheet from that toggle, or aligned first page headings to `x=56px`.
- The target shell is `SidebarProvider -> AppSidebar variant="inset" -> SidebarInset`, with an `18rem` sidebar variable and a `3rem` route-aware `SiteHeader`. The header owns the visible `SidebarTrigger` on every viewport.
- Preserve desktop `collapsible="icon"` behavior and tooltips. Mobile uses the standard modal shadcn `Sheet`; route navigation closes the mobile Sheet. Do not reintroduce the custom morph/keyframes or page-title offsets.
- Keep route content `min-h-0`, flex-filling, and internally scrollable. Do not force `h-svh` on the inset itself because inset margins must remain inside the viewport.
- Shared controls use New York v4 geometry and typography: default controls are `h-9`/`text-sm`, cards use the New York border/shadow/padding composition, and the root radius is `0.625rem`.
- Keep the current semantic sky/mist/status color tokens, `radix-mira` registry configuration, and Hugeicons library. Do not copy demo brand, GitHub, chart, document, avatar, or user data from `dashboard-01`.
- Preserve project extensions required by existing consumers, including `Card size="sm"` and the flex-column/gap contract of `PopoverContent`, when adapting official primitive styles.
- Keep the settings submenu expanded after navigating to one of its children; mobile/tablet navigation may still close or collapse the sidebar itself.

## 2026-07-10 Full Sidebar Collapse And Workspace Navigation

- Supersede the preceding `collapsible="icon"` decision. Desktop and tablet now use the existing shadcn `collapsible="offcanvas"` behavior; a closed sidebar leaves no icon strip, brand icon, rail, tooltip edge, or clickable residue.
- Keep the sidebar brand as a normal `/` link. It must not toggle the sidebar. Align its `SidebarHeader` and the main `SiteHeader` to the shared `3rem` height, and use the standard-size brand menu button rather than the former `h-12` variant.
- Use Hugeicons `PanelLeftIcon` for the header trigger. The trigger, mobile Sheet description, and any retained primitive accessibility copy are Russian; do not reintroduce `Toggle Sidebar` or render `SidebarRail` in `AppSidebar`.
- The route header uses the official shadcn breadcrumb composition. Detail/create/settings routes show a parent link, separator, and current page; ordinary routes show only the current page. Below `420px`, hide the parent and separator so the current label remains stable without overflow.
- Supersede the requirement for a top estimate/repair cancellation action. The top-left outline action is `Назад`; footer/editor `Отмена` actions remain form cancellation.
- Mark every in-app entry into an estimate/repair workspace explicitly in React Router location state. `Назад` uses `navigate(-1)` only for a marked entry; an unmarked direct deep link replaces to the cleaned `/estimates` or `/repairs` list URL.
- Keep estimate and repair creation URL-backed with `?create=1`. Cross-links and task-board entries must carry the same marker so estimate -> repair -> Back and repair -> estimate -> Back restore the exact prior workspace.

## 2026-07-10 Header Separator

- Keep the vertical separator between the `SiteHeader` sidebar trigger and the route breadcrumb at `h-3` (`12px`) using the existing shadcn `Separator` component.

## 2026-07-10 Header Separator Final Adjustment

- Supersede the preceding `h-3` decision: keep the vertical separator between the `SiteHeader` sidebar trigger and the route breadcrumb at `h-2` (`8px`) using the existing shadcn `Separator` component.

## 2026-07-10 Header Separator Centering

- Supersede the preceding `h-2` adjustment. Keep the vertical separator between the `SiteHeader` sidebar trigger and the route breadcrumb at `h-3` (`12px`) with `data-vertical:self-center`, so it has equal free space above and below within the header.

## 2026-07-10 Header Separator Double Height

- Supersede the preceding centered `h-3` size. Keep the vertical separator between the `SiteHeader` sidebar trigger and the route breadcrumb at `h-6` (`24px`) with `data-vertical:self-center`.

## 2026-07-10 Header Separator Guide Alignment

- Supersede the preceding 24px size. Keep the vertical separator between the `SiteHeader` sidebar trigger and the route breadcrumb at `h-8` (`32px`) with `data-vertical:self-center`, leaving eight CSS pixels of free space at the header's top and bottom edges.

## 2026-07-10 Full-Height Sidebar Brand Background

- Supersede the former standard-height visual surface for the sidebar brand: keep its link and icon/content sizing, but make its interactive surface fill the shared `3rem` sidebar header so hover/active background reaches the header boundaries.

## 2026-07-10 Rental-Item Detail Breadcrumb

- Supersede the generic `Склад > Бытовка` route title and the duplicate in-content path. The single rental-item breadcrumb belongs in `SiteHeader` and must show `Склад / <city> / <item number>` using the item's assigned warehouse.

## 2026-07-10 Sidebar Navigation Grid

- Align primary sidebar navigation to the brand grid: use a `32px` icon cell and a `40px` row, so menu icon edges and text origin match the top-left brand icon and title. Apply the same geometry to the Settings action.

## 2026-07-10 Equipment Header Count

- The equipment position count is dynamic for the selected warehouse and belongs beside the `/equipment` route title in the shared header. Do not restore a duplicate page-local title or hard-code the position number.
- Use `В бытовках` as the concise equipment-availability label in desktop and mobile layouts.

## 2026-07-10 Sidebar Warehouse Selector Alignment

- The warehouse selector is the sole visual control for choosing a warehouse in the sidebar; do not restore a separate `Склад` label above it.
- Align the selector vertically with the first page heading while preserving its full width and accessible name.

## 2026-07-10 Operational Estimate And Repair List Grid

- Desktop `/estimates` and `/repairs` lists use the shared `OperationsListGrid` with a compact muted table header and sortable visible columns; preserve the established five-column field orders.
- Grid sorting is local presentation state only, cycling ascending, descending, and source order. It must not be treated as a backend sorting or persistence contract.
- Keep existing summary cards as the mobile representation.

## 2026-07-10 Empty Operational List Grids

- Supersede the prior empty-state cards for `/estimates` and `/repairs`: desktop must render the empty `OperationsListGrid` header with zero rows, while mobile renders no placeholder card.
- This is target UI presentation only; do not infer an empty-list business state or backend contract from it.

## 2026-07-10 Full-Height Empty Operational Grids

- Desktop `/estimates` and `/repairs` grids must fill the available list workspace even with zero rows, keeping equal 24px left, right, and bottom insets from the main content surface.
- Keep the grid `min-height` rather than a fixed height so populated lists can grow and retain normal scrolling behavior.

## 2026-07-10 Estimate And Repair Completion Notices

- Do not display generic inline success notices when an estimate or repair is saved, completed, or created. Preserve the command result and navigation behavior without adding replacement copy.

## 2026-07-10 Settings Submenu Alignment

- Treat expanded settings links as peer navigation rows visually: use the primary row's 40px height, 32px icon cell, and label alignment. Do not restore a nested rail, extra indentation, or pixel translation.

## 2026-07-10 Rental Registry Heading Removal

- Do not restore the page-local `Бытовки` title or total-count badge on `/warehouse`. Preserve the desktop toolbar and mobile filter-parameter interaction without replacement copy.

## 2026-07-10 Task Board Empty Queues

- Empty task-board queues have no visible placeholder card. Preserve the empty queue's droppable section and `SortableContext`; this is a presentation-only change and must not alter the take, ordering, or drag-and-drop command semantics.
- Use `Начать` as the visible label for the existing queue take-next action.

## 2026-07-10 Task Board Inactive Toggle And Scrollbar

- `Неактивные` is the visible, local toggle for SHADOW entries. It uses pressed-button semantics and must neither persist a task change nor alter the existing SHADOW/REAL execution and DnD rules.
- Future SHADOW cards are intentionally muted, dashed, and translucent when shown. Keep the board scroller's reserved bottom spacing so its scrollbar does not cover queue outlines.

## 2026-07-10 Task Board Counter Alignment

- Keep `Создать задание` and `Неактивные` on standard shadcn Button variants. Do not reapply custom toolbar-button opacity.
- Keep the status-counter badge group as a non-scrolling, right-aligned toolbar element; only the queue-column surface may scroll horizontally.

## 2026-07-10 Estimate And Repair Editor Fields, Scroll, And Media

- Keep estimate-line comments at `h-9 min-h-9 resize-y`, matching adjacent form controls on first render. Keep the common repair-work comment at fixed `h-32 min-h-32 resize-none`; do not let browser content sizing enlarge it.
- Keep the root `SidebarProvider` bounded to `h-svh` so editor workspace panels have a finite height. Estimate-line overflow belongs only to their `overflow-y-auto overscroll-contain` region; retain the `Итого` separator and value outside it.
- `PhotoCarousel` is shared media infrastructure, not a rental-item-only component. It accepts a minimal photo model, preserves `blob:`/`data:` URLs, and uses supplied small/full variants. Repair estimate media must keep the full-screen viewer enabled.

## 2026-07-10 Unified Operational Grids And Registry Controls

- Use the pre-existing supplemental-equipment grid as the visual reference for estimates and repairs: muted shadowed header with `px-3 py-3` cells. Preserve equipment's grid structure and interaction behavior.
- Warehouse registry toolbar controls use the default 36px shadcn height; do not add local 40px overrides for its actions or search field.

## 2026-07-10 Transparent Inactive Task-Board Button

- The unpressed `Неактивные` Button keeps standard outline borders and hover behavior but has a transparent fill. When pressed, it uses the standard primary/default fill. Do not attach this purely visual change to task mutations, queue ordering, or SHADOW DnD semantics.

## 2026-07-10 Future Card Drag Appearance

- The in-column SHADOW card and its `DragOverlay` preview must reuse the same visual class. Keep its dashed, muted, translucent, elevated style consistent without affecting task actions, queue ordering, or drag eligibility.

## 2026-07-10 Warehouse Grid Standard

- Supersede the earlier equipment-as-reference decision: `/warehouse` is the canonical target grid. `/equipment`, `/estimates`, and `/repairs` must share its Lucide sort arrows, `41px` header geometry, `39px` row geometry, font treatment, muted shadowed header, and natural-width sorting controls. Do not change existing grid column widths to achieve this consistency.
- `GridSortButton` is the target shared visual primitive. Equipment sorting is local presentation state only and cycles ascending, descending, then source order; do not infer a backend sorting contract.

## 2026-07-10 Unified Grid Row Height

- Supersede the preceding 39px data-row geometry: the estimate-grid row is the visual standard. Primary desktop grids use 49px data rows while preserving the existing 8px vertical and 12px horizontal cell inset, column widths, headers, fonts, and sorting behavior.

## 2026-07-10 Target Repair Acceptance And Write-Off Lifecycle

- A nonempty estimate creates a repair with `origin=ESTIMATE`; a direct `/repairs` creation uses `origin=DIRECT_REPAIR`. An empty completed estimate still frees the cabin and creates no repair.
- Queued repairs set the cabin to `REPAIR`. Completion of every stage sets the repair to `COMPLETED/PENDING` and the cabin to `WAITING_REPAIR_CHECK`. Acceptance sets `ACCEPTED` and cabin `FREE`; write-off sets `WRITTEN_OFF` on both repair acceptance and cabin status.
- `WRITTEN_OFF` is a terminal target status for the cabin. Written-off cabins remain in the warehouse registry and are excluded from new estimate/direct-repair selection. Additional-equipment disposal is outside this decision.
- Direct repairs and estimates share one completion-planning UI and task-plan contract. Automatic mode produces canonical routing; manual mode preserves the submitted work/movement order.
- Board assignment is a structured snapshot of one eligible group and either one member or all active members. Pause, resume, and completion update both stage and assignment active-time snapshots.
- Result photos belong to the completed group stage. The target maximum is 20; stages containing any `photoRequired` work require at least three photos, while other stages allow zero.
- `/acceptance` is the active projection of `COMPLETED + PENDING` repair tasks; `/write-offs` is the read-only projection of `WRITTEN_OFF` repair tasks. Neither page owns a second persistence store.
- The current `Переделать` action is intentionally UI-only: group/worker selection is implemented, but the dialog contains only `Тест` and does not mutate the repair.
- Acceptance/write-off dossiers reuse the existing repair workspace, shadcn primitives, `OperationsListGrid`, and `PhotoCarousel`; the optional mobile natural-flow layout is presentation-only and defaults off for existing consumers.
- Supersede the assumption that write-off is available only after `PENDING` acceptance. Editable estimate and direct-repair draft workspaces expose the same destructive `Списать` action in their lower-right action group. Early write-off records a terminal repair-task snapshot and cabin `WRITTEN_OFF` immediately, without board execution.
- An unsaved estimate-origin write-off has `origin=ESTIMATE` with `sourceEstimateId/sourceEstimateVersion=null`; it must retain that explicit origin across storage normalization and must not render a fake estimate link. A saved estimate draft keeps its actual source snapshot.
- Early direct-repair write-off uses the editor-captured task id/version. A read of current storage may support media cleanup but must not replace the captured optimistic version.

## 2026-07-10 Write-Off Navigation And Equipment Projection

- `Списание` is a collapsible navigation parent with `Склад` at `/write-offs` and `Доп. оборудование` at `/write-offs/equipment`.
- Cabin write-offs remain the canonical `RepairTask`-backed archive and retain `?writeOffId=<repairTaskId>` compatibility.
- Until an equipment disposal ledger exists, the equipment subsection is a read-only projection of `EquipmentItemDto.writtenOffQuantity > 0`. Do not invent reason, author, date, event history, actions, or detail markers from this aggregate field.
- The legacy furniture-material link is a distinct catalog/accessory migration concern; it must not be approximated by the target's name-based or category-only equipment mock.

## 2026-07-10 Acceptance Rework Lifecycle

- A rework is a child `RepairTask` with `kind=REWORK` and `sourceRepairTaskId`; it is not an unrelated direct repair and does not modify the completed estimate snapshot.
- Queueing the child atomically changes the source from `PENDING` to `IN_REWORK`, creates/queues the child, and keeps the cabin `REPAIR`. Wizard open, cancel, and unsaved editor navigation do not mutate the source.
- Only `QUEUED` or `IN_PROGRESS` sibling reworks block another queue command. Abandoned drafts do not permanently block rework.
- Completing a child returns that child to `PENDING`; accepting it accepts its source chain and frees the cabin. Writing it off marks the chain terminal, while the write-off projection displays only the terminal leaf to avoid duplicate cabins.
- The wizard recipient option is preliminary and intentionally not persisted or used as a board assignment. Actual queues/order are chosen by the shared completion planner; durable group/worker routing remains future work.
- Selected source lines are cloned with new IDs/source keys. Empty wizard selection may open the editor, but the existing queue validator still requires a meaningful line before completion.

## 2026-07-10 Sidebar Submenu Hierarchy

- Supersede the earlier settings-submenu peer-row alignment: child navigation must use the standard shadcn `SidebarMenuSub` hierarchy, including its guide rail and horizontal inset, rather than imitate a primary row.
- Keep primary submenu triggers at 40px. Child links are compact 32px rows and use direct standard-size icons. A collapsed trigger uses a right arrow; an expanded trigger uses a down arrow.

## 2026-07-11 Auth And Task-Board Service Boundary

- Keep auth-service and task-board-service as separate Gradle modules with separate PostgreSQL databases and no cross-database foreign keys.
- Auth-service is the only owner of password hashes, USER roles/accesses, WORKER credentials, OAuth clients, authorizations, and signing keys. Task-board owns the Worker business aggregate and stores only login/status/error credential projections.
- Keep USER, WORKER, and SERVICE token subjects distinct. Panel accepts only USER; worker client accepts only WORKER; client-credentials tokens never look up a USER by client ID.
- Browser authentication is Authorization Code + mandatory PKCE with `sessionStorage`, five-minute access tokens, no browser refresh token, and an eight-hour auth session. Expiry repeats the redirect and reuses the auth session when still valid.
- Use separate OIDC and API JWT decoders: valid ID tokens may participate in RP-initiated logout but must be rejected as Bearer API access tokens.
- Require optimistic `version/expectedVersion` for user administration and every mutable task-board entity; stale HTTP commands return 409 and the UI refetches/clears stale dialogs.
- Only SYSTEM_ADMIN may manage SYSTEM_ADMIN. WMS_ADMIN may manage non-system USER accounts. Warehouse MANAGE permits warehouse queues/workers/groups; global worker-class mutation remains global-admin-only.
- Preserve legacy HOLDING-last, future-stage blocker, REAL/SHADOW promotion, class eligibility, and `stopTaskOnTake`. Resume only stages recorded as automatically interrupted.
- Keep `WarehouseInfo.id` as the existing mock key but use evidence-backed legacy UUID `serviceId` values for real microservice calls.
- Operational `/task-board` remains on the current repair-task adapter until a deliberate service snapshot/command cutover; real `/settings/task-board` must not create a second browser persistence store.

## 2026-07-11 Mobile Interaction Refinements

- Use a non-editable shadcn Select for choosing the active warehouse in the mobile sidebar; a warehouse choice is not a text-search task and must not invoke the soft keyboard.
- On mobile `/warehouse`, the header owns the registry-toolbar collapse control. Persist its presentational state in `?toolbar=collapsed` so it remains addressable and hidden toolbar controls are not focusable.
- Keep task-board queue columns horizontally scrollable and equal-height on mobile, with vertical scrolling contained inside each column. `Создать задание` and `Неактивные` share one equal-width, 36px control row.

## 2026-07-11 Temporary Local Content Authentication Bypass

- The local panel content workflow may use `VITE_DEV_AUTH_BYPASS=true` only through `.env.development`; it must also require `import.meta.env.DEV`, so a normal production build uses OIDC.
- Task-board bypass requires both Spring `dev` profile and `rwms.security.dev-auth-bypass=true`. It permits external content APIs only; `/api/internal/**` remains Bearer-authenticated even during local content work.
- Do not weaken auth-service for this temporary workflow. Direct user-administration operations remain an OIDC-only capability.

## 2026-07-11 Registry Toolbar And Equipment Grid

- On mobile `/warehouse`, keep filter and column settings as separate 32px outlined buttons. Use the standard outlined shadcn ToggleGroup for list/grid, with the same geometry and an 8px gap between every visible action.
- Keep the mobile `Добавить новую бытовку` action at 190px and left-align its content so the plus icon has the normal compact button inset rather than a large leading void.
- Desktop `/equipment` must reuse `OperationsListGrid`, including its sorting, sticky header, table semantics, and optional detail row. Preserve the existing mobile equipment card list to avoid forcing horizontal table scrolling on phones.

## 2026-07-11 Target Rental Logistics

- The approved target logistics names are `Возврат из аренды` and `Отгрузка в аренду`, grouped under `Логистика`.
- A registered return requires a currently `RENTED` cabin and an exact normalized match with its current tenant snapshot. It changes the cabin to `AFTER_RENT`; accepting without damage requires photos and changes it to `FREE` while clearing tenant and shipment date.
- Creating an estimate from a return reuses the standard estimate workspace with a locked cabin. Return photos remain immutable audit media; the estimate owns cloned media IDs.
- Shipment selects only `FREE` cabins, a company, date, and active movement-group driver. Successful creation changes every selected cabin to `RENTED` and stores tenant/shipment date plus a read-only contents snapshot.
- Browser-mock lifecycle writers share one rental-item mutation lock and validate allowed source statuses. `RENTED -> REPAIR` is rejected so a concurrent shipment cannot be overwritten by a stale repair completion.
- Return-to-estimate linkage uses version plus claim identity. A live claim blocks another tab; a malformed or older-than-ten-minutes mock claim becomes reclaimable. Exact same-estimate retries are idempotent; conflicting estimates fail closed.
- Operational desktop grids fill the remaining workspace when empty, retaining their header row; mobile keeps the approved card/natural-flow representation.

### Grouped returns and preparation tasks

- A return document has no business status. It owns several cabin lines; each displayed stage derives from the current cabin status and linked estimate/repair facts. Technical claim/link states remain internal.
- Client-list return lines require the selected tenant. A manual return line may select any currently `RENTED` cabin and must preserve `selectionSource=MANUAL` plus the original tenant snapshot for audit.
- Return stages are projected as `AFTER_RENT -> Ожидает осмотра`, `WAITING_ESTIMATE_CONFIRMATION -> Ожидает подтверждения сметы`, `REPAIR/WAITING_REPAIR_CHECK -> В ремонте`, and `FREE -> Свободна`. `WAITING_REPAIR_CHECK` remains part of repair acceptance and is not removed globally.
- Return damage and estimate facts are tri-state. Before inspection they are `Не указано`; acceptance without damage makes both `Нет`; a linked/pending estimate makes both `Да`.
- Shipment contents have immutable before and planned snapshots. Editing computes bring/take deltas but does not mutate cabin assignments or stock. Physical inventory movement remains a future task-completion responsibility.
- `Создать задачу` dispatches one standalone task-board task per changed cabin, never a `RepairTask`. The queue must be active, visible, and bound to active class `GENERAL_WORKER`; absence fails closed.
- Shipment preparation is write-ahead and recoverable. The shipment is persisted before POST, every attempt is persisted before dispatch, and late responses apply only to the exact `externalTaskId + dispatchAttemptId`. `PENDING` and `DISPATCHED` freeze incompatible edits; only finalization changes cabins to `RENTED`.
- Company/client Comboboxes accept both existing options and arbitrary nonblank text snapshots. Company directory and reservation identity remain behind future ports.
## 2026-07-11 Warehouse Inventory Decisions

- Inventory is a warehouse-scoped, versioned aggregate. Only one active session
  may exist per warehouse, and its expected population is frozen from active
  non-rented rental items at start.
- Origin, inspection, reconciliation/conflicts, and repair-publication are
  independent dimensions. An expected uninspected cabin remains `MISSING`; a
  found cabin may simultaneously contain staged work and conflicts.
- Completion freezes findings, catalogue line snapshots, media references, and
  exact statistics before optional publication. Missing/conflicting cabins require
  acknowledgment but do not prevent completion.
- Inventory work is not a repair task until a MANAGE command publishes it.
  Publication uses the inventory session/finding source key, is independently
  retryable after completion, and projects through existing repair/task-board
  storage rather than a second task-board ledger.
- Current persistence is explicitly a browser adapter (`rwms:inventory:v1`) and
  not a production service contract. UI components depend on ports and do not
  access localStorage or IndexedDB directly.
- The start dialog's warehouse, current author, and date are static snapshot
  presentation, not disabled or read-only form controls. They must not accept
  focus, selection, copy interaction, or open a date picker.
- Cabin conflicts are currently inferred from live warehouse, operational status,
  and tenant compared with the start snapshot because the rental DTO has no
  authoritative entity revision.
- An inventory finding owns the immutable repair workflow chosen during its
  inspection: completion mode, movement choice, and ordered stage snapshots.
  Automatic completion is regenerated from catalog evidence at save time;
  manual completion is the operator-selected workflow. Repair publication must
  consume this snapshot and preserve movement stage kinds.

## 2026-07-11 Rental Logistics Company Entry Mode

- Rental-return `От кого` and rental-shipment `Компания` currently operate in a
  soft test mode: the operator may select a suggestion or apply an arbitrary
  trimmed nonblank snapshot with Enter.
- Draft search text and the applied company snapshot are separate UI states.
  Dependent cabin queries and submission use only the applied value.
- A future strict/hardcore directory mode is intentionally not implemented yet.

## 2026-07-11 Returned Equipment Disposition

- Cabin contents recorded at rental return enter a quarantine/disposition queue;
  they are not silently added to stock and are not immediately treated as written off.
- A pending disposition has exactly three operator decisions: return/merge to stock,
  audited write-off with mandatory reason, or transfer/merge into another eligible
  cabin in the same warehouse. The current transfer is additive; it is not a literal
  two-cabin swap.
- A return receipt keeps versioned editable sender/date metadata. Its cabin membership
  may change only while the receipt and every cabin line remain pristine and no
  equipment resolution has been recorded.
- Undamaged acceptance and shipment fail closed while returned equipment remains
  unresolved. Generic cabin/stock movement commands enforce the same rule below the UI.
- The current disposition ledger and recovery journal are browser-adapter scaffolding,
  not a backend contract.

## 2026-07-11 Return Cabin Card Parity

- The return-receipt editor uses the same repeatable cabin-card composition as shipment creation: one initially blank `Бытовка 1` card, combobox selection, removal for additional cards, and `Добавить ещё бытовку` only after every current card is filled.
- Return candidates retain two explicit groups: selected-client cabins are `CLIENT_LIST`; other currently rented cabins are `MANUAL`. The visual unification must not collapse that return audit distinction.

## 2026-07-11 Factual Return Contents

- The current target supersedes returned-equipment quarantine for new return
  receipts created with factual contents. These lines use `FACTUAL` mode and
  retain expected and received cabin-content snapshots; existing historical
  lines default to `LEGACY_QUARANTINE` and retain their previous workflow.
- A return records the same operator inputs as shipment creation: party, date,
  active movement-group driver, repeatable cabin cards, and furniture
  quantities. The shared contents editor must not add a shipment-preparation
  task to a return.
- A factual received quantity becomes the cabin quantity when the return is
  accepted without damage or linked to an estimate. Missing expected quantity
  cannot be accepted as undamaged; estimate linkage records the delta as an
  equipment-register loss. The browser implementation is mock-only and does
  not reserve or physically move free warehouse stock.
- Factual contents may be corrected only while the return line is pristine;
  beginning inspection or estimate handling freezes the snapshot.

## 2026-07-11 Rental Creation Dialog Actions

- Keep the create-dialog section actions and its lower action group in one
  right-aligned 260px desktop/tablet column; mobile actions are full width.
- Characteristics is a nested creation step. Closing its dialog must preserve
  the still-open parent form and its unsaved values.

## 2026-07-11 Equipment Grid Quantity Alignment

- In the primary equipment desktop register, left-align quantity values with
  their column headings. Do not broaden this presentation rule to the reusable
  grid or expanded equipment-detail rows without a separate product decision.

## 2026-07-12 Rental Item Dossier

- Treat one photo folder as one proven source event, never as an arbitrary date
  bucket or one photo.
- Keep dossier/history media preview-only. Resolve original URLs lazily only in
  estimate, inspection, repair-work, and repair-acceptance contexts after the
  per-user preference is enabled.
- Do not synthesize legacy history. Pending logistics records are not completed
  rental events; accepted inbound lines are both inspection and movement facts.
- Keep reserve and return dossier tabs as explicit empty target states until a
  separate approved contract exists.
- Use typed additive cabin seeds for estimate/direct-repair creation and retain
  workspace-entry navigation state for correct Back behavior.
- Keep the dossier hero at 420px from tablet upward. Its passport may scroll
  internally so the complete factual field set does not expand the page hero.
- Dossier registers use the shared `OperationsListGrid` only at `lg` and above;
  below `lg` they use cards because the persistent sidebar makes `md` grids too
  narrow even on a nominal tablet viewport.
- Empty reservation and return tabs may expose the common filters, grid headers,
  and mobile empty text, but this presentation does not create or imply a data
  contract or historical rows.
- Mobile dossier empty states use centered muted text, not Card surfaces. The
  wide-screen empty grid header remains visible.
- Dossier date filters always expose two labelled calendar controls. One value
  means one exact locally displayed day; both values mean an inclusive range.
  Date comparison must use the same local calendar date as the rendered value,
  not the UTC prefix of an ISO string.
- Mobile photo event folders occupy one full-width row each. Filter controls
  occupy the full available width and remain separate from the action row.

## 2026-07-12 Fullscreen Photo Viewer Controls

- Center contained fullscreen media using symmetric mobile slide insets.
- Keep the active-photo counter in the same bottom control group, above the
  previous/next/rotate actions, so navigation state is visible during paging.

## 2026-07-12 Imported Rental Cabin Return Entry

- An imported cabin found in a client's rental must first be represented as a
  `RENTED` cabin with its rental snapshot, then selected in a distinct return
  receipt. Do not silently create it as free warehouse stock.
- Furniture conflict review is distinct from extra-equipment disposition.
  The latter continues through `/write-offs/equipment`; a return-facing UI
  must not present it as furniture handling.

## 2026-07-12 Dossier Register And Contents-Transfer Decisions

- Dossier register controls use the same compact search-plus-filter-chip
  grammar as the warehouse register. One date is an exact local day; two dates
  are an inclusive range.
- Photo folder density shares one responsive standard with the warehouse grid:
  phone `1`, tablet at most `3`, desktop at most `5`. A mobile viewport never
  overwrites the saved larger-screen preference.
- A cabin passport displays the newest proven activity date/actor, not a raw
  entity version and not an inferred `updatedBy` value.
- Cabin-to-cabin contents transfer is valid only inside one warehouse between
  active non-rented, non-frozen cabins. Both versions and quantities are
  rechecked; both cabin versions advance on success.
- Every operator-facing cabin-to-cabin transfer must create/recover one
  task-board `MOVEMENT` task before the cabin mutation. Dossier history reads
  only `APPLIED` transfer records; pending and conflicted attempts are not
  presented as completed facts.

## 2026-07-12 Return Equipment Conflict Gate

- An unresolved extra-equipment disposition is exclusive: render only
  `Конфликт оборудования` and block both undamaged acceptance and estimate
  creation until the operator resolves it in equipment write-offs.
- Keep every return-decision button at 208px so a replacement action does not
  visually shift the decision surface.

## 2026-07-12 Mobile Warehouse And Dossier Controls

- Keep the mobile warehouse create action flexible after the four compact
  toolbar controls; it must share the same right edge as the search and grid.
- In mobile photo folders, expose search, add-photo, and all filters behind
  one funnel button by default. The individual source and author chips use the
  funnel glyph as well.
- Apply the same collapsed funnel/search row to every dossier register. On a
  phone the expanded smart search is horizontally adjacent to the funnel;
  add-photo actions and chips remain on their own rows below it.
- Preserve the dossier tab-row position while a user changes tabs by reserving
  a mobile content area below it. Empty target tabs may use that area as blank
  space rather than allowing the parent scroll position to clamp and move the
  tab row.
- Mobile cabin grid cards prioritize the number/status row, then type,
  dimensions, category, finishing, and two rows of characteristics. This is a
  presentation decision over the existing DTO fields only.

## 2026-07-12 Warehouse Status Filter Colours

- Reuse the warehouse registry status badge token pairs in the corresponding
  filter options. Do not introduce arbitrary, filter-only colours: `Аренда`
  stays blue, `Свободна` stays green, and every other known status preserves
  its table meaning.

## 2026-07-12 Dossier Comments And Passport Layout

- Keep the desktop comment editors in one two-card row and place the recorded
  comment register below them. Preserve a stacked layout on phones.
- The dossier passport facts stay in one vertical column even on wide screens.
  Use the desktop hero's photo height to keep that complete passport visible;
  do not introduce an internal passport scroll or divide the facts into two
  columns.

## 2026-07-12 Logistics Quantity And Photo Intake

- In the logistics furniture picker, quantity is the selection source of truth:
  incrementing zero selects the row at one, and decrementing one clears it.
- Return-estimate photo intake opens the native image picker directly. On
  desktop its empty preview is also a drag-and-drop target; phone and tablet
  keep the direct picker without an empty drop surface.

## 2026-07-12 MOCK Logistics Coordination

- Return intake is one recoverable command; do not create an intermediate
  rented cabin that can survive without its receipt.
- The latest proven completed shipment is the expected return-contents source.
  Factual differences remain explicit and furniture may be split by quantity
  among cabin, warehouse stock, write-off, and task-backed cabin transfer.
- Shipment furniture is reserved when the plan is saved and physically applied
  only after explicit preparation confirmation. A target cabin cannot belong to
  two active shipment documents.
- Inter-warehouse documents may contain several cabins, but departure and
  arrival are confirmed per line. The warehouse changes only at destination
  acceptance; the prior eligible status is restored.
- Development logistics uses an active visible `MOVEMENT` queue bound to
  `GENERAL_WORKER`; administrators may add other class bindings, but removing
  the required binding makes logistics fail closed.
- Location correction is not a fake transport. It requires a reason and approval
  from both warehouses, persists an audit link, and creates no movement task.
- Cross-flow guards are mandatory while shipment or transfer intents are active.
  Pending task attempts are not completed dossier history.
- The transfer create form has no vehicle field. Cabin selection is one vertical
  card column with the number and factual contents in the same card.

## 2026-07-12 Unified Browser MOCK Task Board And Schedules

- Use one schema-versioned browser task-board envelope for development queues,
  ordering, workforce, schedules, tasks, assignments, pause reasons,
  interruption links, notifications, and the demonstration clock. React
  components consume ports; they do not read browser persistence directly.
- Select `BrowserTaskBoardClient` only when the explicit development auth bypass
  is enabled. Production keeps Bearer HTTP clients and must fail closed; the
  browser DTO/envelope is not the production service contract.
- Updates and destructive mock commands use `expectedVersion`. Initial repair
  and logistics task registration instead uses a stable `externalTaskId`; retries
  are idempotent and repair routes synchronize by stable source-step identity
  rather than array position.
- Pause causes are independent `MANUAL`, `SCHEDULE`, and `INTERRUPTION` reasons.
  Removing one reason never resumes a task while another reason remains.
- A `MOVEMENT` task bound with `stopTaskOnTake` interrupts active work sharing
  the selected workers, records explicit interruption links, and notifies the
  affected workers. Completion enters group-level `RETURNING`; early group
  confirmation or the configured grace deadline removes only the interruption
  reason. The initial grace period is three minutes.
- Group schedules use a warehouse time zone, day-specific shifts, and typed rest
  periods. Initial SPB schedules are Monday-Friday 09:00-18:00 with smoke breaks
  11:00-11:10 and 16:00-16:10, lunch 13:00-14:00, five-minute warnings, and
  automatic pauses; weekends are disabled. Linked template days follow template
  edits until individually changed.
- The visible clock simulator, worker switcher, inbox, and sonner notices are
  MOCK-only. Do not use the system Browser Notification API as an implicit
  production push channel.
- Stable additive seed data must create only absent classes, queues, workers,
  groups, schedules, and DEMO tasks; it must not overwrite operator changes.
  `HOLDING` remains last and is not manually assignable. Drivers are available
  to logistics but do not take board work; general workers also have the
  `RIGGER` qualification.
- Browser persistence never stores worker passwords. Mock credential reset may
  change only a credential status and append an event.

## 2026-07-12 F0 Platform And Migration Foundation

- Use JPA/Hibernate mappings as the target application model source. Permit
  `ddl-auto=update` only in explicit local development, use `create-drop` for
  isolated tests, and require `validate` in production; the common starter must
  fail production startup for schema-mutating Hibernate modes.
- Liquibase and Flyway are forbidden target dependencies. Existing auth and
  task-board Liquibase runtimes remain unchanged until their F1/F2 cutovers;
  preserve `databasechangelog*` as historical evidence.
- Apply production schema changes as reviewed, immutable PostgreSQL SQL releases
  under an advisory lock. Verify checksums before mutation and record applied
  version/checksum/description in each service's `rwms_schema_history`.
- Keep `platform:technical-contracts` framework-neutral and immutable with no
  runtime dependencies, Spring/JPA types, repositories, entities, or domain
  models. Keep the shared starter conditional and technical; it must not create
  security chains, persistence models, secrets, service URLs, or business rules.
- Use RabbitMQ as the common at-least-once integration bus through
  `rwms.domain.v1`, with publisher confirms, mandatory returns, exactly three
  retries after the first attempt, consumer-owned DLQ, producer-owned outbox,
  and consumer-owned inbox deduplication. F0 shares conventions, not outbox or
  inbox persistence implementations.
- Approve exactly two stateless deployable shapes: the F3 Spring gateway and the
  Stage 4 Go photo-processing worker. Neither owns a database or domain state;
  the gateway also does not participate in RabbitMQ.
- Keep migration backups, manifests, and detached trust-anchor values outside
  Git. Rollback trusts the separately stored manifest digest and requires a
  disposable restore/reconciliation before any destructive operator action.

## 2026-07-13 F1 Auth Foundation

- Auth schema authority is JPA plus reviewed PostgreSQL releases. Keep dev on
  `update`, isolated PostgreSQL tests on `create-drop`, and base/production on
  `validate`; never restore Liquibase or introduce Flyway.
- Preserve historical `databasechangelog*` tables as unused evidence until a
  separately approved cleanup release. New clean auth databases do not create
  them.
- Manage OAuth clients declaratively. Preserve stable registration IDs and
  issued-at timestamps; unchanged reconciliation must be byte-stable. Require a
  monotonically increasing revision for configuration or secret changes and
  reject revision rollback/config drift.
- Treat client disable and authorization revocation as separate commands:
  disable keeps stored identity/audit rows but lookup fails closed; explicit
  revocation at a new revision removes authorizations and consents.
- Store only secret-source names and policy metadata in configuration. Secret
  values remain external and must never be logged or committed.
- Use the gateway `/auth` URL as the base public issuer and keep direct
  localhost issuer only in explicit development. F3 must strip one prefix,
  trust forwarded metadata only from approved ingress, and exclude the panel
  `/auth/callback` route from the auth catch-all.

## 2026-07-13 F2 Task-Board Foundation

- Task-board schema authority is JPA plus reviewed PostgreSQL releases. Keep
  dev on `update`, isolated PostgreSQL tests on `create-drop`, and
  base/production on `validate`; never restore Liquibase or introduce Flyway.
- Preserve historical `databasechangelog*` as unused evidence. V0001-V0004 are
  the ordered adoption/integration/identity/credential-operation release chain;
  production records them in task-board-owned `rwms_schema_history`.
- Treat mutable version tokens as required non-negative command input. Return
  `400` when absent/null/negative and the shared `ApiProblem` `409` for stale
  optimistic state.
- Treat `externalTaskId` as idempotent only with the same immutable
  `task-board-create:v1` canonical fingerprint. Reject changed,
  cross-warehouse, or legacy-unfingerprinted reuse; changing the canonical
  format requires a new prefix and compatibility decision.
- Canonicalize new worker-class and queue codes to uppercase and worker logins
  to lowercase. Enforce case-insensitive uniqueness in PostgreSQL without
  rewriting historical values; normalized duplicate preflight must fail closed.
- Publish only committed board-task created/cancelled facts through the
  service-owned outbox. Preserve exact UTF-8 envelope bytes/hash,
  per-aggregate ordering, database-time lease owner/token fencing, confirms,
  mandatory returns, bounded retry/DLQ, and inbox event-ID deduplication.
- Keep `task-board-service.delivery-audit` a technical contract/inbox consumer.
  It must not mutate task-board domain state or republish the same fact.
- Serialize worker credential effects per worker with a PostgreSQL session
  advisory lock. Store operation ID/type/start together and fence local
  completions by operation ID; timeout recovery must not be interpreted as
  end-to-end fencing in auth-service.
- Keep queue/workforce/task authorization in task-board. Internal queue
  references require SERVICE principal, `queue-registry.write`, and explicit
  client allowlisting; worker credential calls require the granted
  `worker-credentials.manage` scope.
- Do not promote browser schedules, simulation clocks, worker notifications,
  DEMO tasks, or browser seed state into the F2 production contract. Their
  service-side parity remains a separate Stage 2 decision.

## 2026-07-13 F3 Stateless API Gateway Foundation

- Use Spring Cloud Gateway Server MVC as the one stateless browser edge. It has
  no database, JPA, schema releases, RabbitMQ, token exchange, or business
  aggregation and must remain default-deny for unapproved routes.
- Use one browser origin for OIDC and protected APIs. `/auth/**` removes exactly
  one prefix, the exact `/auth/callback` stays panel-owned, and
  `/api/task-board/**` maps to the owning service's existing `/api/**` contract.
- Validate external Bearer signature, expiry, public issuer, and audience at the
  gateway and repeat validation/authorization downstream. Internal
  client-credentials traffic never uses the public gateway.
- Strip all incoming forwarded metadata and synthesize canonical auth scheme,
  host, port, and prefix only from the validated public base. Require ingress to
  preserve that public `Host`; never derive issuer or redirects from arbitrary
  request headers.
- Use Spring Boot 4.1 plural `spring.http.clients.*` timeout properties.
- Keep production panel configuration fail-closed: do not fall back to direct
  auth/task-board origins or browser mocks when the gateway is unavailable.

## 2026-07-13 F1C Auth Warehouse-Existence Correction

- Keep warehouse-existence validation disabled by default until W1 deployment
  activation. Disabled mode performs no Warehouse Service call and must not
  block login, token issuance, reads, or current pre-W1 administration.
- When enabled, validate every distinct canonical UUID before persistence using
  a private client-credentials token scoped only to `warehouse.read`. Accept
  only exact `{id, version, active}` responses and fail closed on all invalid,
  inactive, mismatched, malformed, token, timeout, and upstream outcomes.
- Auth owns only opaque warehouse-access IDs. It gains no Warehouse entity,
  repository, projection cache, cross-database relation, or per-request/JWT
  Warehouse Service lookup.
- Canonicalize only the approved `spb` and `msk` aliases through reviewed SQL.
  Preserve source row IDs and `created_at`, delete or merge no grant, increment
  `version` and `updated_at` on changed rows, and abort before mutation on
  collisions or unmapped non-UUID values. Treat the SQL mapping, schema history
  and checksum as audit evidence.
- Use the verified pre-migration backup restore as V0002 rollback. Do not require
  or claim unproved compensating SQL for this alias rewrite.
- Allow `warehouse.read` in the panel client registration during F1C, but defer
  requesting that browser scope and activating the gateway warehouse route to
  W1.

## 2026-07-13 Approved F4 Event-Driven Modernization

- Treat F1C closure commit `d50922d` as the prerequisite and execute the F4
  governance approved on 2026-07-13 only in the order
  `F4K -> F4A -> F4T -> F4R -> F4G -> F4I -> W1`. A later gate is not
  authorized by this decision alone.
- Use Apache Kafka 4.3.1 through Spring Cloud Stream. Keep topics at aggregate
  family granularity, key every record by `aggregateId`, and version the event
  type/schema inside the envelope so one aggregate's lifecycle is ordered in
  one partition.
- Keep PostgreSQL transactional outbox and consumer-owned inbox. Delivery is
  at-least-once; event ID deduplication, body hash, aggregate-version gap
  detection, bounded retry and DLT are mandatory. Do not claim cross-database/
  Kafka exactly-once semantics.
- Apply full event sourcing only to service-owned non-secret aggregates in the
  existing auth and task-board services. Existing relational domain tables
  become replayable projections; baseline events are migration snapshots, not
  invented historical facts.
- Exclude PII and credentials from event payloads. Store protected PII or
  operational secret state only in the owning service's local vault/store and
  reference it opaquely when replay requires identity.
- Retain password hashes, OAuth JDBC state, signing/client secrets, sessions,
  leases, inbox/outbox delivery state, checkpoints and technical saga attempts
  as operational persistence rather than event-sourced aggregates.
- Preserve RabbitMQ facts as historical F0/F2 evidence. Remove Rabbit runtime
  from target Spring integration only in F4R after drain, duplicate/recovery
  and Kafka cutover proof. Keep an isolated media-compat RabbitMQ
  runtime/topology for the unchanged legacy Go worker until the Stage 4
  photo-processing cutover; it is not available for new business integration.
- Introduce `DomainEventEnvelopeV2` beside the existing V1 `EventEnvelope`, not
  as an in-place contract change. V2 allows nullable `occurredAt`, requires
  `recordedAt`, and carries a sanitized opaque actor reference without display
  name or raw PII. At F4R V1 and `ActorSnapshot` leave target Spring integration
  and are isolated to media-compat RabbitMQ for the unchanged legacy Go worker
  until the Stage 4 photo-processing cutover.
- Use Lombok for safe boilerplate and MapStruct for explicit DTO/projection
  mapping. Forbid `@Data`, generated equality and unrestricted builders on JPA
  entities; do not move domain invariants into generated mappers.
- F4A owns auth telemetry and F4T owns task-board telemetry. F4G changes only
  the stateless gateway and then performs cross-deployable observability
  verification without changing the already closed services. F4I adds
  readiness manifests/profiles only and transfers no domain ownership to
  infrastructure products.

## 2026-07-13 Implemented F4K Platform Foundation

- Treat commit `52c0702` as the verified implementation of F4K shared
  foundation only. It does not prove Kafka business publication, an auth or
  task-board event store, replay parity or RabbitMQ retirement.
- Use the repository version catalog and convention plugins as the single
  version/annotation-processing boundary for Java 25, Spring Cloud 2025.1.2,
  Cloud Stream 5.0.2, Kafka 4.3.1, Lombok 1.18.46 and MapStruct 1.6.3.
- Keep `DomainEventEnvelopeV2` and canonical YAML schemas as the technical
  contract for owning-gate event-store/Kafka work. Event-type schema validation
  and sensitive-payload checks fail closed before publication.
- Preserve the gateway's event-free classpath and keep RabbitMQ available until
  the separately verified F4R cutover. An F4K broker/profile test does not
  transfer domain ownership or authorize dual publication.

## 2026-07-13 Flyway Schema Authority Supersession

- Supersede the earlier target decision that prohibited Flyway and permitted
  Hibernate schema mutation in development/tests. Liquibase remains forbidden;
  Flyway is the sole target migration, version-history and checksum authority.
- Hibernate never creates, updates or drops target schemas; dev, test and
  production use JPA `validate` after Flyway.
- Keep `baselineOnMigrate=false`. Existing non-empty databases require an
  explicit baseline at their proven current version.
- Execute `F4MA -> F4MT -> F4A`: auth uses cumulative V2/existing baseline 2,
  task-board uses cumulative V4/existing baseline 4, then auth event sourcing is
  V3 and task-board event sourcing is V5.
- Preserve `database/releases`, `rwms_schema_history`, `databasechangelog*` and
  backups as read-only historical evidence; do not rewrite old gate history.
- Use cumulative versioned `V2__auth_schema.sql`, not `B2`, for F4MA clean
  installs. Flyway 12.4.0 runtime evidence showed that applied `B` baseline
  drift was not rejected by `validate`; the versioned migration is required for
  checksum authority. Existing auth databases remain explicit baseline 2.
- Apply the same evidence preventively to F4MT: use cumulative versioned
  `V4__task_board_schema.sql`, not B4, while existing task-board databases remain
  explicit baseline version 4. This fixes the contract but does not implement
  or close F4MT.

## 2026-07-13 F4MT Flyway Closure

- Implementation commit `01cb9c2` closes the governance-only F4MT state above:
  task-board now uses cumulative versioned V4 for clean databases and explicit
  baseline 4 for verified existing databases.
- Flyway 12.4.0 is the sole active task-board schema version/checksum authority;
  `baselineOnMigrate=false` and JPA `validate` remain mandatory in every target
  profile.
- Historical V0001-V0004 releases, their exact SHA-256 values, current rows,
  Rabbit outbox/inbox, `rwms_schema_history` and `databasechangelog*` remain
  read-only evidence.
- F4MT changes no API, domain or Rabbit delivery behavior and authorizes no
  event store or Kafka business publication. F4A is the next gate; task-board
  event sourcing remains F4T through Flyway V5.

## 2026-07-14 F4T Task-Board Stream Boundaries

- Use exactly seven task-board streams: `WORKER_CLASS`, `WORKER`,
  `WORKER_GROUP`, `WORK_QUEUE`, `QUEUE_USAGE_REFERENCE`, `BOARD_TASK` and
  `QUEUE_ENTRY`.
- Keep qualifications, group members and queue-class bindings inside their
  owning aggregate facts. Keep assignments, time events and interruptions as
  `QUEUE_ENTRY` child facts so their ordering follows the entry stream.
- Preserve public HTTP `expectedVersion`/409 and stable `externalTaskId`
  semantics. Event sourcing changes persistence authority, not the API command
  contract.
- Use the canonical task-board JSON schema as cross-service truth. Exclude
  human-readable worker/group snapshots, names, descriptions, comments, task
  text, request fingerprints and credential state from event payloads.
- Retain worker profile PII in the current service-local operational projection
  for F4T and expose only an opaque `profileRevision` in facts. Do not infer a
  new PII service, vault product or erasure workflow.
- Keep Rabbit and Kafka independently feature-gated during F4T. Rabbit removal
  remains exclusively F4R after drain and parity proof.

Implementation and exit verification for these decisions remain pending; this
entry does not close F4T.

## 2026-07-14 F4I Validation Transport And Runtime Preconditions

- Resolve the pinned Strimzi `1.1.0` Helm dependency exclusively through its
  official OCI registry, `oci://quay.io/strimzi-helm`. Do not replace an
  unavailable vendor archive with an unapproved mirror.
- Build the full F4I dependency catalog only in a temporary copy of the
  umbrella chart. Retry a failed dependency retrieval at most three times, and
  never commit generated third-party charts or `Chart.lock` as validation
  by-products.
- Keep the Camunda engine-only, single-binary filesystem-backed Loki test-schema
  and OpenTelemetry Collector values limited to full-catalog render validation.
  They do not approve a business backend, RWMS telemetry pipeline, data owner
  or production storage.
- In the full catalog, disable ClickHouse operator credential generation and
  ECK webhook certificate generation; make Alertmanager configuration an
  explicit external-secret reference. A later deployed topology must provide
  separately approved secret material and may not rely on render-time defaults.
- Suppress the ClickHouse vendor sample-file ConfigMap during render-only
  validation because it contains a literal default password. Treat quoted
  environment-variable expressions as external references, not inline
  credentials; do not exempt literal values in executable resources.
- Remove Camunda's literal starter users/roles from the contract-only render
  and disable ClickHouse CRD hook ConfigMaps that only transport vendor schema
  documents. Neither override approves production identity, CRD-installation
  ownership or any RWMS business use.
- Require Docker cgroup v2 before a live Kind smoke creates a cluster. The
  `.wslconfig` change and Docker Desktop/WSL restart remain explicit operator
  actions because they interrupt active local workloads.
- These transport and runtime safeguards do not close F4I or authorize W1.

## 2026-07-14 F4I Static Schema Validation

- Keep all full-catalog chart dependencies on their declared vendor sources;
  the validation-only schema source below is not a substitute chart registry or
  a production runtime dependency.
- Strict kubeconform validates only rendered Kubernetes built-in resources.
  Helm lint/template continues to parse all CRD and vendor custom-resource
  documents; generic kubeconform schemas must not be presented as validation of
  vendor-owned CRDs.
- While the workstation cannot read the default raw-GitHub schema endpoint,
  retrieve the public `yannh/kubernetes-json-schema` repository through
  jsDelivr at immutable revision `6575cbe6397e3c1cb0946a41a200c37377fafe29`.
  The cache is temporary, the process is capped at 90 seconds, and any revision
  change requires the same review as a validation-contract change.
- A successful full static catalog run does not substitute for each Compose
  profile's runtime health or the cgroup-v2 Kind smoke, and does not authorize
  W1.
- Do not automatically seed a failed official dependency download from a local
  Helm cache. A cache archive may corroborate a prior successful source digest,
  but it must not conceal a current networked preparation failure or replace the
  declared vendor source.

## 2026-07-14 W1 Warehouse Decisions

- Respect the user's service-only direction: F4I is superseded/deferred, not
  passed. Its removed deployment assets are not restored and do not enter W1.
- Use technical uppercase warehouse codes matching
  `^[A-Z0-9][A-Z0-9_-]{0,63}$`; codes are globally unique and never reused.
  `sortOrder` is nullable, non-negative and ordered `NULLS LAST` then code.
- W1 uses a transactional Kafka outbox without event sourcing or consumer
  inbox. MapStruct maps only entity reads and sanitized event payloads; commands
  call aggregate methods explicitly.
- Maintain only canonical UUIDs in W1 service calls. A tightly scoped
  dev-browser bridge may translate the two known UUIDs to unchanged mock-store
  slugs; it is not a production contract or data migration.

## 2026-07-14 Combined Go Media-Service Decision

- Supersede the planned separate Stage 3 `media-service` and stateless Stage 4
  `photo-processing-service` with one stateful Go `media-service`. It owns its
  PostgreSQL/Flyway media metadata, upload sessions, signed MinIO URLs, Kafka
  outbox/inbox and the in-process image/video transformations. No target
  `photo-processing-service` deployable or database exists.
- The panel never receives MinIO credentials or raw object keys. It obtains
  short-lived signed upload/download URLs from the service after local JWT and
  warehouse-access authorization, then transfers bytes directly to MinIO.
- Images have a canonical `ORIGINAL` plus `SMALL` (96px), `MEDIUM` (320px) and
  `LARGE` (1280px) WebP variants by default. EXIF orientation is applied before
  variant creation. Videos are original-only and sort after images.
- Manual rotation is an optimistic, idempotent command. It writes a new
  immutable MinIO generation from the immutable ingress object and changes the
  current generation only after processing succeeds; image generations produce
  all three WebP variants and video generations use FFmpeg.
- `wms-panel-old/photo-worker-go` remains read-only legacy evidence for the
  `bimg/libvips` pipeline. Its isolated Rabbit compatibility path remains only
  until retirement after the combined Stage 3–4 cutover is technically
  verified; the active pointer's user confirmation does not reconstruct that
  runtime evidence.

## 2026-07-16 Asset ownership decisions

- The approved asset boundary owns cabins, canonical statuses, physical
  equipment balances, holds, leases, write-offs and their service-local event
  records. It does not acquire reservations, repairs, inventory, logistics,
  topology, tenant/shipment or dossier ownership.
- Use the explicit approved legacy-status map only; reject unknown source
  values. Use immutable two-line movement ledger entries, no negative balances,
  and `total = stock + non-rented cabin + rented cabin + written-off + lost`.
- Keep general-comment and manual-note text local. Integration facts are
  sanitized and a DLT stores no payload text or PII.
- Media objects remain owned by `media-service`. Until its approved
  rental-item API/edge is available, the asset panel must fail closed rather
  than store objects, invent a media route, or use browser fallback.
- Warehouse verification is a private least-privilege registry call from the
  declaratively configured `asset-service` client and never transfers warehouse
  ownership or auth secrets to asset-service.

## 2026-07-16 Asset Stage 5 completion decisions

- Preserve the public/manual status allowlist: it must continue to reject
  `WRITTEN_OFF`. A separately fenced internal asset command may transition to
  `WRITTEN_OFF` only after it verifies the active operation-lease holder and
  fencing token. Public cabin mutations guarded by the same active-lease check
  must not silently bypass that command boundary.
- Allocation holds have asset-local `ACTIVE -> COMMITTED -> RELEASED` (or
  `EXPIRED` before commit) lifecycle semantics. Commit keeps availability
  reserved and emits a fact, but does not infer a Stage 8 shipment, transfer,
  reservation or balance movement.
- Classifier create/change is a `CLASSIFIER` event-stream family with sanitized
  `label` facts; classifier display/name text, manual notes and other forbidden
  payload fields stay out of integration facts. Deterministic replay validates
  both stream/outbox integrity and parity with a named shadow projection.
- Asset Kafka uses one multiplexed functional input for all owned
  aggregate-family topics and derives the expected family from the received
  topic. Its outbox publisher supplies byte-array aggregate keys, so the common
  binder must enforce `ByteArraySerializer`; marking an outbox row published
  remains conditional on synchronous broker acknowledgement.
