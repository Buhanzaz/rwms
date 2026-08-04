import { useEffect, useState } from "react"
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query"
import { HugeiconsIcon } from "@hugeicons/react"
import {
  Add01Icon,
  ArrowLeft01Icon,
  CheckmarkCircle02Icon,
  ClipboardCheckIcon,
  FilterIcon,
  SentIcon,
} from "@hugeicons/core-free-icons"
import { useNavigate, useParams, useSearchParams } from "react-router-dom"
import { toast } from "sonner"

import { MobileAppRequiredDialog } from "@/components/mobile-app-required-dialog"
import { PageToolbar, PageToolbarActions } from "@/components/page-toolbar"
import { Badge } from "@/components/ui/badge"
import { Button } from "@/components/ui/button"
import {
  Card,
  CardContent,
  CardDescription,
  CardHeader,
  CardTitle,
} from "@/components/ui/card"
import { Checkbox } from "@/components/ui/checkbox"
import {
  Dialog,
  DialogContent,
  DialogDescription,
  DialogFooter,
  DialogHeader,
  DialogTitle,
} from "@/components/ui/dialog"
import { Field, FieldError, FieldLabel } from "@/components/ui/field"
import { Textarea } from "@/components/ui/textarea"
import {
  INVENTORY_QUERY_KEY,
  completeInventory,
  getInventoryFurnitureReview,
  getActiveInventory,
  getInventory,
  inventoryActiveQueryKey,
  inventoryDetailQueryKey,
  inventoryFinishPreviewQueryKey,
  inventoryFurnitureReviewQueryKey,
  inventoryListQueryKey,
  listInventories,
  previewInventoryCompletion,
  publishInventoryWorks,
  resolveInventoryFindingConflict,
  saveInventoryFurnitureReview,
  saveInventoryFinding,
  startInventoryFurnitureReview,
  startInventory,
  subscribeInventory,
} from "@/features/inventory/api/inventory-api"
import {
  getInventoryActor,
  hasInventoryWarehouseAccess,
} from "@/features/inventory/inventory-access"
import { InventoryAddDialog } from "@/features/inventory/inventory-add-dialog"
import {
  createEmptyInventoryFindingFilters,
  filterInventoryFindings,
} from "@/features/inventory/inventory-finding-filtering"
import { InventoryFindingFilters } from "@/features/inventory/inventory-finding-filters"
import { InventoryFindingsList } from "@/features/inventory/inventory-findings-list"
import { InventoryFurnitureReview } from "@/features/inventory/inventory-furniture-review"
import {
  inventoryFurnitureReconciliationCompletionNotice,
  inventoryFurnitureReconciliationPresentation,
} from "@/features/inventory/inventory-furniture-reconciliation-presentation"
import { InventoryInspectionWorkspace } from "@/features/inventory/inventory-inspection-workspace"
import { InventoryMembershipMovements } from "@/features/inventory/inventory-membership-movements"
import {
  InventoryEmptyState,
  InventoryUnavailable,
} from "@/features/inventory/inventory-presentation"
import {
  inventoryPublicationLabel,
  inventoryPublicationNotice,
} from "@/features/inventory/inventory-publication-presentation"
import { InventorySessionList } from "@/features/inventory/inventory-session-list"
import { InventoryStartDialog } from "@/features/inventory/inventory-start-dialog"
import { InventoryStatistics } from "@/features/inventory/inventory-statistics"
import {
  inventoryCompletionRiskSignature,
  inventoryRepairMovementCount,
  reconcileInventoryRepairTaskPlans,
  toInventoryRepairPlanSnapshot,
} from "@/features/inventory/domain/inventory-domain"
import type {
  InventoryFindingDto,
  InventoryFurnitureReviewDto,
  InventorySessionDto,
} from "@/features/inventory/model/inventory"
import { RENTAL_ITEM_STATUS_LABEL } from "@/features/rental-items/model/rental-item"
import type { ReadyMediaReference } from "@/features/media/media-service"
import type { RepairEstimateLineDto } from "@/features/repair-estimates/model/repair-estimate"
import {
  RepairWorkCompletionDialog,
  type RepairWorkCompletionResult,
} from "@/features/repair-estimates/repair-work-completion-dialog"
import {
  getWarehouseQueueCapabilities,
  warehouseQueueCapabilitiesQueryKey,
} from "@/features/repair-estimates/api/warehouse-queue-capabilities"
import { useAuth } from "@/features/auth/use-auth"
import { useIsMobile } from "@/hooks/use-mobile"
import { useResponsiveFiltersOpen } from "@/hooks/use-responsive-filters-open"
import { useWarehouse } from "@/hooks/use-warehouse"
import {
  useWorkspaceBack,
  workspaceEntryNavigationOptions,
} from "@/hooks/use-workspace-back"
import { ApiError } from "@/lib/api-client"

function errorMessage(error: unknown) {
  if (error instanceof ApiError && error.status === 409) {
    return "Данные инвентаризации изменились. Обновите страницу и повторите действие."
  }
  return error instanceof Error ? error.message : "Операция не выполнена"
}

function showPublicationNotice(session: InventorySessionDto) {
  const notice = inventoryPublicationNotice(session)
  toast[notice.kind](notice.message)
}

function showFurnitureReconciliationNotice(session: InventorySessionDto) {
  const notice = inventoryFurnitureReconciliationCompletionNotice(
    session.furnitureReconciliationState
  )
  if (notice) toast[notice.kind](notice.message)
}

function conflictValue(value: string | null) {
  return value?.trim() || "—"
}

function structuredConflictValue(value: string | null) {
  const normalized = value?.trim()
  if (!normalized) return null
  try {
    const parsed: unknown = JSON.parse(normalized)
    return parsed !== null && typeof parsed === "object"
      ? JSON.stringify(parsed, null, 2)
      : null
  } catch {
    return null
  }
}

function ConflictValue({ value }: { value: string | null }) {
  const structured = structuredConflictValue(value)
  if (structured) {
    return (
      <pre className="mt-1 max-w-full overflow-x-auto rounded-md bg-muted p-2 font-mono text-xs break-words whitespace-pre-wrap text-foreground">
        {structured}
      </pre>
    )
  }
  return <span className="text-foreground">{conflictValue(value)}</span>
}

