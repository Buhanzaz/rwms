import {
  createRepairEstimateCatalogIndex,
  getOperationalRepairEstimateCatalog,
} from "@/features/repair-estimate-catalog/api/repair-estimate-catalog-api"
import {
  buildRepairEstimateTaskPlans,
  finalizeTaskPlans,
  validateAutoCompletion,
} from "@/features/repair-estimates/domain/repair-estimate-domain"
import type {
  EstimateRentalItemSearchQuery,
  PendingEstimateMediaUpload,
  RepairEstimateCompletionMode,
  RepairEstimateDto,
  RepairEstimateMediaRefDto,
  RepairPriority,
  RepairEstimateTaskPlanDto,
} from "@/features/repair-estimates/model/repair-estimate"
import { HttpMaintenanceRepairTasksAdapter } from "@/features/repair-tasks/adapters/http-maintenance-repair-tasks-adapter"
import { panelRepairTaskRentalItemsClient } from "@/features/repair-tasks/adapters/panel-repair-task-rental-items-client"
import {
  assertRepairTaskCanBeQueued,
  buildDirectRepairTaskSubtasks,
  buildRepairTaskSubtasks,
} from "@/features/repair-tasks/domain/repair-task-domain"
import type {
  RepairTaskDto,
  RepairTaskEditorDraft,
  RepairTaskOrigin,
  RepairTaskSubtaskDto,
  RepairTaskWriteCommand,
} from "@/features/repair-tasks/model/repair-task"
import type { RepairTasksClient } from "@/features/repair-tasks/ports/repair-tasks-client"

export const REPAIR_TASKS_QUERY_KEY = ["repair-tasks"] as const
export const REPAIR_TASK_RENTAL_ITEMS_QUERY_KEY = [
  "repair-tasks",
  "rental-items",
] as const

const repairTasksClient: RepairTasksClient =
  new HttpMaintenanceRepairTasksAdapter(panelRepairTaskRentalItemsClient)

export function repairTasksListQueryKey(warehouseId: string) {
  return [...REPAIR_TASKS_QUERY_KEY, "list", warehouseId] as const
}

export function repairTaskDetailQueryKey(
  warehouseId: string,
  taskId: string | null
) {
  return [...REPAIR_TASKS_QUERY_KEY, "detail", warehouseId, taskId] as const
}

export function searchRepairTaskRentalItems(
  query: EstimateRentalItemSearchQuery
) {
  return panelRepairTaskRentalItemsClient.search(query)
}

export function resolveRepairTaskRentalItem(
  warehouseId: string,
  rentalItemId: string
) {
  return panelRepairTaskRentalItemsClient.resolveById(warehouseId, rentalItemId)
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

export function getRepairTask(taskId: string, warehouseId: string) {
  return repairTasksClient.getById(taskId, warehouseId)
}

export function getRepairTaskBySourceEstimateId(
  sourceEstimateId: string,
  warehouseId: string
) {
  return repairTasksClient.getBySourceEstimateId(sourceEstimateId, warehouseId)
}

export function getRepairTaskSnapshotBySourceEstimateId(
  sourceEstimateId: string,
  warehouseId: string
) {
  return repairTasksClient.getBySourceEstimateId(sourceEstimateId, warehouseId)
}

function buildWriteCommand(params: {
  draft: RepairTaskEditorDraft
  warehouseId: string
  subtasks: RepairTaskSubtaskDto[]
  priority?: RepairPriority
}): RepairTaskWriteCommand {
  if (params.draft.pendingUploads.length > 0 || params.draft.media.length > 0) {
    throw new Error(
      "Локальные вложения старой панели нельзя сохранить. Загрузите фотографии через media-service."
    )
  }
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
    comment: "",
    media: [],
    maintenanceMediaReferences: params.draft.maintenanceMediaReferences,
    coverMediaId: params.draft.coverMediaId,
    subtasks: params.subtasks,
    priority: params.priority,
  }
}

