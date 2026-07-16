# Panel DTO Table And Photo Architecture

Date: 2026-07-09.

Scope:

- Current React target under `panel`.
- Rental item table schema behavior.
- Rental item photo variant and mobile fullscreen behavior.

## Implemented Target Architecture

- Rental item API/model files are feature-owned, not global scaffolding:
  - `panel/src/features/rental-items/api/rental-items-api.ts`
  - `panel/src/features/rental-items/model/rental-item.ts`
- `RentalItemsTableSchema` is built from actual DTO fields plus known field metadata.
- Known fields keep deliberate labels/renderers/sizing for `number`, `status`, `hasPhotos`, `contents`, booleans, and currency values.
- Unknown scalar DTO fields are included as table columns automatically.
- Filter definitions are derived from the table schema and then narrowed by current visible column settings.
- Column settings are normalized against the current schema, so new DTO fields can appear without discarding saved user settings.
- `panel/src/components/media/photo-carousel.tsx` is the single active carousel/fullscreen implementation.
- The unused duplicate fullscreen file `panel/src/features/rental-items/photo-fullscreen-viewer.tsx` was removed.

## Photo Variant Contract

- `RentalItemPhotoDto.url` remains only a fallback/backward-compatible URL.
- UI must prefer:
  - `RentalItemPhotoDto.variants.small.url` for preview/card/table-sized images.
  - `RentalItemPhotoDto.variants.largeWebp.url` for fullscreen display.
- Current mock returns small and large WebP variants for `getRentalItemPhotos(...)`.
- String-only photo sources are normalized in `PhotoCarousel` into small and large WebP fallback URLs so existing mock `previewPhotoUrls` still behave like the future two-variant contract.

## Mobile Fullscreen Behavior

- Mobile fullscreen photo viewing is handled by `RentalItemPhotoDialog` plus `PhotoCarousel`.
- When `activePhotoIndex` is provided, the carousel is controlled by the parent, as on rental item detail pages.
- When `activePhotoIndex` is omitted, the carousel must stay uncontrolled, as in the warehouse grid/card entry point.
- Horizontal pointer/touch swipes move previous/next.
- Upward pointer/touch swipe closes fullscreen.
- Fullscreen surfaces use `touch-none` and pointer capture to avoid mobile browser gesture cancellation.

## Verification

- `cd panel && npm run typecheck` passed.
- `cd panel && npm run lint` passed.
- `cd panel && npm run build` passed; only Vite chunk-size warning remained.
- Browser validation:
  - `/warehouse` grid card preview loaded small WebP URL (`w=360`, `fm=webp`).
  - Opening a photo from a warehouse grid card loaded large WebP URL (`w=1800`, `fm=webp`).
  - Horizontal swipe changed fullscreen photo from the warehouse grid entry point.
  - Upward swipe closed fullscreen and returned to the grid.

## Unknowns

- Final Spring media DTO/API contract remains `UNKNOWN`.
- Production image dimensions, quality, storage keys, and cache policy for small/large variants remain `UNKNOWN`.
