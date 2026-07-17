# 05 UI

## Legacy Furniture Catalog Link Setting (2026-07-10)

- The legacy accessory-item detail screen exposes `furnitureMaterial` as `Связь с каталогом`.
- Its selector lists active `MATERIAL` nodes beneath a furniture category and excludes materials already linked to another accessory item; the current item link remains selectable while editing.
- The target write-off navigation uses one collapsible `Списание` parent with `Склад` and `Доп. оборудование` children. The equipment child is aggregate read-only UI because no disposal-event contract exists yet.

Evidence:

- `wms-panel-old/src/main/java/dev/buhanzaz/wmspanel/view/accessoryitem/AccessoryItemDetailView.java`
- `wms-panel-old/src/main/resources/dev/buhanzaz/wmspanel/view/accessoryitem/accessory-item-detail-view.xml`
- `panel/src/components/app-sidebar.tsx`
- `panel/src/features/write-offs/equipment-write-offs-page.tsx`

## Global UI Findings

- UI framework: Jmix FlowUI / Vaadin.
- Main shell: `MainView` with drawer navigation, title, user menu, substitution, logout.
- Login: `LoginView` with Jmix login form.
- Menu source: `src/main/resources/dev/buhanzaz/wmspanel/menu.xml`.
- View package: `src/main/java/dev/buhanzaz/wmspanel/view`.
- XML descriptors: `src/main/resources/dev/buhanzaz/wmspanel/view`.
- No view-level `@ViewPolicy`, `@MenuPolicy`, `@RolesAllowed`, or equivalent permissions were found in the view package/XML/menu. Effective UI access is `UNKNOWN` beyond global roles and service checks.

## Navigation Groups

Main menu groups:

- Application/users.
- Warehouse operations.
- Warehouse settings.
- Warehouse access settings.
- Rental dictionary settings.
- Rental attributes settings.
- Stock settings.
- Estimate catalog settings.
- Queue settings.
- Reservation settings.

## Shell Screens

| View | Route | Purpose | Dependencies | React migration |
|---|---|---|---|---|
| `MainView` | root layout | Drawer, navigation, title, user menu, substitution, logout. | `Messages`, `UiComponents`, `CurrentUserSubstitution`. | App shell, sidebar, top bar, user menu. |
| `LoginView` | `/login` | Jmix login page. | Jmix login form. | `/login` route with new auth contract. |

## Warehouse Operations

| View | Route | Purpose | Fields/actions | Backend dependencies | React migration |
|---|---|---|---|---|---|
| `RentalItemListView` | `/rental-items` | Main numbered asset registry. | Warehouse/category/subcategory/type/status filters; grid; detail side panel; dynamic attributes; tags; history/photos; column settings; create/edit/remove. | `WarehouseAccessService`, `ReservationService`, `RentalItemService`, `RentalItemEventService`, `RepairEstimateService`, `ViewStateService`, `DataManager`, `RentalTypeDisplayFormatter`. | `RentalItemsPage` with filters, table/grid, detail drawer, history/photo modals. |
| `RentalItemDetailView` | `/rental-items/:id` | Simple rental item form. | number, warehouse, category, subcategory, type, status, comment. | Jmix entity CRUD. | Fold into edit drawer or standalone edit page. |
| `AccessoryStockBalanceListView` | `/accessory-stock-balances` | Accessory stock counters by warehouse/item. | Warehouse filter; columns category/subcategory/item/total/available/reserved/in-rent/broken/written-off/comment; transfer/assignment behavior in Java. | `RentalItemService`, `RentalItemEventService`, `ViewStateService`, `DataManager`. | Stock balance table plus transfer dialog. |
| `AccessoryStockBalanceDetailView` | `/accessory-stock-balances/:id` | Accessory stock balance form. | warehouse, accessory item, quantities, comment. | Jmix entity CRUD. | Detail drawer/page. |
| `WarehouseAiSearchView` | `/warehouse-ai-search` | Natural-language inventory search. | Search field, result grid, reservation actions, equipment rows. | `WarehouseAiQueryInterpreter`, `ReservationService`, `WarehouseAccessService`, `DataManager`. | AI search page with result table and reservation modal. |
| `ReservationSearchView` | `/reservation-search` | Availability search. | warehouse/category/subcategory/type/quantity; search; temporary reservation; open reservations. | `ReservationService`, `ReservationSearchSettingsService`, `ViewStateService`, `DataManager`. | Reservation search workflow with polling. |
| `ReservationListView` | `/reservations` | Reservation overview. | reservations table; search/open/delete expired/refresh. Rental manager role affects filtering/visibility. | `ReservationService`, `ReservationExpirationService`, `DataManager`. | Reservations table with manager-scoped mode. |
| `ReservationDetailView` | `/reservations/:id` | Reservation state machine. | header/client fields, lines, accessories; move to payment, confirm, release, add accessory, cancel, delete expired. | `ReservationService`, `ReservationExpirationService`, `DataManager`. | Reservation detail page with action bar and accessory dialog. |
| `StockItemListView` | `/stock-items` | Quantity stock item list. | warehouse, name, segment, total/reserved/available, unit, active. | `ReservationService` for reserved/available columns. | Stock item table. |
| `StockItemDetailView` | `/stock-items/:id` | Stock item form. | warehouse, name, segment, quantities, unit, comment, active. | Jmix entity CRUD. | Detail drawer/page. |

## Repair And Queue Workflows

| View | Route | Purpose | Fields/actions | Backend dependencies | React migration |
|---|---|---|---|---|---|
| `QueueWorkBoardView` | `/queue-work-board` | Operational kanban board. | warehouse selector; show SHADOW toggle; add queue/task; columns/cards; drag/drop; take/complete/pause/resume; photos; process dossier; route reorder; threshold notifications. | `QueueBoardService`, `RepairProcessService`, `RepairEstimateService`, `ViewStateService`. | Kanban board with DnD, card detail modal, upload/gallery, route editor. |
| `QueueOrderView` | `/queue-order` | Queue ordering per warehouse. | warehouse selector; draggable queue cards/arrows; save. | `QueueBoardService`, `DataManager`, `ViewStateService`. | Queue order editor. |
| `WorkQueueListView` | `/work-queues` | Queue configuration list. | warehouse/code/name/kind/sort/active/collapsed/hidden/sink/threshold/holding fields; bulk create/configure. | `QueueBoardService`, `DataManager`. | Queue settings table plus bulk dialog. |
| `WorkQueueDetailView` | `/work-queues/:id` | Queue configuration form. | queue fields and worker class binding behavior. | Jmix entity/service. | Queue edit drawer/page. |
| `RepairEstimateView` | `/repair-estimates` | Estimate list and rich editor. | warehouse filter; draft/completed tabs; editor with rental item, source/destination, dispatch, comments, photos, lines, catalog picker, save/complete, task plan dialog. | `RepairEstimateService`, `RepairEstimateTaskPlanService`, `RepairEstimateTaskPlanGenerationService`, `RepairCatalogService`, `RepairProcessService`, `WarehouseAccessService`, `ViewStateService`, `DataManager`. | Estimates page, estimate editor modal/page, catalog picker, gallery, task plan builder. |
| `AfterRepairView` | `/after-repair` | Post-repair acceptance/rework. | process grid; warehouse filter; estimate dialog, rework estimate dialog, photo gallery, accept dialog, send-to-rework dialog. | `RepairProcessService`, `RepairReworkService`, `QueueBoardService`, `RepairEstimateService`, `WarehouseAccessService`, `ViewStateService`. | After-repair review queue with accept/rework modals. |
| `KpiAndReworkView` | `/kpi-and-rework` | KPI/rework history. | worker class selector, worker group selector, history grid. | `KpiAndReworkService`, `RepairEstimateService`. | KPI dashboard/report. |

## Repair Catalog Settings

