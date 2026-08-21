import { useMemo, useRef, useState, type FormEvent } from "react"
import {
  useMutation,
  useQueries,
  useQuery,
  useQueryClient,
} from "@tanstack/react-query"
import { Link, useNavigate, useSearchParams } from "react-router-dom"

import { OperationsListGrid } from "@/components/operations-list-grid"
import {
  PageToolbar,
  PageToolbarActions,
  PageToolbarContent,
} from "@/components/page-toolbar"
import {
  AlertDialog,
  AlertDialogAction,
  AlertDialogCancel,
  AlertDialogContent,
  AlertDialogDescription,
  AlertDialogFooter,
  AlertDialogHeader,
  AlertDialogTitle,
} from "@/components/ui/alert-dialog"
import { Badge } from "@/components/ui/badge"
import { Button } from "@/components/ui/button"
import {
  Card,
  CardAction,
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
  Field,
  FieldDescription,
  FieldError,
  FieldGroup,
} from "@/components/ui/field"
import { Input } from "@/components/ui/input"
import { Skeleton } from "@/components/ui/skeleton"
import { hasWarehouseAccess } from "@/features/auth/warehouse-access"
import { useAuth } from "@/features/auth/use-auth"
import {
  getEquipmentMovementTask,
  type EquipmentMovementTask,
} from "@/features/logistics/api/equipment-movement-tasks-api"
import {
  LogisticsDocumentFilters,
  LogisticsFiltersToggle,
  type LogisticsDocumentFiltersState,
} from "@/features/logistics/logistics-document-filters"
import { LogisticsDriverPicker } from "@/features/logistics/logistics-driver-picker"
import { DesiredTripScheduleFields } from "@/features/logistics/desired-trip-schedule-fields"
import { DRIVER_BOARD_QUERY_KEY } from "@/features/logistics/driver-board/driver-board-api"
import { CabinFurnitureSummary } from "@/features/logistics/order-tasks/cabin-furniture-summary"
import { OrderCustomerOverview } from "@/features/logistics/order-tasks/order-customer-overview"
import {
  RETURNS_QUERY_KEY,
  listReturns,
} from "@/features/logistics/returns/api"
import type { ReturnDocument } from "@/features/logistics/returns/model"
import {
  logisticsAssetLabel,
  logisticsOrderLabel,
  useLogisticsReferenceLabels,
  type LogisticsReferenceLabels,
} from "@/features/logistics/use-logistics-reference-labels"
import {
  SHIPMENT_FURNITURE_READINESS_QUERY_KEY,
  SHIPMENTS_QUERY_KEY,
  cancelShipment,
  confirmShipmentPreparation,
  createShipmentFurnitureTasks,
  getShipmentFurnitureReadiness,
  listShipments,
  replaceShipmentPlan,
} from "@/features/logistics/shipments/api"
import {
  SHIPMENT_DOCUMENT_STATES,
  SHIPMENT_STATE_LABELS,
  type ShipmentDocument,
  type ShipmentFurnitureReadiness,
  type InventoryShipmentFurniture,
  type ShipmentFurnitureTaskStatus,
  type ShipmentDocumentState,
} from "@/features/logistics/shipments/model"
import type { RepairTaskWorkerSnapshotDto } from "@/features/repair-tasks/model/repair-task"
import type { DesiredDeliveryWindow } from "@/features/orders/domain/orders"
import { useResponsiveFiltersOpen } from "@/hooks/use-responsive-filters-open"
import { useWarehouse } from "@/hooks/use-warehouse"
import { ApiError } from "@/lib/api-client"

const CANCELLABLE_STATES = new Set<ShipmentDocumentState>([
  "DRAFT",
  "PREPARING",
  "AWAITING_CONFIRMATION",
])

type ShipmentFilters = LogisticsDocumentFiltersState<ShipmentDocumentState> & {
  counterparties: string[]
  drivers: string[]
}

type ShipmentConfirmationCommand = {
  shipment: ShipmentDocument
  /** Explicitly preserve a planned date when the physical departure is today. */
  keepScheduledDate?: boolean
}

const EMPTY_FILTERS: ShipmentFilters = {
  states: [],
  schedule: "ALL",
  dateFrom: "",
  dateTo: "",
  counterparties: [],
  drivers: [],
}

function commandIdentity() {
  return crypto.randomUUID()
}

function formatDate(value: string) {
  return new Intl.DateTimeFormat("ru-RU", {
    dateStyle: "medium",
  }).format(new Date(`${value}T00:00:00`))
}

function formatSchedule(date: string | null) {
  return date ? formatDate(date) : "Не назначена"
}

function formatOptionalDate(date: string | null | undefined) {
  return date ? formatDate(date) : "—"
}

function rentalMonthLabel(value: number) {
  return `${value} мес.`
}

function linkedReturnDriverLabel(
  document: ReturnDocument | undefined,
  state: LinkedDocumentReadState
) {
  if (state === "loading") return "Загружаем…"
  if (state === "unavailable") return "Данные возврата недоступны"
  if (!document) return "Возврат не создан"
  return document.driverSnapshot ?? "Не назначен"
}

function matchesDateRange(
  value: string | null,
  filters: LogisticsDocumentFiltersState<string>
) {
  if (filters.schedule === "SCHEDULED" && value === null) return false
  if (filters.schedule === "UNSCHEDULED" && value !== null) return false
  if (!value) return !filters.dateFrom && !filters.dateTo
  if (filters.dateFrom && value < filters.dateFrom) {
    return false
  }
  if (filters.dateTo && value > filters.dateTo) {
    return false
  }
  return true
}

function textFilterOptions(values: Iterable<string | null | undefined>) {
  return [
    ...new Set(
      [...values].filter((value): value is string => Boolean(value?.trim()))
    ),
  ]
    .sort((left, right) => left.localeCompare(right, "ru"))
    .map((value) => ({ value, label: value }))
}

function statusVariant(state: ShipmentDocumentState) {
  if (state === "CONFLICT" || state === "RECONCILIATION_REQUIRED") {
    return "destructive" as const
  }
  if (state === "SHIPPED" || state === "CANCELLED") {
    return "secondary" as const
  }
  return "outline" as const
}

function errorMessage(cause: unknown, fallback: string) {
  return cause instanceof Error ? cause.message : fallback
}

function isOrderShipment(shipment: ShipmentDocument) {
  return (
    shipment.rentalOrderId !== null ||
    shipment.lines.some((line) => line.rentalOrderId !== null)
  )
}

function needsFurnitureReadiness(shipment: ShipmentDocument) {
  return (
    isOrderShipment(shipment) &&
    (shipment.state === "DRAFT" || shipment.state === "AWAITING_CONFIRMATION")
  )
}

