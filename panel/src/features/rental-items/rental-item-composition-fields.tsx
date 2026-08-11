import { useMemo, useState } from "react"
import { HugeiconsIcon } from "@hugeicons/react"
import { Add01Icon, CheckIcon, UnfoldMoreIcon } from "@hugeicons/core-free-icons"

import { Badge } from "@/components/ui/badge"
import { Button } from "@/components/ui/button"
import { Checkbox } from "@/components/ui/checkbox"
import {
  Dialog,
  DialogContent,
  DialogDescription,
  DialogFooter,
  DialogHeader,
  DialogTitle,
} from "@/components/ui/dialog"
import {
  Field,
  FieldDescription,
  FieldError,
  FieldGroup,
  FieldLabel,
  FieldLegend,
  FieldSet,
} from "@/components/ui/field"
import {
  Popover,
  PopoverContent,
  PopoverTrigger,
} from "@/components/ui/popover"
import { ToggleGroup, ToggleGroupItem } from "@/components/ui/toggle-group"
import type {
  CabinCatalogValue,
  RentalItemCreationOptions,
} from "@/features/rental-items/api/asset-rental-items-api"
import {
  compositionCategoryOptions,
  dimensionsForRentalType,
  type RentalItemCompositionCategoryMode,
  type RentalItemCompositionFormValue,
} from "@/features/rental-items/rental-item-composition"
import { cn } from "@/lib/utils"

export type {
  RentalItemCompositionCategoryMode,
  RentalItemCompositionFormValue,
} from "@/features/rental-items/rental-item-composition"

function CatalogDropdown({
  value,
  placeholder,
  options,
  disabled = false,
  invalid = false,
  onValueChange,
}: {
  value: string
  placeholder: string
  options: readonly CabinCatalogValue[]
  disabled?: boolean
  invalid?: boolean
  onValueChange: (value: string) => void
}) {
  const [open, setOpen] = useState(false)
  const selectedOption = options.find((option) => option.id === value)

  return (
    <Popover
      open={disabled ? false : open}
      onOpenChange={(nextOpen) => {
        if (!disabled) setOpen(nextOpen)
      }}
    >
      <PopoverTrigger asChild>
        <Button
          type="button"
          variant="outline"
          disabled={disabled}
          aria-invalid={invalid || undefined}
          className={cn(
            "h-10 w-full justify-between rounded-md border-input bg-input/20 px-3 text-sm font-normal md:h-7 md:px-2 md:text-xs/relaxed",
            !selectedOption && "text-muted-foreground"
          )}
        >
          <span className="truncate">
            {selectedOption?.name ?? placeholder}
          </span>
          <HugeiconsIcon icon={UnfoldMoreIcon} data-icon="inline-end" />
        </Button>
      </PopoverTrigger>

      <PopoverContent
        align="start"
        className="max-h-[min(18rem,var(--radix-popover-content-available-height))] w-[var(--radix-popover-trigger-width)] touch-pan-y gap-1 overflow-y-auto overscroll-contain rounded-lg p-1"
        onPointerDown={(event) => event.stopPropagation()}
        onWheel={(event) => event.stopPropagation()}
        onTouchMove={(event) => event.stopPropagation()}
      >
        {options.length > 0 ? (
          options.map((option) => (
            <Button
              key={option.id}
              type="button"
              variant={option.id === value ? "secondary" : "ghost"}
              className="h-10 w-full justify-start rounded-md px-3 text-sm font-normal md:h-7 md:px-2 md:text-xs/relaxed"
              onClick={() => {
                onValueChange(option.id)
                setOpen(false)
              }}
            >
              <span className="truncate">{option.name}</span>
            </Button>
          ))
        ) : (
          <div className="px-2 py-1 text-xs text-muted-foreground">
            Нет вариантов
          </div>
        )}
      </PopoverContent>
    </Popover>
  )
}

