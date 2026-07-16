import {
  EQUIPMENT_MOCK_STORAGE_KEY,
  EQUIPMENT_MOCK_UPDATED_EVENT,
  getEquipmentWarehouseStock,
} from "@/api/equipment-api"
import { hasUnresolvedReturnEquipmentDispositionLowLevel } from "@/features/equipment/return-equipment-disposition-guard"
import { HttpLogisticsPreparationTaskClient } from "@/features/logistics/adapters/http-logistics-preparation-task-client"
import { BrowserLogisticsPreparationTaskClient } from "@/features/logistics/adapters/browser-logistics-preparation-task-client"
import { DEV_AUTH_BYPASS_ENABLED } from "@/features/auth/auth-config"
import type { LogisticsPreparationTaskClient } from "@/features/logistics/ports/logistics-preparation-task-client"
import { hasActiveWarehouseTransfer } from "@/features/logistics/warehouse-transfers/api/warehouse-transfer-api"
import {
  SHIPMENTS_STORAGE_KEY,
  SHIPMENTS_UPDATED_EVENT,
} from "@/features/logistics/shipments/storage"
import {
  buildShipmentPreparationTaskText,
  computeShipmentContentsChanges,
  normalizeContents,
  shipmentPreparationIdentity,
  stablePreparationExternalTaskId,
  validateAllocationCoverage,
} from "@/features/logistics/shipments/domain"
import type {
  Shipment,
  ShipmentAuditEvent,
  ShipmentDraftInput,
  ShipmentEnvelope,
  ShipmentItem,
} from "@/features/logistics/shipments/model"
import type {
  ShipmentClient,
  ShipmentCommandContext,
} from "@/features/logistics/shipments/ports/shipment-client"
import {
  readRentalItems,
  runRentalItemMutation,
  writeRentalItems,
} from "@/features/rental-items/api/rental-items-api"
import type {
  RentalItemContentsItemDto,
  RentalItemDto,
} from "@/features/rental-items/model/rental-item"

type EquipmentMasterItem = {
  id: string
  warehouseId: string
  name: string
  stockQuantity: number
  writtenOffQuantity: number
  lostQuantity: number
}

const EMPTY: ShipmentEnvelope = {
  service: "browser-logistics-shipments",
  schemaVersion: 1,
  revision: 0,
  shipments: [],
}

const norm = (value: string) =>
  value.trim().replace(/\s+/g, " ").toLocaleLowerCase("ru-RU")
const cloneContents = (items: RentalItemContentsItemDto[]) =>
  normalizeContents(items).map((item) => ({ ...item }))

function event(
  type: ShipmentAuditEvent["type"],
  actor: string,
  details: string
): ShipmentAuditEvent {
  return {
    id: crypto.randomUUID(),
    type,
    occurredAt: new Date().toISOString(),
    actor,
    details,
  }
}

function readEnvelope(): ShipmentEnvelope {
  if (typeof window === "undefined") return structuredClone(EMPTY)
  const stored = window.localStorage.getItem(SHIPMENTS_STORAGE_KEY)
  try {
    const parsed = JSON.parse(stored ?? "null") as ShipmentEnvelope | null
    if (
      parsed?.service === EMPTY.service &&
      parsed.schemaVersion === 1 &&
      Array.isArray(parsed.shipments)
    ) {
      return {
        ...parsed,
        shipments: parsed.shipments.map((shipment) => {
          const applicationAttempt = shipment.applicationAttempt as
            | Shipment["applicationAttempt"]
            | (Record<string, unknown> & { rentalMutations?: unknown })
          const unsupportedApplying =
            shipment.status === "APPLYING" &&
            applicationAttempt !== null &&
            !Array.isArray(applicationAttempt.rentalMutations)
          return {
            ...shipment,
            status: unsupportedApplying ? "CONFLICT" : shipment.status,
            error: unsupportedApplying
              ? "Старый незавершённый журнал требует перепланирования"
              : shipment.error,
            evidence: shipment.evidence ?? "PROVEN",
            applicationAttempt: unsupportedApplying
              ? null
              : shipment.applicationAttempt,
            finalizationAttempt: shipment.finalizationAttempt ?? null,
          }
        }),
      }
    }
  } catch {
    // A damaged browser projection is reset; domain state remains in rental-items.
  }
  if (!stored) {
    try {
      const legacy = JSON.parse(
        window.localStorage.getItem("rwms:logistics:v2") ?? "null"
      ) as { shipments?: Shipment[] } | null
      if (Array.isArray(legacy?.shipments) && legacy.shipments.length) {
        const migrated: ShipmentEnvelope = {
          ...EMPTY,
          shipments: legacy.shipments.map((legacyShipment) => ({
            ...legacyShipment,
            status: "LEGACY_QUARANTINE",
            items: legacyShipment.items.map((item) => {
              const changes = computeShipmentContentsChanges(
                item.contentsBefore,
                item.contentsPlanned
              )
              return {
                ...item,
                expectedTargetVersion: null,
                changes,
                sourceAllocations: [],
                preparationState: "CONFLICT",
                preparationTask: null,
                conflict:
                  "Legacy-план не содержит доказанного снимка версий и источников мебели",
              }
            }),
            audit: [],
            evidence: "LEGACY_UNPROVEN",
            applicationAttempt: null,
            finalizationAttempt: null,
          })),
        }
        window.localStorage.setItem(
          SHIPMENTS_STORAGE_KEY,
          JSON.stringify(migrated)
        )
        return migrated
      }
    } catch {
      // Old mock data is ignored when it cannot be proven or safely projected.
    }
  }
  return structuredClone(EMPTY)
}

function writeEnvelope(envelope: ShipmentEnvelope) {
  const saved = { ...envelope, revision: envelope.revision + 1 }
  if (typeof window !== "undefined") {
    window.localStorage.setItem(SHIPMENTS_STORAGE_KEY, JSON.stringify(saved))
    window.dispatchEvent(new Event(SHIPMENTS_UPDATED_EVENT))
  }
  return saved
}

