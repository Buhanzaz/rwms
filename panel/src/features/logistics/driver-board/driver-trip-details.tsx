import { useState } from "react"
import { useQuery } from "@tanstack/react-query"

import { Alert, AlertDescription, AlertTitle } from "@/components/ui/alert"
import { Badge } from "@/components/ui/badge"
import { Button } from "@/components/ui/button"
import { Card, CardContent, CardHeader, CardTitle } from "@/components/ui/card"
import {
  Dialog,
  DialogContent,
  DialogDescription,
  DialogHeader,
  DialogTitle,
} from "@/components/ui/dialog"
import { Separator } from "@/components/ui/separator"
import { useAuth } from "@/features/auth/use-auth"
import {
  driverTaskQueryKey,
  getDriverTask,
} from "@/features/logistics/driver-board/driver-board-api"
import type {
  DriverBoardCard,
  DriverTripActualEquipment,
  DriverTripDesiredEquipment,
  DriverTripDetails,
} from "@/features/logistics/driver-board/driver-board-model"

function formatDate(value: string) {
  return new Intl.DateTimeFormat("ru-RU", { dateStyle: "medium" }).format(
    new Date(`${value}T00:00:00`)
  )
}

function ActualEquipmentList({
  items,
}: {
  items: readonly DriverTripActualEquipment[]
}) {
  const visibleItems = items.filter(
    (item) => Number.isFinite(item.quantity) && item.quantity > 0
  )
  if (visibleItems.length === 0) return null

  return (
    <ul className="flex flex-col gap-1 text-sm">
      {visibleItems.map((item, index) => (
        <li key={`${item.equipmentId}:${item.locationKind}:${index}`}>
          {item.equipmentName?.trim() || "Оборудование"}: {item.quantity}шт
        </li>
      ))}
    </ul>
  )
}

/** One final, user-facing state of a cabin's physical filling. */
type CabinContentStatus =
  "empty" | "awaiting-filling" | "awaiting-removal" | "ready"

const CABIN_CONTENT_STATUS_PRESENTATION: Record<
  CabinContentStatus,
  { label: string; className: string }
> = {
  empty: {
    label: "Нет наполнения",
    className: "border-border bg-muted text-muted-foreground",
  },
  "awaiting-filling": {
    label: "Ожидает наполнения",
    className:
      "border-[color:var(--status-repair-fg)] bg-[var(--status-repair-bg)] text-[var(--status-repair-fg)]",
  },
  "awaiting-removal": {
    label: "Ожидает выноса наполнения",
    className:
      "border-[color:var(--acceptance-time-warning-fg)] bg-[var(--acceptance-time-warning-bg)] text-[var(--acceptance-time-warning-fg)]",
  },
  ready: {
    label: "Наполнение готово",
    className:
      "border-[color:var(--status-free-fg)] bg-[var(--status-free-bg)] text-[var(--status-free-fg)]",
  },
}

function quantitiesByEquipment(
  items: readonly (DriverTripDesiredEquipment | DriverTripActualEquipment)[]
) {
  const quantities = new Map<string, number>()

  items.forEach((item, index) => {
    if (!Number.isFinite(item.quantity) || item.quantity <= 0) return
    const equipmentId = item.equipmentId.trim()
    const key = equipmentId || `unidentified:${index}`
    quantities.set(key, (quantities.get(key) ?? 0) + item.quantity)
  })

  return quantities
}

function contentsMatch(
  desired: ReadonlyMap<string, number>,
  actual: ReadonlyMap<string, number>
) {
  return (
    desired.size === actual.size &&
    [...desired].every(
      ([equipmentId, quantity]) => actual.get(equipmentId) === quantity
    )
  )
}

/**
 * Applies the logistics projection's readiness rules without using its legacy
 * summary flag: unavailable facts are never presented as ready.
 */
function cabinContentStatus(
  cabin: DriverTripDetails["cabins"][number]
): CabinContentStatus {
  if (cabin.actualContents === null) return "awaiting-filling"

  const desired = quantitiesByEquipment(cabin.desiredContents)
  const actual = quantitiesByEquipment(cabin.actualContents)

  if (desired.size === 0 && actual.size === 0) return "empty"
  if (
    actual.size === 0 ||
    (cabin.movementTaskCreated && !cabin.movementTaskCompleted)
  ) {
    return "awaiting-filling"
  }
  if (desired.size === 0) return "awaiting-removal"
  if (contentsMatch(desired, actual)) return "ready"

  return "awaiting-filling"
}

function logisticsTripTitle(kind: DriverBoardCard["kind"]) {
  if (kind === "SHIPMENT") return "Отгрузить бытовку"
  if (kind === "RETURN") return "Вернуть бытовку"
  return "Логистическая ходка"
}

