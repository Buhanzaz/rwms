import { useMemo, useState } from "react"
import { Filter } from "lucide-react"

import { Button } from "@/components/ui/button"
import { Badge } from "@/components/ui/badge"
import { Checkbox } from "@/components/ui/checkbox"
import { Input } from "@/components/ui/input"
import {
  Popover,
  PopoverContent,
  PopoverTrigger,
} from "@/components/ui/popover"
import type {
  RentalItemsFilterOptionSet,
  RentalItemsFiltersState,
} from "@/features/rental-items/model/rental-item"
import { cn } from "@/lib/utils"

type RentalItemsFiltersProps = {
  options: RentalItemsFilterOptionSet[]
  filters: RentalItemsFiltersState
  onFiltersChange: (filters: RentalItemsFiltersState) => void
}

const statusOptionClassName: Record<string, string> = {
  Аренда: "bg-[var(--status-rented-bg)] text-[var(--status-rented-fg)]",
  "Ожидает осмотра":
    "bg-[var(--status-after-rent-bg)] text-[var(--status-after-rent-fg)]",
  "Ожидает подтверждения сметы":
    "bg-[var(--status-waiting-estimate-confirmation-bg)] text-foreground",
  Бронь: "bg-[var(--status-booked-bg)] text-[var(--status-booked-fg)]",
  "В ремонте": "bg-[var(--status-repair-bg)] text-[var(--status-repair-fg)]",
  Списана:
    "bg-[var(--status-written-off-bg)] text-[var(--status-written-off-fg)]",
  Утеряна: "bg-[var(--status-lost-bg)] text-foreground",
  Капремонт:
    "bg-[var(--status-capital-repair-bg)] text-[var(--status-capital-repair-fg)]",
  "Продажа Б/У": "bg-[var(--status-sale-bg)] text-[var(--status-sale-fg)]",
  Свободна: "bg-[var(--status-free-bg)] text-[var(--status-free-fg)]",
  Склад: "bg-[var(--status-warehouse-bg)] text-[var(--status-warehouse-fg)]",
  "Собственные нужды":
    "bg-[var(--status-own-needs-bg)] text-[var(--status-own-needs-fg)]",
  "В перемещении": "bg-[var(--status-in-transfer-bg)] text-foreground",
}

function FilterButton({
  definition,
  values,
  selectedValues,
  onApply,
}: {
  definition: RentalItemsFilterOptionSet
  values: string[]
  selectedValues: string[]
  onApply: (values: string[]) => void
}) {
  const [open, setOpen] = useState(false)
  const [search, setSearch] = useState("")
  const [draftValues, setDraftValues] = useState<string[]>(selectedValues)
  const hasSelectedValues = selectedValues.length > 0

  const filteredValues = useMemo(() => {
    const normalizedSearch = search.trim().toLowerCase()

    if (!normalizedSearch) {
      return values
    }

    return values.filter((value) =>
      value.toLowerCase().includes(normalizedSearch)
    )
  }, [search, values])

  function toggleValue(value: string) {
    setDraftValues((current) => {
      if (current.includes(value)) {
        return current.filter((item) => item !== value)
      }

      return [...current, value]
    })
  }

  function apply() {
    onApply(draftValues)
    setOpen(false)
  }

  function clear() {
    setDraftValues([])
    onApply([])
    setOpen(false)
  }

  return (
    <Popover
      open={open}
      onOpenChange={(value) => {
        setOpen(value)

        if (value) {
          setDraftValues(selectedValues)
        }
      }}
    >
      <PopoverTrigger asChild>
        <Button
          variant="outline"
          className={cn(
            "h-9 w-full justify-start gap-2 sm:w-auto",
            hasSelectedValues && "rwms-button-light"
          )}
          aria-pressed={hasSelectedValues}
        >
          <span>{definition.label}</span>
          {hasSelectedValues && (
            <span className="text-xs text-current">
              {selectedValues.length}
            </span>
          )}
          <Filter className="ml-auto size-3.5 opacity-60" />
        </Button>
      </PopoverTrigger>

      <PopoverContent align="start" className="w-72 p-2">
        <div className="flex flex-col gap-2">
          <Input
            value={search}
            onChange={(event) => setSearch(event.target.value)}
            placeholder="Поиск в фильтре..."
          />

          <div className="flex gap-2">
            <Button
              variant="outline"
              className="h-9 flex-1"
              onClick={() => setDraftValues(values)}
            >
              Выбрать все
            </Button>

            <Button
              variant="outline"
              className="h-9 flex-1"
              onClick={() => setDraftValues([])}
            >
              Снять
            </Button>
          </div>

          <div className="flex max-h-56 flex-col gap-1 overflow-auto rounded-md border p-1">
            {filteredValues.map((value) => (
              <label
                key={value}
                className="flex cursor-pointer items-center gap-2 rounded px-2 py-1.5 text-sm hover:bg-muted"
              >
                <Checkbox
                  checked={draftValues.includes(value)}
                  onCheckedChange={() => toggleValue(value)}
                />
                {definition.id === "status" ? (
                  <Badge
                    variant="secondary"
                    className={statusOptionClassName[value]}
                  >
                    {value}
                  </Badge>
                ) : (
                  <span className="truncate">{value}</span>
                )}
              </label>
            ))}
          </div>

          <div className="flex justify-between gap-2">
            <Button variant="ghost" onClick={clear}>
              Очистить
            </Button>

            <Button onClick={apply}>Применить</Button>
          </div>
        </div>
      </PopoverContent>
    </Popover>
  )
}

export function RentalItemsFilters({
  options,
  filters,
  onFiltersChange,
}: RentalItemsFiltersProps) {
  function setFilter(key: string, values: string[]) {
    onFiltersChange({
      ...filters,
      [key]: values,
    })
  }

  return (
    <div className="flex flex-col gap-2 rounded-lg border bg-card p-2 sm:flex-row sm:flex-wrap">
      {options.map((definition) => (
        <FilterButton
          key={definition.id}
          definition={definition}
          values={definition.values}
          selectedValues={filters[definition.id] ?? []}
          onApply={(values) => setFilter(definition.id, values)}
        />
      ))}
    </div>
  )
}
