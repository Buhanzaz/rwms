import { useState } from "react"
import { useQuery } from "@tanstack/react-query"
import { RefreshIcon } from "@hugeicons/core-free-icons"
import { HugeiconsIcon } from "@hugeicons/react"
import { Link, useNavigate } from "react-router-dom"

import { PageToolbar, PageToolbarContent } from "@/components/page-toolbar"
import { Badge } from "@/components/ui/badge"
import { Button } from "@/components/ui/button"
import {
  Card,
  CardContent,
  CardDescription,
  CardHeader,
  CardTitle,
} from "@/components/ui/card"
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
  Table,
  TableBody,
  TableCell,
  TableHead,
  TableHeader,
  TableRow,
} from "@/components/ui/table"
import {
  CLIENTS_QUERY_KEY,
  listClients,
} from "@/features/clients/api/clients-api"
import {
  CLIENT_TYPES,
  CLIENT_TYPE_LABELS,
  type ClientType,
  type RentalClient,
} from "@/features/clients/domain/clients"
import { useOrdersModule } from "@/features/orders/orders-module-context"

const PAGE_SIZE = 50
const ALL_CLIENT_TYPES = "ALL_CLIENT_TYPES"

function formatDate(value: string) {
  return new Intl.DateTimeFormat("ru-RU", { dateStyle: "medium" }).format(
    new Date(value)
  )
}

function ClientMobileCard({ client }: { client: RentalClient }) {
  return (
    <Link
      to={`/clients/${client.id}`}
      aria-label={`Открыть клиента ${client.displayName}`}
      className="block rounded-xl outline-none focus-visible:ring-[3px] focus-visible:ring-ring/50"
    >
      <Card size="sm">
        <CardHeader>
          <CardTitle>{client.displayName}</CardTitle>
          <CardDescription>{client.phone ?? "Не указан"}</CardDescription>
        </CardHeader>
        <CardContent className="flex flex-wrap gap-2">
          <Badge variant="outline">{CLIENT_TYPE_LABELS[client.type]}</Badge>
          <Badge variant="secondary">
            {client.responsibleManagerDisplayName ??
              client.responsibleManagerId}
          </Badge>
        </CardContent>
      </Card>
    </Link>
  )
}

