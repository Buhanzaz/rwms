import { ApiError, bearerRequest } from "@/lib/api-client"
import { getGatewayRuntimeConfig } from "@/lib/gateway-config"
import type {
  EquipmentBalanceDto,
  EquipmentCategory,
  EquipmentDispositionDto,
  EquipmentDispositionListItemDto,
  EquipmentItemDto,
  EquipmentItemsQueryParams,
  EquipmentMovementDto,
  EquipmentWriteOffSummaryDto,
  DisposeEquipmentInput,
  TransferEquipmentInput,
} from "@/types/equipment"
import type { WarehouseInventoryStockItemDto } from "@/types/warehouse-location"

const UUID_PATTERN =
  /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i
const EQUIPMENT_CATEGORIES = ["FURNITURE", "ELECTRICAL", "OTHER"] as const
const BALANCE_LOCATION_KINDS = [
  "STOCK",
  "CABIN_NON_RENTED",
  "CABIN_RENTED",
  "WRITTEN_OFF",
  "LOST",
] as const

type JsonRecord = Record<string, unknown>

type AssetEquipmentDto = {
  id: string
  version: number
  code: string
  name: string
  category: EquipmentCategory
  active: boolean
  comment: string | null
  createdAt: string
  updatedAt: string
}

type AssetEquipmentTotalsDto = {
  equipmentId: string
  warehouseId: string
  totalQuantity: number
  stockQuantity: number
  nonRentedCabinQuantity: number
  rentedCabinQuantity: number
  writtenOffQuantity: number
  lostQuantity: number
  activeHeldQuantity: number
  availableStock: number
  balances: EquipmentBalanceDto[]
}

function assetApiBaseUrl() {
  return `${getGatewayRuntimeConfig().assetApiBaseUrl}/v1`
}

function requireAccessToken(accessToken: string | null | undefined) {
  if (
    accessToken === null ||
    accessToken === undefined ||
    accessToken.trim() === "" ||
    accessToken === "__rwms_dev_auth_bypass__"
  ) {
    throw new ApiError("Не получен Bearer-токен для сервиса имущества.", 401)
  }

  return accessToken
}

function record(value: unknown): JsonRecord {
  if (typeof value !== "object" || value === null || Array.isArray(value)) {
    throw new Error("Сервис имущества вернул некорректный ответ.")
  }

  return value as JsonRecord
}

function values(value: unknown) {
  if (!Array.isArray(value)) {
    throw new Error("Сервис имущества вернул некорректный ответ.")
  }

  return value
}

function string(value: unknown) {
  if (typeof value !== "string") {
    throw new Error("Сервис имущества вернул некорректный ответ.")
  }

  return value
}

function uuid(value: unknown) {
  const parsed = string(value)
  if (!UUID_PATTERN.test(parsed)) {
    throw new Error("Сервис имущества вернул некорректный идентификатор.")
  }

  return parsed
}

function nullableUuid(value: unknown) {
  return value === null || value === undefined ? null : uuid(value)
}

function nullableString(value: unknown) {
  return value === null || value === undefined ? null : string(value)
}

function nonNegativeInteger(value: unknown) {
  if (typeof value !== "number" || !Number.isSafeInteger(value) || value < 0) {
    throw new Error("Сервис имущества вернул некорректное числовое значение.")
  }

  return value
}

function boolean(value: unknown) {
  if (typeof value !== "boolean") {
    throw new Error("Сервис имущества вернул некорректный ответ.")
  }

  return value
}

function dateTime(value: unknown) {
  const parsed = string(value)
  if (Number.isNaN(Date.parse(parsed))) {
    throw new Error("Сервис имущества вернул некорректную дату.")
  }

  return parsed
}

function enumValue<T extends string>(value: unknown, allowed: readonly T[]) {
  const parsed = string(value)
  if (!allowed.includes(parsed as T)) {
    throw new Error("Сервис имущества вернул неизвестное значение справочника.")
  }

  return parsed as T
}

function parseEquipment(value: unknown): AssetEquipmentDto {
  const source = record(value)
  return {
    id: uuid(source.id),
    version: nonNegativeInteger(source.version),
    code: string(source.code),
    name: string(source.name),
    category: enumValue(source.category, EQUIPMENT_CATEGORIES),
    active: boolean(source.active),
    comment: nullableString(source.comment),
    createdAt: dateTime(source.createdAt),
    updatedAt: dateTime(source.updatedAt),
  }
}

