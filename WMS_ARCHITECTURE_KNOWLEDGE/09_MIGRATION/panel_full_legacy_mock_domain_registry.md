# Panel Full Legacy Mock Domain Registry

Date: 2026-07-09.

Scope:

- New mock-domain type and adapter files under `panel/src/types` and `panel/src/api`.
- No React UI, app shell, sidebar, or existing rental item files were changed.
- `wms-panel-old` remained read-only.

## Source Evidence

- Users, warehouses, access:
  - `wms-panel-old/src/main/java/dev/buhanzaz/wmspanel/entity/User.java`
  - `wms-panel-old/src/main/java/dev/buhanzaz/wmspanel/entity/Warehouse.java`
  - `wms-panel-old/src/main/java/dev/buhanzaz/wmspanel/entity/UserWarehouseAccess.java`
  - `wms-panel-old/src/main/java/dev/buhanzaz/wmspanel/service/WarehouseAccessService.java`
- Reservations:
  - `wms-panel-old/src/main/java/dev/buhanzaz/wmspanel/entity/Reservation.java`
  - `wms-panel-old/src/main/java/dev/buhanzaz/wmspanel/service/ReservationService.java`
- Domain inventory, queue, repair, catalog, workers, AI:
  - `WMS_ARCHITECTURE_KNOWLEDGE/04_ENTITIES/README.md`
  - `WMS_ARCHITECTURE_KNOWLEDGE/05_UI/README.md`
  - `WMS_ARCHITECTURE_KNOWLEDGE/06_BUSINESS/README.md`
  - `WMS_ARCHITECTURE_KNOWLEDGE/07_SECURITY/README.md`
  - `WMS_ARCHITECTURE_KNOWLEDGE/08_INTEGRATIONS/README.md`

## Implemented Mock Boundary

- Added `panel/src/types/legacy-mock-domain.ts`.
- Added `panel/src/api/legacy-mock-domain-api.ts`.
- Adapter functions are async and use `localStorage` through `wms:legacy-mock-domain` for mutating workflows.
- The registry intentionally uses its own storage key and does not mutate the existing rental table mock state.

Covered areas:

- Users, global roles, warehouses, warehouse access, effective VIEWER downgrade, current user switching.
- Rental dictionaries, classifier attributes, accessory dictionaries, warehouse segments.
- Rental item references sufficient for cross-domain workflows.
- Accessory stock balances and stock-to-rental-item accessory assignment.
- Reservation availability search, temporary reservation creation, reservation accessory assignment, reservation status transitions.
- Repair catalog nodes/links, repair estimate draft creation, estimate completion into repair process/queue tasks.
- Work queues, workers, worker groups, board tasks, queue entries, take/pause/resume/complete actions with basic REAL/SHADOW promotion.
- After-repair acceptance and rework process creation.
- KPI/rework summary records from mock process/task state.
- Deterministic local AI search over rental items, stock items, and accessories.

## Mock-Only / UNKNOWN

- This is not a final Spring API contract.
- Queue timing, task interruption, route planning, and KPI calculations are simplified mock behavior. Legacy service tests must still drive the real backend contract.
- Accessory stock total invariants remain `UNKNOWN`; mock updates preserve legacy counter names but do not prove a formula.
- AI search uses deterministic local parsing only. Legacy has OpenAI-compatible/Mistral and Spring AI surfaces, but target provider/model remain configurable.
- The mock registry is separate from existing rental table mock data, so cross-screen consistency with current rental UI is not guaranteed until adapters are reconciled.

## Verification

- `npm run typecheck` in `panel` reached only an unrelated pre-existing error in `panel/src/features/legacy-mock/legacy-mock-model.ts` importing `IconSvgObject` from `@hugeicons/react`.
- `npm run lint` in `panel` is blocked by unrelated unused imports in `panel/src/features/legacy-mock/legacy-mock-pages.tsx`.
- `npm run build` in `panel` is blocked by unrelated errors in existing untracked `panel/src/api/legacy-mock-api.ts` plus the `IconSvgObject` issue.
- New files had no reported TypeScript errors after fixing their initial literal-type issues.

## Superseding Integration Note

Later on 2026-07-09, the main integration pass fixed the blocking React/mock API issues and routed the active full legacy mock through:

- `panel/src/types/legacy-mock.ts`
- `panel/src/api/legacy-mock-api.ts`
- `panel/src/features/legacy-mock/legacy-mock-page.tsx`
- `panel/src/App.tsx`
- `panel/src/components/app-sidebar.tsx`

Current verification is documented in `09_MIGRATION/panel_full_legacy_mock_route_integration.md`; `npm run typecheck`, `npm run lint`, and `npm run build` now pass.

This domain registry remains useful evidence/prototype code, but it is not the active route-facing adapter and is not the final Spring API contract.
