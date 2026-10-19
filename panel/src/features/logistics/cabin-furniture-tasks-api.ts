import { bearerRequest } from "@/lib/api-client"
import { getGatewayRuntimeConfig } from "@/lib/gateway-config"

export type CabinFurnitureTaskRequirement = {
  equipmentId: string
  quantity: number
}

export type CabinFurnitureTaskCommand = {
  accessToken: string
  warehouseId: string
  rentalItemId: string
  scheduledDate: string
  contents: CabinFurnitureTaskRequirement[]
  idempotencyKey: string
}

export type CabinFurnitureTaskResult = {
  rentalItemId: string
  unitNumber: string
  taskId: string | null
  lineCount: number
}

const UUID_PATTERN =
  /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i

function invalidResponse(): never {
  throw new Error("Сервис логистики вернул некорректный ответ задания мебели.")
}

function uuid(value: unknown) {
  if (typeof value !== "string" || !UUID_PATTERN.test(value)) invalidResponse()
  return value
}

function nullableUuid(value: unknown) {
  return value === null ? null : uuid(value)
}

function response(value: unknown): CabinFurnitureTaskResult {
  if (!value || typeof value !== "object" || Array.isArray(value)) {
    invalidResponse()
  }
  const source = value as Record<string, unknown>
  const expected = ["rentalItemId", "unitNumber", "taskId", "lineCount"]
  if (
    Object.keys(source).length !== expected.length ||
    !expected.every((key) => Object.hasOwn(source, key)) ||
    typeof source.unitNumber !== "string" ||
    !source.unitNumber.trim() ||
    !Number.isSafeInteger(source.lineCount) ||
    (source.lineCount as number) < 0
  ) {
    invalidResponse()
  }
  return {
    rentalItemId: uuid(source.rentalItemId),
    unitNumber: source.unitNumber,
    taskId: nullableUuid(source.taskId),
    lineCount: source.lineCount as number,
  }
}

export async function createCabinFurnitureTask(
  input: CabinFurnitureTaskCommand
): Promise<CabinFurnitureTaskResult> {
  const result = await bearerRequest<unknown>(
    input.accessToken,
    `${getGatewayRuntimeConfig().logisticsApiBaseUrl}/v1/rental-items/${encodeURIComponent(input.rentalItemId)}/furniture-tasks`,
    {
      method: "POST",
      headers: { "Idempotency-Key": input.idempotencyKey },
      body: JSON.stringify({
        warehouseId: input.warehouseId,
        scheduledDate: input.scheduledDate,
        contents: input.contents,
      }),
    }
  )
  return response(result)
}
