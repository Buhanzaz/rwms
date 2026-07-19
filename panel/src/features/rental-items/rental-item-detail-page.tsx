import { useState } from "react"
import {
  useInfiniteQuery,
  useMutation,
  useQuery,
  useQueryClient,
} from "@tanstack/react-query"
import { HugeiconsIcon } from "@hugeicons/react"
import {
  Add01Icon,
  ArrowLeft01Icon,
  PencilEdit01Icon,
} from "@hugeicons/core-free-icons"
import { useParams, useSearchParams } from "react-router-dom"
import { toast } from "sonner"

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
import { Field, FieldGroup, FieldLabel } from "@/components/ui/field"
import {
  Select,
  SelectContent,
  SelectGroup,
  SelectItem,
  SelectTrigger,
  SelectValue,
} from "@/components/ui/select"
import { Separator } from "@/components/ui/separator"
import { Tabs, TabsContent, TabsList, TabsTrigger } from "@/components/ui/tabs"
import { Textarea } from "@/components/ui/textarea"
import { useAuth } from "@/features/auth/use-auth"
import { hasWarehouseAccess } from "@/features/auth/warehouse-access"
import {
  getRentalItemDossierPage,
  RENTAL_ITEM_DOSSIER_QUERY_KEY,
  rentalItemDossierQueryKey,
} from "@/features/rental-items/dossier/api/rental-item-dossier-api"
import {
  DossierActivityFiltersPanel,
  DossierActivityRegister,
} from "@/features/rental-items/dossier/dossier-activity-register"
import type { DossierActivityFilters } from "@/features/rental-items/dossier/model/dossier-service"
import {
  addAssetRentalItemManualNote,
  createIdempotencyKey,
  getAssetRentalItem,
  listAssetRentalItemManualNotes,
  updateAssetRentalItemGeneralComment,
  updateAssetRentalItemStatus,
} from "@/features/rental-items/api/asset-rental-items-api"
import {
  CharacteristicTags,
  EmptyDossierRegister,
} from "@/features/rental-items/rental-item-dossier-registers"
import {
  formatRentalItemContents,
  RENTAL_ITEM_STATUS_LABEL,
  type RentalItemDto,
} from "@/features/rental-items/model/rental-item"
import { RentalItemStatusBadge } from "@/features/rental-items/rental-item-status-badge"
import { useWarehouse } from "@/hooks/use-warehouse"
import { useWorkspaceBack } from "@/hooks/use-workspace-back"
import { ApiError } from "@/lib/api-client"

const TAB_IDS = [
  "overview",
  "photos",
  "inspections",
  "estimates",
  "repair",
  "reserves",
  "shipments",
  "returns",
  "history",
  "comments",
] as const
type DetailTab = (typeof TAB_IDS)[number]

const TAB_LABELS: Record<DetailTab, string> = {
  overview: "Обзор",
  photos: "Фото",
  inspections: "Осмотры",
  estimates: "Сметы",
  repair: "Ремонт",
  reserves: "Резервы",
  shipments: "Отгрузки",
  returns: "Возвраты",
  history: "История",
  comments: "Комментарии",
}

const MANUAL_STATUSES = [
  "FREE",
  "WAREHOUSE",
  "OWN_NEEDS",
  "RESERVED",
  "USED_SALE",
] as const

function isDetailTab(value: string | null): value is DetailTab {
  return TAB_IDS.includes(value as DetailTab)
}

function retryDossierQuery(failureCount: number, error: Error) {
  if (error instanceof ApiError && error.status >= 400 && error.status < 500) {
    return false
  }

  return failureCount < 2
}

function DetailEmpty({
  title,
  description,
}: {
  title: string
  description: string
}) {
  return (
    <Card>
      <CardHeader>
        <CardTitle>{title}</CardTitle>
        <CardDescription>{description}</CardDescription>
      </CardHeader>
    </Card>
  )
}

