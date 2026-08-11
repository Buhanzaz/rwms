import { useCallback, useEffect, useMemo, useRef, useState } from "react"
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query"
import { Copy, LoaderCircle, Share2 } from "lucide-react"
import { Navigate, useLocation, useNavigate } from "react-router-dom"
import { toast } from "sonner"

import { Alert, AlertDescription, AlertTitle } from "@/components/ui/alert"
import { Button } from "@/components/ui/button"
import {
  Card,
  CardContent,
  CardDescription,
  CardFooter,
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
  getClientPresentation,
  publishClientPresentation,
  type ClientPresentation,
  type PresentationMode,
} from "@/features/assistant/api/rental-presentations-api"
import { ManagerBookingAlertDialog } from "@/features/assistant/components/manager-booking-alert-dialog"
import { useAuth } from "@/features/auth/use-auth"
import {
  getManualBookingDraftHold,
  MANUAL_BOOKING_DRAFT_HOLD_QUERY_KEY,
} from "@/features/booking/api/manual-booking-drafts-api"
import {
  createManualRentalInquiry,
  listRentalInquiriesForOrder,
  ORDER_RENTAL_INQUIRIES_QUERY_KEY,
} from "@/features/booking/api/rental-inquiries-api"
import { BookingUnavailableDialog } from "@/features/booking/booking-availability"
import { BookingCabinBrowser } from "@/features/booking/booking-cabin-browser"
import { buildManualBookingPresentationGroups } from "@/features/booking/booking-presentation"
import { useBookingSelection } from "@/features/booking/booking-selection-context"
import { useBookingHoldExpiry } from "@/features/booking/use-booking-hold-expiry"
import { OrderCommandIdentityRegistry } from "@/features/orders/api/order-command-identity"
import { getOrder, ORDERS_QUERY_KEY } from "@/features/orders/api/orders-api"
import type { OrderDetail } from "@/features/orders/domain/orders"
import type { RentalItemDto } from "@/features/rental-items/model/rental-item"
import { useWarehouse } from "@/hooks/use-warehouse"
import { ApiError } from "@/lib/api-client"

class BookingUnavailableError extends Error {
  constructor() {
    super("Часть бытовок уже недоступна.")
    this.name = "BookingUnavailableError"
  }
}

const CABIN_AVAILABILITY_CONFLICT_CODES = new Set([
  "MANUAL_BOOKING_HOLD_MISSING",
  "UNIT_NOT_AVAILABLE",
  "UNIT_ORDER_RESERVED",
])

function isCabinAvailabilityConflict(error: unknown) {
  return (
    error instanceof ApiError &&
    error.status === 409 &&
    error.code !== null &&
    CABIN_AVAILABILITY_CONFLICT_CODES.has(error.code)
  )
}

type BookingRoute = {
  orderId: string | null
  clientId: string | null
  mode: PresentationMode
  replacementUnitIds: string[]
}

function routeFromSearch(search: string): BookingRoute {
  const query = new URLSearchParams(search)
  return {
    orderId: query.get("orderId"),
    clientId: query.get("clientId"),
    mode: query.get("mode") === "REPLACEMENT" ? "REPLACEMENT" : "NORMAL",
    replacementUnitIds: query.getAll("replacementUnitId"),
  }
}

function BookingContinueMessage({
  text,
  action,
}: {
  text: string
  action?: React.ReactNode
}) {
  return (
    <div className="flex h-full flex-col items-center justify-center gap-3 rounded-lg border bg-card p-4 text-sm text-muted-foreground">
      <p>{text}</p>
      {action}
    </div>
  )
}