function desiredWindowsForShipment(
  referenceLabels: LogisticsReferenceLabels,
  shipment: ShipmentDocument
) {
  if (!shipment.rentalOrderId) return []
  const order = referenceLabels.orders.get(shipment.rentalOrderId)
  return order?.status === "available" ? order.order.desiredDeliveryWindows : []
}

function furnitureTaskHref(readiness: ShipmentFurnitureReadiness | undefined) {
  const task =
    readiness?.tasks.find((candidate) => candidate.taskState !== "COMPLETED") ??
    readiness?.tasks[0]
  return task
    ? `/task-board?externalTaskId=${encodeURIComponent(task.externalTaskId)}`
    : null
}

type ShipmentFurnitureMovementTaskReference =
  | { status: "loading" }
  | { status: "unavailable" }
  | { status: "available"; task: EquipmentMovementTask }

type LinkedDocumentReadState = "loading" | "available" | "unavailable"

export function LogisticsShipmentsPage() {
  const { selectedWarehouseId } = useWarehouse()
  const { accessToken, currentUser } = useAuth()
  const navigate = useNavigate()
  const [searchParams] = useSearchParams()
  const queryClient = useQueryClient()
  const [search, setSearch] = useState("")
  const [filters, setFilters] = useState(EMPTY_FILTERS)
  const { filtersOpen, setFiltersOpen } = useResponsiveFiltersOpen()
  const [expandedId, setExpandedId] = useState<string | null>(null)
  const [scheduleTarget, setScheduleTarget] = useState<{
    document: ShipmentDocument
    futureDateWarning: boolean
  } | null>(null)
  const [shipmentDateDecisionTarget, setShipmentDateDecisionTarget] =
    useState<ShipmentDocument | null>(null)
  const [cancelTarget, setCancelTarget] = useState<ShipmentDocument | null>(
    null
  )
  const [commandError, setCommandError] = useState<string | null>(null)
  const [commandNotice, setCommandNotice] = useState<string | null>(null)
  const [furnitureTaskCreationRequested, setFurnitureTaskCreationRequested] =
    useState<ReadonlySet<string>>(() => new Set())
  const commandKeys = useRef(new Map<string, string>())
  const selectedShipmentId = searchParams.get("shipmentId")
  const queryKey = [...SHIPMENTS_QUERY_KEY, selectedWarehouseId] as const

  const query = useQuery({
    queryKey,
    queryFn: () => listShipments(accessToken!, selectedWarehouseId!),
    enabled: Boolean(accessToken && selectedWarehouseId),
    refetchInterval: 5_000,
  })
  const returnsQuery = useQuery({
    queryKey: [...RETURNS_QUERY_KEY, "shipment-links", selectedWarehouseId],
    queryFn: () => listReturns(accessToken!, selectedWarehouseId!),
    enabled: Boolean(accessToken && selectedWarehouseId),
    refetchInterval: 5_000,
  })
  const returnsByShipmentId = useMemo(() => {
    const result = new Map<string, ReturnDocument[]>()
    ;(returnsQuery.data ?? []).forEach((document) => {
      if (!document.rentalShipmentId) return
      result.set(document.rentalShipmentId, [
        ...(result.get(document.rentalShipmentId) ?? []),
        document,
      ])
    })
    return result
  }, [returnsQuery.data])
  const furnitureReadinessShipments = useMemo(
    () => (query.data ?? []).filter(needsFurnitureReadiness),
    [query.data]
  )
  const furnitureReadinessQueries = useQueries({
    queries: furnitureReadinessShipments.map((shipment) => ({
      queryKey: [
        ...SHIPMENT_FURNITURE_READINESS_QUERY_KEY,
        currentUser?.id ?? "unknown-user",
        shipment.id,
        shipment.version,
      ],
      queryFn: () => getShipmentFurnitureReadiness(accessToken!, shipment.id),
      enabled: Boolean(accessToken),
      refetchInterval: 3_000,
    })),
  })
  const furnitureReadinessByShipmentId = useMemo(
    () =>
      new Map(
        furnitureReadinessShipments.flatMap((shipment, index) => {
          const readiness = furnitureReadinessQueries[index]?.data
          return readiness ? ([[shipment.id, readiness]] as const) : []
        })
      ),
    [furnitureReadinessQueries, furnitureReadinessShipments]
  )
  const furnitureReadinessQueryByShipmentId = useMemo(
    () =>
      new Map(
        furnitureReadinessShipments.map((shipment, index) => [
          shipment.id,
          furnitureReadinessQueries[index],
        ])
      ),
    [furnitureReadinessQueries, furnitureReadinessShipments]
  )
  const furnitureTaskReferences = useMemo(() => {
    const tasks = new Map<string, ShipmentFurnitureTaskStatus>()
    furnitureReadinessByShipmentId.forEach((readiness) => {
      readiness.tasks.forEach((task) => tasks.set(task.taskId, task))
    })
    return [...tasks.values()]
  }, [furnitureReadinessByShipmentId])
  const furnitureTaskQueries = useQueries({
    queries: furnitureTaskReferences.map((task) => ({
      queryKey: [
        "logistics",
        "shipment-composition-furniture-task",
        currentUser?.id ?? "unknown-user",
        task.taskId,
      ],
      queryFn: () => getEquipmentMovementTask(accessToken!, task.taskId),
      enabled: Boolean(accessToken),
      refetchInterval: 3_000,
    })),
  })
  const furnitureMovementTasksById = useMemo(() => {
    const tasks = new Map<string, ShipmentFurnitureMovementTaskReference>()
    furnitureTaskReferences.forEach((task, index) => {
      const query = furnitureTaskQueries[index]
      const reference: ShipmentFurnitureMovementTaskReference = query?.data
        ? { status: "available", task: query.data }
        : query?.isLoading
          ? { status: "loading" }
          : { status: "unavailable" }
      tasks.set(task.taskId, reference)
    })
    return tasks
  }, [furnitureTaskQueries, furnitureTaskReferences])
  const referenceLabels = useLogisticsReferenceLabels(
    accessToken,
    query.data ?? []
  )

  const stateOptions = useMemo(
    () =>
      SHIPMENT_DOCUMENT_STATES.map((state) => ({
        value: state,
        label: SHIPMENT_STATE_LABELS[state],
      })),
    []
  )
  const counterpartyOptions = useMemo(
    () =>
      textFilterOptions(
        (query.data ?? []).map((shipment) => shipment.partySnapshot)
      ),
    [query.data]
  )
  const driverOptions = useMemo(
    () =>
      textFilterOptions(
        (query.data ?? []).map((shipment) => shipment.driverSnapshot)
      ),
    [query.data]
  )
  const rows = useMemo(() => {
    const needle = search.trim().toLocaleLowerCase("ru")
    return (query.data ?? []).filter((shipment) => {
      if (selectedShipmentId && shipment.id !== selectedShipmentId) return false
      if (
        filters.states.length > 0 &&
        !filters.states.includes(shipment.state)
      ) {
        return false
      }
      if (
        filters.counterparties.length > 0 &&
        !filters.counterparties.includes(shipment.partySnapshot ?? "")
      ) {
        return false
      }
      if (
        filters.drivers.length > 0 &&
        !filters.drivers.includes(shipment.driverSnapshot ?? "")
      ) {
        return false
      }
      if (!matchesDateRange(shipment.scheduledDate, filters)) return false
      if (!needle) return true
      return [
        shipment.id,
        shipment.partySnapshot,
        shipment.driverSnapshot,
        shipment.rentalOrderId,
        shipment.rentalOrderId
          ? referenceLabels.orderNumbers.get(shipment.rentalOrderId)
          : null,
        SHIPMENT_STATE_LABELS[shipment.state],
        ...shipment.lines.flatMap((line) => [
          line.id,
          line.assetId,
          referenceLabels.assetNumbers.get(line.assetId),
          line.rentalOrderId
            ? referenceLabels.orderNumbers.get(line.rentalOrderId)
            : null,
        ]),
      ]
        .filter(Boolean)
        .some((value) => String(value).toLocaleLowerCase("ru").includes(needle))
    })
  }, [
    filters,
    query.data,
    referenceLabels.assetNumbers,
    referenceLabels.orderNumbers,
    search,
    selectedShipmentId,
  ])

  function keyFor(signature: string) {
    const existing = commandKeys.current.get(signature)
    if (existing) return existing
    const idempotencyKey = commandIdentity()
    commandKeys.current.set(signature, idempotencyKey)
    return idempotencyKey
  }

  function applyServerProjection(shipment: ShipmentDocument) {
    queryClient.setQueryData<ShipmentDocument[]>(queryKey, (current) => {
      if (!current) return [shipment]
      const found = current.some((candidate) => candidate.id === shipment.id)
      return found
        ? current.map((candidate) =>
            candidate.id === shipment.id ? shipment : candidate
          )
        : [shipment, ...current]
    })
  }

  const scheduleMutation = useMutation({
    mutationFn: ({
      document,
      driverSnapshot,
      driverWorkerId,
      scheduledDate,
    }: {
      document: ShipmentDocument
      driverSnapshot: string
      driverWorkerId: string | null
      scheduledDate: string
    }) => {
      const signature = `schedule:${document.id}:${document.version}:${driverWorkerId ?? "unassigned"}:${driverSnapshot}:${scheduledDate}`
      return replaceShipmentPlan({
        accessToken: accessToken!,
        documentId: document.id,
        expectedVersion: document.version,
        driverSnapshot,
        driverWorkerId,
        scheduledDate,
        idempotencyKey: keyFor(signature),
      })
    },
    onSuccess: (result) => {
      applyServerProjection(result)
      setScheduleTarget(null)
      setCommandError(null)
      void queryClient.invalidateQueries({ queryKey: SHIPMENTS_QUERY_KEY })
      void queryClient.invalidateQueries({ queryKey: DRIVER_BOARD_QUERY_KEY })
    },
    onError: (cause, variables) => {
      if (cause instanceof ApiError && cause.status === 409) {
        commandKeys.current.delete(
          `schedule:${variables.document.id}:${variables.document.version}:${variables.driverWorkerId ?? "unassigned"}:${variables.driverSnapshot}:${variables.scheduledDate}`
        )
        void queryClient.invalidateQueries({ queryKey: SHIPMENTS_QUERY_KEY })
      }
      setCommandError(errorMessage(cause, "Не удалось назначить отгрузку"))
    },
  })

  const confirmMutation = useMutation({
    mutationFn: ({
      shipment,
      keepScheduledDate = false,
    }: ShipmentConfirmationCommand) => {
      const signature = `confirm:${shipment.id}:${shipment.version}:${keepScheduledDate ? "keep" : "scheduled"}`
      return confirmShipmentPreparation({
        accessToken: accessToken!,
        documentId: shipment.id,
        expectedVersion: shipment.version,
        idempotencyKey: keyFor(signature),
        ...(keepScheduledDate ? { keepScheduledDate: true } : {}),
      })
    },
    onSuccess: (result) => {
      applyServerProjection(result)
      setCommandError(null)
      void queryClient.invalidateQueries({ queryKey: SHIPMENTS_QUERY_KEY })
    },
    onError: (cause, { shipment, keepScheduledDate = false }) => {
      if (cause instanceof ApiError && cause.status === 409) {
        commandKeys.current.delete(
          `confirm:${shipment.id}:${shipment.version}:${keepScheduledDate ? "keep" : "scheduled"}`
        )
        void queryClient.invalidateQueries({ queryKey: SHIPMENTS_QUERY_KEY })
      }
      setCommandError(errorMessage(cause, "Не удалось отметить отгрузку"))
    },
  })

  const furnitureTasksMutation = useMutation({
    mutationFn: (shipment: ShipmentDocument) => {
      const signature = `furniture:${shipment.id}:${shipment.version}`
      return createShipmentFurnitureTasks({
        accessToken: accessToken!,
        documentId: shipment.id,
        expectedVersion: shipment.version,
        idempotencyKey: keyFor(signature),
      })
    },
    onSuccess: (result) => {
      const created = result.tasks.filter((task) => task.taskId !== null).length
      const alreadyMatched = result.tasks.length - created
      const details = [
        created > 0
          ? `создано заданий: ${created}`
          : "новые задания не требуются",
        alreadyMatched > 0
          ? `уже соответствует заказу: ${alreadyMatched}`
          : null,
      ]
        .filter(Boolean)
        .join("; ")
      setFurnitureTaskCreationRequested((current) =>
        new Set(current).add(result.shipmentId)
      )
      setCommandNotice(`Мебель обработана: ${details}.`)
      setCommandError(null)
      void queryClient.invalidateQueries({ queryKey: SHIPMENTS_QUERY_KEY })
      void queryClient.invalidateQueries({
        queryKey: SHIPMENT_FURNITURE_READINESS_QUERY_KEY,
      })
    },
    onError: (cause, shipment) => {
      if (cause instanceof ApiError && cause.status === 409) {
        commandKeys.current.delete(
          `furniture:${shipment.id}:${shipment.version}`
        )
        void queryClient.invalidateQueries({ queryKey: SHIPMENTS_QUERY_KEY })
      }
      setCommandNotice(null)
      setCommandError(errorMessage(cause, "Не удалось добавить мебель"))
    },
  })

  const cancelMutation = useMutation({
    mutationFn: (shipment: ShipmentDocument) => {
      const signature = `cancel:${shipment.id}:${shipment.version}`
      return cancelShipment({
        accessToken: accessToken!,
        documentId: shipment.id,
        expectedVersion: shipment.version,
        idempotencyKey: keyFor(signature),
      })
    },
    onSuccess: (result) => {
      applyServerProjection(result)
      setCancelTarget(null)
      setCommandError(null)
      void queryClient.invalidateQueries({ queryKey: SHIPMENTS_QUERY_KEY })
    },
    onError: (cause, shipment) => {
      if (cause instanceof ApiError && cause.status === 409) {
        commandKeys.current.delete(`cancel:${shipment.id}:${shipment.version}`)
        setCancelTarget(null)
        void queryClient.invalidateQueries({ queryKey: SHIPMENTS_QUERY_KEY })
      }
      setCommandError(errorMessage(cause, "Не удалось отменить отгрузку"))
    },
  })

  function requestConfirmation(shipment: ShipmentDocument) {
    if (!shipment.scheduledDate || !shipment.driverSnapshot) {
      setScheduleTarget({
        document: shipment,
        futureDateWarning: false,
      })
      return
    }
    if (shipment.scheduledDate !== new Date().toISOString().slice(0, 10)) {
      setShipmentDateDecisionTarget(shipment)
      return
    }
    confirmMutation.mutate({ shipment })
  }

  function actions(shipment: ShipmentDocument) {
    const confirming =
      confirmMutation.isPending &&
      confirmMutation.variables?.shipment.id === shipment.id
    const cancelling =
      cancelMutation.isPending && cancelMutation.variables?.id === shipment.id
    const scheduling =
      scheduleMutation.isPending &&
      scheduleMutation.variables?.document.id === shipment.id
    const addingFurniture =
      furnitureTasksMutation.isPending &&
      furnitureTasksMutation.variables?.id === shipment.id
    const orderShipment = isOrderShipment(shipment)
    const furnitureReadiness = furnitureReadinessByShipmentId.get(shipment.id)
    const furnitureReadinessQuery = furnitureReadinessQueryByShipmentId.get(
      shipment.id
    )
    const furnitureReadinessPending =
      needsFurnitureReadiness(shipment) &&
      furnitureReadiness === undefined &&
      (furnitureReadinessQuery?.isLoading ||
        furnitureReadinessQuery?.isFetching)
    const furnitureReady =
      !needsFurnitureReadiness(shipment) ||
      furnitureReadiness?.state === "NOT_REQUIRED" ||
      furnitureReadiness?.state === "READY"
    const furnitureBlocked =
      needsFurnitureReadiness(shipment) && !furnitureReady
    const furnitureTaskLink = furnitureTaskHref(furnitureReadiness)
    const awaitingFurnitureTask =
      furnitureReadiness?.state === "AWAITING_TASK_COMPLETION"
    const furnitureTaskBlocked = furnitureReadiness?.state === "BLOCKED"
    const furnitureTaskCreationNeeded =
      furnitureReadiness?.state === "REQUIRES_TASK_CREATION"
    const furnitureTaskCreationWasRequested =
      furnitureTaskCreationRequested.has(shipment.id)
    const canOpenFurnitureTask =
      Boolean(furnitureTaskLink) &&
      (awaitingFurnitureTask || furnitureTaskBlocked)
    const furnitureBlockerLabel = awaitingFurnitureTask
      ? "Требуется закрыть задание"
      : furnitureTaskBlocked
        ? "Требуется решить задачу"
        : furnitureTaskCreationWasRequested || furnitureReadinessPending
          ? "Проверяем мебель…"
          : furnitureReadinessQuery?.isError
            ? "Не удалось проверить мебель"
            : furnitureTaskCreationNeeded
              ? "Сначала добавьте мебель"
              : "Проверяем мебель…"
    const canEdit = hasWarehouseAccess(
      currentUser,
      shipment.warehouseId,
      "EDIT"
    )
    return (
      <div className="flex flex-wrap gap-2">
        <Button
          type="button"
          size="sm"
          variant="outline"
          onClick={() =>
            setExpandedId((current) =>
              current === shipment.id ? null : shipment.id
            )
          }
        >
          {expandedId === shipment.id ? "Скрыть состав" : "Показать состав"}
        </Button>
        {canEdit &&
        shipment.state === "DRAFT" &&
        orderShipment &&
        !furnitureTaskCreationWasRequested &&
        (furnitureTaskCreationNeeded || furnitureReadinessPending) ? (
          <Button
            type="button"
            size="sm"
            variant="outline"
            disabled={
              addingFurniture || furnitureReadinessPending || !accessToken
            }
            onClick={() => furnitureTasksMutation.mutate(shipment)}
          >
            {addingFurniture
              ? "Добавляем мебель…"
              : furnitureReadinessPending
                ? "Проверяем мебель…"
                : "Добавить мебель"}
          </Button>
        ) : null}
        {canEdit && shipment.state === "DRAFT" ? (
          furnitureBlocked ? (
            <Button
              type="button"
              size="sm"
              variant="outline"
              disabled={
                !canOpenFurnitureTask ||
                scheduling ||
                addingFurniture ||
                !accessToken
              }
              onClick={() => furnitureTaskLink && navigate(furnitureTaskLink)}
            >
              {furnitureBlockerLabel}
            </Button>
          ) : (
            <Button
              type="button"
              size="sm"
              disabled={scheduling || addingFurniture || !accessToken}
              onClick={() =>
                setScheduleTarget({
                  document: shipment,
                  futureDateWarning: false,
                })
              }
            >
              Отгрузить
            </Button>
          )
        ) : null}
        {canEdit && shipment.state === "AWAITING_CONFIRMATION" ? (
          <>
            <Button
              type="button"
              size="sm"
              variant="outline"
              disabled={scheduling || confirming || addingFurniture}
              onClick={() =>
                setScheduleTarget({
                  document: shipment,
                  futureDateWarning: false,
                })
              }
            >
              Изменить дату
            </Button>
            {furnitureBlocked ? (
              <Button
                type="button"
                size="sm"
                variant="outline"
                disabled={
                  !canOpenFurnitureTask ||
                  confirming ||
                  scheduling ||
                  addingFurniture ||
                  !accessToken
                }
                onClick={() => furnitureTaskLink && navigate(furnitureTaskLink)}
              >
                {furnitureBlockerLabel}
              </Button>
            ) : (
              <Button
                type="button"
                size="sm"
                disabled={
                  confirming || scheduling || addingFurniture || !accessToken
                }
                onClick={() => requestConfirmation(shipment)}
              >
                {confirming ? "Отмечаем…" : "Отгружена"}
              </Button>
            )}
          </>
        ) : null}
        {canEdit && CANCELLABLE_STATES.has(shipment.state) ? (
          <Button
            type="button"
            size="sm"
            variant="destructive"
            disabled={
              confirming ||
              cancelling ||
              scheduling ||
              addingFurniture ||
              !accessToken
            }
            onClick={() => setCancelTarget(shipment)}
          >
            Отменить
          </Button>
        ) : null}
      </div>
    )
  }

  return (
    <div className="flex h-full min-h-0 flex-col gap-4 overflow-hidden">
      <PageToolbar>
        <PageToolbarContent className="max-w-xl">
          <Input
            aria-label="Поиск отгрузок"
            placeholder="Заказ, бытовка, контрагент или водитель"
            value={search}
            onChange={(event) => setSearch(event.target.value)}
          />
        </PageToolbarContent>
        <PageToolbarActions>
          <LogisticsFiltersToggle
            open={filtersOpen}
            controls="logistics-shipment-filters"
            onOpenChange={setFiltersOpen}
          />
        </PageToolbarActions>
      </PageToolbar>

      <div id="logistics-shipment-filters" hidden={!filtersOpen}>
        <LogisticsDocumentFilters
          filters={filters}
          stateOptions={stateOptions}
          dateLabel="Отгрузка"
          extraFilters={[
            {
              label: "Контрагент",
              options: counterpartyOptions,
              selected: filters.counterparties,
              onApply: (counterparties) =>
                setFilters((current) => ({ ...current, counterparties })),
            },
            {
              label: "Водитель",
              options: driverOptions,
              selected: filters.drivers,
              onApply: (drivers) =>
                setFilters((current) => ({ ...current, drivers })),
            },
          ]}
          onChange={(nextFilters) =>
            setFilters((current) => ({ ...current, ...nextFilters }))
          }
          onReset={() => setFilters(EMPTY_FILTERS)}
        />
      </div>

      {!accessToken ? (
        <FieldError>Для просмотра отгрузок требуется авторизация.</FieldError>
      ) : null}
      {!selectedWarehouseId ? (
        <FieldError>Выберите склад для просмотра отгрузок.</FieldError>
      ) : null}
      {query.error ? (
        <FieldError>
          {errorMessage(query.error, "Не удалось загрузить отгрузки")}
        </FieldError>
      ) : null}
      {commandError ? <FieldError>{commandError}</FieldError> : null}
      {commandNotice ? (
        <p className="text-sm text-muted-foreground">{commandNotice}</p>
      ) : null}

      <div className="min-h-0 flex-1 overflow-auto">
        <div className="hidden min-h-full md:block">
          <OperationsListGrid
            className="min-h-full"
            items={rows}
            expandedItemId={expandedId}
            renderExpandedRow={(shipment) => (
              <ShipmentDetails
                shipment={shipment}
                referenceLabels={referenceLabels}
                linkedReturns={returnsByShipmentId.get(shipment.id) ?? []}
                linkedReturnsState={
                  returnsQuery.isLoading
                    ? "loading"
                    : returnsQuery.isError
                      ? "unavailable"
                      : "available"
                }
                furnitureReadiness={furnitureReadinessByShipmentId.get(
                  shipment.id
                )}
                furnitureMovementTasksById={furnitureMovementTasksById}
              />
            )}
            columns={[
              {
                id: "scheduledDate",
                label: "Дата отгрузки",
                className: "w-48",
                getSortValue: (shipment) => shipment.scheduledDate ?? "",
                render: (shipment) => formatSchedule(shipment.scheduledDate),
              },
              {
                id: "party",
                label: "Контрагент",
                className: "min-w-52",
                getSortValue: (shipment) => shipment.partySnapshot,
                render: (shipment) => shipment.partySnapshot,
              },
              {
                id: "driver",
                label: "Водитель",
                className: "min-w-52",
                getSortValue: (shipment) => shipment.driverSnapshot ?? "",
                render: (shipment) => shipment.driverSnapshot ?? "Не назначен",
              },
              {
                id: "state",
                label: "Статус",
                className: "w-52",
                getSortValue: (shipment) =>
                  SHIPMENT_STATE_LABELS[shipment.state],
                render: (shipment) => (
                  <Badge variant={statusVariant(shipment.state)}>
                    {SHIPMENT_STATE_LABELS[shipment.state]}
                  </Badge>
                ),
              },
              {
                id: "lines",
                label: "Бытовок",
                className: "w-24",
                getSortValue: (shipment) => shipment.lines.length,
                render: (shipment) => shipment.lines.length,
              },
              {
                id: "actions",
                label: "Действия",
                className: "min-w-96",
                getSortValue: (shipment) => shipment.updatedAt,
                render: actions,
              },
            ]}
          />
        </div>

        <div className="grid gap-3 md:hidden">
          {rows.map((shipment) => (
            <Card key={shipment.id} size="sm">
              <CardHeader>
                <CardTitle>{shipment.partySnapshot}</CardTitle>
                <CardDescription>
                  {formatSchedule(shipment.scheduledDate)}
                  {` · ${shipment.driverSnapshot ?? "водитель не назначен"}`}
                </CardDescription>
                <CardAction>
                  <Badge variant={statusVariant(shipment.state)}>
                    {SHIPMENT_STATE_LABELS[shipment.state]}
                  </Badge>
                </CardAction>
              </CardHeader>
              {expandedId === shipment.id ? (
                <CardContent>
                  <ShipmentDetails
                    shipment={shipment}
                    referenceLabels={referenceLabels}
                    linkedReturns={returnsByShipmentId.get(shipment.id) ?? []}
                    linkedReturnsState={
                      returnsQuery.isLoading
                        ? "loading"
                        : returnsQuery.isError
                          ? "unavailable"
                          : "available"
                    }
                    furnitureReadiness={furnitureReadinessByShipmentId.get(
                      shipment.id
                    )}
                    furnitureMovementTasksById={furnitureMovementTasksById}
                  />
                </CardContent>
              ) : null}
              <CardFooter className="flex-wrap gap-2">
                {actions(shipment)}
              </CardFooter>
            </Card>
          ))}
          {rows.length === 0 && !query.isLoading ? (
            <Card size="sm">
              <CardHeader>
                <CardTitle>Отгрузки не найдены</CardTitle>
                <CardDescription>
                  Измените поиск или фильтры. Новые отгрузки создаются из
                  раздела «Задания» после выбора бытовок и даты рейса.
                </CardDescription>
              </CardHeader>
            </Card>
          ) : null}
        </div>
      </div>

      {scheduleTarget && accessToken ? (
        <ShipmentScheduleDialog
          accessToken={accessToken}
          document={scheduleTarget.document}
          referenceLabels={referenceLabels}
          desiredDeliveryWindows={desiredWindowsForShipment(
            referenceLabels,
            scheduleTarget.document
          )}
          futureDateWarning={scheduleTarget.futureDateWarning}
          pending={scheduleMutation.isPending}
          onOpenChange={(open) => !open && setScheduleTarget(null)}
          onSubmit={(input) =>
            scheduleMutation.mutate({
              document: scheduleTarget.document,
              ...input,
            })
          }
        />
      ) : null}
      {shipmentDateDecisionTarget ? (
        <ShipmentDateDecisionDialog
          shipment={shipmentDateDecisionTarget}
          pending={confirmMutation.isPending}
          onOpenChange={(open) => !open && setShipmentDateDecisionTarget(null)}
          onKeepDate={() => {
            const target = shipmentDateDecisionTarget
            setShipmentDateDecisionTarget(null)
            confirmMutation.mutate({
              shipment: target,
              keepScheduledDate: true,
            })
          }}
          onReschedule={() => {
            const target = shipmentDateDecisionTarget
            setShipmentDateDecisionTarget(null)
            setScheduleTarget({ document: target, futureDateWarning: true })
          }}
        />
      ) : null}

      <AlertDialog
        open={cancelTarget !== null}
        onOpenChange={(open) => !open && setCancelTarget(null)}
      >
        <AlertDialogContent>
          <AlertDialogHeader>
            <AlertDialogTitle>Отменить отгрузку?</AlertDialogTitle>
            <AlertDialogDescription>
              Сервис отменит задания, резервы мебели и активные leases этой
              отгрузки.
            </AlertDialogDescription>
          </AlertDialogHeader>
          <AlertDialogFooter>
            <AlertDialogCancel disabled={cancelMutation.isPending}>
              Не отменять
            </AlertDialogCancel>
            <AlertDialogAction
              disabled={cancelMutation.isPending || !cancelTarget}
              onClick={() =>
                cancelTarget && cancelMutation.mutate(cancelTarget)
              }
            >
              {cancelMutation.isPending ? "Отменяется…" : "Отменить"}
            </AlertDialogAction>
          </AlertDialogFooter>
        </AlertDialogContent>
      </AlertDialog>
    </div>
  )
}

