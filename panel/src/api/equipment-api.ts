import {
  getRentalItemsForEquipmentInventory,
  RENTAL_ITEM_NON_RENTED_ACTIVE_STATUSES,
  RENTAL_ITEMS_MOCK_UPDATED_EVENT,
  moveRentalItemContentsToStock,
  readRentalItems,
  runRentalItemMutation,
  writeRentalItems,
} from "@/features/rental-items/api/rental-items-api"
import type {
  EquipmentDispositionListItemDto,
  EquipmentItemDto,
  EquipmentItemsQueryParams,
  EquipmentRentalUsageDto,
  EquipmentWriteOffSummaryDto,
  RegisterReturnEquipmentDispositionInput,
  ResolveReturnEquipmentDispositionInput,
  ReturnEquipmentDispositionCaseDto,
  UpdateEquipmentUsagePayload,
} from "@/types/equipment"
import type {
  MoveRentalItemContentToStockPayload,
  RentalItemDto,
} from "@/features/rental-items/model/rental-item"
import { hasUnresolvedReturnEquipmentDispositionLowLevel } from "@/features/equipment/return-equipment-disposition-guard"
import type { WarehouseInventoryStockItemDto } from "@/types/warehouse-location"
export const EQUIPMENT_MOCK_STORAGE_KEY = "wms:mock-equipment-master"
export const EQUIPMENT_MOCK_UPDATED_EVENT = "wms:mock-equipment-items-updated"
export const EQUIPMENT_DISPOSITIONS_STORAGE_KEY =
  "wms:mock-return-equipment-dispositions"
export const EQUIPMENT_DISPOSITIONS_UPDATED_EVENT =
  "wms:mock-return-equipment-dispositions-updated"
const EQUIPMENT_DISPOSITION_JOURNAL_KEY =
  "wms:mock-return-equipment-disposition-journal"
const RETURN_EQUIPMENT_PROCESSING_STATUSES = [
  "AFTER_RENT",
  "WAITING_ESTIMATE_CONFIRMATION",
  "REPAIR",
  "WAITING_REPAIR_CHECK",
] as const

type EquipmentMasterItem = {
  id: string
  warehouseId: string
  name: string
  stockQuantity: number
  writtenOffQuantity: number
  lostQuantity: number
}

type EquipmentDispositionState = {
  service: "return-equipment-dispositions"
  schemaVersion: 1
  revision: number
  cases: ReturnEquipmentDispositionCaseDto[]
}

type EquipmentDispositionJournal = {
  id: string
  state: "PREPARED"
  rentalItems: Array<{
    id: string
    before: RentalItemDto
    after: RentalItemDto
  }>
  masterItems: Array<{
    id: string
    before: EquipmentMasterItem | null
    after: EquipmentMasterItem
  }>
  dispositionCases: Array<{
    id: string
    before: ReturnEquipmentDispositionCaseDto
    after: ReturnEquipmentDispositionCaseDto
  }>
}

const EMPTY_DISPOSITION_STATE: EquipmentDispositionState = {
  service: "return-equipment-dispositions",
  schemaVersion: 1,
  revision: 0,
  cases: [],
}

function delay<T>(data: T, timeout = 200): Promise<T> {
  return new Promise((resolve) => {
    window.setTimeout(() => resolve(data), timeout)
  })
}

function normalizeName(value: string) {
  return value.trim().toLowerCase()
}

function createMasterItem(params: {
  id: string
  warehouseId: string
  name: string
  stockQuantity: number
  writtenOffQuantity?: number
  lostQuantity?: number
}): EquipmentMasterItem {
  return {
    id: params.id,
    warehouseId: params.warehouseId,
    name: params.name,
    stockQuantity: params.stockQuantity,
    writtenOffQuantity: params.writtenOffQuantity ?? 0,
    lostQuantity: params.lostQuantity ?? 0,
  }
}

function createInitialEquipmentMasterItems(): EquipmentMasterItem[] {
  return [
    createMasterItem({
      id: "spb-table",
      warehouseId: "spb",
      name: "Стол",
      stockQuantity: 0,
      writtenOffQuantity: 2,
      lostQuantity: 1,
    }),
    createMasterItem({
      id: "spb-office-table",
      warehouseId: "spb",
      name: "Стол офисный",
      stockQuantity: 1,
      writtenOffQuantity: 1,
      lostQuantity: 0,
    }),
    createMasterItem({
      id: "spb-bench",
      warehouseId: "spb",
      name: "Лавка",
      stockQuantity: 20,
      writtenOffQuantity: 0,
      lostQuantity: 2,
    }),
    createMasterItem({
      id: "spb-chair",
      warehouseId: "spb",
      name: "Стул",
      stockQuantity: 0,
      writtenOffQuantity: 5,
      lostQuantity: 3,
    }),
    createMasterItem({
      id: "spb-bed",
      warehouseId: "spb",
      name: "Кровать",
      stockQuantity: 24,
      writtenOffQuantity: 1,
      lostQuantity: 1,
    }),
    createMasterItem({
      id: "spb-bunk-bed",
      warehouseId: "spb",
      name: "Кровать 2-ярусная",
      stockQuantity: 0,
      writtenOffQuantity: 2,
      lostQuantity: 0,
    }),
    createMasterItem({
      id: "spb-wardrobe",
      warehouseId: "spb",
      name: "Шкаф",
      stockQuantity: 0,
      writtenOffQuantity: 0,
      lostQuantity: 0,
    }),
    createMasterItem({
      id: "spb-convector",
      warehouseId: "spb",
      name: "Конвектор",
      stockQuantity: 24,
      writtenOffQuantity: 3,
      lostQuantity: 2,
    }),
    createMasterItem({
      id: "spb-conditioner",
      warehouseId: "spb",
      name: "Кондиционер",
      stockQuantity: 11,
      writtenOffQuantity: 1,
      lostQuantity: 0,
    }),

    createMasterItem({
      id: "msk-table",
      warehouseId: "msk",
      name: "Стол",
      stockQuantity: 12,
      writtenOffQuantity: 1,
      lostQuantity: 0,
    }),
    createMasterItem({
      id: "msk-office-table",
      warehouseId: "msk",
      name: "Стол офисный",
      stockQuantity: 6,
      writtenOffQuantity: 0,
      lostQuantity: 1,
    }),
    createMasterItem({
      id: "msk-chair",
      warehouseId: "msk",
      name: "Стул",
      stockQuantity: 20,
      writtenOffQuantity: 2,
      lostQuantity: 1,
    }),
    createMasterItem({
      id: "msk-bench",
      warehouseId: "msk",
      name: "Лавка",
      stockQuantity: 8,
      writtenOffQuantity: 0,
      lostQuantity: 1,
    }),
    createMasterItem({
      id: "msk-bed",
      warehouseId: "msk",
      name: "Кровать",
      stockQuantity: 10,
      writtenOffQuantity: 1,
      lostQuantity: 2,
    }),
    createMasterItem({
      id: "msk-bunk-bed",
      warehouseId: "msk",
      name: "Кровать 2-ярусная",
      stockQuantity: 5,
      writtenOffQuantity: 1,
      lostQuantity: 0,
    }),
    createMasterItem({
      id: "msk-wardrobe",
      warehouseId: "msk",
      name: "Шкаф",
      stockQuantity: 7,
      writtenOffQuantity: 0,
      lostQuantity: 0,
    }),
  ]
}

