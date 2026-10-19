import {
  useCallback,
  useEffect,
  useMemo,
  useRef,
  useState,
  type FormEvent,
  type ReactNode,
} from "react"
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query"
import {
  Add01Icon,
  AiChat02Icon,
  Copy01Icon,
  Delete02Icon,
  Loading03Icon,
  PanelLeftIcon,
  SentIcon,
  Share01Icon,
} from "@hugeicons/core-free-icons"
import { HugeiconsIcon } from "@hugeicons/react"
import { toast } from "sonner"
import { useSearchParams } from "react-router-dom"

import { AssistantSearchResults } from "@/features/assistant/components/assistant-search-results"
import { AssistantClarifications } from "@/features/assistant/components/assistant-clarifications"
import { ManagerBookingAlertDialog } from "@/features/assistant/components/manager-booking-alert-dialog"
import {
  assistantSearchGroupLabel,
  reconcileSearchResultSelection,
  selectionGroups,
} from "@/features/assistant/assistant-search-selection"
import {
  archiveAssistantConversation,
  asCabinSelectionUpdate,
  asCabinSearchResultEnvelope,
  asPersistedCabinSearchResultEnvelope,
  ASSISTANT_QUERY_KEY,
  cabinSearchNoticeKey,
  createAssistantConversation,
  getAssistantConversation,
  isCabinSearchResultActive,
  listAssistantConversations,
  mergeCabinSearchNotices,
  mergeCabinSearchResults,
  parseCabinSearchNotices,
  streamAssistantTurn,
  updateAssistantSelection,
  type AssistantMessage,
  type AssistantConversation,
  type AssistantConversationDetail,
  type AssistantTurnRequest,
  type CabinSelection,
  type CabinSearchNotice,
  type CabinSearchResult,
  type ClarificationOption,
  type ClarificationQuestion,
} from "@/features/assistant/api/assistant-api"
import {
  getClientPresentation,
  publishClientPresentation,
} from "@/features/assistant/api/rental-presentations-api"
import {
  Attachment,
  AttachmentContent,
  AttachmentDescription,
  AttachmentMedia,
  AttachmentTitle,
} from "@/components/ui/attachment"
import { Bubble, BubbleContent } from "@/components/ui/bubble"
import { Button } from "@/components/ui/button"
import { Card, CardContent, CardHeader, CardTitle } from "@/components/ui/card"
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
  InputGroup,
  InputGroupAddon,
  InputGroupTextarea,
} from "@/components/ui/input-group"
import { Marker, MarkerContent, MarkerIcon } from "@/components/ui/marker"
import {
  Sheet,
  SheetContent,
  SheetHeader,
  SheetTitle,
} from "@/components/ui/sheet"
import { Separator } from "@/components/ui/separator"
import {
  Message,
  MessageAvatar,
  MessageContent,
  MessageHeader,
} from "@/components/ui/message"
import {
  MessageScroller,
  MessageScrollerButton,
  MessageScrollerContent,
  MessageScrollerItem,
  MessageScrollerProvider,
  MessageScrollerViewport,
} from "@/components/ui/message-scroller"
import {
  OrderClientChooser,
  type OrderClientChoice,
} from "@/features/orders/components/order-client-chooser"
import { OrderCommandIdentityRegistry } from "@/features/orders/api/order-command-identity"
import { useAuth } from "@/features/auth/use-auth"
import {
  CLIENTS_QUERY_KEY,
  getClient,
} from "@/features/clients/api/clients-api"
import {
  clientNeedsContactPerson,
  type RentalClient,
} from "@/features/clients/domain/clients"
import { getOrder, ORDERS_QUERY_KEY } from "@/features/orders/api/orders-api"
import type { OrderDetail } from "@/features/orders/domain/orders"
import { ApiError } from "@/lib/api-client"
import { cn } from "@/lib/utils"

