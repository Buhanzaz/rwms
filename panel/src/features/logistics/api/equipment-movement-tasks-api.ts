import { bearerRequest } from "@/lib/api-client"
import { getGatewayRuntimeConfig } from "@/lib/gateway-config"
import type { EquipmentBalanceLocationKind } from "@/types/equipment"

const UUID_PATTERN =
  /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i
const LOCATION_KINDS = new Set<EquipmentMovementLocationKind>([
  "STOCK",
  "CABIN_NON_RENTED",
  "CABIN_RENTED",
])
const TASK_STATES = new Set<EquipmentMovementTaskState>([
  "RESERVING",
  "REGISTERING_TASK",
  "AWAITING_WORKER",
  "EXECUTING",
  "CANCELLING",
  "COMPLETED",
  "CANCELLED",
  "EXPIRED",
  "CONFLICT",
  "RECONCILIATION_REQUIRED",
])
const LINE_STATES = new Set<EquipmentMovementLineState>([
  "PENDING_RESERVATION",
  "RESERVED",
  "EXECUTED",
  "RELEASED",
])

export const MAX_EQUIPMENT_MOVEMENT_WORKER_OPERATIONS = 10

export type EquipmentMovementLocationKind = Extract<
  EquipmentBalanceLocationKind,
  "STOCK" | "CABIN_NON_RENTED" | "CABIN_RENTED"
>

export type EquipmentMovementTaskState =
  | "RESERVING"
  | "REGISTERING_TASK"
  | "AWAITING_WORKER"
  | "EXECUTING"
  | "CANCELLING"
  | "COMPLETED"
  | "CANCELLED"
  | "EXPIRED"
  | "CONFLICT"
  | "RECONCILIATION_REQUIRED"

export type EquipmentMovementLineState =
  "PENDING_RESERVATION" | "RESERVED" | "EXECUTED" | "RELEASED"

export type EquipmentMovementTaskLineInput = {
  equipmentId: string
  sourceRentalItemId: string | null
  sourceLocationKind: EquipmentMovementLocationKind
  expectedSourceBalanceVersion: number
  targetRentalItemId: string | null
  targetLocationKind: EquipmentMovementLocationKind
  quantity: number
}

export type CreateEquipmentMovementTaskInput = {
  warehouseId: string
  unitNumber: string | null
  plannedDurationMinutes: number | null
  deadlineAt: string
  lines: EquipmentMovementTaskLineInput[]
}

export type EquipmentMovementTaskLine = {
  id: string
  version: number
  lineNumber: number
  equipmentId: string
  equipmentCode: string | null
  equipmentName: string | null
  sourceWarehouseId: string
  sourceRentalItemId: string | null
  sourceLocationKind: EquipmentMovementLocationKind
  expectedSourceBalanceVersion: number
  targetWarehouseId: string
  targetRentalItemId: string | null
  targetLocationKind: EquipmentMovementLocationKind
  quantity: number
  reservationId: string | null
  reservationVersion: number | null
  state: EquipmentMovementLineState
  createdAt: string
  updatedAt: string
}

export type EquipmentMovementTask = {
  id: string
  version: number
  warehouseId: string
  externalTaskId: string
  taskBoardTaskId: string | null
  taskBoardTaskVersion: number | null
  taskBoardDoneAt: string | null
  unitNumber: string | null
  plannedDurationMinutes: number | null
  deadlineAt: string
  state: EquipmentMovementTaskState
  terminalState: EquipmentMovementTaskState | null
  failureCode: string | null
  lines: EquipmentMovementTaskLine[]
  createdAt: string
  updatedAt: string
}

type UnknownRecord = Record<string, unknown>

function endpoint(path = "") {
  return `${getGatewayRuntimeConfig().logisticsApiBaseUrl}/v1/equipment-movement-tasks${path}`
}

function isRecord(value: unknown): value is UnknownRecord {
  return typeof value === "object" && value !== null && !Array.isArray(value)
}

function requireUuid(value: unknown, message: string) {
  if (typeof value !== "string" || !UUID_PATTERN.test(value)) {
    throw new Error(message)
  }

  return value
}

function requireInteger(value: unknown, message: string, minimum = 0) {
  if (
    typeof value !== "number" ||
    !Number.isSafeInteger(value) ||
    value < minimum
  ) {
    throw new Error(message)
  }

  return value
}

function requireDateTime(value: unknown, message: string) {
  if (typeof value !== "string" || !Number.isFinite(Date.parse(value))) {
    throw new Error(message)
  }

  return value
}

function nullableUuid(value: unknown, message: string) {
  return value === null ? null : requireUuid(value, message)
}

