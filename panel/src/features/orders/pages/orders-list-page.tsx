import { type ReactNode, useMemo, useState } from "react"
import { useQuery } from "@tanstack/react-query"
import { Add01Icon, RefreshIcon } from "@hugeicons/core-free-icons"
import { HugeiconsIcon } from "@hugeicons/react"
import { ArrowUpDown } from "lucide-react"
import { Link, useLocation, useNavigate } from "react-router-dom"

import {
  GRID_CELL_CLASS,
  GRID_HEADER_CELL_CLASS,
  GRID_HEADER_CLASS,
  GRID_TABLE_ROW_CLASS,
  GridSortButton,
} from "@/components/grid-sort-button"
import {
  PageToolbar,
  PageToolbarActions,
  PageToolbarContent,
} from "@/components/page-toolbar"
import { Button } from "@/components/ui/button"
import {
  Card,
  CardAction,
  CardContent,
  CardDescription,
  CardHeader,
  CardTitle,
} from "@/components/ui/card"
import { Badge } from "@/components/ui/badge"
import { Input } from "@/components/ui/input"
import { Skeleton } from "@/components/ui/skeleton"
import { listOrders, ORDERS_QUERY_KEY } from "@/features/orders/api/orders-api"
import { CreateOrderDialog } from "@/features/orders/components/create-order-dialog"
import {
  OrdersFilters,
  OrdersFiltersToggle,
  type OrdersFiltersState,
} from "@/features/orders/components/orders-filters"
import {
  formatOrderDateTime,
  ORDER_CLIENT_TYPES,
  ORDER_CLIENT_TYPE_LABELS,
  ORDER_STATUSES,
  ORDER_STATUS_LABELS,
  type OrderSummary,
} from "@/features/orders/domain/orders"
import { ORDERS_NAVIGATION } from "@/features/orders/permissions/orders-permissions"
import { useOrdersModule } from "@/features/orders/orders-module-context"
import { listDossierActorDisplays } from "@/features/rental-items/dossier/actor/actor-display-api"
import type { DossierActorDisplay } from "@/features/rental-items/dossier/actor/actor-display"
import { useResponsiveFiltersOpen } from "@/hooks/use-responsive-filters-open"

const LIST_SIZE = 100
const EMPTY_FILTERS: OrdersFiltersState = {
  statuses: [],
  clientTypes: [],
  warehouseIds: [],
  createdFrom: "",
  createdTo: "",
}

function formatWarehouseShortName(warehouse: { name: string; city: string }) {
  const city = warehouse.city.trim().toLocaleLowerCase("ru-RU")
  if (city.includes("санкт-петербург")) {
    return "СПб"
  }
  if (city === "москва") {
    return "Мск"
  }
  return warehouse.name
}

function formatManagerName(actor: DossierActorDisplay | undefined) {
  if (!actor) return "Не указано"
  const name = [actor.lastName, actor.firstName]
    .map((part) => part?.trim())
    .filter((part): part is string => Boolean(part))
    .join(" ")
  return name || "Не указано"
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
  label,
  onChange,
}: {
  field: SortField
  currentField: SortField
  direction: "asc" | "desc"
  label: string
  onChange: (field: SortField) => void
}) {
  const active = field === currentField
  return (
    <GridSortButton
      label={label}
      direction={active ? direction : null}
      onClick={() => onChange(field)}
    />
  )
}

function SortIndicator({ label }: { label: string }) {
  return (
    <span className="inline-flex min-w-0 items-center gap-1 text-left font-medium whitespace-nowrap">
      <span className="truncate">{label}</span>
      <ArrowUpDown
        className="size-3.5 shrink-0 opacity-50"
        aria-hidden="true"
      />
    </span>
  )
}

