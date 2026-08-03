import {
  createRepairEstimateCatalogIndex,
  getOperationalRepairEstimateCatalog,
  type RepairEstimateCatalogIndex,
} from "@/features/repair-estimate-catalog/api/repair-estimate-catalog-api"
import type { RepairEstimateCatalogSnapshotDto } from "@/features/repair-estimate-catalog/model/repair-estimate-catalog"
import {
  acceptMaintenanceRepair,
  createDirectMaintenanceRepair,
  createMaintenanceIdempotencyKey,
  createMaintenanceRework,
  getMaintenanceRepair,
  listMaintenanceAcceptance,
  listMaintenanceRepairs,
  listMaintenanceWriteOffs,
  queueMaintenanceRepair,
  replaceMaintenanceRepairPlan,
  writeOffMaintenanceRepair,
  type MaintenanceAcceptanceProjection,
  type MaintenanceEstimateLine,
  type MaintenanceEstimateLineInput,
  type MaintenancePlanStageInput,
  type MaintenanceRepair,
  type MaintenanceRepairCommandResult,
  type MaintenanceRepairStage,
  type MaintenanceReworkLineInput,
  type MaintenanceRoutingSnapshot,
  type MaintenanceWriteOffProjection,
} from "@/features/repair-estimates/api/http-maintenance-lifecycle-client"
import {
  currentMaintenanceAccessToken,
  type MaintenanceAccessTokenProvider,
} from "@/features/repair-estimates/api/maintenance-auth"
import type { RepairEstimateLineDto } from "@/features/repair-estimates/model/repair-estimate"
import type {
  RepairTaskAcceptCommand,
  RepairTaskDto,
  RepairTaskSubtaskDto,
  RepairTaskWriteCommand,
} from "@/features/repair-tasks/model/repair-task"
import type { RepairTaskRentalItemsClient } from "@/features/repair-tasks/ports/repair-task-rental-items-client"
import {
  RepairTaskQueueDraftPersistedError,
  type RepairTasksClient,
} from "@/features/repair-tasks/ports/repair-tasks-client"
import { taskBoardSettingsClient } from "@/features/settings/task-board/api/task-board-settings-api"
import type { WorkQueueDto } from "@/features/settings/task-board/model/task-board-settings"
import { getTaskBoard } from "@/features/task-board/api/task-board-api"
import type {
  TaskBoardEntryDto,
  TaskBoardSnapshotDto,
} from "@/features/task-board/model/task-board"
import { ApiError } from "@/lib/api-client"

function repairPriority(value: number) {
  if (!Number.isInteger(value) || value < 1 || value > 5) {
    throw new Error("Сервис ремонтов вернул некорректный приоритет.")
  }
  return value as 1 | 2 | 3 | 4 | 5
}

function statusForStage(
  stage: MaintenanceRepairStage,
  entry: TaskBoardEntryDto | null
): RepairTaskSubtaskDto["status"] {
  if (entry) return entry.status
  if (stage.state === "IN_PROGRESS") return "IN_PROGRESS"
  if (stage.state === "DONE") return "DONE"
  if (stage.state === "CANCELLED") return "CANCELLED"
  return "WAITING"
}

function entriesByExternalTaskId(board: TaskBoardSnapshotDto | null) {
  return new Map(
    (board?.queues.flatMap((queue) => queue.entries) ?? [])
      .filter((entry) => entry.externalTaskId)
      .map((entry) => [`${entry.externalTaskId!}:${entry.routeIndex}`, entry])
  )
}

function stageStart(entry: TaskBoardEntryDto | null) {
  const values = [
    entry?.activeStartedAt,
    ...(entry?.assignments.map((assignment) => assignment.startedAt) ?? []),
  ].filter((value): value is string => Boolean(value))
  return values.sort()[0] ?? null
}

function toRepairEstimateLine(
  line: MaintenanceEstimateLine,
  customQueueBinding: RepairEstimateLineDto["customQueueBinding"] = null
): RepairEstimateLineDto {
  const quantity = Number(line.quantity)
  const rework =
    line.disposition && line.lineageRootLineId
      ? {
          disposition: line.disposition,
          sourceRepairId: line.sourceRepairId ?? null,
          sourceLineId: line.sourceLineId ?? null,
          lineageRootLineId: line.lineageRootLineId,
        }
      : null
  return {
    id: line.id,
    sourceLineKey: line.id,
    lineType: line.lineType,
    description: line.description,
    lineComment: line.lineType === "WORK" ? (line.comment ?? "") : "",
    unit: line.unit ?? "",
    quantity: Number.isFinite(quantity) ? quantity : 0,
    normativeMinutes: line.normativeMinutes,
    unitPrice: line.unitPrice,
    lineTotal: line.lineTotal,
    catalogSnapshot: line.catalogSnapshot
      ? {
          nodeId: line.catalogSnapshot.nodeId,
          name: line.catalogSnapshot.name,
          nodeType:
            line.catalogSnapshot.nodeType === "WORK"
              ? "WORK"
              : line.catalogSnapshot.nodeType === "OPTION"
                ? "OPTION"
                : "MATERIAL",
          furnitureEquipment: line.catalogSnapshot.furnitureEquipment ?? null,
          characteristic: line.catalogSnapshot.characteristic ?? null,
        }
      : null,
    customQueueBinding: line.catalogSnapshot ? null : customQueueBinding,
    maintenanceMediaReferences: [...line.mediaReferences],
    rework,
  }
}

