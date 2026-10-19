import { useEffect, useId, useRef, useState, type FormEvent } from "react"
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query"
import { Loading03Icon } from "@hugeicons/core-free-icons"
import { HugeiconsIcon } from "@hugeicons/react"
import { toast } from "sonner"

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
} from "@/components/ui/field"
import { Textarea } from "@/components/ui/textarea"
import { OrderCommandIdentityRegistry } from "@/features/orders/api/order-command-identity"
import { ORDERS_QUERY_KEY } from "@/features/orders/api/orders-api"
import {
  listOrderBookingChangeQuotes,
  ORDER_BOOKING_CHANGE_QUOTES_QUERY_KEY,
  waiveOrderBookingChangeQuote,
  type OrderBookingChangeQuote,
} from "@/features/orders/api/order-booking-change-quotes-api"
import { useOrdersModule } from "@/features/orders/orders-module-context"
import { ApiError } from "@/lib/api-client"

const SETTLEMENT_LABELS: Record<OrderBookingChangeQuote["settlement"], string> =
  {
    POLICY_UNCONFIGURED: "Правило неустойки не настроено",
    PAYMENT_REQUIRED: "Ожидает оплаты",
    NOT_REQUIRED: "Оплата не требуется",
    TEST_PAID: "Подтверждена тестовая оплата",
    WAIVED: "Неустойка отменена",
  }
const APPLICATION_LABELS: Record<
  OrderBookingChangeQuote["applicationState"],
  string
> = {
  OFFERED: "Ожидает решения клиента",
  APPLYING: "Изменение выполняется",
  APPLIED: "Изменение выполнено",
}

function rubles(value: string | null) {
  return value === null
    ? "Не указана"
    : new Intl.NumberFormat("ru-RU", {
        style: "currency",
        currency: "RUB",
        maximumFractionDigits: 0,
      }).format(BigInt(value))
}

function canWaive(quote: OrderBookingChangeQuote, now: number) {
  return (
    quote.applicationState === "OFFERED" &&
    (quote.settlement === "PAYMENT_REQUIRED" ||
      quote.settlement === "POLICY_UNCONFIGURED") &&
    Date.parse(quote.expiresAt) > now
  )
}

/** Mounted only for an already visible order; the capability never grants server access. */
export function OrderBookingChangeQuotesCard({
  orderId,
  warehouseId,
}: {
  orderId: string
  warehouseId: string | null
}) {
  const { accessToken, currentUser, canManageBookingChanges } =
    useOrdersModule()
  if (
    !accessToken ||
    !currentUser ||
    !warehouseId ||
    !canManageBookingChanges(warehouseId)
  )
    return null
  return (
    <OrderBookingChangeQuotes
      key={`${currentUser.id}:${orderId}`}
      accessToken={accessToken}
      subjectId={currentUser.id}
      orderId={orderId}
    />
  )
}

function OrderBookingChangeQuotes({
  accessToken,
  subjectId,
  orderId,
}: {
  accessToken: string
  subjectId: string
  orderId: string
}) {
  const queryClient = useQueryClient()
  const queryKey = [
    ...ORDER_BOOKING_CHANGE_QUOTES_QUERY_KEY,
    subjectId,
    orderId,
  ] as const
  const quotesQuery = useQuery({
    queryKey,
    queryFn: () => listOrderBookingChangeQuotes(accessToken, orderId),
    refetchInterval: 15_000,
  })
  return (
    <BookingChangeQuotes
      accessToken={accessToken}
      orderId={orderId}
      quotesQuery={quotesQuery}
      onUpdated={(quote) => {
        queryClient.setQueryData<OrderBookingChangeQuote[]>(
          queryKey,
          (current) =>
            current?.map((candidate) =>
              candidate.quoteId === quote.quoteId ? quote : candidate
            )
        )
        void queryClient.invalidateQueries({ queryKey, exact: true })
        void queryClient.invalidateQueries({
          queryKey: [...ORDERS_QUERY_KEY, "history", subjectId, orderId],
          exact: true,
        })
      }}
    />
  )
}

