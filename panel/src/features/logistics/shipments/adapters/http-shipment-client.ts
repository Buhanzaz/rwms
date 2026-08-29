import {
  SHIPMENT_DOCUMENT_STATES,
  SHIPMENT_FURNITURE_READINESS_STATES,
  SHIPMENT_FURNITURE_TASK_STATES,
  SHIPMENT_LINE_STATES,
  type ShipmentDocument,
  type ShipmentDocumentState,
  type ShipmentFurnitureReadiness,
  type ShipmentFurnitureReadinessState,
  type ShipmentFurnitureTask,
  type ShipmentFurnitureTaskResult,
  type ShipmentFurnitureTaskState,
  type ShipmentFurnitureTaskStatus,
  type InventoryShipmentFurniture,
  type ShipmentLine,
  type ShipmentLineState,
} from "@/features/logistics/shipments/model"
import type {
  ShipmentClient,
  ShipmentCreateCommand,
  ShipmentPlanCommand,
  ShipmentVersionedCommand,
} from "@/features/logistics/shipments/ports/shipment-client"
import { bearerRequest } from "@/lib/api-client"
import { getGatewayRuntimeConfig } from "@/lib/gateway-config"

type JsonObject = Record<string, unknown>

const UUID_PATTERN =
  /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i
const LOCAL_DATE_PATTERN = /^\d{4}-\d{2}-\d{2}$/
function invalidResponse(): never {
  throw new Error("Сервис логистики вернул некорректную отгрузку.")
}

function object(value: unknown): JsonObject {
  if (!value || typeof value !== "object" || Array.isArray(value)) {
    invalidResponse()
  }
  return value as JsonObject
}

function list(value: unknown): unknown[] {
  if (!Array.isArray(value)) invalidResponse()
  return value
}

function text(value: unknown): string {
  if (typeof value !== "string") invalidResponse()
  return value
}

function nonBlankText(value: unknown): string {
  const candidate = text(value)
  if (!candidate.trim()) invalidResponse()
  return candidate
}

function uuid(value: unknown): string {
  const candidate = text(value)
  if (!UUID_PATTERN.test(candidate)) invalidResponse()
  return candidate
}

function nullableUuid(value: unknown): string | null {
  return value === null ? null : uuid(value)
}

function nullableText(value: unknown): string | null {
  return value === null ? null : text(value)
}

function integer(value: unknown): number {
  if (!Number.isSafeInteger(value) || (value as number) < 0) invalidResponse()
  return value as number
}

function boolean(value: unknown): boolean {
  if (typeof value !== "boolean") invalidResponse()
  return value
}

function timestamp(value: unknown): string {
  const candidate = text(value)
  if (!candidate || !Number.isFinite(Date.parse(candidate))) invalidResponse()
  return candidate
}

function localDate(value: unknown): string {
  const candidate = text(value)
  if (!LOCAL_DATE_PATTERN.test(candidate)) invalidResponse()
  const date = new Date(`${candidate}T00:00:00.000Z`)
  if (date.toISOString().slice(0, 10) !== candidate) invalidResponse()
  return candidate
}

function nullableLocalDate(value: unknown): string | null {
  return value === null ? null : localDate(value)
}

function oneOf<T extends string>(value: unknown, allowed: readonly T[]): T {
  const candidate = text(value)
  if (!allowed.includes(candidate as T)) invalidResponse()
  return candidate as T
}

function inventoryShipmentFurniture(
  value: unknown
): InventoryShipmentFurniture[] | null {
  if (value === null) return null
  const source = list(value)
  if (source.length > 100) invalidResponse()

  const furniture = source.map((item) => {
    const candidate = object(item)
    const quantity = integer(candidate.quantity)
    if (quantity < 1) invalidResponse()
    return {
      equipmentId: uuid(candidate.equipmentId),
      catalogVersion: integer(candidate.catalogVersion),
      quantity,
    }
  })
  if (
    new Set(furniture.map((item) => item.equipmentId)).size !== furniture.length
  ) {
    invalidResponse()
  }
  return furniture
}