function customQueueBindingFromStage(
  stage: MaintenanceRepairStage
): NonNullable<RepairEstimateLineDto["customQueueBinding"]> | null {
  if (
    stage.kind !== "REPAIR_WORK" ||
    !stage.routing.queueId.trim() ||
    !stage.routing.queueName.trim() ||
    (stage.routing.queueType !== "REPAIR" &&
      stage.routing.queueType !== "HOLDING")
  ) {
    return null
  }
  return {
    queueId: stage.routing.queueId,
    queueName: stage.routing.queueName,
    queueKind: stage.routing.queueType,
  }
}

function plannedDurationFromWorkLines(lines: RepairEstimateLineDto[]) {
  if (lines.length === 0) return null
  let totalMinutes = 0
  for (const line of lines) {
    if (
      typeof line.normativeMinutes !== "number" ||
      !Number.isFinite(line.normativeMinutes) ||
      line.normativeMinutes < 0 ||
      !Number.isFinite(line.quantity) ||
      line.quantity <= 0
    ) {
      return null
    }
    totalMinutes += line.normativeMinutes * line.quantity
  }
  return Number.isSafeInteger(Math.ceil(totalMinutes))
    ? Math.ceil(totalMinutes)
    : null
}

function toSubtask(
  stage: MaintenanceRepairStage,
  entry: TaskBoardEntryDto | null
): RepairTaskSubtaskDto {
  const groupIds = new Set(
    entry?.assignments
      .map((assignment) => assignment.workerGroupId)
      .filter((value): value is string => Boolean(value)) ?? []
  )
  const group =
    groupIds.size === 1
      ? entry?.assignments.find(
          (assignment) => assignment.workerGroupId === [...groupIds][0]
        )
      : null
  const customQueueBinding = customQueueBindingFromStage(stage)
  const workLines = stage.workLines.map((line) =>
    toRepairEstimateLine(line, customQueueBinding)
  )
  const primaryLineIndex = stage.primaryLineId
    ? workLines.findIndex((line) => line.id === stage.primaryLineId)
    : -1
  if (primaryLineIndex > 0) {
    workLines.unshift(...workLines.splice(primaryLineIndex, 1))
  }
  const maintenancePlannedDurationMinutes =
    plannedDurationFromWorkLines(workLines)
  return {
    id: stage.id,
    taskBoardEntryId: entry?.id ?? stage.taskSync.taskBoardEntryId,
    externalTaskId: stage.taskSync.externalTaskId,
    taskTitle: entry?.title ?? null,
    taskText: entry?.taskText ?? null,
    kind: stage.kind,
    status: statusForStage(stage, entry),
    workLines,
    materialLines: stage.materialLines.map((line) =>
      toRepairEstimateLine(line)
    ),
    primaryLineId: stage.primaryLineId,
    groupComment: stage.groupComment,
    evidence: stage.evidence.map((evidence) => {
      const workerAssignment = entry?.assignments.find(
        (assignment) => assignment.workerId === evidence.workerId
      )
      const groupAssignment = evidence.workerGroupId
        ? workerAssignment?.workerGroupId === evidence.workerGroupId
          ? workerAssignment
          : entry?.assignments.find(
              (assignment) =>
                assignment.workerGroupId === evidence.workerGroupId
            )
        : null
      return {
        evidenceId: evidence.evidenceId,
        entryId: evidence.entryId,
        workerId: evidence.workerId,
        workerDisplayName: workerAssignment?.workerName ?? null,
        workerGroupId: evidence.workerGroupId,
        workerGroupName: groupAssignment?.workerGroupName ?? null,
        mediaId: evidence.mediaId,
        mediaGeneration: evidence.mediaGeneration,
        capturedAt: evidence.capturedAt,
        recordedAt: evidence.recordedAt,
        state: evidence.state,
      }
    }),
    queueId: stage.routing.queueId,
    queueName: stage.routing.queueName,
    routeQueueKind:
      stage.routing.queueType === "MOVEMENT" ||
      stage.routing.queueType === "REPAIR" ||
      stage.routing.queueType === "HOLDING"
        ? stage.routing.queueType
        : null,
    sortOrder: stage.order,
    entryType: entry?.entryType,
    queuePosition: entry?.queuePosition ?? stage.order,
    scheduledDate: entry?.scheduledDate,
    priority:
      entry && entry.priority >= 1 && entry.priority <= 5
        ? (entry.priority as 1 | 2 | 3 | 4 | 5)
        : undefined,
    pinned: entry?.pinned ?? false,
    plannedDurationMinutes:
      maintenancePlannedDurationMinutes ??
      entry?.plannedDurationMinutes ??
      null,
    startedAt: stageStart(entry),
    completedAt: stage.completedAt,
    activeStartedAt: entry?.activeStartedAt ?? null,
    activeWorkSeconds: entry?.activeWorkSeconds ?? 0,
    workerGroup:
      group?.workerGroupId && group.workerGroupName
        ? { id: group.workerGroupId, name: group.workerGroupName }
        : null,
    assignments:
      entry?.assignments.map((assignment) => ({
        id: assignment.id,
        worker:
          assignment.workerId && assignment.workerName
            ? { id: assignment.workerId, name: assignment.workerName }
            : null,
        assignedAt: assignment.assignedAt,
        startedAt: assignment.startedAt,
        pausedAt: assignment.pausedAt,
        finishedAt: assignment.finishedAt,
        activeStartedAt:
          assignment.status === "ACTIVE" ? assignment.startedAt : null,
        status: assignment.status,
      })) ?? [],
  }
}

