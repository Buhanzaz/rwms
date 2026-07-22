import {
  ORDER_AUDIT_EVENT_TYPES,
  ORDER_CLIENT_TYPES,
  ORDER_STATUSES,
  type OrderAuditEvent,
  type OrderAuditEventType,
  type OrderClient,
  type OrderClientSearchItem,
  type OrderClientType,
  type OrderDetail,
  type OrderEquipmentContent,
  type OrderPage,
  type OrderRentalUnit,
  type OrderStatus,
  type OrderSummary,
  type OrderUnitCandidate,
} from "@/features/orders/domain/orders"
import {
  RENTAL_ITEM_STATUS_LABEL,
  type RentalItemStatus,
} from "@/features/rental-items/model/rental-item"
import { bearerRequest } from "@/lib/api-client"
import { getGatewayRuntimeConfig } from "@/lib/gateway-config"

type JsonRecord = Record<string, unknown>

const UUID_PATTERN =
  /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i
const INVALID_RESPONSE_MESSAGE =
  "Сервис логистики вернул некорректный ответ модуля заказов."

export const ORDERS_QUERY_KEY = ["orders"] as const

export type ListOrdersParams = {
  accessToken: string
  page: number
  size: number
  search?: string
  sort: string
  direction: "asc" | "desc"
  statuses?: OrderStatus[]
  clientTypes?: OrderClientType[]
  warehouseIds?: string[]
  createdFrom?: string
  createdTo?: string
}

export type CreateOrderInput =
  | { clientId: string; newClient?: never }
  | {
      clientId?: never
      newClient: { clientType: OrderClientType; displayName: string }
    }

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
  if (!Array.isArray(value)) {
    invalidResponse()
  }

  return value
}

function text(value: unknown): string {
  if (typeof value !== "string" || value.trim() === "") {
    invalidResponse()
  }

  return value
}

function uuid(value: unknown): string {
  const parsed = text(value)
  if (!UUID_PATTERN.test(parsed)) {
    invalidResponse()
  }

  return parsed
}

function nullableUuid(value: unknown): string | null {
  return value === null ? null : uuid(value)
}

function nullableText(value: unknown): string | null {
  if (value === null) return null
  if (typeof value !== "string") invalidResponse()
  return value as string
}

function nonNegativeInteger(value: unknown): number {
  if (typeof value !== "number" || !Number.isSafeInteger(value) || value < 0) {
    invalidResponse()
  }

  return value
}

function boolean(value: unknown): boolean {
  if (typeof value !== "boolean") invalidResponse()
  return value as boolean
}

function timestamp(value: unknown): string {
  const parsed = text(value)
  if (!Number.isFinite(Date.parse(parsed))) invalidResponse()
  return parsed
}

function enumValue<T extends string>(value: unknown, allowed: readonly T[]): T {
  const parsed = text(value)
  if (!allowed.includes(parsed as T)) invalidResponse()
  return parsed as T
}

function jsonObjectOrNull(value: unknown): JsonRecord | null {
  return value === null ? null : record(value)
}

function parseClient(value: unknown): OrderClient {
  const source = record(value)
  return {
    id: uuid(source.id),
    type: enumValue(source.type, ORDER_CLIENT_TYPES),
    displayName: text(source.displayName),
  }
}

function parseClientSearchItem(value: unknown): OrderClientSearchItem {
  const source = record(value)
  const client = parseClient(source)

  return {
    ...client,
    ...(source.version === undefined
      ? {}
      : { version: nonNegativeInteger(source.version) }),
    ...(source.createdAt === undefined
      ? {}
      : { createdAt: timestamp(source.createdAt) }),
    ...(source.updatedAt === undefined
      ? {}
      : { updatedAt: timestamp(source.updatedAt) }),
  }
}

