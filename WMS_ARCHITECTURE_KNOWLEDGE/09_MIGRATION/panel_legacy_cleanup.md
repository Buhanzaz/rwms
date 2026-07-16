# Panel Legacy Cleanup

Date: 2026-07-09.

Scope:

- Current React target under `panel`.
- Cleanup requested after the target was renamed back to `panel`.
- `wms-panel-old` remained read-only and was not deleted.

## Findings

- The current filesystem has no active legacy mock route folder or facade:
  - `panel/src/features/legacy-mock` is absent.
  - `panel/src/api/legacy-mock-api.ts` is absent.
  - `panel/src/types/legacy-mock.ts` is absent.
  - `wms-panel` is absent on disk.
- `panel/src/App.tsx` and `panel/src/components/app-sidebar.tsx` no longer route or display full legacy mock screens.
- Old migration notes about legacy mock pages are historical audit context only and do not describe the current active React architecture.

## Cleanup Implemented

- Removed unused rental item mock adapter mutation exports:
  - `createRentalItem(...)`
  - `updateRentalItem(...)`
  - `deleteRentalItem(...)`
  - `uploadRentalItemPhoto(...)`
  - `deleteRentalItemPhoto(...)`
- Removed unused mock storage reset exports:
  - `resetRentalItemsMockStorage()`
  - `resetEquipmentMockStorage()`
  - `resetWarehouseLocationMockStorage()`
- Removed unused rental item model exports:
  - `CreateRentalItemPayload`
  - `RENTAL_ITEMS_DEFAULT_COLUMNS`
- Removed stale IDE project state that referenced the old `wms-panel` name:
  - `panel/.idea/wms-panel.iml`
  - `panel/.idea/modules.xml`
  - `panel/.idea/workspace.xml`
- Kept active adapter surface needed by current UI workflows:
  - rental item list/detail reads,
  - table schema and filter options,
  - photo reads,
  - rental item content movement,
  - cross-feature mock read/write helpers while backend contracts remain incomplete.

## Migration Impact

- Do not recreate the removed full legacy mock route layer unless the user explicitly asks for it.
- Future migration should add target routes from documented Spring/API contracts, not from the removed legacy mock facade.
- `wms-panel-old` remains the source of truth for legacy behavior and must not be changed during cleanup.
- Old `panel_full_legacy_mock_*` notes are superseded for current `panel` code shape, but retained as audit history.

## Verification

- `rg` found no remaining references to deleted API names in `panel/src`.
- `rg` found no `legacy-mock`, `wms-panel`, or `web-panel` references in current `panel` source/package/IDE project files.
- `cd panel && npm run typecheck` passed.
- `cd panel && npm run lint` passed.
- `cd panel && npm run build` passed; only Vite chunk-size warning remained.
