import {
  getRentalItemFieldLabel,
  getVisibleRentalItemFilterDefinitions,
  type RentalItemDto,
  type RentalItemsColumnConfig,
  type RentalItemsFilterOptionSet,
  type RentalItemsFiltersState,
  type RentalItemsTableSchema,
} from "@/features/rental-items/model/rental-item"

export function pruneRentalItemsFilters(
  filters: RentalItemsFiltersState,
  filterOptions: RentalItemsFilterOptionSet[]
) {
  const enabledFilterIds = new Set(filterOptions.map((option) => option.id))

  return Object.fromEntries(
    Object.entries(filters).filter(([key, values]) => {
      return enabledFilterIds.has(key) && values && values.length > 0
    })
  ) as RentalItemsFiltersState
}

export function filterRentalItemsByFilters(
  items: RentalItemDto[],
  filters: RentalItemsFiltersState
) {
  return items.filter((item) =>
    Object.entries(filters).every(([key, values]) => {
      if (!values || values.length === 0) return true
      return values.includes(getRentalItemFieldLabel(item, key))
    })
  )
}

export function buildRentalItemsFilterOptions(
  items: RentalItemDto[],
  schema: RentalItemsTableSchema,
  columnsConfig: RentalItemsColumnConfig[]
): RentalItemsFilterOptionSet[] {
  return getVisibleRentalItemFilterDefinitions(schema, columnsConfig).map(
    (filter) => ({
      ...filter,
      values: Array.from(
        new Set(items.map((item) => getRentalItemFieldLabel(item, filter.id)))
      ).sort((left, right) => left.localeCompare(right, "ru")),
    })
  )
}