function nullableInteger(value: unknown, message: string, minimum = 0) {
  return value === null ? null : requireInteger(value, message, minimum)
}

function nullableText(value: unknown, message: string) {
  if (value === null) return null
  if (typeof value !== "string") throw new Error(message)
  return value
}

function requireLocationKind(value: unknown, message: string) {
  if (
    typeof value !== "string" ||
    !LOCATION_KINDS.has(value as EquipmentMovementLocationKind)
  ) {
    throw new Error(message)
  }

  return value as EquipmentMovementLocationKind
}

function requireTaskState(value: unknown, message: string) {
  if (
    typeof value !== "string" ||
    !TASK_STATES.has(value as EquipmentMovementTaskState)
  ) {
    throw new Error(message)
  }

  return value as EquipmentMovementTaskState
}

function requireLineState(value: unknown, message: string) {
  if (
    typeof value !== "string" ||
    !LINE_STATES.has(value as EquipmentMovementLineState)
  ) {
    throw new Error(message)
  }

  return value as EquipmentMovementLineState
}

function locationHasRentalItem(locationKind: EquipmentMovementLocationKind) {
  return locationKind !== "STOCK"
}

function validateLine(
  line: EquipmentMovementTaskLineInput
): EquipmentMovementTaskLineInput {
  const sourceLocationKind = requireLocationKind(
    line.sourceLocationKind,
    "Неизвестное расположение исходного остатка."
  )
  const targetLocationKind = requireLocationKind(
    line.targetLocationKind,
    "Неизвестное расположение целевого остатка."
  )
  const sourceRentalItemId = nullableUuid(
    line.sourceRentalItemId,
    "Некорректный идентификатор бытовки-источника."
  )
  const targetRentalItemId = nullableUuid(
    line.targetRentalItemId,
    "Некорректный идентификатор бытовки-получателя."
  )

  if (
    locationHasRentalItem(sourceLocationKind) !==
    (sourceRentalItemId !== null)
  ) {
    throw new Error("Исходный остаток не соответствует выбранной бытовке.")
  }
  if (
    locationHasRentalItem(targetLocationKind) !==
    (targetRentalItemId !== null)
  ) {
    throw new Error("Целевой остаток не соответствует выбранной бытовке.")
  }
  if (
    sourceRentalItemId === targetRentalItemId &&
    sourceLocationKind === targetLocationKind
  ) {
    throw new Error("Источник и получатель мебели должны различаться.")
  }

  return {
    equipmentId: requireUuid(
      line.equipmentId,
      "Некорректный идентификатор оборудования."
    ),
    sourceRentalItemId,
    sourceLocationKind,
    expectedSourceBalanceVersion: requireInteger(
      line.expectedSourceBalanceVersion,
      "Нужна актуальная версия исходного остатка."
    ),
    targetRentalItemId,
    targetLocationKind,
    quantity: requireInteger(
      line.quantity,
      "Количество для перемещения должно быть целым и больше нуля.",
      1
    ),
  }
}

export function equipmentMovementWorkerOperationCount(
  lines: EquipmentMovementTaskLineInput[]
) {
  return lines.reduce(
    (count, line) =>
      count +
      (line.sourceLocationKind !== "STOCK" &&
      line.targetLocationKind !== "STOCK"
        ? 2
        : 1),
    0
  )
}

export function equipmentMovementDeadlineToIso(
  localDateTime: string,
  now = new Date()
) {
  if (!localDateTime.trim()) {
    throw new Error("Укажите, до какого времени резервировать мебель.")
  }

  const deadline = new Date(localDateTime)
  if (!Number.isFinite(deadline.getTime())) {
    throw new Error("Укажите корректный срок резерва.")
  }
  if (deadline.getTime() <= now.getTime()) {
    throw new Error("Срок резерва должен быть в будущем.")
  }

  return deadline.toISOString()
}

