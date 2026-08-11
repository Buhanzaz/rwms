import { useEffect, useMemo, useRef, useState } from "react"
import { useQuery } from "@tanstack/react-query"
import { MinusSignIcon, PlusSignIcon } from "@hugeicons/core-free-icons"
import { HugeiconsIcon } from "@hugeicons/react"

import { getEquipmentItems } from "@/api/equipment-api"
import { Button } from "@/components/ui/button"
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
} from "@/components/ui/field"
import { Input } from "@/components/ui/input"
import { Tabs, TabsContent, TabsList, TabsTrigger } from "@/components/ui/tabs"
import { Textarea } from "@/components/ui/textarea"
import type { InventoryObservation } from "@/features/inventory/model/inventory-service"
import { RentalItemCompositionFields } from "@/features/rental-items/rental-item-composition-fields"
import type { RentalItemCompositionFormValue } from "@/features/rental-items/rental-item-composition"
import type {
  CabinCatalogValue,
  RentalItemCreationOptions,
} from "@/features/rental-items/api/asset-rental-items-api"
import type { EquipmentItemDto } from "@/types/equipment"

type PassportValues = {
  rentalType: string
  dimensions: string
  finishing: string
  category: string
  characteristics: string[]
  linoleum: "" | "yes" | "no"
}

type FurnitureDialogStep = "decision" | "items"

const passportObservationKeys = new Set([
  "rentalType",
  "dimensions",
  "finishing",
  "category",
  "characteristics",
  "linoleum",
])
const INVENTORY_CURRENT_PASSPORT_VALUE = "inventory-current-passport:"

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === "object" && value !== null && !Array.isArray(value)
}

function observationRecord(
  observation: InventoryObservation,
  snapshot: Record<string, unknown> | null
) {
  if (observation.presence === "PRESENT" && isRecord(observation.value)) {
    return observation.value
  }
  if (observation.presence === "EXPLICIT_EMPTY") return {}
  return snapshot ?? {}
}

function textValue(value: unknown) {
  return typeof value === "string" ? value.trim() : ""
}

function characteristicsValues(value: unknown) {
  if (Array.isArray(value)) {
    return value
      .map((item) => textValue(item))
      .filter(Boolean)
  }
  const singleValue = textValue(value)
  return singleValue ? [singleValue] : []
}

function linoleumFormValue(value: unknown): PassportValues["linoleum"] {
  if (value === true) return "yes"
  if (value === false) return "no"
  return ""
}

function passportValues(
  observation: InventoryObservation,
  snapshot: Record<string, unknown> | null
): PassportValues {
  const value = observationRecord(observation, snapshot)
  return {
    rentalType: textValue(value.rentalType),
    dimensions: textValue(value.dimensions),
    finishing: textValue(value.finishing),
    category: textValue(value.category),
    characteristics: characteristicsValues(value.characteristics),
    linoleum: linoleumFormValue(value.linoleum),
  }
}

function includeCurrentCatalogValues(
  values: readonly CabinCatalogValue[],
  names: readonly string[],
  kind: string
) {
  const next = [...values]
  Array.from(new Set(names.map(textValue).filter(Boolean))).forEach((name) => {
    if (next.some((item) => item.name === name)) return
    next.push({
      id: `${INVENTORY_CURRENT_PASSPORT_VALUE}${kind}:${encodeURIComponent(name)}`,
      name,
    })
  })
  return next
}

function optionId(values: readonly CabinCatalogValue[], name: string) {
  return values.find((value) => value.name === name)?.id ?? ""
}

function optionName(values: readonly CabinCatalogValue[], id: string) {
  return values.find((value) => value.id === id)?.name ?? ""
}