function ShipmentDateDecisionDialog({
  shipment,
  pending,
  onOpenChange,
  onKeepDate,
  onReschedule,
}: {
  shipment: ShipmentDocument
  pending: boolean
  onOpenChange: (open: boolean) => void
  onKeepDate: () => void
  onReschedule: () => void
}) {
  return (
    <Dialog open onOpenChange={onOpenChange}>
      <DialogContent>
        <DialogHeader>
          <DialogTitle>Дата отгрузки отличается</DialogTitle>
          <DialogDescription>
            Назначенная дата: {formatDate(shipment.scheduledDate!)}. Сегодня{" "}
            {formatDate(new Date().toISOString().slice(0, 10))}. Изменение
            графика и подтверждение отгрузки выполняются отдельными командами.
          </DialogDescription>
        </DialogHeader>
        <DialogFooter className="flex-col sm:flex-row sm:justify-end">
          <Button
            type="button"
            variant="outline"
            disabled={pending}
            onClick={onKeepDate}
          >
            Оставить назначенную
          </Button>
          <Button type="button" disabled={pending} onClick={onReschedule}>
            Изменить дату отдельно
          </Button>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  )
}

function ShipmentScheduleDialog({
  accessToken,
  document,
  referenceLabels,
  desiredDeliveryWindows,
  futureDateWarning,
  pending,
  onOpenChange,
  onSubmit,
}: {
  accessToken: string
  document: ShipmentDocument
  referenceLabels: LogisticsReferenceLabels
  desiredDeliveryWindows: readonly DesiredDeliveryWindow[]
  futureDateWarning: boolean
  pending: boolean
  onOpenChange: (open: boolean) => void
  onSubmit: (input: {
    driverSnapshot: string
    driverWorkerId: string | null
    scheduledDate: string
  }) => void
}) {
  const [driver, setDriver] = useState<RepairTaskWorkerSnapshotDto | null>(null)
  const [scheduledDate, setScheduledDate] = useState(
    document.scheduledDate ?? ""
  )
  const [error, setError] = useState<string | null>(null)
  const existingDriver = document.driverSnapshot?.trim() || null

  function submit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault()
    setError(null)
    const driverSnapshot = driver?.name.trim() || existingDriver
    if (!driverSnapshot) {
      setError("Выберите водителя.")
      return
    }
    if (!scheduledDate) {
      setError("Укажите дату отгрузки.")
      return
    }
    onSubmit({
      driverSnapshot,
      driverWorkerId: driver?.id ?? document.driverWorkerId,
      scheduledDate,
    })
  }

  return (
    <Dialog open onOpenChange={onOpenChange}>
      <DialogContent className="max-h-[calc(100dvh-2rem)] overflow-y-auto sm:max-w-2xl">
        <form className="flex flex-col gap-4" onSubmit={submit}>
          <DialogHeader>
            <DialogTitle>
              {document.scheduledDate ? "Изменить дату отгрузки" : "Отгрузить"}
            </DialogTitle>
            <DialogDescription>
              {futureDateWarning
                ? "Дата отгрузки ещё не наступила. Измените её на текущую или прошедшую, затем повторите отметку «Отгружена»."
                : "Назначьте водителя и фактическую дату ходки. Пожелания клиента остаются отдельным ориентиром."}
            </DialogDescription>
          </DialogHeader>
          <ShipmentCabinFillingSummaries
            shipment={document}
            referenceLabels={referenceLabels}
          />
          <FieldGroup>
            {existingDriver ? (
              <Field>
                <FieldDescription>
                  Текущий водитель: {existingDriver}. Выберите другого только
                  при необходимости.
                </FieldDescription>
              </Field>
            ) : null}
            <LogisticsDriverPicker
              accessToken={accessToken}
              id="shipment-driver"
              required={!existingDriver}
              value={driver}
              warehouseId={document.warehouseId}
              onChange={setDriver}
            />
            <DesiredTripScheduleFields
              dateLabel="Фактическая дата отгрузки"
              scheduledDate={scheduledDate}
              desiredDeliveryWindows={desiredDeliveryWindows}
              disabled={pending}
              error={error}
              onDateChange={(value) => {
                setScheduledDate(value)
                setError(null)
              }}
            />
          </FieldGroup>
          <DialogFooter>
            <Button
              type="button"
              variant="outline"
              disabled={pending}
              onClick={() => onOpenChange(false)}
            >
              Отмена
            </Button>
            <Button type="submit" disabled={pending}>
              {pending ? "Сохраняем…" : "Сохранить дату"}
            </Button>
          </DialogFooter>
        </form>
      </DialogContent>
    </Dialog>
  )
}

