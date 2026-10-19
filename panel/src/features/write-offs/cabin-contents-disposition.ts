import type { EquipmentItemDto } from "@/types/equipment"

import type {
  CabinContentsDispositionMode,
  CabinContentsDispositionPlanInput,
} from "./property-dispositions-api"

export type CabinDispositionContent = {
  equipmentId: string
  equipmentName: string
  equipmentFormat: string
  expectedBalanceVersion: number
  currentQuantity: number
}

export type CabinContentsSnapshotItem = {
  equipmentId?: string
  quantity: number
}

export function cabinDispositionContents(
  cabinId: string,
  equipmentItems: readonly EquipmentItemDto[]
): CabinDispositionContent[] {
  return equipmentItems
    .flatMap((equipment) => {
      const balances = equipment.balances.filter(
        (balance) =>
          balance.rentalItemId === cabinId &&
          (balance.locationKind === "CABIN_NON_RENTED" ||
            balance.locationKind === "CABIN_RENTED") &&
          balance.quantity > 0
      )
      if (balances.length > 1) {
        throw new Error(
          `Оборудование «${equipment.name}» имеет несколько активных остатков в бытовке.`
        )
      }
      return balances.map((balance) => ({
        equipmentId: equipment.id,
        equipmentName: equipment.name,
        equipmentFormat: "шт.",
        expectedBalanceVersion: balance.version,
        currentQuantity: balance.quantity,
      }))
    })
    .sort((left, right) =>
      left.equipmentName.localeCompare(right.equipmentName, "ru")
    )
}

export function buildCabinContentsPlan(
  mode: CabinContentsDispositionMode,
  contents: readonly CabinDispositionContent[],
  selectedQuantities: Readonly<Record<string, number>> = {}
): CabinContentsDispositionPlanInput | null {
  if (contents.length === 0) return null

  const seen = new Set<string>()
  const lines = contents.map((content) => {
    if (seen.has(content.equipmentId)) {
      throw new Error("План содержимого содержит повторяющуюся позицию.")
    }
    seen.add(content.equipmentId)
    if (
      !Number.isSafeInteger(content.currentQuantity) ||
      content.currentQuantity < 1
    ) {
      throw new Error("В план нельзя включить нулевое содержимое бытовки.")
    }
    const requested =
      mode === "DISPOSE_WITH_CABIN"
        ? 0
        : (selectedQuantities[content.equipmentId] ?? 0)
    if (
      !Number.isSafeInteger(requested) ||
      requested < 0 ||
      requested > content.currentQuantity
    ) {
      throw new Error(
        `Количество «${content.equipmentName}» должно быть от 0 до ${content.currentQuantity}.`
      )
    }
    return {
      equipmentId: content.equipmentId,
      expectedBalanceVersion: content.expectedBalanceVersion,
      moveToStockQuantity: requested,
    }
  })

  return { mode, lines }
}

export function cabinContentsQuantitiesAreValid(
  contents: readonly CabinDispositionContent[],
  selectedQuantities: Readonly<Record<string, number>>
) {
  return contents.every((content) => {
    const value = selectedQuantities[content.equipmentId] ?? 0
    return (
      Number.isSafeInteger(value) &&
      value >= 0 &&
      value <= content.currentQuantity
    )
  })
}

export function cabinContentsSnapshotMatches(
  snapshot: readonly CabinContentsSnapshotItem[],
  contents: readonly CabinDispositionContent[]
) {
  const nonzeroSnapshot = snapshot.filter((item) => item.quantity > 0)
  if (nonzeroSnapshot.length !== contents.length) return false

  const seen = new Set<string>()
  return nonzeroSnapshot.every((item) => {
    if (!item.equipmentId || seen.has(item.equipmentId)) return false
    seen.add(item.equipmentId)
    const content = contents.find(
      (candidate) => candidate.equipmentId === item.equipmentId
    )
    return content?.currentQuantity === item.quantity
  })
}
