import { assertRepairTaskSubtasksValid } from "@/features/repair-tasks/domain/repair-task-domain"
import type {
  RepairTaskDto,
  RepairTaskAcceptCommand,
  RepairTaskAssignmentDto,
  RepairTaskCompleteEntryCommand,
  RepairTaskEntryRefCommand,
  RepairTaskEarlyWriteOffCommand,
  RepairTaskFromEstimateCommand,
  RepairTaskFromInventoryFindingCommand,
  RepairTaskMoveEntryCommand,
  RepairTaskSubtasksCommand,
  RepairTaskSyncFromEstimateCommand,
  RepairTaskTakeEntryCommand,
  RepairTaskWriteOffCommand,
  RepairTaskWriteCommand,
} from "@/features/repair-tasks/model/repair-task"
import type { RepairTasksClient } from "@/features/repair-tasks/ports/repair-tasks-client"
import type { RepairTaskRentalItemsClient } from "@/features/repair-tasks/ports/repair-task-rental-items-client"
import type { RentalItemStatus } from "@/features/rental-items/model/rental-item"

export const REPAIR_TASKS_MOCK_STORAGE_KEY = "rwms:repair-tasks:v4"
export const REPAIR_TASKS_LEGACY_MOCK_STORAGE_KEY = "rwms:repair-tasks:v3"
const REPAIR_TASKS_V2_MOCK_STORAGE_KEY = "rwms:repair-tasks:v2"
const REPAIR_TASKS_V1_MOCK_STORAGE_KEY = "rwms:repair-tasks:v1"
export const REPAIR_TASKS_UPDATED_EVENT = "rwms:repair-tasks-updated"
const CURRENT_MOCK_AUTHOR_NAME = "Текущий пользователь"
const UNKNOWN_AUTHOR_NAME = "Не указан"

type RepairTasksStorageEnvelope = {
  service: "repair-tasks"
  schemaVersion: 4
  revision: number
  tasks: RepairTaskDto[]
}

type RepairTasksRuntimeState = {
  mutationQueue: Promise<void>
  inventoryPublicationQueue: Promise<void>
}

type LegacyRepairTasksStorageEnvelope = {
  service: "repair-tasks"
  schemaVersion: 1 | 2 | 3
  revision: number
  tasks: unknown[]
}

const RUNTIME_STATE_KEY = "__rwmsRepairTasksRuntimeV4__"
const runtimeGlobal = globalThis as typeof globalThis & {
  [RUNTIME_STATE_KEY]?: RepairTasksRuntimeState
}
const runtimeState: RepairTasksRuntimeState = runtimeGlobal[
  RUNTIME_STATE_KEY
] ?? {
  mutationQueue: Promise.resolve(),
  inventoryPublicationQueue: Promise.resolve(),
}
runtimeState.inventoryPublicationQueue ??= Promise.resolve()
runtimeGlobal[RUNTIME_STATE_KEY] = runtimeState

const REPAIR_TASK_MUTATION_LOCK_NAME = "rwms:repair-tasks:mutation"
const REPAIR_TASK_MUTATION_LEASE_KEY = "rwms:repair-tasks:mutation:lease"

function createOpaqueId(prefix: string) {
  const suffix =
    typeof crypto !== "undefined" && "randomUUID" in crypto
      ? crypto.randomUUID()
      : `${Date.now()}-${Math.random().toString(36).slice(2)}`
  return `${prefix}-${suffix}`
}

function emptyEnvelope(): RepairTasksStorageEnvelope {
  return {
    service: "repair-tasks",
    schemaVersion: 4,
    revision: 0,
    tasks: [],
  }
}

function parseEnvelope(raw: string | null) {
  if (!raw) {
    return null
  }
  try {
    const parsed = JSON.parse(raw) as
      | Partial<RepairTasksStorageEnvelope>
      | Partial<LegacyRepairTasksStorageEnvelope>
    if (
      parsed.service !== "repair-tasks" ||
      (parsed.schemaVersion !== 1 &&
        parsed.schemaVersion !== 2 &&
        parsed.schemaVersion !== 3 &&
        parsed.schemaVersion !== 4) ||
      !Array.isArray(parsed.tasks)
    ) {
      return null
    }
    return {
      service: "repair-tasks" as const,
      schemaVersion: 4 as const,
      revision:
        typeof parsed.revision === "number" && Number.isFinite(parsed.revision)
          ? Math.max(0, Math.floor(parsed.revision))
          : 0,
      tasks: parsed.tasks.map((task) => normalizeStoredTask(task)),
    }
  } catch {
    return null
  }
}

function readEnvelope() {
  if (typeof window === "undefined") {
    return emptyEnvelope()
  }
  const current = parseEnvelope(
    window.localStorage.getItem(REPAIR_TASKS_MOCK_STORAGE_KEY)
  )
  if (current) {
    return current
  }
  const legacy =
    parseEnvelope(
      window.localStorage.getItem(REPAIR_TASKS_LEGACY_MOCK_STORAGE_KEY)
    ) ??
    parseEnvelope(
      window.localStorage.getItem(REPAIR_TASKS_V2_MOCK_STORAGE_KEY)
    ) ??
    parseEnvelope(window.localStorage.getItem(REPAIR_TASKS_V1_MOCK_STORAGE_KEY))
  if (legacy) {
    window.localStorage.setItem(
      REPAIR_TASKS_MOCK_STORAGE_KEY,
      JSON.stringify(legacy)
    )
    window.localStorage.removeItem(REPAIR_TASKS_LEGACY_MOCK_STORAGE_KEY)
    window.localStorage.removeItem(REPAIR_TASKS_V2_MOCK_STORAGE_KEY)
    window.localStorage.removeItem(REPAIR_TASKS_V1_MOCK_STORAGE_KEY)
    return legacy
  }
  return emptyEnvelope()
}

function storedQueueIdentity(subtask: RepairTaskDto["subtasks"][number]) {
  if (subtask.queueCode) {
    return `code:${subtask.queueCode}`
  }
  if (subtask.kind === "MOVE_TO_REPAIR") {
    return "kind:MOVE_TO_REPAIR"
  }
  if (subtask.kind === "MOVE_FROM_REPAIR") {
    return "kind:MOVE_FROM_REPAIR"
  }
  if (subtask.routeQueueKind) {
    return `route:${subtask.routeQueueKind}`
  }
  return "unassigned"
}

function writeEnvelope(envelope: RepairTasksStorageEnvelope) {
  window.localStorage.setItem(
    REPAIR_TASKS_MOCK_STORAGE_KEY,
    JSON.stringify(envelope)
  )
  window.dispatchEvent(new Event(REPAIR_TASKS_UPDATED_EVENT))
}

function runWithOriginMutationLock<T>(operation: () => Promise<T>) {
  if (typeof navigator !== "undefined" && navigator.locks) {
    return navigator.locks.request(REPAIR_TASK_MUTATION_LOCK_NAME, operation)
  }
  return runWithFallbackMutationLease(operation)
}

