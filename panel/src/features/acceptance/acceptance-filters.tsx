import { FilterIcon } from "@hugeicons/core-free-icons"
import { HugeiconsIcon } from "@hugeicons/react"

import { SearchableMultiSelectFilter } from "@/components/searchable-multi-select-filter"
import { Button } from "@/components/ui/button"
import { Input } from "@/components/ui/input"
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
    <label
      htmlFor={id}
      className="flex h-9 w-full items-center gap-2 rounded-md border border-input bg-background px-3 py-1 text-sm shadow-xs transition-[color,box-shadow] focus-within:border-ring focus-within:ring-[3px] focus-within:ring-ring/50 sm:min-w-52 sm:w-auto dark:bg-input/30"
    >
      <span className="shrink-0 text-muted-foreground">{label}</span>
      <Input
        id={id}
        type="date"
        value={value}
        className="h-auto min-w-0 flex-1 border-0 bg-transparent p-0 text-sm shadow-none focus-visible:border-0 focus-visible:ring-0 dark:bg-transparent"
        onChange={(event) => onChange(event.target.value)}
      />
    </label>
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