let equipmentMasterCache: EquipmentMasterItem[] | null = null

function normalizeMasterItem(
  item: Partial<EquipmentMasterItem>
): EquipmentMasterItem | null {
  if (!item.id || !item.warehouseId || !item.name) {
    return null
  }

  return {
    id: item.id,
    warehouseId: item.warehouseId,
    name: item.name,
    stockQuantity: item.stockQuantity ?? 0,
    writtenOffQuantity: item.writtenOffQuantity ?? 0,
    lostQuantity: item.lostQuantity ?? 0,
  }
}

function safeParseMasterItems(
  value: string | null
): EquipmentMasterItem[] | null {
  if (!value) {
    return null
  }

  try {
    const parsed = JSON.parse(value)

    if (!Array.isArray(parsed)) {
      return null
    }

    return parsed
      .map((item) => normalizeMasterItem(item))
      .filter((item): item is EquipmentMasterItem => item !== null)
  } catch {
    return null
  }
}

function readEquipmentMasterItems(): EquipmentMasterItem[] {
  if (typeof window === "undefined") {
    return equipmentMasterCache ?? createInitialEquipmentMasterItems()
  }

  const storedItems = safeParseMasterItems(
    window.localStorage.getItem(EQUIPMENT_MOCK_STORAGE_KEY)
  )

  if (storedItems) {
    equipmentMasterCache = storedItems
    return storedItems
  }

  const initialItems = createInitialEquipmentMasterItems()
  writeEquipmentMasterItems(initialItems, false)

  return initialItems
}

function writeEquipmentMasterItems(
  items: EquipmentMasterItem[],
  emitEvent = true
) {
  equipmentMasterCache = items

  if (typeof window === "undefined") {
    return
  }

  window.localStorage.setItem(EQUIPMENT_MOCK_STORAGE_KEY, JSON.stringify(items))

  if (emitEvent) {
    window.dispatchEvent(new Event(EQUIPMENT_MOCK_UPDATED_EVENT))
  }
}

function normalizeDispositionKey(value: string) {
  return value.trim().replace(/\s+/g, " ").toLocaleLowerCase("ru-RU")
}

function dispositionCaseId(returnItemId: string, normalizedKey: string) {
  return `return-equipment:${returnItemId}:${encodeURIComponent(normalizedKey)}`
}

function readDispositionStateRaw(): EquipmentDispositionState {
  if (typeof window === "undefined") return EMPTY_DISPOSITION_STATE

  const value = window.localStorage.getItem(EQUIPMENT_DISPOSITIONS_STORAGE_KEY)
  if (!value) return EMPTY_DISPOSITION_STATE

  try {
    const parsed = JSON.parse(value) as Partial<EquipmentDispositionState>
    if (
      parsed.service !== "return-equipment-dispositions" ||
      parsed.schemaVersion !== 1 ||
      !Array.isArray(parsed.cases)
    ) {
      return EMPTY_DISPOSITION_STATE
    }

    return {
      service: "return-equipment-dispositions",
      schemaVersion: 1,
      revision: Number.isInteger(parsed.revision) ? parsed.revision! : 0,
      cases: parsed.cases,
    }
  } catch {
    return EMPTY_DISPOSITION_STATE
  }
}

function writeDispositionState(
  state: EquipmentDispositionState,
  emitEvent = true
) {
  if (typeof window === "undefined") return
  window.localStorage.setItem(
    EQUIPMENT_DISPOSITIONS_STORAGE_KEY,
    JSON.stringify(state)
  )
  if (emitEvent) {
    window.dispatchEvent(new Event(EQUIPMENT_DISPOSITIONS_UPDATED_EVENT))
  }
}

function readDispositionJournal(): EquipmentDispositionJournal | null {
  if (typeof window === "undefined") return null
  const value = window.localStorage.getItem(EQUIPMENT_DISPOSITION_JOURNAL_KEY)
  if (!value) return null
  try {
    const parsed = JSON.parse(value) as EquipmentDispositionJournal
    return parsed.state === "PREPARED" ? parsed : null
  } catch {
    return null
  }
}

function recoverEquipmentDispositionJournal() {
  const journal = readDispositionJournal()
  if (!journal || typeof window === "undefined") return false

  const currentRentalItems = readRentalItems()
  const currentMasterItems = readEquipmentMasterItems()
  const currentDispositionState = readDispositionStateRaw()
  const rentalConflict = journal.rentalItems.some((change) => {
    const current = currentRentalItems.find((item) => item.id === change.id)
    return (
      JSON.stringify(current) !== JSON.stringify(change.before) &&
      JSON.stringify(current) !== JSON.stringify(change.after)
    )
  })
  const masterConflict = journal.masterItems.some((change) => {
    const current =
      currentMasterItems.find((item) => item.id === change.id) ?? null
    return (
      JSON.stringify(current) !== JSON.stringify(change.before) &&
      JSON.stringify(current) !== JSON.stringify(change.after)
    )
  })
  const caseConflict = journal.dispositionCases.some((change) => {
    const current =
      currentDispositionState.cases.find((item) => item.id === change.id) ??
      null
    return (
      JSON.stringify(current) !== JSON.stringify(change.before) &&
      JSON.stringify(current) !== JSON.stringify(change.after)
    )
  })
  if (rentalConflict || masterConflict || caseConflict) {
    throw new Error(
      "Незавершённая операция оборудования конфликтует с новыми изменениями"
    )
  }

  let rentalChanged = false
  const restoredRentalItems = currentRentalItems.map((item) => {
    const change = journal.rentalItems.find(
      (candidate) => candidate.id === item.id
    )
    if (!change || JSON.stringify(item) !== JSON.stringify(change.after))
      return item
    rentalChanged = true
    return change.before
  })
  let masterChanged = false
  let restoredMasterItems = [...currentMasterItems]
  journal.masterItems.forEach((change) => {
    const index = restoredMasterItems.findIndex((item) => item.id === change.id)
    const current = index === -1 ? null : restoredMasterItems[index]
    if (JSON.stringify(current) !== JSON.stringify(change.after)) return
    masterChanged = true
    if (change.before === null) {
      restoredMasterItems = restoredMasterItems.filter(
        (item) => item.id !== change.id
      )
    } else if (index === -1) {
      restoredMasterItems.push(change.before)
    } else {
      restoredMasterItems[index] = change.before
    }
  })
  let dispositionChanged = false
  const restoredCases = currentDispositionState.cases.map((item) => {
    const change = journal.dispositionCases.find(
      (candidate) => candidate.id === item.id
    )
    if (!change || JSON.stringify(item) !== JSON.stringify(change.after))
      return item
    dispositionChanged = true
    return change.before
  })
  if (rentalChanged) writeRentalItems(restoredRentalItems, false)
  if (masterChanged) writeEquipmentMasterItems(restoredMasterItems, false)
  if (dispositionChanged) {
    writeDispositionState(
      {
        ...currentDispositionState,
        revision: currentDispositionState.revision + 1,
        cases: restoredCases,
      },
      false
    )
  }
  window.localStorage.removeItem(EQUIPMENT_DISPOSITION_JOURNAL_KEY)
  window.dispatchEvent(new Event(RENTAL_ITEMS_MOCK_UPDATED_EVENT))
  window.dispatchEvent(new Event(EQUIPMENT_MOCK_UPDATED_EVENT))
  window.dispatchEvent(new Event(EQUIPMENT_DISPOSITIONS_UPDATED_EVENT))
  return true
}

