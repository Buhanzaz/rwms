import { useEffect, useRef, useState } from "react"
import {
  useIsMutating,
  useMutation,
  useQuery,
  useQueryClient,
} from "@tanstack/react-query"
import { HugeiconsIcon } from "@hugeicons/react"
import {
  Add01Icon,
  ArrowLeft01Icon,
  CheckmarkCircle02Icon,
  ClipboardCheckIcon,
  FilterIcon,
  Loading03Icon,
  RefreshIcon,
  SentIcon,
} from "@hugeicons/core-free-icons"
import { useNavigate, useParams, useSearchParams } from "react-router-dom"
import { toast } from "sonner"

import { MobileAppRequiredDialog } from "@/components/mobile-app-required-dialog"
import { PageToolbar, PageToolbarActions } from "@/components/page-toolbar"
import { Badge } from "@/components/ui/badge"
import { Button } from "@/components/ui/button"
import { Alert, AlertDescription, AlertTitle } from "@/components/ui/alert"
import {
  AlertDialog,
  AlertDialogAction,
  AlertDialogCancel,
  AlertDialogContent,
  AlertDialogDescription,
  AlertDialogFooter,
  AlertDialogHeader,
  AlertDialogTitle,
  AlertDialogTrigger,
} from "@/components/ui/alert-dialog"
import {
  Card,
  CardContent,
  CardDescription,
  CardFooter,
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
  cancelInventory,
  completeInventory,
  getInventoryFinalPlan,
  getInventoryFurnitureReview,
  getInventoryPlanningSettings,
  getInventoryPreliminaryStatistics,
  getActiveInventory,
  getInventory,
  inventoryActiveQueryKey,
  inventoryDetailQueryKey,
  inventoryFinishPreviewQueryKey,
  inventoryFinalPlanQueryKey,
  inventoryFurnitureReviewQueryKey,
  inventoryListQueryKey,
  inventoryPreliminaryStatisticsQueryKey,
  inventoryPlanningSettingsQueryKey,
  inventoryPublicationQueryKey,
  listInventories,
  previewInventoryCompletion,
  prepareInventoryFinalPlan,
  recalculateInventoryOutcome,
  refreshInventorySession,
  reviewInventoryRegistry,
  resolveInventoryFindingConflict,
  saveInventoryFurnitureReview,
  saveInventoryFinalPlan,
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
import { InventoryFurnitureObservationDialog } from "@/features/inventory/inventory-inspection-details"
import { InventoryFurnitureReview } from "@/features/inventory/inventory-furniture-review"
import { InventoryFinalPlanEditor } from "@/features/inventory/inventory-final-plan"
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
  toInventoryRepairPlanSnapshot,
} from "@/features/inventory/domain/inventory-domain"
import { applyInventoryOutcomeRecalculation } from "@/features/inventory/domain/inventory-view-mapper"
import { formatMoneyDecimal } from "@/features/repair-estimates/domain/repair-estimate-domain"
import type {
  InventoryFindingDto,
  InventoryFurnitureReviewDto,
  InventorySessionDto,
} from "@/features/inventory/model/inventory"
import type { InventoryObservation } from "@/features/inventory/model/inventory-service"
import {
  getRentalItemCreationOptions,
  rentalItemCreationOptionsQueryKey,
} from "@/features/rental-items/api/asset-rental-items-api"
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

function isInventoryDataChangedError(error: unknown) {
  return error instanceof ApiError && error.status === 409
}

function errorMessage(error: unknown) {
  if (isInventoryDataChangedError(error)) {
    return "Данные инвентаризации изменились. Обновите страницу и повторите действие."
  }
  return error instanceof Error ? error.message : "Операция не выполнена"
}

