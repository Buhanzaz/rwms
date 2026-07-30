import { bearerRequest } from "@/lib/api-client"
import { getGatewayRuntimeConfig } from "@/lib/gateway-config"

export type RepairCapacitySetting = {
  warehouseId: string
  version: number
  maxRepairsPerDay: number
  createdAt: string | null
  updatedAt: string | null
}

export type RepairCapacityUpdate = {
  expectedVersion: number
  maxRepairsPerDay: number
}

export const repairCapacityKeys = {
  all: ["maintenance", "repair-capacity"] as const,
  warehouse: (warehouseId: string) =>
    [...repairCapacityKeys.all, warehouseId] as const,
}

function repairCapacityEndpoint(warehouseId: string) {
  if (!warehouseId.trim()) {
    throw new Error("Не выбран склад для настройки лимита ремонтов.")
  }

  return `${getGatewayRuntimeConfig().maintenanceApiBaseUrl}/v1/settings/repair-capacity/${encodeURIComponent(warehouseId)}`
}

function isNullableString(value: unknown): value is string | null {
  return value === null || typeof value === "string"
}

function parseRepairCapacitySetting(value: unknown): RepairCapacitySetting {
  if (typeof value !== "object" || value === null || Array.isArray(value)) {
    throw new Error("Сервис ремонтов вернул некорректную настройку KPI.")
  }

  const response = value as Record<string, unknown>
  if (
    typeof response.warehouseId !== "string" ||
    !response.warehouseId.trim() ||
    typeof response.version !== "number" ||
    !Number.isInteger(response.version) ||
    response.version < 0 ||
    typeof response.maxRepairsPerDay !== "number" ||
    !Number.isInteger(response.maxRepairsPerDay) ||
    response.maxRepairsPerDay < 1 ||
    !isNullableString(response.createdAt) ||
    !isNullableString(response.updatedAt)
  ) {
    throw new Error("Сервис ремонтов вернул некорректную настройку KPI.")
  }

  return {
    warehouseId: response.warehouseId,
    version: response.version,
    maxRepairsPerDay: response.maxRepairsPerDay,
    createdAt: response.createdAt,
    updatedAt: response.updatedAt,
  }
}

function validateUpdate(input: RepairCapacityUpdate) {
  if (
    !Number.isInteger(input.expectedVersion) ||
    input.expectedVersion < 0 ||
    !Number.isInteger(input.maxRepairsPerDay) ||
    input.maxRepairsPerDay < 1
  ) {
    throw new Error("Лимит ремонтов должен быть положительным целым числом.")
  }
}

export async function getRepairCapacity(
  accessToken: string,
  warehouseId: string
): Promise<RepairCapacitySetting> {
  const response = await bearerRequest<unknown>(
    accessToken,
    repairCapacityEndpoint(warehouseId)
  )

  return parseRepairCapacitySetting(response)
}

export async function updateRepairCapacity(
  accessToken: string,
  warehouseId: string,
  input: RepairCapacityUpdate
): Promise<RepairCapacitySetting> {
  validateUpdate(input)

  const response = await bearerRequest<unknown>(
    accessToken,
    repairCapacityEndpoint(warehouseId),
    {
      method: "PUT",
      body: JSON.stringify(input),
    }
  )

  return parseRepairCapacitySetting(response)
}
