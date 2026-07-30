import { FilterIcon } from "@hugeicons/core-free-icons"
import { HugeiconsIcon } from "@hugeicons/react"

import { SearchableMultiSelectFilter } from "@/components/searchable-multi-select-filter"
import { Button } from "@/components/ui/button"

import {
  createEmptyWarehouseFilters,
  getWarehouseStatusOptions,
  type WarehouseActivityFilter,
  type WarehouseFilterOptions,
  type WarehouseFiltersState,
} from "./warehouse-settings-filtering"

function hasActiveFilters(filters: WarehouseFiltersState) {
  return (
    filters.names.length > 0 ||
    filters.cities.length > 0 ||
    filters.timeZones.length > 0 ||
    filters.statuses.length > 0
  )
}

export function WarehouseFiltersToggle({
  open,
  controls,
  onOpenChange,
}: {
  open: boolean
  controls: string
  onOpenChange: (open: boolean) => void
}) {
  return (
    <Button
      type="button"
      size="icon"
      variant={open ? "secondary" : "outline"}
      aria-label={open ? "Скрыть фильтры складов" : "Показать фильтры складов"}
      aria-controls={controls}
      aria-expanded={open}
      onClick={() => onOpenChange(!open)}
    >
      <HugeiconsIcon icon={FilterIcon} aria-hidden="true" />
    </Button>
  )
}

export function WarehouseSettingsFilters({
  filters,
  options,
  onChange,
}: {
  filters: WarehouseFiltersState
  options: WarehouseFilterOptions
  onChange: (filters: WarehouseFiltersState) => void
}) {
  return (
    <div className="flex flex-col items-stretch gap-2 rounded-lg border bg-card p-2 sm:flex-row sm:flex-wrap sm:items-center">
      <SearchableMultiSelectFilter
        label="Название"
        options={options.names}
        selected={filters.names}
        onApply={(names) => onChange({ ...filters, names })}
      />
      <SearchableMultiSelectFilter
        label="Город"
        options={options.cities}
        selected={filters.cities}
        onApply={(cities) => onChange({ ...filters, cities })}
      />
      <SearchableMultiSelectFilter
        label="Временная зона"
        options={options.timeZones}
        selected={filters.timeZones}
        onApply={(timeZones) => onChange({ ...filters, timeZones })}
      />
      <SearchableMultiSelectFilter<WarehouseActivityFilter>
        label="Статус"
        options={getWarehouseStatusOptions()}
        selected={filters.statuses}
        onApply={(statuses) => onChange({ ...filters, statuses })}
      />
      {hasActiveFilters(filters) ? (
        <Button
          type="button"
          size="default"
          variant="ghost"
          className="h-9 w-full self-start sm:w-auto"
          onClick={() => onChange(createEmptyWarehouseFilters())}
        >
          Сбросить фильтры
        </Button>
      ) : null}
    </div>
  )
}
