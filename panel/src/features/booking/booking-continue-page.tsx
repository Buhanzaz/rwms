import { useCallback, useMemo, useRef, useState, type FormEvent } from "react"
import { useMutation, useQueryClient } from "@tanstack/react-query"
import { Copy, LoaderCircle, Share2 } from "lucide-react"
import { useNavigate } from "react-router-dom"
import { toast } from "sonner"

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
  checkRentalItemsAvailability,
  unavailableRentalItemIds,
} from "@/features/booking/api/booking-availability-api"
import { BookingUnavailableDialog } from "@/features/booking/booking-availability"
import { BookingCabinBrowser } from "@/features/booking/booking-cabin-browser"
import { buildManualBookingPresentationGroups } from "@/features/booking/booking-presentation"
import { useBookingSelection } from "@/features/booking/booking-selection-context"
import { useSelectedRentalItemsAvailability } from "@/features/booking/use-selected-rental-items-availability"
import { OrderCommandIdentityRegistry } from "@/features/orders/api/order-command-identity"
import {
  OrderClientChooser,
  type OrderClientChoice,
} from "@/features/orders/components/order-client-chooser"
import type { RentalItemDto } from "@/features/rental-items/model/rental-item"
import { ApiError } from "@/lib/api-client"
import { useWarehouse } from "@/hooks/use-warehouse"

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
  const queryClient = useQueryClient()
  const { selectedItems, removeMany, clear } = useBookingSelection()
  const [search, setSearch] = useState("")
  const [finalSelectedIds, setFinalSelectedIds] = useState(
    () => new Set(selectedItems.map((item) => item.id))
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

  const selectedItemById = useMemo(
    () => new Map(selectedItems.map((item) => [item.id, item])),
    [selectedItems]
  )
  const stagedIds = useMemo(
    () => selectedItems.map((item) => item.id),
    [selectedItems]
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
      if (removed.length > 0) setUnavailableItems(removed)
    },
    [removeMany, selectedItemById]
  )

  useSelectedRentalItemsAvailability({
    accessToken,
    subjectId: actorId,
    warehouseId,
    rentalItemIds: stagedIds,
    onUnavailable: handleUnavailable,
  })

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

      const availability = await checkRentalItemsAvailability({
        accessToken,
        warehouseId,
        rentalItemIds,
      })
      const unavailable = unavailableRentalItemIds(availability, rentalItemIds)
      if (unavailable.length > 0) {
        handleUnavailable(unavailable)
        throw new BookingUnavailableError()
      }

      const workflowFingerprint = JSON.stringify({
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
        inquiryId: conversation.inquiry.id,
        warehouseId,
        groups,
      })

      try {
        const value = await publishClientPresentation({
          accessToken,
          inquiryId: conversation.inquiry.id,
          warehouseId,
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
          const refreshed = await checkRentalItemsAvailability({
            accessToken,
            warehouseId,
            rentalItemIds,
          })
          const unavailableAfterConflict = unavailableRentalItemIds(
            refreshed,
            rentalItemIds
          )
          if (unavailableAfterConflict.length > 0) {
            handleUnavailable(unavailableAfterConflict)
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

  if (selectedItems.length === 0 && !presentation) {
    return (
      <>
        <BookingContinueMessage
          text="Сначала выберите свободные бытовки для бронирования."
          action={
            <Button type="button" onClick={() => navigate("/booking")}>
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
        {selectedItems.length > 0 ? (
          <div className="min-h-80 flex-1">
            <BookingCabinBrowser
              accessToken={accessToken}
              subjectId={actorId}
              warehouseId={warehouseId}
              items={selectedItems}
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
                  onClick={() => navigate("/booking")}
                >
                  Изменить выбор
                </Button>
              }
              footer={
                <p className="text-center text-sm text-muted-foreground">
                  Для представления выбрано: {effectiveFinalSelectedIds.size} из{" "}
                  {selectedItems.length}
                </p>
              }
            />
          </div>
        ) : null}

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
                <OrderClientChooser
                  accessToken={accessToken}
                  actorId={actorId}
                  idPrefix="manual-booking"
                  newClientCreationContext="при создании представления"
                  onChange={handleChoice}
                />
                {errorText ? <FieldError>{errorText}</FieldError> : null}
                <div className="flex flex-wrap justify-end gap-2">
                  <Button
                    type="button"
                    variant="outline"
                    onClick={() => navigate("/booking")}
                  >
                    Назад
                  </Button>
                  <Button
                    type="submit"
                    disabled={
                      !choice ||
                      effectiveFinalSelectedIds.size === 0 ||
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
      </div>

      <BookingUnavailableDialog
        items={unavailableItems}
        onAcknowledge={() => setUnavailableItems([])}
      />

      <Dialog open={presentationOpen} onOpenChange={setPresentationOpen}>
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
              onClick={() => setPresentationOpen(false)}
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
