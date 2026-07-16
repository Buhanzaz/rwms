import {
  getEquipmentItems,
  hasReturnEquipmentDispositionHistoryForReturnItem,
  hasUnresolvedReturnEquipmentDispositionForRentalItem,
  prepareReturnEquipmentDispositionReconciliation,
  applyReturnFurnitureEquipmentAllocation,
} from "@/api/equipment-api"
import { transferRentalItemContentsWithTask } from "@/features/rental-items/contents-transfer/api/contents-transfer-api"
import { HttpContentsTransferTaskClient } from "@/features/rental-items/contents-transfer/adapters/http-contents-transfer-task-client"
import { BrowserContentsTransferTaskClient } from "@/features/rental-items/contents-transfer/adapters/browser-contents-transfer-task-client"
import {
  getWarehouseTransfer,
  listWarehouseAccountingCorrections,
} from "@/features/logistics/warehouse-transfers/api/warehouse-transfer-api"
import { hasActiveWarehouseTransferLowLevel } from "@/features/logistics/warehouse-transfers/active-transfer-guard"
import { IndexedDbRepairEstimateMediaAdapter } from "@/features/repair-estimates/adapters/indexed-db-repair-estimate-media-adapter"
import type {
  PendingEstimateMediaUpload,
  RepairEstimateLineDto,
  RepairEstimateMediaRefDto,
  RepairEstimateStatus,
} from "@/features/repair-estimates/model/repair-estimate"
import {
  addReconciledContentsToRentalItem,
  canonicalizeRentalItemNumber,
  lookupRentalItemByNumber,
  readRentalItems,
  runRentalItemMutation,
  writeRentalItems,
} from "@/features/rental-items/api/rental-items-api"
import type {
  RentalItemContentsItemDto,
  RentalItemDto,
} from "@/features/rental-items/model/rental-item"
import type {
  RentalItemCharacteristic,
  RentalItemCreationPhoto,
  RentalItemCreationType,
  RentalItemFinishing,
} from "@/features/rental-items/model/rental-item-create"
import type {
  LogisticsStateV2,
  ReturnItemTechnicalState,
  ReturnReceiptDto,
  ReturnReceiptItemDto,
  ReturnReceiptViewDto,
  ReturnPassportSnapshotDto,
  ReturnTaskDto,
  ShipmentCandidateDto,
  ShipmentContentsChangeDto,
  ShipmentDto,
  ShipmentPreparationTaskDto,
} from "@/features/logistics/model/logistics"
import { HttpLogisticsPreparationTaskClient } from "@/features/logistics/adapters/http-logistics-preparation-task-client"
import { BrowserLogisticsPreparationTaskClient } from "@/features/logistics/adapters/browser-logistics-preparation-task-client"
import { DEV_AUTH_BYPASS_ENABLED } from "@/features/auth/auth-config"
import { listRepairWorkerGroups } from "@/features/repair-tasks/api/repair-worker-directory-api"

export const LOGISTICS_QUERY_KEY = ["logistics"] as const
export const LOGISTICS_STORAGE_KEY = "rwms:logistics:v2"
const LEGACY_LOGISTICS_STORAGE_KEY = "rwms:logistics:v1"
export const LOGISTICS_UPDATED_EVENT = "rwms:logistics:updated"
export const RETURN_INTAKE_JOURNAL_KEY = "rwms:return-intake-attempts:v1"
export const RETURN_ESTIMATE_APPLY_JOURNAL_KEY = "rwms:return-estimate-apply:v1"
const mediaClient = new IndexedDbRepairEstimateMediaAdapter()
const preparationTaskClient = DEV_AUTH_BYPASS_ENABLED
  ? new BrowserLogisticsPreparationTaskClient()
  : new HttpLogisticsPreparationTaskClient()
const reconciliationTaskClient = DEV_AUTH_BYPASS_ENABLED
  ? new BrowserContentsTransferTaskClient()
  : new HttpContentsTransferTaskClient()
export const RETURN_ESTIMATE_CLAIM_TTL_MS = 10 * 60 * 1000
export const SHIPMENT_DISPATCH_ATTEMPT_TTL_MS = 30 * 1000

type ReturnIntakeAttempt = {
  commandId: string
  status: "PENDING" | "APPLIED" | "CONFLICT" | "FAILED"
  receiptId: string | null
  rentalItemId: string | null
  updatedAt: string
  error: string | null
}

function readReturnIntakeJournal(): ReturnIntakeAttempt[] {
  if (typeof window === "undefined") return []
  try {
    const parsed = JSON.parse(
      window.localStorage.getItem(RETURN_INTAKE_JOURNAL_KEY) ?? "[]"
    )
    return Array.isArray(parsed) ? (parsed as ReturnIntakeAttempt[]) : []
  } catch {
    return []
  }
}

function writeReturnIntakeAttempt(attempt: ReturnIntakeAttempt) {
  if (typeof window === "undefined") return
  const journal = readReturnIntakeJournal().filter(
    (entry) => entry.commandId !== attempt.commandId
  )
  window.localStorage.setItem(
    RETURN_INTAKE_JOURNAL_KEY,
    JSON.stringify([attempt, ...journal].slice(0, 100))
  )
}

type ReturnEstimateApplyAttempt = {
  idempotencyKey: string
  warehouseId: string
  returnItemId: string
  rentalItemId: string
  estimateId: string
  missingEquipment: RentalItemContentsItemDto[]
  phase: "PREPARED" | "APPLIED"
}

function readReturnEstimateApplyJournal(): ReturnEstimateApplyAttempt[] {
  if (typeof window === "undefined") return []
  try {
    const value = JSON.parse(
      window.localStorage.getItem(RETURN_ESTIMATE_APPLY_JOURNAL_KEY) ?? "[]"
    )
    return Array.isArray(value) ? (value as ReturnEstimateApplyAttempt[]) : []
  } catch {
    return []
  }
}

function writeReturnEstimateApplyAttempt(attempt: ReturnEstimateApplyAttempt) {
  if (typeof window === "undefined") return
  const other = readReturnEstimateApplyJournal().filter(
    (entry) => entry.idempotencyKey !== attempt.idempotencyKey
  )
  window.localStorage.setItem(
    RETURN_ESTIMATE_APPLY_JOURNAL_KEY,
    JSON.stringify([attempt, ...other].slice(0, 100))
  )
}

function applyReturnEstimateLoss(attempt: ReturnEstimateApplyAttempt) {
  attempt.missingEquipment.forEach((item) =>
    applyReturnFurnitureEquipmentAllocation({
      idempotencyKey: `${attempt.idempotencyKey}:lost:${normalized(item.name)}`,
      warehouseId: attempt.warehouseId,
      name: item.name,
      quantity: item.quantity,
      action: "LOST",
    })
  )
}

type LegacyReturn = {
  id: string
  version: number
  warehouseId: string
  rentalItemId: string
  cabinNumber: string
  fromParty: string
  returnDate: string
  status: ReturnItemTechnicalState
  createdAt: string
  createdBy: string
  acceptedAt: string | null
  media: RepairEstimateMediaRefDto[]
  sourceEstimateId: string | null
  pendingEstimateId?: string | null
  estimateClaimId?: string | null
  estimateClaimedAt?: string | null
}

type LegacyShipment = Omit<ShipmentDto, "items"> & {
  items: Array<{
    rentalItemId: string
    cabinNumber: string
    contentsItems?: RentalItemContentsItemDto[]
  }>
}

type LegacyState = {
  service: "logistics"
  schemaVersion: 1
  revision: number
  returns: LegacyReturn[]
  shipments: LegacyShipment[]
}

const emptyState = (): LogisticsStateV2 => ({
  service: "logistics",
  schemaVersion: 2,
  revision: 0,
  returnReceipts: [],
  shipments: [],
  preparationDispatches: [],
})

function id(prefix: string) {
  return `${prefix}-${typeof crypto !== "undefined" && crypto.randomUUID ? crypto.randomUUID() : `${Date.now()}-${Math.random()}`}`
}

function normalized(value: string) {
  return value.trim().replace(/\s+/g, " ").toLocaleLowerCase("ru")
}

function cloneContents(items: RentalItemContentsItemDto[]) {
  return items.map((item) => ({ ...item }))
}

function normalizedContents(items: RentalItemContentsItemDto[]) {
  const totals = new Map<string, { name: string; quantity: number }>()
  items.forEach((item) => {
    const name = item.name.trim()
    if (!name || !Number.isFinite(item.quantity) || item.quantity <= 0) return
    const key = normalized(name)
    const current = totals.get(key)
    totals.set(key, {
      name: current?.name ?? name,
      quantity: (current?.quantity ?? 0) + Math.floor(item.quantity),
    })
  })
  return [...totals.values()].sort((left, right) =>
    left.name.localeCompare(right.name, "ru")
  )
}

function missingContents(
  expected: RentalItemContentsItemDto[],
  returned: RentalItemContentsItemDto[]
) {
  const returnedByName = new Map(
    normalizedContents(returned).map((item) => [normalized(item.name), item])
  )

  return normalizedContents(expected)
    .map((item) => ({
      name: item.name,
      quantity: Math.max(
        0,
        item.quantity -
          (returnedByName.get(normalized(item.name))?.quantity ?? 0)
      ),
    }))
    .filter((item) => item.quantity > 0)
}

export function createReturnReplacementEstimateLines(
  item: Pick<
    ReturnReceiptItemDto,
    "id" | "expectedContents" | "returnedContents"
  >
): { lines: RepairEstimateLineDto[]; warnings: string[] } {
  const missing = missingContents(item.expectedContents, item.returnedContents)
  return {
    lines: missing.map((entry) => {
      const stableName = normalized(entry.name).replace(/[^a-zа-яё0-9]+/g, "-")
      const sourceLineKey = `return:${item.id}:replacement:${stableName}`
      return {
        id: sourceLineKey,
        sourceLineKey,
        lineType: "MATERIAL",
        description: `Замена: ${entry.name}`,
        lineComment: "Не возвращено по фактическому осмотру",
        unit: "шт.",
        quantity: entry.quantity,
        unitPrice: "0.00",
        lineTotal: "0.00",
        catalogSnapshot: null,
      }
    }),
    warnings: missing.map(
      (entry) =>
        `«${entry.name}» не сопоставлена с ценовым каталогом: цена установлена 0`
    ),
  }
}

function returnedContentsForInput(
  input: {
    contentsByRentalItemId?: Record<string, RentalItemContentsItemDto[]>
  },
  rentalItem: RentalItemDto
) {
  const provided = input.contentsByRentalItemId?.[rentalItem.id]
  return normalizedContents(provided ?? rentalItem.contentsItems)
}

export function computeShipmentContentsChanges(
  before: RentalItemContentsItemDto[],
  planned: RentalItemContentsItemDto[]
): ShipmentContentsChangeDto[] {
  const beforeMap = new Map(
    normalizedContents(before).map((item) => [normalized(item.name), item])
  )
  const plannedMap = new Map(
    normalizedContents(planned).map((item) => [normalized(item.name), item])
  )
  const keys = [...new Set([...beforeMap.keys(), ...plannedMap.keys()])]
  return keys
    .map((key) => {
      const previous = beforeMap.get(key)
      const next = plannedMap.get(key)
      const beforeQuantity = previous?.quantity ?? 0
      const plannedQuantity = next?.quantity ?? 0
      const quantity = Math.abs(plannedQuantity - beforeQuantity)
      if (quantity === 0) return null
      const name = next?.name ?? previous!.name
      const direction =
        plannedQuantity > beforeQuantity
          ? ("BRING" as const)
          : ("TAKE" as const)
      return {
        name,
        beforeQuantity,
        plannedQuantity,
        direction,
        quantity,
        label: `${direction === "BRING" ? "Принести" : "Вынести"} ${quantity} × ${name}`,
      }
    })
    .filter((item): item is ShipmentContentsChangeDto => item !== null)
    .sort((left, right) => left.name.localeCompare(right.name, "ru"))
}

export function createShipmentPreparationTask(
  changes: ShipmentContentsChangeDto[],
  existingId?: string,
  externalTaskId?: string
): ShipmentPreparationTaskDto {
  if (changes.length === 0) throw new Error("Изменений наполнения нет")
  return {
    id: existingId ?? id("shipment-preparation"),
    externalTaskId: externalTaskId ?? crypto.randomUUID(),
    title: "Подготовка к отгрузке",
    purpose: "GENERAL_WORKERS",
    createdAt: new Date().toISOString(),
    lines: changes.map((change) => ({ ...change })),
    dispatchStatus: "DRAFT",
    boardTaskId: null,
    queueId: null,
    queueCode: null,
    dispatchError: null,
    dispatchAttemptId: null,
    dispatchAttemptedAt: null,
  }
}

export function stablePreparationExternalTaskId(value: string) {
  const words = [2166136261, 2246822519, 3266489917, 668265263]
  for (const character of value) {
    const code = character.charCodeAt(0)
    words.forEach((word, index) => {
      words[index] = Math.imul(word ^ code, 16777619) >>> 0
    })
  }
  const hex = words.map((word) => word.toString(16).padStart(8, "0")).join("")
  return `${hex.slice(0, 8)}-${hex.slice(8, 12)}-4${hex.slice(13, 16)}-a${hex.slice(17, 20)}-${hex.slice(20, 32)}`
}

function assertCalendarDate(value: string, label: string) {
  const match = /^(\d{4})-(\d{2})-(\d{2})$/.exec(value)
  const parsed = match
    ? new Date(
        Date.UTC(Number(match[1]), Number(match[2]) - 1, Number(match[3]))
      )
    : null
  if (
    !match ||
    !parsed ||
    parsed.getUTCFullYear() !== Number(match[1]) ||
    parsed.getUTCMonth() !== Number(match[2]) - 1 ||
    parsed.getUTCDate() !== Number(match[3])
  ) {
    throw new Error(`Укажите корректную дату: ${label}`)
  }
}