export function AssistantPage() {
  const { accessToken, currentUser } = useAuth()
  const queryClient = useQueryClient()
  const [searchParams] = useSearchParams()
  const requestedClientId = searchParams.get("clientId")
  const requestedOrderId = searchParams.get("orderId")
  const [selectedConversationId, setSelectedConversationId] = useState<
    string | null
  >(null)
  const [creatingNew, setCreatingNew] = useState(
    () => requestedClientId !== null && requestedOrderId === null
  )
  const [mobileHistoryOpen, setMobileHistoryOpen] = useState(false)
  const conversationsQuery = useQuery({
    queryKey: [...ASSISTANT_QUERY_KEY, requestedOrderId ?? "all"],
    queryFn: () =>
      listAssistantConversations(accessToken!, requestedOrderId ?? undefined),
    enabled: Boolean(accessToken && currentUser?.rentalAccess),
  })
  const conversations = conversationsQuery.data ?? []
  const requestedClientQuery = useQuery({
    queryKey: [...CLIENTS_QUERY_KEY, "detail", requestedClientId],
    queryFn: () => getClient(accessToken!, requestedClientId!),
    enabled: Boolean(accessToken && requestedClientId && !requestedOrderId),
  })
  const requestedOrderQuery = useQuery({
    queryKey: [...ORDERS_QUERY_KEY, "detail", requestedOrderId],
    queryFn: () => getOrder(accessToken!, requestedOrderId!),
    enabled: Boolean(accessToken && requestedOrderId),
  })
  const defaultConversationId =
    conversations.find((conversation) => !conversation.archived)?.id ??
    conversations[0]?.id ??
    null
  const activeConversationId =
    !creatingNew && selectedConversationId === null
      ? defaultConversationId
      : selectedConversationId

  const archiveMutation = useMutation({
    mutationFn: (conversationId: string) =>
      archiveAssistantConversation(accessToken!, conversationId),
    onSuccess: async (_, conversationId) => {
      if (activeConversationId === conversationId) {
        setSelectedConversationId(null)
      }
      await queryClient.invalidateQueries({ queryKey: ASSISTANT_QUERY_KEY })
    },
    onError: (error) =>
      toast.error(
        error instanceof Error ? error.message : "Не удалось закрыть диалог."
      ),
  })

  const refreshConversationsAfterTurn = useCallback(
    async (conversationId: string) => {
      await queryClient.invalidateQueries({ queryKey: ASSISTANT_QUERY_KEY })
      const refreshed = await conversationsQuery.refetch()
      if (!refreshed.data) return
      if (
        refreshed.data.some(
          (conversation) => conversation.id === conversationId
        )
      ) {
        return
      }
      setSelectedConversationId((current) =>
        current === conversationId ? null : current
      )
    },
    [conversationsQuery, queryClient]
  )

  if (!accessToken || !currentUser) {
    return (
      <AssistantPageAlertBoundary>
        <CenteredState text="Сессия завершена." />
      </AssistantPageAlertBoundary>
    )
  }
  if (!currentUser.rentalAccess) {
    return (
      <AssistantPageAlertBoundary>
        <CenteredState text="Для пользователя не включён доступ к аренде и чату." />
      </AssistantPageAlertBoundary>
    )
  }
  if (requestedOrderId && requestedOrderQuery.isPending) {
    return (
      <AssistantPageAlertBoundary>
        <CenteredState text="Проверяем текущий заказ…" loading />
      </AssistantPageAlertBoundary>
    )
  }
  if (requestedOrderId && requestedOrderQuery.isError) {
    return (
      <AssistantPageAlertBoundary>
        <CenteredState text="Не удалось открыть заказ для AI-чата." />
      </AssistantPageAlertBoundary>
    )
  }
  if (
    requestedOrderId &&
    (!requestedClientId ||
      requestedOrderQuery.data?.client.id !== requestedClientId)
  ) {
    return (
      <AssistantPageAlertBoundary>
        <CenteredState text="Ссылка AI-чата не соответствует клиенту этого заказа." />
      </AssistantPageAlertBoundary>
    )
  }
  if (
    requestedOrderQuery.data &&
    !requestedOrderQuery.data.permissions.canEdit
  ) {
    return (
      <AssistantPageAlertBoundary>
        <CenteredState text="Обычное дополнение этого заказа через AI-чат уже недоступно." />
      </AssistantPageAlertBoundary>
    )
  }
  if (conversationsQuery.isPending) {
    return (
      <AssistantPageAlertBoundary>
        <CenteredState text="Загружаем диалоги…" loading />
      </AssistantPageAlertBoundary>
    )
  }
  if (conversationsQuery.isError) {
    return (
      <AssistantPageAlertBoundary>
        <CenteredState
          text={
            conversationsQuery.error instanceof Error
              ? conversationsQuery.error.message
              : "Не удалось загрузить диалоги."
          }
        />
      </AssistantPageAlertBoundary>
    )
  }

  return (
    <AssistantPageAlertBoundary>
      <div className="-m-4 flex h-[calc(100%+2rem)] min-h-0 lg:-m-3 lg:h-[calc(100%+1.5rem)]">
        <aside className="hidden w-64 shrink-0 flex-col border-r bg-muted/20 lg:flex">
          <div className="flex items-center justify-between border-b p-3">
            <span className="text-sm font-semibold">Диалоги</span>
            {!requestedOrderId ? (
              <Button
                type="button"
                size="icon-sm"
                variant="ghost"
                aria-label="Новый диалог"
                onClick={() => {
                  setCreatingNew(true)
                  setSelectedConversationId(null)
                }}
              >
                <HugeiconsIcon icon={Add01Icon} />
              </Button>
            ) : null}
          </div>
          <ConversationList
            conversations={conversations}
            activeConversationId={activeConversationId}
            archivePending={archiveMutation.isPending}
            onSelect={(conversationId) => {
              setCreatingNew(false)
              setSelectedConversationId(conversationId)
            }}
            onArchive={(conversationId) =>
              archiveMutation.mutate(conversationId)
            }
          />
        </aside>

        <Sheet open={mobileHistoryOpen} onOpenChange={setMobileHistoryOpen}>
          <SheetContent
            side="left"
            className="w-[min(22rem,85vw)] p-0 lg:hidden"
          >
            <SheetHeader className="border-b pr-12">
              <SheetTitle>Диалоги</SheetTitle>
            </SheetHeader>
            <ConversationList
              conversations={conversations}
              activeConversationId={activeConversationId}
              archivePending={archiveMutation.isPending}
              onSelect={(conversationId) => {
                setCreatingNew(false)
                setSelectedConversationId(conversationId)
                setMobileHistoryOpen(false)
              }}
              onArchive={(conversationId) =>
                archiveMutation.mutate(conversationId)
              }
            />
          </SheetContent>
        </Sheet>

        <main className="flex min-h-0 min-w-0 flex-1 flex-col">
          {activeConversationId && !creatingNew ? (
            <ConversationWorkspace
              subjectId={currentUser.id}
              key={activeConversationId}
              accessToken={accessToken}
              conversationId={activeConversationId}
              clientName={
                conversations.find(
                  (conversation) => conversation.id === activeConversationId
                )?.clientDisplayName ?? "Клиент"
              }
              archived={Boolean(
                conversations.find(
                  (conversation) => conversation.id === activeConversationId
                )?.archived
              )}
              onOpenHistory={() => setMobileHistoryOpen(true)}
              onNew={
                requestedOrderId
                  ? undefined
                  : () => {
                      setCreatingNew(true)
                      setSelectedConversationId(null)
                    }
              }
              onTurnCompleted={refreshConversationsAfterTurn}
            />
          ) : requestedOrderQuery.data && requestedOrderId ? (
            <OrderConversationGate
              accessToken={accessToken}
              order={requestedOrderQuery.data}
              onCreated={async (conversationId) => {
                await queryClient.invalidateQueries({
                  queryKey: ASSISTANT_QUERY_KEY,
                })
                setCreatingNew(false)
                setSelectedConversationId(conversationId)
              }}
            />
          ) : (
            <ClientGate
              accessToken={accessToken}
              actorId={currentUser.id}
              responsibleManagerDisplayName={
                currentUser.displayName || currentUser.id
              }
              initialClient={requestedClientQuery.data ?? null}
              initialClientLoading={
                requestedClientId !== null && requestedClientQuery.isPending
              }
              initialClientError={
                requestedClientId !== null && requestedClientQuery.isError
                  ? requestedClientQuery.error
                  : null
              }
              onCancel={
                conversations.length > 0
                  ? () => {
                      setCreatingNew(false)
                      setSelectedConversationId(defaultConversationId)
                    }
                  : undefined
              }
              onCreated={async (conversationId) => {
                await queryClient.invalidateQueries({
                  queryKey: ASSISTANT_QUERY_KEY,
                })
                setCreatingNew(false)
                setSelectedConversationId(conversationId)
              }}
            />
          )}
        </main>
      </div>
    </AssistantPageAlertBoundary>
  )
}

