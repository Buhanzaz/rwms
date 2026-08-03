import { useMemo, useRef, useState } from "react"
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query"
import {
  Add01Icon,
  ArrowLeft01Icon,
  Delete02Icon,
  Loading03Icon,
  PencilEdit01Icon,
  RefreshIcon,
} from "@hugeicons/core-free-icons"
import { HugeiconsIcon } from "@hugeicons/react"
import { Link, useNavigate, useParams } from "react-router-dom"
import { toast } from "sonner"

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
import { Checkbox } from "@/components/ui/checkbox"
import {
  Card,
  CardAction,
  CardContent,
  CardDescription,
  CardFooter,
  CardHeader,
  CardTitle,
} from "@/components/ui/card"
import { FieldError } from "@/components/ui/field"
import { Input } from "@/components/ui/input"
import {
  Select,
  SelectContent,
  SelectGroup,
  SelectItem,
  SelectTrigger,
  SelectValue,
} from "@/components/ui/select"
import { Skeleton } from "@/components/ui/skeleton"
import {
  addOrderUnit,
  deleteOrder,
  getOrder,
  listAvailableOrderUnits,
  listOrderHistory,
  ORDERS_QUERY_KEY,
  removeOrderUnit,
  saveOrder,
  extendOrderRentalTerms,
  selectOrderWarehouse,
  setOrderRentalTerms,
} from "@/features/orders/api/orders-api"
import { OrderCommandIdentityRegistry } from "@/features/orders/api/order-command-identity"
import { EditOrderDialog } from "@/features/orders/components/edit-order-dialog"
import {
  OrderUnitContentsView,
  OrderUnitEquipmentDialog,
} from "@/features/orders/components/order-unit-contents"
import { OrderWarehouseUnitSelection } from "@/features/orders/components/order-warehouse-unit-selection"
import {
  formatOrderDateTime,
  ORDER_AUDIT_EVENT_LABELS,
  ORDER_CLIENT_TYPE_LABELS,
  ORDER_STATUS_LABELS,
  type OrderDetail,
  type OrderUnitCandidate,
} from "@/features/orders/domain/orders"
import { useOrdersModule } from "@/features/orders/orders-module-context"
import { RentalItemStatusBadge } from "@/features/rental-items/rental-item-status-badge"
import { ApiError } from "@/lib/api-client"

const UNIT_PAGE_SIZE = 40
const AVAILABLE_UNITS_REFETCH_INTERVAL_MS = 2_000
const UUID_PATTERN =
  /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i

const ORDER_CHANGE_LABELS: Record<string, string> = {
  clientId: "клиент",
  warehouseId: "склад",
  units: "бытовки",
  desiredEquipment: "желаемое наполнение",
  unitsAndDesiredEquipment: "бытовки и желаемое наполнение",
  status: "статус бронирования",
}

const RESERVATION_STATE_LABELS: Record<string, string> = {
  ACTIVE: "активен",
  RELEASED: "освобождён",
}

function isUuid(value: unknown) {
  return typeof value === "string" && UUID_PATTERN.test(value)
}

function auditValue(key: string, value: unknown) {
  if (isUuid(value) || value === null || value === undefined) return null

  switch (key) {
    case "number":
      return `Номер бронирования: ${String(value)}`
    case "status":
      return typeof value === "string" &&
        Object.hasOwn(ORDER_STATUS_LABELS, value)
        ? `Статус: ${ORDER_STATUS_LABELS[value as keyof typeof ORDER_STATUS_LABELS]}`
        : null
    case "displayName":
      return `Клиент: ${String(value)}`
    case "unitNumber":
      return `Бытовка: ${String(value)}`
    case "equipmentName":
      return `Мебель: ${String(value)}`
    case "quantity":
      return typeof value === "number" ? `Количество: ${value}` : null
    case "state":
      return typeof value === "string" &&
        Object.hasOwn(RESERVATION_STATE_LABELS, value)
        ? `Статус резерва: ${RESERVATION_STATE_LABELS[value]}`
        : null
    case "changedField":
      return typeof value === "string" &&
        Object.hasOwn(ORDER_CHANGE_LABELS, value)
        ? `Изменено: ${ORDER_CHANGE_LABELS[value]}`
        : null
    case "conflictCode":
      return value === "UNIT_ALREADY_RESERVED"
        ? "Бытовка уже занята другим бронированием"
        : null
    default:
      return null
  }
}

function formatAuditValues(values: Record<string, unknown> | null) {
  if (!values || Object.keys(values).length === 0) return null
  const formatted = Object.entries(values)
    .map(([key, value]) => auditValue(key, value))
    .filter((value): value is string => value !== null)
    .join(" · ")
  return formatted || null
}

function auditContext(
  event: { eventType: string; subjectId: string },
  order: OrderDetail,
  warehouseLabel: string | null
) {
  if (
    event.eventType === "CLIENT_CREATED" ||
    event.eventType === "CLIENT_SELECTED"
  ) {
    return `Клиент: ${order.client.displayName}`
  }
  if (event.eventType === "WAREHOUSE_SELECTED" && warehouseLabel) {
    return `Склад: ${warehouseLabel}`
  }
  if (
    event.eventType === "UNIT_ADDED" ||
    event.eventType === "UNIT_REMOVED" ||
    event.eventType === "UNIT_ADD_CONFLICT"
  ) {
    const unit = order.units.find(
      (candidate) => candidate.unit.id === event.subjectId
    )
    return unit ? `Бытовка: ${unit.unit.number}` : null
  }
  return null
}

const RENTAL_MONTH_PRESETS = [1, 2, 3, 6, 12] as const
const EMPTY_UNIT_IDS = new Set<string>()

type VersionedMonthsDraft = {
  orderVersion: number
  values: Record<string, string>
}

type VersionedUnitSelection = {
  orderVersion: number
  unitIds: Set<string>
}

function parsePositiveMonths(value: string): number | null {
  const normalized = value.trim()
  if (!/^\d+$/.test(normalized)) return null

  const parsed = Number(normalized)
  return Number.isSafeInteger(parsed) && parsed > 0 ? parsed : null
}

