import { useMemo, useRef, useState } from "react"
import { useMutation, useQuery } from "@tanstack/react-query"
import {
  Add01Icon,
  Calendar03Icon,
  CheckmarkCircle02Icon,
  Image01Icon,
  Loading03Icon,
  MinusSignIcon,
} from "@hugeicons/core-free-icons"
import { HugeiconsIcon } from "@hugeicons/react"
import { ru } from "date-fns/locale"
import { useParams } from "react-router-dom"

import { PhotoCarousel } from "@/components/media/photo-carousel"
import { Alert, AlertDescription, AlertTitle } from "@/components/ui/alert"
import { Badge } from "@/components/ui/badge"
import { Button } from "@/components/ui/button"
import { Calendar } from "@/components/ui/calendar"
import { Checkbox } from "@/components/ui/checkbox"
import {
  Card,
  CardContent,
  CardDescription,
  CardHeader,
  CardTitle,
} from "@/components/ui/card"
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
  confirmPublicPresentation,
  getPublicPresentation,
  getPublicPresentationBooking,
  type PresentationBooking,
  type PresentationCabin,
  type PresentationEquipmentAvailability,
} from "@/features/assistant/api/rental-presentations-api"
import {
  desiredQuantity,
  equipmentCapacityForCabin,
  presentationDraftIssues,
  selectedCabins,
  type FurnitureDraft,
} from "@/features/assistant/pages/public-client-presentation-draft"
import { AdditionalContactsFields } from "@/features/clients/components/additional-contacts-fields"
import {
  parseAdditionalContacts,
  type AdditionalContact,
} from "@/features/clients/domain/clients"
import { OrderCommandIdentityRegistry } from "@/features/orders/api/order-command-identity"
import type { DesiredDeliveryWindow } from "@/features/orders/domain/orders"
import { ApiError } from "@/lib/api-client"
import { cn } from "@/lib/utils"

