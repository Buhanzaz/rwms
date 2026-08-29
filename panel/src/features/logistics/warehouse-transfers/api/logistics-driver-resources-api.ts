import { bearerRequest } from "@/lib/api-client"
import { getGatewayRuntimeConfig } from "@/lib/gateway-config"

const UUID_PATTERN =
  /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i
const DRIVER_KEYS = [
  "workerId",
  "displayName",
  "employmentType",
  "phone",
  "operationalWarehouseId",
  "availableFrom",
  "availableUntil",
  "availabilityKind",
] as const
const CONTRACTOR_KEYS = [
  "workerId",
  "version",
  "homeWarehouseId",
  "displayName",
  "phone",
  "availableFrom",
  "availableUntil",
  "comment",
  "active",
  "employmentType",
] as const

/** Unknown JSON object narrowed by the strict boundary parsers below. */
type JsonObject = Record<string, unknown>

/** Employment boundary used by the logistics resource directory. */
export type LogisticsDriverEmploymentType = "STAFF" | "CONTRACTOR"

/** Reason why a driver can be offered at the requested warehouse and instant. */
export type LogisticsDriverAvailabilityKind =
  "HOME" | "ACTIVE_ASSIGNMENT" | "INCOMING"

/** Least-privilege driver identity returned by the public task-board resource API. */
export type LogisticsDriverResource = {
  workerId: string
  displayName: string
  employmentType?: LogisticsDriverEmploymentType
  phone?: string | null
  operationalWarehouseId?: string
  availableFrom?: string | null
  availableUntil?: string | null
  availabilityKind?: LogisticsDriverAvailabilityKind
}

/** Stable contractor creation input; it never provisions login credentials. */
export type CreateContractorDriverInput = {
  contractorId: string
  displayName: string
  phone: string
  availableFrom: string
  availableUntil: string
  comment: string | null
}

/** Persisted contractor profile returned by task-board-service. */
export type ContractorDriver = {
  workerId: string
  version: number
  homeWarehouseId: string
  displayName: string
  phone: string
  availableFrom: string
  availableUntil: string
  comment: string | null
  active: boolean
  employmentType: "CONTRACTOR"
}

function invalidResponse(): never {
  throw new Error("Сервис задач вернул некорректный ресурс водителя.")
}

function object(value: unknown, allowedKeys: readonly string[]): JsonObject {
  if (!value || typeof value !== "object" || Array.isArray(value)) {
    invalidResponse()
  }
  const source = value as JsonObject
  if (Object.keys(source).some((key) => !allowedKeys.includes(key))) {
    invalidResponse()
  }
  return source
}

function text(value: unknown): string {
  if (typeof value !== "string" || !value.trim()) invalidResponse()
  return value
}

function string(value: unknown): string {
  if (typeof value !== "string") invalidResponse()
  return value
}

function uuid(value: unknown): string {
  const candidate = text(value)
  if (!UUID_PATTERN.test(candidate)) invalidResponse()
  return candidate
}

function timestamp(value: unknown): string {
  const candidate = text(value)
  if (!Number.isFinite(Date.parse(candidate))) invalidResponse()
  return candidate
}

function optionalTimestamp(value: unknown): string | null | undefined {
  if (value === undefined) return undefined
  return value === null ? null : timestamp(value)
}

function optionalText(value: unknown): string | null | undefined {
  if (value === undefined) return undefined
  return value === null ? null : text(value)
}

function enumValue<T extends string>(value: unknown, allowed: readonly T[]): T {
  const candidate = text(value)
  if (!allowed.includes(candidate as T)) invalidResponse()
  return candidate as T
}

/** Strictly parses one least-privilege public driver directory row. */
export function parseLogisticsDriverResource(
  value: unknown
): LogisticsDriverResource {
  const source = object(value, DRIVER_KEYS)
  return {
    workerId: uuid(source.workerId),
    displayName: text(source.displayName),
    ...(source.employmentType === undefined
      ? {}
      : {
          employmentType: enumValue<LogisticsDriverEmploymentType>(
            source.employmentType,
            ["STAFF", "CONTRACTOR"]
          ),
        }),
    ...(source.phone === undefined
      ? {}
      : { phone: optionalText(source.phone) }),
    ...(source.operationalWarehouseId === undefined
      ? {}
      : { operationalWarehouseId: uuid(source.operationalWarehouseId) }),
    ...(source.availableFrom === undefined
      ? {}
      : { availableFrom: optionalTimestamp(source.availableFrom) }),
    ...(source.availableUntil === undefined
      ? {}
      : { availableUntil: optionalTimestamp(source.availableUntil) }),
    ...(source.availabilityKind === undefined
      ? {}
      : {
          availabilityKind: enumValue<LogisticsDriverAvailabilityKind>(
            source.availabilityKind,
            ["HOME", "ACTIVE_ASSIGNMENT", "INCOMING"]
          ),
        }),
  }
}

function parseContractorDriver(value: unknown): ContractorDriver {
  const source = object(value, CONTRACTOR_KEYS)
  if (
    Object.keys(source).length !== CONTRACTOR_KEYS.length ||
    source.employmentType !== "CONTRACTOR" ||
    typeof source.active !== "boolean" ||
    !Number.isSafeInteger(source.version) ||
    (source.version as number) < 0
  ) {
    invalidResponse()
  }
  const availableFrom = timestamp(source.availableFrom)
  const availableUntil = timestamp(source.availableUntil)
  if (Date.parse(availableUntil) <= Date.parse(availableFrom)) invalidResponse()
  return {
    workerId: uuid(source.workerId),
    version: source.version as number,
    homeWarehouseId: uuid(source.homeWarehouseId),
    displayName: text(source.displayName),
    phone: text(source.phone),
    availableFrom,
    availableUntil,
    comment: source.comment === null ? null : string(source.comment),
    active: source.active,
    employmentType: "CONTRACTOR",
  }
}

function driverResourcesEndpoint(warehouseId: string, suffix = "") {
  return `${getGatewayRuntimeConfig().taskBoardApiBaseUrl}/warehouses/${encodeURIComponent(warehouseId)}/logistics-drivers${suffix}`
}

/** Lists drivers available at a warehouse at an explicit planning instant. */
export async function listLogisticsDriverResources(input: {
  accessToken: string
  warehouseId: string
  at: string
  includeIncoming: boolean
}): Promise<LogisticsDriverResource[]> {
  const query = new URLSearchParams({
    at: input.at,
    includeIncoming: String(input.includeIncoming),
  })
  const response = await bearerRequest<unknown>(
    input.accessToken,
    `${driverResourcesEndpoint(input.warehouseId)}?${query.toString()}`
  )
  if (!Array.isArray(response)) invalidResponse()
  return response.map(parseLogisticsDriverResource)
}

/** Creates a bounded contractor profile without creating an application account. */
export async function createContractorDriver(input: {
  accessToken: string
  warehouseId: string
  contractor: CreateContractorDriverInput
}): Promise<ContractorDriver> {
  return parseContractorDriver(
    await bearerRequest<unknown>(
      input.accessToken,
      driverResourcesEndpoint(input.warehouseId, "/contractors"),
      {
        method: "POST",
        body: JSON.stringify(input.contractor),
      }
    )
  )
}