function ConversationList({
  conversations,
  activeConversationId,
  archivePending,
  onSelect,
  onArchive,
}: {
  conversations: AssistantConversation[]
  activeConversationId: string | null
  archivePending: boolean
  onSelect: (conversationId: string) => void
  onArchive: (conversationId: string) => void
}) {
  const active = conversations.filter((conversation) => !conversation.archived)
  const archived = conversations.filter((conversation) => conversation.archived)
  return (
    <div className="min-h-0 flex-1 overflow-y-auto p-2">
      <ConversationListSection
        title="Текущие"
        conversations={active}
        activeConversationId={activeConversationId}
        archivePending={archivePending}
        onSelect={onSelect}
        onArchive={onArchive}
      />
      {archived.length > 0 ? (
        <ConversationListSection
          title="История"
          conversations={archived}
          activeConversationId={activeConversationId}
          archivePending={archivePending}
          onSelect={onSelect}
          onArchive={onArchive}
        />
      ) : null}
    </div>
  )
}

function ConversationListSection({
  title,
  conversations,
  activeConversationId,
  archivePending,
  onSelect,
  onArchive,
}: {
  title: string
  conversations: AssistantConversation[]
  activeConversationId: string | null
  archivePending: boolean
  onSelect: (conversationId: string) => void
  onArchive: (conversationId: string) => void
}) {
  if (conversations.length === 0) return null
  return (
    <section className="mb-3 last:mb-0">
      <p className="px-2 py-1 text-xs font-medium text-muted-foreground">
        {title}
      </p>
      <div className="space-y-1">
        {conversations.map((conversation) => (
          <div key={conversation.id} className="group relative">
            <Button
              type="button"
              variant={
                activeConversationId === conversation.id ? "secondary" : "ghost"
              }
              className="h-auto w-full justify-start px-3 py-2 pr-9 text-left"
              onClick={() => onSelect(conversation.id)}
            >
              <span className="min-w-0">
                <span className="block truncate text-sm">
                  {conversation.clientDisplayName ?? "Клиент"}
                </span>
                <span className="block truncate text-xs font-normal text-muted-foreground">
                  {conversation.archived ? "Закрыт · " : ""}
                  {formatConversationDate(conversation.updatedAt)}
                </span>
              </span>
            </Button>
            {!conversation.archived ? (
              <Button
                type="button"
                size="icon-xs"
                variant="ghost"
                className="absolute top-2 right-2 opacity-100 lg:opacity-0 lg:group-hover:opacity-100"
                aria-label={`Закрыть диалог ${conversation.clientDisplayName ?? ""}`}
                disabled={archivePending}
                onClick={() => onArchive(conversation.id)}
              >
                <HugeiconsIcon icon={Delete02Icon} />
              </Button>
            ) : null}
          </div>
        ))}
      </div>
    </section>
  )
}

function AssistantPageAlertBoundary({ children }: { children: ReactNode }) {
  return (
    <>
      <ManagerBookingAlertDialog />
      {children}
    </>
  )
}

function OrderConversationGate({
  accessToken,
  order,
  onCreated,
}: {
  accessToken: string
  order: OrderDetail
  onCreated: (conversationId: string) => Promise<void>
}) {
  const [errorText, setErrorText] = useState<string | null>(null)
  const command = useRef(new OrderCommandIdentityRegistry())
  const mutation = useMutation({
    mutationFn: () => {
      const fingerprint = JSON.stringify({
        orderId: order.id,
        clientId: order.client.id,
      })
      return createAssistantConversation({
        accessToken,
        conversationId: command.current.keyFor(fingerprint),
        choice: { kind: "existing", client: order.client },
        rentalOrderId: order.id,
      }).then((response) => ({ response, fingerprint }))
    },
    onSuccess: async ({ response, fingerprint }) => {
      command.current.confirm(fingerprint)
      await onCreated(response.conversation.id)
    },
    onError: (error) =>
      setErrorText(
        error instanceof Error
          ? error.message
          : "Не удалось открыть чат этого заказа."
      ),
  })

  return (
    <div className="flex min-h-0 flex-1 items-center justify-center overflow-y-auto p-6">
      <Card className="w-full max-w-3xl">
        <CardHeader>
          <div className="mb-2 flex size-11 items-center justify-center rounded-xl bg-primary/10 text-primary">
            <HugeiconsIcon icon={AiChat02Icon} className="size-6" />
          </div>
          <CardTitle>AI-чат заказа №{order.number}</CardTitle>
          <p className="text-sm text-muted-foreground">
            Клиент: {order.client.displayName}. Диалог добавит выбранные бытовки
            и мебель в этот заказ. Склад фиксируется сервером по заказу и первой
            выбранной бытовке.
          </p>
        </CardHeader>
        <CardContent className="flex flex-col gap-3">
          {errorText ? <FieldError>{errorText}</FieldError> : null}
          <div className="flex justify-end">
            <Button
              type="button"
              disabled={mutation.isPending}
              onClick={() => mutation.mutate()}
            >
              <HugeiconsIcon
                icon={mutation.isPending ? Loading03Icon : AiChat02Icon}
                className={cn(mutation.isPending && "animate-spin")}
              />
              {mutation.isPending ? "Открываем…" : "Открыть чат заказа"}
            </Button>
          </div>
        </CardContent>
      </Card>
    </div>
  )
}