function validateCreateInput(
  input: CreateEquipmentMovementTaskInput
): CreateEquipmentMovementTaskInput {
  if (!Array.isArray(input.lines) || input.lines.length === 0) {
    throw new Error("Выберите хотя бы одну позицию для перемещения.")
  }
  if (input.lines.length > 100) {
    throw new Error("В одном задании допускается не более 100 позиций.")
  }

  const lines = input.lines.map(validateLine)
  const workerOperations = equipmentMovementWorkerOperationCount(lines)
  if (workerOperations > MAX_EQUIPMENT_MOVEMENT_WORKER_OPERATIONS) {
    throw new Error(
      `В одном задании допускается не более ${MAX_EQUIPMENT_MOVEMENT_WORKER_OPERATIONS} действий работника.`
    )
  }

  const unitNumber = nullableText(
    input.unitNumber,
    "Некорректный номер бытовки для задания."
  )
  if (unitNumber !== null && unitNumber.trim().length > 64) {
    throw new Error("Номер бытовки для задания слишком длинный.")
  }

  return {
    warehouseId: requireUuid(
      input.warehouseId,
      "Некорректный идентификатор склада."
    ),
    unitNumber: unitNumber?.trim() || null,
    plannedDurationMinutes: nullableInteger(
      input.plannedDurationMinutes,
      "Плановая длительность должна быть положительным числом минут.",
      1
    ),
    deadlineAt: requireDateTime(
      input.deadlineAt,
      "Укажите корректный срок резерва."
    ),
    lines,
  }
}

function parseLine(value: unknown): EquipmentMovementTaskLine {
  if (!isRecord(value)) {
    throw new Error("Логистика вернула некорректную строку задания.")
  }

  const message = "Логистика вернула некорректную строку задания."
  return {
    id: requireUuid(value.id, message),
    version: requireInteger(value.version, message),
    lineNumber: requireInteger(value.lineNumber, message, 1),
    equipmentId: requireUuid(value.equipmentId, message),
    equipmentCode: nullableText(value.equipmentCode, message),
    equipmentName: nullableText(value.equipmentName, message),
    sourceWarehouseId: requireUuid(value.sourceWarehouseId, message),
    sourceRentalItemId: nullableUuid(value.sourceRentalItemId, message),
    sourceLocationKind: requireLocationKind(value.sourceLocationKind, message),
    expectedSourceBalanceVersion: requireInteger(
      value.expectedSourceBalanceVersion,
      message
    ),
    targetWarehouseId: requireUuid(value.targetWarehouseId, message),
    targetRentalItemId: nullableUuid(value.targetRentalItemId, message),
    targetLocationKind: requireLocationKind(value.targetLocationKind, message),
    quantity: requireInteger(value.quantity, message, 1),
    reservationId: nullableUuid(value.reservationId, message),
    reservationVersion: nullableInteger(value.reservationVersion, message),
    state: requireLineState(value.state, message),
    createdAt: requireDateTime(value.createdAt, message),
    updatedAt: requireDateTime(value.updatedAt, message),
  }
}

function parseTask(value: unknown): EquipmentMovementTask {
  if (!isRecord(value) || !Array.isArray(value.lines)) {
    throw new Error("Логистика вернула некорректное задание на перемещение.")
  }

  const message = "Логистика вернула некорректное задание на перемещение."
  return {
    id: requireUuid(value.id, message),
    version: requireInteger(value.version, message),
    warehouseId: requireUuid(value.warehouseId, message),
    externalTaskId: requireUuid(value.externalTaskId, message),
    taskBoardTaskId: nullableUuid(value.taskBoardTaskId, message),
    taskBoardTaskVersion: nullableInteger(value.taskBoardTaskVersion, message),
    taskBoardDoneAt:
      value.taskBoardDoneAt === null
        ? null
        : requireDateTime(value.taskBoardDoneAt, message),
    unitNumber: nullableText(value.unitNumber, message),
    plannedDurationMinutes: nullableInteger(
      value.plannedDurationMinutes,
      message,
      1
    ),
    deadlineAt: requireDateTime(value.deadlineAt, message),
    state: requireTaskState(value.state, message),
    terminalState:
      value.terminalState === null
        ? null
        : requireTaskState(value.terminalState, message),
    failureCode: nullableText(value.failureCode, message),
    lines: value.lines.map(parseLine),
    createdAt: requireDateTime(value.createdAt, message),
    updatedAt: requireDateTime(value.updatedAt, message),
  }
}

export async function createEquipmentMovementTask(params: {
  accessToken: string
  idempotencyKey: string
  input: CreateEquipmentMovementTaskInput
}): Promise<EquipmentMovementTask> {
  const idempotencyKey = requireUuid(
    params.idempotencyKey,
    "Для задания нужен UUID Idempotency-Key."
  )
  const input = validateCreateInput(params.input)

  return parseTask(
    await bearerRequest<unknown>(params.accessToken, endpoint(), {
      method: "POST",
      headers: { "Idempotency-Key": idempotencyKey },
      body: JSON.stringify(input),
    })
  )
}

export function createEquipmentMovementTaskIdempotencyKey() {
  if (
    typeof crypto === "undefined" ||
    typeof crypto.randomUUID !== "function"
  ) {
    throw new Error("Браузер не поддерживает генерацию ключа идемпотентности.")
  }

  return crypto.randomUUID()
}
