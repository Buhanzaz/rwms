import {
  createRepairEstimateCatalogIndex,
  getOperationalRepairEstimateCatalog,
} from "@/features/repair-estimate-catalog/api/repair-estimate-catalog-api"
import { IndexedDbRepairEstimateMediaAdapter } from "@/features/repair-estimates/adapters/indexed-db-repair-estimate-media-adapter"
import { panelRepairTaskRentalItemsClient } from "@/features/repair-tasks/adapters/panel-repair-task-rental-items-client"
import {
  assertEstimateLinesValid,
  buildRepairEstimateTaskPlans,
  finalizeTaskPlans,
  validateAutoCompletion,
} from "@/features/repair-estimates/domain/repair-estimate-domain"
import type {
  RepairEstimateDto,
  RepairEstimateCompletionMode,
  RepairEstimateMediaRefDto,
  RepairEstimateTaskPlanDto,
  PendingEstimateMediaUpload,
} from "@/features/repair-estimates/model/repair-estimate"
import { LocalStorageRepairTasksAdapter } from "@/features/repair-tasks/adapters/local-storage-repair-tasks-adapter"
import {
  assertRepairTaskCanBeQueued,
  buildDirectRepairTaskSubtasks,
  buildRepairTaskSubtasks,
} from "@/features/repair-tasks/domain/repair-task-domain"
import type {
  RepairTaskDto,
  RepairTaskEditorDraft,
  RepairTaskFromInventoryFindingCommand,
  RepairTaskOrigin,
  RepairTaskSubtaskDto,
  RepairTaskWriteCommand,
} from "@/features/repair-tasks/model/repair-task"
import type { RepairTasksClient } from "@/features/repair-tasks/ports/repair-tasks-client"
import { listRepairWorkerGroups } from "@/features/repair-tasks/api/repair-worker-directory-api"
import type {
  RepairTaskWorkerGroupSnapshotDto,
  RepairTaskWorkerSnapshotDto,
} from "@/features/repair-tasks/model/repair-task"

export const REPAIR_TASKS_QUERY_KEY = ["repair-tasks"] as const

const mediaClient = new IndexedDbRepairEstimateMediaAdapter()
const localRepairTasksAdapter = new LocalStorageRepairTasksAdapter(
  panelRepairTaskRentalItemsClient
)
const repairTasksClient: RepairTasksClient = localRepairTasksAdapter

export function repairTasksListQueryKey(warehouseId: string) {
  return [...REPAIR_TASKS_QUERY_KEY, "list", warehouseId] as const
}

export function repairTaskDetailQueryKey(
  warehouseId: string,
  taskId: string | null
) {
  return [...REPAIR_TASKS_QUERY_KEY, "detail", warehouseId, taskId] as const
}

export function repairTaskBySourceEstimateQueryKey(
  warehouseId: string,
  sourceEstimateId: string | null
) {
  return [
    ...REPAIR_TASKS_QUERY_KEY,
    "source-estimate",
    warehouseId,
    sourceEstimateId,
  ] as const
}

export function repairTaskByInventoryFindingQueryKey(
  warehouseId: string,
  sourceInventoryId: string | null,
  sourceInventoryFindingId: string | null
) {
  return [
    ...REPAIR_TASKS_QUERY_KEY,
    "source-inventory-finding",
    warehouseId,
    sourceInventoryId,
    sourceInventoryFindingId,
  ] as const
}

export function repairAcceptanceListQueryKey(warehouseId: string) {
  return [...REPAIR_TASKS_QUERY_KEY, "acceptance", warehouseId] as const
}

export function repairWriteOffsListQueryKey(warehouseId: string) {
  return [...REPAIR_TASKS_QUERY_KEY, "write-offs", warehouseId] as const
}

export function listRepairTasks(warehouseId: string) {
  return repairTasksClient.list(warehouseId)
}

export function listPendingRepairAcceptance(warehouseId: string) {
  return repairTasksClient.listPendingAcceptance(warehouseId)
}

export function listRepairWriteOffs(warehouseId: string) {
  return repairTasksClient.listWriteOffs(warehouseId)
}