function passportOptionsForInspection(
  options: RentalItemCreationOptions,
  values: PassportValues
): RentalItemCreationOptions {
  const rentalTypes = includeCurrentCatalogValues(
    options.rentalTypes,
    [values.rentalType],
    "type"
  )
  const dimensions = includeCurrentCatalogValues(
    options.dimensions,
    [values.dimensions],
    "dimension"
  )
  const rentalTypeId = optionId(rentalTypes, values.rentalType)
  const dimensionId = optionId(dimensions, values.dimensions)
  const typeDimensions = [...options.typeDimensions]

  if (
    rentalTypeId &&
    dimensionId &&
    !typeDimensions.some(
      (link) =>
        link.typeId === rentalTypeId && link.dimensionId === dimensionId
    )
  ) {
    const nextSortOrder = typeDimensions.reduce(
      (maximum, link) => Math.max(maximum, link.sortOrder),
      -1
    ) + 1
    typeDimensions.push({
      typeId: rentalTypeId,
      dimensionId,
      sortOrder: nextSortOrder,
    })
  }

  return {
    ...options,
    rentalTypes,
    dimensions,
    finishings: includeCurrentCatalogValues(
      options.finishings,
      [values.finishing],
      "finishing"
    ),
    categories: includeCurrentCatalogValues(
      options.categories,
      [values.category],
      "category"
    ),
    characteristics: includeCurrentCatalogValues(
      options.characteristics,
      values.characteristics,
      "characteristic"
    ),
    typeDimensions,
  }
}

function passportCompositionValue(
  options: RentalItemCreationOptions,
  values: PassportValues
): RentalItemCompositionFormValue {
  return {
    rentalTypeId: optionId(options.rentalTypes, values.rentalType),
    dimensionId: optionId(options.dimensions, values.dimensions),
    finishingId: optionId(options.finishings, values.finishing),
    category: values.category,
    characteristicIds: Array.from(
      new Set(
        values.characteristics
          .map((characteristic) =>
            optionId(options.characteristics, characteristic)
          )
          .filter(Boolean)
      )
    ),
    linoleum: values.linoleum,
  }
}

function passportObservationForSelection(
  selection: RentalItemCompositionFormValue,
  options: RentalItemCreationOptions,
  previous: InventoryObservation
): InventoryObservation {
  const value: Record<string, unknown> = {}
  if (previous.presence === "PRESENT" && isRecord(previous.value)) {
    Object.entries(previous.value).forEach(([key, entry]) => {
      if (!passportObservationKeys.has(key)) value[key] = entry
    })
  }

  const setValue = (key: string, source: string) => {
    if (source) value[key] = source
  }
  setValue("rentalType", optionName(options.rentalTypes, selection.rentalTypeId))
  setValue("dimensions", optionName(options.dimensions, selection.dimensionId))
  setValue("finishing", optionName(options.finishings, selection.finishingId))
  setValue("category", selection.category.trim())
  const characteristics = Array.from(
    new Set(
      selection.characteristicIds
        .map((id) => optionName(options.characteristics, id))
        .filter(Boolean)
    )
  )
  if (characteristics.length > 0) value.characteristics = characteristics
  if (selection.linoleum === "yes") value.linoleum = true
  if (selection.linoleum === "no") value.linoleum = false

  return Object.keys(value).length === 0
    ? { presence: "EXPLICIT_EMPTY", value: {} }
    : { presence: "PRESENT", value }
}