function readEquipment(): EquipmentMasterItem[] {
  if (typeof window === "undefined") return []
  try {
    const parsed = JSON.parse(
      window.localStorage.getItem(EQUIPMENT_MOCK_STORAGE_KEY) ?? "[]"
    )
    return Array.isArray(parsed) ? parsed : []
  } catch {
    return []
  }
}

function writeEquipment(items: EquipmentMasterItem[]) {
  if (typeof window === "undefined") return
  window.localStorage.setItem(EQUIPMENT_MOCK_STORAGE_KEY, JSON.stringify(items))
  window.dispatchEvent(new Event(EQUIPMENT_MOCK_UPDATED_EVENT))
}

function replaceShipment(envelope: ShipmentEnvelope, shipment: Shipment) {
  return writeEnvelope({
    ...envelope,
    shipments: envelope.shipments.some((item) => item.id === shipment.id)
      ? envelope.shipments.map((item) =>
          item.id === shipment.id ? shipment : item
        )
      : [shipment, ...envelope.shipments],
  })
}

const sameSnapshot = (left: unknown, right: unknown) =>
  JSON.stringify(left) === JSON.stringify(right)

function applyRentalMutations(
  mutations: Array<{ id: string; before: RentalItemDto; after: RentalItemDto }>
) {
  const current = readRentalItems()
  mutations.forEach((mutation) => {
    if (
      !sameSnapshot(
        current.find((item) => item.id === mutation.id),
        mutation.before
      )
    )
      throw new Error(`Бытовка ${mutation.before.number} изменена извне`)
  })
  writeRentalItems(
    current.map(
      (item) =>
        mutations.find((mutation) => mutation.id === item.id)?.after ?? item
    )
  )
}

function applyEquipmentMutations(
  mutations: Array<{
    id: string
    before: EquipmentMasterItem | null
    after: EquipmentMasterItem
  }>
) {
  let current = readEquipment()
  mutations.forEach((mutation) => {
    if (
      !sameSnapshot(
        current.find((item) => item.id === mutation.id),
        mutation.before ?? undefined
      )
    )
      throw new Error(`Остаток «${mutation.after.name}» изменён извне`)
    current = current.some((item) => item.id === mutation.id)
      ? current.map((item) => (item.id === mutation.id ? mutation.after : item))
      : [...current, mutation.after]
  })
  writeEquipment(current)
}

function recoverIncompleteOperationsUnderLock() {
  let envelope = readEnvelope()
  const rentals = readRentalItems()
  const equipment = readEquipment()
  let nextRentals = rentals
  let nextEquipment = equipment
  let rentalsChanged = false
  let equipmentChanged = false
  let shipmentsChanged = false

  const shipments = envelope.shipments.map((shipment) => {
    if (shipment.status === "APPLYING" && shipment.applicationAttempt) {
      const attempt = shipment.applicationAttempt
      const allApplied =
        attempt.rentalMutations.every((mutation) =>
          sameSnapshot(
            nextRentals.find((item) => item.id === mutation.id),
            mutation.after
          )
        ) &&
        attempt.equipmentMutations.every((mutation) =>
          sameSnapshot(
            nextEquipment.find((item) => item.id === mutation.id),
            mutation.after
          )
        )
      shipmentsChanged = true
      if (allApplied) {
        return {
          ...shipment,
          version: shipment.version + 1,
          status: "READY_TO_SHIP" as const,
          error: null,
          applicationAttempt: null,
          items: shipment.items.map((item) => ({
            ...item,
            expectedTargetVersion:
              attempt.rentalMutations.find(
                (mutation) => mutation.id === item.rentalItemId
              )?.after.version ?? item.expectedTargetVersion,
            contentsBefore: cloneContents(item.contentsPlanned),
            preparationState: "READY" as const,
            conflict: null,
          })),
          audit: [
            ...shipment.audit,
            event(
              "PREPARATION_CONFIRMED",
              attempt.actor,
              "Прерванное применение подготовки подтверждено по CAS-снимкам"
            ),
          ],
        }
      }

      let foreignChange = false
      attempt.rentalMutations.forEach((mutation) => {
        const current = nextRentals.find((item) => item.id === mutation.id)
        if (sameSnapshot(current, mutation.after)) {
          nextRentals = nextRentals.map((item) =>
            item.id === mutation.id ? mutation.before : item
          )
          rentalsChanged = true
        } else if (!sameSnapshot(current, mutation.before ?? undefined))
          foreignChange = true
      })
      attempt.equipmentMutations.forEach((mutation) => {
        const current = nextEquipment.find((item) => item.id === mutation.id)
        if (sameSnapshot(current, mutation.after)) {
          nextEquipment = mutation.before
            ? nextEquipment.map((item) =>
                item.id === mutation.id ? mutation.before! : item
              )
            : nextEquipment.filter((item) => item.id !== mutation.id)
          equipmentChanged = true
        } else if (!sameSnapshot(current, mutation.before ?? undefined))
          foreignChange = true
      })
      const reason = foreignChange
        ? "Применение прервано; связанные позиции изменены извне и требуют перепланирования"
        : "Прерванное применение подготовки компенсировано"
      return {
        ...shipment,
        version: shipment.version + 1,
        status: "CONFLICT" as const,
        error: reason,
        applicationAttempt: null,
        items: shipment.items.map((item) => ({
          ...item,
          preparationState: "CONFLICT" as const,
          conflict: reason,
        })),
        audit: [
          ...shipment.audit,
          event("PREPARATION_CONFLICT", attempt.actor, reason),
        ],
      }
    }

    if (shipment.status === "FINALIZING" && shipment.finalizationAttempt) {
      const attempt = shipment.finalizationAttempt
      let foreignChange = false
      attempt.rentalMutations.forEach((mutation) => {
        const current = nextRentals.find((item) => item.id === mutation.id)
        if (sameSnapshot(current, mutation.before)) {
          nextRentals = nextRentals.map((item) =>
            item.id === mutation.id ? mutation.after : item
          )
          rentalsChanged = true
        } else if (!sameSnapshot(current, mutation.after)) foreignChange = true
      })
      shipmentsChanged = true
      if (foreignChange) {
        const reason =
          "Финализация прервана: бытовка изменена извне после сохранения намерения"
        return {
          ...shipment,
          version: shipment.version + 1,
          status: "CONFLICT" as const,
          error: reason,
          finalizationAttempt: null,
          audit: [
            ...shipment.audit,
            event("PREPARATION_CONFLICT", attempt.actor, reason),
          ],
        }
      }
      return {
        ...shipment,
        version: shipment.version + 1,
        status: "SHIPPED" as const,
        error: null,
        finalizationAttempt: null,
        audit: [
          ...shipment.audit,
          event(
            "SHIPMENT_FINALIZED",
            attempt.actor,
            "Финализация восстановлена по сохранённому намерению"
          ),
        ],
      }
    }
    return shipment
  })

  if (rentalsChanged) writeRentalItems(nextRentals)
  if (equipmentChanged) writeEquipment(nextEquipment)
  if (shipmentsChanged) envelope = writeEnvelope({ ...envelope, shipments })
  return envelope
}

