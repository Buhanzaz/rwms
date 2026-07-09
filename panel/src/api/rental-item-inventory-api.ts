import {
  decreaseEquipmentStock,
  getEquipmentWarehouseStock,
} from "@/api/equipment-api"
import { readRentalItems, writeRentalItems } from "@/features/rental-items/api/rental-items-api"
import {
  ensureDefaultGraphForWarehouse,
  getDistanceBetweenNodes,
  getWarehouseLocationNodes,
} from "@/api/warehouse-location-api"
import type {
  AddInventoryFromWarehousePayload,
  NearbyInventorySourceDto,
  TransferInventoryFromRentalItemPayload,
} from "@/types/warehouse-location"
import type {
  RentalItemContentsItemDto,
  RentalItemDto,
} from "@/features/rental-items/model/rental-item"

function delay<T>(data: T, timeout = 180): Promise<T> {
  return new Promise((resolve) => {
    window.setTimeout(() => resolve(data), timeout)
  })
}

function normalizeName(value: string) {
  return value.trim().toLowerCase()
}

function validatePositiveItems(
  items: Array<{
    name: string
    quantity: number
  }>
) {
  if (items.length === 0) {
    throw new Error("Не выбраны позиции для перемещения.")
  }

  items.forEach((item) => {
    if (item.quantity <= 0) {
      throw new Error("Количество должно быть больше 0.")
    }
  })
}

function getActualPayload(
  sourceItems: RentalItemContentsItemDto[],
  payload: Array<{
    name: string
    quantity: number
  }>
) {
  validatePositiveItems(payload)

  return payload.map((payloadItem) => {
    const sourceItem = sourceItems.find((item) => {
      return normalizeName(item.name) === normalizeName(payloadItem.name)
    })

    if (!sourceItem) {
      throw new Error(`В исходной бытовке нет позиции: ${payloadItem.name}.`)
    }

    if (sourceItem.quantity < payloadItem.quantity) {
      throw new Error(`Недостаточно доступного количества: ${sourceItem.name}.`)
    }

    return {
      name: sourceItem.name,
      quantity: payloadItem.quantity,
    }
  })
}

function subtractContentsItems(
  contentsItems: RentalItemContentsItemDto[],
  payload: Array<{
    name: string
    quantity: number
  }>
): RentalItemContentsItemDto[] {
  return contentsItems
    .map((contentItem) => {
      const moveItem = payload.find((item) => {
        return normalizeName(item.name) === normalizeName(contentItem.name)
      })

      if (!moveItem) {
        return contentItem
      }

      return {
        ...contentItem,
        quantity: Math.max(0, contentItem.quantity - moveItem.quantity),
      }
    })
    .filter((item) => item.quantity > 0)
}

function addContentsItems(
  contentsItems: RentalItemContentsItemDto[],
  payload: Array<{
    name: string
    quantity: number
  }>
): RentalItemContentsItemDto[] {
  let nextItems = [...contentsItems]

  payload.forEach((moveItem) => {
    const index = nextItems.findIndex((item) => {
      return normalizeName(item.name) === normalizeName(moveItem.name)
    })

    if (index === -1) {
      nextItems = [
        ...nextItems,
        {
          name: moveItem.name,
          quantity: moveItem.quantity,
        },
      ]

      return
    }

    nextItems = nextItems.map((item, itemIndex) => {
      if (itemIndex !== index) {
        return item
      }

      return {
        ...item,
        quantity: item.quantity + moveItem.quantity,
      }
    })
  })

  return nextItems.filter((item) => item.quantity > 0)
}

function normalizeRentalItemContents(item: RentalItemDto): RentalItemDto {
  const contents = item.contentsItems
    .map((contentItem) => `${contentItem.name} ${contentItem.quantity} шт.`)
    .join(", ")

  return {
    ...item,
    contents: item.contentsItems.length > 0 ? contents : null,
  }
}

export async function getRentalItemInventoryAddOptions(
  targetRentalItemId: string
): Promise<{
  warehouseStock: Awaited<ReturnType<typeof getEquipmentWarehouseStock>>
}> {
  const targetItem = readRentalItems().find((item) => {
    return item.id === targetRentalItemId
  })

  if (!targetItem) {
    throw new Error("Целевая бытовка не найдена.")
  }

  const warehouseStock = await getEquipmentWarehouseStock(
    targetItem.warehouseId
  )

  return delay({
    warehouseStock,
  })
}