function latest(values: Array<string | null | undefined>) {
  return (
    values
      .filter((value): value is string => Boolean(value))
      .sort()
      .at(-1) ?? null
  )
}

function earliest(values: Array<string | null | undefined>) {
  return (
    values.filter((value): value is string => Boolean(value)).sort()[0] ?? null
  )
}

function isAwaitingMovementToRepair(repair: MaintenanceRepair) {
  const repairWorkStages = repair.plan.stages.filter(
    (stage) => stage.kind === "REPAIR_WORK"
  )
  return (
    repair.executionState === "QUEUED" &&
    repair.complexity.type !== "CAPITAL" &&
    repair.plan.stages.some((stage) => stage.kind === "MOVE_TO_REPAIR") &&
    repairWorkStages.length > 0 &&
    repairWorkStages.every(
      (stage) => stage.taskSync.taskBoardRegistrationVersion === null
    )
  )
}

async function toTask(
  repair: MaintenanceRepair,
  rentalItemsClient: RepairTaskRentalItemsClient,
  board: TaskBoardSnapshotDto | null,
  projection?: {
    readyAt?: string | null
    writtenOffAt?: string | null
    decisionActorId?: string | null
  }
): Promise<RepairTaskDto> {
  const entries = entriesByExternalTaskId(board)
  const subtasks = repair.plan.stages
    .filter((stage) => stage.kind === "REPAIR_WORK")
    .map((stage) =>
      toSubtask(
        stage,
        entries.get(`${stage.taskSync.externalTaskId}:${stage.order}`) ?? null
      )
    )
    .sort((left, right) => left.sortOrder - right.sortOrder)
  const rentalItem = await rentalItemsClient.resolveById(
    repair.warehouseId,
    repair.rentalItemId
  )
  return {
    id: repair.id,
    version: repair.version,
    status: repair.executionState,
    kind: repair.kind === "REWORK" ? "REWORK" : "REPAIR",
    origin: repair.origin,
    acceptanceStatus: repair.acceptanceState,
    startedAt: earliest(subtasks.map((subtask) => subtask.startedAt)),
    completedAt:
      repair.executionState === "COMPLETED"
        ? latest(subtasks.map((subtask) => subtask.completedAt))
        : null,
    warehouseId: repair.warehouseId,
    rentalItemId: repair.rentalItemId,
    cabinNumber: rentalItem?.number ?? "",
    actorId: repair.actor.actorId,
    sourceParty:
      repair.sourceParty ??
      (repair.origin === "INVENTORY" ? "Инвентаризация" : null),
    dispatchDate: repair.dispatchDate,
    priority: repairPriority(repair.priority),
    maintenanceMediaReferences: repair.mediaReferences,
    coverMediaId: repair.coverMediaId ?? null,
    subtasks,
    sourceEstimateId: repair.estimateId,
    sourceEstimateVersion: null,
    sourceInventoryId: repair.inventorySource?.inventoryId ?? null,
    sourceInventoryFindingId: repair.inventorySource?.findingId ?? null,
    sourceRepairTaskId: repair.sourceRepairId,
    sourceRepairTaskVersion: null,
    readyAt: projection?.readyAt ?? null,
    writtenOffAt: projection?.writtenOffAt ?? null,
    decisionActorId: projection?.decisionActorId ?? null,
    taskBoardAvailable: board !== null,
    awaitingMovement: isAwaitingMovementToRepair(repair),
    logisticsPlanningMode: repair.logisticsPlanningMode,
    logisticsScheduledDate: repair.logisticsScheduledDate,
    createdAt: repair.createdAt,
    updatedAt: repair.updatedAt,
  }
}

function requireDate(value: string | null) {
  if (!value) throw new Error("Не удалось определить дату создания ремонта.")
  return value
}

