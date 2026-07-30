import { useId, useMemo, useState } from "react"
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
  RepairsTableFilterDefinition,
  RepairsTableFiltersState,
} from "@/features/repairs/repairs-table-model"

function RepairsTableFilterButton({
  definition,
  selectedValues,
  onApply,
}: {
  definition: RepairsTableFilterDefinition
  selectedValues: string[]
  onApply: (values: string[]) => void
}) {
  const controlId = useId()
  const [open, setOpen] = useState(false)
  const [search, setSearch] = useState("")
  const [draftValues, setDraftValues] = useState(selectedValues)
  const filteredOptions = useMemo(() => {
    const normalizedSearch = search.trim().toLocaleLowerCase("ru-RU")
    if (!normalizedSearch) return definition.options
    return definition.options.filter((option) =>
      option.label.toLocaleLowerCase("ru-RU").includes(normalizedSearch)
    )
  }, [definition.options, search])

  function toggleValue(value: string) {
    setDraftValues((current) =>
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
        if (nextOpen) {
          setDraftValues(selectedValues)
          setSearch("")
        }
      }}
    >
      <PopoverTrigger asChild>
        <Button
          type="button"
          size="default"
          variant={selectedValues.length > 0 ? "secondary" : "outline"}
          className="w-full justify-start sm:w-auto"
        >
          {definition.label}
          {selectedValues.length > 0 ? (
            <Badge variant="outline">{selectedValues.length}</Badge>
          ) : null}
          <HugeiconsIcon icon={FilterIcon} data-icon="inline-end" />
        </Button>
      </PopoverTrigger>
      <PopoverContent align="start" className="w-72">
        <FieldSet className="gap-3">
          <FieldLegend variant="label">{definition.label}</FieldLegend>
          <Field>
            <FieldLabel htmlFor={`${controlId}-search`} className="sr-only">
              Поиск значений фильтра {definition.label}
            </FieldLabel>
            <Input
              id={`${controlId}-search`}
              value={search}
              autoComplete="off"
              placeholder="Поиск в фильтре..."
              onChange={(event) => setSearch(event.target.value)}
            />
          </Field>
          <div className="flex gap-2">
            <Button
              type="button"
              variant="outline"
              size="sm"
              className="flex-1"
              onClick={() =>
                setDraftValues(definition.options.map((option) => option.value))
              }
            >
              Выбрать все
            </Button>
            <Button
              type="button"
              variant="outline"
              size="sm"
              className="flex-1"
              onClick={() => setDraftValues([])}
            >
              Снять
            </Button>
          </div>
          <FieldGroup className="max-h-56 gap-1 overflow-auto rounded-md border p-1">
            {filteredOptions.length > 0 ? (
              filteredOptions.map((option, index) => {
                const optionId = `${controlId}-option-${index}`
                return (
                  <Field
                    key={option.value}
                    orientation="horizontal"
                    className="rounded px-2 py-1.5 hover:bg-muted"
                  >
                    <Checkbox
                      id={optionId}
                      checked={draftValues.includes(option.value)}
                      onCheckedChange={() => toggleValue(option.value)}
                    />
                    <FieldLabel
                      htmlFor={optionId}
                      className="min-w-0 cursor-pointer font-normal"
                    >
                      <span className="truncate">{option.label}</span>
                    </FieldLabel>
                  </Field>
                )
              })
            ) : (
              <p className="px-2 py-3 text-sm text-muted-foreground">
                Значения не найдены.
              </p>
            )}
          </FieldGroup>
        </FieldSet>
        <div className="flex justify-between gap-2">
          <Button
            type="button"
            variant="ghost"
            size="sm"
            onClick={() => {
              setDraftValues([])
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
              onApply(draftValues)
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

export function RepairsTableFilters({
  definitions,
  filters,
  onChange,
}: {
  definitions: RepairsTableFilterDefinition[]
  filters: RepairsTableFiltersState
  onChange: (filters: RepairsTableFiltersState) => void
}) {
  return (
    <div className="flex flex-col gap-2 rounded-lg border bg-card p-2 sm:flex-row sm:flex-wrap">
      {definitions.map((definition) => (
        <RepairsTableFilterButton
          key={definition.id}
          definition={definition}
          selectedValues={filters[definition.id]}
          onApply={(values) =>
            onChange({ ...filters, [definition.id]: values })
          }
        />
      ))}
    </div>
  )
}