function InventoryConflictResolution({
  findings,
  pending,
  error,
  onAcceptRegistry,
  onKeepInspection,
  onOpenFinding,
}: {
  findings: InventoryFindingDto[]
  pending: boolean
  error: string | null
  onAcceptRegistry: (finding: InventoryFindingDto) => void
  onKeepInspection: (finding: InventoryFindingDto) => void
  onOpenFinding: (finding: InventoryFindingDto) => void
}) {
  if (findings.length === 0) return null
  return (
    <section
      className="flex flex-col gap-3"
      aria-labelledby="conflicts-title"
    >
      <h2 id="conflicts-title" className="text-lg font-semibold">
        Конфликты реестра
      </h2>
      {findings.map((finding) => (
        <Card key={finding.id} size="sm">
          <CardHeader>
            <div className="flex flex-wrap items-center justify-between gap-2">
              <CardTitle>{finding.cabinNumber}</CardTitle>
              {finding.currentSnapshot ? (
                <Badge variant="outline">
                  {RENTAL_ITEM_STATUS_LABEL[finding.currentSnapshot.status]}
                </Badge>
              ) : null}
            </div>
          </CardHeader>
          <CardContent className="flex flex-col gap-3 text-sm">
            {finding.currentSnapshot?.status === "RENTED" ? (
              <p>
                <span className="text-muted-foreground">Арендатор:</span>
                {finding.currentSnapshot.tenant?.trim() || "не указан"}
              </p>
            ) : null}
            <ul className="flex flex-col gap-2">
              {finding.conflicts.map((conflict) => (
                <li key={conflict.code} className="rounded-md border p-3">
                  <p className="font-medium">{conflict.message}</p>
                  <div
                    className="mt-2 grid gap-2 text-muted-foreground sm:grid-cols-[minmax(0,1fr)_auto_minmax(0,1fr)] sm:items-start"
                    aria-label="Было → Стало"
                  >
                    <div className="min-w-0">
                      <span className="block text-xs">Было</span>
                      <ConflictValue value={conflict.expected} />
                    </div>
                    <span aria-hidden="true" className="self-center">
                      →
                    </span>
                    <div className="min-w-0">
                      <span className="block text-xs">Стало</span>
                      <ConflictValue value={conflict.actual} />
                    </div>
                  </div>
                </li>
              ))}
            </ul>
            <div className="flex flex-col gap-1 text-muted-foreground">
              <p>
                «Сохранить актуальные данные реестра» — работы и материалы
                этого осмотра не будут переданы.
              </p>
              <p>
                «Применить данные инвентаризации» — сохраняет работы и
                материалы этого осмотра; потребуется указать причину.
              </p>
            </div>
            <div className="grid gap-2 sm:grid-cols-3">
              <Button
                type="button"
                className="w-full"
                disabled={pending || finding.currentSnapshot === null}
                onClick={() => onAcceptRegistry(finding)}
              >
                Сохранить актуальные данные реестра
              </Button>
              <Button
                type="button"
                className="w-full"
                variant="secondary"
                disabled={pending}
                onClick={() => onKeepInspection(finding)}
              >
                Применить данные инвентаризации
              </Button>
              <Button
                type="button"
                className="w-full"
                variant="outline"
                disabled={pending}
                onClick={() => onOpenFinding(finding)}
              >
                Дополнить осмотр
              </Button>
            </div>
          </CardContent>
        </Card>
      ))}
      {error ? (
        <p role="alert" className="text-sm text-destructive">
          {error}
        </p>
      ) : null}
    </section>
  )
}

function useInventorySync() {
  const queryClient = useQueryClient()
  useEffect(
    () =>
      subscribeInventory(
        () =>
          void queryClient.invalidateQueries({ queryKey: INVENTORY_QUERY_KEY })
      ),
    [queryClient]
  )
}

function businessDateInTimeZone(timeZone: string) {
  const parts = new Intl.DateTimeFormat("en-CA", {
    timeZone,
    year: "numeric",
    month: "2-digit",
    day: "2-digit",
  }).formatToParts(new Date())
  const get = (type: Intl.DateTimeFormatPartTypes) =>
    parts.find((part) => part.type === type)?.value ?? ""
  return `${get("year")}-${get("month")}-${get("day")}`
}

function InventoryLoading({
  children = "Загрузка инвентаризации...",
}: {
  children?: string
}) {
  return <p className="text-sm text-muted-foreground">{children}</p>
}

export function InventoryEntryPage() {
  useInventorySync()
  const navigate = useNavigate()
  const queryClient = useQueryClient()
  const { selectedWarehouse } = useWarehouse()
  const { currentUser } = useAuth()
  const [dialogOpen, setDialogOpen] = useState(false)
  const warehouseId = selectedWarehouse?.id ?? "none"
  const actor = selectedWarehouse
    ? getInventoryActor(currentUser, selectedWarehouse.id)
    : null
  const activeQuery = useQuery({
    queryKey: inventoryActiveQueryKey(warehouseId),
    queryFn: () =>
      selectedWarehouse && actor
        ? getActiveInventory({ warehouseId: selectedWarehouse.id, actor })
        : Promise.resolve(null),
    enabled: selectedWarehouse !== null && actor !== null,
  })

  useEffect(() => {
    if (activeQuery.data)
      navigate(`/inventory/${activeQuery.data.id}`, { replace: true })
  }, [activeQuery.data, navigate])

  const startMutation = useMutation({
    mutationFn: () => {
      if (!selectedWarehouse || !actor) throw new Error("Нет доступа к складу")
      return startInventory({
        warehouse: {
          id: selectedWarehouse.id,
          name: selectedWarehouse.name,
          timeZone: selectedWarehouse.timeZone,
        },
        actor,
        businessDate: businessDateInTimeZone(selectedWarehouse.timeZone),
      })
    },
    onSuccess: (session) => {
      void queryClient.invalidateQueries({ queryKey: INVENTORY_QUERY_KEY })
      navigate(`/inventory/${session.id}`, { replace: true })
    },
  })

  if (!selectedWarehouse)
    return (
      <InventoryUnavailable
        title="Склад не выбран"
        description="Выберите склад в меню."
      />
    )
  if (
    !actor ||
    !hasInventoryWarehouseAccess(currentUser, selectedWarehouse.id, "VIEW")
  )
    return (
      <InventoryUnavailable
        title="Инвентаризация недоступна"
        description="Нет доступа к выбранному складу."
      />
    )
  if (activeQuery.isLoading || activeQuery.data) return <InventoryLoading />
  if (activeQuery.error)
    return (
      <InventoryUnavailable
        title="Сервис инвентаризации недоступен"
        description={errorMessage(activeQuery.error)}
      />
    )

  const canStart = hasInventoryWarehouseAccess(
    currentUser,
    selectedWarehouse.id,
    "EDIT"
  )
  return (
    <div className="flex h-full min-h-0 flex-col gap-4">
      <Card className="max-w-2xl">
        <CardHeader>
          <CardTitle>Инвентаризация склада</CardTitle>
          <CardDescription>
            Активной сессии для склада «{selectedWarehouse.name}» нет.
          </CardDescription>
        </CardHeader>
        <CardContent className="flex flex-wrap gap-2">
          <Button
            type="button"
            disabled={!canStart}
            onClick={() => setDialogOpen(true)}
          >
            <HugeiconsIcon icon={ClipboardCheckIcon} data-icon="inline-start" />
            Создать инвентаризацию
          </Button>
          <Button
            type="button"
            variant="outline"
            onClick={() => navigate("/inventory/history")}
          >
            История
          </Button>
        </CardContent>
      </Card>
      <InventoryStartDialog
        open={dialogOpen}
        warehouseName={selectedWarehouse.name}
        authorName={actor.displayName}
        businessDate={businessDateInTimeZone(selectedWarehouse.timeZone)}
        pending={startMutation.isPending}
        error={startMutation.error ? errorMessage(startMutation.error) : null}
        onOpenChange={setDialogOpen}
        onConfirm={() => startMutation.mutate()}
      />
    </div>
  )
}