export function BookingContinuePage() {
  const { accessToken, currentUser, status } = useAuth()
  const { selectedWarehouse } = useWarehouse()

  let content
  if (status !== "authenticated" || !accessToken || !currentUser) {
    content = (
      <BookingContinueMessage text="Для бронирования требуется авторизация." />
    )
  } else if (!currentUser.rentalAccess) {
    content = (
      <BookingContinueMessage text="Для пользователя не включён доступ к аренде и бронированию." />
    )
  } else {
    content = (
      <BookingContinuePageState
        accessToken={accessToken}
        actorId={currentUser.id}
        selectedWarehouseId={selectedWarehouse?.id ?? null}
      />
    )
  }

  return (
    <>
      <ManagerBookingAlertDialog />
      {content}
    </>
  )
}

function BookingContinuePageState({
  accessToken,
  actorId,
  selectedWarehouseId,
}: {
  accessToken: string
  actorId: string
  selectedWarehouseId: string | null
}) {
  const navigate = useNavigate()
  const location = useLocation()
  const queryClient = useQueryClient()
  const route = useMemo(
    () => routeFromSearch(location.search),
    [location.search]
  )
  const { draftId, stagedItems, activeHold, removeMany, setActiveHold, clear } =
    useBookingSelection()
  const [search, setSearch] = useState("")
  const [finalSelectedIds, setFinalSelectedIds] = useState(
    () => new Set(stagedItems.map((item) => item.id))
  )
  const [errorText, setErrorText] = useState<string | null>(null)
  const [unavailableItems, setUnavailableItems] = useState<RentalItemDto[]>([])
  const [publishedPresentation, setPublishedPresentation] =
    useState<ClientPresentation | null>(null)
  const [presentationOpen, setPresentationOpen] = useState(false)
  const inquiryIdentity = useRef(new OrderCommandIdentityRegistry())
  const presentationIdentity = useRef(new OrderCommandIdentityRegistry())
  const bookingRoot = { pathname: "/booking", search: location.search }

  const orderQuery = useQuery({
    queryKey: [...ORDERS_QUERY_KEY, "detail", route.orderId],
    queryFn: () => getOrder(accessToken, route.orderId!),
    enabled: Boolean(route.orderId),
  })
  const order = orderQuery.data
  const warehouseId =
    order?.warehouseId ?? activeHold?.warehouseId ?? selectedWarehouseId
  const routeError = validateRoute(order, route, warehouseId)

  const inquiriesQuery = useQuery({
    queryKey: [...ORDER_RENTAL_INQUIRIES_QUERY_KEY, actorId, route.orderId],
    queryFn: () =>
      listRentalInquiriesForOrder({
        accessToken,
        rentalOrderId: route.orderId!,
      }),
    enabled: Boolean(route.orderId && order && !routeError),
  })
  const activeInquiry = inquiriesQuery.data?.find(
    (inquiry) => inquiry.state === "ACTIVE" && inquiry.conversationId === null
  )
  const existingPresentationQuery = useQuery({
    queryKey: ["manual-client-presentation", activeInquiry?.id],
    queryFn: () =>
      getClientPresentation({
        accessToken,
        inquiryId: activeInquiry!.id,
      }),
    enabled: Boolean(activeInquiry),
    retry: false,
  })

  const selectedItemById = useMemo(
    () => new Map(stagedItems.map((item) => [item.id, item])),
    [stagedItems]
  )
  const stagedIds = useMemo(
    () => stagedItems.map((item) => item.id),
    [stagedItems]
  )
  const stagedIdSet = useMemo(() => new Set(stagedIds), [stagedIds])
  const effectiveFinalSelectedIds = useMemo(
    () => new Set([...finalSelectedIds].filter((id) => stagedIdSet.has(id))),
    [finalSelectedIds, stagedIdSet]
  )

  const handleUnavailable = useCallback(
    (ids: string[]) => {
      const removed = ids.flatMap((id) => {
        const item = selectedItemById.get(id)
        return item ? [item] : []
      })
      removeMany(ids)
      setFinalSelectedIds(
        (current) => new Set([...current].filter((id) => !ids.includes(id)))
      )
      if (removed.length > 0) {
        setUnavailableItems((current) => {
          const byId = new Map(current.map((item) => [item.id, item]))
          removed.forEach((item) => byId.set(item.id, item))
          return [...byId.values()]
        })
      }
    },
    [removeMany, selectedItemById]
  )

  const holdQuery = useQuery({
    queryKey: [
      ...MANUAL_BOOKING_DRAFT_HOLD_QUERY_KEY,
      actorId,
      warehouseId,
      draftId,
    ],
    queryFn: () =>
      getManualBookingDraftHold({
        accessToken,
        draftId,
        warehouseId: warehouseId!,
      }),
    enabled: Boolean(
      activeHold && activeHold.draftId === draftId && warehouseId && !routeError
    ),
    staleTime: Infinity,
    gcTime: 2 * 60 * 60 * 1_000,
    refetchInterval: 15_000,
    refetchOnWindowFocus: "always",
    refetchOnReconnect: "always",
  })
  const currentHold = holdQuery.data ?? activeHold
  const heldIds = useMemo(
    () => new Set(currentHold?.rentalItemIds ?? []),
    [currentHold?.rentalItemIds]
  )
  const holdCoversAllStaged = stagedIds.every((id) => heldIds.has(id))
  const holdExpiresAt = currentHold?.expiresAt
    ? Date.parse(currentHold.expiresAt)
    : 0
  const holdExpired = useBookingHoldExpiry(currentHold)

  useEffect(() => {
    if (!holdQuery.data) return
    const refreshedIds = new Set(holdQuery.data.rentalItemIds)
    const lostIds = stagedIds.filter((id) => !refreshedIds.has(id))
    const timer = window.setTimeout(() => {
      setActiveHold(holdQuery.data!)
      if (lostIds.length > 0) handleUnavailable(lostIds)
    }, 0)
    return () => window.clearTimeout(timer)
  }, [handleUnavailable, holdQuery.data, setActiveHold, stagedIds])

  const createPresentationMutation = useMutation({
    mutationFn: async () => {
      if (!order || !route.orderId || !route.clientId || !warehouseId) {
        throw new Error("Заказ для бронирования не определён.")
      }
      const currentRouteError = validateRoute(order, route, warehouseId)
      if (currentRouteError) throw new Error(currentRouteError)

      const rentalItemIds = stagedItems
        .map((item) => item.id)
        .filter((id) => effectiveFinalSelectedIds.has(id))
      const requiredCount =
        route.mode === "REPLACEMENT" ? route.replacementUnitIds.length : null
      if (
        rentalItemIds.length === 0 ||
        (requiredCount !== null && rentalItemIds.length !== requiredCount)
      ) {
        throw new Error(
          requiredCount === null
            ? "Выберите хотя бы одну бытовку."
            : `Для замены нужно выбрать ровно ${requiredCount} бытовок.`
        )
      }
      if (
        !currentHold ||
        currentHold.draftId !== draftId ||
        currentHold.warehouseId !== warehouseId ||
        holdExpired ||
        !rentalItemIds.every((id) => heldIds.has(id))
      ) {
        throw new Error(
          "Резерв истёк или изменился. Вернитесь к выбору и создайте его заново."
        )
      }

      const inquiryFingerprint = JSON.stringify({
        orderId: order.id,
        clientId: order.client.id,
      })
      const inquiry =
        activeInquiry ??
        (await createManualRentalInquiry({
          accessToken,
          clientId: order.client.id,
          rentalOrderId: order.id,
          idempotencyKey: inquiryIdentity.current.keyFor(inquiryFingerprint),
        }))
      const groups = buildManualBookingPresentationGroups(rentalItemIds)
      const publishFingerprint = JSON.stringify({
        draftId,
        inquiryId: inquiry.id,
        warehouseId,
        mode: route.mode,
        replacementUnitIds: route.replacementUnitIds,
        groups,
      })

      try {
        const value = await publishClientPresentation({
          accessToken,
          inquiryId: inquiry.id,
          warehouseId,
          manualBookingDraftId: draftId,
          mode: route.mode,
          replacementUnitIds:
            route.mode === "REPLACEMENT" ? route.replacementUnitIds : undefined,
          idempotencyKey:
            presentationIdentity.current.keyFor(publishFingerprint),
          groups,
        })
        publicPresentationUrl(value)
        return { value, inquiryFingerprint, publishFingerprint }
      } catch (error) {
        if (isCabinAvailabilityConflict(error)) {
          const refreshed = await getManualBookingDraftHold({
            accessToken,
            draftId,
            warehouseId,
          })
          setActiveHold(refreshed)
          const refreshedIds = new Set(refreshed.rentalItemIds)
          const lostIds = stagedIds.filter((id) => !refreshedIds.has(id))
          await Promise.all([
            orderQuery.refetch(),
            inquiriesQuery.refetch(),
            activeInquiry ? existingPresentationQuery.refetch() : undefined,
          ])
          if (lostIds.length > 0) {
            handleUnavailable(lostIds)
            throw new BookingUnavailableError()
          }
        }
        throw error
      }
    },
    onSuccess: ({ value, inquiryFingerprint, publishFingerprint }) => {
      inquiryIdentity.current.confirm(inquiryFingerprint)
      presentationIdentity.current.confirm(publishFingerprint)
      setPublishedPresentation(value)
      setPresentationOpen(true)
      clear()
      setFinalSelectedIds(new Set())
      setErrorText(null)
      void queryClient.invalidateQueries({
        queryKey: ORDER_RENTAL_INQUIRIES_QUERY_KEY,
      })
      toast.success(
        route.mode === "REPLACEMENT"
          ? "Представление для замены создано."
          : "Представление для клиента создано."
      )
    },
    onError: (error) => {
      if (error instanceof BookingUnavailableError) return
      setErrorText(
        error instanceof Error
          ? error.message
          : "Не удалось создать представление для клиента."
      )
    },
  })

  if (!route.orderId || !route.clientId) {
    return (
      <BookingContinueMessage text="Откройте добавление бытовок из карточки существующего заказа." />
    )
  }
  if (orderQuery.isPending) {
    return <BookingContinueMessage text="Проверяем текущий заказ…" />
  }
  if (orderQuery.isError) {
    return <BookingContinueMessage text="Не удалось открыть текущий заказ." />
  }
  if (!order) {
    return <BookingContinueMessage text="Текущий заказ не найден." />
  }
  if (routeError) return <BookingContinueMessage text={routeError} />

  const presentation =
    publishedPresentation ?? existingPresentationQuery.data ?? null
  const publicUrl =
    presentation &&
    (presentation.state === "ACTIVE" ||
      presentation.state === "BOOKING_PENDING")
      ? publicPresentationUrl(presentation)
      : null
  if (stagedItems.length === 0) {
    if (
      inquiriesQuery.isPending ||
      (activeInquiry !== undefined && existingPresentationQuery.isPending)
    ) {
      return <BookingContinueMessage text="Проверяем текущее представление…" />
    }
    if (publicUrl && presentation) {
      return (
        <Card className="mx-auto w-full max-w-3xl">
          <CardHeader>
            <CardTitle>Действующее представление заказа</CardTitle>
            <CardDescription>
              Ссылка уже создана для заказа №{order.number}. Можно снова открыть
              её или вернуться к подбору дополнительных бытовок.
            </CardDescription>
          </CardHeader>
          <CardContent>
            <div className="rounded-lg border bg-muted/40 p-3 text-sm break-all">
              {publicUrl}
            </div>
          </CardContent>
          <CardFooter className="justify-end gap-2">
            <Button
              type="button"
              variant="outline"
              onClick={() => navigate(bookingRoot)}
            >
              Добавить бытовки
            </Button>
            <Button type="button" onClick={() => setPresentationOpen(true)}>
              Открыть ссылку
            </Button>
          </CardFooter>
          <PresentationDialog
            open={presentationOpen}
            presentation={presentation}
            publicUrl={publicUrl}
            onOpenChange={setPresentationOpen}
          />
        </Card>
      )
    }
    return <Navigate replace to={bookingRoot} />
  }

  const requiredCount =
    route.mode === "REPLACEMENT" ? route.replacementUnitIds.length : null
  return (
    <>
      <div className="flex h-full min-h-0 flex-col gap-4 overflow-y-auto">
        <div className="shrink-0">
          <BookingCabinBrowser
            accessToken={accessToken}
            subjectId={actorId}
            warehouseId={warehouseId!}
            items={stagedItems}
            selectedIds={effectiveFinalSelectedIds}
            compact
            search={search}
            onSearchChange={setSearch}
            onToggle={(item) =>
              setFinalSelectedIds((current) => {
                const next = new Set(
                  [...current].filter((id) => stagedIdSet.has(id))
                )
                if (next.has(item.id)) next.delete(item.id)
                else if (requiredCount === null || next.size < requiredCount) {
                  next.add(item.id)
                }
                return next
              })
            }
            emptyText="В выбранных бытовках ничего не найдено."
            actions={
              <Button
                type="button"
                variant="outline"
                onClick={() => navigate(bookingRoot)}
              >
                Изменить выбор
              </Button>
            }
            footer={
              <p className="text-center text-sm text-muted-foreground">
                {requiredCount === null
                  ? `Для представления выбрано: ${effectiveFinalSelectedIds.size} из ${stagedItems.length}`
                  : `Для замены выбрано: ${effectiveFinalSelectedIds.size} из ${requiredCount}`}
              </p>
            }
          />
        </div>

        <Alert
          variant={holdExpired || holdQuery.isError ? "destructive" : "default"}
          className="shrink-0 px-3 py-2"
        >
          <AlertTitle>
            {holdExpired
              ? "Срок резерва истёк"
              : holdQuery.isError
                ? "Не удалось проверить резерв"
                : "Бытовки зарезервированы"}
          </AlertTitle>
          <AlertDescription>
            {holdExpired
              ? "Вернитесь к выбору и создайте резерв заново."
              : holdQuery.isError
                ? "Публикация отключена до успешной фоновой проверки."
                : `Резерв действует до ${new Date(holdExpiresAt).toLocaleString("ru-RU")}.`}
          </AlertDescription>
        </Alert>

        <Card className="w-full max-w-4xl shrink-0 self-center">
          <CardHeader>
            <CardTitle>
              {route.mode === "REPLACEMENT"
                ? "Представление для замены"
                : "Клиентское представление заказа"}
            </CardTitle>
            <CardDescription>
              Заказ №{order.number}, клиент {order.client.displayName}. Новая
              ссылка и выбранные бытовки останутся внутри этого заказа без
              создания отдельного заказа или скрытого AI-диалога.
            </CardDescription>
          </CardHeader>
          <CardContent className="flex flex-col gap-3 text-sm">
            {route.mode === "REPLACEMENT" ? (
              <Alert>
                <AlertTitle>Мебель переносится автоматически</AlertTitle>
                <AlertDescription>
                  Клиент выбирает ровно {requiredCount} замен. Существующие
                  количества мебели нельзя изменить в этом представлении.
                </AlertDescription>
              </Alert>
            ) : null}
            <p className="text-muted-foreground">
              Клиент выберет желаемую дату и срок аренды в представлении. Они
              не назначают фактическую дату логистики.
            </p>
            {errorText ? (
              <p role="alert" className="text-sm text-destructive">
                {errorText}
              </p>
            ) : null}
          </CardContent>
          <CardFooter className="justify-end gap-2">
            <Button
              type="button"
              variant="outline"
              onClick={() => navigate(bookingRoot)}
            >
              Назад
            </Button>
            <Button
              type="button"
              disabled={
                effectiveFinalSelectedIds.size === 0 ||
                (requiredCount !== null &&
                  effectiveFinalSelectedIds.size !== requiredCount) ||
                holdExpired ||
                holdQuery.isError ||
                !holdCoversAllStaged ||
                createPresentationMutation.isPending
              }
              onClick={() => createPresentationMutation.mutate()}
            >
              {createPresentationMutation.isPending ? (
                <LoaderCircle
                  className="animate-spin"
                  data-icon="inline-start"
                />
              ) : (
                <Share2 data-icon="inline-start" />
              )}
              {createPresentationMutation.isPending
                ? "Создаём…"
                : "Создать представление для клиента"}
            </Button>
          </CardFooter>
        </Card>
      </div>

      <BookingUnavailableDialog
        items={unavailableItems}
        onAcknowledge={() => setUnavailableItems([])}
      />
      <PresentationDialog
        open={presentationOpen}
        presentation={presentation}
        publicUrl={publicUrl}
        onOpenChange={(open) => {
          setPresentationOpen(open)
          if (!open && publishedPresentation) navigate(bookingRoot)
        }}
      />
    </>
  )
}