function routeForSubtask(
  subtask: RepairTaskSubtaskDto,
  queues: WorkQueueDto[],
  stageNumber: number
): MaintenanceRoutingSnapshot {
  const includesCustomWork =
    subtask.kind === "REPAIR_WORK" &&
    subtask.workLines.some((line) => line.catalogSnapshot === null)
  if (
    includesCustomWork &&
    subtask.routeQueueKind !== "REPAIR" &&
    subtask.routeQueueKind !== "HOLDING"
  ) {
    throw new Error(
      "Пользовательская работа может быть направлена только в очередь ремонта или ожидания."
    )
  }
  const active = queues.filter((queue) => queue.active && !queue.hidden)
  const exactByDefinitionId = subtask.queueId
    ? active.find((queue) => queue.definitionId === subtask.queueId)
    : undefined
  const movementCandidates =
    !subtask.queueId &&
    subtask.kind !== "REPAIR_WORK" &&
    subtask.routeQueueKind === "MOVEMENT"
      ? active.filter((queue) => queue.type === "MOVEMENT")
      : []
  const exact =
    exactByDefinitionId ??
    (movementCandidates.length === 1 ? movementCandidates[0] : undefined)
  if (exact) {
    if (
      includesCustomWork &&
      exact.type !== "REPAIR" &&
      exact.type !== "HOLDING"
    ) {
      throw new Error(
        `Пользовательская работа не может быть направлена в очередь «${exact.name}».`
      )
    }
    if (exact.type === "FURNITURE_MOVEMENT") {
      throw new Error(
        `Очередь «${exact.name}» предназначена для перемещения мебели и не может маршрутизировать этап ремонта.`
      )
    }
    return {
      queueId: exact.definitionId,
      queueName: exact.name,
      queueType: exact.type,
    }
  }
  if (movementCandidates.length > 1) {
    throw new Error(
      "Для перемещения подключите к складу ровно одну активную очередь типа «Перемещение»."
    )
  }
  if (
    !subtask.queueId &&
    subtask.kind !== "REPAIR_WORK" &&
    subtask.routeQueueKind === "MOVEMENT"
  ) {
    throw new Error(
      "К выбранному складу не подключена активная очередь типа «Перемещение». Откройте «Настройки склада → Очереди склада» и подключите её."
    )
  }
  if (subtask.queueName?.trim()) {
    throw new Error(
      `Очередь «${subtask.queueName.trim()}» не подключена к выбранному складу или отключена. Откройте «Настройки склада → Очереди склада» и подключите её.`
    )
  }
  throw new Error(
    `Для рабочего этапа №${stageNumber} не определена доступная очередь. Проверьте привязку категории в «Конструкторе каталога смет» и подключение очереди в «Настройки склада → Очереди склада».`
  )
}

function repairCatalogSnapshot(
  line: RepairEstimateLineDto,
  catalog: RepairEstimateCatalogSnapshotDto,
  catalogIndex: RepairEstimateCatalogIndex
) {
  const nodeId = line.catalogSnapshot?.nodeId
  if (!nodeId) return null
  const node = nodeId
    ? catalog.nodes.find((candidate) => candidate.id === nodeId)
    : null
  if (!node || !node.active) {
    throw new Error(
      `Позиция каталога «${line.description}» не найдена в активном каталоге ремонта.`
    )
  }
  if ((node.routeQueueKind as string | null) === "FURNITURE_MOVEMENT") {
    throw new Error(
      `Позиция каталога «${line.description}» маршрутизирована в очередь перемещения мебели.`
    )
  }
  const effectiveRouting = catalogIndex.getEffectiveQueueBinding(node.id)
  const routing =
    effectiveRouting?.queueId &&
    effectiveRouting.queueName &&
    effectiveRouting.queueKind
      ? {
          queueId: effectiveRouting.queueId,
          queueName: effectiveRouting.queueName,
          queueType: effectiveRouting.queueKind,
        }
      : null
  return {
    catalogVersionId: node.catalogVersionId,
    nodeId: node.id,
    nodeType: node.nodeType,
    name: node.name,
    unit: node.unit,
    unitPrice: node.unitPrice,
    durationMinutes: node.durationMinutes ?? 0,
    routing,
    furnitureEquipment: node.furnitureEquipment ?? null,
    forcesCapitalRepair: node.forcesCapitalRepair,
    characteristic: node.characteristic,
  }
}

function lineInput(
  line: RepairEstimateLineDto,
  id: string,
  catalog: RepairEstimateCatalogSnapshotDto,
  catalogIndex: RepairEstimateCatalogIndex
): MaintenanceEstimateLineInput {
  if (!line.description.trim()) {
    throw new Error("Укажите описание каждой строки ремонта.")
  }
  const snapshot = repairCatalogSnapshot(line, catalog, catalogIndex)
  const lineType = snapshot
    ? snapshot.nodeType === "WORK"
      ? "WORK"
      : "MATERIAL"
    : line.lineType
  const unit = snapshot ? snapshot.unit : line.unit?.trim() || null
  if (!snapshot && !unit) {
    throw new Error("Укажите единицу измерения пользовательской строки.")
  }
  const normativeMinutes = snapshot
    ? snapshot.durationMinutes
    : lineType === "MATERIAL"
      ? 0
      : line.normativeMinutes
  if (
    !snapshot &&
    lineType === "WORK" &&
    (!Number.isInteger(normativeMinutes) ||
      normativeMinutes === undefined ||
      normativeMinutes <= 0)
  ) {
    throw new Error("Укажите время выполнения пользовательской работы.")
  }
  return {
    id,
    catalogSnapshot: snapshot,
    lineType,
    description: line.description.trim(),
    unit,
    quantity: String(line.quantity),
    normativeMinutes,
    unitPrice: line.unitPrice,
    comment: lineType === "WORK" ? line.lineComment.trim() || null : null,
    mediaReferences:
      lineType === "WORK" ? [...(line.maintenanceMediaReferences ?? [])] : [],
  }
}