type BookingChangeQuotesSource = {
  data: OrderBookingChangeQuote[] | undefined
  isError: boolean
  isFetching: boolean
  error: Error | null
  refetch: () => Promise<unknown>
}

/** Shared fee-only form; its source may be an order query or the warehouse-filtered staff feed. */
export function BookingChangeQuotes({
  accessToken,
  orderId,
  quotesQuery,
  onUpdated,
  title = "Изменения клиента и неустойка",
  onEditingChange,
}: {
  accessToken: string
  orderId: string
  quotesQuery: BookingChangeQuotesSource
  onUpdated: (quote: OrderBookingChangeQuote) => void
  title?: string
  onEditingChange?: (editing: boolean) => void
}) {
  const [selectedQuoteId, setSelectedQuoteId] = useState<string | null>(null)
  const [reason, setReason] = useState("")
  const [validationError, setValidationError] = useState<string | null>(null)
  const [errorText, setErrorText] = useState<string | null>(null)
  const [now, setNow] = useState(() => Date.now())
  const reasonId = useId()
  const commandIdentity = useRef(new OrderCommandIdentityRegistry())
  const quotes = quotesQuery.data ?? []
  const selectedQuote = quotes.find(
    (quote) => quote.quoteId === selectedQuoteId
  )

  // Disable at the actual deadline even if no poll or other UI interaction occurs.
  useEffect(() => {
    const clock = Date.now()
    const deadline = Math.min(
      ...(quotesQuery.data ?? [])
        .map((quote) => Date.parse(quote.expiresAt))
        .filter((expiry) => expiry > now)
    )
    if (!Number.isFinite(deadline)) return
    const timer = window.setTimeout(
      () => setNow(Date.now()),
      Math.max(0, Math.min(deadline - clock + 1, 2_147_483_647))
    )
    return () => window.clearTimeout(timer)
  }, [quotesQuery.data, now])

  const waiveMutation = useMutation({
    mutationFn: ({
      quote,
      reason: trimmedReason,
      fingerprint,
    }: {
      quote: OrderBookingChangeQuote
      reason: string
      fingerprint: string
    }) =>
      waiveOrderBookingChangeQuote({
        accessToken,
        orderId,
        quoteId: quote.quoteId,
        expectedVersion: quote.version,
        reason: trimmedReason,
        idempotencyKey: commandIdentity.current.keyFor(fingerprint),
      }),
    onSuccess: (quote, { fingerprint }) => {
      commandIdentity.current.confirm(fingerprint)
      onUpdated(quote)
      setSelectedQuoteId(null)
      onEditingChange?.(false)
      setReason("")
      setErrorText(null)
      toast.success("Неустойка отменена. Изменение заказа подтверждает клиент.")
    },
    onError: async (error) => {
      if (error instanceof ApiError && error.status === 409) {
        setErrorText(
          "Предложение изменилось. Проверьте обновлённые данные и подтвердите действие повторно. Причина сохранена."
        )
        await quotesQuery.refetch()
      } else {
        setErrorText(
          error instanceof Error
            ? error.message
            : "Не удалось отменить неустойку."
        )
      }
    },
  })

  function handleSubmit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault()
    const trimmedReason = reason.trim()
    if (!trimmedReason || trimmedReason.length > 2000) {
      setValidationError(
        "Укажите причину: от 1 до 2000 символов без пробелов по краям."
      )
      return
    }
    if (
      !selectedQuote ||
      !canWaive(selectedQuote, now) ||
      quotesQuery.isError ||
      quotesQuery.isFetching ||
      waiveMutation.isPending
    ) {
      setErrorText(
        "Предложение больше недоступно. Обновите данные; причина сохранена."
      )
      void quotesQuery.refetch()
      return
    }
    setValidationError(null)
    setErrorText(null)
    waiveMutation.mutate({
      quote: selectedQuote,
      reason: trimmedReason,
      fingerprint: JSON.stringify([
        "booking-change-waiver",
        orderId,
        selectedQuote.quoteId,
        selectedQuote.version,
        trimmedReason,
      ]),
    })
  }

  // No placeholder panel on orders without an active customer proposal.
  if (!quotesQuery.isError && quotes.length === 0 && selectedQuoteId === null)
    return null

  return (
    <>
      <Card size="sm">
        <CardHeader>
          <CardTitle>{title}</CardTitle>
          <CardDescription>
            Только актуальные предложения. Снятие платы не отменяет заказ и не
            переносит доставку.
          </CardDescription>
        </CardHeader>
        <CardContent className="flex flex-col gap-4">
          {quotesQuery.isError ? (
            <Alert variant="destructive">
              <AlertTitle>Не удалось загрузить предложения</AlertTitle>
              <AlertDescription>
                <p>
                  {quotesQuery.error?.message ??
                    "Не удалось загрузить предложения."}
                </p>
                <Button
                  type="button"
                  variant="outline"
                  size="sm"
                  disabled={quotesQuery.isFetching}
                  onClick={() => void quotesQuery.refetch()}
                >
                  Повторить загрузку
                </Button>
              </AlertDescription>
            </Alert>
          ) : null}
          {quotes.map((quote) => (
            <section
              key={quote.quoteId}
              className="flex flex-col gap-3 sm:flex-row sm:items-start sm:justify-between"
            >
              <div className="flex min-w-0 flex-col gap-2">
                <div className="flex flex-wrap items-center gap-2">
                  <span className="font-medium">
                    {quote.operation === "CANCEL"
                      ? "Отмена заказа"
                      : "Перенос доставки"}
                  </span>
                  <Badge variant="secondary">
                    {SETTLEMENT_LABELS[quote.settlement]}
                  </Badge>
                </div>
                <dl className="grid gap-x-6 gap-y-2 text-sm sm:grid-cols-2">
                  <div>
                    <dt className="text-muted-foreground">
                      Дата доставки по предложению
                    </dt>
                    <dd>
                      {new Intl.DateTimeFormat("ru-RU", {
                        timeZone: "UTC",
                      }).format(new Date(`${quote.deliveryDate}T00:00:00Z`))}
                    </dd>
                  </div>
                  <div>
                    <dt className="text-muted-foreground">Неустойка</dt>
                    <dd className="tabular-nums">
                      {rubles(quote.amountRubles)}
                    </dd>
                  </div>
                  {quote.targetDeliveryDate ? (
                    <div>
                      <dt className="text-muted-foreground">Новая доставка</dt>
                      <dd>
                        {new Intl.DateTimeFormat("ru-RU", {
                          timeZone: "UTC",
                        }).format(
                          new Date(`${quote.targetDeliveryDate}T00:00:00Z`)
                        )}{" "}
                        · {quote.targetWindowStart?.slice(0, 5)}–
                        {quote.targetWindowEnd?.slice(0, 5)}
                      </dd>
                    </div>
                  ) : null}
                </dl>
                <p className="text-sm text-muted-foreground">
                  {APPLICATION_LABELS[quote.applicationState]} ·{" "}
                  {Date.parse(quote.expiresAt) <= now
                    ? "Срок предложения истёк"
                    : `Действует до ${new Intl.DateTimeFormat("ru-RU", { dateStyle: "short", timeStyle: "short", timeZone: quote.warehouseTimeZone }).format(new Date(quote.expiresAt))} (${quote.warehouseTimeZone})`}
                </p>
              </div>
              {quote.applicationState === "OFFERED" &&
              (quote.settlement === "PAYMENT_REQUIRED" ||
                quote.settlement === "POLICY_UNCONFIGURED") ? (
                <Button
                  type="button"
                  variant="outline"
                  size="sm"
                  disabled={
                    !canWaive(quote, now) ||
                    quotesQuery.isError ||
                    quotesQuery.isFetching ||
                    waiveMutation.isPending
                  }
                  onClick={() => {
                    setSelectedQuoteId(quote.quoteId)
                    onEditingChange?.(true)
                    setReason("")
                    setErrorText(null)
                    setValidationError(null)
                  }}
                >
                  Отменить неустойку
                </Button>
              ) : null}
            </section>
          ))}
        </CardContent>
      </Card>
      <Dialog
        open={selectedQuoteId !== null}
        onOpenChange={(open) => {
          if (!open && !waiveMutation.isPending) {
            setSelectedQuoteId(null)
            onEditingChange?.(false)
          }
        }}
      >
        <DialogContent
          className="max-h-[calc(100dvh-2rem)] overflow-y-auto"
          showCloseButton={false}
        >
          <DialogHeader>
            <DialogTitle>Отменить неустойку</DialogTitle>
            <DialogDescription>
              Укажите причину исключения, например форс-мажор. Заказ останется
              без изменений до подтверждения клиента. Срок предложения не
              продлевается.
            </DialogDescription>
          </DialogHeader>
          <form className="flex flex-col gap-4" onSubmit={handleSubmit}>
            {selectedQuote ? (
              <p className="text-sm">
                {selectedQuote.operation === "CANCEL"
                  ? "Отмена заказа"
                  : "Перенос доставки"}{" "}
                · Неустойка: {rubles(selectedQuote.amountRubles)}
              </p>
            ) : null}
            {!selectedQuote || !canWaive(selectedQuote, now) ? (
              <Alert>
                <AlertTitle>Предложение больше недоступно</AlertTitle>
                <AlertDescription>
                  Оно изменилось или истекло. Причина сохранена; новый расчёт
                  запрашивает клиент.
                </AlertDescription>
              </Alert>
            ) : null}
            <FieldGroup>
              <Field
                data-invalid={Boolean(validationError)}
                data-disabled={waiveMutation.isPending}
              >
                <FieldLabel htmlFor={reasonId}>
                  Причина отмены неустойки
                </FieldLabel>
                <Textarea
                  id={reasonId}
                  value={reason}
                  rows={4}
                  disabled={waiveMutation.isPending}
                  aria-invalid={Boolean(validationError)}
                  aria-describedby={`${reasonId}-hint${validationError ? ` ${reasonId}-error` : ""}`}
                  onChange={(event) => {
                    setReason(event.target.value)
                    setValidationError(null)
                  }}
                />
                <FieldDescription id={`${reasonId}-hint`}>
                  Обязательное поле, до 2000 символов. Причина сохранится в
                  истории.
                </FieldDescription>
                {validationError ? (
                  <FieldError id={`${reasonId}-error`}>
                    {validationError}
                  </FieldError>
                ) : null}
              </Field>
            </FieldGroup>
            {errorText ? (
              <Alert variant="destructive">
                <AlertTitle>Неустойка не отменена</AlertTitle>
                <AlertDescription>{errorText}</AlertDescription>
              </Alert>
            ) : null}
            {quotesQuery.isError || !selectedQuote ? (
              <Button
                type="button"
                variant="outline"
                size="sm"
                disabled={quotesQuery.isFetching || waiveMutation.isPending}
                onClick={() => void quotesQuery.refetch()}
              >
                Обновить предложение
              </Button>
            ) : null}
            <DialogFooter>
              <Button
                type="button"
                variant="outline"
                disabled={waiveMutation.isPending}
                onClick={() => {
                  setSelectedQuoteId(null)
                  onEditingChange?.(false)
                }}
              >
                Оставить неустойку
              </Button>
              <Button
                type="submit"
                disabled={
                  !selectedQuote ||
                  !canWaive(selectedQuote, now) ||
                  quotesQuery.isError ||
                  quotesQuery.isFetching ||
                  waiveMutation.isPending
                }
              >
                {waiveMutation.isPending ? (
                  <HugeiconsIcon
                    icon={Loading03Icon}
                    data-icon="inline-start"
                    className="animate-spin motion-reduce:animate-none"
                  />
                ) : null}
                {waiveMutation.isPending ? "Сохранение…" : "Отменить неустойку"}
              </Button>
            </DialogFooter>
          </form>
        </DialogContent>
      </Dialog>
    </>
  )
}
