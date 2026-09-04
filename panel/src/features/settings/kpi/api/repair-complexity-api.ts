import { bearerRequest } from "@/lib/api-client"
import { getGatewayRuntimeConfig } from "@/lib/gateway-config"

export type RepairComplexitySetting = {
  warehouseId: string
  version: number
  lightBoundaryMinutes: number
  mediumBoundaryMinutes: number
  complexBoundaryMinutes: number
  importedFromTaskBoardVersion: number | null
  importedAt: string | null
  createdAt: string | null
  updatedAt: string | null
}

export type RepairComplexityUpdate = {
  expectedVersion: number
  lightBoundaryMinutes: number
  mediumBoundaryMinutes: number
  complexBoundaryMinutes: number
}

export const repairComplexityKeys = {
  all: ["maintenance", "repair-complexity"] as const,
  warehouse: (warehouseId: string) =>
    [...repairComplexityKeys.all, warehouseId] as const,
}

function endpoint(warehouseId: string) {
  if (!warehouseId.trim()) {
    throw new Error("Не выбран склад для настройки сложности ремонта.")
  }
  return `${getGatewayRuntimeConfig().maintenanceApiBaseUrl}/v1/settings/repair-complexity/${encodeURIComponent(warehouseId)}`
}

function isVersion(value: unknown): value is number {
  return typeof value === "number" && Number.isInteger(value) && value >= 0
}

function isNullableVersion(value: unknown): value is number | null {
  return value === null || isVersion(value)
}

function isNullableString(value: unknown): value is string | null {
  return value === null || typeof value === "string"
}

function parseSetting(value: unknown): RepairComplexitySetting {
  if (typeof value !== "object" || value === null || Array.isArray(value)) {
    throw new Error(
      "Сервис ремонтов вернул некорректные границы сложности ремонта."
    )
  }

  const response = value as Record<string, unknown>
  if (
    typeof response.warehouseId !== "string" ||
    !response.warehouseId.trim() ||
    !isVersion(response.version) ||
    !isVersion(response.lightBoundaryMinutes) ||
    !isVersion(response.mediumBoundaryMinutes) ||
    !isVersion(response.complexBoundaryMinutes) ||
    response.lightBoundaryMinutes < 1 ||
    response.lightBoundaryMinutes >= response.mediumBoundaryMinutes ||
    response.mediumBoundaryMinutes >= response.complexBoundaryMinutes ||
    !isNullableVersion(response.importedFromTaskBoardVersion) ||
    !isNullableString(response.importedAt) ||
    !isNullableString(response.createdAt) ||
    !isNullableString(response.updatedAt)
  ) {
    throw new Error(
      "Сервис ремонтов вернул некорректные границы сложности ремонта."
    )
  }

  return {
    warehouseId: response.warehouseId,
    version: response.version,
    lightBoundaryMinutes: response.lightBoundaryMinutes,
    mediumBoundaryMinutes: response.mediumBoundaryMinutes,
    complexBoundaryMinutes: response.complexBoundaryMinutes,
    importedFromTaskBoardVersion: response.importedFromTaskBoardVersion,
    importedAt: response.importedAt,
    createdAt: response.createdAt,
    updatedAt: response.updatedAt,
  }
}

function parseForWarehouse(value: unknown, warehouseId: string) {
  const setting = parseSetting(value)
  if (setting.warehouseId !== warehouseId) {
    throw new Error(
      "Сервис ремонтов вернул границы сложности ремонта другого склада."
    )
  }
  return setting
}

function validateUpdate(input: RepairComplexityUpdate) {
  if (
    !isVersion(input.expectedVersion) ||
    !isVersion(input.lightBoundaryMinutes) ||
    !isVersion(input.mediumBoundaryMinutes) ||
    !isVersion(input.complexBoundaryMinutes) ||
    input.lightBoundaryMinutes < 1 ||
    input.lightBoundaryMinutes >= input.mediumBoundaryMinutes ||
    input.mediumBoundaryMinutes >= input.complexBoundaryMinutes
  ) {
    throw new Error(
      "Границы сложности должны быть положительными целыми минутами и строго возрастать."
    )
  }
}

export async function getRepairComplexity(
  accessToken: string,
  warehouseId: string
) {
  return parseForWarehouse(
    await bearerRequest<unknown>(accessToken, endpoint(warehouseId)),
    warehouseId
  )
}

export async function updateRepairComplexity(
  accessToken: string,
  warehouseId: string,
  input: RepairComplexityUpdate
) {
  validateUpdate(input)
  return parseForWarehouse(
    await bearerRequest<unknown>(accessToken, endpoint(warehouseId), {
      method: "PUT",
      body: JSON.stringify(input),
    }),
    warehouseId
  )
}
