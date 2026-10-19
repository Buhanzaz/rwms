# Panel Rental Item Table Migration

Date: 2026-07-09.

Scope:

- Existing React rental item table under `panel`.
- Mock adapter behavior needed before Spring endpoints are complete.

## Legacy Evidence

- `wms-panel-old/src/main/java/dev/buhanzaz/wmspanel/view/rentalitem/RentalItemListView.java`
  - `applyFilterParameters()` sets `terminalLoadRequested` only when category is selected.
  - `loadGridItems()` returns an empty list without category and filters by warehouse/category/subcategory/type/status.
  - `configureGridColumns()` registers number/status/warehouse/category/subcategory/type/tags/comment/lastModifiedDate/lastModifiedBy and dynamic classifier columns.
  - `HIDDEN_TERMINAL_ATTRIBUTE_CODES` hides `SHOWERS`, `TOILETS`, `SINKS`, `BOILER`, `PARTS_COUNT`, `SIZE`.
  - details render passport attributes, tags, accessory assignments, latest photos, and history.
- `wms-panel-old/src/main/resources/dev/buhanzaz/wmspanel/view/rentalitem/rental-item-list-view.xml`
  - Grid query includes `:loadAllowed = true` and warehouse/category/subcategory/type/status filters.
  - Static grid columns match the Java view registration.
- `wms-panel-old/src/main/java/dev/buhanzaz/wmspanel/view/rentalitem/RentalItemStatusSupport.java`
  - Legacy status codes and Russian labels are authoritative.
- `wms-panel-old/src/main/java/dev/buhanzaz/wmspanel/service/RentalItemService.java`
  - `transferItemAccessory(...)` enforces positive quantity, same-warehouse target, source quantity availability, source decrement/removal, and target increment.
- `wms-panel-old/src/main/resources/dev/buhanzaz/wmspanel/liquibase/data/rental-import/*.csv`
  - Legacy rental category/subcategory/type seed names were used for mock dictionaries.
- `wms-panel-old/src/main/resources/dev/buhanzaz/wmspanel/liquibase/changelog/2026/06/12-090000-rental-item-attributes-tags.xml`
  - Legacy attribute definitions/options include `FINISHING`, `ELECTRICITY`, `LINOLEUM`, and terminal-hidden attributes.

## Implemented In Panel

- Reused the existing React table/page/dialog components instead of creating a new table UI.
- Updated `panel/src/types/rental-item.ts` to use legacy rental statuses and legacy-derived table/filter/attribute DTOs while preserving current photo/content fields for working UI tests.
- Updated `panel/src/api/rental-items-api.ts` into a mock adapter that now supports:
  - legacy category/subcategory/type dictionaries,
  - category-gated rental item loading,
  - warehouse/category/subcategory/type/status filtering,
  - legacy status labels,
  - classifier attribute metadata and `attr_*` dynamic columns,
  - tags, latest history, photo data, and last modified fields,
  - persisted `RENTAL_ITEM_GRID` column settings,
  - item-to-item and stock-to-item content movement in mock state.
- Updated `panel/src/features/rental-items/rental-items-page.tsx` to load table metadata and column settings through adapter functions.
- Updated `panel/src/features/rental-items/rental-items-filters.tsx` to represent legacy cascading filters: category, class, type, status.
- Updated `panel/src/features/rental-items/rental-items-table-view.tsx` to render legacy static columns and dynamic classifier columns while preserving existing photo/content action entry points.
- Updated `panel/src/features/rental-items/rental-item-detail-page.tsx` to show legacy-derived passport fields, tags, last modified fields, and history.

## 2026-07-09 Current User-Facing Table Shape

- The active target table defaults to the simplified user-facing rental item columns:
  `Номер`, `Тип`, `Габариты`, `Отделка`, `Категория`, `Характеристики`, `Линолеум`, `Статус`.
- Technical DTO metadata must not be inferred into visible table columns:
  `warehouseName`, `categoryId`, `subcategoryId`, `typeId`, `subcategory`, `lastModifiedDate`, `lastModifiedBy`, and related internal IDs/audit/media fields.
- New scalar DTO fields may still be discovered for future table settings, but inferred columns are hidden by default unless product/table configuration deliberately exposes them.
- Saved column and sorting state uses a versioned storage key so previous prototype settings with technical columns do not affect the current table.
- The table element must fill the available table viewport on wide screens and retain horizontal scrolling when configured columns exceed the available width.

## 2026-07-09 Mobile Toolbar Layout

- In the current `panel` mobile rental item page, the top action bar is intentionally split into:
  - a first row with `Фильтры`, `Столбцы`, and the unchanged table/grid toggle;
  - a second row with a full-width `Добавить новую бытовку` button;
  - a third row with the full-width search input.
