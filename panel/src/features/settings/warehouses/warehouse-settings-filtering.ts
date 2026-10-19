import type {
  WarehouseInfo,
  WarehouseLifecycleState,
} from "@/api/warehouse-api"

export type WarehouseLifecycleFilter = WarehouseLifecycleState

export type WarehouseFiltersState = {
  names: string[]
  cities: string[]
  timeZones: string[]
  statuses: WarehouseLifecycleFilter[]
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
  value: WarehouseLifecycleFilter
  label: string
}> = [
  { value: "ACTIVE", label: "Активные" },
  { value: "DRAINING", label: "Выводятся из работы" },
  { value: "INACTIVE", label: "Неактивные" },
]

const russianCollator = new Intl.Collator("ru", {
  numeric: true,
  sensitivity: "base",
})

const warehouseStatusSearchText: Record<WarehouseLifecycleState, string> = {
  ACTIVE: "активен",
  DRAINING: "выводится из работы",
  INACTIVE: "неактивен",
}

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

function matchesSearch(
  warehouse: WarehouseInfo,
  warehousesById: ReadonlyMap<string, WarehouseInfo>,
  search: string
) {
  if (search === "") return true

  const representativeParentWarehouseId = warehouse.representativeParentWarehouseId
  const representativeParentName = representativeParentWarehouseId
    ? warehousesById.get(representativeParentWarehouseId)?.name
    : undefined
  const production = warehouse.production

  return [
    warehouse.name,
    warehouse.city,
    warehouse.timeZone,
    warehouseStatusSearchText[warehouse.lifecycleState],
    production ? "производство" : "",
    warehouse.mainWarehouse ? "основной склад" : "",
    representativeParentWarehouseId ? "представительский склад" : "",
    representativeParentName,
  ].some((value) => value?.toLocaleLowerCase("ru").includes(search) === true)
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
  const warehousesById = new Map(
    warehouses.map((warehouse) => [warehouse.id, warehouse])
  )

  return warehouses.filter((warehouse) => {
    return (
      matchesSearch(warehouse, warehousesById, normalizedSearch) &&
      matchesSelected(filters.names, warehouse.name) &&
      matchesSelected(filters.cities, warehouse.city) &&
      matchesSelected(filters.timeZones, warehouse.timeZone) &&
      matchesSelected(filters.statuses, warehouse.lifecycleState)
    )
  })
}