function ClientGate({
  accessToken,
  actorId,
  responsibleManagerDisplayName,
  initialClient,
  initialClientLoading,
  initialClientError,
  onCancel,
  onCreated,
}: {
  accessToken: string
  actorId: string
  responsibleManagerDisplayName: string
  initialClient: RentalClient | null
  initialClientLoading: boolean
  initialClientError: unknown
  onCancel?: () => void
  onCreated: (conversationId: string) => Promise<void>
}) {
  const [choice, setChoice] = useState<OrderClientChoice | null>(null)
  const [errorText, setErrorText] = useState<string | null>(null)
  const command = useRef(new OrderCommandIdentityRegistry())
  const handleChoice = useCallback((next: OrderClientChoice | null) => {
    command.current.reset()
    setChoice(next)
    setErrorText(null)
  }, [])
  const mutation = useMutation({
    mutationFn: (selected: OrderClientChoice) => {
      const fingerprint = JSON.stringify(selected)
      return createAssistantConversation({
        accessToken,
        conversationId: command.current.keyFor(fingerprint),
        choice: selected,
      })
    },
    onSuccess: async (response, selected) => {
      command.current.confirm(JSON.stringify(selected))
      await onCreated(response.conversation.id)
    },
    onError: (error) =>
      setErrorText(
        error instanceof Error ? error.message : "Не удалось открыть диалог."
      ),
  })

  function submit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault()
    if (!choice) {
      setErrorText("Сначала выберите или создайте клиента.")
      return
    }
    if (choice.kind === "new" && !choice.phone) {
      setErrorText("Укажите телефон нового клиента.")
      return
    }
    if (
      choice.kind === "new" &&
      clientNeedsContactPerson(choice.clientType) &&
      !choice.contactPerson
    ) {
      setErrorText("Укажите основное контактное лицо нового клиента.")
      return
    }
    mutation.mutate(choice)
  }

  return (
    <div className="flex min-h-0 flex-1 items-center justify-center overflow-y-auto p-6">
      <Card className="w-full max-w-4xl">
        <CardHeader>
          <div className="mb-2 flex size-11 items-center justify-center rounded-xl bg-primary/10 text-primary">
            <HugeiconsIcon icon={AiChat02Icon} className="size-6" />
          </div>
          <CardTitle>Клиент перед началом диалога</CardTitle>
          <p className="text-sm text-muted-foreground">
            Как и в бронировании, сначала найдите существующего клиента или
            создайте нового. Изменить клиента внутри диалога нельзя.
          </p>
        </CardHeader>
        <CardContent>
          <form onSubmit={submit}>
            <FieldGroup>
              {initialClientLoading ? (
                <p className="text-sm text-muted-foreground">
                  Загружаем выбранного клиента…
                </p>
              ) : initialClientError ? (
                <FieldError>
                  {initialClientError instanceof Error
                    ? initialClientError.message
                    : "Не удалось загрузить выбранного клиента."}
                </FieldError>
              ) : (
                <OrderClientChooser
                  accessToken={accessToken}
                  actorId={actorId}
                  idPrefix="assistant"
                  responsibleManagerDisplayName={responsibleManagerDisplayName}
                  initialClient={initialClient}
                  newClientCreationContext="после открытия диалога"
                  onChange={handleChoice}
                />
              )}
              {errorText ? <FieldError>{errorText}</FieldError> : null}
              <div className="flex justify-end gap-2">
                {onCancel ? (
                  <Button type="button" variant="outline" onClick={onCancel}>
                    Отмена
                  </Button>
                ) : null}
                <Button
                  type="submit"
                  disabled={
                    !choice ||
                    initialClientLoading ||
                    initialClientError !== null ||
                    (choice.kind === "new" && !choice.phone) ||
                    (choice.kind === "new" &&
                      clientNeedsContactPerson(choice.clientType) &&
                      !choice.contactPerson) ||
                    mutation.isPending
                  }
                >
                  {mutation.isPending ? (
                    <HugeiconsIcon
                      icon={Loading03Icon}
                      className="animate-spin"
                    />
                  ) : (
                    <HugeiconsIcon icon={AiChat02Icon} />
                  )}
                  {mutation.isPending ? "Открываем…" : "Перейти в чат"}
                </Button>
              </div>
            </FieldGroup>
          </form>
        </CardContent>
      </Card>
    </div>
  )
}

