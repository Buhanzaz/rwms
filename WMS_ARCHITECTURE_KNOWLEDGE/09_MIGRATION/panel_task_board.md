# Panel Operational Task Board

Date: 2026-07-10

## Legacy Source Of Truth

- `QueueWorkBoardView` builds the entire board programmatically: warehouse/SHADOW toolbar, horizontal fixed-width queue columns, column actions, entry cards, take dialog, route editor, photos, and dossier links.
- `QueueBoardService` creates a REAL first route entry and SHADOW future entries, takes the first REAL+WAITING entry by queue position, supports pause/resume/complete, accumulates active time, promotes the next stage, and implements cross-queue REAL/future-SHADOW swap.
- `QueueBoardServiceTest` proves first REAL/future SHADOW, promotion, process blockers, route reorder, HOLDING behavior, and worker interruption. Manual pause/resume, general DnD, cancellation, and concurrency are incompletely covered.

Evidence:

- `wms-panel-old/src/main/java/dev/buhanzaz/wmspanel/view/queueworkboard/QueueWorkBoardView.java`
- `wms-panel-old/src/main/java/dev/buhanzaz/wmspanel/service/QueueBoardService.java`
- `wms-panel-old/src/main/java/dev/buhanzaz/wmspanel/entity/QueueEntry.java`
- `wms-panel-old/src/test/java/dev/buhanzaz/wmspanel/service/QueueBoardServiceTest.java`

## Current Target Boundary

- `/repairs` owns `RepairTaskDto`; `/task-board` projects its subtasks as operational entries. There is no second task store.
- Subtask route order and queue order are separate: `sortOrder` versus `queuePosition`.
- Execution metadata is stored compatibly on subtasks: `PAUSED`, `activeStartedAt`, `activeWorkSeconds`, and nullable `assigneeName`. Old localStorage records receive defaults during read normalization.
- Queue columns derive from catalog `workQueueCode`, synthetic movement/route fallbacks, and an explicit unassigned fallback. This is a mock projection, not the final queue registry.
- REAL/SHADOW is derived from current unfinished route order and remains independent of execution status.

## Command And Concurrency Boundary

- Move, take, pause, resume, and complete commands are methods of the existing `RepairTasksClient`/local adapter.
- Every mutation rereads under runtime serialization plus the origin Web Lock where supported and compares `expectedVersion` before state changes.
- Take recomputes the full active warehouse/queue projection and rejects any entry below the first REAL+WAITING position.
- Cross-queue duplicate handling swaps current/future route roles only for a valid REAL WAITING to future WAITING case; other unfinished duplicates reject.
- Read normalization does not globally renumber queue positions, because changing unrelated tasks without their version increments would violate CAS.

## React And DnD Boundary

- `TaskBoardPage` owns one `DndContext`; every queue owns a `SortableContext`; empty expanded queues remain droppable.
- Empty queue columns render no per-column placeholder Card. The `useDroppable` section is retained, so this visual simplification does not remove an empty queue's drop target.
- Pointer/touch collision uses `pointerWithin` with the active sortable entry removed. Leaving all board droppables therefore yields no collision and cancels the move. Keyboard uses `closestCenter`.
- Preview is recomputed idempotently from an immutable drag baseline. Pointer position determines before/after; queue surface means append. Exactly one CAS command is sent on drag end.
- Mutation errors restore the query snapshot and invalidate both task-board and repair-task query families.
- One page clock updates all active elapsed-time displays; cards do not own intervals.
- The `Неактивные` toolbar button is local React display state over existing SHADOW entries. It must not alter task state, route order, queue order, or issue a backend command. When enabled, SHADOW cards remain subject to their existing DnD rules and are merely rendered with the muted translucent presentation.
- The button's unpressed outline state has a transparent fill; its pressed state keeps the standard primary/default fill. This style switch is local presentation and does not change SHADOW visibility semantics.
- Keep SHADOW card presentation in one shared class for both the in-column card and its drag preview, including the dashed, muted, translucent, elevated treatment. It does not change the legacy-derived queue route or DnD semantics.
- The toolbar counters are outside the horizontally scrolling queue container. Their right alignment is presentation-only and must not couple count display to queue-scroll position.

## Reuse

- `Создать задание` navigates to `/repairs?create=1`.
- Card details navigate to `/repairs?repairId=...`, reusing the photo/information/subtask presentation and line-level comments.
- Completed-estimate amendment overlays linked-task routing and preserves matching queue position/execution metadata, so a permitted amendment does not silently revert board work.

## Verification

- Build: typecheck, full ESLint, Vite production build, targeted Prettier check, and diff check.
- Browser: desktop `1440x900`, mobile `390x844`, SHADOW/search, create/detail links, live timer, take/pause/resume/complete, route promotion, cross-queue persistence, outside-drop cancellation, queue-order API rejection, safe duplicate-route swap, and console errors.

## Backend Migration Requirements

- Replace the current projection with explicit Spring board snapshot and command DTOs carrying entry/task versions or a board/queue revision.
- Enforce warehouse access, queue registry membership, worker/group eligibility, REAL/SHADOW blockers, queue position, duplicate routes, and terminal states server-side.
- Return conflict responses that allow React to roll back optimistic state and refetch.
- Keep planned time, worker events, interruption relations, HOLDING thresholds, photos, and live updates outside generic DnD commands.

## UNKNOWN

- Final queue registry/settings API and persistence.
- Final worker/group/current-user assignment contract.
- Planned-time/progress and task-time event API.
- Worker interruption semantics and background HOLDING notifications.
- Server push/polling strategy and multi-operator presence.
- Integration of standalone non-repair legacy board tasks.

