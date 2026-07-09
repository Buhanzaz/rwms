import {
  useId,
  useMemo,
  useRef,
  useState,
  type DragEvent,
  type FormEvent,
} from "react"
import { useMutation, useQueryClient } from "@tanstack/react-query"
import { HugeiconsIcon } from "@hugeicons/react"
import {
  Add01Icon,
  CheckIcon,
  Delete02Icon,
  EyeIcon,
  ImageUploadIcon,
  MinusSignIcon,
  PlusSignIcon,
  RotateClockwiseIcon,
  UnfoldMoreIcon,
} from "@hugeicons/core-free-icons"

import {
  createRentalItem,
  prepareRentalItemPhotoUpload,
  rotateRentalItemCreationPhoto,
} from "@/features/rental-items/api/rental-items-api"
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
  NEW_RENTAL_ITEM_STATUS,
  RENTAL_ITEM_CHARACTERISTIC_OPTIONS,
  RENTAL_ITEM_FINISHING_OPTIONS,
  RENTAL_ITEM_TYPE_OPTIONS,
  type RentalItemCharacteristic,
  type RentalItemCreationPhoto,
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
  photos: RentalItemCreationPhoto[]
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

function createEmptyForm(): RentalItemCreateFormState {
  return {
    number: "",
    type: "",
    dimensions: "",
    finishing: "",
    selectedCharacteristics: getDefaultRentalItemCharacteristics([]),
    sanblockSettings: DEFAULT_SANBLOCK_SETTINGS,
    photos: [],
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

function RentalItemPhotoPreviewDialog({
  photo,
  onOpenChange,
}: {
  photo: RentalItemCreationPhoto | null
  onOpenChange: (open: boolean) => void
}) {
  return (
    <Dialog open={photo !== null} onOpenChange={onOpenChange}>
      <DialogContent className="sm:max-w-3xl">
        <DialogHeader>
          <DialogTitle>{photo?.name ?? "Фото бытовки"}</DialogTitle>
          <DialogDescription>
            Предпросмотр загруженного изображения.
          </DialogDescription>
        </DialogHeader>

        {photo ? (
          <div className="flex max-h-[70svh] items-center justify-center overflow-hidden rounded-lg border bg-muted">
            <img
              src={photo.variants.largeWebp.url}
              alt={photo.name}
              className="max-h-[70svh] max-w-full object-contain"
            />
          </div>
        ) : null}
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
  const numberInputId = useId()
  const fileInputRef = useRef<HTMLInputElement | null>(null)
  const [form, setForm] = useState<RentalItemCreateFormState>(() =>
    createEmptyForm()
  )
  const [submitted, setSubmitted] = useState(false)
  const [characteristicsOpen, setCharacteristicsOpen] = useState(false)
  const [photoPreviewId, setPhotoPreviewId] = useState<string | null>(null)
  const [processingPhotos, setProcessingPhotos] = useState(false)
  const [rotatingPhotoId, setRotatingPhotoId] = useState<string | null>(null)
  const [photoDropActive, setPhotoDropActive] = useState(false)

  const selectedPhoto = useMemo(() => {
    return form.photos.find((photo) => photo.id === photoPreviewId) ?? null
  }, [form.photos, photoPreviewId])

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
    mutationFn: createRentalItem,
    onSuccess: (createdItem) => {
      queryClient.setQueryData(["rental-item", createdItem.id], createdItem)
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

  async function handlePhotoFiles(files: FileList | File[] | null) {
    const imageFiles = Array.from(files ?? []).filter((file) =>
      file.type.startsWith("image/")
    )

    if (imageFiles.length === 0) {
      return
    }

    setProcessingPhotos(true)

    try {
      const nextPhotos = await Promise.all(
        imageFiles.map((file) => prepareRentalItemPhotoUpload(file))
      )

      setForm((current) => ({
        ...current,
        photos: [...current.photos, ...nextPhotos],
      }))
    } finally {
      setProcessingPhotos(false)

      if (fileInputRef.current) {
        fileInputRef.current.value = ""
      }
    }
  }

  function handlePhotoDrop(event: DragEvent<HTMLButtonElement>) {
    event.preventDefault()
    setPhotoDropActive(false)

    if (processingPhotos) {
      return
    }

    void handlePhotoFiles(event.dataTransfer.files)
  }

  async function rotatePhoto(photo: RentalItemCreationPhoto) {
    setRotatingPhotoId(photo.id)

    try {
      const rotatedPhoto = await rotateRentalItemCreationPhoto(photo)

      setForm((current) => ({
        ...current,
        photos: current.photos.map((currentPhoto) =>
          currentPhoto.id === rotatedPhoto.id ? rotatedPhoto : currentPhoto
        ),
      }))
    } finally {
      setRotatingPhotoId(null)
    }
  }

  function removePhoto(photoId: string) {
    setForm((current) => ({
      ...current,
      photos: current.photos.filter((photo) => photo.id !== photoId),
    }))

    if (photoPreviewId === photoId) {
      setPhotoPreviewId(null)
    }
  }

  function handleDialogOpenChange(nextOpen: boolean) {
    if (!nextOpen && !createMutation.isPending) {
      setForm(createEmptyForm())
      setSubmitted(false)
      setPhotoPreviewId(null)
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
      createMutation.isPending
    ) {
      return
    }

    createMutation.mutate({
      warehouseId,
      number: form.number.trim(),
      type,
      dimensions: form.dimensions,
      finishing,
      category: NEW_RENTAL_ITEM_CATEGORY,
      characteristics: selectedCharacteristics,
      photos: form.photos,
      linoleum: form.linoleum === "yes",
      status: NEW_RENTAL_ITEM_STATUS,
    })
  }

  return (
    <>
      <Dialog open={open} onOpenChange={handleDialogOpenChange}>
        <DialogContent className="max-h-[calc(100svh-2rem)] overflow-y-auto sm:max-w-3xl">
          <DialogHeader>
            <DialogTitle>Добавить новую бытовку</DialogTitle>
            <DialogDescription>
              Создание новой бытовки без legacy-категорий и ID справочников.
            </DialogDescription>
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
                  <FieldDescription>
                    Доступные варианты зависят от выбранного типа.
                  </FieldDescription>
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
              <div className="flex flex-wrap items-center justify-between gap-3">
                <FieldLegend>Фото бытовки</FieldLegend>
                <Button
                  type="button"
                  variant="outline"
                  disabled={processingPhotos}
                  onClick={() => fileInputRef.current?.click()}
                >
                  <HugeiconsIcon
                    icon={ImageUploadIcon}
                    data-icon="inline-start"
                  />
                  {processingPhotos ? "Обработка..." : "Загрузить фото"}
                </Button>
              </div>

              <input
                ref={fileInputRef}
                type="file"
                accept="image/*"
                multiple
                className="hidden"
                onChange={(event) => handlePhotoFiles(event.target.files)}
              />

              <button
                type="button"
                disabled={processingPhotos}
                className={cn(
                  "hidden aspect-[4/3] min-h-28 w-full items-center justify-center rounded-lg border border-dashed bg-muted/30 px-4 text-center text-sm font-medium text-muted-foreground transition xl:flex",
                  "hover:bg-muted/50 focus-visible:border-ring focus-visible:ring-2 focus-visible:ring-ring/30 focus-visible:outline-none disabled:pointer-events-none disabled:opacity-60",
                  photoDropActive && "border-primary bg-primary/5 text-primary"
                )}
                onClick={() => fileInputRef.current?.click()}
                onDragEnter={(event) => {
                  event.preventDefault()
                  setPhotoDropActive(true)
                }}
                onDragOver={(event) => {
                  event.preventDefault()
                  event.dataTransfer.dropEffect = "copy"
                  setPhotoDropActive(true)
                }}
                onDragLeave={(event) => {
                  if (
                    event.currentTarget.contains(event.relatedTarget as Node)
                  ) {
                    return
                  }

                  setPhotoDropActive(false)
                }}
                onDrop={handlePhotoDrop}
              >
                Перетащите несколько изображений
              </button>

              {form.photos.length > 0 ? (
                <div className="grid gap-3 sm:grid-cols-2 lg:grid-cols-3">
                  {form.photos.map((photo) => (
                    <div
                      key={photo.id}
                      className="overflow-hidden rounded-lg border bg-card"
                    >
                      <button
                        type="button"
                        className="block aspect-[4/3] w-full bg-muted"
                        onClick={() => setPhotoPreviewId(photo.id)}
                      >
                        <img
                          src={photo.variants.small.url}
                          alt={photo.name}
                          className="h-full w-full object-cover"
                        />
                      </button>

                      <div className="flex items-center justify-between gap-2 p-2">
                        <div className="min-w-0">
                          <div className="truncate text-xs font-medium">
                            {photo.name}
                          </div>
                          <div className="text-xs text-muted-foreground">
                            Поворот: {photo.rotation}°
                          </div>
                        </div>

                        <div className="flex shrink-0 gap-1">
                          <Button
                            type="button"
                            size="icon-sm"
                            variant="ghost"
                            aria-label="Просмотреть фото"
                            onClick={() => setPhotoPreviewId(photo.id)}
                          >
                            <HugeiconsIcon icon={EyeIcon} />
                          </Button>
                          <Button
                            type="button"
                            size="icon-sm"
                            variant="ghost"
                            aria-label="Повернуть фото"
                            disabled={rotatingPhotoId === photo.id}
                            onClick={() => rotatePhoto(photo)}
                          >
                            <HugeiconsIcon icon={RotateClockwiseIcon} />
                          </Button>
                          <Button
                            type="button"
                            size="icon-sm"
                            variant="ghost"
                            aria-label="Удалить фото"
                            onClick={() => removePhoto(photo.id)}
                          >
                            <HugeiconsIcon icon={Delete02Icon} />
                          </Button>
                        </div>
                      </div>
                    </div>
                  ))}
                </div>
              ) : null}
            </FieldSet>

            {createMutation.isError ? (
              <FieldError>
                Не удалось создать бытовку. Проверьте данные и повторите.
              </FieldError>
            ) : null}

            <DialogFooter>
              <Button
                type="button"
                variant="outline"
                disabled={createMutation.isPending}
                onClick={() => handleDialogOpenChange(false)}
              >
                Отмена
              </Button>
              <Button
                type="submit"
                disabled={createMutation.isPending || processingPhotos}
              >
                <HugeiconsIcon icon={CheckIcon} data-icon="inline-start" />
                {createMutation.isPending ? "Создание..." : "Создать бытовку"}
              </Button>
            </DialogFooter>
          </form>
        </DialogContent>
      </Dialog>

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

      <RentalItemPhotoPreviewDialog
        photo={selectedPhoto}
        onOpenChange={(nextOpen) => {
          if (!nextOpen) {
            setPhotoPreviewId(null)
          }
        }}
      />
    </>
  )
}
