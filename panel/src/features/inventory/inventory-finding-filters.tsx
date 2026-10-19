import { useId, useState } from "react"
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
import {
  createEmptyInventoryFindingFilters,
  type InventoryFindingFiltersState,
} from "@/features/inventory/inventory-finding-filtering"
import type {
  InventoryFindingOrigin,
  InventoryReconciliationStatus,
} from "@/features/inventory/model/inventory"
import {
  RENTAL_ITEM_STATUS_LABEL,
  type RentalItemStatus,
} from "@/features/rental-items/model/rental-item"

type InventoryFindingInspectionFilter = "INSPECTED" | "NOT_INSPECTED"
type InventoryFindingAdditionFilter = "ADDED" | "NOT_ADDED"
type InventoryFindingPresenceFilter = "FOUND" | "MISSING"
type InventoryFindingWorkFilter = "WITH_WORK" | "WITHOUT_WORK"

type FilterOption<T extends string> = {
  value: T
  label: string
}

const originOptions: FilterOption<InventoryFindingOrigin>[] = [
  { value: "EXPECTED", label: "Ожидалась" },
  { value: "ADDED_NEW", label: "Добавлена новая" },
  { value: "ADDED_USED", label: "Добавлена б/у" },
  { value: "UNEXPECTED_EXISTING", label: "Неожиданная" },
]

const reconciliationOptions: FilterOption<InventoryReconciliationStatus>[] = [
  { value: "MATCHED", label: "Совпало" },
  { value: "MISSING", label: "Не найдена" },
  { value: "CONFLICT", label: "Есть конфликт" },
]

const inspectionOptions: FilterOption<InventoryFindingInspectionFilter>[] = [
  { value: "INSPECTED", label: "Проверены" },
  { value: "NOT_INSPECTED", label: "Не проверены" },
]

const additionOptions: FilterOption<InventoryFindingAdditionFilter>[] = [
  { value: "ADDED", label: "Добавлены" },
  { value: "NOT_ADDED", label: "Не добавлены" },
]

const presenceOptions: FilterOption<InventoryFindingPresenceFilter>[] = [
  { value: "FOUND", label: "Найдены" },
  { value: "MISSING", label: "Не найдены" },
]

const workOptions: FilterOption<InventoryFindingWorkFilter>[] = [
  { value: "WITH_WORK", label: "С работами" },
  { value: "WITHOUT_WORK", label: "Без работ" },
]

const statusOptions = Object.entries(RENTAL_ITEM_STATUS_LABEL).map(
  ([value, label]) => ({ value: value as RentalItemStatus, label })
)

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
  const controlId = useId()
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
          className={`h-9 w-full justify-start sm:min-w-40 sm:w-auto${
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
          <FieldGroup className="max-h-64 gap-1 overflow-auto">
            {options.map((option, index) => {
              const optionId = `${controlId}-${index}`
              return (
                <Field key={option.value} orientation="horizontal">
                  <Checkbox
                    id={optionId}
                    checked={draft.includes(option.value)}
                    onCheckedChange={() => toggle(option.value)}
                  />
                  <FieldLabel htmlFor={optionId} className="font-normal">
                    {option.label}
                  </FieldLabel>
                </Field>
              )
            })}
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

function activeFiltersCount(filters: InventoryFindingFiltersState) {
  return (
    Number(filters.cabinNumber.trim().length > 0) +
    filters.origins.length +
    filters.statuses.length +
    filters.reconciliations.length +
    filters.inspections.length +
    filters.additions.length +
    filters.presences.length +
    filters.works.length
  )
}

export function InventoryFindingFilters({
  filters,
  onChange,
}: {
  filters: InventoryFindingFiltersState
  onChange: (filters: InventoryFindingFiltersState) => void
}) {
  const active = activeFiltersCount(filters) > 0

  return (
    <div className="flex flex-col items-stretch gap-2 rounded-lg border bg-card p-2 sm:flex-row sm:flex-wrap sm:items-center">
      <Input
        id="inventory-finding-number-filter"
        aria-label="Номер бытовки"
        placeholder="Номер бытовки"
        value={filters.cabinNumber}
        className="h-9 w-full sm:min-w-52 sm:w-auto"
        onChange={(event) =>
          onChange({ ...filters, cabinNumber: event.target.value })
        }
      />
      <MultiSelectFilter
        label="Источник"
        options={originOptions}
        selected={filters.origins}
        onApply={(origins) => onChange({ ...filters, origins })}
      />
      <MultiSelectFilter
        label="Статус"
        options={statusOptions}
        selected={filters.statuses}
        onApply={(statuses) => onChange({ ...filters, statuses })}
      />
      <MultiSelectFilter
        label="Сверка"
        options={reconciliationOptions}
        selected={filters.reconciliations}
        onApply={(reconciliations) => onChange({ ...filters, reconciliations })}
      />
      <MultiSelectFilter
        label="Осмотр"
        options={inspectionOptions}
        selected={filters.inspections}
        onApply={(inspections) => onChange({ ...filters, inspections })}
      />
      <MultiSelectFilter
        label="Добавление"
        options={additionOptions}
        selected={filters.additions}
        onApply={(additions) => onChange({ ...filters, additions })}
      />
      <MultiSelectFilter
        label="Наличие"
        options={presenceOptions}
        selected={filters.presences}
        onApply={(presences) => onChange({ ...filters, presences })}
      />
      <MultiSelectFilter
        label="Работы"
        options={workOptions}
        selected={filters.works}
        onApply={(works) => onChange({ ...filters, works })}
      />
      {active ? (
        <Button
          type="button"
          size="default"
          variant="ghost"
          className="h-9 w-full self-start sm:w-auto"
          onClick={() => onChange(createEmptyInventoryFindingFilters())}
        >
          Сбросить фильтры
        </Button>
      ) : null}
    </div>
  )
}