function FindingEditor({
  session,
  finding,
  readOnly,
  onBack,
}: {
  session: InventorySessionDto
  finding: InventoryFindingDto
  readOnly: boolean
  onBack: () => void
}) {
  const { accessToken, currentUser } = useAuth()
  const queryClient = useQueryClient()
  const actor = getInventoryActor(currentUser, session.warehouseId)
  const [comment, setComment] = useState(finding.comment)
  const [lines, setLines] = useState<RepairEstimateLineDto[]>(finding.lines)
  const [media, setMedia] = useState<ReadyMediaReference[]>(finding.media)
  const [coverMediaId, setCoverMediaId] = useState<string | null>(
    finding.coverMediaId
  )
  const [mediaReady, setMediaReady] = useState(false)
  const [completionOpen, setCompletionOpen] = useState(false)
  const movementCapabilitiesQuery = useQuery({
    queryKey: warehouseQueueCapabilitiesQueryKey(session.warehouseId),
    queryFn: () =>
      getWarehouseQueueCapabilities(accessToken!, session.warehouseId),
    enabled: !readOnly && Boolean(accessToken && session.warehouseId),
  })
  const movementRouteAvailable =
    movementCapabilitiesQuery.data?.movementToShipmentAvailable === true
  const mutation = useMutation({
    mutationFn: (completion: RepairWorkCompletionResult | null) => {
      if (!actor) throw new Error("Нет доступа")
      return saveInventoryFinding({
        inventoryId: session.id,
        expectedVersion: session.version,
        actor,
        findingId: finding.id,
        comment,
        media,
        coverMediaId,
        lines,
        repairPlans: completion
          ? completion.taskPlans.map(toInventoryRepairPlanSnapshot)
          : finding.repairPlans,
        repairCompletionMode: completion?.completionMode ?? null,
        movementRequired:
          movementRouteAvailable && completion?.movementRequired === true,
        logisticsPlanningMode:
          movementRouteAvailable && completion?.movementRequired === true
            ? completion.logisticsPlanningMode
            : "AUTO",
        logisticsScheduledDate:
          movementRouteAvailable && completion?.movementRequired === true
            ? completion.logisticsScheduledDate
            : null,
        priority: completion?.priority ?? finding.repairPriority,
      })
    },
    onSuccess: () => {
      setCompletionOpen(false)
      void queryClient.invalidateQueries({ queryKey: INVENTORY_QUERY_KEY })
      void queryClient.invalidateQueries({
        queryKey: inventoryFurnitureReviewQueryKey(session.id),
      })
      toast.success(
        lines.length > 0
          ? "Осмотр и работы сохранены"
          : "Бытовка готова по результату инвентаризации"
      )
      onBack()
    },
  })
  const snapshot = finding.currentSnapshot ?? finding.expectedSnapshot
  const acceptsAfterRentWithoutEstimate =
    snapshot?.status === "AFTER_RENT" && lines.length === 0
  const missingRequiredAcceptancePhoto =
    acceptsAfterRentWithoutEstimate && media.length === 0
  const missingCoverPhoto = media.length > 0 && coverMediaId === null
  return (
    <div className="flex h-full min-h-0 flex-col gap-4 overflow-hidden">
      <PageToolbar>
        <Button type="button" variant="outline" onClick={onBack}>
          <HugeiconsIcon icon={ArrowLeft01Icon} data-icon="inline-start" />
          Назад
        </Button>
        {!readOnly ? (
          <PageToolbarActions>
            {missingRequiredAcceptancePhoto ? (
              <p role="status" className="text-sm text-muted-foreground">
                Чтобы принять бытовку после аренды, добавьте хотя бы одну
                готовую фотографию.
              </p>
            ) : null}
            <Button
              type="button"
              disabled={
                mutation.isPending ||
                !mediaReady ||
                missingRequiredAcceptancePhoto ||
                missingCoverPhoto
              }
              onClick={() =>
                lines.length > 0
                  ? setCompletionOpen(true)
                  : mutation.mutate(null)
              }
            >
              {mutation.isPending
                ? "Сохраняем..."
                : acceptsAfterRentWithoutEstimate
                  ? "Принять бытовку"
                  : "Сохранить осмотр"}
            </Button>
          </PageToolbarActions>
        ) : null}
      </PageToolbar>
      {mutation.error ? (
        <p role="alert" className="text-sm text-destructive">
          {errorMessage(mutation.error)}
        </p>
      ) : null}
      <InventoryInspectionWorkspace
        accessToken={accessToken}
        warehouseId={session.warehouseId}
        findingId={finding.id}
        cabinNumber={finding.cabinNumber}
        statusLabel={snapshot ? RENTAL_ITEM_STATUS_LABEL[snapshot.status] : "—"}
        tenant={snapshot?.tenant ?? null}
        businessDate={session.businessDate}
        comment={comment}
        lines={lines}
        media={media}
        repairCompletionMode={finding.repairCompletionMode}
        movementRequired={finding.movementRequired}
        repairPlans={finding.repairPlans}
        readOnly={readOnly}
        coverMediaId={coverMediaId}
        message={
          finding.conflicts.map((conflict) => conflict.message).join("; ") ||
          null
        }
        onCommentChange={setComment}
        onLinesChange={setLines}
        onMediaChange={(nextMedia) => {
          setMedia(nextMedia)
          setCoverMediaId((currentCoverMediaId) =>
            currentCoverMediaId &&
            nextMedia.some(
              (reference) => reference.mediaId === currentCoverMediaId
            )
              ? currentCoverMediaId
              : null
          )
        }}
        onMediaReadyChange={setMediaReady}
        onCoverMediaIdChange={setCoverMediaId}
      />
      <RepairWorkCompletionDialog
        open={completionOpen}
        accessToken={accessToken}
        warehouseId={session.warehouseId}
        lines={lines}
        pending={mutation.isPending}
        error={
          completionOpen && mutation.error ? errorMessage(mutation.error) : null
        }
        title="Настройка работ по осмотру"
        description="Выберите режим и порядок этапов. Очереди фиксируются каталогом; настройка будет сохранена в снимке инвентаризации и передана в ремонт только после её завершения."
        completeLabel="Сохранить осмотр"
        pendingLabel="Сохраняем..."
        previewKey={`inventory:${session.id}:${finding.id}:${session.version}`}
        initialCompletionMode={finding.repairCompletionMode ?? undefined}
        initialMovementRequired={finding.movementRequired}
        initialLogisticsPlanningMode={finding.logisticsPlanningMode}
        initialLogisticsScheduledDate={finding.logisticsScheduledDate}
        initialPriority={finding.repairPriority}
        movementRouteAvailable={movementRouteAvailable}
        routingSelectionAvailable={false}
        planStructureEditingAvailable={false}
        reconcileInitialPlans={
          finding.repairPlans.length > 0
            ? (prepared) =>
                reconcileInventoryRepairTaskPlans({
                  lines,
                  stored: finding.repairPlans,
                  prepared,
                })
            : undefined
        }
        onOpenChange={setCompletionOpen}
        onComplete={(completion) => mutation.mutate(completion)}
      />
    </div>
  )
}

