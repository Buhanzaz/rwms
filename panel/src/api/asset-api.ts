import { bearerRequest } from "@/lib/api-client"
import { getGatewayRuntimeConfig } from "@/lib/gateway-config"

const ASSET_API = `${getGatewayRuntimeConfig().assetApiBaseUrl}/v1`

export type AssetRentalItemStatus =
  | "NEW"
  | "RENTED"
  | "BOOKED"
  | "REPAIR"
  | "WAITING_REPAIR_CHECK"
  | "WRITTEN_OFF"
  | "CAPITAL_REPAIR"
  | "AFTER_RENT"
  | "WAITING_ESTIMATE_CONFIRMATION"
  | "SALE"
  | "USED_SALE"
  | "RESERVED"
  | "FREE"
  | "WAREHOUSE"
  | "OWN_NEEDS"
  | "IN_TRANSFER"

export type AssetRentalItem = {
  id: string
  version: number
  warehouseId: string
  number: string
  status: AssetRentalItemStatus
  rentalType: string | null
  dimensions: string | null
  finishing: string | null
  category: string | null
  characteristics: string | null
  linoleum: boolean | null
  generalComment: string | null
  passport: Record<string, unknown>
  tags: string[]
  contents: AssetEquipmentContent[]
  createdAt: string
  updatedAt: string
}

export type AssetEquipmentContent = {
  equipmentId: string
  equipmentCode: string
  quantity: number
  locationKind: AssetBalanceLocationKind
}

export type AssetRentalItemPage = {
  content: AssetRentalItem[]
  page: number
  size: number
  totalElements: number
  totalPages: number
}

export type AssetManualNote = {
  id: string
  rentalItemId: string
  text: string
  createdAt: string
}

export type AssetEquipmentCategory = "FURNITURE" | "ELECTRICAL" | "OTHER"
export type AssetBalanceLocationKind =
  "STOCK" | "CABIN_NON_RENTED" | "CABIN_RENTED" | "WRITTEN_OFF" | "LOST"

export type AssetEquipment = {
  id: string
  version: number
  code: string
  name: string
  category: AssetEquipmentCategory
  active: boolean
  comment: string | null
  createdAt: string
  updatedAt: string
}

export type AssetEquipmentBalance = {
  id: string
  version: number
  equipmentId: string
  warehouseId: string
  rentalItemId: string | null
  locationKind: AssetBalanceLocationKind
  quantity: number
  activeHeldQuantity: number
  availableStock: number
}

export type AssetEquipmentTotals = {
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
  balances: AssetEquipmentBalance[]
}

export type AssetWarehouseEquipment = {
  equipment: AssetEquipment
  totals: AssetEquipmentTotals
}

export type AssetMovement = {
  id: string
  version: number
  equipmentId: string
  sourceBalanceId: string
  targetBalanceId: string
  quantity: number
  kind: string
  occurredAt: string
}

export type AssetEquipmentDisposition = {
  movement: AssetMovement
  equipmentCode: string
  equipmentName: string
}

export type AssetClassifierType =
  "CATEGORY" | "SUBCATEGORY" | "TYPE" | "CONDITION"

export type AssetClassifier = {
  id: string
  version: number
  type: AssetClassifierType
  parentId: string | null
  code: string
  name: string
  active: boolean
  sortOrder: number | null
}

type JsonRecord = Record<string, unknown>

function requireAccessToken(accessToken: string | null) {
  if (accessToken === null || accessToken.trim() === "") {
    throw new Error("Не получен токен доступа к сервису имущества.")
  }

  return accessToken
}

function record(
  value: unknown,
  message = "Сервис имущества вернул некорректный ответ."
): JsonRecord {
  if (typeof value !== "object" || value === null || Array.isArray(value)) {
    throw new Error(message)
  }

  return value as JsonRecord
}

