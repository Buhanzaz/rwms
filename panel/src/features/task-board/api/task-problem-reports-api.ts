import { bearerRequest } from "@/lib/api-client"
import { getGatewayRuntimeConfig } from "@/lib/gateway-config"

const TASK_BOARD_API = getGatewayRuntimeConfig().taskBoardApiBaseUrl

export type TaskProblemReportAttachment = Readonly<{
  evidenceId: string
  state: "RESERVED" | "UPLOADING" | "READY" | "REVIEW_REQUIRED" | "REJECTED"
  capturedAt: string
  recordedAt: string
  mediaId: string | null
  mediaGeneration: number | null
  reviewReason: string | null
  contentType: string | null
  readPath: string | null
  thumbnailPath: string | null
}>

export type TaskProblemReport = Readonly<{
  reportId: string
  entryId: string
  taskId: string
  routeIndex: number
  entryTitle: string
  workerName: string
  comment: string
  occurredAt: string
  recordedAt: string
  readAt: string | null
  attachments: readonly TaskProblemReportAttachment[]
}>

export type TaskProblemReportPage = Readonly<{
  reports: readonly TaskProblemReport[]
  nextCursor: string | null
  unreadCount: number
}>

type RecordValue = Record<string, unknown>

function invalid(): never {
  throw new Error("Сервис доски заданий вернул некорректные сообщения рабочих.")
}

function object(value: unknown): RecordValue {
  if (!value || typeof value !== "object" || Array.isArray(value)) invalid()
  return value as RecordValue
}

function text(value: unknown) {
  if (typeof value !== "string") invalid()
  return value
}

function nullableText(value: unknown) {
  return value === null ? null : text(value)
}

function nonNegativeInteger(value: unknown) {
  if (!Number.isSafeInteger(value) || (value as number) < 0) invalid()
  return value as number
}

function attachment(value: unknown): TaskProblemReportAttachment {
  const source = object(value)
  const state = text(source.state)
  if (
    !["RESERVED", "UPLOADING", "READY", "REVIEW_REQUIRED", "REJECTED"].includes(
      state
    )
  )
    invalid()
  return {
    evidenceId: text(source.evidenceId),
    state: state as TaskProblemReportAttachment["state"],
    capturedAt: text(source.capturedAt),
    recordedAt: text(source.recordedAt),
    mediaId: nullableText(source.mediaId),
    mediaGeneration:
      source.mediaGeneration === null
        ? null
        : nonNegativeInteger(source.mediaGeneration),
    reviewReason: nullableText(source.reviewReason),
    contentType: nullableText(source.contentType),
    readPath: nullableText(source.readPath),
    thumbnailPath: nullableText(source.thumbnailPath),
  }
}

function report(value: unknown): TaskProblemReport {
  const source = object(value)
  if (!Array.isArray(source.attachments)) invalid()
  return {
    reportId: text(source.reportId),
    entryId: text(source.entryId),
    taskId: text(source.taskId),
    routeIndex: nonNegativeInteger(source.routeIndex),
    entryTitle: text(source.entryTitle),
    workerName: text(source.workerName),
    comment: text(source.comment),
    occurredAt: text(source.occurredAt),
    recordedAt: text(source.recordedAt),
    readAt: nullableText(source.readAt),
    attachments: source.attachments.map(attachment),
  }
}

function page(value: unknown): TaskProblemReportPage {
  const source = object(value)
  if (!Array.isArray(source.reports)) invalid()
  return {
    reports: source.reports.map(report),
    nextCursor: nullableText(source.nextCursor),
    unreadCount: nonNegativeInteger(source.unreadCount),
  }
}

export function taskProblemReportsQueryKey(
  warehouseId: string,
  userId: string
) {
  return ["task-board", "problem-reports", warehouseId, userId] as const
}

export async function listTaskProblemReports(
  accessToken: string,
  warehouseId: string,
  cursor: string | null = null,
  signal?: AbortSignal
) {
  const params = new URLSearchParams({ limit: "50" })
  if (cursor) params.set("cursor", cursor)
  return page(
    await bearerRequest<unknown>(
      accessToken,
      `${TASK_BOARD_API}/warehouses/${encodeURIComponent(warehouseId)}/task-problem-reports?${params}`,
      { signal }
    )
  )
}

export function markTaskProblemReportRead(
  accessToken: string,
  warehouseId: string,
  reportId: string
) {
  return bearerRequest<void>(
    accessToken,
    `${TASK_BOARD_API}/warehouses/${encodeURIComponent(warehouseId)}/task-problem-reports/${encodeURIComponent(reportId)}/read`,
    { method: "PUT" }
  )
}
