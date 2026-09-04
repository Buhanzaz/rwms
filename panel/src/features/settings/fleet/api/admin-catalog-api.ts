import { bearerRequest, invalidApiResponseError } from "@/lib/api-client"

export type AdminCatalogKind = "vehicle" | "trailer"

export type AdminCatalogResource = {
  id: string
  version: number
  warehouseId: string
  name: string
  registrationNumber: string
  active: boolean
  notes: string
  capacity: number | null
}

export type AdminCatalogResourceInput = {
  name: string
  registrationNumber: string
  active: boolean
  notes: string
  capacity: number | null
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

  return {
    id: value.id,
    version: value.version,
    warehouseId: value.warehouse_id,
    name: value.name,
    registrationNumber: value.registration_number,
    active: value.active,
    notes: value.notes,
    capacity: kind === "vehicle" ? (capacity as number) : null,
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
}

function resourcePayload(input: AdminCatalogResourceInput) {
  return {
    name: input.name.trim(),
    registration_number: input.registrationNumber.trim(),
    active: input.active,
    notes: input.notes.trim(),
  }
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
          vehicle: { ...payload, capacity: input.capacity },
          load_profiles: [],
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
  const body = {
    expected_version: resource.version,
    ...resourcePayload(input),
    ...(kind === "vehicle" ? { capacity: input.capacity } : {}),
  }
  return parseResource(
    await bearerRequest<unknown>(
      accessToken,
      `${apiBase()}/${resourcePath(kind)}/${encodeURIComponent(resource.id)}`,
      { method: "PATCH", body: JSON.stringify(body) }
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
