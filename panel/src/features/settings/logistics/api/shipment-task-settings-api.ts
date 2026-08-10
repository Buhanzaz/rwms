import { bearerRequest } from "@/lib/api-client"
import { getGatewayRuntimeConfig } from "@/lib/gateway-config"

export type ShipmentTaskSettings = {
  warehouseId: string
  version: number
  maxCabinsPerShipmentTask: number
  updatedBy: string
  updatedAt: string
}

export type ShipmentTaskSettingsUpdate = {
  expectedVersion: number
  maxCabinsPerShipmentTask: number
}

export const shipmentTaskSettingsKeys = {
  all: ["logistics", "shipment-task-settings"] as const,
  warehouse: (warehouseId: string) =>
    [...shipmentTaskSettingsKeys.all, warehouseId] as const,
}

function shipmentTaskSettingsEndpoint(warehouseId: string) {
  if (!warehouseId.trim()) {
    throw new Error("Не выбран склад для настройки лимита бытовок в задании.")
  }

  return `${getGatewayRuntimeConfig().logisticsApiBaseUrl}/v1/warehouses/${encodeURIComponent(warehouseId)}/shipment-task-settings`
}

function isNonBlankString(value: unknown): value is string {
  return typeof value === "string" && value.trim().length > 0
}

function parseShipmentTaskSettings(value: unknown): ShipmentTaskSettings {
  if (typeof value !== "object" || value === null || Array.isArray(value)) {
    throw new Error(
      "Сервис логистики вернул некорректную настройку лимита бытовок."
    )
  }

  const response = value as Record<string, unknown>
  if (
    typeof response.warehouseId !== "string" ||
    !response.warehouseId.trim() ||
    typeof response.version !== "number" ||
    !Number.isInteger(response.version) ||
    response.version < 0 ||
    typeof response.maxCabinsPerShipmentTask !== "number" ||
    !Number.isInteger(response.maxCabinsPerShipmentTask) ||
    response.maxCabinsPerShipmentTask < 1 ||
    response.maxCabinsPerShipmentTask > 100 ||
    !isNonBlankString(response.updatedBy) ||
    !isNonBlankString(response.updatedAt)
  ) {
    throw new Error(
      "Сервис логистики вернул некорректную настройку лимита бытовок."
    )
  }

  return {
    warehouseId: response.warehouseId,
    version: response.version,
    maxCabinsPerShipmentTask: response.maxCabinsPerShipmentTask,
    updatedBy: response.updatedBy,
    updatedAt: response.updatedAt,
  }
}

function validateUpdate(input: ShipmentTaskSettingsUpdate) {
  if (
    !Number.isInteger(input.expectedVersion) ||
    input.expectedVersion < 0 ||
    !Number.isInteger(input.maxCabinsPerShipmentTask) ||
    input.maxCabinsPerShipmentTask < 1 ||
    input.maxCabinsPerShipmentTask > 100
  ) {
    throw new Error("Укажите целое количество бытовок от 1 до 100.")
  }
}

export async function getShipmentTaskSettings(
  accessToken: string,
  warehouseId: string
): Promise<ShipmentTaskSettings> {
  const response = await bearerRequest<unknown>(
    accessToken,
    shipmentTaskSettingsEndpoint(warehouseId)
  )

  return parseShipmentTaskSettings(response)
}

export async function updateShipmentTaskSettings(
  accessToken: string,
  warehouseId: string,
  input: ShipmentTaskSettingsUpdate
): Promise<ShipmentTaskSettings> {
  validateUpdate(input)

  const response = await bearerRequest<unknown>(
    accessToken,
    shipmentTaskSettingsEndpoint(warehouseId),
    {
      method: "PUT",
      body: JSON.stringify(input),
    }
  )

  return parseShipmentTaskSettings(response)
}