function normalizedDispositionContents(
  contents: RegisterReturnEquipmentDispositionInput["contents"]
) {
  const byKey = new Map<string, { name: string; quantity: number }>()

  contents.forEach((item) => {
    const key = normalizeDispositionKey(item.name)
    if (!key || !Number.isInteger(item.quantity) || item.quantity <= 0) return
    const current = byKey.get(key)
    byKey.set(key, {
      name: current?.name ?? item.name.trim().replace(/\s+/g, " "),
      quantity: (current?.quantity ?? 0) + item.quantity,
    })
  })

  return Array.from(byKey, ([normalizedEquipmentKey, item]) => ({
    normalizedEquipmentKey,
    ...item,
  }))
}

function subtractExactContent(
  items: RentalItemDto["contentsItems"],
  normalizedKey: string,
  quantity: number
) {
  const index = items.findIndex(
    (item) => normalizeDispositionKey(item.name) === normalizedKey
  )
  const current = index === -1 ? null : items[index]
  if (!current || current.quantity < quantity) {
    throw new Error(
      "Наполнение бытовки изменилось. Обновите данные и повторите"
    )
  }

  return items
    .map((item, itemIndex) =>
      itemIndex === index
        ? { ...item, quantity: item.quantity - quantity }
        : item
    )
    .filter((item) => item.quantity > 0)
}

function addExactContent(
  items: RentalItemDto["contentsItems"],
  name: string,
  normalizedKey: string,
  quantity: number
) {
  const index = items.findIndex(
    (item) => normalizeDispositionKey(item.name) === normalizedKey
  )
  if (index === -1) return [...items, { name, quantity }]
  return items.map((item, itemIndex) =>
    itemIndex === index ? { ...item, quantity: item.quantity + quantity } : item
  )
}

function updatedDispositionStatus(remainingQuantity: number) {
  if (remainingQuantity === 0) return "RESOLVED" as const
  return "PARTIALLY_RESOLVED" as const
}

function emitEquipmentUpdated() {
  if (typeof window === "undefined") {
    return
  }

  window.dispatchEvent(new Event(EQUIPMENT_MOCK_UPDATED_EVENT))
}

function getMasterId(params: {
  warehouseId: string
  name: string
  masterItems: EquipmentMasterItem[]
}) {
  const normalizedName = normalizeName(params.name)

  const masterItem = params.masterItems.find((item) => {
    return (
      item.warehouseId === params.warehouseId &&
      normalizeName(item.name) === normalizedName
    )
  })

  return masterItem?.id ?? `dynamic:${params.warehouseId}:${normalizedName}`
}

function getActualMovePayload(
  rentalItem: RentalItemDto,
  payload: MoveRentalItemContentToStockPayload[]
): MoveRentalItemContentToStockPayload[] {
  return payload
    .map((moveItem) => {
      const contentItem = rentalItem.contentsItems.find((item) => {
        return normalizeName(item.name) === normalizeName(moveItem.name)
      })

      if (!contentItem) {
        return null
      }

      const quantity = Math.min(
        Math.max(0, moveItem.quantity),
        contentItem.quantity
      )

      if (quantity <= 0) {
        return null
      }

      return {
        name: contentItem.name,
        quantity,
      }
    })
    .filter(
      (item): item is MoveRentalItemContentToStockPayload => item !== null
    )
}

function increaseEquipmentStock(params: {
  warehouseId: string
  payload: MoveRentalItemContentToStockPayload[]
}) {
  if (params.payload.length === 0) {
    return
  }

  const masterItems = readEquipmentMasterItems()

  let nextMasterItems = [...masterItems]

  params.payload.forEach((moveItem) => {
    const normalizedMoveName = normalizeName(moveItem.name)

    const index = nextMasterItems.findIndex((item) => {
      return (
        item.warehouseId === params.warehouseId &&
        normalizeName(item.name) === normalizedMoveName
      )
    })

    if (index === -1) {
      nextMasterItems = [
        ...nextMasterItems,
        {
          id: `dynamic:${params.warehouseId}:${normalizedMoveName}`,
          warehouseId: params.warehouseId,
          name: moveItem.name,
          stockQuantity: moveItem.quantity,
          writtenOffQuantity: 0,
          lostQuantity: 0,
        },
      ]

      return
    }

    nextMasterItems = nextMasterItems.map((item, itemIndex) => {
      if (itemIndex !== index) {
        return item
      }

      return {
        ...item,
        stockQuantity: item.stockQuantity + moveItem.quantity,
      }
    })
  })

  writeEquipmentMasterItems(nextMasterItems)
}

/** Browser-mock transaction participant for factual return furniture allocation. */
export function prepareReturnFurnitureEquipmentAllocation(input: {
  warehouseId: string
  name: string
  quantity: number
  action: "RETURN_TO_STOCK" | "WRITE_OFF"
}) {
  if (!Number.isInteger(input.quantity) || input.quantity < 1)
    throw new Error("Количество должно быть больше 0")
  const before = readEquipmentMasterItems()
  const normalizedName = normalizeName(input.name)
  const index = before.findIndex(
    (item) =>
      item.warehouseId === input.warehouseId &&
      normalizeName(item.name) === normalizedName
  )
  const after = [...before]
  const current =
    index >= 0
      ? after[index]
      : createMasterItem({
          id: `dynamic:${input.warehouseId}:${normalizedName}`,
          warehouseId: input.warehouseId,
          name: input.name,
          stockQuantity: 0,
        })
  const saved = {
    ...current,
    stockQuantity:
      current.stockQuantity +
      (input.action === "RETURN_TO_STOCK" ? input.quantity : 0),
    writtenOffQuantity:
      current.writtenOffQuantity +
      (input.action === "WRITE_OFF" ? input.quantity : 0),
  }
  if (index >= 0) after[index] = saved
  else after.push(saved)
  let committed = false
  return {
    commit() {
      writeEquipmentMasterItems(after)
      emitEquipmentUpdated()
      committed = true
    },
    rollback() {
      if (!committed) return
      const currentState = readEquipmentMasterItems()
      if (JSON.stringify(currentState) !== JSON.stringify(after))
        throw new Error("Остатки оборудования изменились после распределения")
      writeEquipmentMasterItems(before)
      emitEquipmentUpdated()
      committed = false
    },
  }
}