export async function getRepairTask(taskId: string, warehouseId: string) {
  const task = await repairTasksClient.getById(taskId, warehouseId)
  if (!task) {
    return null
  }
  return {
    ...task,
    media: await mediaClient.hydrate(task.media),
    subtasks: await Promise.all(
      task.subtasks.map(async (subtask) => ({
        ...subtask,
        resultMedia: await mediaClient.hydrate(subtask.resultMedia),
      }))
    ),
  }
}

export async function getRepairTaskBySourceEstimateId(
  sourceEstimateId: string,
  warehouseId: string
) {
  const task = await getRepairTaskSnapshotBySourceEstimateId(
    sourceEstimateId,
    warehouseId
  )
  return task ? hydrateCommittedTaskMedia(task) : null
}

export function getRepairTaskSnapshotBySourceEstimateId(
  sourceEstimateId: string,
  warehouseId: string
) {
  return repairTasksClient.getBySourceEstimateId(sourceEstimateId, warehouseId)
}

export async function getRepairTaskByInventoryFinding(
  sourceInventoryId: string,
  sourceInventoryFindingId: string,
  warehouseId: string
) {
  const task = await getRepairTaskSnapshotByInventoryFinding(
    sourceInventoryId,
    sourceInventoryFindingId,
    warehouseId
  )
  return task ? hydrateCommittedTaskMedia(task) : null
}

export function getRepairTaskSnapshotByInventoryFinding(
  sourceInventoryId: string,
  sourceInventoryFindingId: string,
  warehouseId: string
) {
  return repairTasksClient.getByInventoryFinding(
    sourceInventoryId,
    sourceInventoryFindingId,
    warehouseId
  )
}

function buildWriteCommand(params: {
  draft: RepairTaskEditorDraft
  warehouseId: string
  media: RepairEstimateMediaRefDto[]
  subtasks: RepairTaskSubtaskDto[]
}): RepairTaskWriteCommand {
  return {
    taskId: params.draft.taskId,
    expectedVersion: params.draft.expectedVersion,
    kind: params.draft.kind,
    origin: params.draft.origin,
    sourceRepairTaskId: params.draft.sourceRepairTaskId,
    sourceRepairTaskVersion: params.draft.sourceRepairTaskVersion,
    sourceEstimateId: params.draft.sourceEstimateId,
    sourceEstimateVersion: params.draft.sourceEstimateVersion,
    warehouseId: params.warehouseId,
    rentalItemId: params.draft.rentalItemId,
    reason: params.draft.reason,
    dispatchDate: params.draft.dispatchDate,
    comment: params.draft.comment,
    media: mediaClient.dehydrate(params.media),
    subtasks: params.subtasks,
  }
}

async function hydrateCommittedTaskMedia(task: RepairTaskDto) {
  try {
    return {
      ...task,
      media: await mediaClient.hydrate(task.media),
      subtasks: await Promise.all(
        task.subtasks.map(async (subtask) => ({
          ...subtask,
          resultMedia: await mediaClient.hydrate(subtask.resultMedia),
        }))
      ),
    }
  } catch {
    return task
  }
}