function parseOrderSummaryRecord(source: JsonRecord): OrderSummary {
  return {
    id: uuid(source.id),
    version: nonNegativeInteger(source.version),
    number: text(source.number),
    status: enumValue<OrderStatus>(source.status, ORDER_STATUSES),
    client: parseClient(source.client),
    managerId: uuid(source.managerId),
    managerDisplayName: text(source.managerDisplayName),
    createdBy: uuid(source.createdBy),
    createdByDisplayName: text(source.createdByDisplayName),
    warehouseId: nullableUuid(source.warehouseId),
    unitCount: nonNegativeInteger(source.unitCount),
    createdAt: timestamp(source.createdAt),
    updatedAt: timestamp(source.updatedAt),
  }
}

export function parseOrderSummary(value: unknown): OrderSummary {
  return parseOrderSummaryRecord(record(value))
}

function parseEquipmentContent(value: unknown): OrderEquipmentContent {
  const source = record(value)
  return {
    equipmentId: uuid(source.equipmentId),
    equipmentCode: text(source.equipmentCode),
    equipmentName: text(source.equipmentName),
    quantity: nonNegativeInteger(source.quantity),
    locationKind: text(source.locationKind),
  }
}

function parseRentalUnit(value: unknown): OrderRentalUnit {
  const source = record(value)
  const status = text(source.status)
  if (!Object.hasOwn(RENTAL_ITEM_STATUS_LABEL, status)) invalidResponse()

  return {
    id: uuid(source.id),
    version: nonNegativeInteger(source.version),
    warehouseId: uuid(source.warehouseId),
    number: text(source.number),
    status: status as RentalItemStatus,
    rentalType: nullableText(source.rentalType),
    dimensions: nullableText(source.dimensions),
    finishing: nullableText(source.finishing),
    category: nullableText(source.category),
    characteristics: nullableText(source.characteristics),
    linoleum: source.linoleum === null ? null : boolean(source.linoleum),
    tags: list(source.tags).map(text),
    contents: list(source.contents).map(parseEquipmentContent),
    createdAt: timestamp(source.createdAt),
    updatedAt: timestamp(source.updatedAt),
  }
}

function parseOrderUnitCandidate(value: unknown): OrderUnitCandidate {
  const source = record(value)
  const added = boolean(source.added)
  const reservationId = nullableUuid(source.reservationId)

  if (added && reservationId === null) invalidResponse()

  return {
    reservationId,
    added,
    unit: parseRentalUnit(source.unit),
  }
}

export function parseOrderDetail(value: unknown): OrderDetail {
  const source = record(value)
  const permissions = record(source.permissions)
  const detail = {
    ...parseOrderSummaryRecord(source),
    units: list(source.units).map(parseOrderUnitCandidate),
    permissions: {
      canEdit: boolean(permissions.canEdit),
      canViewOtherManagers: boolean(permissions.canViewOtherManagers),
    },
  }

  if (detail.unitCount !== detail.units.length) invalidResponse()
  return detail
}

function parsePage<T>(
  value: unknown,
  parseItem: (item: unknown) => T
): OrderPage<T> {
  const source = record(value)
  return {
    content: list(source.content).map(parseItem),
    page: nonNegativeInteger(source.page),
    size: nonNegativeInteger(source.size),
    totalElements: nonNegativeInteger(source.totalElements),
    totalPages: nonNegativeInteger(source.totalPages),
  }
}

function parseAuditEvent(value: unknown): OrderAuditEvent {
  const source = record(value)
  return {
    id: uuid(source.id),
    orderId: uuid(source.orderId),
    eventType: enumValue<OrderAuditEventType>(
      source.eventType,
      ORDER_AUDIT_EVENT_TYPES
    ),
    actorSubjectId: uuid(source.actorSubjectId),
    actorRole: text(source.actorRole),
    subjectType: text(source.subjectType),
    subjectId: text(source.subjectId),
    previousValues: jsonObjectOrNull(source.previousValues),
    newValues: jsonObjectOrNull(source.newValues),
    occurredAt: timestamp(source.occurredAt),
  }
}

function apiBaseUrl() {
  return `${getGatewayRuntimeConfig().logisticsApiBaseUrl}/v1`
}

function ordersEndpoint(path = "") {
  return `${apiBaseUrl()}/orders${path}`
}

