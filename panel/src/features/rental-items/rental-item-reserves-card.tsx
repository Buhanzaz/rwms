import { useQuery } from "@tanstack/react-query"
import { Link } from "react-router-dom"

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
import { Skeleton } from "@/components/ui/skeleton"
import { useAuth } from "@/features/auth/use-auth"
import {
  getRentalItemReserves,
  type RentalItemReserve,
} from "./rental-item-reserves-api"
import { workspaceEntryNavigationOptions } from "@/hooks/use-workspace-back"

function reserveStatus(reserve: RentalItemReserve) {
  if (reserve.paymentState === "PENDING") return "Ожидает оплаты"
  if (reserve.paymentState === "EXPIRING")
    return "Освобождаем неоплаченную бронь"
  if (reserve.paymentState === "EXPIRED") return "Время оплаты истекло"
  if (
    reserve.paymentState === "CANCELLED" ||
    reserve.orderStatus === "CANCELLED"
  )
    return "Заказ отменён · освобождаем резерв"
  if (reserve.paymentState === "CONFIRMED") return "Оплата подтверждена"
  return reserve.kind === "SELECTION_HOLD"
    ? "Удержание выбора"
    : "Резерв заказа"
}

function reserveStatusVariant(reserve: RentalItemReserve) {
  if (
    reserve.orderStatus === "CANCELLED" ||
    reserve.paymentState === "EXPIRED" ||
    reserve.paymentState === "CANCELLED"
  )
    return "destructive"
  if (reserve.paymentState === "CONFIRMED") return "success"
  if (reserve.paymentState === "EXPIRING") return "progress"
  if (reserve.paymentState === "PENDING" || reserve.kind === "SELECTION_HOLD")
    return "warning"
  return "info"
}

export function RentalItemReservesCard({
  rentalItemId,
  warehouseId,
  timeZone,
}: {
  rentalItemId: string
  warehouseId: string
  timeZone?: string
}) {
  const { accessToken, currentUser } = useAuth()
  const query = useQuery({
    queryKey: [
      "rental-item-reserves",
      currentUser?.id,
      warehouseId,
      rentalItemId,
    ],
    queryFn: () =>
      getRentalItemReserves(accessToken!, rentalItemId, warehouseId),
    enabled: Boolean(accessToken && currentUser),
    refetchInterval: 5_000,
    refetchOnWindowFocus: true,
    refetchOnReconnect: true,
  })
  const formatTime = (value: string) =>
    new Intl.DateTimeFormat("ru-RU", {
      dateStyle: "medium",
      timeStyle: "short",
      timeZone: timeZone ?? "UTC",
    }).format(new Date(value))
  const error =
    !accessToken || !currentUser
      ? "Для просмотра резервов требуется авторизация."
      : query.error instanceof Error
        ? query.error.message
        : query.isError
          ? "Не удалось загрузить резервы."
          : null
  return (
    <Card>
      <CardHeader>
        <CardTitle>Резервы бытовки</CardTitle>
        <CardDescription>
          Активные удержания выбора и резерв заказа. Имена и ссылки доступны в
          пределах ваших прав.{" "}
          {timeZone
            ? "Время указано по складу."
            : "Часовой пояс склада недоступен; время указано в UTC."}
        </CardDescription>
      </CardHeader>
      <CardContent className="flex flex-col gap-4">
        {error ? (
          <Alert variant="destructive">
            <AlertTitle>Резервы недоступны</AlertTitle>
            <AlertDescription>{error}</AlertDescription>
          </Alert>
        ) : !query.data ? (
          <Skeleton className="h-24 w-full" aria-label="Загрузка резервов" />
        ) : query.data.reserves.length === 0 ? (
          <p className="text-sm text-muted-foreground">
            Активных резервов нет.
          </p>
        ) : (
          <ul className="flex flex-col gap-3">
            {query.data.reserves.map((reserve) => (
              <li
                key={reserve.reservationId}
                className="flex flex-col gap-3 rounded-lg border p-4"
              >
                <div className="flex flex-wrap items-center gap-2">
                  <h3 className="font-medium">
                    {reserve.kind === "SELECTION_HOLD"
                      ? "Удержание выбора"
                      : "Резерв заказа"}
                  </h3>
                  <Badge variant="outline">
                    {reserve.source === "CUSTOMER"
                      ? "Клиентское приложение"
                      : "Менеджер"}
                  </Badge>
                  <Badge variant={reserveStatusVariant(reserve)}>
                    {reserveStatus(reserve)}
                  </Badge>
                </div>
                <dl className="grid gap-3 text-sm sm:grid-cols-2">
                  {reserve.clientDisplayName ? (
                    <div>
                      <dt className="text-muted-foreground">Клиент</dt>
                      <dd>{reserve.clientDisplayName}</dd>
                    </div>
                  ) : null}
                  {reserve.managerDisplayName ? (
                    <div>
                      <dt className="text-muted-foreground">Менеджер</dt>
                      <dd>{reserve.managerDisplayName}</dd>
                    </div>
                  ) : null}
                  <div>
                    <dt className="text-muted-foreground">Создан</dt>
                    <dd>
                      <time dateTime={reserve.createdAt}>
                        {formatTime(reserve.createdAt)}
                      </time>
                    </dd>
                  </div>
                  <div>
                    <dt className="text-muted-foreground">Истекает</dt>
                    <dd>
                      {reserve.expiresAt ? (
                        <time dateTime={reserve.expiresAt}>
                          {formatTime(reserve.expiresAt)}
                        </time>
                      ) : (
                        "Срок не указан"
                      )}
                    </dd>
                  </div>
                </dl>
                {reserve.canOpenOrder && reserve.orderId ? (
                  <Button
                    asChild
                    variant="outline"
                    size="sm"
                    className="self-start"
                  >
                    <Link
                      to={`/orders/${reserve.orderId}`}
                      state={workspaceEntryNavigationOptions.state}
                    >
                      {reserve.orderNumber
                        ? `Открыть заказ ${reserve.orderNumber}`
                        : "Открыть заказ"}
                    </Link>
                  </Button>
                ) : null}
              </li>
            ))}
          </ul>
        )}
        {accessToken && currentUser ? (
          <Button
            variant="outline"
            className="self-start"
            disabled={query.isFetching}
            onClick={() => void query.refetch()}
          >
            {query.isFetching ? "Обновляем…" : "Обновить резервы"}
          </Button>
        ) : null}
      </CardContent>
    </Card>
  )
}