function migrateLegacy(state: LegacyState): LogisticsStateV2 {
  return {
    service: "logistics",
    schemaVersion: 2,
    revision: state.revision,
    returnReceipts: state.returns.map((task) => ({
      id: `receipt-${task.id}`,
      version: task.version,
      warehouseId: task.warehouseId,
      fromParty: task.fromParty,
      driverId: null,
      driverName: null,
      shipmentDate: null,
      returnDate: task.returnDate,
      receptionMethod: "WAREHOUSE_INSPECTION",
      createdAt: task.createdAt,
      createdBy: task.createdBy,
      receiverName: null,
      updatedAt: null,
      updatedBy: null,
      items: [
        {
          id: task.id,
          version: task.version,
          rentalItemId: task.rentalItemId,
          cabinNumber: task.cabinNumber,
          selectionSource: "CLIENT_LIST",
          originalTenant: task.fromParty,
          intakeMode: "KNOWN_CABIN",
          passportSnapshot: null,
          previousContents: [],
          expectedContentsSource: "UNKNOWN",
          expectedContentsEditedReason: null,
          conflicts: [],
          conflictResolutions: [],
          intakeHistory: [],
          furnitureDispositions: [],
          contentsMode: "LEGACY_QUARANTINE",
          expectedContents: [],
          returnedContents: [],
          technicalState: task.status,
          acceptedAt: task.acceptedAt,
          media: task.media ?? [],
          sourceEstimateId: task.sourceEstimateId,
          pendingEstimateId: task.pendingEstimateId ?? null,
          estimateClaimId: task.estimateClaimId ?? null,
          estimateClaimedAt: task.estimateClaimedAt ?? null,
        },
      ],
    })),
    shipments: state.shipments.map((shipment) => ({
      ...shipment,
      status: "SHIPPED",
      error: null,
      items: shipment.items.map((item) => {
        const contents = cloneContents(item.contentsItems ?? [])
        return {
          rentalItemId: item.rentalItemId,
          cabinNumber: item.cabinNumber,
          contentsBefore: contents,
          contentsPlanned: cloneContents(contents),
          changes: [],
          preparationTask: null,
        }
      }),
    })),
    preparationDispatches: [],
  }
}

function parseState(raw: string | null): LogisticsStateV2 | null {
  if (!raw) return null
  try {
    const parsed = JSON.parse(raw) as Partial<LogisticsStateV2>
    if (
      parsed.service === "logistics" &&
      parsed.schemaVersion === 2 &&
      Array.isArray(parsed.returnReceipts) &&
      Array.isArray(parsed.shipments)
    ) {
      return {
        ...emptyState(),
        ...parsed,
        returnReceipts: parsed.returnReceipts.map((receipt) => ({
          ...receipt,
          driverId: receipt.driverId ?? null,
          driverName: receipt.driverName ?? null,
          shipmentDate: receipt.shipmentDate ?? null,
          receiverName: receipt.receiverName ?? null,
          updatedAt: receipt.updatedAt ?? null,
          updatedBy: receipt.updatedBy ?? null,
        })),
        shipments: parsed.shipments.map((shipment) => ({
          ...shipment,
          error: shipment.error ?? null,
          items: shipment.items.map((item) => ({
            ...item,
            preparationTask: item.preparationTask
              ? {
                  ...item.preparationTask,
                  dispatchAttemptId:
                    item.preparationTask.dispatchAttemptId ?? null,
                  dispatchAttemptedAt:
                    item.preparationTask.dispatchAttemptedAt ?? null,
                  dispatchError: item.preparationTask.dispatchError ?? null,
                }
              : null,
          })),
        })),
        preparationDispatches: Array.isArray(parsed.preparationDispatches)
          ? parsed.preparationDispatches.map((entry) => ({
              ...entry,
              dispatchAttemptId: entry.dispatchAttemptId ?? null,
            }))
          : [],
      } as LogisticsStateV2
    }
  } catch {
    return null
  }
  return null
}

function readState(): LogisticsStateV2 {
  if (typeof window === "undefined") return emptyState()
  const current = parseState(window.localStorage.getItem(LOGISTICS_STORAGE_KEY))
  if (current) return current
  try {
    const legacy = JSON.parse(
      window.localStorage.getItem(LEGACY_LOGISTICS_STORAGE_KEY) ?? "null"
    ) as LegacyState | null
    if (
      legacy?.service === "logistics" &&
      legacy.schemaVersion === 1 &&
      Array.isArray(legacy.returns) &&
      Array.isArray(legacy.shipments)
    ) {
      const migrated = migrateLegacy(legacy)
      writeState(migrated)
      return { ...migrated, revision: migrated.revision + 1 }
    }
  } catch {
    // Fail closed to a clean mock envelope.
  }
  return emptyState()
}

function writeState(state: LogisticsStateV2) {
  if (typeof window === "undefined") return
  window.localStorage.setItem(
    LOGISTICS_STORAGE_KEY,
    JSON.stringify({ ...state, schemaVersion: 2, revision: state.revision + 1 })
  )
  window.dispatchEvent(new Event(LOGISTICS_UPDATED_EVENT))
}

function restoreLogisticsStateExact(
  before: LogisticsStateV2,
  after: LogisticsStateV2
) {
  const current = readState()
  const expected = { ...after, revision: after.revision + 1 }
  if (JSON.stringify(current) !== JSON.stringify(expected)) {
    throw new Error("Реестр возвратов изменён после сохранения")
  }
  writeState({ ...before, revision: current.revision })
}

function restoreRentalItemsExact(
  before: RentalItemDto[],
  after: RentalItemDto[],
  changedIds: Set<string>
) {
  const current = readRentalItems()
  writeRentalItems(
    current.map((item) => {
      if (!changedIds.has(item.id)) return item
      const beforeItem = before.find((candidate) => candidate.id === item.id)
      const afterItem = after.find((candidate) => candidate.id === item.id)
      return beforeItem &&
        afterItem &&
        JSON.stringify(item) === JSON.stringify(afterItem)
        ? beforeItem
        : item
    })
  )
}

function normalizeReturnItem(item: ReturnReceiptItemDto): ReturnReceiptItemDto {
  const compatibleItem: ReturnReceiptItemDto = {
    ...item,
    selectionSource: item.selectionSource ?? "CLIENT_LIST",
    originalTenant: item.originalTenant ?? null,
    intakeMode: item.intakeMode ?? "KNOWN_CABIN",
    passportSnapshot: item.passportSnapshot ?? null,
    previousContents: cloneContents(item.previousContents ?? []),
    expectedContentsSource: item.expectedContentsSource ?? "UNKNOWN",
    expectedContentsEditedReason: item.expectedContentsEditedReason ?? null,
    conflicts: item.conflicts ?? [],
    conflictResolutions: item.conflictResolutions ?? [],
    intakeHistory: item.intakeHistory ?? [],
    furnitureDispositions: (item.furnitureDispositions ?? []).map((entry) => ({
      ...entry,
      remainingQuantity:
        entry.remainingQuantity ??
        (entry.status === "RESOLVED" ? 0 : entry.quantity),
      allocations: (entry.allocations ?? []).map((allocation) => ({
        ...allocation,
        status: allocation.status ?? "APPLIED",
      })),
    })),
    contentsMode: item.contentsMode ?? "LEGACY_QUARANTINE",
    expectedContents: cloneContents(item.expectedContents ?? []),
    returnedContents: cloneContents(item.returnedContents ?? []),
  }
  const claimedAt = compatibleItem.estimateClaimedAt
    ? Date.parse(compatibleItem.estimateClaimedAt)
    : Number.NaN
  const expired =
    compatibleItem.technicalState === "ESTIMATE_IN_PROGRESS" &&
    (!Number.isFinite(claimedAt) ||
      Date.now() - claimedAt >= RETURN_ESTIMATE_CLAIM_TTL_MS)
  return expired
    ? {
        ...compatibleItem,
        technicalState: "PENDING_INSPECTION",
        estimateClaimId: null,
        estimateClaimedAt: null,
      }
    : compatibleItem
}

function locateReturnItem(
  state: LogisticsStateV2,
  warehouseId: string,
  itemId: string
) {
  const receipt = state.returnReceipts.find(
    (entry) =>
      entry.warehouseId === warehouseId &&
      entry.items.some((item) => item.id === itemId)
  )
  const item = receipt?.items.find((entry) => entry.id === itemId)
  return receipt && item ? { receipt, item: normalizeReturnItem(item) } : null
}

function toTask(
  receipt: ReturnReceiptDto,
  item: ReturnReceiptItemDto
): ReturnTaskDto {
  return {
    ...item,
    receiptId: receipt.id,
    warehouseId: receipt.warehouseId,
    fromParty: receipt.fromParty,
    driverId: receipt.driverId,
    driverName: receipt.driverName,
    shipmentDate: receipt.shipmentDate ?? null,
    returnDate: receipt.returnDate,
    createdAt: receipt.createdAt,
    createdBy: receipt.createdBy,
    receiverName: receipt.receiverName ?? null,
    status: item.technicalState,
  }
}

function replaceReturnItem(
  state: LogisticsStateV2,
  receiptId: string,
  saved: ReturnReceiptItemDto
) {
  return {
    ...state,
    returnReceipts: state.returnReceipts.map((receipt) =>
      receipt.id === receiptId
        ? {
            ...receipt,
            version: receipt.version + 1,
            items: receipt.items.map((item) =>
              item.id === saved.id ? saved : item
            ),
          }
        : receipt
    ),
  }
}

export async function listReturnReceipts(
  warehouseId: string
): Promise<ReturnReceiptViewDto[]> {
  const rentals = readRentalItems()
  return readState()
    .returnReceipts.filter((receipt) => receipt.warehouseId === warehouseId)
    .map((receipt) => ({
      ...receipt,
      items: receipt.items.map((raw) => {
        const item = normalizeReturnItem(raw)
        const rental = rentals.find(
          (candidate) =>
            candidate.id === item.rentalItemId &&
            candidate.warehouseId === warehouseId
        )
        return {
          ...item,
          currentStatus: rental?.status ?? "AFTER_RENT",
          hasUnresolvedEquipmentDisposition:
            hasUnresolvedReturnEquipmentDispositionForRentalItem(
              item.rentalItemId
            ),
          hasEquipmentDispositionHistory:
            hasReturnEquipmentDispositionHistoryForReturnItem(item.id),
        }
      }),
    }))
}

export async function getReturnTask(warehouseId: string, returnTaskId: string) {
  const found = locateReturnItem(readState(), warehouseId, returnTaskId)
  return found ? toTask(found.receipt, found.item) : null
}

export async function isLinkedReturnEstimate(
  warehouseId: string,
  rentalItemId: string,
  estimateId: string
) {
  return readState().returnReceipts.some(
    (receipt) =>
      receipt.warehouseId === warehouseId &&
      receipt.items.some(
        (item) =>
          item.rentalItemId === rentalItemId &&
          (item.sourceEstimateId === estimateId ||
            item.pendingEstimateId === estimateId)
      )
  )
}

export async function listCompanies(warehouseId: string) {
  const state = readState()
  const values = [
    ...readRentalItems()
      .filter((item) => item.warehouseId === warehouseId)
      .map((item) => item.tenant),
    ...state.returnReceipts
      .filter((item) => item.warehouseId === warehouseId)
      .map((item) => item.fromParty),
    ...state.shipments
      .filter((item) => item.warehouseId === warehouseId)
      .map((item) => item.company),
  ].filter((value): value is string => Boolean(value?.trim()))
  return [
    ...new Map(
      values.map((value) => [normalized(value), value.trim()])
    ).values(),
  ].sort((left, right) => left.localeCompare(right, "ru"))
}

export async function listReturnCandidates(
  warehouseId: string,
  party?: string
) {
  return readRentalItems().filter(
    (item) =>
      item.warehouseId === warehouseId &&
      item.status === "RENTED" &&
      Boolean(item.tenant) &&
      (!party || normalized(item.tenant!) === normalized(party))
  )
}

export async function listReturnEditCandidates(
  warehouseId: string,
  receiptId: string
) {
  const receipt = readState().returnReceipts.find(
    (entry) => entry.id === receiptId && entry.warehouseId === warehouseId
  )
  const currentIds = new Set(
    receipt?.items.map((item) => item.rentalItemId) ?? []
  )
  return readRentalItems().filter(
    (item) =>
      item.warehouseId === warehouseId &&
      (item.status === "RENTED" || currentIds.has(item.id))
  )
}

export async function listShipmentCandidates(
  warehouseId: string,
  company: string
): Promise<ShipmentCandidateDto[]> {
  const party = normalized(company)
  return readRentalItems()
    .filter((item) => {
      if (item.warehouseId !== warehouseId) return false
      if (hasUnresolvedReturnEquipmentDispositionForRentalItem(item.id)) {
        return false
      }
      if (item.status === "FREE") return true
      if (item.status !== "BOOKED" && item.status !== "RESERVED") return false
      return Boolean(party && item.tenant && normalized(item.tenant) === party)
    })
    .map((item) => ({
      item: {
        id: item.id,
        number: item.number,
        status: item.status,
        tenant: item.tenant,
        contentsItems: cloneContents(item.contentsItems),
      },
      reservedForCompany:
        (item.status === "BOOKED" || item.status === "RESERVED") &&
        Boolean(party && item.tenant && normalized(item.tenant) === party),
    }))
    .sort(
      (left, right) =>
        Number(right.reservedForCompany) - Number(left.reservedForCompany) ||
        left.item.number.localeCompare(right.item.number, "ru")
    )
}

export async function listShipmentEquipment(warehouseId: string) {
  const items = await getEquipmentItems({ warehouseId })
  return items
    .map((item) => ({
      name: item.name,
      availableQuantity: item.stockQuantity,
    }))
    .sort((left, right) => left.name.localeCompare(right.name, "ru"))
}

export async function listReturnEquipmentCatalog(warehouseId: string) {
  const items = await getEquipmentItems({ warehouseId })
  return items
    .map((item) => ({
      name: item.name,
      availableQuantity: item.totalQuantity,
    }))
    .sort((left, right) => left.name.localeCompare(right.name, "ru"))
}

async function resolveReturnDriver(input: {
  warehouseId: string
  accessToken?: string | null
  driverId?: string | null
  driverName?: string | null
}) {
  const driverId = input.driverId?.trim() || null
  const driverName = input.driverName?.trim() || null
  if (!driverId && !driverName) return { driverId: null, driverName: null }
  if (!driverId || !driverName) throw new Error("Выберите водителя")

  const groups = await listRepairWorkerGroups(
    {
      warehouseId: input.warehouseId,
      queueCode: null,
      routeQueueKind: "MOVEMENT",
      purpose: "DRIVER_DIRECTORY",
    },
    input.accessToken ?? undefined
  )
  const driver = Array.from(
    new Map(
      groups
        .filter((group) => group.active)
        .flatMap((group) => group.members)
        .map((member) => [member.id, member])
    ).values()
  ).find((member) => member.id === driverId)

  if (!driver || driver.name !== driverName)
    throw new Error("Выбранный водитель больше недоступен")

  return { driverId: driver.id, driverName: driver.name }
}

