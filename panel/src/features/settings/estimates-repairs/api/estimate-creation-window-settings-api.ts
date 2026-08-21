import { bearerRequest } from "@/lib/api-client"
import { getGatewayRuntimeConfig } from "@/lib/gateway-config"

export type EstimateCreationWindowSetting = {
  warehouseId: string
  version: number
  days: number
  createdAt: string | null
  updatedAt: string | null
}

export type EstimateCreationWindowUpdate = {
  expectedVersion: number
  days: number
}

export const estimateCreationWindowKeys = {
  all: ["maintenance", "estimate-creation-window"] as const,
  warehouse: (warehouseId: string) =>
    [...estimateCreationWindowKeys.all, warehouseId] as const,
}

function endpoint(warehouseId: string) {
  if (!warehouseId.trim()) {
    throw new Error("Не выбран склад для настройки срока создания сметы.")
  }
  return `${getGatewayRuntimeConfig().maintenanceApiBaseUrl}/v1/settings/estimate-creation-window/${encodeURIComponent(warehouseId)}`
}

function nullableString(value: unknown): value is string | null {
  return value === null || typeof value === "string"
}

function parse(value: unknown): EstimateCreationWindowSetting {
  if (typeof value !== "object" || value === null || Array.isArray(value)) {
    throw new Error("Сервис ремонтов вернул некорректный срок создания сметы.")
  }
  const response = value as Record<string, unknown>
  if (
    typeof response.warehouseId !== "string" ||
    !response.warehouseId.trim() ||
    typeof response.version !== "number" ||
    !Number.isInteger(response.version) ||
    response.version < 0 ||
    typeof response.days !== "number" ||
    !Number.isInteger(response.days) ||
    response.days < 1 ||
    response.days > 3650 ||
    !nullableString(response.createdAt) ||
    !nullableString(response.updatedAt)
  ) {
    throw new Error("Сервис ремонтов вернул некорректный срок создания сметы.")
  }
  return {
    warehouseId: response.warehouseId,
    version: response.version,
    days: response.days,
    createdAt: response.createdAt,
    updatedAt: response.updatedAt,
  }
}

function parseForWarehouse(value: unknown, warehouseId: string) {
  const setting = parse(value)
  if (setting.warehouseId !== warehouseId) {
    throw new Error(
      "Сервис ремонтов вернул срок создания сметы другого склада."
    )
  }
  return setting
}

function validate(input: EstimateCreationWindowUpdate) {
  if (
    !Number.isInteger(input.expectedVersion) ||
    input.expectedVersion < 0 ||
    !Number.isInteger(input.days) ||
    input.days < 1 ||
    input.days > 3650
  ) {
    throw new Error("Укажите срок создания сметы от 1 до 3650 дней.")
  }
}

export async function getEstimateCreationWindow(
  accessToken: string,
  warehouseId: string
): Promise<EstimateCreationWindowSetting> {
  return parseForWarehouse(
    await bearerRequest<unknown>(accessToken, endpoint(warehouseId)),
    warehouseId
  )
}

export async function updateEstimateCreationWindow(
  accessToken: string,
  warehouseId: string,
  input: EstimateCreationWindowUpdate
): Promise<EstimateCreationWindowSetting> {
  validate(input)
  return parseForWarehouse(
    await bearerRequest<unknown>(accessToken, endpoint(warehouseId), {
      method: "PUT",
      body: JSON.stringify(input),
    }),
    warehouseId
  )
}