function PassportObservationEditor({
  observation,
  snapshot,
  options,
  optionsLoading,
  optionsError,
  disabled,
  onChange,
  onRetryOptions,
}: {
  observation: InventoryObservation
  snapshot: Record<string, unknown> | null
  options: RentalItemCreationOptions | null
  optionsLoading: boolean
  optionsError: string | null
  disabled: boolean
  onChange: (observation: InventoryObservation) => void
  onRetryOptions: () => void
}) {
  const currentValues = useMemo(
    () => passportValues(observation, snapshot),
    [observation, snapshot]
  )
  const inspectionOptions = useMemo(
    () =>
      options
        ? passportOptionsForInspection(options, currentValues)
        : null,
    [currentValues, options]
  )
  const valuesKey = JSON.stringify(currentValues)
  const initializedValuesKey = useRef<string | null>(null)
  const [value, setValue] = useState<RentalItemCompositionFormValue>(() =>
    inspectionOptions
      ? passportCompositionValue(inspectionOptions, currentValues)
      : {
          rentalTypeId: "",
          dimensionId: "",
          finishingId: "",
          category: "",
          characteristicIds: [],
          linoleum: "",
        }
  )

  useEffect(() => {
    if (!inspectionOptions || initializedValuesKey.current === valuesKey) return
    setValue(passportCompositionValue(inspectionOptions, currentValues))
    initializedValuesKey.current = valuesKey
  }, [currentValues, inspectionOptions, valuesKey])

  function update(next: RentalItemCompositionFormValue) {
    if (!inspectionOptions) return
    setValue(next)
    onChange(passportObservationForSelection(next, inspectionOptions, observation))
  }

  if (!inspectionOptions) {
    return (
      <div className="flex flex-col gap-3">
        {optionsError ? (
          <>
            <FieldError role="alert">{optionsError}</FieldError>
            <Button
              type="button"
              className="self-start"
              onClick={onRetryOptions}
            >
              Повторить
            </Button>
          </>
        ) : (
          <p className="text-sm text-muted-foreground">
            {optionsLoading
              ? "Загружаем настройки бытовок…"
              : "Настройки бытовок недоступны."}
          </p>
        )}
      </div>
    )
  }

  return (
    <div className="flex flex-col gap-4">
      <p className="text-sm text-muted-foreground">
        Выберите фактические значения из настроек бытовок. Результат
        сохраняется в инвентаризации и не меняет карточку имущества напрямую.
      </p>
      <RentalItemCompositionFields
        options={inspectionOptions}
        value={value}
        categoryMode="EDIT"
        disabled={disabled}
        onChange={update}
      />
    </div>
  )
}

function furnitureEntries(observation: InventoryObservation) {
  if (observation.presence !== "PRESENT" || !Array.isArray(observation.value)) {
    return []
  }
  return observation.value.filter(isRecord)
}

function furnitureSummary(observation: InventoryObservation) {
  if (observation.presence === "ABSENT") {
    return "Наличие мебели ещё не зафиксировано."
  }
  if (observation.presence === "EXPLICIT_EMPTY") return "Мебель отсутствует."

  const entries = furnitureEntries(observation)
  const total = entries.reduce((sum, entry) => {
    const value = entry.quantity
    return (
      sum +
      (typeof value === "number" && Number.isSafeInteger(value) ? value : 0)
    )
  }, 0)
  return total > 0
    ? `Зафиксировано позиций мебели: ${total}.`
    : "Мебель отсутствует."
}

