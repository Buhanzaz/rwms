import { FilterIcon } from "@hugeicons/core-free-icons"
import { HugeiconsIcon } from "@hugeicons/react"

import { SearchableMultiSelectFilter } from "@/components/searchable-multi-select-filter"
import { Button } from "@/components/ui/button"
import { SingleDayPicker } from "@/components/ui/single-day-picker"
import type { RepairTaskAcceptanceStatus } from "@/features/repair-tasks/model/repair-task"

import {
  acceptanceStatusOptions,
  createEmptyAcceptanceFilters,
  type AcceptanceFilterOptions,
  type AcceptanceFiltersState,
} from "./acceptance-filtering"

export function AcceptanceFiltersToggle({
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
      aria-label={open ? "Скрыть фильтры приёмки" : "Показать фильтры приёмки"}
      aria-controls={controls}
      aria-expanded={open}
      onClick={() => onOpenChange(!open)}
    >
      <HugeiconsIcon icon={FilterIcon} aria-hidden="true" />
    </Button>
  )
}

function DateFilter({
  id,
  label,
  value,
  onChange,
}: {
  id: string
  label: string
  value: string
  onChange: (value: string) => void
}) {
  return (
    <SingleDayPicker
      id={id}
      label={label}
      hideLabel
      triggerLabel={label}
      value={value}
      className="w-full sm:w-56"
      onValueChange={onChange}
    />
  )
}

function hasActiveFilters(filters: AcceptanceFiltersState) {
  return (
    filters.sources.length > 0 ||
    filters.sourceParties.length > 0 ||
    filters.authors.length > 0 ||
    filters.statuses.length > 0 ||
    filters.readyAtFrom !== "" ||
    filters.readyAtTo !== ""
  )
}

export function AcceptanceFilters({
  filters,
  options,
  onChange,
}: {
  filters: AcceptanceFiltersState
  options: AcceptanceFilterOptions
  onChange: (filters: AcceptanceFiltersState) => void
}) {
  return (
    <div className="flex flex-col items-stretch gap-2 rounded-lg border bg-card p-2 sm:flex-row sm:flex-wrap sm:items-center">
      <SearchableMultiSelectFilter
        label="Источник"
        options={options.sources}
        selected={filters.sources}
        onApply={(sources) => onChange({ ...filters, sources })}
      />
      <SearchableMultiSelectFilter
        label="От кого"
        options={options.sourceParties}
        selected={filters.sourceParties}
        onApply={(sourceParties) => onChange({ ...filters, sourceParties })}
      />
      <SearchableMultiSelectFilter
        label="Автор"
        options={options.authors}
        selected={filters.authors}
        onApply={(authors) => onChange({ ...filters, authors })}
      />
      <SearchableMultiSelectFilter<RepairTaskAcceptanceStatus>
        label="Статус"
        options={acceptanceStatusOptions}
        selected={filters.statuses}
        onApply={(statuses) => onChange({ ...filters, statuses })}
      />
      <DateFilter
        id="acceptance-ready-at-from"
        label="Готово с"
        value={filters.readyAtFrom}
        onChange={(readyAtFrom) => onChange({ ...filters, readyAtFrom })}
      />
      <DateFilter
        id="acceptance-ready-at-to"
        label="Готово по"
        value={filters.readyAtTo}
        onChange={(readyAtTo) => onChange({ ...filters, readyAtTo })}
      />
      {hasActiveFilters(filters) ? (
        <Button
          type="button"
          size="default"
          variant="ghost"
          className="h-9 w-full self-start sm:w-auto"
          onClick={() => onChange(createEmptyAcceptanceFilters())}
        >
          Сбросить фильтры
        </Button>
      ) : null}
    </div>
  )
}
