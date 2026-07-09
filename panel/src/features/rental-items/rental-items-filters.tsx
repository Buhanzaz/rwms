import { useMemo, useState } from "react"
import { Filter } from "lucide-react"

import { Button } from "@/components/ui/button"
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

type RentalItemsFiltersProps = {
  options: RentalItemsFilterOptionSet[]
  filters: RentalItemsFiltersState
  onFiltersChange: (filters: RentalItemsFiltersState) => void
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
          variant={selectedValues.length ? "secondary" : "outline"}
          size="sm"
          className="h-8 justify-start gap-2"
        >
          <span>{definition.label}</span>
          {selectedValues.length > 0 && (
            <span className="rounded bg-background px-1 text-xs">
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
            className="h-8"
          />

          <div className="flex gap-2">
            <Button
              variant="outline"
              size="sm"
              className="h-8 flex-1"
              onClick={() => setDraftValues(values)}
            >
              Выбрать все
            </Button>

            <Button
              variant="outline"
              size="sm"
              className="h-8 flex-1"
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
                <span className="truncate">{value}</span>
              </label>
            ))}
          </div>

          <div className="flex justify-between gap-2">
            <Button variant="ghost" size="sm" onClick={clear}>
              Очистить
            </Button>

            <Button size="sm" onClick={apply}>
              Применить
            </Button>
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
    <div className="flex flex-wrap gap-2 rounded-lg border bg-card p-2">
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
