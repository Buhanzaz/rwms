import {
  CUSTOMER_CABIN_PROBLEM_ACTION_KINDS,
  CUSTOMER_CABIN_PROBLEM_CATEGORIES,
  CUSTOMER_CABIN_PROBLEM_CLIENT_TYPES,
  CUSTOMER_CABIN_PROBLEM_PHASES,
  CUSTOMER_CABIN_PROBLEM_RESOLUTION_KINDS,
  CUSTOMER_CABIN_PROBLEM_STATUSES,
  type CustomerCabinProblemAction,
  type CustomerCabinProblemActionKind,
  type CustomerCabinProblemCategory,
  type CustomerCabinProblemClaim,
  type CustomerCabinProblemClientType,
  type CustomerCabinProblemPhase,
  type CustomerCabinProblemResolutionKind,
  type CustomerCabinProblemStatus,
} from "@/features/claims/model/claim"
import { bearerRequest, invalidApiResponseError } from "@/lib/api-client"
import { getGatewayRuntimeConfig } from "@/lib/gateway-config"

type JsonRecord = Record<string, unknown>

const UUID_PATTERN =
  /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i
const INVALID_RESPONSE_MESSAGE =
  "Сервис логистики вернул некорректный ответ по претензиям."

export const CUSTOMER_CABIN_PROBLEMS_QUERY_KEY = [
  "customer-cabin-problems",
] as const

function invalidResponse(): never {
  throw new Error(INVALID_RESPONSE_MESSAGE)
}

function record(value: unknown): JsonRecord {
  if (typeof value !== "object" || value === null || Array.isArray(value)) {
    invalidResponse()
  }
  return value as JsonRecord
}

function list(value: unknown): unknown[] {
  if (!Array.isArray(value)) invalidResponse()
  return value
}

function text(value: unknown): string {
  if (typeof value !== "string" || value.trim() === "") invalidResponse()
  return value
}

function nullableText(value: unknown): string | null {
  if (value === null) return null
  return text(value)
}

function uuid(value: unknown): string {
  const parsed = text(value)
  if (!UUID_PATTERN.test(parsed)) invalidResponse()
  return parsed
}

function timestamp(value: unknown): string {
  const parsed = text(value)
  if (!Number.isFinite(Date.parse(parsed))) invalidResponse()
  return parsed
}

function nonNegativeInteger(value: unknown): number {
  if (!Number.isSafeInteger(value) || (value as number) < 0) invalidResponse()
  return value as number
}

function enumValue<T extends string>(value: unknown, allowed: readonly T[]): T {
  const parsed = text(value)
  if (!allowed.includes(parsed as T)) invalidResponse()
  return parsed as T
}

function nullableEnumValue<T extends string>(
  value: unknown,
  allowed: readonly T[]
): T | null {
  return value === null ? null : enumValue(value, allowed)
}

function parseAction(value: unknown): CustomerCabinProblemAction {
  const source = record(value)
  return {
    id: uuid(source.actionId),
    actionKind: enumValue<CustomerCabinProblemActionKind>(
      source.actionKind,
      CUSTOMER_CABIN_PROBLEM_ACTION_KINDS
    ),
    previousStatus: enumValue<CustomerCabinProblemStatus>(
      source.previousStatus,
      CUSTOMER_CABIN_PROBLEM_STATUSES
    ),
    lifecycleStatus: enumValue<CustomerCabinProblemStatus>(
      source.lifecycleStatus,
      CUSTOMER_CABIN_PROBLEM_STATUSES
    ),
    resolutionKind: nullableEnumValue<CustomerCabinProblemResolutionKind>(
      source.resolutionKind,
      CUSTOMER_CABIN_PROBLEM_RESOLUTION_KINDS
    ),
    comment: nullableText(source.commentText),
    occurredAt: timestamp(source.occurredAt),
  }
}

/** Decodes only the typed manager projection and never trusts response-shaped data. */
export function parseCustomerCabinProblemClaim(
  value: unknown
): CustomerCabinProblemClaim {
  const source = record(value)
  return {
    id: uuid(source.problemId),
    orderId: uuid(source.orderId),
    warehouseId: uuid(source.warehouseId),
    bookingId: uuid(source.bookingId),
    cabinUnitId: uuid(source.cabinUnitId),
    orderNumber: text(source.orderNumber),
    category: enumValue<CustomerCabinProblemCategory>(
      source.category,
      CUSTOMER_CABIN_PROBLEM_CATEGORIES
    ),
    phase: enumValue<CustomerCabinProblemPhase>(
      source.phase,
      CUSTOMER_CABIN_PROBLEM_PHASES
    ),
    description: text(source.description),
    reportedAt: timestamp(source.reportedAt),
    clientDisplayName: text(source.clientDisplayName),
    clientType: enumValue<CustomerCabinProblemClientType>(
      source.clientType,
      CUSTOMER_CABIN_PROBLEM_CLIENT_TYPES
    ),
    clientPhone: nullableText(source.clientPhone),
    orderContactPhone: nullableText(source.orderContactPhone),
    deliveryAddress: nullableText(source.deliveryAddress),
    status: enumValue<CustomerCabinProblemStatus>(
      source.status,
      CUSTOMER_CABIN_PROBLEM_STATUSES
    ),
    resolutionDeadline: timestamp(source.resolutionDeadline),
    version: nonNegativeInteger(source.version),
    resolutionKind: nullableEnumValue<CustomerCabinProblemResolutionKind>(
      source.resolutionKind,
      CUSTOMER_CABIN_PROBLEM_RESOLUTION_KINDS
    ),
    resolutionComment: nullableText(source.resolutionComment),
    resolvedAt:
      source.resolvedAt === null ? null : timestamp(source.resolvedAt),
    actions: list(source.actions).map(parseAction),
  }
}

function claimsUrl(status?: CustomerCabinProblemStatus) {
  const base = `${getGatewayRuntimeConfig().logisticsApiBaseUrl}/v1/customer-cabin-problems`
  if (!status) return base
  return `${base}?${new URLSearchParams({ status }).toString()}`
}

export async function listCustomerCabinProblems(
  accessToken: string,
  status?: CustomerCabinProblemStatus
) {
  try {
    const response = await bearerRequest<unknown[]>(
      accessToken,
      claimsUrl(status)
    )
    return list(response).map(parseCustomerCabinProblemClaim)
  } catch (error) {
    throw invalidApiResponseError(error)
  }
}

export async function startCustomerCabinProblem(
  accessToken: string,
  problemId: string,
  expectedVersion: number
) {
  const response = await bearerRequest<unknown>(
    accessToken,
    `${claimsUrl()}/${problemId}/start-progress`,
    {
      method: "POST",
      body: JSON.stringify({ expectedVersion }),
    }
  )
  return parseCustomerCabinProblemClaim(response)
}

export async function resolveCustomerCabinProblem(
  accessToken: string,
  problemId: string,
  input: {
    expectedVersion: number
    resolutionKind: CustomerCabinProblemResolutionKind
    resolutionComment: string
  }
) {
  const response = await bearerRequest<unknown>(
    accessToken,
    `${claimsUrl()}/${problemId}/resolve`,
    {
      method: "POST",
      body: JSON.stringify(input),
    }
  )
  return parseCustomerCabinProblemClaim(response)
}
