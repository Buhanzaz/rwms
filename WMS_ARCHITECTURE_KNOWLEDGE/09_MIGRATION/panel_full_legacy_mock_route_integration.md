# Panel Full Legacy Mock Route Integration

Date: 2026-07-09.

Scope:

- Integrated full legacy menu coverage into `panel` with mock-backed routes.
- `wms-panel-old` remained read-only.
- Existing rental item table UI was preserved; `/warehouse` remains the active rental registry.

## Source Evidence

- Legacy menu:
  - `wms-panel-old/src/main/resources/dev/buhanzaz/wmspanel/menu.xml`
- Project memory:
  - `WMS_ARCHITECTURE_KNOWLEDGE/05_UI/README.md`
  - `WMS_ARCHITECTURE_KNOWLEDGE/06_BUSINESS/README.md`
  - `WMS_ARCHITECTURE_KNOWLEDGE/09_MIGRATION/README.md`
- Subagent findings for UI, business, database/seed, and target `panel` gaps.

## Implemented Active Route Facade

Active files:

- `panel/src/types/legacy-mock.ts`
- `panel/src/api/legacy-mock-api.ts`
- `panel/src/features/legacy-mock/legacy-mock-page.tsx`
- `panel/src/App.tsx`
- `panel/src/components/app-sidebar.tsx`

Behavior:

- Sidebar navigation is generated from a legacy-derived registry aligned with `menu.xml` groups.
- Existing working routes are preserved:
  - `/warehouse` -> existing rental registry.
  - `/warehouse/:rentalItemId` -> existing rental item detail.
  - `/equipment` -> existing equipment/accessory prototype page.
- Legacy route alias:
  - `/rental-items` redirects to `/warehouse`.
- Full mock routes are available for users, warehouses/access, dictionaries, rental attributes/tags, accessory dictionaries, accessory stock balances, stock items, reservations, repair estimates, queue board, after-repair, KPI/rework, repair catalog sections, mobile inventory mock, and AI search.
- Prototype aliases map to legacy-backed mock screens:
  - `/kpi` -> KPI/rework.
  - `/inventory` -> mobile inventory mock.
  - `/estimates` -> repair estimates.
  - `/repairs` -> after-repair.
  - `/task-board` -> queue work board.
  - `/acceptance` -> after-repair.
  - `/settings` -> warehouses/admin mock.

## Mock Adapter Behavior

`panel/src/api/legacy-mock-api.ts` is the route-facing adapter. It uses `localStorage` key `wms:legacy-mock-state` for mutating workflows.

Covered route-facing workflows:

- Dashboard summary across rental items, reservations, repair processes, and board tasks.
- Generic legacy tables for users, warehouses, warehouse access, rental dictionaries, rental attributes, accessories, stock items, workers, queues, and settings.
- Reservation workflows:
  - create temporary reservation
  - move to payment
  - confirm
  - release
  - cancel
  - expire
  - reservation actions update rental item statuses and release accessory reserved counters in the mock.
- Repair estimate workflow:
  - complete draft estimate
  - mark task plans generated
  - create process/board task if absent
  - update rental item status to `WAITING_REPAIR`.
- Queue workflow:
  - start, pause, resume, complete queue entries
  - complete promotes a later `SHADOW` entry to `REAL`
  - final completion marks task done and updates rental item status to `WAITING_REPAIR_CHECK`.
- After-repair workflow:
  - accept process and set item `READY`
  - send process to rework and create child rework process.
- Repair catalog table/canvas sample.
- Deterministic AI search over existing rental mock rows.

## Other Mock Artifacts

Subagents also added:

- `panel/src/types/legacy-mock-domain.ts`
- `panel/src/api/legacy-mock-domain-api.ts`
- `panel/src/features/legacy-mock/legacy-mock-model.ts`
- `panel/src/features/legacy-mock/legacy-mock-data.ts`
- `panel/src/features/legacy-mock/legacy-mock-ui.tsx`
- `panel/src/features/legacy-mock/legacy-mock-pages.tsx`
- `panel/src/features/legacy-mock/index.ts`

These remain isolated/sandbox artifacts and are not the active route source. Future agents should reconcile or remove duplication before treating any mock DTO shape as a backend contract.

## Mock-Only / UNKNOWN

- This is not the final Spring API contract.
- The active route facade and the separate full-domain registry are not yet reconciled into one source of truth.
- Reservation scheduler, payment, and email integrations remain `UNKNOWN`/not implemented.
- Accessory stock total formula remains `UNKNOWN`; mock actions mutate counters for UI testing only.
- Queue worker interruption, exact timers, drag/drop concurrency, and full route planning remain simplified mock behavior.
- Repair catalog graph validation is mock-only.
- AI search is deterministic local parsing; final provider/model remains `UNKNOWN`.
- Mobile API auth remains `UNKNOWN`; legacy public/admin behavior must not be copied.

## Verification

- `npm run typecheck` passed in `panel`.
- `npm run lint` passed in `panel`.
- `npm run build` passed in `panel`; Vite emitted only the existing chunk-size warning.
- Playwright MCP validation on `http://127.0.0.1:8080` confirmed:
  - dashboard and legacy sidebar render
  - `/rental-items` redirects to `/warehouse`
  - reservation creation mutates mock state
  - repair estimate completion generates task plan/process state
  - queue page shows `REAL`/`SHADOW` flow and completion action
  - after-repair accept action reaches `ACCEPTED`
  - AI search returns deterministic result
  - warehouse admin mock table renders
  - old `EmptyPage` placeholder text is no longer visible on tested legacy routes
- After browser validation, `wms:legacy-mock-state` was reset in browser localStorage.
