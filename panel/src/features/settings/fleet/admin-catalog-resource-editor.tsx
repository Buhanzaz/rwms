import { useEffect, useState, type FormEvent, type ReactNode } from "react"
import {
  ArrowDown01Icon,
  Delete02Icon,
  FloppyDiskIcon,
  Loading03Icon,
} from "@hugeicons/core-free-icons"
import { HugeiconsIcon } from "@hugeicons/react"

import { Alert, AlertDescription, AlertTitle } from "@/components/ui/alert"
import { Button } from "@/components/ui/button"
import { Checkbox } from "@/components/ui/checkbox"
import {
  Collapsible,
  CollapsibleContent,
  CollapsibleTrigger,
} from "@/components/ui/collapsible"
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
  FieldContent,
  FieldDescription,
  FieldError,
  FieldGroup,
  FieldLabel,
} from "@/components/ui/field"
import { Input } from "@/components/ui/input"
import {
  Select,
  SelectContent,
  SelectGroup,
  SelectItem,
  SelectTrigger,
  SelectValue,
} from "@/components/ui/select"
import { Textarea } from "@/components/ui/textarea"
import { ToggleGroup, ToggleGroupItem } from "@/components/ui/toggle-group"
import {
  defaultAdminVehicleSpecification,
  emptyAdminPhysicalSpecification,
  physicalFieldNames,
  vehicleLoadProfileTypes,
  type AdminCatalogKind,
  type AdminCatalogResource,
  type AdminCatalogResourceInput,
  type AdminPhysicalSpecification,
  type AdminVehicleSpecification,
  type VehicleLoadProfileType,
} from "@/features/settings/fleet/api/admin-catalog-api"

export type CatalogEditorState = {
  resource: AdminCatalogResource | null
  idempotencyKey: string
}

const physicalGroups = [
  {
    title: "Масса, габариты и оси",
    fields: [
      ["tareWeightKg", "Снаряжённая масса, кг"],
      ["maxGrossWeightKg", "Разрешённая полная масса, кг"],
      ["lengthMm", "Длина транспорта, мм"],
      ["widthMm", "Ширина транспорта, мм"],
      ["heightMm", "Высота транспорта, мм"],
      ["axleCount", "Количество осей"],
      ["maxAxleLoadKg", "Допустимая нагрузка на ось, кг"],
      ["payloadCapacityKg", "Грузоподъёмность, кг"],
    ],
  },
  {
    title: "Платформа и груз",
    fields: [
      ["platformLengthMm", "Длина платформы, мм"],
      ["platformWidthMm", "Ширина платформы, мм"],
      ["platformHeightFromGroundMm", "Высота платформы от земли, мм"],
      ["maxPlatformPayloadKg", "Нагрузка на платформу, кг"],
      ["maxCargoLengthMm", "Максимальная длина груза, мм"],
      ["maxCargoWidthMm", "Максимальная ширина груза, мм"],
      ["maxCargoHeightMm", "Максимальная высота груза, мм"],
      ["maxCargoWeightKg", "Максимальная масса груза, кг"],
    ],
  },
] as const satisfies ReadonlyArray<{
  title: string
  fields: ReadonlyArray<readonly [keyof AdminPhysicalSpecification, string]>
}>

const vehicleNumbers = [
  ["combinedLengthWithTrailerMm", "Полная длина автопоезда, мм", true, 1],
  ["couplingLengthMm", "Длина сцепки, мм", true, 1],
  ["heightSafetyMarginMm", "Запас по высоте, мм", false, 0],
  ["widthSafetyMarginMm", "Запас по ширине, мм", false, 0],
  ["weightSafetyMarginKg", "Запас по массе, кг", false, 0],
  ["averageSpeedCity", "Средняя скорость в городе, км/ч", false, 1],
  ["averageSpeedRegion", "Средняя скорость за городом, км/ч", false, 1],
] as const

const axleLabels: Record<VehicleLoadProfileType, string> = {
  EMPTY_TRUCK: "Пустой автомобиль, кг",
  CARGO_ON_TRUCK: "Автомобиль с грузом, кг",
  EMPTY_COMBINATION: "Пустой автомобиль с прицепом, кг",
  CARGO_ON_TRUCK_WITH_TRAILER: "Груз на автомобиле, прицеп пустой, кг",
  CARGO_ON_TRAILER_WITH_TRAILER: "Автомобиль пустой, груз на прицепе, кг",
  TWO_CARGO_SPLIT: "Груз на автомобиле и прицепе, кг",
}

