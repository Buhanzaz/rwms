import { useId, useMemo, useState, type FormEvent } from "react"
import { useMutation, useQueryClient } from "@tanstack/react-query"
import { HugeiconsIcon } from "@hugeicons/react"
import {
  Add01Icon,
  CheckIcon,
  MinusSignIcon,
  PlusSignIcon,
  UnfoldMoreIcon,
} from "@hugeicons/core-free-icons"

import {
  AssetRentalItemConflictError,
  createAssetRentalItem,
  createIdempotencyKey,
} from "@/features/rental-items/api/asset-rental-items-api"
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
import { Input } from "@/components/ui/input"
import { useAuth } from "@/features/auth/use-auth"
import { hasWarehouseAccess } from "@/features/auth/warehouse-access"
import { cn } from "@/lib/utils"
import {
  Popover,
  PopoverContent,
  PopoverTrigger,
} from "@/components/ui/popover"
import {
  buildRentalItemCharacteristics,
  DEFAULT_RENTAL_ITEM_CHARACTERISTICS,
  getDefaultRentalItemCharacteristics,
  getDefaultRentalItemDimensions,
  getDefaultRentalItemFinishing,
  getRentalItemDimensionsForType,
  isSanblockRentalItemType,
  NEW_RENTAL_ITEM_CATEGORY,
  RENTAL_ITEM_CHARACTERISTIC_OPTIONS,
  RENTAL_ITEM_FINISHING_OPTIONS,
  RENTAL_ITEM_TYPE_OPTIONS,
  type RentalItemCharacteristic,
  type RentalItemCreationType,
  type RentalItemFinishing,
  type SanblockSettings,
} from "@/features/rental-items/model/rental-item-create"

type RentalItemCreateDialogProps = {
  open: boolean
  warehouseId: string
  onOpenChange: (open: boolean) => void
}

type LinoleumValue = "no" | "yes"

type DropdownOption<T extends string> = {
  value: T
  label: string
}

type RentalItemCreateFormState = {
  number: string
  type: RentalItemCreationType | ""
  dimensions: string
  finishing: RentalItemFinishing | ""
  selectedCharacteristics: RentalItemCharacteristic[]
  sanblockSettings: SanblockSettings
  linoleum: LinoleumValue
}

const DEFAULT_SANBLOCK_SETTINGS: SanblockSettings = {
  toilets: 0,
  sinks: 0,
  showers: 0,
}

const LINOLEUM_OPTIONS: DropdownOption<LinoleumValue>[] = [
  {
    value: "no",
    label: "Нет",
  },
  {
    value: "yes",
    label: "Есть",
  },
]

const formSectionActionButtonClassName = "w-full justify-center md:w-[16.25rem]"
const formFooterClassName = "w-full md:w-[16.25rem] md:self-end"

function createEmptyForm(): RentalItemCreateFormState {
  return {
    number: "",
    type: "",
    dimensions: "",
    finishing: "",
    selectedCharacteristics: getDefaultRentalItemCharacteristics([]),
    sanblockSettings: DEFAULT_SANBLOCK_SETTINGS,
    linoleum: "no",
  }
}

function hasRequiredFormFields(form: RentalItemCreateFormState) {
  return (
    form.number.trim().length > 0 &&
    form.type !== "" &&
    form.dimensions !== "" &&
    form.finishing !== ""
  )
}

function toDropdownOptions<T extends string>(
  values: readonly T[]
): DropdownOption<T>[] {
  return values.map((value) => ({
    value,
    label: value,
  }))
}