const IMPORT_OVERRIDE_STATUSES = new Set([
  "FREE",
  "WAREHOUSE",
  "OWN_NEEDS",
  "USED_SALE",
])

function passportForRentalItem(item: RentalItemDto): ReturnPassportSnapshotDto {
  return {
    type: item.type,
    dimensions: item.dimensions,
    finishing: item.finishing,
    category: item.category,
    characteristics: (item.characteristics ?? "")
      .split(",")
      .map((value) => value.trim())
      .filter(Boolean),
    linoleum: item.linoleum,
  }
}

function normalizePassport(input: {
  type: RentalItemCreationType
  dimensions: string
  finishing: RentalItemFinishing
  category: string
  characteristics: RentalItemCharacteristic[]
  linoleum: boolean
}): ReturnPassportSnapshotDto {
  return {
    type: input.type,
    dimensions: input.dimensions,
    finishing: input.finishing,
    category: input.category.trim() || null,
    characteristics: [
      ...new Set(input.characteristics.map((value) => value.trim())),
    ]
      .filter(Boolean)
      .sort((left, right) => left.localeCompare(right, "ru")),
    linoleum: input.linoleum,
  }
}

function passportEquals(
  left: ReturnPassportSnapshotDto,
  right: ReturnPassportSnapshotDto
) {
  return (
    JSON.stringify({
      ...left,
      characteristics: [...left.characteristics].sort(),
    }) ===
    JSON.stringify({
      ...right,
      characteristics: [...right.characteristics].sort(),
    })
  )
}

function contentsEqual(
  left: RentalItemContentsItemDto[],
  right: RentalItemContentsItemDto[]
) {
  return (
    JSON.stringify(normalizedContents(left)) ===
    JSON.stringify(normalizedContents(right))
  )
}

function quantityFor(items: RentalItemContentsItemDto[], name: string) {
  return (
    normalizedContents(items).find(
      (item) => normalized(item.name) === normalized(name)
    )?.quantity ?? 0
  )
}

function buildFurnitureDispositions(input: {
  includePrevious: boolean
  previous: RentalItemContentsItemDto[]
  expected: RentalItemContentsItemDto[]
  actual: RentalItemContentsItemDto[]
}) {
  const previous = input.includePrevious
    ? normalizedContents(input.previous)
        .map((item) => ({
          item,
          quantity: Math.max(
            0,
            item.quantity - quantityFor(input.actual, item.name)
          ),
        }))
        .filter((entry) => entry.quantity > 0)
        .map((entry) => ({
          id: id("return-furniture"),
          name: entry.item.name,
          quantity: entry.quantity,
          remainingQuantity: entry.quantity,
          origin: "PREVIOUS_SNAPSHOT" as const,
          status: "PENDING" as const,
          action: null,
          targetRentalItemId: null,
          reason: null,
          resolvedAt: null,
          resolvedBy: null,
          allocations: [],
        }))
    : []
  const extras = normalizedContents(input.actual)
    .map((item) => ({
      item,
      quantity: Math.max(
        0,
        item.quantity - quantityFor(input.expected, item.name)
      ),
    }))
    .filter((entry) => entry.quantity > 0)
    .map((entry) => ({
      id: id("return-furniture"),
      name: entry.item.name,
      quantity: entry.quantity,
      remainingQuantity: entry.quantity,
      origin: "EXTRA_FACTUAL" as const,
      status: "PENDING" as const,
      action: null,
      targetRentalItemId: null,
      reason: null,
      resolvedAt: null,
      resolvedBy: null,
      allocations: [],
    }))
  return [...previous, ...extras]
}

async function uploadIntakeMedia(photos: RentalItemCreationPhoto[]) {
  const uploads: PendingEstimateMediaUpload[] = []
  for (const photo of photos) {
    const response = await fetch(photo.sourceDataUrl)
    const blob = await response.blob()
    uploads.push({
      id: photo.id,
      file: new File([blob], photo.name, {
        type: blob.type || "image/jpeg",
      }),
      previewUrl: photo.sourceDataUrl,
      rotationDegrees: photo.rotation,
    })
  }
  return uploads.length ? mediaClient.upload(uploads) : []
}

export type CreateImportedReturnIntakeInput = {
  commandId: string
  warehouseId: string
  accessToken?: string | null
  number: string
  fromParty: string
  shipmentDate: string
  returnDate: string
  driverId: string | null
  driverName: string | null
  createdBy: string
  passport: {
    type: RentalItemCreationType
    dimensions: string
    finishing: RentalItemFinishing
    category: string
    characteristics: RentalItemCharacteristic[]
    linoleum: boolean
  }
  expectedContents: RentalItemContentsItemDto[]
  returnedContents: RentalItemContentsItemDto[]
  expectedContentsEditedReason: string | null
  overrideExisting: boolean
  passportChangeConfirmed: boolean
  photos: RentalItemCreationPhoto[]
}

export async function getImportedReturnIntakePreview(input: {
  warehouseId: string
  number: string
}) {
  const lookup = await lookupRentalItemByNumber(input)
  const item = lookup.item
  if (!item)
    return {
      kind: "NOT_FOUND" as const,
      item: null,
      expectedContents: [],
      expectedContentsSource: "MANUAL" as const,
    }
  const latestShipment = [...readState().shipments]
    .filter((shipment) => shipment.status === "SHIPPED")
    .sort((left, right) => right.createdAt.localeCompare(left.createdAt))
    .flatMap((shipment) => shipment.items)
    .find((entry) => entry.rentalItemId === item.id)
  return {
    kind: lookup.kind,
    item,
    expectedContents: cloneContents(
      latestShipment?.contentsPlanned ?? item.contentsItems
    ),
    expectedContentsSource: latestShipment
      ? ("LATEST_SHIPMENT" as const)
      : ("CURRENT_CABIN" as const),
  }
}

/**
 * One recoverable browser command for an imported cabin and its intake receipt.
 * It deliberately writes no cabin before all conflicts and snapshots are known.
 */
export async function createImportedReturnIntake(
  input: CreateImportedReturnIntakeInput
) {
  if (!input.commandId.trim())
    throw new Error("Не задан идентификатор операции")
  const priorAttempt = readReturnIntakeJournal().find(
    (entry) => entry.commandId === input.commandId
  )
  if (
    priorAttempt &&
    (priorAttempt.status === "APPLIED" || priorAttempt.status === "CONFLICT") &&
    priorAttempt.receiptId
  ) {
    const receipt = readState().returnReceipts.find(
      (entry) => entry.id === priorAttempt.receiptId
    )
    if (receipt)
      return {
        receipt,
        kind:
          priorAttempt.status === "CONFLICT"
            ? ("CONFLICT" as const)
            : (receipt.items[0]?.intakeMode ?? "KNOWN_CABIN"),
      }
  }
  writeReturnIntakeAttempt({
    commandId: input.commandId,
    status: "PENDING",
    receiptId: priorAttempt?.receiptId ?? null,
    rentalItemId: priorAttempt?.rentalItemId ?? null,
    updatedAt: new Date().toISOString(),
    error: null,
  })
  let uploaded: RepairEstimateMediaRefDto[] = []
  try {
    uploaded = await uploadIntakeMedia(input.photos)
    const result = await runRentalItemMutation(async () => {
      const number = input.number.trim().replace(/\s+/g, " ")
      const fromParty = input.fromParty.trim()
      if (!number) throw new Error("Укажите номер бытовки")
      if (!fromParty) throw new Error("Укажите, от кого приехала бытовка")
      assertCalendarDate(input.shipmentDate, "историческая отгрузка")
      assertCalendarDate(input.returnDate, "возврат")
      const driver = await resolveReturnDriver(input)
      const rentals = readRentalItems()
      const state = readState()
      const canonicalNumber = canonicalizeRentalItemNumber(number)
      const existing = rentals.find(
        (item) => canonicalizeRentalItemNumber(item.number) === canonicalNumber
      )
      if (existing?.status === "WRITTEN_OFF")
        throw new Error(
          "Номер принадлежит списанной бытовке. Восстановление запрещено"
        )
      const tenantMismatch = Boolean(
        existing?.warehouseId === input.warehouseId &&
        existing.status === "RENTED" &&
        existing.tenant &&
        normalized(existing.tenant) !== normalized(fromParty)
      )

      const now = new Date().toISOString()
      const otherWarehouse =
        existing && existing.warehouseId !== input.warehouseId
      if (
        existing &&
        !otherWarehouse &&
        hasActiveWarehouseTransferLowLevel(existing.id)
      ) {
        throw new Error(
          "Бытовка участвует в межскладском перемещении. Сначала завершите или отмените перемещение"
        )
      }
      const conflict = otherWarehouse
        ? {
            code: "OTHER_WAREHOUSE" as const,
            message: `Бытовка ${existing.number} числится на складе ${existing.warehouseId}`,
            conflictingWarehouseId: existing.warehouseId,
            createdAt: now,
          }
        : tenantMismatch
          ? {
              code: "TENANT_MISMATCH" as const,
              message: `Бытовка числится в аренде у «${existing!.tenant}», но возвращена от «${fromParty}»`,
              conflictingWarehouseId: null,
              createdAt: now,
            }
          : null

      if (
        existing &&
        !otherWarehouse &&
        existing.status !== "RENTED" &&
        !IMPORT_OVERRIDE_STATUSES.has(existing.status)
      ) {
        throw new Error(
          `Бытовка участвует в активном процессе «${existing.status}». Сначала завершите его`
        )
      }
      if (
        existing &&
        !otherWarehouse &&
        existing.status !== "RENTED" &&
        !input.overrideExisting
      ) {
        throw new Error(
          "Подтвердите перезапись существующей бытовки на этом складе"
        )
      }

      const submittedPassport = normalizePassport(input.passport)
      const passportChanged = Boolean(
        existing &&
        !passportEquals(passportForRentalItem(existing), submittedPassport)
      )
      if (passportChanged && !input.passportChangeConfirmed)
        throw new Error("Подтвердите изменение паспортных данных бытовки")

      const latestShipment = [...state.shipments]
        .filter((shipment) => shipment.status === "SHIPPED")
        .sort((left, right) => right.createdAt.localeCompare(left.createdAt))
        .flatMap((shipment) => shipment.items)
        .find((item) => item.rentalItemId === existing?.id)
      const sourceExpected =
        latestShipment?.contentsPlanned ?? existing?.contentsItems ?? []
      const source = latestShipment
        ? ("LATEST_SHIPMENT" as const)
        : existing
          ? ("CURRENT_CABIN" as const)
          : ("MANUAL" as const)
      const expectedContents = normalizedContents(input.expectedContents)
      if (
        source !== "MANUAL" &&
        !contentsEqual(sourceExpected, expectedContents) &&
        !input.expectedContentsEditedReason?.trim()
      ) {
        throw new Error("Укажите причину изменения ожидаемого наполнения")
      }

      const rentalItemId = existing?.id ?? id(input.warehouseId)
      const previousContents = cloneContents(existing?.contentsItems ?? [])
      const mode = existing
        ? existing.status === "RENTED"
          ? ("KNOWN_CABIN" as const)
          : ("OVERRIDE_EXISTING" as const)
        : ("NEW_CABIN" as const)
      const media = mediaClient.dehydrate(uploaded)
      const returnItem: ReturnReceiptItemDto = {
        id: id("return-item"),
        version: 1,
        rentalItemId,
        cabinNumber: existing?.number ?? number,
        selectionSource: "MANUAL",
        originalTenant: existing?.tenant ?? fromParty,
        intakeMode: mode,
        passportSnapshot: submittedPassport,
        previousContents,
        expectedContentsSource: source,
        expectedContentsEditedReason:
          input.expectedContentsEditedReason?.trim() || null,
        conflicts: conflict ? [conflict] : [],
        conflictResolutions: [],
        intakeHistory: [
          {
            id: id("return-audit"),
            type: conflict ? "CONFLICT_REGISTERED" : "INTAKE_REGISTERED",
            createdAt: now,
            createdBy: input.createdBy,
            reason: conflict?.message ?? null,
          },
          ...(passportChanged
            ? [
                {
                  id: id("return-audit"),
                  type: "PASSPORT_CHANGED" as const,
                  createdAt: now,
                  createdBy: input.createdBy,
                  reason: "Изменение подтверждено при возврате",
                },
              ]
            : []),
          ...(!contentsEqual(sourceExpected, expectedContents) &&
          source !== "MANUAL"
            ? [
                {
                  id: id("return-audit"),
                  type: "EXPECTED_CONTENTS_CORRECTED" as const,
                  createdAt: now,
                  createdBy: input.createdBy,
                  reason: input.expectedContentsEditedReason!.trim(),
                },
              ]
            : []),
        ],
        furnitureDispositions: buildFurnitureDispositions({
          includePrevious: mode === "OVERRIDE_EXISTING",
          previous: previousContents,
          expected: expectedContents,
          actual: input.returnedContents,
        }),
        contentsMode: "FACTUAL",
        expectedContents,
        returnedContents: normalizedContents(input.returnedContents),
        technicalState: conflict ? "CONFLICT" : "PENDING_INSPECTION",
        acceptedAt: null,
        media,
        sourceEstimateId: null,
        pendingEstimateId: null,
        estimateClaimId: null,
        estimateClaimedAt: null,
      }
      const receipt: ReturnReceiptDto = {
        id: id("return-receipt"),
        version: 1,
        warehouseId: input.warehouseId,
        fromParty,
        driverId: driver.driverId,
        driverName: driver.driverName,
        shipmentDate: input.shipmentDate,
        returnDate: input.returnDate,
        receptionMethod: "WAREHOUSE_INSPECTION",
        createdAt: now,
        createdBy: input.createdBy,
        receiverName: input.createdBy,
        updatedAt: null,
        updatedBy: null,
        items: [returnItem],
      }

      const nextState = {
        ...state,
        returnReceipts: [receipt, ...state.returnReceipts],
      }
      if (conflict) {
        writeState(nextState)
        return { receipt, kind: "CONFLICT" as const }
      }

      const nextRental: RentalItemDto = {
        ...(existing ?? {}),
        id: rentalItemId,
        version: (existing?.version ?? -1) + 1,
        warehouseId: input.warehouseId,
        locationNodeId: null,
        number: existing?.number ?? number,
        type: submittedPassport.type,
        dimensions: submittedPassport.dimensions,
        finishing: submittedPassport.finishing,
        category: submittedPassport.category,
        characteristics: submittedPassport.characteristics.join(", ") || null,
        linoleum: submittedPassport.linoleum,
        status: "AFTER_RENT",
        comment: existing?.comment ?? null,
        hasPhotos: media.length > 0 || Boolean(existing?.hasPhotos),
        photoCount: (existing?.photoCount ?? 0) + media.length,
        mainPhotoUrl:
          existing?.mainPhotoUrl ?? media[0]?.variants.small.url ?? null,
        previewPhotoUrls: [
          ...(existing?.previewPhotoUrls ?? []),
          ...media.map((photo) => photo.variants.small.url),
        ],
        contents: input.returnedContents.length
          ? normalizedContents(input.returnedContents)
              .map((item) => `${item.name}: ${item.quantity}`)
              .join(", ")
          : null,
        contentsItems: normalizedContents(input.returnedContents),
        shipmentDate: input.shipmentDate,
        tenant: fromParty,
        price: existing?.price ?? null,
      }
      const nextRentals = existing
        ? rentals.map((item) => (item.id === existing.id ? nextRental : item))
        : [nextRental, ...rentals]
      try {
        writeRentalItems(nextRentals)
        writeState(nextState)
        return { receipt, kind: mode }
      } catch (error) {
        writeRentalItems(rentals)
        try {
          restoreLogisticsStateExact(state, nextState)
        } catch {
          // State was not committed; the cabin rollback above is authoritative.
        }
        throw error
      }
    })
    writeReturnIntakeAttempt({
      commandId: input.commandId,
      status: result.kind === "CONFLICT" ? "CONFLICT" : "APPLIED",
      receiptId: result.receipt.id,
      rentalItemId: result.receipt.items[0]?.rentalItemId ?? null,
      updatedAt: new Date().toISOString(),
      error: null,
    })
    return result
  } catch (error) {
    await mediaClient.discard(uploaded.map((item) => item.id))
    writeReturnIntakeAttempt({
      commandId: input.commandId,
      status: "FAILED",
      receiptId: priorAttempt?.receiptId ?? null,
      rentalItemId: priorAttempt?.rentalItemId ?? null,
      updatedAt: new Date().toISOString(),
      error: error instanceof Error ? error.message : "Неизвестная ошибка",
    })
    throw error
  }
}