| View | Route | Purpose | Fields/actions | React migration |
|---|---|---|---|---|
| `RepairEstimateCatalogCanvasView` | `/repair-estimate-catalog-canvas` | Visual repair catalog constructor. | category grid, canvas nodes/links, add/edit/delete category, add block, delete link, save. | Graph/canvas editor, likely with graph library. |
| `RepairEstimateWorkCatalogView` | `/repair-estimate-catalog-works` | Work catalog section editor. | Programmatic base view. | Catalog section table/form. |
| `RepairEstimateMaterialCatalogView` | `/repair-estimate-catalog-materials` | Material catalog section editor. | Programmatic base view. | Catalog section table/form. |
| `RepairEstimateFurnitureCatalogView` | `/repair-estimate-catalog-furniture` | Furniture/material catalog section editor. | Programmatic base view. | Catalog section table/form. |
| `RepairEstimateCatalogNodeListView` | `/repair-estimate-catalog-nodes` | Catalog node CRUD. | name, code, nodeType, parent, workQueue, includeInEstimate, commonItem, active, sort, main menu, pricing, canvas, comment. | Node admin table. |
| `RepairEstimateCatalogNodeDetailView` | `/repair-estimate-catalog-nodes/:id` | Catalog node form. | Same fields as list/detail. | Node edit page. |
| `RepairEstimateCatalogLinkListView` | `/repair-estimate-catalog-links` | Catalog link CRUD. | sourceNode, targetNode, linkType, active, sortOrder, comment. | Link admin table. |
| `RepairEstimateCatalogLinkDetailView` | `/repair-estimate-catalog-links/:id` | Catalog link form. | Same fields. | Link edit page. |

## Warehouse Settings And Dictionaries

| View group | Routes | Purpose | React migration |
|---|---|---|---|
| `WarehouseListView`, `WarehouseDetailView` | `/warehouses`, `/warehouses/:id` | Warehouse CRUD: name, code, city, address, timeZone, active, sort, comment. | Admin warehouse pages. |
| `UserWarehouseAccessListView`, `UserWarehouseAccessDetailView` | `/warehouse-accesses`, `/warehouse-accesses/:id` | User to warehouse access CRUD. | Access management page. |
| `RentalCategoryListView`, `RentalCategoryDetailView` | `/rental-categories`, `/rental-categories/:id` | Rental category CRUD. | Dictionary page. |
| `RentalSubcategoryListView`, `RentalSubcategoryDetailView` | `/rental-subcategories`, `/rental-subcategories/:id` | Rental subcategory CRUD. | Dictionary page with parent category. |
| `RentalTypeListView`, `RentalTypeDetailView` | `/rental-types`, `/rental-types/:id` | Rental type CRUD. | Dictionary page with parent subcategory. |
| `RentalItemConditionListView`, `RentalItemConditionDetailView` | `/rental-item-conditions`, `/rental-item-conditions/:id` | Condition dictionary. | Dictionary page. |
| `RentalTagListView`, `RentalTagDetailView` | `/rental-tags`, `/rental-tags/:id` | Item tag dictionary. | Dictionary page. |
| `RentalAttributeDefinitionListView`, `RentalAttributeDefinitionDetailView` | `/rental-attribute-definitions`, `/rental-attribute-definitions/:id` | Dynamic attribute definitions. | Attribute admin. |
| `RentalAttributeOptionListView`, `RentalAttributeOptionDetailView` | `/rental-attribute-options`, `/rental-attribute-options/:id` | Attribute options. | Attribute option admin. |
| `RentalClassifierAttributeListView`, `RentalClassifierAttributeDetailView` | `/rental-classifier-attributes`, `/rental-classifier-attributes/:id` | Attribute availability by category/subcategory/type. | Attribute assignment UI. |
| `AccessoryCategoryListView`, `AccessoryCategoryDetailView` | `/accessory-categories`, `/accessory-categories/:id` | Accessory category CRUD. | Accessory dictionary. |
| `AccessorySubcategoryListView`, `AccessorySubcategoryDetailView` | `/accessory-subcategories`, `/accessory-subcategories/:id` | Accessory subcategory CRUD. | Accessory dictionary. |
| `AccessoryItemListView`, `AccessoryItemDetailView` | `/accessory-items`, `/accessory-items/:id` | Accessory item CRUD with optional furniture material link. | Accessory catalog. |
| `ReservationSearchSettingsView` | `/reservation-search-settings` | Search refresh seconds setting. | Small settings form. |

## Users And Workers

| View group | Routes | Purpose | React migration |
|---|---|---|---|
| `UserListView`, `UserDetailView` | `/users`, `/users/:id` | User CRUD with password confirmation, global role, active, email/timezone; Jmix role assignments action. | User admin with replacement for Jmix role assignments. |
| `UserGridColumnSettingsListView`, `UserGridColumnSettingsDetailView` | `/user-grid-column-settings`, `/user-grid-column-settings/:id` | User column preferences admin. | Internal preferences admin or hidden admin route. |
| `WorkerClassListView`, `WorkerClassDetailView` | `/worker-classes`, `/worker-classes/:id` | Worker class dictionary. | Workforce settings. |
| `WorkerListView`, `WorkerDetailView` | `/workers`, `/workers/:id` | Worker CRUD with name parts, display name, app login/password, warehouse, active. | Worker admin. |
| `WorkerClassAssignmentListView`, `WorkerClassAssignmentDetailView` | `/worker-class-assignments`, `/worker-class-assignments/:id` | Worker to class assignment. | Assignment UI. |
| `WorkerGroupListView`, `WorkerGroupDetailView` | `/worker-groups`, `/worker-groups/:id` | Worker group/crew CRUD and membership. | Group settings. |

## UI Migration Order

1. Shell/login after backend auth contract is defined.
2. Core dictionaries and warehouse/access/user settings.
3. Rental item registry.
4. Reservations.
5. Queue board.
6. Repair estimates.
7. After-repair/rework.
8. Repair catalog canvas.
9. AI search and advanced admin pages.

## Target Mobile Navigation

- Target-only React behavior: the mobile sidebar and page background share the viewport top edge. Opening or closing the sidebar must not animate the main page's top padding or move its background.
- Target-only React behavior: a short coarse-pointer landscape viewport keeps the mobile sidebar mode, its persistent toggle, and a vertically scrollable central navigation area.
- Target-only React behavior: all mobile viewports reserve a static `4px` gap beneath the persistent sidebar toggle before the page's first content block.
- Supersede the rental-registry part of the preceding rule: the mobile registry title row sits beside the fixed toggle with a `12px` horizontal gap, while the remaining content uses the normal page padding below it.

## Target Mobile Navigation Refinement

- Supersede the `12px` rental-heading gap: the title row uses a `2.5rem` offset from the viewport, yielding a `4px` gap after the fixed `40px` toggle at its `12px` gutter.
- The desktop sidebar rail is hidden inside the mobile Sheet, including landscape mobile classification, so no edge control overlaps the expanded panel.

## Target Mobile Page Title Alignment

- The initial heading of every routed page begins at `x=56px` on mobile: the same compact `4px` clearance after the persistent toggle that the rental registry uses.
- The shared sidebar-inset selector applies the alignment to first page headings. Titles whose containers already carry `data-mobile-page-heading` do not receive a second offset.
- For empty routes and first-card headings, compensate for the card's `16px` padding and `1px` border so their visible text also starts at `x=56px`.

## Target Mobile Estimates Settings Surface

- The mobile estimates/repairs settings sections are full-width, unframed page bands: no rounded outer container, border, or separate card background.
- Their inner controls retain the existing spacing. The initial title continues to align beside the persistent menu toggle at `x=56px`.

## Target Estimate And Repair Workspaces (2026-07-10)

- `/estimates` is the operational estimate list. It retains the `Требуют доработки` and `Завершённые` tabs plus `Создать смету`. Desktop columns and mobile-card fields use `Номер бытовки`, `От кого`, `Автор`, `Статус`, `Создана`.
- `/repairs` is the target repair list. It has no estimate-status tabs and exposes the right-aligned `Создать задание` action. Desktop columns and mobile-card fields use `Номер бытовки`, `Причина`, `Автор`, `Статус`, `Создана`.
- Estimate and direct-repair creation reuse the same responsive photo/information/lines/catalog workspace. The contextual field is `От кого` for an estimate and `Причина` for a direct repair. The lines card remains visibly titled `Смета`; the direct-repair primary action is `Завершить`.
- A queued repair opens a cabin-root detail containing snapshot subtasks. The UI does not expose queue/route or a group-level subtask comment. Each work and material row displays its own `lineComment` opposite that row; an empty comment is shown as `—`.
- When there are multiple subtasks, their order can be changed and explicitly saved. A single subtask has no reorder or save-order controls. Hidden routing/group metadata remains preserved for compatibility.
- `/task-board` remains a separate routed section and is not the direct-repair list.