const RETURN_FURNITURE_LEDGER_JOURNAL_KEY =
  "rwms:return-furniture-ledger-journal:v1"

type ReturnFurnitureLedgerAttempt = {
  idempotencyKey: string
  warehouseId: string
  name: string
  quantity: number
  action: "RETURN_TO_STOCK" | "WRITE_OFF" | "LOST"
  phase: "PREPARED" | "APPLIED"
  before: EquipmentMasterItem[]
  after: EquipmentMasterItem[]
}

function readReturnFurnitureLedgerJournal(): ReturnFurnitureLedgerAttempt[] {
  if (typeof window === "undefined") return []
  try {
    const value = JSON.parse(
      window.localStorage.getItem(RETURN_FURNITURE_LEDGER_JOURNAL_KEY) ?? "[]"
    )
    return Array.isArray(value) ? (value as ReturnFurnitureLedgerAttempt[]) : []
  } catch {
    return []
  }
}

function writeReturnFurnitureLedgerAttempt(
  attempt: ReturnFurnitureLedgerAttempt
) {
  if (typeof window === "undefined") return
  const other = readReturnFurnitureLedgerJournal().filter(
    (entry) => entry.idempotencyKey !== attempt.idempotencyKey
  )
  window.localStorage.setItem(
    RETURN_FURNITURE_LEDGER_JOURNAL_KEY,
    JSON.stringify([attempt, ...other].slice(0, 200))
  )
}

export function applyReturnFurnitureEquipmentAllocation(input: {
  idempotencyKey: string
  warehouseId: string
  name: string
  quantity: number
  action: "RETURN_TO_STOCK" | "WRITE_OFF" | "LOST"
}) {
  if (!input.idempotencyKey.trim()) throw new Error("Не задан ключ ledger")
  const replay = readReturnFurnitureLedgerJournal().find(
    (entry) => entry.idempotencyKey === input.idempotencyKey
  )
  if (replay) {
    if (
      replay.warehouseId !== input.warehouseId ||
      normalizeName(replay.name) !== normalizeName(input.name) ||
      replay.quantity !== input.quantity ||
      replay.action !== input.action
    )
      throw new Error("Ключ ledger уже использован с другими данными")
    const current = readEquipmentMasterItems()
    if (JSON.stringify(current) === JSON.stringify(replay.after)) {
      if (replay.phase !== "APPLIED")
        writeReturnFurnitureLedgerAttempt({ ...replay, phase: "APPLIED" })
      return
    }
    if (JSON.stringify(current) !== JSON.stringify(replay.before))
      throw new Error("Остатки оборудования изменились во время восстановления")
    writeEquipmentMasterItems(replay.after)
    writeReturnFurnitureLedgerAttempt({ ...replay, phase: "APPLIED" })
    emitEquipmentUpdated()
    return
  }
  if (!Number.isInteger(input.quantity) || input.quantity < 1)
    throw new Error("Количество должно быть больше 0")
  const before = readEquipmentMasterItems()
  const normalizedName = normalizeName(input.name)
  const index = before.findIndex(
    (item) =>
      item.warehouseId === input.warehouseId &&
      normalizeName(item.name) === normalizedName
  )
  const after = [...before]
  const current =
    index >= 0
      ? after[index]
      : createMasterItem({
          id: `dynamic:${input.warehouseId}:${normalizedName}`,
          warehouseId: input.warehouseId,
          name: input.name,
          stockQuantity: 0,
        })
  const saved = {
    ...current,
    stockQuantity:
      current.stockQuantity +
      (input.action === "RETURN_TO_STOCK" ? input.quantity : 0),
    writtenOffQuantity:
      current.writtenOffQuantity +
      (input.action === "WRITE_OFF" ? input.quantity : 0),
    lostQuantity:
      current.lostQuantity + (input.action === "LOST" ? input.quantity : 0),
  }
  if (index >= 0) after[index] = saved
  else after.push(saved)
  const attempt: ReturnFurnitureLedgerAttempt = {
    ...input,
    phase: "PREPARED",
    before,
    after,
  }
  writeReturnFurnitureLedgerAttempt(attempt)
  writeEquipmentMasterItems(after)
  writeReturnFurnitureLedgerAttempt({ ...attempt, phase: "APPLIED" })
  emitEquipmentUpdated()
}

function buildUsagesForName(params: {
  equipmentName: string
  equipmentId: string
  rentalItems: RentalItemDto[]
}): EquipmentRentalUsageDto[] {
  const equipmentName = normalizeName(params.equipmentName)

  return params.rentalItems.flatMap((rentalItem) => {
    const contentItem = rentalItem.contentsItems.find((item) => {
      return normalizeName(item.name) === equipmentName && item.quantity > 0
    })

    if (!contentItem) {
      return []
    }

    return [
      {
        id: `${params.equipmentId}:${rentalItem.id}`,
        rentalItemId: rentalItem.id,
        rentalItemNumber: rentalItem.number,
        rentalItemType: rentalItem.type,
        rentalItemStatus: rentalItem.status,
        warehouseId: rentalItem.warehouseId,
        quantity: contentItem.quantity,
      },
    ]
  })
}

async function buildEquipmentItems(
  warehouseId: string
): Promise<EquipmentItemDto[]> {
  const masterItems = readEquipmentMasterItems()
  const rentalItems = await getRentalItemsForEquipmentInventory(warehouseId)

  const warehouseMasterItems = masterItems.filter((item) => {
    return item.warehouseId === warehouseId
  })

  const namesFromMaster = warehouseMasterItems.map((item) => item.name)

  const namesFromRentalItems = rentalItems.flatMap((rentalItem) => {
    return rentalItem.contentsItems
      .filter((item) => item.quantity > 0)
      .map((item) => item.name)
  })

  const uniqueNames = Array.from(
    new Map(
      [...namesFromMaster, ...namesFromRentalItems].map((name) => [
        normalizeName(name),
        name,
      ])
    ).values()
  )

  const items = uniqueNames.map((name): EquipmentItemDto => {
    const normalizedName = normalizeName(name)

    const masterItem = warehouseMasterItems.find((item) => {
      return normalizeName(item.name) === normalizedName
    })

    const equipmentId =
      masterItem?.id ??
      getMasterId({
        warehouseId,
        name,
        masterItems,
      })

    const usages = buildUsagesForName({
      equipmentName: name,
      equipmentId,
      rentalItems,
    })

    const rentedQuantity = usages.reduce((sum, usage) => {
      if (usage.rentalItemStatus !== "RENTED") {
        return sum
      }

      return sum + usage.quantity
    }, 0)

    const cabinStockQuantity = usages.reduce((sum, usage) => {
      if (usage.rentalItemStatus === "RENTED") {
        return sum
      }

      return sum + usage.quantity
    }, 0)

    const stockQuantity = masterItem?.stockQuantity ?? 0
    const writtenOffQuantity = masterItem?.writtenOffQuantity ?? 0
    const lostQuantity = masterItem?.lostQuantity ?? 0

    const totalQuantity =
      stockQuantity +
      cabinStockQuantity +
      rentedQuantity +
      writtenOffQuantity +
      lostQuantity

    return {
      id: equipmentId,
      warehouseId,
      category: "FURNITURE",
      name: masterItem?.name ?? name,

      totalQuantity,
      stockQuantity,
      cabinStockQuantity,
      rentedQuantity,
      writtenOffQuantity,
      lostQuantity,

      usages,
    }
  })

  return items.sort((left, right) => {
    return left.name.localeCompare(right.name, "ru")
  })
}

