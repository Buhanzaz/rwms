# Panel Rental Grid Format Settings

Date: 2026-07-09.

Scope:

- Current React target under `panel`.
- Rental item warehouse page view-mode controls and grid layout.

## Implemented Target Behavior

- The list/grid toggle border now follows the same responsive desktop height as the adjacent action buttons.
- The table/list and grid icon buttons have accessible labels:
  - `Список`
  - `Сетка`
- Grid format settings are available only when the rental item page is in grid mode.
- Grid format settings are not available on phone-sized viewports.
- Phone grid format is automatic:
  - portrait phone: `1x1`;
  - landscape phone: `2x1`.
- Tablet grid format defaults to `3x3` and is capped at `5x5`.
- Desktop grid format defaults to `5x5` and is capped at `10x10`.
- Manual tablet/desktop grid selections are saved per warehouse in localStorage key `rental-items:{warehouseId}:grid-format:v1`.
- Mobile automatic formats do not overwrite the saved tablet/desktop selection.
- The format picker is a shadcn `Dialog` with a pointer-driven grid selector, so mouse and touch/pointer dragging use the same logic.
- `RentalItemsGridView` now accepts separate `columns` and `rows` so non-square mobile landscape format `2x1` is supported.

## Verification

- `npm run typecheck` passed in `panel`.
- `npm run lint` passed in `panel`.
- `npm run build` passed in `panel`; only the existing Vite chunk-size warning remained.
- Browser validation was attempted through the in-app browser, Playwright MCP, and headless Chrome/CDP. Browser tooling was unreliable during this task, so final verification is limited to code/build checks.

## Source Files

- `panel/src/features/rental-items/rental-items-page.tsx`
- `panel/src/features/rental-items/rental-items-grid-view.tsx`
- `panel/src/features/rental-items/rental-items-grid-settings-dialog.tsx`

## Legacy Impact

- No `wms-panel-old` files were inspected or changed for this target-only UI adjustment.

## 2026-07-12 Superseding Responsive Standard

- The previous landscape-phone `2x1`, tablet cap `5x5`, and desktop cap `10x10`
  are superseded by the approved shared standard: phone `1x1`, tablet up to
  `3x3`, and desktop up to `5x5`.
- The same helper now drives the warehouse grid and dossier photo folders.
  Dossier photo density uses its own preference key and never lets a mobile
  viewport overwrite the stored tablet/desktop value.
- The visual picker is now one keyboard/pointer slider with aria-hidden cells,
  eliminating duplicate interactive cell names while preserving the matrix.

## 2026-07-12 Mobile Card Information Density

- The mobile create-cabin action flexes within the shared toolbar after the
  compact filter, column, and view controls. Its right edge matches the search
  and active registry surface; it does not use a fixed `190px` width.
- Where a card has sufficient space (one cabin on phone or the approved
  three-column density), it shows the type, dimensions, category, finishing,
  and compact characteristic tags together with the existing status. The
  characteristic tags use the right-side two-row area, while type/dimensions/
  category and finishing remain a readable two-line block on the left.
- These are target-only presentation changes. They do not change the browser
  adapter DTO or establish a backend schema contract.

## 2026-07-12 Warehouse Status Filter Colour Mapping

- The target warehouse status selector mirrors the semantic colours of the
  status badges shown in its registry rows. This lets an operator recognise a
  status before applying it, without altering filter semantics or DTO fields.