export function PublicClientPresentationPage() {
  const { token = "" } = useParams()
  const [selectedIds, setSelectedIds] = useState<string[]>([])
  const [furnitureDraft, setFurnitureDraft] = useState<FurnitureDraft>({})
  const [furnitureCabinId, setFurnitureCabinId] = useState<string | null>(null)
  const [furnitureEquipmentId, setFurnitureEquipmentId] = useState("")
  const [normalStep, setNormalStep] = useState<"selection" | "details">(
    "selection"
  )
  const [desiredDates, setDesiredDates] = useState<Date[]>([])
  const [desiredDateError, setDesiredDateError] = useState<string | null>(null)
  const [rentalMonths, setRentalMonths] = useState(1)
  const [deliveryAddress, setDeliveryAddress] = useState("")
  const [coordinates, setCoordinates] = useState("")
  const [additionalContacts, setAdditionalContacts] = useState<
    AdditionalContact[]
  >([])
  const [selectionMessage, setSelectionMessage] = useState<string | null>(null)
  const [confirmOpen, setConfirmOpen] = useState(false)
  const [booking, setBooking] = useState<PresentationBooking | null>(null)
  const bookingCommand = useRef(new OrderCommandIdentityRegistry())
  const presentationQuery = useQuery({
    queryKey: ["public-client-presentation", token],
    queryFn: () => getPublicPresentation(token),
    retry: (count, error) =>
      !(error instanceof ApiError && [404, 410].includes(error.status)) &&
      count < 2,
    refetchInterval: (query) =>
      query.state.data?.viewOnly === true ? false : 15_000,
    refetchOnWindowFocus: "always",
    refetchOnReconnect: "always",
  })
  const bookingQuery = useQuery({
    queryKey: ["public-presentation-booking", token, booking?.bookingId],
    queryFn: () =>
      getPublicPresentationBooking({
        token,
        bookingId: booking!.bookingId,
      }),
    enabled: booking?.state === "PENDING",
    refetchInterval: (query) =>
      query.state.data?.state === "PENDING" ? 1_500 : false,
  })
  const effectiveBooking = bookingQuery.data ?? booking
  const presentation = presentationQuery.data
  const requestableDeliveryDates = useMemo(
    () =>
      Array.from(new Set(presentation?.requestableDeliveryDates ?? [])).sort(
        (left, right) => left.localeCompare(right)
      ),
    [presentation?.requestableDeliveryDates]
  )
  const requestableDeliveryDateSet = useMemo(
    () => new Set(requestableDeliveryDates),
    [requestableDeliveryDates]
  )
  const maxDesiredDeliveryDates = requestableDeliveryDates.length
  const requestableDesiredDates = useMemo(
    () =>
      desiredDates.filter((date) =>
        requestableDeliveryDateSet.has(calendarDateValue(date))
      ),
    [desiredDates, requestableDeliveryDateSet]
  )
  const draftIssues = presentation
    ? presentationDraftIssues({
        presentation,
        selectedIds,
        draft: furnitureDraft,
      })
    : []
  const desiredWindowInputs = useMemo<DesiredDeliveryWindow[]>(
    () =>
      [...requestableDesiredDates]
        .map(calendarDateValue)
        .sort((left, right) => left.localeCompare(right))
        .map((date) => ({ startDate: date, endDate: date })),
    [requestableDesiredDates]
  )
  const normalizedDeliveryAddress = deliveryAddress.trim()
  const parsedCoordinates = parseCoordinates(coordinates)
  const parsedAdditionalContacts = parseAdditionalContacts(additionalContacts)
  const requiredSelectionCount =
    presentation?.mode === "REPLACEMENT"
      ? presentation.requiredSelectionCount
      : null
  const selectionCountValid =
    requiredSelectionCount === null
      ? selectedIds.length > 0
      : selectedIds.length === requiredSelectionCount
  const normalPresentation = presentation?.mode === "NORMAL"
  const desiredDatesAreRequestable =
    desiredWindowInputs.length <= maxDesiredDeliveryDates &&
    desiredWindowInputs.every((window) =>
      requestableDeliveryDateSet.has(window.startDate)
    )

  const confirmMutation = useMutation({
    mutationFn: async () => {
      if (!presentation) throw new Error("Представление ещё не загружено.")
      if (presentation.mode === "NORMAL") {
        if (
          desiredWindowInputs.length === 0 ||
          !desiredDatesAreRequestable ||
          rentalMonths < 1 ||
          !normalizedDeliveryAddress ||
          parsedCoordinates.error ||
          !parsedAdditionalContacts.contacts
        ) {
          throw new Error(
            "Укажите дату, срок аренды, адрес и корректные дополнительные контакты."
          )
        }
      }
      const selections = selectedIds.map((rentalItemId) => ({
        rentalItemId,
        equipment:
          presentation.mode === "REPLACEMENT"
            ? []
            : presentation.equipmentAvailability.flatMap((item) => {
                const quantity = desiredQuantity(
                  furnitureDraft,
                  rentalItemId,
                  item.equipmentId
                )
                return quantity > 0
                  ? [{ equipmentId: item.equipmentId, quantity }]
                  : []
              }),
      }))
      const bookingPreferences =
        presentation.mode === "NORMAL"
          ? {
              desiredDeliveryWindows: desiredWindowInputs,
              rentalMonths,
              deliveryAddress: normalizedDeliveryAddress,
              ...(parsedCoordinates.latitude === null
                ? {}
                : {
                    latitude: parsedCoordinates.latitude,
                    longitude: parsedCoordinates.longitude,
                  }),
              additionalContacts: parsedAdditionalContacts.contacts!,
            }
          : {}
      const fingerprint = JSON.stringify({
        token,
        selections,
        ...bookingPreferences,
      })
      const value = await confirmPublicPresentation({
        token,
        selections,
        ...bookingPreferences,
        idempotencyKey: bookingCommand.current.keyFor(fingerprint),
      })
      return { fingerprint, value }
    },
    onSuccess: ({ fingerprint, value }) => {
      bookingCommand.current.confirm(fingerprint)
      setBooking(value)
      setConfirmOpen(false)
      if (value.state === "REJECTED") void presentationQuery.refetch()
    },
    onError: (error) => {
      if (error instanceof ApiError && error.status === 409) {
        void presentationQuery.refetch()
      }
    },
  })

  if (presentationQuery.isPending) {
    return <PublicState loading text="Открываем представление…" />
  }
  if (presentationQuery.isError) {
    const expired =
      presentationQuery.error instanceof ApiError &&
      presentationQuery.error.status === 410
    return (
      <PublicState
        text={
          expired
            ? "Срок просмотра этого представления истёк."
            : "Представление не найдено или ссылка больше не действует."
        }
      />
    )
  }
  if (!presentation) {
    return <PublicState text="Представление не содержит данных." />
  }

  const viewOnly = presentation.viewOnly === true
  const completed = effectiveBooking?.state === "COMPLETED"
  const rejected = effectiveBooking?.state === "REJECTED"
  const furnitureCabin = presentation.groups
    .flatMap((group) => group.cabins)
    .find((cabin) => cabin.id === furnitureCabinId)
  const furniturePositions = furnitureCabin
    ? presentation.equipmentAvailability.filter(
        (equipment) =>
          desiredQuantity(
            furnitureDraft,
            furnitureCabin.id,
            equipment.equipmentId
          ) > 0
      )
    : []
  const addableFurnitureEquipment = furnitureCabin
    ? presentation.equipmentAvailability.filter(
        (equipment) =>
          !furniturePositions.some(
            (position) => position.equipmentId === equipment.equipmentId
          )
      )
    : []
  const selectedFurnitureEquipment = addableFurnitureEquipment.find(
    (equipment) => equipment.equipmentId === furnitureEquipmentId
  )
  const selectedFurnitureCapacity =
    furnitureCabin && selectedFurnitureEquipment
      ? equipmentCapacityForCabin({
          presentation,
          selectedIds,
          draft: furnitureDraft,
          cabinId: furnitureCabin.id,
          equipmentId: selectedFurnitureEquipment.equipmentId,
        })
      : 0
  const selectedCabinList = selectedCabins(presentation, selectedIds)
  const normalDetailsStep = normalPresentation && normalStep === "details"
  const canAdvanceToDetails =
    !viewOnly &&
    !effectiveBooking &&
    selectionCountValid &&
    draftIssues.length === 0
  const canConfirm =
    !viewOnly &&
    !effectiveBooking &&
    selectionCountValid &&
    (!normalPresentation ||
      (normalDetailsStep &&
        desiredWindowInputs.length > 0 &&
        desiredDatesAreRequestable &&
        rentalMonths > 0 &&
        normalizedDeliveryAddress.length > 0 &&
        parsedCoordinates.error === null &&
        parsedAdditionalContacts.contacts !== null)) &&
    draftIssues.length === 0

  function toggleCabin(cabinId: string) {
    setSelectionMessage(null)
    if (selectedIds.includes(cabinId)) {
      setSelectedIds(selectedIds.filter((id) => id !== cabinId))
      setFurnitureDraft((current) => {
        const next = { ...current }
        delete next[cabinId]
        return next
      })
      return
    }
    if (
      requiredSelectionCount !== null &&
      selectedIds.length >= requiredSelectionCount
    ) {
      setSelectionMessage(
        `Для замены можно выбрать ровно ${requiredSelectionCount} бытовок.`
      )
      return
    }
    setSelectedIds([...selectedIds, cabinId])
  }

  function selectDesiredDates(nextDates: Date[] | undefined) {
    const uniqueRequestableDates = Array.from(
      new Map(
        (nextDates ?? []).map((date) => [calendarDateValue(date), date])
      ).values()
    )
      .filter((date) => requestableDeliveryDateSet.has(calendarDateValue(date)))
      .sort((left, right) =>
        calendarDateValue(left).localeCompare(calendarDateValue(right))
      )
    if (uniqueRequestableDates.length > maxDesiredDeliveryDates) {
      setDesiredDateError(
        `Можно выбрать не больше ${maxDesiredDeliveryDates} дней.`
      )
      return
    }
    setDesiredDateError(null)
    setDesiredDates(uniqueRequestableDates)
  }

  function addFurniturePosition(equipmentId: string) {
    const currentFurnitureCabin = furnitureCabin
    const currentPresentation = presentation
    if (!currentFurnitureCabin || !currentPresentation) return
    setFurnitureDraft((current) => {
      if (desiredQuantity(current, currentFurnitureCabin.id, equipmentId) > 0) {
        return current
      }
      const capacity = equipmentCapacityForCabin({
        presentation: currentPresentation,
        selectedIds,
        draft: current,
        cabinId: currentFurnitureCabin.id,
        equipmentId,
      })
      if (capacity < 1) return current
      return {
        ...current,
        [currentFurnitureCabin.id]: {
          ...current[currentFurnitureCabin.id],
          [equipmentId]: 1,
        },
      }
    })
    setFurnitureEquipmentId("")
  }

  function changeFurnitureQuantity(equipmentId: string, delta: number) {
    const currentFurnitureCabin = furnitureCabin
    const currentPresentation = presentation
    if (!currentFurnitureCabin || !currentPresentation) return
    setFurnitureDraft((current) => {
      const currentQuantity = desiredQuantity(
        current,
        currentFurnitureCabin.id,
        equipmentId
      )
      const capacity = equipmentCapacityForCabin({
        presentation: currentPresentation,
        selectedIds,
        draft: current,
        cabinId: currentFurnitureCabin.id,
        equipmentId,
      })
      const quantity = Math.max(0, Math.min(capacity, currentQuantity + delta))
      const cabinDraft = { ...current[currentFurnitureCabin.id] }
      if (quantity === 0) {
        delete cabinDraft[equipmentId]
      } else {
        cabinDraft[equipmentId] = quantity
      }
      const next = { ...current }
      if (Object.keys(cabinDraft).length === 0) {
        delete next[currentFurnitureCabin.id]
      } else {
        next[currentFurnitureCabin.id] = cabinDraft
      }
      return next
    })
  }

  return (
    <main className="h-svh overflow-y-auto bg-muted/30 text-foreground">
      <header className="sticky top-0 z-40 border-b bg-background/95 backdrop-blur">
        <div className="mx-auto flex max-w-6xl items-center justify-between gap-4 px-4 py-3 sm:px-6">
          <div>
            <p className="text-base font-bold tracking-tight">RWMS</p>
            <p className="text-xs text-muted-foreground">
              {presentation.mode === "REPLACEMENT"
                ? "Выбор замены бытовок"
                : "Подборка бытовок для аренды"}
            </p>
          </div>
          {presentation.mode === "REPLACEMENT" || normalStep === "selection" ? (
            <Select
              onValueChange={(value) =>
                document
                  .getElementById(`offer-group-${value}`)
                  ?.scrollIntoView({ behavior: "smooth", block: "start" })
              }
            >
              <SelectTrigger
                aria-label="Перейти к группе бытовок"
                className="w-48 sm:w-64"
              >
                <SelectValue placeholder="Перейти к группе" />
              </SelectTrigger>
              <SelectContent>
                <SelectGroup>
                  {presentation.groups.map((group) => (
                    <SelectItem key={group.key} value={group.key}>
                      {group.label}
                    </SelectItem>
                  ))}
                </SelectGroup>
              </SelectContent>
            </Select>
          ) : null}
        </div>
      </header>

      <div className="mx-auto max-w-6xl px-4 pt-8 pb-[calc(10rem+env(safe-area-inset-bottom))] sm:px-6">
        <div className="mb-8 flex flex-col gap-4 rounded-2xl border bg-background p-5 shadow-sm">
          <div className="flex flex-wrap items-start justify-between gap-4">
            <div>
              <h1 className="text-2xl font-semibold tracking-tight">
                {presentation.mode === "REPLACEMENT"
                  ? "Выберите бытовки на замену"
                  : "Доступные бытовки"}
              </h1>
              <p className="mt-2 max-w-2xl text-sm text-muted-foreground">
                {presentation.mode === "REPLACEMENT"
                  ? `Нужно выбрать ровно ${requiredSelectionCount ?? 0}. Порядок выбора соответствует порядку заменяемых бытовок.`
                  : "Выберите бытовки и при необходимости добавьте наполнение отдельно в каждую из них."}
              </p>
            </div>
            <Badge variant={viewOnly ? "outline" : "secondary"}>
              <HugeiconsIcon icon={Calendar03Icon} />
              {viewOnly
                ? "Только просмотр"
                : `Удержание до ${formatPublicDate(presentation.expiresAt)}`}
            </Badge>
          </div>

          {normalPresentation ? (
            <ol
              aria-label="Шаги оформления аренды"
              className="flex flex-wrap gap-2"
            >
              <li>
                <Badge
                  variant={normalStep === "selection" ? "secondary" : "outline"}
                >
                  1. Бытовки и наполнение
                </Badge>
              </li>
              <li>
                <Badge
                  variant={normalStep === "details" ? "secondary" : "outline"}
                >
                  2. Дата, срок и доставка
                </Badge>
              </li>
            </ol>
          ) : null}

          {presentation.mode === "REPLACEMENT" ? (
            <Alert>
              <AlertTitle>Наполнение останется в заказе</AlertTitle>
              <AlertDescription>
                Количества наполнения сохраняются. Если наполнение уже физически
                находится в старой бытовке, склад получит задание переместить
                его в выбранную замену.
              </AlertDescription>
            </Alert>
          ) : null}

          {presentation.mode === "REPLACEMENT" &&
          presentation.desiredDeliveryWindows.length > 0 ? (
            <Alert>
              <AlertTitle>Условия текущего заказа</AlertTitle>
              <AlertDescription>
                <ul className="mt-2 flex list-disc flex-col gap-1 pl-5">
                  {presentation.desiredDeliveryWindows.map((window, index) => (
                    <li key={`${window.startDate}:${window.endDate}:${index}`}>
                      {formatDesiredWindow(window)}
                    </li>
                  ))}
                </ul>
                Дата и срок аренды при замене не меняются.
              </AlertDescription>
            </Alert>
          ) : null}

          {selectionMessage ? (
            <Alert variant="destructive">
              <AlertTitle>Ограничение выбора</AlertTitle>
              <AlertDescription>{selectionMessage}</AlertDescription>
            </Alert>
          ) : null}
          {draftIssues.length > 0 ? (
            <Alert variant="destructive">
              <AlertTitle>Доступность мебели изменилась</AlertTitle>
              <AlertDescription>
                <ul className="flex list-disc flex-col gap-1 pl-5">
                  {draftIssues.map((issue) => (
                    <li key={issue}>{issue}</li>
                  ))}
                </ul>
                Скорректируйте количество. Подтверждение временно недоступно.
              </AlertDescription>
            </Alert>
          ) : null}
        </div>

        {normalDetailsStep ? (
          <section
            aria-label="Дата, срок и доставка"
            className="flex flex-col gap-6"
          >
            <Card>
              <CardHeader>
                <CardTitle>Выбранные бытовки и наполнение</CardTitle>
                <CardDescription>
                  Проверьте выбранные бытовки. Чтобы изменить выбор или
                  наполнение, вернитесь на предыдущий шаг.
                </CardDescription>
              </CardHeader>
              <CardContent>
                <ul className="flex flex-col gap-3">
                  {selectedCabinList.map((cabin) => {
                    const furniture =
                      presentation.equipmentAvailability.flatMap(
                        (equipment) => {
                          const quantity = desiredQuantity(
                            furnitureDraft,
                            cabin.id,
                            equipment.equipmentId
                          )
                          return quantity > 0
                            ? [`${equipment.equipmentName} — ${quantity}`]
                            : []
                        }
                      )
                    return (
                      <li
                        key={cabin.id}
                        className="flex flex-col gap-1 rounded-lg border p-3"
                      >
                        <span className="font-medium">
                          Бытовка {cabin.number}
                        </span>
                        <span className="text-sm text-muted-foreground">
                          {furniture.length > 0
                            ? `Добавлено: ${furniture.join(", ")}`
                            : "Дополнительное наполнение не выбрано."}
                        </span>
                      </li>
                    )
                  })}
                </ul>
              </CardContent>
            </Card>

            <FieldSet className="gap-5">
              <FieldLegend>Дата, срок и данные доставки</FieldLegend>
              <FieldDescription>
                Дата — пожелание для согласования с логистом. Срок начнёт
                считаться от фактической даты отгрузки.
              </FieldDescription>
              <FieldGroup className="gap-5">
                <Field
                  data-invalid={
                    requestableDesiredDates.length === 0 ||
                    Boolean(desiredDateError)
                      ? true
                      : undefined
                  }
                >
                  <FieldLabel>Желаемые даты получения</FieldLabel>
                  <div className="w-fit max-w-full overflow-x-auto rounded-lg border">
                    <Calendar
                      key={requestableDeliveryDates[0] ?? "no-dates"}
                      mode="multiple"
                      locale={ru}
                      defaultMonth={
                        requestableDeliveryDates[0]
                          ? new Date(`${requestableDeliveryDates[0]}T12:00:00`)
                          : undefined
                      }
                      selected={requestableDesiredDates}
                      disabled={(date) =>
                        viewOnly ||
                        Boolean(effectiveBooking) ||
                        !requestableDeliveryDateSet.has(
                          calendarDateValue(date)
                        ) ||
                        (requestableDesiredDates.length >=
                          maxDesiredDeliveryDates &&
                          !requestableDesiredDates.some(
                            (selectedDate) =>
                              calendarDateValue(selectedDate) ===
                              calendarDateValue(date)
                          ))
                      }
                      aria-label="Календарь выбора желаемой даты получения"
                      aria-invalid={requestableDesiredDates.length === 0}
                      onSelect={selectDesiredDates}
                    />
                  </div>
                  <FieldDescription>
                    {requestableDeliveryDates.length > 0
                      ? `Доступны для запроса и согласования: ${requestableDeliveryDates
                          .map(formatCalendarDate)
                          .join(", ")}`
                      : "Сейчас нет дат, доступных для запроса. Обратитесь к менеджеру за новым предложением."}
                  </FieldDescription>
                  <FieldDescription>
                    Выбор даты не резервирует логистическую мощность до
                    согласования с логистом.
                  </FieldDescription>
                  <FieldDescription>
                    {`Выбрано дней: ${requestableDesiredDates.length} из ${maxDesiredDeliveryDates}.`}
                  </FieldDescription>
                  {requestableDesiredDates.length > 0 ? (
                    <FieldDescription>
                      {desiredWindowInputs
                        .map((window) => formatCalendarDate(window.startDate))
                        .join(", ")}
                    </FieldDescription>
                  ) : null}
                  {desiredDateError ? (
                    <FieldError>{desiredDateError}</FieldError>
                  ) : null}
                </Field>

                <Field>
                  <FieldLabel id="public-presentation-rental-months-label">
                    Срок аренды
                  </FieldLabel>
                  <div
                    role="group"
                    aria-labelledby="public-presentation-rental-months-label"
                    className="flex w-fit items-center gap-2"
                  >
                    <Button
                      type="button"
                      size="icon-sm"
                      variant="outline"
                      disabled={
                        viewOnly ||
                        Boolean(effectiveBooking) ||
                        rentalMonths <= 1
                      }
                      aria-label="Уменьшить срок аренды"
                      onClick={() => setRentalMonths(rentalMonths - 1)}
                    >
                      <HugeiconsIcon icon={MinusSignIcon} />
                    </Button>
                    <output
                      aria-live="polite"
                      className="min-w-20 text-center text-sm font-medium tabular-nums"
                    >
                      {formatRentalMonths(rentalMonths)}
                    </output>
                    <Button
                      type="button"
                      size="icon-sm"
                      variant="outline"
                      disabled={viewOnly || Boolean(effectiveBooking)}
                      aria-label="Увеличить срок аренды"
                      onClick={() => setRentalMonths(rentalMonths + 1)}
                    >
                      <HugeiconsIcon icon={Add01Icon} />
                    </Button>
                  </div>
                  <FieldDescription>
                    После назначения отгрузки система автоматически рассчитает
                    дату возврата для выбранных бытовок.
                  </FieldDescription>
                </Field>

                <Field data-invalid={!normalizedDeliveryAddress || undefined}>
                  <FieldLabel htmlFor="public-presentation-delivery-address">
                    Адрес доставки
                  </FieldLabel>
                  <Input
                    id="public-presentation-delivery-address"
                    value={deliveryAddress}
                    required
                    disabled={viewOnly || Boolean(effectiveBooking)}
                    maxLength={1_000}
                    aria-invalid={!normalizedDeliveryAddress}
                    autoComplete="street-address"
                    placeholder="Город, улица, дом, ориентир"
                    onChange={(event) => setDeliveryAddress(event.target.value)}
                  />
                  {!normalizedDeliveryAddress ? (
                    <FieldError>Укажите адрес доставки.</FieldError>
                  ) : null}
                </Field>

                <Field
                  data-invalid={Boolean(parsedCoordinates.error) || undefined}
                >
                  <FieldLabel htmlFor="public-presentation-coordinates">
                    Координаты
                  </FieldLabel>
                  <Input
                    id="public-presentation-coordinates"
                    value={coordinates}
                    disabled={viewOnly || Boolean(effectiveBooking)}
                    maxLength={64}
                    aria-invalid={Boolean(parsedCoordinates.error)}
                    autoComplete="off"
                    placeholder="55.75, 37.61"
                    onChange={(event) => setCoordinates(event.target.value)}
                  />
                  {parsedCoordinates.error ? (
                    <FieldError>{parsedCoordinates.error}</FieldError>
                  ) : (
                    <FieldDescription>
                      Необязательно. Укажите широту и долготу через запятую.
                    </FieldDescription>
                  )}
                </Field>

                <AdditionalContactsFields
                  idPrefix="public-presentation"
                  value={additionalContacts}
                  errors={
                    parsedAdditionalContacts.contacts === null
                      ? parsedAdditionalContacts.errors
                      : []
                  }
                  disabled={viewOnly || Boolean(effectiveBooking)}
                  ownerLabel="заказа"
                  onChange={setAdditionalContacts}
                />
              </FieldGroup>
            </FieldSet>
          </section>
        ) : (
          <div className="flex flex-col gap-12">
            {presentation.groups.map((group) => (
              <section
                id={`offer-group-${group.key}`}
                key={group.key}
                className="scroll-mt-24"
              >
                <div className="mb-4 flex items-end justify-between gap-4">
                  <div>
                    <h2 className="text-xl font-semibold">{group.label}</h2>
                    <p className="text-sm text-muted-foreground">
                      {group.cabins.length}{" "}
                      {formatCabinCount(group.cabins.length)}
                    </p>
                  </div>
                </div>
                <div className="flex flex-col gap-5">
                  {group.cabins.map((cabin) => (
                    <PublicCabinCard
                      key={cabin.id}
                      cabin={cabin}
                      selected={selectedIds.includes(cabin.id)}
                      furniture={furnitureDraft[cabin.id] ?? {}}
                      equipmentAvailability={presentation.equipmentAvailability}
                      disabled={viewOnly || Boolean(effectiveBooking)}
                      furnitureDisabled={presentation.mode === "REPLACEMENT"}
                      onToggle={() => toggleCabin(cabin.id)}
                      onAddFurniture={() => {
                        setFurnitureEquipmentId("")
                        setFurnitureCabinId(cabin.id)
                      }}
                    />
                  ))}
                </div>
              </section>
            ))}
          </div>
        )}
      </div>

      <div className="sticky bottom-0 z-40 border-t bg-background/95 px-4 pt-3 pb-[calc(0.75rem+env(safe-area-inset-bottom))] backdrop-blur">
        <div className="mx-auto flex max-w-6xl items-center justify-between gap-4">
          <div className="text-sm">
            {completed ? (
              <span className="inline-flex items-center gap-2 font-medium text-emerald-700">
                <HugeiconsIcon icon={CheckmarkCircle02Icon} />
                Выбор отправлен менеджеру
              </span>
            ) : rejected ? (
              <span className="text-destructive">
                Выбор больше нельзя подтвердить. Попросите менеджера отправить
                обновлённую ссылку.
              </span>
            ) : effectiveBooking?.state === "PENDING" ? (
              <span className="inline-flex items-center gap-2">
                <HugeiconsIcon icon={Loading03Icon} className="animate-spin" />
                Создаём бронирование…
              </span>
            ) : viewOnly ? (
              <span className="text-muted-foreground">
                Срок удержания истёк — представление доступно только для
                просмотра.
              </span>
            ) : requiredSelectionCount === null ? (
              <>
                Выбрано: <strong>{selectedIds.length}</strong>
              </>
            ) : (
              <>
                Выбрано: <strong>{selectedIds.length}</strong> из{" "}
                <strong>{requiredSelectionCount}</strong>
              </>
            )}
          </div>
          {!viewOnly && !effectiveBooking ? (
            normalPresentation ? (
              normalDetailsStep ? (
                <div className="flex flex-wrap justify-end gap-2">
                  <Button
                    type="button"
                    variant="outline"
                    onClick={() => setNormalStep("selection")}
                  >
                    Назад к выбору
                  </Button>
                  <Button
                    type="button"
                    disabled={!canConfirm}
                    onClick={() => setConfirmOpen(true)}
                  >
                    Подтвердить выбор
                  </Button>
                </div>
              ) : (
                <Button
                  type="button"
                  disabled={!canAdvanceToDetails}
                  onClick={() => setNormalStep("details")}
                >
                  Далее: дата, срок и доставка
                </Button>
              )
            ) : (
              <Button
                type="button"
                disabled={!canConfirm}
                onClick={() => setConfirmOpen(true)}
              >
                Подтвердить выбор
              </Button>
            )
          ) : null}
        </div>
      </div>

      <Dialog
        open={Boolean(furnitureCabin)}
        onOpenChange={(open) => {
          if (!open) {
            setFurnitureEquipmentId("")
            setFurnitureCabinId(null)
          }
        }}
      >
        <DialogContent className="max-h-[calc(100svh-2rem)] overflow-y-auto sm:max-w-xl">
          <DialogHeader>
            <DialogTitle>
              Добавить наполнение в бытовку {furnitureCabin?.number ?? ""}
            </DialogTitle>
            <DialogDescription>
              «Доступно» учитывает общий остаток, наполнение в выбранных
              бытовках и ограничение для одной бытовки.
            </DialogDescription>
          </DialogHeader>
          {furnitureCabin ? (
            <FieldGroup>
              {presentation.equipmentAvailability.length === 0 ? (
                <FieldDescription>
                  Доступного наполнения сейчас нет.
                </FieldDescription>
              ) : (
                <>
                  <Field>
                    <FieldLabel htmlFor="public-presentation-furniture-type">
                      Тип наполнения
                    </FieldLabel>
                    <Select
                      value={furnitureEquipmentId}
                      onValueChange={setFurnitureEquipmentId}
                    >
                      <SelectTrigger
                        id="public-presentation-furniture-type"
                        className="w-full"
                      >
                        <SelectValue placeholder="Выберите тип наполнения" />
                      </SelectTrigger>
                      <SelectContent>
                        <SelectGroup>
                          {addableFurnitureEquipment.map((equipment) => {
                            const capacity = equipmentCapacityForCabin({
                              presentation,
                              selectedIds,
                              draft: furnitureDraft,
                              cabinId: furnitureCabin.id,
                              equipmentId: equipment.equipmentId,
                            })
                            return (
                              <SelectItem
                                key={equipment.equipmentId}
                                value={equipment.equipmentId}
                                disabled={capacity < 1}
                              >
                                {equipment.equipmentName}: доступно — {capacity}
                              </SelectItem>
                            )
                          })}
                        </SelectGroup>
                      </SelectContent>
                    </Select>
                    {addableFurnitureEquipment.length === 0 ? (
                      <FieldDescription>
                        Все доступные позиции уже добавлены.
                      </FieldDescription>
                    ) : null}
                  </Field>
                  <Button
                    type="button"
                    size="sm"
                    variant="outline"
                    disabled={
                      !selectedFurnitureEquipment ||
                      selectedFurnitureCapacity < 1
                    }
                    onClick={() => {
                      if (selectedFurnitureEquipment) {
                        addFurniturePosition(
                          selectedFurnitureEquipment.equipmentId
                        )
                      }
                    }}
                  >
                    <HugeiconsIcon icon={Add01Icon} data-icon="inline-start" />
                    Добавить наполнение
                  </Button>

                  <FieldSet className="gap-3">
                    <FieldLegend variant="label">
                      Добавленные позиции
                    </FieldLegend>
                    {furniturePositions.length === 0 ? (
                      <FieldDescription>
                        Наполнение пока не выбрано.
                      </FieldDescription>
                    ) : (
                      <FieldGroup className="gap-3">
                        {furniturePositions.map((equipment) => {
                          const capacity = equipmentCapacityForCabin({
                            presentation,
                            selectedIds,
                            draft: furnitureDraft,
                            cabinId: furnitureCabin.id,
                            equipmentId: equipment.equipmentId,
                          })
                          const quantity = desiredQuantity(
                            furnitureDraft,
                            furnitureCabin.id,
                            equipment.equipmentId
                          )
                          return (
                            <Field
                              key={equipment.equipmentId}
                              className="rounded-lg border p-3"
                            >
                              <div className="flex min-w-0 flex-wrap items-center justify-between gap-3">
                                <div className="min-w-0">
                                  <p
                                    className="truncate text-sm font-medium"
                                    title={equipment.equipmentName}
                                  >
                                    {equipment.equipmentName}
                                  </p>
                                  <FieldDescription className="text-xs">
                                    Доступно: {capacity} шт.
                                  </FieldDescription>
                                </div>
                                <div
                                  role="group"
                                  aria-label={`Количество наполнения: ${equipment.equipmentName}`}
                                  className="flex items-center gap-2"
                                >
                                  <Button
                                    type="button"
                                    size="icon-sm"
                                    variant="outline"
                                    aria-label={`Уменьшить количество: ${equipment.equipmentName}`}
                                    onClick={() =>
                                      changeFurnitureQuantity(
                                        equipment.equipmentId,
                                        -1
                                      )
                                    }
                                  >
                                    <HugeiconsIcon icon={MinusSignIcon} />
                                  </Button>
                                  <output
                                    aria-label={`Количество: ${equipment.equipmentName}`}
                                    className="min-w-8 text-center text-sm font-medium tabular-nums"
                                  >
                                    {quantity}
                                  </output>
                                  <Button
                                    type="button"
                                    size="icon-sm"
                                    variant="outline"
                                    disabled={quantity >= capacity}
                                    aria-label={`Увеличить количество: ${equipment.equipmentName}`}
                                    onClick={() =>
                                      changeFurnitureQuantity(
                                        equipment.equipmentId,
                                        1
                                      )
                                    }
                                  >
                                    <HugeiconsIcon icon={Add01Icon} />
                                  </Button>
                                </div>
                              </div>
                            </Field>
                          )
                        })}
                      </FieldGroup>
                    )}
                  </FieldSet>
                </>
              )}
            </FieldGroup>
          ) : null}
          <DialogFooter>
            <Button
              type="button"
              onClick={() => {
                setFurnitureEquipmentId("")
                setFurnitureCabinId(null)
              }}
            >
              Готово
            </Button>
          </DialogFooter>
        </DialogContent>
      </Dialog>

      <Dialog open={confirmOpen} onOpenChange={setConfirmOpen}>
        <DialogContent>
          <DialogHeader>
            <DialogTitle>Подтвердить выбор?</DialogTitle>
            <DialogDescription>
              {presentation.mode === "REPLACEMENT"
                ? `Выбранные ${selectedIds.length} бытовки заменят недоступные в текущем заказе в указанном порядке.`
                : `В текущий заказ попадут выбранные бытовки (${selectedIds.length}) и наполнение по каждой из них.`}
            </DialogDescription>
          </DialogHeader>
          {confirmMutation.isError ? (
            <Alert variant="destructive">
              <AlertTitle>Подтверждение не выполнено</AlertTitle>
              <AlertDescription>
                {confirmMutation.error instanceof Error
                  ? confirmMutation.error.message
                  : "Не удалось подтвердить выбор."}
              </AlertDescription>
            </Alert>
          ) : null}
          <DialogFooter>
            <Button
              type="button"
              variant="outline"
              onClick={() => setConfirmOpen(false)}
            >
              Вернуться
            </Button>
            <Button
              type="button"
              disabled={confirmMutation.isPending || !canConfirm}
              onClick={() => confirmMutation.mutate()}
            >
              {confirmMutation.isPending ? (
                <HugeiconsIcon icon={Loading03Icon} className="animate-spin" />
              ) : null}
              Подтвердить
            </Button>
          </DialogFooter>
        </DialogContent>
      </Dialog>
    </main>
  )
}

