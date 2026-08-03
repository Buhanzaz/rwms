import { bearerRequest } from "@/lib/api-client"
import { getGatewayRuntimeConfig } from "@/lib/gateway-config"

export type RepairCapacitySetting = {
  warehouseId: string
  version: number
  repairPlaceCount: number
  automaticRefillDelayMinutes: number
  createdAt: string | null
  updatedAt: string | null
}

export type RepairCapacityUpdate = {
  expectedVersion: number
  repairPlaceCount: number
  automaticRefillDelayMinutes: number
}

export const repairCapacityKeys = {
  all: ["maintenance", "repair-capacity"] as const,
  warehouse: (warehouseId: string) =>
    [...repairCapacityKeys.all, warehouseId] as const,
}

function repairCapacityEndpoint(warehouseId: string) {
  if (!warehouseId.trim()) {
    throw new Error("Не выбран склад для настройки ремонтных мест.")
  }

  return `${getGatewayRuntimeConfig().maintenanceApiBaseUrl}/v1/settings/repair-capacity/${encodeURIComponent(warehouseId)}`
}

function isNullableString(value: unknown): value is string | null {
  return value === null || typeof value === "string"
}

function parseRepairCapacitySetting(value: unknown): RepairCapacitySetting {
  if (typeof value !== "object" || value === null || Array.isArray(value)) {
    throw new Error("Сервис ремонтов вернул некорректную настройку ремонтных мест.")
  }

  const response = value as Record<string, unknown>
  if (
    typeof response.warehouseId !== "string" ||
    !response.warehouseId.trim() ||
    typeof response.version !== "number" ||
    !Number.isInteger(response.version) ||
    response.version < 0 ||
    typeof response.repairPlaceCount !== "number" ||
    !Number.isInteger(response.repairPlaceCount) ||
    response.repairPlaceCount < 1 ||
    typeof response.automaticRefillDelayMinutes !== "number" ||
    !Number.isInteger(response.automaticRefillDelayMinutes) ||
    response.automaticRefillDelayMinutes < 1 ||
    response.automaticRefillDelayMinutes > 1440 ||
    !isNullableString(response.createdAt) ||
    !isNullableString(response.updatedAt)
  ) {
    throw new Error("Сервис ремонтов вернул некорректную настройку ремонтных мест.")
  }

  return {
    warehouseId: response.warehouseId,
    version: response.version,
    repairPlaceCount: response.repairPlaceCount,
    automaticRefillDelayMinutes: response.automaticRefillDelayMinutes,
    createdAt: response.createdAt,
    updatedAt: response.updatedAt,
  }
}

function validateUpdate(input: RepairCapacityUpdate) {
  if (
    !Number.isInteger(input.expectedVersion) ||
    input.expectedVersion < 0 ||
    !Number.isInteger(input.repairPlaceCount) ||
    input.repairPlaceCount < 1 ||
    !Number.isInteger(input.automaticRefillDelayMinutes) ||
    input.automaticRefillDelayMinutes < 1 ||
    input.automaticRefillDelayMinutes > 1440
  ) {
    throw new Error(
      "Проверьте количество мест и задержку автозаполнения."
    )
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