- On small viewports, `Фильтры` and `Столбцы` use equal widths and expand so the right edge of the table/grid toggle aligns with the right edge of the search input.
- The table/grid toggle container keeps its existing width on mobile; alignment is achieved by widening the neighboring buttons, not by enlarging the toggle.
- On small viewports, the `Добавить новую бытовку` button matches the search input width.

## 2026-07-09 Card Grid Format Limits

- The rental item card/grid view keeps a user-facing `N x N` format setting for tablet and desktop viewports.
- Mobile viewports remain fixed compact layouts rather than using the format picker:
  - all mobile orientations: `1 x 1`.
- Tablet-sized viewports are limited to maximum `3 x 3`.
- Desktop/PC viewports are limited to maximum `5 x 5`.
- Saved grid settings above the current viewport limit must be clamped before rendering so old localStorage values cannot produce over-dense card grids.

## 2026-07-09 Card Grid Photo Ratio

- Rental item grid cards use a fixed internal height split:
  - photo/carousel/empty-photo placeholder: approximately `75%` of card height;
  - rental item description/action area: approximately `25%` of card height.
- The photo block is always rendered, including for items without photos, so empty-photo placeholders follow the same layout as real photos.
- The description area must remain compact and truncated to avoid resizing or overlapping the card when the grid is at tablet `3x3` or desktop `5x5`.
- The selected grid format is an upper bound, not a forced density. The rendered grid adapts below that bound when the available viewport area would make cards too small.
- Desktop classification is width-based. A `1280x720` viewport is desktop-width and should not fall back to the mobile landscape `2x1` behavior.
- The virtualized card grid must re-measure after adaptive row height, row count, or column count changes. Without this, initial page load can use stale TanStack Virtual measurements until the user changes the grid format.

## 2026-07-09 App Shell Header Removal And Mobile Sidebar Trigger

- The previous target behavior where the rental item menu collapsed upward into the app header is superseded.
- Current target `panel` behavior: no top app header is rendered above page content.
- Desktop/tablet navigation remains the left sidebar. When collapsed, it keeps an icon-width rail rather than disappearing completely.
- Desktop sidebar uses the brand block as the toggle: clicking the `WMS Panel`/blue warehouse icon block collapses or expands the sidebar.
- In collapsed desktop state, the sidebar width remains `48px`: a `32px` icon/control width plus `8px` side padding on each side. Visible menu icon controls should also render at `32px`.
- Do not render a separate collapse/expand icon button in the collapsed strip.
- Mobile navigation uses a fixed top-left blue warehouse-icon toggle, not the default white `SidebarTrigger`. The button stays visible while the mobile sidebar sheet is open and closes the sheet on a second tap.
- The mobile sidebar sheet uses paired `360ms` keyframe morphs: it expands from the fixed `40px` button and, on close, contracts back to the same top-left square before unmounting. Position, size, corners, shadow, and opacity animate together while the Sheet retains its normal sidebar color; navigation content appears shortly after expansion starts and fades away before contraction. The Sheet has no border in mobile mode, and the fixed blue toggle has no decorative active ring. The Sheet is non-modal so the fixed toggle remains clickable above it; the navigation content remains vertically scrollable on short landscape screens.
- Phone landscape is still mobile when the touch viewport matches `(max-height: 500px) and (pointer: coarse)`, so it keeps the fixed button, mobile `1x1` rental grid behavior, and hidden format picker. Desktop/tablet keep the `collapsible="icon"` rail and smooth `48px` collapse.
- In the open mobile sidebar, the brand block hides its duplicate blue warehouse icon and shifts `WMS Panel / СПБ` text to the right so it does not sit under the fixed button.
- The sidebar brand subtitle shows the selected warehouse code such as `СПБ` instead of the generic `Складская система` label. Current warehouse codes are expected to be short three-letter codes in this target UI.
- The rental item grid format button remains hidden on mobile, and mobile grid mode always renders `1 x 1`.

## 2026-07-09 Rotated Mobile Grid Format Clamp

- The current `panel` rental item card grid must treat rotated phone-sized viewports as mobile, not tablet.
- On those rotated phone-sized viewports, the grid stays fixed at `1 x 1` and the format picker/button must not be rendered.
- The current implementation in `panel/src/features/rental-items/rental-items-page.tsx` detects this with a phone-landscape viewport guard (`width < 1024` and `height <= 500`) in addition to the normal portrait mobile width check.

## 2026-07-09 Mobile Rental Menu Collapse

- On current mobile `panel` rental item pages, the title row (`Бытовки`, count badge, chevron) is the user-facing collapse/expand trigger for the toolbar/search/filter block.
- Expanded state shows the current mobile actions (`Фильтры`, `Столбцы`, table/grid toggle, `Добавить новую бытовку`) plus search and mobile filters.
- Collapsed state hides that block entirely and leaves only the title row with a downward chevron.
- `panel/src/features/rental-items/rental-items-page.tsx` implements the indicator as a simple chevron rotation instead of a separate arrow icon pair.

