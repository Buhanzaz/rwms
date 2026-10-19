import { bearerRequest, invalidApiResponseError } from "@/lib/api-client"
import { getGatewayRuntimeConfig } from "@/lib/gateway-config"

export const rentalPricingSettingsKey = ["rental-pricing-settings"] as const
export const cabinRentalPricesKey = ["cabin-rental-prices"] as const
export const equipmentRentalPricingKey = ["equipment-rental-pricing"] as const

export type EquipmentRentalPrice = {
  equipmentId: string
  name: string
  active: boolean
  monthlyPriceRubles: string
}
export type EquipmentRentalPricing = {
  version: number
  items: EquipmentRentalPrice[]
  updatedAt: string
}
export type UpdateEquipmentRentalPrice = {
  accessToken: string
  equipmentId: string
  expectedVersion: number
  monthlyPriceRubles: string
}

export type CabinRentalPrice = {
  rentalItemId: string
  rentalItemVersion: number
  rentalTypeId: string
  categoryId: string
  monthlyPriceRubles: string
}
export type CabinRentalPrices = {
  warehouseId: string
  pricingVersion: number
  cabins: CabinRentalPrice[]
}

export type RentalPricingCategory = {
  categoryId: string
  name: string
  active: boolean
  monthlyPriceRubles: string
}
export type RentalPricingType = {
  rentalTypeId: string
  name: string
  active: boolean
  categories: RentalPricingCategory[]
}
export type RentalPricingSettings = {
  version: number
  types: RentalPricingType[]
  updatedAt: string
}
export type UpdateRentalPrice = {
  accessToken: string
  rentalTypeId: string
  categoryId: string
  expectedVersion: number
  monthlyPriceRubles: string
}

/** Money stays a decimal string throughout transport and editing, including above 2^53. */
export function validMonthlyRentalPrice(value: string): boolean {
  return (
    /^(0|[1-9][0-9]{0,18})$/.test(value) &&
    BigInt(value) <= 9223372036854775807n
  )
}

export function formatMonthlyRentalPrice(value: string): string {
  if (!validMonthlyRentalPrice(value)) invalid()
  return `${new Intl.NumberFormat("ru-RU").format(BigInt(value))} ₽/мес.`
}

/** Null pairs identify old sent links, not a zero tariff or a missing service response. */
export function assertPresentationRentalPrice(value: unknown): void {
  const row = record(value)
  if (row.pricingVersion === null && row.monthlyPriceRubles === null) return
  if (
    typeof row.pricingVersion !== "number" ||
    !Number.isSafeInteger(row.pricingVersion) ||
    row.pricingVersion < 0 ||
    typeof row.monthlyPriceRubles !== "string" ||
    !validMonthlyRentalPrice(row.monthlyPriceRubles)
  )
    invalid()
}

function invalid(): never {
  throw invalidApiResponseError("Сервис вернул некорректные цены аренды.")
}
function record(value: unknown): Record<string, unknown> {
  if (!value || typeof value !== "object" || Array.isArray(value)) invalid()
  return value as Record<string, unknown>
}
function list(value: unknown): unknown[] {
  if (!Array.isArray(value)) invalid()
  return value
}
function classification(value: unknown, idField: string) {
  const row = record(value)
  const id = row[idField]
  if (
    typeof id !== "string" ||
    !/^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i.test(
      id
    ) ||
    typeof row.name !== "string" ||
    !row.name.trim() ||
    typeof row.active !== "boolean"
  )
    invalid()
  return { id, name: row.name, active: row.active }
}

function settings(value: unknown): RentalPricingSettings {
  const body = record(value)
  if (
    typeof body.version !== "number" ||
    !Number.isSafeInteger(body.version) ||
    body.version < 0 ||
    typeof body.updatedAt !== "string" ||
    !Number.isFinite(Date.parse(body.updatedAt))
  )
    invalid()
  const typeIds = new Set<string>()
  let categoryFacts: Map<string, { name: string; active: boolean }> | undefined
  const types = list(body.types).map((value): RentalPricingType => {
    const type = classification(value, "rentalTypeId")
    if (typeIds.has(type.id)) invalid()
    typeIds.add(type.id)
    const currentCategories = new Map<
      string,
      { name: string; active: boolean }
    >()
    const categories = list(record(value).categories).map(
      (value): RentalPricingCategory => {
        const category = classification(value, "categoryId")
        const price = record(value).monthlyPriceRubles
        if (
          currentCategories.has(category.id) ||
          typeof price !== "string" ||
          !validMonthlyRentalPrice(price)
        )
          invalid()
        currentCategories.set(category.id, category)
        return {
          categoryId: category.id,
          name: category.name,
          active: category.active,
          monthlyPriceRubles: price,
        }
      }
    )
    if (
      categoryFacts &&
      (categoryFacts.size !== currentCategories.size ||
        [...categoryFacts].some(([id, fact]) => {
          const current = currentCategories.get(id)
          return current?.name !== fact.name || current.active !== fact.active
        }))
    )
      invalid()
    categoryFacts = currentCategories
    return {
      rentalTypeId: type.id,
      name: type.name,
      active: type.active,
      categories,
    }
  })
  if (
    types.some((type) =>
      type.categories.some((category) => typeIds.has(category.categoryId))
    )
  )
    invalid()
  return { version: body.version, types, updatedAt: body.updatedAt }
}

