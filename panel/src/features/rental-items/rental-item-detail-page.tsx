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
  ImageUploadIcon,
  PencilEdit01Icon,
  Wrench01Icon,
} from "@hugeicons/core-free-icons"
import { Link, useNavigate, useParams, useSearchParams } from "react-router-dom"
import { toast } from "sonner"

import { Badge } from "@/components/ui/badge"
import { Button } from "@/components/ui/button"
import { PhotoCarousel } from "@/components/media/photo-carousel"
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
  SHIPMENTS_QUERY_KEY,
  listShipments,
} from "@/features/logistics/shipments/api"
import {
  RETURNS_QUERY_KEY,
  listReturns,
} from "@/features/logistics/returns/api"
import type { ReturnDocument } from "@/features/logistics/returns/model"
import type { ShipmentDocument } from "@/features/logistics/shipments/model"
import { getOrder } from "@/features/orders/api/orders-api"
import { AddContentsDialog } from "@/features/rental-items/add-contents-dialog"
import {
  getRentalItemDossierPage,
  RENTAL_ITEM_DOSSIER_QUERY_KEY,
  rentalItemDossierQueryKey,
} from "@/features/rental-items/dossier/api/rental-item-dossier-api"
import {
  DossierActivityFiltersPanel,
  DossierActivityRegister,
} from "@/features/rental-items/dossier/dossier-activity-register"
import { useDossierActorDisplays } from "@/features/rental-items/dossier/actor/use-dossier-actor-displays"
import { formatDossierActorLabel } from "@/features/rental-items/dossier/actor/actor-display"
import type { DossierActivityFilters } from "@/features/rental-items/dossier/model/dossier-service"
import type {
  DossierActivity,
  DossierActivityCode,
} from "@/features/rental-items/dossier/model/dossier-service"
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
} from "@/features/rental-items/rental-item-detail-support"
import { RentalItemPassportDialog } from "@/features/rental-items/rental-item-passport-dialog"
import { MoveContentsToRentalItemDialog } from "@/features/rental-items/move-contents-to-rental-item-dialog"
import { MoveContentsToStockDialog } from "@/features/rental-items/move-contents-to-stock-dialog"
import {
  RentalItemPhotosRegister,
  RentalItemPhotoUploadDialog,
} from "@/features/rental-items/rental-item-media"
import { useRentalItemMedia } from "@/features/rental-items/use-rental-item-media"
import {
  formatRentalItemContents,
  RENTAL_ITEM_STATUS_LABEL,
  type RentalItemDto,
} from "@/features/rental-items/model/rental-item"
import { RentalItemStatusBadge } from "@/features/rental-items/rental-item-status-badge"
import { useWarehouse } from "@/hooks/use-warehouse"
import {
  useWorkspaceBack,
  workspaceEntryNavigationOptions,
} from "@/hooks/use-workspace-back"
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

const STANDARD_MEDIA_VARIANTS = ["MEDIUM"] as const
const PHOTO_ARCHIVE_MEDIA_VARIANTS = ["SMALL"] as const

const TAB_ACTIVITY_CODES: Partial<Record<DetailTab, DossierActivityCode[]>> = {
  inspections: [
    "INVENTORY_FINDING_ADDED",
    "INVENTORY_INSPECTION_SAVED",
    "INVENTORY_PUBLICATION_READY",
    "INVENTORY_PUBLICATION_REQUESTED",
    "INVENTORY_PUBLICATION_SUCCEEDED",
    "INVENTORY_PUBLICATION_TRANSIENT_FAILED",
    "INVENTORY_PUBLICATION_BLOCKED",
    "INVENTORY_PUBLICATION_CLOSED_BLOCKED",
  ],
  estimates: [
    "ESTIMATE_CREATED",
    "ESTIMATE_DRAFT_CHANGED",
    "ESTIMATE_COMPLETED",
    "ESTIMATE_AMENDED",
  ],
  repair: [
    "REPAIR_CREATED",
    "REPAIR_PLAN_CHANGED",
    "REPAIR_QUEUED",
    "REPAIR_STAGE_COMPLETED",
    "REPAIR_PENDING_ACCEPTANCE",
    "REPAIR_REWORK_CREATED",
    "REPAIR_ACCEPTED",
    "REPAIR_WRITTEN_OFF",
  ],
  shipments: ["CABIN_LOGISTICS_EFFECT_APPLIED"],
}