function shipmentLine(value: unknown): ShipmentLine {
  const source = object(value)
  const lineNumber = integer(source.lineNumber)
  if (lineNumber < 1) invalidResponse()
  return {
    id: uuid(source.id),
    version: integer(source.version),
    lineNumber,
    assetId: uuid(source.assetId),
    assetVersion: integer(source.assetVersion),
    state: oneOf<ShipmentLineState>(source.state, SHIPMENT_LINE_STATES),
    tenantSnapshot: nullableText(source.tenantSnapshot),
    rentalOrderId: nullableUuid(source.rentalOrderId),
    inventorySourceWarehouseId: uuid(source.inventorySourceWarehouseId),
    inventoryShipmentFurniture: inventoryShipmentFurniture(
      source.inventoryShipmentFurniture
    ),
  }
}

function shipmentFurnitureTask(value: unknown): ShipmentFurnitureTask {
  const source = object(value)
  return {
    rentalItemId: uuid(source.rentalItemId),
    unitNumber: nonBlankText(source.unitNumber),
    taskId: nullableUuid(source.taskId),
    lineCount: integer(source.lineCount),
  }
}

function parseShipmentFurnitureTaskResult(
  value: unknown
): ShipmentFurnitureTaskResult {
  const source = object(value)
  return {
    shipmentId: uuid(source.shipmentId),
    shipmentVersion: integer(source.shipmentVersion),
    tasks: list(source.tasks).map(shipmentFurnitureTask),
  }
}

function shipmentFurnitureTaskStatus(
  value: unknown
): ShipmentFurnitureTaskStatus {
  const source = object(value)
  const lineCount = integer(source.lineCount)
  if (lineCount < 1) invalidResponse()
  return {
    rentalItemId: uuid(source.rentalItemId),
    unitNumber: nonBlankText(source.unitNumber),
    taskId: uuid(source.taskId),
    externalTaskId: uuid(source.externalTaskId),
    taskBoardTaskId: nullableUuid(source.taskBoardTaskId),
    taskState: oneOf<ShipmentFurnitureTaskState>(
      source.taskState,
      SHIPMENT_FURNITURE_TASK_STATES
    ),
    lineCount,
  }
}

function parseShipmentFurnitureReadiness(
  value: unknown
): ShipmentFurnitureReadiness {
  const source = object(value)
  return {
    shipmentId: uuid(source.shipmentId),
    shipmentVersion: integer(source.shipmentVersion),
    state: oneOf<ShipmentFurnitureReadinessState>(
      source.state,
      SHIPMENT_FURNITURE_READINESS_STATES
    ),
    tasks: list(source.tasks).map(shipmentFurnitureTaskStatus),
  }
}

export function parseShipmentDocument(value: unknown): ShipmentDocument {
  const source = object(value)
  const lines = list(source.lines).map(shipmentLine)
  if (lines.length === 0) invalidResponse()
  if (source.documentType !== "SHIPMENT") invalidResponse()
  if (nullableUuid(source.destinationWarehouseId) !== null) invalidResponse()

  return {
    id: uuid(source.id),
    version: integer(source.version),
    documentType: "SHIPMENT",
    state: oneOf<ShipmentDocumentState>(source.state, SHIPMENT_DOCUMENT_STATES),
    warehouseId: uuid(source.warehouseId),
    destinationWarehouseId: null,
    partySnapshot: nonBlankText(source.partySnapshot),
    driverSnapshot:
      source.driverSnapshot === null
        ? null
        : nonBlankText(source.driverSnapshot),
    driverWorkerId:
      source.driverWorkerId === undefined
        ? null
        : nullableUuid(source.driverWorkerId),
    clientId: nullableUuid(source.clientId),
    ...(source.historicalRentalImport === undefined
      ? {}
      : { historicalRentalImport: boolean(source.historicalRentalImport) }),
    equipmentMovementTaskId: nullableUuid(source.equipmentMovementTaskId),
    scheduledDate: nullableLocalDate(source.scheduledDate),
    rentalOrderId: nullableUuid(source.rentalOrderId),
    ...(source.rentalShipmentId === undefined
      ? {}
      : { rentalShipmentId: nullableUuid(source.rentalShipmentId) }),
    lines,
    createdAt: timestamp(source.createdAt),
    updatedAt: timestamp(source.updatedAt),
  }
}

