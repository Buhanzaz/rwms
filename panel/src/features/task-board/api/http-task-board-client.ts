import type {
  RepairTaskAssignmentDto,
  RepairTaskDto,
  RepairTaskSubtaskDto,
} from "@/features/repair-tasks/model/repair-task"
import type {
  TaskBoardEntryDto,
  TaskBoardQueueDto,
  TaskBoardSnapshotDto,
} from "@/features/task-board/model/task-board"
import { bearerRequest } from "@/lib/api-client"
import { getGatewayRuntimeConfig } from "@/lib/gateway-config"

const TASK_BOARD_API = getGatewayRuntimeConfig().taskBoardApiBaseUrl

type JsonRecord = Record<string, unknown>

function invalid(): never {
  throw new Error("Сервис доски заданий вернул некорректный ответ.")
}

function object(value: unknown): JsonRecord {
  if (!value || typeof value !== "object" || Array.isArray(value)) invalid()
  return value as JsonRecord
}

function list(value: unknown) {
  if (!Array.isArray(value)) invalid()
  return value
}

function text(value: unknown) {
  if (typeof value !== "string") invalid()
  return value
}

function nullableText(value: unknown) {
  return value === null ? null : text(value)
}

function integer(value: unknown) {
  if (!Number.isSafeInteger(value) || (value as number) < 0) invalid()
  return value as number
}

function token(value: string | null | undefined) {
  if (!value?.trim())
    throw new Error("Не получен токен доступа к доске заданий.")
  return value
}

function assignment(value: unknown): RepairTaskAssignmentDto {
  const source = object(value)
  const workerId = nullableText(source.workerId)
  const workerName = nullableText(source.workerName)
  const status = text(source.status)
  if (
    status !== "ACTIVE" &&
    status !== "PAUSED" &&
    status !== "DONE" &&
    status !== "CANCELLED"
  )
    invalid()
  return {
    id: text(source.id),
    worker: workerId ? { id: workerId, name: workerName ?? workerId } : null,
    assignedAt: text(source.assignedAt),
    startedAt: nullableText(source.startedAt),
    pausedAt: nullableText(source.pausedAt),
    finishedAt: nullableText(source.finishedAt),
    activeStartedAt: nullableText(source.startedAt),
    activeWorkSeconds: 0,
    status,
  }
}

function subtask(source: JsonRecord): RepairTaskSubtaskDto {
  const status = text(source.status)
  if (
    status !== "WAITING" &&
    status !== "IN_PROGRESS" &&
    status !== "PAUSED" &&
    status !== "DONE" &&
    status !== "CANCELLED"
  )
    invalid()
  const assignmentSources = list(source.assignments).map(object)
  const assignments = assignmentSources.map(assignment)
  const groupSource = assignmentSources.find((item) =>
    nullableText(item.workerGroupId)
  )
  const groupId = groupSource ? nullableText(groupSource.workerGroupId) : null
  return {
    id: text(source.id),
    kind: "REPAIR_WORK",
    status,
    workLines: [],
    materialLines: [],
    groupComment: nullableText(source.taskText) ?? "",
    queueCode: text(source.queueCode),
    queueId: nullableText(source.queueId),
    routeQueueKind: null,
    sortOrder: integer(source.routeIndex),
    queuePosition: integer(source.queuePosition),
    plannedDurationMinutes:
      source.plannedDurationMinutes === null
        ? null
        : integer(source.plannedDurationMinutes),
    photoRequired: false,
    startedAt: nullableText(source.activeStartedAt),
    completedAt: null,
    activeStartedAt: nullableText(source.activeStartedAt),
    activeWorkSeconds: integer(source.activeWorkSeconds),
    workerGroup: groupId
      ? {
          id: groupId,
          name: nullableText(groupSource?.workerGroupName) ?? groupId,
        }
      : null,
    assignments,
    resultMedia: [],
    assigneeName: null,
  }
}

function viewTask(source: JsonRecord, warehouseId: string): RepairTaskDto {
  const taskStatus = text(source.taskStatus)
  const status =
    taskStatus === "DONE"
      ? "COMPLETED"
      : taskStatus === "CANCELLED"
        ? "CANCELLED"
        : text(source.status) === "WAITING"
          ? "QUEUED"
          : "IN_PROGRESS"
  const id = nullableText(source.externalTaskId) ?? text(source.taskId)
  return {
    id,
    version: integer(source.taskVersion),
    status,
    kind: "REPAIR",
    origin: "DIRECT_REPAIR",
    acceptanceStatus: "NOT_READY",
    startedAt: nullableText(source.activeStartedAt),
    completedAt: null,
    acceptanceDecidedAt: null,
    acceptanceDecidedBy: null,
    acceptanceComment: null,
    warehouseId,
    rentalItemId: nullableText(source.unitNumber) ?? text(source.taskId),
    cabinNumber: nullableText(source.unitNumber) ?? text(source.taskId),
    authorName: "",
    reason: text(source.title),
    dispatchDate: null,
    comment: nullableText(source.taskText) ?? "",
    media: [],
    subtasks: [subtask(source)],
    sourceEstimateId: null,
    sourceEstimateVersion: null,
    sourceInventoryId: null,
    sourceInventoryFindingId: null,
    sourceRepairTaskId: null,
    sourceRepairTaskVersion: null,
    createdAt: "",
    updatedAt: "",
  }
}

