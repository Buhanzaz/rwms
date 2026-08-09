import {
  useCallback,
  useEffect,
  useMemo,
  useRef,
  useState,
  type FormEvent,
} from "react"
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query"
import { Copy, LoaderCircle, Share2 } from "lucide-react"
import { useLocation, useNavigate } from "react-router-dom"
import { toast } from "sonner"

import { Button } from "@/components/ui/button"
import { Alert, AlertDescription, AlertTitle } from "@/components/ui/alert"
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
import { FieldError, FieldGroup } from "@/components/ui/field"
import {
  ASSISTANT_QUERY_KEY,
  createAssistantConversation,
} from "@/features/assistant/api/assistant-api"
import {
  publishClientPresentation,
  type ClientPresentation,
} from "@/features/assistant/api/rental-presentations-api"
import { ManagerBookingAlertDialog } from "@/features/assistant/components/manager-booking-alert-dialog"
import { useAuth } from "@/features/auth/use-auth"
import {
  getManualBookingDraftHold,
  MANUAL_BOOKING_DRAFT_HOLD_QUERY_KEY,
} from "@/features/booking/api/manual-booking-drafts-api"
import { BookingUnavailableDialog } from "@/features/booking/booking-availability"
import { BookingCabinBrowser } from "@/features/booking/booking-cabin-browser"
import { buildManualBookingPresentationGroups } from "@/features/booking/booking-presentation"
import { useBookingSelection } from "@/features/booking/booking-selection-context"
import { OrderCommandIdentityRegistry } from "@/features/orders/api/order-command-identity"
import {
  OrderClientChooser,
  type OrderClientChoice,
} from "@/features/orders/components/order-client-chooser"
import type { RentalItemDto } from "@/features/rental-items/model/rental-item"
import { ApiError } from "@/lib/api-client"
import { useWarehouse } from "@/hooks/use-warehouse"
import {
  CLIENTS_QUERY_KEY,
  getClient,
} from "@/features/clients/api/clients-api"