Evidence:

- `panel/src/features/repair-estimates/repair-estimates-page.tsx`
- `panel/src/features/repair-estimates/repair-work-information-fields.tsx`
- `panel/src/features/repair-estimates/repair-estimate-workspace-layout.tsx`
- `panel/src/features/repairs/repairs-page.tsx`
- `panel/src/features/repair-tasks/repair-task-editor-workspace.tsx`
- `panel/src/features/repair-tasks/repair-subtasks-editor.tsx`

## Target Repair Detail And Completed Estimate Amendment (2026-07-10)

- A non-draft repair detail uses the shared responsive detail workspace. Desktop places photos top-left, static information top-right, and `Подзадания` across both columns below. Mobile order is `Фото` -> `Информация` -> `Подзадания`, with no horizontal overflow.
- Task information is static and includes cabin, reason, arrival date, author, status, and common comment. Estimate-derived tasks expose `Перейти к смете`; direct tasks do not show a fake estimate link.
- Subtasks render in persisted execution order. Movement stages have distinct titles/descriptions and no fake work/material rows; repair stages show work/material comments opposite their source lines.
- Pre-start queued tasks show reorder arrows and an explicit save-order action. Started or terminal tasks are read-only and show no reorder controls.
- A completed estimate opens as a static photo/information/line snapshot. Cabin and monetary values are plain output, not disabled form controls. If amendment is allowed, `Дополнить смету` opens the existing estimate editor with the cabin still static and reuses the manual task-plan dialog.
- Estimate/task navigation uses `/estimates?estimateId=...` and `/repairs?repairId=...` and survives reload/back-forward.

Evidence:

- `panel/src/features/repair-estimates/repair-estimate-completed-workspace.tsx`
- `panel/src/features/repair-estimates/repair-estimate-lines-snapshot.tsx`
- `panel/src/features/repair-estimates/repair-estimate-workspace-layout.tsx`
- `panel/src/features/repair-tasks/repair-task-detail-workspace.tsx`
- `panel/src/features/repair-tasks/repair-subtasks-editor.tsx`
- `panel/src/features/repairs/repairs-page.tsx`

## Target Operational Task Board (2026-07-10)

- `/task-board` is a warehouse-scoped horizontal Kanban projection. It reuses the globally selected warehouse and does not render a second warehouse selector.
- The toolbar contains search, `Показывать будущие`, REAL/SHADOW counts, refresh, and `Создать задание`; creation opens the existing `/repairs?create=1` editor.
- Tablet and desktop use fixed-width, freely horizontally scrollable columns with independent vertical card scrolling. Mandatory scroll snapping is intentionally absent so returning the scrollbar to the left keeps the first queue anchored at the viewport start.
- Phone viewports, including the shared short coarse-pointer landscape classification, stack full-width queue columns vertically and remove board-level horizontal overflow. Mobile task cards have a stable `320px` minimum height; a populated queue grows to at most three complete cards and then scrolls its card list internally. Empty queues remain compact instead of filling the viewport height.
- A card represents one repair subtask/queue entry and shows cabin, reason, concise stage title, REAL/future state, execution status, route position, assignee, elapsed time, drag handle, details, and pause/resume actions. Details open `/repairs?repairId=...`, where photos, static information, and work/material line comments are already rendered.
- Search filters card content but disables DnD and queue-level take/complete so hidden cards cannot change queue semantics. SHADOW filtering is presentation-only.
- DnD uses the installed classic dnd-kit API with separate sortable contexts, `DragOverlay`, pointer/touch/keyboard sensors, exact before/after insertion, empty-column drops, Russian announcements, and native two-axis scrolling outside the drag handle.
- At `390x844`, document width and the board viewport remain equal with no horizontal overflow. At tablet and desktop widths only the queue viewport owns horizontal overflow.

## Target Dashboard Shell And Shared UI (2026-07-10)

- The current panel shell follows the official shadcn New York v4 `dashboard-01` composition while retaining RWMS routes and warehouse context.
- `SidebarProvider` defines an `18rem` sidebar and `3rem` header. `AppSidebar variant="inset"` remains icon-collapsible on desktop; `SidebarInset` keeps an eight-pixel desktop inset on every edge and does not use `h-svh`.
- `SiteHeader` contains the standard sidebar trigger, vertical separator, and route-aware Russian title. The right side has no placeholder user, GitHub action, or invented controls.
- Mobile uses the shadcn modal Sheet. The old fixed blue toggle, morph animation, title-margin selectors, and page-local `pl-10`/`ml-10` compensations are obsolete.
- The sidebar preserves the selected-warehouse combobox, domain groups, active route, settings submenu, and route-close behavior. Settings child navigation does not collapse its own submenu.
- Existing `Button`, `Input`, `Textarea`, `Select`, `InputGroup`, `Combobox`, `Field`, `Label`, `Card`, `Dialog`, `Popover`, `Tabs`, `Table`, and `Badge` primitives use New York v4 sizing, focus rings, borders, shadows, spacing, and typography. Hugeicons and semantic application colors remain unchanged.
- Compatibility extensions remain deliberate: small cards retain `size="sm"`; popover content remains a flex column with a default gap; narrow catalog tiles use `min-w-0` and breakable titles instead of weakening global card spacing.

## Target Header, Full Collapse, And Workspace Back Refinement (2026-07-10)

- Supersede the icon-collapsible desktop statement above. `AppSidebar variant="inset"` uses shadcn `collapsible="offcanvas"`; closing it removes the entire sidebar and does not render `SidebarRail`.
- The `WMS Panel / <warehouse code>` brand and the main header share the same `3rem` vertical frame. The brand is a home link to `/`, while the separate header `SidebarTrigger` uses `PanelLeftIcon` without dotted affordances.
- `SiteHeader` uses the official shadcn breadcrumb primitive. Nested chains are `Склад > Бытовка`, `Настройки > <settings child>`, `Сметы > Смета/Новая смета`, and `Ремонты > Задание/Новое задание`; narrow mobile renders only the current item.
- Estimate and repair workspaces expose a top-left `Назад` action. Marked list, cross-link, and task-board entries return through browser history; direct deep links fall back to the appropriate list. Footer `Отмена` remains the editor cancellation action.
- Estimate/repair creation is addressable through `?create=1`; detail selection remains addressable through `estimateId` and `repairId`.

Target evidence:

- `panel/src/components/site-header.tsx`
- `panel/src/components/app-sidebar.tsx`
- `panel/src/components/ui/sidebar.tsx`
- `panel/src/components/ui/breadcrumb.tsx`
- `panel/src/hooks/use-workspace-back.ts`
- `panel/src/features/repair-estimates/repair-estimates-page.tsx`
- `panel/src/features/repairs/repairs-page.tsx`

Target evidence:

- `panel/src/App.tsx`
- `panel/src/components/site-header.tsx`
- `panel/src/components/app-sidebar.tsx`
- `panel/src/components/ui/`
- `panel/src/index.css`

Target evidence:

- `panel/src/features/task-board/task-board-page.tsx`
- `panel/src/features/task-board/task-board-column.tsx`
- `panel/src/features/task-board/task-board-card.tsx`
- `panel/src/features/task-board/domain/task-board-domain.ts`
- `panel/src/App.tsx`

## Target Header Separator Refinement (2026-07-10)

- The route-aware `SiteHeader` uses a vertical shadcn `Separator` between the sidebar trigger and the breadcrumb/title.
- The separator is intentionally `h-3` (`12px`) tall with its standard one-pixel width, matching the requested compact visual treatment.

Evidence:

