import { useEffect, useId, useRef, useState, type ReactNode } from "react"
import { Loading03Icon } from "@hugeicons/core-free-icons"
import { HugeiconsIcon } from "@hugeicons/react"

import { Alert, AlertDescription, AlertTitle } from "@/components/ui/alert"
import { Badge } from "@/components/ui/badge"
import { Button } from "@/components/ui/button"
import {
  Card,
  CardContent,
  CardDescription,
  CardFooter,
  CardHeader,
  CardTitle,
} from "@/components/ui/card"
import { Separator } from "@/components/ui/separator"
import { Skeleton } from "@/components/ui/skeleton"
import {
  formatPaymentCountdown,
  formatReceiptRubles,
  paymentRemainingMilliseconds,
  type ObservedOrderPayment,
  type OrderPayment,
  type OrderPaymentReceipt,
} from "@/features/orders/domain/order-payment"

function paymentLabel(payment: OrderPayment, elapsed: boolean): string {
  switch (payment.state) {
    case "CONFIRMED":
      return payment.source === "MANAGER_CONFIRMATION"
        ? "Оплата подтверждена менеджером"
        : payment.source === "CUSTOMER_TEST" ||
            payment.source === "PRESENTATION_TEST"
          ? "Оплачено — тестовый режим"
          : "Оплата подтверждена"
    case "PENDING":
      return elapsed ? "Время оплаты истекло" : "Ожидает оплаты"
    case "EXPIRING":
      return "Освобождаем бронь"
    case "EXPIRED":
      return "Бронь отменена: время оплаты истекло"
    case "CANCELLED":
      return "Заказ отменён"
    default:
      return payment.orderStatus === "DRAFT"
        ? "Чек ещё не сформирован"
        : "Нет сведений об оплате"
  }
}

/** Shared read-only till-style bill; all line factors and totals come from its immutable snapshot. */
function ReceiptLines({ receipt }: { receipt: OrderPaymentReceipt }) {
  const cabins = new Map(
    receipt.lines
      .filter((line) => line.kind === "CABIN")
      .map((line) => [line.rentalItemId, line.label])
  )
  return (
    <div className="flex min-w-0 flex-col gap-4">
      <ol aria-label="Позиции чека" className="flex min-w-0 flex-col gap-4">
        {receipt.lines.map((line, index) => (
          <li key={index} className="flex min-w-0 flex-col gap-1">
            <div className="flex flex-wrap items-baseline justify-between gap-x-5 gap-y-1">
              <p className="min-w-0 font-medium wrap-anywhere">{line.label}</p>
              <p className="min-w-0 text-right font-mono text-sm wrap-anywhere tabular-nums">
                {formatReceiptRubles(line.amountRubles)}
              </p>
            </div>
            {line.kind === "FURNITURE" ? (
              <p className="text-xs text-muted-foreground">
                Для: {cabins.get(line.rentalItemId)}
              </p>
            ) : null}
            <p className="text-sm wrap-anywhere text-muted-foreground tabular-nums">
              {line.kind === "DELIVERY"
                ? "1 услуга × " +
                  formatReceiptRubles(line.unitPriceRubles) +
                  " · однократно"
                : line.quantity +
                  " шт. × " +
                  formatReceiptRubles(line.unitPriceRubles) +
                  "/шт./мес. × " +
                  line.rentalMonths +
                  " мес."}
            </p>
            {index < receipt.lines.length - 1 ? (
              <Separator className="mt-3" />
            ) : null}
          </li>
        ))}
      </ol>
      <Separator />
      <div className="flex flex-wrap items-baseline justify-between gap-x-6 gap-y-2">
        <p className="font-semibold">Итого по чеку</p>
        <p className="min-w-0 text-xl font-semibold wrap-anywhere tabular-nums">
          {formatReceiptRubles(receipt.totalRubles)}
        </p>
      </div>
      {!receipt.deliveryIncluded ? (
        <p className="text-sm text-muted-foreground">
          Доставка в сумму чека не включена. Её стоимость уточняется отдельно.
        </p>
      ) : null}
    </div>
  )
}

