import { bearerRequest } from "@/lib/api-client"
import { getGatewayRuntimeConfig } from "@/lib/gateway-config"

export type WarehouseInfo = {
  id: string
  version: number
  name: string
  city: string
  address: string | null
  latitude: number | null
  longitude: number | null
  timeZone: string
  active: boolean
  lifecycleState: WarehouseLifecycleState
  sortOrder: number | null
  representative: boolean
}

export type WarehouseLifecycleState = "ACTIVE" | "DRAINING" | "INACTIVE"

export type WarehouseWriteInput = {
  name: string
  city: string
  address: string | null
  latitude: number | null
  longitude: number | null
  timeZone: string
  sortOrder: number | null
  representative: boolean
}

export type WarehouseCreateInput = WarehouseWriteInput

export type WarehouseSupportWeekday =
  | "MONDAY"
  | "TUESDAY"
  | "WEDNESDAY"
  | "THURSDAY"
  | "FRIDAY"
  | "SATURDAY"
  | "SUNDAY"

export type WarehouseSupportLinkInput = {
  supportWarehouseId: string
  active: boolean
  priority: number
  allowDrivers: boolean
  allowVehicles: boolean
  allowInventory: boolean
  allowDirectFulfillment: boolean
  allowInterwarehouseTransfer: boolean
  allowContractorFallback: boolean
  allowedWeekdays: WarehouseSupportWeekday[]
  allowedDates: string[]
  excludedDates: string[]
  serviceStart: string | null
  serviceEnd: string | null
}

export type WarehouseSupportLinkInfo = WarehouseSupportLinkInput & {
  id: string
  version: number
  servedWarehouseId: string
}

export type WarehouseSupportLinksInfo = {
  servedWarehouseId: string
  warehouseVersion: number
  links: WarehouseSupportLinkInfo[]
}

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
  "latitude",
  "longitude",
  "timeZone",
  "active",
  "lifecycleState",
  "sortOrder",
  "representative",
] as const

const WAREHOUSE_SUPPORT_LINK_KEYS = [
  "id",
  "version",
  "supportWarehouseId",
  "servedWarehouseId",
  "active",
  "priority",
  "allowDrivers",
  "allowVehicles",
  "allowInventory",
  "allowDirectFulfillment",
  "allowInterwarehouseTransfer",
  "allowContractorFallback",
  "allowedWeekdays",
  "allowedDates",
  "excludedDates",
  "serviceStart",
  "serviceEnd",
] as const

const WAREHOUSE_SUPPORT_LINKS_KEYS = [
  "servedWarehouseId",
  "warehouseVersion",
  "links",
] as const

const SUPPORT_WEEKDAYS: readonly WarehouseSupportWeekday[] = [
  "MONDAY",
  "TUESDAY",
  "WEDNESDAY",
  "THURSDAY",
  "FRIDAY",
  "SATURDAY",
  "SUNDAY",
]

const DATE_PATTERN = /^\d{4}-\d{2}-\d{2}$/
const TIME_PATTERN = /^([01]\d|2[0-3]):[0-5]\d(?::[0-5]\d(?:\.\d{1,9})?)?$/

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

function isNullableCoordinate(
  value: unknown,
  minimum: number,
  maximum: number
): value is number | null {
  return (
    value === null ||
    (typeof value === "number" &&
      Number.isFinite(value) &&
      value >= minimum &&
      value <= maximum)
  )
}

function isCoordinatePair(latitude: unknown, longitude: unknown) {
  return (
    isNullableCoordinate(latitude, -90, 90) &&
    isNullableCoordinate(longitude, -180, 180) &&
    ((latitude === null && longitude === null) ||
      (latitude !== null && longitude !== null))
  )
}

function isWarehouseSupportWeekday(
  value: unknown
): value is WarehouseSupportWeekday {
  return SUPPORT_WEEKDAYS.includes(value as WarehouseSupportWeekday)
}

function isCalendarDate(value: unknown): value is string {
  if (typeof value !== "string" || !DATE_PATTERN.test(value)) return false
  const parsed = new Date(`${value}T00:00:00Z`)
  return (
    !Number.isNaN(parsed.getTime()) && parsed.toISOString().startsWith(value)
  )
}

function isNullableTime(value: unknown): value is string | null {
  return (
    value === null || (typeof value === "string" && TIME_PATTERN.test(value))
  )
}