- `panel/src/components/site-header.tsx:99`
- Browser DOM/computed-style check on `http://127.0.0.1:8080/`: `height: 12px`, `width: 1px`.

## Target Header Separator Final Adjustment (2026-07-10)

- Supersedes the preceding `h-3` refinement: the 12px separator remained visually too close to the former 16px size at the active display scale.
- Keep the vertical `SiteHeader` separator at `h-2` (`8px`) with its standard one-pixel width.

Evidence:

- `panel/src/components/site-header.tsx:99`
- Browser DOM/computed-style check on `http://127.0.0.1:8080/warehouse`: `height: 8px`, `width: 1px`.

## Target Header Separator Centering (2026-07-10)

- Supersedes the preceding `h-2` adjustment. Keep the header separator at `h-3` (`12px`) and override the primitive's vertical `self-stretch` behavior with `data-vertical:self-center`.
- This centers the line within the 48px `SiteHeader`, leaving approximately 18px of free space above and below it.

Evidence:

- `panel/src/components/site-header.tsx:99`
- Browser DOM/computed-style check on `http://127.0.0.1:8080/warehouse`: `align-self: center`, `height: 12px`, `topGap: 17.5px`, `bottomGap: 18.5px`.

## Target Header Separator Double Height (2026-07-10)

- Supersedes the preceding centered `h-3` size. Keep the centered header separator at `h-6` (`24px`), exactly twice the prior 12px height.

Evidence:

- `panel/src/components/site-header.tsx:99`
- Browser DOM/computed-style check on `http://127.0.0.1:8080/warehouse`: `align-self: center`, `height: 24px`, `topGap: 11.5px`, `bottomGap: 12.5px`.

## Target Header Separator Guide Alignment (2026-07-10)

- Supersedes the preceding 24px size. Keep the centered header separator at `h-8` (`32px`), leaving eight CSS pixels of free space at each header edge.

Evidence:

- `panel/src/components/site-header.tsx`
- Browser DOM/computed-style check on `http://127.0.0.1:8080/equipment`: `align-self: center`, `height: 32px`, `topGap: 7.5px`, `bottomGap: 8.5px`.

## Target Sidebar Brand Surface (2026-07-10)

- The `WMS Panel / <warehouse code>` brand link fills the shared `3rem` sidebar-header height. Its hover and active background therefore reaches the header's top and bottom boundaries, while the link remains the normal home navigation action.

Evidence:

- `panel/src/components/app-sidebar.tsx`
- Browser geometry check on `http://127.0.0.1:8080/`: header and brand link both span `top: 8px`, `bottom: 56px`, `height: 48px`; the hovered brand surface has `height: 48px`.

## Target Rental Item Breadcrumb De-duplication (2026-07-10)

- A rental-item detail renders one route breadcrumb only: `Склад / <город склада бытовки> / <номер бытовки>` in `SiteHeader`.
- The header resolves the city from the rental item's own `warehouseId`, rather than relying solely on the currently selected warehouse. The former duplicate breadcrumb above the detail content is removed.

Evidence:

- `panel/src/components/site-header.tsx`
- `panel/src/features/rental-items/rental-item-detail-page.tsx`

## Warehouse Status Filter Colour Mapping (2026-07-12)

- Each selectable warehouse status uses the same semantic foreground/background
  token pair as that status uses in the registry table. For example, `Аренда`
  remains blue, `Свободна` remains green, and `Списана` remains dark.
- This is a presentation-only aid for scanning the filter; its values,
  multi-select behavior, and browser-adapter contract are unchanged.

Evidence:

- `panel/src/features/rental-items/rental-items-filters.tsx`
- `panel/src/features/rental-items/rental-item-status-badge.tsx`

## Dossier Comments And Passport Layout (2026-07-12)

- On desktop, the editable cabin-wide comment and the append-only history-note
  form share one two-column row; the comment register remains below both forms.
  On narrow screens the forms retain their natural one-column reading order.
- The dossier passport remains one vertical column of facts. Its desktop hero
  reserves additional photo height instead of introducing a second facts column
  or an internal passport scrollbar, so the number, status, actions, and all
  details can be read together before proceeding to the tabs.

Evidence:

- `panel/src/features/rental-items/rental-item-detail-page.tsx`
- Browser check on `http://127.0.0.1:8080/warehouse/msk-1`: breadcrumb items are `Склад`, `Москва`, `БЫТ-001`; separators are `/`, and the page has one shadcn breadcrumb.

## Target Sidebar Navigation Alignment (2026-07-10)

- Primary sidebar navigation rows are `40px` tall. Each icon is centred in a `32px` cell, matching the brand icon's size and horizontal span.
- With the sidebar's `16px` menu inset, the brand and navigation icon cells both span `x=24px` to `x=56px`; navigation labels begin at `x=64px`, aligned with the brand text.
- The bottom `Настройки` action uses the same row and icon-cell geometry. Nested settings entries retain their subordinate menu styling.

Evidence:

- `panel/src/components/app-sidebar.tsx`
- Browser geometry check on `http://127.0.0.1:8080/`: brand icon and a primary menu icon both span `left: 24px`, `right: 56px`, `height: 32px`; the primary row is `40px` tall and its label starts at `64px`.

## Target Equipment Header Count And Cabin Label (2026-07-10)

- `/equipment` renders the dynamic gray `N поз.` badge beside `Доп. оборудование` in the shared `SiteHeader`; the former duplicate page-local heading and count are removed.
- The header badge reads the selected warehouse's unfiltered equipment list through the same React Query cache key as the table, and invalidates when equipment or rental-item mock data changes.
- The equipment availability label is `В бытовках` in both desktop table and mobile item cards.

Evidence:

- `panel/src/components/site-header.tsx`
- `panel/src/features/equipment/equipment-item-count-badge.tsx`
- `panel/src/features/equipment/equipment-page.tsx`
- Playwright checks on `http://127.0.0.1:8080/equipment` at `1280x720` and `390x844`.

## Target Sidebar Warehouse Selector Alignment (2026-07-10)

- The sidebar no longer renders a separate visual `Склад` group label above the warehouse selector.
- The selector is vertically aligned with the first page heading: its 36px control and the heading share the same vertical centre on desktop.
- The combobox retains an accessible name, `Выбор склада`, and the existing selected-warehouse behavior.

Evidence:

- `panel/src/components/app-sidebar.tsx`
- Playwright geometry check on `http://127.0.0.1:8080/warehouse`: selector `top: 76px`, `height: 36px`, `center: 94px`; `Бытовки` heading `top: 80px`, `height: 28px`, `center: 94px`.

## Target Operational Estimate And Repair List Grids (2026-07-10)

- Desktop `/estimates` and `/repairs` lists use one compact `OperationsListGrid` presentation: a bordered grid with a continuous muted header, one row per record, hover feedback, and per-column sort controls.
- Estimates keep the product-defined columns `Номер бытовки`, `От кого`, `Автор`, `Статус`, `Создана`; repairs keep `Номер бытовки`, `Причина`, `Автор`, `Статус`, `Создана`.
- Header sorting cycles ascending, descending, then the source order. It is local presentation state and does not change any adapter or backend contract.
- Mobile keeps the existing summary cards rather than forcing the wide table into a phone viewport.

Evidence:

- `panel/src/components/operations-list-grid.tsx`
- `panel/src/features/repair-estimates/repair-estimates-page.tsx`
- `panel/src/features/repairs/repairs-page.tsx`
- Browser check on `http://127.0.0.1:8080/estimates`: gray grid header, five sort controls, two completed rows, and `Статус` reports `aria-sort="ascending"` after click.

## Target Empty Operational List Grids (2026-07-10)

- Supersede the estimate and repair list empty-state cards. When a desktop list has no records, render its `OperationsListGrid` header with zero body rows instead of a `Смет пока нет` or `Ремонтов пока нет` message card.
- On mobile, do not render placeholder cards when the list is empty.

Evidence:

- `panel/src/features/repair-estimates/repair-estimates-page.tsx`
- `panel/src/features/repairs/repairs-page.tsx`
- Browser checks on `/estimates` and `/repairs`: each empty list has five headers, zero body rows, and no former empty-state text.