async function runWithFallbackMutationLease<T>(operation: () => Promise<T>) {
  if (typeof window === "undefined") return operation()
  const token = createOpaqueId("repair-task-lease")
  for (let attempt = 0; attempt < 100; attempt += 1) {
    const now = Date.now()
    let lease: { token: string; expiresAt: number } | null = null
    try {
      lease = JSON.parse(
        window.localStorage.getItem(REPAIR_TASK_MUTATION_LEASE_KEY) ?? "null"
      ) as { token: string; expiresAt: number } | null
    } catch {
      // A corrupt lease is expired and may be replaced.
    }
    if (!lease || lease.expiresAt <= now) {
      window.localStorage.setItem(
        REPAIR_TASK_MUTATION_LEASE_KEY,
        JSON.stringify({ token, expiresAt: now + 5_000 })
      )
      let claimed: { token?: unknown } | null = null
      try {
        claimed = JSON.parse(
          window.localStorage.getItem(REPAIR_TASK_MUTATION_LEASE_KEY) ?? "null"
        ) as { token?: unknown } | null
      } catch {
        // Another context replaced the lease with invalid data.
      }
      if (claimed?.token === token) {
        try {
          return await operation()
        } finally {
          let current: { token?: unknown } | null = null
          try {
            current = JSON.parse(
              window.localStorage.getItem(REPAIR_TASK_MUTATION_LEASE_KEY) ??
                "null"
            ) as { token?: unknown } | null
          } catch {
            // A replaced lease must not be removed by this owner.
          }
          if (current?.token === token) {
            window.localStorage.removeItem(REPAIR_TASK_MUTATION_LEASE_KEY)
          }
        }
      }
    }
    await new Promise((resolve) => window.setTimeout(resolve, 10))
  }
  throw new Error("Задания ремонтов изменяются в другой вкладке")
}

function runSerializedMutation<T>(operation: () => Promise<T>) {
  const run = () => runWithOriginMutationLock(operation)
  const result = runtimeState.mutationQueue.then(run, run)
  runtimeState.mutationQueue = result.then(
    () => undefined,
    () => undefined
  )
  return result
}

function runInventoryPublicationSaga<T>(operation: () => Promise<T>) {
  const result = runtimeState.inventoryPublicationQueue.then(
    operation,
    operation
  )
  runtimeState.inventoryPublicationQueue = result.then(
    () => undefined,
    () => undefined
  )
  return result
}

function normalizeAuthorName(value: unknown) {
  return typeof value === "string" && value.trim()
    ? value.trim()
    : UNKNOWN_AUTHOR_NAME
}

function finiteNonNegativeInteger(value: unknown) {
  return typeof value === "number" && Number.isFinite(value)
    ? Math.max(0, Math.floor(value))
    : 0
}

function normalizeAssignment(
  value: unknown,
  fallback: {
    id: string
    assignedAt: string
    startedAt: string | null
    completedAt: string | null
    activeWorkSeconds: number
    status: RepairTaskDto["subtasks"][number]["status"]
    assigneeName: string | null
  }
): RepairTaskAssignmentDto {
  const assignment =
    value && typeof value === "object"
      ? (value as Partial<RepairTaskAssignmentDto>)
      : null
  const workerName = assignment?.worker?.name?.trim() || fallback.assigneeName
  const assignmentStatus =
    assignment?.status === "ACTIVE" ||
    assignment?.status === "PAUSED" ||
    assignment?.status === "DONE" ||
    assignment?.status === "CANCELLED"
      ? assignment.status
      : fallback.status === "DONE"
        ? "DONE"
        : fallback.status === "CANCELLED"
          ? "CANCELLED"
          : fallback.status === "PAUSED"
            ? "PAUSED"
            : "ACTIVE"
  return {
    id: assignment?.id || fallback.id,
    worker: workerName
      ? {
          id: assignment?.worker?.id || `${fallback.id}:worker`,
          name: workerName,
        }
      : null,
    assignedAt: assignment?.assignedAt || fallback.assignedAt,
    startedAt: assignment?.startedAt ?? fallback.startedAt,
    pausedAt:
      typeof assignment?.pausedAt === "string" ? assignment.pausedAt : null,
    finishedAt: assignment?.finishedAt ?? fallback.completedAt,
    activeStartedAt:
      typeof assignment?.activeStartedAt === "string"
        ? assignment.activeStartedAt
        : assignmentStatus === "ACTIVE"
          ? fallback.startedAt
          : null,
    activeWorkSeconds:
      assignment?.activeWorkSeconds === undefined
        ? fallback.activeWorkSeconds
        : finiteNonNegativeInteger(assignment.activeWorkSeconds),
    status: assignmentStatus,
  }
}

function normalizeStoredTask(value: unknown): RepairTaskDto {
  const task = value as RepairTaskDto
  const normalizedSourceInventoryId =
    typeof task.sourceInventoryId === "string" && task.sourceInventoryId
      ? task.sourceInventoryId
      : null
  const normalizedSourceInventoryFindingId =
    typeof task.sourceInventoryFindingId === "string" &&
    task.sourceInventoryFindingId
      ? task.sourceInventoryFindingId
      : null
  const hasInventorySource = Boolean(
    normalizedSourceInventoryId && normalizedSourceInventoryFindingId
  )
  const createdAt =
    typeof task.createdAt === "string"
      ? task.createdAt
      : new Date(0).toISOString()
  const updatedAt =
    typeof task.updatedAt === "string" ? task.updatedAt : createdAt
  const completedAt =
    typeof task.completedAt === "string"
      ? task.completedAt
      : task.status === "COMPLETED"
        ? updatedAt
        : null
  const acceptanceStatus =
    task.acceptanceStatus === "NOT_READY" ||
    task.acceptanceStatus === "PENDING" ||
    task.acceptanceStatus === "IN_REWORK" ||
    task.acceptanceStatus === "ACCEPTED" ||
    task.acceptanceStatus === "WRITTEN_OFF"
      ? task.acceptanceStatus
      : task.status === "COMPLETED"
        ? "PENDING"
        : "NOT_READY"
  return {
    ...task,
    kind: task.kind === "REWORK" ? "REWORK" : "REPAIR",
    origin:
      task.origin === "ESTIMATE" ||
      task.origin === "DIRECT_REPAIR" ||
      task.origin === "INVENTORY"
        ? task.origin
        : task.sourceEstimateId
          ? "ESTIMATE"
          : "DIRECT_REPAIR",
    acceptanceStatus,
    startedAt: typeof task.startedAt === "string" ? task.startedAt : null,
    completedAt,
    acceptanceDecidedAt:
      typeof task.acceptanceDecidedAt === "string"
        ? task.acceptanceDecidedAt
        : null,
    acceptanceDecidedBy:
      typeof task.acceptanceDecidedBy === "string" &&
      task.acceptanceDecidedBy.trim()
        ? task.acceptanceDecidedBy.trim()
        : null,
    acceptanceComment:
      typeof task.acceptanceComment === "string"
        ? task.acceptanceComment.trim() || null
        : null,
    createdAt,
    updatedAt,
    sourceEstimateVersion:
      typeof task.sourceEstimateVersion === "number"
        ? task.sourceEstimateVersion
        : null,
    sourceInventoryId: hasInventorySource ? normalizedSourceInventoryId : null,
    sourceInventoryFindingId: hasInventorySource
      ? normalizedSourceInventoryFindingId
      : null,
    sourceRepairTaskId:
      typeof task.sourceRepairTaskId === "string" && task.sourceRepairTaskId
        ? task.sourceRepairTaskId
        : null,
    sourceRepairTaskVersion:
      typeof task.sourceRepairTaskVersion === "number" &&
      Number.isFinite(task.sourceRepairTaskVersion)
        ? task.sourceRepairTaskVersion
        : null,
    subtasks: (Array.isArray(task.subtasks) ? task.subtasks : [])
      .map((subtask) => {
        const status = subtask.status ?? "WAITING"
        const startedAt =
          typeof subtask.startedAt === "string"
            ? subtask.startedAt
            : typeof subtask.activeStartedAt === "string"
              ? subtask.activeStartedAt
              : null
        const subtaskCompletedAt =
          typeof subtask.completedAt === "string"
            ? subtask.completedAt
            : status === "DONE" || status === "CANCELLED"
              ? completedAt
              : null
        const activeWorkSeconds = finiteNonNegativeInteger(
          subtask.activeWorkSeconds
        )
        const assigneeName =
          typeof subtask.assigneeName === "string" &&
          subtask.assigneeName.trim()
            ? subtask.assigneeName.trim()
            : null
        const rawAssignments = Array.isArray(subtask.assignments)
          ? subtask.assignments
          : []
        const assignments = rawAssignments.map((assignment, index) =>
          normalizeAssignment(assignment, {
            id: `${subtask.id}:assignment:${index + 1}`,
            assignedAt: startedAt ?? createdAt,
            startedAt,
            completedAt: subtaskCompletedAt,
            activeWorkSeconds,
            status,
            assigneeName,
          })
        )
        if (assignments.length === 0 && assigneeName) {
          assignments.push(
            normalizeAssignment(null, {
              id: `${subtask.id}:compatibility-assignment`,
              assignedAt: startedAt ?? createdAt,
              startedAt,
              completedAt: subtaskCompletedAt,
              activeWorkSeconds,
              status,
              assigneeName,
            })
          )
        }
        return {
          ...subtask,
          kind: subtask.kind ?? "REPAIR_WORK",
          status,
          queuePosition:
            typeof subtask.queuePosition === "number" &&
            Number.isFinite(subtask.queuePosition)
              ? subtask.queuePosition
              : subtask.sortOrder,
          plannedDurationMinutes:
            typeof subtask.plannedDurationMinutes === "number" &&
            Number.isFinite(subtask.plannedDurationMinutes) &&
            subtask.plannedDurationMinutes >= 0
              ? subtask.plannedDurationMinutes
              : null,
          photoRequired: Boolean(subtask.photoRequired),
          startedAt,
          completedAt: subtaskCompletedAt,
          activeStartedAt:
            typeof subtask.activeStartedAt === "string"
              ? subtask.activeStartedAt
              : null,
          activeWorkSeconds,
          workerGroup: subtask.workerGroup
            ? {
                id: subtask.workerGroup.id,
                name: subtask.workerGroup.name,
              }
            : assigneeName
              ? {
                  id: `${subtask.id}:compatibility-group`,
                  name: "Совместимое назначение",
                }
              : null,
          assignments,
          resultMedia: Array.isArray(subtask.resultMedia)
            ? subtask.resultMedia.map((media) => structuredClone(media))
            : [],
          assigneeName,
        }
      })
      .sort((left, right) => left.sortOrder - right.sortOrder),
  }
}

