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
import { Skeleton } from "@/components/ui/skeleton"
import {
  deleteOrder,
  getOrder,
  listOrderHistory,
  ORDERS_QUERY_KEY,
  removeOrderUnit,
  saveOrder,
} from "@/features/orders/api/orders-api"
import { OrderCommandIdentityRegistry } from "@/features/orders/api/order-command-identity"
import { AddCabinsDialog } from "@/features/orders/components/add-cabins-dialog"
import { OrderDeliveryDialog } from "@/features/orders/components/order-delivery-dialog"
import { OrderRentalTermExtensionDialog } from "@/features/orders/components/order-rental-term-extension-dialog"
import { OrderUnitReplacementDialog } from "@/features/orders/components/order-unit-replacement-dialog"
import { isOrderDeliveryComplete } from "@/features/orders/domain/order-delivery-readiness"
import { OrderUnitDossierEvidence } from "@/features/orders/components/order-unit-dossier-evidence"
import {
  OrderUnitContentsView,
  OrderUnitEquipmentDialog,
} from "@/features/orders/components/order-unit-contents"
import {
  formatOrderDateTime,
  ORDER_AUDIT_EVENT_LABELS,
  ORDER_CLIENT_TYPE_LABELS,
  ORDER_STATUS_LABELS,
  type OrderDetail,
  type OrderMovement,
  type OrderUnitCandidate,
} from "@/features/orders/domain/orders"
import { useOrdersModule } from "@/features/orders/orders-module-context"
import { RentalItemStatusBadge } from "@/features/rental-items/rental-item-status-badge"
import { ApiError } from "@/lib/api-client"

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