function PublicCabinCard({
  cabin,
  selected,
  furniture,
  equipmentAvailability,
  disabled,
  furnitureDisabled,
  onToggle,
  onAddFurniture,
}: {
  cabin: PresentationCabin
  selected: boolean
  furniture: Record<string, number>
  equipmentAvailability: PresentationEquipmentAvailability[]
  disabled: boolean
  furnitureDisabled: boolean
  onToggle: () => void
  onAddFurniture: () => void
}) {
  const passport = useMemo(
    () =>
      Object.entries(cabin.passport ?? {}).filter(
        ([key, value]) =>
          !/^legacy/i.test(key) &&
          !/^source$/i.test(key) &&
          !/^(locationNodeId|hasPhotos|photoCount|mainPhotoUrl|previewPhotoUrls)$/i.test(
            key
          ) &&
          !/(author|audit|action|created|updated|version|internal)/i.test(
            key
          ) &&
          ["string", "number", "boolean"].includes(typeof value)
      ),
    [cabin.passport]
  )
  const photos = cabin.photos.map((photo) => ({
    id: photo.mediaId,
    url: photo.thumbnailUrl,
    variants: {
      small: { url: photo.thumbnailUrl },
      medium: { url: photo.contentUrl },
      large: { url: photo.contentUrl },
    },
  }))
  const selectedFurniture = equipmentAvailability.flatMap((equipment) => {
    const quantity = furniture[equipment.equipmentId] ?? 0
    return quantity > 0 ? [`${equipment.equipmentName} — ${quantity}`] : []
  })
  const selectionCheckboxId = `public-presentation-cabin-${cabin.id}`

  return (
    <article
      className={cn(
        "overflow-hidden rounded-2xl border bg-background shadow-sm transition sm:grid sm:grid-cols-[minmax(0,1.15fr)_minmax(18rem,0.85fr)]",
        selected && "border-primary ring-2 ring-primary/15"
      )}
    >
      <PhotoCarousel
        photos={photos}
        title={`Бытовка ${cabin.number}`}
        className="aspect-[4/3] rounded-none sm:aspect-auto sm:min-h-80"
        imageClassName="h-full"
        fullscreenQuality="original"
        controlsVisibility="mobile-visible"
        placeholder={
          <div className="flex size-full min-h-72 items-center justify-center bg-muted text-muted-foreground">
            <HugeiconsIcon icon={Image01Icon} className="size-10" />
          </div>
        }
      />
      <div className="flex flex-col p-5 sm:p-6">
        <div className="flex items-start justify-between gap-4">
          <div>
            <p className="text-xs font-medium tracking-wide text-primary uppercase">
              {cabin.rentalType ?? "Бытовка"}
            </p>
            <h3 className="mt-1 text-xl font-semibold">
              Бытовка {cabin.number}
            </h3>
          </div>
          <div className="flex flex-wrap items-center justify-end gap-2">
            <Field
              orientation="horizontal"
              data-disabled={disabled || undefined}
              className="w-auto gap-2"
            >
              <Checkbox
                id={selectionCheckboxId}
                checked={selected}
                disabled={disabled}
                onCheckedChange={onToggle}
              />
              <FieldContent>
                <FieldLabel htmlFor={selectionCheckboxId}>
                  Выбрать бытовку {cabin.number}
                </FieldLabel>
              </FieldContent>
            </Field>
            {selected && !furnitureDisabled ? (
              <Button
                type="button"
                size="sm"
                variant="outline"
                disabled={disabled}
                onClick={onAddFurniture}
              >
                <HugeiconsIcon icon={Add01Icon} data-icon="inline-start" />
                Добавить наполнение
              </Button>
            ) : null}
          </div>
        </div>
        <dl className="mt-6 grid grid-cols-2 gap-x-5 gap-y-4 text-sm">
          <Characteristic label="Габариты" value={cabin.dimensions} />
          <Characteristic label="Отделка" value={cabin.finishing} />
          <Characteristic label="Категория" value={cabin.category} />
          <Characteristic
            label="Линолеум"
            value={
              cabin.linoleum === null ? null : cabin.linoleum ? "Да" : "Нет"
            }
          />
          {passport.map(([key, value]) => (
            <Characteristic key={key} label={key} value={String(value)} />
          ))}
        </dl>
        {cabin.characteristics ? (
          <div className="mt-6 border-t pt-4">
            <p className="text-xs font-medium text-muted-foreground">
              Дополнительные характеристики
            </p>
            <p className="mt-2 text-sm whitespace-pre-wrap">
              {cabin.characteristics}
            </p>
          </div>
        ) : null}
        {selectedFurniture.length > 0 ? (
          <div className="mt-5 flex flex-col gap-2">
            <p className="text-xs font-medium text-muted-foreground">
              Добавленное наполнение
            </p>
            <div className="flex flex-wrap gap-2">
              {selectedFurniture.map((label) => (
                <Badge key={label} variant="secondary">
                  {label}
                </Badge>
              ))}
            </div>
          </div>
        ) : null}
        {cabin.currentContents.length > 0 ? (
          <div className="mt-5 flex flex-col gap-2">
            <p className="text-xs font-medium text-muted-foreground">
              Уже находится в бытовке
            </p>
            <div className="flex flex-wrap gap-2">
              {cabin.currentContents.map((item) => (
                <Badge key={item.equipmentId} variant="outline">
                  {item.equipmentName ?? "Наполнение"} — {item.quantity}
                </Badge>
              ))}
            </div>
          </div>
        ) : null}
        {cabin.tags.length > 0 ? (
          <div className="mt-auto flex flex-wrap gap-2 pt-5">
            {cabin.tags.map((tag) => (
              <Badge key={tag} variant="outline">
                {tag}
              </Badge>
            ))}
          </div>
        ) : null}
      </div>
    </article>
  )
}