function string(value: unknown, message?: string) {
  if (typeof value !== "string") {
    throw new Error(message ?? "Сервис имущества вернул некорректный ответ.")
  }

  return value
}

function nullableString(value: unknown) {
  return value === null ? null : string(value)
}

function number(value: unknown) {
  if (typeof value !== "number" || !Number.isSafeInteger(value) || value < 0) {
    throw new Error("Сервис имущества вернул некорректный ответ.")
  }

  return value
}

function nullableNumber(value: unknown) {
  return value === null ? null : number(value)
}

function boolean(value: unknown) {
  if (typeof value !== "boolean") {
    throw new Error("Сервис имущества вернул некорректный ответ.")
  }

  return value
}

function nullableBoolean(value: unknown) {
  return value === null ? null : boolean(value)
}

function array(value: unknown) {
  if (!Array.isArray(value)) {
    throw new Error("Сервис имущества вернул некорректный ответ.")
  }

  return value
}

function enumValue<T extends string>(value: unknown, values: readonly T[]): T {
  const result = string(value)
  if (!values.includes(result as T)) {
    throw new Error("Сервис имущества вернул неизвестное значение справочника.")
  }

  return result as T
}

const RENTAL_STATUSES = [
  "NEW",
  "RENTED",
  "BOOKED",
  "REPAIR",
  "WAITING_REPAIR_CHECK",
  "WRITTEN_OFF",
  "CAPITAL_REPAIR",
  "AFTER_RENT",
  "WAITING_ESTIMATE_CONFIRMATION",
  "SALE",
  "USED_SALE",
  "RESERVED",
  "FREE",
  "WAREHOUSE",
  "OWN_NEEDS",
  "IN_TRANSFER",
] as const
const BALANCE_KINDS = [
  "STOCK",
  "CABIN_NON_RENTED",
  "CABIN_RENTED",
  "WRITTEN_OFF",
  "LOST",
] as const
const EQUIPMENT_CATEGORIES = ["FURNITURE", "ELECTRICAL", "OTHER"] as const
const CLASSIFIER_TYPES = [
  "CATEGORY",
  "SUBCATEGORY",
  "TYPE",
  "CONDITION",
] as const

function parseContent(value: unknown): AssetEquipmentContent {
  const source = record(value)
  return {
    equipmentId: string(source.equipmentId),
    equipmentCode: string(source.equipmentCode),
    quantity: number(source.quantity),
    locationKind: enumValue(source.locationKind, BALANCE_KINDS),
  }
}

function parseRentalItem(value: unknown): AssetRentalItem {
  const source = record(value)
  const passport = record(source.passport)
  return {
    id: string(source.id),
    version: number(source.version),
    warehouseId: string(source.warehouseId),
    number: string(source.number),
    status: enumValue(source.status, RENTAL_STATUSES),
    rentalType: nullableString(source.rentalType),
    dimensions: nullableString(source.dimensions),
    finishing: nullableString(source.finishing),
    category: nullableString(source.category),
    characteristics: nullableString(source.characteristics),
    linoleum: nullableBoolean(source.linoleum),
    generalComment: nullableString(source.generalComment),
    passport,
    tags: array(source.tags).map((item) => string(item)),
    contents: array(source.contents).map(parseContent),
    createdAt: string(source.createdAt),
    updatedAt: string(source.updatedAt),
  }
}

function parseRentalItemPage(value: unknown): AssetRentalItemPage {
  const source = record(value)
  return {
    content: array(source.content).map(parseRentalItem),
    page: number(source.page),
    size: number(source.size),
    totalElements: number(source.totalElements),
    totalPages: number(source.totalPages),
  }
}

function parseManualNote(value: unknown): AssetManualNote {
  const source = record(value)
  return {
    id: string(source.id),
    rentalItemId: string(source.rentalItemId),
    text: string(source.text),
    createdAt: string(source.createdAt),
  }
}