## Target Full-Height Empty Operational Grids (2026-07-10)

- The desktop `OperationsListGrid` has a minimum height equal to the remaining list workspace. Empty and short lists therefore retain a full bordered grid surface down to the page bottom.
- For `/estimates` and `/repairs`, the grid maintains equal `24px` gaps from the `main` content edge on the left, right, and bottom.

Evidence:

- `panel/src/components/operations-list-grid.tsx`
- `panel/src/features/repair-estimates/repair-estimates-page.tsx`
- `panel/src/features/repairs/repairs-page.tsx`
- Browser geometry checks on `/estimates` and `/repairs`: `leftGap: 24px`, `rightGap: 24px`, `bottomGap: 24px`; empty grids have five headers and zero rows.

## Target Estimate And Repair Completion Notices (2026-07-10)

- Do not show inline success notices after saving, completing, or creating an estimate or repair.
- Saving still closes the editor and returns to the appropriate list; estimate status filtering remains unchanged.

Evidence:

- `panel/src/features/repair-estimates/repair-estimates-page.tsx`
- `panel/src/features/repairs/repairs-page.tsx`
- Browser checks on `/estimates` and `/repairs`: neither page's DOM contains `Смета завершена` or `Ремонт создан`.

## Target Settings Submenu Alignment (2026-07-10)

- The expanded `Настройки` submenu uses the same 40px row height, 32px icon cell, and 8px text gap as primary sidebar navigation rows.
- Remove the default sub-menu rail, indentation, and one-pixel horizontal translation; retain the existing active state, links, and expanded-state behavior.

Evidence:

- `panel/src/components/app-sidebar.tsx`
- Browser geometry check on `http://127.0.0.1:8080/settings/estimates-repairs`: primary and submenu links both have `left: 16px`, `height: 40px`, icon `left: 24px`, icon width `32px`, and label `left: 64px`.

## Target Task Board Empty Queues (2026-07-10)

- An empty expanded queue is a plain, unobstructed column rather than a repeated `Очередь свободна` Card. Its queue header and action buttons remain visible.
- The queue action that takes the first available subtask is labelled `Начать`; only the label changed, not the command or its eligibility rules.
- The queue section remains the dnd-kit droppable target when its rendered entry list is empty.

Evidence:

- `panel/src/features/task-board/task-board-column.tsx`
- Browser check on `http://127.0.0.1:8080/task-board`: empty queues have no `Очередь свободна` or `Перетащите сюда доступное подзадание.` text, while each visible queue exposes a disabled `Начать` button for its empty state.

## Target Task Board Toolbar And Inactive Stages (2026-07-10)

- The task-board toolbar order is search, `Создать задание`, the `Неактивные` toggle, then the existing `Текущих` and `Будущих` counters. The manual board-reload control is absent.
- `Неактивные` is a real pressable button. It is translucent when off, becomes darker on hover, and retains a muted active treatment when pressed. It toggles only the existing local SHADOW-stage visibility.
- Visible SHADOW cards use a muted gray, dashed, translucent presentation (including the drag preview). Their actions and drag-and-drop eligibility are unchanged.
- The horizontal board scroller reserves `16px` at its bottom, so its scrollbar does not overlap the first queue's border.

Evidence:

- `panel/src/features/task-board/task-board-page.tsx`
- `panel/src/features/task-board/task-board-card.tsx`
- Browser check on `http://127.0.0.1:8080/task-board`: one `Неактивные` button changes `aria-pressed` from `false` to `true` and back; no `Обновить доску` button remains; the initial queue-to-scroller bottom gap is `31px` with a `15px` scrollbar.

## Target Task Board Toolbar Alignment (2026-07-10)

- Supersede the custom translucent toolbar-button treatment. `Создать задание` uses the standard primary Button variant; `Неактивные` uses standard outline/default variants for its off/on states.
- `Текущих` and `Будущих` remain badges, but their group is pinned to the right edge of the non-scrolling toolbar. Horizontal scrolling moves only queue columns, not the counters.

## Target Task Board Transparent Inactive Toggle (2026-07-10)

- Supersede only the off-state background detail above: the unpressed `Неактивные` outline button has a transparent background while retaining its standard border and hover treatment. Its pressed state remains the standard primary/default Button treatment.
- This is visual state only; the existing local SHADOW visibility behavior and task actions do not change.
- Evidence: browser check on `http://127.0.0.1:8080/task-board` returned `rgba(0, 0, 0, 0)` with `aria-pressed="false"`, `oklch(0.5 0.134 242.749)` with `aria-pressed="true"`, and transparent background again after toggling off.

## Target Task Board Future Card Drag Appearance (2026-07-10)

- A visible future (`SHADOW`) task card uses the same visual treatment as its `DragOverlay` preview: dashed border, `bg-muted/70`, `opacity-65`, `shadow-lg`, and opacity transition. Do not apply a separate hover-opacity override to the in-column SHADOW card.
- The shared presentation class is defined in `panel/src/features/task-board/task-board-card.tsx`; it is visual only and does not change the card's DnD, queue, or task-action behavior.

Evidence:

- `panel/src/features/task-board/task-board-page.tsx`
- Browser check on `http://127.0.0.1:8080/task-board`: counters span `x=1060.67px` to `x=1248px` before and after the queue scroller moves from `scrollLeft=1` to `scrollLeft=665`.

## Target Estimate And Repair Editor Inputs, Scrolling, And Media (2026-07-10)

- Estimate-line comments begin at the same 36px height as adjacent fields and retain vertical resizing. The common comment is a fixed, non-resizable 128px field within the information panel.
- The application shell is constrained to the viewport so an estimate editor's line-list scroller is the only vertical scrolling region. Its `Итого` divider and total remain in the static footer of the estimate panel.
- `RepairEstimatePhotos` preserves the media adapter's small and full-size variants when it passes photos to the shared carousel. Pending IndexedDB upload previews retain their original `blob:` URL. The carousel must not rewrite `blob:` or `data:` URLs, and the estimate/repair media view permits the existing full-screen viewer.

Evidence:

- `panel/src/App.tsx`
- `panel/src/features/repair-estimates/repair-work-information-fields.tsx`
- `panel/src/features/repair-estimates/repair-estimate-lines-editor.tsx`
- `panel/src/features/repair-estimates/repair-estimate-editor-workspace.tsx`
- `panel/src/features/repair-estimates/repair-estimate-completed-workspace.tsx`
- `panel/src/features/repair-tasks/repair-task-editor-workspace.tsx`
- `panel/src/features/repair-estimates/repair-estimate-photos.tsx`
- `panel/src/components/media/photo-carousel.tsx`
- Browser checks on `/repairs?create=1` and `/estimates?create=1`: no document scrolling at a 720px viewport; common/line comment heights are 128px/36px; the line scroller retains static `Итого`; uploaded preview has no broken image; full-screen viewer opens.

## Target Rental Registry Heading Removal (2026-07-10)

- The `/warehouse` page does not render the former `Бытовки` heading or `N шт.` total badge. On desktop, the action toolbar is the first content block.
- Mobile retains the existing collapsible parameter region through an icon-only, accessibly labelled control; it shows neither the removed heading nor total count.

Evidence:

- `panel/src/features/rental-items/rental-items-page.tsx`

## Target Warehouse Desktop View Switcher Restoration (2026-07-11)

- `/warehouse` exposes the accessible list/grid ToggleGroup on desktop as well as mobile. On desktop it is a sibling of `Столбцы` in the shared toolbar; it is not the mobile-only control reused outside its breakpoint.
- Both responsive ToggleGroups share the existing warehouse-scoped `viewMode` state and persistence. Each exposes the `Вид реестра` radiogroup with `Список` and `Сетка` choices.
- The restoration is presentation-only: it does not alter grid widths, table layout, or the selected grid format behavior.

Evidence:

- `panel/src/features/rental-items/rental-items-page.tsx`
- Browser check at `http://127.0.0.1:8080/warehouse`: at `1280x720`, `Столбцы` is followed by the visible 32px list/grid radiogroup; selecting `Сетка` renders cards and survives reload. At `390x844`, the mobile radiogroup remains visible and switches back to the table without browser-console errors.

