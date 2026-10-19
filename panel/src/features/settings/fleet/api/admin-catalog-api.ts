import { bearerRequest, invalidApiResponseError } from "@/lib/api-client"

export type AdminCatalogKind = "vehicle" | "trailer"

export const physicalFieldNames = {
  tareWeightKg: "tare_weight_kg",
  maxGrossWeightKg: "max_gross_weight_kg",
  lengthMm: "length_mm",
  widthMm: "width_mm",
  heightMm: "height_mm",
  axleCount: "axle_count",
  maxAxleLoadKg: "max_axle_load_kg",
  payloadCapacityKg: "payload_capacity_kg",
  platformLengthMm: "platform_length_mm",
  platformWidthMm: "platform_width_mm",
  platformHeightFromGroundMm: "platform_height_from_ground_mm",
  maxPlatformPayloadKg: "max_platform_payload_kg",
  maxCargoLengthMm: "max_cargo_length_mm",
  maxCargoWidthMm: "max_cargo_width_mm",
  maxCargoHeightMm: "max_cargo_height_mm",
  maxCargoWeightKg: "max_cargo_weight_kg",
} as const

export type AdminPhysicalSpecification = Record<
  keyof typeof physicalFieldNames,
  number | null
>

export const vehicleLoadProfileTypes = [
  "EMPTY_TRUCK",
  "CARGO_ON_TRUCK",
  "EMPTY_COMBINATION",
  "CARGO_ON_TRUCK_WITH_TRAILER",
  "CARGO_ON_TRAILER_WITH_TRAILER",
  "TWO_CARGO_SPLIT",
] as const

export type VehicleLoadProfileType = (typeof vehicleLoadProfileTypes)[number]
export type AdminVehicleLoadProfile = {
  configurationType: VehicleLoadProfileType
  maxActualAxleLoadKg: number
}

export type AdminVehicleSpecification = {
  vehicleType: string | null
  manufacturer: string | null
  model: string | null
  isHgv: boolean | null
  canUseTrailer: boolean | null
  defaultTrailerId: string | null
  combinedLengthWithTrailerMm: number | null
  couplingLengthMm: number | null
  heightSafetyMarginMm: number
  widthSafetyMarginMm: number
  weightSafetyMarginKg: number
  averageSpeedCity: number
  averageSpeedRegion: number
  loadProfiles: AdminVehicleLoadProfile[]
}

export function emptyAdminPhysicalSpecification(): AdminPhysicalSpecification {
  return Object.fromEntries(
    Object.keys(physicalFieldNames).map((key) => [key, null])
  ) as AdminPhysicalSpecification
}

export function defaultAdminVehicleSpecification(): AdminVehicleSpecification {
  return {
    vehicleType: null,
    manufacturer: null,
    model: null,
    isHgv: null,
    canUseTrailer: null,
    defaultTrailerId: null,
    combinedLengthWithTrailerMm: null,
    couplingLengthMm: null,
    heightSafetyMarginMm: 0,
    widthSafetyMarginMm: 0,
    weightSafetyMarginKg: 0,
    averageSpeedCity: 35,
    averageSpeedRegion: 65,
    loadProfiles: [],
  }
}

export type AdminCatalogResourceInput = {
  name: string
  registrationNumber: string
  active: boolean
  notes: string
  capacity: number | null
  physical: AdminPhysicalSpecification
  vehicle: AdminVehicleSpecification | null
}

export type AdminCatalogResource = AdminCatalogResourceInput & {
  id: string
  version: number
  warehouseId: string
}

const UUID_PATTERN =
  /^[0-9a-f]{8}-[0-9a-f]{4}-[1-5][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/i

function apiBase() {
  return `${window.location.origin}/api/logistics-planner/v1/admin`
}

function resourcePath(kind: AdminCatalogKind) {
  return kind === "vehicle" ? "vehicles" : "trailers"
}

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === "object" && value !== null && !Array.isArray(value)
}

