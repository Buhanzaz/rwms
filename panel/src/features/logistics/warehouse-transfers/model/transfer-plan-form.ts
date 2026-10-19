import type { RentalItemDto } from "@/features/rental-items/model/rental-item"
import type {
  TransferCabinGroupRequest,
  TransferFurniturePerCabinRequest,
  TransferLooseFurnitureRequest,
} from "@/features/logistics/warehouse-transfers/model/warehouse-transfer"

/** Client-only identity around one server-compatible cabin group draft. */
export type TransferCabinGroupDraft = TransferCabinGroupRequest & {
  key: string
}

/** Human-readable reason why a physical cabin cannot satisfy one group. */
export type TransferCabinMismatch = {
  code:
    | "WAREHOUSE"
    | "STATUS"
    | "RESERVATION"
    | "TYPE"
    | "DIMENSION"
    | "FINISHING"
    | "CHARACTERISTICS"
    | "LINOLEUM"
  message: string
}

/** Required-versus-actual furniture difference for one selected cabin. */
export type TransferCabinFurnitureDelta = {
  furnitureCatalogItemId: string
  required: number
  actual: number
  delta: number
}

/** Creates a catalog-empty cabin group without fabricating catalog values. */
export function emptyTransferCabinGroup(key: string): TransferCabinGroupDraft {
  return {
    key,
    rentalTypeId: "",
    dimensionId: null,
    finishingId: null,
    characteristicIds: [],
    linoleum: null,
    quantity: 1,
    furniturePerCabin: [],
    allocatedCabins: [],
  }
}

function sameIds(left: readonly string[], right: readonly string[]) {
  const sortedLeft = [...left].sort()
  const sortedRight = [...right].sort()
  return (
    sortedLeft.length === sortedRight.length &&
    sortedLeft.every((id, index) => id === sortedRight[index])
  )
}

/** Explains every authoritative cabin fact that conflicts with a group. */
export function cabinGroupMismatches(
  cabin: RentalItemDto,
  group: TransferCabinGroupRequest,
  sourceWarehouseId: string
): TransferCabinMismatch[] {
  const mismatches: TransferCabinMismatch[] = []
  if (cabin.warehouseId !== sourceWarehouseId) {
    mismatches.push({
      code: "WAREHOUSE",
      message: "Бытовка физически находится на другом складе.",
    })
  }
  if (cabin.status !== "FREE") {
    mismatches.push({
      code: "STATUS",
      message: `Статус бытовки: ${cabin.status}. Требуется FREE.`,
    })
  }
  if (cabin.activeOrderReservation) {
    mismatches.push({
      code: "RESERVATION",
      message: "Бытовка уже зарезервирована клиентским заказом.",
    })
  }
  if (cabin.rentalTypeId !== group.rentalTypeId) {
    mismatches.push({ code: "TYPE", message: "Не совпадает тип бытовки." })
  }
  if (cabin.dimensionId !== group.dimensionId) {
    mismatches.push({ code: "DIMENSION", message: "Не совпадают габариты." })
  }
  if (cabin.finishingId !== group.finishingId) {
    mismatches.push({ code: "FINISHING", message: "Не совпадает отделка." })
  }
  if (
    !sameIds(
      (cabin.characteristics ?? []).map((item) => item.id),
      group.characteristicIds
    )
  ) {
    mismatches.push({
      code: "CHARACTERISTICS",
      message: "Не совпадает набор характеристик.",
    })
  }
  if (group.linoleum !== null && cabin.linoleum !== group.linoleum) {
    mismatches.push({
      code: "LINOLEUM",
      message: "Не совпадает признак линолеума.",
    })
  }
  return mismatches
}

/** Allocates distinct FREE exact-match cabins without reserving them in a draft. */
export function autoAllocateCabinGroups(
  groups: readonly TransferCabinGroupDraft[],
  cabins: readonly RentalItemDto[],
  sourceWarehouseId: string
): TransferCabinGroupDraft[] {
  const used = new Set<string>()
  return groups.map((group) => {
    const allocatedCabins = cabins
      .filter(
        (cabin) =>
          !used.has(cabin.id) &&
          cabinGroupMismatches(cabin, group, sourceWarehouseId).length === 0
      )
      .slice(0, group.quantity)
      .map((cabin) => {
        used.add(cabin.id)
        return { assetId: cabin.id, assetVersion: cabin.version }
      })
    return { ...group, allocatedCabins }
  })
}

/** Aggregates per-cabin and independent furniture exactly once. */
export function calculateTransferFurnitureTotals(
  groups: readonly Pick<
    TransferCabinGroupRequest,
    "quantity" | "furniturePerCabin"
  >[],
  looseFurniture: readonly TransferLooseFurnitureRequest[]
) {
  const totals = new Map<
    string,
    { cabinRequirementQuantity: number; looseQuantity: number }
  >()
  for (const group of groups) {
    for (const item of group.furniturePerCabin) {
      const current = totals.get(item.furnitureCatalogItemId) ?? {
        cabinRequirementQuantity: 0,
        looseQuantity: 0,
      }
      current.cabinRequirementQuantity += item.quantityPerCabin * group.quantity
      totals.set(item.furnitureCatalogItemId, current)
    }
  }
  for (const item of looseFurniture) {
    const current = totals.get(item.furnitureCatalogItemId) ?? {
      cabinRequirementQuantity: 0,
      looseQuantity: 0,
    }
    current.looseQuantity += item.quantity
    totals.set(item.furnitureCatalogItemId, current)
  }
  return [...totals.entries()]
    .map(([furnitureCatalogItemId, quantities]) => ({
      furnitureCatalogItemId,
      ...quantities,
      totalQuantity:
        quantities.cabinRequirementQuantity + quantities.looseQuantity,
    }))
    .sort((left, right) =>
      left.furnitureCatalogItemId.localeCompare(right.furnitureCatalogItemId)
    )
}

/** Compares one cabin's physical furniture ledger with group requirements. */
export function calculateCabinFurnitureDelta(
  cabin: Pick<RentalItemDto, "contentsItems">,
  required: readonly TransferFurniturePerCabinRequest[]
): TransferCabinFurnitureDelta[] {
  const actual = new Map<string, number>()
  for (const item of cabin.contentsItems) {
    if (item.equipmentId) {
      actual.set(
        item.equipmentId,
        (actual.get(item.equipmentId) ?? 0) + item.quantity
      )
    }
  }
  const requiredMap = new Map(
    required.map((item) => [item.furnitureCatalogItemId, item.quantityPerCabin])
  )
  return [...new Set([...actual.keys(), ...requiredMap.keys()])]
    .map((furnitureCatalogItemId) => {
      const requiredQuantity = requiredMap.get(furnitureCatalogItemId) ?? 0
      const actualQuantity = actual.get(furnitureCatalogItemId) ?? 0
      return {
        furnitureCatalogItemId,
        required: requiredQuantity,
        actual: actualQuantity,
        delta: requiredQuantity - actualQuantity,
      }
    })
    .filter((item) => item.delta !== 0)
    .sort((left, right) =>
      left.furnitureCatalogItemId.localeCompare(right.furnitureCatalogItemId)
    )
}