function ConversationWorkspace({
  accessToken,
  subjectId,
  conversationId,
  clientName,
  archived,
  onOpenHistory,
  onNew,
  onTurnCompleted,
}: {
  accessToken: string
  conversationId: string
  subjectId: string
  clientName: string
  archived: boolean
  onOpenHistory: () => void
  onNew?: () => void
  onTurnCompleted: (conversationId: string) => Promise<void>
}) {
  const queryClient = useQueryClient()
  const detailQuery = useQuery({
    queryKey: [...ASSISTANT_QUERY_KEY, conversationId],
    queryFn: () => getAssistantConversation(accessToken, conversationId),
  })
  const [draft, setDraft] = useState("")
  const [localMessages, setLocalMessages] = useState<AssistantMessage[]>([])
  const [sending, setSending] = useState(false)
  const [toolRunning, setToolRunning] = useState(false)
  const [errorText, setErrorText] = useState<string | null>(null)
  const [liveSearchResult, setLiveSearchResult] =
    useState<CabinSearchResult | null>(null)
  const [liveClarifications, setLiveClarifications] = useState<
    ClarificationQuestion[] | null
  >(null)
  const [liveCurrentSelection, setLiveCurrentSelection] = useState<
    CabinSelection | null | undefined
  >(undefined)
  const [searchNow, setSearchNow] = useState(() => Date.now())
  const [searchResultsVisible, setSearchResultsVisible] = useState(true)
  const [presentationOpen, setPresentationOpen] = useState(false)
  const presentationCommand = useRef(new OrderCommandIdentityRegistry())
  const selectionCommand = useRef(new OrderCommandIdentityRegistry())
  const turnController = useRef<AbortController | null>(null)

  useEffect(() => () => turnController.current?.abort(), [])

  const persistedSearchEnvelope = useMemo(
    () =>
      asPersistedCabinSearchResultEnvelope(detailQuery.data?.lastSearchResult),
    [detailQuery.data?.lastSearchResult]
  )
  const persistedSearchResult = persistedSearchEnvelope?.data ?? null
  const searchResultCandidate = liveSearchResult ?? persistedSearchResult
  const searchResult =
    searchResultCandidate &&
    isCabinSearchResultActive(searchResultCandidate, searchNow)
      ? searchResultCandidate
      : null
  useEffect(() => {
    if (!searchResultCandidate) return
    const expiresAt = Date.parse(searchResultCandidate.expiresAt)
    if (!Number.isFinite(expiresAt)) return
    const timeout = window.setTimeout(
      () => {
        setSearchNow(Date.now())
      },
      Math.max(0, expiresAt - Date.now() + 50)
    )
    return () => window.clearTimeout(timeout)
  }, [searchResultCandidate])
  const clarifications =
    liveClarifications ?? detailQuery.data?.clarifications ?? []
  const pendingClarification = [...clarifications]
    .filter((question) => question.status === "PENDING")
    .sort((left, right) => left.sequenceNumber - right.sequenceNumber)[0]
  const currentSelection =
    liveCurrentSelection === undefined
      ? (detailQuery.data?.currentSelection ?? null)
      : liveCurrentSelection
  const selectedIds = useMemo(
    () => new Set(currentSelection?.rentalItemIds ?? []),
    [currentSelection?.rentalItemIds]
  )
  const visibleSearchResult = useMemo(() => {
    if (!searchResult) return null
    const groups = searchResult.groups
      .filter((entry) =>
        Boolean(entry.group.cabinType?.trim() && entry.group.finish?.trim())
      )
      .map((entry) => ({
        ...entry,
        cabins: sending
          ? entry.cabins
          : entry.cabins.filter((cabin) => selectedIds.has(cabin.id)),
      }))
      .filter((entry) => entry.cabins.length > 0)
    return groups.length > 0 ? { ...searchResult, groups } : null
  }, [searchResult, selectedIds, sending])
  const selectionMutation = useMutation({
    mutationFn: (next: Set<string>) => {
      const warehouseId =
        currentSelection?.warehouseId ?? searchResult?.warehouseId
      if (!warehouseId) throw new Error("Склад текущей выборки не определён.")
      const rentalItemIds = [...next].sort()
      const fingerprint = JSON.stringify({ warehouseId, rentalItemIds })
      const removedIds = [...selectedIds].filter((id) => !next.has(id))
      return updateAssistantSelection({
        accessToken,
        conversationId,
        idempotencyKey: selectionCommand.current.keyFor(fingerprint),
        warehouseId,
        rentalItemIds,
      }).then((selection) => ({
        selection,
        fingerprint,
        removedIds,
      }))
    },
    onSuccess: ({ selection: nextSelection, fingerprint, removedIds }) => {
      selectionCommand.current.confirm(fingerprint)
      queryClient.setQueryData<AssistantConversationDetail>(
        [...ASSISTANT_QUERY_KEY, conversationId],
        (current) =>
          current
            ? {
                ...current,
                currentSelection:
                  nextSelection.rentalItemIds.length > 0 ? nextSelection : null,
              }
            : current
      )
      setLiveCurrentSelection(
        nextSelection.rentalItemIds.length > 0 ? nextSelection : null
      )
      if (removedIds.length > 0 && searchResultCandidate) {
        const nextResult = reconcileSearchResultSelection(
          searchResultCandidate,
          nextSelection,
          new Set(removedIds)
        )
        setLiveSearchResult(nextResult)
        setSearchNow(Date.now())
      }
      if (nextSelection.rentalItemIds.length === 0) {
        toast.success("Выборка освобождена.")
      }
    },
    onError: (error) =>
      toast.error(
        error instanceof Error
          ? error.message
          : "Не удалось изменить текущую выборку."
      ),
  })
  const handleSelectionChange = useCallback(
    (next: Set<string>) => selectionMutation.mutate(next),
    [selectionMutation]
  )
  const presentationQueryKey = [
    "assistant-client-presentation",
    detailQuery.data?.conversation.rentalInquiryId,
  ] as const

  const presentationQuery = useQuery({
    queryKey: presentationQueryKey,
    queryFn: () =>
      getClientPresentation({
        accessToken,
        inquiryId: detailQuery.data!.conversation.rentalInquiryId,
      }),
    enabled: Boolean(detailQuery.data?.conversation.rentalInquiryId),
    retry: false,
  })

  const presentation = presentationQuery.data ?? null

  const publishMutation = useMutation({
    mutationFn: () => {
      if (!searchResult || !detailQuery.data) {
        throw new Error("Сначала выполните подбор бытовок.")
      }
      const groups = selectionGroups(searchResult, selectedIds)
      if (groups.length === 0) {
        throw new Error("Выберите хотя бы одну бытовку.")
      }
      const fingerprint = JSON.stringify({
        warehouseId: searchResult.warehouseId,
        groups,
      })
      const publishedIds = [...selectedIds]
      return publishClientPresentation({
        accessToken,
        inquiryId: detailQuery.data.conversation.rentalInquiryId,
        warehouseId: searchResult.warehouseId,
        idempotencyKey: presentationCommand.current.keyFor(fingerprint),
        groups,
      }).then((value) => ({ value, fingerprint, publishedIds }))
    },
    onSuccess: ({ value, fingerprint, publishedIds }) => {
      presentationCommand.current.confirm(fingerprint)
      queryClient.setQueryData(presentationQueryKey, value)
      if (searchResult) {
        const retained = retainCabins(searchResult, new Set(publishedIds))
        setLiveSearchResult(retained)
      }
      setPresentationOpen(true)
      toast.success("Представление для клиента создано.")
    },
    onError: (error) =>
      toast.error(
        error instanceof Error
          ? error.message
          : "Не удалось создать представление."
      ),
  })

  async function sendTurn(request: AssistantTurnRequest, userContent: string) {
    if (sending || archived || turnController.current) return
    const controller = new AbortController()
    turnController.current = controller
    const userMessage: AssistantMessage = {
      id: crypto.randomUUID(),
      role: "USER",
      content: userContent,
      createdAt: new Date().toISOString(),
    }
    const assistantMessage: AssistantMessage = {
      id: crypto.randomUUID(),
      role: "ASSISTANT",
      content: "",
      createdAt: new Date().toISOString(),
    }
    setErrorText(null)
    setToolRunning(false)
    setSending(true)
    setLocalMessages([userMessage, assistantMessage])
    let failedCode: string | null = null
    let terminalEvent = false
    let turnSearchResult: CabinSearchResult | null = null
    let turnSearchResultMode: "APPEND" | "REPLACE" | null = null
    const searchResultBeforeTurn = searchResult
    try {
      await streamAssistantTurn({
        accessToken,
        conversationId,
        ...request,
        signal: controller.signal,
        onEvent: (event) => {
          if (controller.signal.aborted) return
          if (event.event === "assistant.delta" && event.delta) {
            setLocalMessages((current) =>
              current.map((item) =>
                item.id === assistantMessage.id
                  ? { ...item, content: item.content + event.delta }
                  : item
              )
            )
          }
          if (event.event === "tool.started") setToolRunning(true)
          if (event.event === "tool.completed") setToolRunning(false)
          if (
            (event.event === "clarification.requested" ||
              event.event === "clarification.answered") &&
            event.clarification
          ) {
            setLiveClarifications((current) =>
              mergeClarifications(
                current ?? detailQuery.data?.clarifications ?? [],
                event.clarification!
              )
            )
          }
          const selectionUpdate = asCabinSelectionUpdate(event)
          if (selectionUpdate) {
            const nextSelection =
              selectionUpdate.rentalItemIds.length > 0 ? selectionUpdate : null
            setLiveCurrentSelection(nextSelection)
            queryClient.setQueryData<AssistantConversationDetail>(
              [...ASSISTANT_QUERY_KEY, conversationId],
              (current) =>
                current
                  ? { ...current, currentSelection: nextSelection }
                  : current
            )
            setLiveSearchResult((current) => {
              const source = current ?? persistedSearchResult
              return source
                ? reconcileSearchResultSelection(
                    source,
                    selectionUpdate,
                    new Set(selectionUpdate.removedRentalItemIds)
                  )
                : current
            })
            setSearchNow(Date.now())
          }
          const nextSearch = asCabinSearchResultEnvelope(event)
          if (nextSearch) {
            if (nextSearch.resultMode === "APPEND") {
              turnSearchResultMode = "APPEND"
            } else if (!turnSearchResultMode) {
              turnSearchResultMode = "REPLACE"
            }
            turnSearchResult = mergeCabinSearchResults(
              turnSearchResult,
              nextSearch.data
            )
            const displayedSearchResult =
              turnSearchResultMode === "APPEND"
                ? mergeCabinSearchResults(
                    searchResultBeforeTurn,
                    turnSearchResult
                  )
                : turnSearchResult
            setLiveSearchResult(displayedSearchResult)
            if (nextSearch.notices.length > 0) {
              setLocalMessages((current) =>
                current.map((item) =>
                  item.id === userMessage.id
                    ? {
                        ...item,
                        searchNotices: mergeCabinSearchNotices(
                          item.searchNotices,
                          nextSearch.notices
                        ),
                      }
                    : item
                )
              )
            }
          }
          if (event.event === "turn.failed") {
            terminalEvent = true
            failedCode = event.code ?? "TURN_FAILED"
          }
          if (event.event === "turn.completed") terminalEvent = true
        },
      })
      if (controller.signal.aborted) return
      if (failedCode) {
        throw new Error("LLM не смог завершить ответ. Повторите запрос.")
      }
      if (!terminalEvent) {
        throw new Error("Поток ответа прервался. Повторите запрос.")
      }
      await queryClient.invalidateQueries({
        queryKey: [...ASSISTANT_QUERY_KEY, conversationId],
      })
      if (controller.signal.aborted) return
      await detailQuery.refetch()
      if (controller.signal.aborted) return
      await onTurnCompleted(conversationId)
      if (controller.signal.aborted) return
      setLocalMessages([])
      setLiveClarifications(null)
      setLiveCurrentSelection(undefined)
    } catch (error) {
      if (controller.signal.aborted) return
      if (
        error instanceof ApiError &&
        error.status === 409 &&
        "clarificationAnswer" in request
      ) {
        setLiveClarifications(null)
        setErrorText(
          "Уточнение уже изменилось. Показан текущий вопрос сервера."
        )
        await queryClient.invalidateQueries({
          queryKey: [...ASSISTANT_QUERY_KEY, conversationId],
        })
        if (controller.signal.aborted) return
        await detailQuery.refetch()
      } else {
        setErrorText(
          error instanceof Error ? error.message : "Не удалось получить ответ."
        )
      }
    } finally {
      if (turnController.current === controller) turnController.current = null
      if (!controller.signal.aborted) {
        setToolRunning(false)
        setSending(false)
      }
    }
  }

  async function sendMessage(messageOverride?: string) {
    if (pendingClarification) return
    const message = (messageOverride ?? draft).trim()
    if (!message) return
    if (messageOverride === undefined) setDraft("")
    await sendTurn({ message }, message)
  }

  function answerClarification(
    question: ClarificationQuestion,
    option: ClarificationOption
  ) {
    void sendTurn(
      {
        clarificationAnswer: {
          questionId: question.id,
          optionId: option.id,
        },
      },
      `Ответ на «${question.prompt}»: ${option.label}`
    )
  }

  if (detailQuery.isPending) {
    return <CenteredState text="Загружаем историю…" loading />
  }
  if (detailQuery.isError) {
    return (
      <CenteredState
        text={
          detailQuery.error instanceof Error
            ? detailQuery.error.message
            : "Не удалось загрузить диалог."
        }
      />
    )
  }

  const messages = [...detailQuery.data.messages, ...localMessages]
  const messageItems = renderConversationMessageItems(messages, sending)
  const publicUrl =
    presentation?.publicPath &&
    new URL(presentation.publicPath, window.location.origin).toString()

  return (
    <div className="flex min-h-0 flex-1 flex-col">
      <header className="flex h-14 shrink-0 items-center justify-between gap-3 border-b px-4">
        <div className="flex min-w-0 items-center gap-2">
          <Button
            type="button"
            variant="ghost"
            size="icon-sm"
            className="shrink-0 hover:bg-muted hover:text-foreground active:bg-muted lg:hidden"
            aria-label="Открыть историю диалогов"
            onClick={onOpenHistory}
          >
            <HugeiconsIcon icon={PanelLeftIcon} strokeWidth={2} />
          </Button>
          <Separator
            orientation="vertical"
            className="h-4 lg:hidden data-vertical:self-center"
          />
          <div className="min-w-0">
            <p className="truncate text-sm font-semibold">{clientName}</p>
            <p className="text-xs text-muted-foreground">
              LLM-подбор доступных бытовок
            </p>
          </div>
        </div>
        <div className="flex items-center gap-2">
          {presentation?.publicPath ? (
            <Button
              type="button"
              variant="outline"
              size="sm"
              onClick={() => setPresentationOpen(true)}
            >
              <HugeiconsIcon icon={Share01Icon} />
              Ссылка клиенту
            </Button>
          ) : null}
          {onNew ? (
            <Button type="button" variant="outline" size="sm" onClick={onNew}>
              <HugeiconsIcon icon={Add01Icon} />
              Новый диалог
            </Button>
          ) : null}
        </div>
      </header>

      <MessageScrollerProvider autoScroll defaultScrollPosition="end">
        <MessageScroller className="h-auto w-full flex-1">
          <MessageScrollerViewport
            aria-label="История диалога"
            className="h-auto flex-1"
          >
            <MessageScrollerContent className="w-full px-5 py-6">
              {messages.length === 0 ? (
                <div className="mx-auto flex min-h-64 w-full max-w-4xl flex-1 flex-col items-center justify-center text-center">
                  <div className="mb-4 flex size-12 items-center justify-center rounded-2xl bg-primary/10 text-primary">
                    <HugeiconsIcon icon={AiChat02Icon} className="size-7" />
                  </div>
                  <h2 className="text-lg font-semibold">
                    Что подобрать для клиента?
                  </h2>
                  <p className="mt-2 max-w-md text-sm text-muted-foreground">
                    Например: «Покажи 10 свободных БК-1 с ДВП, категорий ИТР и
                    Новая, в Санкт-Петербурге». Фильтры и параметры определяет
                    только LLM через разрешённые инструменты.
                  </p>
                </div>
              ) : (
                messageItems
              )}
              <AssistantClarifications
                questions={clarifications}
                disabled={sending || archived}
                onAnswer={answerClarification}
              />
              {toolRunning ? (
                <MessageScrollerItem className="mx-auto w-full max-w-4xl">
                  <Attachment state="processing" size="sm">
                    <AttachmentMedia>
                      <HugeiconsIcon
                        icon={Loading03Icon}
                        className="animate-spin"
                      />
                    </AttachmentMedia>
                    <AttachmentContent>
                      <AttachmentTitle>
                        Проверяем доступные бытовки
                      </AttachmentTitle>
                      <AttachmentDescription>
                        Запрос выполняется через LLM tool call
                      </AttachmentDescription>
                    </AttachmentContent>
                  </Attachment>
                </MessageScrollerItem>
              ) : null}
              {errorText ? (
                <MessageScrollerItem className="mx-auto w-full max-w-4xl">
                  <Marker variant="separator" className="text-destructive">
                    <MarkerIcon>
                      <HugeiconsIcon icon={AiChat02Icon} />
                    </MarkerIcon>
                    <MarkerContent>{errorText}</MarkerContent>
                  </Marker>
                </MessageScrollerItem>
              ) : null}
            </MessageScrollerContent>
          </MessageScrollerViewport>
          <MessageScrollerButton />
        </MessageScroller>
      </MessageScrollerProvider>

      {visibleSearchResult ? (
        <div className="shrink-0">
          <AssistantSearchResults
            accessToken={accessToken}
            subjectId={subjectId}
            result={visibleSearchResult}
            selectedIds={selectedIds}
            onSelectionChange={handleSelectionChange}
            selectionPending={selectionMutation.isPending}
            collapsed={!searchResultsVisible}
            onCollapsedChange={(collapsed) =>
              setSearchResultsVisible(!collapsed)
            }
            footer={
              <>
                <span
                  className="text-xs text-muted-foreground"
                  aria-live="polite"
                >
                  Выбрано: {selectedIds.size}
                </span>
                <span className="text-xs text-muted-foreground">
                  {currentSelection?.expiresAt
                    ? `Резерв действует до ${formatConversationDate(currentSelection.expiresAt)}`
                    : "Активного резерва нет"}
                </span>
                <Button
                  type="button"
                  size="sm"
                  disabled={
                    publishMutation.isPending ||
                    selectionMutation.isPending ||
                    selectedIds.size === 0
                  }
                  onClick={() => publishMutation.mutate()}
                >
                  {publishMutation.isPending ? (
                    <HugeiconsIcon
                      icon={Loading03Icon}
                      data-icon="inline-start"
                      className="animate-spin"
                    />
                  ) : (
                    <HugeiconsIcon
                      icon={Share01Icon}
                      data-icon="inline-start"
                    />
                  )}
                  Создать представление для клиента
                </Button>
              </>
            }
          />
        </div>
      ) : null}

      {archived ? (
        <div className="shrink-0 border-t bg-muted/30 px-4 py-4 text-center text-sm text-muted-foreground">
          Диалог закрыт и доступен только для просмотра в истории.
        </div>
      ) : (
        <div
          data-slot="assistant-composer"
          className="shrink-0 bg-background px-4 pt-3 pb-5"
        >
          <form
            className="mx-auto w-full max-w-4xl"
            onSubmit={(event) => {
              event.preventDefault()
              void sendMessage()
            }}
          >
            <InputGroup className="rounded-2xl bg-background shadow-lg">
              <InputGroupTextarea
                value={draft}
                rows={1}
                maxLength={8_000}
                disabled={sending || Boolean(pendingClarification)}
                placeholder={
                  pendingClarification
                    ? "Сначала ответьте на уточнение выше"
                    : "Напишите, какие бытовки подобрать…"
                }
                className="max-h-36 min-h-12 py-3"
                onChange={(event) => setDraft(event.target.value)}
                onKeyDown={(event) => {
                  if (event.key === "Enter" && !event.shiftKey) {
                    event.preventDefault()
                    void sendMessage()
                  }
                }}
              />
              <InputGroupAddon align="inline-end">
                <Button
                  type="submit"
                  size="icon-sm"
                  variant="default"
                  aria-label="Отправить сообщение"
                  disabled={
                    sending || Boolean(pendingClarification) || !draft.trim()
                  }
                  className="rounded-full"
                >
                  <HugeiconsIcon
                    icon={sending ? Loading03Icon : SentIcon}
                    className={cn("size-4", sending && "animate-spin")}
                  />
                </Button>
              </InputGroupAddon>
            </InputGroup>
            <p className="mt-2 text-center text-[11px] text-muted-foreground">
              Ответ и поиск формирует LLM; доступ к базе ограничен разрешёнными
              безопасными инструментами.
            </p>
          </form>
        </div>
      )}

      <Dialog open={presentationOpen} onOpenChange={setPresentationOpen}>
        <DialogContent>
          <DialogHeader>
            <DialogTitle>Представление готово</DialogTitle>
            <DialogDescription>
              Бытовки временно удерживаются до{" "}
              {presentation
                ? formatConversationDate(presentation.expiresAt)
                : "истечения срока"}
              . Новое представление заменит эту ссылку.
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
              <HugeiconsIcon icon={Copy01Icon} />
              Скопировать ссылку
            </Button>
          </DialogFooter>
        </DialogContent>
      </Dialog>
    </div>
  )
}