const MOVEMENT_TYPE_LABELS: Record<OrderMovement["documentType"], string> = {
  SHIPMENT: "Отвоз клиенту",
  RETURN: "Возврат от клиента",
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

function formatRentalDate(value: string | null) {
  if (!value) return "Не назначена"
  const [year, month, day] = value.split("-")
  return `${day}.${month}.${year}`
}

function formatDesiredWindow({
  startDate,
  endDate,
}: OrderDetail["desiredDeliveryWindows"][number]) {
  return (
    startDate === endDate
      ? formatRentalDate(startDate)
      : `${formatRentalDate(startDate)} — ${formatRentalDate(endDate)}`
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

export function OrderDetailPage() {
  const { orderId } = useParams<{ orderId: string }>()
  const navigate = useNavigate()
  const queryClient = useQueryClient()
  const { accessToken, currentUser, warehouses } = useOrdersModule()
  const [contentsUnitId, setContentsUnitId] = useState<string | null>(null)
  const [addCabinsDialogOpen, setAddCabinsDialogOpen] = useState(false)
  const [replacementDialogOpen, setReplacementDialogOpen] = useState(false)
  const [deliveryDialogOpen, setDeliveryDialogOpen] = useState(false)
  const [cancelDialogOpen, setCancelDialogOpen] = useState(false)
  const [extensionDialogOpen, setExtensionDialogOpen] = useState(false)
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
      queryKey: [...ORDERS_QUERY_KEY, "history", subjectId, orderId],
    })
  }

  function refreshOrderBoundary() {
    void detailQuery.refetch()
    void historyQuery.refetch()
  }

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
        "Заказ сохранён. Откройте «Задания», чтобы создать отгрузку; сохранение само не создаёт рейс."
      )
    },
    onError: (error) => {
      toast.error(
        error instanceof Error ? error.message : "Не удалось сохранить заказ."
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

  const contentsCandidate =
    selectedUnits.find((candidate) => candidate.unit.id === contentsUnitId) ??
    null
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
  const canReplaceUnits = order.permissions.canReplaceUnits
  const canCancel = canEdit && order.status === "DRAFT"
  const desiredDeliveryWindows = order.desiredDeliveryWindows ?? []
  const orderMovements = order.movements ?? []
  const isDraft = order.status === "DRAFT"
  const contactComplete = isOrderDeliveryComplete(order)
  const warehouseSelected = order.warehouseId !== null
  const cabinsSelected = selectedUnits.length > 0
  const draftReady = contactComplete && warehouseSelected && cabinsSelected
  const hasClientRentalTerms =
    cabinsSelected &&
    selectedUnits.every(
      (candidate) => (candidate.rentalTerm?.rentalMonths ?? 0) > 0
    )
  const hasEligibleShippedRentalTerm = selectedUnits.some((candidate) => {
    const term = candidate.rentalTerm
    return (
      candidate.unit.status === "RENTED" &&
      term?.shipmentDate !== null &&
      term?.shipmentDate !== undefined &&
      term?.returnDate !== null &&
      term?.returnDate !== undefined
    )
  })
  const canExtendRentalTerms =
    order.permissions.canExtendRentalTerms && hasEligibleShippedRentalTerm
  const deliveryActionLabel = contactComplete
    ? "Изменить данные заказа"
    : "Добавить данные заказа"

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

      {canEdit || canReplaceUnits || canExtendRentalTerms ? (
        <div className="flex flex-wrap justify-end gap-2">
          {canEdit ? (
            <Button type="button" onClick={() => setAddCabinsDialogOpen(true)}>
              <HugeiconsIcon icon={Add01Icon} data-icon="inline-start" />
              Добавить бытовки
            </Button>
          ) : null}
          {canReplaceUnits ? (
            <Button
              type="button"
              variant="outline"
              onClick={() => setReplacementDialogOpen(true)}
            >
              Заменить бытовки
            </Button>
          ) : null}
          {canExtendRentalTerms ? (
            <Button
              type="button"
              variant="outline"
              onClick={() => setExtensionDialogOpen(true)}
            >
              Продлить аренду
            </Button>
          ) : null}
          {canEdit && isDraft ? (
            <Button
              type="button"
              disabled={!draftReady || saveMutation.isPending}
              aria-describedby="order-draft-readiness-description"
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
              {saveMutation.isPending ? "Сохраняем…" : "Сохранить заказ"}
            </Button>
          ) : null}
          {canEdit && order.status === "SAVED" ? (
            <Button asChild>
              <Link to="/logistics/order-tasks">Перейти к заданиям</Link>
            </Button>
          ) : null}
          {canEdit && (!isDraft || contactComplete) ? (
            <Button
              type="button"
              variant="outline"
              onClick={() => setDeliveryDialogOpen(true)}
            >
              <HugeiconsIcon icon={PencilEdit01Icon} data-icon="inline-start" />
              {deliveryActionLabel}
            </Button>
          ) : null}
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

      {isDraft ? (
        <Card size="sm" aria-labelledby="order-draft-readiness-title">
          <CardHeader>
            <h2
              id="order-draft-readiness-title"
              className="leading-none font-semibold"
            >
              Готовность к сохранению
            </h2>
            <CardDescription id="order-draft-readiness-description">
              Черновик можно сохранить после заполнения каждого пункта ниже.
            </CardDescription>
          </CardHeader>
          <CardContent>
            <ul className="flex flex-col gap-3">
              <li className="flex flex-wrap items-center justify-between gap-3 rounded-lg border p-3">
                <div>
                  <p className="font-medium">Контакт заказа</p>
                  <p className="text-sm text-muted-foreground">
                    {contactComplete
                      ? "Контактный телефон заполнен."
                      : "Укажите контактный телефон заказа."}
                  </p>
                </div>
                {contactComplete ? (
                  <Badge variant="outline">Готово</Badge>
                ) : canEdit ? (
                  <Button
                    type="button"
                    size="sm"
                    onClick={() => setDeliveryDialogOpen(true)}
                  >
                    Добавить данные заказа
                  </Button>
                ) : (
                  <Badge variant="secondary">Не заполнено</Badge>
                )}
              </li>
              <li className="flex flex-wrap items-center justify-between gap-3 rounded-lg border p-3">
                <div>
                  <p className="font-medium">Склад</p>
                  <p className="text-sm text-muted-foreground">
                    {warehouseSelected
                      ? "Склад выбран."
                      : "Определится по первой выбранной бытовке."}
                  </p>
                </div>
                <Badge variant={warehouseSelected ? "outline" : "secondary"}>
                  {warehouseSelected ? "Готово" : "Не выбран"}
                </Badge>
              </li>
              <li className="flex flex-wrap items-center justify-between gap-3 rounded-lg border p-3">
                <div>
                  <p className="font-medium">Бытовки</p>
                  <p className="text-sm text-muted-foreground">
                    {cabinsSelected
                      ? `Выбрано бытовок: ${selectedUnits.length}.`
                      : "Добавьте хотя бы одну бытовку в бронирование."}
                  </p>
                </div>
                <Badge variant={cabinsSelected ? "outline" : "secondary"}>
                  {cabinsSelected ? "Готово" : "Не выбраны"}
                </Badge>
              </li>
              <li className="flex flex-wrap items-center justify-between gap-3 rounded-lg border p-3">
                <div>
                  <p className="font-medium">Сроки аренды</p>
                  <p className="text-sm text-muted-foreground">
                    {hasClientRentalTerms
                      ? "Срок выбран клиентом в представлении."
                      : "Получите выбор клиента в представлении: менеджер не назначает срок вручную."}
                  </p>
                </div>
                <Badge variant={hasClientRentalTerms ? "outline" : "secondary"}>
                  {hasClientRentalTerms
                    ? "Выбрано клиентом"
                    : "Ожидается выбор"}
                </Badge>
              </li>
            </ul>
          </CardContent>
        </Card>
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
          <div>
            <p className="text-xs text-muted-foreground">Склад заказа</p>
            <p className="font-medium">
              {selectedWarehouse?.name ?? "Определится по первой бытовке"}
            </p>
          </div>
          <div>
            <p className="text-xs text-muted-foreground">
              Основное контактное лицо клиента
            </p>
            <p className="font-medium">
              {order.client.contactPerson ?? order.client.displayName}
            </p>
            <p className="text-sm text-muted-foreground">
              {order.client.phone ?? "Телефон не указан"}
            </p>
          </div>
          <div className="sm:col-span-2">
            <p className="text-xs text-muted-foreground">
              Дополнительные контакты клиента
            </p>
            {order.client.additionalContacts.length === 0 ? (
              <p className="font-medium">Не указаны</p>
            ) : (
              <div className="mt-1 flex flex-wrap gap-2">
                {order.client.additionalContacts.map((contact, index) => (
                  <Badge
                    key={`${contact.name}:${contact.phone}:${index}`}
                    variant="outline"
                  >
                    {contact.name}: {contact.phone}
                  </Badge>
                ))}
              </div>
            )}
          </div>
        </CardContent>
      </Card>

      <Card size="sm">
        <CardHeader>
          <CardTitle>Доставка и приёмка клиента</CardTitle>
          <CardDescription>
            Клиент указывает адрес, координаты и дополнительные контакты в
            представлении; менеджер видит их здесь только для чтения.
          </CardDescription>
        </CardHeader>
        <CardContent className="grid gap-4 sm:grid-cols-2 xl:grid-cols-4">
          <div className="sm:col-span-2">
            <p className="text-xs text-muted-foreground">Адрес</p>
            <p className="font-medium">
              {order.deliveryAddress ?? "Не указан"}
            </p>
            {order.latitude !== null && order.longitude !== null ? (
              <p className="text-sm text-muted-foreground">
                Координаты: {order.latitude}, {order.longitude}
              </p>
            ) : (
              <p className="text-sm text-muted-foreground">
                Координаты не указаны
              </p>
            )}
          </div>
          <div>
            <p className="text-xs text-muted-foreground">Контактный телефон</p>
            <p className="font-medium">{order.contactPhone ?? "Не указан"}</p>
          </div>
          <div className="sm:col-span-2">
            <p className="text-xs text-muted-foreground">
              Дополнительные контакты заказа
            </p>
            {order.additionalContacts.length === 0 ? (
              <p className="font-medium">Не указаны</p>
            ) : (
              <div className="mt-1 flex flex-wrap gap-2">
                {order.additionalContacts.map((contact, index) => (
                  <Badge
                    key={`${contact.name}:${contact.phone}:${index}`}
                    variant="outline"
                  >
                    {contact.name}: {contact.phone}
                  </Badge>
                ))}
              </div>
            )}
          </div>
          <div>
            <p className="text-xs text-muted-foreground">Комментарий</p>
            <p className="font-medium">{order.comment ?? "Нет"}</p>
          </div>
          <div className="sm:col-span-2 xl:col-span-4">
            <p className="text-xs text-muted-foreground">
              Выбрано клиентом в представлении
            </p>
            <div className="mt-1 flex flex-wrap gap-2">
              {desiredDeliveryWindows.length === 0 ? (
                <span className="text-sm font-medium">Не указаны</span>
              ) : (
                desiredDeliveryWindows.map((window, index) => (
                  <Badge
                    key={`${window.startDate}:${window.endDate}:${index}`}
                    variant="outline"
                  >
                    {formatDesiredWindow(window)}
                  </Badge>
                ))
              )}
            </div>
            <p className="mt-2 text-sm text-muted-foreground">
              Желаемые даты доступны только для просмотра и не являются
              назначенным расписанием ходки.
            </p>
          </div>
        </CardContent>
      </Card>

      <Card size="sm">
        <CardHeader>
          <CardTitle>Логистика заказа</CardTitle>
          <CardDescription>
            Плановые и фактические отвозы и возвраты из logistics-service.
          </CardDescription>
        </CardHeader>
        <CardContent>
          {orderMovements.length === 0 ? (
            <p className="text-sm text-muted-foreground">
              Отвозы и возвраты ещё не зарегистрированы.
            </p>
          ) : (
            <div className="flex flex-col gap-3">
              {orderMovements.map((movement) => (
                <div
                  key={movement.documentId}
                  className="rounded-lg border p-3"
                >
                  <div className="flex flex-wrap items-center justify-between gap-2">
                    <p className="font-medium">
                      {MOVEMENT_TYPE_LABELS[movement.documentType] ??
                        movement.documentType}
                    </p>
                    <Badge variant="outline">{movement.state}</Badge>
                  </div>
                  <dl className="mt-3 grid gap-2 text-sm sm:grid-cols-2 xl:grid-cols-4">
                    <div>
                      <dt className="text-muted-foreground">Запланировано</dt>
                      <dd className="font-medium">
                        {movement.scheduledDate
                          ? formatRentalDate(movement.scheduledDate)
                          : "Не назначено"}
                      </dd>
                    </div>
                    <div>
                      <dt className="text-muted-foreground">Выполнено</dt>
                      <dd className="font-medium">
                        {movement.actualAt
                          ? formatOrderDateTime(movement.actualAt)
                          : "Ещё не выполнено"}
                      </dd>
                    </div>
                    <div className="sm:col-span-2">
                      <dt className="text-muted-foreground">Бытовки</dt>
                      <dd className="font-medium">
                        {movement.cabins
                          .map((cabin) => {
                            const candidate = order.units.find(
                              (item) => item.unit.id === cabin.rentalItemId
                            )
                            return `${candidate?.unit.number ?? cabin.rentalItemId} · ${cabin.lineState}`
                          })
                          .join(", ") || "Не указаны"}
                      </dd>
                    </div>
                  </dl>
                </div>
              ))}
            </div>
          )}
        </CardContent>
      </Card>

      <section
        className="flex flex-col gap-3"
        aria-labelledby="order-units-title"
      >
        <div>
          <h2 id="order-units-title" className="text-lg font-semibold">
            Бытовки в бронировании
          </h2>
          <p className="text-sm text-muted-foreground">
            Для каждой бытовки отдельно показаны резерв, заказанная мебель и
            фактическое наполнение. Дополнительные бытовки добавляются тем же
            order-linked процессом.
          </p>
        </div>
        {selectedUnits.length === 0 ? (
          <Card size="sm">
            <CardHeader>
              <CardTitle>Бытовки ещё не добавлены</CardTitle>
              <CardDescription>
                Используйте кнопку «Добавить бытовки» и выберите AI-чат или
                обычное бронирование. Дату и срок аренды клиент выберет в
                представлении.
              </CardDescription>
            </CardHeader>
          </Card>
        ) : (
          selectedUnits.map((candidate) => {
            const rentalTerm = candidate.rentalTerm ?? null
            const isShipped =
              candidate.unit.status === "RENTED" &&
              rentalTerm?.shipmentDate !== null &&
              rentalTerm?.shipmentDate !== undefined &&
              rentalTerm?.returnDate !== null &&
              rentalTerm?.returnDate !== undefined

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
                    <Badge variant="secondary">
                      Резерв бытовки:{" "}
                      {candidate.reservationState
                        ? RESERVATION_STATE_LABELS[candidate.reservationState]
                        : "не активен"}
                    </Badge>
                    {canEdit ? (
                      <Button
                        type="button"
                        size="sm"
                        variant="outline"
                        aria-label={`Добавить мебель ${candidate.unit.number}`}
                        onClick={() => setContentsUnitId(candidate.unit.id)}
                      >
                        <HugeiconsIcon
                          icon={Add01Icon}
                          data-icon="inline-start"
                        />
                        {candidate.desiredContents.length > 0
                          ? "Изменить мебель"
                          : "Добавить мебель"}
                      </Button>
                    ) : null}
                  </CardAction>
                </CardHeader>
                <CardContent className="flex flex-col gap-2">
                  <h3 className="font-medium">Мебель по заказу</h3>
                  {candidate.desiredContents.length === 0 ? (
                    <p className="text-sm text-muted-foreground">
                      Мебель не выбрана.
                    </p>
                  ) : (
                    <div className="flex flex-wrap gap-2">
                      {candidate.desiredContents.map((equipment) => (
                        <Badge key={equipment.equipmentId} variant="outline">
                          {equipment.equipmentName} × {equipment.quantity} ·
                          резерв{" "}
                          {RESERVATION_STATE_LABELS[
                            equipment.reservationState
                          ] ?? equipment.reservationState}
                        </Badge>
                      ))}
                    </div>
                  )}
                  <h3 className="mt-2 font-medium">
                    Фактически находится в бытовке
                  </h3>
                  <OrderUnitContentsView contents={candidate.unit.contents} />
                  {isShipped && rentalTerm ? (
                    <div className="mt-3 rounded-lg border bg-muted/20 p-3">
                      <div className="flex flex-wrap items-start justify-between gap-2">
                        <div>
                          <h3 className="font-medium">Срок аренды</h3>
                          <p className="text-sm text-muted-foreground">
                            Данные сформированы после отгрузки бытовки.
                          </p>
                        </div>
                        <Badge variant="secondary">
                          {monthLabel(rentalTerm.rentalMonths)}
                        </Badge>
                      </div>
                      <dl className="mt-3 grid gap-3 text-sm sm:grid-cols-3">
                        <div>
                          <dt className="text-muted-foreground">
                            Длительность
                          </dt>
                          <dd className="font-medium">
                            {monthLabel(rentalTerm.rentalMonths)}
                          </dd>
                        </div>
                        <div>
                          <dt className="text-muted-foreground">
                            Дата отгрузки
                          </dt>
                          <dd className="font-medium">
                            {formatRentalDate(rentalTerm.shipmentDate)}
                          </dd>
                        </div>
                        <div>
                          <dt className="text-muted-foreground">
                            Расчётная дата возврата
                          </dt>
                          <dd className="font-medium">
                            {formatRentalDate(rentalTerm.returnDate)}
                          </dd>
                        </div>
                      </dl>
                    </div>
                  ) : (
                    <p className="mt-3 text-sm text-muted-foreground">
                      Срок аренды выбирает клиент в представлении. Данные
                      отгрузки и расчётная дата возврата появятся после
                      фактической отгрузки бытовки.
                    </p>
                  )}
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

      <OrderUnitDossierEvidence
        accessToken={accessToken!}
        orderId={order.id}
        orderCreatedAt={order.createdAt}
        candidates={selectedUnits}
        movements={orderMovements}
      />

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

      <AddCabinsDialog
        open={addCabinsDialogOpen}
        clientId={order.client.id}
        orderId={order.id}
        onOpenChange={setAddCabinsDialogOpen}
      />

      <OrderRentalTermExtensionDialog
        open={extensionDialogOpen}
        order={order}
        onOpenChange={setExtensionDialogOpen}
        onUpdated={applyProjection}
        onConflict={refreshOrderBoundary}
      />

      <OrderUnitReplacementDialog
        open={replacementDialogOpen}
        order={order}
        onOpenChange={setReplacementDialogOpen}
        onReplaced={applyProjection}
        onConflict={refreshOrderBoundary}
      />

      <OrderDeliveryDialog
        open={deliveryDialogOpen}
        order={order}
        onOpenChange={setDeliveryDialogOpen}
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