function accumulatedActiveSeconds(
  activeWorkSeconds: number,
  activeStartedAt: string | null,
  now: string
) {
  if (!activeStartedAt) {
    return activeWorkSeconds
  }
  const started = Date.parse(activeStartedAt)
  const ended = Date.parse(now)
  if (!Number.isFinite(started) || !Number.isFinite(ended)) {
    return activeWorkSeconds
  }
  return activeWorkSeconds + Math.max(0, Math.floor((ended - started) / 1000))
}

function firstUnfinishedSubtask(task: RepairTaskDto) {
  return task.subtasks
    .slice()
    .sort((left, right) => left.sortOrder - right.sortOrder)
    .find(
      (subtask) => subtask.status !== "DONE" && subtask.status !== "CANCELLED"
    )
}

function compareQueueEntries(
  left: { task: RepairTaskDto; subtask: RepairTaskDto["subtasks"][number] },
  right: { task: RepairTaskDto; subtask: RepairTaskDto["subtasks"][number] }
) {
  return (
    left.subtask.queuePosition - right.subtask.queuePosition ||
    left.task.createdAt.localeCompare(right.task.createdAt) ||
    left.task.id.localeCompare(right.task.id) ||
    left.subtask.sortOrder - right.subtask.sortOrder ||
    left.subtask.id.localeCompare(right.subtask.id)
  )
}

function firstWaitingRealEntry(
  tasks: RepairTaskDto[],
  warehouseId: string,
  queueIdentity: string
) {
  return tasks
    .filter(
      (task) =>
        task.warehouseId === warehouseId &&
        (task.status === "QUEUED" || task.status === "IN_PROGRESS")
    )
    .map((task) => ({ task, subtask: firstUnfinishedSubtask(task) }))
    .filter(
      (
        entry
      ): entry is {
        task: RepairTaskDto
        subtask: RepairTaskDto["subtasks"][number]
      } =>
        entry.subtask?.status === "WAITING" &&
        storedQueueIdentity(entry.subtask) === queueIdentity
    )
    .sort(compareQueueEntries)[0]
}

function targetQueueIdentity(
  subtask: RepairTaskDto["subtasks"][number],
  command: RepairTaskMoveEntryCommand
) {
  return storedQueueIdentity({
    ...subtask,
    queueCode: command.targetQueueCode?.trim() || null,
    routeQueueKind: command.targetRouteQueueKind,
  })
}

function cloneTask(task: RepairTaskDto) {
  return {
    ...structuredClone(task),
    authorName: normalizeAuthorName(task.authorName),
    subtasks: task.subtasks
      .slice()
      .sort((left, right) => left.sortOrder - right.sortOrder)
      .map((subtask) => structuredClone(subtask)),
  }
}

function validateDateOnly(value: string | null) {
  if (!value) {
    return
  }
  const match = /^(\d{4})-(\d{2})-(\d{2})$/.exec(value)
  if (!match) {
    throw new Error("Дата задания должна быть календарной датой")
  }
  const parsed = new Date(
    Date.UTC(Number(match[1]), Number(match[2]) - 1, Number(match[3]))
  )
  if (
    parsed.getUTCFullYear() !== Number(match[1]) ||
    parsed.getUTCMonth() !== Number(match[2]) - 1 ||
    parsed.getUTCDate() !== Number(match[3])
  ) {
    throw new Error("Дата задания должна быть календарной датой")
  }
}

export class LocalStorageRepairTasksAdapter implements RepairTasksClient {
  private readonly rentalItemsClient: RepairTaskRentalItemsClient

  constructor(rentalItemsClient: RepairTaskRentalItemsClient) {
    this.rentalItemsClient = rentalItemsClient
  }

  async list(warehouseId: string) {
    return readEnvelope()
      .tasks.filter((task) => task.warehouseId === warehouseId)
      .sort((left, right) => right.updatedAt.localeCompare(left.updatedAt))
      .map(cloneTask)
  }

  async listPendingAcceptance(warehouseId: string) {
    return readEnvelope()
      .tasks.filter(
        (task) =>
          task.warehouseId === warehouseId &&
          task.status === "COMPLETED" &&
          task.acceptanceStatus === "PENDING"
      )
      .sort((left, right) =>
        (right.completedAt ?? right.updatedAt).localeCompare(
          left.completedAt ?? left.updatedAt
        )
      )
      .map(cloneTask)
  }

  async listWriteOffs(warehouseId: string) {
    const tasks = readEnvelope().tasks
    return tasks
      .filter(
        (task) =>
          task.warehouseId === warehouseId &&
          task.acceptanceStatus === "WRITTEN_OFF" &&
          !tasks.some(
            (child) =>
              child.sourceRepairTaskId === task.id &&
              child.acceptanceStatus === "WRITTEN_OFF"
          )
      )
      .sort((left, right) =>
        (right.acceptanceDecidedAt ?? right.updatedAt).localeCompare(
          left.acceptanceDecidedAt ?? left.updatedAt
        )
      )
      .map(cloneTask)
  }