function outcomeRecalculationErrorMessage(error: unknown) {
  return `${errorMessage(error)} Данные, фотографии и история сохранены. Обновите страницу и повторите пересчёт.`
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

function formatInventoryDateTime(value: string) {
  return new Intl.DateTimeFormat("ru-RU", {
    dateStyle: "medium",
    timeStyle: "short",
  }).format(new Date(value))
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
    <section className="flex flex-col gap-3" aria-labelledby="conflicts-title">
      <h2 id="conflicts-title" className="text-lg font-semibold" tabIndex={-1}>
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
                «Сохранить актуальные данные реестра» — работы и материалы этого
                осмотра не будут переданы.
              </p>
              <p>
                «Применить данные инвентаризации» — сохраняет работы и материалы
                этого осмотра; потребуется указать причину.
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
  const passportOptionsQuery = useQuery({
    queryKey: rentalItemCreationOptionsQueryKey(session.warehouseId),
    queryFn: () =>
      getRentalItemCreationOptions(accessToken, session.warehouseId),
    enabled: Boolean(accessToken && session.warehouseId),
  })
  const [baseFindingVersion] = useState(finding.version)
  const [baseLinesJson] = useState(() => JSON.stringify(finding.lines))
  const [comment, setComment] = useState(finding.comment)
  const [passportObservation, setPassportObservation] = useState(
    finding.passportObservation
  )
  const [equipmentObservation, setEquipmentObservation] = useState(
    finding.equipmentObservation
  )
  const [lines, setLines] = useState<RepairEstimateLineDto[]>(finding.lines)
  const [media, setMedia] = useState<ReadyMediaReference[]>(finding.media)
  const [coverMediaId, setCoverMediaId] = useState<string | null>(
    finding.coverMediaId
  )
  const [mediaReady, setMediaReady] = useState(false)
  const [completionOpen, setCompletionOpen] = useState(false)
  const [completionEquipmentObservation, setCompletionEquipmentObservation] =
    useState<InventoryObservation | null>(null)
  const [furnitureDialog, setFurnitureDialog] = useState<{
    step: "decision" | "items"
    saveAfterResolve: boolean
  } | null>(null)
  const movementCapabilitiesQuery = useQuery({
    queryKey: warehouseQueueCapabilitiesQueryKey(session.warehouseId),
    queryFn: () =>
      getWarehouseQueueCapabilities(accessToken!, session.warehouseId),
    enabled: !readOnly && Boolean(accessToken && session.warehouseId),
  })
  const movementRouteAvailable =
    (movementCapabilitiesQuery.data?.movementQueueDefinitions.length ?? 0) > 0
  const mutation = useMutation({
    mutationFn: ({
      completion,
      equipmentObservation: equipmentObservationForSave,
    }: {
      completion: RepairWorkCompletionResult | null
      equipmentObservation: InventoryObservation
    }) => {
      if (!actor) throw new Error("Нет доступа")
      return saveInventoryFinding({
        inventoryId: session.id,
        expectedFindingVersion: baseFindingVersion,
        actor,
        findingId: finding.id,
        comment,
        passportObservation,
        equipmentObservation: equipmentObservationForSave,
        media,
        coverMediaId,
        lines,
        repairPlans: completion
          ? completion.taskPlans
              .filter((plan) => plan.kind === "REPAIR_WORK")
              .map((plan) =>
                frozenPlanUnchanged
                  ? (finding.repairPlans.find(
                      (savedPlan) => savedPlan.id === plan.id
                    ) ?? toInventoryRepairPlanSnapshot(plan))
                  : toInventoryRepairPlanSnapshot(plan)
              )
          : finding.repairPlans,
        repairCompletionMode: completion?.completionMode ?? null,
        movementToRepair:
          movementRouteAvailable && completion?.movementToRepair === true,
        forceCapitalRepair: completion?.forceCapitalRepair === true,
        logisticsPlanningMode:
          movementRouteAvailable && completion?.movementToRepair === true
            ? completion.logisticsPlanningMode
            : undefined,
        logisticsScheduledDate:
          movementRouteAvailable && completion?.movementToRepair === true
            ? completion.logisticsScheduledDate
            : null,
        priority: completion?.priority ?? finding.repairPriority,
      })
    },
    onSuccess: () => {
      setCompletionOpen(false)
      setCompletionEquipmentObservation(null)
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
  const inspectionSnapshot = finding.inspectionBaseline ?? snapshot
  const acceptsAfterRentWithoutEstimate =
    snapshot?.status === "AFTER_RENT" && lines.length === 0
  const missingRequiredAcceptancePhoto =
    acceptsAfterRentWithoutEstimate && media.length === 0
  const missingCoverPhoto = media.length > 0 && coverMediaId === null
  const frozenPlanUnchanged = JSON.stringify(lines) === baseLinesJson
  const passportOptionsError = passportOptionsQuery.error
    ? passportOptionsQuery.error instanceof Error
      ? passportOptionsQuery.error.message
      : "Не удалось загрузить настройки бытовок."
    : null

  function beginSave(nextEquipmentObservation: InventoryObservation) {
    if (lines.length > 0) {
      setCompletionEquipmentObservation(nextEquipmentObservation)
      setCompletionOpen(true)
      return
    }
    mutation.mutate({
      completion: null,
      equipmentObservation: nextEquipmentObservation,
    })
  }

  function requestSave() {
    if (equipmentObservation.presence === "ABSENT") {
      setFurnitureDialog({ step: "decision", saveAfterResolve: true })
      return
    }
    beginSave(equipmentObservation)
  }

  function editFurniture() {
    setFurnitureDialog({
      step: equipmentObservation.presence === "ABSENT" ? "decision" : "items",
      saveAfterResolve: false,
    })
  }

  function resolveFurniture(nextEquipmentObservation: InventoryObservation) {
    const saveAfterResolve = furnitureDialog?.saveAfterResolve === true
    setEquipmentObservation(nextEquipmentObservation)
    setFurnitureDialog(null)
    if (saveAfterResolve) beginSave(nextEquipmentObservation)
  }

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
              onClick={requestSave}
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
        passportObservation={passportObservation}
        passportSnapshot={inspectionSnapshot?.passportSnapshot ?? null}
        passportOptions={passportOptionsQuery.data ?? null}
        passportOptionsLoading={passportOptionsQuery.isLoading}
        passportOptionsError={passportOptionsError}
        equipmentObservation={equipmentObservation}
        lines={lines}
        media={media}
        repairCompletionMode={finding.repairCompletionMode}
        movementToRepair={finding.movementToRepair}
        repairPlans={finding.repairPlans}
        readOnly={readOnly}
        coverMediaId={coverMediaId}
        message={
          finding.conflicts.map((conflict) => conflict.message).join("; ") ||
          null
        }
        onCommentChange={setComment}
        onPassportObservationChange={setPassportObservation}
        onEditFurniture={editFurniture}
        onRetryPassportOptions={() => {
          void passportOptionsQuery.refetch()
        }}
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
      {furnitureDialog ? (
        <InventoryFurnitureObservationDialog
          open
          step={furnitureDialog.step}
          accessToken={accessToken}
          warehouseId={session.warehouseId}
          observation={equipmentObservation}
          contentsSnapshot={inspectionSnapshot?.contentsSnapshot ?? []}
          onOpenChange={(open) => {
            if (!open) setFurnitureDialog(null)
          }}
          onRequestItems={() =>
            setFurnitureDialog((current) =>
              current ? { ...current, step: "items" } : current
            )
          }
          onResolved={resolveFurniture}
        />
      ) : null}
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
        description="Выберите приоритет и параметры перемещения. Настройка будет сохранена в снимке инвентаризации и передана в ремонт только после её завершения."
        completeLabel="Сохранить осмотр"
        pendingLabel="Сохраняем..."
        previewKey={`inventory:${session.id}:${finding.id}:${session.version}`}
        initialMovementToRepair={finding.movementToRepair}
        initialForceCapitalRepair={finding.forceCapitalRepair}
        initialLogisticsPlanningMode={finding.logisticsPlanningMode}
        initialLogisticsScheduledDate={finding.logisticsScheduledDate}
        initialPriority={finding.repairPriority}
        initialCompletionMode={
          frozenPlanUnchanged
            ? (finding.repairCompletionMode ?? "MANUAL")
            : undefined
        }
        initialTaskPlans={
          frozenPlanUnchanged
            ? finding.repairPlans.map((plan) => ({
                ...plan,
                generationStatus: "PENDING_GENERATION" as const,
                workflowRequestRef: null,
              }))
            : undefined
        }
        movementRouteAvailable={movementRouteAvailable}
        showForceCapitalRepair
        onOpenChange={(open) => {
          setCompletionOpen(open)
          if (!open) setCompletionEquipmentObservation(null)
        }}
        onComplete={(completion) =>
          mutation.mutate({
            completion,
            equipmentObservation:
              completionEquipmentObservation ?? equipmentObservation,
          })
        }
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

function inventoryRegistryReviewQueryKey(session: InventorySessionDto | null) {
  const revisionVector = session
    ? [...session.findings]
        .sort((left, right) => left.id.localeCompare(right.id))
        .map((finding) => [finding.id, finding.version] as const)
    : []
  return [
    ...INVENTORY_QUERY_KEY,
    "registry-review",
    session?.id ?? null,
    session?.version ?? null,
    revisionVector,
  ] as const
}

export function InventorySessionPage() {
  const isMobile = useIsMobile()
  const navigate = useNavigate()
  const queryClient = useQueryClient()
  const [searchParams] = useSearchParams()
  const { currentUser } = useAuth()
  const { inventoryId, actor, selectedWarehouse, query, session } =
    useInventoryDetailRoute()
  const [filters, setFilters] = useState(createEmptyInventoryFindingFilters)
  const { filtersOpen, setFiltersOpen } = useResponsiveFiltersOpen()
  const [addOpen, setAddOpen] = useState(false)
  const [cancelOpen, setCancelOpen] = useState(false)
  const [cancelReason, setCancelReason] = useState("")
  const [cancelSubmitted, setCancelSubmitted] = useState(false)
  const [mobileAppOperation, setMobileAppOperation] = useState<
    "Осмотр бытовки" | null
  >(null)
  const findingId = searchParams.get("findingId")
  const goBack = useWorkspaceBack(`/inventory/${inventoryId}`)
  const selectedFinding =
    session?.findings.find((item) => item.id === findingId) ?? null
  const cancelMutation = useMutation({
    mutationFn: () => {
      if (!session) throw new Error("Инвентаризация недоступна")
      return cancelInventory({
        inventoryId: session.id,
        expectedVersion: session.version,
        reason: cancelReason,
      })
    },
    onSuccess: (cancelled) => {
      setCancelOpen(false)
      void queryClient.invalidateQueries({ queryKey: INVENTORY_QUERY_KEY })
      toast.success("Инвентаризация отменена. Данные сохранены в истории.")
      navigate(`/inventory/history/${cancelled.id}`, { replace: true })
    },
    onError: () => {
      void queryClient.invalidateQueries({ queryKey: INVENTORY_QUERY_KEY })
    },
  })

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
            variant="outline"
            disabled={!canManage}
            onClick={() => {
              cancelMutation.reset()
              setCancelReason("")
              setCancelSubmitted(false)
              setCancelOpen(true)
            }}
          >
            Отменить
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
      <Dialog
        open={cancelOpen}
        onOpenChange={(open) => {
          if (!cancelMutation.isPending) setCancelOpen(open)
        }}
      >
        <DialogContent>
          <DialogHeader>
            <DialogTitle>Отменить инвентаризацию?</DialogTitle>
            <DialogDescription>
              Сессия станет недоступна для изменений. Осмотры и журнал движения
              сохранятся в истории, а задания и сметы созданы не будут.
            </DialogDescription>
          </DialogHeader>
          <Field data-invalid={cancelSubmitted && !cancelReason.trim()}>
            <FieldLabel htmlFor="inventory-cancel-reason">
              Причина отмены
            </FieldLabel>
            <Textarea
              id="inventory-cancel-reason"
              value={cancelReason}
              maxLength={2000}
              aria-invalid={cancelSubmitted && !cancelReason.trim()}
              onChange={(event) => setCancelReason(event.target.value)}
            />
            {cancelSubmitted && !cancelReason.trim() ? (
              <FieldError>Укажите причину отмены.</FieldError>
            ) : null}
          </Field>
          {cancelMutation.error ? (
            <p role="alert" className="text-sm text-destructive">
              {errorMessage(cancelMutation.error)}
            </p>
          ) : null}
          <DialogFooter>
            <Button
              type="button"
              variant="outline"
              disabled={cancelMutation.isPending}
              onClick={() => setCancelOpen(false)}
            >
              Вернуться
            </Button>
            <Button
              type="button"
              variant="destructive"
              disabled={cancelMutation.isPending}
              onClick={() => {
                setCancelSubmitted(true)
                if (!cancelReason.trim()) return
                cancelMutation.mutate()
              }}
            >
              {cancelMutation.isPending
                ? "Отменяем..."
                : "Отменить инвентаризацию"}
            </Button>
          </DialogFooter>
        </DialogContent>
      </Dialog>
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
  const [finalPlanDirty, setFinalPlanDirty] = useState(false)
  const [dataChangedDialogOpen, setDataChangedDialogOpen] = useState(false)
  const [registryReviewEnabled, setRegistryReviewEnabled] = useState(false)
  const conflictNavigationRequested = useRef(false)
  const sessionHasConflicts = Boolean(
    session?.findings.some((finding) => finding.conflicts.length > 0)
  )

  useEffect(() => {
    if (
      dataChangedDialogOpen ||
      !conflictNavigationRequested.current ||
      !sessionHasConflicts
    ) {
      return
    }
    const target = document.getElementById("conflicts-title")
    if (!target) return
    target.focus()
    target.scrollIntoView?.({ behavior: "smooth", block: "start" })
    conflictNavigationRequested.current = false
  }, [dataChangedDialogOpen, sessionHasConflicts])
  const canManage = Boolean(
    session &&
    hasInventoryWarehouseAccess(currentUser, session.warehouseId, "MANAGE")
  )
  const registryReviewQuery = useQuery({
    queryKey: inventoryRegistryReviewQueryKey(session),
    queryFn: () =>
      session
        ? reviewInventoryRegistry(session.id)
        : Promise.resolve<InventorySessionDto | null>(null),
    enabled: Boolean(
      registryReviewEnabled &&
      session &&
      actor &&
      canManage &&
      session.status === "ACTIVE" &&
      session.reviewStage === "CABINS"
    ),
    staleTime: Number.POSITIVE_INFINITY,
    refetchOnWindowFocus: false,
  })
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
  const preliminaryStatisticsQuery = useQuery({
    queryKey: inventoryPreliminaryStatisticsQueryKey(
      session?.id ?? null,
      session?.version ?? null
    ),
    queryFn: () =>
      session
        ? getInventoryPreliminaryStatistics(session.id)
        : Promise.resolve(null),
    enabled: Boolean(
      session &&
      actor &&
      canManage &&
      session.status === "ACTIVE" &&
      session.reviewStage === "CABINS"
    ),
    refetchOnWindowFocus: "always",
  })
  const furnitureReviewMatchesSession =
    furnitureReviewQuery.data?.sessionRevision === session?.version
  const planningSettingsQueryKey = inventoryPlanningSettingsQueryKey(
    session?.warehouseId ?? null
  )
  const planningSettingsQuery = useQuery({
    queryKey: planningSettingsQueryKey,
    queryFn: () =>
      session
        ? getInventoryPlanningSettings(session.warehouseId)
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
  })
  const finalPlanQueryKey = inventoryFinalPlanQueryKey(
    session?.id ?? null,
    session?.version ?? null
  )
  const finalPlanQuery = useQuery({
    queryKey: finalPlanQueryKey,
    queryFn: () =>
      session ? getInventoryFinalPlan(session.id) : Promise.resolve(null),
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
  const finalPlan = finalPlanQuery.data ?? null
  const draftFinalPlan = finalPlan?.state === "DRAFT" ? finalPlan : null
  const completionPreviewQueryKey = inventoryFinishPreviewQueryKey(
    session?.id ?? null,
    session?.version ?? null,
    draftFinalPlan?.finalPlanVersion ?? null
  )
  const completionPreviewQuery = useQuery({
    queryKey: completionPreviewQueryKey,
    queryFn: () =>
      session && actor && draftFinalPlan
        ? previewInventoryCompletion({
            inventoryId: session.id,
            expectedVersion: session.version,
            actor,
            finalPlan: draftFinalPlan,
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
      !furnitureReviewDirty &&
      draftFinalPlan &&
      !finalPlanDirty
    ),
    refetchOnWindowFocus: false,
  })
  const reviewedFurnitureSession = finalPlanDirty
    ? null
    : completionPreviewQuery.data
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
      setFinalPlanDirty(false)
      void queryClient.removeQueries({
        queryKey: [...INVENTORY_QUERY_KEY, "finish-preview", updated.id],
      })
      void queryClient.removeQueries({
        queryKey: [...INVENTORY_QUERY_KEY, "final-plan", updated.id],
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
      setFinalPlanDirty(false)
      void queryClient.invalidateQueries({
        queryKey: inventoryDetailQueryKey(review.inventoryId),
      })
      toast.success("Проверка бытовок завершена. Перейдите к сверке мебели.")
    },
    onError: (error) => {
      if (isInventoryDataChangedError(error)) {
        setDataChangedDialogOpen(true)
      }
      void queryClient.invalidateQueries({ queryKey: INVENTORY_QUERY_KEY })
    },
  })
  const registryReviewMutation = useMutation({
    mutationFn: () => {
      if (!session) throw new Error("Инвентаризация недоступна")
      return reviewInventoryRegistry(session.id)
    },
    onSuccess: (reviewed) => {
      const hasConflicts = reviewed.findings.some(
        (finding) => finding.conflicts.length > 0
      )
      conflictNavigationRequested.current = hasConflicts
      setRegistryReviewEnabled(true)
      queryClient.setQueryData(
        inventoryRegistryReviewQueryKey(reviewed),
        reviewed
      )
      queryClient.setQueryData(inventoryDetailQueryKey(reviewed.id), reviewed)
      setAcknowledgedRiskSignature(null)
      setDataChangedDialogOpen(false)
      startFurnitureReviewMutation.reset()
      if (!hasConflicts) {
        toast.success(
          "Данные обновлены. Конфликтов больше нет — повторите переход к мебели."
        )
      }
    },
    onError: () => {
      void queryClient.invalidateQueries({ queryKey: INVENTORY_QUERY_KEY })
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
      queryClient.setQueryData(finalPlanQueryKey, null)
      setFurnitureReviewDirty(false)
      setFinalPlanDirty(false)
      void queryClient.removeQueries({
        queryKey: [
          ...INVENTORY_QUERY_KEY,
          "finish-preview",
          review.inventoryId,
        ],
      })
      void queryClient.invalidateQueries({ queryKey: INVENTORY_QUERY_KEY })
      toast.success("Сверка мебели сохранена")
    },
  })
  const prepareFinalPlanMutation = useMutation({
    mutationFn: () => {
      if (!session || !planningSettingsQuery.data) {
        throw new Error("Настройки итогового плана недоступны")
      }
      return prepareInventoryFinalPlan({
        inventoryId: session.id,
        expectedSessionRevision: session.version,
        expectedSettingsRevision: planningSettingsQuery.data.settingsRevision,
        movementScheduleMode: "AUTO",
        repairScheduleMode: "AUTO",
      })
    },
    onSuccess: (prepared) => {
      queryClient.setQueryData(finalPlanQueryKey, prepared)
      setFinalPlanDirty(false)
      void queryClient.removeQueries({
        queryKey: [
          ...INVENTORY_QUERY_KEY,
          "finish-preview",
          prepared.inventoryId,
        ],
      })
      toast.success("Итоговый план построен. Проверьте порядок и задания.")
    },
    onError: () => {
      void queryClient.invalidateQueries({ queryKey: INVENTORY_QUERY_KEY })
      void planningSettingsQuery.refetch()
    },
  })
  const saveFinalPlanMutation = useMutation({
    mutationFn: (
      request: Parameters<typeof saveInventoryFinalPlan>[0]["request"]
    ) => {
      if (!session) throw new Error("Инвентаризация недоступна")
      return saveInventoryFinalPlan({ inventoryId: session.id, request })
    },
    onSuccess: (saved) => {
      queryClient.setQueryData(finalPlanQueryKey, saved)
      setFinalPlanDirty(false)
      void queryClient.removeQueries({
        queryKey: [...INVENTORY_QUERY_KEY, "finish-preview", saved.inventoryId],
      })
      toast.success("Итоговый план сохранён и повторно проверен сервером.")
    },
    onError: () => {
      void queryClient.invalidateQueries({ queryKey: INVENTORY_QUERY_KEY })
    },
  })
  const completionMutation = useMutation({
    mutationFn: async () => {
      if (!reviewedFurnitureSession || !actor || !draftFinalPlan) {
        throw new Error("Инвентаризация недоступна")
      }
      if (
        reviewedFurnitureSession.findings.some(
          (finding) => finding.conflicts.length > 0
        )
      ) {
        throw new Error(
          "Урегулируйте все конфликты реестра перед завершением инвентаризации"
        )
      }
      if (
        reviewedFurnitureSession.completionEvidence.finalPlanVersion !==
          draftFinalPlan.finalPlanVersion ||
        reviewedFurnitureSession.completionEvidence.finalPlanSha256 !==
          draftFinalPlan.finalPlanSha256
      ) {
        throw new Error("Итоговый план изменился. Просмотрите его ещё раз.")
      }
      return completeInventory({
        inventoryId: reviewedFurnitureSession.id,
        expectedVersion: reviewedFurnitureSession.version,
        actor,
        completionEvidence: reviewedFurnitureSession.completionEvidence,
      })
    },
    onSuccess: (completed) => {
      queryClient.setQueryData(inventoryDetailQueryKey(completed.id), completed)
      queryClient.setQueryData<InventorySessionDto | null>(
        inventoryActiveQueryKey(completed.warehouseId),
        null
      )
      queryClient.setQueryData<InventorySessionDto[]>(
        inventoryListQueryKey(completed.warehouseId),
        (sessions) =>
          sessions?.map((session) =>
            session.id === completed.id ? completed : session
          )
      )
      navigate(`/inventory/history/${completed.id}`, { replace: true })
      void queryClient.invalidateQueries({
        queryKey: inventoryActiveQueryKey(completed.warehouseId),
        refetchType: "none",
      })
      void queryClient.invalidateQueries({
        queryKey: inventoryListQueryKey(completed.warehouseId),
        refetchType: "none",
      })
      showFurnitureReconciliationNotice(completed)
      if (
        completed.publicationStatus !== "NOT_REQUESTED" ||
        completed.findings.some((finding) => finding.lines.length > 0)
      ) {
        showPublicationNotice(completed)
      }
    },
    onError: () => {
      setFurnitureReviewDirty(false)
      setFinalPlanDirty(false)
      void queryClient.invalidateQueries({ queryKey: INVENTORY_QUERY_KEY })
    },
  })
  const refreshSessionMutation = useMutation({
    mutationFn: () => {
      if (!session) throw new Error("Инвентаризация недоступна")
      return refreshInventorySession({
        inventoryId: session.id,
        expectedSessionRevision: session.version,
      })
    },
    onSuccess: (updated) => {
      queryClient.removeQueries({
        queryKey: inventoryFurnitureReviewQueryKey(updated.id),
      })
      queryClient.removeQueries({
        queryKey: [...INVENTORY_QUERY_KEY, "final-plan", updated.id],
      })
      queryClient.removeQueries({
        queryKey: [...INVENTORY_QUERY_KEY, "finish-preview", updated.id],
      })
      queryClient.removeQueries({
        queryKey: [...INVENTORY_QUERY_KEY, "statistics-preview", updated.id],
      })
      queryClient.removeQueries({
        queryKey: [...INVENTORY_QUERY_KEY, "registry-review", updated.id],
      })
      queryClient.setQueryData(inventoryDetailQueryKey(updated.id), updated)
      setAcknowledgedRiskSignature(null)
      setKeepInspectionFinding(null)
      setKeepInspectionReason("")
      setKeepInspectionSubmitted(false)
      setFurnitureReviewDirty(false)
      setFinalPlanDirty(false)
      setDataChangedDialogOpen(false)
      setRegistryReviewEnabled(false)
      conflictNavigationRequested.current = false
      resolutionMutation.reset()
      startFurnitureReviewMutation.reset()
      registryReviewMutation.reset()
      saveFurnitureReviewMutation.reset()
      prepareFinalPlanMutation.reset()
      saveFinalPlanMutation.reset()
      completionMutation.reset()
      void queryClient.invalidateQueries({ queryKey: INVENTORY_QUERY_KEY })
      toast.success(
        "Изменения сессии пересчитаны. Сохранённые осмотры бытовок сохранены."
      )
    },
    onError: () => {
      if (!session) return
      void queryClient.invalidateQueries({
        queryKey: inventoryDetailQueryKey(session.id),
      })
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
  const refreshBlockedByUnsavedDraft = furnitureReviewDirty || finalPlanDirty
  const refreshBlockedByPendingOperation =
    refreshSessionMutation.isPending ||
    resolutionMutation.isPending ||
    startFurnitureReviewMutation.isPending ||
    registryReviewMutation.isPending ||
    saveFurnitureReviewMutation.isPending ||
    prepareFinalPlanMutation.isPending ||
    saveFinalPlanMutation.isPending ||
    completionMutation.isPending
  const refreshSessionCard = (
    <Card>
      <CardHeader>
        <CardTitle>Пересчитать данные инвентаризации</CardTitle>
        <CardDescription>
          Сохранённые осмотры бытовок останутся в базе. Сервер заново соберёт
          живой состав склада, после чего сверку мебели и итоговый план нужно
          будет построить заново по текущим данным.
        </CardDescription>
      </CardHeader>
      <CardFooter className="flex-col items-stretch gap-2 border-t">
        {refreshBlockedByUnsavedDraft ? (
          <p className="text-sm text-muted-foreground">
            Сначала сохраните или отмените несохранённые изменения сверки мебели
            или итогового плана.
          </p>
        ) : null}
        {refreshSessionMutation.error ? (
          <p role="alert" className="text-sm text-destructive">
            Не удалось пересчитать изменения сессии:{" "}
            {errorMessage(refreshSessionMutation.error)}
          </p>
        ) : null}
        <Button
          type="button"
          variant="outline"
          disabled={
            refreshBlockedByPendingOperation || refreshBlockedByUnsavedDraft
          }
          onClick={() => {
            refreshSessionMutation.reset()
            refreshSessionMutation.mutate()
          }}
        >
          {refreshSessionMutation.isPending
            ? "Пересчитываем изменения сессии..."
            : "Пересчитать изменения сессии"}
        </Button>
      </CardFooter>
    </Card>
  )
  if (
    session.reviewStage === "FURNITURE" &&
    (furnitureReviewQuery.error || !furnitureReviewQuery.data)
  )
    return (
      <div className="flex h-full min-h-0 flex-col gap-4 overflow-y-auto">
        {refreshSessionCard}
        <InventoryUnavailable
          title="Сверка мебели недоступна"
          description={
            furnitureReviewQuery.error
              ? errorMessage(furnitureReviewQuery.error)
              : "Не удалось получить снимок мебели для этой инвентаризации."
          }
        />
      </div>
    )
  const furnitureReview = furnitureReviewQuery.data
  const cabinReviewSession =
    session.reviewStage === "CABINS" && registryReviewQuery.data
      ? registryReviewQuery.data
      : session
  const activeReviewSession =
    session.reviewStage === "FURNITURE" &&
    furnitureReview?.confirmed &&
    furnitureReviewMatchesSession &&
    !furnitureReviewDirty &&
    reviewedFurnitureSession
      ? reviewedFurnitureSession
      : cabinReviewSession
  const displayedStatistics =
    activeReviewSession.statistics ?? preliminaryStatisticsQuery.data ?? null
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
      displayedStatistics?.expectedCount ?? activeReviewSession.findings.length,
    ],
    [
      "Проверено",
      displayedStatistics?.inspectedCount ??
        activeReviewSession.findings.length - notInspected,
    ],
    ["Не найдено", displayedStatistics?.missingCount ?? missing],
    ["Не проверено", notInspected],
    ["Готовы", displayedStatistics?.readyCount ?? 0],
    ["С работами", displayedStatistics?.withWorkCount ?? withWork.length],
    ["Добавлено", displayedStatistics?.addedCount ?? 0],
    [
      "Конфликты",
      displayedStatistics?.conflictCount ?? conflictingFindings.length,
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
      {refreshSessionCard}
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
                {displayedStatistics ? (
                  <Badge>
                    Итого: {formatMoneyDecimal(displayedStatistics.grandTotal)}{" "}
                    ₽
                  </Badge>
                ) : null}
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
              {startFurnitureReviewMutation.error &&
              !isInventoryDataChangedError(
                startFurnitureReviewMutation.error
              ) ? (
                <p role="alert" className="text-sm text-destructive">
                  {errorMessage(startFurnitureReviewMutation.error)}
                </p>
              ) : null}
              <Button
                type="button"
                disabled={
                  startFurnitureReviewMutation.isPending ||
                  (confirmationRequired && !confirmed)
                }
                onClick={() => {
                  startFurnitureReviewMutation.reset()
                  if (unresolvedConflicts) {
                    setDataChangedDialogOpen(true)
                    return
                  }
                  startFurnitureReviewMutation.mutate(confirmationRequired)
                }}
              >
                Завершить проверку бытовок и перейти к мебели
              </Button>
            </CardContent>
          </Card>
          {preliminaryStatisticsQuery.error ? (
            <p role="alert" className="text-sm text-destructive">
              {errorMessage(preliminaryStatisticsQuery.error)}
            </p>
          ) : null}
          {displayedStatistics ? (
            <section
              className="flex flex-col gap-3"
              aria-labelledby="preview-title"
            >
              <h2 id="preview-title" className="text-lg font-semibold">
                Предварительные итоги
              </h2>
              <InventoryStatistics
                statistics={displayedStatistics}
                findings={activeReviewSession.findings}
                showCounters={false}
                separateAggregateTables
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
            onDraftChange={(dirty) => {
              setFurnitureReviewDirty(dirty)
              if (dirty) setFinalPlanDirty(true)
            }}
          />
          {furnitureReview!.confirmed && !furnitureReviewDirty ? (
            <>
              {!furnitureReviewMatchesSession ? (
                <InventoryLoading>
                  Обновляем версию инвентаризации...
                </InventoryLoading>
              ) : planningSettingsQuery.isLoading ||
                finalPlanQuery.isLoading ? (
                <InventoryLoading>
                  Загружаем настройки и итоговый план...
                </InventoryLoading>
              ) : null}
              {planningSettingsQuery.error ? (
                <p role="alert" className="text-sm text-destructive">
                  {errorMessage(planningSettingsQuery.error)}
                </p>
              ) : null}
              {finalPlanQuery.error ? (
                <p role="alert" className="text-sm text-destructive">
                  {errorMessage(finalPlanQuery.error)}
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
          planningSettingsQuery.data &&
          !planningSettingsQuery.error &&
          !finalPlanQuery.error &&
          (finalPlan === null || finalPlan.state === "STALE") ? (
            <Card>
              <CardHeader>
                <CardTitle>
                  {finalPlan?.state === "STALE"
                    ? "Итоговый план устарел"
                    : "Подготовить итоговый план"}
                </CardTitle>
                <CardDescription>
                  {finalPlan?.state === "STALE"
                    ? "После подготовки плана состав или результаты инвентаризации изменились. Постройте новую версию: устаревшую нельзя сохранить или применить."
                    : "Сервер возьмёт только текущий живой состав, найдёт уже существующие сметы и ремонты и предложит предварительные даты. До окончательного завершения рабочие доски не меняются."}
                </CardDescription>
              </CardHeader>
              <CardContent className="flex flex-wrap gap-2">
                <Badge variant="secondary">
                  Перемещения:{" "}
                  {planningSettingsQuery.data.movementDailyCapacity}
                  /день
                </Badge>
                <Badge variant="secondary">
                  Ремонты: {planningSettingsQuery.data.repairDailyCapacity}/день
                </Badge>
                <Badge variant="outline">
                  Настройки v{planningSettingsQuery.data.settingsRevision}
                </Badge>
              </CardContent>
              <CardFooter className="flex-col items-stretch gap-3 border-t">
                {prepareFinalPlanMutation.error ? (
                  <p role="alert" className="text-sm text-destructive">
                    {errorMessage(prepareFinalPlanMutation.error)}
                  </p>
                ) : null}
                <Button
                  type="button"
                  disabled={prepareFinalPlanMutation.isPending}
                  onClick={() => prepareFinalPlanMutation.mutate()}
                >
                  {finalPlan?.state === "STALE"
                    ? "Перестроить план по актуальным данным"
                    : "Подготовить план и перейти к сверке заданий"}
                </Button>
              </CardFooter>
            </Card>
          ) : null}
          {draftFinalPlan ? (
            <InventoryFinalPlanEditor
              key={draftFinalPlan.finalPlanVersion}
              plan={draftFinalPlan}
              findings={session.findings}
              pending={saveFinalPlanMutation.isPending}
              error={
                saveFinalPlanMutation.error
                  ? errorMessage(saveFinalPlanMutation.error)
                  : null
              }
              onDirtyChange={setFinalPlanDirty}
              onOpenFinding={(findingId) =>
                navigate(
                  `/inventory/${session.id}?findingId=${findingId}`,
                  workspaceEntryNavigationOptions
                )
              }
              onOpenCandidate={(candidate) =>
                navigate(
                  candidate.targetKind === "ESTIMATE"
                    ? `/estimates?estimateId=${encodeURIComponent(candidate.targetId)}`
                    : `/repairs?repairId=${encodeURIComponent(candidate.targetId)}`
                )
              }
              onSave={(request) => saveFinalPlanMutation.mutate(request)}
            />
          ) : null}
          {draftFinalPlan &&
          !finalPlanDirty &&
          completionPreviewQuery.isLoading ? (
            <InventoryLoading>
              Проверяем точную версию плана и актуальное состояние реестра...
            </InventoryLoading>
          ) : null}
          {draftFinalPlan && !finalPlanDirty && completionPreviewQuery.error ? (
            <Card>
              <CardHeader>
                <CardTitle>План нужно проверить заново</CardTitle>
                <CardDescription role="alert" className="text-destructive">
                  {errorMessage(completionPreviewQuery.error)}
                </CardDescription>
              </CardHeader>
              <CardFooter className="justify-end border-t">
                <Button
                  type="button"
                  variant="outline"
                  disabled={prepareFinalPlanMutation.isPending}
                  onClick={() => prepareFinalPlanMutation.mutate()}
                >
                  Перестроить план по актуальным данным
                </Button>
              </CardFooter>
            </Card>
          ) : null}
          {furnitureReview!.confirmed &&
          !furnitureReviewDirty &&
          furnitureReviewMatchesSession &&
          draftFinalPlan &&
          !finalPlanDirty &&
          reviewedFurnitureSession &&
          !completionPreviewQuery.error ? (
            <Card>
              <CardHeader>
                <CardTitle>Завершение инвентаризации</CardTitle>
                <CardDescription>
                  Вы видите проверенную сервером версию итогового плана.
                  Завершение одним действием зафиксирует результаты, применит
                  выбранные замены/слияния и создаст новые сметы или ремонты.
                </CardDescription>
              </CardHeader>
              <CardContent className="flex flex-col gap-3">
                <div className="flex flex-wrap gap-2">
                  <Badge>План v{draftFinalPlan.finalPlanVersion}</Badge>
                  <Badge variant="secondary">
                    Бытовок: {draftFinalPlan.entries.length}
                  </Badge>
                  <Badge variant="secondary">
                    С замечаниями:{" "}
                    {
                      draftFinalPlan.entries.filter((entry) => entry.hasWork)
                        .length
                    }
                  </Badge>
                  <Badge variant="outline">
                    Проверка: {draftFinalPlan.finalPlanSha256.slice(0, 12)}…
                  </Badge>
                </div>
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
              </CardContent>
              <CardFooter className="justify-end border-t">
                <Button
                  type="button"
                  disabled={
                    completionMutation.isPending ||
                    completionPreviewQuery.isFetching ||
                    unresolvedConflicts
                  }
                  onClick={() => completionMutation.mutate()}
                >
                  <HugeiconsIcon icon={SentIcon} data-icon="inline-start" />
                  Завершить и применить итоговый план
                </Button>
              </CardFooter>
            </Card>
          ) : null}
        </>
      )}
      <Dialog
        open={dataChangedDialogOpen}
        onOpenChange={(open) => {
          setDataChangedDialogOpen(open)
          if (!open) {
            startFurnitureReviewMutation.reset()
            registryReviewMutation.reset()
          }
        }}
      >
        <DialogContent>
          <DialogHeader>
            <DialogTitle>Данные инвентаризации изменились</DialogTitle>
            <DialogDescription>
              После открытия этой страницы сведения о бытовках изменились. Перед
              переходом к мебели сравните данные и разрешите конфликты с
              актуальным реестром.
            </DialogDescription>
          </DialogHeader>
          {registryReviewMutation.error ? (
            <p role="alert" className="text-sm text-destructive">
              {errorMessage(registryReviewMutation.error)}
            </p>
          ) : null}
          <DialogFooter>
            <Button
              type="button"
              variant="outline"
              onClick={() => {
                setDataChangedDialogOpen(false)
                startFurnitureReviewMutation.reset()
                registryReviewMutation.reset()
              }}
            >
              Остаться на странице
            </Button>
            <Button
              type="button"
              disabled={registryReviewMutation.isPending}
              onClick={() => {
                registryReviewMutation.reset()
                registryReviewMutation.mutate()
              }}
            >
              {registryReviewMutation.isPending
                ? "Загружаем изменения..."
                : "Перейти к разрешению конфликтов"}
            </Button>
          </DialogFooter>
        </DialogContent>
      </Dialog>
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
  const [outcomeDialogOpen, setOutcomeDialogOpen] = useState(false)
  const { currentUser } = useAuth()
  const { inventoryId, query, session } = useInventoryDetailRoute()
  const findingId = searchParams.get("findingId")
  const selectedFinding =
    session?.findings.find((item) => item.id === findingId) ?? null
  const goBack = useWorkspaceBack(`/inventory/history/${inventoryId}`)
  const canRecalculate = Boolean(
    session &&
    hasInventoryWarehouseAccess(currentUser, session.warehouseId, "MANAGE")
  )
  const historyMutationKey = [
    ...INVENTORY_QUERY_KEY,
    "history-mutation",
    inventoryId,
  ] as const
  const historyMutationCount = useIsMutating({
    mutationKey: historyMutationKey,
  })
  const historyFinalPlanQueryKey = inventoryFinalPlanQueryKey(
    session?.id ?? null,
    session?.version ?? null
  )
  const historyFinalPlanQuery = useQuery({
    queryKey: historyFinalPlanQueryKey,
    queryFn: () =>
      session ? getInventoryFinalPlan(session.id) : Promise.resolve(null),
    enabled: Boolean(
      session && session.status === "COMPLETED" && canRecalculate
    ),
  })
  const completedFinalPlan =
    historyFinalPlanQuery.data?.state === "COMPLETED"
      ? historyFinalPlanQuery.data
      : null
  const outcomeMutation = useMutation({
    mutationKey: [...historyMutationKey, "outcome-recalculate"],
    mutationFn: async () => {
      if (!session || !completedFinalPlan) {
        throw new Error("Завершённый итоговый план недоступен")
      }
      const outcome = await recalculateInventoryOutcome({
        inventoryId: session.id,
        expectedSessionRevision: session.version,
        finalPlanVersion: completedFinalPlan.finalPlanVersion,
        finalPlanSha256: completedFinalPlan.finalPlanSha256,
      })
      if (
        outcome.inventoryId !== session.id ||
        outcome.finalPlanVersion !== completedFinalPlan.finalPlanVersion ||
        outcome.finalPlanSha256 !== completedFinalPlan.finalPlanSha256
      ) {
        throw new Error(
          "Сервис вернул пересчёт для другой версии итогового плана"
        )
      }
      return outcome
    },
    onSuccess: async (outcome) => {
      queryClient.setQueryData(
        inventoryPublicationQueryKey(outcome.inventoryId),
        outcome.publicationBatch
      )
      queryClient.setQueryData<InventorySessionDto>(
        inventoryDetailQueryKey(outcome.inventoryId),
        (cached) =>
          cached ? applyInventoryOutcomeRecalculation(cached, outcome) : cached
      )
      await Promise.all([
        queryClient.invalidateQueries({
          queryKey: inventoryDetailQueryKey(outcome.inventoryId),
        }),
        queryClient.invalidateQueries({
          queryKey: [...INVENTORY_QUERY_KEY, "final-plan", outcome.inventoryId],
        }),
        queryClient.invalidateQueries({
          queryKey: [
            ...INVENTORY_QUERY_KEY,
            "statistics-preview",
            outcome.inventoryId,
          ],
        }),
        queryClient.invalidateQueries({
          queryKey: inventoryListQueryKey(session!.warehouseId),
        }),
      ])
      setOutcomeDialogOpen(false)
      toast.success(
        `Пересчёт запущен: создано ${outcome.createdPublicationCount}, заново поставлено ${outcome.requeuedPublicationCount}, уже полностью применено ${outcome.preservedSucceededPublicationCount}.`
      )
    },
  })
  const historyMutationPending =
    historyMutationCount > 0 || outcomeMutation.isPending
  const outcomeActionDisabled =
    !canRecalculate ||
    !completedFinalPlan ||
    historyFinalPlanQuery.isFetching ||
    historyMutationPending
  const outcomeActionDisabledReason = !canRecalculate
    ? "Для пересчёта нужны права MANAGE на выбранном складе"
    : !completedFinalPlan
      ? "Завершённый итоговый план не загружен"
      : historyMutationPending
        ? "Другая команда истории ещё выполняется"
        : undefined
  if (query.isLoading) return <InventoryLoading />
  if (query.error)
    return (
      <InventoryUnavailable
        title="Результат не загружен"
        description={errorMessage(query.error)}
      />
    )
  if (!session || session.status === "ACTIVE")
    return (
      <InventoryUnavailable
        title="Результат недоступен"
        description="Завершённая или отменённая инвентаризация не найдена на выбранном складе."
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
  if (session.status === "CANCELLED") {
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
        </PageToolbar>
        <Card className="border-destructive/50">
          <CardHeader>
            <CardTitle className="flex flex-wrap items-center gap-2">
              <Badge variant="destructive">Инвентаризация отменена</Badge>
              <span>{session.warehouse.name}</span>
            </CardTitle>
            <CardDescription>
              Отмена не публикует сметы, ремонты или задания. Собранные данные
              сохранены только как журнал этой сессии.
            </CardDescription>
          </CardHeader>
          <CardContent>
            <dl className="grid gap-2 text-sm sm:grid-cols-[12rem_1fr]">
              <dt className="text-muted-foreground">Причина</dt>
              <dd>{session.cancellation?.reason ?? "Причина не сохранена"}</dd>
              <dt className="text-muted-foreground">Время отмены</dt>
              <dd>
                {session.cancellation?.cancelledAt
                  ? formatInventoryDateTime(session.cancellation.cancelledAt)
                  : session.completedAt
                    ? formatInventoryDateTime(session.completedAt)
                    : "—"}
              </dd>
              <dt className="text-muted-foreground">Автор сессии</dt>
              <dd>{session.author.displayName}</dd>
            </dl>
          </CardContent>
        </Card>
        <InventoryMembershipMovements
          movements={session.membershipMovements}
          warehouse={session.warehouse}
        />
        <div className="min-h-[20rem] md:flex">
          <InventoryFindingsList
            findings={session.findings}
            canInspect={false}
            statusMode="COMPLETION"
            onOpen={(finding) =>
              navigate(
                `/inventory/history/${session.id}?findingId=${finding.id}`,
                workspaceEntryNavigationOptions
              )
            }
          />
        </div>
      </div>
    )
  }
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
        <PageToolbarActions>
          <AlertDialog
            open={outcomeDialogOpen}
            onOpenChange={(open) => {
              if (!open && historyMutationPending) return
              setOutcomeDialogOpen(open)
            }}
          >
            <AlertDialogTrigger asChild>
              <Button
                type="button"
                disabled={outcomeActionDisabled}
                title={outcomeActionDisabledReason}
              >
                <HugeiconsIcon icon={RefreshIcon} data-icon="inline-start" />
                Пересчитать и применить итоги
              </Button>
            </AlertDialogTrigger>
            <AlertDialogContent>
              <AlertDialogHeader>
                <AlertDialogTitle>
                  Применить итоги завершённой инвентаризации?
                </AlertDialogTitle>
                <AlertDialogDescription>
                  Завершённая инвентаризация станет текущей истиной. Активная
                  аренда, резерв, внутреннее перемещение и прежняя привязка к
                  ремонту могут быть заменены её результатом. Бытовка без работ
                  станет свободной, с обычными работами — в ремонте, с
                  принудительным капремонтом — в капитальном ремонте.
                  Фотографии, доказательства и история сохраняются.
                </AlertDialogDescription>
              </AlertDialogHeader>
              {outcomeMutation.error ? (
                <Alert variant="destructive">
                  <AlertTitle>Пересчёт не запущен</AlertTitle>
                  <AlertDescription>
                    {outcomeRecalculationErrorMessage(outcomeMutation.error)}
                  </AlertDescription>
                </Alert>
              ) : null}
              <AlertDialogFooter>
                <AlertDialogCancel disabled={historyMutationPending}>
                  Отмена
                </AlertDialogCancel>
                <AlertDialogAction
                  disabled={historyMutationPending}
                  onClick={(event) => {
                    event.preventDefault()
                    outcomeMutation.mutate()
                  }}
                >
                  {historyMutationPending ? (
                    <HugeiconsIcon
                      icon={Loading03Icon}
                      data-icon="inline-start"
                      className="animate-spin"
                    />
                  ) : null}
                  {historyMutationPending
                    ? "Запускаем пересчёт…"
                    : "Пересчитать и применить"}
                </AlertDialogAction>
              </AlertDialogFooter>
            </AlertDialogContent>
          </AlertDialog>
        </PageToolbarActions>
      </PageToolbar>
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
