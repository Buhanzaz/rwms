import { useState } from "react"
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query"
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

import { PhotoCarousel } from "@/components/media/photo-carousel"
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
  FieldError,
  FieldGroup,
  FieldLabel,
} from "@/components/ui/field"
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
import { AddContentsDialog } from "@/features/rental-items/add-contents-dialog"
import {
  addRentalItemDossierComment,
  addRentalItemDossierPhotoGroup,
  getRentalItemDossier,
  rentalItemDossierQueryKey,
  RENTAL_ITEM_DOSSIER_QUERY_KEY,
  updateRentalItemDossierGeneralComment,
  updateRentalItemDossierStatus,
} from "@/features/rental-items/dossier/api/rental-item-dossier-api"
import type {
  CabinActorSnapshot,
  CabinPhotoGroupDto,
  RentalItemDossierDto,
  RentalItemDossierNavigationState,
} from "@/features/rental-items/dossier/model/rental-item-dossier"
import {
  CharacteristicTags,
  CommentsRegister,
  EmptyDossierRegister,
  EstimatesRegister,
  HistoryRegister,
  InspectionsRegister,
  PhotosRegister,
  RepairsRegister,
  ShipmentsRegister,
} from "@/features/rental-items/rental-item-dossier-registers"
import {
  formatRentalItemContents,
  RENTAL_ITEM_STATUS_LABEL,
} from "@/features/rental-items/model/rental-item"
import { MoveContentsToRentalItemDialog } from "@/features/rental-items/move-contents-to-rental-item-dialog"
import { MoveContentsToStockDialog } from "@/features/rental-items/move-contents-to-stock-dialog"
import { RentalItemStatusBadge } from "@/features/rental-items/rental-item-status-badge"
import {
  RepairEstimatePhotoManagerDialog,
  type RepairEstimatePhotoManagerItem,
} from "@/features/repair-estimates/repair-estimate-photo-manager-dialog"
import { useWarehouse } from "@/hooks/use-warehouse"
import {
  useWorkspaceBack,
  workspaceEntryNavigationOptions,
} from "@/hooks/use-workspace-back"

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

function formatDateTime(value: string | null) {
  if (!value) return "Дата не зафиксирована"
  return new Intl.DateTimeFormat("ru-RU", {
    dateStyle: "medium",
    timeStyle: "short",
  }).format(new Date(value))
}

function formatCurrency(value: number | null) {
  if (value === null) return "—"
  return new Intl.NumberFormat("ru-RU", {
    style: "currency",
    currency: "RUB",
    maximumFractionDigits: 0,
  }).format(value)
}