function DossierActions({
  rentalItem,
  accessToken,
  canEdit,
  onSaved,
}: {
  rentalItem: RentalItemDto
  accessToken: string | null
  canEdit: boolean
  onSaved: (value: RentalItemDto) => void
}) {
  const [statusOpen, setStatusOpen] = useState(false)
  const [status, setStatus] = useState<(typeof MANUAL_STATUSES)[number]>(
    () => MANUAL_STATUSES.find((value) => value !== rentalItem.status) ?? "FREE"
  )
  const statusBlocked = [
    "RENTED",
    "WRITTEN_OFF",
    "REPAIR",
    "WAITING_REPAIR_CHECK",
    "CAPITAL_REPAIR",
    "WAITING_ESTIMATE_CONFIRMATION",
    "AFTER_RENT",
  ].includes(rentalItem.status)
  const effectiveStatus =
    status === rentalItem.status
      ? (MANUAL_STATUSES.find((value) => value !== rentalItem.status) ?? "FREE")
      : status

  const statusMutation = useMutation({
    mutationFn: () =>
      updateAssetRentalItemStatus({
        accessToken,
        input: {
          id: rentalItem.id,
          expectedVersion: rentalItem.version,
          status: effectiveStatus,
        },
      }),
    onSuccess: (saved) => {
      onSaved(saved)
      setStatusOpen(false)
      toast.success("Статус изменён")
    },
    onError: (error) =>
      toast.error(
        error instanceof Error ? error.message : "Не удалось изменить статус"
      ),
  })

  if (!canEdit) {
    return null
  }

  return (
    <>
      <div className="flex flex-wrap gap-2">
        <Button
          size="sm"
          variant="outline"
          disabled={statusBlocked}
          onClick={() => setStatusOpen(true)}
        >
          <HugeiconsIcon icon={PencilEdit01Icon} data-icon="inline-start" />
          Изменить статус
        </Button>
      </div>

      <Dialog open={statusOpen} onOpenChange={setStatusOpen}>
        <DialogContent>
          <DialogHeader>
            <DialogTitle>Изменить статус</DialogTitle>
            <DialogDescription>
              Причина и история статуса пока не передаются публичным API.
            </DialogDescription>
          </DialogHeader>
          <FieldGroup>
            <Field>
              <FieldLabel>Новый статус</FieldLabel>
              <Select
                value={effectiveStatus}
                onValueChange={(value) => setStatus(value as typeof status)}
              >
                <SelectTrigger
                  className="w-full"
                  aria-label="Новый статус бытовки"
                >
                  <SelectValue />
                </SelectTrigger>
                <SelectContent>
                  <SelectGroup>
                    {MANUAL_STATUSES.map((value) => (
                      <SelectItem key={value} value={value}>
                        {RENTAL_ITEM_STATUS_LABEL[value]}
                      </SelectItem>
                    ))}
                  </SelectGroup>
                </SelectContent>
              </Select>
            </Field>
          </FieldGroup>
          <DialogFooter>
            <Button variant="outline" onClick={() => setStatusOpen(false)}>
              Отмена
            </Button>
            <Button
              disabled={
                statusMutation.isPending ||
                effectiveStatus === rentalItem.status
              }
              onClick={() => {
                if (effectiveStatus !== rentalItem.status) {
                  statusMutation.mutate()
                }
              }}
            >
              Сохранить
            </Button>
          </DialogFooter>
        </DialogContent>
      </Dialog>
    </>
  )
}

