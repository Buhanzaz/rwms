import { useMemo, useState } from "react"
import { useQuery } from "@tanstack/react-query"
import {
  Add01Icon,
  ArrowDown01Icon,
  ArrowUp01Icon,
  RefreshIcon,
} from "@hugeicons/core-free-icons"
import { HugeiconsIcon } from "@hugeicons/react"
import { Link, useLocation, useNavigate } from "react-router-dom"

import { Button } from "@/components/ui/button"
import {
  Card,
  CardContent,
  CardDescription,
  CardHeader,
  CardTitle,
} from "@/components/ui/card"
import { Badge } from "@/components/ui/badge"
import { Input } from "@/components/ui/input"
import { Skeleton } from "@/components/ui/skeleton"
import {
  Table,
  TableBody,
  TableCell,
  TableHead,
  TableHeader,
  TableRow,
} from "@/components/ui/table"
import { listOrders, ORDERS_QUERY_KEY } from "@/features/orders/api/orders-api"
import { CreateOrderDialog } from "@/features/orders/components/create-order-dialog"
import {
  OrdersFilters,
  type OrdersFiltersState,
} from "@/features/orders/components/orders-filters"
import {
  formatOrderDateTime,
  ORDER_CLIENT_TYPES,
  ORDER_CLIENT_TYPE_LABELS,
  ORDER_STATUSES,
  ORDER_STATUS_LABELS,
} from "@/features/orders/domain/orders"
import {
  canViewAllOrders,
  getOrdersListNavigationLabel,
} from "@/features/orders/permissions/orders-permissions"
import { useOrdersModule } from "@/features/orders/orders-module-context"

const PAGE_SIZE = 20
const EMPTY_FILTERS: OrdersFiltersState = {
  statuses: [],
  clientTypes: [],
  warehouseIds: [],
  createdFrom: "",
  createdTo: "",
}

function dateBoundary(value: string, endOfDay: boolean) {
  if (!value) return undefined
  return new Date(
    `${value}T${endOfDay ? "23:59:59.999" : "00:00:00.000"}`
  ).toISOString()
}

type SortField =
  "number" | "client" | "manager" | "status" | "createdAt" | "updatedAt"

function SortButton({
  field,
  currentField,
  direction,
  children,
  onChange,
}: {
  field: SortField
  currentField: SortField
  direction: "asc" | "desc"
  children: React.ReactNode
  onChange: (field: SortField) => void
}) {
  const active = field === currentField
  return (
    <Button
      type="button"
      variant="ghost"
      size="sm"
      aria-label={`Сортировать: ${String(children)}`}
      onClick={() => onChange(field)}
    >
      {children}
      {active ? (
        <HugeiconsIcon
          icon={direction === "asc" ? ArrowUp01Icon : ArrowDown01Icon}
          data-icon="inline-end"
        />
      ) : null}
    </Button>
  )
}