## 2026-07-11 Shared operational grids

- `OperationsListGrid` supports optional expanded detail rows. Use this capability for desktop operational registers that need drill-down while retaining the project's sorting, sticky header, and table semantics.
- Mobile `/warehouse` action controls are independent outlined icon buttons and a standard ToggleGroup, not one merged control shell.
- Browser checks on `http://127.0.0.1:8080/warehouse`: desktop has no `h2` and no `120 шт.`, with the create action at `top: 80px`; at `390x844`, the expanded parameter control has no visible text and neither removed string appears in the DOM.

## Target Unified Operational Grid And Registry Controls (2026-07-10)

- The pre-existing supplemental-equipment grid is the visual standard for `/equipment`, `/estimates`, and `/repairs`: muted sticky header, light header shadow, 12px horizontal cell inset, and 12px vertical cell inset.
- `/warehouse` toolbar buttons and search input use the default shadcn `h-9` (36px) control height; do not restore page-local `h-10` overrides.

Evidence:

- `panel/src/components/operations-list-grid.tsx`
- `panel/src/features/equipment/equipment-page.tsx`
- `panel/src/features/rental-items/rental-items-page.tsx`
- Browser checks: warehouse create/columns/search controls each measure `36px`; equipment, estimates, and repairs headers use the same muted background, light shadow, and `12px` left cell padding.

## Target Warehouse Grid Standard (2026-07-10)

- Supersede the supplemental-equipment visual reference above: the `/warehouse` rental registry is the canonical target presentation for tabular grids.
- `/warehouse`, `/equipment`, `/estimates`, and `/repairs` share `GridSortButton` and the same Lucide `ArrowUp`, `ArrowDown`, and neutral `ArrowUpDown` icons. Sorting cycles ascending, descending, then source order.
- Header cells have a rendered height of `41px`, `12px` padding, and `500 12px/16px` Geist text. Data rows render at `39px` with `14px/20px` Geist text. Sort controls retain their natural content width; do not stretch them across a column or change the existing column widths.
- The supplemental-equipment grid now performs local presentation sorting for every visible desktop column. This does not establish a backend query, persistence, or domain sorting contract.

Evidence:

- `panel/src/components/grid-sort-button.tsx`
- `panel/src/components/operations-list-grid.tsx`
- `panel/src/features/rental-items/rental-items-table-view.tsx`
- `panel/src/features/equipment/equipment-page.tsx`
- Browser checks on `/warehouse`, `/equipment`, `/estimates`, and `/repairs`: headers are `41px`; equipment rows are `39px`; all table sort controls expose the matching Lucide icon classes. Clicking `/equipment` `Всего` changes `aria-sort` from `ascending` to `descending` and reorders the local rows.

## Target Unified Grid Row Height (2026-07-10)

- Supersede the earlier `39px` row-height decision: the visible estimate-grid cell is the reference for row height. All primary desktop grids (`/warehouse`, `/equipment`, `/estimates`, and `/repairs`) render data rows at exactly `49px`.
- Preserve the estimate cell inset of `8px 12px`, existing column widths, header geometry, and sort controls. The shared constants encode the exact table/flex row minimum instead of relying on a particular cell's badge or button content to determine the row height.

Evidence:

- `panel/src/components/grid-sort-button.tsx`
- `panel/src/components/operations-list-grid.tsx`
- `panel/src/features/rental-items/rental-items-table-view.tsx`
- `panel/src/features/equipment/equipment-page.tsx`
- Browser check on `/warehouse`: first `tr` and `td` are `49px` high with `8px 12px` padding. Browser check on `/equipment`: first equipment row is `49px` high.

## Target Acceptance And Write-Off UI (2026-07-10)

- `/acceptance` has no create action. Desktop uses `OperationsListGrid`; mobile uses cards. Rows contain cabin, origin, reason, author, start, completion, and acceptance status, and open with `?acceptanceId=<repairTaskId>`.
- `/write-offs` appears immediately below Inventory in the sidebar. Its grid/card projection opens a read-only dossier through `?writeOffId=<repairTaskId>`.
- Both detail routes use the shared repair workspace, nested `SiteHeader` breadcrumb with cabin number, and history-aware `Назад` behavior.
- Dossiers show before-repair media, exactly one source link, ordered completed stages, line comments, worker group/assignments, timing, and group-result galleries through the existing `PhotoCarousel` with persistent controls.
- Acceptance mode exposes group and worker checkboxes, an indeterminate group state, `Переделать`, `Принять`, and `Списать`. Write-off mode removes selection and decisions.
- Remaining-time badges use strict boundaries: `>60` green, `>40` yellow, `>20` red, and `<=20`/overdue black with white text; missing or zero normative time is neutral `Нет норматива`.
- `RepairWorkDetailWorkspaceLayout.mobileContentFlow` is optional and enabled only for the acceptance/write-off dossier, preserving existing desktop/editor behavior while allowing natural document flow at narrow viewports.

Target evidence:

- `panel/src/features/acceptance/`
- `panel/src/features/write-offs/`
- `panel/src/components/site-header.tsx`
- `panel/src/components/app-sidebar.tsx`
- `panel/src/features/repair-estimates/repair-estimate-workspace-layout.tsx`

### Early write-off action correction

- Editable estimate and direct-repair workspaces place `Списать` first in the common lower-right action group, followed by `Отмена`, draft save, and completion actions.
- The shared `RepairTaskWriteOffDialog` uses the existing shadcn Dialog/Field/Textarea/Button primitives, requires a nonblank reason, and disables closing/actions during persistence.
- The action is disabled until a cabin is selected and while another workspace mutation is pending. Completed-estimate amendment presentation is unchanged.
- Success opens `/write-offs?writeOffId=<repairTaskId>` with the workspace navigation marker. An unsaved estimate-origin record shows `Из сметы` but no `Перейти к смете` control.

Evidence:

- `panel/src/features/repair-estimates/repair-estimate-editor-workspace.tsx`
- `panel/src/features/repair-tasks/repair-task-editor-workspace.tsx`
- `panel/src/features/repair-tasks/repair-task-write-off-dialog.tsx`
- `panel/src/features/acceptance/repair-acceptance-dossier.tsx`

## Target Sidebar Submenu Hierarchy (2026-07-10)

- `Списание` and `Настройки` remain 40px primary navigation controls. Their closed indicator points right; their expanded indicator points down.
- Expanded entries use the shadcn `SidebarMenuSub` hierarchy: a subtle vertical guide, an inset relative to the primary row, 32px child rows, and compact 16px icons. Active state remains supplied by the existing sidebar primitive.
- This visually differentiates a subordinate destination from a peer navigation section without changing routes or the collapse/navigation behavior.

Evidence:

- `panel/src/components/app-sidebar.tsx`
- Browser inspection at `http://127.0.0.1:8080/` at `1280x720`: both expanded menus render a 228px inset sublist with a guide rail and two 32px links; browser console reports no application errors.

## Target Mobile Warehouse Selection And Registry Controls (2026-07-11)

- The sidebar warehouse selector is a non-editable Select trigger, not a searchable Combobox. Opening the mobile navigation or the selector must not focus an input, select warehouse text, or invoke the device keyboard.
- The `/warehouse` mobile header contains the sole collapse/expand trigger for the registry toolbar. The compact filter, column, list, and grid controls remain below it only while expanded.
- Filter and column controls are icon-only on mobile but retain explicit accessible names. The list/grid choices are one labelled ToggleGroup.

Evidence:

- `panel/src/components/app-sidebar.tsx`
- `panel/src/components/site-header.tsx`
- `panel/src/features/rental-items/rental-items-page.tsx`

## Target Header And Logistics Grid Alignment (2026-07-11)

- On desktop, the `SiteHeader` horizontal padding and routed content padding share the same `24px` inset from the `SidebarInset` edge. The header trigger, list toolbar, and logistics desktop grid therefore start at one x-coordinate.
- The header sidebar trigger retains its standard 32px hit target, but its icon is start-aligned within that target. This makes the visible icon meet the grid edge without using a transform, absolute offset, or changing any table width.
- The same rule applies to narrow layouts at their shared 16px inset. Logistics uses its existing card presentation below the desktop breakpoint.