function isDetailTab(value: string | null): value is DetailTab {
  return TAB_IDS.includes(value as DetailTab)
}

function formatDateTime(value: string | null) {
  if (!value) return "—"
  const date = new Date(value)
  if (!Number.isFinite(date.getTime())) return value
  return new Intl.DateTimeFormat("ru-RU", {
    dateStyle: "medium",
    timeStyle: value.includes("T") ? "short" : undefined,
  }).format(date)
}

function isDateDue(value: string | null) {
  return Boolean(value && value <= new Date().toISOString().slice(0, 10))
}

function rentalLifecycleLabel(
  shipment: ShipmentDocument | null,
  rentalReturn: ReturnDocument | null,
  returnDate: string | null
) {
  if (rentalReturn?.state === "ACCEPTED") return "Возвращено"
  if (rentalReturn && rentalReturn.state !== "DRAFT")
    return "Возврат в процессе"
  if (shipment?.state === "SHIPPED" && isDateDue(returnDate)) {
    return "Требует возврата"
  }
  if (shipment?.state === "SHIPPED") return "Отгружено"
  if (shipment?.state === "DRAFT") return "Ожидает отгрузки"
  if (shipment) return "В процессе отгрузки"
  return "Не отгружена"
}

function formatCurrency(value: number | null) {
  if (value === null) return "—"
  return new Intl.NumberFormat("ru-RU", {
    style: "currency",
    currency: "RUB",
    maximumFractionDigits: 0,
  }).format(value)
}