async function buildEquipmentItemsFromAnyWarehouse(): Promise<
  EquipmentItemDto[]
> {
  const masterItems = readEquipmentMasterItems()
  const warehouseIds = Array.from(
    new Set(masterItems.map((item) => item.warehouseId))
  )

  const allItems = await Promise.all(
    warehouseIds.map((warehouseId) => {
      return buildEquipmentItems(warehouseId)
    })
  )

  return allItems.flat()
}

export async function getEquipmentItems(
  params: EquipmentItemsQueryParams
): Promise<EquipmentItemDto[]> {
  const search = params.search?.trim().toLowerCase()

  let items = await buildEquipmentItems(params.warehouseId)

  if (search) {
    items = items.filter((item) => {
      return item.name.toLowerCase().includes(search)
    })
  }

  return delay(items)
}

export async function listEquipmentWriteOffs(params: {
  warehouseId: string
  search?: string
}): Promise<EquipmentWriteOffSummaryDto[]> {
  const items = await getEquipmentItems(params)

  return items
    .filter((item) => item.writtenOffQuantity > 0)
    .map((item) => ({
      id: item.id,
      warehouseId: item.warehouseId,
      name: item.name,
      writtenOffQuantity: item.writtenOffQuantity,
    }))
}

export async function getEquipmentItem(
  id: string
): Promise<EquipmentItemDto | null> {
  const items = await buildEquipmentItemsFromAnyWarehouse()

  const item = items.find((equipmentItem) => {
    return equipmentItem.id === id
  })

  return delay(item ?? null)
}

export async function updateEquipmentUsages(
  equipmentItemId: string,
  payload: UpdateEquipmentUsagePayload[]
): Promise<EquipmentItemDto | null> {
  const item = await getEquipmentItem(equipmentItemId)

  console.log("updateEquipmentUsages mock", {
    equipmentItemId,
    payload,
  })

  emitEquipmentUpdated()

  return delay(item)
}

export async function moveEquipmentUsageToStock(
  equipmentItemId: string,
  usageId: string
): Promise<EquipmentItemDto | null> {
  const allItems = await buildEquipmentItemsFromAnyWarehouse()

  const equipmentItem = allItems.find((item) => {
    return item.id === equipmentItemId
  })

  if (!equipmentItem) {
    return delay(null)
  }

  const usage = equipmentItem.usages.find((item) => {
    return item.id === usageId
  })

  if (!usage) {
    return delay(equipmentItem)
  }

  await moveRentalItemEquipmentToStock(usage.rentalItemId, [
    {
      name: equipmentItem.name,
      quantity: usage.quantity,
    },
  ])

  const updatedItems = await buildEquipmentItems(usage.warehouseId)

  const updatedEquipmentItem = updatedItems.find((item) => {
    return item.id === equipmentItemId
  })

  return delay(updatedEquipmentItem ?? null)
}

export async function moveRentalItemEquipmentToStock(
  rentalItemId: string,
  payload: MoveRentalItemContentToStockPayload[]
): Promise<RentalItemDto | null> {
  return runRentalItemMutation(async () => {
    if (hasUnresolvedReturnEquipmentDispositionForRentalItem(rentalItemId)) {
      throw new Error("Оборудование бытовки ожидает решения в разделе списания")
    }
    const rentalItem = readRentalItems().find((item) => {
      return item.id === rentalItemId
    })

    if (!rentalItem) {
      return delay(null)
    }

    const actualMovePayload = getActualMovePayload(rentalItem, payload)

    if (actualMovePayload.length === 0) {
      return delay(rentalItem)
    }

    const updatedRentalItem = await moveRentalItemContentsToStock(
      rentalItemId,
      actualMovePayload
    )

    if (!updatedRentalItem) {
      return delay(null)
    }

    increaseEquipmentStock({
      warehouseId: rentalItem.warehouseId,
      payload: actualMovePayload,
    })

    emitEquipmentUpdated()

    return delay(updatedRentalItem)
  })
}
export async function getEquipmentWarehouseStock(
  warehouseId: string
): Promise<WarehouseInventoryStockItemDto[]> {
  const equipmentItems = await getEquipmentItems({
    warehouseId,
  })

  return equipmentItems
    .filter((item) => item.stockQuantity > 0)
    .map((item) => ({
      name: item.name,
      availableQuantity: item.stockQuantity,
    }))
    .sort((left, right) => {
      return left.name.localeCompare(right.name, "ru")
    })
}

export async function decreaseEquipmentStock(params: {
  warehouseId: string
  items: Array<{
    name: string
    quantity: number
  }>
}): Promise<void> {
  const masterItems = readEquipmentMasterItems()

  let nextMasterItems = [...masterItems]

  params.items.forEach((moveItem) => {
    if (moveItem.quantity <= 0) {
      throw new Error("Количество должно быть больше 0.")
    }

    const index = nextMasterItems.findIndex((item) => {
      return (
        item.warehouseId === params.warehouseId &&
        normalizeName(item.name) === normalizeName(moveItem.name)
      )
    })

    if (index === -1) {
      throw new Error(`На складе нет позиции: ${moveItem.name}.`)
    }

    const currentItem = nextMasterItems[index]

    if (currentItem.stockQuantity < moveItem.quantity) {
      throw new Error(`Недостаточно доступного количества: ${moveItem.name}.`)
    }

    nextMasterItems = nextMasterItems.map((item, itemIndex) => {
      if (itemIndex !== index) {
        return item
      }

      return {
        ...item,
        stockQuantity: item.stockQuantity - moveItem.quantity,
      }
    })
  })

  writeEquipmentMasterItems(nextMasterItems)
  emitEquipmentUpdated()

  return delay(undefined)
}

/**
 * Target-only browser adapter command: a return estimate confirms that items
 * expected in a cabin were absent on receipt. They leave the cabin snapshot
 * and become an auditable loss in the equipment register.
 */