function formatOrderWarehouse(
  warehouseId: string | null,
  warehouses: readonly { id: string; name: string; city: string }[]
) {
  const warehouse = warehouses.find((candidate) => candidate.id === warehouseId)
  if (warehouse) return formatWarehouseShortName(warehouse)
  return warehouseId ? "Недоступный склад" : "Не выбран"
}

function OrderStatusBadge({ status }: { status: OrderSummary["status"] }) {
  return (
    <Badge variant={status === "DRAFT" ? "secondary" : "outline"}>
      {ORDER_STATUS_LABELS[status]}
    </Badge>
  )
}

function OrderMobileDetail({
  label,
  children,
}: {
  label: string
  children: ReactNode
}) {
  return (
    <div className="flex items-start justify-between gap-3">
      <dt className="shrink-0 text-muted-foreground">{label}</dt>
      <dd className="min-w-0 text-right font-medium break-words">{children}</dd>
    </div>
  )
}

function OrderMobileCard({
  order,
  managerName,
  warehouseName,
}: {
  order: OrderSummary
  managerName: string
  warehouseName: string
}) {
  return (
    <Link
      to={`/orders/${order.id}`}
      aria-label={`Открыть бронирование ${order.number}`}
      className="block rounded-xl outline-none focus-visible:ring-[3px] focus-visible:ring-ring/50"
    >
      <Card size="sm" className="transition-colors hover:bg-muted/40">
        <CardHeader>
          <CardTitle>{order.number}</CardTitle>
          <CardDescription className="truncate">
            {order.client.displayName}
          </CardDescription>
          <CardAction>
            <OrderStatusBadge status={order.status} />
          </CardAction>
        </CardHeader>
        <CardContent className="flex flex-col gap-3">
          <dl className="flex flex-col gap-2">
            <OrderMobileDetail label="Тип клиента">
              {ORDER_CLIENT_TYPE_LABELS[order.client.type]}
            </OrderMobileDetail>
            <OrderMobileDetail label="Менеджер">
              {managerName}
            </OrderMobileDetail>
            <OrderMobileDetail label="Склад">{warehouseName}</OrderMobileDetail>
            <OrderMobileDetail label="Бытовки">
              {order.unitCount}
            </OrderMobileDetail>
            <OrderMobileDetail label="Создан">
              {formatOrderDateTime(order.createdAt)}
            </OrderMobileDetail>
            <OrderMobileDetail label="Изменён">
              {formatOrderDateTime(order.updatedAt)}
            </OrderMobileDetail>
          </dl>
        </CardContent>
      </Card>
    </Link>
  )
}

