import { useRef, useState } from "react"
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query"
import { Loading03Icon } from "@hugeicons/core-free-icons"
import { HugeiconsIcon } from "@hugeicons/react"
import { useNavigate } from "react-router-dom"
import { toast } from "sonner"
import { Alert, AlertDescription, AlertTitle } from "@/components/ui/alert"
import { ApiError } from "@/lib/api-client"
import {
  acknowledgeRentalBookingChangeAlert,
  getRentalBookingChangeAlerts,
  type RentalBookingChangeAlert,
} from "@/features/assistant/api/rental-booking-change-alerts-api"

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

function rentalBookingAlertsQueryKey(subjectId: string | undefined) {
  return [
    ...RENTAL_BOOKING_ALERTS_QUERY_KEY,
    subjectId ?? "unknown-user",
  ] as const
}

export function ManagerBookingAlertDialog() {
  const { accessToken, currentUser } = useAuth()
  return (
    <ManagerBookingAlertContent
      key={currentUser?.id ?? "unknown-user"}
      accessToken={accessToken}
      subjectId={currentUser?.id}
      rentalAccess={currentUser?.rentalAccess ?? false}
      role={currentUser?.globalRole}
    />
  )
}

function ManagerBookingAlertContent({
  accessToken,
  subjectId,
  rentalAccess,
  role,
}: {
  accessToken: string | null
  subjectId: string | undefined
  rentalAccess: boolean
  role: string | undefined
}) {
  const navigate = useNavigate()
  const queryClient = useQueryClient()
  const commandIdentity = useRef(new OrderCommandIdentityRegistry())
  const [handledBookingIds, setHandledBookingIds] = useState(
    () => new Set<string>()
  )
  const [handledMutationIds, setHandledMutationIds] = useState(
    () => new Set<string>()
  )
  const queryKey = rentalBookingAlertsQueryKey(subjectId)
  const changeQueryKey = [
    "rental-booking-change-alerts",
    subjectId ?? "unknown-user",
  ] as const
  const enabled = Boolean(accessToken && rentalAccess)
  const changesEnabled = enabled && role === "RENTAL_MANAGER"
  const alertsQuery = useQuery({
    queryKey,
    queryFn: () => getRentalBookingAlerts(accessToken!),
    enabled,
    refetchInterval: enabled
      ? RENTAL_BOOKING_ALERTS_REFETCH_INTERVAL_MS
      : false,
  })
  const changesQuery = useQuery({
    queryKey: changeQueryKey,
    queryFn: () => getRentalBookingChangeAlerts(accessToken!),
    enabled: changesEnabled,
    refetchInterval: changesEnabled
      ? RENTAL_BOOKING_ALERTS_REFETCH_INTERVAL_MS
      : false,
  })
  const changes = (changesEnabled ? (changesQuery.data ?? []) : []).filter(
    (candidate) => !handledMutationIds.has(candidate.mutationId)
  )
  const alerts = (alertsQuery.data ?? []).filter(
    (candidate) => !handledBookingIds.has(candidate.bookingId)
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
      setHandledBookingIds((current) => {
        const next = new Set(current)
        next.add(alert.bookingId)
        return next
      })
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

  const changeMutation = useMutation({
    mutationFn: async ({
      change,
      openOrder,
    }: {
      change: RentalBookingChangeAlert
      openOrder: boolean
    }) => {
      if (!accessToken) throw new Error("Сессия завершена.")
      const fingerprint = `booking-change:${change.mutationId}:${change.version}`
      await acknowledgeRentalBookingChangeAlert({
        accessToken,
        mutationId: change.mutationId,
        expectedVersion: change.version,
        idempotencyKey: commandIdentity.current.keyFor(fingerprint),
      })
      return { change, openOrder, fingerprint }
    },
    onSuccess: ({ change, openOrder, fingerprint }) => {
      commandIdentity.current.confirm(fingerprint)
      setHandledMutationIds(
        (current) => new Set([...current, change.mutationId])
      )
      queryClient.setQueryData<RentalBookingChangeAlert[]>(
        changeQueryKey,
        (current) =>
          current?.filter(
            (candidate) => candidate.mutationId !== change.mutationId
          )
      )
      void queryClient.invalidateQueries({ queryKey: changeQueryKey })
      if (openOrder && change.canOpenOrder)
        navigate(`/orders/${change.orderId}`)
    },
    onError: (error) => {
      toast.error(error.message)
      if (error instanceof ApiError && error.status === 409)
        void queryClient.invalidateQueries({ queryKey: changeQueryKey })
    },
  })

  const change = actionMutation.isPending
    ? undefined
    : changeMutation.isPending
      ? changeMutation.variables?.change
      : changes[0]
  if (change)
    return (
      <BookingChangeAlertDialog
        change={change}
        additionalAlerts={Math.max(0, changes.length + alerts.length - 1)}
        pending={changeMutation.isPending}
        error={
          changeMutation.variables?.change.mutationId === change.mutationId
            ? changeMutation.error?.message
            : undefined
        }
        onAcknowledge={(openOrder) => {
          if (!changeMutation.isPending)
            changeMutation.mutate({ change, openOrder })
        }}
      />
    )

  if (!alert)
    return changesQuery.isError ? (
      <Alert variant="destructive">
        <AlertTitle>Не удалось проверить изменения заказов</AlertTitle>
        <AlertDescription>
          {changesQuery.error.message}
          <Button
            type="button"
            variant="outline"
            onClick={() => void changesQuery.refetch()}
          >
            Повторить
          </Button>
        </AlertDescription>
      </Alert>
    ) : null

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

const settlementLabels: Record<
  NonNullable<RentalBookingChangeAlert["settlement"]>,
  string
> = {
  POLICY_UNCONFIGURED: "Правило неустойки не настроено",
  PAYMENT_REQUIRED: "Ожидает оплаты",
  NOT_REQUIRED: "Оплата не требуется",
  TEST_PAID: "Подтверждена тестовая оплата",
  WAIVED: "Неустойка отменена менеджером",
}

function BookingChangeAlertDialog({
  change,
  additionalAlerts,
  pending,
  error,
  onAcknowledge,
}: {
  change: RentalBookingChangeAlert
  additionalAlerts: number
  pending: boolean
  error?: string
  onAcknowledge: (openOrder: boolean) => void
}) {
  const cancelled = change.operation === "CANCEL"
  return (
    <AlertDialog open onOpenChange={() => undefined}>
      <AlertDialogContent
        className="max-h-[calc(100dvh-2rem)] max-w-md overflow-y-auto"
        onEscapeKeyDown={(event) => event.preventDefault()}
      >
        <AlertDialogHeader>
          <AlertDialogTitle>
            {cancelled ? "Клиент отменил заказ" : "Клиент перенёс доставку"}
          </AlertDialogTitle>
          <AlertDialogDescription>
            Заказ {change.orderId.slice(0, 8)}. Изменение уже выполнено;
            уведомление не меняет заказ и не списывает деньги.
          </AlertDialogDescription>
        </AlertDialogHeader>
        <dl className="flex flex-col gap-3 text-sm">
          <div className="flex flex-col gap-1">
            <dt className="text-muted-foreground">Адрес доставки</dt>
            <dd className="break-words">{change.deliveryAddress}</dd>
          </div>
          <div className="flex flex-wrap justify-between gap-2">
            <dt className="text-muted-foreground">
              {cancelled ? "Отменённая дата" : "Прежняя дата"}
            </dt>
            <dd>{formatDeliveryDate(change.previousDeliveryDate)}</dd>
          </div>
          {!cancelled && (
            <div className="flex flex-wrap justify-between gap-2">
              <dt className="text-muted-foreground">Новая дата</dt>
              <dd>
                {change.newDeliveryDate
                  ? formatDeliveryDate(change.newDeliveryDate)
                  : "Не указана"}
              </dd>
            </div>
          )}
          <div className="flex flex-wrap justify-between gap-2">
            <dt className="text-muted-foreground">Неустойка</dt>
            <dd>
              {change.feeRubles === null
                ? "Не указана"
                : new Intl.NumberFormat("ru-RU", {
                    style: "currency",
                    currency: "RUB",
                    maximumFractionDigits: 0,
                  }).format(BigInt(change.feeRubles))}
            </dd>
          </div>
          {change.settlement && (
            <div className="flex flex-wrap justify-between gap-2">
              <dt className="text-muted-foreground">Расчёт</dt>
              <dd>{settlementLabels[change.settlement]}</dd>
            </div>
          )}
          <div className="flex flex-wrap justify-between gap-2">
            <dt className="text-muted-foreground">Изменено</dt>
            <dd>{formatConfirmedAt(change.occurredAt)}</dd>
          </div>
          {additionalAlerts > 0 && (
            <div className="flex flex-wrap justify-between gap-2">
              <dt className="text-muted-foreground">Других уведомлений</dt>
              <dd>{additionalAlerts}</dd>
            </div>
          )}
        </dl>
        {error && (
          <Alert variant="destructive">
            <AlertTitle>Уведомление не подтверждено</AlertTitle>
            <AlertDescription>{error}</AlertDescription>
          </Alert>
        )}
        <AlertDialogFooter>
          <Button
            type="button"
            variant="outline"
            disabled={pending}
            onClick={() => onAcknowledge(false)}
          >
            Понятно
          </Button>
          {change.canOpenOrder && (
            <Button
              type="button"
              disabled={pending}
              onClick={() => onAcknowledge(true)}
            >
              {pending && (
                <HugeiconsIcon
                  icon={Loading03Icon}
                  data-icon="inline-start"
                  className="animate-spin motion-reduce:animate-none"
                  aria-hidden="true"
                />
              )}
              Открыть заказ
            </Button>
          )}
        </AlertDialogFooter>
      </AlertDialogContent>
    </AlertDialog>
  )
}

function formatDeliveryDate(value: string) {
  return new Intl.DateTimeFormat("ru-RU", {
    dateStyle: "medium",
    timeZone: "UTC",
  }).format(new Date(`${value}T00:00:00Z`))
}

function formatConfirmedAt(value: string) {
  const date = new Date(value)
  if (Number.isNaN(date.getTime())) return value

  return new Intl.DateTimeFormat("ru-RU", {
    dateStyle: "medium",
    timeStyle: "short",
  }).format(date)
}
