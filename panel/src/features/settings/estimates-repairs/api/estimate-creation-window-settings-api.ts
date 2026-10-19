import { bearerRequest } from "@/lib/api-client"
import { getGatewayRuntimeConfig } from "@/lib/gateway-config"

export type EstimateCreationWindowSetting = {
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
}

function endpoint() {
  return `${getGatewayRuntimeConfig().maintenanceApiBaseUrl}/v1/settings/estimate-creation-window`
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
    "warehouseId" in response ||
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
    version: response.version,
    days: response.days,
    createdAt: response.createdAt,
    updatedAt: response.updatedAt,
  }
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
  accessToken: string
): Promise<EstimateCreationWindowSetting> {
  return parse(
    await bearerRequest<unknown>(accessToken, endpoint())
  )
}

export async function updateEstimateCreationWindow(
  accessToken: string,
  input: EstimateCreationWindowUpdate
): Promise<EstimateCreationWindowSetting> {
  validate(input)
  return parse(
    await bearerRequest<unknown>(accessToken, endpoint(), {
      method: "PUT",
      body: JSON.stringify(input),
    })
  )
}
