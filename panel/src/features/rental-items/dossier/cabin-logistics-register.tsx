import { useQuery } from "@tanstack/react-query"
import { Link } from "react-router-dom"
import { HugeiconsIcon } from "@hugeicons/react"
import { ChevronDownIcon } from "@hugeicons/core-free-icons"

import { getWarehouse, listWarehouses } from "@/api/warehouse-api"
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
import {
  Collapsible,
  CollapsibleContent,
  CollapsibleTrigger,
} from "@/components/ui/collapsible"
import { Skeleton } from "@/components/ui/skeleton"
import { useAuth } from "@/features/auth/use-auth"
import { hasWarehouseAccess } from "@/features/auth/warehouse-access"
import {
  listShipments,
  SHIPMENTS_QUERY_KEY,
} from "@/features/logistics/shipments/api"
import {
  listReturns,
  RETURNS_QUERY_KEY,
} from "@/features/logistics/returns/api"
import {
  SHIPMENT_STATE_LABELS,
  type ShipmentDocument,
} from "@/features/logistics/shipments/model"
import {
  RETURN_STATE_LABELS,
  type ReturnDocument,
} from "@/features/logistics/returns/model"
import { useWarehouse } from "@/hooks/use-warehouse"
import { workspaceEntryNavigationOptions } from "@/hooks/use-workspace-back"
import { ApiError } from "@/lib/api-client"
import { logisticsStatusVariant } from "@/features/logistics/logistics-status-variant"
import { LogisticsHistoryDetails } from "./logistics-history-details"

type CabinLogisticsDocument = ShipmentDocument | ReturnDocument

const lineStateLabels = {
  PENDING: "Ожидает выполнения",
  DEPARTING: "Оформляется выезд",
  DEPARTED: "Выехала",
  ARRIVING: "Оформляется прибытие",
  ARRIVED: "Прибыла",
  CONFLICT: "Конфликт",
  CANCELLED: "Отменено",
} as const

function formatDate(value: string) {
  return new Intl.DateTimeFormat("ru-RU", { dateStyle: "medium" }).format(
    new Date(`${value}T12:00:00`)
  )
}

function formatInstant(value: string) {
  return new Intl.DateTimeFormat("ru-RU", {
    dateStyle: "medium",
    timeStyle: "short",
  }).format(new Date(value))
}

function retryHistory(count: number, error: Error) {
  return (
    !(error instanceof ApiError && [401, 403].includes(error.status)) &&
    count < 2
  )
}

