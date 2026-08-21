import type { WorkerGroupDto } from "@/features/settings/task-board/model/task-board-settings"
import type {
  TaskBoardAssignmentDto,
  TaskBoardAssignmentStatus,
  TaskBoardEntryDto,
  TaskBoardEntryStatus,
  TaskBoardEntryType,
  TaskBoardQueueDto,
  TaskBoardQueueKind,
  TaskBoardSnapshotDto,
  TaskBoardSourceDto,
  TaskBoardTaskStatus,
  TaskBoardTimerSnapshotDto,
  TaskBoardTimerState,
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

function signedInteger(value: unknown) {
  if (!Number.isSafeInteger(value)) invalid()
  return value as number
}

function finiteNumber(value: unknown) {
  if (typeof value !== "number" || !Number.isFinite(value)) invalid()
  return value
}

function priority(value: unknown) {
  const candidate = integer(value)
  if (candidate < 1 || candidate > 5) invalid()
  return candidate
}

function boolean(value: unknown) {
  if (typeof value !== "boolean") invalid()
  return value
}

function oneOf<T extends string>(value: unknown, values: readonly T[]): T {
  const candidate = text(value)
  if (!values.includes(candidate as T)) invalid()
  return candidate as T
}

function assignment(value: unknown): TaskBoardAssignmentDto {
  const source = object(value)
  return {
    id: text(source.id),
    version: integer(source.version),
    workerId: nullableText(source.workerId),
    workerName: nullableText(source.workerName),
    workerGroupId: nullableText(source.workerGroupId),
    workerGroupName: nullableText(source.workerGroupName),
    status: oneOf<TaskBoardAssignmentStatus>(source.status, [
      "ACTIVE",
      "PAUSED",
      "DONE",
      "CANCELLED",
    ]),
    assignedAt: text(source.assignedAt),
    startedAt: nullableText(source.startedAt),
    pausedAt: nullableText(source.pausedAt),
    finishedAt: nullableText(source.finishedAt),
  }
}

function taskSource(value: unknown): TaskBoardSourceDto | null {
  if (value === null || value === undefined) return null

  const source = object(value)
  if (source.type !== "MAINTENANCE_REPAIR") return null

  return {
    type: "MAINTENANCE_REPAIR",
    sourceId: text(source.sourceId),
  }
}

function detailsHref(source: TaskBoardSourceDto | null) {
  return source?.type === "MAINTENANCE_REPAIR"
    ? `/repairs?repairId=${encodeURIComponent(source.sourceId)}`
    : null
}

function timerSnapshot(value: unknown): TaskBoardTimerSnapshotDto | null {
  if (value === null) return null
  const source = object(value)
  return {
    countedActiveSeconds: integer(source.countedActiveSeconds),
    remainingSeconds:
      source.remainingSeconds === null
        ? null
        : signedInteger(source.remainingSeconds),
    remainingPercent:
      source.remainingPercent === null
        ? null
        : finiteNumber(source.remainingPercent),
    timerState: oneOf<TaskBoardTimerState>(source.timerState, [
      "WORKING",
      "BREAK",
      "OFF_SHIFT",
      "PAUSED",
      "DONE",
    ]),
    nextTransitionAt: nullableText(source.nextTransitionAt),
    serverTime: text(source.serverTime),
  }
}

function entry(
  value: unknown,
  warehouseId: string,
  queueKey: string
): TaskBoardEntryDto {
  const source = object(value)
  const externalTaskId = nullableText(source.externalTaskId)
  const entrySource = taskSource(source.source)
  return {
    id: text(source.id),
    version: integer(source.version),
    warehouseId,
    queueKey,
    queueId: text(source.queueId),
    entryType: oneOf<TaskBoardEntryType>(source.entryType, ["REAL", "SHADOW"]),
    routeIndex: integer(source.routeIndex),
    routeLength: 1,
    queuePosition: integer(source.queuePosition),
    taskId: text(source.taskId),
    externalTaskId,
    source: entrySource,
    taskVersion: integer(source.taskVersion),
    title: text(source.title),
    unitNumber: nullableText(source.unitNumber),
    taskStatus: oneOf<TaskBoardTaskStatus>(source.taskStatus, [
      "ACTIVE",
      "DONE",
      "CANCELLED",
    ]),
    scheduledDate: text(source.scheduledDate),
    priority: priority(source.priority),
    pinned: boolean(source.pinned),
    status: oneOf<TaskBoardEntryStatus>(source.status, [
      "WAITING",
      "IN_PROGRESS",
      "PAUSED",
      "DONE",
      "CANCELLED",
    ]),
    taskText: nullableText(source.taskText),
    plannedDurationMinutes:
      source.plannedDurationMinutes === null
        ? null
        : integer(source.plannedDurationMinutes),
    activeStartedAt: nullableText(source.activeStartedAt),
    pausedAt: nullableText(source.pausedAt),
    activeWorkSeconds: integer(source.activeWorkSeconds),
    timerSnapshot: timerSnapshot(source.timerSnapshot),
    assignments: list(source.assignments).map(assignment),
    detailsHref: detailsHref(entrySource),
  }
}

function parseBoard(value: unknown): TaskBoardSnapshotDto {
  const source = object(value)
  const warehouseId = text(source.warehouseId)
  const queues: TaskBoardQueueDto[] = list(source.columns).map((value) => {
    const column = object(value)
    const queueId = text(column.queueId)
    const kind = oneOf<TaskBoardQueueKind>(column.queueType, [
      "MOVEMENT",
      "REPAIR",
      "HOLDING",
      "FURNITURE_MOVEMENT",
    ])
    return {
      key: queueId,
      label: text(column.queueName),
      kind,
      settingsQueueId: queueId,
      settingsCollapsed: false,
      availableTaskLimit: (() => {
        const limit = integer(column.availableTaskLimit)
        if (limit < 1 || limit > 50) invalid()
        return limit
      })(),
      entries: list(column.entries).map((value) =>
        entry(value, warehouseId, queueId)
      ),
    }
  })
  const entries = queues.flatMap((queue) => queue.entries)
  const routeLengths = new Map<string, number>()
  entries.forEach((item) => {
    routeLengths.set(
      item.taskId,
      Math.max(routeLengths.get(item.taskId) ?? 1, item.routeIndex + 1)
    )
  })
  entries.forEach((item) => {
    item.routeLength = routeLengths.get(item.taskId) ?? 1
  })
  return {
    warehouseId,
    queues,
    totalEntries: entries.length,
    realEntries: entries.filter((item) => item.entryType === "REAL").length,
    shadowEntries: entries.filter((item) => item.entryType === "SHADOW").length,
  }
}

function entryPath(entry: TaskBoardEntryDto, effect: string) {
  return `${TASK_BOARD_API}/warehouses/${encodeURIComponent(entry.warehouseId)}/task-board/entries/${encodeURIComponent(entry.id)}/${effect}`
}

function command(entry: TaskBoardEntryDto, effect: string, body: unknown) {
  return {
    url: entryPath(entry, effect),
    init: { method: "POST", body: JSON.stringify(body) },
  }
}

export async function getHttpTaskBoard(
  accessToken: string,
  warehouseId: string
) {
  return parseBoard(
    await bearerRequest<unknown>(
      accessToken,
      `${TASK_BOARD_API}/warehouses/${encodeURIComponent(warehouseId)}/task-board`
    )
  )
}

export function listHttpEligibleWorkerGroups(
  accessToken: string,
  warehouseId: string,
  queueId: string
) {
  return bearerRequest<WorkerGroupDto[]>(
    accessToken,
    `${TASK_BOARD_API}/warehouses/${encodeURIComponent(warehouseId)}/task-board/queues/${encodeURIComponent(queueId)}/eligible-groups`
  )
}

export function pinHttpTaskBoardEntry(
  accessToken: string,
  entry: TaskBoardEntryDto,
  pinned: boolean
) {
  return bearerRequest<unknown>(
    accessToken,
    `${TASK_BOARD_API}/warehouses/${encodeURIComponent(entry.warehouseId)}/task-board/tasks/${encodeURIComponent(entry.taskId)}/pin`,
    {
      method: "POST",
      body: JSON.stringify({
        expectedTaskVersion: entry.taskVersion,
        pinned,
      }),
    }
  ).then(parseBoard)
}

export function takeHttpTaskBoardEntry(
  accessToken: string,
  entry: TaskBoardEntryDto,
  workerGroupId: string | null,
  workerId: string | null
) {
  const request = command(entry, "take", {
    expectedVersion: entry.version,
    workerGroupId,
    workerId,
  })
  return bearerRequest<unknown>(accessToken, request.url, request.init)
}

export function pauseHttpTaskBoardEntry(
  accessToken: string,
  entry: TaskBoardEntryDto
) {
  const request = command(entry, "pause", {
    expectedVersion: entry.version,
    reason: null,
  })
  return bearerRequest<unknown>(accessToken, request.url, request.init)
}

export function resumeHttpTaskBoardEntry(
  accessToken: string,
  entry: TaskBoardEntryDto
) {
  const request = command(entry, "resume", { expectedVersion: entry.version })
  return bearerRequest<unknown>(accessToken, request.url, request.init)
}

export function completeHttpTaskBoardEntry(
  accessToken: string,
  entry: TaskBoardEntryDto
) {
  const request = command(entry, "complete", { expectedVersion: entry.version })
  return bearerRequest<unknown>(accessToken, request.url, request.init)
}