  async getById(taskId: string, warehouseId: string) {
    const task = readEnvelope().tasks.find(
      (candidate) =>
        candidate.id === taskId && candidate.warehouseId === warehouseId
    )
    return task ? cloneTask(task) : null
  }

  async getBySourceEstimateId(sourceEstimateId: string, warehouseId: string) {
    const task = readEnvelope().tasks.find(
      (candidate) =>
        candidate.sourceEstimateId === sourceEstimateId &&
        candidate.kind === "REPAIR" &&
        candidate.warehouseId === warehouseId
    )
    return task ? cloneTask(task) : null
  }

  async getByInventoryFinding(
    sourceInventoryId: string,
    sourceInventoryFindingId: string,
    warehouseId: string
  ) {
    const task = readEnvelope().tasks.find(
      (candidate) =>
        candidate.sourceInventoryId === sourceInventoryId &&
        candidate.sourceInventoryFindingId === sourceInventoryFindingId &&
        candidate.kind === "REPAIR" &&
        candidate.warehouseId === warehouseId
    )
    return task ? cloneTask(task) : null
  }

  async saveDraft(command: RepairTaskWriteCommand) {
    return runSerializedMutation(() => this.persist(command, "DRAFT"))
  }

  async queue(command: RepairTaskWriteCommand) {
    return runSerializedMutation(() => this.persist(command, "QUEUED"))
  }

  async updateSubtasks(command: RepairTaskSubtasksCommand) {
    return runSerializedMutation(async () => {
      const envelope = readEnvelope()
      const existing = envelope.tasks.find(
        (task) =>
          task.id === command.taskId && task.warehouseId === command.warehouseId
      )
      if (!existing) {
        throw new Error("Задание не найдено")
      }
      if (existing.version !== command.expectedVersion) {
        throw new Error(
          "Задание было изменено. Обновите данные и повторите действие"
        )
      }
      if (
        existing.status !== "QUEUED" ||
        existing.startedAt !== null ||
        existing.subtasks.some((subtask) => subtask.status !== "WAITING")
      ) {
        throw new Error(
          "Порядок подзаданий можно менять только до начала ремонта"
        )
      }
      const orderedIds = command.orderedSubtaskIds
      const existingIds = existing.subtasks.map((subtask) => subtask.id)
      if (
        orderedIds.length !== existingIds.length ||
        new Set(orderedIds).size !== orderedIds.length ||
        orderedIds.some((id) => !existingIds.includes(id))
      ) {
        throw new Error("Передан неполный или некорректный порядок подзаданий")
      }
      if (orderedIds.every((id, index) => id === existingIds[index])) {
        return cloneTask(existing)
      }
      const subtaskById = new Map(
        existing.subtasks.map((subtask) => [subtask.id, subtask])
      )
      const saved: RepairTaskDto = {
        ...existing,
        version: existing.version + 1,
        subtasks: orderedIds.map((id, index) => ({
          ...subtaskById.get(id)!,
          sortOrder: (index + 1) * 10,
        })),
        updatedAt: new Date().toISOString(),
      }
      writeEnvelope({
        ...envelope,
        revision: envelope.revision + 1,
        tasks: envelope.tasks.map((task) =>
          task.id === saved.id ? saved : task
        ),
      })
      return cloneTask(saved)
    })
  }

  async moveEntry(command: RepairTaskMoveEntryCommand) {
    return runSerializedMutation(() =>
      this.mutateEntry(command, (task, subtask) => {
        if (subtask.status !== "WAITING" && subtask.status !== "PAUSED") {
          throw new Error(
            "Перемещать можно только ожидающее или приостановленное подзадание"
          )
        }
        if (!Number.isFinite(command.targetQueuePosition)) {
          throw new Error("Некорректная позиция в очереди")
        }
        const targetIdentity = targetQueueIdentity(subtask, command)
        const duplicates = task.subtasks.filter(
          (candidate) =>
            candidate.id !== subtask.id &&
            candidate.status !== "DONE" &&
            candidate.status !== "CANCELLED" &&
            storedQueueIdentity(candidate) === targetIdentity
        )
        const duplicate = duplicates[0]
        if (duplicate) {
          const firstUnfinished = firstUnfinishedSubtask(task)
          const canSwapRealWithFuture =
            firstUnfinished?.id === subtask.id &&
            duplicates.length === 1 &&
            subtask.status === "WAITING" &&
            duplicate.status === "WAITING" &&
            duplicate.sortOrder > subtask.sortOrder
          if (!canSwapRealWithFuture) {
            throw new Error(
              "В маршруте задания уже есть незавершённый этап этой очереди"
            )
          }
          const currentSortOrder = subtask.sortOrder
          subtask.sortOrder = duplicate.sortOrder
          duplicate.sortOrder = currentSortOrder
          duplicate.queuePosition = command.targetQueuePosition
          task.subtasks.sort((left, right) => left.sortOrder - right.sortOrder)
          return task
        }
        subtask.queueCode = command.targetQueueCode?.trim() || null
        subtask.routeQueueKind = command.targetRouteQueueKind
        subtask.queuePosition = command.targetQueuePosition
        return task
      })
    )
  }

  async takeEntry(command: RepairTaskTakeEntryCommand) {
    return runSerializedMutation(() =>
      this.mutateEntry(command, (task, subtask, now, envelope) => {
        const groupName = command.workerGroup.name.trim()
        if (!command.workerGroup.id || !groupName) {
          throw new Error("Выберите рабочую группу")
        }
        const workers = command.workers
          .map((worker) => ({ ...worker, name: worker.name.trim() }))
          .filter((worker) => worker.id && worker.name)
        if (workers.length === 0) {
          throw new Error("В рабочей группе нет активных исполнителей")
        }
        if (
          new Set(workers.map((worker) => worker.id)).size !== workers.length
        ) {
          throw new Error("Исполнители рабочей группы не должны повторяться")
        }
        const firstUnfinished = firstUnfinishedSubtask(task)
        if (
          firstUnfinished?.id !== subtask.id ||
          subtask.status !== "WAITING"
        ) {
          throw new Error(
            "В работу можно взять только первое ожидающее подзадание"
          )
        }
        const queueFirst = firstWaitingRealEntry(
          envelope.tasks,
          command.warehouseId,
          storedQueueIdentity(subtask)
        )
        if (
          queueFirst?.task.id !== task.id ||
          queueFirst.subtask.id !== subtask.id
        ) {
          throw new Error(
            "Сначала возьмите верхнее доступное подзадание этой очереди"
          )
        }
        subtask.status = "IN_PROGRESS"
        subtask.startedAt ??= now
        subtask.activeStartedAt = now
        subtask.workerGroup = {
          id: command.workerGroup.id,
          name: groupName,
        }
        subtask.assignments = workers.map((worker) => ({
          id: createOpaqueId("repair-assignment"),
          worker,
          assignedAt: now,
          startedAt: now,
          pausedAt: null,
          finishedAt: null,
          activeStartedAt: now,
          activeWorkSeconds: 0,
          status: "ACTIVE",
        }))
        subtask.assigneeName = workers.map((worker) => worker.name).join(", ")
        task.status = "IN_PROGRESS"
        task.startedAt ??= now
        return task
      })
    )
  }

