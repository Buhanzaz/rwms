import { useState } from "react"
import { FilterIcon } from "@hugeicons/core-free-icons"
import { HugeiconsIcon } from "@hugeicons/react"

import { Badge } from "@/components/ui/badge"
import { Button } from "@/components/ui/button"
import { Checkbox } from "@/components/ui/checkbox"
import {
  Field,
  FieldGroup,
  FieldLabel,
  FieldLegend,
  FieldSet,
} from "@/components/ui/field"
import { Input } from "@/components/ui/input"
import {
  Popover,
  PopoverContent,
  PopoverTrigger,
} from "@/components/ui/popover"
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

export function LogisticsDocumentFilters<TState extends string>({
  filters,
  stateOptions,
  dateLabel,
  onChange,
}: {
  filters: LogisticsDocumentFiltersState<TState>
  stateOptions: Array<{ value: TState; label: string }>
  dateLabel: string
  onChange: (filters: LogisticsDocumentFiltersState<TState>) => void
}) {
  const [statusOpen, setStatusOpen] = useState(false)
  const [draftStates, setDraftStates] = useState<TState[]>(filters.states)
  const active =
    filters.states.length > 0 ||
    filters.schedule !== "ALL" ||
    filters.dateFrom !== "" ||
    filters.dateTo !== ""

  function toggleState(value: TState) {
    setDraftStates((current) =>
      current.includes(value)
        ? current.filter((candidate) => candidate !== value)
        : [...current, value]
    )
  }

  return (
    <div className="flex flex-wrap items-end gap-2 rounded-lg border bg-card p-2">
      <Popover
        open={statusOpen}
        onOpenChange={(open) => {
          setStatusOpen(open)
          if (open) setDraftStates(filters.states)
        }}
      >
        <PopoverTrigger asChild>
          <Button
            type="button"
            size="sm"
            variant={filters.states.length > 0 ? "secondary" : "outline"}
          >
            Статус
            {filters.states.length > 0 ? (
              <Badge variant="outline">{filters.states.length}</Badge>
            ) : null}
            <HugeiconsIcon icon={FilterIcon} data-icon="inline-end" />
          </Button>
        </PopoverTrigger>
        <PopoverContent align="start" className="w-72">
          <FieldSet>
            <FieldLegend variant="label">Статусы</FieldLegend>
            <FieldGroup className="max-h-64 overflow-auto">
              {stateOptions.map((option) => (
                <Field key={option.value} orientation="horizontal">
                  <Checkbox
                    id={`document-state-${option.value}`}
                    checked={draftStates.includes(option.value)}
                    onCheckedChange={() => toggleState(option.value)}
                  />
                  <FieldLabel
                    htmlFor={`document-state-${option.value}`}
                    className="font-normal"
                  >
                    {option.label}
                  </FieldLabel>
                </Field>
              ))}
            </FieldGroup>
          </FieldSet>
          <div className="mt-3 flex justify-between gap-2">
            <Button
              type="button"
              size="sm"
              variant="ghost"
              onClick={() => {
                setDraftStates([])
                onChange({ ...filters, states: [] })
                setStatusOpen(false)
              }}
            >
              Очистить
            </Button>
            <Button
              type="button"
              size="sm"
              onClick={() => {
                onChange({ ...filters, states: draftStates })
                setStatusOpen(false)
              }}
            >
              Применить
            </Button>
          </div>
        </PopoverContent>
      </Popover>

      <Field className="w-52">
        <FieldLabel htmlFor="document-schedule-filter">Дата</FieldLabel>
        <Select
          value={filters.schedule}
          onValueChange={(schedule) =>
            onChange({
              ...filters,
              schedule: schedule as SchedulePresence,
            })
          }
        >
          <SelectTrigger id="document-schedule-filter">
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
      </Field>

      <Field className="w-40">
        <FieldLabel htmlFor="document-date-from">{dateLabel} с</FieldLabel>
        <Input
          id="document-date-from"
          type="date"
          value={filters.dateFrom}
          onChange={(event) =>
            onChange({ ...filters, dateFrom: event.target.value })
          }
        />
      </Field>
      <Field className="w-40">
        <FieldLabel htmlFor="document-date-to">{dateLabel} по</FieldLabel>
        <Input
          id="document-date-to"
          type="date"
          value={filters.dateTo}
          onChange={(event) =>
            onChange({ ...filters, dateTo: event.target.value })
          }
        />
      </Field>
      {active ? (
        <Button
          type="button"
          size="sm"
          variant="ghost"
          onClick={() =>
            onChange({
              states: [],
              schedule: "ALL",
              dateFrom: "",
              dateTo: "",
            })
          }
        >
          Сбросить фильтры
        </Button>
      ) : null}
    </div>
  )
}