function availableTarget(item: RentalItemDto, company: string) {
  return (
    item.status === "FREE" ||
    item.status === "WAREHOUSE" ||
    item.status === "OWN_NEEDS" ||
    ((item.status === "BOOKED" || item.status === "RESERVED") &&
      norm(item.tenant ?? "") === norm(company))
  )
}

function activeReservation(shipment: Shipment) {
  return (
    shipment.evidence === "PROVEN" &&
    shipment.status !== "SHIPPED" &&
    shipment.status !== "CANCELLED"
  )
}

function holdsReservation(shipment: Shipment) {
  return (
    shipment.status === "PREPARING" ||
    shipment.status === "FAILED" ||
    shipment.status === "AWAITING_CONFIRMATION" ||
    shipment.status === "CONFLICT"
  )
}

function reservedTargetIds(
  shipments: Shipment[],
  exceptShipmentId?: string | null
) {
  return new Set(
    shipments
      .filter(
        (shipment) =>
          shipment.id !== exceptShipmentId && activeReservation(shipment)
      )
      .flatMap((shipment) => shipment.items.map((item) => item.rentalItemId))
  )
}

function aggregateAllocations(
  shipments: Shipment[],
  exceptShipmentId: string | null
) {
  const warehouse = new Map<string, number>()
  const cabins = new Map<string, Map<string, number>>()
  shipments
    .filter(
      (shipment) =>
        shipment.id !== exceptShipmentId && holdsReservation(shipment)
    )
    .flatMap((shipment) => shipment.items)
    .flatMap((item) => item.sourceAllocations)
    .forEach((allocation) => {
      if (allocation.sourceType === "WAREHOUSE") {
        allocation.items.forEach((item) =>
          warehouse.set(
            norm(item.name),
            (warehouse.get(norm(item.name)) ?? 0) + item.quantity
          )
        )
        return
      }
      const source = allocation.sourceRentalItemId
      if (!source) return
      const sourceMap = cabins.get(source) ?? new Map<string, number>()
      allocation.items.forEach((item) =>
        sourceMap.set(
          norm(item.name),
          (sourceMap.get(norm(item.name)) ?? 0) + item.quantity
        )
      )
      cabins.set(source, sourceMap)
    })
  return { warehouse, cabins }
}

function validateAllocations(
  warehouseId: string,
  items: ShipmentItem[],
  existingShipments: Shipment[],
  shipmentId: string | null,
  rentals: RentalItemDto[],
  equipment: EquipmentMasterItem[],
  activeTransferIds: Set<string>
) {
  const other = aggregateAllocations(existingShipments, shipmentId)
  const requestedWarehouse = new Map(other.warehouse)
  const requestedCabins = new Map(
    [...other.cabins].map(([id, values]) => [id, new Map(values)])
  )
  const targetIds = new Set(items.map((item) => item.rentalItemId))
  items.forEach((item) => {
    validateAllocationCoverage(item.changes, item.sourceAllocations)
    item.sourceAllocations.forEach((allocation) => {
      if (allocation.sourceType === "WAREHOUSE") {
        allocation.items.forEach((entry) =>
          requestedWarehouse.set(
            norm(entry.name),
            (requestedWarehouse.get(norm(entry.name)) ?? 0) + entry.quantity
          )
        )
        return
      }
      const sourceId = allocation.sourceRentalItemId
      if (
        !sourceId ||
        sourceId === item.rentalItemId ||
        targetIds.has(sourceId)
      )
        throw new Error("Целевая бытовка не может быть источником мебели")
      const source = rentals.find(
        (candidate) =>
          candidate.id === sourceId && candidate.warehouseId === warehouseId
      )
      if (
        !source ||
        !["FREE", "WAREHOUSE", "OWN_NEEDS"].includes(source.status) ||
        activeTransferIds.has(source.id)
      )
        throw new Error("Бытовка-источник недоступна")
      if (source.version !== allocation.expectedSourceVersion)
        throw new Error(
          `Наполнение ${source.number} изменилось. Перепланируйте подготовку`
        )
      const map = requestedCabins.get(sourceId) ?? new Map<string, number>()
      allocation.items.forEach((entry) =>
        map.set(
          norm(entry.name),
          (map.get(norm(entry.name)) ?? 0) + entry.quantity
        )
      )
      requestedCabins.set(sourceId, map)
    })
  })
  requestedWarehouse.forEach((quantity, key) => {
    const stock =
      equipment.find(
        (item) => item.warehouseId === warehouseId && norm(item.name) === key
      )?.stockQuantity ?? 0
    if (quantity > stock)
      throw new Error(`Недостаточно складского остатка: ${key}`)
  })
  requestedCabins.forEach((quantities, sourceId) => {
    const source = rentals.find((item) => item.id === sourceId)!
    quantities.forEach((quantity, key) => {
      const available =
        source.contentsItems.find((item) => norm(item.name) === key)
          ?.quantity ?? 0
      if (quantity > available)
        throw new Error(`Недостаточно позиции «${key}» в ${source.number}`)
    })
  })
}