function isUniqueArray<T>(values: T[]) {
  return new Set(values).size === values.length
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
    latitude,
    longitude,
    timeZone,
    active,
    lifecycleState,
    sortOrder,
    representative,
  } = value
  if (
    !isUuid(id) ||
    !isNonNegativeInteger(version) ||
    !isNonEmptyString(name, 255) ||
    !isNonEmptyString(city, 255) ||
    !isNullableString(address, 1000) ||
    !isCoordinatePair(latitude, longitude) ||
    !isNonEmptyString(timeZone, 64) ||
    typeof active !== "boolean" ||
    !isWarehouseLifecycleState(lifecycleState) ||
    active !== (lifecycleState === "ACTIVE") ||
    !isOptionalSortOrder(sortOrder) ||
    typeof representative !== "boolean"
  ) {
    throw new Error("Сервис складов вернул некорректный ответ.")
  }

  return {
    id,
    version,
    name,
    city,
    address,
    latitude: latitude as number | null,
    longitude: longitude as number | null,
    timeZone,
    active,
    lifecycleState,
    sortOrder,
    representative,
  }
}

function parseWarehouseSupportLink(value: unknown): WarehouseSupportLinkInfo {
  if (!isRecord(value) || !hasExactKeys(value, WAREHOUSE_SUPPORT_LINK_KEYS)) {
    throw new Error("Сервис складов вернул некорректную опорную связь.")
  }

  const {
    id,
    version,
    supportWarehouseId,
    servedWarehouseId,
    active,
    priority,
    allowDrivers,
    allowVehicles,
    allowInventory,
    allowDirectFulfillment,
    allowInterwarehouseTransfer,
    allowContractorFallback,
    allowedWeekdays,
    allowedDates,
    excludedDates,
    serviceStart,
    serviceEnd,
  } = value

  const validWeekdays =
    Array.isArray(allowedWeekdays) &&
    allowedWeekdays.every(isWarehouseSupportWeekday) &&
    isUniqueArray(allowedWeekdays)
  const validAllowedDates =
    Array.isArray(allowedDates) &&
    allowedDates.every(isCalendarDate) &&
    isUniqueArray(allowedDates)
  const validExcludedDates =
    Array.isArray(excludedDates) &&
    excludedDates.every(isCalendarDate) &&
    isUniqueArray(excludedDates)

  if (
    !isUuid(id) ||
    !isNonNegativeInteger(version) ||
    !isUuid(supportWarehouseId) ||
    !isUuid(servedWarehouseId) ||
    typeof active !== "boolean" ||
    !Number.isInteger(priority) ||
    (priority as number) < 1 ||
    typeof allowDrivers !== "boolean" ||
    typeof allowVehicles !== "boolean" ||
    typeof allowInventory !== "boolean" ||
    typeof allowDirectFulfillment !== "boolean" ||
    typeof allowInterwarehouseTransfer !== "boolean" ||
    typeof allowContractorFallback !== "boolean" ||
    !validWeekdays ||
    !validAllowedDates ||
    !validExcludedDates ||
    !isNullableTime(serviceStart) ||
    !isNullableTime(serviceEnd) ||
    (serviceStart === null) !== (serviceEnd === null) ||
    (serviceStart !== null && serviceEnd !== null && serviceStart >= serviceEnd)
  ) {
    throw new Error("Сервис складов вернул некорректную опорную связь.")
  }

  return {
    id,
    version,
    supportWarehouseId,
    servedWarehouseId,
    active,
    priority: priority as number,
    allowDrivers,
    allowVehicles,
    allowInventory,
    allowDirectFulfillment,
    allowInterwarehouseTransfer,
    allowContractorFallback,
    allowedWeekdays: allowedWeekdays as WarehouseSupportWeekday[],
    allowedDates: allowedDates as string[],
    excludedDates: excludedDates as string[],
    serviceStart,
    serviceEnd,
  }
}