export function OrdersListPage() {
  const { accessToken, currentUser, warehouses } = useOrdersModule()
  const location = useLocation()
  const navigate = useNavigate()
  const [search, setSearch] = useState("")
  const [page, setPage] = useState(0)
  const [sort, setSort] = useState<SortField>("updatedAt")
  const [direction, setDirection] = useState<"asc" | "desc">("desc")
  const [filters, setFilters] = useState<OrdersFiltersState>(EMPTY_FILTERS)
  const [createDialogOpen, setCreateDialogOpen] = useState(false)
  const routeRequestsCreate = location.pathname.endsWith("/orders/new")
  const canSearchManagers = canViewAllOrders(currentUser)
  const listTitle = getOrdersListNavigationLabel(currentUser)
  const statusOptions = useMemo(
    () =>
      ORDER_STATUSES.map((status) => ({
        value: status,
        label: ORDER_STATUS_LABELS[status],
      })),
    []
  )
  const clientTypeOptions = useMemo(
    () =>
      ORDER_CLIENT_TYPES.map((clientType) => ({
        value: clientType,
        label: ORDER_CLIENT_TYPE_LABELS[clientType],
      })),
    []
  )
  const warehouseOptions = useMemo(
    () =>
      warehouses.map((warehouse) => ({
        value: warehouse.id,
        label: `${warehouse.code} · ${warehouse.name}`,
      })),
    [warehouses]
  )

  const ordersQuery = useQuery({
    queryKey: [
      ...ORDERS_QUERY_KEY,
      "list",
      currentUser?.id ?? "unknown-user",
      page,
      PAGE_SIZE,
      search,
      sort,
      direction,
      filters,
    ],
    queryFn: () =>
      listOrders({
        accessToken: accessToken!,
        page,
        size: PAGE_SIZE,
        search,
        sort,
        direction,
        statuses: filters.statuses,
        clientTypes: filters.clientTypes,
        warehouseIds: filters.warehouseIds,
        createdFrom: dateBoundary(filters.createdFrom, false),
        createdTo: dateBoundary(filters.createdTo, true),
      }),
    enabled: Boolean(accessToken),
  })
  const ordersPage = ordersQuery.data

  function changeSort(field: SortField) {
    if (sort === field) {
      setDirection((current) => (current === "asc" ? "desc" : "asc"))
    } else {
      setSort(field)
      setDirection("asc")
    }
    setPage(0)
  }

  function setDialogOpen(open: boolean) {
    setCreateDialogOpen(open)
    if (!open && routeRequestsCreate) {
      navigate("/orders", { replace: true })
    }
  }

  return (
    <div className="flex h-full min-h-0 flex-col gap-4">
      <div className="flex flex-col gap-3 sm:flex-row sm:items-end sm:justify-between">
        <div className="flex flex-col gap-1">
          <h1 className="text-xl font-semibold">{listTitle}</h1>
          <p className="text-sm text-muted-foreground">
            Поиск, сортировка и ограничения доступа применяются сервисом
            логистики.
          </p>
        </div>
        <Button type="button" onClick={() => setCreateDialogOpen(true)}>
          <HugeiconsIcon icon={Add01Icon} data-icon="inline-start" />
          Создать новый заказ
        </Button>
      </div>

      <Input
        type="search"
        value={search}
        aria-label="Поиск заказов"
        placeholder={
          canSearchManagers
            ? "Номер заказа, клиент или менеджер"
            : "Номер заказа или клиент"
        }
        className="max-w-xl"
        onChange={(event) => {
          setSearch(event.target.value)
          setPage(0)
        }}
      />

      <OrdersFilters
        filters={filters}
        statusOptions={statusOptions}
        clientTypeOptions={clientTypeOptions}
        warehouseOptions={warehouseOptions}
        onChange={(nextFilters) => {
          setFilters(nextFilters)
          setPage(0)
        }}
      />

      {ordersQuery.isLoading ? (
        <Card className="min-h-0 flex-1" size="sm">
          <CardHeader>
            <CardTitle>Загрузка заказов</CardTitle>
            <CardDescription>Получаем актуальную выборку…</CardDescription>
          </CardHeader>
          <CardContent className="flex flex-col gap-3">
            {Array.from({ length: 6 }, (_, index) => (
              <Skeleton key={index} className="h-10 w-full" />
            ))}
          </CardContent>
        </Card>
      ) : ordersQuery.isError ? (
        <Card size="sm">
          <CardHeader>
            <CardTitle>Не удалось загрузить заказы</CardTitle>
            <CardDescription role="alert">
              {ordersQuery.error instanceof Error
                ? ordersQuery.error.message
                : "Сервис заказов недоступен."}
            </CardDescription>
          </CardHeader>
          <CardContent>
            <Button
              type="button"
              variant="outline"
              onClick={() => void ordersQuery.refetch()}
            >
              <HugeiconsIcon icon={RefreshIcon} data-icon="inline-start" />
              Повторить
            </Button>
          </CardContent>
        </Card>
      ) : !ordersPage || ordersPage.content.length === 0 ? (
        <Card size="sm">
          <CardHeader>
            <CardTitle>Заказы не найдены</CardTitle>
            <CardDescription>
              {search.trim() ||
              filters.statuses.length > 0 ||
              filters.clientTypes.length > 0 ||
              filters.warehouseIds.length > 0 ||
              filters.createdFrom ||
              filters.createdTo
                ? "Измените поисковый запрос или фильтры."
                : "Создайте первый заказ для начала работы."}
            </CardDescription>
          </CardHeader>
        </Card>
      ) : (
        <Card className="min-h-0 flex-1 overflow-auto py-0" size="sm">
          <CardContent className="px-0">
            <Table>
              <TableHeader>
                <TableRow>
                  <TableHead>
                    <SortButton
                      field="number"
                      currentField={sort}
                      direction={direction}
                      onChange={changeSort}
                    >
                      Номер
                    </SortButton>
                  </TableHead>
                  <TableHead>
                    <SortButton
                      field="client"
                      currentField={sort}
                      direction={direction}
                      onChange={changeSort}
                    >
                      Клиент
                    </SortButton>
                  </TableHead>
                  <TableHead>Тип клиента</TableHead>
                  <TableHead>
                    <SortButton
                      field="manager"
                      currentField={sort}
                      direction={direction}
                      onChange={changeSort}
                    >
                      Менеджер
                    </SortButton>
                  </TableHead>
                  <TableHead>Склад</TableHead>
                  <TableHead>Бытовки</TableHead>
                  <TableHead>
                    <SortButton
                      field="status"
                      currentField={sort}
                      direction={direction}
                      onChange={changeSort}
                    >
                      Статус
                    </SortButton>
                  </TableHead>
                  <TableHead>
                    <SortButton
                      field="createdAt"
                      currentField={sort}
                      direction={direction}
                      onChange={changeSort}
                    >
                      Создан
                    </SortButton>
                  </TableHead>
                  <TableHead>
                    <SortButton
                      field="updatedAt"
                      currentField={sort}
                      direction={direction}
                      onChange={changeSort}
                    >
                      Изменён
                    </SortButton>
                  </TableHead>
                </TableRow>
              </TableHeader>
              <TableBody>
                {ordersPage.content.map((order) => {
                  const warehouse = warehouses.find(
                    (candidate) => candidate.id === order.warehouseId
                  )
                  return (
                    <TableRow key={order.id}>
                      <TableCell className="font-medium">
                        <Link
                          to={`/orders/${order.id}`}
                          className="underline-offset-4 hover:underline"
                        >
                          {order.number}
                        </Link>
                      </TableCell>
                      <TableCell>{order.client.displayName}</TableCell>
                      <TableCell>
                        {ORDER_CLIENT_TYPE_LABELS[order.client.type]}
                      </TableCell>
                      <TableCell>{order.managerDisplayName}</TableCell>
                      <TableCell>
                        {warehouse
                          ? `${warehouse.code} · ${warehouse.name}`
                          : order.warehouseId
                            ? "Недоступный склад"
                            : "Не выбран"}
                      </TableCell>
                      <TableCell>{order.unitCount}</TableCell>
                      <TableCell>
                        <Badge
                          variant={
                            order.status === "DRAFT" ? "secondary" : "outline"
                          }
                        >
                          {ORDER_STATUS_LABELS[order.status]}
                        </Badge>
                      </TableCell>
                      <TableCell>
                        {formatOrderDateTime(order.createdAt)}
                      </TableCell>
                      <TableCell>
                        {formatOrderDateTime(order.updatedAt)}
                      </TableCell>
                    </TableRow>
                  )
                })}
              </TableBody>
            </Table>
          </CardContent>
        </Card>
      )}

      {ordersQuery.data ? (
        <div className="flex flex-wrap items-center justify-between gap-3 text-sm text-muted-foreground">
          <span>
            Показано {ordersQuery.data.content.length} из{" "}
            {ordersQuery.data.totalElements}
          </span>
          <div className="flex items-center gap-2">
            <Button
              type="button"
              size="sm"
              variant="outline"
              disabled={page === 0 || ordersQuery.isFetching}
              onClick={() => setPage((current) => Math.max(0, current - 1))}
            >
              Назад
            </Button>
            <span>
              Страница {ordersQuery.data.totalPages === 0 ? 0 : page + 1} из{" "}
              {ordersQuery.data.totalPages}
            </span>
            <Button
              type="button"
              size="sm"
              variant="outline"
              disabled={
                page + 1 >= ordersQuery.data.totalPages ||
                ordersQuery.isFetching
              }
              onClick={() => setPage((current) => current + 1)}
            >
              Вперёд
            </Button>
          </div>
        </div>
      ) : null}

      <CreateOrderDialog
        open={routeRequestsCreate || createDialogOpen}
        onOpenChange={setDialogOpen}
        onCreated={(order) => navigate(`/orders/${order.id}`)}
      />
    </div>
  )
}