function positiveInteger(value: unknown): number {
  if (typeof value !== "number" || !Number.isSafeInteger(value) || value <= 0) {
    throw new Error("Expected a positive integer")
  }
  return value
}

function nullablePositiveInteger(value: unknown): number | null {
  return value === null ? null : positiveInteger(value)
}

function nonNegativeInteger(value: unknown): number {
  if (value === 0) return 0
  return positiveInteger(value)
}

function positiveNumber(value: unknown): number {
  if (typeof value !== "number" || !Number.isFinite(value) || value <= 0) {
    throw new Error("Expected a positive number")
  }
  return value
}

function nullableString(value: unknown): string | null {
  if (value !== null && typeof value !== "string")
    throw new Error("Expected a nullable string")
  return value
}

function nullableBoolean(value: unknown): boolean | null {
  if (value !== null && typeof value !== "boolean")
    throw new Error("Expected a nullable boolean")
  return value
}

function parsePhysical(
  value: Record<string, unknown>
): AdminPhysicalSpecification {
  return Object.fromEntries(
    Object.entries(physicalFieldNames).map(([key, wireName]) => [
      key,
      nullablePositiveInteger(value[wireName]),
    ])
  ) as AdminPhysicalSpecification
}

function parseVehicle(
  value: Record<string, unknown>
): AdminVehicleSpecification {
  const trailerId = nullableString(value.default_trailer_id)
  if (trailerId !== null && !UUID_PATTERN.test(trailerId))
    throw new Error("Invalid trailer UUID")
  if (trailerId !== null && value.can_use_trailer !== true)
    throw new Error("A default trailer requires confirmed trailer capability")
  if (!Array.isArray(value.load_profiles))
    throw new Error("Missing vehicle axle-load profiles")
  const seen = new Set<string>()
  const loadProfiles = value.load_profiles.map(
    (profile): AdminVehicleLoadProfile => {
      if (
        !isRecord(profile) ||
        !vehicleLoadProfileTypes.includes(
          profile.configuration_type as VehicleLoadProfileType
        ) ||
        seen.has(profile.configuration_type as string)
      )
        throw new Error("Invalid vehicle axle-load profile")
      seen.add(profile.configuration_type as string)
      return {
        configurationType: profile.configuration_type as VehicleLoadProfileType,
        maxActualAxleLoadKg: positiveInteger(profile.max_actual_axle_load_kg),
      }
    }
  )
  return {
    vehicleType: nullableString(value.vehicle_type),
    manufacturer: nullableString(value.manufacturer),
    model: nullableString(value.model),
    isHgv: nullableBoolean(value.is_hgv),
    canUseTrailer: nullableBoolean(value.can_use_trailer),
    defaultTrailerId: trailerId,
    combinedLengthWithTrailerMm: nullablePositiveInteger(
      value.combined_length_with_trailer_mm
    ),
    couplingLengthMm: nullablePositiveInteger(value.coupling_length_mm),
    heightSafetyMarginMm: nonNegativeInteger(value.height_safety_margin_mm),
    widthSafetyMarginMm: nonNegativeInteger(value.width_safety_margin_mm),
    weightSafetyMarginKg: nonNegativeInteger(value.weight_safety_margin_kg),
    averageSpeedCity: positiveNumber(value.average_speed_city),
    averageSpeedRegion: positiveNumber(value.average_speed_region),
    loadProfiles,
  }
}