function parseWarehouseSupportLinks(
  value: unknown,
  expectedWarehouseId: string
): WarehouseSupportLinksInfo {
  if (!isRecord(value) || !hasExactKeys(value, WAREHOUSE_SUPPORT_LINKS_KEYS)) {
    throw new Error(
      "Сервис складов вернул некорректный список опорных складов."
    )
  }

  const { servedWarehouseId, warehouseVersion, links } = value
  if (
    servedWarehouseId !== expectedWarehouseId ||
    !isUuid(servedWarehouseId) ||
    !isNonNegativeInteger(warehouseVersion) ||
    !Array.isArray(links)
  ) {
    throw new Error(
      "Сервис складов вернул некорректный список опорных складов."
    )
  }

  const parsedLinks = links.map(parseWarehouseSupportLink)
  if (
    parsedLinks.some((link) => link.servedWarehouseId !== servedWarehouseId) ||
    !isUniqueArray(parsedLinks.map((link) => link.supportWarehouseId))
  ) {
    throw new Error(
      "Сервис складов вернул некорректный список опорных складов."
    )
  }

  return { servedWarehouseId, warehouseVersion, links: parsedLinks }
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
    isCoordinatePair(input.latitude, input.longitude) &&
    isNonEmptyString(input.timeZone, 64) &&
    isOptionalSortOrder(input.sortOrder) &&
    typeof input.representative === "boolean"

  if (!valid) {
    throw new Error("Параметры склада не соответствуют контракту API.")
  }
}

function requireWarehouseSupportLinkInput(
  servedWarehouseId: string,
  links: WarehouseSupportLinkInput[]
) {
  const supportWarehouseIds = new Set<string>()

  for (const link of links) {
    const valid =
      isUuid(link.supportWarehouseId) &&
      link.supportWarehouseId !== servedWarehouseId &&
      !supportWarehouseIds.has(link.supportWarehouseId) &&
      typeof link.active === "boolean" &&
      Number.isInteger(link.priority) &&
      link.priority >= 1 &&
      typeof link.allowDrivers === "boolean" &&
      typeof link.allowVehicles === "boolean" &&
      typeof link.allowInventory === "boolean" &&
      typeof link.allowDirectFulfillment === "boolean" &&
      typeof link.allowInterwarehouseTransfer === "boolean" &&
      typeof link.allowContractorFallback === "boolean" &&
      Array.isArray(link.allowedWeekdays) &&
      link.allowedWeekdays.every(isWarehouseSupportWeekday) &&
      isUniqueArray(link.allowedWeekdays) &&
      Array.isArray(link.allowedDates) &&
      link.allowedDates.every(isCalendarDate) &&
      isUniqueArray(link.allowedDates) &&
      Array.isArray(link.excludedDates) &&
      link.excludedDates.every(isCalendarDate) &&
      isUniqueArray(link.excludedDates) &&
      isNullableTime(link.serviceStart) &&
      isNullableTime(link.serviceEnd) &&
      ((link.serviceStart === null && link.serviceEnd === null) ||
        (link.serviceStart !== null &&
          link.serviceEnd !== null &&
          link.serviceStart < link.serviceEnd))

    if (!valid) {
      throw new Error(
        "Параметры опорного склада не соответствуют контракту API."
      )
    }
    supportWarehouseIds.add(link.supportWarehouseId)
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
        latitude: input.latitude,
        longitude: input.longitude,
        timeZone: input.timeZone,
        sortOrder: input.sortOrder,
        representative: input.representative,
      }),
    }
  )

  return parseWarehouse(response)
}

export async function listWarehouseSupportLinks(
  accessToken: string | null,
  servedWarehouseId: string
): Promise<WarehouseSupportLinksInfo> {
  const warehouseId = requireWarehouseId(servedWarehouseId)
  const response = await bearerRequest<unknown>(
    requireAccessToken(accessToken),
    `${WAREHOUSES_ENDPOINT}/${encodeURIComponent(warehouseId)}/support-links`
  )

  return parseWarehouseSupportLinks(response, warehouseId)
}

export async function replaceWarehouseSupportLinks(
  accessToken: string | null,
  servedWarehouseId: string,
  expectedVersion: number,
  links: WarehouseSupportLinkInput[]
): Promise<WarehouseSupportLinksInfo> {
  const warehouseId = requireWarehouseId(servedWarehouseId)
  requireWarehouseSupportLinkInput(warehouseId, links)
  const response = await bearerRequest<unknown>(
    requireAccessToken(accessToken),
    `${WAREHOUSES_ENDPOINT}/${encodeURIComponent(warehouseId)}/support-links`,
    {
      method: "PUT",
      body: JSON.stringify({
        expectedVersion: requireExpectedVersion(expectedVersion),
        links,
      }),
    }
  )

  return parseWarehouseSupportLinks(response, warehouseId)
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
