import type { PublicClientPresentation } from "@/features/assistant/api/rental-presentations-api"

export type FurnitureDraft = Record<string, Record<string, number>>

export function selectedCabins(
  presentation: PublicClientPresentation,
  selectedIds: readonly string[]
) {
  const selected = new Set(selectedIds)
  return presentation.groups
    .flatMap((group) => group.cabins)
    .filter((cabin) => selected.has(cabin.id))
}

export function desiredQuantity(
  draft: FurnitureDraft,
  cabinId: string,
  equipmentId: string
) {
  return draft[cabinId]?.[equipmentId] ?? 0
}

/**
 * Computes the largest quantity currently assignable to one cabin from the
 * shared server availability plus furniture already inside selected held cabins.
 */
export function equipmentCapacityForCabin(params: {
  presentation: PublicClientPresentation
  selectedIds: readonly string[]
  draft: FurnitureDraft
  cabinId: string
  equipmentId: string
}) {
  const availability = params.presentation.equipmentAvailability.find(
    (item) => item.equipmentId === params.equipmentId
  )
  if (!availability) return 0
  const cabins = selectedCabins(params.presentation, params.selectedIds)
  const physicalQuantity = cabins.reduce(
    (total, cabin) =>
      total +
      cabin.currentContents
        .filter((item) => item.equipmentId === params.equipmentId)
        .reduce((sum, item) => sum + item.quantity, 0),
    0
  )
  const aggregateDesired = cabins.reduce(
    (total, cabin) =>
      total + desiredQuantity(params.draft, cabin.id, params.equipmentId),
    0
  )
  const ownDesired = desiredQuantity(
    params.draft,
    params.cabinId,
    params.equipmentId
  )
  const sharedCapacity = Math.max(
    0,
    ownDesired +
      availability.availableQuantity +
      physicalQuantity -
      aggregateDesired
  )
  return Math.min(
    sharedCapacity,
    availability.maximumPerCabin ?? Number.MAX_SAFE_INTEGER
  )
}

/** Returns explainable client-side conflicts without replacing server authority. */
export function presentationDraftIssues(params: {
  presentation: PublicClientPresentation
  selectedIds: readonly string[]
  draft: FurnitureDraft
}) {
  const allCabinIds = new Set(
    params.presentation.groups.flatMap((group) =>
      group.cabins.map((cabin) => cabin.id)
    )
  )
  const issues: string[] = []
  params.selectedIds.forEach((cabinId) => {
    if (!allCabinIds.has(cabinId)) {
      issues.push("Одна из выбранных бытовок больше не входит в представление.")
      return
    }
    const availableEquipmentIds = new Set(
      params.presentation.equipmentAvailability.map((item) => item.equipmentId)
    )
    Object.entries(params.draft[cabinId] ?? {}).forEach(
      ([equipmentId, quantity]) => {
        if (quantity > 0 && !availableEquipmentIds.has(equipmentId)) {
          issues.push(
            `Позиция мебели ${equipmentId} больше не доступна в этом представлении.`
          )
        }
      }
    )
    params.presentation.equipmentAvailability.forEach((equipment) => {
      const quantity = desiredQuantity(
        params.draft,
        cabinId,
        equipment.equipmentId
      )
      const capacity = equipmentCapacityForCabin({
        ...params,
        cabinId,
        equipmentId: equipment.equipmentId,
      })
      if (quantity > capacity) {
        issues.push(
          `${equipment.equipmentName}: выбранное количество ${quantity} больше доступного ${capacity}.`
        )
      }
    })
  })
  return [...new Set(issues)]
}
