import { type ReactNode, useState } from "react"
import { useQuery } from "@tanstack/react-query"
import {
  ArrowLeft01Icon,
  ClipboardListIcon,
  RefreshIcon,
} from "@hugeicons/core-free-icons"
import { HugeiconsIcon } from "@hugeicons/react"
import { Link, useNavigate, useParams } from "react-router-dom"

import { Badge } from "@/components/ui/badge"
import { Button } from "@/components/ui/button"
import {
  Card,
  CardAction,
  CardContent,
  CardDescription,
  CardHeader,
  CardTitle,
} from "@/components/ui/card"
import { FieldError } from "@/components/ui/field"
import { Skeleton } from "@/components/ui/skeleton"
import {
  Table,
  TableBody,
  TableCell,
  TableHead,
  TableHeader,
  TableRow,
} from "@/components/ui/table"
import {
  CLIENTS_QUERY_KEY,
  getClient,
} from "@/features/clients/api/clients-api"
import { CLIENT_TYPE_LABELS } from "@/features/clients/domain/clients"
import { CLIENTS_NAVIGATION } from "@/features/clients/clients-navigation"
import {
  listClientOrders,
  ORDERS_QUERY_KEY,
} from "@/features/orders/api/orders-api"
import {
  formatOrderDateTime,
  ORDER_STATUS_LABELS,
  type OrderSummary,
} from "@/features/orders/domain/orders"
import { useOrdersModule } from "@/features/orders/orders-module-context"

const ORDERS_PAGE_SIZE = 30

function DetailValue({
  label,
  value,
}: {
  label: string
  value: string | null
}) {
  return (
    <div>
      <dt className="text-xs text-muted-foreground">{label}</dt>
      <dd className="font-medium whitespace-pre-wrap">{value || "—"}</dd>
    </div>
  )
}