function Characteristic({
  label,
  value,
}: {
  label: string
  value: string | null
}) {
  if (!value) return null
  return (
    <div>
      <dt className="text-xs text-muted-foreground">{label}</dt>
      <dd className="mt-1 font-medium">{value}</dd>
    </div>
  )
}

function PublicState({
  text,
  loading = false,
}: {
  text: string
  loading?: boolean
}) {
  return (
    <main className="flex min-h-svh flex-col items-center justify-center gap-4 bg-muted/30 p-6 text-center">
      <div className="flex size-12 items-center justify-center rounded-2xl bg-background shadow-sm">
        <HugeiconsIcon
          icon={loading ? Loading03Icon : Calendar03Icon}
          className={cn("size-6", loading && "animate-spin")}
        />
      </div>
      <p className="max-w-md text-sm text-muted-foreground">{text}</p>
    </main>
  )
}

function calendarDateValue(value: Date) {
  const year = value.getFullYear()
  const month = String(value.getMonth() + 1).padStart(2, "0")
  const day = String(value.getDate()).padStart(2, "0")
  return `${year}-${month}-${day}`
}

function formatCalendarDate(value: string) {
  const [year, month, day] = value.split("-").map(Number)
  const date = new Date(year, month - 1, day)
  return Number.isNaN(date.getTime())
    ? value
    : new Intl.DateTimeFormat("ru-RU", { dateStyle: "medium" }).format(date)
}