function renderConversationMessageItems(
  messages: AssistantMessage[],
  sending: boolean
) {
  const items: ReactNode[] = []
  const noticesAwaitingAssistant: Array<{
    messageId: string
    notices: CabinSearchNotice[]
  }> = []

  messages.forEach((message, index) => {
    if (message.role === "USER") {
      const notices = parseCabinSearchNotices(message.searchNotices)
      if (notices.length > 0) {
        noticesAwaitingAssistant.push({ messageId: message.id, notices })
      }
    }

    items.push(
      <MessageScrollerItem
        key={message.id}
        messageId={message.id}
        scrollAnchor={index === messages.length - 1}
        className="mx-auto w-full max-w-4xl"
      >
        <ChatMessage
          message={message}
          streaming={
            sending &&
            message.role === "ASSISTANT" &&
            index === messages.length - 1
          }
        />
      </MessageScrollerItem>
    )

    if (message.role !== "ASSISTANT" || !message.content.trim()) return
    for (const pending of noticesAwaitingAssistant.splice(0)) {
      for (const notice of pending.notices) {
        const key = `${pending.messageId}:notice:${cabinSearchNoticeKey(notice)}`
        items.push(
          <MessageScrollerItem
            key={key}
            messageId={key}
            className="mx-auto w-full max-w-4xl"
          >
            <CabinSearchNoticeMessage notice={notice} />
          </MessageScrollerItem>
        )
      }
    }
  })

  return items
}