function useInventoryDetailRoute() {
  useInventorySync()
  const { inventoryId = "" } = useParams()
  const { currentUser } = useAuth()
  const { selectedWarehouse } = useWarehouse()
  const actor = selectedWarehouse
    ? getInventoryActor(currentUser, selectedWarehouse.id)
    : null
  const query = useQuery({
    queryKey: inventoryDetailQueryKey(inventoryId),
    queryFn: () => getInventory(inventoryId),
    enabled: Boolean(inventoryId && actor),
    refetchInterval: (query) =>
      query.state.data?.status === "ACTIVE" ? 5_000 : false,
  })
  const session =
    query.data && query.data.warehouseId === selectedWarehouse?.id
      ? query.data
      : null
  return { inventoryId, actor, selectedWarehouse, query, session }
}

export function InventorySessionPage() {
  const isMobile = useIsMobile()
  const navigate = useNavigate()
  const [searchParams] = useSearchParams()
  const { currentUser } = useAuth()
  const { inventoryId, actor, selectedWarehouse, query, session } =
    useInventoryDetailRoute()
  const [filters, setFilters] = useState(createEmptyInventoryFindingFilters)
  const { filtersOpen, setFiltersOpen } = useResponsiveFiltersOpen()
  const [addOpen, setAddOpen] = useState(false)
  const [mobileAppOperation, setMobileAppOperation] = useState<
    "Осмотр бытовки" | null
  >(null)
  const findingId = searchParams.get("findingId")
  const goBack = useWorkspaceBack(`/inventory/${inventoryId}`)
  const selectedFinding =
    session?.findings.find((item) => item.id === findingId) ?? null

  if (!actor || !selectedWarehouse)
    return (
      <InventoryUnavailable
        title="Инвентаризация недоступна"
        description="Нет доступа к выбранному складу."
      />
    )
  if (query.isLoading) return <InventoryLoading />
  if (query.error)
    return (
      <InventoryUnavailable
        title="Сервис инвентаризации недоступен"
        description={errorMessage(query.error)}
      />
    )
  if (!session)
    return (
      <InventoryUnavailable
        title="Инвентаризация недоступна"
        description="Сессия не найдена на выбранном складе или нет прав на просмотр."
      />
    )
  if (session.status !== "ACTIVE") {
    navigate(`/inventory/history/${session.id}`, { replace: true })
    return <InventoryLoading />
  }
  if (isMobile && findingId)
    return (
      <MobileAppRequiredDialog
        open={true}
        onOpenChange={(open) => {
          if (!open) navigate(`/inventory/${inventoryId}`, { replace: true })
        }}
        operation="Осмотр бытовки"
      />
    )
  if (findingId) {
    if (!selectedFinding)
      return (
        <InventoryUnavailable
          title="Бытовка недоступна"
          description="Результат осмотра не найден."
        />
      )
    return (
      <FindingEditor
        key={selectedFinding.id}
        session={session}
        finding={selectedFinding}
        readOnly={
          !hasInventoryWarehouseAccess(currentUser, session.warehouseId, "EDIT")
        }
        onBack={goBack}
      />
    )
  }

  const inspected = session.findings.filter(
    (item) => item.inspectionStatus !== "NOT_INSPECTED"
  ).length
  const canEdit = hasInventoryWarehouseAccess(
    currentUser,
    session.warehouseId,
    "EDIT"
  )
  const canManage = hasInventoryWarehouseAccess(
    currentUser,
    session.warehouseId,
    "MANAGE"
  )
  const visible = filterInventoryFindings(session.findings, filters)
  const openFinding = (finding: InventoryFindingDto) => {
    if (isMobile) {
      setMobileAppOperation("Осмотр бытовки")
      return
    }
    navigate(
      `/inventory/${session.id}?findingId=${encodeURIComponent(finding.id)}`,
      workspaceEntryNavigationOptions
    )
  }
  return (
    <div className="flex h-full min-h-0 flex-col gap-4 overflow-hidden">
      <PageToolbar>
        <div className="flex flex-wrap items-center gap-2">
          <Badge variant="secondary">
            Проверено {inspected} из {session.findings.length}
          </Badge>
          <div
            className="h-2 w-40 overflow-hidden rounded-full bg-muted"
            role="progressbar"
            aria-valuemin={0}
            aria-valuemax={session.findings.length}
            aria-valuenow={inspected}
            aria-label="Прогресс сверки"
          >
            <div
              className="h-full bg-primary"
              style={{
                width: `${session.findings.length ? (inspected / session.findings.length) * 100 : 0}%`,
              }}
            />
          </div>
        </div>
        <PageToolbarActions className="w-full flex-nowrap overflow-x-auto pb-1 sm:w-auto sm:pb-0">
          <Button
            type="button"
            size="icon"
            variant={filtersOpen ? "secondary" : "outline"}
            aria-label={
              filtersOpen
                ? "Скрыть фильтры инвентаризации"
                : "Показать фильтры инвентаризации"
            }
            aria-controls="inventory-finding-filters"
            aria-expanded={filtersOpen}
            onClick={() => setFiltersOpen((current) => !current)}
          >
            <HugeiconsIcon icon={FilterIcon} aria-hidden="true" />
          </Button>
          <Button
            type="button"
            variant="outline"
            disabled={!canEdit}
            onClick={() => setAddOpen(true)}
          >
            <HugeiconsIcon icon={Add01Icon} data-icon="inline-start" />
            Добавить бытовку
          </Button>
          <Button
            type="button"
            className="ml-auto"
            disabled={!canManage}
            onClick={() =>
              navigate(
                `/inventory/${session.id}/finish`,
                workspaceEntryNavigationOptions
              )
            }
          >
            <HugeiconsIcon
              icon={CheckmarkCircle02Icon}
              data-icon="inline-start"
            />
            Закончить
          </Button>
        </PageToolbarActions>
      </PageToolbar>
      <div id="inventory-finding-filters" hidden={!filtersOpen}>
        <InventoryFindingFilters filters={filters} onChange={setFilters} />
      </div>
      <div className="min-h-0 flex-1 overflow-y-auto md:flex">
        {visible.length > 0 ? (
          <InventoryFindingsList
            findings={visible}
            canInspect={canEdit}
            onOpen={openFinding}
          />
        ) : (
          <InventoryEmptyState>
            По выбранному фильтру бытовок нет.
          </InventoryEmptyState>
        )}
      </div>
      <InventoryAddDialog
        open={addOpen}
        session={session}
        actor={actor}
        onOpenChange={setAddOpen}
        onResolved={(_, finding) => openFinding(finding)}
      />
      <MobileAppRequiredDialog
        open={mobileAppOperation !== null}
        onOpenChange={(open) => {
          if (!open) setMobileAppOperation(null)
        }}
        operation={mobileAppOperation ?? "Осмотр бытовки"}
      />
    </div>
  )
}

