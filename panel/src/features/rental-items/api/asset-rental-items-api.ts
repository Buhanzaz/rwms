import { ApiError, bearerRequest } from "@/lib/api-client"
import { getGatewayRuntimeConfig } from "@/lib/gateway-config"
import {
  formatRentalItemContents,
  type PageResponse,
  type RentalItemContentsItemDto,
  type RentalItemActiveOrderReservationDto,
  type RentalItemDto,
  type RentalItemStatus,
} from "@/features/rental-items/model/rental-item"

const INVALID_RESPONSE_MESSAGE =
  "Сервис имущества вернул некорректный ответ о бытовке."

const MISSING_ACCESS_TOKEN_MESSAGE =
  "Не получен токен доступа к сервису имущества."

const UUID_PATTERN =
  /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i

const RENTAL_ITEM_STATUSES = new Set<RentalItemStatus>([
  "RENTED",
  "BOOKED",
  "REPAIR",
  "WAITING_REPAIR_CHECK",
  "WRITTEN_OFF",
  "LOST",
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
  equipmentName: string
  quantity: number
  locationKind: string
}

type AssetRentalItem = {
  id: string
  version: number
  warehouseId: string
  number: string
  status: RentalItemStatus
  rentalTypeId: string | null
  rentalType: string | null
  dimensionId: string | null
  dimensions: string | null
  finishingId: string | null
  finishing: string | null
  category: string | null
  characteristics: CabinCatalogValue[]
  linoleum: boolean | null
  generalComment: string | null
  passport: UnknownRecord
  tags: string[]
  contents: AssetEquipmentContent[]
  activeOrderReservation: RentalItemActiveOrderReservationDto | null
  createdAt: string
  updatedAt: string
}

export type CreateAssetRentalItemInput = {
  warehouseId: string
  number: string
  rentalTypeId: string
  dimensionId: string
  finishingId: string
  category: string | null
  characteristicIds: string[]
  linoleum: boolean
  passport?: UnknownRecord
  tags?: string[]
}

export type UpdateAssetRentalItemPassportInput = {
  id: string
  expectedVersion: number
  rentalTypeId: string
  dimensionId: string
  finishingId: string
  category: string | null
  characteristicIds: string[]
  linoleum: boolean
  passport?: UnknownRecord
  tags?: string[]
}

export type CabinCatalogKind =
  "TYPE" | "DIMENSION" | "FINISHING" | "CHARACTERISTIC"

export type CabinCatalogValue = {
  id: string
  name: string
}

export type CabinCatalogItem = CabinCatalogValue & {
  version: number
  kind: CabinCatalogKind
  active: boolean
  createdAt: string
  updatedAt: string
}

export type CabinTypeDimension = {
  typeId: string
  dimensionId: string
  sortOrder: number
}

export type RentalItemCreationOptions = {
  newCategory: string
  usedCategories: string[]
  rentalTypes: CabinCatalogValue[]
  dimensions: CabinCatalogValue[]
  finishings: CabinCatalogValue[]
  categories: CabinCatalogValue[]
  characteristics: CabinCatalogValue[]
  typeDimensions: CabinTypeDimension[]
}

export type CabinSettings = {
  types: CabinCatalogItem[]
  dimensions: CabinCatalogItem[]
  finishings: CabinCatalogItem[]
  characteristics: CabinCatalogItem[]
  typeDimensions: CabinTypeDimension[]
}

export type UpdateAssetRentalItemStatusInput = {
  id: string
  expectedVersion: number
  status: Exclude<
    RentalItemStatus,
    "IN_TRANSFER" | "WRITTEN_OFF" | "LOST"
  >
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
): asserts status is Exclude<
  RentalItemStatus,
  "IN_TRANSFER" | "WRITTEN_OFF" | "LOST"
> {
  if (
    status === "IN_TRANSFER" ||
    status === "WRITTEN_OFF" ||
    status === "LOST"
  ) {
    throw new Error(
      "Публичная смена статуса не поддерживает IN_TRANSFER, WRITTEN_OFF и LOST."
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

function isOptionalNullableString(
  value: unknown
): value is string | null | undefined {
  return value === undefined || isNullableString(value)
}

function isOptionalNullableUuid(
  value: unknown
): value is string | null | undefined {
  return value === undefined || value === null || isUuid(value)
}

function isOptionalNullableBoolean(
  value: unknown
): value is boolean | null | undefined {
  return value === undefined || value === null || typeof value === "boolean"
}

function isIsoDateTime(value: unknown): value is string {
  return (
    typeof value === "string" &&
    value.trim() !== "" &&
    Number.isFinite(Date.parse(value))
  )
}

function isCabinCatalogKind(value: unknown): value is CabinCatalogKind {
  return (
    value === "TYPE" ||
    value === "DIMENSION" ||
    value === "FINISHING" ||
    value === "CHARACTERISTIC"
  )
}

function parseCabinCatalogValue(value: unknown): CabinCatalogValue {
  if (!isRecord(value)) {
    throw new Error(INVALID_RESPONSE_MESSAGE)
  }

  const { id, name } = value
  if (!isUuid(id) || typeof name !== "string" || name.trim() === "") {
    throw new Error(INVALID_RESPONSE_MESSAGE)
  }

  return { id, name }
}

function parseCabinCatalogItem(value: unknown): CabinCatalogItem {
  if (!isRecord(value)) {
    throw new Error(INVALID_RESPONSE_MESSAGE)
  }

  const { id, version, kind, name, active, createdAt, updatedAt } = value
  if (
    !isUuid(id) ||
    !isNonNegativeSafeInteger(version) ||
    !isCabinCatalogKind(kind) ||
    typeof name !== "string" ||
    name.trim() === "" ||
    typeof active !== "boolean" ||
    !isIsoDateTime(createdAt) ||
    !isIsoDateTime(updatedAt)
  ) {
    throw new Error(INVALID_RESPONSE_MESSAGE)
  }

  return { id, version, kind, name, active, createdAt, updatedAt }
}

function parseCabinTypeDimension(value: unknown): CabinTypeDimension {
  if (!isRecord(value)) {
    throw new Error(INVALID_RESPONSE_MESSAGE)
  }

  const { typeId, dimensionId, sortOrder } = value
  if (
    !isUuid(typeId) ||
    !isUuid(dimensionId) ||
    !isNonNegativeSafeInteger(sortOrder)
  ) {
    throw new Error(INVALID_RESPONSE_MESSAGE)
  }

  return { typeId, dimensionId, sortOrder }
}

function parseStringList(value: unknown): string[] {
  if (
    !Array.isArray(value) ||
    !value.every((entry) => typeof entry === "string" && entry.trim() !== "")
  ) {
    throw new Error(INVALID_RESPONSE_MESSAGE)
  }

  return [...value]
}

function parseEquipmentContent(value: unknown): AssetEquipmentContent {
  if (!isRecord(value)) {
    throw new Error(INVALID_RESPONSE_MESSAGE)
  }

  const { equipmentId, equipmentName, quantity, locationKind } = value
  if (
    !isUuid(equipmentId) ||
    typeof equipmentName !== "string" ||
    equipmentName.trim() === "" ||
    !isNonNegativeSafeInteger(quantity) ||
    typeof locationKind !== "string" ||
    locationKind.trim() === ""
  ) {
    throw new Error(INVALID_RESPONSE_MESSAGE)
  }

  return {
    equipmentId,
    equipmentName,
    quantity,
    locationKind,
  }
}

function parseActiveOrderReservation(
  value: unknown
): RentalItemActiveOrderReservationDto | null {
  if (value === null) return null
  if (!isRecord(value)) {
    throw new Error(INVALID_RESPONSE_MESSAGE)
  }
  const { reservationId, orderId, clientId, tenantSnapshot, reservedAt } = value
  if (
    !isUuid(reservationId) ||
    !isUuid(orderId) ||
    (clientId !== null && !isUuid(clientId)) ||
    !isNullableString(tenantSnapshot) ||
    !isIsoDateTime(reservedAt)
  ) {
    throw new Error(INVALID_RESPONSE_MESSAGE)
  }
  return { reservationId, orderId, clientId, tenantSnapshot, reservedAt }
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
    rentalTypeId,
    rentalType,
    dimensionId,
    dimensions,
    finishingId,
    finishing,
    category,
    characteristics,
    linoleum,
    generalComment,
    passport,
    tags,
    contents,
    activeOrderReservation,
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
    !isOptionalNullableUuid(rentalTypeId) ||
    !isOptionalNullableString(rentalType) ||
    !isOptionalNullableUuid(dimensionId) ||
    !isOptionalNullableString(dimensions) ||
    !isOptionalNullableUuid(finishingId) ||
    !isOptionalNullableString(finishing) ||
    !isOptionalNullableString(category) ||
    !Array.isArray(characteristics) ||
    !isOptionalNullableBoolean(linoleum) ||
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
    rentalTypeId: rentalTypeId ?? null,
    rentalType: rentalType ?? null,
    dimensionId: dimensionId ?? null,
    dimensions: dimensions ?? null,
    finishingId: finishingId ?? null,
    finishing: finishing ?? null,
    category: category ?? null,
    characteristics: characteristics.map(parseCabinCatalogValue),
    linoleum: linoleum ?? null,
    generalComment,
    passport,
    tags,
    contents: contents.map(parseEquipmentContent),
    activeOrderReservation: parseActiveOrderReservation(activeOrderReservation),
    createdAt,
    updatedAt,
  }
}

function toRentalItemContents(
  contents: AssetEquipmentContent[]
): RentalItemContentsItemDto[] {
  return contents.map((content) => ({
    equipmentId: content.equipmentId,
    equipmentName: content.equipmentName,
    locationKind: content.locationKind,
    name: content.equipmentName,
    quantity: content.quantity,
  }))
}

function passportString(passport: UnknownRecord, key: string) {
  const value = passport[key]
  return typeof value === "string" && value.trim() ? value : null
}

function compositionLabel(value: string | null): string {
  return value?.trim() || "—"
}

function optionalCompositionLabel(value: string | null): string | null {
  return value?.trim() || null
}

export function mapAssetRentalItem(value: unknown): RentalItemDto {
  const item = parseAssetRentalItem(value)
  const contentsItems = toRentalItemContents(item.contents)
  const passportPrice = item.passport.price
  const price =
    typeof passportPrice === "number" && Number.isFinite(passportPrice)
      ? passportPrice
      : null

  return {
    id: item.id,
    version: item.version,
    warehouseId: item.warehouseId,
    number: item.number,
    // The normal passport commands stay UUID-only.  Imported incomplete
    // cabins use an empty form value so the existing required-field checks
    // force an explicit selection before a passport update can be sent.
    rentalTypeId: item.rentalTypeId ?? "",
    dimensionId: item.dimensionId ?? "",
    finishingId: item.finishingId ?? "",
    type: compositionLabel(item.rentalType),
    dimensions: optionalCompositionLabel(item.dimensions),
    finishing: optionalCompositionLabel(item.finishing),
    category: item.category,
    characteristics: item.characteristics,
    linoleum: item.linoleum,
    status: item.status,
    comment: item.generalComment,
    mediaAvailability: "AVAILABLE",
    contents: formatRentalItemContents(contentsItems),
    contentsItems,
    shipmentDate: passportString(item.passport, "shipmentDate"),
    tenant:
      item.activeOrderReservation?.tenantSnapshot ??
      passportString(item.passport, "tenant"),
    price,
    activeOrderReservation: item.activeOrderReservation,
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

function cabinSettingsEndpoint() {
  return `${getGatewayRuntimeConfig().assetApiBaseUrl}/v1/cabin-settings`
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

function parseRentalItemCreationOptions(
  value: unknown
): RentalItemCreationOptions {
  if (!isRecord(value)) {
    throw new Error(INVALID_RESPONSE_MESSAGE)
  }

  const {
    newCategory,
    usedCategories,
    rentalTypes,
    dimensions,
    finishings,
    categories,
    characteristics,
    typeDimensions,
  } = value
  if (
    typeof newCategory !== "string" ||
    newCategory.trim() === "" ||
    !Array.isArray(rentalTypes) ||
    !Array.isArray(dimensions) ||
    !Array.isArray(finishings) ||
    !Array.isArray(categories) ||
    !Array.isArray(characteristics) ||
    !Array.isArray(typeDimensions)
  ) {
    throw new Error(INVALID_RESPONSE_MESSAGE)
  }

  return {
    newCategory,
    usedCategories: parseStringList(usedCategories),
    rentalTypes: rentalTypes.map(parseCabinCatalogValue),
    dimensions: dimensions.map(parseCabinCatalogValue),
    finishings: finishings.map(parseCabinCatalogValue),
    categories: categories.map(parseCabinCatalogValue),
    characteristics: characteristics.map(parseCabinCatalogValue),
    typeDimensions: typeDimensions.map(parseCabinTypeDimension),
  }
}

function parseCabinSettings(value: unknown): CabinSettings {
  if (!isRecord(value)) {
    throw new Error(INVALID_RESPONSE_MESSAGE)
  }

  const { types, dimensions, finishings, characteristics, typeDimensions } =
    value
  if (
    !Array.isArray(types) ||
    !Array.isArray(dimensions) ||
    !Array.isArray(finishings) ||
    !Array.isArray(characteristics) ||
    !Array.isArray(typeDimensions)
  ) {
    throw new Error(INVALID_RESPONSE_MESSAGE)
  }

  return {
    types: types.map(parseCabinCatalogItem),
    dimensions: dimensions.map(parseCabinCatalogItem),
    finishings: finishings.map(parseCabinCatalogItem),
    characteristics: characteristics.map(parseCabinCatalogItem),
    typeDimensions: typeDimensions.map(parseCabinTypeDimension),
  }
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

export function rentalItemCreationOptionsQueryKey(warehouseId: string) {
  return ["rental-item-creation-options", warehouseId] as const
}

export async function getRentalItemCreationOptions(
  accessToken: string | null,
  warehouseId: string
): Promise<RentalItemCreationOptions> {
  const response = await bearerRequest<unknown>(
    requireAccessToken(accessToken),
    appendQuery(`${assetRentalItemsEndpoint()}/creation-options`, {
      warehouseId,
    })
  )
  return parseRentalItemCreationOptions(response)
}

export async function getCabinSettings(
  accessToken: string | null
): Promise<CabinSettings> {
  const response = await bearerRequest<unknown>(
    requireAccessToken(accessToken),
    cabinSettingsEndpoint()
  )
  return parseCabinSettings(response)
}

export async function createCabinCatalogItem(params: {
  accessToken: string | null
  idempotencyKey: string
  input: { kind: CabinCatalogKind; name: string }
}): Promise<CabinCatalogItem> {
  try {
    const response = await bearerRequest<unknown>(
      requireAccessToken(params.accessToken),
      `${cabinSettingsEndpoint()}/items`,
      {
        method: "POST",
        headers: { "Idempotency-Key": params.idempotencyKey },
        body: JSON.stringify(params.input),
      }
    )
    return parseCabinCatalogItem(response)
  } catch (error) {
    return mapConflict(error)
  }
}

export async function updateCabinCatalogItem(params: {
  accessToken: string | null
  id: string
  expectedVersion: number
  name: string
  active: boolean
}): Promise<CabinCatalogItem> {
  try {
    const response = await bearerRequest<unknown>(
      requireAccessToken(params.accessToken),
      `${cabinSettingsEndpoint()}/items/${encodeURIComponent(params.id)}`,
      {
        method: "PUT",
        body: JSON.stringify({
          expectedVersion: params.expectedVersion,
          name: params.name,
          active: params.active,
        }),
      }
    )
    return parseCabinCatalogItem(response)
  } catch (error) {
    return mapConflict(error)
  }
}

/**
 * Permanently removes an unused cabin-composition catalog value. The asset
 * service is the authority for whether the value is still referenced.
 */
export async function deleteCabinCatalogItem(params: {
  accessToken: string | null
  id: string
  expectedVersion: number
}): Promise<void> {
  await bearerRequest<void>(
    requireAccessToken(params.accessToken),
    appendQuery(
      `${cabinSettingsEndpoint()}/items/${encodeURIComponent(params.id)}`,
      { expectedVersion: params.expectedVersion }
    ),
    { method: "DELETE" }
  )
}

export async function replaceCabinTypeDimensions(params: {
  accessToken: string | null
  typeId: string
  expectedVersion: number
  dimensionIds: string[]
}): Promise<CabinSettings> {
  try {
    const response = await bearerRequest<unknown>(
      requireAccessToken(params.accessToken),
      `${cabinSettingsEndpoint()}/types/${encodeURIComponent(params.typeId)}/dimensions`,
      {
        method: "PUT",
        body: JSON.stringify({
          expectedVersion: params.expectedVersion,
          dimensionIds: params.dimensionIds,
        }),
      }
    )
    return parseCabinSettings(response)
  } catch (error) {
    return mapConflict(error)
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
          rentalTypeId: params.input.rentalTypeId,
          dimensionId: params.input.dimensionId,
          finishingId: params.input.finishingId,
          category: params.input.category,
          characteristicIds: params.input.characteristicIds,
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

export async function updateAssetRentalItemPassport(params: {
  accessToken: string | null
  input: UpdateAssetRentalItemPassportInput
}): Promise<RentalItemDto> {
  try {
    const response = await bearerRequest<unknown>(
      requireAccessToken(params.accessToken),
      `${itemEndpoint(params.input.id)}/passport`,
      {
        method: "PUT",
        body: JSON.stringify({
          expectedVersion: params.input.expectedVersion,
          rentalTypeId: params.input.rentalTypeId,
          dimensionId: params.input.dimensionId,
          finishingId: params.input.finishingId,
          category: params.input.category,
          characteristicIds: params.input.characteristicIds,
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

const INVALID_HTML_IMPORT_RESPONSE_MESSAGE =
  "Сервис имущества вернул некорректные данные импорта HTML."

export type HtmlImportMappingAction = "MAP" | "IGNORE" | "CREATE"

export type HtmlImportRowAction = "MERGE" | "CREATE" | "EXCLUDE" | "REVIEW"

export type HtmlImportFieldChoice = "SOURCE" | "TARGET"

export type HtmlImportMappedStatus = Exclude<
  RentalItemStatus,
  "IN_TRANSFER" | "WRITTEN_OFF" | "LOST"
>

export type HtmlImportState =
  | "DRAFT"
  | "REVIEW_REQUIRED"
  | "READY"
  | "COMMITTING"
  | "ASSETS_COMMITTED"
  | "MEDIA_IMPORTING"
  | "COMPLETED"
  | "COMPLETED_WITH_WARNINGS"
  | "FAILED"

export type HtmlImportDiagnostic = {
  id: string
  severity: "ERROR" | "WARNING" | "INFO"
  message: string
  code: string | null
  rowId: string | null
}

export type HtmlImportMappingTarget = {
  id: string
  label: string
  kind: string | null
  active: boolean | null
}

export type HtmlImportMapping = {
  id: string
  group: "CATALOG" | "EQUIPMENT"
  kind: string | null
  sourceId: string
  sourceValue: string
  sourceLabel: string
  suggestedValue: string | null
  occurrences: number | null
  required: boolean
  targets: HtmlImportMappingTarget[]
  suggestedTargetId: string | null
  plannedAction: HtmlImportMappingAction | null
  plannedTargetId: string | null
}

export type HtmlImportStatusCandidate = {
  id: string
  sourceValue: string
  sourceLabel: string
  suggestedTargetStatus: HtmlImportMappedStatus | null
  occurrences: number | null
  required: boolean
  plannedTargetStatus: HtmlImportMappedStatus | null
}

export type HtmlImportCabin = {
  id: string | null
  version: number | null
  label: string
  fields: Record<string, string | null>
  mediaCount: number | null
}

export type HtmlImportRowDiagnostic = {
  severity: "ERROR" | "WARNING" | "INFO"
  code: string
  field: string | null
}

export type HtmlImportRow = {
  id: string
  sourceRowId: string
  number: string
  proposedNumber: string | null
  source: HtmlImportCabin
  existing: HtmlImportCabin | null
  suggestedAction: HtmlImportRowAction | null
  plannedAction: HtmlImportRowAction | null
  plannedExistingRentalItemId: string | null
  plannedRentalTypeId: string | null
  plannedDimensionId: string | null
  plannedFinishingId: string | null
  fieldDecisions: Record<string, HtmlImportFieldChoice>
  diagnostics: HtmlImportRowDiagnostic[]
}

export type HtmlImportRowsPage = {
  content: HtmlImportRow[]
  page: number
  size: number
  totalElements: number
  totalPages: number
}

export type HtmlImportMediaReplacement = {
  rowId: string
  publicUrl: string
}

export type HtmlImportSummary = {
  sourceRows: number | null
  mappedCatalogValues: number | null
  mappedEquipmentValues: number | null
  createRows: number | null
  mergeRows: number | null
  excludedRows: number | null
  committedRows: number | null
  mediaPendingRows: number | null
}

export type HtmlImport = {
  id: string
  warehouseId: string | null
  version: number
  state: HtmlImportState
  diagnostics: HtmlImportDiagnostic[]
  catalogTargets: HtmlImportMappingTarget[]
  catalogMappings: HtmlImportMapping[]
  equipmentMappings: HtmlImportMapping[]
  statusCandidates: HtmlImportStatusCandidate[]
  statusMappings: HtmlImportStatusMapping[]
  summary: HtmlImportSummary
  mediaJobId: string | null
  failureCode: string | null
  createdAt: string | null
  updatedAt: string | null
}

export type HtmlImportMappingDecision = {
  kind?: string
  sourceValue: string
  action: HtmlImportMappingAction
  targetId?: string
  stagedName?: string
}

export type HtmlImportRowDecision = {
  sourceRowId: string
  action: HtmlImportRowAction
  proposedNumber?: string
  targetRentalItemId?: string
  targetExpectedVersion?: number
  rentalTypeId?: string
  dimensionId?: string
  finishingId?: string
  categoryId?: string
  characteristicIds?: string[]
  status?: HtmlImportMappedStatus
  linoleum?: boolean
  comment?: string | null
  mergeChoices?: Record<string, HtmlImportFieldChoice>
}

export type HtmlImportStatusMapping = {
  sourceValue: string
  targetStatus: HtmlImportMappedStatus
}

function htmlImportsEndpoint() {
  return `${getGatewayRuntimeConfig().assetApiBaseUrl}/v1/html-imports`
}

function htmlImportEndpoint(importId: string) {
  return `${htmlImportsEndpoint()}/${encodeURIComponent(importId)}`
}

function optionalString(value: unknown) {
  return typeof value === "string" && value.trim() ? value : null
}

function optionalNonNegativeSafeInteger(value: unknown) {
  return isNonNegativeSafeInteger(value) ? value : null
}

function optionalIsoDateTime(value: unknown) {
  return isIsoDateTime(value) ? value : null
}

function recordField(record: UnknownRecord, ...keys: string[]) {
  for (const key of keys) {
    const candidate = record[key]
    if (isRecord(candidate)) return candidate
  }

  return null
}

function arrayField(record: UnknownRecord, ...keys: string[]) {
  for (const key of keys) {
    const candidate = record[key]
    if (Array.isArray(candidate)) return candidate
  }

  return []
}

function stringField(record: UnknownRecord, ...keys: string[]) {
  for (const key of keys) {
    const candidate = optionalString(record[key])
    if (candidate !== null) return candidate
  }

  return null
}

function parseHtmlImportDiagnostic(
  value: unknown,
  index: number
): HtmlImportDiagnostic {
  if (!isRecord(value)) {
    throw new Error(INVALID_HTML_IMPORT_RESPONSE_MESSAGE)
  }

  const message = stringField(value, "message", "detail", "text")
  if (message === null) {
    throw new Error(INVALID_HTML_IMPORT_RESPONSE_MESSAGE)
  }

  const rawSeverity = stringField(value, "severity", "level")?.toUpperCase()
  const severity =
    rawSeverity === "ERROR" ||
    rawSeverity === "WARNING" ||
    rawSeverity === "INFO"
      ? rawSeverity
      : "INFO"

  return {
    id: stringField(value, "id", "diagnosticId") ?? `diagnostic-${index}`,
    severity,
    message,
    code: stringField(value, "code"),
    rowId: stringField(value, "rowId", "sourceRowId"),
  }
}

function parseHtmlImportRowDiagnostic(value: unknown): HtmlImportRowDiagnostic {
  if (!isRecord(value)) {
    throw new Error(INVALID_HTML_IMPORT_RESPONSE_MESSAGE)
  }

  const code = stringField(value, "code")
  if (code === null) {
    throw new Error(INVALID_HTML_IMPORT_RESPONSE_MESSAGE)
  }

  const rawSeverity = stringField(value, "severity", "level")?.toUpperCase()
  const severity =
    rawSeverity === "ERROR" ||
    rawSeverity === "WARNING" ||
    rawSeverity === "INFO"
      ? rawSeverity
      : "INFO"

  return {
    severity,
    code,
    field: stringField(value, "field"),
  }
}

function parseHtmlImportTarget(value: unknown): HtmlImportMappingTarget {
  if (!isRecord(value)) {
    throw new Error(INVALID_HTML_IMPORT_RESPONSE_MESSAGE)
  }

  const id = stringField(
    value,
    "id",
    "targetId",
    "catalogItemId",
    "equipmentId"
  )
  const label = stringField(value, "label", "name", "title", "displayName")
  if (id === null || label === null) {
    throw new Error(INVALID_HTML_IMPORT_RESPONSE_MESSAGE)
  }

  return {
    id,
    label,
    kind: stringField(value, "kind"),
    active: typeof value.active === "boolean" ? value.active : null,
  }
}

function parseHtmlImportMappingAction(
  value: unknown
): HtmlImportMappingAction | null {
  return value === "MAP" || value === "IGNORE" || value === "CREATE"
    ? value
    : null
}

function parseHtmlImportMappedStatus(
  value: unknown
): HtmlImportMappedStatus | null {
  if (
    typeof value !== "string" ||
    !RENTAL_ITEM_STATUSES.has(value as RentalItemStatus) ||
    value === "IN_TRANSFER" ||
    value === "WRITTEN_OFF" ||
    value === "LOST"
  ) {
    return null
  }

  return value as HtmlImportMappedStatus
}

function htmlImportSourceValueKey(value: string) {
  return value.trim().toLocaleLowerCase("ru-RU")
}

function parseHtmlImportRowAction(value: unknown): HtmlImportRowAction | null {
  return value === "MERGE" ||
    value === "CREATE" ||
    value === "EXCLUDE" ||
    value === "REVIEW"
    ? value
    : null
}

function parseHtmlImportFieldChoice(
  value: unknown
): HtmlImportFieldChoice | null {
  if (value === "SOURCE" || value === "TARGET") return value
  // Older import records used EXISTING for the same durable decision. The
  // command contract now calls it TARGET, so normalise it at the boundary.
  return value === "EXISTING" ? "TARGET" : null
}

function parseHtmlImportMapping(
  value: unknown,
  group: "CATALOG" | "EQUIPMENT",
  index: number,
  availableTargets: HtmlImportMappingTarget[] = [],
  plannedValue: unknown = null
): HtmlImportMapping {
  if (!isRecord(value)) {
    throw new Error(INVALID_HTML_IMPORT_RESPONSE_MESSAGE)
  }

  const planned = isRecord(plannedValue) ? plannedValue : null
  const source = recordField(value, "source", "legacy", "from")
  const target = recordField(value, "target", "mappedTarget", "to")
  const sourceId =
    stringField(value, "sourceId", "legacyId", "key", "sourceValue") ??
    (source ? stringField(source, "id", "key", "code", "name") : null)
  const sourceLabel =
    stringField(
      value,
      "sourceLabel",
      "sourceName",
      "legacyName",
      "label",
      "sourceValue"
    ) ?? (source ? stringField(source, "label", "name", "title", "code") : null)
  const sourceValue = stringField(value, "sourceValue") ?? sourceId
  if (sourceId === null || sourceLabel === null || sourceValue === null) {
    throw new Error(INVALID_HTML_IMPORT_RESPONSE_MESSAGE)
  }

  const kind = stringField(value, "kind")
  const targets = [
    ...arrayField(value, "targets", "targetOptions", "candidates").map(
      parseHtmlImportTarget
    ),
    ...availableTargets.filter(
      (target) => group === "EQUIPMENT" || target.kind === kind
    ),
  ].filter(
    (target, targetIndex, candidates) =>
      candidates.findIndex((candidate) => candidate.id === target.id) ===
      targetIndex
  )
  const directTarget = target ? parseHtmlImportTarget(target) : null
  if (
    directTarget &&
    !targets.some((candidate) => candidate.id === directTarget.id)
  ) {
    targets.unshift(directTarget)
  }

  const plannedTargetId =
    stringField(planned ?? {}, "targetId") ??
    stringField(value, "plannedTargetId", "targetId", "mappedTargetId") ??
    directTarget?.id ??
    null
  const suggestedTargetId =
    stringField(value, "suggestedTargetId", "recommendedTargetId") ??
    plannedTargetId

  return {
    id:
      stringField(value, "id", "mappingId") ??
      `${group.toLowerCase()}-${kind ?? "value"}-${sourceValue}-${index}`,
    group,
    kind,
    sourceId,
    sourceValue,
    sourceLabel,
    suggestedValue: stringField(value, "suggestedValue"),
    occurrences: optionalNonNegativeSafeInteger(value.occurrences),
    required: value.required === true,
    targets,
    suggestedTargetId,
    plannedAction: parseHtmlImportMappingAction(
      planned?.action ?? value.plannedAction ?? value.action ?? value.decision
    ),
    plannedTargetId,
  }
}

function parseHtmlImportStatusCandidate(
  value: unknown,
  index: number,
  plannedValue: unknown = null
): HtmlImportStatusCandidate {
  if (!isRecord(value)) {
    throw new Error(INVALID_HTML_IMPORT_RESPONSE_MESSAGE)
  }

  const sourceValue = stringField(value, "sourceValue")
  if (sourceValue === null) {
    throw new Error(INVALID_HTML_IMPORT_RESPONSE_MESSAGE)
  }

  const planned = isRecord(plannedValue) ? plannedValue : null
  return {
    id:
      stringField(value, "id", "mappingId") ?? `status-${sourceValue}-${index}`,
    sourceValue,
    sourceLabel:
      stringField(value, "sourceLabel", "sourceName", "label", "sourceValue") ??
      sourceValue,
    suggestedTargetStatus: parseHtmlImportMappedStatus(value.suggestedValue),
    occurrences: optionalNonNegativeSafeInteger(value.occurrences),
    required: value.required === true,
    plannedTargetStatus: parseHtmlImportMappedStatus(
      planned?.targetStatus ?? value.targetStatus
    ),
  }
}

function parseHtmlImportFieldDecisions(value: unknown) {
  if (!isRecord(value)) return {}

  return Object.entries(value).reduce<Record<string, HtmlImportFieldChoice>>(
    (result, [key, candidate]) => {
      const choice = parseHtmlImportFieldChoice(candidate)
      if (choice !== null) result[key] = choice
      return result
    },
    {}
  )
}

const HTML_IMPORT_CABIN_EXCLUDED_FIELDS = new Set([
  "id",
  "rentalitemid",
  "assetid",
  "uuid",
  "version",
  "media",
  "mediacount",
  "mediaids",
  "images",
  "photos",
  "fields",
  "values",
  "data",
])

function readableFieldValue(value: unknown): string | null {
  if (value === null || value === undefined) return null
  if (typeof value === "string") return value.trim() || null
  if (typeof value === "number" || typeof value === "boolean")
    return String(value)
  return null
}

function parseHtmlImportCabin(value: unknown): HtmlImportCabin {
  if (!isRecord(value)) {
    throw new Error(INVALID_HTML_IMPORT_RESPONSE_MESSAGE)
  }

  const valueRecord = recordField(value, "fields", "values", "data") ?? value
  const fields = Object.entries(valueRecord).reduce<
    Record<string, string | null>
  >((result, [key, candidate]) => {
    if (HTML_IMPORT_CABIN_EXCLUDED_FIELDS.has(key.toLowerCase())) {
      return result
    }

    const parsed = fieldListValue(candidate)
    if (parsed !== null) result[key] = parsed
    return result
  }, {})
  if (fields.generalComment && !fields.comment) {
    fields.comment = fields.generalComment
    delete fields.generalComment
  }
  flattenHtmlImportPassport(fields, value.passport)
  const id = stringField(
    value,
    "id",
    "rentalItemId",
    "assetId",
    "existingRentalItemId"
  )
  const label =
    stringField(
      value,
      "number",
      "label",
      "displayName",
      "name",
      "legacyNumber"
    ) ??
    fields.number ??
    fields.legacyNumber ??
    id ??
    "Без номера"

  return {
    id,
    version: optionalNonNegativeSafeInteger(value.version),
    label,
    fields,
    mediaCount: optionalNonNegativeSafeInteger(
      value.mediaCount ?? value.photoCount ?? value.imagesCount
    ),
  }
}

function fieldListValue(value: unknown) {
  if (!Array.isArray(value)) return sourceValueFieldValue(value)
  const values = value
    .map(sourceValueFieldValue)
    .filter((candidate): candidate is string => candidate !== null)
  return values.length ? values.join(", ") : null
}

function sourceValueFieldValue(value: unknown) {
  const readable = readableFieldValue(value)
  if (readable !== null) return readable
  if (!isRecord(value)) return null
  return stringField(
    value,
    "sourceLabel",
    "label",
    "name",
    "suggestedValue",
    "sourceId",
    "id"
  )
}

function furnitureFieldValue(value: unknown) {
  if (!Array.isArray(value)) return fieldListValue(value)

  const lines = value
    .map((candidate) => {
      if (!isRecord(candidate)) return sourceValueFieldValue(candidate)
      const label = sourceValueFieldValue(candidate)
      if (label === null) return null
      const quantity = optionalNonNegativeSafeInteger(candidate.quantity)
      return quantity === null ? label : `${label} × ${quantity}`
    })
    .filter((candidate): candidate is string => candidate !== null)

  return lines.length ? lines.join(", ") : null
}

function setHtmlImportCabinField(
  fields: Record<string, string | null>,
  key: string,
  value: unknown
) {
  const parsed = fieldListValue(value)
  if (parsed !== null) fields[key] = parsed
}

function flattenHtmlImportPassport(
  fields: Record<string, string | null>,
  passport: unknown
) {
  if (!isRecord(passport)) return

  Object.entries(passport).forEach(([key, value]) => {
    setHtmlImportCabinField(fields, `passport.${key}`, value)
  })
}

function flattenSourceHtmlImportPassport(
  fields: Record<string, string | null>,
  value: UnknownRecord
) {
  flattenHtmlImportPassport(fields, value.passport)
  ;["storageState", "shipmentDate", "tenant", "price"].forEach((key) => {
    if (value[key] !== undefined) {
      setHtmlImportCabinField(fields, `passport.${key}`, value[key])
    }
  })
}

function parseFlatRowCabin(
  value: UnknownRecord,
  source: boolean
): HtmlImportCabin {
  const sourceNumber = source
    ? stringField(value, "sourceNumber", "proposedNumber", "number")
    : stringField(value, "number", "displayName", "label")
  const fields: Record<string, string | null> = {}
  ;[
    "rentalType",
    "dimension",
    "dimensions",
    "finishing",
    "finish",
    "category",
    "characteristics",
    "linoleum",
  ].forEach((key) => setHtmlImportCabinField(fields, key, value[key]))

  if (source) {
    const proposedStatus = parseHtmlImportMappedStatus(value.proposedStatus)
    const sourceStatus = fieldListValue(value.status)
    if (proposedStatus !== null) {
      fields.status = proposedStatus
    } else if (sourceStatus !== null) {
      fields.status = sourceStatus
    }
    setHtmlImportCabinField(fields, "comment", value.comment)
    const furniture = furnitureFieldValue(value.furniture)
    if (furniture !== null) fields.furniture = furniture
    flattenSourceHtmlImportPassport(fields, value)
  } else {
    setHtmlImportCabinField(fields, "status", value.status)
    setHtmlImportCabinField(
      fields,
      "comment",
      value.generalComment ?? value.comment
    )
    const furniture = furnitureFieldValue(value.furniture)
    if (furniture !== null) fields.furniture = furniture
    flattenHtmlImportPassport(fields, value.passport)
  }

  const id = source
    ? null
    : stringField(
        value,
        "id",
        "rentalItemId",
        "assetId",
        "existingRentalItemId"
      )
  return {
    id,
    version: source ? null : optionalNonNegativeSafeInteger(value.version),
    label: sourceNumber ?? id ?? "Без номера",
    fields,
    mediaCount: source
      ? value.hasPhotoLink === true
        ? 1
        : null
      : optionalNonNegativeSafeInteger(
          value.mediaCount ?? value.photoCount ?? value.imagesCount
        ),
  }
}

function parseHtmlImportRow(value: unknown, index: number): HtmlImportRow {
  if (!isRecord(value)) {
    throw new Error(INVALID_HTML_IMPORT_RESPONSE_MESSAGE)
  }

  const source = recordField(value, "source", "incoming", "legacy", "parsed")
  const existing = recordField(
    value,
    "existingRentalItem",
    "existing",
    "matched",
    "current"
  )
  const sourceCabin = source
    ? parseHtmlImportCabin(source)
    : parseFlatRowCabin(value, true)
  const existingCabin = existing
    ? recordField(existing, "fields", "values", "data")
      ? parseHtmlImportCabin(existing)
      : parseFlatRowCabin(existing, false)
    : null
  const id = stringField(value, "id", "rowId", "sourceRowId")
  const sourceRowId = stringField(value, "sourceRowId", "id", "rowId")
  if (id === null || sourceRowId === null) {
    throw new Error(INVALID_HTML_IMPORT_RESPONSE_MESSAGE)
  }
  const decision = recordField(value, "decision")

  return {
    id,
    sourceRowId,
    number:
      stringField(value, "number", "sourceNumber", "legacyNumber") ??
      sourceCabin.label ??
      `Строка ${index + 1}`,
    proposedNumber: stringField(value, "proposedNumber"),
    source: sourceCabin,
    existing: existingCabin,
    suggestedAction: parseHtmlImportRowAction(
      value.suggestedAction ?? value.recommendedAction ?? value.action
    ),
    plannedAction: parseHtmlImportRowAction(
      decision?.action ?? value.plannedAction ?? value.action
    ),
    plannedExistingRentalItemId:
      stringField(
        decision ?? {},
        "targetRentalItemId",
        "existingRentalItemId"
      ) ??
      stringField(
        value,
        "plannedExistingRentalItemId",
        "targetRentalItemId",
        "existingRentalItemId"
      ) ??
      existingCabin?.id ??
      null,
    plannedRentalTypeId:
      stringField(decision ?? {}, "rentalTypeId") ??
      stringField(value, "plannedRentalTypeId", "rentalTypeId"),
    plannedDimensionId:
      stringField(decision ?? {}, "dimensionId") ??
      stringField(value, "plannedDimensionId", "dimensionId"),
    plannedFinishingId:
      stringField(decision ?? {}, "finishingId") ??
      stringField(value, "plannedFinishingId", "finishingId"),
    fieldDecisions: parseHtmlImportFieldDecisions(
      decision?.mergeChoices ?? value.fieldDecisions ?? value.mergeDecisions
    ),
    diagnostics: arrayField(value, "diagnostics").map(
      parseHtmlImportRowDiagnostic
    ),
  }
}

function parseHtmlImportRowsPage(value: unknown): HtmlImportRowsPage {
  if (Array.isArray(value)) {
    return {
      content: value.map(parseHtmlImportRow),
      page: 0,
      size: value.length,
      totalElements: value.length,
      totalPages: value.length > 0 ? 1 : 0,
    }
  }
  if (!isRecord(value)) {
    throw new Error(INVALID_HTML_IMPORT_RESPONSE_MESSAGE)
  }

  const content = arrayField(value, "content", "items", "rows")
  if (!Object.values(value).some((candidate) => Array.isArray(candidate))) {
    throw new Error(INVALID_HTML_IMPORT_RESPONSE_MESSAGE)
  }

  const pageRecord = recordField(value, "pageable")
  const page =
    optionalNonNegativeSafeInteger(value.page) ??
    optionalNonNegativeSafeInteger(pageRecord?.pageNumber) ??
    0
  const size =
    optionalNonNegativeSafeInteger(value.size) ??
    optionalNonNegativeSafeInteger(pageRecord?.pageSize) ??
    content.length
  const totalElements =
    optionalNonNegativeSafeInteger(value.totalElements) ?? content.length
  const totalPages =
    optionalNonNegativeSafeInteger(value.totalPages) ??
    (totalElements === 0
      ? 0
      : Math.max(1, Math.ceil(totalElements / Math.max(size, 1))))

  return {
    content: content.map(parseHtmlImportRow),
    page,
    size,
    totalElements,
    totalPages,
  }
}

function summaryNumber(record: UnknownRecord, ...keys: string[]) {
  for (const key of keys) {
    const value = optionalNonNegativeSafeInteger(record[key])
    if (value !== null) return value
  }

  return null
}

function parseHtmlImportSummary(value: unknown): HtmlImportSummary {
  const record = isRecord(value) ? value : {}

  return {
    sourceRows: summaryNumber(
      record,
      "rowCount",
      "sourceRows",
      "totalRows",
      "parsedRows"
    ),
    mappedCatalogValues: summaryNumber(
      record,
      "mappedCatalogValues",
      "catalogMappings",
      "selectedCount"
    ),
    mappedEquipmentValues: summaryNumber(
      record,
      "mappedEquipmentValues",
      "equipmentMappings"
    ),
    createRows: summaryNumber(record, "createRows", "createdRows"),
    mergeRows: summaryNumber(record, "mergeRows", "mergedRows"),
    excludedRows: summaryNumber(record, "excludedRows"),
    committedRows: summaryNumber(
      record,
      "committedRows",
      "processedRows",
      "selectedCount"
    ),
    mediaPendingRows: summaryNumber(
      record,
      "mediaPendingRows",
      "pendingMediaRows",
      "mediaLinkCount"
    ),
  }
}

function parseHtmlImportState(value: unknown): HtmlImportState | null {
  switch (value) {
    case "DRAFT":
    case "REVIEW_REQUIRED":
    case "READY":
    case "COMMITTING":
    case "ASSETS_COMMITTED":
    case "MEDIA_IMPORTING":
    case "COMPLETED":
    case "COMPLETED_WITH_WARNINGS":
    case "FAILED":
      return value
    default:
      return null
  }
}

function parseHtmlImportStatusMapping(value: unknown): HtmlImportStatusMapping {
  if (!isRecord(value)) {
    throw new Error(INVALID_HTML_IMPORT_RESPONSE_MESSAGE)
  }
  const sourceValue = stringField(value, "sourceValue")
  const targetStatus = parseHtmlImportMappedStatus(value.targetStatus)
  if (sourceValue === null || targetStatus === null) {
    throw new Error(INVALID_HTML_IMPORT_RESPONSE_MESSAGE)
  }
  return { sourceValue, targetStatus }
}

function parseHtmlImport(value: unknown): HtmlImport {
  if (!isRecord(value)) {
    throw new Error(INVALID_HTML_IMPORT_RESPONSE_MESSAGE)
  }

  const id = stringField(value, "id", "importId")
  const version = value.version
  const state = parseHtmlImportState(value.state ?? value.status)
  if (id === null || !isNonNegativeSafeInteger(version) || state === null) {
    throw new Error(INVALID_HTML_IMPORT_RESPONSE_MESSAGE)
  }

  const plan = recordField(value, "plan")
  const catalogTargets = arrayField(value, "catalogTargets").map(
    parseHtmlImportTarget
  )
  const equipmentTargets = arrayField(value, "equipmentTargets").map(
    parseHtmlImportTarget
  )
  const sourceCandidates = arrayField(value, "sourceCandidates")
  const catalogPlan = arrayField(plan ?? {}, "catalogMappings")
  const equipmentPlan = arrayField(plan ?? {}, "equipmentMappings")
  const statusPlan = arrayField(plan ?? {}, "statusMappings")
  const legacyMappingsRecord = recordField(
    value,
    "mappings",
    "mappingCandidates"
  )
  const legacyCatalogValues = [
    ...arrayField(value, "catalogMappings", "catalogMappingCandidates"),
    ...arrayField(legacyMappingsRecord ?? {}, "catalog"),
  ]
  const legacyEquipmentValues = [
    ...arrayField(value, "equipmentMappings", "equipmentMappingCandidates"),
    ...arrayField(legacyMappingsRecord ?? {}, "equipment"),
  ]
  const planForCandidate = (
    candidate: unknown,
    group: "CATALOG" | "EQUIPMENT"
  ) => {
    if (!isRecord(candidate)) return null
    const sourceValue = stringField(candidate, "sourceValue")
    const kind = stringField(candidate, "kind")
    return (
      (group === "CATALOG" ? catalogPlan : equipmentPlan).find(
        (planned) =>
          isRecord(planned) &&
          stringField(planned, "sourceValue") === sourceValue &&
          (group === "EQUIPMENT" || stringField(planned, "kind") === kind)
      ) ?? null
    )
  }
  const catalogCandidates = sourceCandidates.filter((candidate) => {
    if (!isRecord(candidate)) return false
    const kind = stringField(candidate, "kind")?.toUpperCase()
    const suggestedTargetId = stringField(candidate, "suggestedTargetId")
    return (
      kind !== "EQUIPMENT" &&
      kind !== "STATUS" &&
      !equipmentTargets.some((target) => target.id === suggestedTargetId)
    )
  })
  const equipmentCandidates = sourceCandidates.filter((candidate) => {
    if (!isRecord(candidate)) return false
    const kind = stringField(candidate, "kind")?.toUpperCase()
    const suggestedTargetId = stringField(candidate, "suggestedTargetId")
    return (
      kind === "EQUIPMENT" ||
      equipmentTargets.some((target) => target.id === suggestedTargetId)
    )
  })
  const statusCandidates = sourceCandidates.filter((candidate) => {
    if (!isRecord(candidate)) return false
    return stringField(candidate, "kind")?.toUpperCase() === "STATUS"
  })
  const planForStatusCandidate = (candidate: unknown) => {
    if (!isRecord(candidate)) return null
    const sourceValue = stringField(candidate, "sourceValue")
    if (sourceValue === null) return null
    return (
      statusPlan.find((planned) => {
        if (!isRecord(planned)) return false
        const plannedSourceValue = stringField(planned, "sourceValue")
        return (
          plannedSourceValue !== null &&
          htmlImportSourceValueKey(plannedSourceValue) ===
            htmlImportSourceValueKey(sourceValue)
        )
      }) ?? null
    )
  }

  return {
    id,
    warehouseId: stringField(value, "warehouseId"),
    version,
    state,
    diagnostics: arrayField(value, "diagnostics", "issues").map(
      parseHtmlImportDiagnostic
    ),
    catalogTargets,
    catalogMappings: (catalogCandidates.length
      ? catalogCandidates
      : legacyCatalogValues
    ).map((entry, index) =>
      parseHtmlImportMapping(
        entry,
        "CATALOG",
        index,
        catalogTargets,
        planForCandidate(entry, "CATALOG")
      )
    ),
    equipmentMappings: (equipmentCandidates.length
      ? equipmentCandidates
      : legacyEquipmentValues
    ).map((entry, index) =>
      parseHtmlImportMapping(
        entry,
        "EQUIPMENT",
        index,
        equipmentTargets,
        planForCandidate(entry, "EQUIPMENT")
      )
    ),
    statusCandidates: statusCandidates.map((entry, index) =>
      parseHtmlImportStatusCandidate(
        entry,
        index,
        planForStatusCandidate(entry)
      )
    ),
    statusMappings: arrayField(plan ?? {}, "statusMappings").map(
      parseHtmlImportStatusMapping
    ),
    summary: parseHtmlImportSummary(value.summary ?? value.progress ?? value),
    mediaJobId: stringField(value, "mediaJobId"),
    failureCode: stringField(value, "failureCode"),
    createdAt: optionalIsoDateTime(value.createdAt),
    updatedAt: optionalIsoDateTime(value.updatedAt),
  }
}

async function readHtmlImportProblemDetail(response: Response) {
  const fallback = `Запрос завершился с ошибкой ${response.status}`
  const contentType = response.headers.get("content-type") ?? ""

  if (!contentType.includes("json")) {
    return { message: (await response.text()).trim() || fallback, code: null }
  }

  try {
    const body = (await response.json()) as {
      detail?: unknown
      message?: unknown
      title?: unknown
      code?: unknown
    }
    const detail = body.detail ?? body.message ?? body.title
    return {
      message: typeof detail === "string" && detail.trim() ? detail : fallback,
      code:
        typeof body.code === "string" && body.code.trim() ? body.code : null,
    }
  } catch {
    return { message: fallback, code: null }
  }
}

async function multipartHtmlImportRequest(
  accessToken: string,
  input: string,
  body: FormData,
  idempotencyKey: string
) {
  const response = await fetch(input, {
    method: "POST",
    headers: {
      Accept: "application/json",
      Authorization: `Bearer ${accessToken}`,
      "Idempotency-Key": idempotencyKey,
    },
    body,
  })

  if (!response.ok) {
    const problem = await readHtmlImportProblemDetail(response)
    throw new ApiError(problem.message, response.status, problem.code)
  }

  return (await response.json()) as unknown
}

export function htmlImportsQueryKey(warehouseId: string) {
  return ["html-imports", warehouseId] as const
}

export async function createHtmlImport(params: {
  accessToken: string | null
  warehouseId: string
  html: Blob | File
  idempotencyKey: string
}): Promise<HtmlImport> {
  const formData = new FormData()
  formData.append("warehouseId", params.warehouseId)
  formData.append(
    "html",
    params.html,
    params.html instanceof File && params.html.name.trim()
      ? params.html.name
      : "pasted.html"
  )

  return parseHtmlImport(
    await multipartHtmlImportRequest(
      requireAccessToken(params.accessToken),
      htmlImportsEndpoint(),
      formData,
      params.idempotencyKey
    )
  )
}

export async function listHtmlImports(
  accessToken: string | null,
  warehouseId: string
): Promise<HtmlImport[]> {
  const response = await bearerRequest<unknown>(
    requireAccessToken(accessToken),
    appendQuery(htmlImportsEndpoint(), { warehouseId })
  )

  if (Array.isArray(response)) return response.map(parseHtmlImport)
  if (!isRecord(response)) {
    throw new Error(INVALID_HTML_IMPORT_RESPONSE_MESSAGE)
  }

  const content = arrayField(response, "content", "items", "imports")
  if (!Object.values(response).some((candidate) => Array.isArray(candidate))) {
    throw new Error(INVALID_HTML_IMPORT_RESPONSE_MESSAGE)
  }
  return content.map(parseHtmlImport)
}

export async function getHtmlImport(
  accessToken: string | null,
  importId: string
): Promise<HtmlImport> {
  return parseHtmlImport(
    await bearerRequest<unknown>(
      requireAccessToken(accessToken),
      htmlImportEndpoint(importId)
    )
  )
}

export async function cancelHtmlImport(params: {
  accessToken: string | null
  importId: string
  expectedVersion: number
}): Promise<void> {
  await bearerRequest<void>(
    requireAccessToken(params.accessToken),
    appendQuery(htmlImportEndpoint(params.importId), {
      expectedVersion: params.expectedVersion,
    }),
    { method: "DELETE" }
  )
}

export async function listHtmlImportRows(params: {
  accessToken: string | null
  importId: string
  page?: number
  size?: number
}): Promise<HtmlImportRowsPage> {
  return parseHtmlImportRowsPage(
    await bearerRequest<unknown>(
      requireAccessToken(params.accessToken),
      appendQuery(`${htmlImportEndpoint(params.importId)}/rows`, {
        page: params.page ?? 0,
        size: params.size ?? 200,
      })
    )
  )
}

export async function saveHtmlImportPlan(params: {
  accessToken: string | null
  importId: string
  expectedVersion: number
  catalogMappings: HtmlImportMappingDecision[]
  equipmentMappings: HtmlImportMappingDecision[]
  statusMappings?: HtmlImportStatusMapping[]
  rows: HtmlImportRowDecision[]
}): Promise<HtmlImport> {
  return parseHtmlImport(
    await bearerRequest<unknown>(
      requireAccessToken(params.accessToken),
      `${htmlImportEndpoint(params.importId)}/plan`,
      {
        method: "PUT",
        body: JSON.stringify({
          expectedVersion: params.expectedVersion,
          catalogMappings: params.catalogMappings,
          equipmentMappings: params.equipmentMappings,
          statusMappings: params.statusMappings ?? [],
          rows: params.rows,
        }),
      }
    )
  )
}

export async function commitHtmlImport(params: {
  accessToken: string | null
  importId: string
  expectedVersion: number
  idempotencyKey: string
}): Promise<HtmlImport> {
  return parseHtmlImport(
    await bearerRequest<unknown>(
      requireAccessToken(params.accessToken),
      `${htmlImportEndpoint(params.importId)}/commit`,
      {
        method: "POST",
        headers: { "Idempotency-Key": params.idempotencyKey },
        body: JSON.stringify({ expectedVersion: params.expectedVersion }),
      }
    )
  )
}

export async function retryHtmlImportMedia(params: {
  accessToken: string | null
  importId: string
  expectedVersion: number
  idempotencyKey: string
}): Promise<HtmlImport> {
  return parseHtmlImport(
    await bearerRequest<unknown>(
      requireAccessToken(params.accessToken),
      `${htmlImportEndpoint(params.importId)}/retry-media`,
      {
        method: "POST",
        headers: { "Idempotency-Key": params.idempotencyKey },
        body: JSON.stringify({ expectedVersion: params.expectedVersion }),
      }
    )
  )
}

export async function replaceHtmlImportMedia(params: {
  accessToken: string | null
  importId: string
  expectedVersion: number
  idempotencyKey: string
  replacements: HtmlImportMediaReplacement[]
}): Promise<HtmlImport> {
  return parseHtmlImport(
    await bearerRequest<unknown>(
      requireAccessToken(params.accessToken),
      `${htmlImportEndpoint(params.importId)}/replace-media`,
      {
        method: "POST",
        headers: { "Idempotency-Key": params.idempotencyKey },
        body: JSON.stringify({
          expectedVersion: params.expectedVersion,
          replacements: params.replacements,
        }),
      }
    )
  )
}

export async function skipHtmlImportMedia(params: {
  accessToken: string | null
  importId: string
  expectedVersion: number
  idempotencyKey: string
}): Promise<HtmlImport> {
  return parseHtmlImport(
    await bearerRequest<unknown>(
      requireAccessToken(params.accessToken),
      `${htmlImportEndpoint(params.importId)}/skip-media`,
      {
        method: "POST",
        headers: { "Idempotency-Key": params.idempotencyKey },
        body: JSON.stringify({ expectedVersion: params.expectedVersion }),
      }
    )
  )
}
