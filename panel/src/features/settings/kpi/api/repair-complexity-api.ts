import { bearerRequest } from "@/lib/api-client"
import { getGatewayRuntimeConfig } from "@/lib/gateway-config"

export type RepairComplexitySetting = {
  version: number
  lightBoundaryMinutes: number
  mediumBoundaryMinutes: number
  complexBoundaryMinutes: number
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
}

function endpoint() {
  return `${getGatewayRuntimeConfig().maintenanceApiBaseUrl}/v1/settings/repair-complexity`
}

function isVersion(value: unknown): value is number {
  return typeof value === "number" && Number.isInteger(value) && value >= 0
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
    "warehouseId" in response ||
    !isVersion(response.version) ||
    !isVersion(response.lightBoundaryMinutes) ||
    !isVersion(response.mediumBoundaryMinutes) ||
    !isVersion(response.complexBoundaryMinutes) ||
    response.lightBoundaryMinutes < 1 ||
    response.lightBoundaryMinutes >= response.mediumBoundaryMinutes ||
    response.mediumBoundaryMinutes >= response.complexBoundaryMinutes ||
    !isNullableString(response.createdAt) ||
    !isNullableString(response.updatedAt)
  ) {
    throw new Error(
      "Сервис ремонтов вернул некорректные границы сложности ремонта."
    )
  }

  return {
    version: response.version,
    lightBoundaryMinutes: response.lightBoundaryMinutes,
    mediumBoundaryMinutes: response.mediumBoundaryMinutes,
    complexBoundaryMinutes: response.complexBoundaryMinutes,
    createdAt: response.createdAt,
    updatedAt: response.updatedAt,
  }
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
  accessToken: string
) {
  return parseSetting(
    await bearerRequest<unknown>(accessToken, endpoint())
  )
}

export async function updateRepairComplexity(
  accessToken: string,
  input: RepairComplexityUpdate
) {
  validateUpdate(input)
  return parseSetting(
    await bearerRequest<unknown>(accessToken, endpoint(), {
      method: "PUT",
      body: JSON.stringify(input),
    })
  )
}