/** A countdown is a UI affordance only; expiration and confirmation always remain server decisions. */
export function OrderPaymentCard({
  observation,
  loading = false,
  refreshing = false,
  unavailable = false,
  error,
  pending = false,
  mode,
  onConfirm,
  onRefresh,
  children,
}: {
  observation?: ObservedOrderPayment
  loading?: boolean
  refreshing?: boolean
  unavailable?: boolean
  error?: string | null
  pending?: boolean
  mode: "MANAGER" | "TEST"
  onConfirm?: () => void
  onRefresh: () => void
  children?: ReactNode
}) {
  const titleId = useId()
  const [monotonicNow, setMonotonicNow] = useState(() => performance.now())
  const notifiedDeadline = useRef<string | null>(null)
  const payment = observation?.payment
  const remaining = observation
    ? paymentRemainingMilliseconds(observation, monotonicNow)
    : null
  const elapsed = remaining === 0
  const clockActive = payment?.state === "PENDING" && !elapsed

  useEffect(() => {
    if (!clockActive) return
    const timer = window.setInterval(
      () => setMonotonicNow(performance.now()),
      250
    )
    return () => window.clearInterval(timer)
  }, [clockActive, observation?.observedAt])

  useEffect(() => {
    if (!payment || payment.state !== "PENDING" || !elapsed) return
    const deadline = payment.orderId + ":" + payment.expiresAt
    if (notifiedDeadline.current === deadline) return
    notifiedDeadline.current = deadline
    onRefresh()
  }, [elapsed, onRefresh, payment])

  const confirmEnabled = Boolean(
    payment?.canConfirm &&
    payment.state === "PENDING" &&
    remaining !== null &&
    remaining > 0 &&
    !unavailable &&
    !pending
  )
  const expired = payment?.state === "EXPIRED" || payment?.state === "CANCELLED"
  return (
    <Card aria-labelledby={titleId} className="min-w-0">
      <CardHeader>
        <CardTitle id={titleId}>
          {payment?.receipt
            ? "Чек заказа " + payment.receipt.orderNumber
            : "Оплата заказа"}
        </CardTitle>
        <CardDescription>
          Первоначальный счёт за аренду. Не является кассовым или фискальным
          чеком.
        </CardDescription>
      </CardHeader>
      <CardContent className="flex min-w-0 flex-col gap-5">
        {loading && !payment ? (
          <div role="status" className="flex flex-col gap-3">
            <p className="text-sm text-muted-foreground">
              Загружаем чек и срок оплаты…
            </p>
            <Skeleton className="h-5 w-2/3" />
            <Skeleton className="h-24 w-full" />
          </div>
        ) : null}
        {error ? (
          <Alert variant="destructive">
            <AlertTitle>Не удалось обновить оплату</AlertTitle>
            <AlertDescription>{error}</AlertDescription>
          </Alert>
        ) : null}
        {payment ? (
          <>
            <div className="flex flex-wrap items-center justify-between gap-3">
              <Badge
                variant={
                  payment.state === "CONFIRMED"
                    ? "success"
                    : expired
                      ? "destructive"
                      : "warning"
                }
              >
                {paymentLabel(payment, elapsed)}
              </Badge>
              {payment.state === "PENDING" && remaining !== null ? (
                <p
                  role="timer"
                  aria-label="Осталось на оплату"
                  className="text-xl font-semibold tabular-nums"
                >
                  {formatPaymentCountdown(remaining)}
                </p>
              ) : null}
            </div>
            {payment.state === "PENDING" ? (
              <p className="text-sm text-muted-foreground">
                {elapsed
                  ? "Срок завершился. Проверяем освобождение брони на сервере."
                  : "На оплату отведено 5 минут. Если не оплатить вовремя, бронь будет отменена."}
              </p>
            ) : null}
            {payment.state === "EXPIRING" ? (
              <p className="text-sm text-muted-foreground">
                Время оплаты истекло. Сервер освобождает бытовки и наполнение.
              </p>
            ) : null}
            {expired ? (
              <p className="text-sm text-muted-foreground">
                Этот чек оплатить нельзя. Для новой брони выберите бытовки
                заново или свяжитесь с менеджером.
              </p>
            ) : null}
            {payment.receipt ? (
              <ReceiptLines receipt={payment.receipt} />
            ) : (
              <p className="text-sm text-muted-foreground">
                {payment.orderStatus === "DRAFT"
                  ? "Ожидаем сохранения заказа и формирования чека. Срок оплаты ещё не начался."
                  : "Сервер не выдавал чек для этого заказа. Это не подтверждение оплаты и не нулевая стоимость."}
              </p>
            )}
            {payment.state === "PENDING" && !elapsed ? (
              <p className="text-sm text-muted-foreground">
                {mode === "TEST"
                  ? "Тестовый режим: деньги не списываются. Кнопка подтверждает тестовую оплату этого чека."
                  : "Подтверждайте только полученную оплату. Это отметка менеджера, а не банковское списание."}
              </p>
            ) : null}
          </>
        ) : null}
      </CardContent>
      <CardFooter className="flex flex-wrap gap-2">
        {payment?.state === "PENDING" && payment.canConfirm && onConfirm ? (
          <Button type="button" onClick={onConfirm} disabled={!confirmEnabled}>
            {pending ? (
              <HugeiconsIcon
                icon={Loading03Icon}
                data-icon="inline-start"
                className="animate-spin"
              />
            ) : null}
            {pending
              ? "Подтверждаем…"
              : mode === "TEST"
                ? "Оплатить (тест)"
                : "Подтвердить оплату"}
          </Button>
        ) : null}
        <Button
          type="button"
          variant="outline"
          onClick={onRefresh}
          disabled={refreshing || pending}
        >
          {refreshing ? "Обновляем…" : "Обновить оплату"}
        </Button>
        {children}
      </CardFooter>
    </Card>
  )
}