export async function recordEquipmentLossFromReturn(params: {
  warehouseId: string
  items: Array<{
    name: string
    quantity: number
  }>
}): Promise<void> {
  const losses = new Map<string, { name: string; quantity: number }>()

  params.items.forEach((item) => {
    const name = item.name.trim().replace(/\s+/g, " ")
    const quantity = Math.floor(item.quantity)
    if (!name || quantity <= 0) return

    const key = normalizeName(name)
    const current = losses.get(key)
    losses.set(key, {
      name: current?.name ?? name,
      quantity: (current?.quantity ?? 0) + quantity,
    })
  })

  if (losses.size === 0) return delay(undefined)

  let nextMasterItems = [...readEquipmentMasterItems()]

  losses.forEach((loss, key) => {
    const index = nextMasterItems.findIndex(
      (item) =>
        item.warehouseId === params.warehouseId &&
        normalizeName(item.name) === key
    )

    if (index === -1) {
      nextMasterItems.push(
        createMasterItem({
          id: `dynamic:${params.warehouseId}:${key}`,
          warehouseId: params.warehouseId,
          name: loss.name,
          stockQuantity: 0,
          lostQuantity: loss.quantity,
        })
      )
      return
    }

    nextMasterItems = nextMasterItems.map((item, itemIndex) =>
      itemIndex === index
        ? { ...item, lostQuantity: item.lostQuantity + loss.quantity }
        : item
    )
  })

  writeEquipmentMasterItems(nextMasterItems)
  emitEquipmentUpdated()
  return delay(undefined)
}

/**
 * Mock adapter boundary used by rental-return registration and editing.
 * The deterministic source identity makes retries safe and preserves case IDs.
 */
function buildReconciledDispositionState(
  state: EquipmentDispositionState,
  input: RegisterReturnEquipmentDispositionInput
) {
  const rentalItem = readRentalItems().find(
    (item) =>
      item.id === input.sourceRentalItemId &&
      item.warehouseId === input.warehouseId
  )
  if (!rentalItem) throw new Error("Бытовка для возврата не найдена")

  const normalizedContents = normalizedDispositionContents(input.contents)
  const existingForSource = state.cases.filter(
    (item) => item.returnItemId === input.returnItemId
  )
  if (
    existingForSource.some(
      (item) =>
        item.sourceRentalItemId !== input.sourceRentalItemId ||
        item.returnReceiptId !== input.returnReceiptId
    )
  ) {
    throw new Error("Источник карантинных позиций уже изменён")
  }

  const desiredKeys = new Set(
    normalizedContents.map((item) => item.normalizedEquipmentKey)
  )
  existingForSource
    .filter((item) => item.resolutions.length > 0)
    .forEach((item) => desiredKeys.add(item.normalizedEquipmentKey))

  desiredKeys.forEach((key) => {
    const content = normalizedContents.find(
      (item) => item.normalizedEquipmentKey === key
    )
    const existing = existingForSource.find(
      (item) => item.normalizedEquipmentKey === key
    )
    const requiredQuantity = existing?.resolutions.length
      ? existing.remainingQuantity
      : (content?.quantity ?? 0)
    const liveQuantity =
      rentalItem.contentsItems.find(
        (item) => normalizeDispositionKey(item.name) === key
      )?.quantity ?? 0
    if (liveQuantity < requiredQuantity) {
      throw new Error(
        `Наполнение бытовки ${rentalItem.number} изменилось: ${content?.name ?? existing?.equipmentName ?? key}`
      )
    }
  })

  const masterItems = readEquipmentMasterItems()
  const retained = state.cases.filter(
    (item) =>
      item.returnItemId !== input.returnItemId ||
      desiredKeys.has(item.normalizedEquipmentKey)
  )
  const casesForSource = Array.from(desiredKeys).map((key) => {
    const content = normalizedContents.find(
      (item) => item.normalizedEquipmentKey === key
    )
    const existing = existingForSource.find(
      (item) => item.normalizedEquipmentKey === key
    )
    if (existing) {
      if (existing.resolutions.length > 0) {
        const metadataChanged =
          existing.sourceCabinNumber !== input.sourceCabinNumber ||
          existing.receivedAt !== input.receivedAt
        return metadataChanged
          ? {
              ...existing,
              version: existing.version + 1,
              sourceCabinNumber: input.sourceCabinNumber,
              receivedAt: input.receivedAt,
            }
          : existing
      }
      if (!content) return existing
      if (existing.receivedQuantity === content.quantity) {
        const metadataChanged =
          existing.sourceCabinNumber !== input.sourceCabinNumber ||
          existing.receivedAt !== input.receivedAt
        return metadataChanged
          ? {
              ...existing,
              version: existing.version + 1,
              sourceCabinNumber: input.sourceCabinNumber,
              receivedAt: input.receivedAt,
            }
          : existing
      }
      return {
        ...existing,
        version: existing.version + 1,
        equipmentName: content.name,
        receivedQuantity: content.quantity,
        remainingQuantity: content.quantity,
        status: "ACTION_REQUIRED" as const,
      }
    }

    if (!content) {
      throw new Error("Не удалось восстановить карантинную позицию")
    }

    const master = masterItems.find(
      (item) =>
        item.warehouseId === input.warehouseId &&
        normalizeDispositionKey(item.name) === key
    )
    return {
      id: dispositionCaseId(input.returnItemId, key),
      version: 1,
      warehouseId: input.warehouseId,
      returnReceiptId: input.returnReceiptId,
      returnItemId: input.returnItemId,
      sourceRentalItemId: input.sourceRentalItemId,
      sourceCabinNumber: input.sourceCabinNumber,
      equipmentMasterItemId: master?.id ?? null,
      equipmentName: content.name,
      normalizedEquipmentKey: key,
      receivedQuantity: content.quantity,
      remainingQuantity: content.quantity,
      receivedAt: input.receivedAt,
      status: "ACTION_REQUIRED" as const,
      resolutions: [],
    }
  })

  const byId = new Map(casesForSource.map((item) => [item.id, item]))
  const nextCases = retained.map((item) => byId.get(item.id) ?? item)
  casesForSource.forEach((item) => {
    if (!nextCases.some((candidate) => candidate.id === item.id)) {
      nextCases.push(item)
    }
  })
  const nextState: EquipmentDispositionState = {
    ...state,
    revision: state.revision + 1,
    cases: nextCases,
  }
  return { state: nextState, cases: casesForSource }
}

export type PreparedReturnEquipmentDispositionReconciliation = {
  cases: ReturnEquipmentDispositionCaseDto[]
  commit: () => void
  rollback: () => void
}

/** Caller-held rental mutation lock variant for logistics create/edit coordinators. */
export function prepareReturnEquipmentDispositionReconciliation(input: {
  upserts: RegisterReturnEquipmentDispositionInput[]
  removeReturnItemIds?: string[]
}): PreparedReturnEquipmentDispositionReconciliation {
  recoverEquipmentDispositionJournal()
  const before = readDispositionStateRaw()
  let after = before
  const allCases: ReturnEquipmentDispositionCaseDto[] = []
  const upsertIds = new Set(input.upserts.map((item) => item.returnItemId))

  for (const returnItemId of input.removeReturnItemIds ?? []) {
    if (upsertIds.has(returnItemId)) continue
    const matching = after.cases.filter(
      (item) => item.returnItemId === returnItemId
    )
    if (matching.some((item) => item.resolutions.length > 0)) {
      throw new Error(
        "Нельзя удалить бытовку из возврата после обработки её оборудования"
      )
    }
    if (matching.length > 0) {
      after = {
        ...after,
        revision: after.revision + 1,
        cases: after.cases.filter((item) => item.returnItemId !== returnItemId),
      }
    }
  }
  for (const upsert of input.upserts) {
    const result = buildReconciledDispositionState(after, upsert)
    after = result.state
    allCases.push(...result.cases)
  }

  const afterImage = JSON.stringify(after)
  let committed = false
  return {
    cases: allCases,
    commit: () => {
      const current = readDispositionStateRaw()
      if (JSON.stringify(current) !== JSON.stringify(before)) {
        throw new Error("Карантинный реестр уже изменён в другой вкладке")
      }
      writeDispositionState(after)
      committed = true
    },
    rollback: () => {
      if (!committed) return
      const current = readDispositionStateRaw()
      if (JSON.stringify(current) !== afterImage) {
        throw new Error("Карантинный реестр изменён после сохранения")
      }
      writeDispositionState({
        ...before,
        revision: current.revision + 1,
      })
      committed = false
    },
  }
}

