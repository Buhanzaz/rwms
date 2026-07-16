# Panel Shared List Toolbar

Date: 2026-07-10

## Target UI Decision

- List-page search, tabs, counters, and primary actions use the shared `PageToolbar` composition.
- On desktop, the leading control starts at the page content inset and actions align to the right on the same `36px` row. On narrow screens, the same composition wraps into vertically ordered rows.
- Search inputs use the standard shadcn input height instead of page-specific height overrides.
- The site-header sidebar trigger has no negative horizontal margin. Its divider uses the standard compact vertical height, keeping the trigger aligned to the routed page inset.
- The expanded desktop warehouse selector uses the same `24px` post-header top inset as routed page content, so its control aligns vertically with the shared list toolbar.

## Active Target Evidence

- `panel/src/components/page-toolbar.tsx`
- `panel/src/components/site-header.tsx`
- `panel/src/components/app-sidebar.tsx`
- `panel/src/features/rental-items/rental-items-page.tsx`
- `panel/src/features/equipment/equipment-page.tsx`
- `panel/src/features/repair-estimates/repair-estimates-page.tsx`
- `panel/src/features/repairs/repairs-page.tsx`
- `panel/src/features/task-board/task-board-page.tsx`
- `panel/src/features/write-offs/equipment-write-offs-page.tsx`

## Verification

- `npm run typecheck` passed before concurrent repair/rework model changes made the workspace build incomplete.
- `npm run lint` passed after the toolbar changes.
- Playwright at `1440x900` confirmed the toolbar begins at `x=312`, uses `y=80` and `height=36` across the affected list pages; search/actions share the same row where both exist.
- Browser console reported no errors on the validated pages.
- Full `npm run build` is currently blocked by concurrent, unrelated incomplete `IN_REWORK`/`RepairTaskDto` changes in the working tree.
- Follow-up Playwright geometry checks confirmed the warehouse combobox and routed toolbar controls share the same top coordinate and `36px` height.

## Equipment Table Row Spacing Follow-Up

- The expandable equipment table now owns one divider on each row wrapper, matching the visual rhythm of the other list tables.
- Flex-grid cells retain the shared `12px` horizontal and `8px` vertical padding but no longer add a second bottom border beneath the row divider.
- Target evidence: `panel/src/components/grid-sort-button.tsx`, `panel/src/features/equipment/equipment-page.tsx`.

## Header And Logistics Registry Alignment (2026-07-11)

- The header and routed-content inset are one layout contract: `24px` at desktop and `16px` at narrow widths. Do not compensate with route-local margins or alter registry widths.
- The sidebar trigger remains a 32px shadcn Button target. Its icon is layout-aligned to the start edge, so the visual icon, toolbar, and logistics `OperationsListGrid` share one left edge while accessibility and hit-area size remain unchanged.

Evidence:

- `panel/src/App.tsx`
- `panel/src/components/site-header.tsx`
- Playwright geometry checks on `/logistics/returns` and `/logistics/shipments` at `1280x720`.