function subtract(
  items: RentalItemContentsItemDto[],
  removed: RentalItemContentsItemDto[]
) {
  const removal = new Map(
    normalizeContents(removed).map((item) => [norm(item.name), item.quantity])
  )
  return normalizeContents(items)
    .map((item) => ({
      ...item,
      quantity: item.quantity - (removal.get(norm(item.name)) ?? 0),
    }))
    .filter((item) => item.quantity > 0)
}

export class BrowserShipmentClient implements ShipmentClient {
  private readonly taskClient: LogisticsPreparationTaskClient

  constructor(taskClient?: LogisticsPreparationTaskClient) {
    this.taskClient =
      taskClient ??
      (DEV_AUTH_BYPASS_ENABLED
        ? new BrowserLogisticsPreparationTaskClient()
        : new HttpLogisticsPreparationTaskClient())
  }

  async list(warehouseId: string) {
    return readEnvelope().shipments.filter(
      (item) => item.warehouseId === warehouseId
    )
  }

  async listAvailableWarehouseStock(
    warehouseId: string,
    exceptShipmentId: string | null = null
  ) {
    const stock = await getEquipmentWarehouseStock(warehouseId)
    const reserved = aggregateAllocations(
      readEnvelope().shipments.filter(
        (shipment) => shipment.warehouseId === warehouseId
      ),
      exceptShipmentId
    ).warehouse
    return stock.map((item) => ({
      ...item,
      availableQuantity: Math.max(
        0,
        item.availableQuantity - (reserved.get(norm(item.name)) ?? 0)
      ),
    }))
  }

  async listCandidates(warehouseId: string, company: string) {
    const rentals = readRentalItems()
    const reservedTargets = reservedTargetIds(readEnvelope().shipments)
    const activeTransferIds = new Set(
      (
        await Promise.all(
          rentals.map(async (item) => ({
            id: item.id,
            active: await hasActiveWarehouseTransfer(item.id),
          }))
        )
      )
        .filter((item) => item.active)
        .map((item) => item.id)
    )
    return rentals
      .filter(
        (item) =>
          item.warehouseId === warehouseId &&
          !reservedTargets.has(item.id) &&
          availableTarget(item, company) &&
          !activeTransferIds.has(item.id) &&
          !hasUnresolvedReturnEquipmentDispositionLowLevel(item.id)
      )
      .map((item) => ({
        item: {
          id: item.id,
          version: item.version,
          number: item.number,
          status: item.status,
          tenant: item.tenant,
          contentsItems: cloneContents(item.contentsItems),
        },
        reservedForCompany:
          (item.status === "BOOKED" || item.status === "RESERVED") &&
          norm(item.tenant ?? "") === norm(company),
      }))
      .sort(
        (left, right) =>
          Number(right.reservedForCompany) - Number(left.reservedForCompany) ||
          left.item.number.localeCompare(right.item.number, "ru", {
            numeric: true,
          })
      )
  }

  async listSourceCabins(
    warehouseId: string,
    excludedIds: string[],
    exceptShipmentId: string | null = null
  ) {
    const excluded = new Set(excludedIds)
    const rentals = readRentalItems()
    const envelope = readEnvelope()
    const reservedTargets = reservedTargetIds(
      envelope.shipments,
      exceptShipmentId
    )
    const heldSources = aggregateAllocations(
      envelope.shipments.filter(
        (shipment) => shipment.warehouseId === warehouseId
      ),
      exceptShipmentId
    ).cabins
    const activeTransferIds = new Set(
      (
        await Promise.all(
          rentals.map(async (item) => ({
            id: item.id,
            active: await hasActiveWarehouseTransfer(item.id),
          }))
        )
      )
        .filter((item) => item.active)
        .map((item) => item.id)
    )
    return rentals
      .filter(
        (item) =>
          item.warehouseId === warehouseId &&
          !excluded.has(item.id) &&
          !reservedTargets.has(item.id) &&
          ["FREE", "WAREHOUSE", "OWN_NEEDS"].includes(item.status) &&
          item.contentsItems.some((entry) => entry.quantity > 0) &&
          !activeTransferIds.has(item.id) &&
          !hasUnresolvedReturnEquipmentDispositionLowLevel(item.id)
      )
      .map((item) => ({
        id: item.id,
        version: item.version,
        number: item.number,
        contentsItems: subtract(
          item.contentsItems,
          [...(heldSources.get(item.id) ?? new Map<string, number>())].map(
            ([name, quantity]) => ({ name, quantity })
          )
        ),
      }))
      .filter((item) => item.contentsItems.length > 0)
      .sort((left, right) =>
        left.number.localeCompare(right.number, "ru", { numeric: true })
      )
  }

