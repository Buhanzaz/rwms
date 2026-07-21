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
  selectOrderWarehouse,
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

function formatAuditValues(values: Record<string, unknown> | null) {
  if (!values || Object.keys(values).length === 0) return null
  return Object.entries(values)
    .map(([key, value]) => `${key}: ${String(value)}`)
    .join(" · ")
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
    enabled: Boolean(accessToken && orderId && order?.warehouseId),
    refetchInterval: order?.warehouseId
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
      toast.success("Склад заказа выбран.")
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
      toast.success(`Бытовка ${candidate.unit.number} добавлена в заказ.`)
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
          "Бытовка уже занята другим заказом. Список доступных бытовок обновлён."
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
        "Черновик удалён: заказ логически отменён, резервирования освобождены."
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

  const selectedUnits = useMemo(
    () => order?.units.filter((candidate) => candidate.added) ?? [],
    [order?.units]
  )
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
          <CardTitle>Заказ не выбран</CardTitle>
          <CardDescription>
            В URL отсутствует идентификатор заказа.
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
          <CardTitle>Заказ недоступен</CardTitle>
          <CardDescription role="alert">
            {detailQuery.error instanceof Error
              ? detailQuery.error.message
              : "Заказ не найден или у вас нет доступа."}
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
  const canEdit = order.permissions.canEdit && order.status === "DRAFT"
  const warehouseLocked = order.unitCount > 0

  return (
    <div className="flex h-full min-h-0 flex-col gap-4 overflow-auto pr-1">
      <div className="flex flex-wrap items-center justify-between gap-3">
        <div className="flex items-center gap-3">
          <Button asChild size="icon-sm" variant="outline">
            <Link to="/orders" aria-label="Вернуться к списку заказов">
              <HugeiconsIcon icon={ArrowLeft01Icon} />
            </Link>
          </Button>
          <div>
            <div className="flex flex-wrap items-center gap-2">
              <h1 className="text-xl font-semibold">Заказ {order.number}</h1>
              <Badge
                variant={order.status === "DRAFT" ? "secondary" : "outline"}
              >
                {ORDER_STATUS_LABELS[order.status]}
              </Badge>
            </div>
            <p className="text-sm text-muted-foreground">
              Изменён {formatOrderDateTime(order.updatedAt)}
            </p>
          </div>
        </div>
        {canEdit ? (
          <div className="flex flex-wrap items-center gap-2">
            <Button
              type="button"
              variant="outline"
              onClick={() => setEditDialogOpen(true)}
            >
              <HugeiconsIcon icon={PencilEdit01Icon} data-icon="inline-start" />
              Редактировать заказ
            </Button>
            <Button
              type="button"
              variant="destructive"
              onClick={() => setCancelDialogOpen(true)}
            >
              <HugeiconsIcon icon={Delete02Icon} data-icon="inline-start" />
              Удалить черновик
            </Button>
          </div>
        ) : null}
      </div>

      <Card size="sm">
        <CardHeader>
          <CardTitle>Основные данные</CardTitle>
          <CardDescription>
            Менеджер и автор сохранены независимо друг от друга.
          </CardDescription>
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
            {order.permissions.canViewOtherManagers ? (
              <p className="text-xs text-muted-foreground">{order.managerId}</p>
            ) : null}
          </div>
          <div>
            <p className="text-xs text-muted-foreground">Автор</p>
            <p className="font-medium">{order.createdByDisplayName}</p>
            <p className="text-xs text-muted-foreground">{order.createdBy}</p>
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
          <CardTitle>Склад заказа</CardTitle>
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
              aria-label="Склад заказа"
              className="w-full max-w-xl"
            >
              <SelectValue placeholder="Выберите склад" />
            </SelectTrigger>
            <SelectContent>
              <SelectGroup>
                {warehouses.map((warehouse) => (
                  <SelectItem key={warehouse.id} value={warehouse.id}>
                    {warehouse.code} · {warehouse.name} · {warehouse.city}
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

      {!order.warehouseId ? (
        <Card size="sm">
          <CardHeader>
            <CardTitle>Склад не выбран</CardTitle>
            <CardDescription>
              Сначала выберите склад заказа, затем станет доступен поиск
              бытовок.
            </CardDescription>
          </CardHeader>
        </Card>
      ) : (
        <Card className="min-h-[36rem]" size="sm">
          <CardHeader>
            <CardTitle>Выбор бытовок</CardTitle>
            <CardDescription>
              Показаны свободные бытовки выбранного склада и бытовки этого
              заказа.
            </CardDescription>
          </CardHeader>
          <CardContent className="flex min-h-0 flex-1 flex-col gap-4">
            <Input
              type="search"
              value={unitSearch}
              aria-label="Поиск бытовок для заказа"
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
                Свободные бытовки не найдены.
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
                    toast.error("Изменение этого заказа запрещено.")
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
            Бытовки в заказе
          </h2>
          <p className="text-sm text-muted-foreground">
            Наполнение показано только для просмотра. Изменения открываются
            отдельной кнопкой с плюсом рядом со статусом.
          </p>
        </div>
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
          selectedUnits.map((candidate) => (
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
                  <Button
                    type="button"
                    size="icon-sm"
                    variant="outline"
                    disabled={!canEdit}
                    aria-label={`Изменить наполнение ${candidate.unit.number}`}
                    onClick={() => setContentsUnitId(candidate.unit.id)}
                  >
                    <HugeiconsIcon icon={Add01Icon} />
                  </Button>
                </CardAction>
              </CardHeader>
              <CardContent className="flex flex-col gap-2">
                <h3 className="font-medium">Наполнение</h3>
                <OrderUnitContentsView contents={candidate.unit.contents} />
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
                    Удалить из заказа
                  </Button>
                </CardFooter>
              ) : null}
            </Card>
          ))
        )}
      </section>

      <Card size="sm">
        <CardHeader>
          <CardTitle>История заказа</CardTitle>
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
                  <span className="text-muted-foreground">
                    Actor: {event.actorSubjectId} · {event.actorRole}
                  </span>
                  <span className="text-muted-foreground">
                    {event.subjectType}: {event.subjectId}
                  </span>
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
            <AlertDialogTitle>Удалить черновик заказа?</AlertDialogTitle>
            <AlertDialogDescription>
              Это логическое удаление: заказ будет отменён, а все активные
              резервирования бытовок будут освобождены. Действие фиксируется в
              истории.
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
