import { useQuery } from "@tanstack/react-query"

import { Alert, AlertDescription, AlertTitle } from "@/components/ui/alert"
import {
  getExpiredTrips,
  getRentalExpiredTrips,
  type ExpiredTripNotice,
} from "@/features/logistics/driver-board/driver-board-api"

/** Warehouse-scoped persisted history; dismissing UI never releases the actual cargo. */
export function ExpiredTripAlert({
  accessToken,
  warehouseId,
  subjectId,
  warehouseNames,
}: {
  accessToken: string
  warehouseId?: string
  subjectId: string
  warehouseNames?: Readonly<Record<string, string>>
}) {
  const history = useQuery<ExpiredTripNotice[]>({
    queryKey: [
      "logistics",
      "expired-trips",
      subjectId,
      warehouseId ?? "rental-warehouses",
    ],
    queryFn: () =>
      warehouseId
        ? getExpiredTrips(accessToken, warehouseId)
        : getRentalExpiredTrips(accessToken),
    refetchInterval: 30_000,
  })
  if (history.isError)
    return (
      <Alert variant="destructive">
        <AlertTitle>История автоотмен временно недоступна</AlertTitle>
        <AlertDescription>
          Не удалось проверить просроченные рейсы. Повторяем проверку
          автоматически.
        </AlertDescription>
      </Alert>
    )
  if (!history.data?.length) return null
  return (
    <Alert>
      <AlertTitle>
        Отменённые просроченные рейсы: {history.data.length}
      </AlertTitle>
      <AlertDescription>
        <p>
          Запланированный день склада завершён. Неустойка за автоотмену не
          начисляется.
        </p>
        <details>
          <summary>
            Последние 50 автоотмен — проверить и связаться с клиентами
          </summary>
          <ul className="flex flex-col gap-3 py-2">
            {history.data.map((trip) => (
              <li key={trip.id} className="flex flex-col gap-1">
                <span>
                  {trip.scheduledDate} · {trip.unitNumber} ·{" "}
                  {warehouseNames?.[trip.warehouseId]
                    ? `${warehouseNames[trip.warehouseId]} · `
                    : ""}
                  {trip.tripDetails?.clientName ?? "Рейс"} · Отменён
                </span>
                <span>
                  {trip.failureCode === "TRIP_DAY_EXPIRED_CARGO_REVIEW"
                    ? "Уточните груз у водителя. Оформите фактический возврат или согласуйте доставку другому клиенту; бытовка не освобождена."
                    : "Свяжитесь с клиентом и согласуйте новую дату с повторным расчётом маршрута."}
                </span>
                {trip.tripDetails?.primaryContactPhone ? (
                  <a
                    href={`tel:${trip.tripDetails.primaryContactPhone}`}
                    className="underline"
                  >
                    Позвонить клиенту
                  </a>
                ) : null}
              </li>
            ))}
          </ul>
        </details>
      </AlertDescription>
    </Alert>
  )
}