export async function createReturnReceipt(input: {
  warehouseId: string
  accessToken?: string | null
  clientRentalItemIds: string[]
  manualRentalItemIds: string[]
  fromParty: string
  driverId?: string | null
  driverName?: string | null
  returnDate: string
  contentsByRentalItemId?: Record<string, RentalItemContentsItemDto[]>
  createdBy: string
}) {
  return runRentalItemMutation(async () => {
    if (!input.fromParty.trim())
      throw new Error("Укажите, от кого приехали бытовки")
    assertCalendarDate(input.returnDate, "возврат")
    const clientIds = [...new Set(input.clientRentalItemIds)]
    const manualIds = [
      ...new Set(
        input.manualRentalItemIds.filter(
          (rentalItemId) => !clientIds.includes(rentalItemId)
        )
      ),
    ]
    const ids = [...clientIds, ...manualIds]
    if (ids.length === 0) throw new Error("Выберите хотя бы одну бытовку")
    const rentals = readRentalItems()
    const selected = ids.map((rentalItemId) =>
      rentals.find(
        (item) =>
          item.id === rentalItemId && item.warehouseId === input.warehouseId
      )
    )
    if (selected.some((item) => !item))
      throw new Error("Одна из бытовок не найдена")
    const cabins = selected as RentalItemDto[]
    if (cabins.some((item) => item.status !== "RENTED"))
      throw new Error("Все бытовки должны находиться в аренде")
    const clientCabins = cabins.filter((item) => clientIds.includes(item.id))
    if (
      clientCabins.some(
        (item) =>
          !item.tenant ||
          normalized(item.tenant) !== normalized(input.fromParty)
      )
    )
      throw new Error(
        "Бытовка из списка клиента больше не принадлежит выбранному клиенту"
      )
    const state = readState()
    const alreadyPending = new Set(
      state.returnReceipts.flatMap((receipt) =>
        receipt.items
          .filter(
            (item) =>
              normalizeReturnItem(item).technicalState === "PENDING_INSPECTION"
          )
          .map((item) => item.rentalItemId)
      )
    )
    if (cabins.some((item) => alreadyPending.has(item.id))) {
      throw new Error("Для одной из бытовок уже создан возврат")
    }
    const driver = await resolveReturnDriver(input)
    const factualContents = input.contentsByRentalItemId !== undefined
    const now = new Date().toISOString()
    const receipt: ReturnReceiptDto = {
      id: id("return-receipt"),
      version: 1,
      warehouseId: input.warehouseId,
      fromParty: input.fromParty.trim(),
      driverId: driver.driverId,
      driverName: driver.driverName,
      shipmentDate: null,
      returnDate: input.returnDate,
      receptionMethod: "WAREHOUSE_INSPECTION",
      createdAt: now,
      createdBy: input.createdBy,
      receiverName: input.createdBy,
      updatedAt: null,
      updatedBy: null,
      items: cabins.map((item) => {
        const latestShipment = [...state.shipments]
          .filter((shipment) => shipment.status === "SHIPPED")
          .sort((left, right) => right.createdAt.localeCompare(left.createdAt))
          .flatMap((shipment) => shipment.items)
          .find((shipmentItem) => shipmentItem.rentalItemId === item.id)
        const expectedContents = cloneContents(
          latestShipment?.contentsPlanned ?? item.contentsItems
        )
        const returnedContents = returnedContentsForInput(input, item)
        return {
          id: id("return-item"),
          version: 1,
          rentalItemId: item.id,
          cabinNumber: item.number,
          selectionSource: manualIds.includes(item.id)
            ? "MANUAL"
            : "CLIENT_LIST",
          originalTenant: item.tenant,
          intakeMode: "KNOWN_CABIN" as const,
          passportSnapshot: {
            type: item.type,
            dimensions: item.dimensions,
            finishing: item.finishing,
            category: item.category,
            characteristics: (item.characteristics ?? "")
              .split(",")
              .map((value) => value.trim())
              .filter(Boolean),
            linoleum: item.linoleum,
          },
          previousContents: cloneContents(item.contentsItems),
          expectedContentsSource: latestShipment
            ? ("LATEST_SHIPMENT" as const)
            : ("CURRENT_CABIN" as const),
          expectedContentsEditedReason: null,
          conflicts: [],
          conflictResolutions: [],
          intakeHistory: [
            {
              id: id("return-audit"),
              type: "INTAKE_REGISTERED",
              createdAt: now,
              createdBy: input.createdBy,
              reason: null,
            },
          ],
          furnitureDispositions: factualContents
            ? buildFurnitureDispositions({
                includePrevious: false,
                previous: [],
                expected: expectedContents,
                actual: returnedContents,
              })
            : [],
          contentsMode: factualContents
            ? ("FACTUAL" as const)
            : ("LEGACY_QUARANTINE" as const),
          expectedContents,
          returnedContents,
          technicalState: "PENDING_INSPECTION",
          acceptedAt: null,
          media: [],
          sourceEstimateId: null,
          pendingEstimateId: null,
          estimateClaimId: null,
          estimateClaimedAt: null,
        }
      }),
    }
    const nextState = {
      ...state,
      returnReceipts: [receipt, ...state.returnReceipts],
    }
    const nextRentals = rentals.map((item) =>
      ids.includes(item.id) ? { ...item, status: "AFTER_RENT" as const } : item
    )
    const disposition = factualContents
      ? null
      : prepareReturnEquipmentDispositionReconciliation({
          upserts: receipt.items.map((item) => {
            const cabin = cabins.find(
              (candidate) => candidate.id === item.rentalItemId
            )!
            return {
              warehouseId: receipt.warehouseId,
              returnReceiptId: receipt.id,
              returnItemId: item.id,
              sourceRentalItemId: cabin.id,
              sourceCabinNumber: cabin.number,
              receivedAt: receipt.returnDate,
              contents: cabin.contentsItems,
            }
          }),
        })
    let stateWritten = false
    try {
      writeRentalItems(nextRentals)
      writeState(nextState)
      stateWritten = true
      disposition?.commit()
      return receipt
    } catch (error) {
      const compensationErrors: unknown[] = []
      try {
        disposition?.rollback()
      } catch (compensationError) {
        compensationErrors.push(compensationError)
      }
      if (stateWritten) {
        try {
          restoreLogisticsStateExact(state, nextState)
        } catch (compensationError) {
          compensationErrors.push(compensationError)
        }
      }
      try {
        restoreRentalItemsExact(rentals, nextRentals, new Set(ids))
      } catch (compensationError) {
        compensationErrors.push(compensationError)
      }
      if (compensationErrors.length > 0) {
        throw new AggregateError(
          [error, ...compensationErrors],
          "Не удалось полностью отменить создание возврата",
          { cause: error }
        )
      }
      throw error
    }
  })
}

function isPristineReturnItem(item: ReturnReceiptItemDto) {
  const normalizedItem = normalizeReturnItem(item)
  return (
    normalizedItem.technicalState === "PENDING_INSPECTION" &&
    normalizedItem.acceptedAt === null &&
    normalizedItem.media.length === 0 &&
    normalizedItem.sourceEstimateId === null &&
    normalizedItem.pendingEstimateId === null &&
    normalizedItem.estimateClaimId === null
  )
}

export function isReturnReceiptMembershipEditable(
  receipt: ReturnReceiptViewDto
) {
  return receipt.items.every(
    (item) =>
      isPristineReturnItem(item) &&
      item.currentStatus === "AFTER_RENT" &&
      (item.contentsMode === "FACTUAL" || !item.hasEquipmentDispositionHistory)
  )
}

export async function updateReturnReceipt(input: {
  warehouseId: string
  accessToken?: string | null
  receiptId: string
  expectedVersion: number
  clientRentalItemIds: string[]
  manualRentalItemIds: string[]
  fromParty: string
  driverId?: string | null
  driverName?: string | null
  returnDate: string
  contentsByRentalItemId?: Record<string, RentalItemContentsItemDto[]>
  updatedBy: string
}) {
  return runRentalItemMutation(async () => {
    const fromParty = input.fromParty.trim()
    if (!fromParty) throw new Error("Укажите, от кого приехали бытовки")
    assertCalendarDate(input.returnDate, "возврат")

    const state = readState()
    const receipt = state.returnReceipts.find(
      (entry) =>
        entry.id === input.receiptId && entry.warehouseId === input.warehouseId
    )
    if (!receipt || receipt.version !== input.expectedVersion) {
      throw new Error("Возврат изменился. Обновите список")
    }

    const clientIds = [...new Set(input.clientRentalItemIds)]
    const manualIds = [
      ...new Set(
        input.manualRentalItemIds.filter(
          (rentalItemId) => !clientIds.includes(rentalItemId)
        )
      ),
    ]
    const desiredIds = [...clientIds, ...manualIds]
    if (desiredIds.length === 0) {
      throw new Error("В возврате должна остаться хотя бы одна бытовка")
    }

    const currentIds = receipt.items.map((item) => item.rentalItemId)
    const addedIds = desiredIds.filter((itemId) => !currentIds.includes(itemId))
    const removedIds = currentIds.filter(
      (itemId) => !desiredIds.includes(itemId)
    )
    const membershipChanged = addedIds.length > 0 || removedIds.length > 0
    const rentals = readRentalItems()

    if (membershipChanged) {
      const allPristine = receipt.items.every((item) => {
        const rental = rentals.find(
          (candidate) =>
            candidate.id === item.rentalItemId &&
            candidate.warehouseId === input.warehouseId
        )
        return (
          isPristineReturnItem(item) &&
          rental?.status === "AFTER_RENT" &&
          (item.contentsMode === "FACTUAL" ||
            !hasReturnEquipmentDispositionHistoryForReturnItem(item.id))
        )
      })
      if (!allPristine) {
        throw new Error(
          "Состав бытовок нельзя менять после начала осмотра или создания сметы"
        )
      }
    }

    const addedRentals = addedIds.map((rentalItemId) =>
      rentals.find(
        (item) =>
          item.id === rentalItemId && item.warehouseId === input.warehouseId
      )
    )
    if (addedRentals.some((item) => !item || item.status !== "RENTED")) {
      throw new Error(
        "Одна из добавленных бытовок больше не находится в аренде"
      )
    }
    const validAddedRentals = addedRentals as RentalItemDto[]
    if (
      validAddedRentals.some(
        (item) =>
          clientIds.includes(item.id) &&
          (!item.tenant || normalized(item.tenant) !== normalized(fromParty))
      )
    ) {
      throw new Error(
        "Бытовка из списка клиента больше не принадлежит выбранному клиенту"
      )
    }

    const existingByRentalItemId = new Map(
      receipt.items.map((item) => [item.rentalItemId, item])
    )
    const driver = await resolveReturnDriver(input)
    const factualContents = input.contentsByRentalItemId !== undefined
    const now = new Date().toISOString()
    const nextItems = desiredIds.map((rentalItemId) => {
      const existing = existingByRentalItemId.get(rentalItemId)
      const rental = validAddedRentals.find((item) => item.id === rentalItemId)!

      if (existing) {
        if (!factualContents || existing.contentsMode !== "FACTUAL") {
          return existing
        }

        const currentRental = rentals.find((item) => item.id === rentalItemId)!
        const returnedContents = returnedContentsForInput(input, currentRental)
        if (
          !isPristineReturnItem(existing) &&
          JSON.stringify(normalizedContents(existing.returnedContents)) !==
            JSON.stringify(returnedContents)
        ) {
          throw new Error(
            "Фактическое наполнение нельзя менять после начала осмотра или создания сметы"
          )
        }
        return {
          ...existing,
          version: existing.version + 1,
          returnedContents,
        }
      }

      return {
        id: id("return-item"),
        version: 1,
        rentalItemId: rental.id,
        cabinNumber: rental.number,
        selectionSource: clientIds.includes(rental.id)
          ? ("CLIENT_LIST" as const)
          : ("MANUAL" as const),
        originalTenant: rental.tenant,
        intakeMode: "KNOWN_CABIN" as const,
        passportSnapshot: passportForRentalItem(rental),
        previousContents: cloneContents(rental.contentsItems),
        expectedContentsSource: "CURRENT_CABIN" as const,
        expectedContentsEditedReason: null,
        conflicts: [],
        conflictResolutions: [],
        intakeHistory: [
          {
            id: id("return-audit"),
            type: "INTAKE_REGISTERED" as const,
            createdAt: now,
            createdBy: input.updatedBy,
            reason: "Бытовка добавлена при корректировке возврата",
          },
        ],
        furnitureDispositions: [],
        contentsMode: factualContents
          ? ("FACTUAL" as const)
          : ("LEGACY_QUARANTINE" as const),
        expectedContents: cloneContents(rental.contentsItems),
        returnedContents: returnedContentsForInput(input, rental),
        technicalState: "PENDING_INSPECTION" as const,
        acceptedAt: null,
        media: [],
        sourceEstimateId: null,
        pendingEstimateId: null,
        estimateClaimId: null,
        estimateClaimedAt: null,
      }
    })
    const saved: ReturnReceiptDto = {
      ...receipt,
      version: receipt.version + 1,
      fromParty,
      driverId: driver.driverId,
      driverName: driver.driverName,
      returnDate: input.returnDate,
      updatedAt: now,
      updatedBy: input.updatedBy,
      items: nextItems,
    }
    const nextState = {
      ...state,
      returnReceipts: state.returnReceipts.map((entry) =>
        entry.id === saved.id ? saved : entry
      ),
    }
    const changedIds = new Set([...addedIds, ...removedIds])
    const nextRentals = rentals.map((item) => {
      if (addedIds.includes(item.id))
        return { ...item, status: "AFTER_RENT" as const }
      if (removedIds.includes(item.id))
        return { ...item, status: "RENTED" as const }
      return item
    })
    const legacyItems = nextItems.filter(
      (item) =>
        (item.contentsMode ?? "LEGACY_QUARANTINE") === "LEGACY_QUARANTINE"
    )
    const disposition = legacyItems.length
      ? prepareReturnEquipmentDispositionReconciliation({
          upserts: legacyItems.map((item) => {
            const cabin = rentals.find(
              (candidate) => candidate.id === item.rentalItemId
            )!
            return {
              warehouseId: saved.warehouseId,
              returnReceiptId: saved.id,
              returnItemId: item.id,
              sourceRentalItemId: cabin.id,
              sourceCabinNumber: cabin.number,
              receivedAt: saved.returnDate,
              contents: cabin.contentsItems,
            }
          }),
          removeReturnItemIds: receipt.items
            .filter(
              (item) =>
                (item.contentsMode ?? "LEGACY_QUARANTINE") ===
                  "LEGACY_QUARANTINE" && removedIds.includes(item.rentalItemId)
            )
            .map((item) => item.id),
        })
      : null
    let stateWritten = false

    try {
      if (changedIds.size > 0) writeRentalItems(nextRentals)
      writeState(nextState)
      stateWritten = true
      disposition?.commit()
      return saved
    } catch (error) {
      const compensationErrors: unknown[] = []
      try {
        disposition?.rollback()
      } catch (compensationError) {
        compensationErrors.push(compensationError)
      }
      if (stateWritten) {
        try {
          restoreLogisticsStateExact(state, nextState)
        } catch (compensationError) {
          compensationErrors.push(compensationError)
        }
      }
      if (changedIds.size > 0) {
        try {
          restoreRentalItemsExact(rentals, nextRentals, changedIds)
        } catch (compensationError) {
          compensationErrors.push(compensationError)
        }
      }
      if (compensationErrors.length > 0) {
        throw new AggregateError(
          [error, ...compensationErrors],
          "Не удалось полностью отменить редактирование возврата",
          { cause: error }
        )
      }
      throw error
    }
  })
}