## Implemented Service And Settings Boundary (2026-07-11)

The previous UNKNOWNs about the queue registry, workforce directory, optimistic HTTP contract, and `stopTaskOnTake` service behavior are resolved for the new `task-board-service`:

- `/api/warehouses/{warehouseId}/work-queues`, `/work-queue-order`, `/workers`, `/worker-groups`, `/task-board`, and `/api/worker-classes` are versioned Bearer APIs.
- HOLDING queues are normalized to the end; a pending HOLDING stage hides later SHADOW stages even when inactive stages are requested.
- Direct workers and groups are validated against queue class bindings. `stopTaskOnTake` creates explicit automatic-interruption links and resumes only work that was automatically interrupted.
- Worker/group names are snapshotted into assignments/history. The board always includes the virtual `Без очереди` column.
- Queue, class, group, and worker deletes are fail-closed when referenced. Worker deletion first reconciles the separate auth credential.
- `/settings/task-board` now uses real HTTP adapters and five tabs: queues, order, classes, groups, and workers. It reuses `OperationsListGrid`, shadcn Field/AlertDialog, sonner, dnd-kit, and optimistic 409 refetch behavior.

The existing operational `panel/src/features/task-board` still projects the browser repair-task aggregate and has not yet been replaced by the service HTTP snapshot. That final operational-board cutover remains `UNKNOWN`; the new settings screen is real-service-backed.

Evidence:

- `services/task-board-service/src/main/java/dev/buhanzaz/rwms/taskboard/`
- `services/task-board-service/src/test/java/dev/buhanzaz/rwms/taskboard/TaskBoardServiceIntegrationTest.java`
- `panel/src/features/settings/task-board/`

## Responsive Board Scrolling And Actions (2026-07-11)

- Superseded: the earlier target kept the legacy horizontal fixed-width queue model on phones. The approved current target stacks full-width queue columns vertically on phone viewports, including the shared short coarse-pointer landscape classification.
- A mobile queue grows naturally for zero to three cards. Mobile cards have a stable `320px` minimum height; after three complete cards the queue card list scrolls vertically. Empty queues stay header-height, and collapsed queues become full-width horizontal rows.
- Tablet and desktop retain fixed-width horizontal queue columns and independent vertical card scrolling. The board uses free horizontal scrolling without mandatory CSS scroll snapping, so the first queue remains at the left edge after the scrollbar returns to zero.
- On phone-sized viewports, `Создать задание` and `Неактивные` form one two-column row with equal `36px` heights and equal widths. The counters occupy their own subsequent row.

Evidence:

- Legacy: `wms-panel-old/src/main/java/dev/buhanzaz/wmspanel/view/queueworkboard/QueueWorkBoardView.java:163-203` configures a full-height horizontal board with `overflow-x: auto`, `360px` queue columns, and vertically scrollable cards.
- Target: `panel/src/features/task-board/task-board-page.tsx`
- Target: `panel/src/features/task-board/task-board-column.tsx`

Verification:

- Playwright at `390x844` measured equal `358px` mobile board `clientWidth`/`scrollWidth`, a vertical queue track, four `320px` test cards, and exactly three complete cards visible inside the capped populated queue; the fourth was reachable at card-list `scrollTop=332`.
- Playwright at `768x900` and `1440x900` measured horizontal queue tracks, `overflow-x: auto`, `scroll-snap-type: none`, and the first queue at a `1px` inset from the queue viewport after returning `scrollLeft` to zero.
- The two mobile action controls each measured `175×36px`.

## Unified Development Browser Runtime (2026-07-12)

Supersede only the development-runtime statements above: when the explicit
development auth bypass is enabled, both settings and the operational board now
select the unified `BrowserTaskBoardClient`. Production still selects the Bearer
HTTP settings/runtime clients and remains fail-closed. This does not claim that
the browser envelope or the new scheduling behavior exists in
`task-board-service`.

- The mock owns queues/order, workforce, schedules, tasks/routes/assignments,
  independent pause reasons, interruption links, notifications, and its
  simulation clock in one schema-versioned envelope.
- Repair and logistics producers use stable `externalTaskId` registration.
  Repair routes additionally reconcile by stable source subtask ID so completion
  and future amendments preserve the locked execution prefix.
- `/settings/task-board` has six tabs in development. `График` edits linked
  weekly group schedules and return grace and exposes MOCK clock controls.
- `/task-board` reads queue visibility/collapse/order/class bindings from the same
  envelope, preserves `Без очереди`, and exposes the MOCK worker inbox.
- Movement interruption uses explicit links and `INTERRUPTION` pause reasons.
  After movement completes, interrupted assignments wait in `RETURNING` for the
  group's grace period; schedule and manual pause causes continue to compose.
- Seeded UUIDs and DEMO tasks are browser scaffolding only. Seed merges absent
  records and does not overwrite operator changes or create cabin history.

Detailed target/migration note:

- `panel_mock_task_board_schedules.md`

Evidence:

- `panel/src/features/task-board/mock/`
- `panel/src/features/task-board/api/task-board-api.ts`
- `panel/src/features/settings/task-board/`
- `panel/src/features/task-board/task-board-page.tsx`
- `panel/src/features/repair-tasks/api/repair-tasks-api.ts`
- `panel/src/features/logistics/adapters/browser-logistics-preparation-task-client.ts`

Remaining production boundary:

- Service scheduling, time authority, delivery/push, final schedule and
  interruption HTTP contracts, and cross-service outbox/reconciliation remain
  `UNKNOWN`.