function ClientOrderMobileDetail({
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

function ClientOrderMobileCard({ order }: { order: OrderSummary }) {
  return (
    <Link
      to={`/orders/${order.id}`}
      aria-label={`Открыть заказ ${order.number}`}
      className="block rounded-xl outline-none focus-visible:ring-[3px] focus-visible:ring-ring/50 md:hidden"
    >
      <Card size="sm" className="transition-colors hover:bg-muted/40">
        <CardHeader>
          <CardTitle>{order.number}</CardTitle>
          <CardAction>
            <Badge variant={order.status === "DRAFT" ? "secondary" : "outline"}>
              {ORDER_STATUS_LABELS[order.status]}
            </Badge>
          </CardAction>
        </CardHeader>
        <CardContent>
          <dl className="flex flex-col gap-2 text-sm">
            <ClientOrderMobileDetail label="Адрес">
              {order.deliveryAddress ?? "Не указан"}
            </ClientOrderMobileDetail>
            <ClientOrderMobileDetail label="Телефон">
              {order.contactPhone ?? "Не указан"}
            </ClientOrderMobileDetail>
            <ClientOrderMobileDetail label="Бытовки">
              {order.unitCount}
            </ClientOrderMobileDetail>
            <ClientOrderMobileDetail label="Изменён">
              {formatOrderDateTime(order.updatedAt)}
            </ClientOrderMobileDetail>
          </dl>
        </CardContent>
      </Card>
    </Link>
  )
}

export function ClientDetailPage() {
  const { clientId } = useParams<{ clientId: string }>()
  const navigate = useNavigate()
  const { accessToken, currentUser } = useOrdersModule()
  const [ordersPage, setOrdersPage] = useState(0)
  const clientQuery = useQuery({
    queryKey: [...CLIENTS_QUERY_KEY, "detail", clientId],
    queryFn: () => getClient(accessToken!, clientId!),
    enabled: Boolean(accessToken && clientId),
  })
  const ordersQuery = useQuery({
    queryKey: [
      ...ORDERS_QUERY_KEY,
      "client",
      currentUser?.id ?? "unknown-user",
      clientId,
      ordersPage,
      ORDERS_PAGE_SIZE,
    ],
    queryFn: () =>
      listClientOrders({
        accessToken: accessToken!,
        clientId: clientId!,
        page: ordersPage,
        size: ORDERS_PAGE_SIZE,
      }),
    enabled: Boolean(accessToken && clientId && clientQuery.data),
  })

  if (!clientId) return <FieldError>В URL отсутствует клиент.</FieldError>
  if (clientQuery.isLoading) {
    return (
      <div className="flex flex-col gap-4">
        <Skeleton className="h-10 w-64" />
        <Skeleton className="h-56 w-full" />
      </div>
    )
  }
  if (clientQuery.isError || !clientQuery.data) {
    return (
      <Card size="sm">
        <CardHeader>
          <CardTitle>Клиент недоступен</CardTitle>
          <CardDescription role="alert">
            {clientQuery.error instanceof Error
              ? clientQuery.error.message
              : "Клиент не найден или у вас нет доступа."}
          </CardDescription>
        </CardHeader>
        <CardContent className="flex gap-2">
          <Button asChild variant="outline">
            <Link to={CLIENTS_NAVIGATION.listPath}>К списку клиентов</Link>
          </Button>
          <Button onClick={() => void clientQuery.refetch()}>
            <HugeiconsIcon icon={RefreshIcon} data-icon="inline-start" />
            Повторить
          </Button>
        </CardContent>
      </Card>
    )
  }

  const client = clientQuery.data
  const clientQueryValue = encodeURIComponent(client.id)
  const orders = ordersQuery.data

  return (
    <div className="flex h-full min-h-0 flex-col gap-4 overflow-y-auto pr-1">
      <div className="flex flex-wrap items-center justify-between gap-3">
        <Button asChild size="sm" variant="outline">
          <Link to={CLIENTS_NAVIGATION.listPath}>
            <HugeiconsIcon icon={ArrowLeft01Icon} data-icon="inline-start" />
            Назад
          </Link>
        </Button>
        <div className="flex flex-wrap gap-2">
          <Button asChild size="sm">
            <Link to={`/orders/new?clientId=${clientQueryValue}`}>
              <HugeiconsIcon
                icon={ClipboardListIcon}
                data-icon="inline-start"
              />
              Создать заказ
            </Link>
          </Button>
        </div>
      </div>

      <Card size="sm">
        <CardHeader>
          <CardTitle>{client.displayName}</CardTitle>
          <CardDescription>
            <Badge variant="outline">{CLIENT_TYPE_LABELS[client.type]}</Badge>
          </CardDescription>
        </CardHeader>
        <CardContent>
          <dl className="grid gap-4 sm:grid-cols-2 xl:grid-cols-4">
            <DetailValue
              label="Основной телефон"
              value={client.phone ?? "Не указан"}
            />
            <DetailValue label="Контактное лицо" value={client.contactPerson} />
            <DetailValue label="Email" value={client.email} />
            <DetailValue
              label="Ответственный менеджер"
              value={
                client.responsibleManagerDisplayName ??
                client.responsibleManagerId
              }
            />
            <DetailValue label="Источник" value={client.source} />
            <DetailValue label="Комментарий" value={client.comment} />
            <DetailValue
              label="Создан"
              value={formatOrderDateTime(client.createdAt)}
            />
            <DetailValue
              label="Изменён"
              value={formatOrderDateTime(client.updatedAt)}
            />
          </dl>
          <div className="mt-5 flex flex-col gap-2">
            <p className="text-xs text-muted-foreground">
              Дополнительные контакты клиента
            </p>
            {client.additionalContacts.length === 0 ? (
              <p className="text-sm font-medium">Не указаны</p>
            ) : (
              <dl className="grid gap-3 sm:grid-cols-2 xl:grid-cols-4">
                {client.additionalContacts.map((contact, index) => (
                  <DetailValue
                    key={`${contact.name}:${contact.phone}:${index}`}
                    label={contact.name}
                    value={contact.phone}
                  />
                ))}
              </dl>
            )}
          </div>
        </CardContent>
      </Card>

      <Card className="min-h-80" size="sm">
        <CardHeader>
          <CardTitle>Заказы клиента</CardTitle>
          <CardDescription>
            Выберите заказ, чтобы открыть бытовки, логистику, сметы и ремонтные
            подтверждения.
          </CardDescription>
        </CardHeader>
        <CardContent>
          {ordersQuery.isLoading ? (
            <Skeleton className="h-40 w-full" />
          ) : ordersQuery.isError ? (
            <FieldError>
              {ordersQuery.error instanceof Error
                ? ordersQuery.error.message
                : "Не удалось загрузить заказы клиента."}
            </FieldError>
          ) : !orders || orders.content.length === 0 ? (
            <p className="text-sm text-muted-foreground">
              У клиента пока нет доступных заказов.
            </p>
          ) : (
            <div className="flex flex-col gap-3">
              <Table className="hidden md:table">
                <TableHeader>
                  <TableRow>
                    <TableHead>Номер</TableHead>
                    <TableHead>Статус</TableHead>
                    <TableHead>Адрес</TableHead>
                    <TableHead>Телефон</TableHead>
                    <TableHead>Бытовки</TableHead>
                    <TableHead>Изменён</TableHead>
                  </TableRow>
                </TableHeader>
                <TableBody>
                  {orders.content.map((order) => (
                    <TableRow
                      key={order.id}
                      tabIndex={0}
                      className="cursor-pointer"
                      aria-label={`Открыть заказ ${order.number}`}
                      onDoubleClick={() => navigate(`/orders/${order.id}`)}
                      onKeyDown={(event) => {
                        if (event.key === "Enter" || event.key === " ") {
                          event.preventDefault()
                          navigate(`/orders/${order.id}`)
                        }
                      }}
                    >
                      <TableCell className="font-medium">
                        <Link
                          to={`/orders/${order.id}`}
                          className="underline-offset-4 hover:underline focus-visible:rounded-sm focus-visible:ring-[3px] focus-visible:ring-ring/50 focus-visible:outline-none"
                        >
                          {order.number}
                        </Link>
                      </TableCell>
                      <TableCell>{ORDER_STATUS_LABELS[order.status]}</TableCell>
                      <TableCell>
                        {order.deliveryAddress ?? "Не указан"}
                      </TableCell>
                      <TableCell>{order.contactPhone ?? "Не указан"}</TableCell>
                      <TableCell>{order.unitCount}</TableCell>
                      <TableCell>
                        {formatOrderDateTime(order.updatedAt)}
                      </TableCell>
                    </TableRow>
                  ))}
                </TableBody>
              </Table>
              <div className="flex flex-col gap-3 md:hidden">
                {orders.content.map((order) => (
                  <ClientOrderMobileCard key={order.id} order={order} />
                ))}
              </div>
              <div className="flex items-center justify-between gap-3 text-sm text-muted-foreground">
                <span>Всего заказов: {orders.totalElements}</span>
                <div className="flex gap-2">
                  <Button
                    size="sm"
                    variant="outline"
                    disabled={ordersPage === 0 || ordersQuery.isFetching}
                    onClick={() =>
                      setOrdersPage((current) => Math.max(0, current - 1))
                    }
                  >
                    Назад
                  </Button>
                  <Button
                    size="sm"
                    variant="outline"
                    disabled={
                      ordersPage + 1 >= orders.totalPages ||
                      ordersQuery.isFetching
                    }
                    onClick={() => setOrdersPage((current) => current + 1)}
                  >
                    Вперёд
                  </Button>
                </div>
              </div>
            </div>
          )}
        </CardContent>
      </Card>
    </div>
  )
}