function ShipmentDetails({
  shipment,
  referenceLabels,
  linkedReturns,
  linkedReturnsState,
  furnitureReadiness,
  furnitureMovementTasksById,
}: {
  shipment: ShipmentDocument
  referenceLabels: LogisticsReferenceLabels
  linkedReturns: readonly ReturnDocument[]
  linkedReturnsState: LinkedDocumentReadState
  furnitureReadiness: ShipmentFurnitureReadiness | undefined
  furnitureMovementTasksById: ReadonlyMap<
    string,
    ShipmentFurnitureMovementTaskReference
  >
}) {
  const orderId =
    shipment.rentalOrderId ??
    shipment.lines.find((line) => line.rentalOrderId)?.rentalOrderId ??
    null
  const orderReference = orderId
    ? referenceLabels.orders.get(orderId)
    : undefined
  const order =
    orderReference?.status === "available" ? orderReference.order : null
  const inventoryShipment = shipment.lines.some(
    (line) => line.inventoryShipmentFurniture !== null
  )

  return (
    <div className="grid min-w-0 gap-3">
      {inventoryShipment && !orderId ? (
        <section
          aria-label="Отгрузка по инвентаризации"
          className="grid min-w-0 gap-2 rounded-lg border bg-muted/20 p-3 text-sm"
        >
          <h3 className="font-semibold">Отгрузка по инвентаризации</h3>
          <p className="break-words">
            <span className="text-muted-foreground">Клиент:</span>{" "}
            {shipment.partySnapshot}
          </p>
          <p>
            <span className="text-muted-foreground">Когда уехала:</span>{" "}
            {formatOptionalDate(shipment.scheduledDate)}
          </p>
        </section>
      ) : (
        <OrderCustomerOverview
          order={order}
          orderId={orderId}
          orderNumber={
            orderId ? referenceLabels.orderNumbers.get(orderId) : undefined
          }
          state={orderReference?.status ?? "unavailable"}
        />
      )}
      <ShipmentLines
        shipment={shipment}
        referenceLabels={referenceLabels}
        linkedReturns={linkedReturns}
        linkedReturnsState={linkedReturnsState}
        furnitureReadiness={furnitureReadiness}
        furnitureMovementTasksById={furnitureMovementTasksById}
      />
    </div>
  )
}