function clientsEndpoint(path = "") {
  return `${apiBaseUrl()}/clients${path}`
}

function idempotencyHeaders(idempotencyKey: string) {
  return { "Idempotency-Key": uuid(idempotencyKey) }
}

function orderPath(orderId: string, suffix = "") {
  return `/${encodeURIComponent(uuid(orderId))}${suffix}`
}

export async function listOrders(
  params: ListOrdersParams
): Promise<OrderPage<OrderSummary>> {
  const endpoint = new URL(ordersEndpoint())
  endpoint.searchParams.set("page", String(params.page))
  endpoint.searchParams.set("size", String(params.size))
  if (params.search?.trim()) {
    endpoint.searchParams.set("search", params.search.trim())
  }
  endpoint.searchParams.set("sort", params.sort)
  endpoint.searchParams.set("direction", params.direction.toUpperCase())
  params.statuses?.forEach((status) =>
    endpoint.searchParams.append("status", status)
  )
  params.clientTypes?.forEach((clientType) =>
    endpoint.searchParams.append("clientType", clientType)
  )
  params.warehouseIds?.forEach((warehouseId) =>
    endpoint.searchParams.append("warehouseId", uuid(warehouseId))
  )
  if (params.createdFrom) {
    endpoint.searchParams.set("createdFrom", params.createdFrom)
  }
  if (params.createdTo) {
    endpoint.searchParams.set("createdTo", params.createdTo)
  }

  return parsePage(
    await bearerRequest<unknown>(params.accessToken, endpoint),
    parseOrderSummary
  )
}

export async function createOrder(params: {
  accessToken: string
  idempotencyKey: string
  input: CreateOrderInput
}): Promise<OrderDetail> {
  return parseOrderDetail(
    await bearerRequest<unknown>(params.accessToken, ordersEndpoint(), {
      method: "POST",
      headers: idempotencyHeaders(params.idempotencyKey),
      body: JSON.stringify(params.input),
    })
  )
}

export async function getOrder(
  accessToken: string,
  orderId: string
): Promise<OrderDetail> {
  return parseOrderDetail(
    await bearerRequest<unknown>(
      accessToken,
      ordersEndpoint(orderPath(orderId))
    )
  )
}

export async function updateOrder(params: {
  accessToken: string
  orderId: string
  expectedVersion: number
  clientId: string
  idempotencyKey: string
}): Promise<OrderDetail> {
  return parseOrderDetail(
    await bearerRequest<unknown>(
      params.accessToken,
      ordersEndpoint(orderPath(params.orderId)),
      {
        method: "PUT",
        headers: idempotencyHeaders(params.idempotencyKey),
        body: JSON.stringify({
          expectedVersion: params.expectedVersion,
          clientId: uuid(params.clientId),
        }),
      }
    )
  )
}

export async function deleteOrder(params: {
  accessToken: string
  orderId: string
  expectedVersion: number
  idempotencyKey: string
}): Promise<OrderDetail> {
  const endpoint = new URL(ordersEndpoint(orderPath(params.orderId)))
  endpoint.searchParams.set("expectedVersion", String(params.expectedVersion))

  return parseOrderDetail(
    await bearerRequest<unknown>(params.accessToken, endpoint, {
      method: "DELETE",
      headers: idempotencyHeaders(params.idempotencyKey),
    })
  )
}

export async function saveOrder(params: {
  accessToken: string
  orderId: string
  expectedVersion: number
  idempotencyKey: string
}): Promise<OrderDetail> {
  const endpoint = new URL(ordersEndpoint(orderPath(params.orderId, "/save")))
  endpoint.searchParams.set("expectedVersion", String(params.expectedVersion))

  return parseOrderDetail(
    await bearerRequest<unknown>(params.accessToken, endpoint, {
      method: "POST",
      headers: idempotencyHeaders(params.idempotencyKey),
    })
  )
}