function parseResource(
  value: unknown,
  kind: AdminCatalogKind
): AdminCatalogResource {
  if (!isRecord(value)) {
    throw invalidApiResponseError(
      new Error("Planner returned a non-object catalog resource")
    )
  }

  const capacity = kind === "vehicle" ? value.capacity : null
  if (
    typeof value.id !== "string" ||
    !UUID_PATTERN.test(value.id) ||
    typeof value.version !== "number" ||
    !Number.isInteger(value.version) ||
    value.version < 1 ||
    typeof value.warehouse_id !== "string" ||
    !UUID_PATTERN.test(value.warehouse_id) ||
    typeof value.name !== "string" ||
    !value.name.trim() ||
    typeof value.registration_number !== "string" ||
    !value.registration_number.trim() ||
    typeof value.active !== "boolean" ||
    typeof value.notes !== "string" ||
    (kind === "vehicle" &&
      (typeof capacity !== "number" ||
        !Number.isInteger(capacity) ||
        capacity < 1 ||
        capacity > 2))
  ) {
    throw invalidApiResponseError(
      new Error("Planner returned an invalid admin catalog resource")
    )
  }

  try {
    return {
      id: value.id,
      version: value.version,
      warehouseId: value.warehouse_id,
      name: value.name,
      registrationNumber: value.registration_number,
      active: value.active,
      notes: value.notes,
      capacity: kind === "vehicle" ? (capacity as number) : null,
      physical: parsePhysical(value),
      vehicle: kind === "vehicle" ? parseVehicle(value) : null,
    }
  } catch (error) {
    throw invalidApiResponseError(error)
  }
}

function validateWarehouseId(warehouseId: string) {
  if (!UUID_PATTERN.test(warehouseId)) {
    throw new Error("Не выбран корректный объект каталога.")
  }
}

function validateResourceInput(
  kind: AdminCatalogKind,
  input: AdminCatalogResourceInput
) {
  if (!input.name.trim() || !input.registrationNumber.trim()) {
    throw new Error("Укажите название и регистрационный номер.")
  }
  if (
    kind === "vehicle" &&
    (input.capacity === null ||
      !Number.isInteger(input.capacity) ||
      input.capacity < 1 ||
      input.capacity > 2)
  ) {
    throw new Error("Вместимость транспорта должна быть 1 или 2 бытовки.")
  }
  try {
    const payload = resourcePayload(input)
    parsePhysical(payload)
    if (kind === "vehicle") {
      if (!input.vehicle) throw new Error("Missing vehicle specification")
      const vehicle = parseVehicle({
        ...payload,
        ...vehiclePayload(input.vehicle),
        load_profiles: profilePayload(input.vehicle),
      })
      if (vehicle.defaultTrailerId !== null && vehicle.canUseTrailer !== true) {
        throw new Error("Trailer capability is not confirmed")
      }
    } else if (input.vehicle !== null || input.capacity !== null) {
      throw new Error("A trailer cannot own vehicle fields")
    }
  } catch {
    throw new Error(
      "Проверьте характеристики: размеры и массы — положительные целые числа, запасы — неотрицательные. Для прицепа подтвердите возможность буксировки."
    )
  }
}

function resourcePayload(input: AdminCatalogResourceInput) {
  return {
    name: input.name.trim(),
    registration_number: input.registrationNumber.trim(),
    active: input.active,
    notes: input.notes.trim(),
    ...Object.fromEntries(
      Object.entries(physicalFieldNames).map(([key, wireName]) => [
        wireName,
        input.physical[key as keyof AdminPhysicalSpecification],
      ])
    ),
  }
}

function vehiclePayload(vehicle: AdminVehicleSpecification) {
  return {
    vehicle_type: vehicle.vehicleType?.trim() || null,
    manufacturer: vehicle.manufacturer?.trim() || null,
    model: vehicle.model?.trim() || null,
    is_hgv: vehicle.isHgv,
    can_use_trailer: vehicle.canUseTrailer,
    default_trailer_id: vehicle.defaultTrailerId,
    combined_length_with_trailer_mm: vehicle.combinedLengthWithTrailerMm,
    coupling_length_mm: vehicle.couplingLengthMm,
    height_safety_margin_mm: vehicle.heightSafetyMarginMm,
    width_safety_margin_mm: vehicle.widthSafetyMarginMm,
    weight_safety_margin_kg: vehicle.weightSafetyMarginKg,
    average_speed_city: vehicle.averageSpeedCity,
    average_speed_region: vehicle.averageSpeedRegion,
  }
}

function profilePayload(vehicle: AdminVehicleSpecification) {
  return vehicle.loadProfiles.map((profile) => ({
    configuration_type: profile.configurationType,
    max_actual_axle_load_kg: profile.maxActualAxleLoadKg,
  }))
}

