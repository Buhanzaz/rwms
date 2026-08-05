import { bearerRequest } from "@/lib/api-client"
import { getGatewayRuntimeConfig } from "@/lib/gateway-config"

export type WarehouseInfo = {
  id: string
  version: number
  name: string
  city: string
  address: string | null
  timeZone: string
  active: boolean
  lifecycleState: WarehouseLifecycleState
  sortOrder: number | null
}

export type WarehouseLifecycleState = "ACTIVE" | "DRAINING" | "INACTIVE"

export type WarehouseWriteInput = {
  name: string
  city: string
  address: string | null
  timeZone: string
  sortOrder: number | null
}

export type WarehouseCreateInput = WarehouseWriteInput

export type WarehouseTimeZoneChange = {
  warehouseId: string
  warehouseVersion: number
  timeZone: string
  effectiveFrom: string
}

const WAREHOUSES_ENDPOINT = `${getGatewayRuntimeConfig().warehouseApiBaseUrl}/v1/warehouses`
const UUID_PATTERN =
  /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i
const WAREHOUSE_RESPONSE_KEYS = [
  "id",
  "version",
  "name",
  "city",
  "address",
  "timeZone",
  "active",
  "lifecycleState",
  "sortOrder",
] as const

const WAREHOUSE_TIME_ZONE_CHANGE_KEYS = [
  "warehouseId",
  "warehouseVersion",
  "timeZone",
  "effectiveFrom",
] as const

function requireAccessToken(accessToken: string | null): string {
  if (accessToken === null || accessToken.trim() === "") {
    throw new Error("Не получен токен доступа к сервису складов.")
  }

  return accessToken
}

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === "object" && value !== null && !Array.isArray(value)
}

function hasExactKeys(
  value: Record<string, unknown>,
  expectedKeys: readonly string[]
) {
  const actualKeys = Object.keys(value)

  return (
    actualKeys.length === expectedKeys.length &&
    expectedKeys.every((key) => Object.hasOwn(value, key))
  )
}

function isUuid(value: unknown): value is string {
  return typeof value === "string" && UUID_PATTERN.test(value)
}

function isNonEmptyString(value: unknown, maxLength: number): value is string {
  return (
    typeof value === "string" &&
    value.trim().length > 0 &&
    value.length <= maxLength
  )
}

function isNullableString(
  value: unknown,
  maxLength: number
): value is string | null {
  return (
    value === null || (typeof value === "string" && value.length <= maxLength)
  )
}

function isNonNegativeInteger(value: unknown): value is number {
  return Number.isInteger(value) && typeof value === "number" && value >= 0
}

function isOptionalSortOrder(value: unknown): value is number | null {
  return value === null || isNonNegativeInteger(value)
}

function isWarehouseLifecycleState(
  value: unknown
): value is WarehouseLifecycleState {
  return value === "ACTIVE" || value === "DRAINING" || value === "INACTIVE"
}

function isDateTime(value: unknown): value is string {
  return (
    typeof value === "string" &&
    value.trim().length > 0 &&
    Number.isFinite(Date.parse(value))
  )
}

function parseWarehouse(value: unknown): WarehouseInfo {
  if (!isRecord(value) || !hasExactKeys(value, WAREHOUSE_RESPONSE_KEYS)) {
    throw new Error("Сервис складов вернул некорректный ответ.")
  }

  const {
    id,
    version,
    name,
    city,
    address,
    timeZone,
    active,
    lifecycleState,
    sortOrder,
  } = value
  if (
    !isUuid(id) ||
    !isNonNegativeInteger(version) ||
    !isNonEmptyString(name, 255) ||
    !isNonEmptyString(city, 255) ||
    !isNullableString(address, 1000) ||
    !isNonEmptyString(timeZone, 64) ||
    typeof active !== "boolean" ||
    !isWarehouseLifecycleState(lifecycleState) ||
    active !== (lifecycleState === "ACTIVE") ||
    !isOptionalSortOrder(sortOrder)
  ) {
    throw new Error("Сервис складов вернул некорректный ответ.")
  }

  return {
    id,
    version,
    name,
    city,
    address,
    timeZone,
    active,
    lifecycleState,
    sortOrder,
  }
}

function parseWarehouseTimeZoneChange(value: unknown): WarehouseTimeZoneChange {
  if (
    !isRecord(value) ||
    !hasExactKeys(value, WAREHOUSE_TIME_ZONE_CHANGE_KEYS)
  ) {
    throw new Error("Сервис складов вернул некорректный ответ.")
  }

  const { warehouseId, warehouseVersion, timeZone, effectiveFrom } = value
  if (
    !isUuid(warehouseId) ||
    !isNonNegativeInteger(warehouseVersion) ||
    !isNonEmptyString(timeZone, 64) ||
    !isDateTime(effectiveFrom)
  ) {
    throw new Error("Сервис складов вернул некорректный ответ.")
  }

  return { warehouseId, warehouseVersion, timeZone, effectiveFrom }
}

