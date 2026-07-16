import { bearerRequest } from "@/lib/api-client"
import { getGatewayRuntimeConfig } from "@/lib/gateway-config"

export type WarehouseInfo = {
  id: string
  version: number
  code: string
  name: string
  city: string
  address: string | null
  timeZone: string
  active: boolean
  sortOrder: number | null
}

export type WarehouseWriteInput = {
  code: string
  name: string
  city: string
  address: string | null
  timeZone: string
  active: boolean
  sortOrder: number | null
}

export type WarehouseCreateInput = Omit<WarehouseWriteInput, "active">

const WAREHOUSES_ENDPOINT = `${getGatewayRuntimeConfig().warehouseApiBaseUrl}/v1/warehouses`

function requireAccessToken(accessToken: string | null): string {
  if (accessToken === null || accessToken.trim() === "") {
    throw new Error("Не получен токен доступа к сервису складов.")
  }

  return accessToken
}

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === "object" && value !== null
}

function parseWarehouse(value: unknown): WarehouseInfo {
  if (!isRecord(value)) {
    throw new Error("Сервис складов вернул некорректный ответ.")
  }

  const {
    id,
    version,
    code,
    name,
    city,
    address,
    timeZone,
    active,
    sortOrder,
  } = value
  const valid =
    typeof id === "string" &&
    typeof version === "number" &&
    Number.isInteger(version) &&
    typeof code === "string" &&
    typeof name === "string" &&
    typeof city === "string" &&
    (typeof address === "string" || address === null) &&
    typeof timeZone === "string" &&
    typeof active === "boolean" &&
    (sortOrder === null ||
      (typeof sortOrder === "number" && Number.isInteger(sortOrder)))

  if (!valid) {
    throw new Error("Сервис складов вернул некорректный ответ.")
  }

  return {
    id,
    version,
    code,
    name,
    city,
    address,
    timeZone,
    active,
    sortOrder,
  }
}

export async function listWarehouses(
  accessToken: string | null,
  includeInactive = false
): Promise<WarehouseInfo[]> {
  const endpoint = new URL(WAREHOUSES_ENDPOINT)
  if (includeInactive) {
    endpoint.searchParams.set("includeInactive", "true")
  }

  const response = await bearerRequest<unknown>(
    requireAccessToken(accessToken),
    endpoint
  )

  if (!Array.isArray(response)) {
    throw new Error("Сервис складов вернул некорректный список.")
  }

  return response.map(parseWarehouse)
}

export async function getWarehouse(
  accessToken: string | null,
  warehouseId: string
): Promise<WarehouseInfo> {
  const response = await bearerRequest<unknown>(
    requireAccessToken(accessToken),
    `${WAREHOUSES_ENDPOINT}/${encodeURIComponent(warehouseId)}`
  )
  return parseWarehouse(response)
}

export async function createWarehouse(
  accessToken: string | null,
  idempotencyKey: string,
  input: WarehouseCreateInput
): Promise<WarehouseInfo> {
  const response = await bearerRequest<unknown>(
    requireAccessToken(accessToken),
    WAREHOUSES_ENDPOINT,
    {
      method: "POST",
      headers: { "Idempotency-Key": idempotencyKey },
      body: JSON.stringify(input),
    }
  )
  return parseWarehouse(response)
}

export async function replaceWarehouse(
  accessToken: string | null,
  warehouseId: string,
  expectedVersion: number,
  input: WarehouseWriteInput
): Promise<WarehouseInfo> {
  const response = await bearerRequest<unknown>(
    requireAccessToken(accessToken),
    `${WAREHOUSES_ENDPOINT}/${encodeURIComponent(warehouseId)}`,
    {
      method: "PUT",
      body: JSON.stringify({ ...input, expectedVersion }),
    }
  )
  return parseWarehouse(response)
}

export async function deactivateWarehouse(
  accessToken: string | null,
  warehouseId: string,
  expectedVersion: number
): Promise<void> {
  const endpoint = new URL(
    `${WAREHOUSES_ENDPOINT}/${encodeURIComponent(warehouseId)}`
  )
  endpoint.searchParams.set("expectedVersion", String(expectedVersion))
  await bearerRequest<void>(requireAccessToken(accessToken), endpoint, {
    method: "DELETE",
  })
}