function ChatMessage({
  message,
  streaming,
}: {
  message: AssistantMessage
  streaming: boolean
}) {
  const user = message.role === "USER"
  return (
    <Message align={user ? "end" : "start"}>
      {!user ? (
        <MessageAvatar className="size-8 bg-primary/10 text-primary">
          <HugeiconsIcon icon={AiChat02Icon} />
        </MessageAvatar>
      ) : null}
      <MessageContent>
        <MessageHeader>{user ? "Вы" : "Ассистент"}</MessageHeader>
        <Bubble variant={user ? "default" : "muted"}>
          <BubbleContent className="text-sm whitespace-pre-wrap">
            {message.content ||
              (streaming ? (
                <span className="inline-flex gap-1" aria-label="Печатает">
                  <span className="animate-pulse">●</span>
                  <span className="animate-pulse [animation-delay:150ms]">
                    ●
                  </span>
                  <span className="animate-pulse [animation-delay:300ms]">
                    ●
                  </span>
                </span>
              ) : null)}
          </BubbleContent>
        </Bubble>
      </MessageContent>
    </Message>
  )
}

function CabinSearchNoticeMessage({ notice }: { notice: CabinSearchNotice }) {
  return (
    <Message align="start">
      <MessageAvatar className="size-8">
        <HugeiconsIcon icon={AiChat02Icon} />
      </MessageAvatar>
      <MessageContent>
        <MessageHeader>Ассистент</MessageHeader>
        <Bubble variant="destructive">
          <BubbleContent className="whitespace-pre-wrap">
            {formatCabinSearchNotice(notice)}
          </BubbleContent>
        </Bubble>
      </MessageContent>
    </Message>
  )
}

