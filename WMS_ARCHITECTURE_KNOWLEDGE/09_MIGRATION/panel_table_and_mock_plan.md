# Panel Rental Item Table And Mock Plan

Date: 2026-07-09.

Scope: existing React `panel` rental item table and the working mock layer needed before Spring API contracts are complete.

## Current React Evidence

- `panel` is a Vite React SPA with React Router and React Query.
- Existing rental item table page is `panel/src/features/rental-items/rental-items-page.tsx`.
- Existing table implementation is `panel/src/features/rental-items/rental-items-table-view.tsx`.
- The table already uses TanStack Table for sorting, column sizing, column visibility, and column order.
- Column settings dialog exists at `panel/src/features/rental-items/rental-items-column-settings-dialog.tsx`.
- Photo UI is implemented through `panel/src/components/media/photo-carousel.tsx` and `panel/src/features/rental-items/rental-item-photo-dialog.tsx`.
- Accessory/contents movement dialogs exist in `panel/src/features/rental-items/add-contents-dialog.tsx`, `move-contents-to-rental-item-dialog.tsx`, and `move-contents-to-stock-dialog.tsx`.
- `panel/src/api/*` is currently browser mock/localStorage scaffolding and must not be treated as final backend contract.

## Legacy Evidence

- Legacy table screen: `wms-panel-old/src/main/java/dev/buhanzaz/wmspanel/view/rentalitem/RentalItemListView.java`.
- Legacy descriptor: `wms-panel-old/src/main/resources/dev/buhanzaz/wmspanel/view/rentalitem/rental-item-list-view.xml`.
- Legacy table filters are warehouse, category, subcategory, type, and status.
- Legacy grid data loads only after category selection through `terminalLoadRequested`.
- Legacy grid has static columns for number, status, warehouse, category, subcategory, type, tags, comment, last modified date, and last modified by.
- Legacy grid adds dynamic classifier attribute columns from `RentalItemService.loadClassifierAttributes(...)`.
- Legacy grid column visibility is persisted through `RentalItemService.loadGridColumnVisibility("RENTAL_ITEM_GRID")` and `saveGridColumnVisibility(...)`.
- Legacy detail side panel shows editable fields, passport, dynamic attributes, accessories, tags, comment, latest photos, and history.
- Legacy accessory transfer between rental items is implemented by `RentalItemService.transferItemAccessory(...)`.
- Legacy item-to-stock behavior from the current React mock table is not fully proven as a direct `RentalItemListView` action. Mark direct item-to-stock movement `UNKNOWN`/`mock-only` until proven by source.

## Migration Plan

1. Reuse the existing React table UI instead of creating a new table.
2. Introduce legacy-derived table DTOs and metadata without treating the current flattened `RentalItemDto` as final.
3. Keep React components behind adapter functions so mock and future Spring endpoints expose the same shape.
4. Replace prototype statuses with legacy statuses:
   - `READY`
   - `TEMP_RESERVED`
   - `RESERVED`
   - `IN_RENT`
   - `NEED_INSPECTION`
   - `WAITING_ESTIMATE_CONFIRMATION`
   - `WAITING_REPAIR`
   - `IN_REPAIR`
   - `WAITING_REPAIR_CHECK`
   - `IN_CAP_REPAIR`
5. Add dynamic classifier attribute column metadata to the table adapter.
6. Keep column visibility/order persistent in the mock layer and later map it to the real user column settings API.
7. Make mock workflows testable before backend integration:
   - list/filter/sort rental items
   - show dynamic attributes and tags
   - show photos/history data
   - transfer accessory quantities between rental items in the same warehouse
   - move stock quantities into rental items
   - preserve quantity invariants in mock state
8. Mark any behavior not proven by legacy as `UNKNOWN` or `mock-only`.

## Verification Plan

- Run `npm run typecheck` in `panel`.
- Run `npm run lint` in `panel`.
- Run `npm run build` in `panel`.
- Use Playwright/browser validation for table filtering, column settings, photo dialog, and mock movement workflows when implemented.

## Execution Prompt

- Use `PROMPT_PANEL_RENTAL_TABLE_MIGRATION.md` from the repository root for future implementation runs of this migration slice.
