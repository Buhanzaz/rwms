import { useState } from "react"
import { useQuery } from "@tanstack/react-query"

import { Alert, AlertDescription, AlertTitle } from "@/components/ui/alert"
import { Badge } from "@/components/ui/badge"
import { Button } from "@/components/ui/button"
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

function operationLabel(value: string) {
  switch (value) {
    case "SHIPMENT":
      return "Отгрузка"
    case "RETURN":
      return "Вывоз"
    case "TRANSFER":
      return "Перемещение"
    default:
      return value
  }
}

function desiredWindowLabel(
  window: DriverTripDetails["desiredDeliveryWindows"][number]
) {
  return (
    window.startDate === window.endDate
      ? formatDate(window.startDate)
      : `${formatDate(window.startDate)} — ${formatDate(window.endDate)}`
  )
}

function EquipmentList({
  items,
}: {
  items: readonly (DriverTripDesiredEquipment | DriverTripActualEquipment)[]
}) {
  if (items.length === 0) {
    return <p className="text-sm text-muted-foreground">Не требуется.</p>
  }
  return (
    <ul className="flex flex-col gap-1 text-sm">
      {items.map((item) => (
        <li
          key={`${item.equipmentId}:${"locationKind" in item ? item.locationKind : "desired"}`}
          className="flex items-baseline justify-between gap-3"
        >
          <span>{item.equipmentName?.trim() || "Оборудование"}</span>
          <span className="shrink-0 text-muted-foreground">
            × {item.quantity}
          </span>
        </li>
      ))}
    </ul>
  )
}

/** Renders the logistics-owned trip projection without deriving readiness. */
export function DriverTripDetailsView({
  details,
  live,
}: {
  details: DriverTripDetails
  live: boolean
}) {
  return (
    <div className="flex flex-col gap-3" aria-label="Данные водительской ходки">
      <div className="flex flex-wrap gap-1">
        <Badge variant="secondary">Задание №{details.taskNumber}</Badge>
        <Badge variant="outline">Ходка №{details.tripNumber}</Badge>
        <Badge variant="outline">{operationLabel(details.operationType)}</Badge>
      </div>

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

      <section className="flex flex-col gap-2 text-sm">
        <h3 className="font-medium">Желаемые даты клиента</h3>
        {details.desiredDeliveryWindows.length > 0 ? (
          <div className="flex flex-wrap gap-1">
            {details.desiredDeliveryWindows.map((window, index) => (
              <Badge
                key={`${window.startDate}:${window.endDate}:${index}`}
                variant="outline"
              >
                {desiredWindowLabel(window)}
              </Badge>
            ))}
          </div>
        ) : (
          <p className="text-muted-foreground">Пожелания не указаны.</p>
        )}
        <p>Фактически назначено: {formatDate(details.scheduledDate)}</p>
      </section>

      <Separator />

      <section className="flex flex-col gap-2" aria-label="Бытовки ходки">
        <h3 className="text-sm font-medium">Бытовки</h3>
        {details.cabins.map((cabin) => (
          <Card key={cabin.cabinId} size="sm">
            <CardHeader>
              <CardTitle>Бытовка {cabin.unitNumber}</CardTitle>
              <CardDescription>
                Подготовка наполнения к этой ходке
              </CardDescription>
            </CardHeader>
            <CardContent className="flex flex-col gap-3">
              <section className="flex flex-col gap-1">
                <h4 className="text-sm font-medium">Нужно по заказу</h4>
                <EquipmentList items={cabin.desiredContents} />
              </section>
              <section className="flex flex-col gap-1">
                <h4 className="text-sm font-medium">Фактически в бытовке</h4>
                {cabin.actualContents === null ? (
                  <p className="text-sm text-muted-foreground">
                    Недоступно в карточке доски. Откройте подробности ходки для
                    актуального состава.
                  </p>
                ) : (
                  <EquipmentList items={cabin.actualContents} />
                )}
              </section>
              <div className="flex flex-wrap gap-1">
                <Badge
                  variant={cabin.movementTaskCreated ? "secondary" : "outline"}
                >
                  {cabin.movementTaskCreated
                    ? "Задание на мебель создано"
                    : "Задание на мебель не создано"}
                </Badge>
                <Badge
                  variant={
                    cabin.movementTaskCompleted ? "secondary" : "outline"
                  }
                >
                  {cabin.movementTaskCompleted
                    ? "Перемещение мебели выполнено"
                    : "Перемещение мебели не выполнено"}
                </Badge>
                {cabin.contentReady === null ? (
                  <Badge variant="outline">
                    Готовность наполнения — в подробностях
                  </Badge>
                ) : (
                  <Badge
                    variant={cabin.contentReady ? "secondary" : "destructive"}
                  >
                    {cabin.contentReady
                      ? "Наполнение готово"
                      : "Наполнение не готово"}
                  </Badge>
                )}
              </div>
              {!live &&
              (cabin.actualContents === null || cabin.contentReady === null) ? (
                <p className="text-xs text-muted-foreground">
                  Карточка не подменяет live-проверку состава и готовности.
                </p>
              ) : null}
            </CardContent>
          </Card>
        ))}
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
          <DialogTitle>
            Задание №{card.tripDetails.taskNumber} · Ходка №
            {card.tripDetails.tripNumber}
          </DialogTitle>
          <DialogDescription>
            Актуальная информация для выполнения всей сгруппированной ходки.
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
