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
  type MaintenancePlanStageInput,
  type MaintenanceRepair,
  type MaintenanceRepairCommandResult,
  type MaintenanceRepairStage,
  type MaintenanceRoutingSnapshot,
  type MaintenanceWriteOffProjection,
} from "@/features/repair-estimates/api/http-maintenance-lifecycle-client"
import {
  currentMaintenanceAccessToken,
  type MaintenanceAccessTokenProvider,
} from "@/features/repair-estimates/api/maintenance-auth"
import type {
  RepairTaskAcceptCommand,
  RepairTaskDto,
  RepairTaskSubtaskDto,
  RepairTaskWriteCommand,
} from "@/features/repair-tasks/model/repair-task"
import type { RepairTaskRentalItemsClient } from "@/features/repair-tasks/ports/repair-task-rental-items-client"
import type { RepairTasksClient } from "@/features/repair-tasks/ports/repair-tasks-client"
import { taskBoardSettingsClient } from "@/features/settings/task-board/api/task-board-settings-api"
import type { WorkQueueDto } from "@/features/settings/task-board/model/task-board-settings"
import { getTaskBoard } from "@/features/task-board/api/task-board-api"
import type {
  TaskBoardEntryDto,
  TaskBoardSnapshotDto,
} from "@/features/task-board/model/task-board"
import { ApiError } from "@/lib/api-client"

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
      .map((entry) => [entry.externalTaskId!, entry])
  )
}

function stageStart(entry: TaskBoardEntryDto | null) {
  const values = [
    entry?.activeStartedAt,
    ...(entry?.assignments.map((assignment) => assignment.startedAt) ?? []),
  ].filter((value): value is string => Boolean(value))
  return values.sort()[0] ?? null
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
  return {
    id: stage.id,
    externalTaskId: stage.taskSync.externalTaskId,
    kind: stage.kind,
    status: statusForStage(stage, entry),
    workLines: [],
    materialLines: [],
    groupComment: "",
    queueId: stage.routing.queueId,
    queueCode: stage.routing.queueCode,
    routeQueueKind:
      stage.routing.queueKind === "MOVEMENT" ||
      stage.routing.queueKind === "REPAIR" ||
      stage.routing.queueKind === "HOLDING"
        ? stage.routing.queueKind
        : null,
    sortOrder: stage.order,
    queuePosition: entry?.queuePosition ?? stage.order,
    plannedDurationMinutes: entry?.plannedDurationMinutes ?? null,
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
    .map((stage) =>
      toSubtask(stage, entries.get(stage.taskSync.externalTaskId) ?? null)
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
    sourceParty: repair.sourceParty,
    dispatchDate: repair.dispatchDate,
    maintenanceMediaReferences: repair.mediaReferences,
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
    createdAt: repair.createdAt,
    updatedAt: repair.updatedAt,
  }
}

function requireDate(value: string | null) {
  if (!value) throw new Error("Укажите дату прибытия.")
  return value
}

function routeForSubtask(
  subtask: RepairTaskSubtaskDto,
  queues: WorkQueueDto[]
): MaintenanceRoutingSnapshot {
  const active = queues.filter((queue) => queue.active && !queue.hidden)
  const exactById = subtask.queueId
    ? active.find((queue) => queue.id === subtask.queueId)
    : null
  const exactByCode =
    !exactById && subtask.queueCode
      ? active.find((queue) => queue.code === subtask.queueCode)
      : null
  const exact = exactById ?? exactByCode
  if (exact) {
    return {
      queueId: exact.id,
      queueCode: exact.code,
      queueKind: exact.type,
    }
  }
  const byKind = subtask.routeQueueKind
    ? active.filter((queue) => queue.type === subtask.routeQueueKind)
    : []
  if (byKind.length === 1) {
    return {
      queueId: byKind[0].id,
      queueCode: byKind[0].code,
      queueKind: byKind[0].type,
    }
  }
  throw new Error(
    byKind.length > 1
      ? `Для этапа ${subtask.sortOrder} выберите конкретную очередь.`
      : `Для этапа ${subtask.sortOrder} не настроена активная очередь.`
  )
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
  const queues = await taskBoardSettingsClient.listQueues(
    accessToken,
    command.warehouseId
  )
  if (command.subtasks.length === 0) {
    throw new Error("Добавьте хотя бы один этап ремонта.")
  }
  return command.subtasks
    .slice()
    .sort((left, right) => left.sortOrder - right.sortOrder)
    .map<MaintenancePlanStageInput>((subtask, index) => ({
      id: UUID_PATTERN.test(subtask.id)
        ? subtask.id
        : createMaintenanceIdempotencyKey(),
      kind: subtask.kind,
      order: index,
      routing: routeForSubtask(subtask, queues),
      taskDeadline: null,
    }))
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
    try {
      return await getTaskBoard(accessToken, warehouseId)
    } catch {
      return null
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
    const plan = await planForCommand(accessToken, command)
    if (command.taskId) {
      if (command.expectedVersion === null) {
        throw new Error("Отсутствует версия ремонтного задания.")
      }
      return replaceMaintenanceRepairPlan(
        accessToken,
        command.warehouseId,
        command.taskId,
        command.expectedVersion,
        plan,
        command.maintenanceMediaReferences
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
          plan,
          mediaReferences: command.maintenanceMediaReferences,
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
        sourceParty: command.reason.trim() || null,
        plan,
        mediaReferences: command.maintenanceMediaReferences,
      }
    )
  }

  private async awaitQueueConfirmation(
    accessToken: string,
    warehouseId: string,
    result: MaintenanceRepairCommandResult
  ) {
    if (hasPermanentQueueFailure(result)) {
      throw permanentQueueFailure(result.repair.id)
    }
    if (result.repair.executionState !== "DRAFT") {
      return result.repair
    }

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
    }

    throw new Error(
      `Ремонт ${result.repair.id} сохранён, но его состояние в maintenance-service всё ещё ожидает подтверждения постановки в очередь.`,
      lastReadError === undefined ? undefined : { cause: lastReadError }
    )
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
    const draft = await this.saveWithToken(accessToken, command)
    let result: MaintenanceRepairCommandResult
    try {
      result = await queueMaintenanceRepair(
        accessToken,
        command.warehouseId,
        draft.id,
        draft.version,
        createMaintenanceIdempotencyKey()
      )
    } catch (error) {
      throw new Error(
        `Черновик ремонта ${draft.id} сохранён, но постановка в очередь не выполнена: ${error instanceof Error ? error.message : "неизвестная ошибка"}`,
        { cause: error }
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
        taskDeadline: stage.taskDeadline,
      }
    })
    const saved = await replaceMaintenanceRepairPlan(
      accessToken,
      command.warehouseId,
      command.taskId,
      command.expectedVersion,
      stages,
      repair.mediaReferences
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