export function InventoryInspectionDetails({
  cabinNumber,
  statusLabel,
  tenant,
  businessDate,
  comment,
  passportObservation: currentPassportObservation,
  passportSnapshot,
  passportOptions,
  passportOptionsLoading,
  passportOptionsError,
  equipmentObservation,
  readOnly,
  onCommentChange,
  onPassportObservationChange,
  onEditFurniture,
  onRetryPassportOptions,
}: {
  cabinNumber: string
  statusLabel: string
  tenant: string | null
  businessDate: string
  comment: string
  passportObservation: InventoryObservation
  passportSnapshot: Record<string, unknown> | null
  passportOptions: RentalItemCreationOptions | null
  passportOptionsLoading: boolean
  passportOptionsError: string | null
  equipmentObservation: InventoryObservation
  readOnly: boolean
  onCommentChange: (value: string) => void
  onPassportObservationChange: (value: InventoryObservation) => void
  onEditFurniture: () => void
  onRetryPassportOptions: () => void
}) {
  return (
    <Tabs defaultValue="information" className="min-h-0">
      <TabsList className="w-full">
        <TabsTrigger value="information">Информация</TabsTrigger>
        <TabsTrigger value="passport">Паспорт бытовки</TabsTrigger>
      </TabsList>
      <TabsContent value="information" className="pt-3">
        <FieldGroup className="gap-3">
          <Field orientation="horizontal">
            <FieldLabel htmlFor="inventory-inspection-number" className="w-32">
              Бытовка
            </FieldLabel>
            <Input
              id="inventory-inspection-number"
              value={cabinNumber}
              readOnly
            />
          </Field>
          <Field orientation="horizontal">
            <FieldLabel htmlFor="inventory-inspection-reason" className="w-32">
              Причина
            </FieldLabel>
            <Input
              id="inventory-inspection-reason"
              value="Инвентаризация"
              readOnly
            />
          </Field>
          <Field orientation="horizontal">
            <FieldLabel htmlFor="inventory-inspection-date" className="w-32">
              Дата
            </FieldLabel>
            <Input
              id="inventory-inspection-date"
              type="date"
              value={businessDate}
              readOnly
            />
          </Field>
          <Field orientation="horizontal">
            <FieldLabel htmlFor="inventory-inspection-status" className="w-32">
              Статус
            </FieldLabel>
            <Input
              id="inventory-inspection-status"
              value={statusLabel || "—"}
              readOnly
            />
          </Field>
          <Field orientation="horizontal">
            <FieldLabel htmlFor="inventory-inspection-tenant" className="w-32">
              Арендатор
            </FieldLabel>
            <Input
              id="inventory-inspection-tenant"
              value={tenant?.trim() || "—"}
              readOnly
            />
          </Field>
          <Field>
            <div className="flex flex-wrap items-center justify-between gap-2">
              <FieldLabel>Мебель</FieldLabel>
              {!readOnly ? (
                <Button
                  type="button"
                  size="sm"
                  variant="outline"
                  onClick={onEditFurniture}
                >
                  {equipmentObservation.presence === "ABSENT"
                    ? "Указать мебель"
                    : "Изменить мебель"}
                </Button>
              ) : null}
            </div>
            <FieldDescription>
              {furnitureSummary(equipmentObservation)}
            </FieldDescription>
            <FieldDescription>
              Мебель попадёт в общую сверку после осмотра; отдельная задача
              отсюда не создаётся.
            </FieldDescription>
          </Field>
          <Field data-disabled={readOnly}>
            <FieldLabel htmlFor="inventory-inspection-comment">
              Комментарий
            </FieldLabel>
            <Textarea
              id="inventory-inspection-comment"
              className="field-sizing-fixed h-28 min-h-28 resize-none"
              disabled={readOnly}
              value={comment}
              onChange={(event) => onCommentChange(event.target.value)}
            />
          </Field>
        </FieldGroup>
      </TabsContent>
      <TabsContent value="passport" className="pt-3">
        <PassportObservationEditor
          observation={currentPassportObservation}
          snapshot={passportSnapshot}
          options={passportOptions}
          optionsLoading={passportOptionsLoading}
          optionsError={passportOptionsError}
          disabled={readOnly}
          onChange={onPassportObservationChange}
          onRetryOptions={onRetryPassportOptions}
        />
      </TabsContent>
    </Tabs>
  )
}

function isFurniture(item: EquipmentItemDto) {
  return item.active && item.category === "FURNITURE"
}

function positiveInteger(value: unknown) {
  if (typeof value === "number" && Number.isSafeInteger(value) && value >= 0) {
    return value
  }
  if (typeof value === "string" && /^\d+$/.test(value)) {
    const parsed = Number(value)
    return Number.isSafeInteger(parsed) ? parsed : null
  }
  return null
}