export function OrdersListPage() {
  const { accessToken, currentUser, warehouses } = useOrdersModule()
  const location = useLocation()
  const navigate = useNavigate()
  const [search, setSearch] = useState("")
  const [sort, setSort] = useState<SortField>("updatedAt")
  const [direction, setDirection] = useState<"asc" | "desc">("desc")
  const [filters, setFilters] = useState<OrdersFiltersState>(EMPTY_FILTERS)
  const { filtersOpen, setFiltersOpen } = useResponsiveFiltersOpen()
  const [createDialogOpen, setCreateDialogOpen] = useState(false)
  const routeRequestsCreate = location.pathname === ORDERS_NAVIGATION.createPath
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
        label: formatWarehouseShortName(warehouse),
      })),
    [warehouses]
  )

  const ordersQuery = useQuery({
    queryKey: [
      ...ORDERS_QUERY_KEY,
      "list",
      currentUser?.id ?? "unknown-user",
      LIST_SIZE,
      search,
      sort,
      direction,
      filters,
    ],
    queryFn: () =>
      listOrders({
        accessToken: accessToken!,
        page: 0,
        size: LIST_SIZE,
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
  const managerIds = useMemo(
    () =>
      [
        ...new Set((ordersPage?.content ?? []).map((order) => order.managerId)),
      ].sort(),
    [ordersPage?.content]
  )
  const managerDisplaysQuery = useQuery({
    queryKey: ["orders", "manager-displays", managerIds],
    queryFn: () => listDossierActorDisplays(accessToken!, managerIds),
    enabled: Boolean(accessToken && managerIds.length > 0),
  })
  const managersById = useMemo(
    () =>
      new Map<string, DossierActorDisplay>(
        (managerDisplaysQuery.data ?? []).map((actor) => [
          actor.subjectId,
          actor,
        ])
      ),
    [managerDisplaysQuery.data]
  )

  function changeSort(field: SortField) {
    if (sort === field) {
      setDirection((current) => (current === "asc" ? "desc" : "asc"))
    } else {
      setSort(field)
      setDirection("asc")
    }
  }

  function setDialogOpen(open: boolean) {
    setCreateDialogOpen(open)
    if (!open && routeRequestsCreate) {
      navigate("/orders", { replace: true })
    }
  }

  return (
    <div className="flex h-full min-h-0 flex-col gap-4">
      <PageToolbar>
        <PageToolbarContent className="max-w-xl">
          <Input
            type="search"
            value={search}
            aria-label="Поиск бронирований"
            placeholder="Номер, клиент или менеджер"
            autoComplete="off"
            onChange={(event) => {
              setSearch(event.target.value)
            }}
          />
        </PageToolbarContent>
        <PageToolbarActions className="w-full justify-between sm:w-auto sm:justify-end">
          <OrdersFiltersToggle
            open={filtersOpen}
            controls="orders-filters"
            onOpenChange={setFiltersOpen}
          />
          <Button type="button" onClick={() => setCreateDialogOpen(true)}>
            <HugeiconsIcon icon={Add01Icon} data-icon="inline-start" />
            {ORDERS_NAVIGATION.createLabel}
          </Button>
        </PageToolbarActions>
      </PageToolbar>

      <div id="orders-filters" hidden={!filtersOpen}>
        <OrdersFilters
          filters={filters}
          statusOptions={statusOptions}
          clientTypeOptions={clientTypeOptions}
          warehouseOptions={warehouseOptions}
          onChange={(nextFilters) => {
            setFilters(nextFilters)
          }}
        />
      </div>

      {ordersQuery.isLoading ? (
        <Card className="min-h-0 flex-1" size="sm">
          <CardHeader>
            <CardTitle>Загрузка бронирований</CardTitle>
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
            <CardTitle>Не удалось загрузить бронирования</CardTitle>
            <CardDescription role="alert">
              {ordersQuery.error instanceof Error
                ? ordersQuery.error.message
                : "Сервис бронирований недоступен."}
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
            <CardTitle>Бронирования не найдены</CardTitle>
            <CardDescription>
              {search.trim() ||
              filters.statuses.length > 0 ||
              filters.clientTypes.length > 0 ||
              filters.warehouseIds.length > 0 ||
              filters.createdFrom ||
              filters.createdTo
                ? "Измените поисковый запрос или фильтры."
                : "Создайте первое бронирование для начала работы."}
            </CardDescription>
          </CardHeader>
        </Card>
      ) : (
        <>
          <div className="hidden min-h-0 w-full flex-1 overflow-hidden rounded-lg border bg-card md:block">
            <div className="h-full w-full overflow-auto">
              <table className="w-full min-w-[1050px] table-fixed border-separate border-spacing-0 text-sm">
                <thead className={GRID_HEADER_CLASS}>
                  <tr>
                    <th className={GRID_HEADER_CELL_CLASS}>
                      <SortButton
                        field="number"
                        currentField={sort}
                        direction={direction}
                        label="Номер"
                        onChange={changeSort}
                      />
                    </th>
                    <th className={GRID_HEADER_CELL_CLASS}>
                      <SortButton
                        field="client"
                        currentField={sort}
                        direction={direction}
                        label="Клиент"
                        onChange={changeSort}
                      />
                    </th>
                    <th className={GRID_HEADER_CELL_CLASS}>
                      <SortIndicator label="Тип клиента" />
                    </th>
                    <th className={GRID_HEADER_CELL_CLASS}>
                      <SortButton
                        field="manager"
                        currentField={sort}
                        direction={direction}
                        label="Менеджер"
                        onChange={changeSort}
                      />
                    </th>
                    <th className={GRID_HEADER_CELL_CLASS}>
                      <SortIndicator label="Склад" />
                    </th>
                    <th className={GRID_HEADER_CELL_CLASS}>
                      <SortIndicator label="Бытовки" />
                    </th>
                    <th className={GRID_HEADER_CELL_CLASS}>
                      <SortButton
                        field="status"
                        currentField={sort}
                        direction={direction}
                        label="Статус"
                        onChange={changeSort}
                      />
                    </th>
                    <th className={GRID_HEADER_CELL_CLASS}>
                      <SortButton
                        field="createdAt"
                        currentField={sort}
                        direction={direction}
                        label="Создан"
                        onChange={changeSort}
                      />
                    </th>
                    <th className={GRID_HEADER_CELL_CLASS}>
                      <SortButton
                        field="updatedAt"
                        currentField={sort}
                        direction={direction}
                        label="Изменён"
                        onChange={changeSort}
                      />
                    </th>
                  </tr>
                </thead>
                <tbody>
                  {ordersPage.content.map((order) => {
                    const warehouseName = formatOrderWarehouse(
                      order.warehouseId,
                      warehouses
                    )
                    return (
                      <tr
                        key={order.id}
                        className={`${GRID_TABLE_ROW_CLASS} cursor-pointer border-b hover:bg-muted/40`}
                        onDoubleClick={() => navigate(`/orders/${order.id}`)}
                      >
                        <td className={`${GRID_CELL_CLASS} font-medium`}>
                          <div className="min-w-0 truncate">{order.number}</div>
                        </td>
                        <td className={GRID_CELL_CLASS}>
                          <div className="min-w-0 truncate">
                            {order.client.displayName}
                          </div>
                        </td>
                        <td className={GRID_CELL_CLASS}>
                          <div className="min-w-0 truncate">
                            {ORDER_CLIENT_TYPE_LABELS[order.client.type]}
                          </div>
                        </td>
                        <td className={GRID_CELL_CLASS}>
                          <div className="min-w-0 truncate">
                            {formatManagerName(
                              managersById.get(order.managerId)
                            )}
                          </div>
                        </td>
                        <td className={GRID_CELL_CLASS}>
                          <div className="min-w-0 truncate">
                            {warehouseName}
                          </div>
                        </td>
                        <td className={GRID_CELL_CLASS}>{order.unitCount}</td>
                        <td className={GRID_CELL_CLASS}>
                          <OrderStatusBadge status={order.status} />
                        </td>
                        <td className={GRID_CELL_CLASS}>
                          {formatOrderDateTime(order.createdAt)}
                        </td>
                        <td className={GRID_CELL_CLASS}>
                          {formatOrderDateTime(order.updatedAt)}
                        </td>
                      </tr>
                    )
                  })}
                </tbody>
              </table>
            </div>
          </div>
          <div
            data-slot="orders-mobile-list"
            className="min-h-0 flex-1 overflow-y-auto md:hidden"
          >
            <div className="flex flex-col gap-3 pb-1">
              {ordersPage.content.map((order) => (
                <OrderMobileCard
                  key={order.id}
                  order={order}
                  managerName={formatManagerName(
                    managersById.get(order.managerId)
                  )}
                  warehouseName={formatOrderWarehouse(
                    order.warehouseId,
                    warehouses
                  )}
                />
              ))}
            </div>
          </div>
        </>
      )}

      <CreateOrderDialog
        open={routeRequestsCreate || createDialogOpen}
        onOpenChange={setDialogOpen}
        onCreated={(order) => navigate(`/orders/${order.id}`)}
      />
    </div>
  )
}
