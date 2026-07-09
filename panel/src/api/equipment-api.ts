import {
  getRentalItemsForEquipmentInventory,
  moveRentalItemContentsToStock,
  readRentalItems,
} from "@/features/rental-items/api/rental-items-api"
import type {
  EquipmentItemDto,
  EquipmentItemsQueryParams,
  EquipmentRentalUsageDto,
  UpdateEquipmentUsagePayload,
} from "@/types/equipment"
import type {
  MoveRentalItemContentToStockPayload,
  RentalItemDto,
} from "@/features/rental-items/model/rental-item"
import type { WarehouseInventoryStockItemDto } from "@/types/warehouse-location"
export const EQUIPMENT_MOCK_STORAGE_KEY = "wms:mock-equipment-master"
export const EQUIPMENT_MOCK_UPDATED_EVENT = "wms:mock-equipment-items-updated"

type EquipmentMasterItem = {
  id: string
  warehouseId: string
  name: string
  stockQuantity: number
  writtenOffQuantity: number
  lostQuantity: number
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
