import type { TaskBoardSourceDto } from "@/features/task-board/model/task-board"
import { bearerRequest } from "@/lib/api-client"
import { getGatewayRuntimeConfig } from "@/lib/gateway-config"

const TASK_BOARD_API = getGatewayRuntimeConfig().taskBoardApiBaseUrl
const LOCAL_DATE = /^\d{4}-\d{2}-\d{2}$/

type JsonRecord = Record<string, unknown>

/** Execution state of one task interval owned by task-board. */
export type DailyBrigadeActivityStatus = "ACTIVE" | "PAUSED" | "DONE"

/** One actual take-to-finish interval for a brigade and physical task queue. */
export type DailyBrigadeActivityInterval = {
  workerGroupId: string
  workerGroupName: string
  taskId: string
  entryId: string
  queueId: string
  queueName: string
  title: string
  unitNumber: string | null
  taskText: string | null
  priority: number
  startedAt: string
  finishedAt: string | null
  status: DailyBrigadeActivityStatus
  source: TaskBoardSourceDto | null
}

/** Current warehouse-local day and its authoritative task execution intervals. */
export type DailyBrigadeActivitySnapshot = {
  warehouseId: string
  localDate: string
  serverTime: string
  intervals: DailyBrigadeActivityInterval[]
}

export function dailyBrigadeActivityQueryKey(warehouseId: string) {
  return ["task-board", warehouseId, "daily-brigade-activity"] as const
}

function invalid(): never {
  throw new Error("Сервис доски заданий вернул некорректную статистику дня.")
}

function object(value: unknown): JsonRecord {
  if (!value || typeof value !== "object" || Array.isArray(value)) invalid()
  return value as JsonRecord
}

function text(value: unknown) {
  if (typeof value !== "string" || !value.trim()) invalid()
  return value
}

function nullableText(value: unknown) {
  return value === null ? null : text(value)
}

function instant(value: unknown) {
  const candidate = text(value)
  if (!Number.isFinite(Date.parse(candidate))) invalid()
  return candidate
}

function nullableInstant(value: unknown) {
  return value === null ? null : instant(value)
}

function priority(value: unknown) {
  if (
    !Number.isSafeInteger(value) ||
    (value as number) < 1 ||
    (value as number) > 5
  ) {
    invalid()
  }
  return value as number
}

function status(value: unknown): DailyBrigadeActivityStatus {
  if (value !== "ACTIVE" && value !== "PAUSED" && value !== "DONE") {
    invalid()
  }
  return value
}

function source(value: unknown): TaskBoardSourceDto | null {
  if (value === null) return null
  const candidate = object(value)
  const type = text(candidate.type)
  const sourceId = text(candidate.sourceId)
  return type === "MAINTENANCE_REPAIR"
    ? { type: "MAINTENANCE_REPAIR", sourceId }
    : null
}

function interval(value: unknown): DailyBrigadeActivityInterval {
  const candidate = object(value)
  const startedAt = instant(candidate.startedAt)
  const finishedAt = nullableInstant(candidate.finishedAt)
  const intervalStatus = status(candidate.status)
  if (
    (intervalStatus === "DONE") !== (finishedAt !== null) ||
    (finishedAt !== null && Date.parse(finishedAt) < Date.parse(startedAt))
  ) {
    invalid()
  }
  return {
    workerGroupId: text(candidate.workerGroupId),
    workerGroupName: text(candidate.workerGroupName),
    taskId: text(candidate.taskId),
    entryId: text(candidate.entryId),
    queueId: text(candidate.queueId),
    queueName: text(candidate.queueName),
    title: text(candidate.title),
    unitNumber: nullableText(candidate.unitNumber),
    taskText: nullableText(candidate.taskText),
    priority: priority(candidate.priority),
    startedAt,
    finishedAt,
    status: intervalStatus,
    source: source(candidate.source),
  }
}

function snapshot(
  value: unknown,
  expectedWarehouseId: string
): DailyBrigadeActivitySnapshot {
  const candidate = object(value)
  const warehouseId = text(candidate.warehouseId)
  const localDate = text(candidate.localDate)
  if (warehouseId !== expectedWarehouseId || !LOCAL_DATE.test(localDate)) {
    invalid()
  }
  if (!Array.isArray(candidate.intervals)) invalid()
  return {
    warehouseId,
    localDate,
    serverTime: instant(candidate.serverTime),
    intervals: candidate.intervals.map(interval),
  }
}

/** Loads today's task-board-owned brigade execution intervals through the gateway. */
export async function getDailyBrigadeActivity(
  accessToken: string,
  warehouseId: string
) {
  const response = await bearerRequest<unknown>(
    accessToken,
    `${TASK_BOARD_API}/warehouses/${encodeURIComponent(warehouseId)}/task-board/daily-brigade-activity`
  )
  return snapshot(response, warehouseId)
}