export function InventoryFinishPage() {
  const navigate = useNavigate()
  const queryClient = useQueryClient()
  const { currentUser } = useAuth()
  const { actor, query, session } = useInventoryDetailRoute()
  const [acknowledgedRiskSignature, setAcknowledgedRiskSignature] = useState<
    string | null
  >(null)
  const [keepInspectionFinding, setKeepInspectionFinding] = useState<{
    finding: InventoryFindingDto
    reviewSession: InventorySessionDto
  } | null>(null)
  const [keepInspectionReason, setKeepInspectionReason] = useState("")
  const [keepInspectionSubmitted, setKeepInspectionSubmitted] = useState(false)
  const [furnitureReviewDirty, setFurnitureReviewDirty] = useState(false)
  const canManage = Boolean(
    session &&
    hasInventoryWarehouseAccess(currentUser, session.warehouseId, "MANAGE")
  )
  const furnitureReviewQueryKey = inventoryFurnitureReviewQueryKey(
    session?.id ?? null
  )
  const furnitureReviewQuery = useQuery({
    queryKey: furnitureReviewQueryKey,
    queryFn: () =>
      session
        ? getInventoryFurnitureReview(session.id)
        : Promise.resolve<InventoryFurnitureReviewDto | null>(null),
    enabled: Boolean(
      session &&
      actor &&
      canManage &&
      session.status === "ACTIVE" &&
      session.reviewStage === "FURNITURE"
    ),
  })
  const completionPreviewQueryKey = inventoryFinishPreviewQueryKey(
    session?.id ?? null,
    session?.version ?? null
  )
  const furnitureReviewMatchesSession =
    furnitureReviewQuery.data?.sessionRevision === session?.version
  const completionPreviewQuery = useQuery({
    queryKey: completionPreviewQueryKey,
    queryFn: () =>
      session && actor
        ? previewInventoryCompletion({
            inventoryId: session.id,
            expectedVersion: session.version,
            actor,
          })
        : Promise.resolve(null),
    enabled: Boolean(
      session &&
      actor &&
      canManage &&
      session.status === "ACTIVE" &&
      session.reviewStage === "FURNITURE" &&
      furnitureReviewQuery.data?.confirmed &&
      furnitureReviewMatchesSession &&
      !furnitureReviewDirty
    ),
    refetchOnWindowFocus: "always",
  })
  const reviewedFurnitureSession = completionPreviewQuery.data
  const resolutionMutation = useMutation({
    mutationFn: ({
      reviewSession,
      finding,
      strategy,
      reason,
    }: {
      reviewSession: InventorySessionDto
      finding: InventoryFindingDto
      strategy: "ACCEPT_REGISTRY" | "KEEP_INSPECTION"
      reason: string | null
    }) => {
      if (!actor) throw new Error("Инвентаризация недоступна")
      return resolveInventoryFindingConflict({
        inventoryId: reviewSession.id,
        expectedVersion: reviewSession.version,
        expectedFindingVersion: finding.version,
        actor,
        findingId: finding.id,
        strategy,
        reason,
      })
    },
    onSuccess: (updated) => {
      queryClient.setQueryData(inventoryDetailQueryKey(updated.id), updated)
      setAcknowledgedRiskSignature(null)
      setKeepInspectionFinding(null)
      setKeepInspectionReason("")
      setKeepInspectionSubmitted(false)
      setFurnitureReviewDirty(false)
      void queryClient.removeQueries({
        queryKey: [...INVENTORY_QUERY_KEY, "finish-preview", updated.id],
      })
      void queryClient.invalidateQueries({ queryKey: furnitureReviewQueryKey })
      void queryClient.invalidateQueries({ queryKey: INVENTORY_QUERY_KEY })
      toast.success("Конфликт урегулирован")
    },
    onError: () => {
      void queryClient.invalidateQueries({ queryKey: INVENTORY_QUERY_KEY })
    },
  })
  const startFurnitureReviewMutation = useMutation({
    mutationFn: (acknowledgeIncomplete: boolean) => {
      if (!session) throw new Error("Инвентаризация недоступна")
      return startInventoryFurnitureReview({
        session,
        acknowledgeIncomplete,
      })
    },
    onSuccess: (review) => {
      queryClient.setQueryData(furnitureReviewQueryKey, review)
      setAcknowledgedRiskSignature(null)
      setFurnitureReviewDirty(false)
      void queryClient.invalidateQueries({ queryKey: INVENTORY_QUERY_KEY })
      toast.success("Проверка бытовок завершена. Перейдите к сверке мебели.")
    },
  })
  const saveFurnitureReviewMutation = useMutation({
    mutationFn: (review: InventoryFurnitureReviewDto) => {
      if (!session) throw new Error("Инвентаризация недоступна")
      return saveInventoryFurnitureReview({
        review,
        session: reviewedFurnitureSession ?? session,
      })
    },
    onSuccess: (review) => {
      queryClient.setQueryData(furnitureReviewQueryKey, review)
      setFurnitureReviewDirty(false)
      void queryClient.invalidateQueries({ queryKey: INVENTORY_QUERY_KEY })
      toast.success("Сверка мебели сохранена")
    },
  })
  const completionMutation = useMutation({
    mutationFn: async (publish: boolean) => {
      if (!reviewedFurnitureSession || !actor) {
        throw new Error("Инвентаризация недоступна")
      }
      const refreshed = await previewInventoryCompletion({
        inventoryId: reviewedFurnitureSession.id,
        expectedVersion: reviewedFurnitureSession.version,
        actor,
      })
      queryClient.setQueryData(completionPreviewQueryKey, refreshed)
      if (refreshed.findings.some((finding) => finding.conflicts.length > 0)) {
        throw new Error(
          "Урегулируйте все конфликты реестра перед завершением инвентаризации"
        )
      }
      const completed = await completeInventory({
        inventoryId: refreshed.id,
        expectedVersion: refreshed.version,
        actor,
      })
      return publish
        ? publishInventoryWorks({ inventoryId: completed.id, actor })
        : completed
    },
    onSuccess: (completed, publish) => {
      void queryClient.invalidateQueries({ queryKey: INVENTORY_QUERY_KEY })
      if (publish) {
        showPublicationNotice(completed)
      }
      showFurnitureReconciliationNotice(completed)
      navigate(`/inventory/history/${completed.id}`, { replace: true })
    },
    onError: () => {
      setFurnitureReviewDirty(false)
      void completionPreviewQuery.refetch()
      void queryClient.invalidateQueries({ queryKey: INVENTORY_QUERY_KEY })
    },
  })
  const goBack = useWorkspaceBack(
    session ? `/inventory/${session.id}` : "/inventory"
  )
  if (
    query.isLoading ||
    (session?.reviewStage === "FURNITURE" && furnitureReviewQuery.isLoading)
  ) {
    return <InventoryLoading />
  }
  if (!session || !actor || !canManage)
    return (
      <InventoryUnavailable
        title="Сверка недоступна"
        description="Сессия не найдена на выбранном складе или нет прав MANAGE."
      />
    )
  if (session.status !== "ACTIVE")
    return (
      <InventoryUnavailable
        title="Инвентаризация уже завершена"
        description="Откройте её в истории."
      />
    )
  if (
    session.reviewStage === "FURNITURE" &&
    (furnitureReviewQuery.error || !furnitureReviewQuery.data)
  )
    return (
      <InventoryUnavailable
        title="Сверка мебели недоступна"
        description={
          furnitureReviewQuery.error
            ? errorMessage(furnitureReviewQuery.error)
            : "Не удалось получить снимок мебели для этой инвентаризации."
        }
      />
    )
  const furnitureReview = furnitureReviewQuery.data
  const activeReviewSession =
    session.reviewStage === "FURNITURE" &&
    furnitureReview?.confirmed &&
    furnitureReviewMatchesSession &&
    !furnitureReviewDirty &&
    reviewedFurnitureSession
      ? reviewedFurnitureSession
      : session
  const missing = activeReviewSession.findings.filter(
    (item) => item.reconciliationStatus === "MISSING"
  ).length
  const notInspected = activeReviewSession.findings.filter(
    (item) => item.inspectionStatus === "NOT_INSPECTED"
  ).length
  const conflictingFindings = activeReviewSession.findings.filter(
    (item) => item.conflicts.length > 0
  )
  const unresolvedConflicts = conflictingFindings.length > 0
  const withWork = activeReviewSession.findings.filter(
    (item) => item.lines.length > 0
  )
  const repairMovementCount = inventoryRepairMovementCount(
    activeReviewSession.findings
  )
  const completionCounters = [
    ["Всего", activeReviewSession.findings.length],
    [
      "Ожидалось",
      activeReviewSession.statistics?.expectedCount ??
        activeReviewSession.findings.length,
    ],
    [
      "Проверено",
      activeReviewSession.statistics?.inspectedCount ??
        activeReviewSession.findings.length - notInspected,
    ],
    ["Не найдено", activeReviewSession.statistics?.missingCount ?? missing],
    ["Не проверено", notInspected],
    ["Готовы", activeReviewSession.statistics?.readyCount ?? 0],
    [
      "С работами",
      activeReviewSession.statistics?.withWorkCount ?? withWork.length,
    ],
    ["Добавлено", activeReviewSession.statistics?.addedCount ?? 0],
    [
      "Конфликты",
      activeReviewSession.statistics?.conflictCount ??
        conflictingFindings.length,
    ],
    ["Перемещения на ремонт и вывозы", repairMovementCount],
  ] as const
  const riskSignature = inventoryCompletionRiskSignature(
    activeReviewSession.findings
  )
  const confirmationRequired = riskSignature.length > 0
  const confirmed =
    !confirmationRequired || acknowledgedRiskSignature === riskSignature
  return (
    <div className="flex h-full min-h-0 flex-col gap-4 overflow-y-auto">
      <PageToolbar>
        <Button type="button" variant="outline" onClick={goBack}>
          <HugeiconsIcon icon={ArrowLeft01Icon} data-icon="inline-start" />
          Назад
        </Button>
      </PageToolbar>
      {session.reviewStage === "CABINS" ? (
        <>
          <Card>
            <CardHeader>
              <CardTitle>Итоговая сверка бытовок</CardTitle>
              <CardDescription>
                Проверьте результаты по бытовкам, затем перейдите к сверке
                мебели.
              </CardDescription>
            </CardHeader>
            <CardContent className="flex flex-col gap-4">
              <div className="flex flex-wrap gap-2">
                {completionCounters.map(([label, value]) => (
                  <Badge key={label} variant="secondary">
                    {label}: {value}
                  </Badge>
                ))}
              </div>
              {confirmationRequired ? (
                <Field orientation="horizontal">
                  <Checkbox
                    id="inventory-finish-confirm"
                    checked={confirmed}
                    onCheckedChange={(value) =>
                      setAcknowledgedRiskSignature(
                        value === true ? riskSignature : null
                      )
                    }
                  />
                  <FieldLabel
                    htmlFor="inventory-finish-confirm"
                    className="font-normal"
                  >
                    Я проверил непроверенные и ненайденные бытовки
                  </FieldLabel>
                </Field>
              ) : null}
              {unresolvedConflicts ? (
                <p role="alert" className="text-sm text-destructive">
                  Урегулируйте все конфликты реестра перед переходом к сверке
                  мебели.
                </p>
              ) : null}
              {startFurnitureReviewMutation.error ? (
                <p role="alert" className="text-sm text-destructive">
                  {errorMessage(startFurnitureReviewMutation.error)}
                </p>
              ) : null}
              <Button
                type="button"
                disabled={
                  startFurnitureReviewMutation.isPending ||
                  unresolvedConflicts ||
                  (confirmationRequired && !confirmed)
                }
                onClick={() =>
                  startFurnitureReviewMutation.mutate(confirmationRequired)
                }
              >
                Завершить проверку бытовок и перейти к мебели
              </Button>
            </CardContent>
          </Card>
          {activeReviewSession.statistics ? (
            <section
              className="flex flex-col gap-3"
              aria-labelledby="preview-title"
            >
              <h2 id="preview-title" className="text-lg font-semibold">
                Предварительные итоги
              </h2>
              <InventoryStatistics
                statistics={activeReviewSession.statistics}
                findings={activeReviewSession.findings}
                showCounters={false}
              />
            </section>
          ) : null}
          <InventoryMembershipMovements
            movements={activeReviewSession.membershipMovements}
            warehouse={activeReviewSession.warehouse}
          />
          <InventoryConflictResolution
            findings={conflictingFindings}
            pending={resolutionMutation.isPending}
            error={
              resolutionMutation.error && !keepInspectionFinding
                ? errorMessage(resolutionMutation.error)
                : null
            }
            onAcceptRegistry={(finding) => {
              resolutionMutation.reset()
              resolutionMutation.mutate({
                reviewSession: activeReviewSession,
                finding,
                strategy: "ACCEPT_REGISTRY",
                reason: null,
              })
            }}
            onKeepInspection={(finding) => {
              resolutionMutation.reset()
              setKeepInspectionFinding({
                finding,
                reviewSession: activeReviewSession,
              })
              setKeepInspectionReason("")
              setKeepInspectionSubmitted(false)
            }}
            onOpenFinding={(finding) =>
              navigate(
                `/inventory/${activeReviewSession.id}?findingId=${finding.id}`,
                workspaceEntryNavigationOptions
              )
            }
          />
          <InventoryFindingsList
            findings={activeReviewSession.findings}
            canInspect={false}
            statusMode="COMPLETION"
            onOpen={(finding) =>
              navigate(
                `/inventory/${activeReviewSession.id}?findingId=${finding.id}`,
                workspaceEntryNavigationOptions
              )
            }
          />
        </>
      ) : (
        <>
          <Card>
            <CardHeader>
              <CardTitle>Проверка мебели</CardTitle>
              <CardDescription>
                Сначала сохраните полную сверку мебели. После подтверждения
                станут доступны завершающие действия.
              </CardDescription>
            </CardHeader>
          </Card>
          <InventoryFurnitureReview
            review={furnitureReview!}
            pending={saveFurnitureReviewMutation.isPending}
            error={
              saveFurnitureReviewMutation.error
                ? errorMessage(saveFurnitureReviewMutation.error)
                : null
            }
            onSave={(review) => saveFurnitureReviewMutation.mutate(review)}
            onDraftChange={setFurnitureReviewDirty}
          />
          {furnitureReview!.confirmed && !furnitureReviewDirty ? (
            <>
              {!furnitureReviewMatchesSession ? (
                <InventoryLoading>
                  Обновляем версию инвентаризации...
                </InventoryLoading>
              ) : completionPreviewQuery.isLoading ? (
                <InventoryLoading>
                  Проверяем актуальное состояние реестра...
                </InventoryLoading>
              ) : null}
              {completionPreviewQuery.error ? (
                <p role="alert" className="text-sm text-destructive">
                  {errorMessage(completionPreviewQuery.error)}
                </p>
              ) : null}
            </>
          ) : null}
          <InventoryConflictResolution
            findings={conflictingFindings}
            pending={resolutionMutation.isPending}
            error={
              resolutionMutation.error && !keepInspectionFinding
                ? errorMessage(resolutionMutation.error)
                : null
            }
            onAcceptRegistry={(finding) => {
              resolutionMutation.reset()
              resolutionMutation.mutate({
                reviewSession: activeReviewSession,
                finding,
                strategy: "ACCEPT_REGISTRY",
                reason: null,
              })
            }}
            onKeepInspection={(finding) => {
              resolutionMutation.reset()
              setKeepInspectionFinding({
                finding,
                reviewSession: activeReviewSession,
              })
              setKeepInspectionReason("")
              setKeepInspectionSubmitted(false)
            }}
            onOpenFinding={(finding) =>
              navigate(
                `/inventory/${activeReviewSession.id}?findingId=${finding.id}`,
                workspaceEntryNavigationOptions
              )
            }
          />
          {furnitureReview!.confirmed &&
          !furnitureReviewDirty &&
          furnitureReviewMatchesSession &&
          reviewedFurnitureSession &&
          !completionPreviewQuery.error ? (
            <Card>
              <CardHeader>
                <CardTitle>Завершение инвентаризации</CardTitle>
                <CardDescription>
                  Сверка мебели подтверждена. Завершение зафиксирует
                  неизменяемый снимок результатов.
                </CardDescription>
              </CardHeader>
              <CardContent className="flex flex-col gap-3">
                {unresolvedConflicts ? (
                  <p role="alert" className="text-sm text-destructive">
                    Урегулируйте все конфликты реестра перед завершением
                    инвентаризации.
                  </p>
                ) : null}
                {completionMutation.error ? (
                  <p role="alert" className="text-sm text-destructive">
                    {errorMessage(completionMutation.error)}
                  </p>
                ) : null}
                <div className="flex flex-wrap gap-2">
                  {withWork.length > 0 ? (
                    <>
                      <Button
                        type="button"
                        disabled={
                          completionMutation.isPending ||
                          completionPreviewQuery.isFetching ||
                          unresolvedConflicts
                        }
                        onClick={() => completionMutation.mutate(true)}
                      >
                        <HugeiconsIcon
                          icon={SentIcon}
                          data-icon="inline-start"
                        />
                        Завершить и передать работы
                      </Button>
                      <Button
                        type="button"
                        variant="outline"
                        disabled={
                          completionMutation.isPending ||
                          completionPreviewQuery.isFetching ||
                          unresolvedConflicts
                        }
                        onClick={() => completionMutation.mutate(false)}
                      >
                        Завершить без передачи
                      </Button>
                    </>
                  ) : (
                    <Button
                      type="button"
                      disabled={
                        completionMutation.isPending ||
                        completionPreviewQuery.isFetching ||
                        unresolvedConflicts
                      }
                      onClick={() => completionMutation.mutate(false)}
                    >
                      Завершить
                    </Button>
                  )}
                </div>
              </CardContent>
            </Card>
          ) : null}
        </>
      )}
      <Dialog
        open={keepInspectionFinding !== null}
        onOpenChange={(open) => {
          if (open) return
          setKeepInspectionFinding(null)
          setKeepInspectionReason("")
          setKeepInspectionSubmitted(false)
          resolutionMutation.reset()
        }}
      >
        <DialogContent>
          <DialogHeader>
            <DialogTitle>Применить данные инвентаризации</DialogTitle>
            <DialogDescription>
              Укажите причину, по которой наблюдения инвентаризации должны быть
              применены вместо актуальных данных реестра. Работы и материалы
              этого осмотра будут сохранены для передачи.
            </DialogDescription>
          </DialogHeader>
          <Field
            data-invalid={
              keepInspectionSubmitted && !keepInspectionReason.trim()
            }
          >
            <FieldLabel htmlFor="inventory-conflict-reason">Причина</FieldLabel>
            <Textarea
              id="inventory-conflict-reason"
              value={keepInspectionReason}
              maxLength={2000}
              aria-invalid={
                keepInspectionSubmitted && !keepInspectionReason.trim()
              }
              onChange={(event) => setKeepInspectionReason(event.target.value)}
            />
            {keepInspectionSubmitted && !keepInspectionReason.trim() ? (
              <FieldError>Введите причину решения</FieldError>
            ) : null}
          </Field>
          {resolutionMutation.error ? (
            <p role="alert" className="text-sm text-destructive">
              {errorMessage(resolutionMutation.error)}
            </p>
          ) : null}
          <DialogFooter>
            <Button
              type="button"
              variant="outline"
              disabled={resolutionMutation.isPending}
              onClick={() => setKeepInspectionFinding(null)}
            >
              Отмена
            </Button>
            <Button
              type="button"
              disabled={resolutionMutation.isPending}
              onClick={() => {
                setKeepInspectionSubmitted(true)
                const reason = keepInspectionReason.trim()
                if (!keepInspectionFinding || !reason) return
                resolutionMutation.mutate({
                  reviewSession: keepInspectionFinding.reviewSession,
                  finding: keepInspectionFinding.finding,
                  strategy: "KEEP_INSPECTION",
                  reason,
                })
              }}
            >
              Сохранить решение
            </Button>
          </DialogFooter>
        </DialogContent>
      </Dialog>
    </div>
  )
}