  async pauseEntry(command: RepairTaskEntryRefCommand) {
    return runSerializedMutation(() =>
      this.mutateEntry(command, (task, subtask, now) => {
        if (subtask.status !== "IN_PROGRESS") {
          throw new Error(
            "Поставить на паузу можно только выполняемое подзадание"
          )
        }
        subtask.activeWorkSeconds = accumulatedActiveSeconds(
          subtask.activeWorkSeconds,
          subtask.activeStartedAt,
          now
        )
        subtask.activeStartedAt = null
        subtask.status = "PAUSED"
        subtask.assignments.forEach((assignment) => {
          if (assignment.status !== "ACTIVE") {
            return
          }
          assignment.activeWorkSeconds = accumulatedActiveSeconds(
            assignment.activeWorkSeconds,
            assignment.activeStartedAt,
            now
          )
          assignment.activeStartedAt = null
          assignment.pausedAt = now
          assignment.status = "PAUSED"
        })
        return task
      })
    )
  }

  async resumeEntry(command: RepairTaskEntryRefCommand) {
    return runSerializedMutation(() =>
      this.mutateEntry(command, (task, subtask, now) => {
        if (subtask.status !== "PAUSED") {
          throw new Error("Продолжить можно только приостановленное подзадание")
        }
        subtask.status = "IN_PROGRESS"
        subtask.activeStartedAt = now
        subtask.assignments.forEach((assignment) => {
          if (assignment.status !== "PAUSED") {
            return
          }
          assignment.status = "ACTIVE"
          assignment.activeStartedAt = now
          assignment.pausedAt = null
        })
        task.status = "IN_PROGRESS"
        task.startedAt ??= now
        return task
      })
    )
  }

  async completeEntry(command: RepairTaskCompleteEntryCommand) {
    return runSerializedMutation(() =>
      this.mutateEntry(command, (task, subtask, now) => {
        if (subtask.status !== "IN_PROGRESS") {
          throw new Error("Завершить можно только выполняемое подзадание")
        }
        if (command.resultMedia.length > 20) {
          throw new Error("К этапу можно прикрепить не более 20 фотографий")
        }
        if (subtask.photoRequired && command.resultMedia.length < 3) {
          throw new Error(
            "Для этого этапа прикрепите не менее 3 фотографий результата"
          )
        }
        if (
          subtask.assignments.length === 0 ||
          subtask.assignments.some((assignment) => assignment.worker === null)
        ) {
          throw new Error("У этапа нет полного назначения исполнителей")
        }
        subtask.activeWorkSeconds = accumulatedActiveSeconds(
          subtask.activeWorkSeconds,
          subtask.activeStartedAt,
          now
        )
        subtask.activeStartedAt = null
        subtask.status = "DONE"
        subtask.completedAt = now
        subtask.resultMedia = command.resultMedia.map((media) =>
          structuredClone(media)
        )
        subtask.assignments.forEach((assignment) => {
          assignment.activeWorkSeconds = accumulatedActiveSeconds(
            assignment.activeWorkSeconds,
            assignment.activeStartedAt,
            now
          )
          assignment.activeStartedAt = null
          assignment.pausedAt = null
          assignment.finishedAt = now
          assignment.status = "DONE"
        })
        const completed = task.subtasks.every(
          (candidate) =>
            candidate.status === "DONE" || candidate.status === "CANCELLED"
        )
        task.status = completed ? "COMPLETED" : "IN_PROGRESS"
        task.completedAt = completed ? now : null
        task.acceptanceStatus = completed ? "PENDING" : "NOT_READY"
        return task
      })
    )
  }

  async accept(command: RepairTaskAcceptCommand) {
    return runSerializedMutation(async () => {
      const envelope = readEnvelope()
      const existing = this.requirePendingAcceptance(envelope, command)
      const now = new Date().toISOString()
      const saved: RepairTaskDto = {
        ...cloneTask(existing),
        version: existing.version + 1,
        acceptanceStatus: "ACCEPTED",
        acceptanceDecidedAt: now,
        acceptanceDecidedBy: CURRENT_MOCK_AUTHOR_NAME,
        acceptanceComment: command.comment.trim() || null,
        updatedAt: now,
      }
      const cascaded = this.cascadeReworkDecision(
        envelope,
        saved,
        "ACCEPTED",
        now,
        command.comment.trim() || null
      )
      await this.commitTasksWithRentalStatus(envelope, cascaded, saved, "FREE")
      return cloneTask(saved)
    })
  }

  async writeOff(command: RepairTaskWriteOffCommand) {
    return runSerializedMutation(async () => {
      const reason = command.reason.trim()
      if (!reason) {
        throw new Error("Укажите причину списания")
      }
      const envelope = readEnvelope()
      const existing = this.requirePendingAcceptance(envelope, command)
      const now = new Date().toISOString()
      const saved: RepairTaskDto = {
        ...cloneTask(existing),
        version: existing.version + 1,
        acceptanceStatus: "WRITTEN_OFF",
        acceptanceDecidedAt: now,
        acceptanceDecidedBy: CURRENT_MOCK_AUTHOR_NAME,
        acceptanceComment: reason,
        updatedAt: now,
      }
      const cascaded = this.cascadeReworkDecision(
        envelope,
        saved,
        "WRITTEN_OFF",
        now,
        reason
      )
      await this.commitTasksWithRentalStatus(
        envelope,
        cascaded,
        saved,
        "WRITTEN_OFF"
      )
      return cloneTask(saved)
    })
  }

  async earlyWriteOff(command: RepairTaskEarlyWriteOffCommand) {
    return runSerializedMutation(async () => {
      const writeOffReason = command.writeOffReason.trim()
      if (!writeOffReason) {
        throw new Error("Укажите причину списания")
      }
      if (!command.rentalItemId) {
        throw new Error("Выберите бытовку")
      }
      if (command.origin === "DIRECT_REPAIR" && command.sourceEstimateId) {
        throw new Error("Прямое задание не может быть связано со сметой")
      }
      if (!command.sourceEstimateId && command.sourceEstimateVersion !== null) {
        throw new Error("Версия сметы без идентификатора недопустима")
      }
      validateDateOnly(command.dispatchDate)
      assertRepairTaskSubtasksValid(command.subtasks)
      if (command.media.length > 20) {
        throw new Error(
          "К одному заданию можно прикрепить не более 20 фотографий"
        )
      }

      const rentalItem = await this.rentalItemsClient.resolveById(
        command.warehouseId,
        command.rentalItemId
      )
      if (!rentalItem) {
        throw new Error("Бытовка не найдена на выбранном складе")
      }

      const envelope = readEnvelope()
      const taskById = command.taskId
        ? envelope.tasks.find((task) => task.id === command.taskId)
        : null
      if (taskById && taskById.warehouseId !== command.warehouseId) {
        throw new Error("Задание принадлежит другому складу")
      }
      const taskByEstimate = command.sourceEstimateId
        ? envelope.tasks.find(
            (task) =>
              task.sourceEstimateId === command.sourceEstimateId &&
              task.kind === "REPAIR" &&
              task.warehouseId === command.warehouseId
          )
        : null
      if (taskById && taskByEstimate && taskById.id !== taskByEstimate.id) {
        throw new Error("Смета уже связана с другим заданием")
      }
      const existing = taskById ?? taskByEstimate
      if (command.taskId && !existing) {
        throw new Error("Задание не найдено")
      }
      if (existing && existing.rentalItemId !== command.rentalItemId) {
        throw new Error("Задание связано с другой бытовкой")
      }
      if (existing && existing.status !== "DRAFT") {
        throw new Error("Списать можно только черновик задания")
      }
      if (
        existing &&
        (command.expectedVersion === null ||
          existing.version !== command.expectedVersion)
      ) {
        throw new Error(
          "Задание было изменено. Обновите данные и повторите действие"
        )
      }
      if (!existing && command.expectedVersion !== null) {
        throw new Error("Версия нового задания должна быть пустой")
      }

      const now = new Date().toISOString()
      const saved: RepairTaskDto = {
        id: existing?.id ?? createOpaqueId("repair-task"),
        version: (existing?.version ?? 0) + 1,
        status: "CANCELLED",
        kind: "REPAIR",
        origin: command.origin,
        acceptanceStatus: "WRITTEN_OFF",
        startedAt: existing?.startedAt ?? null,
        completedAt: now,
        acceptanceDecidedAt: now,
        acceptanceDecidedBy: CURRENT_MOCK_AUTHOR_NAME,
        acceptanceComment: writeOffReason,
        warehouseId: command.warehouseId,
        rentalItemId: rentalItem.id,
        cabinNumber: rentalItem.number,
        authorName: existing
          ? normalizeAuthorName(existing.authorName)
          : CURRENT_MOCK_AUTHOR_NAME,
        reason: command.reason.trim(),
        dispatchDate: command.dispatchDate,
        comment: command.comment.trim(),
        media: command.media.map((media) => structuredClone(media)),
        subtasks: command.subtasks.map((subtask, index) => ({
          ...structuredClone(subtask),
          sortOrder: (index + 1) * 10,
        })),
        sourceEstimateId:
          command.origin === "ESTIMATE" ? command.sourceEstimateId : null,
        sourceEstimateVersion:
          command.origin === "ESTIMATE" ? command.sourceEstimateVersion : null,
        sourceInventoryId: null,
        sourceInventoryFindingId: null,
        sourceRepairTaskId: null,
        sourceRepairTaskVersion: null,
        createdAt: existing?.createdAt ?? now,
        updatedAt: now,
      }
      await this.commitWithRentalStatus(envelope, saved, "WRITTEN_OFF")
      return cloneTask(saved)
    })
  }