function parseCoordinates(value: string) {
  const normalized = value.trim()
  if (!normalized) {
    return { latitude: null, longitude: null, error: null }
  }
  const pair =
    /^([+-]?(?:\d+(?:\.\d*)?|\.\d+))\s*,\s*([+-]?(?:\d+(?:\.\d*)?|\.\d+))$/.exec(
      normalized
    )
  if (!pair) {
    return {
      latitude: null,
      longitude: null,
      error: "Укажите широту и долготу в формате «55.75, 37.61».",
    }
  }
  const latitude = Number(pair[1])
  const longitude = Number(pair[2])
  if (!Number.isFinite(latitude) || latitude < -90 || latitude > 90) {
    return {
      latitude: null,
      longitude: null,
      error: "Широта должна быть числом от −90 до 90.",
    }
  }
  if (!Number.isFinite(longitude) || longitude < -180 || longitude > 180) {
    return {
      latitude: null,
      longitude: null,
      error: "Долгота должна быть числом от −180 до 180.",
    }
  }
  return { latitude, longitude, error: null }
}

function formatRentalMonths(value: number) {
  const lastTwo = value % 100
  const last = value % 10
  if (lastTwo >= 11 && lastTwo <= 14) return `${value} месяцев`
  if (last === 1) return `${value} месяц`
  if ([2, 3, 4].includes(last)) return `${value} месяца`
  return `${value} месяцев`
}

function formatPublicDate(value: string) {
  const date = new Date(value)
  return Number.isNaN(date.getTime())
    ? value
    : new Intl.DateTimeFormat("ru-RU", { dateStyle: "medium" }).format(date)
}

function formatDesiredWindow(window: DesiredDeliveryWindow) {
  const dates =
    window.startDate === window.endDate
      ? window.startDate
      : `${window.startDate} — ${window.endDate}`
  return dates
}

function formatCabinCount(count: number) {
  const last = count % 10
  const lastTwo = count % 100
  if (last === 1 && lastTwo !== 11) return "бытовка"
  if ([2, 3, 4].includes(last) && ![12, 13, 14].includes(lastTwo)) {
    return "бытовки"
  }
  return "бытовок"
}