async function uploadRequiredPhotos(uploads: PendingEstimateMediaUpload[]) {
  if (uploads.length < 1) throw new Error("Добавьте хотя бы одну фотографию")
  if (uploads.length > 20)
    throw new Error("Можно добавить не более 20 фотографий")
  return mediaClient.upload(uploads)
}

export async function acceptReturnUndamaged(input: {
  warehouseId: string
  returnTaskId: string
  expectedVersion: number
  uploads: PendingEstimateMediaUpload[]
}) {
  let uploaded: RepairEstimateMediaRefDto[] = []
  try {
    return await runRentalItemMutation(async () => {
      const state = readState()
      const found = locateReturnItem(
        state,
        input.warehouseId,
        input.returnTaskId
      )
      if (
        !found ||
        found.item.technicalState !== "PENDING_INSPECTION" ||
        found.item.version !== input.expectedVersion
      )
        throw new Error("Возврат изменился. Обновите список")
      if (
        found.item.contentsMode !== "FACTUAL" &&
        hasUnresolvedReturnEquipmentDispositionForRentalItem(
          found.item.rentalItemId
        )
      ) {
        throw new Error("Сначала распределите оборудование бытовки")
      }
      if (
        found.item.contentsMode === "FACTUAL" &&
        missingContents(
          found.item.expectedContents,
          found.item.returnedContents
        ).length > 0
      ) {
        throw new Error(
          "В фактическом наполнении есть отсутствующие позиции. Создайте смету для их списания"
        )
      }
      if (
        found.item.furnitureDispositions.some(
          (entry) => entry.status === "PENDING"
        )
      ) {
        throw new Error("Сначала распределите конфликтную мебель")
      }
      if (input.uploads.length === 0 && found.item.media.length === 0)
        throw new Error("Добавьте хотя бы одну фотографию")
      uploaded = input.uploads.length
        ? await uploadRequiredPhotos(input.uploads)
        : []
      const rentals = readRentalItems()
      const cabin = rentals.find(
        (item) =>
          item.id === found.item.rentalItemId &&
          item.warehouseId === input.warehouseId
      )
      if (!cabin || cabin.status !== "AFTER_RENT")
        throw new Error("Бытовка больше не ожидает осмотра")
      const saved: ReturnReceiptItemDto = {
        ...found.item,
        version: found.item.version + 1,
        technicalState: "ACCEPTED",
        acceptedAt: new Date().toISOString(),
        media: [...found.item.media, ...mediaClient.dehydrate(uploaded)],
      }
      const nextState = replaceReturnItem(state, found.receipt.id, saved)
      try {
        writeState(nextState)
        writeRentalItems(
          rentals.map((item) =>
            item.id === cabin.id
              ? {
                  ...item,
                  contentsItems:
                    found.item.contentsMode === "FACTUAL"
                      ? cloneContents(found.item.returnedContents)
                      : item.contentsItems,
                  status: "FREE",
                  tenant: null,
                  shipmentDate: null,
                }
              : item
          )
        )
        return toTask(found.receipt, saved)
      } catch (error) {
        writeState(state)
        const current = readRentalItems()
        writeRentalItems(
          current.map((candidate) => {
            const previous = rentals.find((item) => item.id === candidate.id)
            return candidate.id === cabin.id &&
              candidate.status === "FREE" &&
              candidate.tenant === null &&
              candidate.shipmentDate === null &&
              previous
              ? previous
              : candidate
          })
        )
        throw error
      }
    })
  } catch (error) {
    await mediaClient.discard(uploaded.map((item) => item.id))
    throw error
  }
}

export async function attachReturnMedia(input: {
  warehouseId: string
  returnTaskId: string
  expectedVersion: number
  uploads: PendingEstimateMediaUpload[]
}) {
  const uploaded = await uploadRequiredPhotos(input.uploads)
  try {
    return await runRentalItemMutation(async () => {
      const state = readState()
      const found = locateReturnItem(
        state,
        input.warehouseId,
        input.returnTaskId
      )
      if (
        !found ||
        found.item.technicalState !== "PENDING_INSPECTION" ||
        found.item.version !== input.expectedVersion
      )
        throw new Error("Возврат изменился. Обновите список")
      const saved = {
        ...found.item,
        version: found.item.version + 1,
        media: mediaClient.dehydrate(uploaded),
      }
      writeState(replaceReturnItem(state, found.receipt.id, saved))
      return toTask(found.receipt, saved)
    })
  } catch (error) {
    await mediaClient.discard(uploaded.map((item) => item.id))
    throw error
  }
}

async function reserveReturnFurnitureTransfer(input: {
  warehouseId: string
  returnItemId: string
  dispositionId: string
  expectedVersion: number
  quantity: number
  idempotencyKey: string
  targetRentalItemId: string
  reason: string | null
  resolvedBy: string
}) {
  return runRentalItemMutation(async () => {
    const state = readState()
    const found = locateReturnItem(state, input.warehouseId, input.returnItemId)
    if (!found) throw new Error("Возврат не найден")
    const disposition = found.item.furnitureDispositions.find(
      (entry) => entry.id === input.dispositionId
    )
    if (!disposition) throw new Error("Конфликт мебели не найден")
    const existing = disposition.allocations.find(
      (entry) => entry.idempotencyKey === input.idempotencyKey
    )
    if (existing) return toTask(found.receipt, found.item)
    if (found.item.version !== input.expectedVersion)
      throw new Error("Возврат изменился. Обновите список")
    if (
      !Number.isInteger(input.quantity) ||
      input.quantity < 1 ||
      input.quantity > disposition.remainingQuantity
    )
      throw new Error(
        `Количество должно быть от 1 до ${disposition.remainingQuantity}`
      )
    const now = new Date().toISOString()
    const saved: ReturnReceiptItemDto = {
      ...found.item,
      version: found.item.version + 1,
      furnitureDispositions: found.item.furnitureDispositions.map((entry) =>
        entry.id === disposition.id
          ? {
              ...entry,
              remainingQuantity: entry.remainingQuantity - input.quantity,
              allocations: [
                ...entry.allocations,
                {
                  id: id("return-furniture-allocation"),
                  idempotencyKey: input.idempotencyKey,
                  status: "PENDING_TASK",
                  action: "TRANSFER_TO_CABIN",
                  quantity: input.quantity,
                  targetRentalItemId: input.targetRentalItemId,
                  reason: input.reason,
                  createdAt: now,
                  createdBy: input.resolvedBy,
                  taskExternalId: input.idempotencyKey,
                },
              ],
            }
          : entry
      ),
    }
    writeState(replaceReturnItem(state, found.receipt.id, saved))
    return toTask(found.receipt, saved)
  })
}

async function reserveReturnFurnitureLedger(input: {
  warehouseId: string
  returnItemId: string
  dispositionId: string
  expectedVersion: number
  quantity: number
  idempotencyKey: string
  action: "RETURN_TO_STOCK" | "WRITE_OFF"
  reason: string | null
  resolvedBy: string
}) {
  return runRentalItemMutation(async () => {
    const state = readState()
    const found = locateReturnItem(state, input.warehouseId, input.returnItemId)
    if (!found) throw new Error("Возврат не найден")
    const disposition = found.item.furnitureDispositions.find(
      (entry) => entry.id === input.dispositionId
    )
    if (!disposition) throw new Error("Конфликт мебели не найден")
    const existing = disposition.allocations.find(
      (entry) => entry.idempotencyKey === input.idempotencyKey
    )
    if (existing) return toTask(found.receipt, found.item)
    if (found.item.version !== input.expectedVersion)
      throw new Error("Возврат изменился. Обновите список")
    if (
      !Number.isInteger(input.quantity) ||
      input.quantity < 1 ||
      input.quantity > disposition.remainingQuantity
    )
      throw new Error(
        `Количество должно быть от 1 до ${disposition.remainingQuantity}`
      )
    if (input.action === "WRITE_OFF" && !input.reason)
      throw new Error("Укажите причину списания")
    const now = new Date().toISOString()
    const saved: ReturnReceiptItemDto = {
      ...found.item,
      version: found.item.version + 1,
      furnitureDispositions: found.item.furnitureDispositions.map((entry) =>
        entry.id === disposition.id
          ? {
              ...entry,
              remainingQuantity: entry.remainingQuantity - input.quantity,
              allocations: [
                ...entry.allocations,
                {
                  id: id("return-furniture-allocation"),
                  idempotencyKey: input.idempotencyKey,
                  status: "PENDING_LEDGER",
                  action: input.action,
                  quantity: input.quantity,
                  targetRentalItemId: null,
                  reason: input.reason,
                  createdAt: now,
                  createdBy: input.resolvedBy,
                  taskExternalId: null,
                },
              ],
            }
          : entry
      ),
    }
    writeState(replaceReturnItem(state, found.receipt.id, saved))
    return toTask(found.receipt, saved)
  })
}

async function finalizeReturnFurnitureTransfer(input: {
  warehouseId: string
  returnItemId: string
  dispositionId: string
  idempotencyKey: string
}) {
  return runRentalItemMutation(async () => {
    const state = readState()
    const found = locateReturnItem(state, input.warehouseId, input.returnItemId)
    if (!found) throw new Error("Возврат не найден")
    const disposition = found.item.furnitureDispositions.find(
      (entry) => entry.id === input.dispositionId
    )
    const allocation = disposition?.allocations.find(
      (entry) => entry.idempotencyKey === input.idempotencyKey
    )
    if (!disposition || !allocation)
      throw new Error("Резерв распределения не найден")
    if (allocation.status === "APPLIED")
      return toTask(found.receipt, found.item)
    let returnedContents = found.item.returnedContents
    if (disposition.origin === "EXTRA_FACTUAL") {
      returnedContents = normalizedContents(
        returnedContents.map((entry) =>
          normalized(entry.name) === normalized(disposition.name)
            ? { ...entry, quantity: entry.quantity - allocation.quantity }
            : entry
        )
      )
    }
    const now = new Date().toISOString()
    const saved: ReturnReceiptItemDto = {
      ...found.item,
      version: found.item.version + 1,
      returnedContents,
      furnitureDispositions: found.item.furnitureDispositions.map((entry) => {
        if (entry.id !== disposition.id) return entry
        const allocations = entry.allocations.map((candidate) =>
          candidate.idempotencyKey === input.idempotencyKey
            ? { ...candidate, status: "APPLIED" as const }
            : candidate
        )
        const complete =
          entry.remainingQuantity === 0 &&
          allocations.every((candidate) => candidate.status === "APPLIED")
        return {
          ...entry,
          allocations,
          status: complete ? ("RESOLVED" as const) : ("PENDING" as const),
          resolvedAt: complete ? now : null,
          resolvedBy: complete ? allocation.createdBy : null,
        }
      }),
    }
    if (
      allocation.action === "RETURN_TO_STOCK" ||
      allocation.action === "WRITE_OFF"
    ) {
      const rentals = readRentalItems()
      writeRentalItems(
        rentals.map((item) =>
          item.id === found.item.rentalItemId
            ? {
                ...item,
                version: item.version + 1,
                contentsItems: cloneContents(returnedContents),
              }
            : item
        )
      )
    }
    writeState(replaceReturnItem(state, found.receipt.id, saved))
    return toTask(found.receipt, saved)
  })
}

