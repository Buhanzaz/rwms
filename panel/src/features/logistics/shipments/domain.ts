import type { RentalItemContentsItemDto } from "@/features/rental-items/model/rental-item"
import type {
  ShipmentContentsChange,
  ShipmentPreparationTask,
  ShipmentSourceAllocation,
  ShipmentSourceCandidate,
} from "@/features/logistics/shipments/model"

export const normalizeContents = (items: RentalItemContentsItemDto[]) => {
  const byName = new Map<string, RentalItemContentsItemDto>()
  items.forEach((item) => {
    const name = item.name.trim().replace(/\s+/g, " ")
    if (!name || !Number.isInteger(item.quantity) || item.quantity <= 0) return
    const key = name.toLocaleLowerCase("ru-RU")
    const previous = byName.get(key)
    byName.set(key, {
      name: previous?.name ?? name,
      quantity: (previous?.quantity ?? 0) + item.quantity,
    })
  })
  return [...byName.values()].sort((left, right) =>
    left.name.localeCompare(right.name, "ru")
  )
}

export function combineShipmentPreparationCatalog(
  warehouse: Array<{ name: string; availableQuantity: number }>,
  sourceCabins: ShipmentSourceCandidate[]
) {
  const quantities = new Map<string, { name: string; quantity: number }>()
  warehouse.forEach((item) => {
    const key = item.name.trim().toLocaleLowerCase("ru-RU")
    quantities.set(key, { name: item.name, quantity: item.availableQuantity })
  })
  sourceCabins
    .flatMap((source) => source.contentsItems)
    .forEach((item) => {
      const key = item.name.trim().toLocaleLowerCase("ru-RU")
      const current = quantities.get(key)
      quantities.set(key, {
        name: current?.name ?? item.name,
        quantity: (current?.quantity ?? 0) + item.quantity,
      })
    })
  return [...quantities.values()]
    .filter((item) => item.quantity > 0)
    .map((item) => ({ name: item.name, availableQuantity: item.quantity }))
    .sort((left, right) => left.name.localeCompare(right.name, "ru"))
}

export function computeShipmentContentsChanges(
  before: RentalItemContentsItemDto[],
  planned: RentalItemContentsItemDto[]
): ShipmentContentsChange[] {
  const beforeMap = new Map(
    normalizeContents(before).map((item) => [
      item.name.toLocaleLowerCase("ru-RU"),
      item,
    ])
  )
  const plannedMap = new Map(
    normalizeContents(planned).map((item) => [
      item.name.toLocaleLowerCase("ru-RU"),
      item,
    ])
  )
  return [...new Set([...beforeMap.keys(), ...plannedMap.keys()])]
    .map((key) => {
      const previous = beforeMap.get(key)
      const next = plannedMap.get(key)
      const beforeQuantity = previous?.quantity ?? 0
      const plannedQuantity = next?.quantity ?? 0
      if (beforeQuantity === plannedQuantity) return null
      const direction =
        plannedQuantity > beforeQuantity
          ? ("BRING" as const)
          : ("TAKE" as const)
      const quantity = Math.abs(plannedQuantity - beforeQuantity)
      const name = next?.name ?? previous!.name
      return {
        name,
        beforeQuantity,
        plannedQuantity,
        direction,
        quantity,
        label: `${direction === "BRING" ? "Принести" : "Вынести"} ${quantity} × ${name}`,
      }
    })
    .filter((item): item is ShipmentContentsChange => item !== null)
    .sort((left, right) => left.name.localeCompare(right.name, "ru"))
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

export function shipmentPreparationIdentity(input: {
  warehouseId: string
  company: string
  shipmentDate: string
  rentalItemId: string
  changes: ShipmentContentsChange[]
  sourceAllocations: ShipmentSourceAllocation[]
}) {
  const sources = input.sourceAllocations
    .map((allocation) => ({
      sourceType: allocation.sourceType,
      sourceRentalItemId: allocation.sourceRentalItemId,
      items: normalizeContents(allocation.items).map((item) => ({
        name: item.name.toLocaleLowerCase("ru-RU"),
        quantity: item.quantity,
      })),
    }))
    .sort((left, right) =>
      `${left.sourceType}:${left.sourceRentalItemId ?? ""}`.localeCompare(
        `${right.sourceType}:${right.sourceRentalItemId ?? ""}`
      )
    )
  const changes = [...input.changes]
    .map((change) => ({
      name: change.name.toLocaleLowerCase("ru-RU"),
      direction: change.direction,
      quantity: change.quantity,
    }))
    .sort((left, right) =>
      `${left.direction}:${left.name}`.localeCompare(
        `${right.direction}:${right.name}`
      )
    )
  return JSON.stringify({
    warehouseId: input.warehouseId,
    company: input.company.trim().toLocaleLowerCase("ru-RU"),
    shipmentDate: input.shipmentDate,
    rentalItemId: input.rentalItemId,
    changes,
    sources,
  })
}

export function buildShipmentPreparationTaskText(input: {
  cabinNumber: string
  changes: ShipmentContentsChange[]
  sourceAllocations: ShipmentSourceAllocation[]
}) {
  const sources = input.sourceAllocations.map((allocation) => {
    const title =
      allocation.sourceType === "WAREHOUSE"
        ? "Со склада"
        : `Из бытовки ${allocation.sourceCabinNumber}`
    return `${title}: ${allocation.items
      .map((entry) => `${entry.quantity} × ${entry.name}`)
      .join(", ")}`
  })
  const removals = input.changes
    .filter((change) => change.direction === "TAKE")
    .map(
      (change) =>
        `Переместить из бытовки ${input.cabinNumber} на склад: ${change.quantity} × ${change.name}`
    )
  const destination = sources.length
    ? [`Переместить в бытовку ${input.cabinNumber}`]
    : []
  return [...sources, ...removals, ...destination].join("; ")
}

export function createShipmentPreparationTask(
  changes: ShipmentContentsChange[],
  existingId?: string,
  externalTaskId?: string
): ShipmentPreparationTask {
  if (!changes.length) throw new Error("Изменений наполнения нет")
  return {
    id: existingId ?? crypto.randomUUID(),
    externalTaskId: externalTaskId ?? crypto.randomUUID(),
    createdAt: new Date().toISOString(),
    lines: changes.map((line) => ({ ...line })),
    dispatchStatus: "DRAFT",
    boardTaskId: null,
    boardTaskVersion: null,
    queueId: null,
    queueCode: null,
    dispatchError: null,
    dispatchAttemptId: null,
    dispatchAttemptedAt: null,
  }
}

export function validateAllocationCoverage(
  changes: ShipmentContentsChange[],
  allocations: ShipmentSourceAllocation[]
) {
  const required = new Map(
    changes
      .filter((change) => change.direction === "BRING")
      .map((change) => [
        change.name.toLocaleLowerCase("ru-RU"),
        change.quantity,
      ])
  )
  const supplied = new Map<string, number>()
  allocations.forEach((allocation) =>
    normalizeContents(allocation.items).forEach((item) => {
      const key = item.name.toLocaleLowerCase("ru-RU")
      supplied.set(key, (supplied.get(key) ?? 0) + item.quantity)
    })
  )
  required.forEach((quantity, key) => {
    if ((supplied.get(key) ?? 0) !== quantity)
      throw new Error(
        "Источники мебели должны полностью покрывать планируемое пополнение"
      )
  })
  supplied.forEach((_quantity, key) => {
    if (!required.has(key))
      throw new Error(
        "Источник содержит позицию, отсутствующую в плане пополнения"
      )
  })
}