function ShipmentCabinFillingSummaries({
  shipment,
  referenceLabels,
}: {
  shipment: ShipmentDocument
  referenceLabels: LogisticsReferenceLabels
}) {
  return (
    <section
      aria-label="Бытовки отгрузки"
      className="grid max-h-72 gap-2 overflow-y-auto pr-1"
    >
      {shipment.lines.map((line) => {
        const orderId = line.rentalOrderId ?? shipment.rentalOrderId
        const orderReference = orderId
          ? referenceLabels.orders.get(orderId)
          : undefined
        const assetReference = referenceLabels.assets.get(line.assetId)
        const desired =
          orderReference?.status === "available"
            ? (orderReference.order.units.find(
                (candidate) => candidate.unit.id === line.assetId
              )?.desiredContents ?? [])
            : []
        const actual =
          assetReference?.status === "available"
            ? assetReference.asset.contentsItems
            : []
        const unavailable =
          assetReference?.status !== "available" ||
          (Boolean(orderId) && orderReference?.status !== "available")

        return (
          <div
            key={line.id}
            className="grid min-w-0 gap-2 rounded-lg border p-3"
          >
            <p className="text-sm font-medium break-words">
              Бытовка {logisticsAssetLabel(referenceLabels, line.assetId)}
            </p>
            {line.inventoryShipmentFurniture !== null ? (
              <InventoryShipmentFurnitureSummary
                furniture={line.inventoryShipmentFurniture}
                actual={actual}
              />
            ) : (
              <CabinFurnitureSummary
                desired={desired}
                actual={actual}
                unavailable={unavailable}
              />
            )}
          </div>
        )
      })}
    </section>
  )
}