async function persistTaskWithMedia(params: {
  draft: RepairTaskEditorDraft
  warehouseId: string
  status: "DRAFT" | "QUEUED"
  taskPlans?: RepairEstimateTaskPlanDto[]
  completionMode?: RepairEstimateCompletionMode
  movementRequired?: boolean
}) {
  assertEstimateLinesValid(params.draft.lines)
  if (params.status === "QUEUED") {
    assertRepairTaskCanBeQueued(params.draft.lines)
  }
  const snapshot = await getOperationalRepairEstimateCatalog()
  const catalog = createRepairEstimateCatalogIndex(snapshot)
  if (params.status === "QUEUED" && params.completionMode === "AUTO") {
    const autoIssues = validateAutoCompletion(params.draft.lines, catalog)
    if (autoIssues.length > 0) {
      throw new Error(autoIssues.slice(0, 3).join("; "))
    }
  }
  const basePlans =
    params.completionMode === "AUTO"
      ? buildRepairEstimateTaskPlans(params.draft.lines, catalog)
      : params.taskPlans
  const taskPlans =
    basePlans && params.completionMode
      ? finalizeTaskPlans({
          plans: basePlans,
          completionMode: params.completionMode,
          movementRequired: Boolean(params.movementRequired),
        })
      : null
  const subtasks = taskPlans
    ? buildRepairTaskSubtasks({
        lines: params.draft.lines,
        plans: taskPlans,
        catalog,
      })
    : buildDirectRepairTaskSubtasks(params.draft.lines, catalog)
  if (
    params.status === "QUEUED" &&
    params.movementRequired &&
    (!subtasks.some((subtask) => subtask.kind === "MOVE_TO_REPAIR") ||
      !subtasks.some((subtask) => subtask.kind === "MOVE_FROM_REPAIR"))
  ) {
    throw new Error("Добавьте оба этапа перемещения в план ремонта")
  }
  const existing = params.draft.taskId
    ? await repairTasksClient.getById(params.draft.taskId, params.warehouseId)
    : null
  const uploaded = await mediaClient.upload(params.draft.pendingUploads)
  const media = [...params.draft.media, ...uploaded]
  let saved: RepairTaskDto

  try {
    const command = buildWriteCommand({ ...params, media, subtasks })
    saved =
      params.status === "QUEUED"
        ? await repairTasksClient.queue(command)
        : await repairTasksClient.saveDraft(command)
  } catch (error) {
    try {
      await mediaClient.discard(uploaded.map((item) => item.id))
    } catch {
      // Preserve the task persistence error; media compensation is best-effort.
    }
    throw error
  }

  const committedIds = new Set(saved.media.map((item) => item.id))
  const removedIds =
    existing?.media
      .map((item) => item.id)
      .filter((id) => !committedIds.has(id)) ?? []
  try {
    await mediaClient.discard(removedIds)
  } catch {
    // Cleanup after the durable task write is best-effort.
  }
  return hydrateCommittedTaskMedia(saved)
}

export function saveRepairTaskDraft(params: {
  draft: RepairTaskEditorDraft
  warehouseId: string
}) {
  return persistTaskWithMedia({ ...params, status: "DRAFT" })
}

export function queueRepairTask(params: {
  draft: RepairTaskEditorDraft
  warehouseId: string
  completionMode: RepairEstimateCompletionMode
  movementRequired: boolean
  taskPlans: RepairEstimateTaskPlanDto[]
}) {
  return persistTaskWithMedia({ ...params, status: "QUEUED" })
}

export function updateRepairTaskSubtasks(params: {
  task: RepairTaskDto
  orderedSubtaskIds: string[]
}) {
  return repairTasksClient.updateSubtasks({
    taskId: params.task.id,
    expectedVersion: params.task.version,
    warehouseId: params.task.warehouseId,
    orderedSubtaskIds: params.orderedSubtaskIds,
  })
}

export function moveRepairTaskEntry(params: {
  task: RepairTaskDto
  subtaskId: string
  targetQueueCode: string | null
  targetRouteQueueKind: RepairTaskSubtaskDto["routeQueueKind"]
  targetQueuePosition: number
}) {
  return repairTasksClient.moveEntry({
    taskId: params.task.id,
    subtaskId: params.subtaskId,
    expectedVersion: params.task.version,
    warehouseId: params.task.warehouseId,
    targetQueueCode: params.targetQueueCode,
    targetRouteQueueKind: params.targetRouteQueueKind,
    targetQueuePosition: params.targetQueuePosition,
  })
}