export function ClientsListPage() {
  const navigate = useNavigate()
  const { accessToken, currentUser } = useOrdersModule()
  const [search, setSearch] = useState("")
  const [type, setType] = useState<ClientType | null>(null)
  const [page, setPage] = useState(0)
  const clientsQuery = useQuery({
    queryKey: [
      ...CLIENTS_QUERY_KEY,
      "list",
      currentUser?.id ?? "unknown-user",
      search,
      type,
      page,
      PAGE_SIZE,
    ],
    queryFn: () =>
      listClients({
        accessToken: accessToken!,
        search,
        type: type ?? undefined,
        page,
        size: PAGE_SIZE,
      }),
    enabled: Boolean(accessToken),
  })
  const clients = clientsQuery.data

  return (
    <div className="flex h-full min-h-0 flex-col gap-4">
      <PageToolbar>
        <PageToolbarContent className="flex max-w-2xl gap-2">
          <Input
            type="search"
            value={search}
            aria-label="Поиск клиентов"
            placeholder="ФИО, название, телефон или email"
            autoComplete="off"
            onChange={(event) => {
              setSearch(event.target.value)
              setPage(0)
            }}
          />
          <Select
            value={type ?? ALL_CLIENT_TYPES}
            onValueChange={(value) => {
              setType(value === ALL_CLIENT_TYPES ? null : (value as ClientType))
              setPage(0)
            }}
          >
            <SelectTrigger className="w-56" aria-label="Тип клиента">
              <SelectValue />
            </SelectTrigger>
            <SelectContent>
              <SelectGroup>
                <SelectItem value={ALL_CLIENT_TYPES}>Все типы</SelectItem>
                {CLIENT_TYPES.map((clientType) => (
                  <SelectItem key={clientType} value={clientType}>
                    {CLIENT_TYPE_LABELS[clientType]}
                  </SelectItem>
                ))}
              </SelectGroup>
            </SelectContent>
          </Select>
        </PageToolbarContent>
      </PageToolbar>

      {clientsQuery.isLoading ? (
        <Card className="min-h-0 flex-1" size="sm">
          <CardHeader>
            <CardTitle>Загрузка клиентов</CardTitle>
          </CardHeader>
          <CardContent className="flex flex-col gap-3">
            {Array.from({ length: 7 }, (_, index) => (
              <Skeleton key={index} className="h-10 w-full" />
            ))}
          </CardContent>
        </Card>
      ) : clientsQuery.isError ? (
        <Card size="sm">
          <CardHeader>
            <CardTitle>Не удалось загрузить клиентов</CardTitle>
            <CardDescription role="alert">
              {clientsQuery.error instanceof Error
                ? clientsQuery.error.message
                : "Сервис клиентов недоступен."}
            </CardDescription>
          </CardHeader>
          <CardContent>
            <Button
              variant="outline"
              onClick={() => void clientsQuery.refetch()}
            >
              <HugeiconsIcon icon={RefreshIcon} data-icon="inline-start" />
              Повторить
            </Button>
          </CardContent>
        </Card>
      ) : !clients || clients.content.length === 0 ? (
        <Card size="sm">
          <CardHeader>
            <CardTitle>Клиенты не найдены</CardTitle>
            <CardDescription>
              Измените запрос или создайте клиента в чате, бронировании или
              новом заказе.
            </CardDescription>
          </CardHeader>
        </Card>
      ) : (
        <>
          <Card
            className="hidden min-h-0 flex-1 overflow-hidden md:flex"
            size="sm"
          >
            <CardContent className="min-h-0 flex-1 overflow-auto p-0">
              <Table>
                <TableHeader>
                  <TableRow>
                    <TableHead>Клиент</TableHead>
                    <TableHead>Тип</TableHead>
                    <TableHead>Телефон</TableHead>
                    <TableHead>Контактное лицо</TableHead>
                    <TableHead>Менеджер</TableHead>
                    <TableHead>Изменён</TableHead>
                  </TableRow>
                </TableHeader>
                <TableBody>
                  {clients.content.map((client) => (
                    <TableRow
                      key={client.id}
                      tabIndex={0}
                      className="cursor-pointer"
                      aria-label={`Клиент ${client.displayName}. Двойное нажатие открывает карточку.`}
                      onDoubleClick={() => navigate(`/clients/${client.id}`)}
                      onKeyDown={(event) => {
                        if (event.key === "Enter" || event.key === " ") {
                          event.preventDefault()
                          navigate(`/clients/${client.id}`)
                        }
                      }}
                    >
                      <TableCell className="font-medium">
                        <Link
                          to={`/clients/${client.id}`}
                          className="underline-offset-4 hover:underline focus-visible:rounded-sm focus-visible:ring-[3px] focus-visible:ring-ring/50 focus-visible:outline-none"
                        >
                          {client.displayName}
                        </Link>
                      </TableCell>
                      <TableCell>{CLIENT_TYPE_LABELS[client.type]}</TableCell>
                      <TableCell>{client.phone ?? "Не указан"}</TableCell>
                      <TableCell>{client.contactPerson ?? "—"}</TableCell>
                      <TableCell>
                        {client.responsibleManagerDisplayName ??
                          client.responsibleManagerId}
                      </TableCell>
                      <TableCell>{formatDate(client.updatedAt)}</TableCell>
                    </TableRow>
                  ))}
                </TableBody>
              </Table>
            </CardContent>
          </Card>
          <div className="min-h-0 flex-1 overflow-y-auto md:hidden">
            <div className="flex flex-col gap-3">
              {clients.content.map((client) => (
                <ClientMobileCard key={client.id} client={client} />
              ))}
            </div>
          </div>
          <div className="flex items-center justify-between gap-3 text-sm text-muted-foreground">
            <span>
              Страница {clients.page + 1} из {Math.max(clients.totalPages, 1)} ·
              всего {clients.totalElements}
            </span>
            <div className="flex gap-2">
              <Button
                type="button"
                size="sm"
                variant="outline"
                disabled={page === 0 || clientsQuery.isFetching}
                onClick={() => setPage((current) => Math.max(0, current - 1))}
              >
                Назад
              </Button>
              <Button
                type="button"
                size="sm"
                variant="outline"
                disabled={
                  page + 1 >= clients.totalPages || clientsQuery.isFetching
                }
                onClick={() => setPage((current) => current + 1)}
              >
                Вперёд
              </Button>
            </div>
          </div>
        </>
      )}
    </div>
  )
}
