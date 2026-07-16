import { useEffect, useMemo, useRef, useState } from "react"
import { useMutation, useQuery } from "@tanstack/react-query"
import { HugeiconsIcon } from "@hugeicons/react"
import { CheckIcon } from "@hugeicons/core-free-icons"
import { toast } from "sonner"
import { useAuth } from "@/features/auth/use-auth"

import {
  AlertDialog,
  AlertDialogAction,
  AlertDialogCancel,
  AlertDialogContent,
  AlertDialogDescription,
  AlertDialogFooter,
  AlertDialogHeader,
  AlertDialogTitle,
} from "@/components/ui/alert-dialog"
import { Button } from "@/components/ui/button"
import { Checkbox } from "@/components/ui/checkbox"
import {
  Combobox,
  ComboboxContent,
  ComboboxEmpty,
  ComboboxGroup,
  ComboboxInput,
  ComboboxItem,
  ComboboxList,
} from "@/components/ui/combobox"
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
import {
  Select,
  SelectContent,
  SelectGroup,
  SelectItem,
  SelectTrigger,
  SelectValue,
} from "@/components/ui/select"
import {
  createImportedReturnIntake,
  getImportedReturnIntakePreview,
  listCompanies,
  listReturnEquipmentCatalog,
  LOGISTICS_QUERY_KEY,
} from "@/features/logistics/api/logistics-api"
import { LogisticsCabinContentsEditor } from "@/features/logistics/logistics-cabin-contents-editor"
import type { RentalItemContentsItemDto } from "@/features/rental-items/model/rental-item"
import {
  getDefaultRentalItemDimensions,
  getRentalItemDimensionsForType,
  RENTAL_ITEM_CHARACTERISTIC_OPTIONS,
  RENTAL_ITEM_FINISHING_OPTIONS,
  RENTAL_ITEM_TYPE_OPTIONS,
  type RentalItemCharacteristic,
  type RentalItemCreationPhoto,
  type RentalItemCreationType,
  type RentalItemFinishing,
} from "@/features/rental-items/model/rental-item-create"
import { RentalItemPhotoUploader } from "@/features/rental-items/rental-item-photo-uploader"
import {
  listRepairWorkerGroups,
  repairWorkerGroupsQueryKey,
} from "@/features/repair-tasks/api/repair-worker-directory-api"

const calendarDate = () => {
  const value = new Date()
  return `${value.getFullYear()}-${String(value.getMonth() + 1).padStart(2, "0")}-${String(value.getDate()).padStart(2, "0")}`
}