function initialValues(
  resource: AdminCatalogResource | null
): Record<string, string> {
  const physical = resource?.physical ?? emptyAdminPhysicalSpecification()
  const vehicle = resource?.vehicle ?? defaultAdminVehicleSpecification()
  return {
    name: resource?.name ?? "",
    registrationNumber: resource?.registrationNumber ?? "",
    capacity: String(resource?.capacity ?? 2),
    notes: resource?.notes ?? "",
    ...Object.fromEntries(
      Object.keys(physicalFieldNames).map((key) => [
        key,
        String(physical[key as keyof AdminPhysicalSpecification] ?? ""),
      ])
    ),
    ...Object.fromEntries(
      vehicleNumbers.map(([key]) => [key, String(vehicle[key] ?? "")])
    ),
    vehicleType: vehicle.vehicleType ?? "",
    manufacturer: vehicle.manufacturer ?? "",
    model: vehicle.model ?? "",
    isHgv: vehicle.isHgv == null ? "unknown" : String(vehicle.isHgv),
    canUseTrailer:
      vehicle.canUseTrailer == null ? "unknown" : String(vehicle.canUseTrailer),
    defaultTrailerId: vehicle.defaultTrailerId ?? "none",
    ...Object.fromEntries(
      vehicleLoadProfileTypes.map((type) => [
        type,
        String(
          vehicle.loadProfiles.find(
            (profile) => profile.configurationType === type
          )?.maxActualAxleLoadKg ?? ""
        ),
      ])
    ),
  }
}

function parseInput(
  kind: AdminCatalogKind,
  values: Record<string, string>,
  active: boolean
) {
  const errors: Record<string, string> = {}
  const read = (key: string) => values[key]?.trim() ?? ""
  const number = (
    key: string,
    nullable = true,
    minimum = 1,
    decimal = false
  ): number | null => {
    const value = read(key)
    if (!value && nullable) return null
    const parsed = Number(value.replace(",", "."))
    const validSyntax = decimal
      ? /^(?:\d+(?:[.,]\d*)?|[.,]\d+)$/.test(value)
      : /^\d+$/.test(value)
    if (
      !validSyntax ||
      !Number.isFinite(parsed) ||
      (!decimal && !Number.isSafeInteger(parsed)) ||
      (decimal ? parsed <= 0 : parsed < minimum)
    ) {
      errors[key] = decimal
        ? "Введите положительное число."
        : minimum === 0
          ? "Введите целое число от 0."
          : "Введите целое число больше 0."
      return null
    }
    return parsed
  }
  if (!read("name")) errors.name = "Укажите название."
  if (!read("registrationNumber"))
    errors.registrationNumber = "Укажите регистрационный номер."
  const capacity = kind === "vehicle" ? Number(read("capacity")) : null
  if (kind === "vehicle" && capacity !== 1 && capacity !== 2)
    errors.capacity = "Выберите вместимость."
  const physical = Object.fromEntries(
    Object.keys(physicalFieldNames).map((key) => [key, number(key)])
  ) as AdminPhysicalSpecification
  let vehicle: AdminVehicleSpecification | null = null
  if (kind === "vehicle") {
    const numbers = Object.fromEntries(
      vehicleNumbers.map(([key, , nullable, minimum]) => [
        key,
        number(key, nullable, minimum, key.startsWith("averageSpeed")),
      ])
    ) as Pick<AdminVehicleSpecification, (typeof vehicleNumbers)[number][0]>
    const canUseTrailer =
      read("canUseTrailer") === "unknown"
        ? null
        : read("canUseTrailer") === "true"
    const defaultTrailerId =
      read("defaultTrailerId") === "none" ? null : read("defaultTrailerId")
    if (defaultTrailerId && canUseTrailer !== true)
      errors.canUseTrailer =
        "Для назначенного прицепа выберите «Да» или снимите назначение."
    vehicle = {
      ...numbers,
      vehicleType: read("vehicleType") || null,
      manufacturer: read("manufacturer") || null,
      model: read("model") || null,
      isHgv: read("isHgv") === "unknown" ? null : read("isHgv") === "true",
      canUseTrailer,
      defaultTrailerId,
      loadProfiles: vehicleLoadProfileTypes.flatMap((type) => {
        const load = number(type)
        return load === null
          ? []
          : [{ configurationType: type, maxActualAxleLoadKg: load }]
      }),
    }
  }
  const input: AdminCatalogResourceInput = {
    name: read("name"),
    registrationNumber: read("registrationNumber"),
    notes: read("notes"),
    active,
    capacity,
    physical,
    vehicle,
  }
  return { errors, input }
}