export function RentalItemDetailPage() {
  const goBack = useWorkspaceBack("/warehouse")
  const queryClient = useQueryClient()
  const { rentalItemId } = useParams()
  const [searchParams, setSearchParams] = useSearchParams()
  const { selectedWarehouse } = useWarehouse()
  const { accessToken, currentUser } = useAuth()
  const [manualComment, setManualComment] = useState("")
  const [dossierFilters, setDossierFilters] = useState<DossierActivityFilters>(
    {}
  )
  const [generalCommentDraft, setGeneralCommentDraft] = useState<{
    rentalItemId: string
    value: string
  } | null>(null)
  const activeTab = isDetailTab(searchParams.get("tab"))
    ? searchParams.get("tab")!
    : "overview"
  const warehouseId = selectedWarehouse?.id ?? "none"
  const canEditRentalItem = hasWarehouseAccess(currentUser, warehouseId, "EDIT")
  const userCacheKey = currentUser?.id ?? "unknown-user"
  const currentRentalItemId = rentalItemId ?? "none"
  const assetQueryKey = [
    "asset-rental-item",
    warehouseId,
    currentRentalItemId,
    userCacheKey,
  ] as const
  const manualNotesQueryKey = [
    "asset-rental-item-manual-notes",
    warehouseId,
    currentRentalItemId,
    userCacheKey,
  ] as const
  const assetQuery = useQuery({
    queryKey: assetQueryKey,
    queryFn: async () => {
      const item = await getAssetRentalItem(accessToken, rentalItemId!)
      return item.warehouseId === warehouseId ? item : null
    },
    enabled: Boolean(rentalItemId && selectedWarehouse && accessToken),
  })
  const dossierQuery = useInfiniteQuery({
    queryKey: [
      ...rentalItemDossierQueryKey(currentRentalItemId, dossierFilters),
      userCacheKey,
    ],
    initialPageParam: null as string | null,
    queryFn: ({ pageParam }) =>
      getRentalItemDossierPage(accessToken, rentalItemId!, {
        ...dossierFilters,
        limit: 25,
        after: pageParam ?? undefined,
      }),
    getNextPageParam: (lastPage) => lastPage.nextCursor,
    retry: retryDossierQuery,
    enabled: Boolean(rentalItemId && selectedWarehouse && accessToken),
  })
  const manualNotesQuery = useQuery({
    queryKey: manualNotesQueryKey,
    queryFn: () => listAssetRentalItemManualNotes(accessToken, rentalItemId!),
    enabled: Boolean(
      activeTab === "comments" &&
      rentalItemId &&
      selectedWarehouse &&
      accessToken
    ),
  })
  const rentalItem = assetQuery.data ?? null
  const dossierPages = dossierQuery.data?.pages
  const hasGeneralComment = Boolean(rentalItem?.comment?.trim())
  const generalComment =
    rentalItem && generalCommentDraft?.rentalItemId === rentalItem.id
      ? generalCommentDraft.value
      : (rentalItem?.comment ?? "")

  const commentMutation = useMutation({
    mutationFn: () =>
      addAssetRentalItemManualNote({
        accessToken,
        rentalItemId: rentalItem!.id,
        expectedVersion: rentalItem!.version,
        text: manualComment.trim(),
        idempotencyKey: createIdempotencyKey(),
      }),
    onSuccess: () => {
      setManualComment("")
      void queryClient.invalidateQueries({ queryKey: assetQueryKey })
      void queryClient.invalidateQueries({ queryKey: manualNotesQueryKey })
      void queryClient.invalidateQueries({
        queryKey: RENTAL_ITEM_DOSSIER_QUERY_KEY,
      })
      toast.success("Комментарий добавлен")
    },
    onError: (error) =>
      toast.error(
        error instanceof Error
          ? error.message
          : "Не удалось добавить комментарий"
      ),
  })
  const generalCommentMutation = useMutation({
    mutationFn: () =>
      updateAssetRentalItemGeneralComment({
        accessToken,
        input: {
          id: rentalItem!.id,
          expectedVersion: rentalItem!.version,
          comment: generalComment.trim() || null,
        },
      }),
    onSuccess: (saved) => {
      setRentalItem(saved)
      setGeneralCommentDraft(null)
      toast.success(
        hasGeneralComment
          ? "Общий комментарий сохранён"
          : "Общий комментарий добавлен"
      )
    },
    onError: (error) =>
      toast.error(
        error instanceof Error
          ? error.message
          : "Не удалось сохранить комментарий"
      ),
  })

  function setRentalItem(saved: RentalItemDto) {
    queryClient.setQueryData(assetQueryKey, saved)
    void queryClient.invalidateQueries({
      queryKey: ["rental-items"],
    })
    void queryClient.invalidateQueries({
      queryKey: RENTAL_ITEM_DOSSIER_QUERY_KEY,
    })
  }
  function changeTab(value: string) {
    const next = new URLSearchParams(searchParams)
    if (value === "overview") next.delete("tab")
    else next.set("tab", value)
    setSearchParams(next, { replace: true })
  }

  if (assetQuery.isLoading)
    return (
      <div className="flex h-full items-center justify-center text-sm text-muted-foreground">
        Загрузка бытовки...
      </div>
    )
  if (!accessToken)
    return (
      <DetailEmpty
        title="Требуется авторизация"
        description="Войдите в панель, чтобы открыть бытовку."
      />
    )
  if (
    assetQuery.isError &&
    assetQuery.error instanceof ApiError &&
    assetQuery.error.status === 404
  )
    return (
      <DetailEmpty
        title="Бытовка не найдена"
        description="Asset-service не нашёл бытовку на выбранном складе."
      />
    )
  if (assetQuery.isError)
    return (
      <DetailEmpty
        title="Не удалось загрузить бытовку"
        description={
          assetQuery.error instanceof Error
            ? assetQuery.error.message
            : "Сервис имущества недоступен."
        }
      />
    )
  if (!rentalItem)
    return (
      <DetailEmpty
        title="Бытовка не найдена"
        description="Она отсутствует на выбранном складе."
      />
    )

  return (
    <div className="flex h-full min-h-0 flex-col gap-4 overflow-auto pb-4">
      <Button variant="ghost" className="w-fit" onClick={goBack}>
        <HugeiconsIcon icon={ArrowLeft01Icon} data-icon="inline-start" />
        Назад
      </Button>
      <section className="shrink-0 overflow-hidden rounded-lg border bg-card">
        <div className="grid md:grid-cols-[minmax(0,1fr)_minmax(22rem,26rem)] xl:grid-cols-[minmax(0,1fr)_minmax(26rem,30rem)]">
          <div className="flex h-[280px] items-center justify-center bg-muted p-6 text-center text-sm text-muted-foreground sm:h-[340px] md:h-[420px] lg:h-[560px]">
            Фотографии бытовки недоступны: asset-service пока не предоставляет
            публичный media API.
          </div>
          <aside className="flex min-h-0 flex-col gap-2 border-t p-3 md:border-t-0 md:border-l">
            <div className="flex flex-wrap items-center gap-2">
              <h1 className="text-xl font-semibold">{rentalItem.number}</h1>
              <RentalItemStatusBadge status={rentalItem.status} />
              <Badge variant="secondary">{rentalItem.type}</Badge>
            </div>
            <DossierActions
              rentalItem={rentalItem}
              accessToken={accessToken}
              canEdit={canEditRentalItem}
              onSaved={setRentalItem}
            />
            <Separator />
            <dl className="grid grid-cols-[6.5rem_minmax(0,1fr)] gap-x-3 gap-y-1.5 text-xs">
              <dt className="text-muted-foreground">Склад</dt>
              <dd>
                {selectedWarehouse?.name ?? selectedWarehouse?.code ?? "Склад"}
              </dd>
              <dt className="text-muted-foreground">Габариты</dt>
              <dd>{rentalItem.dimensions ?? "—"}</dd>
              <dt className="text-muted-foreground">Отделка</dt>
              <dd>{rentalItem.finishing ?? "—"}</dd>
              <dt className="text-muted-foreground">Категория</dt>
              <dd>{rentalItem.category ?? "—"}</dd>
              <dt className="self-start text-muted-foreground">
                Характеристики
              </dt>
              <dd>
                <CharacteristicTags value={rentalItem.characteristics} />
              </dd>
              <dt className="text-muted-foreground">Линолеум</dt>
              <dd>
                {rentalItem.linoleum === null
                  ? "—"
                  : rentalItem.linoleum
                    ? "Да"
                    : "Нет"}
              </dd>
              <dt className="text-muted-foreground">Статус</dt>
              <dd>{RENTAL_ITEM_STATUS_LABEL[rentalItem.status]}</dd>
              <dt className="text-muted-foreground">Комментарий</dt>
              <dd>{rentalItem.comment ?? "—"}</dd>
              <dt className="text-muted-foreground">Фото</dt>
              <dd>Недоступно через публичный asset API</dd>
              <dt className="text-muted-foreground">Наполнение</dt>
              <dd>
                {formatRentalItemContents(
                  rentalItem.contentsItems,
                  rentalItem.contents
                )}
              </dd>
              <dt className="text-muted-foreground">Отгрузка</dt>
              <dd>Недоступно через публичный asset API</dd>
              <dt className="text-muted-foreground">Арендатор</dt>
              <dd>Недоступно через публичный asset API</dd>
              <dt className="text-muted-foreground">Цена</dt>
              <dd>Недоступно через публичный asset API</dd>
              <dt className="text-muted-foreground">История</dt>
              <dd>См. подтверждённые события во вкладке «История»</dd>
              <dt className="text-muted-foreground">Actor</dt>
              <dd>Opaque actor reference доступен в истории</dd>
            </dl>
          </aside>
        </div>
      </section>

      <Tabs
        className="min-h-[calc(100svh+12rem)] shrink-0 lg:min-h-0"
        value={activeTab}
        onValueChange={changeTab}
      >
        <div className="sticky top-0 z-20 overflow-x-auto rounded-lg border bg-background p-2 shadow-sm">
          <TabsList className="w-max min-w-full justify-start">
            {TAB_IDS.map((id) => (
              <TabsTrigger key={id} value={id}>
                {TAB_LABELS[id]}
              </TabsTrigger>
            ))}
          </TabsList>
        </div>
        <TabsContent value="overview" className="grid gap-4 lg:grid-cols-2">
          <Card>
            <CardHeader>
              <CardTitle>Статус и расположение</CardTitle>
            </CardHeader>
            <CardContent className="grid grid-cols-2 gap-4 text-sm">
              <div>
                <p className="text-muted-foreground">Текущий статус</p>
                <div className="mt-1">
                  <RentalItemStatusBadge status={rentalItem.status} />
                </div>
              </div>
              <div>
                <p className="text-muted-foreground">Склад</p>
                <p className="font-medium">
                  {selectedWarehouse?.name ??
                    selectedWarehouse?.code ??
                    "Склад"}
                </p>
              </div>
              <div>
                <p className="text-muted-foreground">Последний осмотр</p>
                <p className="font-medium">Недоступно через публичный API</p>
              </div>
              <div>
                <p className="text-muted-foreground">Активные ремонты</p>
                <p className="font-medium">Недоступно через публичный API</p>
              </div>
            </CardContent>
          </Card>
          <Card>
            <CardHeader>
              <CardTitle>Клиент и документы</CardTitle>
            </CardHeader>
            <CardContent className="flex flex-col gap-4 text-sm">
              <div className="grid grid-cols-2 gap-4">
                <div>
                  <p className="text-muted-foreground">Арендатор</p>
                  <p className="font-medium">Недоступно через публичный API</p>
                </div>
                <div>
                  <p className="text-muted-foreground">Дата отгрузки</p>
                  <p className="font-medium">Недоступно через публичный API</p>
                </div>
              </div>
              <p className="text-muted-foreground">
                Связанные документы не входят в публичный asset API.
              </p>
            </CardContent>
          </Card>
          <Card>
            <CardHeader>
              <CardTitle>Характеристики</CardTitle>
            </CardHeader>
            <CardContent>
              <CharacteristicTags value={rentalItem.characteristics} />
            </CardContent>
          </Card>
          <Card>
            <CardHeader>
              <CardTitle>Осмотр и ремонт</CardTitle>
            </CardHeader>
            <CardContent className="text-sm text-muted-foreground">
              Dossier-service предоставляет подтверждённые события, но не
              вычисляет текущие агрегированные счётчики документов. Полная
              хронология доступна во вкладке «История».
            </CardContent>
          </Card>
          <Card className="lg:col-span-2">
            <CardHeader>
              <div className="flex flex-wrap items-center justify-between gap-3">
                <div>
                  <CardTitle>Наполнение</CardTitle>
                  <CardDescription>
                    Мебель и оборудование, закреплённые за бытовкой.
                  </CardDescription>
                </div>
              </div>
            </CardHeader>
            <CardContent>
              {rentalItem.contentsItems.length > 0 ? (
                <div className="flex flex-wrap gap-2">
                  {rentalItem.contentsItems.map((item) => (
                    <Badge
                      key={item.name}
                      variant="outline"
                      className="rounded-full px-3 py-1.5 text-sm"
                    >
                      {item.name} · {item.quantity} шт.
                    </Badge>
                  ))}
                </div>
              ) : (
                <p className="text-sm text-muted-foreground">
                  Наполнение не указано.
                </p>
              )}
              <p className="mt-3 text-sm text-muted-foreground">
                Изменение наполнения временно недоступно: версионная операция
                перемещения исходного и целевого баланса ещё не подключена в
                панели.
              </p>
            </CardContent>
          </Card>
        </TabsContent>
        <TabsContent value="photos">
          <EmptyDossierRegister
            title="Фото"
            description="Действия с фото принадлежат media-service. Dossier показывает только подтверждённые media state references во вкладке «История»."
            columns={["Источник", "Дата", "Статус"]}
          />
        </TabsContent>
        <TabsContent value="inspections">
          <EmptyDossierRegister
            title="Осмотры"
            description="Dossier не подменяет документы inventory-service. Подтверждённые коды событий доступны во вкладке «История»."
            columns={["Дата", "Источник", "Результат", "Исполнитель"]}
          />
        </TabsContent>
        <TabsContent value="estimates">
          <EmptyDossierRegister
            title="Сметы"
            description="Dossier не подменяет документы maintenance-service. Подтверждённые коды событий доступны во вкладке «История»."
            columns={["Дата", "Статус", "Автор", "Сумма"]}
          />
        </TabsContent>
        <TabsContent value="repair">
          <EmptyDossierRegister
            title="Ремонт"
            description="Dossier не подменяет ремонтные документы maintenance-service. Подтверждённые коды событий доступны во вкладке «История»."
            columns={["Дата", "Статус", "Исполнитель", "Комментарий"]}
          />
        </TabsContent>
        <TabsContent value="reserves">
          <EmptyDossierRegister
            title="Резервы"
            description="Раздел подготовлен и пока не реализован."
            columns={["Клиент", "Статус", "Создан", "Истекает"]}
          />
        </TabsContent>
        <TabsContent value="shipments">
          <EmptyDossierRegister
            title="Отгрузки"
            description="Покрытие logistics producer facts пока не подтверждено текущим dossier contract vocabulary."
            columns={["Дата", "Направление", "Контрагент", "Статус"]}
          />
        </TabsContent>
        <TabsContent value="returns">
          <EmptyDossierRegister
            title="Возвраты"
            description="Раздел подготовлен и пока не реализован."
            columns={["Дата", "От кого", "Статус", "Действия"]}
          />
        </TabsContent>
        <TabsContent value="history">
          <div className="flex flex-col gap-4">
            <DossierActivityFiltersPanel
              value={dossierFilters}
              onApply={setDossierFilters}
            />
            <DossierActivityRegister
              pages={dossierPages}
              error={dossierQuery.error}
              isLoading={dossierQuery.isLoading}
              hasNextPage={Boolean(dossierQuery.hasNextPage)}
              isFetchingNextPage={dossierQuery.isFetchingNextPage}
              onLoadMore={() => void dossierQuery.fetchNextPage()}
            />
          </div>
        </TabsContent>
        <TabsContent value="comments" className="flex flex-col gap-4">
          <div className="grid items-stretch gap-4 lg:grid-cols-2">
            <Card className="h-full">
              <CardHeader>
                <CardTitle>Общий комментарий</CardTitle>
                <CardDescription>
                  Редактируемая заметка по бытовке.
                </CardDescription>
              </CardHeader>
              <CardContent>
                <FieldGroup>
                  <Field data-disabled={!canEditRentalItem || undefined}>
                    <FieldLabel htmlFor="general-comment">
                      Комментарий
                    </FieldLabel>
                    <Textarea
                      id="general-comment"
                      value={generalComment}
                      maxLength={4000}
                      disabled={!canEditRentalItem}
                      placeholder={rentalItem.comment ?? "Комментарий"}
                      onChange={(event) =>
                        setGeneralCommentDraft({
                          rentalItemId: rentalItem.id,
                          value: event.target.value,
                        })
                      }
                    />
                  </Field>
                </FieldGroup>
                <Button
                  className="mt-4"
                  disabled={
                    !canEditRentalItem || generalCommentMutation.isPending
                  }
                  onClick={() => {
                    if (canEditRentalItem) {
                      generalCommentMutation.mutate()
                    }
                  }}
                >
                  {hasGeneralComment
                    ? "Сохранить изменения"
                    : "Добавить комментарий"}
                </Button>
              </CardContent>
            </Card>
            <Card className="h-full">
              <CardHeader>
                <CardTitle>Добавить ручную заметку</CardTitle>
                <CardDescription>
                  Заметка сохранится в неизменяемом журнале asset-service.
                </CardDescription>
              </CardHeader>
              <CardContent>
                <FieldGroup>
                  <Field data-disabled={!canEditRentalItem || undefined}>
                    <FieldLabel htmlFor="manual-comment">Текст</FieldLabel>
                    <Textarea
                      id="manual-comment"
                      value={manualComment}
                      maxLength={4000}
                      disabled={!canEditRentalItem}
                      onChange={(event) => setManualComment(event.target.value)}
                    />
                  </Field>
                </FieldGroup>
                <Button
                  className="mt-4"
                  disabled={
                    !canEditRentalItem ||
                    !manualComment.trim() ||
                    commentMutation.isPending
                  }
                  onClick={() => {
                    if (canEditRentalItem) {
                      commentMutation.mutate()
                    }
                  }}
                >
                  <HugeiconsIcon icon={Add01Icon} data-icon="inline-start" />
                  Добавить заметку
                </Button>
              </CardContent>
            </Card>
          </div>
          {manualNotesQuery.isLoading ? (
            <DetailEmpty
              title="Загрузка ручных заметок..."
              description="Получаем неизменяемые заметки из asset-service."
            />
          ) : manualNotesQuery.isError ? (
            <DetailEmpty
              title="Не удалось загрузить ручные заметки"
              description={
                manualNotesQuery.error instanceof Error
                  ? manualNotesQuery.error.message
                  : "Asset-service недоступен."
              }
            />
          ) : manualNotesQuery.data?.length ? (
            <Card>
              <CardHeader>
                <CardTitle>Ручные заметки asset-service</CardTitle>
                <CardDescription>
                  Ответ asset-service не содержит actor display name, поэтому он
                  не восстанавливается в браузере.
                </CardDescription>
              </CardHeader>
              <CardContent className="flex flex-col gap-3">
                {manualNotesQuery.data.map((note) => (
                  <article key={note.id} className="rounded-md border p-3">
                    <p className="text-sm whitespace-pre-wrap">{note.text}</p>
                    <p className="mt-2 font-mono text-xs text-muted-foreground">
                      {new Date(note.createdAt).toLocaleString("ru-RU")} ·{" "}
                      {note.id}
                    </p>
                  </article>
                ))}
              </CardContent>
            </Card>
          ) : (
            <DetailEmpty
              title="Ручных заметок нет"
              description="Asset-service пока не вернул ни одной заметки."
            />
          )}
        </TabsContent>
      </Tabs>
    </div>
  )
}
