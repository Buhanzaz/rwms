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
import { cn } from "@/lib/utils"

export type SearchableMultiSelectOption<TValue extends string = string> = {
  value: TValue
  label: string
}

export function SearchableMultiSelectFilter<TValue extends string>({
  label,
  options,
  selected,
  onApply,
  className,
}: {
  label: string
  options: SearchableMultiSelectOption<TValue>[]
  selected: TValue[]
  onApply: (values: TValue[]) => void
  className?: string
}) {
  const controlId = useId()
  const [open, setOpen] = useState(false)
  const [search, setSearch] = useState("")
  const [draft, setDraft] = useState<TValue[]>(selected)
  const filteredOptions = useMemo(() => {
    const normalizedSearch = search.trim().toLocaleLowerCase("ru-RU")

    if (!normalizedSearch) {
      return options
    }

    return options.filter((option) =>
      option.label.toLocaleLowerCase("ru-RU").includes(normalizedSearch)
    )
  }, [options, search])

  function toggle(value: TValue) {
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

        if (nextOpen) {
          setDraft(selected)
          setSearch("")
        }
      }}
    >
      <PopoverTrigger asChild>
        <Button
          type="button"
          size="default"
          variant={selected.length > 0 ? "secondary" : "outline"}
          className={cn("h-9 w-full justify-start sm:w-auto", className)}
          aria-label={
            selected.length > 0
              ? `${label}: выбрано ${selected.length}`
              : undefined
          }
        >
          {label}
          {selected.length > 0 ? (
            <Badge aria-hidden="true" variant="outline">
              {selected.length}
            </Badge>
          ) : null}
          <HugeiconsIcon
            icon={FilterIcon}
            data-icon="inline-end"
            className="ml-auto"
          />
        </Button>
      </PopoverTrigger>

      <PopoverContent align="start" className="w-72">
        <FieldSet className="gap-3">
          <FieldLegend variant="label">{label}</FieldLegend>

          <Field>
            <FieldLabel htmlFor={`${controlId}-search`} className="sr-only">
              Поиск значений фильтра {label}
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
              onClick={() => setDraft(options.map((option) => option.value))}
            >
              Выбрать все
            </Button>
            <Button
              type="button"
              variant="outline"
              size="sm"
              className="flex-1"
              onClick={() => setDraft([])}
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
                      checked={draft.includes(option.value)}
                      onCheckedChange={() => toggle(option.value)}
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
