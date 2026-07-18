import { ApiError, bearerRequest } from "@/lib/api-client"
import { getGatewayRuntimeConfig } from "@/lib/gateway-config"
import {
  formatRentalItemContents,
  type PageResponse,
  type RentalItemContentsItemDto,
  type RentalItemDto,
  type RentalItemStatus,
} from "@/features/rental-items/model/rental-item"

const INVALID_RESPONSE_MESSAGE =
  "Сервис имущества вернул некорректный ответ о бытовке."

const MISSING_ACCESS_TOKEN_MESSAGE =
  "Не получен токен доступа к сервису имущества."

const UUID_PATTERN =
  /^[0-9a-f]{8}-[0-9a-f]{4}-[1-5][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/i

const RENTAL_ITEM_STATUSES = new Set<RentalItemStatus>([
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
])

type UnknownRecord = Record<string, unknown>

type AssetEquipmentContent = {
  equipmentId: string
  equipmentCode: string
  quantity: number
  locationKind: string
}

type AssetRentalItem = {
  id: string
  version: number
  warehouseId: string
  number: string
  status: RentalItemStatus
  rentalType: string | null
  dimensions: string | null
  finishing: string | null
  category: string | null
  characteristics: string | null
  linoleum: boolean | null
  generalComment: string | null
  passport: UnknownRecord
  tags: string[]
  contents: AssetEquipmentContent[]
  createdAt: string
  updatedAt: string
}

export type CreateAssetRentalItemInput = {
  warehouseId: string
  number: string
  rentalType: string | null
  dimensions: string | null
  finishing: string | null
  category: string | null
  characteristics: string | null
  linoleum: boolean | null
  passport?: UnknownRecord
  tags?: string[]
}

export type UpdateAssetRentalItemStatusInput = {
  id: string
  expectedVersion: number
  status: Exclude<RentalItemStatus, "IN_TRANSFER" | "WRITTEN_OFF">
}

export type UpdateAssetRentalItemGeneralCommentInput = {
  id: string
  expectedVersion: number
  comment: string | null
}

export type AssetRentalItemManualNote = {
  id: string
  rentalItemId: string
  text: string
  createdAt: string
}

export class AssetRentalItemConflictError extends Error {
  constructor(message: string) {
    super(message)
    this.name = "AssetRentalItemConflictError"
  }
}

function assertPublicStatus(
  status: RentalItemStatus
): asserts status is Exclude<RentalItemStatus, "IN_TRANSFER" | "WRITTEN_OFF"> {
  if (status === "IN_TRANSFER" || status === "WRITTEN_OFF") {
    throw new Error(
      "Публичная смена статуса не поддерживает IN_TRANSFER и WRITTEN_OFF."
    )
  }
}

function requireAccessToken(accessToken: string | null): string {
  if (accessToken === null || accessToken.trim() === "") {
    throw new Error(MISSING_ACCESS_TOKEN_MESSAGE)
  }

  return accessToken
}

function isRecord(value: unknown): value is UnknownRecord {
  return typeof value === "object" && value !== null && !Array.isArray(value)
}

function isUuid(value: unknown): value is string {
  return typeof value === "string" && UUID_PATTERN.test(value)
}

function isNonNegativeSafeInteger(value: unknown): value is number {
  return typeof value === "number" && Number.isSafeInteger(value) && value >= 0
}

function isNullableString(value: unknown): value is string | null {
  return value === null || typeof value === "string"
}

function isIsoDateTime(value: unknown): value is string {
  return (
    typeof value === "string" &&
    value.trim() !== "" &&
    Number.isFinite(Date.parse(value))
  )
}

function parseEquipmentContent(value: unknown): AssetEquipmentContent {
  if (!isRecord(value)) {
    throw new Error(INVALID_RESPONSE_MESSAGE)
  }

  const { equipmentId, equipmentCode, quantity, locationKind } = value
  if (
    !isUuid(equipmentId) ||
    typeof equipmentCode !== "string" ||
    equipmentCode.trim() === "" ||
    !isNonNegativeSafeInteger(quantity) ||
    typeof locationKind !== "string" ||
    locationKind.trim() === ""
  ) {
    throw new Error(INVALID_RESPONSE_MESSAGE)
  }

  return { equipmentId, equipmentCode, quantity, locationKind }
}

function parseAssetRentalItem(value: unknown): AssetRentalItem {
  if (!isRecord(value)) {
    throw new Error(INVALID_RESPONSE_MESSAGE)
  }

  const {
    id,
    version,
    warehouseId,
    number,
    status,
    rentalType,
    dimensions,
    finishing,
    category,
    characteristics,
    linoleum,
    generalComment,
    passport,
    tags,
    contents,
    createdAt,
    updatedAt,
  } = value

  if (
    !isUuid(id) ||
    !isNonNegativeSafeInteger(version) ||
    !isUuid(warehouseId) ||
    typeof number !== "string" ||
    number.trim() === "" ||
    typeof status !== "string" ||
    !RENTAL_ITEM_STATUSES.has(status as RentalItemStatus) ||
    !isNullableString(rentalType) ||
    !isNullableString(dimensions) ||
    !isNullableString(finishing) ||
    !isNullableString(category) ||
    !isNullableString(characteristics) ||
    (linoleum !== null && typeof linoleum !== "boolean") ||
    !isNullableString(generalComment) ||
    !isRecord(passport) ||
    !Array.isArray(tags) ||
    !tags.every((tag) => typeof tag === "string") ||
    !Array.isArray(contents) ||
    !isIsoDateTime(createdAt) ||
    !isIsoDateTime(updatedAt)
  ) {
    throw new Error(INVALID_RESPONSE_MESSAGE)
  }

  return {
    id,
    version,
    warehouseId,
    number,
    status: status as RentalItemStatus,
    rentalType,
    dimensions,
    finishing,
    category,
    characteristics,
    linoleum,
    generalComment,
    passport,
    tags,
    contents: contents.map(parseEquipmentContent),
    createdAt,
    updatedAt,
  }
}

function toRentalItemContents(
  contents: AssetEquipmentContent[]
): RentalItemContentsItemDto[] {
  return contents.map((content) => ({
    equipmentId: content.equipmentId,
    equipmentCode: content.equipmentCode,
    locationKind: content.locationKind,
    name: content.equipmentCode,
    quantity: content.quantity,
  }))
}

export function mapAssetRentalItem(value: unknown): RentalItemDto {
  const item = parseAssetRentalItem(value)
  const contentsItems = toRentalItemContents(item.contents)

  return {
    id: item.id,
    version: item.version,
    warehouseId: item.warehouseId,
    number: item.number,
    type: item.rentalType ?? "—",
    dimensions: item.dimensions,
    finishing: item.finishing,
    category: item.category,
    characteristics: item.characteristics,
    linoleum: item.linoleum,
    status: item.status,
    comment: item.generalComment,
    mediaAvailability: "UNAVAILABLE",
    hasPhotos: false,
    photoCount: 0,
    mainPhotoUrl: null,
    previewPhotoUrls: [],
    locationNodeId: null,
    contents: formatRentalItemContents(contentsItems),
    contentsItems,
    shipmentDate: null,
    tenant: null,
    price: null,
    passport: item.passport,
    tags: item.tags,
    createdAt: item.createdAt,
    updatedAt: item.updatedAt,
  }
}

function assetRentalItemsEndpoint() {
  return `${getGatewayRuntimeConfig().assetApiBaseUrl}/v1/rental-items`
}

function itemEndpoint(id: string) {
  return `${assetRentalItemsEndpoint()}/${encodeURIComponent(id)}`
}

function appendQuery(
  endpoint: string,
  params: Record<string, string | number | readonly string[] | undefined>
) {
  const query = new URLSearchParams()
  Object.entries(params).forEach(([key, value]) => {
    if (Array.isArray(value)) {
      value.forEach((entry) => query.append(key, entry))
    } else if (value !== undefined) {
      query.set(key, String(value))
    }
  })
  const encoded = query.toString()
  return encoded ? `${endpoint}?${encoded}` : endpoint
}

function mapConflict(error: unknown): never {
  if (error instanceof ApiError && error.status === 409) {
    throw new AssetRentalItemConflictError(error.message)
  }

  throw error
}

function parsePage(value: unknown): PageResponse<RentalItemDto> {
  if (!isRecord(value)) {
    throw new Error(INVALID_RESPONSE_MESSAGE)
  }

  const { content, page, size, totalElements, totalPages } = value
  if (
    !Array.isArray(content) ||
    !isNonNegativeSafeInteger(page) ||
    !isNonNegativeSafeInteger(size) ||
    !isNonNegativeSafeInteger(totalElements) ||
    !isNonNegativeSafeInteger(totalPages)
  ) {
    throw new Error(INVALID_RESPONSE_MESSAGE)
  }

  return {
    content: content.map(mapAssetRentalItem),
    page,
    size,
    totalElements,
    totalPages,
  }
}

export async function listAssetRentalItems(params: {
  accessToken: string | null
  warehouseId: string
  page?: number
  size?: number
  search?: string
  excludeStatuses?: RentalItemStatus[]
}): Promise<PageResponse<RentalItemDto>> {
  const response = await bearerRequest<unknown>(
    requireAccessToken(params.accessToken),
    appendQuery(assetRentalItemsEndpoint(), {
      warehouseId: params.warehouseId,
      page: params.page ?? 0,
      size: params.size ?? 200,
      search: params.search?.trim() || undefined,
      excludeStatus: params.excludeStatuses,
    })
  )
  return parsePage(response)
}

export async function getAssetRentalItem(
  accessToken: string | null,
  rentalItemId: string
): Promise<RentalItemDto> {
  const response = await bearerRequest<unknown>(
    requireAccessToken(accessToken),
    itemEndpoint(rentalItemId)
  )
  return mapAssetRentalItem(response)
}

export async function createAssetRentalItem(params: {
  accessToken: string | null
  idempotencyKey: string
  input: CreateAssetRentalItemInput
}): Promise<RentalItemDto> {
  try {
    const response = await bearerRequest<unknown>(
      requireAccessToken(params.accessToken),
      assetRentalItemsEndpoint(),
      {
        method: "POST",
        headers: { "Idempotency-Key": params.idempotencyKey },
        body: JSON.stringify({
          warehouseId: params.input.warehouseId,
          number: params.input.number,
          rentalType: params.input.rentalType,
          dimensions: params.input.dimensions,
          finishing: params.input.finishing,
          category: params.input.category,
          characteristics: params.input.characteristics,
          linoleum: params.input.linoleum,
          passport: params.input.passport ?? {},
          tags: params.input.tags ?? [],
        }),
      }
    )
    return mapAssetRentalItem(response)
  } catch (error) {
    return mapConflict(error)
  }
}

export async function updateAssetRentalItemStatus(params: {
  accessToken: string | null
  input: UpdateAssetRentalItemStatusInput
}): Promise<RentalItemDto> {
  assertPublicStatus(params.input.status)

  try {
    const response = await bearerRequest<unknown>(
      requireAccessToken(params.accessToken),
      `${itemEndpoint(params.input.id)}/status`,
      {
        method: "PUT",
        body: JSON.stringify({
          expectedVersion: params.input.expectedVersion,
          status: params.input.status,
        }),
      }
    )
    return mapAssetRentalItem(response)
  } catch (error) {
    return mapConflict(error)
  }
}

export async function updateAssetRentalItemGeneralComment(params: {
  accessToken: string | null
  input: UpdateAssetRentalItemGeneralCommentInput
}): Promise<RentalItemDto> {
  try {
    const response = await bearerRequest<unknown>(
      requireAccessToken(params.accessToken),
      `${itemEndpoint(params.input.id)}/general-comment`,
      {
        method: "PUT",
        body: JSON.stringify({
          expectedVersion: params.input.expectedVersion,
          comment: params.input.comment,
        }),
      }
    )
    return mapAssetRentalItem(response)
  } catch (error) {
    return mapConflict(error)
  }
}

function parseManualNote(value: unknown): AssetRentalItemManualNote {
  if (!isRecord(value)) {
    throw new Error(INVALID_RESPONSE_MESSAGE)
  }

  const { id, rentalItemId, text, createdAt } = value
  if (
    !isUuid(id) ||
    !isUuid(rentalItemId) ||
    typeof text !== "string" ||
    text.trim() === "" ||
    !isIsoDateTime(createdAt)
  ) {
    throw new Error(INVALID_RESPONSE_MESSAGE)
  }

  return { id, rentalItemId, text, createdAt }
}

export async function listAssetRentalItemManualNotes(
  accessToken: string | null,
  rentalItemId: string
): Promise<AssetRentalItemManualNote[]> {
  const response = await bearerRequest<unknown>(
    requireAccessToken(accessToken),
    `${itemEndpoint(rentalItemId)}/manual-notes`
  )
  if (!Array.isArray(response)) {
    throw new Error(INVALID_RESPONSE_MESSAGE)
  }

  return response.map(parseManualNote)
}

export async function addAssetRentalItemManualNote(params: {
  accessToken: string | null
  rentalItemId: string
  expectedVersion: number
  text: string
  idempotencyKey: string
}): Promise<AssetRentalItemManualNote> {
  try {
    const response = await bearerRequest<unknown>(
      requireAccessToken(params.accessToken),
      `${itemEndpoint(params.rentalItemId)}/manual-notes`,
      {
        method: "POST",
        headers: { "Idempotency-Key": params.idempotencyKey },
        body: JSON.stringify({
          expectedVersion: params.expectedVersion,
          text: params.text,
        }),
      }
    )
    return parseManualNote(response)
  } catch (error) {
    return mapConflict(error)
  }
}

export function createIdempotencyKey() {
  if (
    typeof crypto !== "undefined" &&
    typeof crypto.randomUUID === "function"
  ) {
    return crypto.randomUUID()
  }

  throw new Error("Браузер не поддерживает генерацию ключа идемпотентности.")
}
