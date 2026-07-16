# Panel Create Rental Item Dialog

Date: 2026-07-09.

Scope:

- Current React target under `panel`.
- New rental item creation dialog on the warehouse page.

## Implemented Target Behavior

- The warehouse action button label is `Добавить новую бытовку`.
- Creation UI is implemented from scratch in `panel/src/features/rental-items/rental-item-create-dialog.tsx`.
- Static creation dictionaries live in `panel/src/features/rental-items/model/rental-item-create.ts`.
- The creation form does not use `categoryId`, `subcategoryId`, `typeId`, or legacy classifier IDs.
- The creation payload contains only the simple target model:
  - number
  - type
  - dimensions
  - finishing
  - category
  - characteristics
  - photos
  - linoleum
  - status
- Category is hidden from the user and set to `Новая`.
- Status is hidden from the user and set to `NEW` / `Новая`.
- Type, dimensions, finishing, and linoleum use a local shadcn `Popover` + `Button` dropdown because OS-native select menus looked out of style and the previous custom select did not reliably update form state in this modal.
- `БК-Пост охраны` defaults dimensions to `2x2` and allows these dimensions: `2x2`, `2.4x2`, `2.4x2.4`, `2.4x3`, `2.4x4`, `2.4x5`, `3x3`.
- `БК-Санблок` automatically sets dimensions to `2.4x6`, finishing to `ПВХ`, and shows toilet/sink/shower counters.
- `БК-Санблок` automatically sets linoleum to `Есть`.
- Every new rental item automatically includes the `Пластиковое окно` characteristic before type selection.
- `Пластиковое окно` is checked and disabled in the characteristics dialog because it is mandatory for new rental items.
- The static characteristic list includes `Мама-папа`.
- Photo upload supports multiple images, preview tiles, preview dialog, rotate, delete, and drag-and-drop zone text `Перетащите несколько изображений`.
- The drag-and-drop photo upload zone is desktop-only (`xl` and wider); mobile and tablet users keep the normal upload button without the drag-and-drop component.
- Photo processing in the mock adapter produces small WebP preview variants and large WebP fullscreen variants.
- The UI does not show the technical WebP variant explanation to users.
- Shared shadcn `Button` sizing is larger on mobile for touch ergonomics and reverts to the existing compact density from `md` upward.

## Verification

- `npm run typecheck` passed in `panel`.
- `npm run lint` passed in `panel`.
- `npm run build` passed in `panel`; only the existing Vite chunk-size warning remained.
- Browser validation on `http://127.0.0.1:8080/warehouse` confirmed:
  - the creation dialog opens from `Добавить новую бытовку`;
  - the drag-and-drop photo zone is visible;
  - selecting `БК-Пост охраны` defaults dimensions to `2x2` and exposes all seven post-guard dimensions;
  - selecting `БК-Санблок` sets dimensions to `2.4x6`, finishing to `ПВХ`, and shows sanblock counters.
- Follow-up browser validation confirmed selecting `БК-Санблок` sets linoleum `Есть` and selected `Пластиковое окно`.
- Follow-up browser validation confirmed `Мама-папа` appears in the characteristics dialog.
- Follow-up browser validation confirmed the drag-and-drop photo zone is hidden at `390px` and `820px`, visible at `1440px`.
- Follow-up browser validation confirmed mobile creation dialog buttons render at `40px` height with `20px` SVG icons.
- Follow-up browser validation confirmed the sanblock default `Пластиковое окно` is checked in the characteristics dialog (`aria-checked="true"`, `data-state="checked"`) and included in the create payload source.
- Follow-up browser validation confirmed a fresh creation form shows `Пластиковое окно` before type selection and the checkbox is checked/disabled.

## Unknowns

- Final backend create endpoint and MinIO photo rotation endpoint remain `UNKNOWN`; current implementation uses the feature mock adapter only.

## 2026-07-09 Mobile Action Button Width Follow-Up

- In `panel/src/features/rental-items/rental-item-create-dialog.tsx`, the `Добавить характеристики` and `Загрузить фото` section action buttons now share the same responsive layout class.
- On narrow/mobile widths, both buttons use full available row width and centered content so the shorter photo-upload label does not render narrower than the characteristics button.
- From `sm` upward, both buttons keep the existing auto-width desktop behavior.

### Follow-Up Verification

- `npm run typecheck` passed in `panel`.
- `npm run lint` passed in `panel`.
- `npm run build` passed in `panel`; only the existing Vite chunk-size warning remained.
- Browser validation on `http://127.0.0.1:8080/warehouse` with an iPhone 12 viewport confirmed:
  - opening `Добавить новую бытовку` still works;
  - `Добавить характеристики` width is `326px`;
  - `Загрузить фото` width is `326px`;
  - the two buttons render with equal width on mobile.

## 2026-07-09 Desktop Photo Drop Zone Height Follow-Up

- In `panel/src/features/rental-items/rental-item-create-dialog.tsx`, the desktop-only drag-and-drop photo zone no longer uses `aspect-[4/3]`.
- The desktop drop zone now uses a fixed `h-28` height while keeping full width, dashed border styling, and the existing `xl`-only visibility rule.
- Mobile and tablet behavior is unchanged because the drag-and-drop zone remains hidden below `xl`.

### Follow-Up Verification

- `npm run typecheck` passed in `panel`.
- `npm run lint` passed in `panel`.
- `npm run build` is currently blocked by unrelated existing TypeScript errors in `panel/src/features/settings/estimates-repairs/api/repair-estimate-catalog-store.ts`; this follow-up did not modify that file.
- Automated browser validation was not completed in this session because `playwright-cli` is not available on the current PATH even though the local app is running on `http://127.0.0.1:8080`.