export const adminCatalogKeys = {
  all: ["planner-admin-catalog"] as const,
  warehouse: (kind: AdminCatalogKind, warehouseId: string) =>
    [...adminCatalogKeys.all, kind, warehouseId] as const,
}

export async function listAdminCatalogResources(
  accessToken: string,
  warehouseId: string,
  kind: AdminCatalogKind
) {
  validateWarehouseId(warehouseId)
  const values = await bearerRequest<unknown>(
    accessToken,
    `${apiBase()}/warehouses/${encodeURIComponent(warehouseId)}/${resourcePath(kind)}`
  )
  if (!Array.isArray(values)) {
    throw invalidApiResponseError(
      new Error("Planner returned a non-array admin catalog")
    )
  }
  return values.map((value) => parseResource(value, kind))
}

export async function createAdminCatalogResource(
  accessToken: string,
  warehouseId: string,
  kind: AdminCatalogKind,
  input: AdminCatalogResourceInput,
  idempotencyKey: string
) {
  validateWarehouseId(warehouseId)
  validateResourceInput(kind, input)
  if (!idempotencyKey.trim())
    throw new Error("Не получен ключ идемпотентности.")

  const payload = resourcePayload(input)
  const path =
    kind === "vehicle"
      ? `warehouses/${encodeURIComponent(warehouseId)}/vehicle-configurations`
      : `warehouses/${encodeURIComponent(warehouseId)}/trailers`
  const body =
    kind === "vehicle"
      ? {
          vehicle: {
            ...payload,
            capacity: input.capacity,
            ...vehiclePayload(input.vehicle!),
          },
          load_profiles: profilePayload(input.vehicle!),
        }
      : payload
  return parseResource(
    await bearerRequest<unknown>(accessToken, `${apiBase()}/${path}`, {
      method: "POST",
      headers: { "Idempotency-Key": idempotencyKey },
      body: JSON.stringify(body),
    }),
    kind
  )
}

export async function updateAdminCatalogResource(
  accessToken: string,
  kind: AdminCatalogKind,
  resource: AdminCatalogResource,
  input: AdminCatalogResourceInput
) {
  validateResourceInput(kind, input)
  const fields = {
    expected_version: resource.version,
    ...resourcePayload(input),
    ...(kind === "vehicle"
      ? { capacity: input.capacity, ...vehiclePayload(input.vehicle!) }
      : {}),
  }
  const body =
    kind === "vehicle"
      ? { vehicle: fields, load_profiles: profilePayload(input.vehicle!) }
      : fields
  const suffix = kind === "vehicle" ? "/configuration" : ""
  return parseResource(
    await bearerRequest<unknown>(
      accessToken,
      `${apiBase()}/${resourcePath(kind)}/${encodeURIComponent(resource.id)}${suffix}`,
      {
        method: kind === "vehicle" ? "PUT" : "PATCH",
        body: JSON.stringify(body),
      }
    ),
    kind
  )
}

export async function deleteAdminCatalogResource(
  accessToken: string,
  kind: AdminCatalogKind,
  resource: AdminCatalogResource
) {
  const params = new URLSearchParams({
    expected_version: String(resource.version),
  })
  await bearerRequest<void>(
    accessToken,
    `${apiBase()}/${resourcePath(kind)}/${encodeURIComponent(resource.id)}?${params}`,
    { method: "DELETE" }
  )
}

export async function relocateAdminCatalogResource(
  accessToken: string,
  kind: AdminCatalogKind,
  resource: AdminCatalogResource,
  targetWarehouseId: string
) {
  validateWarehouseId(targetWarehouseId)
  return parseResource(
    await bearerRequest<unknown>(
      accessToken,
      `${apiBase()}/${resourcePath(kind)}/${encodeURIComponent(resource.id)}/relocate`,
      {
        method: "POST",
        body: JSON.stringify({
          expected_version: resource.version,
          target_warehouse_id: targetWarehouseId,
        }),
      }
    ),
    kind
  )
}