export async function resolveReturnFurnitureDisposition(input: {
  warehouseId: string
  serviceWarehouseId?: string | null
  accessToken?: string | null
  returnItemId: string
  dispositionId: string
  expectedVersion: number
  quantity: number
  idempotencyKey: string
  action:
    "KEEP_IN_CABIN" | "RETURN_TO_STOCK" | "WRITE_OFF" | "TRANSFER_TO_CABIN"
  targetRentalItemId?: string | null
  reason?: string | null
  actorId?: string | null
  resolvedBy: string
}) {
  const initial = locateReturnItem(
    readState(),
    input.warehouseId,
    input.returnItemId
  )
  const initialDisposition = initial?.item.furnitureDispositions.find(
    (entry) => entry.id === input.dispositionId
  )
  const replay = initialDisposition?.allocations.find(
    (entry) => entry.idempotencyKey === input.idempotencyKey
  )
  if (
    replay &&
    input.action !== "TRANSFER_TO_CABIN" &&
    replay.status === "APPLIED"
  ) {
    if (
      replay.action !== input.action ||
      replay.quantity !== input.quantity ||
      replay.targetRentalItemId !== (input.targetRentalItemId ?? null)
    )
      throw new Error("Ключ распределения уже использован с другими данными")
    return toTask(initial!.receipt, initial!.item)
  }
  if (input.action === "RETURN_TO_STOCK" || input.action === "WRITE_OFF") {
    const reason = input.reason?.trim() || null
    const reserved = await reserveReturnFurnitureLedger({
      warehouseId: input.warehouseId,
      returnItemId: input.returnItemId,
      dispositionId: input.dispositionId,
      expectedVersion: input.expectedVersion,
      quantity: input.quantity,
      idempotencyKey: input.idempotencyKey,
      action: input.action,
      reason,
      resolvedBy: input.resolvedBy,
    })
    const reservedDisposition = reserved.furnitureDispositions.find(
      (entry) => entry.id === input.dispositionId
    )!
    const allocation = reservedDisposition.allocations.find(
      (entry) => entry.idempotencyKey === input.idempotencyKey
    )!
    if (allocation.status === "APPLIED") return reserved
    applyReturnFurnitureEquipmentAllocation({
      idempotencyKey: input.idempotencyKey,
      warehouseId: input.warehouseId,
      name: reservedDisposition.name,
      quantity: input.quantity,
      action: input.action,
    })
    return finalizeReturnFurnitureTransfer({
      warehouseId: input.warehouseId,
      returnItemId: input.returnItemId,
      dispositionId: input.dispositionId,
      idempotencyKey: input.idempotencyKey,
    })
  }
  if (input.action === "TRANSFER_TO_CABIN") {
    if (!initial || !initialDisposition)
      throw new Error("Конфликт мебели не найден")
    if (!input.targetRentalItemId)
      throw new Error("Выберите бытовку назначения")
    if (!input.serviceWarehouseId)
      throw new Error("Не определён сервисный склад для задания")
    const reserved = await reserveReturnFurnitureTransfer({
      warehouseId: input.warehouseId,
      returnItemId: input.returnItemId,
      dispositionId: input.dispositionId,
      expectedVersion: input.expectedVersion,
      quantity: input.quantity,
      idempotencyKey: input.idempotencyKey,
      targetRentalItemId: input.targetRentalItemId,
      reason: input.reason?.trim() || null,
      resolvedBy: input.resolvedBy,
    })
    const reservedDisposition = reserved.furnitureDispositions.find(
      (entry) => entry.id === input.dispositionId
    )!
    const allocation = reservedDisposition.allocations.find(
      (entry) => entry.idempotencyKey === input.idempotencyKey
    )!
    if (allocation.status === "APPLIED") return reserved
    const rentals = readRentalItems()
    const source = rentals.find((item) => item.id === initial.item.rentalItemId)
    const target = rentals.find((item) => item.id === input.targetRentalItemId)
    if (!source || !target) throw new Error("Бытовка перемещения не найдена")
    if (reservedDisposition.origin === "EXTRA_FACTUAL") {
      await transferRentalItemContentsWithTask({
        externalTaskId: input.idempotencyKey,
        warehouseId: input.warehouseId,
        serviceWarehouseId: input.serviceWarehouseId,
        accessToken: input.accessToken ?? "",
        actor: { id: input.actorId ?? null, displayName: input.resolvedBy },
        source: {
          rentalItemId: source.id,
          number: source.number,
          expectedVersion: source.version,
        },
        target: {
          rentalItemId: target.id,
          number: target.number,
          expectedVersion: target.version,
        },
        items: [{ name: reservedDisposition.name, quantity: input.quantity }],
      })
    } else {
      await reconciliationTaskClient.dispatch(input.accessToken ?? "", {
        externalTaskId: input.idempotencyKey,
        serviceWarehouseId: input.serviceWarehouseId,
        source: {
          rentalItemId: source.id,
          number: source.number,
          expectedVersion: source.version,
        },
        target: {
          rentalItemId: target.id,
          number: target.number,
          expectedVersion: target.version,
        },
        items: [{ name: reservedDisposition.name, quantity: input.quantity }],
      })
      await addReconciledContentsToRentalItem({
        reconciliationKey: input.idempotencyKey,
        targetRentalItemId: target.id,
        warehouseId: input.warehouseId,
        expectedTargetVersion: target.version,
        payload: [{ name: reservedDisposition.name, quantity: input.quantity }],
      })
    }
    return finalizeReturnFurnitureTransfer({
      warehouseId: input.warehouseId,
      returnItemId: input.returnItemId,
      dispositionId: input.dispositionId,
      idempotencyKey: input.idempotencyKey,
    })
  }
  return runRentalItemMutation(async () => {
    const state = readState()
    const found = locateReturnItem(state, input.warehouseId, input.returnItemId)
    if (!found || found.item.version !== input.expectedVersion)
      throw new Error("Возврат изменился. Обновите список")
    const disposition = found.item.furnitureDispositions.find(
      (entry) => entry.id === input.dispositionId
    )
    if (!disposition) throw new Error("Конфликт мебели не найден")
    if (
      !Number.isInteger(input.quantity) ||
      input.quantity < 1 ||
      input.quantity > disposition.remainingQuantity
    )
      throw new Error(
        `Количество должно быть от 1 до ${disposition.remainingQuantity}`
      )
    const reason = input.reason?.trim() || null
    if (input.action === "WRITE_OFF" && !reason)
      throw new Error("Укажите причину списания")
    if (input.action === "TRANSFER_TO_CABIN" && !input.targetRentalItemId)
      throw new Error("Выберите бытовку назначения")

    let returnedContents = cloneContents(found.item.returnedContents)
    const subtractFromReturned =
      disposition.origin === "EXTRA_FACTUAL" && input.action !== "KEEP_IN_CABIN"
    const addPreviousToReturned =
      disposition.origin === "PREVIOUS_SNAPSHOT" &&
      input.action === "KEEP_IN_CABIN"
    if (subtractFromReturned) {
      returnedContents = normalizedContents(
        returnedContents.map((entry) =>
          normalized(entry.name) === normalized(disposition.name)
            ? { ...entry, quantity: entry.quantity - input.quantity }
            : entry
        )
      )
    }
    if (addPreviousToReturned) {
      returnedContents = normalizedContents([
        ...returnedContents,
        { name: disposition.name, quantity: input.quantity },
      ])
    }

    const now = new Date().toISOString()
    const remainingQuantity = disposition.remainingQuantity - input.quantity
    const saved: ReturnReceiptItemDto = {
      ...found.item,
      version: found.item.version + 1,
      returnedContents,
      furnitureDispositions: found.item.furnitureDispositions.map((entry) =>
        entry.id === disposition.id
          ? {
              ...entry,
              remainingQuantity,
              status: remainingQuantity === 0 ? "RESOLVED" : "PENDING",
              action: remainingQuantity === 0 ? input.action : null,
              targetRentalItemId:
                remainingQuantity === 0
                  ? (input.targetRentalItemId ?? null)
                  : null,
              reason: remainingQuantity === 0 ? reason : null,
              resolvedAt: remainingQuantity === 0 ? now : null,
              resolvedBy: remainingQuantity === 0 ? input.resolvedBy : null,
              allocations: [
                ...entry.allocations,
                {
                  id: id("return-furniture-allocation"),
                  idempotencyKey: input.idempotencyKey,
                  status: "APPLIED",
                  action: input.action,
                  quantity: input.quantity,
                  targetRentalItemId: input.targetRentalItemId ?? null,
                  reason,
                  createdAt: now,
                  createdBy: input.resolvedBy,
                  taskExternalId:
                    input.action === "TRANSFER_TO_CABIN"
                      ? input.idempotencyKey
                      : null,
                },
              ],
            }
          : entry
      ),
    }
    const nextState = replaceReturnItem(state, found.receipt.id, saved)
    const rentals = readRentalItems()
    const sourceRental = rentals.find(
      (item) => item.id === found.item.rentalItemId
    )
    const nextRentals = sourceRental
      ? rentals.map((item) =>
          item.id === sourceRental.id
            ? {
                ...item,
                version: item.version + 1,
                contentsItems: cloneContents(returnedContents),
              }
            : item
        )
      : rentals
    try {
      if (sourceRental) writeRentalItems(nextRentals)
      writeState(nextState)
      return toTask(found.receipt, saved)
    } catch (error) {
      writeState(state)
      if (sourceRental) writeRentalItems(rentals)
      throw error
    }
  })
}

/** Retries a previously reserved task allocation with its original immutable data. */
export async function retryReturnFurnitureTransfer(input: {
  warehouseId: string
  serviceWarehouseId: string | null
  accessToken: string | null
  returnItemId: string
  dispositionId: string
  allocationId: string
  actorId: string | null
  resolvedBy: string
}) {
  const found = locateReturnItem(
    readState(),
    input.warehouseId,
    input.returnItemId
  )
  const disposition = found?.item.furnitureDispositions.find(
    (entry) => entry.id === input.dispositionId
  )
  const allocation = disposition?.allocations.find(
    (entry) => entry.id === input.allocationId
  )
  if (!found || !disposition || !allocation)
    throw new Error("Распределение мебели не найдено")
  if (allocation.status === "APPLIED") return toTask(found.receipt, found.item)
  if (
    allocation.action !== "TRANSFER_TO_CABIN" ||
    !allocation.targetRentalItemId
  )
    throw new Error("Это распределение не содержит задания перемещения")
  return resolveReturnFurnitureDisposition({
    warehouseId: input.warehouseId,
    serviceWarehouseId: input.serviceWarehouseId,
    accessToken: input.accessToken,
    returnItemId: input.returnItemId,
    dispositionId: input.dispositionId,
    expectedVersion: found.item.version,
    quantity: allocation.quantity,
    idempotencyKey: allocation.idempotencyKey,
    action: "TRANSFER_TO_CABIN",
    targetRentalItemId: allocation.targetRentalItemId,
    reason: allocation.reason,
    actorId: input.actorId,
    resolvedBy: input.resolvedBy,
  })
}