function parseBoard(value: unknown): TaskBoardSnapshotDto {
  const source = object(value)
  const warehouseId = text(source.warehouseId)
  const queues: TaskBoardQueueDto[] = list(source.columns).map((item) => {
    const column = object(item)
    const queueCode = text(column.queueCode)
    const queueId = nullableText(column.queueId)
    const kind = nullableText(column.queueType)
    const routeQueueKind =
      kind === "MOVEMENT" || kind === "REPAIR" || kind === "HOLDING"
        ? kind
        : null
    const entries: TaskBoardEntryDto[] = list(column.entries).map(
      (entryValue) => {
        const entry = object(entryValue)
        const task = viewTask(entry, warehouseId)
        const taskSubtask = task.subtasks[0]!
        return {
          id: text(entry.id),
          queueKey: queueId ?? `virtual:${queueCode}`,
          entryType: text(entry.entryType) === "SHADOW" ? "SHADOW" : "REAL",
          routeIndex: integer(entry.routeIndex),
          routeLength: 1,
          queuePosition: integer(entry.queuePosition),
          task,
          subtask: taskSubtask,
          taskBoardEntryVersion: integer(entry.version),
          taskBoardWarehouseId: warehouseId,
          taskBoardQueueId: queueId,
          detailsHref: null,
        }
      }
    )
    return {
      key: queueId ?? `virtual:${queueCode}`,
      label: text(column.queueName),
      kind: routeQueueKind ?? "UNASSIGNED",
      queueCode,
      routeQueueKind,
      settingsQueueId: queueId,
      settingsCollapsed: false,
      entries,
    }
  })
  const entries = queues.flatMap((queue) => queue.entries)
  const routeLengths = new Map<string, number>()
  entries.forEach((entry) => {
    routeLengths.set(
      entry.task.id,
      Math.max(routeLengths.get(entry.task.id) ?? 1, entry.routeIndex + 1)
    )
  })
  entries.forEach((entry) => {
    entry.routeLength = routeLengths.get(entry.task.id) ?? 1
  })
  return {
    warehouseId,
    queues,
    totalEntries: entries.length,
    realEntries: entries.filter((entry) => entry.entryType === "REAL").length,
    shadowEntries: entries.filter((entry) => entry.entryType === "SHADOW")
      .length,
  }
}

function entryVersion(entry: TaskBoardEntryDto) {
  if (entry.taskBoardEntryVersion === undefined) {
    throw new Error("Не подтверждена версия записи доски заданий.")
  }
  return entry.taskBoardEntryVersion
}

function entryPath(entry: TaskBoardEntryDto, effect: string) {
  return `${TASK_BOARD_API}/warehouses/${encodeURIComponent(entry.taskBoardWarehouseId ?? entry.task.warehouseId)}/task-board/entries/${encodeURIComponent(entry.id)}/${effect}`
}

export async function getHttpTaskBoard(
  accessToken: string | null | undefined,
  warehouseId: string
) {
  return parseBoard(
    await bearerRequest<unknown>(
      token(accessToken),
      `${TASK_BOARD_API}/warehouses/${encodeURIComponent(warehouseId)}/task-board?includeShadow=true`
    )
  )
}

export function moveHttpTaskBoardEntry(
  accessToken: string | null | undefined,
  entry: TaskBoardEntryDto,
  targetQueueId: string | null,
  targetIndex: number
) {
  return bearerRequest<unknown>(token(accessToken), entryPath(entry, "move"), {
    method: "POST",
    body: JSON.stringify({
      expectedVersion: entryVersion(entry),
      targetQueueId,
      targetIndex,
    }),
  }).then(parseBoard)
}

export function takeHttpTaskBoardEntry(
  accessToken: string | null | undefined,
  entry: TaskBoardEntryDto,
  workerGroupId: string | null,
  workerId: string | null
) {
  return bearerRequest<unknown>(token(accessToken), entryPath(entry, "take"), {
    method: "POST",
    body: JSON.stringify({
      expectedVersion: entryVersion(entry),
      workerGroupId,
      workerId,
    }),
  })
}

export function pauseHttpTaskBoardEntry(
  accessToken: string | null | undefined,
  entry: TaskBoardEntryDto
) {
  return bearerRequest<unknown>(token(accessToken), entryPath(entry, "pause"), {
    method: "POST",
    body: JSON.stringify({
      expectedVersion: entryVersion(entry),
      reason: null,
    }),
  })
}

export function resumeHttpTaskBoardEntry(
  accessToken: string | null | undefined,
  entry: TaskBoardEntryDto
) {
  return bearerRequest<unknown>(
    token(accessToken),
    entryPath(entry, "resume"),
    {
      method: "POST",
      body: JSON.stringify({ expectedVersion: entryVersion(entry) }),
    }
  )
}

export function completeHttpTaskBoardEntry(
  accessToken: string | null | undefined,
  entry: TaskBoardEntryDto
) {
  return bearerRequest<unknown>(
    token(accessToken),
    entryPath(entry, "complete"),
    {
      method: "POST",
      body: JSON.stringify({ expectedVersion: entryVersion(entry) }),
    }
  )
}
