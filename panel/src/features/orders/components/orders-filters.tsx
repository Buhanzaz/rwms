import { useState } from "react"
import { FilterIcon } from "@hugeicons/core-free-icons"
import { HugeiconsIcon } from "@hugeicons/react"

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
import type {
  OrderClientType,
  OrderStatus,
} from "@/features/orders/domain/orders"

export type OrdersFiltersState = {
  statuses: OrderStatus[]
  clientTypes: OrderClientType[]
  warehouseIds: string[]
  createdFrom: string
  createdTo: string
}

type FilterOption<T extends string> = {
  value: T
  label: string
}

export function OrdersFiltersToggle({
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
      aria-label={
        open ? "Скрыть фильтры бронирований" : "Показать фильтры бронирований"
      }
      aria-controls={controls}
      aria-expanded={open}
      onClick={() => onOpenChange(!open)}
    >
      <HugeiconsIcon icon={FilterIcon} aria-hidden="true" />
    </Button>
  )
}

function MultiSelectFilter<T extends string>({
  label,
  options,
  selected,
  onApply,
}: {
  label: string
  options: FilterOption<T>[]
  selected: T[]
  onApply: (values: T[]) => void
}) {
  const [open, setOpen] = useState(false)
  const [draft, setDraft] = useState<T[]>(selected)
  const controlPrefix = label.toLocaleLowerCase("ru-RU").replace(/\s+/g, "-")
  const hasSelectedValues = selected.length > 0

  function toggle(value: T) {
    setDraft((current) =>
      current.includes(value)
        ? current.filter((candidate) => candidate !== value)
        : [...current, value]
    )
  }

  return (
    <Popover
      open={open}
      onOpenChange={(nextOpen) => {
        setOpen(nextOpen)
        if (nextOpen) setDraft(selected)
      }}
    >
      <PopoverTrigger asChild>
        <Button
          type="button"
          size="default"
          variant="outline"
          className={`h-9 w-full justify-start${
            hasSelectedValues ? " rwms-button-light" : ""
          }`}
          aria-pressed={hasSelectedValues}
        >
          {label}
          {hasSelectedValues ? (
            <span className="text-xs text-current">{selected.length}</span>
          ) : null}
          <HugeiconsIcon
            icon={FilterIcon}
            data-icon="inline-end"
            className="ml-auto"
          />
        </Button>
      </PopoverTrigger>
      <PopoverContent align="start" className="w-72">
        <FieldSet>
          <FieldLegend variant="label">{label}</FieldLegend>
          <FieldGroup className="max-h-64 overflow-auto">
            {options.map((option) => (
              <Field key={option.value} orientation="horizontal">
                <Checkbox
                  id={`${controlPrefix}-${option.value}`}
                  checked={draft.includes(option.value)}
                  onCheckedChange={() => toggle(option.value)}
                />
                <FieldLabel
                  htmlFor={`${controlPrefix}-${option.value}`}
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
              setDraft([])
              onApply([])
              setOpen(false)
            }}
          >
            Очистить
          </Button>
          <Button
            type="button"
            size="sm"
            onClick={() => {
              onApply(draft)
              setOpen(false)
            }}
          >
            Применить
          </Button>
        </div>
      </PopoverContent>
    </Popover>
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
    <Field
      orientation="horizontal"
      className="h-9 w-full gap-2 rounded-md border border-input bg-background px-3 py-1 text-sm shadow-xs transition-[color,box-shadow] focus-within:border-ring focus-within:ring-[3px] focus-within:ring-ring/50 dark:bg-input/30"
    >
      <FieldLabel
        htmlFor={id}
        className="!flex-none shrink-0 font-normal text-muted-foreground"
      >
        {label}
      </FieldLabel>
      <Input
        id={id}
        type="date"
        value={value}
        className="h-auto min-w-0 flex-1 border-0 bg-transparent p-0 text-sm shadow-none focus-visible:border-0 focus-visible:ring-0 dark:bg-transparent"
        onChange={(event) => onChange(event.target.value)}
      />
    </Field>
  )
}

export function OrdersFilters({
  filters,
  statusOptions,
  clientTypeOptions,
  warehouseOptions,
  onChange,
}: {
  filters: OrdersFiltersState
  statusOptions: FilterOption<OrderStatus>[]
  clientTypeOptions: FilterOption<OrderClientType>[]
  warehouseOptions: FilterOption<string>[]
  onChange: (filters: OrdersFiltersState) => void
}) {
  const active =
    filters.statuses.length > 0 ||
    filters.clientTypes.length > 0 ||
    filters.warehouseIds.length > 0 ||
    filters.createdFrom !== "" ||
    filters.createdTo !== ""

  return (
    <FieldGroup className="gap-2 rounded-lg border bg-card p-2">
      <FieldGroup
        data-slot="orders-filter-columns"
        className="flex flex-col gap-2 sm:flex-row sm:flex-wrap"
      >
        <div data-slot="orders-filter-warehouse" className="w-full sm:w-40">
          <MultiSelectFilter
            label="Склад"
            options={warehouseOptions}
            selected={filters.warehouseIds}
            onApply={(warehouseIds) => onChange({ ...filters, warehouseIds })}
          />
        </div>
        <div data-slot="orders-filter-status" className="w-full sm:w-40">
          <MultiSelectFilter
            label="Статус"
            options={statusOptions}
            selected={filters.statuses}
            onApply={(statuses) => onChange({ ...filters, statuses })}
          />
        </div>
        <div data-slot="orders-filter-client-type" className="w-full sm:w-40">
          <MultiSelectFilter
            label="Тип клиента"
            options={clientTypeOptions}
            selected={filters.clientTypes}
            onApply={(clientTypes) => onChange({ ...filters, clientTypes })}
          />
        </div>
        <div data-slot="orders-filter-created-from" className="w-full sm:w-52">
          <DateFilter
            id="orders-created-from"
            label="Создан с"
            value={filters.createdFrom}
            onChange={(createdFrom) => onChange({ ...filters, createdFrom })}
          />
        </div>
        <div data-slot="orders-filter-created-to" className="w-full sm:w-52">
          <DateFilter
            id="orders-created-to"
            label="Создан по"
            value={filters.createdTo}
            onChange={(createdTo) => onChange({ ...filters, createdTo })}
          />
        </div>
      </FieldGroup>
      {active ? (
        <Button
          type="button"
          size="default"
          variant="ghost"
          className="h-9 w-full self-start sm:w-auto"
          onClick={() =>
            onChange({
              statuses: [],
              clientTypes: [],
              warehouseIds: [],
              createdFrom: "",
              createdTo: "",
            })
          }
        >
          Сбросить фильтры
        </Button>
      ) : null}
    </FieldGroup>
  )
}