Evidence:

- `panel/src/App.tsx`
- `panel/src/components/site-header.tsx`
- `panel/src/features/logistics/logistics-returns-page.tsx`
- `panel/src/features/logistics/logistics-shipments-page.tsx`
- Browser geometry at `1280x720`: `SidebarInset` left `288px`, header trigger/grid/toolbar left `312px`, trigger `32×32px`, and trigger SVG left `312px`. At `390x844`, trigger SVG and toolbar both start at `16px`.
## Target Warehouse Inventory UI (2026-07-11)

- `Инвентаризация` is a collapsible sidebar parent. It presents start/history
  without an active session and continue/finish/history for the selected
  warehouse while a session is active.
- Routes are `/inventory`, `/inventory/:inventoryId`,
  `/inventory/:inventoryId/finish`, `/inventory/history`, and
  `/inventory/history/:inventoryId`; each uses explicit breadcrumbs and the
  existing workspace Back fallback.
- The working route combines reconciliation filters, the shared full-height
  desktop `OperationsListGrid`, mobile finding cards, add-cabin flow, and the
  repair photo/catalog/line presentation without invoking estimate mutations.
- Finish is a review screen rather than an immediate command. It displays live
  reconciliation risks and work/material totals, requires acknowledgment for
  missing/conflicting cabins, then offers publication only when staged work exists.
- The start dialog displays the selected warehouse, current author, and date as
  non-interactive snapshot text. These values are labelled for assistive technology
  but are not inputs, are not tabbable, and cannot be selected or copied.
- Completed history renders frozen statistics and per-finding publication state,
  including partial/failed/blocked outcomes and retry controls allowed by MANAGE.

Target evidence:

- `panel/src/features/inventory/`
- `panel/src/components/app-sidebar.tsx`
- `panel/src/components/site-header.tsx`
- `panel/src/App.tsx`

## Inventory Repair Workflow UI (2026-07-11)

- Saving an editable inventory finding that contains repair rows opens the shared
  repair-work completion dialog rather than silently deriving tasks.
- The dialog retains the saved manual/automatic mode, movement decision,
  routes, order, comments, and per-stage photo requirement when the finding is
  reopened. Completed-history inspection renders this frozen workflow as a
  read-only plan next to its frozen estimate lines.

Target evidence:

- `panel/src/features/inventory/inventory-pages.tsx`
- `panel/src/features/inventory/inventory-inspection-workspace.tsx`
- `panel/src/features/repair-estimates/repair-work-completion-dialog.tsx`

## Return cabin selection parity (2026-07-11)

- The return and shipment dialogs share the same visual language for repeatable cabin selection: numbered cards, Comboboxes, removable additional rows, and a gated `Добавить ещё бытовку` action.
- Return keeps semantic groups inside the menu — `Бытовки клиента` and `Другие арендованные` — instead of exposing a separate checkbox list and manual-entry surface.

## Factual return contents (2026-07-11)

- `Добавить возврат из аренды` now has the shipment-create field hierarchy:
  party, date, movement driver, repeatable `Бытовка N` cards, and a content
  editor inside each selected cabin card.
- The return editor labels its quantities `Фактическое наполнение`; it must not
  render shipment-only preparation tasks. The same shadcn Dialog, Field,
  Select, Combobox, Card, Button, and Checkbox composition is used in both
  flows, with return-specific factual copy.
- In a mobile dialog, content remains vertically scrollable inside the Dialog;
  the viewport must not acquire horizontal overflow.

## Return/shipment content-editor spacing parity (2026-07-11)

- A selected return cabin uses the same `CardContent` vertical `gap-4` layout
  as a selected shipment cabin. The `Фактическое наполнение` heading and its
  add-position action therefore start on a distinct row after the cabin-number
  field, instead of visually attaching to the combobox.

## Rental Item Creation Dialog Refinement (2026-07-11)

- The warehouse create dialog title is `Создание новой бытовки`; it does not
  expose internal legacy-category or dictionary-ID wording.
- Remove the non-actionable dimensions hint `Доступные варианты зависят от
  выбранного типа.` The disabled dimensions control itself communicates that a
  type must be selected first.
- On tablet/desktop, `Добавить характеристики`, `Загрузить фото`, and the
  combined `Отмена`/`Создать бытовку` action group share one 260px right-aligned
  action column. On mobile, all three use the available dialog width.
- `Добавить характеристики` is a child dialog of creation. Its backdrop, Escape,
  and close control return to the creation form without discarding that form.

Evidence:

- `panel/src/features/rental-items/rental-item-create-dialog.tsx`

## Equipment Register Quantity Alignment (2026-07-11)

- In the primary desktop equipment register, every quantity begins at the left
  edge of its own column, directly below the corresponding column heading. This
  makes the relationship between a value and its status explicit at a glance.
- This applies to the primary equipment rows only. Expanded usage details retain
  their separate layout.

Evidence:

- `panel/src/features/equipment/equipment-page.tsx`

## Rental Item Dossier (2026-07-12)

- `/warehouse/:rentalItemId` is the target cabin dossier. Its shadcn Tabs are
  URL-backed by `?tab=...`; on mobile they stay on one horizontally scrollable
  row instead of wrapping.
- The dossier exposes overview, event photo folders, inspections, estimates,
  repairs, rental movements, append-only history, and comments. `Резервы` and
  `Возвраты` intentionally remain explicit empty states until their target
  contracts are approved.
- One square photo folder represents one proven source event. Folder covers use
  preview media; the folder contains square thumbnail selection and the shared
  preview-only fullscreen carousel.
- `PhotoCarousel` is the shared shadcn/Embla wrapper. Edge arrows navigate only;
  the center opens fullscreen; mouse drag, keyboard arrows, and touch swipe are
  independent. The selected slide is synchronized between embedded and
  fullscreen viewers.
- On a direct dossier URL, Back falls back to `/warehouse`. Links from the
  dossier carry the workspace-entry marker so target workspaces return to the
  exact dossier URL and tab.
- The hero section and Tabs are non-shrinking children of the internally
  scrolling detail page. This prevents the mobile information/actions aside
  from being clipped or overlapped by Tabs.
- From `md` upward the hero is a 420px two-column evidence panel rather than a
  page-dominating image. The photo column is flexible; the complete passport is
  288-420px wide and scrolls internally when necessary.
- The passport includes warehouse, dimensions, finishing, category,
  characteristic tags, linoleum, status, comment, photo count, contents,
  shipment, tenant, price, and version. Normal tags use the muted treatment;
  sanitary facilities use the semantic primary-blue treatment.
- Photos support table/gallery switching on wide screens, event/date/author
  filters, and an in-tab add-photo action. Other populated dossier projections
  use `OperationsListGrid` at `lg` and cards below `lg`; this breakpoint accounts
  for the persistent sidebar reducing the actual tablet workspace.
- Reservation and return tabs remain data-empty, but present common filters,
  wide-screen empty grid headers, and compact muted empty text below `lg`.
- Below `lg`, empty dossier projections render only centered muted
  `... не найдены` text without a Card border, shadow, title, or description.
- Register filters are full-width fields. On phones they stack as labelled
  `Дата с`, `Дата по`, category/status/source, and author controls; the date
  inputs display calendar icons. One selected date is an exact-day filter and
  two dates are an inclusive range.
- Mobile photo folders use one full-width column and scroll vertically. The
  photo action sits on its own row instead of narrowing the filter group.
- Overview content is factual. It must not restore the legacy hardcoded row,
  readiness, planned inspection, or reservation dates. Cabin contents use the
  current DTO and the existing add/move dialogs.

Evidence:

- `panel/src/features/rental-items/rental-item-detail-page.tsx`
- `panel/src/features/rental-items/rental-item-dossier-registers.tsx`
- `panel/src/components/media/photo-carousel.tsx`
- `panel/src/components/ui/carousel.tsx`

## Fullscreen Photo Viewer Alignment (2026-07-12)