function actorLabel(actor: CabinActorSnapshot | null) {
  return actor?.displayName || "Автор не зафиксирован"
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

function PhotoGroupDialog({
  group,
  open,
  onOpenChange,
}: {
  group: CabinPhotoGroupDto | null
  open: boolean
  onOpenChange: (open: boolean) => void
}) {
  const [activeIndex, setActiveIndex] = useState(0)
  const photos = group?.photos ?? []
  return (
    <Dialog open={open} onOpenChange={onOpenChange}>
      <DialogContent className="max-w-5xl">
        <DialogHeader>
          <DialogTitle>{group?.sourceLabel ?? "Фотографии"}</DialogTitle>
          <DialogDescription>
            {group
              ? `${formatDateTime(group.occurredAt)} · ${actorLabel(group.actor)}`
              : "Группа фотографий"}
          </DialogDescription>
        </DialogHeader>
        <div className="grid max-h-40 grid-cols-3 gap-2 overflow-y-auto sm:grid-cols-5 lg:grid-cols-7">
          {photos.map((photo, index) => (
            <Button
              key={photo.id}
              type="button"
              variant={activeIndex === index ? "secondary" : "ghost"}
              className="aspect-square h-auto p-1"
              aria-label={`Открыть фото ${index + 1}`}
              onClick={() => setActiveIndex(index)}
            >
              <img
                src={photo.variants.thumb.url}
                alt=""
                className="size-full rounded-sm object-cover"
              />
            </Button>
          ))}
        </div>
        <PhotoCarousel
          photos={photos.map((photo) => ({
            id: photo.id,
            url: photo.variants.preview.url,
            variants: {
              small: { url: photo.variants.thumb.url },
              largeWebp: { url: photo.variants.preview.url },
            },
          }))}
          title={group?.sourceLabel}
          className="h-[65svh] rounded-lg border bg-muted"
          fit="contain"
          controlsVisibility="always"
          activeIndex={activeIndex}
          onActiveIndexChange={setActiveIndex}
        />
      </DialogContent>
    </Dialog>
  )
}

function DossierActions({
  dossier,
  actor,
  onSaved,
  photoOpen,
  onPhotoOpenChange,
}: {
  dossier: RentalItemDossierDto
  actor: CabinActorSnapshot
  onSaved: (value: RentalItemDossierDto) => void
  photoOpen: boolean
  onPhotoOpenChange: (open: boolean) => void
}) {
  const navigate = useNavigate()
  const [statusOpen, setStatusOpen] = useState(false)
  const [photoItems, setPhotoItems] = useState<
    Array<RepairEstimatePhotoManagerItem & { file: File }>
  >([])
  const [status, setStatus] = useState<(typeof MANUAL_STATUSES)[number]>(
    () =>
      MANUAL_STATUSES.find((value) => value !== dossier.rentalItem.status) ??
      "FREE"
  )
  const [reason, setReason] = useState("")
  const [statusSubmitted, setStatusSubmitted] = useState(false)
  const statusBlocked = [
    "RENTED",
    "WRITTEN_OFF",
    "REPAIR",
    "WAITING_REPAIR_CHECK",
    "CAPITAL_REPAIR",
    "WAITING_ESTIMATE_CONFIRMATION",
    "AFTER_RENT",
  ].includes(dossier.rentalItem.status)
  const effectiveStatus =
    status === dossier.rentalItem.status
      ? (MANUAL_STATUSES.find((value) => value !== dossier.rentalItem.status) ??
        "FREE")
      : status

  const photoMutation = useMutation({
    mutationFn: () =>
      addRentalItemDossierPhotoGroup({
        rentalItemId: dossier.rentalItem.id,
        warehouseId: dossier.rentalItem.warehouseId,
        expectedVersion: dossier.rentalItem.version,
        actor,
        uploads: photoItems.map((item) => ({
          file: item.file,
          rotationDegrees: item.rotationDegrees,
        })),
      }),
    onSuccess: (saved) => {
      onSaved(saved)
      photoItems.forEach((item) => URL.revokeObjectURL(item.previewUrl))
      setPhotoItems([])
      onPhotoOpenChange(false)
      toast.success("Фотографии добавлены")
    },
    onError: (error) =>
      toast.error(
        error instanceof Error
          ? error.message
          : "Не удалось добавить фотографии"
      ),
  })
  const statusMutation = useMutation({
    mutationFn: () =>
      updateRentalItemDossierStatus({
        rentalItemId: dossier.rentalItem.id,
        warehouseId: dossier.rentalItem.warehouseId,
        expectedVersion: dossier.rentalItem.version,
        actor,
        status: effectiveStatus,
        reason,
      }),
    onSuccess: (saved) => {
      onSaved(saved)
      setReason("")
      setStatusSubmitted(false)
      setStatusOpen(false)
      toast.success("Статус изменён")
    },
    onError: (error) =>
      toast.error(
        error instanceof Error ? error.message : "Не удалось изменить статус"
      ),
  })

  function createRepairCycle() {
    const action = dossier.repairAction
    if (action.type === "NONE") return
    const state: RentalItemDossierNavigationState = {
      rentalItemSeed:
        action.type === "CREATE_ESTIMATE"
          ? {
              type: "rental-item-estimate-seed-v1",
              warehouseId: dossier.rentalItem.warehouseId,
              rentalItemId: dossier.rentalItem.id,
              cabinNumber: dossier.rentalItem.number,
            }
          : {
              type: "rental-item-repair-seed-v1",
              warehouseId: dossier.rentalItem.warehouseId,
              rentalItemId: dossier.rentalItem.id,
              cabinNumber: dossier.rentalItem.number,
            },
    }
    navigate(
      action.type === "CREATE_ESTIMATE"
        ? "/estimates?create=1"
        : "/repairs?create=1",
      {
        ...workspaceEntryNavigationOptions,
        state: { ...workspaceEntryNavigationOptions.state, ...state },
      }
    )
  }

  return (
    <>
      <div className="flex flex-wrap gap-2">
        <Button
          size="sm"
          variant="outline"
          onClick={() => onPhotoOpenChange(true)}
        >
          <HugeiconsIcon icon={ImageUploadIcon} data-icon="inline-start" />
          Добавить фото
        </Button>
        {dossier.repairAction.type !== "NONE" ? (
          <Button size="sm" variant="outline" onClick={createRepairCycle}>
            <HugeiconsIcon icon={Wrench01Icon} data-icon="inline-start" />
            {dossier.repairAction.type === "CREATE_ESTIMATE"
              ? "Создать смету"
              : "Создать ремонт"}
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
      <RepairEstimatePhotoManagerDialog
        open={photoOpen}
        items={photoItems}
        onOpenChange={onPhotoOpenChange}
        pending={photoMutation.isPending}
        onConfirm={() => photoMutation.mutate()}
        onCancel={() => {
          photoItems.forEach((item) => URL.revokeObjectURL(item.previewUrl))
          setPhotoItems([])
          onPhotoOpenChange(false)
        }}
        onAddFiles={(files) =>
          setPhotoItems((current) => [
            ...current,
            ...files.map((file) => ({
              id: crypto.randomUUID(),
              fileName: file.name,
              previewUrl: URL.createObjectURL(file),
              rotationDegrees: 0 as const,
              source: "PENDING" as const,
              file,
            })),
          ])
        }
        onRemove={(removed) =>
          setPhotoItems((current) => {
            const item = current.find(
              (candidate) => candidate.id === removed.id
            )
            if (item) URL.revokeObjectURL(item.previewUrl)
            return current.filter((candidate) => candidate.id !== removed.id)
          })
        }
        onRotate={(rotated, direction) =>
          setPhotoItems((current) =>
            current.map((item) =>
              item.id === rotated.id
                ? {
                    ...item,
                    rotationDegrees: ((item.rotationDegrees +
                      (direction === "RIGHT" ? 90 : 270)) %
                      360) as 0 | 90 | 180 | 270,
                  }
                : item
            )
          )
        }
      />

      <Dialog open={statusOpen} onOpenChange={setStatusOpen}>
        <DialogContent>
          <DialogHeader>
            <DialogTitle>Изменить статус</DialogTitle>
            <DialogDescription>
              Изменение сохранится в истории бытовки.
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
            <Field data-invalid={statusSubmitted && !reason.trim()}>
              <FieldLabel htmlFor="status-reason">Причина</FieldLabel>
              <Textarea
                id="status-reason"
                value={reason}
                aria-invalid={statusSubmitted && !reason.trim()}
                onChange={(event) => setReason(event.target.value)}
              />
              {statusSubmitted && !reason.trim() ? (
                <FieldError>Укажите причину изменения.</FieldError>
              ) : null}
            </Field>
          </FieldGroup>
          <DialogFooter>
            <Button variant="outline" onClick={() => setStatusOpen(false)}>
              Отмена
            </Button>
            <Button
              disabled={
                statusMutation.isPending ||
                effectiveStatus === dossier.rentalItem.status ||
                !reason.trim()
              }
              onClick={() => {
                setStatusSubmitted(true)
                if (
                  reason.trim() &&
                  effectiveStatus !== dossier.rentalItem.status
                )
                  statusMutation.mutate()
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
  const { currentUser } = useAuth()
  const [selectedPhotoGroup, setSelectedPhotoGroup] =
    useState<CabinPhotoGroupDto | null>(null)
  const [photoManagerOpen, setPhotoManagerOpen] = useState(false)
  const [addContentsOpen, setAddContentsOpen] = useState(false)
  const [moveContentsToStockOpen, setMoveContentsToStockOpen] = useState(false)
  const [moveContentsToRentalItemOpen, setMoveContentsToRentalItemOpen] =
    useState(false)
  const [manualComment, setManualComment] = useState("")
  const [generalCommentDraft, setGeneralCommentDraft] = useState<{
    rentalItemId: string
    value: string
  } | null>(null)
  const activeTab = isDetailTab(searchParams.get("tab"))
    ? searchParams.get("tab")!
    : "overview"
  const warehouseId = selectedWarehouse?.id ?? "none"
  const actor = {
    id: currentUser?.id ?? null,
    displayName:
      currentUser?.displayName ||
      currentUser?.username ||
      "Текущий пользователь",
  }
  const dossierQuery = useQuery({
    queryKey: rentalItemDossierQueryKey(warehouseId, rentalItemId ?? "none"),
    queryFn: () => getRentalItemDossier(warehouseId, rentalItemId!),
    enabled: Boolean(rentalItemId && selectedWarehouse),
  })
  const dossier = dossierQuery.data ?? null
  const hasGeneralComment = Boolean(dossier?.rentalItem.comment?.trim())
  const generalComment =
    dossier && generalCommentDraft?.rentalItemId === dossier.rentalItem.id
      ? generalCommentDraft.value
      : (dossier?.rentalItem.comment ?? "")

  const commentMutation = useMutation({
    mutationFn: () =>
      addRentalItemDossierComment({
        rentalItemId: dossier!.rentalItem.id,
        warehouseId,
        actor,
        text: manualComment,
      }),
    onSuccess: (saved) => {
      setDossier(saved)
      setManualComment("")
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
      updateRentalItemDossierGeneralComment({
        rentalItemId: dossier!.rentalItem.id,
        warehouseId,
        expectedVersion: dossier!.rentalItem.version,
        actor,
        comment: generalComment,
      }),
    onSuccess: (saved) => {
      setDossier(saved)
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

  function setDossier(saved: RentalItemDossierDto) {
    queryClient.setQueryData(
      rentalItemDossierQueryKey(warehouseId, saved.rentalItem.id),
      saved
    )
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

  const heroPhotos =
    dossier?.photoGroups.flatMap((group) =>
      group.photos.map((photo) => ({
        id: photo.id,
        url: photo.variants.preview.url,
        variants: {
          small: { url: photo.variants.thumb.url },
          largeWebp: { url: photo.variants.preview.url },
        },
      }))
    ) ?? []
  if (dossierQuery.isLoading)
    return (
      <div className="flex h-full items-center justify-center text-sm text-muted-foreground">
        Загрузка бытовки...
      </div>
    )
  if (!dossier)
    return (
      <DetailEmpty
        title="Бытовка не найдена"
        description="Она отсутствует на выбранном складе."
      />
    )

  const lastRecordedActivity = dossier.activities[0] ?? null

  return (
    <div className="flex h-full min-h-0 flex-col gap-4 overflow-auto pb-4">
      <Button variant="ghost" className="w-fit" onClick={goBack}>
        <HugeiconsIcon icon={ArrowLeft01Icon} data-icon="inline-start" />
        Назад
      </Button>
      <section className="shrink-0 overflow-hidden rounded-lg border bg-card">
        <div className="grid md:grid-cols-[minmax(0,1fr)_minmax(22rem,26rem)] xl:grid-cols-[minmax(0,1fr)_minmax(26rem,30rem)]">
          <PhotoCarousel
            photos={heroPhotos}
            photoCount={heroPhotos.length}
            showPhotoCount
            className="h-[280px] bg-muted sm:h-[340px] md:h-[420px] lg:h-[560px]"
            fit="contain"
            controlsVisibility="mobile-visible"
          />
          <aside className="flex min-h-0 flex-col gap-2 border-t p-3 md:border-t-0 md:border-l">
            <div className="flex flex-wrap items-center gap-2">
              <h1 className="text-xl font-semibold">
                {dossier.rentalItem.number}
              </h1>
              <RentalItemStatusBadge status={dossier.rentalItem.status} />
              <Badge variant="secondary">{dossier.rentalItem.type}</Badge>
            </div>
            <DossierActions
              dossier={dossier}
              actor={actor}
              onSaved={setDossier}
              photoOpen={photoManagerOpen}
              onPhotoOpenChange={setPhotoManagerOpen}
            />
            <Separator />
            <dl className="grid grid-cols-[6.5rem_minmax(0,1fr)] gap-x-3 gap-y-1.5 text-xs">
              <dt className="text-muted-foreground">Склад</dt>
              <dd>
                {selectedWarehouse?.name ?? selectedWarehouse?.code ?? "Склад"}
              </dd>
              <dt className="text-muted-foreground">Габариты</dt>
              <dd>{dossier.rentalItem.dimensions ?? "—"}</dd>
              <dt className="text-muted-foreground">Отделка</dt>
              <dd>{dossier.rentalItem.finishing ?? "—"}</dd>
              <dt className="text-muted-foreground">Категория</dt>
              <dd>{dossier.rentalItem.category ?? "—"}</dd>
              <dt className="self-start text-muted-foreground">
                Характеристики
              </dt>
              <dd>
                <CharacteristicTags
                  value={dossier.rentalItem.characteristics}
                />
              </dd>
              <dt className="text-muted-foreground">Линолеум</dt>
              <dd>
                {dossier.rentalItem.linoleum === null
                  ? "—"
                  : dossier.rentalItem.linoleum
                    ? "Да"
                    : "Нет"}
              </dd>
              <dt className="text-muted-foreground">Статус</dt>
              <dd>{RENTAL_ITEM_STATUS_LABEL[dossier.rentalItem.status]}</dd>
              <dt className="text-muted-foreground">Комментарий</dt>
              <dd>{dossier.rentalItem.comment ?? "—"}</dd>
              <dt className="text-muted-foreground">Фото</dt>
              <dd>
                <Button
                  type="button"
                  variant="link"
                  className="h-auto p-0"
                  onClick={() => changeTab("photos")}
                >
                  {dossier.rentalItem.photoCount} фото
                </Button>
              </dd>
              <dt className="text-muted-foreground">Наполнение</dt>
              <dd>
                {formatRentalItemContents(
                  dossier.rentalItem.contentsItems,
                  dossier.rentalItem.contents
                )}
              </dd>
              <dt className="text-muted-foreground">Отгрузка</dt>
              <dd>{formatDateTime(dossier.rentalItem.shipmentDate)}</dd>
              <dt className="text-muted-foreground">Арендатор</dt>
              <dd>{dossier.rentalItem.tenant ?? "—"}</dd>
              <dt className="text-muted-foreground">Цена</dt>
              <dd>{formatCurrency(dossier.rentalItem.price)}</dd>
              <dt className="text-muted-foreground">Последнее действие</dt>
              <dd>
                {lastRecordedActivity
                  ? formatDateTime(lastRecordedActivity.occurredAt)
                  : "Не зафиксировано"}
              </dd>
              <dt className="text-muted-foreground">Автор действия</dt>
              <dd>
                {lastRecordedActivity?.actor?.displayName || "Не зафиксировано"}
              </dd>
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
                  <RentalItemStatusBadge status={dossier.rentalItem.status} />
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
                <p className="font-medium">
                  {formatDateTime(dossier.overview.lastInspectionAt)}
                </p>
              </div>
              <div>
                <p className="text-muted-foreground">Активные ремонты</p>
                <p className="font-medium">
                  {dossier.overview.activeRepairCount}
                </p>
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
                  <p className="font-medium">
                    {dossier.rentalItem.tenant ?? "—"}
                  </p>
                </div>
                <div>
                  <p className="text-muted-foreground">Дата отгрузки</p>
                  <p className="font-medium">
                    {formatDateTime(dossier.rentalItem.shipmentDate)}
                  </p>
                </div>
              </div>
              <div className="flex flex-wrap gap-2">
                {dossier.overview.latestEstimateId ? (
                  <Button size="sm" variant="outline" asChild>
                    <Link
                      to={
                        dossier.estimates.find(
                          (item) =>
                            item.id === dossier.overview.latestEstimateId
                        )?.link.href ?? "/estimates"
                      }
                      state={workspaceEntryNavigationOptions.state}
                    >
                      Последняя смета
                    </Link>
                  </Button>
                ) : null}
                {dossier.overview.latestRepairId ? (
                  <Button size="sm" variant="outline" asChild>
                    <Link
                      to={
                        dossier.repairs.find(
                          (item) => item.id === dossier.overview.latestRepairId
                        )?.link.href ?? "/repairs"
                      }
                      state={workspaceEntryNavigationOptions.state}
                    >
                      Последний ремонт
                    </Link>
                  </Button>
                ) : null}
                {!dossier.overview.latestEstimateId &&
                !dossier.overview.latestRepairId ? (
                  <span className="text-muted-foreground">
                    Связанных документов нет.
                  </span>
                ) : null}
              </div>
            </CardContent>
          </Card>
          <Card>
            <CardHeader>
              <CardTitle>Характеристики</CardTitle>
            </CardHeader>
            <CardContent>
              <CharacteristicTags value={dossier.rentalItem.characteristics} />
            </CardContent>
          </Card>
          <Card>
            <CardHeader>
              <CardTitle>Осмотр и ремонт</CardTitle>
            </CardHeader>
            <CardContent className="grid grid-cols-2 gap-4 text-sm">
              <div>
                <p className="text-muted-foreground">Осмотров</p>
                <p className="font-medium">{dossier.inspections.length}</p>
              </div>
              <div>
                <p className="text-muted-foreground">Ремонтов всего</p>
                <p className="font-medium">{dossier.repairs.length}</p>
              </div>
              <div>
                <p className="text-muted-foreground">Активные ремонты</p>
                <p className="font-medium">
                  {dossier.overview.activeRepairCount}
                </p>
              </div>
              <div>
                <p className="text-muted-foreground">Смет</p>
                <p className="font-medium">{dossier.estimates.length}</p>
              </div>
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
                <div className="flex flex-wrap gap-2">
                  <Button
                    size="sm"
                    variant="outline"
                    onClick={() => setAddContentsOpen(true)}
                  >
                    Добавить
                  </Button>
                  {dossier.rentalItem.contentsItems.length > 0 ? (
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
              </div>
            </CardHeader>
            <CardContent>
              {dossier.rentalItem.contentsItems.length > 0 ? (
                <div className="flex flex-wrap gap-2">
                  {dossier.rentalItem.contentsItems.map((item) => (
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
          <PhotosRegister
            values={dossier.photoGroups}
            onOpen={setSelectedPhotoGroup}
            onAddPhoto={() => setPhotoManagerOpen(true)}
          />
        </TabsContent>
        <TabsContent value="inspections">
          <InspectionsRegister
            values={dossier.inspections}
            photoGroups={dossier.photoGroups}
            onOpenPhotoGroup={setSelectedPhotoGroup}
          />
        </TabsContent>
        <TabsContent value="estimates">
          <EstimatesRegister values={dossier.estimates} />
        </TabsContent>
        <TabsContent value="repair">
          <RepairsRegister values={dossier.repairs} />
        </TabsContent>
        <TabsContent value="reserves">
          <EmptyDossierRegister
            title="Резервы"
            description="Раздел подготовлен и пока не реализован."
            columns={["Клиент", "Статус", "Создан", "Истекает"]}
          />
        </TabsContent>
        <TabsContent value="shipments">
          <ShipmentsRegister values={dossier.shipments} />
        </TabsContent>
        <TabsContent value="returns">
          <EmptyDossierRegister
            title="Возвраты"
            description="Раздел подготовлен и пока не реализован."
            columns={["Дата", "От кого", "Статус", "Действия"]}
          />
        </TabsContent>
        <TabsContent value="history">
          <HistoryRegister
            values={dossier.activities}
            photoGroups={dossier.photoGroups}
            onOpenPhotoGroup={setSelectedPhotoGroup}
          />
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
                  <Field>
                    <FieldLabel htmlFor="general-comment">
                      Комментарий
                    </FieldLabel>
                    <Textarea
                      id="general-comment"
                      value={generalComment}
                      placeholder={dossier.rentalItem.comment ?? "Комментарий"}
                      onChange={(event) =>
                        setGeneralCommentDraft({
                          rentalItemId: dossier.rentalItem.id,
                          value: event.target.value,
                        })
                      }
                    />
                  </Field>
                </FieldGroup>
                <Button
                  className="mt-4"
                  disabled={generalCommentMutation.isPending}
                  onClick={() => generalCommentMutation.mutate()}
                >
                  {hasGeneralComment
                    ? "Сохранить изменения"
                    : "Добавить комментарий"}
                </Button>
              </CardContent>
            </Card>
            <Card className="h-full">
              <CardHeader>
                <CardTitle>Добавить запись в историю</CardTitle>
                <CardDescription>
                  Запись сохранится в неизменяемой истории бытовки.
                </CardDescription>
              </CardHeader>
              <CardContent>
                <FieldGroup>
                  <Field>
                    <FieldLabel htmlFor="manual-comment">Текст</FieldLabel>
                    <Textarea
                      id="manual-comment"
                      value={manualComment}
                      onChange={(event) => setManualComment(event.target.value)}
                    />
                  </Field>
                </FieldGroup>
                <Button
                  className="mt-4"
                  disabled={!manualComment.trim() || commentMutation.isPending}
                  onClick={() => commentMutation.mutate()}
                >
                  <HugeiconsIcon icon={Add01Icon} data-icon="inline-start" />
                  Добавить в историю
                </Button>
              </CardContent>
            </Card>
          </div>
          <CommentsRegister values={dossier.comments} />
        </TabsContent>
      </Tabs>
      <PhotoGroupDialog
        key={selectedPhotoGroup?.id ?? "closed-photo-group"}
        group={selectedPhotoGroup}
        open={selectedPhotoGroup !== null}
        onOpenChange={(open) => {
          if (!open) setSelectedPhotoGroup(null)
        }}
      />
      <AddContentsDialog
        item={dossier.rentalItem}
        open={addContentsOpen}
        onOpenChange={(open) => {
          setAddContentsOpen(open)
          if (!open) {
            void queryClient.invalidateQueries({
              queryKey: RENTAL_ITEM_DOSSIER_QUERY_KEY,
            })
          }
        }}
      />
      <MoveContentsToRentalItemDialog
        item={dossier.rentalItem}
        open={moveContentsToRentalItemOpen}
        onOpenChange={(open) => {
          setMoveContentsToRentalItemOpen(open)
          if (!open) {
            void queryClient.invalidateQueries({
              queryKey: RENTAL_ITEM_DOSSIER_QUERY_KEY,
            })
          }
        }}
      />
      <MoveContentsToStockDialog
        item={dossier.rentalItem}
        open={moveContentsToStockOpen}
        onOpenChange={(open) => {
          setMoveContentsToStockOpen(open)
          if (!open) {
            void queryClient.invalidateQueries({
              queryKey: RENTAL_ITEM_DOSSIER_QUERY_KEY,
            })
          }
        }}
      />
    </div>
  )
}