function maintenanceLineInput(
  line: MaintenanceEstimateLine
): MaintenanceEstimateLineInput {
  return {
    id: line.id,
    catalogSnapshot: line.catalogSnapshot,
    lineType: line.lineType,
    description: line.description,
    unit: line.unit,
    quantity: line.quantity,
    normativeMinutes: line.normativeMinutes,
    unitPrice: line.unitPrice,
    comment: line.lineType === "WORK" ? line.comment : null,
    mediaReferences: line.lineType === "WORK" ? [...line.mediaReferences] : [],
  }
}

function uniqueMaintenanceLines(stages: MaintenanceRepairStage[]) {
  const lines = new Map<string, MaintenanceEstimateLineInput>()
  stages.forEach((stage) => {
    const stageLines = [...stage.workLines, ...stage.materialLines]
    stageLines.forEach((line) => {
      if (!lines.has(line.id)) lines.set(line.id, maintenanceLineInput(line))
    })
  })
  return [...lines.values()]
}

async function planForCommand(
  accessToken: string,
  command: RepairTaskWriteCommand
) {
  if (command.media.length > 0) {
    throw new Error(
      "Локальные вложения старой панели нельзя отправить в maintenance-service."
    )
  }
  const [queues, catalog] = await Promise.all([
    taskBoardSettingsClient.listQueues(accessToken, command.warehouseId),
    getOperationalRepairEstimateCatalog(),
  ])
  const catalogIndex = createRepairEstimateCatalogIndex(catalog)
  if (command.subtasks.length === 0) {
    throw new Error("Добавьте хотя бы один этап ремонта.")
  }
  if (command.maintenanceMediaReferences.length > 0 && !command.coverMediaId) {
    throw new Error("Выберите титульную фотографию.")
  }
  if (
    command.coverMediaId &&
    !command.maintenanceMediaReferences.some(
      (reference) => reference.mediaId === command.coverMediaId
    )
  ) {
    throw new Error("Титульная фотография отсутствует среди готовых фото.")
  }
  const lineIdBySourceId = new Map<string, string>()
  const lines = new Map<string, MaintenanceEstimateLineInput>()
  const sourceLineByMaintenanceId = new Map<string, RepairEstimateLineDto>()
  command.subtasks.forEach((subtask) => {
    const subtaskLines = [...subtask.workLines, ...subtask.materialLines]
    subtaskLines.forEach((line) => {
      if (lineIdBySourceId.has(line.id)) return
      const id = UUID_PATTERN.test(line.id)
        ? line.id
        : createMaintenanceIdempotencyKey()
      lineIdBySourceId.set(line.id, id)
      lines.set(id, lineInput(line, id, catalog, catalogIndex))
      sourceLineByMaintenanceId.set(id, line)
    })
  })
  const maintenanceLineId = (line: RepairEstimateLineDto) => {
    const id = lineIdBySourceId.get(line.id)
    if (!id) {
      throw new Error(`Этап ссылается на отсутствующую строку ${line.id}.`)
    }
    return id
  }
  const plan = command.subtasks
    .slice()
    .sort((left, right) => left.sortOrder - right.sortOrder)
    .map<MaintenancePlanStageInput>((subtask, index) => {
      const primaryWorkLine = subtask.workLines.find(
        (line) => line.lineType === "WORK"
      )
      return {
        id: UUID_PATTERN.test(subtask.id)
          ? subtask.id
          : createMaintenanceIdempotencyKey(),
        kind: subtask.kind,
        order: index,
        routing: routeForSubtask(subtask, queues, index + 1),
        includedLineIds: [...subtask.workLines, ...subtask.materialLines].map(
          maintenanceLineId
        ),
        primaryLineId: primaryWorkLine
          ? maintenanceLineId(primaryWorkLine)
          : null,
        groupComment: subtask.groupComment,
        taskDeadline: null,
      }
    })
  return { lines: [...lines.values()], plan, sourceLineByMaintenanceId }
}