  async saveDraft(input: ShipmentDraftInput) {
    return runRentalItemMutation(async () => {
      if (
        !input.company.trim() ||
        !input.driverId ||
        !/^\d{4}-\d{2}-\d{2}$/.test(input.shipmentDate)
      )
        throw new Error("Заполните компанию, дату и водителя")
      if (
        !input.items.length ||
        new Set(input.items.map((item) => item.rentalItemId)).size !==
          input.items.length
      )
        throw new Error("Выберите неповторяющиеся бытовки")
      const envelope = recoverIncompleteOperationsUnderLock()
      const existing = input.shipmentId
        ? envelope.shipments.find(
            (item) => item.id === input.shipmentId && activeReservation(item)
          )
        : null
      if (input.shipmentId && !existing)
        throw new Error("Черновик изменён или завершён")
      if (
        existing &&
        input.expectedVersion !== undefined &&
        existing.version !== input.expectedVersion
      )
        throw new Error("Черновик отгрузки изменён в другой вкладке")
      const targetsReservedElsewhere = reservedTargetIds(
        envelope.shipments,
        existing?.id
      )
      if (
        input.items.some((item) =>
          targetsReservedElsewhere.has(item.rentalItemId)
        )
      )
        throw new Error("Одна из бытовок уже включена в другую отгрузку")
      const rentals = readRentalItems()
      const activeTransferIds = new Set(
        (
          await Promise.all(
            rentals.map(async (item) => ({
              id: item.id,
              active: await hasActiveWarehouseTransfer(item.id),
            }))
          )
        )
          .filter((item) => item.active)
          .map((item) => item.id)
      )
      const snapshots: ShipmentItem[] = input.items.map((draft) => {
        const target = rentals.find(
          (item) =>
            item.id === draft.rentalItemId &&
            item.warehouseId === input.warehouseId
        )
        if (!target || !availableTarget(target, input.company))
          throw new Error("Одна из бытовок недоступна для отгрузки")
        if (activeTransferIds.has(target.id))
          throw new Error(`Бытовка ${target.number} участвует в перемещении`)
        if (
          draft.expectedTargetVersion === null ||
          target.version !== draft.expectedTargetVersion ||
          JSON.stringify(cloneContents(target.contentsItems)) !==
            JSON.stringify(cloneContents(draft.contentsBefore))
        )
          throw new Error(
            `Бытовка ${target.number} изменилась. Выберите её заново`
          )
        const changes = computeShipmentContentsChanges(
          draft.contentsBefore,
          draft.contentsPlanned
        )
        if (
          draft.preparationTask &&
          draft.preparationTask.externalTaskId !==
            stablePreparationExternalTaskId(
              shipmentPreparationIdentity({
                warehouseId: input.warehouseId,
                company: input.company,
                shipmentDate: input.shipmentDate,
                rentalItemId: target.id,
                changes,
                sourceAllocations: draft.sourceAllocations,
              })
            )
        )
          throw new Error(`Обновите задачу подготовки для ${target.number}`)
        return {
          rentalItemId: target.id,
          cabinNumber: target.number,
          expectedTargetVersion: target.version,
          contentsBefore: cloneContents(draft.contentsBefore),
          contentsPlanned: cloneContents(draft.contentsPlanned),
          changes,
          sourceAllocations: draft.sourceAllocations.map((allocation) => ({
            ...allocation,
            items: cloneContents(allocation.items),
          })),
          preparationState:
            draft.preparationTask?.dispatchStatus === "DISPATCHED"
              ? "TASK_SENT"
              : "PLANNED",
          preparationTask: draft.preparationTask
            ? structuredClone(draft.preparationTask)
            : null,
          conflict: null,
        }
      })
      validateAllocations(
        input.warehouseId,
        snapshots,
        envelope.shipments,
        existing?.id ?? null,
        rentals,
        readEquipment(),
        activeTransferIds
      )
      existing?.items.forEach((previous) => {
        if (previous.preparationTask?.dispatchStatus !== "DISPATCHED") return
        const next = snapshots.find(
          (item) => item.rentalItemId === previous.rentalItemId
        )
        if (JSON.stringify(next) !== JSON.stringify(previous))
          throw new Error(
            `Задача ${previous.cabinNumber} уже отправлена; сначала отмените отгрузку`
          )
      })
      const saved: Shipment = {
        id: existing?.id ?? crypto.randomUUID(),
        version: (existing?.version ?? 0) + 1,
        warehouseId: input.warehouseId,
        company: input.company.trim(),
        driverId: input.driverId,
        driverName: input.driverName,
        shipmentDate: input.shipmentDate,
        status: "PREPARING",
        error: null,
        createdAt: existing?.createdAt ?? new Date().toISOString(),
        createdBy: existing?.createdBy ?? input.createdBy,
        items: snapshots,
        audit: [
          ...(existing?.audit ?? []),
          event(
            existing ? "DRAFT_SAVED" : "STOCK_RESERVED",
            input.createdBy,
            existing
              ? "План подготовки обновлён"
              : "Источники мебели зарезервированы"
          ),
        ],
        evidence: "PROVEN",
        applicationAttempt: existing?.applicationAttempt ?? null,
        finalizationAttempt: existing?.finalizationAttempt ?? null,
      }
      replaceShipment(envelope, saved)
      return saved
    })
  }