## 2026-07-09 Mobile And Tablet Detail Swipe Back

- Current target `panel` behavior for `/warehouse/:rentalItemId`: on mobile and tablet, a left-edge horizontal swipe should return to the previous history entry instead of forcing `/warehouse`.
- This preserves navigation context for flows that open rental item detail from other pages such as `Доп. оборудование`.
- `panel/src/features/rental-items/rental-item-detail-page.tsx` implements the gesture only for `max-width: 1023px`.
- The detail root scroll container uses `touch-pan-y` and pointer capture for the armed edge gesture so mobile touch input is not canceled before `pointerup`.
- When the browser history has no previous entry, the fallback route remains `/warehouse`.

## Deliberate Target Architecture Deviations

- The target React layer uses adapter functions and DTOs instead of copying Jmix entity graphs or FlowUI state patterns.
- Legacy remains source of truth for business semantics, but implementation architecture can differ when the legacy shape is poor or framework-specific.
- Deviations must stay documented with evidence and must not invent unproven business behavior.

## Mock-Only / UNKNOWN Behavior

- Search remains a React mock/testing convenience, not a proven legacy `RentalItemListView` filter.
- Photo and content action columns are preserved for React workflow testing even though the legacy static grid column list does not include them as ordinary columns.
- Direct item-to-stock content movement is retained as mock-only/UNKNOWN because direct `RentalItemListView` evidence is incomplete.
- The current nearest-location graph used by `AddContentsDialog` remains prototype/mock-only unless future legacy evidence proves a location-distance transfer model.

## Verification

- `cd panel && npm run typecheck` passed.
- `cd panel && npm run lint` passed.
- `cd panel && npm run build` passed; Vite emitted only the existing chunk-size warning.
- Browser validation on `http://127.0.0.1:8080/warehouse` confirmed:
  - table starts empty until category is selected,
  - category/class/type/status filters work,
  - dynamic columns `Отделка`, `Электрика`, `Линолеум` appear after category selection,
  - hidden terminal attributes such as `Бойлер` do not appear,
  - sorting by number toggles descending,
  - column visibility persists across reload,
  - photo dialog opens,
  - detail page shows passport/history fields,
  - item-to-item movement moves quantities from source to target,
  - stock-to-item movement decreases available stock through the mock workflow and updates the table row.
- Mobile browser validation at `390x844` confirmed:
  - `Добавить новую бытовку` width matches the search input width (`358px`);
  - `Фильтры` and `Столбцы` have equal widths (`134px`);
  - the right edge of the table/grid toggle matches the search input right edge (`374px`);
  - the table/grid toggle size itself is unchanged.

## Mobile Registry Control Refinement (2026-07-11)

- Supersede the preceding mobile-toolbar width decision: `Фильтры` and `Столбцы` are icon-only controls in the same compact bordered control surface as the list/grid ToggleGroup; all four controls use the same `32px` interactive geometry.
- The `Добавить новую бытовку` action remains explicit but uses the compact `sm` Button size and consumes the remaining action-row width rather than recreating a full-width second row.
- The mobile registry toolbar can be collapsed only from the `/warehouse` route header. Its URL state is `?toolbar=collapsed`, so the header control and page remain synchronized and the hidden controls cannot receive focus.

Evidence:

- `panel/src/features/rental-items/rental-items-page.tsx`
- `panel/src/components/site-header.tsx`

Verification:

- Playwright at `390x844` found icon-only controls with accessible names `Фильтры` and `Настроить столбцы`, a labelled list/grid radiogroup, and a compact `Добавить новую бытовку` action.
- Clicking the header control removed the toolbar search field and set `?toolbar=collapsed`; clicking it again restored the field and cleared the query parameter.

## Separate mobile registry controls (2026-07-11)

- The mobile `Фильтры`, `Настроить столбцы`, list, and grid actions are separate controls with shared 32px outlined geometry and 8px gaps. The list/grid view uses the standard shadcn `ToggleGroup` outline variant rather than a bespoke combined switcher.
- `Добавить новую бытовку` remains in the same row at 190px wide and has standard compact left content padding.

Evidence: `panel/src/features/rental-items/rental-items-page.tsx`.

## Desktop registry view-switcher regression fix (2026-07-11)

- Commit `b724112` moved the existing list/grid ToggleGroup into a `lg:hidden` mobile-only control as part of the mobile-toolbar rewrite. This unintentionally removed the only desktop control for the persisted `viewMode`.
- The target restores a separate desktop-visible ToggleGroup beside `Столбцы`, using the same existing state and warehouse-scoped persistence. The mobile control remains intact; no table/card grid widths or formatting rules changed.

Evidence:

- `panel/src/features/rental-items/rental-items-page.tsx`
- `git show b724112 -- panel/src/features/rental-items/rental-items-page.tsx`