function parseEquipmentBalance(value: unknown): EquipmentBalanceDto {
  const source = record(value)
  return {
    id: uuid(source.id),
    version: nonNegativeInteger(source.version),
    equipmentId: uuid(source.equipmentId),
    warehouseId: uuid(source.warehouseId),
    rentalItemId: nullableUuid(source.rentalItemId),
    locationKind: enumValue(source.locationKind, BALANCE_LOCATION_KINDS),
    quantity: nonNegativeInteger(source.quantity),
    activeHeldQuantity: nonNegativeInteger(source.activeHeldQuantity),
    availableStock: nonNegativeInteger(source.availableStock),
  }
}

function parseEquipmentTotals(value: unknown): AssetEquipmentTotalsDto {
  const source = record(value)
  return {
    equipmentId: uuid(source.equipmentId),
    warehouseId: uuid(source.warehouseId),
    totalQuantity: nonNegativeInteger(source.totalQuantity),
    stockQuantity: nonNegativeInteger(source.stockQuantity),
    nonRentedCabinQuantity: nonNegativeInteger(source.nonRentedCabinQuantity),
    rentedCabinQuantity: nonNegativeInteger(source.rentedCabinQuantity),
    writtenOffQuantity: nonNegativeInteger(source.writtenOffQuantity),
    lostQuantity: nonNegativeInteger(source.lostQuantity),
    activeHeldQuantity: nonNegativeInteger(source.activeHeldQuantity),
    availableStock: nonNegativeInteger(source.availableStock),
    balances: values(source.balances).map(parseEquipmentBalance),
  }
}

function parseEquipmentItem(value: unknown): EquipmentItemDto {
  const source = record(value)
  const equipment = parseEquipment(source.equipment)
  const totals = parseEquipmentTotals(source.totals)

  if (equipment.id !== totals.equipmentId) {
    throw new Error(
      "Сервис имущества вернул несогласованные остатки оборудования."
    )
  }

  if (
    totals.balances.some(
      (balance) =>
        balance.equipmentId !== equipment.id ||
        balance.warehouseId !== totals.warehouseId
    )
  ) {
    throw new Error(
      "Сервис имущества вернул несогласованный баланс оборудования."
    )
  }

  return {
    id: equipment.id,
    version: equipment.version,
    warehouseId: totals.warehouseId,
    code: equipment.code,
    name: equipment.name,
    category: equipment.category,
    active: equipment.active,
    comment: equipment.comment,
    totalQuantity: totals.totalQuantity,
    stockQuantity: totals.stockQuantity,
    cabinStockQuantity: totals.nonRentedCabinQuantity,
    rentedQuantity: totals.rentedCabinQuantity,
    writtenOffQuantity: totals.writtenOffQuantity,
    lostQuantity: totals.lostQuantity,
    activeHeldQuantity: totals.activeHeldQuantity,
    availableStock: totals.availableStock,
    balances: totals.balances,
    usages: [],
  }
}

function parseEquipmentDisposition(value: unknown): EquipmentDispositionDto {
  const source = record(value)
  const movement = record(source.movement)
  return {
    id: uuid(movement.id),
    version: nonNegativeInteger(movement.version),
    equipmentId: uuid(movement.equipmentId),
    sourceBalanceId: uuid(movement.sourceBalanceId),
    targetBalanceId: uuid(movement.targetBalanceId),
    quantity: nonNegativeInteger(movement.quantity),
    kind: string(movement.kind),
    occurredAt: dateTime(movement.occurredAt),
    equipmentCode: string(source.equipmentCode),
    equipmentName: string(source.equipmentName),
  }
}

function parseMovement(value: unknown): EquipmentMovementDto {
  const movement = record(value)
  return {
    id: uuid(movement.id),
    version: nonNegativeInteger(movement.version),
    equipmentId: uuid(movement.equipmentId),
    sourceBalanceId: uuid(movement.sourceBalanceId),
    targetBalanceId: uuid(movement.targetBalanceId),
    quantity: nonNegativeInteger(movement.quantity),
    kind: string(movement.kind),
    occurredAt: dateTime(movement.occurredAt),
  }
}