function requireWarehouseId(warehouseId: string) {
  if (!isUuid(warehouseId)) {
    throw new Error("Некорректный идентификатор склада.")
  }

  return warehouseId
}

function requireExpectedVersion(expectedVersion: number) {
  if (!Number.isInteger(expectedVersion) || expectedVersion < 0) {
    throw new Error("Версия склада должна быть неотрицательным целым числом.")
  }

  return expectedVersion
}

function requireWarehouseWriteInput(input: WarehouseWriteInput) {
  const valid =
    isNonEmptyString(input.name, 255) &&
    isNonEmptyString(input.city, 255) &&
    isNullableString(input.address, 1000) &&
    isNonEmptyString(input.timeZone, 64) &&
    isOptionalSortOrder(input.sortOrder)

  if (!valid) {
    throw new Error("Параметры склада не соответствуют контракту API.")
  }
}

function requireIdempotencyKey(idempotencyKey: string) {
  if (!isUuid(idempotencyKey)) {
    throw new Error("Не удалось подготовить безопасный ключ команды.")
  }

  return idempotencyKey
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
    `${WAREHOUSES_ENDPOINT}/${encodeURIComponent(requireWarehouseId(warehouseId))}`
  )

  return parseWarehouse(response)
}

export async function createWarehouse(
  accessToken: string | null,
  idempotencyKey: string,
  input: WarehouseCreateInput
): Promise<WarehouseInfo> {
  requireWarehouseWriteInput(input)

  const response = await bearerRequest<unknown>(
    requireAccessToken(accessToken),
    WAREHOUSES_ENDPOINT,
    {
      method: "POST",
      headers: { "Idempotency-Key": requireIdempotencyKey(idempotencyKey) },
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
  requireWarehouseWriteInput(input)

  const response = await bearerRequest<unknown>(
    requireAccessToken(accessToken),
    `${WAREHOUSES_ENDPOINT}/${encodeURIComponent(requireWarehouseId(warehouseId))}`,
    {
      method: "PUT",
      body: JSON.stringify({
        expectedVersion: requireExpectedVersion(expectedVersion),
        name: input.name,
        city: input.city,
        address: input.address,
        timeZone: input.timeZone,
        sortOrder: input.sortOrder,
      }),
    }
  )

  return parseWarehouse(response)
}

async function transitionWarehouseLifecycle(
  accessToken: string | null,
  warehouseId: string,
  expectedVersion: number,
  transition: "draining" | "inactivation"
): Promise<WarehouseInfo> {
  const response = await bearerRequest<unknown>(
    requireAccessToken(accessToken),
    `${WAREHOUSES_ENDPOINT}/${encodeURIComponent(requireWarehouseId(warehouseId))}/${transition}`,
    {
      method: "POST",
      body: JSON.stringify({
        expectedVersion: requireExpectedVersion(expectedVersion),
      }),
    }
  )

  return parseWarehouse(response)
}

export function startWarehouseDraining(
  accessToken: string | null,
  warehouseId: string,
  expectedVersion: number
): Promise<WarehouseInfo> {
  return transitionWarehouseLifecycle(
    accessToken,
    warehouseId,
    expectedVersion,
    "draining"
  )
}

export function completeWarehouseInactivation(
  accessToken: string | null,
  warehouseId: string,
  expectedVersion: number
): Promise<WarehouseInfo> {
  return transitionWarehouseLifecycle(
    accessToken,
    warehouseId,
    expectedVersion,
    "inactivation"
  )
}

export async function scheduleWarehouseTimeZone(
  accessToken: string | null,
  warehouseId: string,
  expectedVersion: number,
  timeZone: string,
  effectiveFrom: string
): Promise<WarehouseTimeZoneChange> {
  if (!isNonEmptyString(timeZone, 64) || !isDateTime(effectiveFrom)) {
    throw new Error("Параметры изменения временной зоны не соответствуют API.")
  }

  const response = await bearerRequest<unknown>(
    requireAccessToken(accessToken),
    `${WAREHOUSES_ENDPOINT}/${encodeURIComponent(requireWarehouseId(warehouseId))}/time-zone-changes`,
    {
      method: "POST",
      body: JSON.stringify({
        expectedVersion: requireExpectedVersion(expectedVersion),
        timeZone,
        effectiveFrom,
      }),
    }
  )

  const change = parseWarehouseTimeZoneChange(response)
  if (change.warehouseId !== warehouseId) {
    throw new Error("Сервис складов вернул изменение другого склада.")
  }

  return change
}