function parseEquipment(value: unknown): AssetEquipment {
  const source = record(value)
  return {
    id: string(source.id),
    version: number(source.version),
    code: string(source.code),
    name: string(source.name),
    category: enumValue(source.category, EQUIPMENT_CATEGORIES),
    active: boolean(source.active),
    comment: nullableString(source.comment),
    createdAt: string(source.createdAt),
    updatedAt: string(source.updatedAt),
  }
}

function parseBalance(value: unknown): AssetEquipmentBalance {
  const source = record(value)
  return {
    id: string(source.id),
    version: number(source.version),
    equipmentId: string(source.equipmentId),
    warehouseId: string(source.warehouseId),
    rentalItemId: nullableString(source.rentalItemId),
    locationKind: enumValue(source.locationKind, BALANCE_KINDS),
    quantity: number(source.quantity),
    activeHeldQuantity: number(source.activeHeldQuantity),
    availableStock: number(source.availableStock),
  }
}

function parseTotals(value: unknown): AssetEquipmentTotals {
  const source = record(value)
  return {
    equipmentId: string(source.equipmentId),
    warehouseId: string(source.warehouseId),
    totalQuantity: number(source.totalQuantity),
    stockQuantity: number(source.stockQuantity),
    nonRentedCabinQuantity: number(source.nonRentedCabinQuantity),
    rentedCabinQuantity: number(source.rentedCabinQuantity),
    writtenOffQuantity: number(source.writtenOffQuantity),
    lostQuantity: number(source.lostQuantity),
    activeHeldQuantity: number(source.activeHeldQuantity),
    availableStock: number(source.availableStock),
    balances: array(source.balances).map(parseBalance),
  }
}

function parseWarehouseEquipment(value: unknown): AssetWarehouseEquipment {
  const source = record(value)
  return {
    equipment: parseEquipment(source.equipment),
    totals: parseTotals(source.totals),
  }
}

function parseMovement(value: unknown): AssetMovement {
  const source = record(value)
  return {
    id: string(source.id),
    version: number(source.version),
    equipmentId: string(source.equipmentId),
    sourceBalanceId: string(source.sourceBalanceId),
    targetBalanceId: string(source.targetBalanceId),
    quantity: number(source.quantity),
    kind: string(source.kind),
    occurredAt: string(source.occurredAt),
  }
}

function parseDisposition(value: unknown): AssetEquipmentDisposition {
  const source = record(value)
  return {
    movement: parseMovement(source.movement),
    equipmentCode: string(source.equipmentCode),
    equipmentName: string(source.equipmentName),
  }
}

function parseClassifier(value: unknown): AssetClassifier {
  const source = record(value)
  return {
    id: string(source.id),
    version: number(source.version),
    type: enumValue(source.type, CLASSIFIER_TYPES),
    parentId: nullableString(source.parentId),
    code: string(source.code),
    name: string(source.name),
    active: boolean(source.active),
    sortOrder: nullableNumber(source.sortOrder),
  }
}

export async function listAssetRentalItems(
  accessToken: string | null,
  options: {
    warehouseId: string
    search?: string
    page?: number
    size?: number
  }
) {
  const endpoint = new URL(`${ASSET_API}/rental-items`)
  endpoint.searchParams.set("warehouseId", options.warehouseId)
  endpoint.searchParams.set("page", String(options.page ?? 0))
  endpoint.searchParams.set("size", String(options.size ?? 50))
  if (options.search?.trim())
    endpoint.searchParams.set("search", options.search.trim())
  return parseRentalItemPage(
    await bearerRequest<unknown>(requireAccessToken(accessToken), endpoint)
  )
}

export async function getAssetRentalItem(
  accessToken: string | null,
  rentalItemId: string
) {
  return parseRentalItem(
    await bearerRequest<unknown>(
      requireAccessToken(accessToken),
      `${ASSET_API}/rental-items/${encodeURIComponent(rentalItemId)}`
    )
  )
}