function searchByName<T extends { name?: string; equipmentName?: string }>(
  items: T[],
  search: string | undefined
) {
  const normalized = search?.trim().toLocaleLowerCase("ru-RU")
  if (!normalized) return items

  return items.filter((item) => {
    const candidate = item.name ?? item.equipmentName ?? ""
    return candidate.toLocaleLowerCase("ru-RU").includes(normalized)
  })
}

export function getEquipmentItems(
  params: EquipmentItemsQueryParams
): Promise<EquipmentItemDto[]>
export function getEquipmentItems(
  accessToken: string | null,
  params: EquipmentItemsQueryParams
): Promise<EquipmentItemDto[]>
export async function getEquipmentItems(
  accessTokenOrParams: string | null | EquipmentItemsQueryParams,
  maybeParams?: EquipmentItemsQueryParams
): Promise<EquipmentItemDto[]> {
  const legacyParamsCall =
    accessTokenOrParams !== null && typeof accessTokenOrParams === "object"
  const accessToken = legacyParamsCall ? null : accessTokenOrParams
  const params = legacyParamsCall ? accessTokenOrParams : maybeParams

  if (!params) {
    throw new Error("Не задан склад для оборудования.")
  }

  const endpoint = new URL(`${assetApiBaseUrl()}/equipment`)
  endpoint.searchParams.set("warehouseId", params.warehouseId)

  const items = values(
    await bearerRequest<unknown>(requireAccessToken(accessToken), endpoint)
  ).map(parseEquipmentItem)

  if (items.some((item) => item.warehouseId !== params.warehouseId)) {
    throw new Error("Сервис имущества вернул остатки другого склада.")
  }

  return searchByName(items, params.search).sort((left, right) =>
    left.name.localeCompare(right.name, "ru")
  )
}

export function listEquipmentDispositions(
  accessToken: string | null,
  warehouseId: string
): Promise<EquipmentDispositionDto[]> {
  const endpoint = new URL(`${assetApiBaseUrl()}/equipment/dispositions`)
  endpoint.searchParams.set("warehouseId", warehouseId)

  return bearerRequest<unknown>(requireAccessToken(accessToken), endpoint).then(
    (response) => values(response).map(parseEquipmentDisposition)
  )
}

export async function disposeEquipment(
  accessToken: string | null,
  idempotencyKey: string,
  input: DisposeEquipmentInput
): Promise<EquipmentMovementDto> {
  if (!UUID_PATTERN.test(idempotencyKey)) {
    throw new Error("Для списания нужен UUID Idempotency-Key.")
  }

  if (
    !Number.isSafeInteger(input.sourceExpectedVersion) ||
    input.sourceExpectedVersion < 0
  ) {
    throw new Error("Для списания нужна актуальная версия исходного остатка.")
  }

  if (!Number.isSafeInteger(input.quantity) || input.quantity < 1) {
    throw new Error("Количество списания должно быть целым и больше нуля.")
  }

  const request = {
    equipmentId: uuid(input.equipmentId),
    warehouseId: uuid(input.warehouseId),
    sourceRentalItemId: nullableUuid(input.sourceRentalItemId),
    sourceLocationKind: enumValue(
      input.sourceLocationKind,
      BALANCE_LOCATION_KINDS
    ),
    sourceExpectedVersion: input.sourceExpectedVersion,
    quantity: input.quantity,
    disposition: enumValue(input.disposition, ["WRITE_OFF", "LOSS"] as const),
  }

  const response = await bearerRequest<unknown>(
    requireAccessToken(accessToken),
    `${assetApiBaseUrl()}/equipment/dispositions`,
    {
      method: "POST",
      headers: { "Idempotency-Key": idempotencyKey },
      body: JSON.stringify(request),
    }
  )

  return parseMovement(response)
}