function validateRoute(
  order: OrderDetail | undefined,
  route: BookingRoute,
  warehouseId: string | null
) {
  if (!route.orderId || !route.clientId) {
    return "Откройте добавление бытовок из карточки существующего заказа."
  }
  if (!order) return null
  if (order.client.id !== route.clientId) {
    return "Ссылка бронирования не соответствует клиенту этого заказа."
  }
  if (!warehouseId) return "Склад не выбран."
  if (route.mode === "NORMAL" && !order.permissions.canEdit) {
    return "Обычное бронирование для этого заказа уже недоступно."
  }
  if (route.mode === "REPLACEMENT") {
    if (
      !order.permissions.canReplaceUnits ||
      route.replacementUnitIds.length === 0 ||
      new Set(route.replacementUnitIds).size !==
        route.replacementUnitIds.length ||
      route.replacementUnitIds.some(
        (unitId) =>
          !order.units.some(
            (candidate) => candidate.added && candidate.unit.id === unitId
          )
      )
    ) {
      return "Выбранные бытовки нельзя заменить через это представление."
    }
  }
  return null
}

function publicPresentationUrl(presentation: ClientPresentation) {
  const value = new URL(presentation.publicPath, window.location.origin)
  if (value.origin !== window.location.origin) {
    throw new Error("Сервис вернул ссылку за пределами текущего сайта.")
  }
  return value.toString()
}