function currentFurnitureQuantities(
  observation: InventoryObservation,
  contentsSnapshot: Record<string, unknown> | unknown[]
) {
  const source =
    observation.presence === "PRESENT"
      ? furnitureEntries(observation)
      : observation.presence === "EXPLICIT_EMPTY"
        ? []
        : Array.isArray(contentsSnapshot)
          ? contentsSnapshot.filter(isRecord)
          : []
  const quantities = new Map<string, number>()
  source.forEach((entry) => {
    const equipmentId = textValue(entry.equipmentId)
    const quantity = positiveInteger(entry.quantity)
    if (!equipmentId || quantity === null) return
    quantities.set(equipmentId, (quantities.get(equipmentId) ?? 0) + quantity)
  })
  return quantities
}

function initialFurnitureQuantities(
  items: EquipmentItemDto[],
  observation: InventoryObservation,
  contentsSnapshot: Record<string, unknown> | unknown[]
) {
  const current = currentFurnitureQuantities(observation, contentsSnapshot)
  return Object.fromEntries(
    items.map((item) => [item.id, String(current.get(item.id) ?? 0)])
  )
}

function InventoryFurnitureItems({
  furniture,
  observation,
  contentsSnapshot,
  onOpenChange,
  onResolved,
}: {
  furniture: EquipmentItemDto[]
  observation: InventoryObservation
  contentsSnapshot: Record<string, unknown> | unknown[]
  onOpenChange: (open: boolean) => void
  onResolved: (observation: InventoryObservation) => void
}) {
  const [quantities, setQuantities] = useState<Record<string, string>>(() =>
    initialFurnitureQuantities(furniture, observation, contentsSnapshot)
  )
  const [validationError, setValidationError] = useState<string | null>(null)

  function changeQuantity(equipmentId: string, value: string) {
    if (value !== "" && !/^\d+$/.test(value)) return
    setQuantities((current) => ({ ...current, [equipmentId]: value }))
    setValidationError(null)
  }

  function adjustQuantity(equipmentId: string, adjustment: number) {
    const current = positiveInteger(quantities[equipmentId]) ?? 0
    changeQuantity(equipmentId, String(Math.max(0, current + adjustment)))
  }

  function saveFurniture() {
    const entries: Array<Record<string, unknown>> = []
    for (const item of furniture) {
      const quantity = positiveInteger(quantities[item.id])
      if (quantity === null) {
        setValidationError(`Укажите целое количество для «${item.name}».`)
        return
      }
      if (quantity > 0) {
        entries.push({
          equipmentId: item.id,
          equipmentName: item.name,
          equipmentCategory: item.category,
          catalogVersion: item.version,
          quantity,
        })
      }
    }
    onResolved(
      entries.length > 0
        ? { presence: "PRESENT", value: entries }
        : { presence: "EXPLICIT_EMPTY", value: [] }
    )
  }

  return (
    <div className="flex flex-col gap-3">
      {furniture.map((item) => {
        const current = quantities[item.id] ?? "0"
        return (
          <Field key={item.id} orientation="horizontal">
            <FieldLabel
              htmlFor={`inventory-furniture-${item.id}`}
              className="min-w-0 flex-1"
            >
              {item.name}
            </FieldLabel>
            <div className="flex items-center gap-1">
              <Button
                type="button"
                size="icon-sm"
                variant="outline"
                aria-label={`Уменьшить количество ${item.name}`}
                disabled={(positiveInteger(current) ?? 0) === 0}
                onClick={() => adjustQuantity(item.id, -1)}
              >
                <HugeiconsIcon icon={MinusSignIcon} />
              </Button>
              <Input
                id={`inventory-furniture-${item.id}`}
                className="w-20 text-center"
                inputMode="numeric"
                aria-label={`Количество мебели ${item.name}`}
                value={current}
                onChange={(event) =>
                  changeQuantity(item.id, event.target.value)
                }
              />
              <Button
                type="button"
                size="icon-sm"
                variant="outline"
                aria-label={`Увеличить количество ${item.name}`}
                onClick={() => adjustQuantity(item.id, 1)}
              >
                <HugeiconsIcon icon={PlusSignIcon} />
              </Button>
            </div>
          </Field>
        )
      })}
      {validationError ? (
        <FieldError role="alert">{validationError}</FieldError>
      ) : null}
      <DialogFooter>
        <Button
          type="button"
          variant="outline"
          onClick={() => onOpenChange(false)}
        >
          Отмена
        </Button>
        <Button type="button" onClick={saveFurniture}>
          Сохранить мебель
        </Button>
      </DialogFooter>
    </div>
  )
}