function formatCabinSearchNotice(notice: CabinSearchNotice) {
  const criteria = notice.groups
    .map((group, index) => `«${assistantSearchGroupLabel(group, index)}»`)
    .join(", ")
  const quantity = `Запрошено: ${notice.requestedQuantity}; найдено: ${notice.foundQuantity}.`
  return notice.code === "CABINS_NOT_FOUND"
    ? `Не найдено: ${criteria}. ${quantity}`
    : `Найдено меньше, чем запрошено: ${criteria}. ${quantity}`
}

function CenteredState({
  text,
  loading = false,
}: {
  text: string
  loading?: boolean
}) {
  return (
    <div className="flex min-h-0 flex-1 items-center justify-center gap-2 p-6 text-sm text-muted-foreground">
      {loading ? (
        <HugeiconsIcon icon={Loading03Icon} className="animate-spin" />
      ) : null}
      {text}
    </div>
  )
}

function formatConversationDate(value: string) {
  const date = new Date(value)
  return Number.isNaN(date.getTime())
    ? value
    : new Intl.DateTimeFormat("ru-RU", {
        dateStyle: "short",
        timeStyle: "short",
      }).format(date)
}

function retainCabins(
  result: CabinSearchResult,
  selectedIds: ReadonlySet<string>
): CabinSearchResult {
  return {
    ...result,
    groups: result.groups
      .map((entry) => ({
        ...entry,
        cabins: entry.cabins.filter((cabin) => selectedIds.has(cabin.id)),
      }))
      .filter((entry) => entry.cabins.length > 0),
  }
}

function mergeClarifications(
  current: ClarificationQuestion[],
  next: ClarificationQuestion
) {
  const byId = new Map(current.map((question) => [question.id, question]))
  byId.set(next.id, next)
  return [...byId.values()].sort(
    (left, right) => left.sequenceNumber - right.sequenceNumber
  )
}
