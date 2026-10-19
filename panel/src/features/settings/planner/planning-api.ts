import { bearerRequest, invalidApiResponseError } from "@/lib/api-client"
import { parsePolicyZoneGeometry } from "./policy-zone-geometry"
import {
  numericPlanningKeys,
  type PlannerWarehouseSettings,
  type PlanningSettings,
  type PlanningSettingsInput,
  type PolicyZoneInput,
  type WarehousePolicyZone,
} from "./planning-types"

const guid = /^[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}$/i
function invalid(): never {
  throw invalidApiResponseError(
    new Error("Некорректные настройки планировщика")
  )
}
function record(value: unknown): Record<string, unknown> {
  if (!value || typeof value !== "object" || Array.isArray(value)) invalid()
  return value as Record<string, unknown>
}
function number(value: unknown): number {
  if (typeof value !== "number" || !Number.isFinite(value)) invalid()
  return value
}
function integer(value: unknown, minimum = 1): number {
  const result = number(value)
  if (!Number.isSafeInteger(result) || result < minimum) invalid()
  return result
}
function string(value: unknown): string {
  if (typeof value !== "string" || !value.trim()) invalid()
  return value
}
function uuid(value: unknown): string {
  const result = string(value)
  if (!guid.test(result)) invalid()
  return result
}
function warehousePath(warehouseId: string) {
  return `${window.location.origin}/api/logistics-planner/v1/admin/warehouses/${encodeURIComponent(uuid(warehouseId))}`
}
function settingsResponse(
  value: unknown,
  warehouseId: string
): PlannerWarehouseSettings {
  const row = record(value)
  if (row.warehouse_id !== warehouseId) invalid()
  const settings = record(row.settings)
  for (const key of numericPlanningKeys) number(settings[key])
  if (
    settings.deliveries_before_pickups !== true ||
    typeof settings.allow_soft_overtime !== "boolean"
  )
    invalid()
  if (
    !Array.isArray(row.isochrone_tariffs) ||
    !row.isochrone_tariffs.length ||
    row.isochrone_tariffs.length > 12
  )
    invalid()
  const tariffs = row.isochrone_tariffs.map((value, index) => {
    const tariff = record(value)
    if (tariff.travel_minutes !== (index + 1) * 60) invalid()
    return {
      travel_minutes: (index + 1) * 60,
      price_rubles: integer(tariff.price_rubles, 0),
    }
  })
  const status = string(row.capacity_publish_status)
  if (!["NOT_REQUESTED", "PENDING", "PUBLISHED", "FAILED"].includes(status))
    invalid()
  return {
    warehouse_id: warehouseId,
    version: integer(row.version),
    name: string(row.name),
    timezone: string(row.timezone),
    latitude: number(row.latitude),
    longitude: number(row.longitude),
    settings: settings as unknown as PlanningSettings,
    isochrone_tariffs: tariffs,
    capacity_publish_status:
      status as PlannerWarehouseSettings["capacity_publish_status"],
  }
}
function policyResponse(
  value: unknown,
  warehouseId: string
): WarehousePolicyZone {
  const row = record(value)
  if (
    row.warehouse_id !== warehouseId ||
    !["SPECIAL_PRICE", "FORBIDDEN", "NO_TRAILER"].includes(string(row.kind))
  )
    invalid()
  const priced = row.kind === "SPECIAL_PRICE"
  if (
    !priced &&
    (row.delivery_price_rubles !== null || row.pickup_price_rubles !== null)
  )
    invalid()
  const color = string(row.color)
  if (!/^#[0-9a-f]{6}$/i.test(color)) invalid()
  const created = string(row.created_at),
    updated = string(row.updated_at)
  if (
    !Number.isFinite(Date.parse(created)) ||
    !Number.isFinite(Date.parse(updated))
  )
    invalid()
  let geometry: WarehousePolicyZone["geometry"]
  try {
    geometry = parsePolicyZoneGeometry(JSON.stringify(row.geometry))
  } catch {
    invalid()
  }
  return {
    id: uuid(row.id),
    warehouse_id: warehouseId,
    version: integer(row.version),
    name: string(row.name),
    kind: row.kind as WarehousePolicyZone["kind"],
    color,
    geometry,
    delivery_price_rubles: priced
      ? integer(row.delivery_price_rubles, 0)
      : null,
    pickup_price_rubles: priced ? integer(row.pickup_price_rubles, 0) : null,
    created_at: created,
    updated_at: updated,
  }
}

/** Administrative commands preserve canonical identity, observed versions and create receipts. */
export const planningApi = {
  async getSettings(token: string, warehouseId: string, signal?: AbortSignal) {
    return settingsResponse(
      await bearerRequest<unknown>(
        token,
        `${warehousePath(warehouseId)}/planning-settings`,
        { signal }
      ),
      warehouseId
    )
  },
  async saveSettings(
    token: string,
    warehouseId: string,
    input: PlanningSettingsInput,
    version: number
  ) {
    return settingsResponse(
      await bearerRequest<unknown>(
        token,
        `${warehousePath(warehouseId)}/planning-settings`,
        {
          method: "PUT",
          body: JSON.stringify({ ...input, expected_version: version }),
        }
      ),
      warehouseId
    )
  },
  async listPolicyZones(token: string, warehouseId: string) {
    const result = await bearerRequest<unknown>(
      token,
      `${warehousePath(warehouseId)}/policy-zones`
    )
    if (!Array.isArray(result)) invalid()
    return result.map((row) => policyResponse(row, warehouseId))
  },
  async createPolicyZone(
    token: string,
    warehouseId: string,
    input: PolicyZoneInput,
    idempotencyKey: string
  ) {
    return policyResponse(
      await bearerRequest<unknown>(
        token,
        `${warehousePath(warehouseId)}/policy-zones`,
        {
          method: "POST",
          headers: { "Idempotency-Key": idempotencyKey },
          body: JSON.stringify(input),
        }
      ),
      warehouseId
    )
  },
  async updatePolicyZone(
    token: string,
    warehouseId: string,
    zoneId: string,
    input: PolicyZoneInput,
    version: number
  ) {
    return policyResponse(
      await bearerRequest<unknown>(
        token,
        `${warehousePath(warehouseId)}/policy-zones/${encodeURIComponent(uuid(zoneId))}`,
        {
          method: "PATCH",
          body: JSON.stringify({ ...input, expected_version: version }),
        }
      ),
      warehouseId
    )
  },
  async deletePolicyZone(
    token: string,
    warehouseId: string,
    zoneId: string,
    version: number
  ) {
    await bearerRequest<void>(
      token,
      `${warehousePath(warehouseId)}/policy-zones/${encodeURIComponent(uuid(zoneId))}?expected_version=${version}`,
      { method: "DELETE" }
    )
  },
}