function ShipmentLines({
  shipment,
  referenceLabels,
  linkedReturns,
  linkedReturnsState,
  furnitureReadiness,
  furnitureMovementTasksById,
}: {
  shipment: ShipmentDocument
  referenceLabels: LogisticsReferenceLabels
  linkedReturns: readonly ReturnDocument[]
  linkedReturnsState: LinkedDocumentReadState
  furnitureReadiness: ShipmentFurnitureReadiness | undefined
  furnitureMovementTasksById: ReadonlyMap<
    string,
    ShipmentFurnitureMovementTaskReference
  >
}) {
  return (
    <div className="grid gap-2">
      {shipment.lines.map((line) => {
        const orderId = line.rentalOrderId ?? shipment.rentalOrderId
        const assetReference = referenceLabels.assets.get(line.assetId)
        const actualContents =
          assetReference?.status === "available"
            ? assetReference.asset.contentsItems
            : []
        const orderReference = orderId
          ? referenceLabels.orders.get(orderId)
          : undefined
        const desiredUnit =
          orderReference?.status === "available"
            ? orderReference.order.units.find(
                (candidate) => candidate.unit.id === line.assetId
              )
            : undefined
        const desiredContents = desiredUnit?.desiredContents ?? []
        const fillingUnavailable =
          assetReference?.status !== "available" ||
          (Boolean(orderId) && orderReference?.status !== "available")
        const linkedReturn = linkedReturns.find((document) =>
          document.lines.some((candidate) => candidate.assetId === line.assetId)
        )
        const rentalTerm = desiredUnit?.rentalTerm ?? null
        const furnitureTasks = (furnitureReadiness?.tasks ?? []).filter(
          (task) => {
            const movementTask = furnitureMovementTasksById.get(task.taskId)
            if (movementTask?.status !== "available") {
              return task.rentalItemId === line.assetId
            }
            return movementTask.task.lines.some(
              (taskLine) =>
                taskLine.sourceRentalItemId === line.assetId ||
                taskLine.targetRentalItemId === line.assetId
            )
          }
        )

        return (
          <Card key={line.id} size="sm">
            <CardHeader>
              <CardTitle>
                Бытовка {logisticsAssetLabel(referenceLabels, line.assetId)}
              </CardTitle>
              <CardDescription>
                {orderId ? (
                  <Link
                    className="underline-offset-4 hover:underline"
                    to={`/orders/${orderId}`}
                  >
                    Заказ {logisticsOrderLabel(referenceLabels, orderId)}
                  </Link>
                ) : line.inventoryShipmentFurniture !== null ? (
                  "Создано по итогам инвентаризации"
                ) : (
                  "Заказ не указан"
                )}
              </CardDescription>
              <CardAction>
                <Badge variant="outline">Строка {line.lineNumber}</Badge>
              </CardAction>
            </CardHeader>
            <CardContent className="flex flex-col gap-4">
              <div className="grid min-w-0 gap-2 text-sm sm:grid-cols-2">
                <p className="break-words">
                  <span className="text-muted-foreground">Арендатор:</span>{" "}
                  {line.tenantSnapshot ?? shipment.partySnapshot ?? "—"}
                </p>
                <p className="break-words">
                  <span className="text-muted-foreground">Отгрузил:</span>{" "}
                  {shipment.driverSnapshot ?? "Не назначен"}
                </p>
                <p className="break-words">
                  <span className="text-muted-foreground">Привёз:</span>{" "}
                  {linkedReturnDriverLabel(linkedReturn, linkedReturnsState)}
                </p>
              </div>
              {line.inventoryShipmentFurniture !== null ? null : rentalTerm ? (
                <div className="grid gap-1 rounded-lg border bg-muted/20 p-3 text-sm">
                  <p className="font-medium">
                    Срок аренды: {rentalMonthLabel(rentalTerm.rentalMonths)}
                  </p>
                  <p className="text-muted-foreground">
                    Когда уехала: {formatOptionalDate(rentalTerm.shipmentDate)}{" "}
                    · Возврат по сроку:{" "}
                    {formatOptionalDate(rentalTerm.returnDate)}
                  </p>
                </div>
              ) : (
                <p className="text-sm text-muted-foreground">
                  Срок аренды недоступен.
                </p>
              )}
              {line.inventoryShipmentFurniture !== null ? (
                <InventoryShipmentFurnitureSummary
                  furniture={line.inventoryShipmentFurniture}
                  actual={actualContents}
                />
              ) : (
                <CabinFurnitureSummary
                  desired={desiredContents}
                  actual={actualContents}
                  unavailable={fillingUnavailable}
                />
              )}
              {furnitureTasks.length > 0 ? (
                <FurnitureMovementTasks
                  assetId={line.assetId}
                  furnitureTasks={furnitureTasks}
                  referenceLabels={referenceLabels}
                  movementTasksById={furnitureMovementTasksById}
                />
              ) : null}
            </CardContent>
          </Card>
        )
      })}
    </div>
  )
}

