import type { WarehouseInfo } from "@/api/warehouse-api"

export type WarehouseActivityFilter = "active" | "inactive"

export type WarehouseFiltersState = {
  names: string[]
  cities: string[]
  timeZones: string[]
  statuses: WarehouseActivityFilter[]
}

export type WarehouseFilterOption = {
  value: string
  label: string
}

export type WarehouseFilterOptions = {
  names: WarehouseFilterOption[]
  cities: WarehouseFilterOption[]
  timeZones: WarehouseFilterOption[]
}

const warehouseStatusOptions: Array<{
  value: WarehouseActivityFilter
  label: string
}> = [
  { value: "active", label: "Активные" },
  { value: "inactive", label: "Неактивные" },
]

const russianCollator = new Intl.Collator("ru", {
  numeric: true,
  sensitivity: "base",
})

export function createEmptyWarehouseFilters(): WarehouseFiltersState {
  return {
    names: [],
    cities: [],
    timeZones: [],
    statuses: [],
  }
}

function textOptions(values: string[]): WarehouseFilterOption[] {
  return [...new Set(values)]
    .sort((left, right) => russianCollator.compare(left, right))
    .map((value) => ({ value, label: value }))
}

export function getWarehouseFilterOptions(
  warehouses: WarehouseInfo[]
): WarehouseFilterOptions {
  return {
    names: textOptions(warehouses.map((warehouse) => warehouse.name)),
    cities: textOptions(warehouses.map((warehouse) => warehouse.city)),
    timeZones: textOptions(warehouses.map((warehouse) => warehouse.timeZone)),
  }
}

export function getWarehouseStatusOptions() {
  return warehouseStatusOptions
}

function matchesSearch(warehouse: WarehouseInfo, search: string) {
  if (search === "") return true

  return [
    warehouse.name,
    warehouse.city,
    warehouse.timeZone,
    warehouse.active ? "активен" : "неактивен",
  ].some((value) => value.toLocaleLowerCase("ru").includes(search))
}

function matchesSelected(values: string[], value: string) {
  return values.length === 0 || values.includes(value)
}

export function filterWarehouses(
  warehouses: WarehouseInfo[],
  search: string,
  filters: WarehouseFiltersState
) {
  const normalizedSearch = search.trim().toLocaleLowerCase("ru")

  return warehouses.filter((warehouse) => {
    const status: WarehouseActivityFilter = warehouse.active
      ? "active"
      : "inactive"

    return (
      matchesSearch(warehouse, normalizedSearch) &&
      matchesSelected(filters.names, warehouse.name) &&
      matchesSelected(filters.cities, warehouse.city) &&
      matchesSelected(filters.timeZones, warehouse.timeZone) &&
      matchesSelected(filters.statuses, status)
    )
  })
}
