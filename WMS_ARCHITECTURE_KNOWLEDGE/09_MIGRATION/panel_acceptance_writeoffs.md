# Panel Repair Acceptance And Write-Offs

## Acceptance Rework Wizard (2026-07-10)

The target replaces the non-mutating `Тест` dialog with a guided flow: preliminary selected-group/worker versus general routing, optional cloning of source work/material lines, and the existing direct-task editor/planner prefilled for the same cabin with reason `Переделка`.

The persisted lifecycle is source-child based. A queued child has `kind=REWORK` and a versioned source reference; its source becomes `IN_REWORK` in the same local-storage mutation and the cabin remains `REPAIR`. Child completion produces a new pending acceptance. Accepting or writing off the child cascades the decision through its source chain; only the terminal written-off leaf is projected in the archive.

Recipient choice is not a durable assignment contract yet. The wizard explicitly describes it as preliminary, and the existing completion planner remains authoritative for stage queues and ordering. This avoids encoding an unproven multi-stage group/worker rule while preserving the requested UX.

## Write-Off Subsections (2026-07-10)

`Списание` is a navigation parent rather than one leaf. Cabin write-offs remain at `/write-offs`; aggregate supplemental-equipment write-offs are exposed at `/write-offs/equipment`.

The equipment view deliberately projects only `writtenOffQuantity > 0` from the existing equipment adapter. It has no create action or event dossier because the current target model does not contain a disposal reason, author, date, or ledger. Adding those fields requires a separate domain/API migration and must not be inferred from the aggregate quantity.

Legacy also proves a separate furniture-material mapping: `AccessoryItem.furnitureMaterial` links one accessory item to one furniture catalog material and synchronizes estimate quantities into rental-item accessory assignments and stock availability on estimate save. The current target does not implement an equivalent contract; its category/name-based mock behavior is not a substitute.

Date: 2026-07-10

## Target Mapping

The React target projects acceptance and write-offs from the versioned `RepairTask` aggregate rather than introducing a second store:

- `COMPLETED + PENDING` -> `/acceptance`;
- acceptance command -> task `ACCEPTED`, rental item `FREE`;
- write-off command with required reason -> task/rental item `WRITTEN_OFF`, visible under `/write-offs`;
- all-stage completion -> rental item `WAITING_REPAIR_CHECK`.

`RepairTask` v2 records source origin, root completion and decision audit fields. Each stage snapshots normative time, photo requirement, group, assignments, active timing, and result media. The local adapter reads schema v1 and normalizes missing fields, infers origin from `sourceEstimateId`, promotes old completed tasks to pending acceptance, and converts the legacy free-text assignee into a compatibility group/assignment.

## UI And Adapter Boundaries

- Estimates and direct repairs reuse `RepairWorkCompletionDialog`; direct repairs do not create an estimate.
- Worker groups are provided through a directory port with a legacy-informed mock adapter. Final server identity/membership contracts remain `UNKNOWN`.
- Media remains behind the existing IndexedDB adapter. Completion dehydrates uploaded references into the stage and discards uploaded media when the repair command fails.
- Acceptance/write-off pages use `OperationsListGrid`, shadcn primitives, and the shared `PhotoCarousel`. The write-off dossier is read-only; the current rework dialog is deliberately a non-mutating `Тест` placeholder.
- Rental statuses `WAITING_REPAIR_CHECK` and `WRITTEN_OFF` are target extensions. Written-off cabins remain in warehouse results but are filtered from new estimate/repair pickers.

## Legacy Relationship

Legacy proves the adjacent repair-process `AFTER_REPAIR`, `ACCEPTED`, and `REWORK` concepts, worker/group/queue relationships, and audit ownership, but it does not prove the exact new target DTO, time-percentage presentation, group-stage photo contract, or terminal cabin `WRITTEN_OFF` status. Those items are explicit target product decisions and must not be back-projected as legacy facts.

Legacy evidence:

- `wms-panel-old/src/main/java/dev/buhanzaz/wmspanel/entity/RepairProcess.java`
- `wms-panel-old/src/main/java/dev/buhanzaz/wmspanel/entity/BoardTask.java`
- `wms-panel-old/src/main/java/dev/buhanzaz/wmspanel/view/queueworkboard/QueueWorkBoardView.java`

Target evidence:

- `panel/src/features/repair-tasks/`
- `panel/src/features/task-board/`
- `panel/src/features/acceptance/`
- `panel/src/features/write-offs/`
- `panel/src/features/rental-items/model/rental-item.ts`

## Verification

- `npm run typecheck`
- `npm run lint`
- `npm run build`
- Browser QA: shared direct-repair planner, two-worker take/finish, board-to-acceptance promotion, acceptance and write-off decisions, direct markers/Back, desktop/tablet/390x844, and zero console errors.

## Early Write-Off From Draft Workspaces

The editable estimate and direct-repair workspaces also terminate the cabin through a dedicated early-write-off command. This is separate from the `COMPLETED/PENDING` acceptance write-off command so the latter retains its stricter lifecycle guard.

- New unsaved estimate: `origin=ESTIMATE`, nullable source identifiers, no fake estimate persistence or source link.
- Saved estimate draft: real estimate id/version snapshot.
- New direct repair: new terminal repair task.
- Saved direct draft: update by the editor-captured task id/version; stale versions fail.
- All paths: required decision reason, available form/line/media snapshot, media compensation, rental `WRITTEN_OFF`, then `/write-offs?writeOffId=<id>`.

Browser verification used separate cabins for estimate and direct-repair paths. It confirmed the required-reason error, retained estimate origin after a direct reload, absence/presence of source links as applicable, picker exclusion, and zero console errors.
