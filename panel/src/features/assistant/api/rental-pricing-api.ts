import { bearerRequest, invalidApiResponseError } from "@/lib/api-client"
import { getGatewayRuntimeConfig } from "@/lib/gateway-config"

export const rentalPricingSettingsKey = ["rental-pricing-settings"] as const

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