- A fullscreen photo uses equal horizontal insets on a phone so portrait media
  remains centered in the visible viewport rather than attaching to one edge.
- The active-photo counter is visually grouped with the fullscreen controls and
  appears immediately above the previous, next, and rotate actions.

Evidence:

- `panel/src/components/media/photo-carousel.tsx`

## Imported Rental Cabin Return Entry (2026-07-12)

- `/logistics/returns` exposes `Добавить бытовку из аренды` before the normal
  return action. It records a cabin as `RENTED` with counterparty, shipment
  date, driver, furniture contents, and optional photos, then opens the normal
  return form with those values and the cabin preselected.
- The return register no longer renders the non-actionable reception-method
  column. Primary actions use `Конфликт мебели`, `Конфликт оборудования`, and
  `Создать смету` terminology. A furniture conflict shows factual deltas and
  the other rented cabins of the same counterparty.

Evidence:

- `panel/src/features/logistics/import-rented-cabin-dialog.tsx`
- `panel/src/features/logistics/logistics-returns-page.tsx`

## Return Equipment Conflict Gate (2026-07-12)

- An unresolved extra-equipment disposition replaces the return decision set
  with one `Конфликт оборудования` action. Until it is resolved, the operator
  cannot choose undamaged acceptance or estimate creation.
- `Конфликт оборудования`, `Конфликт мебели`, `Принять без повреждений`, and
  `Создать смету` use the same 208px action width.

Evidence:

- `panel/src/features/logistics/logistics-returns-page.tsx`

## Dossier Register And Warehouse Cell Refinement (2026-07-12)

- Every dossier tab exposes a full-width smart-search row followed by compact
  date/category/author filter chips. Wide screens retain the shared grid;
  smaller screens retain cards or one full-width photo folder.
- Photo folders use the shared density dialog. Phone is always one column;
  tablet exposes up to 3x3 and desktop up to 5x5. The saved non-phone choice
  survives tab changes and reloads.
- The density matrix is one keyboard/pointer slider rather than multiple
  duplicate buttons. Filter controls expose their active range/count to
  assistive technology.
- Warehouse Type, Characteristics, and Comment cells remain truncated in-row
  but open the complete text in a Popover by mouse or keyboard.
- The add-contents dialog keeps warehouse-stock allocation and replaces the
  nearest-cabin block with a labelled shadcn Combobox of eligible filled cabins.
  Quantity controls state the available maximum and remain capped.

Evidence:

- `panel/src/features/rental-items/rental-item-dossier-registers.tsx`
- `panel/src/features/rental-items/rental-items-grid-settings-dialog.tsx`
- `panel/src/features/rental-items/rental-items-table-view.tsx`
- `panel/src/features/rental-items/add-contents-dialog.tsx`

## Mobile Warehouse And Dossier Stability (2026-07-12)

- The mobile warehouse create action uses the remaining toolbar width, so its
  right edge is aligned with the search field and registry without horizontal
  clipping.
- In phone grid cards, the primary metadata reads `type, dimensions,
  category`; finishing occupies the next left line and characteristics occupy
  up to two compact rows on the right below the status. The photo area yields
  height to this information for one- and three-column card layouts.
- Mobile photo-register controls are initially a single accessible filter
  button with a funnel icon. It expands the search, add-photo action, date,
  event, and author controls; event and author chips also use the funnel icon,
  not a settings/sliders glyph. Larger screens keep their expanded controls.
- Apply the same collapsed-control pattern to every dossier register,
  including inspections, estimates, repairs, reserves, shipments, returns,
  history, and comments. When opened on a phone, the funnel control and smart
  search share one row; actions and filter chips remain below it.
- The mobile dossier tabs reserve a stable content area, so switching between
  overview, photos, and empty inspections does not move the tab separator
  under the operator's finger.

Evidence:

- `panel/src/features/rental-items/rental-items-page.tsx`
- `panel/src/features/rental-items/rental-items-grid-view.tsx`
- `panel/src/features/rental-items/rental-item-dossier-registers.tsx`
- `panel/src/features/rental-items/rental-item-detail-page.tsx`

## Development Task-Board Schedule And Worker Surfaces (2026-07-12)

- With the development auth bypass enabled, `/settings/task-board` exposes a
  sixth `График` tab. It selects a group, edits its warehouse time zone and
  return grace, and renders a Monday-Sunday schedule with linked template days,
  copy-to-day/group actions, shifts, and typed rest periods.
- The schedule timeline distinguishes work and rest with semantic chart tokens.
  Rest blocks move and resize in five-minute steps by pointer or keyboard; the
  exact Popover editor validates start/end, type, warning, and automatic pause.
  The rail remains horizontally usable on mobile.
- MOCK-only simulation controls expose date/time, run/pause, speeds x1/x60/x300,
  and reset to real time. These controls are absent from the production HTTP
  surface.
- In development `/task-board` uses configured active/hidden/collapsed queues,
  order, class bindings, and the persistent virtual `Без очереди` column. A
  worker switcher exposes a local inbox and unread counter; new local events use
  sonner rather than operating-system notifications.
- Responsive board/card and DnD presentation remains the existing approved
  task-board UI; the unified mock changes its data source, not the production
  shell contract.

Evidence:

- `panel/src/features/settings/task-board/task-board-settings-page.tsx`
- `panel/src/features/settings/task-board/schedule-settings.tsx`
- `panel/src/features/settings/task-board/schedule-timeline.tsx`
- `panel/src/features/task-board/task-board-page.tsx`
- `panel/src/features/task-board/task-board-mock-toolbar.tsx`

## W1 Warehouse HTTP Cutover (2026-07-14)

`WarehouseProvider` now loads active warehouses through the gateway Bearer
client and persists the canonical UUID only. It migrates the one-time saved
`spb`/`msk` selection to the confirmed IDs; an unknown stored value resolves to
the first active warehouse, and a missing production token/service is an error
rather than a mock fallback.

`/settings/warehouses` is restricted to `SYSTEM_ADMIN` and supplies searchable
desktop grid/mobile cards, create/edit including nullable sort order, and
soft-deactivation confirmation. A `409` refreshes data and reports conflict
without replacing newer data. Its Playwright route fixture is test-only;
`warehouse-location-api.ts` remains a development fixture while topology is
`UNKNOWN`.

## Asset HTTP cutover (implementation record, 2026-07-16)

The production routes `/warehouse`, rental-item detail, `/equipment`,
`/write-offs/equipment` and `/settings/assets` use gateway Bearer HTTP adapters
for the asset contract. They retain the shared responsive grid/card and shadcn
form/dialog patterns, but do not fall back to browser business stores when the
asset service, runtime configuration or token is missing. The detail surface
supports passport, status, general comment, append-only notes and contents;
settings supports global catalog and classifier CRUD.

The photo tab fails closed because the currently available media surface does
not yet expose the required rental-item upload/read endpoint through the
approved edge. Tenant, shipment, topology, reservation, repair, inventory,
logistics and dossier workflow surfaces remain explicitly deferred rather than
using browser production fallbacks.

`panel/e2e/asset-cutover.spec.ts` exercises the cutover through a test-only
HTTP route fixture at desktop, tablet and mobile viewports. Its TypeScript,
lint and production-build checks pass. The Playwright configuration uses its
bundled Chromium channel rather than a system Google Chrome dependency; the
flow passed all three configured projects, 3/3, in an isolated official test
container. The fixture is not a production fallback or a service mock used by
the application runtime.

## Maintenance HTTP cutover (verified implementation, 2026-07-17)

Production catalog, estimate, repair, acceptance and maintenance write-off
surfaces now use the versioned `/api/maintenance/v1` Bearer HTTP adapter. The
catalog production store is fail-closed when service configuration, token or
warehouse context is missing; browser persistence remains only an explicit
development/test fixture. Transport stage order is normalized without changing
the operator-facing ordering convention.

Panel ESLint, TypeScript, the production build and 46 Vitest files/261 tests
passed. `panel/e2e/maintenance-cutover.spec.ts` passed desktop, tablet and
mobile, 3/3, with bundled Chromium in the official Playwright container.
