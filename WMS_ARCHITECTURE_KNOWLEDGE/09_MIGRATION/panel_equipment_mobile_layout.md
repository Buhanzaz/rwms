# Panel Equipment Mobile Layout

Date: 2026-07-09.

## Request

- Fix `panel` mobile layout for `Доп. оборудование`.
- The mobile page must fit the smartphone viewport without horizontal scrolling.
- Do not inspect or change `wms-panel-old` for this UI-only adjustment.

## Implementation

- Updated `panel/src/features/equipment/equipment-page.tsx`.
- Removed the mobile `min-w-[980px]` table constraint.
- Main equipment rows now render as mobile-first cards with a compact two-column quantity summary.
- The desktop/tablet `md:` layout still renders the previous grid table:
  - name
  - total
  - stock
  - rented
  - written off
  - lost
- Expanded rental usage rows now stack vertically on mobile and keep the grid layout on `md` and above.
- Usage action buttons stack to full row width on mobile and remain compact horizontal buttons on desktop.
- Button icons use shadcn button icon spacing via `data-icon`.

## Verification

- `npm run typecheck` passed in `panel`.
- `npm run lint` passed in `panel`.
- `npm run build` passed in `panel`; Vite emitted only the existing chunk-size warning.
- Browser validation on `http://127.0.0.1:8080/equipment` with `390x844` viewport confirmed:
  - before expanding rows, `documentScrollWidth`, `bodyScrollWidth`, and `mainScrollWidth` all equal `390`;
  - after expanding an equipment row, `documentScrollWidth`, `bodyScrollWidth`, and `mainScrollWidth` all still equal `390`;
  - action buttons fit inside the viewport.
- Browser validation at `1280x720` confirmed no document-level horizontal overflow and desktop rows still render as CSS grid.

## 2026-07-09 Mobile Cabin Usage Summary Update

Request:

- Add a single `В бытовках` quantity to the mobile equipment card.
- Place the new cabin summary under `Всего`.

Implementation:

- Updated `panel/src/features/equipment/equipment-page.tsx`.
- Replaced the mobile `В аренде` summary tile with `В бытовках`.
- The `В бытовках` tile is forced into the first mobile grid column, so it appears directly below `Всего`.
- Current panel mock behavior: the single `В бытовках` number is derived from `item.rentedQuantity`.
- This is a panel UI/mock mapping only; `wms-panel-old` was not inspected for this UI-only adjustment.

Verification:

- `npm run typecheck` passed in `panel`.
- `npx eslint src/features/equipment/equipment-page.tsx` passed in `panel`.
- `npm run build` passed in `panel`; Vite emitted only the existing chunk-size warning.
- Browser validation on `http://127.0.0.1:8080/equipment` with `390x844` viewport confirmed:
  - `В бытовках` and one number are rendered in the mobile card;
  - `Поз.` and `К-во` are not rendered;
  - `В бытовках` is aligned with and positioned below `Всего`;
  - `documentScrollWidth`, `bodyScrollWidth`, and `mainScrollWidth` all equal `390`.

## 2026-07-09 Mobile Equipment Rent And Cabin Stock Split

Request:

- Restore the separate `В аренде` field in mobile equipment cards.
- Rename `В бытовках` to `В бытовках на складе`.
- Account for the new split in the mock data.

Implementation:

- Updated `panel/src/types/equipment.ts`.
- Updated `panel/src/api/equipment-api.ts`.
- Updated `panel/src/features/equipment/equipment-page.tsx`.
- `EquipmentItemDto` now exposes `cabinStockQuantity` separately from `rentedQuantity`.
- `EquipmentRentalUsageDto` now carries `rentalItemStatus` from the rental item mock.
- Current panel mock split:
  - `rentedQuantity` sums equipment usage in rental items with status `RENTED`.
  - `cabinStockQuantity` sums equipment usage in rental items whose status is not `RENTED`.
  - `totalQuantity` includes `stockQuantity`, `cabinStockQuantity`, `rentedQuantity`, `writtenOffQuantity`, and `lostQuantity`.
- Mobile cards render `В бытовках на складе` under `Всего`, and `В аренде` as a separate tile in the same row.
- Desktop rows now also show a `В бытовках на складе` column so visible quantities reconcile with `Всего`.
- This is a panel mock/UI mapping; final Spring DTO semantics remain `UNKNOWN` until backend contract migration.

Verification:

- `npm run typecheck` passed in `panel`.
- `npx eslint src/types/equipment.ts src/api/equipment-api.ts src/features/equipment/equipment-page.tsx` passed in `panel`.
- `npm run lint` passed in `panel`.
- `npm run build` passed in `panel`; Vite emitted only the existing chunk-size warning.
- Browser validation on `http://127.0.0.1:8080/equipment` with `390x844` viewport confirmed:
  - `В бытовках на складе` and `В аренде` are both rendered in the first mobile card;
  - `В бытовках на складе` is aligned with and positioned below `Всего`;
  - `В аренде` is in the right tile of the same row;
  - `documentScrollWidth`, `bodyScrollWidth`, and `mainScrollWidth` all equal `390`.
- Browser validation at `1280x720` confirmed `В бытовках на складе` and `В аренде` headers are rendered and document-level horizontal overflow is absent.

## 2026-07-09 Mobile Usage Row Opens Rental Item Detail

Request:

- On mobile, tapping the upper usage-row area with rental item number, type, and equipment quantity should open the full rental item detail view used by `/warehouse`.
- The clickable area should be visually discoverable on hover/focus with a subtle gray shadow in the current panel style.

Implementation:

- Updated `panel/src/features/equipment/equipment-page.tsx`.
- `UsageRows` now accepts `onOpenRentalItem`.
- `EquipmentPage` opens the existing `/warehouse/{rentalItemId}` detail route via `useNavigate`.
- Mobile usage rows render the number/type/quantity area as a separate `button`.
- Desktop usage rows keep the previous table-like cells.
- The mobile button uses a transparent border by default and adds muted background, border, focus ring, and a subtle gray shadow on hover/focus.
- Existing action buttons remain separate and keep their own click handlers.

Verification:

- `npm run typecheck` passed in `panel`.
- `npx eslint src/features/equipment/equipment-page.tsx` passed in `panel`.
- `npm run lint` passed in `panel`.
- `npm run build` passed in `panel`; Vite emitted only the existing chunk-size warning.
- Browser validation on `http://127.0.0.1:8080/equipment` with `390x844` viewport confirmed:
  - expanding an equipment row renders mobile buttons labeled `Открыть карточку бытовки ...`;
  - the mobile info area changes from no shadow to a gray hover shadow, muted background, and border;
  - tapping the info area opens `/warehouse/{rentalItemId}`;
  - the detail page renders the existing rental item detail chrome with `Обзор`, `Фото`, and `Наполнение`;
  - document-level horizontal overflow is absent.

## Shared Grid desktop register (2026-07-11)

- Desktop and tablet `/equipment` now use `OperationsListGrid`, the same semantic Table/Grid infrastructure used by other panel registers. The table supports all existing numeric columns, built-in sortable headers, and an expanded usage row spanning the Grid columns.
- The mobile compact equipment cards remain unchanged in structure to preserve the previously verified no-horizontal-overflow phone behavior.

Evidence:

- `panel/src/features/equipment/equipment-page.tsx`
- `panel/src/components/operations-list-grid.tsx`