function shipmentsEndpoint(path = "") {
  return `${getGatewayRuntimeConfig().logisticsApiBaseUrl}/v1/shipments${path}`
}

function commandHeaders(idempotencyKey: string) {
  return { "Idempotency-Key": idempotencyKey }
}

async function parsedRequest(
  accessToken: string,
  input: string,
  init?: RequestInit
) {
  return parseShipmentDocument(
    await bearerRequest<unknown>(accessToken, input, init)
  )
}

export class HttpShipmentClient implements ShipmentClient {
  async list(accessToken: string, warehouseId: string) {
    const response = await bearerRequest<unknown>(
      accessToken,
      shipmentsEndpoint(`?warehouseId=${encodeURIComponent(warehouseId)}`)
    )
    return list(response).map(parseShipmentDocument)
  }

  get(accessToken: string, documentId: string) {
    return parsedRequest(
      accessToken,
      shipmentsEndpoint(`/${encodeURIComponent(documentId)}`)
    )
  }

  async getFurnitureReadiness(accessToken: string, documentId: string) {
    return parseShipmentFurnitureReadiness(
      await bearerRequest<unknown>(
        accessToken,
        shipmentsEndpoint(
          `/${encodeURIComponent(documentId)}/furniture-readiness`
        )
      )
    )
  }

  create(input: ShipmentCreateCommand) {
    return parsedRequest(input.accessToken, shipmentsEndpoint(), {
      method: "POST",
      headers: commandHeaders(input.idempotencyKey),
      body: JSON.stringify({
        warehouseId: input.warehouseId,
        clientId: input.clientId,
        rentalOrderId: input.rentalOrderId,
        partySnapshot: input.partySnapshot,
        driverSnapshot: input.driverSnapshot,
        driverWorkerId: input.driverWorkerId,
        lines: input.lines,
      }),
    })
  }

  replacePlan(input: ShipmentPlanCommand) {
    return parsedRequest(
      input.accessToken,
      shipmentsEndpoint(
        `/${encodeURIComponent(input.documentId)}/plan?expectedVersion=${input.expectedVersion}`
      ),
      {
        method: "PUT",
        headers: commandHeaders(input.idempotencyKey),
        body: JSON.stringify({
          driverSnapshot: input.driverSnapshot,
          driverWorkerId: input.driverWorkerId,
          scheduledDate: input.scheduledDate,
        }),
      }
    )
  }

  async createFurnitureTasks(input: ShipmentVersionedCommand) {
    return parseShipmentFurnitureTaskResult(
      await bearerRequest<unknown>(
        input.accessToken,
        shipmentsEndpoint(
          `/${encodeURIComponent(input.documentId)}/furniture-tasks?expectedVersion=${input.expectedVersion}`
        ),
        {
          method: "POST",
          headers: commandHeaders(input.idempotencyKey),
        }
      )
    )
  }

  confirmPreparation(input: ShipmentVersionedCommand) {
    const keepScheduledDate = input.keepScheduledDate
      ? "&keepScheduledDate=true"
      : ""
    return parsedRequest(
      input.accessToken,
      shipmentsEndpoint(
        `/${encodeURIComponent(input.documentId)}/confirm-preparation?expectedVersion=${input.expectedVersion}${keepScheduledDate}`
      ),
      {
        method: "POST",
        headers: commandHeaders(input.idempotencyKey),
      }
    )
  }

  cancel(input: ShipmentVersionedCommand) {
    return parsedRequest(
      input.accessToken,
      shipmentsEndpoint(
        `/${encodeURIComponent(input.documentId)}/cancel?expectedVersion=${input.expectedVersion}`
      ),
      {
        method: "POST",
        headers: commandHeaders(input.idempotencyKey),
      }
    )
  }
}