function formatRentalDate(value: string | null) {
  if (!value) return "Не назначена"
  const [year, month, day] = value.split("-")
  return `${day}.${month}.${year}`
}

function isShippedRentalTerm(candidate: OrderUnitCandidate) {
  const shipmentDate = candidate.rentalTerm?.shipmentDate
  const returnDate = candidate.rentalTerm?.returnDate
  return (
    candidate.unit.status === "RENTED" &&
    shipmentDate !== null &&
    shipmentDate !== undefined &&
    returnDate !== null &&
    returnDate !== undefined
  )
}

function monthLabel(value: number) {
  const remainder10 = value % 10
  const remainder100 = value % 100
  if (remainder10 === 1 && remainder100 !== 11) return `${value} месяц`
  if (
    remainder10 >= 2 &&
    remainder10 <= 4 &&
    (remainder100 < 10 || remainder100 >= 20)
  ) {
    return `${value} месяца`
  }
  return `${value} месяцев`
}

function RentalMonthsPicker({
  id,
  label,
  value,
  disabled,
  onChange,
}: {
  id: string
  label: string
  value: string
  disabled?: boolean
  onChange: (value: string) => void
}) {
  const normalized = value.trim()
  const preset = RENTAL_MONTH_PRESETS.find(
    (months) => String(months) === normalized
  )
  const [customMode, setCustomMode] = useState(
    normalized !== "" && preset === undefined
  )

  const mode =
    customMode || (normalized !== "" && preset === undefined)
      ? "custom"
      : normalized === ""
        ? ""
        : String(preset)

  return (
    <div className="grid gap-1 text-sm">
      <label htmlFor={id} className="font-medium">
        {label}
      </label>
      <select
        id={id}
        aria-label={label}
        value={mode}
        disabled={disabled}
        className="h-9 w-full rounded-md border border-input bg-background px-3 text-sm"
        onChange={(event) => {
          const next = event.target.value
          if (next === "custom") {
            setCustomMode(true)
            if (preset !== undefined) onChange("")
          } else {
            setCustomMode(false)
            onChange(next)
          }
        }}
      >
        <option value="">Выберите срок</option>
        {RENTAL_MONTH_PRESETS.map((months) => (
          <option key={months} value={months}>
            {monthLabel(months)}
          </option>
        ))}
        <option value="custom">Другое положительное целое</option>
      </select>
      {mode === "custom" ? (
        <Input
          type="number"
          min={1}
          step={1}
          value={value}
          disabled={disabled}
          aria-label={`${label}: произвольное значение`}
          placeholder="Количество месяцев"
          onChange={(event) => onChange(event.target.value)}
        />
      ) : null}
    </div>
  )
}

