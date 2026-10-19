import type { RentalItemDto } from "@/features/rental-items/model/rental-item"
import type { EquipmentItemDto } from "@/types/equipment"

export type CabinFurnitureRequirementInput = {
  equipmentId: string
  quantity: number
}

export function furnitureEquipmentIds(items: EquipmentItemDto[] | undefined) {
  if (!items) return undefined
  return new Set(
    items.filter((item) => item.category === "FURNITURE").map((item) => item.id)
  )
}

export function cabinFurnitureRows(
  cabin: Pick<RentalItemDto, "contentsItems">,
  furnitureIds?: ReadonlySet<string>
) {
  return cabin.contentsItems.filter(
    (item) =>
      item.quantity > 0 &&
      (!furnitureIds ||
        (item.equipmentId !== undefined && furnitureIds.has(item.equipmentId)))
  )
}

export function cabinHasFurniture(
  cabin: Pick<RentalItemDto, "contents" | "contentsItems">,
  furnitureIds?: ReadonlySet<string>
) {
  return (
    cabinFurnitureRows(cabin, furnitureIds).length > 0 ||
    (!furnitureIds && Boolean(cabin.contents?.trim()))
  )
}