  async dispatchPreparation(
    context: ShipmentCommandContext,
    shipmentId: string,
    rentalItemId: string
  ) {
    const envelope = readEnvelope()
    const shipment = envelope.shipments.find(
      (item) =>
        item.id === shipmentId &&
        item.warehouseId === context.warehouseId &&
        activeReservation(item)
    )
    const item = shipment?.items.find(
      (candidate) => candidate.rentalItemId === rentalItemId
    )
    if (!shipment || !item?.preparationTask)
      throw new Error("Задача подготовки не найдена")
    if (item.preparationTask.dispatchStatus === "DISPATCHED") return shipment
    const attemptId = crypto.randomUUID()
    const attemptedAt = new Date().toISOString()
    const pending: Shipment = {
      ...shipment,
      version: shipment.version + 1,
      status: "PREPARING",
      error: null,
      items: shipment.items.map((candidate) =>
        candidate.rentalItemId === rentalItemId && candidate.preparationTask
          ? {
              ...candidate,
              preparationTask: {
                ...candidate.preparationTask,
                dispatchStatus: "PENDING",
                dispatchAttemptId: attemptId,
                dispatchAttemptedAt: attemptedAt,
                dispatchError: null,
              },
            }
          : candidate
      ),
    }
    replaceShipment(envelope, pending)
    try {
      const result = await this.taskClient.dispatch(context.accessToken, {
        serviceWarehouseId: context.serviceWarehouseId,
        externalTaskId: item.preparationTask.externalTaskId,
        cabinNumber: item.cabinNumber,
        taskText: buildShipmentPreparationTaskText({
          cabinNumber: item.cabinNumber,
          changes: item.changes,
          sourceAllocations: item.sourceAllocations,
        }),
      })
      const latest = readEnvelope()
      const current = latest.shipments.find(
        (candidate) => candidate.id === shipmentId
      )!
      const saved: Shipment = {
        ...current,
        version: current.version + 1,
        status: current.items.every(
          (candidate) =>
            candidate.rentalItemId === rentalItemId ||
            candidate.changes.length === 0 ||
            candidate.preparationTask?.dispatchStatus === "DISPATCHED"
        )
          ? "AWAITING_CONFIRMATION"
          : "PREPARING",
        items: current.items.map((candidate) =>
          candidate.rentalItemId === rentalItemId && candidate.preparationTask
            ? {
                ...candidate,
                preparationState: "TASK_SENT",
                preparationTask: {
                  ...candidate.preparationTask,
                  dispatchStatus: "DISPATCHED",
                  boardTaskId: result.boardTaskId,
                  boardTaskVersion: result.boardTaskVersion,
                  queueId: result.queueId,
                  queueCode: result.queueCode,
                  dispatchError: null,
                },
              }
            : candidate
        ),
        audit: [
          ...current.audit,
          event(
            "TASK_DISPATCHED",
            context.actor,
            `Задача подготовки ${item.cabinNumber} зарегистрирована`
          ),
        ],
      }
      replaceShipment(latest, saved)
      return saved
    } catch (cause) {
      const latest = readEnvelope()
      const current = latest.shipments.find(
        (candidate) => candidate.id === shipmentId
      )!
      const message =
        cause instanceof Error ? cause.message : "Task-board недоступен"
      const failed: Shipment = {
        ...current,
        version: current.version + 1,
        status: "FAILED",
        error: message,
        items: current.items.map((candidate) =>
          candidate.rentalItemId === rentalItemId && candidate.preparationTask
            ? {
                ...candidate,
                preparationTask: {
                  ...candidate.preparationTask,
                  dispatchStatus: "FAILED",
                  dispatchError: message,
                },
              }
            : candidate
        ),
      }
      replaceShipment(latest, failed)
      throw cause
    }
  }

