import { useMemo, useRef, useState, type FormEvent } from "react"
import {
  useMutation,
  useQueries,
  useQuery,
  useQueryClient,
} from "@tanstack/react-query"
import { Link } from "react-router-dom"

import { OperationsListGrid } from "@/components/operations-list-grid"
import {
  PageToolbar,
  PageToolbarActions,
  PageToolbarContent,
} from "@/components/page-toolbar"
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
import { Checkbox } from "@/components/ui/checkbox"
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
  FieldLabel,
} from "@/components/ui/field"
import { Input } from "@/components/ui/input"
import { hasWarehouseAccess } from "@/features/auth/warehouse-access"
import { useAuth } from "@/features/auth/use-auth"
import { createCabinFurnitureTask } from "@/features/logistics/cabin-furniture-tasks-api"
import {
  LogisticsDocumentFilters,
  LogisticsFiltersToggle,
  type LogisticsDocumentFiltersState,
} from "@/features/logistics/logistics-document-filters"
import { LogisticsDriverPicker } from "@/features/logistics/logistics-driver-picker"
import { createOrderShipment } from "@/features/logistics/order-tasks-api"
import {
  RETURNS_QUERY_KEY,
  listReturns,
  registerReturn,
} from "@/features/logistics/returns/api"
import type { ReturnDocument } from "@/features/logistics/returns/model"
import {
  SHIPMENTS_QUERY_KEY,
  confirmShipmentPreparation,
  createShipmentFurnitureTasks,
  getShipmentFurnitureReadiness,
  listShipments,
  replaceShipmentPlan,
} from "@/features/logistics/shipments/api"
import type {
  ShipmentDocument,
  ShipmentFurnitureReadiness,
} from "@/features/logistics/shipments/model"
import {
  logisticsAssetLabel,
  logisticsOrderLabel,
  useLogisticsReferenceLabels,
  type LogisticsReferenceLabels,
} from "@/features/logistics/use-logistics-reference-labels"
import type { OrderDetail, OrderSummary } from "@/features/orders/domain/orders"
import {
  getOrder,
  listOrders,
  ORDERS_QUERY_KEY,
} from "@/features/orders/api/orders-api"
import type { RepairTaskWorkerSnapshotDto } from "@/features/repair-tasks/model/repair-task"
import { RENTAL_ITEM_STATUS_LABEL } from "@/features/rental-items/model/rental-item"
import { useResponsiveFiltersOpen } from "@/hooks/use-responsive-filters-open"
import { useWarehouse } from "@/hooks/use-warehouse"
import { ApiError } from "@/lib/api-client"

type TaskKind = "SHIPMENT" | "RETURN"
type TaskDocument = ShipmentDocument | ReturnDocument
type RentalOrderTask = {
  id: string
  kind: TaskKind
  document: TaskDocument
  /** The opposite movement document for the same rental shipment, when known. */
  linkedShipment?: ShipmentDocument
  linkedReturns?: ReturnDocument[]
  /** A saved order without a shipment yet; it is the actionable order task. */
  virtual?: boolean
  order?: OrderDetail
}

type ShipmentConfirmationCommand = {
  task: RentalOrderTask
  /** Explicitly preserve a planned date when the physical departure is today. */
  keepScheduledDate?: boolean
}

const TASK_STATES = [
  "WAITING_SHIPMENT",
  "IN_PROGRESS_SHIPMENT",
  "SHIPPED",
  "REQUIRES_RETURN",
  "IN_PROGRESS_RETURN",
  "RETURNED",
] as const
type TaskState = (typeof TASK_STATES)[number]

const TASK_STATE_LABELS: Record<TaskState, string> = {
  WAITING_SHIPMENT: "Ожидает отгрузки",
  IN_PROGRESS_SHIPMENT: "В процессе отгрузки",
  SHIPPED: "Отгружено",
  REQUIRES_RETURN: "Требует возврата",
  IN_PROGRESS_RETURN: "Возврат в процессе",
  RETURNED: "Возвращено",
}

type TaskFilters = LogisticsDocumentFiltersState<TaskState> & {
  counterparties: string[]
  drivers: string[]
  kinds: TaskKind[]
}

const EMPTY_FILTERS: TaskFilters = {
  states: [],
  schedule: "ALL",
  dateFrom: "",
  dateTo: "",
  counterparties: [],
  drivers: [],
  kinds: [],
}

const ORDER_TASK_PAGE_SIZE = 100
const ORDER_TASK_FURNITURE_READINESS_QUERY_KEY = [
  "logistics",
  "order-task-furniture-readiness",
] as const

type RentalTermProjection = {
  rentalMonths: number
  shipmentDate: string | null
  returnDate: string | null
}

function commandIdentity() {
  return crypto.randomUUID()
}

function today() {
  return new Date().toISOString().slice(0, 10)
}

function formatDate(value: string | null) {
  if (!value) return "Не назначена"
  return new Intl.DateTimeFormat("ru-RU", { dateStyle: "medium" }).format(
    new Date(`${value}T00:00:00`)
  )
}

function formatDetailDate(value: string | null) {
  if (!value) return "—"
  return formatDate(value)
}

function isDue(value: string | null) {
  return Boolean(value && value <= today())
}