export async function takeRepairTaskEntry(params: {
  task: RepairTaskDto
  subtaskId: string
  workerGroup: RepairTaskWorkerGroupSnapshotDto
  workers?: RepairTaskWorkerSnapshotDto[]
  accessToken?: string
}) {
  const subtask = params.task.subtasks.find(
    (candidate) => candidate.id === params.subtaskId
  )
  if (!subtask) {
    throw new Error("Подзадание не найдено")
  }
  const groups = await listRepairWorkerGroups(
    {
      warehouseId: params.task.warehouseId,
      queueCode: subtask.queueCode,
      routeQueueKind: subtask.routeQueueKind,
    },
    params.accessToken
  )
  const selectedGroup = groups.find(
    (group) => group.id === params.workerGroup.id && group.active
  )
  if (!selectedGroup) {
    throw new Error("Рабочая группа недоступна для этой очереди")
  }
  const requestedWorkers = params.workers ?? []
  if (requestedWorkers.length > 1) {
    throw new Error("Выберите одного исполнителя или всю рабочую группу")
  }
  const requestedWorker = requestedWorkers[0]
  const directoryWorker = requestedWorker
    ? selectedGroup.members.find((member) => member.id === requestedWorker.id)
    : null
  if (requestedWorker && !directoryWorker) {
    throw new Error("Выбранный исполнитель не состоит в рабочей группе")
  }
  return repairTasksClient.takeEntry({
    taskId: params.task.id,
    subtaskId: params.subtaskId,
    expectedVersion: params.task.version,
    warehouseId: params.task.warehouseId,
    workerGroup: {
      id: selectedGroup.id,
      name: selectedGroup.name,
    },
    workers: directoryWorker ? [directoryWorker] : selectedGroup.members,
  })
}

export function pauseRepairTaskEntry(params: {
  task: RepairTaskDto
  subtaskId: string
}) {
  return repairTasksClient.pauseEntry({
    taskId: params.task.id,
    subtaskId: params.subtaskId,
    expectedVersion: params.task.version,
    warehouseId: params.task.warehouseId,
  })
}

export function resumeRepairTaskEntry(params: {
  task: RepairTaskDto
  subtaskId: string
}) {
  return repairTasksClient.resumeEntry({
    taskId: params.task.id,
    subtaskId: params.subtaskId,
    expectedVersion: params.task.version,
    warehouseId: params.task.warehouseId,
  })
}

export async function completeRepairTaskEntry(params: {
  task: RepairTaskDto
  subtaskId: string
  pendingUploads: PendingEstimateMediaUpload[]
}) {
  if (params.pendingUploads.length > 20) {
    throw new Error("К этапу можно прикрепить не более 20 фотографий")
  }
  const uploaded = await mediaClient.upload(params.pendingUploads)
  try {
    const saved = await repairTasksClient.completeEntry({
      taskId: params.task.id,
      subtaskId: params.subtaskId,
      expectedVersion: params.task.version,
      warehouseId: params.task.warehouseId,
      resultMedia: mediaClient.dehydrate(uploaded),
    })
    return hydrateCommittedTaskMedia(saved)
  } catch (error) {
    try {
      await mediaClient.discard(uploaded.map((media) => media.id))
    } catch {
      // Preserve the task completion error; compensation is best-effort.
    }
    throw error
  }
}

export async function acceptRepairTask(params: {
  task: RepairTaskDto
  comment: string
}) {
  const saved = await repairTasksClient.accept({
    taskId: params.task.id,
    expectedVersion: params.task.version,
    warehouseId: params.task.warehouseId,
    comment: params.comment,
  })
  return hydrateCommittedTaskMedia(saved)
}

export async function writeOffRepairTask(params: {
  task: RepairTaskDto
  reason: string
}) {
  const saved = await repairTasksClient.writeOff({
    taskId: params.task.id,
    expectedVersion: params.task.version,
    warehouseId: params.task.warehouseId,
    reason: params.reason,
  })
  return hydrateCommittedTaskMedia(saved)
}