type EquipmentLabelItem = {
  equipmentId?: string
  equipmentName?: string | null
  name?: string
  quantity: number
}

function InventoryShipmentFurnitureSummary({
  furniture,
  actual,
}: {
  furniture: readonly InventoryShipmentFurniture[]
  actual: readonly EquipmentLabelItem[]
}) {
  const equipmentNames = new Map(
    actual.map((item) => [
      item.equipmentId,
      item.equipmentName?.trim() || item.name?.trim(),
    ])
  )

  return (
    <section
      aria-label="Мебель, отгруженная по инвентаризации"
      className="grid gap-2 rounded-lg border bg-muted/20 p-3 text-sm"
    >
      <h4 className="font-medium">Отгружено по инвентаризации</h4>
      {furniture.length === 0 ? (
        <p className="text-muted-foreground">Мебель не указана.</p>
      ) : (
        <ul className="grid gap-1">
          {furniture.map((item) => (
            <li key={item.equipmentId} className="break-words">
              {equipmentNames.get(item.equipmentId) ??
                `Оборудование ${item.equipmentId}`}{" "}
              — {item.quantity} шт.
            </li>
          ))}
        </ul>
      )}
    </section>
  )
}

function FurnitureMovementTasks({
  assetId,
  furnitureTasks,
  referenceLabels,
  movementTasksById,
}: {
  assetId: string
  furnitureTasks: readonly ShipmentFurnitureTaskStatus[]
  referenceLabels: LogisticsReferenceLabels
  movementTasksById: ReadonlyMap<string, ShipmentFurnitureMovementTaskReference>
}) {
  return (
    <section className="flex flex-col gap-2">
      <h3 className="text-sm font-medium">Задание на перемещение</h3>
      {furnitureTasks.map((furnitureTask) => {
        const reference = movementTasksById.get(furnitureTask.taskId)
        if (reference?.status === "loading") {
          return (
            <div
              key={furnitureTask.taskId}
              aria-live="polite"
              className="flex flex-col gap-2"
            >
              <Skeleton className="h-4 w-3/4" />
              <Skeleton className="h-4 w-1/2" />
            </div>
          )
        }
        if (reference?.status !== "available") {
          return (
            <p
              key={furnitureTask.taskId}
              className="text-sm text-muted-foreground"
            >
              Строки задания на перемещение недоступны.
            </p>
          )
        }

        const taskLines = reference.task.lines.filter(
          (taskLine) =>
            taskLine.sourceRentalItemId === assetId ||
            taskLine.targetRentalItemId === assetId
        )
        if (taskLines.length === 0) {
          return (
            <p
              key={furnitureTask.taskId}
              className="text-sm text-muted-foreground"
            >
              В задании нет строк для этой бытовки.
            </p>
          )
        }

        return (
          <ul
            key={furnitureTask.taskId}
            aria-label="Строки задания на перемещение"
            className="flex flex-col gap-1 text-sm"
          >
            {taskLines.map((taskLine) => (
              <li
                key={taskLine.id}
                className="flex items-baseline justify-between gap-3"
              >
                <span>
                  {equipmentLabel(taskLine)} ·{" "}
                  {movementLocationLabel(
                    taskLine.sourceLocationKind,
                    taskLine.sourceRentalItemId,
                    referenceLabels
                  )}{" "}
                  →{" "}
                  {movementLocationLabel(
                    taskLine.targetLocationKind,
                    taskLine.targetRentalItemId,
                    referenceLabels
                  )}
                </span>
                <span className="shrink-0 text-muted-foreground">
                  × {taskLine.quantity}
                </span>
              </li>
            ))}
          </ul>
        )
      })}
    </section>
  )
}

function equipmentLabel(item: EquipmentLabelItem) {
  const name = item.equipmentName?.trim() || item.name?.trim() || "Оборудование"
  return name
}

function movementLocationLabel(
  locationKind: "STOCK" | "CABIN_NON_RENTED" | "CABIN_RENTED",
  rentalItemId: string | null,
  referenceLabels: LogisticsReferenceLabels
) {
  if (locationKind === "STOCK") return "Склад"
  if (rentalItemId) {
    return `Бытовка ${logisticsAssetLabel(referenceLabels, rentalItemId)}`
  }
  return locationKind === "CABIN_RENTED" ? "Бытовка в аренде" : "Бытовка"
}