export async function transferEquipment(
  accessToken: string | null,
  idempotencyKey: string,
  input: TransferEquipmentInput
): Promise<EquipmentMovementDto> {
  if (!UUID_PATTERN.test(idempotencyKey)) {
    throw new Error("Для перемещения нужен UUID Idempotency-Key.")
  }

  if (
    !Number.isSafeInteger(input.sourceExpectedVersion) ||
    input.sourceExpectedVersion < 0 ||
    !Number.isSafeInteger(input.targetExpectedVersion) ||
    input.targetExpectedVersion < 0
  ) {
    throw new Error(
      "Для перемещения нужны актуальные версии исходного и целевого остатков."
    )
  }

  if (!Number.isSafeInteger(input.quantity) || input.quantity < 1) {
    throw new Error("Количество перемещения должно быть целым и больше нуля.")
  }

  const request = {
    equipmentId: uuid(input.equipmentId),
    sourceWarehouseId: uuid(input.sourceWarehouseId),
    sourceRentalItemId: nullableUuid(input.sourceRentalItemId),
    sourceLocationKind: enumValue(
      input.sourceLocationKind,
      BALANCE_LOCATION_KINDS
    ),
    sourceExpectedVersion: input.sourceExpectedVersion,
    targetWarehouseId: uuid(input.targetWarehouseId),
    targetRentalItemId: nullableUuid(input.targetRentalItemId),
    targetLocationKind: enumValue(
      input.targetLocationKind,
      BALANCE_LOCATION_KINDS
    ),
    targetExpectedVersion: input.targetExpectedVersion,
    quantity: input.quantity,
  }

  const response = await bearerRequest<unknown>(
    requireAccessToken(accessToken),
    `${assetApiBaseUrl()}/equipment/transfers`,
    {
      method: "POST",
      headers: { "Idempotency-Key": idempotencyKey },
      body: JSON.stringify(request),
    }
  )

  return parseMovement(response)
}

export function listEquipmentDispositionItems(params: {
  warehouseId: string
  search?: string
}): Promise<EquipmentDispositionListItemDto[]>
export function listEquipmentDispositionItems(
  accessToken: string | null,
  params: { warehouseId: string; search?: string }
): Promise<EquipmentDispositionListItemDto[]>
export async function listEquipmentDispositionItems(
  accessTokenOrParams: string | null | { warehouseId: string; search?: string },
  maybeParams?: { warehouseId: string; search?: string }
): Promise<EquipmentDispositionListItemDto[]> {
  const legacyParamsCall =
    accessTokenOrParams !== null && typeof accessTokenOrParams === "object"
  const accessToken = legacyParamsCall ? null : accessTokenOrParams
  const params = legacyParamsCall ? accessTokenOrParams : maybeParams

  if (!params) {
    throw new Error("Не задан склад для списаний оборудования.")
  }

  return searchByName(
    await listEquipmentDispositions(accessToken, params.warehouseId),
    params.search
  ).sort((left, right) => right.occurredAt.localeCompare(left.occurredAt))
}

export function getEquipmentWarehouseStock(
  warehouseId: string,
  accessToken: string | null = null
): Promise<WarehouseInventoryStockItemDto[]> {
  return getEquipmentItems(accessToken, { warehouseId }).then((items) =>
    items
      .filter((item) => item.availableStock > 0)
      .map((item) => ({
        name: item.name,
        availableQuantity: item.availableStock,
      }))
  )
}

export function listEquipmentWriteOffs(params: {
  warehouseId: string
  search?: string
}): Promise<EquipmentWriteOffSummaryDto[]>
export function listEquipmentWriteOffs(
  accessToken: string | null,
  params: { warehouseId: string; search?: string }
): Promise<EquipmentWriteOffSummaryDto[]>
export async function listEquipmentWriteOffs(
  accessTokenOrParams: string | null | { warehouseId: string; search?: string },
  maybeParams?: { warehouseId: string; search?: string }
): Promise<EquipmentWriteOffSummaryDto[]> {
  const legacyParamsCall =
    accessTokenOrParams !== null && typeof accessTokenOrParams === "object"
  const accessToken = legacyParamsCall ? null : accessTokenOrParams
  const params = legacyParamsCall ? accessTokenOrParams : maybeParams

  if (!params) {
    throw new Error("Не задан склад для оборудования.")
  }

  return (await getEquipmentItems(accessToken, params))
    .filter((item) => item.writtenOffQuantity > 0)
    .map((item) => ({
      id: item.id,
      warehouseId: item.warehouseId,
      name: item.name,
      writtenOffQuantity: item.writtenOffQuantity,
    }))
}