function FormDropdown<T extends string>({
  value,
  placeholder,
  options,
  disabled = false,
  invalid = false,
  onValueChange,
}: {
  value: T | ""
  placeholder: string
  options: DropdownOption<T>[]
  disabled?: boolean
  invalid?: boolean
  onValueChange: (value: T) => void
}) {
  const [open, setOpen] = useState(false)
  const selectedOption = options.find((option) => option.value === value)

  return (
    <Popover
      open={disabled ? false : open}
      onOpenChange={(nextOpen) => {
        if (!disabled) {
          setOpen(nextOpen)
        }
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
            {selectedOption?.label ?? placeholder}
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
              key={option.value}
              type="button"
              variant={option.value === value ? "secondary" : "ghost"}
              className="h-10 w-full justify-start rounded-md px-3 text-sm font-normal md:h-7 md:px-2 md:text-xs/relaxed"
              onClick={() => {
                onValueChange(option.value)
                setOpen(false)
              }}
            >
              <span className="truncate">{option.label}</span>
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

function CounterControl({
  label,
  value,
  onChange,
}: {
  label: string
  value: number
  onChange: (value: number) => void
}) {
  return (
    <Field
      orientation="horizontal"
      className="justify-between rounded-md border p-2"
    >
      <FieldLabel>{label}</FieldLabel>

      <div className="flex items-center gap-2">
        <Button
          type="button"
          size="icon-sm"
          variant="outline"
          disabled={value <= 0}
          onClick={() => onChange(Math.max(0, value - 1))}
        >
          <HugeiconsIcon icon={MinusSignIcon} data-icon="inline-start" />
        </Button>

        <span className="min-w-8 text-center text-sm font-medium">{value}</span>

        <Button
          type="button"
          size="icon-sm"
          variant="outline"
          onClick={() => onChange(value + 1)}
        >
          <HugeiconsIcon icon={PlusSignIcon} data-icon="inline-start" />
        </Button>
      </div>
    </Field>
  )
}

function RentalItemCharacteristicsDialog({
  open,
  defaultCharacteristics,
  selectedCharacteristics,
  onOpenChange,
  onConfirm,
}: {
  open: boolean
  defaultCharacteristics: RentalItemCharacteristic[]
  selectedCharacteristics: RentalItemCharacteristic[]
  onOpenChange: (open: boolean) => void
  onConfirm: (characteristics: RentalItemCharacteristic[]) => void
}) {
  const [draftCharacteristics, setDraftCharacteristics] = useState<
    RentalItemCharacteristic[]
  >(selectedCharacteristics)

  function toggleCharacteristic(
    characteristic: RentalItemCharacteristic,
    checked: boolean
  ) {
    setDraftCharacteristics((current) => {
      if (checked) {
        return current.includes(characteristic)
          ? current
          : [...current, characteristic]
      }

      return current.filter((value) => value !== characteristic)
    })
  }

  return (
    <Dialog open={open} onOpenChange={onOpenChange}>
      <DialogContent className="sm:max-w-lg">
        <DialogHeader>
          <DialogTitle>Добавить характеристики</DialogTitle>
          <DialogDescription>
            Выберите характеристики, которые нужно добавить к бытовке.
          </DialogDescription>
        </DialogHeader>

        <FieldSet>
          <FieldLegend variant="label">Доступные характеристики</FieldLegend>
          <FieldGroup className="gap-3">
            {RENTAL_ITEM_CHARACTERISTIC_OPTIONS.map((characteristic, index) => {
              const isDefault = defaultCharacteristics.includes(characteristic)
              const checked =
                isDefault || draftCharacteristics.includes(characteristic)
              const id = `rental-item-characteristic-${index}`

              return (
                <Field
                  key={characteristic}
                  data-disabled={isDefault || undefined}
                  orientation="horizontal"
                >
                  <Checkbox
                    id={id}
                    checked={checked}
                    disabled={isDefault}
                    onCheckedChange={(value) =>
                      toggleCharacteristic(characteristic, value === true)
                    }
                  />
                  <FieldLabel htmlFor={id} className="font-normal">
                    {characteristic}
                  </FieldLabel>
                </Field>
              )
            })}
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
              onConfirm(
                getDefaultRentalItemCharacteristics(draftCharacteristics)
              )
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

export function RentalItemCreateDialog({
  open,
  warehouseId,
  onOpenChange,
}: RentalItemCreateDialogProps) {
  const queryClient = useQueryClient()
  const { accessToken, currentUser } = useAuth()
  const canEditRentalItems = hasWarehouseAccess(
    currentUser,
    warehouseId,
    "EDIT"
  )
  const numberInputId = useId()
  const [form, setForm] = useState<RentalItemCreateFormState>(() =>
    createEmptyForm()
  )
  const [submitted, setSubmitted] = useState(false)
  const [characteristicsOpen, setCharacteristicsOpen] = useState(false)

  const dimensionsOptions = useMemo(() => {
    return getRentalItemDimensionsForType(form.type)
  }, [form.type])

  const selectedCharacteristicOptions = useMemo(() => {
    return getDefaultRentalItemCharacteristics(form.selectedCharacteristics)
  }, [form.selectedCharacteristics])

  const selectedCharacteristics = useMemo(() => {
    if (!form.type) {
      return selectedCharacteristicOptions
    }

    return buildRentalItemCharacteristics({
      selectedCharacteristics: selectedCharacteristicOptions,
      type: form.type,
      sanblockSettings: form.sanblockSettings,
    })
  }, [form.sanblockSettings, form.type, selectedCharacteristicOptions])

  const createMutation = useMutation({
    mutationFn: (input: {
      idempotencyKey: string
      number: string
      rentalType: string
      dimensions: string
      finishing: string
      category: string
      characteristics: string
      linoleum: boolean
    }) =>
      createAssetRentalItem({
        accessToken,
        idempotencyKey: input.idempotencyKey,
        input: {
          warehouseId,
          number: input.number,
          rentalType: input.rentalType,
          dimensions: input.dimensions,
          finishing: input.finishing,
          category: input.category,
          characteristics: input.characteristics,
          linoleum: input.linoleum,
        },
      }),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ["rental-items"] })
      queryClient.invalidateQueries({
        queryKey: ["rental-items-table-schema", warehouseId],
      })
      queryClient.invalidateQueries({
        queryKey: ["rental-item-filter-options", warehouseId],
      })
      setForm(createEmptyForm())
      setSubmitted(false)
      onOpenChange(false)
    },
  })

  function updateSanblockSettings(key: keyof SanblockSettings, value: number) {
    setForm((current) => ({
      ...current,
      sanblockSettings: {
        ...current.sanblockSettings,
        [key]: Math.max(0, value),
      },
    }))
  }

  function handleTypeChange(value: string) {
    const type = value as RentalItemCreationType
    const dimensions = getDefaultRentalItemDimensions(type)

    setForm((current) => ({
      ...current,
      type,
      dimensions,
      finishing: getDefaultRentalItemFinishing(type, current.finishing),
      selectedCharacteristics: getDefaultRentalItemCharacteristics(
        current.selectedCharacteristics
      ),
      linoleum: isSanblockRentalItemType(type) ? "yes" : current.linoleum,
    }))
  }

  function handleDialogOpenChange(nextOpen: boolean) {
    if (nextOpen && !canEditRentalItems) {
      return
    }

    if (!nextOpen && !createMutation.isPending) {
      setForm(createEmptyForm())
      setSubmitted(false)
      setCharacteristicsOpen(false)
    }

    onOpenChange(nextOpen)
  }

  function submitForm(event: FormEvent<HTMLFormElement>) {
    event.preventDefault()
    setSubmitted(true)

    const type = form.type
    const finishing = form.finishing

    if (
      !hasRequiredFormFields(form) ||
      !type ||
      !finishing ||
      !canEditRentalItems ||
      createMutation.isPending
    ) {
      return
    }

    createMutation.mutate({
      idempotencyKey: createIdempotencyKey(),
      number: form.number.trim(),
      rentalType: type,
      dimensions: form.dimensions,
      finishing,
      category: NEW_RENTAL_ITEM_CATEGORY,
      characteristics: selectedCharacteristics.join(", "),
      linoleum: form.linoleum === "yes",
    })
  }

  return (
    <>
      <Dialog
        open={open && canEditRentalItems}
        onOpenChange={handleDialogOpenChange}
      >
        <DialogContent
          className="max-h-[calc(100svh-2rem)] overflow-y-auto sm:max-w-3xl"
          onEscapeKeyDown={(event) => {
            if (characteristicsOpen) {
              event.preventDefault()
            }
          }}
          onInteractOutside={(event) => {
            if (characteristicsOpen) {
              event.preventDefault()
            }
          }}
        >
          <DialogHeader>
            <DialogTitle>Создание новой бытовки</DialogTitle>
          </DialogHeader>

          <form className="flex flex-col gap-5" onSubmit={submitForm}>
            <FieldGroup>
              <Field
                data-invalid={submitted && form.number.trim().length === 0}
              >
                <FieldLabel htmlFor={numberInputId}>Номер бытовки</FieldLabel>
                <Input
                  id={numberInputId}
                  value={form.number}
                  maxLength={128}
                  aria-invalid={submitted && form.number.trim().length === 0}
                  onChange={(event) =>
                    setForm((current) => ({
                      ...current,
                      number: event.target.value,
                    }))
                  }
                  placeholder="Например, БЫТ-121"
                />
                {submitted && form.number.trim().length === 0 ? (
                  <FieldError>Укажите номер бытовки.</FieldError>
                ) : null}
              </Field>

              <div className="grid gap-4 md:grid-cols-2">
                <Field data-invalid={submitted && form.type === ""}>
                  <FieldLabel>Тип бытовки</FieldLabel>
                  <FormDropdown
                    value={form.type}
                    placeholder="Выберите тип"
                    options={toDropdownOptions(RENTAL_ITEM_TYPE_OPTIONS)}
                    invalid={submitted && form.type === ""}
                    onValueChange={handleTypeChange}
                  />
                  {submitted && form.type === "" ? (
                    <FieldError>Выберите тип бытовки.</FieldError>
                  ) : null}
                </Field>

                <Field data-invalid={submitted && form.dimensions === ""}>
                  <FieldLabel>Габариты</FieldLabel>
                  <FormDropdown
                    value={form.dimensions}
                    disabled={!form.type || dimensionsOptions.length <= 1}
                    placeholder={
                      form.type ? "Выберите габариты" : "Сначала выберите тип"
                    }
                    options={toDropdownOptions(dimensionsOptions)}
                    invalid={submitted && form.dimensions === ""}
                    onValueChange={(value) =>
                      setForm((current) => ({
                        ...current,
                        dimensions: value,
                      }))
                    }
                  />
                  {submitted && form.dimensions === "" ? (
                    <FieldError>Выберите габариты.</FieldError>
                  ) : null}
                </Field>
              </div>

              <div className="grid gap-4 md:grid-cols-2">
                <Field data-invalid={submitted && form.finishing === ""}>
                  <FieldLabel>Отделка</FieldLabel>
                  <FormDropdown
                    value={form.finishing}
                    disabled={isSanblockRentalItemType(form.type)}
                    placeholder="Выберите отделку"
                    options={toDropdownOptions(RENTAL_ITEM_FINISHING_OPTIONS)}
                    invalid={submitted && form.finishing === ""}
                    onValueChange={(value) =>
                      setForm((current) => ({
                        ...current,
                        finishing: value,
                      }))
                    }
                  />
                  {isSanblockRentalItemType(form.type) ? (
                    <FieldDescription>
                      Для БК-Санблок отделка автоматически ПВХ.
                    </FieldDescription>
                  ) : null}
                  {submitted && form.finishing === "" ? (
                    <FieldError>Выберите отделку.</FieldError>
                  ) : null}
                </Field>

                <Field>
                  <FieldLabel>Линолеум</FieldLabel>
                  <FormDropdown
                    value={form.linoleum}
                    disabled={isSanblockRentalItemType(form.type)}
                    placeholder="Нет"
                    options={LINOLEUM_OPTIONS}
                    onValueChange={(value) =>
                      setForm((current) => ({
                        ...current,
                        linoleum: value,
                      }))
                    }
                  />
                  {isSanblockRentalItemType(form.type) ? (
                    <FieldDescription>
                      Для БК-Санблок линолеум автоматически Есть.
                    </FieldDescription>
                  ) : null}
                </Field>
              </div>
            </FieldGroup>

            {isSanblockRentalItemType(form.type) ? (
              <FieldSet>
                <FieldLegend>Настройки санблока</FieldLegend>
                <FieldGroup className="grid gap-3 md:grid-cols-3">
                  <CounterControl
                    label="Туалеты"
                    value={form.sanblockSettings.toilets}
                    onChange={(value) =>
                      updateSanblockSettings("toilets", value)
                    }
                  />
                  <CounterControl
                    label="Раковины"
                    value={form.sanblockSettings.sinks}
                    onChange={(value) => updateSanblockSettings("sinks", value)}
                  />
                  <CounterControl
                    label="Душевые"
                    value={form.sanblockSettings.showers}
                    onChange={(value) =>
                      updateSanblockSettings("showers", value)
                    }
                  />
                </FieldGroup>
              </FieldSet>
            ) : null}

            <FieldSet>
              <div className="flex flex-wrap items-center justify-between gap-3">
                <FieldLegend>Характеристики</FieldLegend>
                <Button
                  type="button"
                  variant="outline"
                  className={formSectionActionButtonClassName}
                  onClick={() => setCharacteristicsOpen(true)}
                >
                  <HugeiconsIcon icon={Add01Icon} data-icon="inline-start" />
                  Добавить характеристики
                </Button>
              </div>

              {selectedCharacteristics.length > 0 ? (
                <div className="flex flex-wrap gap-2">
                  {selectedCharacteristics.map((characteristic) => (
                    <Badge key={characteristic} variant="secondary">
                      {characteristic}
                    </Badge>
                  ))}
                </div>
              ) : (
                <FieldDescription>Характеристики не выбраны.</FieldDescription>
              )}
            </FieldSet>

            <FieldSet>
              <FieldLegend>Фото</FieldLegend>
              <FieldDescription>
                Фотографии пока недоступны: asset-service не предоставляет
                публичный media API для бытовок.
              </FieldDescription>
            </FieldSet>

            {createMutation.isError ? (
              <FieldError>
                {createMutation.error instanceof AssetRentalItemConflictError
                  ? "Бытовка была изменена другим пользователем. Обновите реестр и повторите действие."
                  : createMutation.error instanceof Error
                    ? createMutation.error.message
                    : "Не удалось создать бытовку. Проверьте данные и повторите."}
              </FieldError>
            ) : null}

            <DialogFooter className={formFooterClassName}>
              <Button
                type="button"
                variant="outline"
                className="flex-1"
                disabled={createMutation.isPending}
                onClick={() => handleDialogOpenChange(false)}
              >
                Отмена
              </Button>
              <Button
                type="submit"
                className="flex-[1.65]"
                disabled={!canEditRentalItems || createMutation.isPending}
              >
                <HugeiconsIcon icon={CheckIcon} data-icon="inline-start" />
                {createMutation.isPending ? "Создание..." : "Создать бытовку"}
              </Button>
            </DialogFooter>
          </form>

          {characteristicsOpen ? (
            <RentalItemCharacteristicsDialog
              open={characteristicsOpen}
              defaultCharacteristics={DEFAULT_RENTAL_ITEM_CHARACTERISTICS}
              selectedCharacteristics={selectedCharacteristicOptions}
              onOpenChange={setCharacteristicsOpen}
              onConfirm={(characteristics) =>
                setForm((current) => ({
                  ...current,
                  selectedCharacteristics: characteristics,
                }))
              }
            />
          ) : null}
        </DialogContent>
      </Dialog>
    </>
  )
}