export async function reconcileReturnEquipmentDispositionCases(
  input: RegisterReturnEquipmentDispositionInput
): Promise<ReturnEquipmentDispositionCaseDto[]> {
  return runRentalItemMutation(async () => {
    const prepared = prepareReturnEquipmentDispositionReconciliation({
      upserts: [input],
    })
    prepared.commit()
    return delay(prepared.cases)
  })
}

export async function removeUnresolvedReturnEquipmentDispositionCases(
  returnItemId: string
): Promise<void> {
  return runRentalItemMutation(async () => {
    recoverEquipmentDispositionJournal()
    const state = readDispositionStateRaw()
    const matching = state.cases.filter(
      (item) => item.returnItemId === returnItemId
    )
    if (matching.some((item) => item.resolutions.length > 0)) {
      throw new Error(
        "Нельзя удалить бытовку из возврата после обработки её оборудования"
      )
    }
    if (matching.length === 0) return
    writeDispositionState({
      ...state,
      revision: state.revision + 1,
      cases: state.cases.filter((item) => item.returnItemId !== returnItemId),
    })
  })
}

export async function getUnresolvedReturnEquipmentDispositionCases(params: {
  warehouseId?: string
  returnItemId?: string
  sourceRentalItemId?: string
}): Promise<ReturnEquipmentDispositionCaseDto[]> {
  recoverEquipmentDispositionJournal()
  return delay(
    readDispositionStateRaw().cases.filter(
      (item) =>
        item.status !== "RESOLVED" &&
        (!params.warehouseId || item.warehouseId === params.warehouseId) &&
        (!params.returnItemId || item.returnItemId === params.returnItemId) &&
        (!params.sourceRentalItemId ||
          item.sourceRentalItemId === params.sourceRentalItemId)
    )
  )
}

/** Synchronous guard for callers that already hold the rental mutation lock. */
export function hasUnresolvedReturnEquipmentDispositionForRentalItem(
  rentalItemId: string
) {
  recoverEquipmentDispositionJournal()
  return hasUnresolvedReturnEquipmentDispositionLowLevel(rentalItemId)
}

export function hasReturnEquipmentDispositionHistoryForReturnItem(
  returnItemId: string
) {
  recoverEquipmentDispositionJournal()
  return readDispositionStateRaw().cases.some(
    (item) => item.returnItemId === returnItemId && item.resolutions.length > 0
  )
}

/** Synchronous snapshot for coordinators that already hold the mutation lock. */
export function getUnresolvedReturnEquipmentDispositionSnapshot(params: {
  returnItemId?: string
  sourceRentalItemId?: string
}) {
  recoverEquipmentDispositionJournal()
  return readDispositionStateRaw().cases.filter(
    (item) =>
      item.status !== "RESOLVED" &&
      (!params.returnItemId || item.returnItemId === params.returnItemId) &&
      (!params.sourceRentalItemId ||
        item.sourceRentalItemId === params.sourceRentalItemId)
  )
}

export async function listEquipmentDispositionItems(params: {
  warehouseId: string
  search?: string
}): Promise<EquipmentDispositionListItemDto[]> {
  recoverEquipmentDispositionJournal()
  const search = normalizeDispositionKey(params.search ?? "")
  const pending = readDispositionStateRaw()
    .cases.filter(
      (item) =>
        item.warehouseId === params.warehouseId && item.status !== "RESOLVED"
    )
    .filter(
      (item) =>
        !search ||
        normalizeDispositionKey(
          `${item.equipmentName} ${item.sourceCabinNumber}`
        ).includes(search)
    )
    .map((item) => ({ ...item, kind: "RETURN_DISPOSITION" as const }))
  const historical = (await listEquipmentWriteOffs(params)).map((item) => ({
    ...item,
    id: `historical:${item.id}`,
    kind: "HISTORICAL_WRITE_OFF" as const,
  }))
  return [...pending, ...historical]
}

export async function listEquipmentDispositionTransferTargets(params: {
  warehouseId: string
  sourceRentalItemId: string
}): Promise<Array<{ id: string; number: string }>> {
  recoverEquipmentDispositionJournal()
  const quarantinedRentalItemIds = new Set(
    readDispositionStateRaw()
      .cases.filter((item) => item.status !== "RESOLVED")
      .map((item) => item.sourceRentalItemId)
  )
  return delay(
    readRentalItems()
      .filter(
        (item) =>
          item.warehouseId === params.warehouseId &&
          item.id !== params.sourceRentalItemId &&
          !quarantinedRentalItemIds.has(item.id) &&
          item.status !== "RENTED" &&
          item.status !== "WRITTEN_OFF" &&
          RENTAL_ITEM_NON_RENTED_ACTIVE_STATUSES.includes(item.status)
      )
      .map((item) => ({ id: item.id, number: item.number }))
      .sort((left, right) =>
        left.number.localeCompare(right.number, "ru", { numeric: true })
      )
  )
}

