import {
  CLIENT_TYPES,
  type ClientPage,
  type ClientType,
  type CreateClientInput,
  type RentalClient,
} from "@/features/clients/domain/clients"
import { bearerRequest } from "@/lib/api-client"
import { getGatewayRuntimeConfig } from "@/lib/gateway-config"

type JsonRecord = Record<string, unknown>

const UUID_PATTERN =
  /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i
const INVALID_RESPONSE_MESSAGE =
  "Сервис логистики вернул некорректный ответ о клиентах."

export const CLIENTS_QUERY_KEY = ["rental-clients"] as const

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

function nonNegativeInteger(value: unknown): number {
  if (typeof value !== "number" || !Number.isSafeInteger(value) || value < 0) {
    invalidResponse()
  }
  return value
}

function timestamp(value: unknown): string {
  const parsed = text(value)
  if (!Number.isFinite(Date.parse(parsed))) invalidResponse()
  return parsed
}

function clientType(value: unknown): ClientType {
  const parsed = text(value)
  if (!CLIENT_TYPES.includes(parsed as ClientType)) invalidResponse()
  return parsed as ClientType
}

export function parseRentalClient(value: unknown): RentalClient {
  const source = record(value)
  return {
    id: uuid(source.id),
    version: nonNegativeInteger(source.version),
    type: clientType(source.type),
    displayName: text(source.displayName),
    phone: nullableText(source.phone),
    contactPerson: nullableText(source.contactPerson),
    email: nullableText(source.email),
    responsibleManagerId: uuid(source.responsibleManagerId),
    responsibleManagerDisplayName:
      source.responsibleManagerDisplayName === undefined
        ? null
        : nullableText(source.responsibleManagerDisplayName),
    comment: nullableText(source.comment),
    source: nullableText(source.source),
    createdAt: timestamp(source.createdAt),
    updatedAt: timestamp(source.updatedAt),
  }
}

function parseClientPage(value: unknown): ClientPage {
  const source = record(value)
  return {
    content: list(source.content).map(parseRentalClient),
    page: nonNegativeInteger(source.page),
    size: nonNegativeInteger(source.size),
    totalElements: nonNegativeInteger(source.totalElements),
    totalPages: nonNegativeInteger(source.totalPages),
  }
}

function clientsEndpoint(path = "") {
  return `${getGatewayRuntimeConfig().logisticsApiBaseUrl}/v1/clients${path}`
}

function idempotencyHeaders(idempotencyKey: string) {
  return { "Idempotency-Key": uuid(idempotencyKey) }
}

export async function listClients(params: {
  accessToken: string
  type?: ClientType
  search?: string
  page?: number
  size?: number
}): Promise<ClientPage> {
  const endpoint = new URL(clientsEndpoint())
  if (params.type) endpoint.searchParams.set("type", params.type)
  if (params.search?.trim()) {
    endpoint.searchParams.set("search", params.search.trim())
  }
  endpoint.searchParams.set("page", String(params.page ?? 0))
  endpoint.searchParams.set("size", String(params.size ?? 50))
  return parseClientPage(
    await bearerRequest<unknown>(params.accessToken, endpoint)
  )
}

export async function getClient(accessToken: string, clientId: string) {
  return parseRentalClient(
    await bearerRequest<unknown>(
      accessToken,
      clientsEndpoint(`/${encodeURIComponent(uuid(clientId))}`)
    )
  )
}

export async function createClient(params: {
  accessToken: string
  idempotencyKey: string
  input: CreateClientInput
}) {
  return parseRentalClient(
    await bearerRequest<unknown>(params.accessToken, clientsEndpoint(), {
      method: "POST",
      headers: idempotencyHeaders(params.idempotencyKey),
      body: JSON.stringify(params.input),
    })
  )
}
