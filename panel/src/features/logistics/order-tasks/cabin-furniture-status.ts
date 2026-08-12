export type FurnitureQuantityItem = {
  equipmentId?: string | null
  quantity: number
}

export type CabinFurnitureStatus =
  "required" | "action" | "loaded" | "none" | "unavailable"

function quantitiesByEquipment(
  items: readonly FurnitureQuantityItem[]
): Map<string, number> {
  const result = new Map<string, number>()

  items.forEach((item, index) => {
    if (!Number.isFinite(item.quantity) || item.quantity <= 0) return
    const equipmentId = item.equipmentId?.trim()
    const key = equipmentId || `__unidentified_${index}`
    result.set(key, (result.get(key) ?? 0) + item.quantity)
  })

  return result
}

/**
 * Compares requested and actual cabin contents by canonical equipment identity
 * and total quantity. Unexpected or excess contents require an explicit action
 * and take precedence over a simultaneous shortage.
 */
export function classifyCabinFurniture(
  desired: readonly FurnitureQuantityItem[],
  actual: readonly FurnitureQuantityItem[],
  unavailable = false
): CabinFurnitureStatus {
  if (unavailable) return "unavailable"

  const desiredQuantities = quantitiesByEquipment(desired)
  const actualQuantities = quantitiesByEquipment(actual)

  if (desiredQuantities.size === 0 && actualQuantities.size === 0) return "none"

  const hasUnexpectedOrExcess = [...actualQuantities].some(
    ([equipmentId, quantity]) =>
      equipmentId.startsWith("__unidentified_") ||
      quantity > (desiredQuantities.get(equipmentId) ?? 0)
  )
  if (hasUnexpectedOrExcess) return "action"

  const hasShortage = [...desiredQuantities].some(
    ([equipmentId, quantity]) =>
      (actualQuantities.get(equipmentId) ?? 0) < quantity
  )
  if (hasShortage) return "required"

  return "loaded"
}
