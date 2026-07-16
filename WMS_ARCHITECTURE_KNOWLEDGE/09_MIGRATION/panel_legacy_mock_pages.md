# Panel Legacy Mock Pages

Date: 2026-07-09.

Scope:

- Added reusable React mock pages under `panel/src/features/legacy-mock`.
- No `wms-panel-old` files were changed.
- No `panel` routing, sidebar, rental item, or equipment files were changed.

Legacy evidence:

- Reservations:
  - `wms-panel-old/src/main/java/dev/buhanzaz/wmspanel/entity/ReservationStatus.java`
  - `wms-panel-old/src/main/java/dev/buhanzaz/wmspanel/view/reservation/ReservationDetailView.java`
  - `wms-panel-old/src/main/resources/dev/buhanzaz/wmspanel/view/reservation/reservation-detail-view.xml`
- Repair estimates:
  - `wms-panel-old/src/main/java/dev/buhanzaz/wmspanel/entity/RepairEstimateStatus.java`
  - `wms-panel-old/src/main/java/dev/buhanzaz/wmspanel/view/repairestimate/RepairEstimateView.java`
- Queue board:
  - `wms-panel-old/src/main/java/dev/buhanzaz/wmspanel/entity/WorkQueueKind.java`
  - `wms-panel-old/src/main/java/dev/buhanzaz/wmspanel/view/queueworkboard/QueueWorkBoardView.java`
- After repair and rework:
  - `wms-panel-old/src/main/java/dev/buhanzaz/wmspanel/entity/RepairProcessStatus.java`
  - `wms-panel-old/src/main/java/dev/buhanzaz/wmspanel/view/afterrepair/AfterRepairView.java`
  - `wms-panel-old/src/main/java/dev/buhanzaz/wmspanel/service/RepairProcessService.java`
  - `wms-panel-old/src/main/java/dev/buhanzaz/wmspanel/service/RepairReworkService.java`
- KPI:
  - `wms-panel-old/src/main/java/dev/buhanzaz/wmspanel/view/kpiandrework/KpiAndReworkView.java`
- Repair catalog:
  - `wms-panel-old/src/main/java/dev/buhanzaz/wmspanel/view/repairestimatecatalogcanvas/RepairEstimateCatalogCanvasView.java`
- AI search:
  - `wms-panel-old/src/main/java/dev/buhanzaz/wmspanel/view/warehouseai/WarehouseAiSearchView.java`

Implemented target artifacts:

- `panel/src/features/legacy-mock/legacy-mock-model.ts`
- `panel/src/features/legacy-mock/legacy-mock-data.ts`
- `panel/src/features/legacy-mock/legacy-mock-ui.tsx`
- `panel/src/features/legacy-mock/legacy-mock-pages.tsx`
- `panel/src/features/legacy-mock/index.ts`

Behavior covered:

- Generic CRUD table for simple legacy dictionaries/settings screens.
- Reservation list/detail mock with legacy status labels and local actions:
  - move temporary/active reservation to waiting payment
  - confirm payment
  - release reservation line
  - add accessory as mock-only UI behavior
  - cancel reservation with reason
  - delete expired reservation
- Repair estimate mock with draft/completed tabs, line list, photo count, save draft, complete, and generated task-plan count.
- Queue board mock with work queues, REAL/SHADOW cards, show-shadow toggle, add queue/task, take, pause, resume, complete, and route-step movement.
- After-repair mock with process dossier summary, accept process, and create rework process.
- KPI/rework mock with worker-class and worker-group filters, history table, planned/actual minutes, photo count, and rework marker.
- Repair catalog mock with category/node/link data, active/include flags, and simple canvas-style node positions.
- AI search mock with token filtering across rental/stock rows and temporary/client reservation creation.

Migration impact:

- These pages are reusable React UI scaffolding only. They are intentionally not wired into `App.tsx` or navigation.
- Mock state is local and reset on reload.
- Backend DTO/API contracts remain `UNKNOWN` until Spring endpoints are derived from legacy services.
- Catalog drag/link validation, stock effects, queue persistence, photo upload/processing, AI provider behavior, and KPI calculations are mock-only approximations unless backed by future Spring service contracts.

Verification:

- Used Context7 official React docs before React changes.
- Used local shadcn skill and `npx shadcn@latest info/docs` before shadcn-based React changes.
- `npx eslint src/features/legacy-mock` passed.
- `npm run lint` passed in `panel`.
- `npm run typecheck` and `npm run build` are currently blocked by pre-existing errors in `panel/src/api/legacy-mock-api.ts`:
  - line 2242: `String` value called as a function
  - line 2242: implicit `any` parameter
  - line 2253: `RentalItemDto.warehouse` does not exist; current type has `warehouseId`

## Superseding Integration Note

Later on 2026-07-09, the main integration pass fixed the blocking `panel/src/api/legacy-mock-api.ts` errors and wired active legacy mock routes through `panel/src/features/legacy-mock/legacy-mock-page.tsx`.

Current verification is documented in `09_MIGRATION/panel_full_legacy_mock_route_integration.md`; `npm run typecheck`, `npm run lint`, and `npm run build` now pass.

The reusable files from this note remain isolated/sandbox artifacts and are not the active route source unless future agents deliberately wire or reconcile them.
