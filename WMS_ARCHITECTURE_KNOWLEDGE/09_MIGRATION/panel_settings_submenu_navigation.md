# Panel Settings Submenu Navigation

Date: 2026-07-09.

## Request

- In `panel`, change the footer `Настройки` navigation so it does not open settings directly.
- Make `Настройки` expand an upward submenu.
- Add two submenu entries:
  - `Настройка смет и ремонтов`
  - `Настройка Доски задач`
- Remove the submenu left line.
- Keep the submenu right edge aligned with the `Настройки` button.
- Close the submenu on repeated `Настройки` click or when clicking another sidebar area.
- Make mobile sidebar buttons and icons larger and consistent.

## Implementation

- Updated `panel/src/components/app-sidebar.tsx`.
- `Настройки` is now a toggle button, not a direct link.
- Submenu entries route to:
  - `/settings/estimates-repairs`
  - `/settings/task-board`
- The settings submenu uses `SidebarMenuSub`/`SidebarMenuSubButton` without the default left border.
- Menu visibility is manual UI state, while `/settings/**` still keeps the main settings button active.
- Clicks in `SidebarHeader` or `SidebarContent` close the settings submenu.
- Mobile sidebar menu buttons and submenu buttons use `40px` height and `20px` icons; desktop remains compact.
- Updated `panel/src/App.tsx` with placeholder page metadata for the two new settings routes.

## Verification

- `npm run typecheck` passed in `panel`.
- `npm run lint` passed in `panel`.
- `npm run build` passed in `panel`; Vite emitted only the existing chunk-size warning.
- Browser validation on `http://127.0.0.1:8080/` confirmed:
  - `Настройки` opens the submenu upward.
  - repeated `Настройки` click closes the submenu.
  - clicking `KPI` in the sidebar closes the submenu.
  - submenu left border is `0px`.
  - submenu and submenu item right edges align with the settings button.
  - mobile viewport `390x844` renders sidebar menu buttons and submenu buttons at `40px` height with `20px` icons.

## 2026-07-09 Compact Auto-Close Update

Request:

- When a user chooses a sidebar destination such as `Склад` or a settings submenu item, the menu should close on mobile devices and tablets.
- Wide desktop behavior must remain unchanged.
- Do not inspect or change `wms-panel-old` for this UI-only adjustment.

Implementation:

- Updated `panel/src/hooks/use-mobile.ts`.
- Added a shared media-query helper plus `useIsTabletOrSmaller()` for tablet-aware sidebar behavior.
- Updated `panel/src/components/app-sidebar.tsx`.
- Sidebar navigation links now call a shared navigation-close handler that:
  - closes the Radix `Sheet` on mobile widths below `768px`;
  - collapses the current expandable sidebar on tablet widths below `1024px`;
  - leaves wide desktop sidebar state untouched.
- The behavior is attached to actual navigation links only; the `Настройки` toggle still just opens its submenu.

Verification:

- `npm run typecheck` passed in `panel`.
- `npm run lint` passed in `panel`.
- `npm run build` passed in `panel`; Vite emitted only the existing chunk-size warning.
- Browser validation on `http://127.0.0.1:8080/` confirmed:
  - at `390x844`, clicking `Склад` closes the mobile sidebar sheet;
  - at `390x844`, clicking `Настройка Доски задач` closes the mobile sidebar sheet;
  - at `820x1180`, clicking `Доп. оборудование` collapses the tablet sidebar;
  - at `820x1180`, clicking `Настройка смет и ремонтов` collapses the tablet sidebar.

## 2026-07-09 Breakpoint Boundary Verification

Implementation note:

- The current compact auto-close logic is boundary-based, not heuristic:
  - mobile `Sheet` close path is used below `768px`;
  - sidebar collapse-on-navigation path is used from `768px` through `1023px`;
  - wide desktop keeps the sidebar open from `1024px` and above.

Verification:

- Boundary browser verification on `http://127.0.0.1:8080/` confirmed:
  - `768px` auto-closes on navigation;
  - `1023px` auto-closes on navigation;
  - `1024px` remains expanded after navigation;
  - `1280px` remains expanded after navigation.