export function OrderDetailPage() {
  const { orderId } = useParams<{ orderId: string }>()
  const navigate = useNavigate()
  const queryClient = useQueryClient()
  const { accessToken, currentUser, warehouses } = useOrdersModule()
  const [unitSearch, setUnitSearch] = useState("")
  const [unitPage, setUnitPage] = useState(0)
  const [pendingAddUnitIds, setPendingAddUnitIds] = useState(
    () => new Set<string>()
  )
  const [unitConflictObservedAt, setUnitConflictObservedAt] = useState(
    () => new Map<string, number>()
  )
  const [contentsUnitId, setContentsUnitId] = useState<string | null>(null)
  const [editDialogOpen, setEditDialogOpen] = useState(false)
  const [cancelDialogOpen, setCancelDialogOpen] = useState(false)
  const [rentalMonthsDraft, setRentalMonthsDraft] =
    useState<VersionedMonthsDraft>({ orderVersion: -1, values: {} })
  const [extensionMonthsDraft, setExtensionMonthsDraft] =
    useState<VersionedMonthsDraft>({ orderVersion: -1, values: {} })
  const [extensionSelection, setExtensionSelection] =
    useState<VersionedUnitSelection>({
      orderVersion: -1,
      unitIds: new Set<string>(),
    })
  const commandIdentity = useRef(new OrderCommandIdentityRegistry())
  const subjectId = currentUser?.id ?? "unknown-user"
  const detailQueryKey = [
    ...ORDERS_QUERY_KEY,
    "detail",
    subjectId,
    orderId,
  ] as const

  const detailQuery = useQuery({
    queryKey: detailQueryKey,
    queryFn: () => getOrder(accessToken!, orderId!),
    enabled: Boolean(accessToken && orderId),
  })
  const order = detailQuery.data
  const editableOrder = order?.permissions.canEdit === true
  const rentalMonthsByUnitId =
    rentalMonthsDraft.orderVersion === order?.version
      ? rentalMonthsDraft.values
      : {}
  const extensionMonthsByUnitId =
    extensionMonthsDraft.orderVersion === order?.version
      ? extensionMonthsDraft.values
      : {}
  const extensionUnitIds =
    extensionSelection.orderVersion === order?.version
      ? extensionSelection.unitIds
      : EMPTY_UNIT_IDS

  const availableQueryKey = [
    ...ORDERS_QUERY_KEY,
    "available-units",
    subjectId,
    orderId,
    unitPage,
    UNIT_PAGE_SIZE,
    unitSearch,
  ] as const
  const availableUnitsQuery = useQuery({
    queryKey: availableQueryKey,
    queryFn: () =>
      listAvailableOrderUnits({
        accessToken: accessToken!,
        orderId: orderId!,
        page: unitPage,
        size: UNIT_PAGE_SIZE,
        search: unitSearch,
      }),
    enabled: Boolean(
      accessToken && orderId && order?.warehouseId && editableOrder
    ),
    refetchInterval:
      order?.warehouseId && editableOrder
        ? AVAILABLE_UNITS_REFETCH_INTERVAL_MS
        : false,
  })
  const historyQuery = useQuery({
    queryKey: [...ORDERS_QUERY_KEY, "history", subjectId, orderId],
    queryFn: () => listOrderHistory(accessToken!, orderId!),
    enabled: Boolean(accessToken && orderId && order),
  })

  function applyProjection(projection: OrderDetail) {
    queryClient.setQueryData(detailQueryKey, projection)
    void queryClient.invalidateQueries({
      queryKey: [...ORDERS_QUERY_KEY, "list"],
    })
    void queryClient.invalidateQueries({
      queryKey: [...ORDERS_QUERY_KEY, "available-units", subjectId, orderId],
    })
    void queryClient.invalidateQueries({
      queryKey: [...ORDERS_QUERY_KEY, "history", subjectId, orderId],
    })
  }

  function refreshOrderBoundary() {
    void detailQuery.refetch()
    if (order?.warehouseId) void availableUnitsQuery.refetch()
    void historyQuery.refetch()
  }

  const warehouseMutation = useMutation({
    mutationFn: ({
      warehouseId,
      expectedVersion,
      fingerprint,
    }: {
      warehouseId: string
      expectedVersion: number
      fingerprint: string
    }) => {
      if (!accessToken || !order) throw new Error("Сессия завершена.")
      return selectOrderWarehouse({
        accessToken,
        orderId: order.id,
        expectedVersion,
        warehouseId,
        idempotencyKey: commandIdentity.current.keyFor(fingerprint),
      })
    },
    onSuccess: (projection, { fingerprint }) => {
      commandIdentity.current.confirm(fingerprint)
      applyProjection(projection)
      toast.success("Склад бронирования выбран.")
    },
    onError: (error) => {
      toast.error(
        error instanceof Error ? error.message : "Не удалось выбрать склад."
      )
      if (error instanceof ApiError && error.status === 409) {
        refreshOrderBoundary()
      }
    },
  })

  const addUnitMutation = useMutation({
    mutationFn: ({
      candidate,
      expectedVersion,
      fingerprint,
    }: {
      candidate: OrderUnitCandidate
      expectedVersion: number
      fingerprint: string
    }) => {
      if (!accessToken || !orderId) throw new Error("Сессия завершена.")
      return addOrderUnit({
        accessToken,
        orderId,
        expectedVersion,
        unitId: candidate.unit.id,
        idempotencyKey: commandIdentity.current.keyFor(fingerprint),
      })
    },
    onMutate: ({ candidate }) => {
      setPendingAddUnitIds((current) => {
        const next = new Set(current)
        next.add(candidate.unit.id)
        return next
      })
    },
    onSuccess: (projection, { candidate, fingerprint }) => {
      commandIdentity.current.confirm(fingerprint)
      applyProjection(projection)
      setUnitConflictObservedAt((current) => {
        const next = new Map(current)
        next.delete(candidate.unit.id)
        return next
      })
      toast.success(
        `Бытовка ${candidate.unit.number} добавлена в бронирование.`
      )
    },
    onError: (error, { candidate }) => {
      if (
        error instanceof ApiError &&
        error.status === 409 &&
        error.code === "UNIT_ALREADY_RESERVED"
      ) {
        setUnitConflictObservedAt((current) => {
          const next = new Map(current)
          next.set(candidate.unit.id, availableUnitsQuery.dataUpdatedAt)
          return next
        })
        toast.error(
          "Бытовка уже занята другим бронированием. Список доступных бытовок обновлён."
        )
        refreshOrderBoundary()
        return
      }

      toast.error(
        error instanceof Error
          ? error.message
          : "Не удалось зарезервировать бытовку."
      )
      if (error instanceof ApiError && error.status === 409) {
        refreshOrderBoundary()
      }
    },
    onSettled: (_data, _error, { candidate }) => {
      setPendingAddUnitIds((current) => {
        const next = new Set(current)
        next.delete(candidate.unit.id)
        return next
      })
    },
  })

  const removeUnitMutation = useMutation({
    mutationFn: ({
      candidate,
      expectedVersion,
      fingerprint,
    }: {
      candidate: OrderUnitCandidate
      expectedVersion: number
      fingerprint: string
    }) => {
      if (!accessToken || !orderId) throw new Error("Сессия завершена.")
      return removeOrderUnit({
        accessToken,
        orderId,
        expectedVersion,
        unitId: candidate.unit.id,
        idempotencyKey: commandIdentity.current.keyFor(fingerprint),
      })
    },
    onSuccess: (projection, { candidate, fingerprint }) => {
      commandIdentity.current.confirm(fingerprint)
      applyProjection(projection)
      toast.success(
        `Бытовка ${candidate.unit.number} удалена, резервирование освобождено.`
      )
    },
    onError: (error) => {
      toast.error(
        error instanceof Error
          ? error.message
          : "Не удалось освободить бытовку."
      )
      if (error instanceof ApiError && error.status === 409) {
        refreshOrderBoundary()
      }
    },
  })

  const cancelMutation = useMutation({
    mutationFn: ({
      expectedVersion,
      fingerprint,
    }: {
      expectedVersion: number
      fingerprint: string
    }) => {
      if (!accessToken || !order) throw new Error("Сессия завершена.")
      return deleteOrder({
        accessToken,
        orderId: order.id,
        expectedVersion,
        idempotencyKey: commandIdentity.current.keyFor(fingerprint),
      })
    },
    onSuccess: (_data, { fingerprint }) => {
      commandIdentity.current.confirm(fingerprint)
      void queryClient.invalidateQueries({ queryKey: ORDERS_QUERY_KEY })
      toast.success(
        "Черновик удалён: бронирование логически отменено, резервирования освобождены."
      )
      navigate("/orders", { replace: true })
    },
    onError: (error) => {
      toast.error(
        error instanceof Error ? error.message : "Не удалось удалить черновик."
      )
      if (error instanceof ApiError && error.status === 409) {
        setCancelDialogOpen(false)
        refreshOrderBoundary()
      }
    },
  })

  const saveMutation = useMutation({
    mutationFn: ({
      expectedVersion,
      fingerprint,
    }: {
      expectedVersion: number
      fingerprint: string
    }) => {
      if (!accessToken || !order) throw new Error("Сессия завершена.")
      return saveOrder({
        accessToken,
        orderId: order.id,
        expectedVersion,
        idempotencyKey: commandIdentity.current.keyFor(fingerprint),
      })
    },
    onSuccess: (projection, { fingerprint }) => {
      commandIdentity.current.confirm(fingerprint)
      applyProjection(projection)
      toast.success(
        "Бронирование сохранено. Создайте отгрузку в разделе «Задания» логистики."
      )
    },
    onError: (error) => {
      toast.error(
        error instanceof Error
          ? error.message
          : "Не удалось сохранить бронирование."
      )
      if (error instanceof ApiError && error.status === 409) {
        refreshOrderBoundary()
      }
    },
  })

  const selectedUnits = useMemo(
    () => order?.units.filter((candidate) => candidate.added) ?? [],
    [order?.units]
  )

  function rentalMonthsValue(candidate: OrderUnitCandidate) {
    return (
      rentalMonthsByUnitId[candidate.unit.id] ??
      (candidate.rentalTerm?.rentalMonths
        ? String(candidate.rentalTerm.rentalMonths)
        : "")
    )
  }

  function extensionMonthsValue(unitId: string) {
    return extensionMonthsByUnitId[unitId] ?? "1"
  }

  function setRentalMonthsValue(unitId: string, value: string) {
    if (!order) return
    setRentalMonthsDraft((current) => ({
      orderVersion: order.version,
      values: {
        ...(current.orderVersion === order.version ? current.values : {}),
        [unitId]: value,
      },
    }))
  }

  function setExtensionMonthsValue(unitId: string, value: string) {
    if (!order) return
    setExtensionMonthsDraft((current) => ({
      orderVersion: order.version,
      values: {
        ...(current.orderVersion === order.version ? current.values : {}),
        [unitId]: value,
      },
    }))
  }

  function setExtensionUnitSelected(unitId: string, checked: boolean) {
    if (!order) return
    setExtensionSelection((current) => {
      const next = new Set(
        current.orderVersion === order.version ? current.unitIds : []
      )
      if (checked) next.add(unitId)
      else next.delete(unitId)
      return { orderVersion: order.version, unitIds: next }
    })
  }

  const rentalTermsMutation = useMutation({
    mutationFn: ({
      expectedVersion,
      terms,
      fingerprint,
    }: {
      expectedVersion: number
      terms: Array<{ unitId: string; rentalMonths: number }>
      fingerprint: string
    }) => {
      if (!accessToken || !order) throw new Error("Сессия завершена.")
      return setOrderRentalTerms({
        accessToken,
        orderId: order.id,
        expectedVersion,
        terms,
        idempotencyKey: commandIdentity.current.keyFor(fingerprint),
      })
    },
    onSuccess: (projection, { fingerprint }) => {
      commandIdentity.current.confirm(fingerprint)
      applyProjection(projection)
      toast.success("Сроки аренды сохранены.")
    },
    onError: (error) => {
      toast.error(
        error instanceof Error
          ? error.message
          : "Не удалось сохранить сроки аренды."
      )
      if (error instanceof ApiError && error.status === 409) {
        refreshOrderBoundary()
      }
    },
  })

  const extensionMutation = useMutation({
    mutationFn: ({
      expectedVersion,
      terms,
      fingerprint,
    }: {
      expectedVersion: number
      terms: Array<{ unitId: string; additionalMonths: number }>
      fingerprint: string
    }) => {
      if (!accessToken || !order) throw new Error("Сессия завершена.")
      return extendOrderRentalTerms({
        accessToken,
        orderId: order.id,
        expectedVersion,
        terms,
        idempotencyKey: commandIdentity.current.keyFor(fingerprint),
      })
    },
    onSuccess: (projection, { fingerprint }) => {
      commandIdentity.current.confirm(fingerprint)
      applyProjection(projection)
      toast.success("Срок аренды продлён.")
    },
    onError: (error) => {
      toast.error(
        error instanceof Error
          ? error.message
          : "Не удалось продлить срок аренды."
      )
      if (error instanceof ApiError && error.status === 409) {
        refreshOrderBoundary()
      }
    },
  })

  const rentalTermsReady =
    selectedUnits.length > 0 &&
    selectedUnits.every(
      (candidate) => parsePositiveMonths(rentalMonthsValue(candidate)) !== null
    )
  const rentalTermsConfigured =
    selectedUnits.length > 0 &&
    selectedUnits.every(
      (candidate) =>
        candidate.rentalTerm?.rentalMonths !== undefined &&
        candidate.rentalTerm.rentalMonths > 0
    )
  const rentalTermsDirty = selectedUnits.some((candidate) => {
    const next = parsePositiveMonths(rentalMonthsValue(candidate))
    const current = candidate.rentalTerm?.rentalMonths ?? null
    return next !== current
  })
  const extensionEligibleUnitIds = new Set(
    selectedUnits
      .filter(isShippedRentalTerm)
      .map((candidate) => candidate.unit.id)
  )
  const extensionReady =
    extensionUnitIds.size > 0 &&
    [...extensionUnitIds].every(
      (unitId) =>
        extensionEligibleUnitIds.has(unitId) &&
        parsePositiveMonths(extensionMonthsValue(unitId)) !== null
    )
  const canExtendRentalTerms =
    (order?.status === "SAVED" || order?.status === "FULFILLED") &&
    extensionEligibleUnitIds.size > 0

  const contentsCandidate =
    selectedUnits.find((candidate) => candidate.unit.id === contentsUnitId) ??
    null
  const availableUnitPage = availableUnitsQuery.data
  const displayedAvailableCandidates = useMemo(() => {
    if (!availableUnitPage) return []

    const selectedByUnitId = new Map(
      selectedUnits.map((candidate) => [candidate.unit.id, candidate])
    )

    return availableUnitPage.content.map((candidate) => {
      const selectedCandidate = selectedByUnitId.get(candidate.unit.id)
      if (selectedCandidate) return selectedCandidate

      // A successful remove projection no longer contains the released unit,
      // while the invalidated availability page may still contain its old
      // `added` state until the refetch completes.
      return candidate.added
        ? { ...candidate, reservationId: null, added: false }
        : candidate
    })
  }, [availableUnitPage, selectedUnits])
  const activeConflictingUnitIds = useMemo(() => {
    const active = new Set<string>()

    for (const [unitId, observedAt] of unitConflictObservedAt) {
      const serverConfirmedFree =
        availableUnitsQuery.dataUpdatedAt > observedAt &&
        availableUnitPage?.content.some(
          (candidate) => candidate.unit.id === unitId && !candidate.added
        )
      if (!serverConfirmedFree) active.add(unitId)
    }

    return active
  }, [
    availableUnitPage,
    availableUnitsQuery.dataUpdatedAt,
    unitConflictObservedAt,
  ])
  const history = historyQuery.data ?? []

  if (!orderId) {
    return (
      <Card size="sm">
        <CardHeader>
          <CardTitle>Бронирование не выбрано</CardTitle>
          <CardDescription>
            В URL отсутствует идентификатор бронирования.
          </CardDescription>
        </CardHeader>
      </Card>
    )
  }

  if (detailQuery.isLoading) {
    return (
      <div className="flex h-full flex-col gap-4">
        <Skeleton className="h-10 w-72" />
        <Skeleton className="h-48 w-full" />
        <Skeleton className="min-h-80 w-full flex-1" />
      </div>
    )
  }

  if (detailQuery.isError || !order) {
    return (
      <Card size="sm">
        <CardHeader>
          <CardTitle>Бронирование недоступно</CardTitle>
          <CardDescription role="alert">
            {detailQuery.error instanceof Error
              ? detailQuery.error.message
              : "Бронирование не найдено или у вас нет доступа."}
          </CardDescription>
        </CardHeader>
        <CardContent className="flex gap-2">
          <Button asChild variant="outline">
            <Link to="/orders">
              <HugeiconsIcon icon={ArrowLeft01Icon} data-icon="inline-start" />К
              списку
            </Link>
          </Button>
          <Button type="button" onClick={() => void detailQuery.refetch()}>
            <HugeiconsIcon icon={RefreshIcon} data-icon="inline-start" />
            Повторить
          </Button>
        </CardContent>
      </Card>
    )
  }

  const selectedWarehouse = warehouses.find(
    (warehouse) => warehouse.id === order.warehouseId
  )
  const canEdit = order.permissions.canEdit
  const canCancel = canEdit && order.status === "DRAFT"
  const warehouseLocked = order.unitCount > 0
  const saveActionLabel =
    order.status === "SAVED" ? "Создать заказ" : "Сохранить бронирование"

  return (
    <div className="flex h-full min-h-0 flex-col gap-4 overflow-auto pr-1">
      <div className="flex flex-wrap items-center justify-between gap-3">
        <Button asChild size="sm" variant="outline">
          <Link to="/orders">
            <HugeiconsIcon icon={ArrowLeft01Icon} data-icon="inline-start" />
            Назад
          </Link>
        </Button>
        <div className="ml-auto flex flex-wrap items-center justify-end gap-2">
          <Badge variant={order.status === "DRAFT" ? "secondary" : "outline"}>
            {ORDER_STATUS_LABELS[order.status]}
          </Badge>
          <span className="text-sm text-muted-foreground">
            Изменён {formatOrderDateTime(order.updatedAt)}
          </span>
        </div>
      </div>

      {canEdit ? (
        <div className="flex flex-wrap justify-end gap-2">
          <Button
            type="button"
            disabled={
              saveMutation.isPending ||
              order.unitCount === 0 ||
              order.warehouseId === null ||
              !rentalTermsConfigured ||
              rentalTermsDirty
            }
            onClick={() =>
              saveMutation.mutate({
                expectedVersion: order.version,
                fingerprint: `save:${order.id}:${order.version}`,
              })
            }
          >
            {saveMutation.isPending ? (
              <HugeiconsIcon
                icon={Loading03Icon}
                data-icon="inline-start"
                className="animate-spin"
              />
            ) : null}
            {saveMutation.isPending ? "Сохраняем…" : saveActionLabel}
          </Button>
          <Button
            type="button"
            variant="outline"
            onClick={() => setEditDialogOpen(true)}
          >
            <HugeiconsIcon icon={PencilEdit01Icon} data-icon="inline-start" />
            Редактировать бронирование
          </Button>
          {canCancel ? (
            <Button
              type="button"
              variant="destructive"
              onClick={() => setCancelDialogOpen(true)}
            >
              <HugeiconsIcon icon={Delete02Icon} data-icon="inline-start" />
              Удалить черновик
            </Button>
          ) : null}
        </div>
      ) : null}

      <Card size="sm">
        <CardHeader>
          <CardTitle>Основные данные</CardTitle>
          <CardDescription>Ответственные за бронирование.</CardDescription>
        </CardHeader>
        <CardContent className="grid gap-4 sm:grid-cols-2 xl:grid-cols-4">
          <div>
            <p className="text-xs text-muted-foreground">Клиент</p>
            <p className="font-medium">{order.client.displayName}</p>
            <p className="text-sm text-muted-foreground">
              {ORDER_CLIENT_TYPE_LABELS[order.client.type]}
            </p>
          </div>
          <div>
            <p className="text-xs text-muted-foreground">Менеджер</p>
            <p className="font-medium">{order.managerDisplayName}</p>
          </div>
          <div>
            <p className="text-xs text-muted-foreground">Автор</p>
            <p className="font-medium">{order.createdByDisplayName}</p>
          </div>
          <div>
            <p className="text-xs text-muted-foreground">Создан</p>
            <p className="font-medium">
              {formatOrderDateTime(order.createdAt)}
            </p>
          </div>
        </CardContent>
      </Card>

      <Card size="sm">
        <CardHeader>
          <CardTitle>Склад бронирования</CardTitle>
          <CardDescription>
            {warehouseLocked
              ? "Склад заблокирован после добавления первой бытовки."
              : "До выбора склада поиск бытовок недоступен."}
          </CardDescription>
        </CardHeader>
        <CardContent>
          <Select
            value={order.warehouseId ?? ""}
            disabled={
              !canEdit || warehouseLocked || warehouseMutation.isPending
            }
            onValueChange={(warehouseId) => {
              const fingerprint = `warehouse:${order.id}:${order.version}:${warehouseId}`
              warehouseMutation.mutate({
                warehouseId,
                expectedVersion: order.version,
                fingerprint,
              })
            }}
          >
            <SelectTrigger
              aria-label="Склад бронирования"
              className="w-full max-w-xl"
            >
              <SelectValue placeholder="Выберите склад" />
            </SelectTrigger>
            <SelectContent>
              <SelectGroup>
                {warehouses.map((warehouse) => (
                  <SelectItem key={warehouse.id} value={warehouse.id}>
                    {warehouse.name} · {warehouse.city}
                  </SelectItem>
                ))}
              </SelectGroup>
            </SelectContent>
          </Select>
          {warehouseMutation.isPending ? (
            <p className="mt-2 text-sm text-muted-foreground">
              Сохраняем склад…
            </p>
          ) : selectedWarehouse ? (
            <p className="mt-2 text-sm text-muted-foreground">
              {selectedWarehouse.address ?? selectedWarehouse.city}
            </p>
          ) : null}
        </CardContent>
      </Card>

      {!canEdit ? null : !order.warehouseId ? (
        <Card size="sm">
          <CardHeader>
            <CardTitle>Склад не выбран</CardTitle>
            <CardDescription>
              Сначала выберите склад бронирования, затем станет доступен поиск
              бытовок.
            </CardDescription>
          </CardHeader>
        </Card>
      ) : (
        <Card className="min-h-[36rem] min-w-0 overflow-hidden" size="sm">
          <CardHeader>
            <CardTitle>Выбор бытовок</CardTitle>
            <CardDescription>
              Показаны доступные для аренды свободные и новые бытовки выбранного
              склада, а также бытовки этого бронирования.
            </CardDescription>
          </CardHeader>
          <CardContent className="flex min-h-0 min-w-0 flex-1 flex-col gap-4 overflow-hidden">
            <Input
              type="search"
              value={unitSearch}
              aria-label="Поиск бытовок для бронирования"
              placeholder="Поиск по номеру и характеристикам"
              className="max-w-xl"
              onChange={(event) => {
                setUnitSearch(event.target.value)
                setUnitPage(0)
              }}
            />

            {availableUnitsQuery.isLoading ? (
              <div className="flex flex-col gap-3">
                <Skeleton className="h-48 w-full" />
                <Skeleton className="h-48 w-full" />
              </div>
            ) : availableUnitsQuery.isError ? (
              <div className="flex flex-col items-start gap-3">
                <FieldError>
                  {availableUnitsQuery.error instanceof Error
                    ? availableUnitsQuery.error.message
                    : "Не удалось загрузить бытовки."}
                </FieldError>
                <Button
                  type="button"
                  variant="outline"
                  onClick={() => void availableUnitsQuery.refetch()}
                >
                  <HugeiconsIcon icon={RefreshIcon} data-icon="inline-start" />
                  Повторить
                </Button>
              </div>
            ) : displayedAvailableCandidates.length === 0 ? (
              <p className="text-sm text-muted-foreground">
                Доступные для аренды бытовки не найдены.
              </p>
            ) : (
              <OrderWarehouseUnitSelection
                accessToken={accessToken!}
                warehouseId={order.warehouseId}
                candidates={displayedAvailableCandidates}
                canEdit={canEdit}
                pendingUnitIds={pendingAddUnitIds}
                conflictingUnitIds={activeConflictingUnitIds}
                onAdd={(candidate) => {
                  if (!canEdit) {
                    toast.error("Изменение этого бронирования запрещено.")
                    return
                  }
                  addUnitMutation.mutate({
                    candidate,
                    expectedVersion: order.version,
                    fingerprint: `add-unit:${order.id}:${order.version}:${candidate.unit.id}`,
                  })
                }}
                onEditContents={(candidate) =>
                  setContentsUnitId(candidate.unit.id)
                }
              />
            )}
          </CardContent>
          {availableUnitsQuery.data ? (
            <CardFooter className="justify-between gap-3 border-t text-sm text-muted-foreground">
              <span>
                Показано {availableUnitsQuery.data.content.length} из{" "}
                {availableUnitsQuery.data.totalElements}
              </span>
              <div className="flex items-center gap-2">
                <Button
                  type="button"
                  size="sm"
                  variant="outline"
                  disabled={unitPage === 0 || availableUnitsQuery.isFetching}
                  onClick={() =>
                    setUnitPage((current) => Math.max(0, current - 1))
                  }
                >
                  Назад
                </Button>
                <Button
                  type="button"
                  size="sm"
                  variant="outline"
                  disabled={
                    unitPage + 1 >= availableUnitsQuery.data.totalPages ||
                    availableUnitsQuery.isFetching
                  }
                  onClick={() => setUnitPage((current) => current + 1)}
                >
                  Вперёд
                </Button>
              </div>
            </CardFooter>
          ) : null}
        </Card>
      )}

      <section
        className="flex flex-col gap-3"
        aria-labelledby="order-units-title"
      >
        <div>
          <h2 id="order-units-title" className="text-lg font-semibold">
            Бытовки в бронировании
          </h2>
          <p className="text-sm text-muted-foreground">
            Наполнение показано для каждой бытовки. Пока бронирование в
            черновике, его можно добавить или изменить из свободного остатка
            склада.
          </p>
        </div>
        {selectedUnits.length > 0 ? (
          <div className="rounded-lg border bg-muted/20 p-3">
            <div className="flex flex-wrap items-center justify-between gap-3">
              <div>
                <h3 className="font-medium">Сроки аренды</h3>
                <p className="text-sm text-muted-foreground">
                  Укажите срок для каждой бытовки до сохранения бронирования.
                </p>
              </div>
              <div className="flex flex-wrap gap-2">
                {canEdit ? (
                  <Button
                    type="button"
                    size="sm"
                    disabled={
                      !rentalTermsReady ||
                      !rentalTermsDirty ||
                      rentalTermsMutation.isPending
                    }
                    onClick={() => {
                      const terms = selectedUnits.flatMap((candidate) => {
                        const rentalMonths = parsePositiveMonths(
                          rentalMonthsValue(candidate)
                        )
                        return rentalMonths === null
                          ? []
                          : [
                              {
                                unitId: candidate.unit.id,
                                rentalMonths,
                              },
                            ]
                      })
                      if (terms.length !== selectedUnits.length) return

                      rentalTermsMutation.mutate({
                        expectedVersion: order.version,
                        terms,
                        fingerprint: `rental-terms:${order.id}:${order.version}:${terms
                          .map((term) => `${term.unitId}:${term.rentalMonths}`)
                          .join(",")}`,
                      })
                    }}
                  >
                    {rentalTermsMutation.isPending ? (
                      <HugeiconsIcon
                        icon={Loading03Icon}
                        data-icon="inline-start"
                        className="animate-spin"
                      />
                    ) : null}
                    {rentalTermsMutation.isPending
                      ? "Сохраняем сроки…"
                      : "Сохранить сроки аренды"}
                  </Button>
                ) : null}
                {canExtendRentalTerms ? (
                  <Button
                    type="button"
                    size="sm"
                    variant="outline"
                    disabled={!extensionReady || extensionMutation.isPending}
                    onClick={() => {
                      const terms = selectedUnits.flatMap((candidate) => {
                        if (!extensionUnitIds.has(candidate.unit.id)) return []
                        const additionalMonths = parsePositiveMonths(
                          extensionMonthsValue(candidate.unit.id)
                        )
                        return additionalMonths === null
                          ? []
                          : [
                              {
                                unitId: candidate.unit.id,
                                additionalMonths,
                              },
                            ]
                      })
                      if (terms.length !== extensionUnitIds.size) return

                      extensionMutation.mutate({
                        expectedVersion: order.version,
                        terms,
                        fingerprint: `extend-rental-terms:${order.id}:${order.version}:${terms
                          .map(
                            (term) => `${term.unitId}:${term.additionalMonths}`
                          )
                          .join(",")}`,
                      })
                    }}
                  >
                    {extensionMutation.isPending ? (
                      <HugeiconsIcon
                        icon={Loading03Icon}
                        data-icon="inline-start"
                        className="animate-spin"
                      />
                    ) : null}
                    {extensionMutation.isPending
                      ? "Продлеваем…"
                      : "Продлить выбранные бытовки"}
                  </Button>
                ) : null}
              </div>
            </div>
            {canEdit && !rentalTermsReady ? (
              <p className="mt-2 text-sm text-muted-foreground">
                Выберите положительное целое число месяцев для каждой бытовки.
              </p>
            ) : canEdit && rentalTermsDirty ? (
              <p className="mt-2 text-sm text-muted-foreground">
                Сначала сохраните изменённые сроки аренды, затем создавайте
                заказ.
              </p>
            ) : null}
            {canExtendRentalTerms &&
            extensionUnitIds.size > 0 &&
            !extensionReady ? (
              <p className="mt-2 text-sm text-muted-foreground">
                Укажите положительный срок продления для каждой выбранной
                бытовки.
              </p>
            ) : null}
          </div>
        ) : null}
        {selectedUnits.length === 0 ? (
          <Card size="sm">
            <CardHeader>
              <CardTitle>Бытовки ещё не добавлены</CardTitle>
              <CardDescription>
                Выберите свободную бытовку в складской сетке выше.
              </CardDescription>
            </CardHeader>
          </Card>
        ) : (
          selectedUnits.map((candidate) => {
            const rentalTerm = candidate.rentalTerm ?? null
            const isShipped = isShippedRentalTerm(candidate)
            const selectedForExtension = extensionUnitIds.has(candidate.unit.id)

            return (
              <Card key={candidate.unit.id} size="sm">
                <CardHeader>
                  <CardTitle className="flex flex-wrap items-center gap-2">
                    {candidate.unit.number}
                    <RentalItemStatusBadge status={candidate.unit.status} />
                  </CardTitle>
                  <CardDescription>
                    {[
                      candidate.unit.rentalType,
                      candidate.unit.dimensions,
                      candidate.unit.category,
                    ]
                      .filter(Boolean)
                      .join(" · ")}
                  </CardDescription>
                  <CardAction className="flex items-center gap-1">
                    <Badge variant="secondary">Добавлено</Badge>
                    {canEdit ? (
                      <Button
                        type="button"
                        size="sm"
                        variant="outline"
                        aria-label={`Добавить наполнение ${candidate.unit.number}`}
                        onClick={() => setContentsUnitId(candidate.unit.id)}
                      >
                        <HugeiconsIcon
                          icon={Add01Icon}
                          data-icon="inline-start"
                        />
                        {candidate.unit.contents.length > 0
                          ? "Изменить наполнение"
                          : "Добавить наполнение"}
                      </Button>
                    ) : null}
                  </CardAction>
                </CardHeader>
                <CardContent className="flex flex-col gap-2">
                  <h3 className="font-medium">Наполнение</h3>
                  <OrderUnitContentsView contents={candidate.unit.contents} />
                  <div className="mt-3 rounded-lg border bg-muted/20 p-3">
                    <div className="flex flex-wrap items-start justify-between gap-2">
                      <div>
                        <h3 className="font-medium">Срок аренды</h3>
                        <p className="text-sm text-muted-foreground">
                          Настройка срока для бытовки {candidate.unit.number}
                        </p>
                      </div>
                      <Badge variant={rentalTerm ? "secondary" : "outline"}>
                        {rentalTerm
                          ? monthLabel(rentalTerm.rentalMonths)
                          : "Не задан"}
                      </Badge>
                    </div>

                    <div className="mt-3 grid gap-3 sm:grid-cols-3">
                      <RentalMonthsPicker
                        key={`rental-term-${candidate.unit.id}-${order.version}`}
                        id={`rental-term-${candidate.unit.id}`}
                        label={`Срок аренды в месяцах для ${candidate.unit.number}`}
                        value={rentalMonthsValue(candidate)}
                        disabled={!canEdit || rentalTermsMutation.isPending}
                        onChange={(value) =>
                          setRentalMonthsValue(candidate.unit.id, value)
                        }
                      />
                      <div className="grid gap-1 text-sm">
                        <span className="font-medium">Дата отгрузки</span>
                        <output className="flex h-9 items-center rounded-md border border-input bg-background px-3">
                          {formatRentalDate(rentalTerm?.shipmentDate ?? null)}
                        </output>
                      </div>
                      <div className="grid gap-1 text-sm">
                        <span className="font-medium">Дата возврата</span>
                        <output className="flex h-9 items-center rounded-md border border-input bg-background px-3">
                          {formatRentalDate(rentalTerm?.returnDate ?? null)}
                        </output>
                      </div>
                    </div>

                    {canExtendRentalTerms && isShipped ? (
                      <div className="mt-3 grid gap-3 rounded-md border bg-background p-3 sm:grid-cols-[minmax(0,1fr)_minmax(12rem,16rem)] sm:items-end">
                        <div className="flex items-center gap-2 text-sm">
                          <Checkbox
                            id={`extend-rental-term-${candidate.unit.id}`}
                            aria-label={`Выбрать бытовку ${candidate.unit.number} для продления`}
                            checked={selectedForExtension}
                            disabled={extensionMutation.isPending}
                            onCheckedChange={(checked) =>
                              setExtensionUnitSelected(
                                candidate.unit.id,
                                checked === true
                              )
                            }
                          />
                          <label
                            htmlFor={`extend-rental-term-${candidate.unit.id}`}
                            className="cursor-pointer font-medium"
                          >
                            Продлить срок этой бытовки
                          </label>
                        </div>
                        <RentalMonthsPicker
                          key={`extend-rental-term-${candidate.unit.id}-${order.version}`}
                          id={`extend-rental-months-${candidate.unit.id}`}
                          label={`Продление в месяцах для ${candidate.unit.number}`}
                          value={extensionMonthsValue(candidate.unit.id)}
                          disabled={
                            !selectedForExtension || extensionMutation.isPending
                          }
                          onChange={(value) =>
                            setExtensionMonthsValue(candidate.unit.id, value)
                          }
                        />
                      </div>
                    ) : rentalTerm ? (
                      <p className="mt-3 text-sm text-muted-foreground">
                        {isShipped
                          ? "Срок можно продлить, пока заказ доступен для изменений."
                          : "Дата возврата будет рассчитана после отгрузки бытовки."}
                      </p>
                    ) : null}
                  </div>
                </CardContent>
                {canEdit ? (
                  <CardFooter className="border-t">
                    <Button
                      type="button"
                      size="sm"
                      variant="outline"
                      disabled={
                        removeUnitMutation.isPending &&
                        removeUnitMutation.variables?.candidate.unit.id ===
                          candidate.unit.id
                      }
                      onClick={() =>
                        removeUnitMutation.mutate({
                          candidate,
                          expectedVersion: order.version,
                          fingerprint: `remove-unit:${order.id}:${order.version}:${candidate.unit.id}`,
                        })
                      }
                    >
                      {removeUnitMutation.isPending &&
                      removeUnitMutation.variables?.candidate.unit.id ===
                        candidate.unit.id ? (
                        <HugeiconsIcon
                          icon={Loading03Icon}
                          data-icon="inline-start"
                          className="animate-spin"
                        />
                      ) : (
                        <HugeiconsIcon
                          icon={Delete02Icon}
                          data-icon="inline-start"
                        />
                      )}
                      Удалить из бронирования
                    </Button>
                  </CardFooter>
                ) : null}
              </Card>
            )
          })
        )}
      </section>

      <Card size="sm">
        <CardHeader>
          <CardTitle>История бронирования</CardTitle>
          <CardDescription>
            Аудит действий, резервирований и складских операций.
          </CardDescription>
        </CardHeader>
        <CardContent className="flex flex-col gap-3">
          {historyQuery.isLoading ? (
            <Skeleton className="h-24 w-full" />
          ) : historyQuery.isError ? (
            <FieldError>
              {historyQuery.error instanceof Error
                ? historyQuery.error.message
                : "Не удалось загрузить историю."}
            </FieldError>
          ) : history.length === 0 ? (
            <p className="text-sm text-muted-foreground">
              История изменений отсутствует.
            </p>
          ) : (
            history.map((event) => {
              const previous = formatAuditValues(event.previousValues)
              const next = formatAuditValues(event.newValues)
              const context = auditContext(
                event,
                order,
                selectedWarehouse ? selectedWarehouse.name : null
              )
              return (
                <div
                  key={event.id}
                  className="flex flex-col gap-1 rounded-lg border p-3 text-sm"
                >
                  <div className="flex flex-wrap items-center justify-between gap-2">
                    <span className="font-medium">
                      {ORDER_AUDIT_EVENT_LABELS[event.eventType]}
                    </span>
                    <span className="text-xs text-muted-foreground">
                      {formatOrderDateTime(event.occurredAt)}
                    </span>
                  </div>
                  {context ? (
                    <span className="text-muted-foreground">{context}</span>
                  ) : null}
                  {previous ? (
                    <span className="text-muted-foreground">
                      Было: {previous}
                    </span>
                  ) : null}
                  {next ? (
                    <span className="text-muted-foreground">Стало: {next}</span>
                  ) : null}
                </div>
              )
            })
          )}
        </CardContent>
      </Card>

      <OrderUnitEquipmentDialog
        open={contentsCandidate !== null}
        order={order}
        candidate={contentsCandidate}
        onOpenChange={(open) => {
          if (!open) setContentsUnitId(null)
        }}
        onConflict={refreshOrderBoundary}
      />

      <EditOrderDialog
        open={editDialogOpen}
        order={order}
        onOpenChange={setEditDialogOpen}
        onUpdated={applyProjection}
        onConflict={refreshOrderBoundary}
      />

      <AlertDialog open={cancelDialogOpen} onOpenChange={setCancelDialogOpen}>
        <AlertDialogContent>
          <AlertDialogHeader>
            <AlertDialogTitle>Удалить черновик бронирования?</AlertDialogTitle>
            <AlertDialogDescription>
              Это логическое удаление: бронирование будет отменено, а все
              активные резервирования бытовок будут освобождены. Действие
              фиксируется в истории.
            </AlertDialogDescription>
          </AlertDialogHeader>
          <AlertDialogFooter>
            <AlertDialogCancel>Не удалять</AlertDialogCancel>
            <AlertDialogAction
              disabled={cancelMutation.isPending}
              onClick={() =>
                cancelMutation.mutate({
                  expectedVersion: order.version,
                  fingerprint: `cancel:${order.id}:${order.version}`,
                })
              }
            >
              {cancelMutation.isPending ? "Удаление…" : "Удалить черновик"}
            </AlertDialogAction>
          </AlertDialogFooter>
        </AlertDialogContent>
      </AlertDialog>
    </div>
  )
}