export async function listOrderClients(params: {
  accessToken: string
  type?: OrderClientType
  search: string
  page?: number
  size?: number
}): Promise<OrderPage<OrderClientSearchItem>> {
  const endpoint = new URL(clientsEndpoint())
  if (params.type) endpoint.searchParams.set("type", params.type)
  endpoint.searchParams.set("search", params.search.trim())
  endpoint.searchParams.set("page", String(params.page ?? 0))
  endpoint.searchParams.set("size", String(params.size ?? 20))

  return parsePage(
    await bearerRequest<unknown>(params.accessToken, endpoint),
    parseClientSearchItem
  )
}

export async function createOrderClient(params: {
  accessToken: string
  idempotencyKey: string
  input: { clientType: OrderClientType; displayName: string }
}): Promise<OrderClientSearchItem> {
  return parseClientSearchItem(
    await bearerRequest<unknown>(params.accessToken, clientsEndpoint(), {
      method: "POST",
      headers: idempotencyHeaders(params.idempotencyKey),
      body: JSON.stringify(params.input),
    })
  )
}

export async function selectOrderWarehouse(params: {
  accessToken: string
  orderId: string
  expectedVersion: number
  warehouseId: string
  idempotencyKey: string
}): Promise<OrderDetail> {
  return parseOrderDetail(
    await bearerRequest<unknown>(
      params.accessToken,
      ordersEndpoint(orderPath(params.orderId, "/warehouse")),
      {
        method: "PUT",
        headers: idempotencyHeaders(params.idempotencyKey),
        body: JSON.stringify({
          expectedVersion: params.expectedVersion,
          warehouseId: uuid(params.warehouseId),
        }),
      }
    )
  )
}

export async function listAvailableOrderUnits(params: {
  accessToken: string
  orderId: string
  page: number
  size: number
  search?: string
}): Promise<OrderPage<OrderUnitCandidate>> {
  const endpoint = new URL(
    ordersEndpoint(orderPath(params.orderId, "/available-units"))
  )
  endpoint.searchParams.set("page", String(params.page))
  endpoint.searchParams.set("size", String(params.size))
  if (params.search?.trim()) {
    endpoint.searchParams.set("search", params.search.trim())
  }

  return parsePage(
    await bearerRequest<unknown>(params.accessToken, endpoint),
    parseOrderUnitCandidate
  )
}

export async function addOrderUnit(params: {
  accessToken: string
  orderId: string
  expectedVersion: number
  unitId: string
  idempotencyKey: string
}): Promise<OrderDetail> {
  return parseOrderDetail(
    await bearerRequest<unknown>(
      params.accessToken,
      ordersEndpoint(orderPath(params.orderId, "/units")),
      {
        method: "POST",
        headers: idempotencyHeaders(params.idempotencyKey),
        body: JSON.stringify({
          expectedVersion: params.expectedVersion,
          unitId: uuid(params.unitId),
        }),
      }
    )
  )
}

export async function removeOrderUnit(params: {
  accessToken: string
  orderId: string
  expectedVersion: number
  unitId: string
  idempotencyKey: string
}): Promise<OrderDetail> {
  const suffix = `/units/${encodeURIComponent(uuid(params.unitId))}`
  const endpoint = new URL(ordersEndpoint(orderPath(params.orderId, suffix)))
  endpoint.searchParams.set("expectedVersion", String(params.expectedVersion))

  return parseOrderDetail(
    await bearerRequest<unknown>(params.accessToken, endpoint, {
      method: "DELETE",
      headers: idempotencyHeaders(params.idempotencyKey),
    })
  )
}

export async function listOrderHistory(
  accessToken: string,
  orderId: string
): Promise<OrderAuditEvent[]> {
  return list(
    await bearerRequest<unknown>(
      accessToken,
      ordersEndpoint(orderPath(orderId, "/history"))
    )
  ).map(parseAuditEvent)
}

export function createOrderIdempotencyKey() {
  if (
    typeof crypto === "undefined" ||
    typeof crypto.randomUUID !== "function"
  ) {
    throw new Error("Браузер не поддерживает ключи идемпотентности.")
  }

  return crypto.randomUUID()
}
