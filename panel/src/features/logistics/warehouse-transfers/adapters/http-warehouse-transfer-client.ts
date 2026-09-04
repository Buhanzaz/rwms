import {
  TRANSFER_DOCUMENT_STATES,
  TRANSFER_FURNITURE_READINESS_STATES,
  TRANSFER_FURNITURE_TASK_STATES,
  TRANSFER_LINE_STATES,
  type TransferDocument,
  type TransferArrivalPreflight,
  type TransferDocumentState,
  type TransferFurnitureReadiness,
  type TransferFurnitureReadinessState,
  type TransferFurnitureTaskState,
  type TransferFurnitureTaskStatus,
  type TransferLine,
  type TransferLineState,
  type TransferCabinAllocationRequest,
  type TransferCabinGroup,
  type TransferFurniturePerCabin,
  type TransferFurnitureTotal,
  type TransferLooseFurnitureRequest,
  type TransferPlan,
  type TransferPlanState,
  type TransferReservationReadiness,
  type TransferResourceReposition,
  type TransferResourceRepositionMode,
} from "@/features/logistics/warehouse-transfers/model/warehouse-transfer"
import { listAllLogisticsDocumentPages } from "@/features/logistics/document-list-pagination"
import type {
  TransferArrivalCommand,
  TransferCreateCommand,
  TransferLineCommand,
  TransferPlanUpdateCommand,
  TransferReconcileCommand,
  TransferVersionedCommand,
  WarehouseTransferClient,
} from "@/features/logistics/warehouse-transfers/ports/warehouse-transfer-client"
import { bearerRequest } from "@/lib/api-client"
import { getGatewayRuntimeConfig } from "@/lib/gateway-config"

type JsonObject = Record<string, unknown>

const UUID_PATTERN =
  /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i
const DOCUMENT_KEYS = [
  "id",
  "version",
  "documentType",
  "customerDeliveryPurpose",
  "state",
  "warehouseId",
  "destinationWarehouseId",
  "linkedReturnTransferId",
  "partySnapshot",
  "driverSnapshot",
  "driverWorkerId",
  "clientId",
  "historicalRentalImport",
  "equipmentMovementTaskId",
  "scheduledDate",
  "rentalOrderId",
  "rentalShipmentId",
  "inventorySourceId",
  "inventorySourceFindingId",
  "inventorySourceDispositionKind",
  "lines",
  "createdAt",
  "updatedAt",
] as const
const LINE_KEYS = [
  "id",
  "version",
  "lineNumber",
  "assetId",
  "assetVersion",
  "state",
  "tenantSnapshot",
  "rentalOrderId",
  "inventorySourceWarehouseId",
  "inventoryShipmentFurniture",
] as const
const FURNITURE_READINESS_KEYS = [
  "transferId",
  "transferVersion",
  "state",
  "tasks",
] as const
const FURNITURE_TASK_KEYS = [
  "rentalItemId",
  "unitNumber",
  "taskId",
  "externalTaskId",
  "taskBoardTaskId",
  "taskState",
  "lineCount",
] as const
const ARRIVAL_PREFLIGHT_KEYS = [
  "transferId",
  "lineId",
  "activeRepairId",
  "priorityRequired",
  "missingQueueDefinitionIds",
] as const
const TRANSFER_PLAN_KEYS = [
  "transferId",
  "documentVersion",
  "documentState",
  "planId",
  "planVersion",
  "state",
  "reservationReadiness",
  "readinessDetail",
  "legacyCompatible",
  "scheduledDate",
  "plannedDepartureAt",
  "plannedArrivalAt",
  "logisticsComment",
  "tripDriverId",
  "tripVehicleId",
  "driverReposition",
  "vehicleReposition",
  "cabinGroups",
  "looseFurniture",
  "totalCabinCount",
  "furnitureTotals",
] as const
const RESOURCE_INTENT_KEYS = ["resourceId", "mode", "until"] as const
const CABIN_GROUP_KEYS = [
  "groupId",
  "position",
  "rentalTypeId",
  "dimensionId",
  "finishingId",
  "characteristicIds",
  "linoleum",
  "quantity",
  "furniturePerCabin",
  "allocatedCabins",
] as const
const FURNITURE_PER_CABIN_KEYS = [
  "furnitureCatalogItemId",
  "quantityPerCabin",
  "totalQuantity",
] as const
const CABIN_ALLOCATION_KEYS = ["assetId", "assetVersion"] as const
const LOOSE_FURNITURE_KEYS = ["furnitureCatalogItemId", "quantity"] as const
const FURNITURE_TOTAL_KEYS = [
  "furnitureCatalogItemId",
  "cabinRequirementQuantity",
  "looseQuantity",
  "totalQuantity",
] as const