  async upsertFromEstimate(command: RepairTaskFromEstimateCommand) {
    return runSerializedMutation(async () => {
      assertRepairTaskSubtasksValid(command.subtasks)
      const envelope = readEnvelope()
      const existing = envelope.tasks.find(
        (task) =>
          task.kind === "REPAIR" &&
          task.sourceEstimateId === command.sourceEstimateId
      )
      if (existing) {
        if (existing.warehouseId !== command.warehouseId) {
          throw new Error("Задание сметы принадлежит другому складу")
        }
        return cloneTask(existing)
      }
      const now = new Date().toISOString()
      const saved: RepairTaskDto = {
        id: createOpaqueId("repair-task"),
        version: 1,
        status: "QUEUED",
        kind: "REPAIR",
        origin: "ESTIMATE",
        acceptanceStatus: "NOT_READY",
        startedAt: null,
        completedAt: null,
        acceptanceDecidedAt: null,
        acceptanceDecidedBy: null,
        acceptanceComment: null,
        warehouseId: command.warehouseId,
        rentalItemId: command.rentalItemId,
        cabinNumber: command.cabinNumber,
        authorName: normalizeAuthorName(command.authorName),
        reason: command.reason.trim(),
        dispatchDate: command.dispatchDate,
        comment: command.comment.trim(),
        media: command.media.map((media) => structuredClone(media)),
        subtasks: command.subtasks.map((subtask, index) => ({
          ...structuredClone(subtask),
          sortOrder: (index + 1) * 10,
        })),
        sourceEstimateId: command.sourceEstimateId,
        sourceEstimateVersion: command.sourceEstimateVersion,
        sourceInventoryId: null,
        sourceInventoryFindingId: null,
        sourceRepairTaskId: null,
        sourceRepairTaskVersion: null,
        createdAt: now,
        updatedAt: now,
      }
      await this.commitWithRentalStatus(
        envelope,
        saved,
        "REPAIR",
        command.allowWaitingEstimateConfirmation
      )
      return cloneTask(saved)
    })
  }

  async upsertByInventoryFinding(
    command: RepairTaskFromInventoryFindingCommand
  ) {
    const sourceInventoryId = command.sourceInventoryId.trim()
    const sourceInventoryFindingId = command.sourceInventoryFindingId.trim()
    if (!sourceInventoryId || !sourceInventoryFindingId) {
      throw new Error("Не указан источник работ инвентаризации")
    }
    if (!command.rentalItemId) {
      throw new Error("Не указана бытовка для работ инвентаризации")
    }
    validateDateOnly(command.dispatchDate)
    assertRepairTaskSubtasksValid(command.subtasks)
    if (
      !command.subtasks.some(
        (subtask) =>
          subtask.kind === "REPAIR_WORK" &&
          subtask.workLines.length + subtask.materialLines.length > 0
      )
    ) {
      throw new Error("В результате инвентаризации нет работ для передачи")
    }
    if (command.media.length > 20) {
      throw new Error(
        "К одному заданию можно прикрепить не более 20 фотографий"
      )
    }

    const rentalItem = await this.rentalItemsClient.resolveById(
      command.warehouseId,
      command.rentalItemId
    )
    if (!rentalItem) {
      throw new Error("Бытовка не найдена на выбранном складе")
    }
    if (
      rentalItem.id !== command.rentalItemId ||
      rentalItem.number !== command.cabinNumber
    ) {
      throw new Error("Данные бытовки изменились после инвентаризации")
    }

    return runInventoryPublicationSaga(async () => {
      const prepared = await runSerializedMutation(async () => {
        const envelope = readEnvelope()
        const existing = envelope.tasks.find(
          (task) =>
            task.kind === "REPAIR" &&
            task.sourceInventoryId === sourceInventoryId &&
            task.sourceInventoryFindingId === sourceInventoryFindingId
        )
        if (existing) {
          if (existing.warehouseId !== command.warehouseId) {
            throw new Error("Задание инвентаризации принадлежит другому складу")
          }
          if (
            existing.rentalItemId !== rentalItem.id ||
            existing.cabinNumber !== rentalItem.number
          ) {
            throw new Error("Источник инвентаризации связан с другой бытовкой")
          }
          return { task: cloneTask(existing), created: false }
        }

        const now = new Date().toISOString()
        const saved: RepairTaskDto = {
          id: createOpaqueId("repair-task"),
          version: 1,
          status: "QUEUED",
          kind: "REPAIR",
          origin: "INVENTORY",
          acceptanceStatus: "NOT_READY",
          startedAt: null,
          completedAt: null,
          acceptanceDecidedAt: null,
          acceptanceDecidedBy: null,
          acceptanceComment: null,
          warehouseId: command.warehouseId,
          rentalItemId: rentalItem.id,
          cabinNumber: rentalItem.number,
          authorName: normalizeAuthorName(command.authorName),
          reason: command.reason.trim(),
          dispatchDate: command.dispatchDate,
          comment: command.comment.trim(),
          media: command.media.map((media) => structuredClone(media)),
          subtasks: command.subtasks.map((subtask, index) => ({
            ...structuredClone(subtask),
            sortOrder: (index + 1) * 10,
          })),
          sourceEstimateId: null,
          sourceEstimateVersion: null,
          sourceInventoryId,
          sourceInventoryFindingId,
          sourceRepairTaskId: null,
          sourceRepairTaskVersion: null,
          createdAt: now,
          updatedAt: now,
        }
        writeEnvelope({
          ...envelope,
          revision: envelope.revision + 1,
          tasks: [...envelope.tasks, saved],
        })
        return { task: cloneTask(saved), created: true }
      })

      try {
        await this.rentalItemsClient.updateStatus({
          warehouseId: prepared.task.warehouseId,
          rentalItemId: prepared.task.rentalItemId,
          status: "REPAIR",
        })
      } catch (error) {
        if (prepared.created) {
          await runSerializedMutation(async () => {
            const envelope = readEnvelope()
            const current = envelope.tasks.find(
              (task) => task.id === prepared.task.id
            )
            if (
              current?.version === prepared.task.version &&
              current.status === "QUEUED" &&
              current.startedAt === null
            ) {
              writeEnvelope({
                ...envelope,
                revision: envelope.revision + 1,
                tasks: envelope.tasks.filter(
                  (task) => task.id !== prepared.task.id
                ),
              })
            }
          })
        }
        throw error
      }

      return cloneTask(prepared.task)
    })
  }