function CategoryDropdown({
  value,
  options,
  disabled,
  invalid,
  onValueChange,
}: {
  value: string
  options: readonly string[]
  disabled: boolean
  invalid: boolean
  onValueChange: (value: string) => void
}) {
  const [open, setOpen] = useState(false)

  return (
    <Popover
      open={disabled ? false : open}
      onOpenChange={(nextOpen) => {
        if (!disabled) setOpen(nextOpen)
      }}
    >
      <PopoverTrigger asChild>
        <Button
          type="button"
          variant="outline"
          disabled={disabled}
          aria-invalid={invalid || undefined}
          className={cn(
            "h-10 w-full justify-between rounded-md border-input bg-input/20 px-3 text-sm font-normal md:h-7 md:px-2 md:text-xs/relaxed",
            !value && "text-muted-foreground"
          )}
        >
          <span className="truncate">{value || "Выберите категорию"}</span>
          <HugeiconsIcon icon={UnfoldMoreIcon} data-icon="inline-end" />
        </Button>
      </PopoverTrigger>
      <PopoverContent
        align="start"
        className="max-h-[min(18rem,var(--radix-popover-content-available-height))] w-[var(--radix-popover-trigger-width)] touch-pan-y overflow-y-auto overscroll-contain rounded-lg p-1"
      >
        {options.length > 0 ? (
          options.map((option) => (
            <Button
              key={option}
              type="button"
              variant={option === value ? "secondary" : "ghost"}
              className="h-10 w-full justify-start rounded-md px-3 text-sm font-normal md:h-7 md:px-2 md:text-xs/relaxed"
              onClick={() => {
                onValueChange(option)
                setOpen(false)
              }}
            >
              <span className="truncate">{option}</span>
            </Button>
          ))
        ) : (
          <div className="px-2 py-1 text-xs text-muted-foreground">
            Нет вариантов
          </div>
        )}
      </PopoverContent>
    </Popover>
  )
}

function CharacteristicsDialog({
  open,
  items,
  selectedIds,
  onOpenChange,
  onConfirm,
}: {
  open: boolean
  items: readonly CabinCatalogValue[]
  selectedIds: readonly string[]
  onOpenChange: (open: boolean) => void
  onConfirm: (ids: string[]) => void
}) {
  const [draftIds, setDraftIds] = useState<string[]>(() => [...selectedIds])

  function toggleCharacteristic(id: string, checked: boolean) {
    setDraftIds((current) => {
      if (checked) {
        return current.includes(id) ? current : [...current, id]
      }

      return current.filter((value) => value !== id)
    })
  }

  return (
    <Dialog open={open} onOpenChange={onOpenChange}>
      <DialogContent className="sm:max-w-lg">
        <DialogHeader>
          <DialogTitle>Характеристики бытовки</DialogTitle>
          <DialogDescription>
            Выберите характеристики, которые относятся к этой бытовке.
          </DialogDescription>
        </DialogHeader>

        <FieldSet>
          <FieldLegend variant="label">Доступные характеристики</FieldLegend>
          <FieldGroup className="gap-3">
            {items.length > 0 ? (
              items.map((item) => {
                const id = `rental-item-characteristic-${item.id}`
                return (
                  <Field key={item.id} orientation="horizontal">
                    <Checkbox
                      id={id}
                      checked={draftIds.includes(item.id)}
                      onCheckedChange={(value) =>
                        toggleCharacteristic(item.id, value === true)
                      }
                    />
                    <FieldLabel htmlFor={id} className="font-normal">
                      {item.name}
                    </FieldLabel>
                  </Field>
                )
              })
            ) : (
              <FieldDescription>
                Активных характеристик пока нет.
              </FieldDescription>
            )}
          </FieldGroup>
        </FieldSet>

        <DialogFooter>
          <Button
            type="button"
            variant="outline"
            onClick={() => onOpenChange(false)}
          >
            Отмена
          </Button>
          <Button
            type="button"
            onClick={() => {
              onConfirm(draftIds)
              onOpenChange(false)
            }}
          >
            <HugeiconsIcon icon={CheckIcon} data-icon="inline-start" />
            Применить
          </Button>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  )
}