function latestActivity(
  activities: DossierActivity[],
  codes?: readonly DossierActivityCode[]
) {
  return activities.find(
    (activity) => !codes || codes.includes(activity.activityCode)
  )
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
  onAddPhoto,
  onEditPassport,
  onSaved,
}: {
  rentalItem: RentalItemDto
  accessToken: string | null
  canEdit: boolean
  onAddPhoto: () => void
  onEditPassport: () => void
  onSaved: (value: RentalItemDto) => void
}) {
  const navigate = useNavigate()
  const [statusOpen, setStatusOpen] = useState(false)
  const [status, setStatus] = useState<(typeof MANUAL_STATUSES)[number]>(
    () => MANUAL_STATUSES.find((value) => value !== rentalItem.status) ?? "FREE"
  )
  const statusBlocked = [
    "BOOKED",
    "RENTED",
    "WRITTEN_OFF",
    "LOST",
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

  const repairAllowed = ![
    "RENTED",
    "AFTER_RENT",
    "WRITTEN_OFF",
    "LOST",
  ].includes(rentalItem.status)

  function createRepair() {
    navigate("/repairs?create=1", {
      ...workspaceEntryNavigationOptions,
      state: {
        ...workspaceEntryNavigationOptions.state,
        rentalItemSeed: {
          type: "rental-item-repair-seed-v1",
          warehouseId: rentalItem.warehouseId,
          rentalItemId: rentalItem.id,
          number: rentalItem.number,
        },
      },
    })
  }

  return (
    <>
      <div className="flex flex-wrap gap-2">
        <Button size="sm" variant="outline" onClick={onEditPassport}>
          <HugeiconsIcon icon={PencilEdit01Icon} data-icon="inline-start" />
          Изменить паспорт
        </Button>
        <Button size="sm" variant="outline" onClick={onAddPhoto}>
          <HugeiconsIcon icon={ImageUploadIcon} data-icon="inline-start" />
          Добавить фото
        </Button>
        {repairAllowed ? (
          <Button size="sm" variant="outline" onClick={createRepair}>
            <HugeiconsIcon icon={Wrench01Icon} data-icon="inline-start" />
            Отправить в ремонт
          </Button>
        ) : null}
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
              Изменение будет сохранено asset-service и появится в истории
              бытовки.
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
  const [photoUploadOpen, setPhotoUploadOpen] = useState(false)
  const [passportEditOpen, setPassportEditOpen] = useState(false)
  const [addContentsOpen, setAddContentsOpen] = useState(false)
  const [moveContentsToStockOpen, setMoveContentsToStockOpen] = useState(false)
  const [moveContentsToRentalItemOpen, setMoveContentsToRentalItemOpen] =
    useState(false)
  const [manualComment, setManualComment] = useState("")
  const [dossierFilters, setDossierFilters] = useState<DossierActivityFilters>(
    {}
  )
  const [generalCommentDraft, setGeneralCommentDraft] = useState<{
    rentalItemId: string
    value: string
  } | null>(null)
  const requestedTab = searchParams.get("tab")
  const activeTab: DetailTab = isDetailTab(requestedTab)
    ? requestedTab
    : "overview"
  const warehouseId = selectedWarehouse?.id ?? "none"
  const canEditRentalItem = hasWarehouseAccess(currentUser, warehouseId, "EDIT")
  const canManageContents = hasWarehouseAccess(
    currentUser,
    warehouseId,
    "MANAGE"
  )
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
  const activeOrderReservation = rentalItem?.activeOrderReservation ?? null
  const hasRentalLifecycle = Boolean(
    activeOrderReservation ||
      rentalItem?.shipmentDate ||
      rentalItem?.tenant ||
      rentalItem?.status === "RENTED"
  )
  const shipmentsQuery = useQuery({
    queryKey: [...SHIPMENTS_QUERY_KEY, rentalItem?.warehouseId ?? "none"],
    queryFn: () => listShipments(accessToken!, rentalItem!.warehouseId),
    enabled: Boolean(accessToken && rentalItem && hasRentalLifecycle),
  })
  const returnsQuery = useQuery({
    queryKey: [...RETURNS_QUERY_KEY, rentalItem?.warehouseId ?? "none"],
    queryFn: () => listReturns(accessToken!, rentalItem!.warehouseId),
    enabled: Boolean(accessToken && rentalItem && hasRentalLifecycle),
  })
  const rentalOrderShipment =
    (shipmentsQuery.data ?? [])
      .filter(
        (shipment) =>
          (!activeOrderReservation ||
            shipment.rentalOrderId === activeOrderReservation.orderId) &&
          shipment.lines.some((line) => line.assetId === rentalItem?.id) &&
          shipment.state !== "CANCELLED"
      )
      .sort((left, right) =>
        right.updatedAt.localeCompare(left.updatedAt)
      )[0] ?? null
  const rentalOrderReturn =
    (returnsQuery.data ?? [])
      .filter(
        (rentalReturn) =>
          (!activeOrderReservation ||
            rentalReturn.rentalOrderId === activeOrderReservation.orderId) &&
          rentalReturn.lines.some((line) => line.assetId === rentalItem?.id) &&
          rentalReturn.state !== "CANCELLED"
      )
      .sort((left, right) =>
        right.updatedAt.localeCompare(left.updatedAt)
      )[0] ?? null
  const relatedOrderId =
    activeOrderReservation?.orderId ??
    rentalOrderShipment?.rentalOrderId ??
    rentalOrderReturn?.rentalOrderId ??
    null
  const rentalOrderQuery = useQuery({
    queryKey: ["orders", "detail", userCacheKey, relatedOrderId ?? "none"],
    queryFn: () => getOrder(accessToken!, relatedOrderId!),
    enabled: Boolean(accessToken && relatedOrderId),
  })
  const rentalOrderUnit = rentalOrderQuery.data?.units.find(
    (candidate) => candidate.unit.id === rentalItem?.id
  )
  const rentalTerm = rentalOrderUnit?.rentalTerm ?? null
  const effectiveShipmentDate =
    rentalTerm?.shipmentDate ??
    rentalOrderShipment?.scheduledDate ??
    rentalItem?.shipmentDate ??
    null
  const effectiveTenant =
    activeOrderReservation?.tenantSnapshot ?? rentalItem?.tenant ?? null
  const rentalLifecycle = rentalLifecycleLabel(
    rentalOrderShipment,
    rentalOrderReturn,
    rentalTerm?.returnDate ?? null
  )
  const dossierPages = dossierQuery.data?.pages
  const dossierActivities =
    dossierPages?.flatMap((page) => page.activities) ?? []
  const actorDisplays = useDossierActorDisplays(
    dossierActivities.flatMap((activity) =>
      activity.actorRef ? [activity.actorRef.subjectId] : []
    )
  )
  const media = useRentalItemMedia({
    item: rentalItem,
    accessToken,
    dossierActivities,
    actorDisplays,
    initialVariants:
      activeTab === "photos"
        ? PHOTO_ARCHIVE_MEDIA_VARIANTS
        : STANDARD_MEDIA_VARIANTS,
    enabled: Boolean(rentalItem),
  })
  const sectionActivityCodes = TAB_ACTIVITY_CODES[activeTab]
  const sectionDossierPages = sectionActivityCodes
    ? dossierPages?.map((page) => ({
        ...page,
        activities: page.activities.filter((activity) =>
          sectionActivityCodes.includes(activity.activityCode)
        ),
      }))
    : dossierPages
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
            : "Не удалось получить данные от asset-service."
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

  const lastRecordedActivity = latestActivity(dossierActivities)
  const lastRecordedActorLabel = lastRecordedActivity?.actorRef
    ? formatDossierActorLabel(
        lastRecordedActivity.actorRef,
        actorDisplays.get(lastRecordedActivity.actorRef.subjectId)
      )
    : "Не зафиксировано"
  const lastInspectionActivity = latestActivity(
    dossierActivities,
    TAB_ACTIVITY_CODES.inspections
  )
  const repairActivityCount = dossierActivities.filter((activity) =>
    TAB_ACTIVITY_CODES.repair?.includes(activity.activityCode)
  ).length
  const estimateActivityCount = dossierActivities.filter((activity) =>
    TAB_ACTIVITY_CODES.estimates?.includes(activity.activityCode)
  ).length

  return (
    <div className="flex h-full min-h-0 flex-col gap-4 overflow-auto pb-4">
      <Button variant="outline" className="w-fit" onClick={goBack}>
        <HugeiconsIcon icon={ArrowLeft01Icon} data-icon="inline-start" />
        Назад
      </Button>
      <section className="shrink-0 overflow-hidden rounded-lg border bg-card">
        <div className="grid md:grid-cols-[minmax(0,1fr)_minmax(22rem,26rem)] xl:grid-cols-[minmax(0,1fr)_minmax(26rem,30rem)]">
          <PhotoCarousel
            photos={media.photos}
            item={rentalItem}
            loading={media.isLoading}
            photoCount={media.logicalPhotoCount}
            showPhotoCount
            emptyLabel={
              media.error
                ? "Сервис фото недоступен"
                : media.logicalPhotoCount > 0
                  ? "Фото обрабатываются"
                  : "Нет фото"
            }
            className="h-[280px] bg-muted sm:h-[340px] md:h-[420px] lg:h-[560px]"
            fit="contain"
            controlsVisibility="always"
            onRequestFullscreen={media.requestFullscreen}
          />
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
              onAddPhoto={() => setPhotoUploadOpen(true)}
              onEditPassport={() => setPassportEditOpen(true)}
              onSaved={setRentalItem}
            />
            <Separator />
            <dl className="grid grid-cols-[6.5rem_minmax(0,1fr)] gap-x-3 gap-y-1.5 text-xs">
              <dt className="text-muted-foreground">Склад</dt>
              <dd>{selectedWarehouse?.name ?? "Склад"}</dd>
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
                <CharacteristicTags values={rentalItem.characteristics} />
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
              <dd>
                <Button
                  type="button"
                  variant="link"
                  className="h-auto p-0"
                  onClick={() => changeTab("photos")}
                >
                  {media.logicalPhotoCount} фото
                </Button>
              </dd>
              <dt className="text-muted-foreground">Наполнение</dt>
              <dd>
                {formatRentalItemContents(
                  rentalItem.contentsItems,
                  rentalItem.contents
                )}
              </dd>
              <dt className="text-muted-foreground">Отгрузка</dt>
              <dd>{formatDateTime(effectiveShipmentDate)}</dd>
              <dt className="text-muted-foreground">Арендатор</dt>
              <dd>{effectiveTenant ?? "—"}</dd>
              <dt className="text-muted-foreground">Статус аренды</dt>
              <dd>
                <Badge
                  variant={
                    rentalLifecycle === "Требует возврата"
                      ? "destructive"
                      : rentalLifecycle === "Отгружено" ||
                          rentalLifecycle === "Возвращено"
                        ? "secondary"
                        : "outline"
                  }
                >
                  {rentalLifecycle}
                </Badge>
              </dd>
              <dt className="text-muted-foreground">Срок аренды</dt>
              <dd>
                {rentalTerm ? `${rentalTerm.rentalMonths} мес.` : "Не задан"}
              </dd>
              <dt className="text-muted-foreground">Возврат до</dt>
              <dd>{formatDateTime(rentalTerm?.returnDate ?? null)}</dd>
              <dt className="text-muted-foreground">Цена</dt>
              <dd>{formatCurrency(rentalItem.price)}</dd>
              <dt className="text-muted-foreground">Последнее действие</dt>
              <dd>
                {lastRecordedActivity
                  ? formatDateTime(
                      lastRecordedActivity.occurredAt ??
                        lastRecordedActivity.recordedAt
                    )
                  : "Не зафиксировано"}
              </dd>
              <dt className="text-muted-foreground">Автор действия</dt>
              <dd>{lastRecordedActorLabel}</dd>
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
                  {selectedWarehouse?.name ?? "Склад"}
                </p>
              </div>
              <div>
                <p className="text-muted-foreground">Последний осмотр</p>
                <p className="font-medium">
                  {lastInspectionActivity
                    ? formatDateTime(
                        lastInspectionActivity.occurredAt ??
                          lastInspectionActivity.recordedAt
                      )
                    : "Не зафиксирован"}
                </p>
              </div>
              <div>
                <p className="text-muted-foreground">События ремонта</p>
                <p className="font-medium">{repairActivityCount}</p>
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
                  <p className="font-medium">{effectiveTenant ?? "—"}</p>
                </div>
                <div>
                  <p className="text-muted-foreground">Дата отгрузки</p>
                  <p className="font-medium">
                    {formatDateTime(effectiveShipmentDate)}
                  </p>
                </div>
                <div>
                  <p className="text-muted-foreground">Срок аренды</p>
                  <p className="font-medium">
                    {rentalTerm
                      ? `${rentalTerm.rentalMonths} мес.`
                      : "Не задан"}
                  </p>
                </div>
                <div>
                  <p className="text-muted-foreground">Возврат до</p>
                  <p className="font-medium">
                    {formatDateTime(rentalTerm?.returnDate ?? null)}
                  </p>
                </div>
                <div>
                  <p className="text-muted-foreground">Статус аренды</p>
                  <div className="mt-1">
                    <Badge
                      variant={
                        rentalLifecycle === "Требует возврата"
                          ? "destructive"
                          : rentalLifecycle === "Отгружено" ||
                              rentalLifecycle === "Возвращено"
                            ? "secondary"
                            : "outline"
                      }
                    >
                      {rentalLifecycle}
                    </Badge>
                  </div>
                </div>
              </div>
              <div className="flex flex-wrap gap-2">
                {activeOrderReservation ? (
                  <Button size="sm" variant="outline" asChild>
                    <Link
                      to={`/orders/${activeOrderReservation.orderId}`}
                      state={workspaceEntryNavigationOptions.state}
                    >
                      Открыть заказ
                    </Link>
                  </Button>
                ) : null}
                {rentalOrderShipment ? (
                  <Button size="sm" variant="outline" asChild>
                    <Link
                      to="/logistics/shipments"
                      state={workspaceEntryNavigationOptions.state}
                    >
                      Открыть отгрузки
                    </Link>
                  </Button>
                ) : null}
                <Button
                  type="button"
                  size="sm"
                  variant="outline"
                  onClick={() => changeTab("estimates")}
                >
                  Сметы: {estimateActivityCount}
                </Button>
                <Button
                  type="button"
                  size="sm"
                  variant="outline"
                  onClick={() => changeTab("repair")}
                >
                  События ремонта: {repairActivityCount}
                </Button>
              </div>
            </CardContent>
          </Card>
          <Card>
            <CardHeader>
              <CardTitle>Характеристики</CardTitle>
            </CardHeader>
            <CardContent>
              <CharacteristicTags values={rentalItem.characteristics} />
            </CardContent>
          </Card>
          <Card>
            <CardHeader>
              <CardTitle>Осмотр и ремонт</CardTitle>
            </CardHeader>
            <CardContent className="text-sm text-muted-foreground">
              Подтверждённые события осмотров, смет и ремонтов доступны в
              соответствующих вкладках и в общей истории dossier-service.
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
                {canManageContents ? (
                  <div className="flex flex-wrap gap-2">
                    <Button
                      size="sm"
                      variant="outline"
                      onClick={() => setAddContentsOpen(true)}
                    >
                      Добавить
                    </Button>
                    {rentalItem.contentsItems.length > 0 ? (
                      <>
                        <Button
                          size="sm"
                          variant="outline"
                          onClick={() => setMoveContentsToRentalItemOpen(true)}
                        >
                          В другую бытовку
                        </Button>
                        <Button
                          size="sm"
                          variant="outline"
                          onClick={() => setMoveContentsToStockOpen(true)}
                        >
                          На склад
                        </Button>
                      </>
                    ) : null}
                  </div>
                ) : null}
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
            </CardContent>
          </Card>
        </TabsContent>
        <TabsContent value="photos">
          <RentalItemPhotosRegister
            item={rentalItem}
            folders={media.photoFolders}
            assets={media.assets}
            loading={media.isLoading}
            error={media.error}
            canEdit={canEditRentalItem}
            rotating={media.isRotating}
            onAdd={() => setPhotoUploadOpen(true)}
            onRotate={media.rotate}
            onOpenFolder={media.requestFolderPreview}
            onRequestFullscreen={media.requestFullscreen}
          />
        </TabsContent>
        <TabsContent value="inspections">
          <DossierActivityRegister
            pages={sectionDossierPages}
            error={dossierQuery.error}
            isLoading={dossierQuery.isLoading}
            hasNextPage={Boolean(dossierQuery.hasNextPage)}
            isFetchingNextPage={dossierQuery.isFetchingNextPage}
            onLoadMore={() => void dossierQuery.fetchNextPage()}
          />
        </TabsContent>
        <TabsContent value="estimates">
          <DossierActivityRegister
            pages={sectionDossierPages}
            error={dossierQuery.error}
            isLoading={dossierQuery.isLoading}
            hasNextPage={Boolean(dossierQuery.hasNextPage)}
            isFetchingNextPage={dossierQuery.isFetchingNextPage}
            onLoadMore={() => void dossierQuery.fetchNextPage()}
          />
        </TabsContent>
        <TabsContent value="repair">
          <DossierActivityRegister
            pages={sectionDossierPages}
            error={dossierQuery.error}
            isLoading={dossierQuery.isLoading}
            hasNextPage={Boolean(dossierQuery.hasNextPage)}
            isFetchingNextPage={dossierQuery.isFetchingNextPage}
            onLoadMore={() => void dossierQuery.fetchNextPage()}
          />
        </TabsContent>
        <TabsContent value="reserves">
          {activeOrderReservation ? (
            <Card>
              <CardHeader>
                <CardTitle>Активный резерв заказа</CardTitle>
                <CardDescription>
                  Резерв подтверждён asset-service и блокирует бытовку для
                  других заказов.
                </CardDescription>
              </CardHeader>
              <CardContent className="flex flex-col gap-4 text-sm">
                <div className="grid gap-4 sm:grid-cols-3">
                  <div>
                    <p className="text-muted-foreground">Клиент</p>
                    <p className="font-medium">{effectiveTenant ?? "—"}</p>
                  </div>
                  <div>
                    <p className="text-muted-foreground">Статус</p>
                    <p className="font-medium">Активен</p>
                  </div>
                  <div>
                    <p className="text-muted-foreground">Создан</p>
                    <p className="font-medium">
                      {formatDateTime(activeOrderReservation.reservedAt)}
                    </p>
                  </div>
                </div>
                <div className="flex flex-wrap gap-2">
                  <Button size="sm" variant="outline" asChild>
                    <Link
                      to={`/orders/${activeOrderReservation.orderId}`}
                      state={workspaceEntryNavigationOptions.state}
                    >
                      Открыть заказ
                    </Link>
                  </Button>
                  {rentalOrderShipment ? (
                    <Button size="sm" variant="outline" asChild>
                      <Link
                        to="/logistics/shipments"
                        state={workspaceEntryNavigationOptions.state}
                      >
                        Открыть отгрузки
                      </Link>
                    </Button>
                  ) : null}
                </div>
              </CardContent>
            </Card>
          ) : (
            <EmptyDossierRegister
              title="Резервы"
              description="Для этой бытовки нет подтверждённых резервов."
              columns={["Клиент", "Статус", "Создан", "Истекает"]}
            />
          )}
        </TabsContent>
        <TabsContent value="shipments">
          <DossierActivityRegister
            pages={sectionDossierPages}
            error={dossierQuery.error}
            isLoading={dossierQuery.isLoading}
            hasNextPage={Boolean(dossierQuery.hasNextPage)}
            isFetchingNextPage={dossierQuery.isFetchingNextPage}
            onLoadMore={() => void dossierQuery.fetchNextPage()}
          />
        </TabsContent>
        <TabsContent value="returns">
          {rentalOrderReturn ? (
            <Card>
              <CardHeader>
                <CardTitle>Возврат из аренды</CardTitle>
                <CardDescription>
                  Документ возврата по текущей отгрузке этой бытовки.
                </CardDescription>
              </CardHeader>
              <CardContent className="grid gap-4 text-sm sm:grid-cols-4">
                <div>
                  <p className="text-muted-foreground">Статус</p>
                  <p className="font-medium">{rentalOrderReturn.state}</p>
                </div>
                <div>
                  <p className="text-muted-foreground">Дата задания</p>
                  <p className="font-medium">
                    {formatDateTime(rentalOrderReturn.scheduledDate)}
                  </p>
                </div>
                <div>
                  <p className="text-muted-foreground">Водитель</p>
                  <p className="font-medium">
                    {rentalOrderReturn.driverSnapshot ?? "Не назначен"}
                  </p>
                </div>
                <div>
                  <p className="text-muted-foreground">Документ</p>
                  <p className="font-mono text-xs">{rentalOrderReturn.id}</p>
                </div>
              </CardContent>
            </Card>
          ) : (
            <EmptyDossierRegister
              title="Возвраты"
              description={
                rentalTerm?.returnDate
                  ? `Возврат ожидается до ${formatDateTime(rentalTerm.returnDate)}.`
                  : "Для этой бытовки возврат из аренды ещё не создан."
              }
              columns={["Дата", "От кого", "Статус", "Действия"]}
            />
          )}
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
                  : "Не удалось получить заметки от asset-service."
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
                      {new Date(note.createdAt).toLocaleString("ru-RU")}
                    </p>
                  </article>
                ))}
              </CardContent>
            </Card>
          ) : (
            <DetailEmpty
              title="Ручных заметок нет"
              description="В asset-service нет ручных заметок для этой бытовки."
            />
          )}
        </TabsContent>
      </Tabs>

      {passportEditOpen ? (
        <RentalItemPassportDialog
          key={`${rentalItem.id}:${rentalItem.version}`}
          open={passportEditOpen}
          rentalItem={rentalItem}
          onOpenChange={setPassportEditOpen}
          onSaved={setRentalItem}
        />
      ) : null}
      <RentalItemPhotoUploadDialog
        open={photoUploadOpen}
        pending={media.isUploading}
        onOpenChange={setPhotoUploadOpen}
        onConfirm={media.upload}
      />
      {canManageContents ? (
        <>
          <AddContentsDialog
            item={rentalItem}
            open={addContentsOpen}
            onOpenChange={setAddContentsOpen}
          />
          <MoveContentsToRentalItemDialog
            item={rentalItem}
            open={moveContentsToRentalItemOpen}
            onOpenChange={setMoveContentsToRentalItemOpen}
          />
          <MoveContentsToStockDialog
            item={rentalItem}
            open={moveContentsToStockOpen}
            onOpenChange={setMoveContentsToStockOpen}
          />
        </>
      ) : null}
    </div>
  )
}