export async function createAssetRentalItem(
  accessToken: string | null,
  idempotencyKey: string,
  input: Omit<
    AssetRentalItem,
    | "id"
    | "version"
    | "status"
    | "generalComment"
    | "contents"
    | "createdAt"
    | "updatedAt"
  > & { warehouseId: string; number: string }
) {
  const response = await bearerRequest<unknown>(
    requireAccessToken(accessToken),
    `${ASSET_API}/rental-items`,
    {
      method: "POST",
      headers: { "Idempotency-Key": idempotencyKey },
      body: JSON.stringify({
        warehouseId: input.warehouseId,
        number: input.number,
        rentalType: input.rentalType,
        dimensions: input.dimensions,
        finishing: input.finishing,
        category: input.category,
        characteristics: input.characteristics,
        linoleum: input.linoleum,
        passport: input.passport,
        tags: input.tags,
      }),
    }
  )
  return parseRentalItem(response)
}

export async function updateAssetRentalItemStatus(
  accessToken: string | null,
  rentalItemId: string,
  expectedVersion: number,
  status: AssetRentalItemStatus
) {
  return parseRentalItem(
    await bearerRequest<unknown>(
      requireAccessToken(accessToken),
      `${ASSET_API}/rental-items/${encodeURIComponent(rentalItemId)}/status`,
      {
        method: "PUT",
        body: JSON.stringify({ expectedVersion, status }),
      }
    )
  )
}

export async function updateAssetRentalItemPassport(
  accessToken: string | null,
  rentalItemId: string,
  input: Pick<
    AssetRentalItem,
    | "version"
    | "rentalType"
    | "dimensions"
    | "finishing"
    | "category"
    | "characteristics"
    | "linoleum"
    | "passport"
    | "tags"
  >
) {
  return parseRentalItem(
    await bearerRequest<unknown>(
      requireAccessToken(accessToken),
      `${ASSET_API}/rental-items/${encodeURIComponent(rentalItemId)}/passport`,
      {
        method: "PUT",
        body: JSON.stringify({
          expectedVersion: input.version,
          rentalType: input.rentalType,
          dimensions: input.dimensions,
          finishing: input.finishing,
          category: input.category,
          characteristics: input.characteristics,
          linoleum: input.linoleum,
          passport: input.passport,
          tags: input.tags,
        }),
      }
    )
  )
}

export async function updateAssetRentalItemGeneralComment(
  accessToken: string | null,
  rentalItemId: string,
  expectedVersion: number,
  comment: string | null
) {
  return parseRentalItem(
    await bearerRequest<unknown>(
      requireAccessToken(accessToken),
      `${ASSET_API}/rental-items/${encodeURIComponent(rentalItemId)}/general-comment`,
      {
        method: "PUT",
        body: JSON.stringify({ expectedVersion, comment }),
      }
    )
  )
}

export async function listAssetManualNotes(
  accessToken: string | null,
  rentalItemId: string
) {
  const response = await bearerRequest<unknown>(
    requireAccessToken(accessToken),
    `${ASSET_API}/rental-items/${encodeURIComponent(rentalItemId)}/manual-notes`
  )
  return array(response).map(parseManualNote)
}

export async function addAssetManualNote(
  accessToken: string | null,
  rentalItemId: string,
  idempotencyKey: string,
  expectedVersion: number,
  text: string
) {
  return parseManualNote(
    await bearerRequest<unknown>(
      requireAccessToken(accessToken),
      `${ASSET_API}/rental-items/${encodeURIComponent(rentalItemId)}/manual-notes`,
      {
        method: "POST",
        headers: { "Idempotency-Key": idempotencyKey },
        body: JSON.stringify({ expectedVersion, text }),
      }
    )
  )
}

export async function listAssetEquipment(
  accessToken: string | null,
  warehouseId: string
) {
  const endpoint = new URL(`${ASSET_API}/equipment`)
  endpoint.searchParams.set("warehouseId", warehouseId)
  return array(
    await bearerRequest<unknown>(requireAccessToken(accessToken), endpoint)
  ).map(parseWarehouseEquipment)
}