/** Continues a persisted intake after transfer or an approved accounting correction. */
export async function resumeImportedReturnConflict(input: {
  warehouseId: string
  returnItemId: string
  expectedVersion: number
  resolvedBy: string
  resolution: {
    kind: "WAREHOUSE_TRANSFER" | "ACCOUNTING_CORRECTION"
    id: string
  }
}) {
  const transfer =
    input.resolution.kind === "WAREHOUSE_TRANSFER"
      ? await getWarehouseTransfer(input.resolution.id)
      : null
  const correction =
    input.resolution.kind === "ACCOUNTING_CORRECTION"
      ? ((await listWarehouseAccountingCorrections()).find(
          (entry) => entry.id === input.resolution.id
        ) ?? null)
      : null
  return runRentalItemMutation(async () => {
    const state = readState()
    const found = locateReturnItem(state, input.warehouseId, input.returnItemId)
    if (!found) throw new Error("Конфликт возврата не найден")
    const rentals = readRentalItems()
    const rental = rentals.find(
      (item) =>
        item.id === found.item.rentalItemId &&
        item.warehouseId === input.warehouseId &&
        item.status !== "WRITTEN_OFF"
    )
    if (!rental) throw new Error("Бытовка ещё не числится на складе возврата")
    const transferLine =
      transfer?.destinationWarehouse.id === input.warehouseId
        ? (transfer.lines.find(
            (line) =>
              line.rentalItemId === found.item.rentalItemId &&
              line.status === "RECEIVED"
          ) ?? null)
        : null
    const transferProvesResolution = Boolean(transferLine)
    const correctionProvesResolution = Boolean(
      correction &&
      correction.rentalItemId === found.item.rentalItemId &&
      correction.destinationWarehouse.id === input.warehouseId &&
      correction.status === "APPLIED"
    )
    if (!transferProvesResolution && !correctionProvesResolution)
      throw new Error("Перемещение или коррекция ещё не подтверждены")
    const alreadyResolved = found.item.conflictResolutions.some(
      (resolution) =>
        resolution.kind === input.resolution.kind &&
        resolution.id === input.resolution.id
    )
    if (alreadyResolved) return toTask(found.receipt, found.item)
    const proofMatchesCurrentCabin =
      transferLine?.rentalItemVersion === rental.version ||
      (correction?.expectedRentalItemVersion !== undefined &&
        correction.expectedRentalItemVersion + 1 === rental.version)
    if (!proofMatchesCurrentCabin)
      throw new Error("Бытовка изменилась после подтверждения склада")
    if (
      found.item.version !== input.expectedVersion ||
      found.item.technicalState !== "CONFLICT"
    )
      throw new Error("Конфликт возврата уже изменён")
    if (
      !found.item.conflicts.some(
        (conflict) => conflict.code === "OTHER_WAREHOUSE"
      )
    )
      throw new Error("Подтверждение склада не решает этот тип конфликта")
    const remainingConflicts = found.item.conflicts.filter(
      (conflict) => conflict.code !== "OTHER_WAREHOUSE"
    )
    const now = new Date().toISOString()
    const saved: ReturnReceiptItemDto = {
      ...found.item,
      version: found.item.version + 1,
      technicalState:
        remainingConflicts.length > 0 ? "CONFLICT" : "PENDING_INSPECTION",
      conflicts: remainingConflicts,
      conflictResolutions: [
        ...found.item.conflictResolutions,
        {
          ...input.resolution,
          resolvedAt: now,
          resolvedBy: input.resolvedBy,
        },
      ],
      intakeHistory: [
        ...found.item.intakeHistory,
        {
          id: id("return-audit"),
          type: "INTAKE_REGISTERED",
          createdAt: now,
          createdBy: input.resolvedBy,
          reason: `${input.resolution.kind}:${input.resolution.id}`,
        },
      ],
    }
    if (remainingConflicts.length > 0) {
      writeState(replaceReturnItem(state, found.receipt.id, saved))
      return toTask(found.receipt, saved)
    }
    const passport = found.item.passportSnapshot
    const previews = found.item.media.map((media) => media.variants.small.url)
    const nextRental: RentalItemDto = {
      ...rental,
      version: rental.version + 1,
      status: "AFTER_RENT",
      locationNodeId: null,
      type: passport?.type ?? rental.type,
      dimensions: passport?.dimensions ?? rental.dimensions,
      finishing: passport?.finishing ?? rental.finishing,
      category: passport?.category ?? rental.category,
      characteristics:
        passport?.characteristics.join(", ") || rental.characteristics,
      linoleum: passport?.linoleum ?? rental.linoleum,
      contentsItems: cloneContents(found.item.returnedContents),
      contents: found.item.returnedContents.length
        ? found.item.returnedContents
            .map((item) => `${item.name}: ${item.quantity}`)
            .join(", ")
        : null,
      tenant: found.receipt.fromParty,
      shipmentDate: found.receipt.shipmentDate,
      hasPhotos: rental.hasPhotos || found.item.media.length > 0,
      photoCount: rental.photoCount + found.item.media.length,
      mainPhotoUrl: rental.mainPhotoUrl ?? previews[0] ?? null,
      previewPhotoUrls: [...(rental.previewPhotoUrls ?? []), ...previews],
    }
    const nextState = replaceReturnItem(state, found.receipt.id, saved)
    try {
      writeRentalItems(
        rentals.map((item) => (item.id === nextRental.id ? nextRental : item))
      )
      writeState(nextState)
    } catch (error) {
      writeRentalItems(rentals)
      try {
        restoreLogisticsStateExact(state, nextState)
      } catch {
        // Cabin rollback above keeps the aggregate safe for an operator retry.
      }
      throw error
    }
    return toTask(found.receipt, saved)
  })
}

export async function cloneReturnMediaToPendingUploads(
  media: RepairEstimateMediaRefDto[]
): Promise<PendingEstimateMediaUpload[]> {
  const hydrated = await mediaClient.hydrate(media)
  const copies: PendingEstimateMediaUpload[] = []
  try {
    for (const item of hydrated) {
      const response = await fetch(item.variants.largeWebp.url)
      if (!response.ok)
        throw new Error(`Не удалось скопировать фото ${item.fileName}`)
      const blob = await response.blob()
      const file = new File([blob], item.fileName, {
        type: item.mimeType || blob.type || "image/jpeg",
      })
      copies.push({
        id: id("estimate-media"),
        file,
        previewUrl: URL.createObjectURL(file),
        rotationDegrees: item.rotationDegrees,
      })
    }
    return copies
  } catch (error) {
    copies.forEach((item) => URL.revokeObjectURL(item.previewUrl))
    throw error
  }
}

export async function claimReturnForEstimate(input: {
  warehouseId: string
  returnTaskId: string
  rentalItemId: string
  expectedVersion: number
}) {
  return runRentalItemMutation(async () => {
    const state = readState()
    const found = locateReturnItem(state, input.warehouseId, input.returnTaskId)
    if (
      !found ||
      found.item.rentalItemId !== input.rentalItemId ||
      found.item.version !== input.expectedVersion ||
      found.item.technicalState !== "PENDING_INSPECTION" ||
      found.item.sourceEstimateId ||
      found.item.pendingEstimateId
    )
      throw new Error("Возврат уже изменён или обрабатывается в другой вкладке")
    if (
      found.item.furnitureDispositions.some(
        (entry) => entry.status === "PENDING"
      )
    )
      throw new Error("Сначала распределите конфликтную мебель")
    const saved: ReturnReceiptItemDto = {
      ...found.item,
      version: found.item.version + 1,
      technicalState: "ESTIMATE_IN_PROGRESS",
      estimateClaimId: id("estimate-claim"),
      estimateClaimedAt: new Date().toISOString(),
    }
    writeState(replaceReturnItem(state, found.receipt.id, saved))
    return toTask(found.receipt, saved)
  })
}

export async function releaseReturnEstimateClaim(input: {
  warehouseId: string
  returnTaskId: string
  expectedVersion: number
  claimId: string
}) {
  return runRentalItemMutation(async () => {
    const state = readState()
    const found = locateReturnItem(state, input.warehouseId, input.returnTaskId)
    if (
      !found ||
      found.item.version !== input.expectedVersion ||
      found.item.technicalState !== "ESTIMATE_IN_PROGRESS" ||
      found.item.estimateClaimId !== input.claimId
    )
      return found ? toTask(found.receipt, found.item) : null
    const saved: ReturnReceiptItemDto = {
      ...found.item,
      version: found.item.version + 1,
      technicalState: "PENDING_INSPECTION",
      estimateClaimId: null,
      estimateClaimedAt: null,
    }
    writeState(replaceReturnItem(state, found.receipt.id, saved))
    return toTask(found.receipt, saved)
  })
}

export async function markReturnEstimateCreated(input: {
  warehouseId: string
  returnTaskId: string
  expectedVersion: number
  estimateId: string
  estimateStatus?: RepairEstimateStatus
  rentalItemId: string
  claimId: string | null
}) {
  return runRentalItemMutation(async () => {
    const state = readState()
    const found = locateReturnItem(state, input.warehouseId, input.returnTaskId)
    const idempotencyKey = `return-estimate:${input.returnTaskId}:${input.estimateId}`
    const existingAttempt = readReturnEstimateApplyJournal().find(
      (entry) => entry.idempotencyKey === idempotencyKey
    )
    if (
      found?.item.technicalState === "ESTIMATE_CREATED" &&
      found.item.sourceEstimateId === input.estimateId
    ) {
      const recoveryAttempt: ReturnEstimateApplyAttempt = existingAttempt ?? {
        idempotencyKey,
        warehouseId: input.warehouseId,
        returnItemId: input.returnTaskId,
        rentalItemId: input.rentalItemId,
        estimateId: input.estimateId,
        missingEquipment:
          found.item.contentsMode === "FACTUAL"
            ? missingContents(
                found.item.expectedContents,
                found.item.returnedContents
              )
            : [],
        phase: "PREPARED",
      }
      writeReturnEstimateApplyAttempt(recoveryAttempt)
      applyReturnEstimateLoss(recoveryAttempt)
      writeReturnEstimateApplyAttempt({
        ...recoveryAttempt,
        phase: "APPLIED",
      })
      return toTask(found.receipt, found.item)
    }
    if (!found || found.item.rentalItemId !== input.rentalItemId)
      throw new Error("Не удалось связать смету: возврат изменился")
    if (found.item.version !== input.expectedVersion)
      throw new Error("Не удалось связать смету: версия возврата изменилась")
    const validClaim =
      found.item.technicalState === "ESTIMATE_IN_PROGRESS" &&
      input.claimId !== null &&
      found.item.estimateClaimId === input.claimId
    const validRecovery =
      found.item.technicalState === "ESTIMATE_LINK_FAILED" &&
      found.item.pendingEstimateId === input.estimateId
    if (!validClaim && !validRecovery)
      throw new Error("Не удалось связать смету: конфликт связанного возврата")
    const saved: ReturnReceiptItemDto = {
      ...found.item,
      version: found.item.version + 1,
      technicalState: "ESTIMATE_CREATED",
      sourceEstimateId: input.estimateId,
      pendingEstimateId: null,
      estimateClaimId: null,
      estimateClaimedAt: null,
    }
    const rentals = readRentalItems()
    const cabin = rentals.find(
      (item) =>
        item.id === input.rentalItemId && item.warehouseId === input.warehouseId
    )
    if (!cabin) throw new Error("Бытовка возврата не найдена")
    const factualContents = found.item.contentsMode === "FACTUAL"
    const missingEquipment = factualContents
      ? missingContents(
          found.item.expectedContents,
          found.item.returnedContents
        )
      : []
    const attempt: ReturnEstimateApplyAttempt = existingAttempt ?? {
      idempotencyKey,
      warehouseId: input.warehouseId,
      returnItemId: input.returnTaskId,
      rentalItemId: input.rentalItemId,
      estimateId: input.estimateId,
      missingEquipment,
      phase: "PREPARED",
    }
    if (
      attempt.warehouseId !== input.warehouseId ||
      attempt.rentalItemId !== input.rentalItemId ||
      attempt.estimateId !== input.estimateId
    )
      throw new Error("Журнал сметы связан с другой операцией")
    const moveToWaiting =
      input.estimateStatus === "DRAFT" ||
      (input.estimateStatus === undefined && cabin.status === "AFTER_RENT")
    if (
      moveToWaiting &&
      cabin.status !== "AFTER_RENT" &&
      !(
        existingAttempt?.phase === "PREPARED" &&
        cabin.status === "WAITING_ESTIMATE_CONFIRMATION"
      )
    )
      throw new Error("Бытовка больше не ожидает осмотра")
    writeReturnEstimateApplyAttempt({ ...attempt, phase: "PREPARED" })
    applyReturnEstimateLoss(attempt)
    const nextRentals = rentals.map((item) => {
      if (item.id !== cabin.id) return item

      return {
        ...item,
        contentsItems: factualContents
          ? cloneContents(found.item.returnedContents)
          : item.contentsItems,
        status: moveToWaiting ? "WAITING_ESTIMATE_CONFIRMATION" : item.status,
      }
    })
    if (moveToWaiting || factualContents) {
      writeRentalItems(nextRentals)
    }
    writeState(replaceReturnItem(state, found.receipt.id, saved))
    writeReturnEstimateApplyAttempt({ ...attempt, phase: "APPLIED" })
    return toTask(found.receipt, saved)
  })
}

export async function recordReturnEstimateLinkFailure(input: {
  warehouseId: string
  returnTaskId: string
  rentalItemId: string
  estimateId: string
  expectedVersion: number
  claimId: string
}) {
  return runRentalItemMutation(async () => {
    const state = readState()
    const found = locateReturnItem(state, input.warehouseId, input.returnTaskId)
    if (!found || found.item.rentalItemId !== input.rentalItemId)
      throw new Error("Возврат больше нельзя связать со сметой")
    if (
      found.item.technicalState === "ESTIMATE_CREATED" &&
      found.item.sourceEstimateId === input.estimateId
    )
      return toTask(found.receipt, found.item)
    if (
      found.item.technicalState === "ESTIMATE_LINK_FAILED" &&
      found.item.pendingEstimateId === input.estimateId
    )
      return toTask(found.receipt, found.item)
    if (
      found.item.technicalState === "ESTIMATE_CREATED" ||
      found.item.version !== input.expectedVersion ||
      found.item.technicalState !== "ESTIMATE_IN_PROGRESS" ||
      found.item.estimateClaimId !== input.claimId
    )
      throw new Error("Возврат уже изменён или связан с другой сметой")
    const saved: ReturnReceiptItemDto = {
      ...found.item,
      version: found.item.version + 1,
      technicalState: "ESTIMATE_LINK_FAILED",
      pendingEstimateId: input.estimateId,
      estimateClaimId: null,
      estimateClaimedAt: null,
    }
    writeState(replaceReturnItem(state, found.receipt.id, saved))
    return toTask(found.receipt, saved)
  })
}

export async function listShipments(warehouseId: string) {
  return readState().shipments.filter(
    (item) => item.warehouseId === warehouseId
  )
}

function changesEqual(
  left: ShipmentContentsChangeDto[],
  right: ShipmentContentsChangeDto[]
) {
  return JSON.stringify(left) === JSON.stringify(right)
}

export type ShipmentDraftInput = {
  shipmentId?: string | null
  warehouseId: string
  accessToken?: string | null
  company: string
  driverId: string
  driverName: string
  shipmentDate: string
  items: Array<{
    rentalItemId: string
    contentsBefore: RentalItemContentsItemDto[]
    contentsPlanned: RentalItemContentsItemDto[]
    preparationTask: ShipmentPreparationTaskDto | null
  }>
  createdBy: string
}