export function InventoryHistoryPage() {
  useInventorySync()
  const navigate = useNavigate()
  const { currentUser } = useAuth()
  const { selectedWarehouse } = useWarehouse()
  const canView = selectedWarehouse
    ? hasInventoryWarehouseAccess(currentUser, selectedWarehouse.id, "VIEW")
    : false
  const query = useQuery({
    queryKey: inventoryListQueryKey(selectedWarehouse?.id ?? "none"),
    queryFn: () => listInventories(selectedWarehouse!.id),
    enabled: Boolean(selectedWarehouse && canView),
  })
  if (!canView)
    return (
      <InventoryUnavailable
        title="История недоступна"
        description="Нет прав VIEW на выбранном складе."
      />
    )
  if (query.isLoading)
    return <InventoryLoading>Загрузка истории...</InventoryLoading>
  if (query.error)
    return (
      <InventoryUnavailable
        title="История не загружена"
        description={errorMessage(query.error)}
      />
    )
  const sessions = query.data ?? []
  return (
    <div className="flex h-full min-h-0 flex-col overflow-hidden">
      <div className="min-h-0 flex-1 overflow-y-auto md:flex">
        {sessions.length > 0 ? (
          <InventorySessionList
            sessions={sessions}
            onOpen={(session) =>
              navigate(
                session.status === "ACTIVE"
                  ? `/inventory/${session.id}`
                  : `/inventory/history/${session.id}`,
                workspaceEntryNavigationOptions
              )
            }
          />
        ) : (
          <InventoryEmptyState>Инвентаризаций пока нет.</InventoryEmptyState>
        )}
      </div>
    </div>
  )
}