class BookingUnavailableError extends Error {
  constructor() {
    super("Часть бытовок уже недоступна.")
    this.name = "BookingUnavailableError"
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
  } else if (!selectedWarehouse) {
    content = <BookingContinueMessage text="Склад не выбран." />
  } else {
    content = (
      <BookingContinuePageState
        key={selectedWarehouse.id}
        accessToken={accessToken}
        actorId={currentUser.id}
        warehouseId={selectedWarehouse.id}
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
  warehouseId,
}: {
  accessToken: string
  actorId: string
  warehouseId: string
}) {
  const navigate = useNavigate()
  const location = useLocation()
  const queryClient = useQueryClient()
  const { draftId, stagedItems, activeHold, removeMany, setActiveHold, clear } =
    useBookingSelection()
  const [search, setSearch] = useState("")
  const [finalSelectedIds, setFinalSelectedIds] = useState(
    () => new Set(stagedItems.map((item) => item.id))
  )
  const [choice, setChoice] = useState<OrderClientChoice | null>(null)
  const [errorText, setErrorText] = useState<string | null>(null)
  const [unavailableItems, setUnavailableItems] = useState<RentalItemDto[]>([])
  const [presentation, setPresentation] = useState<ClientPresentation | null>(
    null
  )
  const [presentationOpen, setPresentationOpen] = useState(false)
  const conversationIdentity = useRef(new OrderCommandIdentityRegistry())
  const presentationIdentity = useRef(new OrderCommandIdentityRegistry())
  const requestedClientId = new URLSearchParams(location.search).get("clientId")
  const initialClientQuery = useQuery({
    queryKey: [...CLIENTS_QUERY_KEY, "detail", requestedClientId],
    queryFn: () => getClient(accessToken, requestedClientId!),
    enabled: Boolean(requestedClientId),
  })
  const bookingRoot = { pathname: "/booking", search: location.search }

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
      getManualBookingDraftHold({ accessToken, draftId, warehouseId }),
    enabled: Boolean(activeHold && activeHold.draftId === draftId),
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
  const [now, setNow] = useState(() => Date.now())
  const holdExpiresAt = currentHold?.expiresAt
    ? Date.parse(currentHold.expiresAt)
    : 0
  const holdExpired = !currentHold || holdExpiresAt <= now

  useEffect(() => {
    if (!currentHold) return
    const timer = window.setInterval(() => setNow(Date.now()), 1_000)
    return () => window.clearInterval(timer)
  }, [currentHold])

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

  const handleChoice = useCallback((next: OrderClientChoice | null) => {
    conversationIdentity.current.reset()
    presentationIdentity.current.reset()
    setChoice(next)
    setErrorText(null)
  }, [])

  const createPresentationMutation = useMutation({
    mutationFn: async () => {
      if (!choice) throw new Error("Сначала выберите или создайте клиента.")
      if (choice.kind === "new" && !choice.phone.trim()) {
        throw new Error("Укажите телефон нового клиента.")
      }

      const rentalItemIds = [...effectiveFinalSelectedIds].sort()
      if (rentalItemIds.length === 0) {
        throw new Error("Выберите хотя бы одну бытовку.")
      }
      if (rentalItemIds.length > 100) {
        throw new Error("В представлении может быть не более 100 бытовок.")
      }
      if (
        !currentHold ||
        currentHold.draftId !== draftId ||
        holdExpired ||
        !rentalItemIds.every((id) => heldIds.has(id))
      ) {
        throw new Error(
          "Резерв истёк или изменился. Вернитесь к выбору и создайте его заново."
        )
      }

      const workflowFingerprint = JSON.stringify({
        draftId,
        warehouseId,
        rentalItemIds,
        choice,
      })
      const conversation = await createAssistantConversation({
        accessToken,
        conversationId:
          conversationIdentity.current.keyFor(workflowFingerprint),
        choice,
      })
      const groups = buildManualBookingPresentationGroups(rentalItemIds)
      const publishFingerprint = JSON.stringify({
        draftId,
        inquiryId: conversation.inquiry.id,
        warehouseId,
        groups,
      })

      try {
        const value = await publishClientPresentation({
          accessToken,
          inquiryId: conversation.inquiry.id,
          warehouseId,
          manualBookingDraftId: draftId,
          idempotencyKey:
            presentationIdentity.current.keyFor(publishFingerprint),
          groups,
        })
        if (!value.publicPath) {
          throw new Error("Сервис не вернул публичную ссылку представления.")
        }

        const publicUrl = new URL(value.publicPath, window.location.origin)
        if (publicUrl.origin !== window.location.origin) {
          throw new Error("Сервис вернул ссылку за пределами текущего сайта.")
        }
        return {
          value,
          workflowFingerprint,
          publishFingerprint,
        }
      } catch (error) {
        if (error instanceof ApiError && error.status === 409) {
          const refreshed = await getManualBookingDraftHold({
            accessToken,
            draftId,
            warehouseId,
          })
          setActiveHold(refreshed)
          const refreshedIds = new Set(refreshed.rentalItemIds)
          const lostIds = stagedIds.filter((id) => !refreshedIds.has(id))
          if (lostIds.length > 0) {
            handleUnavailable(lostIds)
            throw new BookingUnavailableError()
          }
        }
        throw error
      }
    },
    onSuccess: ({ value, workflowFingerprint, publishFingerprint }) => {
      conversationIdentity.current.confirm(workflowFingerprint)
      presentationIdentity.current.confirm(publishFingerprint)
      setPresentation(value)
      setPresentationOpen(true)
      clear()
      setFinalSelectedIds(new Set())
      setErrorText(null)
      void queryClient.invalidateQueries({ queryKey: ASSISTANT_QUERY_KEY })
      toast.success("Представление для клиента создано.")
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

  function submit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault()
    setErrorText(null)
    createPresentationMutation.mutate()
  }

  const publicUrl = presentation?.publicPath
    ? new URL(presentation.publicPath, window.location.origin).toString()
    : null

  function changePresentationOpen(open: boolean) {
    setPresentationOpen(open)
    if (!open && presentation) navigate(bookingRoot)
  }

  if (stagedItems.length === 0 && !presentation) {
    return (
      <>
        <BookingContinueMessage
          text="Сначала выберите свободные бытовки для бронирования."
          action={
            <Button type="button" onClick={() => navigate(bookingRoot)}>
              Перейти к выбору
            </Button>
          }
        />
        <BookingUnavailableDialog
          items={unavailableItems}
          onAcknowledge={() => setUnavailableItems([])}
        />
      </>
    )
  }

  return (
    <>
      <div className="flex h-full min-h-0 flex-col gap-4 overflow-y-auto">
        {stagedItems.length > 0 ? (
          <div className="min-h-80 flex-1">
            <BookingCabinBrowser
              accessToken={accessToken}
              subjectId={actorId}
              warehouseId={warehouseId}
              items={stagedItems}
              selectedIds={effectiveFinalSelectedIds}
              search={search}
              onSearchChange={setSearch}
              onToggle={(item) =>
                setFinalSelectedIds((current) => {
                  const next = new Set(
                    [...current].filter((id) => stagedIdSet.has(id))
                  )
                  if (next.has(item.id)) next.delete(item.id)
                  else next.add(item.id)
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
                  Для представления выбрано: {effectiveFinalSelectedIds.size} из{" "}
                  {stagedItems.length}
                </p>
              }
            />
          </div>
        ) : null}

        {stagedItems.length > 0 ? (
          <Alert
            variant={
              holdExpired || holdQuery.isError ? "destructive" : "default"
            }
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
                ? "Вернитесь к выбору и нажмите «Продолжить бронирование», чтобы создать резерв заново."
                : holdQuery.isError
                  ? "Публикация отключена до успешной фоновой проверки."
                  : `Резерв действует до ${new Date(holdExpiresAt).toLocaleString("ru-RU")}.`}
            </AlertDescription>
          </Alert>
        ) : null}

        {stagedItems.length > 0 ? (
          <Card className="shrink-0">
            <CardHeader>
              <CardTitle>Клиентское представление</CardTitle>
              <CardDescription>
                Выберите клиента. Мы создадим тот же защищённый публичный сайт,
                который используется в чате, и свяжем его с новым диалогом.
              </CardDescription>
            </CardHeader>
            <CardContent>
              <form onSubmit={submit}>
                <FieldGroup>
                  {initialClientQuery.isPending && requestedClientId ? (
                    <p className="text-sm text-muted-foreground">
                      Загружаем выбранного клиента…
                    </p>
                  ) : initialClientQuery.isError && requestedClientId ? (
                    <FieldError>
                      {initialClientQuery.error instanceof Error
                        ? initialClientQuery.error.message
                        : "Не удалось загрузить выбранного клиента."}
                    </FieldError>
                  ) : (
                    <OrderClientChooser
                      accessToken={accessToken}
                      actorId={actorId}
                      idPrefix="manual-booking"
                      initialClient={initialClientQuery.data ?? null}
                      newClientCreationContext="при создании представления"
                      onChange={handleChoice}
                    />
                  )}
                  {errorText ? <FieldError>{errorText}</FieldError> : null}
                  <div className="flex flex-wrap justify-end gap-2">
                    <Button
                      type="button"
                      variant="outline"
                      onClick={() => navigate(bookingRoot)}
                    >
                      Назад
                    </Button>
                    <Button
                      type="submit"
                      disabled={
                        !choice ||
                        effectiveFinalSelectedIds.size === 0 ||
                        holdExpired ||
                        holdQuery.isError ||
                        !holdCoversAllStaged ||
                        (choice.kind === "new" && !choice.phone.trim()) ||
                        createPresentationMutation.isPending
                      }
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
                  </div>
                </FieldGroup>
              </form>
            </CardContent>
          </Card>
        ) : null}
      </div>

      <BookingUnavailableDialog
        items={unavailableItems}
        onAcknowledge={() => setUnavailableItems([])}
      />

      <Dialog open={presentationOpen} onOpenChange={changePresentationOpen}>
        <DialogContent>
          <DialogHeader>
            <DialogTitle>Представление готово</DialogTitle>
            <DialogDescription>
              Бытовки временно удерживаются до{" "}
              {presentation
                ? new Date(presentation.expiresAt).toLocaleString("ru-RU")
                : "истечения срока"}
              . Ссылка работает на том же клиентском сайте, что и ссылка из
              чата.
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
              onClick={() => changePresentationOpen(false)}
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
    </>
  )
}
