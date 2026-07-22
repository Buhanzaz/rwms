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
          size="sm"
          variant={selected.length > 0 ? "secondary" : "outline"}
        >
          {label}
          {selected.length > 0 ? (
            <Badge variant="outline">{selected.length}</Badge>
          ) : null}
          <HugeiconsIcon icon={FilterIcon} data-icon="inline-end" />
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
    <div className="flex flex-wrap items-end gap-2 rounded-lg border bg-card p-2">
      <MultiSelectFilter
        label="Статус"
        options={statusOptions}
        selected={filters.statuses}
        onApply={(statuses) => onChange({ ...filters, statuses })}
      />
      <MultiSelectFilter
        label="Тип клиента"
        options={clientTypeOptions}
        selected={filters.clientTypes}
        onApply={(clientTypes) => onChange({ ...filters, clientTypes })}
      />
      <MultiSelectFilter
        label="Склад"
        options={warehouseOptions}
        selected={filters.warehouseIds}
        onApply={(warehouseIds) => onChange({ ...filters, warehouseIds })}
      />
      <Field className="w-40">
        <FieldLabel htmlFor="orders-created-from">Создан с</FieldLabel>
        <Input
          id="orders-created-from"
          type="date"
          value={filters.createdFrom}
          onChange={(event) =>
            onChange({ ...filters, createdFrom: event.target.value })
          }
        />
      </Field>
      <Field className="w-40">
        <FieldLabel htmlFor="orders-created-to">Создан по</FieldLabel>
        <Input
          id="orders-created-to"
          type="date"
          value={filters.createdTo}
          onChange={(event) =>
            onChange({ ...filters, createdTo: event.target.value })
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
    </div>
  )
}
