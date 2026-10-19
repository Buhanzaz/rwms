# Panel Target Audit

Date: 2026-07-09.

Scope: renamed target React application under `panel`.

## Current Target Shape

- `panel` is a Vite React SPA, not a Spring backend.
- Main implemented routes are `/warehouse`, `/warehouse/:rentalItemId`, and `/equipment`.
- Other configured navigation entries render placeholder/empty pages.
- API modules under `panel/src/api` are browser mock/localStorage adapters, not backend migration contracts.
- The current UI data models are prototype-shaped and do not yet match legacy Jmix aggregates.

## Fixed Broken Build/Tooling

The target app previously failed production build because component props did not match their call sites:

- `PhotoCarousel` was called with `activeIndex`, `onActiveIndexChange`, and `showViewerToolbar`, but the component type did not define those props.
- `RentalItemsTableView` was called with controlled sorting and open callbacks, but the component type did not define those props.
- `npm run typecheck` used `tsc --noEmit`, which did not typecheck referenced projects from the root config.

Applied fixes:

- `PhotoCarousel` now supports controlled active photo index and delegates center clicks through `onCenterClick`.
- `RentalItemsTableView` now supports controlled sorting plus parent-owned item/photo open callbacks.
- `typecheck` now runs `tsc -b`, matching the build graph.
- ESLint is configured to allow known shadcn exports: `badgeVariants`, `buttonVariants`, `useComboboxAnchor`, and `useSidebar`.
- Hook lint issues in mobile detection, move target filtering, and fullscreen photo viewer dependencies were corrected.
- TanStack Table/Virtual React Compiler diagnostics are locally documented and suppressed at call sites.

Verification:

- `npm run typecheck` passes.
- `npm run lint` passes.
- `npm run build` passes.

Build note:

- Vite emits a chunk-size warning for a JavaScript bundle over 500 kB. This is not a build failure.

## Migration Risks Still Open

- Legacy source of truth remains `wms-panel-old`.
- React rental item statuses differ from legacy statuses.
- React rental item DTO is flattened and display-oriented; legacy uses classifier relations, dynamic attributes, event history, accessories, and media entities.
- Equipment API combines concepts that legacy separates across stock items, accessory balances, and rental-item accessory assignments.
- Warehouse location graph in `panel` has no confirmed legacy equivalent and remains prototype-only unless future evidence proves otherwise.
- Security, warehouse access, media authorization, mobile auth, queue board, repairs, reservations, and AI behavior are not implemented as target contracts in `panel`.

## Target Navigation Polish

Date: 2026-07-10.

- The current target mobile sidebar is a custom-animated shadcn Sheet in `panel/src/components/ui/sidebar.tsx`; its visual geometry and border treatment live in `panel/src/index.css`.
- The open mobile Sheet uses a one-pixel `--sidebar-border` outer edge. Header separators and control borders inherit the same token. During the closing morph, the edge fades to transparent to avoid an outline around the persistent blue toggle.
- This is target UI behavior only; the legacy Jmix source does not define the animation or visual token treatment.

## Target Navigation Divider Correction

Date: 2026-07-10.

- Supersedes the preceding Sheet-edge note: `panel/src/App.tsx` renders a `MobileSidebarBackgroundDivider` only while `openMobile` is true.
- The divider continues the mobile sidebar header line beneath the Sheet across `SidebarInset` at `64px`; the Sheet itself remains borderless.

## Target Navigation Content Offset Correction

Date: 2026-07-10.

- Supersedes the mobile background-divider treatment: no divider is rendered behind the Sheet.
- `panel/src/index.css` keeps the mobile content card `4px` below the fixed toggle while closed and `4px` below the `64px` sidebar header while open. The opening and closing offset follows the `360ms` sidebar animation and is also applied to touch landscape phones.

## Target Navigation Background Alignment Correction

Date: 2026-07-10.

- Supersedes the mobile content-offset correction: `main` retains a static mobile top padding and has no open-sidebar padding transition.
- The page inset, main background, and open mobile Sheet all begin at the shared top coordinate `0`; no divider is rendered behind the Sheet.

## Target Mobile Landscape Navigation

Date: 2026-07-10.

- The persistent mobile sidebar toggle is hidden by default and shown through the shared mobile media condition: narrow screens or short coarse-pointer screens. This preserves access after a phone rotates to landscape.
- The mobile sidebar navigation region scrolls vertically using `overflow-y: auto`, contained overscroll, and `touch-action: pan-y`; the sidebar header and settings footer remain fixed in the Sheet.

## Target Mobile Content Clearance

Date: 2026-07-10.

- `panel/src/App.tsx` marks the content container with `data-mobile-main`; `panel/src/index.css` applies static `3.5rem` top padding for the shared mobile condition.
- This prevents the fixed `40px` toggle at `12px` from covering the initial page controls after rotation while preserving the shared top coordinate of the background and open Sheet.

## Target Mobile Rental Heading Correction

Date: 2026-07-10.

- Supersedes the preceding global-content-padding treatment for the rental registry: `main` keeps its regular padding.
- `panel/src/features/rental-items/rental-items-page.tsx` marks its mobile title row with `data-mobile-page-heading`; its `3rem` mobile-only margin places the title and count beside the fixed icon with a `12px` gap.

## Target Mobile Sidebar Compact Correction

Date: 2026-07-10.

- `panel/src/index.css` reduces `[data-mobile-page-heading]` to `margin-left: 2.5rem`; on a `390px` viewport it gives the rental heading a `4px` clearance after the persistent sidebar button.
- `panel/src/index.css` hides `[data-sidebar="rail"]` below `[data-mobile-sidebar="true"]`. This preserves the Sheet-only mobile interaction and prevents the desktop rail from appearing at the panel's edge in landscape.

## Target Global Mobile Page Title Alignment

Date: 2026-07-10.

- `panel/src/index.css` aligns the initial `h1`/`h2` of every routed page to the persistent mobile sidebar control through the sidebar-inset layout, avoiding per-screen React changes.
- Existing `data-mobile-page-heading` containers retain their own `2.5rem` margin, while their nested heading margin is reset to avoid a double offset.
- First-section cards and empty route messages use a padding-aware `calc(1.5rem - 1px)` correction. Browser checks at `390x844` confirmed `x=56px` for the rental registry, equipment, estimates settings, and empty home content.

## Target Mobile Estimates Settings Surface

Date: 2026-07-10.

- `panel/src/index.css` removes the outer border, corner radius, and card background from direct configuration sections on the mobile estimates/repairs settings page.
- The first title receives a `1.5rem` inner-spacing correction after border removal, retaining `x=56px`; the warehouse registry's `x=56px` title alignment remains unchanged.