export async function findNearestInventorySources(params: {
  targetRentalItemId: string
  limit?: number
}): Promise<NearbyInventorySourceDto[]> {
  const limit = params.limit ?? 10

  const rentalItems = readRentalItems()

  const targetItem = rentalItems.find((item) => {
    return item.id === params.targetRentalItemId
  })

  if (!targetItem) {
    throw new Error("Целевая бытовка не найдена.")
  }

  if (!targetItem.locationNodeId) {
    throw new Error("У целевой бытовки не указано местоположение.")
  }

  await ensureDefaultGraphForWarehouse(targetItem.warehouseId)

  const nodes = await getWarehouseLocationNodes(targetItem.warehouseId)

  const result: NearbyInventorySourceDto[] = []

  for (const sourceItem of rentalItems) {
    if (sourceItem.id === targetItem.id) {
      continue
    }

    if (sourceItem.warehouseId !== targetItem.warehouseId) {
      continue
    }

    if (!sourceItem.locationNodeId) {
      continue
    }

    const inventory = sourceItem.contentsItems
      .filter((item) => item.quantity > 0)
      .map((item) => ({
        name: item.name,
        availableQuantity: item.quantity,
      }))

    if (inventory.length === 0) {
      continue
    }

    const distance = await getDistanceBetweenNodes({
      warehouseId: targetItem.warehouseId,
      targetLocationNodeId: targetItem.locationNodeId,
      sourceLocationNodeId: sourceItem.locationNodeId,
    })

    if (!distance) {
      continue
    }

    const locationNode = nodes.find((node) => {
      return node.id === sourceItem.locationNodeId
    })

    if (!locationNode) {
      continue
    }

    result.push({
      rentalItemId: sourceItem.id,
      number: sourceItem.number,
      type: sourceItem.type,
      locationNodeId: locationNode.id,
      locationCode: locationNode.code,
      locationDisplayName: locationNode.displayName,
      distanceWeight: distance.distanceWeight,
      distanceLabel: distance.distanceLabel,
      inventory,
    })
  }

  return delay(
    result
      .sort((left, right) => {
        if (left.distanceWeight !== right.distanceWeight) {
          return left.distanceWeight - right.distanceWeight
        }

        return left.number.localeCompare(right.number, "ru", {
          numeric: true,
        })
      })
      .slice(0, limit)
  )
}

export async function transferInventoryFromRentalItem(params: {
  targetRentalItemId: string
  payload: TransferInventoryFromRentalItemPayload
}): Promise<{
  targetItem: RentalItemDto
  sourceItem: RentalItemDto
}> {
  const rentalItems = readRentalItems()

  const targetIndex = rentalItems.findIndex((item) => {
    return item.id === params.targetRentalItemId
  })

  const sourceIndex = rentalItems.findIndex((item) => {
    return item.id === params.payload.sourceRentalItemId
  })

  if (targetIndex === -1) {
    throw new Error("Целевая бытовка не найдена.")
  }

  if (sourceIndex === -1) {
    throw new Error("Исходная бытовка не найдена.")
  }

  const targetItem = rentalItems[targetIndex]
  const sourceItem = rentalItems[sourceIndex]

  if (targetItem.id === sourceItem.id) {
    throw new Error("Нельзя переместить наполнение в эту же бытовку.")
  }

  if (targetItem.warehouseId !== sourceItem.warehouseId) {
    throw new Error("Бытовки находятся на разных складах.")
  }

  if (!targetItem.locationNodeId) {
    throw new Error("У целевой бытовки не указано местоположение.")
  }

  if (!sourceItem.locationNodeId) {
    throw new Error("У исходной бытовки не указано местоположение.")
  }

  const distance = await getDistanceBetweenNodes({
    warehouseId: targetItem.warehouseId,
    targetLocationNodeId: targetItem.locationNodeId,
    sourceLocationNodeId: sourceItem.locationNodeId,
  })

  if (!distance) {
    throw new Error("Исходная бытовка находится слишком далеко.")
  }

  const actualPayload = getActualPayload(
    sourceItem.contentsItems,
    params.payload.items
  )

  const nextSourceItem = normalizeRentalItemContents({
    ...sourceItem,
    contentsItems: subtractContentsItems(
      sourceItem.contentsItems,
      actualPayload
    ),
  })

  const nextTargetItem = normalizeRentalItemContents({
    ...targetItem,
    contentsItems: addContentsItems(targetItem.contentsItems, actualPayload),
  })

  const nextRentalItems = rentalItems.map((item, index) => {
    if (index === sourceIndex) {
      return nextSourceItem
    }

    if (index === targetIndex) {
      return nextTargetItem
    }

    return item
  })

  writeRentalItems(nextRentalItems)

  return delay({
    targetItem: nextTargetItem,
    sourceItem: nextSourceItem,
  })
}

export async function addInventoryFromWarehouseStock(params: {
  targetRentalItemId: string
  payload: AddInventoryFromWarehousePayload
}): Promise<RentalItemDto> {
  validatePositiveItems(params.payload.items)

  const rentalItems = readRentalItems()

  const targetIndex = rentalItems.findIndex((item) => {
    return item.id === params.targetRentalItemId
  })

  if (targetIndex === -1) {
    throw new Error("Целевая бытовка не найдена.")
  }

  const targetItem = rentalItems[targetIndex]

  const warehouseStock = await getEquipmentWarehouseStock(
    targetItem.warehouseId
  )

  params.payload.items.forEach((payloadItem) => {
    const stockItem = warehouseStock.find((item) => {
      return normalizeName(item.name) === normalizeName(payloadItem.name)
    })

    if (!stockItem || stockItem.availableQuantity < payloadItem.quantity) {
      throw new Error(
        `Недостаточно доступного количества: ${payloadItem.name}.`
      )
    }
  })

  await decreaseEquipmentStock({
    warehouseId: targetItem.warehouseId,
    items: params.payload.items,
  })

  const nextTargetItem = normalizeRentalItemContents({
    ...targetItem,
    contentsItems: addContentsItems(
      targetItem.contentsItems,
      params.payload.items
    ),
  })

  const nextRentalItems = rentalItems.map((item, index) => {
    return index === targetIndex ? nextTargetItem : item
  })

  writeRentalItems(nextRentalItems)

  return delay(nextTargetItem)
}