function equipmentPricing(value: unknown): EquipmentRentalPricing {
  const body = record(value)
  if (
    typeof body.version !== "number" ||
    !Number.isSafeInteger(body.version) ||
    body.version < 0 ||
    typeof body.updatedAt !== "string" ||
    !Number.isFinite(Date.parse(body.updatedAt))
  )
    invalid()
  const ids = new Set<string>()
  const items = list(body.items).map((value): EquipmentRentalPrice => {
    const item = classification(value, "equipmentId")
    const amount = record(value).monthlyPriceRubles
    if (
      ids.has(item.id) ||
      item.name.length > 255 ||
      typeof amount !== "string" ||
      !validMonthlyRentalPrice(amount)
    )
      invalid()
    ids.add(item.id)
    return {
      equipmentId: item.id,
      name: item.name,
      active: item.active,
      monthlyPriceRubles: amount,
    }
  })
  return { version: body.version, items, updatedAt: body.updatedAt }
}

function equipmentPricingUrl() {
  return `${getGatewayRuntimeConfig().logisticsApiBaseUrl}/v1/settings/equipment-rental-prices`
}

export async function getEquipmentRentalPricing(
  accessToken: string,
  signal?: AbortSignal
): Promise<EquipmentRentalPricing> {
  return equipmentPricing(
    await bearerRequest<unknown>(accessToken, equipmentPricingUrl(), { signal })
  )
}

export async function updateEquipmentRentalPrice({
  accessToken,
  equipmentId,
  expectedVersion,
  monthlyPriceRubles,
}: UpdateEquipmentRentalPrice): Promise<EquipmentRentalPricing> {
  if (!validMonthlyRentalPrice(monthlyPriceRubles))
    throw new Error("Укажите целую сумму от 0 до 9223372036854775807 ₽.")
  return equipmentPricing(
    await bearerRequest<unknown>(
      accessToken,
      `${equipmentPricingUrl()}/${encodeURIComponent(equipmentId)}`,
      {
        method: "PUT",
        body: JSON.stringify({ expectedVersion, monthlyPriceRubles }),
      }
    )
  )
}

function settingsUrl() {
  return `${getGatewayRuntimeConfig().logisticsApiBaseUrl}/v1/settings/rental-prices`
}

export async function getRentalPricingSettings(
  accessToken: string,
  signal?: AbortSignal
): Promise<RentalPricingSettings> {
  return settings(
    await bearerRequest<unknown>(accessToken, settingsUrl(), { signal })
  )
}

export async function updateRentalPrice({
  accessToken,
  rentalTypeId,
  categoryId,
  expectedVersion,
  monthlyPriceRubles,
}: UpdateRentalPrice): Promise<RentalPricingSettings> {
  if (!validMonthlyRentalPrice(monthlyPriceRubles))
    throw new Error("Укажите целую сумму от 0 до 9223372036854775807 ₽.")
  return settings(
    await bearerRequest<unknown>(
      accessToken,
      `${settingsUrl()}/${encodeURIComponent(rentalTypeId)}/${encodeURIComponent(categoryId)}`,
      {
        method: "PUT",
        body: JSON.stringify({ expectedVersion, monthlyPriceRubles }),
      }
    )
  )
}

/** Informational current prices, not a booking, payment or frozen commercial offer. */
export async function getCabinRentalPrices(
  accessToken: string,
  warehouseId: string,
  rentalItemIds: readonly string[],
  signal?: AbortSignal
): Promise<CabinRentalPrices> {
  if (
    rentalItemIds.length < 1 ||
    rentalItemIds.length > 100 ||
    new Set(rentalItemIds).size !== rentalItemIds.length
  ) {
    throw new Error("Запрос цен должен содержать от 1 до 100 разных бытовок.")
  }
  const body = record(
    await bearerRequest<unknown>(
      accessToken,
      `${getGatewayRuntimeConfig().logisticsApiBaseUrl}/v1/cabins/rental-prices`,
      {
        method: "POST",
        body: JSON.stringify({ warehouseId, rentalItemIds }),
        signal,
      }
    )
  )
  if (
    body.warehouseId !== warehouseId ||
    typeof body.pricingVersion !== "number" ||
    !Number.isSafeInteger(body.pricingVersion) ||
    body.pricingVersion < 0
  )
    invalid()
  const requested = new Set(rentalItemIds)
  const seen = new Set<string>()
  const cabins = list(body.cabins).map((value): CabinRentalPrice => {
    const row = record(value)
    for (const field of [
      "rentalItemId",
      "rentalTypeId",
      "categoryId",
    ] as const) {
      if (
        typeof row[field] !== "string" ||
        !/^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i.test(
          row[field]
        )
      )
        invalid()
    }
    if (
      typeof row.rentalItemVersion !== "number" ||
      !Number.isSafeInteger(row.rentalItemVersion) ||
      row.rentalItemVersion < 0 ||
      typeof row.monthlyPriceRubles !== "string" ||
      !validMonthlyRentalPrice(row.monthlyPriceRubles)
    )
      invalid()
    const rentalItemId = row.rentalItemId as string
    if (!requested.has(rentalItemId) || seen.has(rentalItemId)) invalid()
    seen.add(rentalItemId)
    return {
      rentalItemId,
      rentalItemVersion: row.rentalItemVersion,
      rentalTypeId: row.rentalTypeId as string,
      categoryId: row.categoryId as string,
      monthlyPriceRubles: row.monthlyPriceRubles,
    }
  })
  if (seen.size !== requested.size) invalid()
  return { warehouseId, pricingVersion: body.pricingVersion, cabins }
}
