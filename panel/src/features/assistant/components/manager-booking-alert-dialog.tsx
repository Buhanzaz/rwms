import { useEffect, useRef } from "react"
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query"
import { Loading03Icon } from "@hugeicons/core-free-icons"
import { HugeiconsIcon } from "@hugeicons/react"
import { useNavigate } from "react-router-dom"
import { toast } from "sonner"

import {
  actOnRentalBookingAlert,
  getRentalBookingAlerts,
  type RentalBookingAlert,
  type RentalBookingAlertAction,
} from "@/features/assistant/api/rental-presentations-api"
import { useAuth } from "@/features/auth/use-auth"
import { OrderCommandIdentityRegistry } from "@/features/orders/api/order-command-identity"
import {
  AlertDialog,
  AlertDialogContent,
  AlertDialogDescription,
  AlertDialogFooter,
  AlertDialogHeader,
  AlertDialogTitle,
} from "@/components/ui/alert-dialog"
import { Button } from "@/components/ui/button"

const RENTAL_BOOKING_ALERTS_QUERY_KEY = ["rental-booking-alerts"] as const
const RENTAL_BOOKING_ALERTS_REFETCH_INTERVAL_MS = 10_000

export function rentalBookingAlertsQueryKey(subjectId: string | undefined) {
  return [
    ...RENTAL_BOOKING_ALERTS_QUERY_KEY,
    subjectId ?? "unknown-user",
  ] as const
}

export function ManagerBookingAlertDialog() {
  const { accessToken, currentUser } = useAuth()
  const navigate = useNavigate()
  const queryClient = useQueryClient()
  const commandIdentity = useRef(new OrderCommandIdentityRegistry())
  const handledBookingIds = useRef(new Set<string>())
  const queryKey = rentalBookingAlertsQueryKey(currentUser?.id)
  const enabled = Boolean(accessToken && currentUser?.rentalAccess)
  useEffect(() => {
    handledBookingIds.current.clear()
  }, [currentUser?.id])
  const alertsQuery = useQuery({
    queryKey,
    queryFn: () => getRentalBookingAlerts(accessToken!),
    enabled,
    refetchInterval: enabled
      ? RENTAL_BOOKING_ALERTS_REFETCH_INTERVAL_MS
      : false,
  })
  const alerts = (alertsQuery.data ?? []).filter(
    (candidate) => !handledBookingIds.current.has(candidate.bookingId)
  )
  const alert = alerts[0]

  const actionMutation = useMutation({
    mutationFn: ({
      alert,
      action,
    }: {
      alert: RentalBookingAlert
      action: RentalBookingAlertAction
    }) => {
      if (!accessToken) throw new Error("Сессия завершена.")

      const fingerprint = `${alert.bookingId}:${action}`
      return actOnRentalBookingAlert({
        accessToken,
        bookingId: alert.bookingId,
        expectedVersion: alert.version,
        action,
        idempotencyKey: commandIdentity.current.keyFor(fingerprint),
      }).then(() => ({ alert, action, fingerprint }))
    },
    onSuccess: ({ alert, action, fingerprint }) => {
      commandIdentity.current.confirm(fingerprint)
      handledBookingIds.current.add(alert.bookingId)
      queryClient.setQueryData<RentalBookingAlert[]>(queryKey, (current) =>
        current?.filter((candidate) => candidate.bookingId !== alert.bookingId)
      )
      void queryClient.invalidateQueries({ queryKey })

      if (action === "CONTINUE") {
        toast.success("Открываем черновик бронирования.")
        navigate(`/orders/${alert.orderId}`)
        return
      }

      toast.success("Черновик оставлен без изменений.")
    },
    onError: (error) =>
      toast.error(
        error instanceof Error
          ? error.message
          : "Не удалось обработать подтверждение клиента."
      ),
  })

  if (!alert) return null

  const additionalAlerts = Math.max(0, alerts.length - 1)

  function submit(action: RentalBookingAlertAction) {
    if (!actionMutation.isPending) actionMutation.mutate({ alert, action })
  }

  return (
    <AlertDialog open onOpenChange={() => undefined}>
      <AlertDialogContent
        className="max-w-md"
        onEscapeKeyDown={(event) => event.preventDefault()}
      >
        <AlertDialogHeader>
          <AlertDialogTitle>
            Клиент «{alert.client.displayName}» подтвердил выбор
          </AlertDialogTitle>
          <AlertDialogDescription>
            Проверьте выбранные бытовки и решите, открывать ли черновик
            бронирования сейчас.
          </AlertDialogDescription>
        </AlertDialogHeader>

        <div className="space-y-3 text-sm">
          <div className="space-y-1">
            <p className="text-muted-foreground">Выбранные бытовки</p>
            <ul
              aria-label="Выбранные бытовки"
              className="space-y-1 rounded-lg border bg-muted/30 p-3"
            >
              {alert.cabins.map((cabin) => (
                <li key={cabin.id} className="flex gap-2">
                  <span className="font-medium">{cabin.number}</span>
                  <span className="text-muted-foreground">
                    {cabin.rentalType ?? "Тип не указан"}
                  </span>
                </li>
              ))}
            </ul>
          </div>
          <dl className="space-y-1 text-muted-foreground">
            <div className="flex flex-wrap justify-between gap-x-4 gap-y-1">
              <dt>Подтверждено</dt>
              <dd>{formatConfirmedAt(alert.confirmedAt)}</dd>
            </div>
            <div className="flex flex-wrap justify-between gap-x-4 gap-y-1">
              <dt>Других ожидающих подтверждений</dt>
              <dd>{additionalAlerts}</dd>
            </div>
          </dl>
        </div>

        <AlertDialogFooter>
          <Button
            type="button"
            variant="outline"
            disabled={actionMutation.isPending}
            onClick={() => submit("KEEP_DRAFT")}
          >
            {actionMutation.isPending ? (
              <HugeiconsIcon icon={Loading03Icon} className="animate-spin" />
            ) : null}
            Сохранить черновик
          </Button>
          <Button
            type="button"
            disabled={actionMutation.isPending}
            onClick={() => submit("CONTINUE")}
          >
            {actionMutation.isPending ? (
              <HugeiconsIcon icon={Loading03Icon} className="animate-spin" />
            ) : null}
            Продолжить
          </Button>
        </AlertDialogFooter>
      </AlertDialogContent>
    </AlertDialog>
  )
}

function formatConfirmedAt(value: string) {
  const date = new Date(value)
  if (Number.isNaN(date.getTime())) return value

  return new Intl.DateTimeFormat("ru-RU", {
    dateStyle: "medium",
    timeStyle: "short",
  }).format(date)
}