export async function writeOffRepairDraft(params: {
  warehouseId: string
  origin: RepairTaskOrigin
  taskId: string | null
  expectedVersion: number | null
  rentalItemId: string
  sourceEstimateId: string | null
  sourceEstimateVersion: number | null
  reason: string
  dispatchDate: string | null
  comment: string
  lines: RepairEstimateDto["lines"]
  media: RepairEstimateMediaRefDto[]
  pendingUploads: PendingEstimateMediaUpload[]
  writeOffReason: string
}) {
  const snapshot = await getOperationalRepairEstimateCatalog()
  const catalog = createRepairEstimateCatalogIndex(snapshot)
  const subtasks = buildDirectRepairTaskSubtasks(params.lines, catalog)
  const existing = params.taskId
    ? await repairTasksClient.getById(params.taskId, params.warehouseId)
    : params.sourceEstimateId
      ? await repairTasksClient.getBySourceEstimateId(
          params.sourceEstimateId,
          params.warehouseId
        )
      : null
  const uploaded = await mediaClient.upload(params.pendingUploads)
  const media = [...params.media, ...uploaded]
  let saved: RepairTaskDto

  try {
    saved = await repairTasksClient.earlyWriteOff({
      taskId: params.taskId,
      expectedVersion: params.expectedVersion,
      kind: "REPAIR",
      sourceRepairTaskId: null,
      sourceRepairTaskVersion: null,
      warehouseId: params.warehouseId,
      rentalItemId: params.rentalItemId,
      origin: params.origin,
      sourceEstimateId: params.sourceEstimateId,
      sourceEstimateVersion: params.sourceEstimateVersion,
      reason: params.reason,
      dispatchDate: params.dispatchDate,
      comment: params.comment,
      media: mediaClient.dehydrate(media),
      subtasks,
      writeOffReason: params.writeOffReason,
    })
  } catch (error) {
    try {
      await mediaClient.discard(uploaded.map((item) => item.id))
    } catch {
      // Preserve the write-off error; media compensation is best-effort.
    }
    throw error
  }

  const committedIds = new Set(saved.media.map((item) => item.id))
  const removedIds =
    existing?.media
      .map((item) => item.id)
      .filter((id) => !committedIds.has(id)) ?? []
  try {
    await mediaClient.discard(removedIds)
  } catch {
    // Cleanup after the durable write-off is best-effort.
  }
  return hydrateCommittedTaskMedia(saved)
}

export async function createRepairTaskFromCompletedEstimate(params: {
  estimate: RepairEstimateDto
  taskPlans: RepairEstimateTaskPlanDto[]
  allowWaitingEstimateConfirmation?: boolean
}) {
  const snapshot = await getOperationalRepairEstimateCatalog()
  const catalog = createRepairEstimateCatalogIndex(snapshot)
  return repairTasksClient.upsertFromEstimate({
    warehouseId: params.estimate.warehouseId,
    rentalItemId: params.estimate.rentalItemId,
    cabinNumber: params.estimate.cabinNumber,
    authorName: params.estimate.authorName,
    sourceEstimateId: params.estimate.id,
    sourceEstimateVersion: params.estimate.version,
    allowWaitingEstimateConfirmation: params.allowWaitingEstimateConfirmation,
    reason: "Ремонт по смете",
    dispatchDate: params.estimate.dispatchDate,
    comment: params.estimate.comment,
    media: params.estimate.media.map((media) => structuredClone(media)),
    subtasks: buildRepairTaskSubtasks({
      lines: params.estimate.lines,
      plans: params.taskPlans,
      catalog,
    }),
  })
}

export async function upsertRepairTaskByInventoryFinding(
  command: RepairTaskFromInventoryFindingCommand
) {
  const saved = await repairTasksClient.upsertByInventoryFinding({
    ...command,
    media: mediaClient.dehydrate(command.media),
    subtasks: command.subtasks.map((subtask) => ({
      ...structuredClone(subtask),
      resultMedia: mediaClient.dehydrate(subtask.resultMedia),
    })),
  })
  return hydrateCommittedTaskMedia(saved)
}

export async function syncRepairTaskFromCompletedEstimate(params: {
  estimate: RepairEstimateDto
  taskPlans: RepairEstimateTaskPlanDto[]
  expectedTaskVersion: number | null
}) {
  const snapshot = await getOperationalRepairEstimateCatalog()
  const catalog = createRepairEstimateCatalogIndex(snapshot)
  return repairTasksClient.syncFromEstimate({
    warehouseId: params.estimate.warehouseId,
    rentalItemId: params.estimate.rentalItemId,
    cabinNumber: params.estimate.cabinNumber,
    authorName: params.estimate.authorName,
    sourceEstimateId: params.estimate.id,
    sourceEstimateVersion: params.estimate.version,
    expectedTaskVersion: params.expectedTaskVersion,
    reason: "Ремонт по смете",
    dispatchDate: params.estimate.dispatchDate,
    comment: params.estimate.comment,
    media: params.estimate.media.map((media) => structuredClone(media)),
    subtasks: buildRepairTaskSubtasks({
      lines: params.estimate.lines,
      plans: params.taskPlans,
      catalog,
    }),
  })
}
