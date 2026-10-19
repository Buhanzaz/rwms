import type { RentalItemCreationOptions } from "@/features/rental-items/api/asset-rental-items-api"

export type RentalItemCompositionFormValue = {
  rentalTypeId: string
  dimensionId: string
  finishingId: string
  category: string
  characteristicIds: string[]
  linoleum: "" | "yes" | "no"
}

export type RentalItemCompositionCategoryMode = "NEW" | "USED" | "EDIT"

export function emptyRentalItemComposition(): RentalItemCompositionFormValue {
  return {
    rentalTypeId: "",
    dimensionId: "",
    finishingId: "",
    category: "",
    characteristicIds: [],
    linoleum: "",
  }
}

export function compositionCategoryOptions(
  options: RentalItemCreationOptions,
  mode: RentalItemCompositionCategoryMode,
  currentCategory: string
) {
  const values =
    mode === "NEW"
      ? [options.newCategory]
      : mode === "USED"
        ? options.usedCategories
        : [
            currentCategory,
            ...options.categories.map((category) => category.name),
          ]

  return Array.from(new Set(values.filter((value) => value.trim() !== "")))
}

export function dimensionsForRentalType(
  options: RentalItemCreationOptions,
  rentalTypeId: string
) {
  if (!rentalTypeId) return []

  const dimensionsById = new Map(
    options.dimensions.map((dimension) => [dimension.id, dimension])
  )

  return options.typeDimensions
    .filter((link) => link.typeId === rentalTypeId)
    .sort(
      (left, right) =>
        left.sortOrder - right.sortOrder ||
        left.dimensionId.localeCompare(right.dimensionId)
    )
    .flatMap((link) => {
      const dimension = dimensionsById.get(link.dimensionId)
      return dimension ? [dimension] : []
    })
}

export function isRentalItemCompositionComplete(
  value: RentalItemCompositionFormValue
) {
  return (
    value.rentalTypeId !== "" &&
    value.dimensionId !== "" &&
    value.finishingId !== "" &&
    value.category.trim() !== "" &&
    value.linoleum !== ""
  )
}