function SpecificationSection({
  title,
  invalid,
  children,
}: {
  title: string
  invalid: boolean
  children: ReactNode
}) {
  const [open, setOpen] = useState(false)
  return (
    <Collapsible
      open={open || invalid}
      onOpenChange={setOpen}
      className="flex flex-col gap-3"
    >
      <CollapsibleTrigger asChild>
        <Button
          type="button"
          variant="outline"
          className="w-full justify-between"
        >
          {title}
          <HugeiconsIcon
            icon={ArrowDown01Icon}
            data-icon="inline-end"
            aria-hidden="true"
          />
        </Button>
      </CollapsibleTrigger>
      <CollapsibleContent>
        <FieldGroup>{children}</FieldGroup>
      </CollapsibleContent>
    </Collapsible>
  )
}

export function AdminCatalogResourceEditor({
  kind,
  state,
  pending,
  error,
  trailers,
  trailersLoading,
  trailersError,
  onReloadTrailers,
  onClose,
  onSave,
  onDelete,
}: {
  kind: AdminCatalogKind
  state: CatalogEditorState
  pending: boolean
  error: string | null
  trailers: AdminCatalogResource[]
  trailersLoading: boolean
  trailersError: string | null
  onReloadTrailers: () => void
  onClose: () => void
  onSave: (input: AdminCatalogResourceInput) => Promise<void>
  onDelete?: () => void
}) {
  const [values, setValues] = useState(() => initialValues(state.resource))
  const [active, setActive] = useState(state.resource?.active ?? true)
  const [errors, setErrors] = useState<Record<string, string>>({})
  useEffect(() => {
    const first = Object.keys(errors)[0]
    if (first) document.getElementById(`catalog-resource-${first}`)?.focus()
  }, [errors])

  function setValue(key: string, value: string) {
    setValues((current) => ({ ...current, [key]: value }))
  }

  async function submit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault()
    if (pending) return
    const parsed = parseInput(kind, values, active)
    setErrors(parsed.errors)
    if (Object.keys(parsed.errors).length) return
    await onSave(parsed.input)
  }

  function inputField(
    key: string,
    label: string,
    numeric = false,
    decimal = false
  ) {
    const id = `catalog-resource-${key}`
    return (
      <Field
        key={key}
        data-invalid={Boolean(errors[key])}
        data-disabled={pending}
      >
        <FieldLabel htmlFor={id}>{label}</FieldLabel>
        <Input
          id={id}
          name={key}
          autoComplete="off"
          spellCheck={false}
          inputMode={numeric ? (decimal ? "decimal" : "numeric") : undefined}
          value={values[key] ?? ""}
          disabled={pending}
          onChange={(event) => setValue(key, event.target.value)}
          aria-invalid={Boolean(errors[key])}
          aria-describedby={errors[key] ? `${id}-error` : undefined}
        />
        {errors[key] ? (
          <FieldError id={`${id}-error`}>{errors[key]}</FieldError>
        ) : null}
      </Field>
    )
  }

  function nullableFlag(key: string, label: string) {
    const id = `catalog-resource-${key}`
    return (
      <Field data-invalid={Boolean(errors[key])} data-disabled={pending}>
        <FieldLabel id={`${id}-label`}>{label}</FieldLabel>
        <ToggleGroup
          id={id}
          type="single"
          variant="outline"
          aria-labelledby={`${id}-label`}
          aria-invalid={Boolean(errors[key])}
          aria-describedby={errors[key] ? `${id}-error` : undefined}
          value={values[key]}
          disabled={pending}
          onValueChange={(value) => {
            if (value) setValue(key, value)
          }}
        >
          <ToggleGroupItem value="unknown">Не указано</ToggleGroupItem>
          <ToggleGroupItem value="true">Да</ToggleGroupItem>
          <ToggleGroupItem value="false">Нет</ToggleGroupItem>
        </ToggleGroup>
        {errors[key] ? (
          <FieldError id={`${id}-error`}>{errors[key]}</FieldError>
        ) : null}
      </Field>
    )
  }

  const vehicleKeys = vehicleNumbers.map(([key]) => key)
  const currentTrailerMissing =
    values.defaultTrailerId !== "none" &&
    !trailers.some((trailer) => trailer.id === values.defaultTrailerId)
  return (
    <Dialog
      open
      onOpenChange={(open) => {
        if (!open && !pending) onClose()
      }}
    >
      <DialogContent
        className="max-h-[calc(100dvh-2rem)] overflow-hidden sm:max-w-3xl"
        showCloseButton={!pending}
      >
        <DialogHeader>
          <DialogTitle>
            {state.resource ? "Изменить" : "Добавить"}{" "}
            {kind === "vehicle" ? "транспорт" : "прицеп"}
          </DialogTitle>
          <DialogDescription>
            {kind === "vehicle"
              ? "Характеристики и осевые замеры сохраняются вместе."
              : "Характеристики прицепа сохраняются в каталоге выбранного объекта."}{" "}
            Неизвестные значения оставьте пустыми — это не ноль.
          </DialogDescription>
        </DialogHeader>
        <form
          className="flex min-h-0 flex-col gap-4 overflow-hidden"
          noValidate
          onSubmit={(event) => void submit(event)}
        >
          <FieldGroup className="min-h-0 overflow-y-auto overscroll-contain px-1 pb-1">
            <FieldGroup className="grid gap-4 sm:grid-cols-2">
              {inputField("name", "Название")}
              {inputField("registrationNumber", "Регистрационный номер")}
            </FieldGroup>
            {kind === "vehicle" ? (
              <>
                <Field data-disabled={pending}>
                  <FieldLabel id="catalog-resource-capacity-label">
                    Вместимость, бытовок
                  </FieldLabel>
                  <ToggleGroup
                    id="catalog-resource-capacity"
                    type="single"
                    variant="outline"
                    aria-labelledby="catalog-resource-capacity-label"
                    value={values.capacity}
                    disabled={pending}
                    onValueChange={(value) => {
                      if (value) setValue("capacity", value)
                    }}
                  >
                    <ToggleGroupItem value="1">1 бытовка</ToggleGroupItem>
                    <ToggleGroupItem value="2">2 бытовки</ToggleGroupItem>
                  </ToggleGroup>
                </Field>
                <SpecificationSection
                  title="Автомобиль"
                  invalid={Boolean(errors.isHgv)}
                >
                  <FieldGroup className="grid gap-4 sm:grid-cols-2">
                    {inputField("vehicleType", "Тип автомобиля")}
                    {inputField("manufacturer", "Производитель")}
                    {inputField("model", "Модель")}
                    {nullableFlag("isHgv", "Грузовой автомобиль (HGV)")}
                  </FieldGroup>
                </SpecificationSection>
              </>
            ) : null}
            <FieldDescription>
              Размеры вводятся в миллиметрах, массы и нагрузки — в килограммах.
              Заполняйте по документам и фактическим замерам: неполные данные
              могут ограничить доступные маршруты.
            </FieldDescription>
            {physicalGroups.map((group) => (
              <SpecificationSection
                key={group.title}
                title={group.title}
                invalid={group.fields.some(([key]) => Boolean(errors[key]))}
              >
                <FieldGroup className="grid gap-4 sm:grid-cols-2">
                  {group.fields.map(([key, label]) =>
                    inputField(key, label, true)
                  )}
                </FieldGroup>
              </SpecificationSection>
            ))}
            {kind === "vehicle" ? (
              <>
                <SpecificationSection
                  title="Прицеп"
                  invalid={
                    Boolean(errors.canUseTrailer) ||
                    vehicleKeys.slice(0, 2).some((key) => Boolean(errors[key]))
                  }
                >
                  {nullableFlag("canUseTrailer", "Можно использовать прицеп")}
                  <Field data-disabled={pending}>
                    <FieldLabel htmlFor="catalog-resource-defaultTrailerId">
                      Прицеп по умолчанию
                    </FieldLabel>
                    <Select
                      value={values.defaultTrailerId}
                      disabled={pending}
                      onValueChange={(value) =>
                        setValue("defaultTrailerId", value)
                      }
                    >
                      <SelectTrigger
                        id="catalog-resource-defaultTrailerId"
                        className="w-full"
                      >
                        <SelectValue />
                      </SelectTrigger>
                      <SelectContent>
                        <SelectGroup>
                          <SelectItem value="none">Не назначен</SelectItem>
                          {currentTrailerMissing ? (
                            <SelectItem value={values.defaultTrailerId!}>
                              Текущий прицеп — сведения недоступны
                            </SelectItem>
                          ) : null}
                          {trailers.map((trailer) => (
                            <SelectItem key={trailer.id} value={trailer.id}>
                              {trailer.name} · {trailer.registrationNumber}
                              {trailer.active ? "" : " · неактивен"}
                            </SelectItem>
                          ))}
                        </SelectGroup>
                      </SelectContent>
                    </Select>
                    <FieldDescription>
                      {trailersLoading
                        ? "Загружаем прицепы объекта…"
                        : "Доступны прицепы того же объекта. Для назначения подтвердите возможность буксировки."}
                    </FieldDescription>
                  </Field>
                  {trailersError ? (
                    <Alert variant="destructive">
                      <AlertTitle>Не удалось загрузить прицепы</AlertTitle>
                      <AlertDescription>
                        <span>
                          {trailersError} Текущее назначение сохранено.
                        </span>
                        <Button
                          type="button"
                          variant="outline"
                          size="sm"
                          disabled={trailersLoading}
                          onClick={onReloadTrailers}
                        >
                          Повторить загрузку прицепов
                        </Button>
                      </AlertDescription>
                    </Alert>
                  ) : null}
                  <FieldGroup className="grid gap-4 sm:grid-cols-2">
                    {vehicleNumbers
                      .slice(0, 2)
                      .map(([key, label]) => inputField(key, label, true))}
                  </FieldGroup>
                </SpecificationSection>
                <SpecificationSection
                  title="Осевые замеры"
                  invalid={vehicleLoadProfileTypes.some((key) =>
                    Boolean(errors[key])
                  )}
                >
                  <FieldDescription>
                    Максимальная фактическая нагрузка на одну ось для каждого
                    состояния. Не заменяйте замеры паспортным пределом или
                    средней нагрузкой. Очистка поля удаляет только этот замер.
                  </FieldDescription>
                  <FieldGroup className="grid gap-4 sm:grid-cols-2">
                    {vehicleLoadProfileTypes.map((key) =>
                      inputField(key, axleLabels[key], true)
                    )}
                  </FieldGroup>
                </SpecificationSection>
                <SpecificationSection
                  title="Расчёт маршрута"
                  invalid={vehicleKeys
                    .slice(2)
                    .some((key) => Boolean(errors[key]))}
                >
                  <FieldDescription>
                    Запасы прибавляются к габаритам и массе. Ноль означает
                    отсутствие дополнительного запаса. Средние скорости задаются
                    в км/ч.
                  </FieldDescription>
                  <FieldGroup className="grid gap-4 sm:grid-cols-2">
                    {vehicleNumbers
                      .slice(2)
                      .map(([key, label]) =>
                        inputField(
                          key,
                          label,
                          true,
                          key.startsWith("averageSpeed")
                        )
                      )}
                  </FieldGroup>
                </SpecificationSection>
              </>
            ) : null}
            <Field data-disabled={pending}>
              <FieldLabel htmlFor="catalog-resource-notes">
                Комментарий
              </FieldLabel>
              <Textarea
                id="catalog-resource-notes"
                name="notes"
                autoComplete="off"
                value={values.notes}
                disabled={pending}
                onChange={(event) => setValue("notes", event.target.value)}
              />
            </Field>
            <Field orientation="horizontal" data-disabled={pending}>
              <Checkbox
                id="catalog-resource-active"
                checked={active}
                disabled={pending}
                onCheckedChange={(value) => setActive(value === true)}
              />
              <FieldContent>
                <FieldLabel htmlFor="catalog-resource-active">
                  Активен
                </FieldLabel>
                <FieldDescription>
                  Неактивный ресурс остаётся в каталоге, но не участвует в
                  планировании.
                </FieldDescription>
              </FieldContent>
            </Field>
          </FieldGroup>
          {error ? <FieldError role="alert">{error}</FieldError> : null}
          {Object.keys(errors).length ? (
            <FieldError role="alert">Проверьте отмеченные поля.</FieldError>
          ) : null}
          <DialogFooter>
            {state.resource ? (
              <Button
                type="button"
                variant="destructive"
                size="icon"
                className="sm:mr-auto"
                aria-label="Удалить"
                title="Удалить"
                disabled={pending}
                onClick={onDelete}
              >
                <HugeiconsIcon icon={Delete02Icon} aria-hidden="true" />
              </Button>
            ) : null}
            <Button
              type="button"
              variant="outline"
              disabled={pending}
              onClick={onClose}
            >
              Отмена
            </Button>
            <Button type="submit" disabled={pending}>
              <HugeiconsIcon
                icon={pending ? Loading03Icon : FloppyDiskIcon}
                data-icon="inline-start"
                className={pending ? "animate-spin" : undefined}
                aria-hidden="true"
              />
              {pending ? "Сохраняем…" : "Сохранить"}
            </Button>
          </DialogFooter>
        </form>
      </DialogContent>
    </Dialog>
  )
}
