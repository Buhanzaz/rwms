import type { ReactNode } from "react"
import { FilterIcon } from "@hugeicons/core-free-icons"
import { HugeiconsIcon } from "@hugeicons/react"

import { SearchableMultiSelectFilter } from "@/components/searchable-multi-select-filter"
import { Button } from "@/components/ui/button"
import { SingleDayPicker } from "@/components/ui/single-day-picker"
import {
  Select,
  SelectContent,
  SelectGroup,
  SelectItem,
  SelectTrigger,
  SelectValue,
} from "@/components/ui/select"

export type SchedulePresence = "ALL" | "SCHEDULED" | "UNSCHEDULED"

export type LogisticsDocumentFiltersState<TState extends string> = {
  states: TState[]
  schedule: SchedulePresence
  dateFrom: string
  dateTo: string
}

export type LogisticsDocumentExtraFilter = {
  label: string
  options: Array<{ value: string; label: string }>
  selected: string[]
  onApply: (values: string[]) => void
}

export function LogisticsFiltersToggle({
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
      aria-label={open ? "Скрыть фильтры" : "Показать фильтры"}
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

export function LogisticsDocumentFilters<TState extends string>({
  filters,
  stateOptions,
  stateLabel = "Статус",
  stateAfterExtraFilters = false,
  dateLabel,
  dateFromLabel,
  dateToLabel,
  extraFilters = [],
  leadingControl,
  showSchedule = true,
  showDateRange = true,
  onChange,
  onReset,
}: {
  filters: LogisticsDocumentFiltersState<TState>
  stateOptions: Array<{ value: TState; label: string }>
  stateLabel?: string
  stateAfterExtraFilters?: boolean
  dateLabel: string
  dateFromLabel?: string
  dateToLabel?: string
  extraFilters?: LogisticsDocumentExtraFilter[]
  leadingControl?: ReactNode
  showSchedule?: boolean
  showDateRange?: boolean
  onChange: (filters: LogisticsDocumentFiltersState<TState>) => void
  onReset?: () => void
}) {
  const active =
    filters.states.length > 0 ||
    (showSchedule && filters.schedule !== "ALL") ||
    (showDateRange && (filters.dateFrom !== "" || filters.dateTo !== "")) ||
    extraFilters.some((filter) => filter.selected.length > 0)

  return (
    <div className="flex flex-col items-stretch gap-2 rounded-lg border bg-card p-2 sm:flex-row sm:flex-wrap sm:items-center">
      {leadingControl}
      {!stateAfterExtraFilters ? (
        <SearchableMultiSelectFilter
          label={stateLabel}
          options={stateOptions}
          selected={filters.states}
          onApply={(states) => onChange({ ...filters, states })}
        />
      ) : null}
      {extraFilters.map((filter) => (
        <SearchableMultiSelectFilter key={filter.label} {...filter} />
      ))}
      {stateAfterExtraFilters ? (
        <SearchableMultiSelectFilter
          label={stateLabel}
          options={stateOptions}
          selected={filters.states}
          onApply={(states) => onChange({ ...filters, states })}
        />
      ) : null}
      {showSchedule ? (
        <Select
          value={filters.schedule}
          onValueChange={(schedule) =>
            onChange({
              ...filters,
              schedule: schedule as SchedulePresence,
            })
          }
        >
          <SelectTrigger
            id="document-schedule-filter"
            aria-label="Наличие даты"
            className="h-9 w-full sm:min-w-44 sm:w-auto"
          >
            <SelectValue />
          </SelectTrigger>
          <SelectContent>
            <SelectGroup>
              <SelectItem value="ALL">Все документы</SelectItem>
              <SelectItem value="UNSCHEDULED">Без даты</SelectItem>
              <SelectItem value="SCHEDULED">С датой</SelectItem>
            </SelectGroup>
          </SelectContent>
        </Select>
      ) : null}
      {showDateRange ? (
        <>
          <DateFilter
            id="document-date-from"
            label={dateFromLabel ?? `${dateLabel} с`}
            value={filters.dateFrom}
            onChange={(dateFrom) => onChange({ ...filters, dateFrom })}
          />
          <DateFilter
            id="document-date-to"
            label={dateToLabel ?? `${dateLabel} по`}
            value={filters.dateTo}
            onChange={(dateTo) => onChange({ ...filters, dateTo })}
          />
        </>
      ) : null}
      {active ? (
        <Button
          type="button"
          size="default"
          variant="ghost"
          className="h-9 w-full self-start sm:w-auto"
          onClick={() => {
            if (onReset) {
              onReset()
              return
            }
            onChange({
              states: [],
              schedule: "ALL",
              dateFrom: "",
              dateTo: "",
            })
          }}
        >
          Сбросить фильтры
        </Button>
      ) : null}
    </div>
  )
}