function invalidResponse(): never {
  throw new Error("Сервис логистики вернул некорректный ответ перемещения.")
}

function object(value: unknown, keys: readonly string[]): JsonObject {
  if (!value || typeof value !== "object" || Array.isArray(value)) {
    invalidResponse()
  }
  const source = value as JsonObject
  const actualKeys = Object.keys(source)
  if (
    actualKeys.length !== keys.length ||
    !keys.every((key) => Object.hasOwn(source, key))
  ) {
    invalidResponse()
  }
  return source
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

function nullableText(value: unknown): string | null {
  return value === null ? null : text(value)
}

function nullableSnapshot(value: unknown): string | null {
  if (value === null) return null
  const candidate = nonBlankText(value)
  if (candidate.length > 512) invalidResponse()
  return candidate
}

function nullableUuid(value: unknown): string | null {
  return value === null ? null : uuid(value)
}

function nullableTimestamp(value: unknown): string | null {
  return value === null ? null : timestamp(value)
}

function nullableInteger(value: unknown): number | null {
  return value === null ? null : integer(value)
}

function integer(value: unknown): number {
  if (!Number.isSafeInteger(value) || (value as number) < 0) invalidResponse()
  return value as number
}

function flag(value: unknown): boolean {
  if (typeof value !== "boolean") invalidResponse()
  return value
}

function nullableFlag(value: unknown): boolean | null {
  return value === null ? null : flag(value)
}

function timestamp(value: unknown): string {
  const candidate = text(value)
  if (!candidate || !Number.isFinite(Date.parse(candidate))) invalidResponse()
  return candidate
}

function calendarDate(value: unknown): string {
  const candidate = text(value)
  if (!/^\d{4}-\d{2}-\d{2}$/.test(candidate)) invalidResponse()
  const date = new Date(`${candidate}T00:00:00Z`)
  if (
    !Number.isFinite(date.getTime()) ||
    date.toISOString().slice(0, 10) !== candidate
  ) {
    invalidResponse()
  }
  return candidate
}

function oneOf<T extends string>(value: unknown, allowed: readonly T[]): T {
  const candidate = text(value)
  if (!allowed.includes(candidate as T)) invalidResponse()
  return candidate as T
}

function positiveInteger(value: unknown): number {
  const candidate = integer(value)
  if (candidate < 1) invalidResponse()
  return candidate
}

function uniqueUuids(value: unknown): string[] {
  const ids = list(value).map(uuid)
  if (new Set(ids).size !== ids.length) invalidResponse()
  return ids
}

function transferLine(value: unknown): TransferLine {
  const source = object(value, LINE_KEYS)
  const lineNumber = integer(source.lineNumber)
  if (lineNumber < 1) invalidResponse()
  return {
    id: uuid(source.id),
    version: integer(source.version),
    lineNumber,
    assetId: uuid(source.assetId),
    assetVersion: integer(source.assetVersion),
    state: oneOf<TransferLineState>(source.state, TRANSFER_LINE_STATES),
    tenantSnapshot: nullableText(source.tenantSnapshot),
    rentalOrderId: (() => {
      if (nullableUuid(source.rentalOrderId) !== null) invalidResponse()
      return null
    })(),
    inventorySourceWarehouseId: uuid(source.inventorySourceWarehouseId),
    inventoryShipmentFurniture: (() => {
      if (source.inventoryShipmentFurniture !== null) invalidResponse()
      return null
    })(),
  }
}

function transferFurnitureTaskStatus(
  value: unknown
): TransferFurnitureTaskStatus {
  const source = object(value, FURNITURE_TASK_KEYS)
  const lineCount = integer(source.lineCount)
  if (lineCount < 1) invalidResponse()
  return {
    rentalItemId: uuid(source.rentalItemId),
    unitNumber: nonBlankText(source.unitNumber),
    taskId: uuid(source.taskId),
    externalTaskId: uuid(source.externalTaskId),
    taskBoardTaskId: nullableUuid(source.taskBoardTaskId),
    taskState: oneOf<TransferFurnitureTaskState>(
      source.taskState,
      TRANSFER_FURNITURE_TASK_STATES
    ),
    lineCount,
  }
}

export function parseTransferFurnitureReadiness(
  value: unknown
): TransferFurnitureReadiness {
  const source = object(value, FURNITURE_READINESS_KEYS)
  return {
    transferId: uuid(source.transferId),
    transferVersion: integer(source.transferVersion),
    state: oneOf<TransferFurnitureReadinessState>(
      source.state,
      TRANSFER_FURNITURE_READINESS_STATES
    ),
    tasks: list(source.tasks).map(transferFurnitureTaskStatus),
  }
}

export function parseTransferArrivalPreflight(
  value: unknown
): TransferArrivalPreflight {
  const source = object(value, ARRIVAL_PREFLIGHT_KEYS)
  const activeRepairId = nullableUuid(source.activeRepairId)
  const priorityRequired = flag(source.priorityRequired)
  const missingQueueDefinitionIds = list(source.missingQueueDefinitionIds).map(
    uuid
  )
  if (
    new Set(missingQueueDefinitionIds).size !==
      missingQueueDefinitionIds.length ||
    (activeRepairId === null &&
      (priorityRequired || missingQueueDefinitionIds.length > 0)) ||
    (activeRepairId !== null && !priorityRequired)
  ) {
    invalidResponse()
  }
  return {
    transferId: uuid(source.transferId),
    lineId: uuid(source.lineId),
    activeRepairId,
    priorityRequired,
    missingQueueDefinitionIds,
  }
}

function transferResourceIntent(value: unknown): TransferResourceReposition {
  const source = object(value, RESOURCE_INTENT_KEYS)
  const resourceId = nullableUuid(source.resourceId)
  const mode = oneOf<TransferResourceRepositionMode>(source.mode, [
    "NONE",
    "TEMPORARY",
    "PERMANENT",
  ])
  const until = nullableTimestamp(source.until)
  if (
    (mode === "NONE" && (resourceId !== null || until !== null)) ||
    (mode === "TEMPORARY" && (resourceId === null || until === null)) ||
    (mode === "PERMANENT" && (resourceId === null || until !== null))
  ) {
    invalidResponse()
  }
  return { resourceId, mode, until }
}

function transferFurniturePerCabin(
  value: unknown,
  groupQuantity: number
): TransferFurniturePerCabin {
  const source = object(value, FURNITURE_PER_CABIN_KEYS)
  const quantityPerCabin = positiveInteger(source.quantityPerCabin)
  const totalQuantity = positiveInteger(source.totalQuantity)
  if (totalQuantity !== quantityPerCabin * groupQuantity) invalidResponse()
  return {
    furnitureCatalogItemId: uuid(source.furnitureCatalogItemId),
    quantityPerCabin,
    totalQuantity,
  }
}

function transferCabinAllocation(
  value: unknown
): TransferCabinAllocationRequest {
  const source = object(value, CABIN_ALLOCATION_KEYS)
  return {
    assetId: uuid(source.assetId),
    assetVersion: integer(source.assetVersion),
  }
}

function transferCabinGroup(value: unknown): TransferCabinGroup {
  const source = object(value, CABIN_GROUP_KEYS)
  const quantity = positiveInteger(source.quantity)
  const characteristicIds = uniqueUuids(source.characteristicIds)
  const furniturePerCabin = list(source.furniturePerCabin).map((item) =>
    transferFurniturePerCabin(item, quantity)
  )
  const allocatedCabins = list(source.allocatedCabins).map(
    transferCabinAllocation
  )
  if (
    new Set(furniturePerCabin.map((item) => item.furnitureCatalogItemId))
      .size !== furniturePerCabin.length ||
    new Set(allocatedCabins.map((item) => item.assetId)).size !==
      allocatedCabins.length ||
    allocatedCabins.length > quantity
  ) {
    invalidResponse()
  }
  return {
    groupId: uuid(source.groupId),
    position: positiveInteger(source.position),
    rentalTypeId: uuid(source.rentalTypeId),
    dimensionId: nullableUuid(source.dimensionId),
    finishingId: nullableUuid(source.finishingId),
    characteristicIds,
    linoleum: nullableFlag(source.linoleum),
    quantity,
    furniturePerCabin,
    allocatedCabins,
  }
}

function transferLooseFurniture(value: unknown): TransferLooseFurnitureRequest {
  const source = object(value, LOOSE_FURNITURE_KEYS)
  return {
    furnitureCatalogItemId: uuid(source.furnitureCatalogItemId),
    quantity: positiveInteger(source.quantity),
  }
}

function transferFurnitureTotal(value: unknown): TransferFurnitureTotal {
  const source = object(value, FURNITURE_TOTAL_KEYS)
  const cabinRequirementQuantity = integer(source.cabinRequirementQuantity)
  const looseQuantity = integer(source.looseQuantity)
  const totalQuantity = positiveInteger(source.totalQuantity)
  if (totalQuantity !== cabinRequirementQuantity + looseQuantity) {
    invalidResponse()
  }
  return {
    furnitureCatalogItemId: uuid(source.furnitureCatalogItemId),
    cabinRequirementQuantity,
    looseQuantity,
    totalQuantity,
  }
}

/** Parses the exact planning projection without synthesizing missing readiness. */
export function parseTransferPlan(value: unknown): TransferPlan {
  const source = object(value, TRANSFER_PLAN_KEYS)
  const plannedDepartureAt = nullableTimestamp(source.plannedDepartureAt)
  const plannedArrivalAt = nullableTimestamp(source.plannedArrivalAt)
  const cabinGroups = list(source.cabinGroups).map(transferCabinGroup)
  const looseFurniture = list(source.looseFurniture).map(transferLooseFurniture)
  const furnitureTotals = list(source.furnitureTotals).map(
    transferFurnitureTotal
  )
  const totalCabinCount = integer(source.totalCabinCount)
  if (
    (plannedDepartureAt !== null &&
      plannedArrivalAt !== null &&
      Date.parse(plannedArrivalAt) <= Date.parse(plannedDepartureAt)) ||
    new Set(cabinGroups.map((group) => group.groupId)).size !==
      cabinGroups.length ||
    new Set(cabinGroups.map((group) => group.position)).size !==
      cabinGroups.length ||
    new Set(looseFurniture.map((item) => item.furnitureCatalogItemId)).size !==
      looseFurniture.length ||
    new Set(furnitureTotals.map((item) => item.furnitureCatalogItemId)).size !==
      furnitureTotals.length ||
    totalCabinCount !==
      cabinGroups.reduce((total, group) => total + group.quantity, 0)
  ) {
    invalidResponse()
  }
  return {
    transferId: uuid(source.transferId),
    documentVersion: integer(source.documentVersion),
    documentState: oneOf<TransferDocumentState>(
      source.documentState,
      TRANSFER_DOCUMENT_STATES
    ),
    planId: nullableUuid(source.planId),
    planVersion: nullableInteger(source.planVersion),
    state: oneOf<TransferPlanState>(source.state, ["DRAFT", "CONFIRMED"]),
    reservationReadiness: oneOf<TransferReservationReadiness>(
      source.reservationReadiness,
      ["NOT_RESERVED", "RESERVED"]
    ),
    readinessDetail: nullableText(source.readinessDetail),
    legacyCompatible: flag(source.legacyCompatible),
    scheduledDate: calendarDate(source.scheduledDate),
    plannedDepartureAt,
    plannedArrivalAt,
    logisticsComment: nullableText(source.logisticsComment),
    tripDriverId: nullableUuid(source.tripDriverId),
    tripVehicleId: nullableUuid(source.tripVehicleId),
    driverReposition: transferResourceIntent(source.driverReposition),
    vehicleReposition: transferResourceIntent(source.vehicleReposition),
    cabinGroups,
    looseFurniture,
    totalCabinCount,
    furnitureTotals,
  }
}

export function parseTransferDocument(value: unknown): TransferDocument {
  const source = object(value, DOCUMENT_KEYS)
  const warehouseId = uuid(source.warehouseId)
  const destinationWarehouseId = uuid(source.destinationWarehouseId)
  const lines = list(source.lines).map(transferLine)
  const linkedReturnTransferId = nullableUuid(source.linkedReturnTransferId)
  const driverSnapshot = nullableSnapshot(source.driverSnapshot)
  const driverWorkerId = nullableUuid(source.driverWorkerId)
  if (
    source.documentType !== "TRANSFER" ||
    source.customerDeliveryPurpose !== null ||
    source.partySnapshot !== null ||
    nullableUuid(source.clientId) !== null ||
    flag(source.historicalRentalImport) ||
    source.rentalShipmentId !== null ||
    nullableUuid(source.inventorySourceId) !== null ||
    nullableUuid(source.inventorySourceFindingId) !== null ||
    source.inventorySourceDispositionKind !== null ||
    warehouseId === destinationWarehouseId
  ) {
    invalidResponse()
  }
  if (
    (driverSnapshot === null) !== (driverWorkerId === null) ||
    lines.some(
      (line) =>
        line.inventorySourceWarehouseId !== warehouseId ||
        line.inventoryShipmentFurniture !== null
    )
  ) {
    invalidResponse()
  }

  return {
    id: uuid(source.id),
    version: integer(source.version),
    documentType: "TRANSFER",
    customerDeliveryPurpose: null,
    state: oneOf<TransferDocumentState>(source.state, TRANSFER_DOCUMENT_STATES),
    warehouseId,
    destinationWarehouseId,
    linkedReturnTransferId,
    partySnapshot: null,
    driverSnapshot,
    driverWorkerId,
    clientId: null,
    historicalRentalImport: false,
    equipmentMovementTaskId: nullableUuid(source.equipmentMovementTaskId),
    scheduledDate: (() => {
      return calendarDate(source.scheduledDate)
    })(),
    rentalOrderId: (() => {
      if (nullableUuid(source.rentalOrderId) !== null) invalidResponse()
      return null
    })(),
    rentalShipmentId: null,
    inventorySourceId: null,
    inventorySourceFindingId: null,
    inventorySourceDispositionKind: null,
    lines,
    createdAt: timestamp(source.createdAt),
    updatedAt: timestamp(source.updatedAt),
  }
}

function transfersEndpoint(path = "") {
  return `${getGatewayRuntimeConfig().logisticsApiBaseUrl}/v1/transfers${path}`
}

function commandHeaders(idempotencyKey: string) {
  return { "Idempotency-Key": idempotencyKey }
}

async function parsedRequest(
  accessToken: string,
  input: string,
  init?: RequestInit
) {
  return parseTransferDocument(
    await bearerRequest<unknown>(accessToken, input, init)
  )
}

function lineCommandPath(
  input: Pick<
    TransferLineCommand,
    "documentId" | "lineId" | "expectedVersion" | "expectedLineVersion"
  >,
  action: string
) {
  const documentId = encodeURIComponent(input.documentId)
  const lineId = encodeURIComponent(input.lineId)
  return `/${documentId}/lines/${lineId}/${action}?expectedVersion=${input.expectedVersion}&expectedLineVersion=${input.expectedLineVersion}`
}

export class HttpWarehouseTransferClient implements WarehouseTransferClient {
  async list(
    accessToken: string,
    warehouseId: string,
    scheduledDate?: string
  ) {
    const search = new URLSearchParams({ warehouseId })
    if (scheduledDate) search.set("scheduledDate", scheduledDate)
    const endpoint = transfersEndpoint(`?${search.toString()}`)
    if (scheduledDate) {
      return listAllLogisticsDocumentPages(accessToken, endpoint, (response) =>
        list(response).map(parseTransferDocument)
      )
    }
    const response = await bearerRequest<unknown>(accessToken, endpoint)
    return list(response).map(parseTransferDocument)
  }

  get(accessToken: string, documentId: string) {
    return parsedRequest(
      accessToken,
      transfersEndpoint(`/${encodeURIComponent(documentId)}`)
    )
  }

  async getPlan(accessToken: string, documentId: string) {
    const plan = parseTransferPlan(
      await bearerRequest<unknown>(
        accessToken,
        transfersEndpoint(`/${encodeURIComponent(documentId)}/plan`)
      )
    )
    if (plan.transferId !== documentId) invalidResponse()
    return plan
  }

  async getFurnitureReadiness(accessToken: string, documentId: string) {
    const readiness = parseTransferFurnitureReadiness(
      await bearerRequest<unknown>(
        accessToken,
        transfersEndpoint(
          `/${encodeURIComponent(documentId)}/furniture-readiness`
        )
      )
    )
    if (readiness.transferId !== documentId) invalidResponse()
    return readiness
  }

  async getArrivalPreflight(
    input: Omit<TransferLineCommand, "idempotencyKey">
  ) {
    const preflight = parseTransferArrivalPreflight(
      await bearerRequest<unknown>(
        input.accessToken,
        transfersEndpoint(lineCommandPath(input, "arrival-preflight"))
      )
    )
    if (
      preflight.transferId !== input.documentId ||
      preflight.lineId !== input.lineId
    ) {
      invalidResponse()
    }
    return preflight
  }

  create(input: TransferCreateCommand) {
    return parsedRequest(input.accessToken, transfersEndpoint(), {
      method: "POST",
      headers: commandHeaders(input.idempotencyKey),
      body: JSON.stringify({
        warehouseId: input.warehouseId,
        destinationWarehouseId: input.destinationWarehouseId,
        scheduledDate: input.scheduledDate,
        lines: input.lines,
        furnitureReplacements: input.furnitureReplacements,
        ...(Object.hasOwn(input, "plan") ? { plan: input.plan ?? null } : {}),
      }),
    })
  }

  async updatePlan(input: TransferPlanUpdateCommand) {
    const plan = parseTransferPlan(
      await bearerRequest<unknown>(
        input.accessToken,
        transfersEndpoint(
          `/${encodeURIComponent(input.documentId)}/plan?expectedVersion=${input.expectedVersion}`
        ),
        {
          method: "PUT",
          headers: commandHeaders(input.idempotencyKey),
          body: JSON.stringify({
            scheduledDate: input.scheduledDate,
            plan: input.plan,
          }),
        }
      )
    )
    if (plan.transferId !== input.documentId) invalidResponse()
    return plan
  }

  async confirm(input: TransferVersionedCommand) {
    const plan = parseTransferPlan(
      await bearerRequest<unknown>(
        input.accessToken,
        transfersEndpoint(
          `/${encodeURIComponent(input.documentId)}/confirm?expectedVersion=${input.expectedVersion}`
        ),
        {
          method: "POST",
          headers: commandHeaders(input.idempotencyKey),
        }
      )
    )
    if (plan.transferId !== input.documentId) invalidResponse()
    return plan
  }

  depart(input: TransferLineCommand) {
    return parsedRequest(
      input.accessToken,
      transfersEndpoint(lineCommandPath(input, "depart")),
      {
        method: "POST",
        headers: commandHeaders(input.idempotencyKey),
      }
    )
  }

  arrive(input: TransferArrivalCommand) {
    return parsedRequest(
      input.accessToken,
      transfersEndpoint(lineCommandPath(input, "arrive")),
      {
        method: "POST",
        headers: commandHeaders(input.idempotencyKey),
        body: JSON.stringify({
          references: input.references,
          priority: input.priority,
        }),
      }
    )
  }

  cancel(input: TransferVersionedCommand) {
    return parsedRequest(
      input.accessToken,
      transfersEndpoint(
        `/${encodeURIComponent(input.documentId)}/cancel?expectedVersion=${input.expectedVersion}`
      ),
      {
        method: "POST",
        headers: commandHeaders(input.idempotencyKey),
      }
    )
  }

  reconcile(input: TransferReconcileCommand) {
    return parsedRequest(
      input.accessToken,
      transfersEndpoint(
        `/${encodeURIComponent(input.documentId)}/reconcile?expectedVersion=${input.expectedVersion}`
      ),
      {
        method: "POST",
        headers: commandHeaders(input.idempotencyKey),
        body: JSON.stringify({ reason: input.reason }),
      }
    )
  }
}