/** Renders the logistics-owned trip projection with its final filling states. */
export function DriverTripDetailsView({
  details,
}: {
  details: DriverTripDetails
  live: boolean
}) {
  return (
    <div className="flex flex-col gap-3" aria-label="Данные водительской ходки">
      <section className="flex flex-col gap-1 text-sm">
        <h3 className="font-medium">Клиент и маршрут</h3>
        <p>{details.clientName}</p>
        <p className="text-muted-foreground">
          {details.address?.trim() || "Адрес не указан"}
        </p>
        <p className="text-muted-foreground">
          {details.latitude !== null && details.longitude !== null
            ? `Координаты: ${details.latitude}, ${details.longitude}`
            : "Координаты не указаны"}
        </p>
      </section>

      <Separator />

      <section className="flex flex-col gap-1 text-sm">
        <h3 className="font-medium">Контакты</h3>
        <p>
          Основной: {details.primaryContactName?.trim() || "имя не указано"} ·{" "}
          {details.primaryContactPhone ?? "телефон не указан"}
        </p>
        {details.additionalContacts.length > 0 ? (
          <ul className="flex flex-col gap-1">
            {details.additionalContacts.map((contact, index) => (
              <li key={`${contact.name}:${contact.phone}:${index}`}>
                {contact.name} · {contact.phone}
              </li>
            ))}
          </ul>
        ) : (
          <p className="text-muted-foreground">Дополнительных контактов нет.</p>
        )}
      </section>

      {details.comment?.trim() ? (
        <section className="flex flex-col gap-1 text-sm">
          <h3 className="font-medium">Комментарий</h3>
          <p className="whitespace-pre-wrap">{details.comment.trim()}</p>
        </section>
      ) : null}

      <Separator />

      <section className="flex flex-col gap-1 text-sm">
        <p>Дата выполнения задания: {formatDate(details.scheduledDate)}</p>
      </section>

      <Separator />

      <section className="flex flex-col gap-2" aria-label="Бытовки ходки">
        <h3 className="text-sm font-medium">Бытовки</h3>
        {details.cabins.map((cabin) => {
          const status = cabinContentStatus(cabin)
          const presentation = CABIN_CONTENT_STATUS_PRESENTATION[status]

          return (
            <Card
              key={cabin.cabinId}
              size="sm"
              aria-label={`Бытовка ${cabin.unitNumber}`}
            >
              <CardHeader>
                <CardTitle>Бытовка - {cabin.unitNumber}</CardTitle>
              </CardHeader>
              <CardContent className="flex flex-col gap-3">
                <section className="flex flex-col gap-1">
                  <h4 className="text-sm font-medium">Наполнение</h4>
                  {cabin.actualContents ? (
                    <ActualEquipmentList items={cabin.actualContents} />
                  ) : null}
                </section>
                <Badge
                  variant="outline"
                  className={presentation.className}
                  aria-label={`Статус наполнения: ${presentation.label}`}
                  data-content-status={status}
                >
                  {presentation.label}
                </Badge>
              </CardContent>
            </Card>
          )
        })}
      </section>
    </div>
  )
}

/** Opens the live logistics detail endpoint for the exact grouped trip. */
export function DriverTripDetailsDialog({ card }: { card: DriverBoardCard }) {
  const { accessToken } = useAuth()
  const [open, setOpen] = useState(false)
  const query = useQuery({
    queryKey: driverTaskQueryKey(card.driverTaskId ?? "unavailable"),
    queryFn: () => getDriverTask(accessToken!, card.driverTaskId!),
    enabled: open && accessToken !== null && card.driverTaskId !== null,
  })

  if (!card.tripDetails || !card.driverTaskId) return null

  return (
    <Dialog open={open} onOpenChange={setOpen}>
      <Button
        type="button"
        size="sm"
        variant="outline"
        onPointerDown={(event) => event.stopPropagation()}
        onKeyDown={(event) => event.stopPropagation()}
        onClick={(event) => {
          event.stopPropagation()
          setOpen(true)
        }}
      >
        Подробности ходки
      </Button>
      <DialogContent className="max-h-[calc(100svh-2rem)] overflow-y-auto sm:max-w-3xl">
        <DialogHeader>
          <DialogTitle>{logisticsTripTitle(card.kind)}</DialogTitle>
          <DialogDescription>
            Актуальная информация для выполнения сгруппированной ходки.
          </DialogDescription>
        </DialogHeader>
        {query.isLoading ? (
          <p className="text-sm text-muted-foreground" aria-live="polite">
            Загружаем актуальный состав и готовность…
          </p>
        ) : null}
        {query.isError ? (
          <Alert variant="destructive">
            <AlertTitle>Подробности ходки недоступны</AlertTitle>
            <AlertDescription>
              {query.error instanceof Error
                ? query.error.message
                : "Сервис логистики не ответил."}
            </AlertDescription>
          </Alert>
        ) : null}
        {query.data?.tripDetails ? (
          <DriverTripDetailsView details={query.data.tripDetails} live />
        ) : null}
        {query.data && !query.data.tripDetails ? (
          <Alert>
            <AlertTitle>Данные ходки не сформированы</AlertTitle>
            <AlertDescription>
              Сервис вернул задание без логистической проекции ходки.
            </AlertDescription>
          </Alert>
        ) : null}
      </DialogContent>
    </Dialog>
  )
}