const UUID_PATTERN =
  /^[0-9a-f]{8}-[0-9a-f]{4}-[1-8][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/i

const QUEUE_CONFIRMATION_BACKOFF_MS = [100, 200, 400, 800, 1_000] as const

type QueueConfirmationWait = (delayMs: number) => Promise<void>

function waitForQueueConfirmation(delayMs: number) {
  return new Promise<void>((resolve) => window.setTimeout(resolve, delayMs))
}

function hasPermanentQueueFailure(result: MaintenanceRepairCommandResult) {
  return (
    result.delivery.state === "QUARANTINED" ||
    result.repair.plan.stages.some(
      (stage) =>
        stage.taskSync.generationState === "FAILED" ||
        stage.taskSync.delivery.state === "QUARANTINED"
    )
  )
}

function hasPermanentRepairFailure(repair: MaintenanceRepair) {
  return repair.plan.stages.some(
    (stage) =>
      stage.taskSync.generationState === "FAILED" ||
      stage.taskSync.delivery.state === "QUARANTINED"
  )
}

function permanentQueueFailure(repairId: string) {
  return new Error(
    `Ремонт ${repairId} не поставлен в очередь: maintenance-service зафиксировал необратимую ошибку формирования или доставки этапов.`
  )
}

export class HttpMaintenanceRepairTasksAdapter implements RepairTasksClient {
  private readonly rentalItemsClient: RepairTaskRentalItemsClient
  private readonly tokenProvider: MaintenanceAccessTokenProvider
  private readonly queueConfirmationWait: QueueConfirmationWait

  constructor(
    rentalItemsClient: RepairTaskRentalItemsClient,
    tokenProvider: MaintenanceAccessTokenProvider = currentMaintenanceAccessToken,
    queueConfirmationWait: QueueConfirmationWait = waitForQueueConfirmation
  ) {
    this.rentalItemsClient = rentalItemsClient
    this.tokenProvider = tokenProvider
    this.queueConfirmationWait = queueConfirmationWait
  }

  private async board(accessToken: string, warehouseId: string) {
    const initial = await getTaskBoard(accessToken, warehouseId)
    const additional = await Promise.all(
      initial.availableDates
        .filter((date) => date !== initial.selectedDate)
        .map((date) => getTaskBoard(accessToken, warehouseId, date))
    )
    if (additional.length === 0) return initial
    const queueByKey = new Map(
      initial.queues.map((queue) => [
        queue.key,
        { ...queue, entries: [...queue.entries] },
      ])
    )
    additional.forEach((board) => {
      board.queues.forEach((queue) => {
        const current = queueByKey.get(queue.key)
        if (!current) {
          queueByKey.set(queue.key, {
            ...queue,
            entries: [...queue.entries],
          })
          return
        }
        const entryIds = new Set(current.entries.map((entry) => entry.id))
        current.entries.push(
          ...queue.entries.filter((entry) => !entryIds.has(entry.id))
        )
      })
    })
    const queues = [...queueByKey.values()]
    const entries = queues.flatMap((queue) => queue.entries)
    return {
      warehouseId,
      selectedDate: null,
      availableDates: initial.availableDates,
      queues,
      totalEntries: entries.length,
      realEntries: entries.filter((entry) => entry.entryType === "REAL").length,
      shadowEntries: entries.filter((entry) => entry.entryType === "SHADOW")
        .length,
    }
  }

  private async projection(
    accessToken: string,
    warehouseId: string,
    repair: MaintenanceRepair
  ) {
    if (repair.acceptanceState === "PENDING") {
      const page = await listMaintenanceAcceptance(
        accessToken,
        warehouseId,
        "PENDING"
      )
      const projection = page.items.find(
        (candidate) => candidate.repairId === repair.id
      )
      return projection ? { readyAt: projection.readyAt } : undefined
    }
    if (repair.acceptanceState === "WRITTEN_OFF") {
      const page = await listMaintenanceWriteOffs(accessToken, warehouseId)
      const projection = page.items.find(
        (candidate) => candidate.repairId === repair.id
      )
      return projection
        ? {
            writtenOffAt: projection.writtenOffAt,
            decisionActorId: projection.actor.actorId,
          }
        : undefined
    }
    return undefined
  }

  async list(warehouseId: string) {
    const accessToken = await this.tokenProvider()
    const [page, board] = await Promise.all([
      listMaintenanceRepairs(accessToken, warehouseId),
      this.board(accessToken, warehouseId),
    ])
    return Promise.all(
      page.items.map((repair) => toTask(repair, this.rentalItemsClient, board))
    )
  }

  async listPendingAcceptance(warehouseId: string) {
    const accessToken = await this.tokenProvider()
    const [page, board] = await Promise.all([
      listMaintenanceAcceptance(accessToken, warehouseId, "PENDING"),
      this.board(accessToken, warehouseId),
    ])
    return Promise.all(
      page.items.map(async (projection: MaintenanceAcceptanceProjection) =>
        toTask(
          await getMaintenanceRepair(
            accessToken,
            warehouseId,
            projection.repairId
          ),
          this.rentalItemsClient,
          board,
          { readyAt: projection.readyAt }
        )
      )
    )
  }

  async listWriteOffs(warehouseId: string) {
    const accessToken = await this.tokenProvider()
    const [page, board] = await Promise.all([
      listMaintenanceWriteOffs(accessToken, warehouseId),
      this.board(accessToken, warehouseId),
    ])
    return Promise.all(
      page.items.map(async (projection: MaintenanceWriteOffProjection) =>
        toTask(
          await getMaintenanceRepair(
            accessToken,
            warehouseId,
            projection.repairId
          ),
          this.rentalItemsClient,
          board,
          {
            writtenOffAt: projection.writtenOffAt,
            decisionActorId: projection.actor.actorId,
          }
        )
      )
    )
  }

  async getById(taskId: string, warehouseId: string) {
    const accessToken = await this.tokenProvider()
    try {
      const [repair, board] = await Promise.all([
        getMaintenanceRepair(accessToken, warehouseId, taskId),
        this.board(accessToken, warehouseId),
      ])
      return toTask(
        repair,
        this.rentalItemsClient,
        board,
        await this.projection(accessToken, warehouseId, repair)
      )
    } catch (error) {
      if (error instanceof ApiError && error.status === 404) return null
      throw error
    }
  }

  async getBySourceEstimateId(sourceEstimateId: string, warehouseId: string) {
    const accessToken = await this.tokenProvider()
    const [page, board] = await Promise.all([
      listMaintenanceRepairs(accessToken, warehouseId),
      this.board(accessToken, warehouseId),
    ])
    const repair = page.items.find(
      (candidate) => candidate.estimateId === sourceEstimateId
    )
    return repair ? toTask(repair, this.rentalItemsClient, board) : null
  }

  private async saveWithToken(
    accessToken: string,
    command: RepairTaskWriteCommand
  ) {
    const content = await planForCommand(accessToken, command)
    if (command.taskId) {
      if (command.expectedVersion === null) {
        throw new Error("Отсутствует версия ремонтного задания.")
      }
      return replaceMaintenanceRepairPlan(
        accessToken,
        command.warehouseId,
        command.taskId,
        command.expectedVersion,
        content.lines,
        content.plan,
        command.maintenanceMediaReferences,
        command.coverMediaId
      )
    }
    if (command.kind === "REWORK") {
      if (
        !command.sourceRepairTaskId ||
        command.sourceRepairTaskVersion === null ||
        !command.reason.trim()
      ) {
        throw new Error("Для доработки нужны исходный ремонт и причина.")
      }
      return createMaintenanceRework(
        accessToken,
        command.warehouseId,
        command.sourceRepairTaskId,
        createMaintenanceIdempotencyKey(),
        {
          expectedVersion: command.sourceRepairTaskVersion,
          reason: command.reason.trim(),
          lines: content.lines.map<MaintenanceReworkLineInput>((line) => {
            const source = content.sourceLineByMaintenanceId.get(line.id)
            if (
              source?.rework?.disposition === "REPEAT" &&
              source.rework.sourceRepairId &&
              source.rework.sourceLineId
            ) {
              return {
                id: line.id,
                disposition: "REPEAT",
                sourceRepairId: source.rework.sourceRepairId,
                sourceLineId: source.rework.sourceLineId,
                quantity: line.quantity,
                comment: line.comment,
              }
            }
            return { id: line.id, disposition: "ADDED", line }
          }),
          plan: content.plan,
          mediaReferences: command.maintenanceMediaReferences,
          coverMediaId: command.coverMediaId,
        }
      )
    }
    return createDirectMaintenanceRepair(
      accessToken,
      createMaintenanceIdempotencyKey(),
      {
        warehouseId: command.warehouseId,
        rentalItemId: command.rentalItemId,
        dispatchDate: requireDate(command.dispatchDate),
        sourceParty: "WMS-панель",
        lines: content.lines,
        plan: content.plan,
        mediaReferences: command.maintenanceMediaReferences,
        coverMediaId: command.coverMediaId,
      }
    )
  }

  private async awaitQueueConfirmation(
    accessToken: string,
    warehouseId: string,
    result: MaintenanceRepairCommandResult
  ) {
    if (result.repair.executionState !== "DRAFT") {
      if (hasPermanentQueueFailure(result)) {
        throw permanentQueueFailure(result.repair.id)
      }
      return result.repair
    }

    let latestDraft = result.repair
    let lastReadError: unknown
    for (const delayMs of QUEUE_CONFIRMATION_BACKOFF_MS) {
      await this.queueConfirmationWait(delayMs)
      let repair: MaintenanceRepair
      try {
        repair = await getMaintenanceRepair(
          accessToken,
          warehouseId,
          result.repair.id
        )
        lastReadError = undefined
      } catch (error) {
        lastReadError = error
        continue
      }
      if (hasPermanentRepairFailure(repair)) {
        throw permanentQueueFailure(repair.id)
      }
      if (repair.executionState !== "DRAFT") {
        return repair
      }
      latestDraft = repair
    }

    throw new RepairTaskQueueDraftPersistedError({
      taskId: latestDraft.id,
      expectedVersion: latestDraft.version,
      message: `Ремонт ${result.repair.id} сохранён, но его состояние в maintenance-service всё ещё ожидает подтверждения постановки в очередь.`,
      cause: lastReadError,
    })
  }

  private async recoverPersistedDraftAfterQueueFailure(
    accessToken: string,
    warehouseId: string,
    draft: MaintenanceRepair,
    queueError: unknown
  ) {
    let persistedDraft = draft
    try {
      const persisted = await getMaintenanceRepair(
        accessToken,
        warehouseId,
        draft.id
      )
      if (persisted.executionState !== "DRAFT") {
        return persisted
      }
      persistedDraft = persisted
    } catch {
      // The create response is still authoritative for the persisted identity.
    }

    throw new RepairTaskQueueDraftPersistedError({
      taskId: persistedDraft.id,
      expectedVersion: persistedDraft.version,
      message: `Черновик ремонта ${persistedDraft.id} сохранён, но постановка в очередь не выполнена: ${queueError instanceof Error ? queueError.message : "неизвестная ошибка"}`,
      cause: queueError,
    })
  }

  async saveDraft(command: RepairTaskWriteCommand) {
    const accessToken = await this.tokenProvider()
    const repair = await this.saveWithToken(accessToken, command)
    return toTask(
      repair,
      this.rentalItemsClient,
      await this.board(accessToken, command.warehouseId)
    )
  }

  async queue(command: RepairTaskWriteCommand) {
    const accessToken = await this.tokenProvider()
    if (!command.priority) {
      throw new Error("Выберите приоритет ремонта.")
    }
    const draft = await this.saveWithToken(accessToken, command)
    let result: MaintenanceRepairCommandResult
    try {
      result = await queueMaintenanceRepair(
        accessToken,
        command.warehouseId,
        draft.id,
        draft.version,
        command.priority,
        createMaintenanceIdempotencyKey(),
        command.logisticsPlanningMode,
        command.logisticsScheduledDate
      )
    } catch (error) {
      const repair = await this.recoverPersistedDraftAfterQueueFailure(
        accessToken,
        command.warehouseId,
        draft,
        error
      )
      return toTask(
        repair,
        this.rentalItemsClient,
        await this.board(accessToken, command.warehouseId)
      )
    }
    const repair = await this.awaitQueueConfirmation(
      accessToken,
      command.warehouseId,
      result
    )
    return toTask(
      repair,
      this.rentalItemsClient,
      await this.board(accessToken, command.warehouseId)
    )
  }

  async updateSubtasks(command: {
    taskId: string
    expectedVersion: number
    warehouseId: string
    orderedSubtaskIds: string[]
  }) {
    const accessToken = await this.tokenProvider()
    const repair = await getMaintenanceRepair(
      accessToken,
      command.warehouseId,
      command.taskId
    )
    if (repair.version !== command.expectedVersion) {
      throw new Error("Ремонт изменился. Обновите данные.")
    }
    const stageById = new Map(
      repair.plan.stages.map((stage) => [stage.id, stage])
    )
    const stages = command.orderedSubtaskIds.map((id, index) => {
      const stage = stageById.get(id)
      if (!stage) throw new Error("Этап ремонта не найден.")
      return {
        id: stage.id,
        kind: stage.kind,
        order: index,
        routing: stage.routing,
        includedLineIds: [...stage.workLines, ...stage.materialLines].map(
          (line) => line.id
        ),
        primaryLineId: stage.primaryLineId,
        groupComment: stage.groupComment,
        taskDeadline: stage.taskDeadline,
      }
    })
    const saved = await replaceMaintenanceRepairPlan(
      accessToken,
      command.warehouseId,
      command.taskId,
      command.expectedVersion,
      uniqueMaintenanceLines(repair.plan.stages),
      stages,
      repair.mediaReferences,
      repair.coverMediaId ?? null
    )
    return toTask(
      saved,
      this.rentalItemsClient,
      await this.board(accessToken, command.warehouseId)
    )
  }

  async accept(command: RepairTaskAcceptCommand) {
    const accessToken = await this.tokenProvider()
    const result = await acceptMaintenanceRepair(
      accessToken,
      command.warehouseId,
      command.taskId,
      command.expectedVersion,
      command.comment.trim() || null,
      command.maintenanceMediaReferences,
      createMaintenanceIdempotencyKey()
    )
    return toTask(
      result.repair,
      this.rentalItemsClient,
      await this.board(accessToken, command.warehouseId)
    )
  }

  async writeOff(command: {
    taskId: string
    expectedVersion: number
    warehouseId: string
    reason: string
  }) {
    if (!command.reason.trim()) throw new Error("Укажите причину списания.")
    const accessToken = await this.tokenProvider()
    const result = await writeOffMaintenanceRepair(
      accessToken,
      command.warehouseId,
      command.taskId,
      command.expectedVersion,
      command.reason.trim(),
      null,
      createMaintenanceIdempotencyKey()
    )
    return toTask(
      result.repair,
      this.rentalItemsClient,
      await this.board(accessToken, command.warehouseId)
    )
  }

  async earlyWriteOff(
    command: RepairTaskWriteCommand & { writeOffReason: string }
  ) {
    const accessToken = await this.tokenProvider()
    const draft = await this.saveWithToken(accessToken, command)
    const result = await writeOffMaintenanceRepair(
      accessToken,
      command.warehouseId,
      draft.id,
      draft.version,
      command.writeOffReason.trim(),
      null,
      createMaintenanceIdempotencyKey()
    )
    return toTask(
      result.repair,
      this.rentalItemsClient,
      await this.board(accessToken, command.warehouseId)
    )
  }
}