  async confirmPreparation(
    warehouseId: string,
    shipmentId: string,
    actor: string
  ) {
    return runRentalItemMutation(async () => {
      const envelope = recoverIncompleteOperationsUnderLock()
      const shipment = envelope.shipments.find(
        (item) =>
          item.id === shipmentId &&
          item.warehouseId === warehouseId &&
          activeReservation(item)
      )
      if (!shipment) throw new Error("Отгрузка не найдена")
      if (shipment.status === "READY_TO_SHIP") return shipment
      if (
        !["PREPARING", "FAILED", "AWAITING_CONFIRMATION", "CONFLICT"].includes(
          shipment.status
        )
      )
        throw new Error("Подготовка уже применяется или завершена")
      if (
        shipment.items.some(
          (item) =>
            item.changes.length > 0 &&
            item.preparationTask?.dispatchStatus !== "DISPATCHED"
        )
      )
        throw new Error("Сначала отправьте все задания подготовки")
      const rentalsBefore = readRentalItems()
      const equipmentBefore = readEquipment()
      try {
        const activeTransferIds = new Set(
          (
            await Promise.all(
              rentalsBefore.map(async (item) => ({
                id: item.id,
                active: await hasActiveWarehouseTransfer(item.id),
              }))
            )
          )
            .filter((item) => item.active)
            .map((item) => item.id)
        )
        if (
          shipment.items.some((item) =>
            activeTransferIds.has(item.rentalItemId)
          )
        )
          throw new Error(
            "Одна из бытовок участвует в межскладском перемещении"
          )
        validateAllocations(
          warehouseId,
          shipment.items,
          envelope.shipments,
          shipment.id,
          rentalsBefore,
          equipmentBefore,
          activeTransferIds
        )
        const targetIds = new Set(
          shipment.items.map((item) => item.rentalItemId)
        )
        shipment.items.forEach((item) => {
          const target = rentalsBefore.find(
            (rental) => rental.id === item.rentalItemId
          )
          if (
            !target ||
            target.version !== item.expectedTargetVersion ||
            JSON.stringify(cloneContents(target.contentsItems)) !==
              JSON.stringify(item.contentsBefore)
          )
            throw new Error(
              `Бытовка ${item.cabinNumber} изменилась. Требуется перепланирование`
            )
        })
        const cabinRemovals = new Map<string, RentalItemContentsItemDto[]>()
        const stockRemovals: RentalItemContentsItemDto[] = []
        shipment.items
          .flatMap((item) => item.sourceAllocations)
          .forEach((allocation) => {
            if (allocation.sourceType === "WAREHOUSE")
              stockRemovals.push(...allocation.items)
            else if (allocation.sourceRentalItemId)
              cabinRemovals.set(allocation.sourceRentalItemId, [
                ...(cabinRemovals.get(allocation.sourceRentalItemId) ?? []),
                ...allocation.items,
              ])
          })
        const rentalsAfter = rentalsBefore.map((rental) => {
          const target = shipment.items.find(
            (item) => item.rentalItemId === rental.id
          )
          if (target)
            return {
              ...rental,
              version: rental.version + 1,
              contentsItems: cloneContents(target.contentsPlanned),
              contents:
                target.contentsPlanned
                  .map((entry) => `${entry.name} ${entry.quantity} шт.`)
                  .join(", ") || null,
            }
          const removed = cabinRemovals.get(rental.id)
          if (removed && !targetIds.has(rental.id))
            return {
              ...rental,
              version: rental.version + 1,
              contentsItems: subtract(rental.contentsItems, removed),
              contents:
                subtract(rental.contentsItems, removed)
                  .map((entry) => `${entry.name} ${entry.quantity} шт.`)
                  .join(", ") || null,
            }
          return rental
        })
        const stockAggregate = normalizeContents(stockRemovals)
        const stockReturns = normalizeContents(
          shipment.items.flatMap((item) =>
            item.changes
              .filter((change) => change.direction === "TAKE")
              .map((change) => ({
                name: change.name,
                quantity: change.quantity,
              }))
          )
        )
        let equipmentAfter = equipmentBefore.map((stock) => {
          const removed =
            stock.warehouseId === warehouseId
              ? (stockAggregate.find(
                  (entry) => norm(entry.name) === norm(stock.name)
                )?.quantity ?? 0)
              : 0
          const returned =
            stock.warehouseId === warehouseId
              ? (stockReturns.find(
                  (entry) => norm(entry.name) === norm(stock.name)
                )?.quantity ?? 0)
              : 0
          return {
            ...stock,
            stockQuantity: stock.stockQuantity - removed + returned,
          }
        })
        stockReturns.forEach((returned) => {
          if (
            equipmentAfter.some(
              (stock) =>
                stock.warehouseId === warehouseId &&
                norm(stock.name) === norm(returned.name)
            )
          )
            return
          equipmentAfter = [
            ...equipmentAfter,
            {
              id: `shipment-stock:${warehouseId}:${encodeURIComponent(norm(returned.name))}`,
              warehouseId,
              name: returned.name,
              stockQuantity: returned.quantity,
              writtenOffQuantity: 0,
              lostQuantity: 0,
            },
          ]
        })
        if (equipmentAfter.some((item) => item.stockQuantity < 0))
          throw new Error(
            "Складской остаток изменился. Требуется перепланирование"
          )
        const applicationAttempt = {
          id: crypto.randomUUID(),
          phase: "PREPARED" as const,
          createdAt: new Date().toISOString(),
          actor,
          rentalMutations: rentalsAfter
            .filter((after) => {
              const before = rentalsBefore.find((item) => item.id === after.id)
              return before && !sameSnapshot(before, after)
            })
            .map((after) => ({
              id: after.id,
              before: structuredClone(
                rentalsBefore.find((item) => item.id === after.id)!
              ),
              after: structuredClone(after),
            })),
          equipmentMutations: [
            ...new Set([
              ...equipmentBefore.map((item) => item.id),
              ...equipmentAfter.map((item) => item.id),
            ]),
          ]
            .map((id) => ({
              id,
              before: equipmentBefore.find((item) => item.id === id) ?? null,
              after: equipmentAfter.find((item) => item.id === id),
            }))
            .filter(
              (
                mutation
              ): mutation is {
                id: string
                before: EquipmentMasterItem | null
                after: EquipmentMasterItem
              } =>
                mutation.after !== undefined &&
                !sameSnapshot(mutation.before, mutation.after)
            )
            .map((mutation) => structuredClone(mutation)),
        }
        const applying: Shipment = {
          ...shipment,
          version: shipment.version + 1,
          status: "APPLYING",
          error: null,
          applicationAttempt,
        }
        let applicationEnvelope = replaceShipment(envelope, applying)
        applyRentalMutations(applicationAttempt.rentalMutations)
        const rentalsWritten: Shipment = {
          ...applying,
          version: applying.version + 1,
          applicationAttempt: {
            ...applicationAttempt,
            phase: "RENTALS_WRITTEN",
          },
        }
        applicationEnvelope = replaceShipment(
          applicationEnvelope,
          rentalsWritten
        )
        applyEquipmentMutations(applicationAttempt.equipmentMutations)
        const equipmentWritten: Shipment = {
          ...rentalsWritten,
          version: rentalsWritten.version + 1,
          applicationAttempt: {
            ...applicationAttempt,
            phase: "EQUIPMENT_WRITTEN",
          },
        }
        applicationEnvelope = replaceShipment(
          applicationEnvelope,
          equipmentWritten
        )
        const saved: Shipment = {
          ...equipmentWritten,
          version: equipmentWritten.version + 1,
          status: "READY_TO_SHIP",
          error: null,
          applicationAttempt: null,
          items: shipment.items.map((item) => ({
            ...item,
            expectedTargetVersion:
              applicationAttempt.rentalMutations.find(
                (mutation) => mutation.id === item.rentalItemId
              )?.after.version ?? item.expectedTargetVersion,
            contentsBefore: cloneContents(item.contentsPlanned),
            preparationState: "READY",
            conflict: null,
          })),
          audit: [
            ...shipment.audit,
            event(
              "PREPARATION_CONFIRMED",
              actor,
              "Фактическая подготовка подтверждена; мебель перемещена атомарно"
            ),
          ],
        }
        replaceShipment(applicationEnvelope, saved)
        return saved
      } catch (cause) {
        const message =
          cause instanceof Error ? cause.message : "Конфликт подготовки"
        const interrupted = readEnvelope().shipments.find(
          (candidate) =>
            candidate.id === shipment.id && candidate.status === "APPLYING"
        )
        if (interrupted?.applicationAttempt) {
          recoverIncompleteOperationsUnderLock()
          throw cause
        }
        const conflicted: Shipment = {
          ...shipment,
          version: shipment.version + 1,
          status: "CONFLICT",
          error: message,
          applicationAttempt: null,
          items: shipment.items.map((item) => ({
            ...item,
            preparationState: "CONFLICT",
            conflict: message,
          })),
          audit: [
            ...shipment.audit,
            event("PREPARATION_CONFLICT", actor, message),
          ],
        }
        replaceShipment(readEnvelope(), conflicted)
        throw cause
      }
    })
  }