export function InventoryHistoryDetailPage() {
  const navigate = useNavigate()
  const queryClient = useQueryClient()
  const [searchParams] = useSearchParams()
  const { currentUser } = useAuth()
  const { inventoryId, actor, query, session } = useInventoryDetailRoute()
  const findingId = searchParams.get("findingId")
  const selectedFinding =
    session?.findings.find((item) => item.id === findingId) ?? null
  const goBack = useWorkspaceBack(`/inventory/history/${inventoryId}`)
  const publishMutation = useMutation({
    mutationFn: () => {
      if (!session || !actor) throw new Error("Инвентаризация недоступна")
      return publishInventoryWorks({ inventoryId: session.id, actor })
    },
    onSuccess: (published) => {
      void queryClient.invalidateQueries({ queryKey: INVENTORY_QUERY_KEY })
      showPublicationNotice(published)
    },
  })
  if (query.isLoading) return <InventoryLoading />
  if (query.error)
    return (
      <InventoryUnavailable
        title="Результат не загружен"
        description={errorMessage(query.error)}
      />
    )
  if (!session || session.status !== "COMPLETED")
    return (
      <InventoryUnavailable
        title="Результат недоступен"
        description="Завершённая инвентаризация не найдена на выбранном складе."
      />
    )
  if (selectedFinding)
    return (
      <FindingEditor
        key={selectedFinding.id}
        session={session}
        finding={selectedFinding}
        readOnly
        onBack={goBack}
      />
    )
  const canPublish = hasInventoryWarehouseAccess(
    currentUser,
    session.warehouseId,
    "MANAGE"
  )
  const unpublished = session.findings.some(
    (item) => item.lines.length > 0 && item.publicationStatus !== "PUBLISHED"
  )
  const furnitureReconciliation = inventoryFurnitureReconciliationPresentation(
    session.furnitureReconciliationState
  )
  return (
    <div className="flex h-full min-h-0 flex-col gap-4 overflow-y-auto">
      <PageToolbar>
        <Button
          type="button"
          variant="outline"
          onClick={() => navigate("/inventory/history")}
        >
          <HugeiconsIcon icon={ArrowLeft01Icon} data-icon="inline-start" />
          Назад
        </Button>
        {unpublished ? (
          <PageToolbarActions>
            <Button
              type="button"
              disabled={!canPublish || publishMutation.isPending}
              onClick={() => publishMutation.mutate()}
            >
              <HugeiconsIcon icon={SentIcon} data-icon="inline-start" />
              Передать в ремонты
            </Button>
          </PageToolbarActions>
        ) : null}
      </PageToolbar>
      {publishMutation.error ? (
        <p role="alert" className="text-sm text-destructive">
          {errorMessage(publishMutation.error)}
        </p>
      ) : null}
      {session.statistics ? (
        <section className="flex flex-col gap-3" aria-labelledby="frozen-title">
          <h2 id="frozen-title" className="text-lg font-semibold">
            Зафиксированные итоги
          </h2>
          <InventoryStatistics
            statistics={session.statistics}
            findings={session.findings}
          />
        </section>
      ) : null}
      <InventoryMembershipMovements
        movements={session.membershipMovements}
        warehouse={session.warehouse}
      />
      <div className="min-h-[20rem] md:flex">
        <InventoryFindingsList
          findings={session.findings}
          canInspect={false}
          showPublication
          statusMode="COMPLETION"
          onOpen={(finding) =>
            navigate(
              `/inventory/history/${session.id}?findingId=${finding.id}`,
              workspaceEntryNavigationOptions
            )
          }
        />
      </div>
      <Card size="sm">
        <CardHeader>
          <CardTitle>Статус передачи</CardTitle>
        </CardHeader>
        <CardContent>
          <Badge variant="secondary">
            {inventoryPublicationLabel(session)}
          </Badge>
        </CardContent>
      </Card>
      <Card
        size="sm"
        className={
          furnitureReconciliation.problem ? "border-destructive" : undefined
        }
      >
        <CardHeader>
          <CardTitle>Статус сверки мебели</CardTitle>
        </CardHeader>
        <CardContent className="flex flex-col items-start gap-2">
          <Badge variant={furnitureReconciliation.badgeVariant}>
            {furnitureReconciliation.label}
          </Badge>
          {furnitureReconciliation.problem ? (
            <p role="alert" className="text-sm text-destructive">
              {furnitureReconciliation.problem}
            </p>
          ) : null}
        </CardContent>
      </Card>
    </div>
  )
}