export function ImportRentedCabinDialog({
  open,
  warehouseId,
  createdBy,
  onOpenChange,
  onCreated,
}: {
  open: boolean
  warehouseId: string | null
  createdBy: string
  onOpenChange: (open: boolean) => void
  onCreated: () => void
}) {
  const { accessToken } = useAuth()
  const dialogContentRef = useRef<HTMLDivElement>(null)
  const [number, setNumber] = useState("")
  const [commandId, setCommandId] = useState(() => crypto.randomUUID())
  const [fromParty, setFromParty] = useState("")
  const [fromPartyInput, setFromPartyInput] = useState("")
  const [shipmentDate, setShipmentDate] = useState(calendarDate())
  const [returnDate, setReturnDate] = useState(calendarDate())
  const [driverId, setDriverId] = useState("")
  const [type, setType] = useState<RentalItemCreationType>("БК-2")
  const [dimensions, setDimensions] = useState(
    getDefaultRentalItemDimensions("БК-2")
  )
  const [finishing, setFinishing] = useState<RentalItemFinishing>("ЛДСП")
  const [category, setCategory] = useState("Обычная")
  const [characteristics, setCharacteristics] = useState<
    RentalItemCharacteristic[]
  >([])
  const [linoleum, setLinoleum] = useState(false)
  const [expectedContents, setExpectedContents] = useState<
    RentalItemContentsItemDto[]
  >([])
  const [returnedContents, setReturnedContents] = useState<
    RentalItemContentsItemDto[]
  >([])
  const [expectedReason, setExpectedReason] = useState("")
  const [photos, setPhotos] = useState<RentalItemCreationPhoto[]>([])
  const [previewAppliedFor, setPreviewAppliedFor] = useState("")
  const [confirmationOpen, setConfirmationOpen] = useState(false)
  const [error, setError] = useState<string | null>(null)

  const companies = useQuery({
    queryKey: [...LOGISTICS_QUERY_KEY, "companies", warehouseId, open],
    queryFn: () => listCompanies(warehouseId!),
    enabled: open && Boolean(warehouseId),
  })
  const catalog = useQuery({
    queryKey: [...LOGISTICS_QUERY_KEY, "return-equipment", warehouseId, open],
    queryFn: () => listReturnEquipmentCatalog(warehouseId!),
    enabled: open && Boolean(warehouseId),
  })
  const preview = useQuery({
    queryKey: [
      ...LOGISTICS_QUERY_KEY,
      "import-preview",
      warehouseId,
      number.trim(),
    ],
    queryFn: () =>
      getImportedReturnIntakePreview({ warehouseId: warehouseId!, number }),
    enabled: open && Boolean(warehouseId && number.trim()),
  })
  const groups = useQuery({
    queryKey: repairWorkerGroupsQueryKey({
      warehouseId: warehouseId ?? "none",
      queueCode: null,
      routeQueueKind: "MOVEMENT",
      purpose: "DRIVER_DIRECTORY",
    }),
    queryFn: () =>
      listRepairWorkerGroups(
        {
          warehouseId: warehouseId!,
          queueCode: null,
          routeQueueKind: "MOVEMENT",
          purpose: "DRIVER_DIRECTORY",
        },
        accessToken ?? undefined
      ),
    enabled: open && Boolean(warehouseId),
  })
  const drivers = useMemo(
    () =>
      Array.from(
        new Map(
          (groups.data ?? [])
            .filter((group) => group.active)
            .flatMap((group) => group.members)
            .map((member) => [member.id, member])
        ).values()
      ),
    [groups.data]
  )
  const selectedDriver = drivers.find((driver) => driver.id === driverId)
  const existing = preview.data?.item ?? null
  const passiveOverride = preview.data?.kind === "CURRENT_WAREHOUSE"
  const otherWarehouse = preview.data?.kind === "OTHER_WAREHOUSE"
  const writtenOff = preview.data?.kind === "WRITTEN_OFF"
  const passportChanged = Boolean(
    existing &&
    (existing.type !== type ||
      existing.dimensions !== dimensions ||
      existing.finishing !== finishing ||
      (existing.category ?? "") !== category.trim() ||
      (existing.linoleum === true) !== linoleum ||
      (existing.characteristics ?? "")
        .split(",")
        .map((value) => value.trim())
        .filter(Boolean)
        .sort()
        .join("|") !== [...characteristics].sort().join("|"))
  )

  useEffect(() => {
    if (!existing || previewAppliedFor === existing.id) return
    const timeout = window.setTimeout(() => {
      setPreviewAppliedFor(existing.id)
      setType(existing.type as RentalItemCreationType)
      setDimensions(
        (existing.dimensions ??
          getDefaultRentalItemDimensions(
            existing.type as RentalItemCreationType
          )) as ReturnType<typeof getDefaultRentalItemDimensions>
      )
      setFinishing((existing.finishing ?? "ЛДСП") as RentalItemFinishing)
      setCategory(existing.category ?? "Обычная")
      setCharacteristics(
        (existing.characteristics ?? "")
          .split(",")
          .map((value) => value.trim())
          .filter(Boolean) as RentalItemCharacteristic[]
      )
      setLinoleum(existing.linoleum === true)
      setExpectedContents(
        preview.data?.expectedContents.map((item) => ({ ...item })) ?? []
      )
      setReturnedContents(
        preview.data?.expectedContents.map((item) => ({ ...item })) ?? []
      )
    }, 0)
    return () => window.clearTimeout(timeout)
  }, [existing, preview.data?.expectedContents, previewAppliedFor])

  const mutation = useMutation({
    mutationFn: (confirmed: boolean) =>
      createImportedReturnIntake({
        commandId,
        warehouseId: warehouseId!,
        accessToken,
        number,
        fromParty,
        shipmentDate,
        returnDate,
        driverId: driverId || null,
        driverName: selectedDriver?.name ?? null,
        createdBy,
        passport: {
          type,
          dimensions,
          finishing,
          category,
          characteristics,
          linoleum,
        },
        expectedContents,
        returnedContents,
        expectedContentsEditedReason: expectedReason || null,
        overrideExisting: confirmed,
        passportChangeConfirmed: confirmed,
        photos,
      }),
    onSuccess: (result) => {
      toast.success(
        result.kind === "CONFLICT"
          ? "Конфликт возврата зарегистрирован"
          : "Бытовка принята в ожидание осмотра"
      )
      reset()
      onOpenChange(false)
      onCreated()
    },
    onError: (cause) =>
      setError(
        cause instanceof Error
          ? cause.message
          : "Не удалось зарегистрировать возврат"
      ),
  })

  function reset() {
    setNumber("")
    setCommandId(crypto.randomUUID())
    setFromParty("")
    setFromPartyInput("")
    setShipmentDate(calendarDate())
    setReturnDate(calendarDate())
    setDriverId("")
    setType("БК-2")
    setDimensions(getDefaultRentalItemDimensions("БК-2"))
    setFinishing("ЛДСП")
    setCategory("Обычная")
    setCharacteristics([])
    setLinoleum(false)
    setExpectedContents([])
    setReturnedContents([])
    setExpectedReason("")
    setPhotos([])
    setPreviewAppliedFor("")
    setError(null)
  }

  function submit() {
    setError(null)
    if (writtenOff) {
      setError("Номер принадлежит списанной бытовке")
      return
    }
    if (passiveOverride || passportChanged) {
      setConfirmationOpen(true)
      return
    }
    mutation.mutate(false)
  }

  return (
    <>
      <Dialog
        open={open}
        onOpenChange={(next) => {
          if (!next && !mutation.isPending) reset()
          onOpenChange(next)
        }}
      >
        <DialogContent
          ref={dialogContentRef}
          className="max-h-[calc(100svh-1rem)] overflow-y-auto sm:max-w-5xl"
        >
          <DialogHeader>
            <DialogTitle>Добавить бытовку из аренды</DialogTitle>
            <DialogDescription>
              Регистрация бытовки и возврата выполняется одной операцией. При
              конфликте запись сохраняется без изменения склада.
            </DialogDescription>
          </DialogHeader>
          <FieldGroup className="grid gap-4 sm:grid-cols-2">
            <Field>
              <FieldLabel htmlFor="import-return-number">
                Номер бытовки
              </FieldLabel>
              <Input
                id="import-return-number"
                value={number}
                onChange={(event) => {
                  setNumber(event.target.value)
                  setPreviewAppliedFor("")
                }}
                placeholder="Например, БЫТ-121"
              />
              {preview.isFetching ? (
                <FieldDescription>Проверяем реестр...</FieldDescription>
              ) : null}
              {otherWarehouse ? (
                <FieldError>
                  Бытовка числится на складе {existing?.warehouseId}. Будет
                  создан конфликт.
                </FieldError>
              ) : null}
              {writtenOff ? (
                <FieldError>
                  Списанный номер нельзя восстановить через возврат.
                </FieldError>
              ) : null}
              {passiveOverride ? (
                <FieldDescription>
                  Бытовка уже есть на этом складе. Продолжение потребует
                  подтверждения перезаписи.
                </FieldDescription>
              ) : null}
            </Field>
            <Field>
              <FieldLabel htmlFor="import-return-party">
                У кого была в аренде
              </FieldLabel>
              <Combobox
                items={companies.data ?? []}
                value={fromParty || null}
                inputValue={fromPartyInput}
                onInputValueChange={(value, details) => {
                  if (details.reason === "input-change")
                    setFromPartyInput(value)
                }}
                onValueChange={(value) => {
                  const next = value ?? ""
                  setFromParty(next)
                  setFromPartyInput(next)
                }}
              >
                <ComboboxInput
                  id="import-return-party"
                  placeholder="Компания или ФИО"
                  onBlur={() => {
                    const next = fromPartyInput.trim()
                    if (next) setFromParty(next)
                  }}
                />
                <ComboboxContent portalContainer={dialogContentRef}>
                  <ComboboxList>
                    <ComboboxEmpty>Контрагенты не найдены</ComboboxEmpty>
                    <ComboboxGroup>
                      {(companies.data ?? []).map((company) => (
                        <ComboboxItem key={company} value={company}>
                          {company}
                        </ComboboxItem>
                      ))}
                    </ComboboxGroup>
                  </ComboboxList>
                </ComboboxContent>
              </Combobox>
            </Field>
            <Field>
              <FieldLabel htmlFor="import-shipment-date">
                Дата отгрузки клиенту
              </FieldLabel>
              <Input
                id="import-shipment-date"
                type="date"
                value={shipmentDate}
                onChange={(event) => setShipmentDate(event.target.value)}
              />
            </Field>
            <Field>
              <FieldLabel htmlFor="import-return-date">
                Дата возврата
              </FieldLabel>
              <Input
                id="import-return-date"
                type="date"
                value={returnDate}
                onChange={(event) => setReturnDate(event.target.value)}
              />
            </Field>
            <Field className="sm:col-span-2">
              <FieldLabel htmlFor="import-return-driver">Водитель</FieldLabel>
              <Select value={driverId} onValueChange={setDriverId}>
                <SelectTrigger id="import-return-driver">
                  <SelectValue placeholder="Выберите водителя" />
                </SelectTrigger>
                <SelectContent>
                  <SelectGroup>
                    {drivers.map((driver) => (
                      <SelectItem key={driver.id} value={driver.id}>
                        {driver.name}
                      </SelectItem>
                    ))}
                  </SelectGroup>
                </SelectContent>
              </Select>
            </Field>
            <FieldSet className="sm:col-span-2">
              <FieldLegend>Паспорт бытовки</FieldLegend>
              <FieldGroup className="grid gap-4 sm:grid-cols-2 lg:grid-cols-4">
                <Field>
                  <FieldLabel>Тип</FieldLabel>
                  <Select
                    value={type}
                    onValueChange={(value) => {
                      const next = value as RentalItemCreationType
                      setType(next)
                      setDimensions(getDefaultRentalItemDimensions(next))
                    }}
                  >
                    <SelectTrigger>
                      <SelectValue />
                    </SelectTrigger>
                    <SelectContent>
                      <SelectGroup>
                        {RENTAL_ITEM_TYPE_OPTIONS.map((value) => (
                          <SelectItem key={value} value={value}>
                            {value}
                          </SelectItem>
                        ))}
                      </SelectGroup>
                    </SelectContent>
                  </Select>
                </Field>
                <Field>
                  <FieldLabel>Габариты</FieldLabel>
                  <Select
                    value={dimensions}
                    onValueChange={(value) =>
                      setDimensions(
                        value as ReturnType<
                          typeof getDefaultRentalItemDimensions
                        >
                      )
                    }
                  >
                    <SelectTrigger>
                      <SelectValue />
                    </SelectTrigger>
                    <SelectContent>
                      <SelectGroup>
                        {getRentalItemDimensionsForType(type).map((value) => (
                          <SelectItem key={value} value={value}>
                            {value}
                          </SelectItem>
                        ))}
                      </SelectGroup>
                    </SelectContent>
                  </Select>
                </Field>
                <Field>
                  <FieldLabel>Отделка</FieldLabel>
                  <Select
                    value={finishing}
                    onValueChange={(value) =>
                      setFinishing(value as RentalItemFinishing)
                    }
                  >
                    <SelectTrigger>
                      <SelectValue />
                    </SelectTrigger>
                    <SelectContent>
                      <SelectGroup>
                        {RENTAL_ITEM_FINISHING_OPTIONS.map((value) => (
                          <SelectItem key={value} value={value}>
                            {value}
                          </SelectItem>
                        ))}
                      </SelectGroup>
                    </SelectContent>
                  </Select>
                </Field>
                <Field>
                  <FieldLabel htmlFor="import-category">Категория</FieldLabel>
                  <Input
                    id="import-category"
                    value={category}
                    onChange={(event) => setCategory(event.target.value)}
                  />
                </Field>
              </FieldGroup>
              <div className="flex flex-wrap gap-3">
                {RENTAL_ITEM_CHARACTERISTIC_OPTIONS.map((value) => (
                  <label
                    key={value}
                    className="flex items-center gap-2 text-sm"
                  >
                    <Checkbox
                      checked={characteristics.includes(value)}
                      onCheckedChange={(checked) =>
                        setCharacteristics((current) =>
                          checked
                            ? [...current, value]
                            : current.filter((item) => item !== value)
                        )
                      }
                    />
                    {value}
                  </label>
                ))}
              </div>
              <label className="flex items-center gap-2 text-sm">
                <Checkbox
                  checked={linoleum}
                  onCheckedChange={(checked) => setLinoleum(checked === true)}
                />
                Линолеум
              </label>
            </FieldSet>
            <FieldSet className="sm:col-span-2">
              <FieldLegend>Мебель по заданию</FieldLegend>
              <FieldDescription>
                Снимок последней отгрузки; если его нет — текущее наполнение
                бытовки.
              </FieldDescription>
              <LogisticsCabinContentsEditor
                contents={expectedContents}
                catalog={catalog.data ?? []}
                frozen={mutation.isPending}
                mode="return"
                onUpdate={(updater) =>
                  setExpectedContents((current) => updater(current))
                }
              />
              <Field>
                <FieldLabel htmlFor="expected-reason">
                  Причина ручной корректировки
                </FieldLabel>
                <Input
                  id="expected-reason"
                  value={expectedReason}
                  onChange={(event) => setExpectedReason(event.target.value)}
                  placeholder="Обязательно, если состав изменён"
                />
              </Field>
            </FieldSet>
            <FieldSet className="sm:col-span-2">
              <FieldLegend>Фактическое наполнение</FieldLegend>
              <LogisticsCabinContentsEditor
                contents={returnedContents}
                catalog={catalog.data ?? []}
                frozen={mutation.isPending}
                mode="return"
                onUpdate={(updater) =>
                  setReturnedContents((current) => updater(current))
                }
              />
            </FieldSet>
            <div className="sm:col-span-2">
              <RentalItemPhotoUploader
                photos={photos}
                disabled={mutation.isPending}
                onChange={setPhotos}
              />
            </div>
          </FieldGroup>
          {error ? <FieldError>{error}</FieldError> : null}
          <DialogFooter>
            <Button
              variant="outline"
              onClick={() => onOpenChange(false)}
              disabled={mutation.isPending}
            >
              Отмена
            </Button>
            <Button
              onClick={submit}
              disabled={mutation.isPending || writtenOff}
            >
              <HugeiconsIcon icon={CheckIcon} data-icon="inline-start" />
              {otherWarehouse
                ? "Зафиксировать конфликт"
                : "Зарегистрировать возврат"}
            </Button>
          </DialogFooter>
        </DialogContent>
      </Dialog>
      <AlertDialog open={confirmationOpen} onOpenChange={setConfirmationOpen}>
        <AlertDialogContent>
          <AlertDialogHeader>
            <AlertDialogTitle>Подтвердить изменения бытовки?</AlertDialogTitle>
            <AlertDialogDescription>
              Текущий паспорт и наполнение сохранятся в снимке возврата.
              Подтверждённые паспортные изменения попадут в аудит, а бытовка
              перейдёт в ожидание осмотра.
            </AlertDialogDescription>
          </AlertDialogHeader>
          <AlertDialogFooter>
            <AlertDialogCancel>Отмена</AlertDialogCancel>
            <AlertDialogAction onClick={() => mutation.mutate(true)}>
              Продолжить
            </AlertDialogAction>
          </AlertDialogFooter>
        </AlertDialogContent>
      </AlertDialog>
    </>
  )
}