  async syncFromEstimate(command: RepairTaskSyncFromEstimateCommand) {
    return runSerializedMutation(async () => {
      assertRepairTaskSubtasksValid(command.subtasks)
      const envelope = readEnvelope()
      const existing = envelope.tasks.find(
        (task) =>
          task.kind === "REPAIR" &&
          task.sourceEstimateId === command.sourceEstimateId
      )
      if (existing && existing.warehouseId !== command.warehouseId) {
        throw new Error("Задание сметы принадлежит другому складу")
      }
      if (existing && existing.rentalItemId !== command.rentalItemId) {
        throw new Error("Задание сметы связано с другой бытовкой")
      }
      if (existing) {
        if (
          command.expectedTaskVersion === null ||
          existing.version !== command.expectedTaskVersion
        ) {
          throw new Error(
            "Задание было изменено. Обновите данные и повторите действие"
          )
        }
        if (
          existing.status !== "QUEUED" ||
          existing.startedAt !== null ||
          existing.subtasks.some((subtask) => subtask.status !== "WAITING")
        ) {
          throw new Error("Начатое задание нельзя изменить из сметы")
        }
      } else if (command.expectedTaskVersion !== null) {
        throw new Error("Связанное задание не найдено")
      }

      const now = new Date().toISOString()
      const existingSubtaskById = new Map(
        existing?.subtasks.map((subtask) => [subtask.id, subtask]) ?? []
      )
      const saved: RepairTaskDto = {
        id: existing?.id ?? createOpaqueId("repair-task"),
        version: (existing?.version ?? 0) + 1,
        status: "QUEUED",
        kind: "REPAIR",
        origin: "ESTIMATE",
        acceptanceStatus: existing?.acceptanceStatus ?? "NOT_READY",
        startedAt: null,
        completedAt: null,
        acceptanceDecidedAt: existing?.acceptanceDecidedAt ?? null,
        acceptanceDecidedBy: existing?.acceptanceDecidedBy ?? null,
        acceptanceComment: existing?.acceptanceComment ?? null,
        warehouseId: command.warehouseId,
        rentalItemId: command.rentalItemId,
        cabinNumber: command.cabinNumber,
        authorName: existing
          ? normalizeAuthorName(existing.authorName)
          : normalizeAuthorName(command.authorName),
        reason: command.reason.trim(),
        dispatchDate: command.dispatchDate,
        comment: command.comment.trim(),
        media: command.media.map((media) => structuredClone(media)),
        subtasks: command.subtasks.map((subtask, index) => {
          const current = existingSubtaskById.get(subtask.id)
          return {
            ...structuredClone(subtask),
            status: current?.status ?? subtask.status,
            sortOrder: (index + 1) * 10,
            queuePosition: current?.queuePosition ?? subtask.queuePosition,
            activeStartedAt:
              current?.activeStartedAt ?? subtask.activeStartedAt,
            activeWorkSeconds:
              current?.activeWorkSeconds ?? subtask.activeWorkSeconds,
            assigneeName: current?.assigneeName ?? subtask.assigneeName,
          }
        }),
        sourceEstimateId: command.sourceEstimateId,
        sourceEstimateVersion: command.sourceEstimateVersion,
        sourceInventoryId: null,
        sourceInventoryFindingId: null,
        sourceRepairTaskId: null,
        sourceRepairTaskVersion: null,
        createdAt: existing?.createdAt ?? now,
        updatedAt: now,
      }
      if (existing) {
        writeEnvelope({
          ...envelope,
          revision: envelope.revision + 1,
          tasks: envelope.tasks.map((task) =>
            task.id === existing.id ? saved : task
          ),
        })
      } else {
        await this.commitWithRentalStatus(envelope, saved, "REPAIR")
      }
      return cloneTask(saved)
    })
  }

  private async persist(
    command: RepairTaskWriteCommand,
    status: "DRAFT" | "QUEUED"
  ) {
    if (!command.rentalItemId) {
      throw new Error("Выберите бытовку")
    }
    validateDateOnly(command.dispatchDate)
    assertRepairTaskSubtasksValid(command.subtasks)
    if (command.media.length > 20) {
      throw new Error(
        "К одному заданию можно прикрепить не более 20 фотографий"
      )
    }

    const rentalItem = await this.rentalItemsClient.resolveById(
      command.warehouseId,
      command.rentalItemId
    )
    if (!rentalItem) {
      throw new Error("Бытовка не найдена на выбранном складе")
    }

    const envelope = readEnvelope()
    const taskById = command.taskId
      ? envelope.tasks.find((task) => task.id === command.taskId)
      : null
    if (taskById && taskById.warehouseId !== command.warehouseId) {
      throw new Error("Задание нельзя перенести на другой склад")
    }
    const existing = command.taskId
      ? envelope.tasks.find(
          (task) =>
            task.id === command.taskId &&
            task.warehouseId === command.warehouseId
        )
      : null
    if (command.taskId && !existing) {
      throw new Error("Задание не найдено")
    }
    if (
      existing &&
      ["IN_PROGRESS", "COMPLETED", "CANCELLED"].includes(existing.status)
    ) {
      throw new Error("Начатое или завершённое задание нельзя редактировать")
    }
    if (existing?.status === "QUEUED" && existing.startedAt !== null) {
      throw new Error("Начатое задание нельзя повторно поставить в очередь")
    }
    if (existing?.status === "QUEUED" && status === "DRAFT") {
      throw new Error(
        "Поставленное в очередь задание нельзя вернуть в черновик"
      )
    }
    if (
      existing &&
      (command.expectedVersion === null ||
        existing.version !== command.expectedVersion)
    ) {
      throw new Error(
        "Задание было изменено. Обновите данные и повторите действие"
      )
    }
    if (!existing && command.expectedVersion !== null) {
      throw new Error("Версия нового задания должна быть пустой")
    }

    const sourceRepair = command.sourceRepairTaskId
      ? envelope.tasks.find(
          (task) =>
            task.id === command.sourceRepairTaskId &&
            task.warehouseId === command.warehouseId
        )
      : null
    if (command.kind === "REWORK") {
      if (!sourceRepair || sourceRepair.id === existing?.id) {
        throw new Error("Исходное задание доработки не найдено")
      }
      if (sourceRepair.rentalItemId !== command.rentalItemId) {
        throw new Error("Доработка связана с другой бытовкой")
      }
      if (
        command.sourceRepairTaskVersion === null ||
        sourceRepair.version !== command.sourceRepairTaskVersion
      ) {
        throw new Error(
          "Исходное задание изменилось. Вернитесь в приёмку и повторите действие"
        )
      }
      if (
        sourceRepair.status !== "COMPLETED" ||
        sourceRepair.acceptanceStatus !== "PENDING"
      ) {
        throw new Error("Исходное задание больше не ожидает приёмки")
      }
      if (
        status === "QUEUED" &&
        envelope.tasks.some(
          (task) =>
            task.id !== existing?.id &&
            task.kind === "REWORK" &&
            task.sourceRepairTaskId === sourceRepair.id &&
            ["QUEUED", "IN_PROGRESS"].includes(task.status)
        )
      ) {
        throw new Error("Для задания уже создана активная доработка")
      }
    } else if (command.sourceRepairTaskId) {
      throw new Error("Обычное задание не может ссылаться на доработку")
    }

    const now = new Date().toISOString()
    const saved: RepairTaskDto = {
      id: existing?.id ?? createOpaqueId("repair-task"),
      version: (existing?.version ?? 0) + 1,
      status,
      kind: command.kind,
      origin: command.origin,
      acceptanceStatus: "NOT_READY",
      startedAt: existing?.startedAt ?? null,
      completedAt: null,
      acceptanceDecidedAt: null,
      acceptanceDecidedBy: null,
      acceptanceComment: null,
      warehouseId: command.warehouseId,
      rentalItemId: rentalItem.id,
      cabinNumber: rentalItem.number,
      authorName: existing
        ? normalizeAuthorName(existing.authorName)
        : CURRENT_MOCK_AUTHOR_NAME,
      reason: command.reason.trim(),
      dispatchDate: command.dispatchDate,
      comment: command.comment.trim(),
      media: command.media.map((media) => structuredClone(media)),
      subtasks: command.subtasks.map((subtask, index) => ({
        ...structuredClone(subtask),
        sortOrder: (index + 1) * 10,
      })),
      sourceEstimateId: command.sourceEstimateId,
      sourceEstimateVersion: command.sourceEstimateVersion,
      sourceInventoryId: existing?.sourceInventoryId ?? null,
      sourceInventoryFindingId: existing?.sourceInventoryFindingId ?? null,
      sourceRepairTaskId: command.sourceRepairTaskId,
      sourceRepairTaskVersion: command.sourceRepairTaskVersion,
      createdAt: existing?.createdAt ?? now,
      updatedAt: now,
    }
    if (status === "QUEUED" && sourceRepair) {
      const updatedSource: RepairTaskDto = {
        ...cloneTask(sourceRepair),
        version: sourceRepair.version + 1,
        acceptanceStatus: "IN_REWORK",
        updatedAt: now,
      }
      saved.sourceRepairTaskVersion = updatedSource.version
      await this.commitTasksWithRentalStatus(
        envelope,
        [updatedSource, saved],
        saved,
        "REPAIR"
      )
    } else if (status === "QUEUED") {
      await this.commitWithRentalStatus(envelope, saved, "REPAIR")
    } else {
      writeEnvelope({
        ...envelope,
        revision: envelope.revision + 1,
        tasks: existing
          ? envelope.tasks.map((task) => (task.id === saved.id ? saved : task))
          : [...envelope.tasks, saved],
      })
    }
    return cloneTask(saved)
  }