function errorMessage(cause: unknown, fallback: string) {
  return cause instanceof Error ? cause.message : fallback
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

function matchesDateRange(
  value: string | null,
  filters: LogisticsDocumentFiltersState<string>
) {
  if (filters.schedule === "SCHEDULED" && value === null) return false
  if (filters.schedule === "UNSCHEDULED" && value !== null) return false
  if (!value) return !filters.dateFrom && !filters.dateTo
  if (filters.dateFrom && value < filters.dateFrom) return false
  if (filters.dateTo && value > filters.dateTo) return false
  return true
}

function isRentalDocument(document: TaskDocument) {
  return (
    document.rentalOrderId !== null ||
    document.lines.some((line) => line.rentalOrderId !== null)
  )
}

function taskKey(kind: TaskKind, documentId: string) {
  return `${kind}:${documentId}`
}

function pendingOrderShipment(
  order: OrderDetail,
  assignedUnitIds: ReadonlySet<string>
): ShipmentDocument | null {
  if (order.status !== "SAVED" || !order.warehouseId) return null
  const units = order.units.filter(
    (candidate) => candidate.added && !assignedUnitIds.has(candidate.unit.id)
  )
  if (units.length === 0) return null

  return {
    id: `order-task:${order.id}`,
    version: order.version,
    documentType: "SHIPMENT",
    state: "DRAFT",
    warehouseId: order.warehouseId,
    destinationWarehouseId: null,
    partySnapshot: order.client.displayName,
    driverSnapshot: null,
    clientId: order.client.id,
    equipmentMovementTaskId: null,
    scheduledDate: null,
    rentalOrderId: order.id,
    rentalShipmentId: null,
    lines: units.map((candidate, index) => ({
      id: `order-task:${order.id}:line:${candidate.unit.id}`,
      version: candidate.unit.version,
      lineNumber: index + 1,
      assetId: candidate.unit.id,
      assetVersion: candidate.unit.version,
      state: "PENDING",
      tenantSnapshot: order.client.displayName,
      rentalOrderId: order.id,
    })),
    createdAt: order.createdAt,
    updatedAt: order.updatedAt,
  }
}

async function listSavedOrderTasks(
  accessToken: string,
  warehouseId: string
): Promise<OrderSummary[]> {
  const orders: OrderSummary[] = []
  let page = 0
  let totalPages = 1

  while (page < totalPages) {
    const result = await listOrders({
      accessToken,
      page,
      size: ORDER_TASK_PAGE_SIZE,
      sort: "updatedAt",
      direction: "desc",
      statuses: ["SAVED"],
      warehouseIds: [warehouseId],
    })
    orders.push(...result.content)
    totalPages = result.totalPages
    page += 1
  }

  return orders
}

function taskState(
  task: RentalOrderTask,
  referenceLabels?: LogisticsReferenceLabels
): TaskState {
  if (task.kind === "RETURN") {
    const document = task.document as ReturnDocument
    if (document.state === "DRAFT") {
      const order = referenceLabels
        ? orderFromLabels(referenceLabels, document.rentalOrderId)
        : null
      const due = order
        ? document.lines.some((line) => {
            const term = unitTerm(order, line.assetId)
            return term?.returnDate ? isDue(term.returnDate) : false
          })
        : false
      return due ? "REQUIRES_RETURN" : "SHIPPED"
    }
    if (document.state === "ACCEPTED") return "RETURNED"
    return "IN_PROGRESS_RETURN"
  }

  const document = task.document as ShipmentDocument
  if (document.state === "SHIPPED") {
    const order = referenceLabels
      ? orderFromLabels(referenceLabels, document.rentalOrderId)
      : null
    if (
      order &&
      document.lines.some((line) => {
        const term = unitTerm(order, line.assetId)
        return term?.returnDate ? isDue(term.returnDate) : false
      })
    ) {
      return "REQUIRES_RETURN"
    }
    return "SHIPPED"
  }
  if (document.state === "DRAFT") return "WAITING_SHIPMENT"
  return "IN_PROGRESS_SHIPMENT"
}

function stateVariant(state: TaskState) {
  if (state === "REQUIRES_RETURN") return "destructive" as const
  if (state === "SHIPPED" || state === "RETURNED") return "secondary" as const
  return "outline" as const
}

function taskDate(task: RentalOrderTask) {
  return task.document.scheduledDate
}

function orderFromLabels(
  labels: LogisticsReferenceLabels,
  orderId: string | null
): OrderDetail | null {
  if (!orderId) return null
  const reference = labels.orders.get(orderId)
  return reference?.status === "available" ? reference.order : null
}

function unitTerm(order: OrderDetail | null, assetId: string) {
  const candidate = order?.units.find(({ unit }) => unit.id === assetId)
  const value = (
    candidate as (typeof candidate & { rentalTerm?: unknown }) | undefined
  )?.rentalTerm
  if (!value || typeof value !== "object") return null
  const term = value as Record<string, unknown>
  if (
    typeof term.rentalMonths !== "number" ||
    !Number.isSafeInteger(term.rentalMonths) ||
    term.rentalMonths < 1
  ) {
    return null
  }
  return {
    rentalMonths: term.rentalMonths,
    shipmentDate:
      typeof term.shipmentDate === "string" ? term.shipmentDate : null,
    returnDate: typeof term.returnDate === "string" ? term.returnDate : null,
  } satisfies RentalTermProjection
}

export function LogisticsOrderTasksPage() {
  const { selectedWarehouseId } = useWarehouse()
  const { accessToken, currentUser } = useAuth()
  const queryClient = useQueryClient()
  const [search, setSearch] = useState("")
  const [filters, setFilters] = useState(EMPTY_FILTERS)
  const { filtersOpen, setFiltersOpen } = useResponsiveFiltersOpen()
  const [expandedId, setExpandedId] = useState<string | null>(null)
  const [expandedCabinId, setExpandedCabinId] = useState<string | null>(null)
  const [selectedTaskId, setSelectedTaskId] = useState<string | null>(null)
  const [selectedLineIds, setSelectedLineIds] = useState<string[]>([])
  const [scheduleTarget, setScheduleTarget] = useState<RentalOrderTask | null>(
    null
  )
  const [shipmentDateDecisionTarget, setShipmentDateDecisionTarget] =
    useState<RentalOrderTask | null>(null)
  const [commandError, setCommandError] = useState<string | null>(null)
  const [commandNotice, setCommandNotice] = useState<string | null>(null)
  const commandKeys = useRef(new Map<string, string>())

  const shipmentsQuery = useQuery({
    queryKey: [...SHIPMENTS_QUERY_KEY, "order-tasks", selectedWarehouseId],
    queryFn: () => listShipments(accessToken!, selectedWarehouseId!),
    enabled: Boolean(accessToken && selectedWarehouseId),
    refetchInterval: 5_000,
  })
  const returnsQuery = useQuery({
    queryKey: [...RETURNS_QUERY_KEY, "order-tasks", selectedWarehouseId],
    queryFn: () => listReturns(accessToken!, selectedWarehouseId!),
    enabled: Boolean(accessToken && selectedWarehouseId),
    refetchInterval: 5_000,
  })
  const savedOrdersQuery = useQuery({
    queryKey: [...ORDERS_QUERY_KEY, "order-tasks", selectedWarehouseId],
    queryFn: () => listSavedOrderTasks(accessToken!, selectedWarehouseId!),
    enabled: Boolean(accessToken && selectedWarehouseId),
    refetchInterval: 5_000,
  })
  const savedOrderDetailsQueries = useQueries({
    queries: (savedOrdersQuery.data ?? []).map((summary) => ({
      queryKey: [...ORDERS_QUERY_KEY, "order-tasks-detail", summary.id],
      queryFn: () => getOrder(accessToken!, summary.id),
      enabled: Boolean(accessToken),
      refetchInterval: 5_000,
    })),
  })

  const tasks = useMemo<RentalOrderTask[]>(() => {
    const rentalReturns = (returnsQuery.data ?? [])
      .filter(isRentalDocument)
      .filter((document) => document.state !== "CANCELLED")
    const returnShipmentIds = new Set(
      rentalReturns.flatMap((document) =>
        document.rentalShipmentId ? [document.rentalShipmentId] : []
      )
    )
    const allShipments = (shipmentsQuery.data ?? [])
      .filter(isRentalDocument)
      .filter((document) => document.state !== "CANCELLED")
    const shipmentsById = new Map(
      allShipments.map((document) => [document.id, document] as const)
    )
    const returnsByShipmentId = new Map<string, ReturnDocument[]>()
    rentalReturns.forEach((document) => {
      if (!document.rentalShipmentId) return
      const existing = returnsByShipmentId.get(document.rentalShipmentId) ?? []
      returnsByShipmentId.set(document.rentalShipmentId, [
        ...existing,
        document,
      ])
    })
    const shipments = allShipments
      .filter(
        (document) =>
          document.state !== "SHIPPED" || !returnShipmentIds.has(document.id)
      )
      .map((document) => ({
        id: taskKey("SHIPMENT", document.id),
        kind: "SHIPMENT" as const,
        document,
        linkedReturns: returnsByShipmentId.get(document.id) ?? [],
      }))
    const returns = rentalReturns.map((document) => ({
      id: taskKey("RETURN", document.id),
      kind: "RETURN" as const,
      document,
      linkedShipment: document.rentalShipmentId
        ? shipmentsById.get(document.rentalShipmentId)
        : undefined,
    }))
    const assignedUnitIds = new Set(
      allShipments.flatMap((document) =>
        document.state === "CANCELLED"
          ? []
          : document.lines.map((line) => line.assetId)
      )
    )
    const pendingOrders = savedOrderDetailsQueries.flatMap((query) => {
      const document = query.data
        ? pendingOrderShipment(query.data, assignedUnitIds)
        : null
      return document
        ? [
            {
              id: taskKey("SHIPMENT", document.id),
              kind: "SHIPMENT" as const,
              document,
              virtual: true,
              order: query.data,
            },
          ]
        : []
    })
    return [...shipments, ...returns, ...pendingOrders].sort((left, right) =>
      right.document.updatedAt.localeCompare(left.document.updatedAt)
    )
  }, [returnsQuery.data, savedOrderDetailsQueries, shipmentsQuery.data])

  const referenceLabels = useLogisticsReferenceLabels(
    accessToken,
    tasks.map(({ document }) => document)
  )
  const furnitureReadinessTasks = useMemo(
    () =>
      tasks.filter(
        (task) =>
          task.kind === "SHIPMENT" &&
          !task.virtual &&
          ["DRAFT", "PREPARING", "AWAITING_CONFIRMATION"].includes(
            task.document.state
          )
      ),
    [tasks]
  )
  const furnitureReadinessQueries = useQueries({
    queries: furnitureReadinessTasks.map((task) => ({
      queryKey: [
        ...ORDER_TASK_FURNITURE_READINESS_QUERY_KEY,
        task.id,
        task.document.version,
      ],
      queryFn: () =>
        getShipmentFurnitureReadiness(accessToken!, task.document.id),
      enabled: Boolean(accessToken),
      refetchInterval: 3_000,
    })),
  })
  const furnitureReadinessByTaskId = useMemo(
    () =>
      new Map(
        furnitureReadinessTasks.flatMap((task, index) => {
          const readiness = furnitureReadinessQueries[index]?.data
          return readiness ? ([[task.id, readiness]] as const) : []
        })
      ),
    [furnitureReadinessQueries, furnitureReadinessTasks]
  )
  const furnitureReadinessQueryByTaskId = useMemo(
    () =>
      new Map(
        furnitureReadinessTasks.map((task, index) => [
          task.id,
          furnitureReadinessQueries[index],
        ])
      ),
    [furnitureReadinessQueries, furnitureReadinessTasks]
  )

  const stateOptions = useMemo(
    () =>
      TASK_STATES.map((state) => ({
        value: state,
        label: TASK_STATE_LABELS[state],
      })),
    []
  )
  const counterpartyOptions = useMemo(
    () => textFilterOptions(tasks.map((task) => task.document.partySnapshot)),
    [tasks]
  )
  const driverOptions = useMemo(
    () => textFilterOptions(tasks.map((task) => task.document.driverSnapshot)),
    [tasks]
  )
  const rows = useMemo(() => {
    const needle = search.trim().toLocaleLowerCase("ru")
    return tasks.filter((task) => {
      const state = taskState(task, referenceLabels)
      if (filters.states.length > 0 && !filters.states.includes(state)) {
        return false
      }
      if (filters.kinds.length > 0 && !filters.kinds.includes(task.kind)) {
        return false
      }
      if (
        filters.counterparties.length > 0 &&
        !filters.counterparties.includes(task.document.partySnapshot ?? "")
      ) {
        return false
      }
      if (
        filters.drivers.length > 0 &&
        !filters.drivers.includes(task.document.driverSnapshot ?? "")
      ) {
        return false
      }
      if (!matchesDateRange(taskDate(task), filters)) return false
      if (!needle) return true
      const orderId = task.document.rentalOrderId
      return [
        task.document.id,
        task.document.partySnapshot,
        task.document.driverSnapshot,
        orderId,
        orderId ? referenceLabels.orderNumbers.get(orderId) : null,
        TASK_STATE_LABELS[state],
        task.kind === "SHIPMENT" ? "Отгрузка" : "Возврат",
        ...task.document.lines.flatMap((line) => [
          line.id,
          line.assetId,
          referenceLabels.assetNumbers.get(line.assetId),
          line.tenantSnapshot,
        ]),
      ]
        .filter(Boolean)
        .some((value) => String(value).toLocaleLowerCase("ru").includes(needle))
    })
  }, [filters, referenceLabels, search, tasks])

  const selectedTask = tasks.find((task) => task.id === selectedTaskId) ?? null
  function keyFor(signature: string) {
    const existing = commandKeys.current.get(signature)
    if (existing) return existing
    const key = commandIdentity()
    commandKeys.current.set(signature, key)
    return key
  }

  function toggleLine(task: RentalOrderTask, lineId: string, checked: boolean) {
    if (selectedTaskId !== task.id) {
      setSelectedTaskId(task.id)
      setSelectedLineIds(checked ? [lineId] : [])
      return
    }
    setSelectedLineIds((current) =>
      checked
        ? [...new Set([...current, lineId])]
        : current.filter((candidate) => candidate !== lineId)
    )
  }

  function clearSelection() {
    setSelectedTaskId(null)
    setSelectedLineIds([])
  }

  const shipmentCreateMutation = useMutation({
    mutationFn: ({
      task,
      driverSnapshot,
      scheduledDate,
    }: {
      task: RentalOrderTask
      driverSnapshot: string
      scheduledDate: string
    }) => {
      if (task.kind !== "SHIPMENT" || !task.document.rentalOrderId) {
        throw new Error("Задание не связано с заказом")
      }
      const order =
        task.order ??
        orderFromLabels(referenceLabels, task.document.rentalOrderId)
      if (!order) throw new Error("Не удалось загрузить версию заказа")
      const unitIds = task.document.lines
        .filter((line) => selectedLineIds.includes(line.id))
        .map((line) => line.assetId)
      if (unitIds.length === 0) throw new Error("Выберите хотя бы одну бытовку")
      const signature = `order-shipment:${order.id}:${order.version}:${unitIds.join(",")}:${driverSnapshot}:${scheduledDate}`
      return createOrderShipment({
        accessToken: accessToken!,
        orderId: order.id,
        expectedVersion: order.version,
        driverSnapshot,
        scheduledDate,
        unitIds,
        idempotencyKey: keyFor(signature),
      })
    },
    onSuccess: (shipment, variables) => {
      setScheduleTarget(null)
      setCommandError(null)
      setCommandNotice("Отгрузка создана. Проверяем наполнение бытовок…")
      clearSelection()
      void queryClient.invalidateQueries({ queryKey: SHIPMENTS_QUERY_KEY })
      furnitureTasksMutation.mutate({
        shipment,
        driverSnapshot: variables.driverSnapshot,
        scheduledDate: variables.scheduledDate,
      })
    },
    onError: (cause) => {
      setCommandError(errorMessage(cause, "Не удалось создать отгрузку"))
    },
  })

  const planMutation = useMutation({
    mutationFn: ({
      shipment,
      driverSnapshot,
      scheduledDate,
    }: {
      shipment: ShipmentDocument
      driverSnapshot: string
      scheduledDate: string
    }) => {
      const signature = `order-plan:${shipment.id}:${shipment.version}:${driverSnapshot}:${scheduledDate}`
      return replaceShipmentPlan({
        accessToken: accessToken!,
        documentId: shipment.id,
        expectedVersion: shipment.version,
        driverSnapshot,
        scheduledDate,
        idempotencyKey: keyFor(signature),
      })
    },
    onSuccess: (shipment) => {
      setCommandError(null)
      setCommandNotice(
        shipment.state === "PREPARING"
          ? "Подготовка отгрузки запущена. После выполнения задач появится «Готово к отгрузке»."
          : "Отгрузка запланирована."
      )
      void queryClient.invalidateQueries({ queryKey: SHIPMENTS_QUERY_KEY })
      void queryClient.invalidateQueries({
        queryKey: ORDER_TASK_FURNITURE_READINESS_QUERY_KEY,
      })
    },
    onError: (cause, variables) => {
      if (cause instanceof ApiError && cause.status === 409) {
        commandKeys.current.delete(
          `order-plan:${variables.shipment.id}:${variables.shipment.version}:${variables.driverSnapshot}:${variables.scheduledDate}`
        )
        void queryClient.invalidateQueries({ queryKey: SHIPMENTS_QUERY_KEY })
      }
      setCommandError(
        errorMessage(cause, "Не удалось запустить подготовку отгрузки")
      )
    },
  })

  const furnitureTasksMutation = useMutation({
    mutationFn: ({
      shipment,
    }: {
      shipment: ShipmentDocument
      driverSnapshot?: string
      scheduledDate?: string
    }) => {
      const signature = `order-furniture:${shipment.id}:${shipment.version}`
      return createShipmentFurnitureTasks({
        accessToken: accessToken!,
        documentId: shipment.id,
        expectedVersion: shipment.version,
        idempotencyKey: keyFor(signature),
      })
    },
    onSuccess: (result, variables) => {
      const createdTaskCount = result.tasks.filter(
        (task) => task.taskId !== null
      ).length
      const driverSnapshot =
        variables.driverSnapshot ?? variables.shipment.driverSnapshot
      const scheduledDate =
        variables.scheduledDate ?? variables.shipment.scheduledDate
      setCommandError(null)
      setCommandNotice(
        createdTaskCount > 0
          ? `Создано заданий на мебель: ${createdTaskCount}. После их выполнения нажмите «Готово к отгрузке».`
          : "Мебель уже соответствует заказу. Запускаем подготовку отгрузки…"
      )
      void queryClient.invalidateQueries({ queryKey: SHIPMENTS_QUERY_KEY })
      void queryClient.invalidateQueries({
        queryKey: ORDER_TASK_FURNITURE_READINESS_QUERY_KEY,
      })

      // If the cabin has no furniture delta, the worker gate is already clear and
      // preparation can start immediately. Otherwise leave the document in DRAFT
      // until the worker task reaches COMPLETED; the row action then calls planMutation.
      if (createdTaskCount === 0 && driverSnapshot && scheduledDate) {
        planMutation.mutate({
          shipment: {
            ...variables.shipment,
            version: result.shipmentVersion,
          },
          driverSnapshot,
          scheduledDate,
        })
      }
    },
    onError: (cause) => {
      setCommandError(
        errorMessage(cause, "Не удалось создать задачу на мебель")
      )
    },
  })

  const returnMutation = useMutation({
    mutationFn: ({
      task,
      driverSnapshot,
      scheduledDate,
    }: {
      task: RentalOrderTask
      driverSnapshot: string
      scheduledDate: string
    }) => {
      if (task.kind !== "RETURN") throw new Error("Это не задание возврата")
      const signature = `rental-return:${task.document.id}:${task.document.version}:${driverSnapshot}:${scheduledDate}`
      return registerReturn({
        accessToken: accessToken!,
        documentId: task.document.id,
        expectedVersion: task.document.version,
        driverSnapshot,
        scheduledDate,
        idempotencyKey: keyFor(signature),
      })
    },
    onSuccess: () => {
      setScheduleTarget(null)
      setCommandError(null)
      setCommandNotice("Возврат поставлен в логистическое задание.")
      clearSelection()
      void queryClient.invalidateQueries({ queryKey: RETURNS_QUERY_KEY })
    },
    onError: (cause) => {
      setCommandError(errorMessage(cause, "Не удалось создать возврат"))
    },
  })

  const confirmMutation = useMutation({
    mutationFn: ({
      task,
      keepScheduledDate = false,
    }: ShipmentConfirmationCommand) => {
      if (task.kind !== "SHIPMENT") throw new Error("Это не задание отгрузки")
      const signature = `order-confirm:${task.document.id}:${task.document.version}:${keepScheduledDate ? "keep" : "scheduled"}`
      return confirmShipmentPreparation({
        accessToken: accessToken!,
        documentId: task.document.id,
        expectedVersion: task.document.version,
        idempotencyKey: keyFor(signature),
        ...(keepScheduledDate ? { keepScheduledDate: true } : {}),
      })
    },
    onSuccess: () => {
      setShipmentDateDecisionTarget(null)
      setCommandError(null)
      setCommandNotice("Задание отмечено как отгруженное.")
      void queryClient.invalidateQueries({ queryKey: SHIPMENTS_QUERY_KEY })
      void queryClient.invalidateQueries({ queryKey: RETURNS_QUERY_KEY })
    },
    onError: (cause, { task, keepScheduledDate = false }) => {
      if (cause instanceof ApiError && cause.status === 409) {
        commandKeys.current.delete(
          `order-confirm:${task.document.id}:${task.document.version}:${keepScheduledDate ? "keep" : "scheduled"}`
        )
      }
      setCommandError(
        errorMessage(cause, "Не удалось завершить подготовку отгрузки")
      )
    },
  })

  const rescheduleAndConfirmMutation = useMutation({
    mutationFn: async (task: RentalOrderTask) => {
      if (task.kind !== "SHIPMENT") {
        throw new Error("Это не задание отгрузки")
      }
      const shipment = task.document as ShipmentDocument
      if (!shipment.driverSnapshot) {
        throw new Error("Для отгрузки не указан водитель")
      }
      const scheduledDate = today()
      const planSignature = `order-plan:${shipment.id}:${shipment.version}:${shipment.driverSnapshot}:${scheduledDate}`
      const planned = await replaceShipmentPlan({
        accessToken: accessToken!,
        documentId: shipment.id,
        expectedVersion: shipment.version,
        driverSnapshot: shipment.driverSnapshot,
        scheduledDate,
        idempotencyKey: keyFor(planSignature),
      })
      const confirmSignature = `order-confirm:${planned.id}:${planned.version}`
      return confirmShipmentPreparation({
        accessToken: accessToken!,
        documentId: planned.id,
        expectedVersion: planned.version,
        idempotencyKey: keyFor(confirmSignature),
      })
    },
    onSuccess: () => {
      setShipmentDateDecisionTarget(null)
      setCommandError(null)
      setCommandNotice(
        "Дата отгрузки изменена на сегодня, бытовка отмечена как отгруженная."
      )
      void queryClient.invalidateQueries({ queryKey: SHIPMENTS_QUERY_KEY })
      void queryClient.invalidateQueries({ queryKey: RETURNS_QUERY_KEY })
      void queryClient.invalidateQueries({ queryKey: ORDERS_QUERY_KEY })
    },
    onError: (cause) => {
      setCommandError(
        errorMessage(cause, "Не удалось изменить дату и завершить отгрузку")
      )
    },
  })

  const clearContentsMutation = useMutation({
    mutationFn: ({
      task,
      lineId,
    }: {
      task: RentalOrderTask
      lineId: string
    }) => {
      const line = task.document.lines.find(
        (candidate) => candidate.id === lineId
      )
      if (!line) throw new Error("Строка бытовки не найдена")
      return createCabinFurnitureTask({
        accessToken: accessToken!,
        warehouseId: task.document.warehouseId,
        rentalItemId: line.assetId,
        scheduledDate: today(),
        contents: [],
        idempotencyKey: keyFor(
          `return-furniture:${task.document.id}:${line.id}`
        ),
      })
    },
    onSuccess: (result) => {
      setCommandError(null)
      setCommandNotice(
        result.taskId
          ? "Задание на освобождение наполнения создано на доске рабочих."
          : "Наполнение уже пустое."
      )
    },
    onError: (cause) => {
      setCommandError(
        errorMessage(cause, "Не удалось создать задачу на мебель")
      )
    },
  })

  function requestShipmentConfirmation(task: RentalOrderTask) {
    if (task.kind !== "SHIPMENT") return
    const shipment = task.document as ShipmentDocument
    const scheduledDate = shipment.scheduledDate
    if (!scheduledDate || scheduledDate !== today()) {
      setShipmentDateDecisionTarget(task)
      return
    }
    confirmMutation.mutate({ task })
  }

  function scheduleReturnForLine(task: RentalOrderTask, lineId: string) {
    if (task.kind !== "RETURN") return
    if (task.document.state !== "DRAFT") {
      setCommandError("Возврат уже запущен или завершён.")
      return
    }
    if (task.document.lines.length !== 1) {
      setCommandError(
        "Для старого задания с несколькими бытовками сначала разделите возвраты. Новые задания создаются отдельно на каждую бытовку."
      )
      return
    }
    setSelectedTaskId(task.id)
    setSelectedLineIds([lineId])
    setScheduleTarget(task)
  }

  function requestFurnitureTasks(task: RentalOrderTask) {
    if (task.kind !== "SHIPMENT" || task.virtual) return
    furnitureTasksMutation.mutate({
      shipment: task.document as ShipmentDocument,
    })
  }

  function planShipmentTask(task: RentalOrderTask) {
    if (task.kind !== "SHIPMENT" || task.virtual) return
    const shipment = task.document as ShipmentDocument
    if (!shipment.driverSnapshot || !shipment.scheduledDate) {
      setCommandError(
        "Для отгрузки не указаны водитель и дата. Создайте новую отгрузку из выбранных бытовок."
      )
      return
    }
    planMutation.mutate({
      shipment,
      driverSnapshot: shipment.driverSnapshot,
      scheduledDate: shipment.scheduledDate,
    })
  }

  function submitSelectedTask(input: {
    driverSnapshot: string
    scheduledDate: string
  }) {
    if (!selectedTask || selectedLineIds.length === 0) return
    if (selectedTask.kind === "RETURN") {
      if (
        selectedTask.document.lines.length !== 1 ||
        selectedLineIds.length !== 1
      ) {
        setCommandError("Возврат создаётся для всех бытовок этого задания.")
        return
      }
      returnMutation.mutate({ task: selectedTask, ...input })
      return
    }
    shipmentCreateMutation.mutate({ task: selectedTask, ...input })
  }

  const commandPending =
    shipmentCreateMutation.isPending ||
    planMutation.isPending ||
    furnitureTasksMutation.isPending ||
    returnMutation.isPending ||
    confirmMutation.isPending ||
    rescheduleAndConfirmMutation.isPending ||
    clearContentsMutation.isPending

  const canSubmitSelectedTask =
    selectedTask !== null &&
    (selectedTask.kind === "SHIPMENT"
      ? selectedTask.virtual === true
      : selectedTask.document.state === "DRAFT")

  return (
    <div className="flex h-full min-h-0 flex-col gap-4 overflow-hidden">
      <PageToolbar>
        <PageToolbarContent className="max-w-xl">
          <Input
            aria-label="Поиск заданий"
            placeholder="Заказ, бытовка, контрагент или водитель"
            value={search}
            onChange={(event) => setSearch(event.target.value)}
          />
        </PageToolbarContent>
        <PageToolbarActions>
          <LogisticsFiltersToggle
            open={filtersOpen}
            controls="logistics-order-task-filters"
            onOpenChange={setFiltersOpen}
          />
        </PageToolbarActions>
      </PageToolbar>

      <div id="logistics-order-task-filters" hidden={!filtersOpen}>
        <LogisticsDocumentFilters
          filters={filters}
          stateOptions={stateOptions}
          dateLabel="Дата задания"
          extraFilters={[
            {
              label: "Тип",
              options: [
                { value: "SHIPMENT", label: "Отгрузка" },
                { value: "RETURN", label: "Возврат" },
              ],
              selected: filters.kinds,
              onApply: (kinds) =>
                setFilters((current) => ({
                  ...current,
                  kinds: kinds as TaskKind[],
                })),
            },
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
          onChange={(next) =>
            setFilters((current) => ({ ...current, ...next }))
          }
          onReset={() => setFilters(EMPTY_FILTERS)}
        />
      </div>

      {!accessToken ? (
        <FieldError>Для просмотра заданий требуется авторизация.</FieldError>
      ) : null}
      {!selectedWarehouseId ? (
        <FieldError>Выберите склад для просмотра заданий.</FieldError>
      ) : null}
      {shipmentsQuery.error || returnsQuery.error || savedOrdersQuery.error ? (
        <FieldError>
          {errorMessage(
            shipmentsQuery.error ??
              returnsQuery.error ??
              savedOrdersQuery.error,
            "Не удалось загрузить задания"
          )}
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
            renderExpandedRow={(task) => (
              <div className="grid gap-2 pr-2">
                <RentalOrderTaskLines
                  task={task}
                  referenceLabels={referenceLabels}
                  canEdit={hasWarehouseAccess(
                    currentUser,
                    task.document.warehouseId,
                    "EDIT"
                  )}
                  selectedLineIds={
                    selectedTaskId === task.id ? selectedLineIds : []
                  }
                  expandedCabinId={expandedCabinId}
                  onToggleLine={(lineId, checked) =>
                    toggleLine(task, lineId, checked)
                  }
                  onToggleCabin={(lineId) =>
                    setExpandedCabinId((current) =>
                      current === lineId ? null : lineId
                    )
                  }
                  onCreateContentsTask={(lineId) =>
                    clearContentsMutation.mutate({ task, lineId })
                  }
                  onScheduleReturn={(lineId) =>
                    scheduleReturnForLine(task, lineId)
                  }
                />
                {selectedTask?.id === task.id &&
                selectedLineIds.length > 0 &&
                canSubmitSelectedTask ? (
                  <SelectedCabinsActions
                    task={task}
                    selectedLineCount={selectedLineIds.length}
                    pending={commandPending}
                    hasAccessToken={Boolean(accessToken)}
                    onSchedule={() => setScheduleTarget(task)}
                    onClear={clearSelection}
                  />
                ) : null}
              </div>
            )}
            columns={[
              {
                id: "date",
                label: "Дата",
                className: "w-44",
                getSortValue: taskDate,
                render: (task) => formatDate(taskDate(task)),
              },
              {
                id: "kind",
                label: "Тип",
                className: "w-36",
                getSortValue: (task) => task.kind,
                render: (task) =>
                  task.kind === "SHIPMENT" ? "Отгрузка" : "Возврат",
              },
              {
                id: "order",
                label: "Заказ",
                className: "min-w-40",
                getSortValue: (task) => task.document.rentalOrderId ?? "",
                render: (task) =>
                  task.document.rentalOrderId ? (
                    <Link
                      className="underline-offset-4 hover:underline"
                      to={`/orders/${task.document.rentalOrderId}`}
                    >
                      {logisticsOrderLabel(
                        referenceLabels,
                        task.document.rentalOrderId
                      )}
                    </Link>
                  ) : (
                    "Без заказа"
                  ),
              },
              {
                id: "party",
                label: "Контрагент",
                className: "min-w-52",
                getSortValue: (task) => task.document.partySnapshot ?? "",
                render: (task) => task.document.partySnapshot ?? "Не указан",
              },
              {
                id: "status",
                label: "Статус",
                className: "w-52",
                getSortValue: taskState,
                render: (task) => {
                  const state = taskState(task, referenceLabels)
                  return (
                    <Badge variant={stateVariant(state)}>
                      {TASK_STATE_LABELS[state]}
                    </Badge>
                  )
                },
              },
              {
                id: "cabins",
                label: "Бытовки",
                className: "w-24",
                getSortValue: (task) => task.document.lines.length,
                render: (task) => task.document.lines.length,
              },
              {
                id: "actions",
                label: "Действия",
                className: "min-w-80",
                getSortValue: (task) => task.document.updatedAt,
                render: (task) => (
                  <TaskActions
                    task={task}
                    expanded={expandedId === task.id}
                    canEdit={hasWarehouseAccess(
                      currentUser,
                      task.document.warehouseId,
                      "EDIT"
                    )}
                    readiness={
                      task.kind === "SHIPMENT"
                        ? furnitureReadinessByTaskId.get(task.id)
                        : undefined
                    }
                    readinessPending={
                      task.kind === "SHIPMENT"
                        ? furnitureReadinessQueryByTaskId.get(task.id)
                            ?.isPending ||
                          furnitureReadinessQueryByTaskId.get(task.id)
                            ?.isFetching
                        : false
                    }
                    pending={commandPending}
                    onToggle={() =>
                      setExpandedId((current) =>
                        current === task.id ? null : task.id
                      )
                    }
                    onConfirm={() => requestShipmentConfirmation(task)}
                    onPlan={() => planShipmentTask(task)}
                    onCreateFurniture={() => requestFurnitureTasks(task)}
                  />
                ),
              },
            ]}
          />
        </div>

        <div className="grid gap-3 md:hidden">
          {rows.map((task) => (
            <Card key={task.id} size="sm">
              <CardHeader>
                <CardTitle>
                  {task.kind === "SHIPMENT" ? "Отгрузка" : "Возврат"} ·{" "}
                  {task.document.partySnapshot ?? "Заказ"}
                </CardTitle>
                <CardDescription>{formatDate(taskDate(task))}</CardDescription>
                <CardAction>
                  <Badge
                    variant={stateVariant(taskState(task, referenceLabels))}
                  >
                    {TASK_STATE_LABELS[taskState(task, referenceLabels)]}
                  </Badge>
                </CardAction>
              </CardHeader>
              {expandedId === task.id ? (
                <CardContent className="grid gap-2">
                  <RentalOrderTaskLines
                    task={task}
                    referenceLabels={referenceLabels}
                    canEdit={hasWarehouseAccess(
                      currentUser,
                      task.document.warehouseId,
                      "EDIT"
                    )}
                    selectedLineIds={
                      selectedTaskId === task.id ? selectedLineIds : []
                    }
                    expandedCabinId={expandedCabinId}
                    onToggleLine={(lineId, checked) =>
                      toggleLine(task, lineId, checked)
                    }
                    onToggleCabin={(lineId) =>
                      setExpandedCabinId((current) =>
                        current === lineId ? null : lineId
                      )
                    }
                    onCreateContentsTask={(lineId) =>
                      clearContentsMutation.mutate({ task, lineId })
                    }
                    onScheduleReturn={(lineId) =>
                      scheduleReturnForLine(task, lineId)
                    }
                  />
                  {selectedTask?.id === task.id &&
                  selectedLineIds.length > 0 &&
                  canSubmitSelectedTask ? (
                    <SelectedCabinsActions
                      task={task}
                      selectedLineCount={selectedLineIds.length}
                      pending={commandPending}
                      hasAccessToken={Boolean(accessToken)}
                      onSchedule={() => setScheduleTarget(task)}
                      onClear={clearSelection}
                    />
                  ) : null}
                </CardContent>
              ) : null}
              <CardFooter className="flex-wrap gap-2">
                <TaskActions
                  task={task}
                  expanded={expandedId === task.id}
                  canEdit={hasWarehouseAccess(
                    currentUser,
                    task.document.warehouseId,
                    "EDIT"
                  )}
                  readiness={
                    task.kind === "SHIPMENT"
                      ? furnitureReadinessByTaskId.get(task.id)
                      : undefined
                  }
                  readinessPending={
                    task.kind === "SHIPMENT"
                      ? furnitureReadinessQueryByTaskId.get(task.id)
                          ?.isPending ||
                        furnitureReadinessQueryByTaskId.get(task.id)?.isFetching
                      : false
                  }
                  pending={commandPending}
                  onToggle={() =>
                    setExpandedId((current) =>
                      current === task.id ? null : task.id
                    )
                  }
                  onConfirm={() => requestShipmentConfirmation(task)}
                  onPlan={() => planShipmentTask(task)}
                  onCreateFurniture={() => requestFurnitureTasks(task)}
                />
              </CardFooter>
            </Card>
          ))}
          {rows.length === 0 &&
          !shipmentsQuery.isLoading &&
          !returnsQuery.isLoading ? (
            <Card size="sm">
              <CardHeader>
                <CardTitle>Задания не найдены</CardTitle>
                <CardDescription>Измените поиск или фильтры.</CardDescription>
              </CardHeader>
            </Card>
          ) : null}
        </div>
      </div>

      {scheduleTarget && accessToken ? (
        <TaskScheduleDialog
          accessToken={accessToken}
          task={scheduleTarget}
          pending={shipmentCreateMutation.isPending || returnMutation.isPending}
          onOpenChange={(open) => !open && setScheduleTarget(null)}
          onSubmit={submitSelectedTask}
        />
      ) : null}
      {shipmentDateDecisionTarget ? (
        <ShipmentDateDecisionDialog
          task={shipmentDateDecisionTarget}
          pending={
            confirmMutation.isPending || rescheduleAndConfirmMutation.isPending
          }
          onOpenChange={(open) => !open && setShipmentDateDecisionTarget(null)}
          onKeepDate={() => {
            const target = shipmentDateDecisionTarget
            setShipmentDateDecisionTarget(null)
            confirmMutation.mutate({ task: target, keepScheduledDate: true })
          }}
          onUseToday={() =>
            rescheduleAndConfirmMutation.mutate(shipmentDateDecisionTarget)
          }
        />
      ) : null}
    </div>
  )
}

function TaskActions({
  task,
  expanded,
  canEdit,
  readiness,
  readinessPending,
  pending,
  onToggle,
  onConfirm,
  onPlan,
  onCreateFurniture,
}: {
  task: RentalOrderTask
  expanded: boolean
  canEdit: boolean
  readiness: ShipmentFurnitureReadiness | undefined
  readinessPending: boolean | undefined
  pending: boolean
  onToggle: () => void
  onConfirm: () => void
  onPlan: () => void
  onCreateFurniture: () => void
}) {
  const shipment =
    task.kind === "SHIPMENT" ? (task.document as ShipmentDocument) : null
  const furnitureReady =
    readiness?.state === "READY" || readiness?.state === "NOT_REQUIRED"
  const furnitureWaiting = readiness?.state === "AWAITING_TASK_COMPLETION"
  const furnitureBlocked = readiness?.state === "BLOCKED"
  const furnitureTaskCreationNeeded =
    readiness?.state === "REQUIRES_TASK_CREATION"
  return (
    <div className="flex flex-wrap gap-2">
      <Button type="button" size="sm" variant="outline" onClick={onToggle}>
        {expanded ? "Скрыть бытовки" : "Показать бытовки"}
      </Button>
      {canEdit && shipment?.state === "DRAFT" && !task.virtual ? (
        furnitureTaskCreationNeeded ? (
          <Button
            type="button"
            size="sm"
            variant="outline"
            disabled={pending}
            onClick={onCreateFurniture}
          >
            {pending ? "Создаём задачу…" : "Создать задачу на мебель"}
          </Button>
        ) : furnitureWaiting ? (
          <Button type="button" size="sm" variant="outline" disabled>
            Ожидает выполнения мебели
          </Button>
        ) : furnitureBlocked ? (
          <Badge variant="destructive">Задача на мебель требует внимания</Badge>
        ) : furnitureReady ? (
          <Button type="button" size="sm" disabled={pending} onClick={onPlan}>
            {pending ? "Запускаем…" : "Готово к отгрузке"}
          </Button>
        ) : (
          <Button type="button" size="sm" variant="outline" disabled>
            {readinessPending ? "Проверяем мебель…" : "Ожидаем проверки мебели"}
          </Button>
        )
      ) : null}
      {canEdit && shipment?.state === "AWAITING_CONFIRMATION" ? (
        <Button
          type="button"
          size="sm"
          disabled={
            pending ||
            readinessPending ||
            !furnitureReady ||
            furnitureWaiting ||
            furnitureBlocked
          }
          onClick={onConfirm}
        >
          {pending ? "Проверяем…" : "Готово к отгрузке"}
        </Button>
      ) : null}
      {shipment?.state === "SHIPPED" ? (
        <Badge variant="secondary">Отгружено</Badge>
      ) : null}
    </div>
  )
}

function SelectedCabinsActions({
  task,
  selectedLineCount,
  pending,
  hasAccessToken,
  onSchedule,
  onClear,
}: {
  task: RentalOrderTask
  selectedLineCount: number
  pending: boolean
  hasAccessToken: boolean
  onSchedule: () => void
  onClear: () => void
}) {
  return (
    <section
      aria-label="Действия с выбранными бытовками"
      className="flex flex-wrap items-center gap-3 rounded-lg border bg-muted/40 p-3"
    >
      <div className="mr-auto text-sm">
        Выбрано бытовок: <strong>{selectedLineCount}</strong>
        {task.kind === "RETURN" &&
        selectedLineCount !== task.document.lines.length ? (
          <span className="ml-2 text-muted-foreground">
            Для возврата выберите все бытовки задания.
          </span>
        ) : null}
      </div>
      <Button
        type="button"
        variant="secondary"
        disabled={pending || !hasAccessToken}
        onClick={onSchedule}
      >
        {task.kind === "SHIPMENT" ? "Создать отгрузку" : "Возврат"}
      </Button>
      <Button
        type="button"
        variant="ghost"
        disabled={pending}
        onClick={onClear}
      >
        Снять выбор
      </Button>
    </section>
  )
}

function RentalOrderTaskLines({
  task,
  referenceLabels,
  canEdit,
  selectedLineIds,
  expandedCabinId,
  onToggleLine,
  onToggleCabin,
  onCreateContentsTask,
  onScheduleReturn,
}: {
  task: RentalOrderTask
  referenceLabels: LogisticsReferenceLabels
  canEdit: boolean
  selectedLineIds: readonly string[]
  expandedCabinId: string | null
  onToggleLine: (lineId: string, checked: boolean) => void
  onToggleCabin: (lineId: string) => void
  onCreateContentsTask: (lineId: string) => void
  onScheduleReturn: (lineId: string) => void
}) {
  const shipmentDocument =
    task.kind === "SHIPMENT"
      ? (task.document as ShipmentDocument)
      : task.linkedShipment
  const returnDocuments =
    task.kind === "RETURN"
      ? [task.document as ReturnDocument]
      : (task.linkedReturns ?? [])
  const orderId =
    task.document.rentalOrderId ??
    shipmentDocument?.rentalOrderId ??
    returnDocuments[0]?.rentalOrderId ??
    null
  const order = task.order ?? orderFromLabels(referenceLabels, orderId)
  return (
    <div className="grid gap-2">
      {task.document.lines.map((line) => {
        const assetReference = referenceLabels.assets.get(line.assetId)
        const asset =
          assetReference?.status === "available" ? assetReference.asset : null
        const desired =
          order?.units.find(({ unit }) => unit.id === line.assetId)
            ?.desiredContents ?? []
        const term = unitTerm(order, line.assetId)
        const disabled =
          !canEdit ||
          (task.kind === "SHIPMENT" && !task.virtual) ||
          ["CANCELLED", "ARRIVED", "DEPARTED"].includes(line.state) ||
          (task.kind === "RETURN" && task.document.state !== "DRAFT")
        const expanded = expandedCabinId === line.id
        const assetStatus = asset
          ? (RENTAL_ITEM_STATUS_LABEL[asset.status] ?? asset.status)
          : line.state
        const returnDocument = returnDocuments.find((document) =>
          document.lines.some((candidate) => candidate.assetId === line.assetId)
        )
        const outboundDriver = shipmentDocument?.driverSnapshot ?? "—"
        const returnDriver = returnDocument?.driverSnapshot ?? "—"
        return (
          <Card key={line.id} size="sm">
            <CardHeader className="gap-3 sm:flex-row sm:items-center">
              <Checkbox
                checked={selectedLineIds.includes(line.id)}
                disabled={disabled}
                aria-label={`Выбрать бытовку ${logisticsAssetLabel(referenceLabels, line.assetId)}`}
                onCheckedChange={(value) =>
                  onToggleLine(line.id, value === true)
                }
              />
              <div className="min-w-0 flex-1">
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
                  ) : (
                    "Заказ не указан"
                  )}
                </CardDescription>
              </div>
              <CardAction>
                <Button
                  type="button"
                  size="sm"
                  variant="ghost"
                  onClick={() => onToggleCabin(line.id)}
                >
                  {expanded ? "Скрыть детали" : "Детали"}
                </Button>
              </CardAction>
            </CardHeader>
            {expanded ? (
              <CardContent className="grid gap-3">
                <div className="grid gap-2 text-sm sm:grid-cols-2">
                  <p>
                    <span className="text-muted-foreground">Статус:</span>{" "}
                    {assetStatus}
                  </p>
                  <p>
                    <span className="text-muted-foreground">Арендатор:</span>{" "}
                    {line.tenantSnapshot ?? "—"}
                  </p>
                  <p>
                    <span className="text-muted-foreground">Менеджер:</span>{" "}
                    {order?.managerDisplayName ?? "—"}
                  </p>
                  <p>
                    <span className="text-muted-foreground">Отгрузил:</span>{" "}
                    {outboundDriver}
                  </p>
                  <p>
                    <span className="text-muted-foreground">Привёз:</span>{" "}
                    {returnDriver}
                  </p>
                </div>
                {term ? (
                  <div className="rounded-md border bg-muted/20 p-3 text-sm">
                    <p className="font-medium">
                      Аренда: {term.rentalMonths} мес.
                    </p>
                    <p className="text-muted-foreground">
                      Отгрузка: {formatDetailDate(term.shipmentDate)} · Возврат
                      по сроку: {formatDetailDate(term.returnDate)}
                    </p>
                    {returnDocument?.scheduledDate &&
                    returnDocument.scheduledDate !== term.returnDate ? (
                      <p className="text-muted-foreground">
                        Запланированный возврат:{" "}
                        {formatDetailDate(returnDocument.scheduledDate)}
                      </p>
                    ) : null}
                    {term.returnDate &&
                    isDue(term.returnDate) &&
                    task.kind === "SHIPMENT" ? (
                      <Badge className="mt-2" variant="destructive">
                        Требует возврата
                      </Badge>
                    ) : null}
                    {term.returnDate && !isDue(term.returnDate) ? (
                      <p className="mt-1 text-muted-foreground">
                        {task.kind === "RETURN"
                          ? "Можно назначить возврат на любую дату кнопкой «Возврат»."
                          : "Дата возврата считается сервисом по сроку аренды."}
                      </p>
                    ) : null}
                  </div>
                ) : null}
                {canEdit &&
                task.kind === "RETURN" &&
                task.document.state === "DRAFT" ? (
                  <Button
                    type="button"
                    size="sm"
                    variant="secondary"
                    onClick={() => onScheduleReturn(line.id)}
                  >
                    Возврат
                  </Button>
                ) : null}
                <CompositionBlock
                  title="Фактическое наполнение"
                  items={asset?.contentsItems ?? []}
                  unavailable={!asset}
                />
                {orderId ? (
                  <CompositionBlock
                    title="Наполнение по заказу"
                    items={desired}
                    unavailable={!order}
                  />
                ) : null}
                {asset?.comment ? (
                  <p className="text-sm">
                    <span className="text-muted-foreground">Комментарий:</span>{" "}
                    {asset.comment}
                  </p>
                ) : null}
                {canEdit &&
                task.kind === "RETURN" &&
                (asset?.contentsItems.length ?? 0) > 0 ? (
                  <Button
                    type="button"
                    size="sm"
                    variant="outline"
                    onClick={() => onCreateContentsTask(line.id)}
                  >
                    Создать задачу на мебель
                  </Button>
                ) : null}
              </CardContent>
            ) : null}
          </Card>
        )
      })}
    </div>
  )
}

function ShipmentDateDecisionDialog({
  task,
  pending,
  onOpenChange,
  onKeepDate,
  onUseToday,
}: {
  task: RentalOrderTask
  pending: boolean
  onOpenChange: (open: boolean) => void
  onKeepDate: () => void
  onUseToday: () => void
}) {
  const shipment =
    task.kind === "SHIPMENT" ? (task.document as ShipmentDocument) : null
  if (!shipment) return null
  return (
    <Dialog open onOpenChange={onOpenChange}>
      <DialogContent>
        <DialogHeader>
          <DialogTitle>Дата отгрузки отличается</DialogTitle>
          <DialogDescription>
            Назначенная дата: {formatDetailDate(shipment.scheduledDate)}.
            Сегодня {formatDetailDate(today())}. Выберите, как сохранить дату
            задания.
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
          <Button type="button" disabled={pending} onClick={onUseToday}>
            Изменить на сегодня
          </Button>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  )
}

function CompositionBlock({
  title,
  items,
  unavailable,
}: {
  title: string
  items: readonly {
    equipmentName?: string | null
    name?: string
    quantity: number
  }[]
  unavailable: boolean
}) {
  return (
    <section className="grid gap-1">
      <h3 className="text-sm font-medium">{title}</h3>
      {unavailable ? (
        <p className="text-sm text-muted-foreground">Состав недоступен.</p>
      ) : null}
      {!unavailable && items.length === 0 ? (
        <p className="text-sm text-muted-foreground">Пусто.</p>
      ) : null}
      {!unavailable && items.length > 0 ? (
        <ul className="grid gap-1 text-sm">
          {items.map((item, index) => (
            <li
              key={`${item.equipmentName ?? item.name ?? "item"}-${index}`}
              className="flex justify-between gap-3"
            >
              <span>{item.equipmentName ?? item.name ?? "Оборудование"}</span>
              <span className="text-muted-foreground">{item.quantity}</span>
            </li>
          ))}
        </ul>
      ) : null}
    </section>
  )
}

function TaskScheduleDialog({
  accessToken,
  task,
  pending,
  onOpenChange,
  onSubmit,
}: {
  accessToken: string
  task: RentalOrderTask
  pending: boolean
  onOpenChange: (open: boolean) => void
  onSubmit: (input: { driverSnapshot: string; scheduledDate: string }) => void
}) {
  const [driver, setDriver] = useState<RepairTaskWorkerSnapshotDto | null>(null)
  const [scheduledDate, setScheduledDate] = useState(
    task.document.scheduledDate ?? today()
  )
  const [error, setError] = useState<string | null>(null)

  function submit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault()
    setError(null)
    if (!driver?.name.trim()) {
      setError("Выберите водителя.")
      return
    }
    if (!scheduledDate) {
      setError("Укажите дату.")
      return
    }
    onSubmit({ driverSnapshot: driver.name.trim(), scheduledDate })
  }

  const isShipment = task.kind === "SHIPMENT"
  return (
    <Dialog open onOpenChange={onOpenChange}>
      <DialogContent>
        <form className="flex flex-col gap-4" onSubmit={submit}>
          <DialogHeader>
            <DialogTitle>
              {isShipment ? "Создать отгрузку" : "Возврат из аренды"}
            </DialogTitle>
            <DialogDescription>
              {isShipment
                ? "Выбранные бытовки будут объединены в отдельное задание. После создания сервис рассчитает дату возврата по сроку аренды."
                : "Назначьте водителя и дату вывоза выбранных бытовок."}
            </DialogDescription>
          </DialogHeader>
          <FieldGroup>
            <LogisticsDriverPicker
              accessToken={accessToken}
              id="order-task-driver"
              required
              value={driver}
              warehouseId={task.document.warehouseId}
              onChange={setDriver}
            />
            <Field data-invalid={Boolean(error) || undefined}>
              <FieldLabel htmlFor="order-task-date">Дата</FieldLabel>
              <Input
                id="order-task-date"
                type="date"
                value={scheduledDate}
                aria-invalid={Boolean(error) || undefined}
                onChange={(event) => setScheduledDate(event.target.value)}
              />
              {error ? <FieldError>{error}</FieldError> : null}
              <FieldDescription>
                Дата хранится как календарная дата без времени.
              </FieldDescription>
            </Field>
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
              {pending
                ? "Сохраняем…"
                : isShipment
                  ? "Создать отгрузку"
                  : "Создать возврат"}
            </Button>
          </DialogFooter>
        </form>
      </DialogContent>
    </Dialog>
  )
}