export function RentalItemCompositionFields({
  options,
  value,
  categoryMode,
  submitted = false,
  disabled = false,
  onChange,
}: {
  options: RentalItemCreationOptions
  value: RentalItemCompositionFormValue
  categoryMode: RentalItemCompositionCategoryMode
  submitted?: boolean
  disabled?: boolean
  onChange: (value: RentalItemCompositionFormValue) => void
}) {
  const [characteristicsOpen, setCharacteristicsOpen] = useState(false)
  const dimensions = useMemo(
    () => dimensionsForRentalType(options, value.rentalTypeId),
    [options, value.rentalTypeId]
  )
  const categories = useMemo(
    () => compositionCategoryOptions(options, categoryMode, value.category),
    [categoryMode, options, value.category]
  )
  const selectedCharacteristics = useMemo(() => {
    const selectedIds = new Set(value.characteristicIds)
    return options.characteristics.filter((item) => selectedIds.has(item.id))
  }, [options.characteristics, value.characteristicIds])

  function changeType(rentalTypeId: string) {
    const nextDimensions = dimensionsForRentalType(options, rentalTypeId)
    const dimensionStillAllowed = nextDimensions.some(
      (dimension) => dimension.id === value.dimensionId
    )
    onChange({
      ...value,
      rentalTypeId,
      dimensionId: dimensionStillAllowed ? value.dimensionId : "",
    })
  }

  const categoryLocked = categoryMode === "NEW"

  return (
    <>
      <FieldGroup data-rental-item-section="passport">
        <Field data-invalid={submitted && value.category.trim() === ""}>
          <FieldLabel>Категория</FieldLabel>
          <CategoryDropdown
            value={value.category}
            options={categories}
            disabled={disabled || categoryLocked}
            invalid={submitted && value.category.trim() === ""}
            onValueChange={(category) => onChange({ ...value, category })}
          />
          {submitted && value.category.trim() === "" ? (
            <FieldError>Выберите категорию.</FieldError>
          ) : null}
        </Field>

        <div className="grid gap-4 md:grid-cols-2">
          <Field data-invalid={submitted && value.rentalTypeId === ""}>
            <FieldLabel>Тип бытовки</FieldLabel>
            <CatalogDropdown
              value={value.rentalTypeId}
              placeholder="Выберите тип"
              options={options.rentalTypes}
              disabled={disabled}
              invalid={submitted && value.rentalTypeId === ""}
              onValueChange={changeType}
            />
            {submitted && value.rentalTypeId === "" ? (
              <FieldError>Выберите тип бытовки.</FieldError>
            ) : null}
          </Field>

          <Field data-invalid={submitted && value.dimensionId === ""}>
            <FieldLabel>Габариты</FieldLabel>
            <CatalogDropdown
              value={value.dimensionId}
              disabled={disabled || value.rentalTypeId === ""}
              placeholder={
                value.rentalTypeId
                  ? "Выберите габариты"
                  : "Сначала выберите тип"
              }
              options={dimensions}
              invalid={submitted && value.dimensionId === ""}
              onValueChange={(dimensionId) =>
                onChange({ ...value, dimensionId })
              }
            />
            {value.rentalTypeId && dimensions.length === 0 ? (
              <FieldDescription>
                Для выбранного типа не настроены габариты.
              </FieldDescription>
            ) : null}
            {submitted && value.dimensionId === "" ? (
              <FieldError>Выберите габариты.</FieldError>
            ) : null}
          </Field>
        </div>

        <div className="grid gap-4 md:grid-cols-2">
          <Field data-invalid={submitted && value.finishingId === ""}>
            <FieldLabel>Отделка</FieldLabel>
            <CatalogDropdown
              value={value.finishingId}
              placeholder="Выберите отделку"
              options={options.finishings}
              disabled={disabled}
              invalid={submitted && value.finishingId === ""}
              onValueChange={(finishingId) =>
                onChange({ ...value, finishingId })
              }
            />
            {submitted && value.finishingId === "" ? (
              <FieldError>Выберите отделку.</FieldError>
            ) : null}
          </Field>

          <Field data-invalid={submitted && value.linoleum === ""}>
            <FieldLabel>Линолеум</FieldLabel>
            <ToggleGroup
              type="single"
              value={value.linoleum}
              disabled={disabled}
              variant="outline"
              spacing={2}
              className="w-full"
              aria-label="Линолеум"
              aria-invalid={submitted && value.linoleum === "" ? true : undefined}
              onValueChange={(linoleum) => {
                if (linoleum === "yes" || linoleum === "no") {
                  onChange({ ...value, linoleum })
                }
              }}
            >
              <ToggleGroupItem value="yes" className="flex-1">
                Есть
              </ToggleGroupItem>
              <ToggleGroupItem value="no" className="flex-1">
                Нет
              </ToggleGroupItem>
            </ToggleGroup>
            {submitted && value.linoleum === "" ? (
              <FieldError>Выберите, есть ли линолеум.</FieldError>
            ) : null}
          </Field>
        </div>
      </FieldGroup>

      <FieldSet>
        <div className="flex flex-wrap items-center justify-between gap-3">
          <FieldLegend>Характеристики</FieldLegend>
          <Button
            type="button"
            variant="outline"
            className="w-full justify-center md:w-[16.25rem]"
            disabled={disabled}
            onClick={() => setCharacteristicsOpen(true)}
          >
            <HugeiconsIcon icon={Add01Icon} data-icon="inline-start" />
            Выбрать характеристики
          </Button>
        </div>

        {selectedCharacteristics.length > 0 ? (
          <div className="flex flex-wrap gap-2">
            {selectedCharacteristics.map((characteristic) => (
              <Badge key={characteristic.id} variant="secondary">
                {characteristic.name}
              </Badge>
            ))}
          </div>
        ) : (
          <FieldDescription>Характеристики не выбраны.</FieldDescription>
        )}
      </FieldSet>

      {characteristicsOpen ? (
        <CharacteristicsDialog
          open={characteristicsOpen}
          items={options.characteristics}
          selectedIds={value.characteristicIds}
          onOpenChange={setCharacteristicsOpen}
          onConfirm={(characteristicIds) =>
            onChange({ ...value, characteristicIds })
          }
        />
      ) : null}
    </>
  )
}