export async function resolveReturnEquipmentDisposition(
  input: ResolveReturnEquipmentDispositionInput
): Promise<ReturnEquipmentDispositionCaseDto> {
  return runRentalItemMutation(async () => {
    recoverEquipmentDispositionJournal()
    const state = readDispositionStateRaw()
    const caseIndex = state.cases.findIndex((item) => item.id === input.caseId)
    if (caseIndex === -1) throw new Error("Карантинная позиция не найдена")
    const currentCase = state.cases[caseIndex]
    const normalizedReason = input.reason?.trim() || null
    const replay = currentCase.resolutions.find(
      (item) => item.idempotencyKey === input.idempotencyKey
    )
    if (replay) {
      if (
        replay.action !== input.action ||
        replay.quantity !== input.quantity ||
        replay.targetRentalItemId !== (input.targetRentalItemId ?? null) ||
        replay.reason !== normalizedReason ||
        replay.createdBy !== input.createdBy.trim()
      ) {
        throw new Error("Ключ операции уже использован с другими данными")
      }
      return currentCase
    }
    if (currentCase.version !== input.expectedVersion) {
      throw new Error("Позиция уже изменена в другой вкладке")
    }
    if (
      !Number.isInteger(input.quantity) ||
      input.quantity < 1 ||
      input.quantity > currentCase.remainingQuantity
    ) {
      throw new Error(
        `Количество должно быть от 1 до ${currentCase.remainingQuantity}`
      )
    }
    if (input.action === "WRITE_OFF" && !normalizedReason) {
      throw new Error("Укажите причину списания")
    }

    const rentalItems = readRentalItems()
    const sourceIndex = rentalItems.findIndex(
      (item) =>
        item.id === currentCase.sourceRentalItemId &&
        item.warehouseId === currentCase.warehouseId
    )
    if (sourceIndex === -1) throw new Error("Исходная бытовка не найдена")
    const nextSource = {
      ...rentalItems[sourceIndex],
      contentsItems: subtractExactContent(
        rentalItems[sourceIndex].contentsItems,
        currentCase.normalizedEquipmentKey,
        input.quantity
      ),
    }
    if (
      !RETURN_EQUIPMENT_PROCESSING_STATUSES.includes(
        rentalItems[sourceIndex]
          .status as (typeof RETURN_EQUIPMENT_PROCESSING_STATUSES)[number]
      )
    ) {
      throw new Error("Бытовка больше не находится в обработке возврата")
    }
    let targetIndex = -1
    let targetCabinNumber: string | null = null
    let nextTarget: RentalItemDto | null = null
    if (input.action === "TRANSFER_TO_CABIN") {
      if (!input.targetRentalItemId) throw new Error("Выберите бытовку")
      targetIndex = rentalItems.findIndex(
        (item) => item.id === input.targetRentalItemId
      )
      const target = targetIndex === -1 ? null : rentalItems[targetIndex]
      if (
        !target ||
        target.id === currentCase.sourceRentalItemId ||
        target.warehouseId !== currentCase.warehouseId ||
        target.status === "RENTED" ||
        target.status === "WRITTEN_OFF" ||
        state.cases.some(
          (item) =>
            item.status !== "RESOLVED" && item.sourceRentalItemId === target.id
        ) ||
        !RENTAL_ITEM_NON_RENTED_ACTIVE_STATUSES.includes(target.status)
      ) {
        throw new Error("Выбранная бытовка недоступна для перемещения")
      }
      targetCabinNumber = target.number
      nextTarget = {
        ...target,
        contentsItems: addExactContent(
          target.contentsItems,
          currentCase.equipmentName,
          currentCase.normalizedEquipmentKey,
          input.quantity
        ),
      }
    }

    const masterItems = readEquipmentMasterItems()
    let masterIndex = masterItems.findIndex(
      (item) =>
        item.warehouseId === currentCase.warehouseId &&
        normalizeDispositionKey(item.name) ===
          currentCase.normalizedEquipmentKey
    )
    if (
      input.action === "RETURN_TO_STOCK" &&
      masterIndex === -1 &&
      !input.confirmCreateMasterItem
    ) {
      throw new Error("Подтвердите создание новой складской позиции")
    }
    const nextMasterItems = [...masterItems]
    if (
      (input.action === "RETURN_TO_STOCK" || input.action === "WRITE_OFF") &&
      masterIndex === -1
    ) {
      nextMasterItems.push(
        createMasterItem({
          id: `dynamic:${currentCase.warehouseId}:${currentCase.normalizedEquipmentKey}`,
          warehouseId: currentCase.warehouseId,
          name: currentCase.equipmentName,
          stockQuantity: 0,
        })
      )
      masterIndex = nextMasterItems.length - 1
    }
    if (input.action === "RETURN_TO_STOCK") {
      nextMasterItems[masterIndex] = {
        ...nextMasterItems[masterIndex],
        stockQuantity:
          nextMasterItems[masterIndex].stockQuantity + input.quantity,
      }
    } else if (input.action === "WRITE_OFF") {
      nextMasterItems[masterIndex] = {
        ...nextMasterItems[masterIndex],
        writtenOffQuantity:
          nextMasterItems[masterIndex].writtenOffQuantity + input.quantity,
      }
    }

    const nextRentalItems = rentalItems.map((item, index) => {
      if (index === sourceIndex) return nextSource
      if (index === targetIndex && nextTarget) return nextTarget
      return item
    })
    const remainingQuantity = currentCase.remainingQuantity - input.quantity
    const savedCase: ReturnEquipmentDispositionCaseDto = {
      ...currentCase,
      version: currentCase.version + 1,
      equipmentMasterItemId:
        masterIndex >= 0
          ? nextMasterItems[masterIndex].id
          : currentCase.equipmentMasterItemId,
      remainingQuantity,
      status: updatedDispositionStatus(remainingQuantity),
      resolutions: [
        ...currentCase.resolutions,
        {
          id: `resolution:${input.idempotencyKey}`,
          idempotencyKey: input.idempotencyKey,
          action: input.action,
          quantity: input.quantity,
          targetRentalItemId: input.targetRentalItemId ?? null,
          targetCabinNumber,
          reason: normalizedReason,
          createdAt: new Date().toISOString(),
          createdBy: input.createdBy.trim(),
        },
      ],
    }
    const nextDispositionState: EquipmentDispositionState = {
      ...state,
      revision: state.revision + 1,
      cases: state.cases.map((item, index) =>
        index === caseIndex ? savedCase : item
      ),
    }
    const journal: EquipmentDispositionJournal = {
      id: input.idempotencyKey,
      state: "PREPARED",
      rentalItems: nextRentalItems
        .filter((after) => {
          const before = rentalItems.find((item) => item.id === after.id)
          return JSON.stringify(before) !== JSON.stringify(after)
        })
        .map((after) => ({
          id: after.id,
          before: rentalItems.find((item) => item.id === after.id)!,
          after,
        })),
      masterItems: nextMasterItems
        .filter((after) => {
          const before =
            masterItems.find((item) => item.id === after.id) ?? null
          return JSON.stringify(before) !== JSON.stringify(after)
        })
        .map((after) => ({
          id: after.id,
          before: masterItems.find((item) => item.id === after.id) ?? null,
          after,
        })),
      dispositionCases: [
        {
          id: currentCase.id,
          before: currentCase,
          after: savedCase,
        },
      ],
    }
    window.localStorage.setItem(
      EQUIPMENT_DISPOSITION_JOURNAL_KEY,
      JSON.stringify(journal)
    )
    try {
      writeRentalItems(nextRentalItems, false)
      writeEquipmentMasterItems(nextMasterItems, false)
      writeDispositionState(nextDispositionState, false)
      window.localStorage.removeItem(EQUIPMENT_DISPOSITION_JOURNAL_KEY)
    } catch (error) {
      recoverEquipmentDispositionJournal()
      throw error
    }
    window.dispatchEvent(new Event(RENTAL_ITEMS_MOCK_UPDATED_EVENT))
    window.dispatchEvent(new Event(EQUIPMENT_MOCK_UPDATED_EVENT))
    window.dispatchEvent(new Event(EQUIPMENT_DISPOSITIONS_UPDATED_EVENT))
    return delay(savedCase)
  })
}