/** Reads permanent cabin relationships, separately from the passport's current rental summary. */
export function CabinLogisticsRegister({
  cabinId,
  kind,
}: {
  cabinId: string
  kind: "SHIPMENT" | "RETURN"
}) {
  const { accessToken, currentUser } = useAuth()
  const { setSelectedWarehouseId } = useWarehouse()
  const title = kind === "SHIPMENT" ? "Отгрузки" : "Возвраты"
  const grantedWarehouseIds = [
    ...new Set(
      currentUser?.warehouseAccesses.map((access) => access.warehouseId)
    ),
  ].sort()
  // The operational selector omits inactive warehouses; their authorized history must remain readable.
  const directory = useQuery({
    queryKey: [
      "cabin-history-warehouses",
      currentUser?.id,
      currentUser?.globalRole,
      currentUser?.warehouseAccessAll,
      grantedWarehouseIds,
    ],
    queryFn: () =>
      currentUser!.warehouseAccessAll
        ? listWarehouses(
            accessToken!,
            currentUser!.globalRole === "SYSTEM_ADMIN"
          )
        : Promise.all(
            grantedWarehouseIds.map((id) => getWarehouse(accessToken!, id))
          ),
    enabled: Boolean(accessToken && currentUser),
    staleTime: 60_000,
    retry: retryHistory,
  })
  const warehouses = (directory.data ?? [])
    .filter((warehouse) =>
      hasWarehouseAccess(currentUser, warehouse.id, "VIEW")
    )
    .sort((left, right) => left.id.localeCompare(right.id))
  const query = useQuery<CabinLogisticsDocument[]>({
    queryKey: [
      ...(kind === "SHIPMENT" ? SHIPMENTS_QUERY_KEY : RETURNS_QUERY_KEY),
      "cabin-history",
      currentUser?.id,
      cabinId,
      warehouses.map((warehouse) => warehouse.id),
    ],
    queryFn: async () => {
      const pages = await Promise.all(
        warehouses.map((warehouse) =>
          kind === "SHIPMENT"
            ? listShipments(accessToken!, warehouse.id, undefined, cabinId)
            : listReturns(accessToken!, warehouse.id, undefined, cabinId)
        )
      )
      return pages
        .flat()
        .sort(
          (left, right) =>
            right.createdAt.localeCompare(left.createdAt) ||
            right.id.localeCompare(left.id)
        )
    },
    enabled: Boolean(accessToken && currentUser && directory.isSuccess),
    refetchInterval: 30_000,
    retry: retryHistory,
  })
  const error = directory.error ?? query.error

  if (!accessToken || !currentUser)
    return <p role="alert">Для просмотра истории требуется авторизация.</p>
  if (error)
    return (
      <Card>
        <CardHeader>
          <CardTitle>Не удалось загрузить {title.toLowerCase()}</CardTitle>
          <CardDescription role="alert">{error.message}</CardDescription>
        </CardHeader>
        <CardContent>
          <Button
            variant="outline"
            onClick={() =>
              void (directory.error ? directory.refetch() : query.refetch())
            }
          >
            Повторить загрузку
          </Button>
        </CardContent>
      </Card>
    )
  if (directory.isPending || query.isPending)
    return (
      <Skeleton
        className="h-28 w-full"
        aria-label="Загрузка истории логистики"
      />
    )

  return (
    <section aria-label={title} className="flex flex-col gap-3">
      <p className="text-sm text-muted-foreground">
        Документы этой бытовки на доступных складах, включая завершённые и
        отменённые.
      </p>
      {currentUser.warehouseAccessAll &&
      currentUser.globalRole !== "SYSTEM_ADMIN" ? (
        <p className="text-sm text-muted-foreground">
          Закрытые склады не входят в доступный вашей роли справочник.
        </p>
      ) : null}
      {query.data?.length === 0 ? (
        <p className="text-sm">Документов пока нет.</p>
      ) : null}
      {query.data?.map((document) => {
        const warehouse = warehouses.find(
          (candidate) => candidate.id === document.warehouseId
        )
        const line = document.lines.find(
          (candidate) => candidate.assetId === cabinId
        )
        const party =
          line?.tenantSnapshot ??
          document.partySnapshot ??
          "Контрагент не указан"
        const status =
          document.documentType === "SHIPMENT"
            ? SHIPMENT_STATE_LABELS[document.state]
            : RETURN_STATE_LABELS[document.state]
        const date = document.scheduledDate
          ? formatDate(document.scheduledDate)
          : "Дата не назначена"
        const orderId = line?.rentalOrderId ?? document.rentalOrderId
        const documentQuery = new URLSearchParams({
          [kind === "SHIPMENT" ? "shipmentId" : "receiptId"]: document.id,
          ...(document.scheduledDate ? { date: document.scheduledDate } : {}),
        })

        return (
          <Collapsible key={document.id}>
            <Card size="sm">
              <CardHeader>
                <CardTitle className="flex flex-wrap items-center gap-2">
                  {date}
                  <Badge variant={logisticsStatusVariant(document.state)}>
                    {status}
                  </Badge>
                </CardTitle>
                <CardDescription>
                  {party} · {warehouse?.name}
                </CardDescription>
                <CardAction>
                  <CollapsibleTrigger asChild>
                    <Button
                      variant="ghost"
                      size="icon-sm"
                      aria-label={`Подробнее: ${party}, ${date}`}
                    >
                      <HugeiconsIcon icon={ChevronDownIcon} />
                    </Button>
                  </CollapsibleTrigger>
                </CardAction>
              </CardHeader>
              <CollapsibleContent>
                <CardContent className="flex flex-col gap-3">
                  <dl className="grid gap-3 text-sm sm:grid-cols-2 lg:grid-cols-4">
                    <div>
                      <dt className="text-muted-foreground">
                        {kind === "SHIPMENT" ? "Кому" : "От кого"}
                      </dt>
                      <dd>{party}</dd>
                    </div>
                    <div>
                      <dt className="text-muted-foreground">Водитель</dt>
                      <dd>{document.driverSnapshot ?? "Не указан"}</dd>
                    </div>
                    <div>
                      <dt className="text-muted-foreground">
                        Движение бытовки
                      </dt>
                      <dd>
                        {line ? lineStateLabels[line.state] : "Не указано"}
                      </dd>
                    </div>
                    <div>
                      <dt className="text-muted-foreground">Документ создан</dt>
                      <dd>{formatInstant(document.createdAt)}</dd>
                    </div>
                  </dl>
                  <LogisticsHistoryDetails
                    cabinId={cabinId}
                    documentVersion={document.version}
                    reference={{
                      documentId: document.id,
                      warehouseId: document.warehouseId,
                      documentType: document.documentType,
                    }}
                  />
                  <p className="text-xs break-all text-muted-foreground">
                    Документ {document.id} · Обновлён{" "}
                    {formatInstant(document.updatedAt)}
                  </p>
                  <div className="flex flex-wrap gap-2">
                    {document.scheduledDate &&
                    warehouse?.lifecycleState !== "INACTIVE" ? (
                      <Button variant="outline" size="sm" asChild>
                        <Link
                          to={`/logistics/${kind === "SHIPMENT" ? "shipments" : "returns"}?${documentQuery}`}
                          state={workspaceEntryNavigationOptions.state}
                          onClick={() =>
                            setSelectedWarehouseId(document.warehouseId)
                          }
                        >
                          Открыть документ
                        </Link>
                      </Button>
                    ) : null}
                    {orderId ? (
                      <Button variant="outline" size="sm" asChild>
                        <Link
                          to={`/orders/${orderId}`}
                          state={workspaceEntryNavigationOptions.state}
                        >
                          Открыть заказ
                        </Link>
                      </Button>
                    ) : null}
                  </div>
                </CardContent>
              </CollapsibleContent>
            </Card>
          </Collapsible>
        )
      })}
    </section>
  )
}