  private requirePendingAcceptance(
    envelope: RepairTasksStorageEnvelope,
    command: Pick<
      RepairTaskAcceptCommand,
      "taskId" | "expectedVersion" | "warehouseId"
    >
  ) {
    const existing = envelope.tasks.find(
      (task) =>
        task.id === command.taskId && task.warehouseId === command.warehouseId
    )
    if (!existing) {
      throw new Error("Задание не найдено")
    }
    if (existing.version !== command.expectedVersion) {
      throw new Error(
        "Задание было изменено. Обновите данные и повторите действие"
      )
    }
    if (
      existing.status !== "COMPLETED" ||
      existing.acceptanceStatus !== "PENDING"
    ) {
      throw new Error("Задание больше не ожидает приёмки")
    }
    return existing
  }

  private cascadeReworkDecision(
    envelope: RepairTasksStorageEnvelope,
    child: RepairTaskDto,
    acceptanceStatus: "ACCEPTED" | "WRITTEN_OFF",
    decidedAt: string,
    comment: string | null
  ) {
    const result = [child]
    const visited = new Set([child.id])
    let sourceId = child.sourceRepairTaskId
    while (sourceId && !visited.has(sourceId)) {
      visited.add(sourceId)
      const source = envelope.tasks.find((task) => task.id === sourceId)
      if (!source) break
      const savedSource: RepairTaskDto = {
        ...cloneTask(source),
        version: source.version + 1,
        acceptanceStatus,
        acceptanceDecidedAt: decidedAt,
        acceptanceDecidedBy: CURRENT_MOCK_AUTHOR_NAME,
        acceptanceComment: comment,
        updatedAt: decidedAt,
      }
      result.push(savedSource)
      sourceId = source.sourceRepairTaskId
    }
    return result
  }

  private async commitWithRentalStatus(
    envelope: RepairTasksStorageEnvelope,
    saved: RepairTaskDto,
    status: RentalItemStatus,
    allowWaitingEstimateConfirmation = false
  ) {
    return this.commitTasksWithRentalStatus(
      envelope,
      [saved],
      saved,
      status,
      allowWaitingEstimateConfirmation
    )
  }

  private async commitTasksWithRentalStatus(
    envelope: RepairTasksStorageEnvelope,
    savedTasks: RepairTaskDto[],
    rentalTask: RepairTaskDto,
    status: RentalItemStatus,
    allowWaitingEstimateConfirmation = false
  ) {
    const savedById = new Map(savedTasks.map((task) => [task.id, task]))
    const committedEnvelope: RepairTasksStorageEnvelope = {
      ...envelope,
      revision: envelope.revision + 1,
      tasks: [
        ...envelope.tasks.map((task) => savedById.get(task.id) ?? task),
        ...savedTasks.filter(
          (savedTask) =>
            !envelope.tasks.some((task) => task.id === savedTask.id)
        ),
      ],
    }
    writeEnvelope(committedEnvelope)
    try {
      await this.rentalItemsClient.updateStatus({
        warehouseId: rentalTask.warehouseId,
        rentalItemId: rentalTask.rentalItemId,
        status,
        allowWaitingEstimateConfirmation,
      })
    } catch (error) {
      writeEnvelope({
        ...envelope,
        revision: committedEnvelope.revision + 1,
      })
      throw error
    }
  }

  private async mutateEntry(
    command: RepairTaskEntryRefCommand,
    mutation: (
      task: RepairTaskDto,
      subtask: RepairTaskDto["subtasks"][number],
      now: string,
      envelope: RepairTasksStorageEnvelope
    ) => RepairTaskDto
  ) {
    const envelope = readEnvelope()
    const existing = envelope.tasks.find(
      (task) =>
        task.id === command.taskId && task.warehouseId === command.warehouseId
    )
    if (!existing) {
      throw new Error("Задание не найдено")
    }
    if (existing.version !== command.expectedVersion) {
      throw new Error(
        "Задание было изменено. Обновите доску и повторите действие"
      )
    }
    if (existing.status !== "QUEUED" && existing.status !== "IN_PROGRESS") {
      throw new Error("Задание больше не находится в активной работе")
    }
    const saved = cloneTask(existing)
    const subtask = saved.subtasks.find(
      (candidate) => candidate.id === command.subtaskId
    )
    if (!subtask) {
      throw new Error("Подзадание не найдено")
    }
    const now = new Date().toISOString()
    const mutated = mutation(saved, subtask, now, envelope)
    mutated.version = existing.version + 1
    mutated.updatedAt = now
    if (
      existing.acceptanceStatus !== "PENDING" &&
      mutated.acceptanceStatus === "PENDING"
    ) {
      await this.commitWithRentalStatus(
        envelope,
        mutated,
        "WAITING_REPAIR_CHECK"
      )
    } else {
      writeEnvelope({
        ...envelope,
        revision: envelope.revision + 1,
        tasks: envelope.tasks.map((task) =>
          task.id === mutated.id ? mutated : task
        ),
      })
    }
    return cloneTask(mutated)
  }
}