export async function listAssetEquipmentCatalog(accessToken: string | null) {
  return array(
    await bearerRequest<unknown>(
      requireAccessToken(accessToken),
      `${ASSET_API}/equipment/catalog`
    )
  ).map(parseEquipment)
}

export async function createAssetEquipmentCatalogItem(
  accessToken: string | null,
  idempotencyKey: string,
  input: Pick<AssetEquipment, "code" | "name" | "category" | "comment">
) {
  return parseEquipment(
    await bearerRequest<unknown>(
      requireAccessToken(accessToken),
      `${ASSET_API}/equipment/catalog`,
      {
        method: "POST",
        headers: { "Idempotency-Key": idempotencyKey },
        body: JSON.stringify(input),
      }
    )
  )
}

export async function updateAssetEquipmentCatalogItem(
  accessToken: string | null,
  id: string,
  input: Pick<
    AssetEquipment,
    "version" | "code" | "name" | "category" | "active" | "comment"
  >
) {
  return parseEquipment(
    await bearerRequest<unknown>(
      requireAccessToken(accessToken),
      `${ASSET_API}/equipment/catalog/${encodeURIComponent(id)}`,
      {
        method: "PUT",
        body: JSON.stringify({ expectedVersion: input.version, ...input }),
      }
    )
  )
}

export async function listAssetEquipmentDispositions(
  accessToken: string | null,
  warehouseId: string
) {
  const endpoint = new URL(`${ASSET_API}/equipment/dispositions`)
  endpoint.searchParams.set("warehouseId", warehouseId)
  return array(
    await bearerRequest<unknown>(requireAccessToken(accessToken), endpoint)
  ).map(parseDisposition)
}

export async function disposeAssetEquipment(
  accessToken: string | null,
  idempotencyKey: string,
  input: {
    equipmentId: string
    warehouseId: string
    sourceRentalItemId: string | null
    sourceLocationKind: AssetBalanceLocationKind
    sourceExpectedVersion: number
    quantity: number
    disposition: "WRITE_OFF" | "LOSS"
  }
) {
  return parseMovement(
    await bearerRequest<unknown>(
      requireAccessToken(accessToken),
      `${ASSET_API}/equipment/dispositions`,
      {
        method: "POST",
        headers: { "Idempotency-Key": idempotencyKey },
        body: JSON.stringify(input),
      }
    )
  )
}

export async function listAssetClassifiers(
  accessToken: string | null,
  type?: AssetClassifierType
) {
  const endpoint = new URL(`${ASSET_API}/classifiers`)
  if (type) endpoint.searchParams.set("type", type)
  return array(
    await bearerRequest<unknown>(requireAccessToken(accessToken), endpoint)
  ).map(parseClassifier)
}

export async function createAssetClassifier(
  accessToken: string | null,
  idempotencyKey: string,
  input: Omit<AssetClassifier, "id" | "version">
) {
  return parseClassifier(
    await bearerRequest<unknown>(
      requireAccessToken(accessToken),
      `${ASSET_API}/classifiers`,
      {
        method: "POST",
        headers: { "Idempotency-Key": idempotencyKey },
        body: JSON.stringify(input),
      }
    )
  )
}

export async function updateAssetClassifier(
  accessToken: string | null,
  id: string,
  input: AssetClassifier
) {
  return parseClassifier(
    await bearerRequest<unknown>(
      requireAccessToken(accessToken),
      `${ASSET_API}/classifiers/${encodeURIComponent(id)}`,
      {
        method: "PUT",
        body: JSON.stringify({
          expectedVersion: input.version,
          type: input.type,
          parentId: input.parentId,
          code: input.code,
          name: input.name,
          active: input.active,
          sortOrder: input.sortOrder,
        }),
      }
    )
  )
}