export function InventoryFurnitureObservationDialog({
  open,
  step,
  accessToken,
  warehouseId,
  observation,
  contentsSnapshot,
  onOpenChange,
  onRequestItems,
  onResolved,
}: {
  open: boolean
  step: FurnitureDialogStep
  accessToken: string | null
  warehouseId: string
  observation: InventoryObservation
  contentsSnapshot: Record<string, unknown> | unknown[]
  onOpenChange: (open: boolean) => void
  onRequestItems: () => void
  onResolved: (observation: InventoryObservation) => void
}) {
  const equipmentQuery = useQuery({
    queryKey: ["inventory", "furniture-catalog", warehouseId],
    queryFn: () => getEquipmentItems(accessToken, { warehouseId }),
    enabled: open && step === "items" && Boolean(accessToken && warehouseId),
  })
  const furniture = useMemo(
    () =>
      (equipmentQuery.data ?? [])
        .filter(isFurniture)
        .slice()
        .sort((left, right) =>
          left.name.localeCompare(right.name, "ru", { sensitivity: "base" })
        ),
    [equipmentQuery.data]
  )

  return (
    <Dialog open={open} onOpenChange={onOpenChange}>
      <DialogContent className="max-h-[calc(100svh-1rem)] overflow-y-auto sm:max-w-2xl">
        {step === "decision" ? (
          <>
            <DialogHeader>
              <DialogTitle>Мебель в бытовке</DialogTitle>
              <DialogDescription>
                Есть ли мебель в бытовке? Ответ сохранится как часть осмотра.
              </DialogDescription>
            </DialogHeader>
            <DialogFooter>
              <Button
                type="button"
                variant="outline"
                onClick={() =>
                  onResolved({ presence: "EXPLICIT_EMPTY", value: [] })
                }
              >
                Мебели нет
              </Button>
              <Button type="button" onClick={onRequestItems}>
                Мебель есть
              </Button>
            </DialogFooter>
          </>
        ) : (
          <>
            <DialogHeader>
              <DialogTitle>Комплектация мебелью</DialogTitle>
              <DialogDescription>
                Укажите фактическое количество. Ноль означает, что позиции в
                бытовке нет.
              </DialogDescription>
            </DialogHeader>
            {equipmentQuery.isLoading ? (
              <p className="text-sm text-muted-foreground">
                Загружаем каталог мебели…
              </p>
            ) : equipmentQuery.isError ? (
              <div className="flex flex-col gap-3">
                <FieldError role="alert">
                  {equipmentQuery.error instanceof Error
                    ? equipmentQuery.error.message
                    : "Не удалось загрузить каталог мебели."}
                </FieldError>
                <Button
                  type="button"
                  className="self-start"
                  onClick={() => void equipmentQuery.refetch()}
                >
                  Повторить
                </Button>
              </div>
            ) : furniture.length === 0 ? (
              <FieldError role="alert">
                В каталоге нет активных позиций мебели. Обновите каталог и
                повторите осмотр.
              </FieldError>
            ) : open ? (
              <InventoryFurnitureItems
                key={JSON.stringify({
                  furniture: furniture.map((item) => [item.id, item.version]),
                  observation,
                  contentsSnapshot,
                })}
                furniture={furniture}
                observation={observation}
                contentsSnapshot={contentsSnapshot}
                onOpenChange={onOpenChange}
                onResolved={onResolved}
              />
            ) : null}
          </>
        )}
      </DialogContent>
    </Dialog>
  )
}
