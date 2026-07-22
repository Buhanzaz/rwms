import { bearerRequest } from "@/lib/api-client"
import { getGatewayRuntimeConfig } from "@/lib/gateway-config"
import type {
  EquipmentBalanceLocationKind,
  EquipmentMovementDto,
} from "@/types/equipment"

const UUID_PATTERN =
  /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i

type JsonRecord = Record<string, unknown>
type CabinLocationKind = Extract<
  EquipmentBalanceLocationKind,
  "CABIN_NON_RENTED" | "CABIN_RENTED"
>

export type MoveEquipmentUsageToStockInput = {
  equipmentId: string
  warehouseId: string
  rentalItemId: string
  sourceLocationKind: CabinLocationKind
  sourceExpectedVersion: number
  targetExpectedVersion: number
  quantity: number
}

function record(value: unknown): JsonRecord {
  if (typeof value !== "object" || value === null || Array.isArray(value)) {
    throw new Error("Сервис имущества вернул некорректный ответ.")
  }

  return value as JsonRecord
}

function string(value: unknown) {
  if (typeof value !== "string") {
    throw new Error("Сервис имущества вернул некорректный ответ.")
  }

  return value
}

function uuid(value: unknown, message: string) {
  const parsed = string(value)
  if (!UUID_PATTERN.test(parsed)) {
    throw new Error(message)
  }

  return parsed
}

function nonNegativeInteger(value: unknown, message: string) {
  if (typeof value !== "number" || !Number.isSafeInteger(value) || value < 0) {
    throw new Error(message)
  }

  return value
}

function positiveInteger(value: unknown, message: string) {
  const parsed = nonNegativeInteger(value, message)
  if (parsed < 1) {
    throw new Error(message)
  }

  return parsed
}

function dateTime(value: unknown) {
  const parsed = string(value)
  if (Number.isNaN(Date.parse(parsed))) {
    throw new Error("Сервис имущества вернул некорректную дату перемещения.")
  }

  return parsed
}

function cabinLocationKind(value: unknown): CabinLocationKind {
  if (value === "CABIN_NON_RENTED" || value === "CABIN_RENTED") {
    return value
  }

  throw new Error("Перемещение на склад возможно только из бытовки.")
}

function parseMovement(value: unknown): EquipmentMovementDto {
  const movement = record(value)
  return {
    id: uuid(
      movement.id,
      "Сервис имущества вернул некорректный идентификатор."
    ),
    version: nonNegativeInteger(
      movement.version,
      "Сервис имущества вернул некорректную версию перемещения."
    ),
    equipmentId: uuid(
      movement.equipmentId,
      "Сервис имущества вернул некорректный идентификатор оборудования."
    ),
    sourceBalanceId: uuid(
      movement.sourceBalanceId,
      "Сервис имущества вернул некорректный исходный остаток."
    ),
    targetBalanceId: uuid(
      movement.targetBalanceId,
      "Сервис имущества вернул некорректный целевой остаток."
    ),
    quantity: positiveInteger(
      movement.quantity,
      "Сервис имущества вернул некорректное количество перемещения."
    ),
    kind: string(movement.kind),
    occurredAt: dateTime(movement.occurredAt),
  }
}

function commandEndpoint() {
  return `${getGatewayRuntimeConfig().assetApiBaseUrl}/v1/equipment/transfers`
}

export function createEquipmentUsageMoveIdempotencyKey() {
  if (
    typeof crypto === "undefined" ||
    typeof crypto.randomUUID !== "function"
  ) {
    throw new Error("Браузер не поддерживает генерацию ключа идемпотентности.")
  }

  return crypto.randomUUID()
}

/**
 * Applies the asset-service CABIN_TO_STOCK command for one displayed equipment
 * balance. The service locks both balance streams and records the immutable
 * movement ledger; the panel only supplies current versions and intent.
 */
export async function moveEquipmentUsageToStock(params: {
  accessToken: string
  idempotencyKey: string
  input: MoveEquipmentUsageToStockInput
}): Promise<EquipmentMovementDto> {
  const idempotencyKey = uuid(
    params.idempotencyKey,
    "Для перемещения нужен UUID Idempotency-Key."
  )
  const input = params.input
  const request = {
    equipmentId: uuid(input.equipmentId, "Не задано оборудование."),
    sourceWarehouseId: uuid(input.warehouseId, "Не задан склад."),
    sourceRentalItemId: uuid(input.rentalItemId, "Не задана бытовка."),
    sourceLocationKind: cabinLocationKind(input.sourceLocationKind),
    sourceExpectedVersion: nonNegativeInteger(
      input.sourceExpectedVersion,
      "Для перемещения нужна актуальная версия остатка в бытовке."
    ),
    targetWarehouseId: uuid(input.warehouseId, "Не задан склад."),
    targetRentalItemId: null,
    targetLocationKind: "STOCK",
    targetExpectedVersion: nonNegativeInteger(
      input.targetExpectedVersion,
      "Для перемещения нужна актуальная версия складского остатка."
    ),
    quantity: positiveInteger(
      input.quantity,
      "Количество перемещения должно быть целым и больше нуля."
    ),
  }

  return parseMovement(
    await bearerRequest<unknown>(params.accessToken, commandEndpoint(), {
      method: "POST",
      headers: { "Idempotency-Key": idempotencyKey },
      body: JSON.stringify(request),
    })
  )
}