  async finalize(warehouseId: string, shipmentId: string, actor: string) {
    return runRentalItemMutation(async () => {
      const envelope = recoverIncompleteOperationsUnderLock()
      const shipment = envelope.shipments.find(
        (item) => item.id === shipmentId && item.warehouseId === warehouseId
      )
      if (shipment?.status === "SHIPPED") return shipment
      if (!shipment || shipment.status !== "READY_TO_SHIP")
        throw new Error("Сначала подтвердите подготовку")
      if (
        (
          await Promise.all(
            shipment.items.map((item) =>
              hasActiveWarehouseTransfer(item.rentalItemId)
            )
          )
        ).some(Boolean)
      )
        throw new Error("Одна из бытовок участвует в межскладском перемещении")
      const rentals = readRentalItems()
      shipment.items.forEach((item) => {
        const target = rentals.find((rental) => rental.id === item.rentalItemId)
        if (
          !target ||
          target.version !== item.expectedTargetVersion ||
          JSON.stringify(cloneContents(target.contentsItems)) !==
            JSON.stringify(cloneContents(item.contentsPlanned))
        )
          throw new Error(`Бытовка ${item.cabinNumber} изменилась`)
      })
      const rentalsAfter = rentals.map((rental) => {
        const item = shipment.items.find(
          (candidate) => candidate.rentalItemId === rental.id
        )
        return item
          ? {
              ...rental,
              version: rental.version + 1,
              status: "RENTED" as const,
              tenant: shipment.company,
              shipmentDate: shipment.shipmentDate,
              contentsItems: cloneContents(item.contentsPlanned),
            }
          : rental
      })
      const finalizationAttempt = {
        id: crypto.randomUUID(),
        phase: "PREPARED" as const,
        createdAt: new Date().toISOString(),
        actor,
        rentalMutations: rentalsAfter
          .filter((after) => {
            const before = rentals.find((item) => item.id === after.id)
            return before && !sameSnapshot(before, after)
          })
          .map((after) => ({
            id: after.id,
            before: structuredClone(
              rentals.find((item) => item.id === after.id)!
            ),
            after: structuredClone(after),
          })),
      }
      const finalizing: Shipment = {
        ...shipment,
        version: shipment.version + 1,
        status: "FINALIZING",
        error: null,
        finalizationAttempt,
      }
      let finalizationEnvelope = replaceShipment(envelope, finalizing)
      applyRentalMutations(finalizationAttempt.rentalMutations)
      const rentalsWritten: Shipment = {
        ...finalizing,
        version: finalizing.version + 1,
        finalizationAttempt: {
          ...finalizationAttempt,
          phase: "RENTALS_WRITTEN",
        },
      }
      finalizationEnvelope = replaceShipment(
        finalizationEnvelope,
        rentalsWritten
      )
      const saved: Shipment = {
        ...rentalsWritten,
        version: rentalsWritten.version + 1,
        status: "SHIPPED",
        error: null,
        finalizationAttempt: null,
        audit: [
          ...shipment.audit,
          event(
            "SHIPMENT_FINALIZED",
            actor,
            `Отгружено: ${shipment.items.map((item) => item.cabinNumber).join(", ")}`
          ),
        ],
      }
      replaceShipment(finalizationEnvelope, saved)
      return saved
    })
  }

  async cancel(
    context: ShipmentCommandContext,
    shipmentId: string,
    reason: string
  ) {
    if (!reason.trim()) throw new Error("Укажите причину отмены")
    const envelope = readEnvelope()
    const shipment = envelope.shipments.find(
      (item) =>
        item.id === shipmentId && item.warehouseId === context.warehouseId
    )
    if (shipment?.status === "CANCELLED") return shipment
    if (
      !shipment ||
      shipment.status === "SHIPPED" ||
      shipment.status === "READY_TO_SHIP" ||
      shipment.status === "APPLYING" ||
      shipment.status === "FINALIZING"
    )
      throw new Error("Эту отгрузку уже нельзя отменить")
    let currentShipment = shipment
    for (const item of currentShipment.items) {
      const task = item.preparationTask
      if (task?.dispatchStatus === "DISPATCHED") {
        if (task.boardTaskVersion === null)
          throw new Error(
            `Не удалось безопасно отменить старое задание ${item.cabinNumber}: версия task-board неизвестна`
          )
        const cancelled = await this.taskClient.cancel(context.accessToken, {
          serviceWarehouseId: context.serviceWarehouseId,
          externalTaskId: task.externalTaskId,
          expectedTaskVersion: task.boardTaskVersion,
          reason,
        })
        const latest = readEnvelope()
        const persisted = latest.shipments.find(
          (candidate) => candidate.id === shipmentId
        )!
        currentShipment = {
          ...persisted,
          version: persisted.version + 1,
          items: persisted.items.map((candidate) =>
            candidate.rentalItemId === item.rentalItemId &&
            candidate.preparationTask
              ? {
                  ...candidate,
                  preparationTask: {
                    ...candidate.preparationTask,
                    dispatchStatus: "CANCELLED",
                    boardTaskVersion: cancelled.boardTaskVersion,
                  },
                }
              : candidate
          ),
        }
        replaceShipment(latest, currentShipment)
      }
    }
    const latest = readEnvelope()
    const current = latest.shipments.find((item) => item.id === shipmentId)!
    const saved: Shipment = {
      ...current,
      version: current.version + 1,
      status: "CANCELLED",
      error: null,
      items: current.items.map((item) => ({
        ...item,
        preparationTask: item.preparationTask
          ? { ...item.preparationTask, dispatchStatus: "CANCELLED" }
          : null,
      })),
      audit: [
        ...current.audit,
        event("SHIPMENT_CANCELLED", context.actor, reason.trim()),
      ],
    }
    replaceShipment(latest, saved)
    return saved
  }
}