async function planSubtasks(params: {
  draft: RepairTaskEditorDraft
  warehouseId: string
  status: "DRAFT" | "QUEUED"
  taskPlans?: RepairEstimateTaskPlanDto[]
  completionMode?: RepairEstimateCompletionMode
  movementRequired?: boolean
  priority?: RepairPriority
}) {
  const existing = params.draft.taskId
    ? await repairTasksClient.getById(params.draft.taskId, params.warehouseId)
    : params.draft.kind === "REWORK" && params.draft.sourceRepairTaskId
      ? await repairTasksClient.getById(
          params.draft.sourceRepairTaskId,
          params.warehouseId
        )
      : null
  if (params.draft.lines.length === 0 && existing?.subtasks.length) {
    if (params.taskPlans?.length) {
      const existingById = new Map(
        existing.subtasks.map((subtask) => [subtask.id, subtask])
      )
      return params.taskPlans
        .slice()
        .sort((left, right) => left.sortOrder - right.sortOrder)
        .map((plan, index): RepairTaskSubtaskDto => {
          const current = existingById.get(plan.id)
          return {
            id: plan.id,
            externalTaskId: current?.externalTaskId ?? null,
            kind: plan.kind,
            status: current?.status ?? "WAITING",
            workLines: current?.workLines ?? [],
            materialLines: current?.materialLines ?? [],
            groupComment: plan.groupComment,
            queueId: plan.queueId ?? null,
            queueName: plan.queueName,
            routeQueueKind: plan.routeQueueKind,
            sortOrder: index,
            queuePosition: index,
            plannedDurationMinutes: current?.plannedDurationMinutes ?? null,
            startedAt: current?.startedAt ?? null,
            completedAt: current?.completedAt ?? null,
            activeStartedAt: current?.activeStartedAt ?? null,
            activeWorkSeconds: current?.activeWorkSeconds ?? 0,
            workerGroup: current?.workerGroup ?? null,
            assignments: current?.assignments ?? [],
          }
        })
    }
    return existing.subtasks.map((subtask) => ({ ...subtask }))
  }
  if (params.status === "QUEUED") {
    assertRepairTaskCanBeQueued(params.draft.lines)
  }
  const snapshot = await getOperationalRepairEstimateCatalog()
  const catalog = createRepairEstimateCatalogIndex(snapshot)
  if (params.status === "QUEUED" && params.completionMode === "AUTO") {
    const issues = validateAutoCompletion(params.draft.lines, catalog)
    if (issues.length > 0) throw new Error(issues.slice(0, 3).join("; "))
  }
  const basePlans =
    params.completionMode === "AUTO"
      ? buildRepairEstimateTaskPlans(params.draft.lines, catalog)
      : params.taskPlans
  const plans =
    basePlans && params.completionMode
      ? finalizeTaskPlans({
          plans: basePlans,
          completionMode: params.completionMode,
          movementRequired: Boolean(params.movementRequired),
        })
      : null
  return plans
    ? buildRepairTaskSubtasks({
        lines: params.draft.lines,
        plans,
        catalog,
      })
    : buildDirectRepairTaskSubtasks(params.draft.lines, catalog)
}

async function persistRepair(params: {
  draft: RepairTaskEditorDraft
  warehouseId: string
  status: "DRAFT" | "QUEUED"
  taskPlans?: RepairEstimateTaskPlanDto[]
  completionMode?: RepairEstimateCompletionMode
  movementRequired?: boolean
  priority?: RepairPriority
}) {
  const subtasks = await planSubtasks(params)
  const command = buildWriteCommand({ ...params, subtasks })
  return params.status === "QUEUED"
    ? repairTasksClient.queue(command)
    : repairTasksClient.saveDraft(command)
}

export function saveRepairTaskDraft(params: {
  draft: RepairTaskEditorDraft
  warehouseId: string
}) {
  return persistRepair({ ...params, status: "DRAFT" })
}

export function queueRepairTask(params: {
  draft: RepairTaskEditorDraft
  warehouseId: string
  completionMode: RepairEstimateCompletionMode
  movementRequired: boolean
  taskPlans: RepairEstimateTaskPlanDto[]
  priority: RepairPriority
}) {
  return persistRepair({ ...params, status: "QUEUED" })
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

export function acceptRepairTask(params: {
  task: RepairTaskDto
  comment: string
  maintenanceMediaReferences?: Array<{ mediaId: string; generation: number }>
}) {
  return repairTasksClient.accept({
    taskId: params.task.id,
    expectedVersion: params.task.version,
    warehouseId: params.task.warehouseId,
    comment: params.comment,
    maintenanceMediaReferences: params.maintenanceMediaReferences ?? [],
  })
}

export function writeOffRepairTask(params: {
  task: RepairTaskDto
  reason: string
}) {
  return repairTasksClient.writeOff({
    taskId: params.task.id,
    expectedVersion: params.task.version,
    warehouseId: params.task.warehouseId,
    reason: params.reason,
  })
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
  maintenanceMediaReferences: Array<{ mediaId: string; generation: number }>
  coverMediaId?: string | null
  pendingUploads: PendingEstimateMediaUpload[]
  writeOffReason: string
}) {
  if (params.origin === "ESTIMATE" && params.taskId === null) {
    throw new Error(
      "Списание из незавершённой сметы не определено maintenance-контрактом. Сначала завершите смету."
    )
  }
  const draft: RepairTaskEditorDraft = {
    taskId: params.taskId,
    expectedVersion: params.expectedVersion,
    kind: "REPAIR",
    origin: params.origin,
    sourceRepairTaskId: null,
    sourceRepairTaskVersion: null,
    sourceEstimateId: params.sourceEstimateId,
    sourceEstimateVersion: params.sourceEstimateVersion,
    rentalItemId: params.rentalItemId,
    reason: params.reason,
    dispatchDate: params.dispatchDate,
    comment: "",
    lines: params.lines,
    media: params.media,
    maintenanceMediaReferences: params.maintenanceMediaReferences,
    coverMediaId: params.coverMediaId ?? null,
    pendingUploads: params.pendingUploads,
  }
  const subtasks = await planSubtasks({
    draft,
    warehouseId: params.warehouseId,
    status: "DRAFT",
  })
  return repairTasksClient.earlyWriteOff({
    ...buildWriteCommand({ draft, warehouseId: params.warehouseId, subtasks }),
    writeOffReason: params.writeOffReason,
  })
}