async function validateShipmentDraft(input: ShipmentDraftInput) {
  if (!input.company.trim()) throw new Error("Выберите компанию")
  assertCalendarDate(input.shipmentDate, "отгрузка")
  if (!input.driverId) throw new Error("Выберите водителя")
  const groups = await listRepairWorkerGroups(
    {
      warehouseId: input.warehouseId,
      queueCode: null,
      routeQueueKind: "MOVEMENT",
      purpose: "DRIVER_DIRECTORY",
    },
    input.accessToken ?? undefined
  )
  const driver = Array.from(
    new Map(
      groups
        .filter((group) => group.active)
        .flatMap((group) => group.members)
        .map((member) => [member.id, member])
    ).values()
  ).find((member) => member.id === input.driverId)
  if (!driver || driver.name !== input.driverName)
    throw new Error("Выбранный водитель больше недоступен")
  const ids = input.items.map((item) => item.rentalItemId)
  if (ids.length === 0) throw new Error("Выберите хотя бы одну бытовку")
  if (new Set(ids).size !== ids.length)
    throw new Error("Бытовки не должны повторяться")
  const rentals = readRentalItems()
  const cabins = ids.map((rentalItemId) =>
    rentals.find(
      (item) =>
        item.id === rentalItemId && item.warehouseId === input.warehouseId
    )
  )
  if (cabins.some((item) => !item))
    throw new Error("Одна из бытовок не найдена")
  const selected = cabins as RentalItemDto[]
  if (
    selected.some(
      (item) =>
        item.status !== "FREE" &&
        !(
          (item.status === "BOOKED" || item.status === "RESERVED") &&
          item.tenant &&
          normalized(item.tenant) === normalized(input.company)
        )
    )
  )
    throw new Error("Одна из бытовок уже недоступна для этой компании")

  const snapshots = input.items.map((draft) => {
    const cabin = selected.find((item) => item.id === draft.rentalItemId)!
    const before = normalizedContents(draft.contentsBefore)
    if (
      JSON.stringify(before) !==
      JSON.stringify(normalizedContents(cabin.contentsItems))
    )
      throw new Error(
        `Наполнение ${cabin.number} изменилось. Выберите бытовку заново`
      )
    const planned = normalizedContents(draft.contentsPlanned)
    const changes = computeShipmentContentsChanges(before, planned)
    if (draft.preparationTask) {
      if (!changesEqual(changes, draft.preparationTask.lines))
        throw new Error(`Обновите задачу подготовки для ${cabin.number}`)
      const expectedExternalTaskId = stablePreparationExternalTaskId(
        `${input.warehouseId}|${input.company}|${input.shipmentDate}|${cabin.id}|${JSON.stringify(changes)}`
      )
      if (draft.preparationTask.externalTaskId !== expectedExternalTaskId)
        throw new Error(`Обновите задачу подготовки для ${cabin.number}`)
    }
    return {
      rentalItemId: cabin.id,
      cabinNumber: cabin.number,
      contentsBefore: cloneContents(before),
      contentsPlanned: cloneContents(planned),
      changes,
      preparationTask: draft.preparationTask
        ? {
            ...draft.preparationTask,
            lines: changes.map((change) => ({ ...change })),
          }
        : null,
    }
  })
  return { rentals, snapshots }
}

export async function upsertShipmentDraft(input: ShipmentDraftInput) {
  return runRentalItemMutation(async () => {
    const { snapshots } = await validateShipmentDraft(input)
    const state = readState()
    const existing = input.shipmentId
      ? state.shipments.find(
          (shipment) =>
            shipment.id === input.shipmentId &&
            shipment.warehouseId === input.warehouseId &&
            shipment.status !== "SHIPPED"
        )
      : null
    if (input.shipmentId && !existing)
      throw new Error("Черновик отгрузки изменился или уже завершён")
    existing?.items.forEach((previous) => {
      if (
        previous.preparationTask?.dispatchStatus !== "DISPATCHED" &&
        previous.preparationTask?.dispatchStatus !== "PENDING"
      )
        return
      const next = snapshots.find(
        (item) => item.rentalItemId === previous.rentalItemId
      )
      if (!next || JSON.stringify(next) !== JSON.stringify(previous))
        throw new Error(
          `Подготовка ${previous.cabinNumber} уже отправлена, изменение запрещено`
        )
    })
    const shipment: ShipmentDto = {
      id: existing?.id ?? id("shipment"),
      version: existing ? existing.version + 1 : 1,
      warehouseId: input.warehouseId,
      company: input.company.trim(),
      driverId: input.driverId,
      driverName: input.driverName,
      shipmentDate: input.shipmentDate,
      status: "PREPARING",
      error: null,
      createdAt: existing?.createdAt ?? new Date().toISOString(),
      createdBy: input.createdBy,
      items: snapshots,
    }
    writeState({
      ...state,
      shipments: existing
        ? state.shipments.map((item) =>
            item.id === shipment.id ? shipment : item
          )
        : [shipment, ...state.shipments],
    })
    return shipment
  })
}

export async function dispatchShipmentPreparationTask(input: {
  warehouseId: string
  serviceWarehouseId: string
  accessToken: string
  shipmentId: string
  rentalItemId: string
}) {
  const pending = await runRentalItemMutation(async () => {
    const state = readState()
    const shipment = state.shipments.find(
      (item) =>
        item.id === input.shipmentId &&
        item.warehouseId === input.warehouseId &&
        item.status !== "SHIPPED"
    )
    const shipmentItem = shipment?.items.find(
      (item) => item.rentalItemId === input.rentalItemId
    )
    const task = shipmentItem?.preparationTask
    if (!shipment || !shipmentItem || !task)
      throw new Error("Задача подготовки не найдена")
    if (task.dispatchStatus === "DISPATCHED")
      return { shipment, shipmentItem, task }
    if (task.dispatchStatus === "PENDING") {
      const attemptedAt = task.dispatchAttemptedAt
        ? Date.parse(task.dispatchAttemptedAt)
        : Number.NaN
      if (
        Number.isFinite(attemptedAt) &&
        Date.now() - attemptedAt < SHIPMENT_DISPATCH_ATTEMPT_TTL_MS
      )
        throw new Error("Задача уже отправляется. Повторите позже")
    }
    const taskText = task.lines.map((line) => line.label).join("; ")
    const attemptedAt = new Date().toISOString()
    const dispatchAttemptId = crypto.randomUUID()
    const pendingTask: ShipmentPreparationTaskDto = {
      ...task,
      dispatchStatus: "PENDING",
      dispatchError: null,
      dispatchAttemptId,
      dispatchAttemptedAt: attemptedAt,
    }
    const saved: ShipmentDto = {
      ...shipment,
      version: shipment.version + 1,
      status: "PREPARING",
      error: null,
      items: shipment.items.map((item) =>
        item.rentalItemId === shipmentItem.rentalItemId
          ? { ...item, preparationTask: pendingTask }
          : item
      ),
    }
    writeState({
      ...state,
      shipments: state.shipments.map((item) =>
        item.id === saved.id ? saved : item
      ),
      preparationDispatches: [
        ...state.preparationDispatches.filter(
          (entry) => entry.externalTaskId !== task.externalTaskId
        ),
        {
          externalTaskId: task.externalTaskId,
          warehouseId: shipment.warehouseId,
          rentalItemId: shipmentItem.rentalItemId,
          cabinNumber: shipmentItem.cabinNumber,
          status: "PENDING",
          boardTaskId: null,
          queueId: null,
          queueCode: null,
          taskText,
          lastAttemptAt: attemptedAt,
          dispatchAttemptId,
          error: null,
        },
      ],
    })
    return { shipment: saved, shipmentItem, task: pendingTask }
  })
  if (pending.task.dispatchStatus === "DISPATCHED") return pending.shipment
  try {
    const result = await preparationTaskClient.dispatch(input.accessToken, {
      serviceWarehouseId: input.serviceWarehouseId,
      externalTaskId: pending.task.externalTaskId,
      cabinNumber: pending.shipmentItem.cabinNumber,
      taskText: pending.task.lines.map((line) => line.label).join("; "),
    })
    return runRentalItemMutation(async () => {
      const state = readState()
      const shipment = state.shipments.find(
        (item) => item.id === input.shipmentId
      )
      if (!shipment) throw new Error("Черновик отгрузки не найден")
      const currentTask = shipment.items.find(
        (item) => item.rentalItemId === input.rentalItemId
      )?.preparationTask
      if (
        !currentTask ||
        currentTask.externalTaskId !== pending.task.externalTaskId ||
        currentTask.dispatchAttemptId !== pending.task.dispatchAttemptId
      ) {
        if (currentTask?.dispatchStatus === "DISPATCHED") return shipment
        throw new Error("Попытка отправки задачи устарела")
      }
      const saved: ShipmentDto = {
        ...shipment,
        version: shipment.version + 1,
        status: "PREPARING",
        error: null,
        items: shipment.items.map((item) =>
          item.rentalItemId === input.rentalItemId && item.preparationTask
            ? {
                ...item,
                preparationTask: {
                  ...item.preparationTask,
                  dispatchStatus: "DISPATCHED",
                  boardTaskId: result.boardTaskId,
                  queueId: result.queueId,
                  queueCode: result.queueCode,
                  dispatchError: null,
                  dispatchAttemptId: pending.task.dispatchAttemptId,
                  dispatchAttemptedAt: pending.task.dispatchAttemptedAt,
                },
              }
            : item
        ),
      }
      writeState({
        ...state,
        shipments: state.shipments.map((item) =>
          item.id === saved.id ? saved : item
        ),
        preparationDispatches: state.preparationDispatches.map((entry) =>
          entry.externalTaskId === pending.task.externalTaskId &&
          entry.dispatchAttemptId === pending.task.dispatchAttemptId
            ? {
                ...entry,
                status: "DISPATCHED",
                boardTaskId: result.boardTaskId,
                queueId: result.queueId,
                queueCode: result.queueCode,
                lastAttemptAt: new Date().toISOString(),
                error: null,
              }
            : entry
        ),
      })
      return saved
    })
  } catch (error) {
    await runRentalItemMutation(async () => {
      const state = readState()
      const message =
        error instanceof Error ? error.message : "Ошибка task-board"
      const currentTask = state.shipments
        .find((shipment) => shipment.id === input.shipmentId)
        ?.items.find(
          (item) => item.rentalItemId === input.rentalItemId
        )?.preparationTask
      if (
        !currentTask ||
        currentTask.dispatchStatus === "DISPATCHED" ||
        currentTask.externalTaskId !== pending.task.externalTaskId ||
        currentTask.dispatchAttemptId !== pending.task.dispatchAttemptId
      )
        return
      writeState({
        ...state,
        shipments: state.shipments.map((shipment) =>
          shipment.id === input.shipmentId
            ? {
                ...shipment,
                version: shipment.version + 1,
                status: "FAILED",
                error: message,
                items: shipment.items.map((item) =>
                  item.rentalItemId === input.rentalItemId &&
                  item.preparationTask?.externalTaskId ===
                    pending.task.externalTaskId &&
                  item.preparationTask.dispatchAttemptId ===
                    pending.task.dispatchAttemptId
                    ? {
                        ...item,
                        preparationTask: {
                          ...item.preparationTask,
                          dispatchStatus: "FAILED",
                          dispatchError: message,
                        },
                      }
                    : item
                ),
              }
            : shipment
        ),
        preparationDispatches: state.preparationDispatches.map((entry) =>
          entry.externalTaskId === pending.task.externalTaskId &&
          entry.dispatchAttemptId === pending.task.dispatchAttemptId
            ? {
                ...entry,
                status: "FAILED",
                lastAttemptAt: new Date().toISOString(),
                error: message,
              }
            : entry
        ),
      })
    })
    throw error
  }
}

export async function retryShipmentPreparationTasks(input: {
  warehouseId: string
  serviceWarehouseId: string
  accessToken: string
  shipmentId: string
}) {
  let shipment = readState().shipments.find(
    (item) => item.id === input.shipmentId
  )
  if (!shipment) throw new Error("Черновик отгрузки не найден")
  for (const item of shipment.items) {
    if (
      item.preparationTask &&
      item.preparationTask.dispatchStatus !== "DISPATCHED"
    ) {
      shipment = await dispatchShipmentPreparationTask({
        ...input,
        rentalItemId: item.rentalItemId,
      })
    }
  }
  return shipment
}

export async function finalizeShipment(input: {
  warehouseId: string
  shipmentId: string
}) {
  return runRentalItemMutation(async () => {
    const state = readState()
    const shipment = state.shipments.find(
      (item) =>
        item.id === input.shipmentId &&
        item.warehouseId === input.warehouseId &&
        item.status !== "SHIPPED"
    )
    if (!shipment) throw new Error("Черновик отгрузки изменился или завершён")
    if (
      shipment.items.some((item) => {
        if (item.changes.length === 0) return false
        const task = item.preparationTask
        return (
          task?.dispatchStatus !== "DISPATCHED" ||
          !changesEqual(item.changes, task.lines) ||
          task.externalTaskId !==
            stablePreparationExternalTaskId(
              `${shipment.warehouseId}|${shipment.company}|${shipment.shipmentDate}|${item.rentalItemId}|${JSON.stringify(item.changes)}`
            )
        )
      })
    )
      throw new Error("Сначала отправьте все задачи подготовки")
    const rentals = readRentalItems()
    shipment.items.forEach((item) => {
      if (
        hasUnresolvedReturnEquipmentDispositionForRentalItem(item.rentalItemId)
      ) {
        throw new Error("Оборудование бытовки требует распределения")
      }
      const current = rentals.find(
        (rental) =>
          rental.id === item.rentalItemId &&
          rental.warehouseId === shipment.warehouseId
      )
      if (
        !current ||
        (current.status !== "FREE" &&
          !(
            (current.status === "BOOKED" || current.status === "RESERVED") &&
            current.tenant &&
            normalized(current.tenant) === normalized(shipment.company)
          )) ||
        JSON.stringify(normalizedContents(current.contentsItems)) !==
          JSON.stringify(normalizedContents(item.contentsBefore))
      )
        throw new Error(`Бытовка ${item.cabinNumber} изменилась`)
    })
    const ids = shipment.items.map((item) => item.rentalItemId)
    const saved: ShipmentDto = {
      ...shipment,
      version: shipment.version + 1,
      status: "SHIPPED",
      error: null,
    }
    try {
      writeRentalItems(
        rentals.map((item) =>
          ids.includes(item.id)
            ? {
                ...item,
                status: "RENTED",
                tenant: shipment.company,
                shipmentDate: shipment.shipmentDate,
              }
            : item
        )
      )
      writeState({
        ...state,
        shipments: state.shipments.map((item) =>
          item.id === saved.id ? saved : item
        ),
      })
      return saved
    } catch (error) {
      const current = readRentalItems()
      writeRentalItems(
        current.map((candidate) => {
          const previous = rentals.find((item) => item.id === candidate.id)
          const commandStillOwnsValue =
            ids.includes(candidate.id) &&
            candidate.status === "RENTED" &&
            candidate.tenant === shipment.company &&
            candidate.shipmentDate === shipment.shipmentDate
          return commandStillOwnsValue && previous ? previous : candidate
        })
      )
      throw error
    }
  })
}

export function hydrateLogisticsMedia(media: RepairEstimateMediaRefDto[]) {
  return mediaClient.hydrate(media)
}