function PresentationDialog({
  open,
  presentation,
  publicUrl,
  onOpenChange,
}: {
  open: boolean
  presentation: ClientPresentation | null
  publicUrl: string | null
  onOpenChange: (open: boolean) => void
}) {
  return (
    <Dialog open={open} onOpenChange={onOpenChange}>
      <DialogContent>
        <DialogHeader>
          <DialogTitle>Представление готово</DialogTitle>
          <DialogDescription>
            Бытовки временно удерживаются до{" "}
            {presentation
              ? new Date(presentation.expiresAt).toLocaleString("ru-RU")
              : "истечения срока"}
            . Ссылка добавляет выбор в текущий заказ.
          </DialogDescription>
        </DialogHeader>
        {publicUrl ? (
          <div className="rounded-lg border bg-muted/40 p-3 text-sm break-all">
            {publicUrl}
          </div>
        ) : null}
        <DialogFooter>
          <Button
            type="button"
            variant="outline"
            onClick={() => onOpenChange(false)}
          >
            Закрыть
          </Button>
          <Button
            type="button"
            disabled={!publicUrl}
            onClick={() => {
              if (!publicUrl) return
              void navigator.clipboard.writeText(publicUrl).then(() => {
                toast.success("Ссылка скопирована.")
              })
            }}
          >
            <Copy data-icon="inline-start" />
            Скопировать ссылку
          </Button>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  )
}
