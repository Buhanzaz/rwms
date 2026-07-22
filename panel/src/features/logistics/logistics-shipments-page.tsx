import { useMemo, useRef, useState, type FormEvent } from "react"
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query"
import { Add01Icon, Delete02Icon } from "@hugeicons/core-free-icons"
import { HugeiconsIcon } from "@hugeicons/react"
import { useSearchParams } from "react-router-dom"

import { getEquipmentItems } from "@/api/equipment-api"
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
  Combobox,
  ComboboxContent,
  ComboboxEmpty,
  ComboboxGroup,
  ComboboxInput,
  ComboboxItem,
  ComboboxLabel,
  ComboboxList,
} from "@/components/ui/combobox"
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
  FieldLegend,
  FieldSet,
} from "@/components/ui/field"
import { Input } from "@/components/ui/input"
import {
  Select,
  SelectContent,
  SelectGroup,
  SelectItem,
  SelectTrigger,
  SelectValue,
} from "@/components/ui/select"
import { hasWarehouseAccess } from "@/features/auth/warehouse-access"
import { useAuth } from "@/features/auth/use-auth"
import { LogisticsDriverPicker } from "@/features/logistics/logistics-driver-picker"
import { RentalClientPicker } from "@/features/logistics/rental-client-picker"
import {
  SHIPMENTS_QUERY_KEY,
  cancelShipment,
  confirmShipmentPreparation,
  createShipment,
  listShipments,
} from "@/features/logistics/shipments/api"
import {
  SHIPMENT_STATE_LABELS,
  type ShipmentDocument,
  type ShipmentDocumentState,
  type ShipmentEquipmentAllocation,
} from "@/features/logistics/shipments/model"
import {
  listAvailableOrderUnits,
  listOrders,
  ORDERS_QUERY_KEY,
} from "@/features/orders/api/orders-api"
import type {
  OrderClientSearchItem,
  OrderUnitCandidate,
} from "@/features/orders/domain/orders"
import type { RepairTaskWorkerSnapshotDto } from "@/features/repair-tasks/model/repair-task"
import { useWarehouse } from "@/hooks/use-warehouse"
import { ApiError } from "@/lib/api-client"

const TERMINAL_STATES = new Set<ShipmentDocumentState>(["SHIPPED", "CANCELLED"])
const CANCELLABLE_STATES = new Set<ShipmentDocumentState>([
  "DRAFT",
  "PREPARING",
  "AWAITING_CONFIRMATION",
])

type ShipmentEquipmentDraft = {
  key: string
  equipmentId: string
  quantity: number
}

type ShipmentLineDraft = {
  key: string
  assetId: string | null
  allocations: ShipmentEquipmentDraft[]
}

type CommandAttempt = {
  signature: string
  idempotencyKey: string
}

function commandIdentity() {
  return crypto.randomUUID()
}

function emptyLine(): ShipmentLineDraft {
  return { key: commandIdentity(), assetId: null, allocations: [] }
}

function formatDateTime(value: string) {
  return new Intl.DateTimeFormat("ru-RU", {
    dateStyle: "medium",
    timeStyle: "short",
  }).format(new Date(value))
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

function stockVersion(
  item: Awaited<ReturnType<typeof getEquipmentItems>>[number]
) {
  return item.balances.find(
    (balance) => balance.locationKind === "STOCK" && balance.availableStock > 0
  )?.version
}

export function LogisticsShipmentsPage() {
  const { selectedWarehouseId } = useWarehouse()
  const { accessToken, currentUser } = useAuth()
  const [searchParams, setSearchParams] = useSearchParams()
  const queryClient = useQueryClient()
  const [createOpen, setCreateOpen] = useState(false)
  const [showAll, setShowAll] = useState(false)
  const [search, setSearch] = useState("")
  const [expandedId, setExpandedId] = useState<string | null>(null)
  const [cancelTarget, setCancelTarget] = useState<ShipmentDocument | null>(
    null
  )
  const [commandError, setCommandError] = useState<string | null>(null)
  const commandKeys = useRef(new Map<string, string>())
  const selectedShipmentId = searchParams.get("shipmentId")
  const canEditSelectedWarehouse =
    selectedWarehouseId !== null &&
    hasWarehouseAccess(currentUser, selectedWarehouseId, "EDIT")
  const queryKey = [...SHIPMENTS_QUERY_KEY, selectedWarehouseId] as const

  const query = useQuery({
    queryKey,
    queryFn: () => listShipments(accessToken!, selectedWarehouseId!),
    enabled: Boolean(accessToken && selectedWarehouseId),
    refetchInterval: 5_000,
  })

  const rows = useMemo(() => {
    const needle = search.trim().toLocaleLowerCase("ru")
    return (query.data ?? []).filter((shipment) => {
      if (selectedShipmentId && shipment.id !== selectedShipmentId) return false
      if (!showAll && TERMINAL_STATES.has(shipment.state)) return false
      if (!needle) return true
      return [
        shipment.id,
        shipment.partySnapshot,
        shipment.driverSnapshot,
        SHIPMENT_STATE_LABELS[shipment.state],
        ...shipment.lines.flatMap((line) => [line.id, line.assetId]),
      ]
        .filter(Boolean)
        .some((value) => String(value).toLocaleLowerCase("ru").includes(needle))
    })
  }, [query.data, search, selectedShipmentId, showAll])

  function keyFor(action: string, shipment: ShipmentDocument) {
    const identity = `${action}:${shipment.id}:${shipment.version}`
    const existing = commandKeys.current.get(identity)
    if (existing) return existing
    const idempotencyKey = commandIdentity()
    commandKeys.current.set(identity, idempotencyKey)
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

  const confirmMutation = useMutation({
    mutationFn: (shipment: ShipmentDocument) =>
      confirmShipmentPreparation({
        accessToken: accessToken!,
        documentId: shipment.id,
        expectedVersion: shipment.version,
        idempotencyKey: keyFor("confirm", shipment),
      }),
    onSuccess: (result, shipment) => {
      commandKeys.current.delete(`confirm:${shipment.id}:${shipment.version}`)
      applyServerProjection(result)
      setCommandError(null)
      void queryClient.invalidateQueries({ queryKey: SHIPMENTS_QUERY_KEY })
    },
    onError: (cause, shipment) => {
      if (cause instanceof ApiError && cause.status === 409) {
        commandKeys.current.delete(`confirm:${shipment.id}:${shipment.version}`)
        void queryClient.invalidateQueries({ queryKey: SHIPMENTS_QUERY_KEY })
      }
      setCommandError(errorMessage(cause, "Не удалось подтвердить подготовку"))
    },
  })

  const cancelMutation = useMutation({
    mutationFn: (shipment: ShipmentDocument) =>
      cancelShipment({
        accessToken: accessToken!,
        documentId: shipment.id,
        expectedVersion: shipment.version,
        idempotencyKey: keyFor("cancel", shipment),
      }),
    onSuccess: (result, shipment) => {
      commandKeys.current.delete(`cancel:${shipment.id}:${shipment.version}`)
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

  function clearSelection() {
    const next = new URLSearchParams(searchParams)
    next.delete("shipmentId")
    setSearchParams(next, { replace: true })
  }

  function actions(shipment: ShipmentDocument) {
    const confirming =
      confirmMutation.isPending && confirmMutation.variables?.id === shipment.id
    const cancelling =
      cancelMutation.isPending && cancelMutation.variables?.id === shipment.id
    const canEdit = hasWarehouseAccess(
      currentUser,
      shipment.warehouseId,
      "EDIT"
    )
    return (
      <div className="flex flex-wrap gap-2">
        <Button
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
        {canEdit && shipment.state === "AWAITING_CONFIRMATION" ? (
          <Button
            size="sm"
            disabled={confirming || cancelling || !accessToken}
            onClick={() => confirmMutation.mutate(shipment)}
          >
            {confirming ? "Подтверждается…" : "Подтвердить подготовку"}
          </Button>
        ) : null}
        {canEdit && CANCELLABLE_STATES.has(shipment.state) ? (
          <Button
            size="sm"
            variant="destructive"
            disabled={confirming || cancelling || !accessToken}
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
        <PageToolbarContent>
          <Input
            aria-label="Поиск отгрузок"
            placeholder="ID документа, бытовки, контрагент или водитель"
            value={search}
            onChange={(event) => setSearch(event.target.value)}
          />
        </PageToolbarContent>
        <PageToolbarActions>
          {selectedShipmentId ? (
            <Button variant="outline" onClick={clearSelection}>
              Показать все документы
            </Button>
          ) : null}
          <Button
            variant="outline"
            onClick={() => setShowAll((value) => !value)}
          >
            {showAll ? "Скрыть завершённые" : "Показать завершённые"}
          </Button>
          <Button
            variant="outline"
            disabled={!accessToken || !selectedWarehouseId || query.isFetching}
            onClick={() => void query.refetch()}
          >
            {query.isFetching ? "Обновляется…" : "Обновить"}
          </Button>
          {canEditSelectedWarehouse ? (
            <Button onClick={() => setCreateOpen(true)}>
              <HugeiconsIcon icon={Add01Icon} data-icon="inline-start" />
              Создать отгрузку
            </Button>
          ) : null}
        </PageToolbarActions>
      </PageToolbar>

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

      <div className="min-h-0 flex-1 overflow-auto">
        <div className="hidden min-h-full md:block">
          <OperationsListGrid
            className="min-h-full"
            items={rows}
            expandedItemId={expandedId}
            renderExpandedRow={(shipment) => (
              <ShipmentLines shipment={shipment} />
            )}
            columns={[
              {
                id: "createdAt",
                label: "Создан",
                className: "w-48",
                getSortValue: (shipment) => shipment.createdAt,
                render: (shipment) => formatDateTime(shipment.createdAt),
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
                getSortValue: (shipment) => shipment.driverSnapshot,
                render: (shipment) => shipment.driverSnapshot,
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
                className: "min-w-80",
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
                <CardDescription>{shipment.driverSnapshot}</CardDescription>
                <CardAction>
                  <Badge variant={statusVariant(shipment.state)}>
                    {SHIPMENT_STATE_LABELS[shipment.state]}
                  </Badge>
                </CardAction>
              </CardHeader>
              <CardContent>
                <ShipmentLines shipment={shipment} />
              </CardContent>
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
                  Измените фильтр или создайте новую отгрузку в аренду.
                </CardDescription>
              </CardHeader>
            </Card>
          ) : null}
        </div>
      </div>

      {createOpen &&
      selectedWarehouseId &&
      accessToken &&
      canEditSelectedWarehouse ? (
        <CreateShipmentDialog
          accessToken={accessToken}
          warehouseId={selectedWarehouseId}
          onOpenChange={setCreateOpen}
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
              Logistics-service отменит свои задания, резервирование мебели и
              leases. Панель не выполняет компенсацию самостоятельно.
            </AlertDialogDescription>
          </AlertDialogHeader>
          <AlertDialogFooter>
            <AlertDialogCancel disabled={cancelMutation.isPending}>
              Не отменять
            </AlertDialogCancel>
            <AlertDialogAction
              disabled={cancelMutation.isPending}
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

function ShipmentLines({ shipment }: { shipment: ShipmentDocument }) {
  return (
    <div className="grid gap-2">
      {shipment.lines.map((line) => (
        <Card key={line.id} size="sm">
          <CardHeader>
            <CardTitle>Бытовка {line.lineNumber}</CardTitle>
            <CardDescription>
              Аренда{" "}
              <span className="font-mono text-xs">{line.rentalOrderId}</span>
            </CardDescription>
            <CardAction>
              <Badge variant="outline">v{line.version}</Badge>
            </CardAction>
          </CardHeader>
          <CardContent className="grid gap-1 text-sm">
            <span>
              Asset: <span className="font-mono text-xs">{line.assetId}</span>
            </span>
            <span>Версия бытовки: {line.assetVersion}</span>
          </CardContent>
        </Card>
      ))}
    </div>
  )
}

function CreateShipmentDialog({
  accessToken,
  warehouseId,
  onOpenChange,
}: {
  accessToken: string
  warehouseId: string
  onOpenChange: (open: boolean) => void
}) {
  const queryClient = useQueryClient()
  const contentRef = useRef<HTMLDivElement>(null)
  const commandAttempt = useRef<CommandAttempt | null>(null)
  const [client, setClient] = useState<OrderClientSearchItem | null>(null)
  const [orderId, setOrderId] = useState("")
  const [driver, setDriver] = useState<RepairTaskWorkerSnapshotDto | null>(null)
  const [lines, setLines] = useState<ShipmentLineDraft[]>(() => [emptyLine()])
  const [validationError, setValidationError] = useState<string | null>(null)

  const ordersQuery = useQuery({
    queryKey: [
      ...ORDERS_QUERY_KEY,
      "shipment-orders",
      warehouseId,
      client?.id ?? "none",
    ],
    queryFn: () =>
      listOrders({
        accessToken,
        page: 0,
        size: 100,
        search: client?.displayName,
        sort: "updatedAt",
        direction: "desc",
      }),
    enabled: client !== null,
  })
  const orders = useMemo(
    () =>
      (ordersQuery.data?.content ?? []).filter(
        (order) =>
          order.client.id === client?.id &&
          order.status === "DRAFT" &&
          order.warehouseId === warehouseId
      ),
    [client?.id, ordersQuery.data?.content, warehouseId]
  )
  const selectedOrder = orders.find((order) => order.id === orderId) ?? null
  const candidatesQuery = useQuery({
    queryKey: [...ORDERS_QUERY_KEY, "shipment-candidates", orderId],
    queryFn: () =>
      listAvailableOrderUnits({
        accessToken,
        orderId,
        page: 0,
        size: 100,
      }),
    enabled: orderId.length > 0,
  })
  const candidates = candidatesQuery.data?.content ?? []
  const equipmentQuery = useQuery({
    queryKey: ["equipment", "shipment", warehouseId],
    queryFn: () => getEquipmentItems(accessToken, { warehouseId }),
  })
  const furniture = (equipmentQuery.data ?? []).filter(
    (item) =>
      item.active && item.category === "FURNITURE" && item.availableStock > 0
  )
  const candidateByAssetId = new Map(
    candidates.map((candidate) => [candidate.unit.id, candidate])
  )
  const selectedAssetIds = lines
    .map((line) => line.assetId)
    .filter((assetId): assetId is string => assetId !== null)
  const mutation = useMutation({
    mutationFn: ({
      idempotencyKey,
      commandLines,
    }: {
      idempotencyKey: string
      commandLines: Array<{
        assetId: string
        assetVersion: number
        allocations: ShipmentEquipmentAllocation[]
      }>
    }) =>
      createShipment({
        accessToken,
        warehouseId,
        clientId: client!.id,
        rentalOrderId: orderId,
        partySnapshot: client!.displayName,
        driverSnapshot: driver!.name,
        lines: commandLines,
        idempotencyKey,
      }),
    onSuccess: (result) => {
      queryClient.setQueryData<ShipmentDocument[]>(
        [...SHIPMENTS_QUERY_KEY, warehouseId],
        (current) => [
          result,
          ...(current ?? []).filter((item) => item.id !== result.id),
        ]
      )
      void queryClient.invalidateQueries({ queryKey: SHIPMENTS_QUERY_KEY })
      onOpenChange(false)
    },
  })

  function selectCandidate(key: string, candidate: OrderUnitCandidate | null) {
    setLines((current) =>
      current.map((line) =>
        line.key === key
          ? { ...line, assetId: candidate?.unit.id ?? null, allocations: [] }
          : line
      )
    )
  }

  function addAllocation(lineKey: string) {
    setLines((current) =>
      current.map((line) =>
        line.key === lineKey
          ? {
              ...line,
              allocations: [
                ...line.allocations,
                { key: commandIdentity(), equipmentId: "", quantity: 1 },
              ],
            }
          : line
      )
    )
  }

  function updateAllocation(
    lineKey: string,
    allocationKey: string,
    update: Partial<Pick<ShipmentEquipmentDraft, "equipmentId" | "quantity">>
  ) {
    setLines((current) =>
      current.map((line) =>
        line.key === lineKey
          ? {
              ...line,
              allocations: line.allocations.map((allocation) =>
                allocation.key === allocationKey
                  ? { ...allocation, ...update }
                  : allocation
              ),
            }
          : line
      )
    )
  }

  function removeAllocation(lineKey: string, allocationKey: string) {
    setLines((current) =>
      current.map((line) =>
        line.key === lineKey
          ? {
              ...line,
              allocations: line.allocations.filter(
                (allocation) => allocation.key !== allocationKey
              ),
            }
          : line
      )
    )
  }

  function submit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault()
    const selected = lines.map((line) =>
      line.assetId ? (candidateByAssetId.get(line.assetId) ?? null) : null
    )
    if (!client || !selectedOrder || !driver) {
      setValidationError("Выберите контрагента, аренду и водителя.")
      return
    }
    if (selected.some((candidate) => candidate === null)) {
      setValidationError("Выберите бытовку в каждой строке отгрузки.")
      return
    }
    const selectedCandidates = selected as OrderUnitCandidate[]
    if (
      new Set(selectedCandidates.map((candidate) => candidate.unit.id)).size !==
      selectedCandidates.length
    ) {
      setValidationError(
        "Одну бытовку можно добавить в отгрузку только один раз."
      )
      return
    }

    const allocatedTotals = new Map<string, number>()
    const commandLines: Array<{
      assetId: string
      assetVersion: number
      allocations: ShipmentEquipmentAllocation[]
    }> = []
    for (let index = 0; index < lines.length; index += 1) {
      const line = lines[index]!
      const equipmentIds = new Set<string>()
      const allocations: ShipmentEquipmentAllocation[] = []
      for (const allocation of line.allocations) {
        const furnitureItem = furniture.find(
          (item) => item.id === allocation.equipmentId
        )
        const expectedStockVersion = furnitureItem
          ? stockVersion(furnitureItem)
          : undefined
        if (
          !furnitureItem ||
          expectedStockVersion === undefined ||
          !Number.isSafeInteger(allocation.quantity) ||
          allocation.quantity < 1 ||
          equipmentIds.has(allocation.equipmentId)
        ) {
          setValidationError(
            "Выберите уникальную мебель и корректное количество для каждой бытовки."
          )
          return
        }
        equipmentIds.add(allocation.equipmentId)
        allocatedTotals.set(
          allocation.equipmentId,
          (allocatedTotals.get(allocation.equipmentId) ?? 0) +
            allocation.quantity
        )
        allocations.push({
          equipmentId: allocation.equipmentId,
          quantity: allocation.quantity,
          expectedStockVersion,
        })
      }
      const candidate = selectedCandidates[index]!
      commandLines.push({
        assetId: candidate.unit.id,
        assetVersion: candidate.unit.version,
        allocations,
      })
    }
    for (const [equipmentId, quantity] of allocatedTotals) {
      const available = furniture.find(
        (item) => item.id === equipmentId
      )?.availableStock
      if (available === undefined || quantity > available) {
        setValidationError(
          "Количество мебели превышает доступный остаток на складе."
        )
        return
      }
    }

    const signature = JSON.stringify({
      warehouseId,
      clientId: client.id,
      rentalOrderId: selectedOrder.id,
      driverSnapshot: driver.name,
      lines: commandLines,
    })
    const idempotencyKey =
      commandAttempt.current?.signature === signature
        ? commandAttempt.current.idempotencyKey
        : commandIdentity()
    commandAttempt.current = { signature, idempotencyKey }
    setValidationError(null)
    mutation.mutate({ idempotencyKey, commandLines })
  }

  return (
    <Dialog open onOpenChange={onOpenChange}>
      <DialogContent
        ref={contentRef}
        className="max-h-[calc(100svh-1rem)] overflow-y-auto sm:max-w-4xl"
      >
        <form onSubmit={submit}>
          <DialogHeader>
            <DialogTitle>Создать отгрузку в аренду</DialogTitle>
            <DialogDescription>
              Выберите контрагента, аренду, водителя, бытовки и требуемую
              мебель. Сервер создаст документ и задания подготовки.
            </DialogDescription>
          </DialogHeader>
          <FieldGroup className="py-4">
            <RentalClientPicker
              accessToken={accessToken}
              idPrefix="shipment"
              portalContainer={contentRef}
              value={client}
              disabled={mutation.isPending}
              onChange={(next) => {
                setClient(next)
                setOrderId("")
                setLines([emptyLine()])
                commandAttempt.current = null
                setValidationError(null)
              }}
            />
            <Field>
              <FieldLabel htmlFor="shipment-order">Аренда</FieldLabel>
              <Select
                value={orderId}
                disabled={!client || mutation.isPending}
                onValueChange={(next) => {
                  setOrderId(next)
                  setLines([emptyLine()])
                  commandAttempt.current = null
                }}
              >
                <SelectTrigger id="shipment-order">
                  <SelectValue placeholder="Выберите аренду" />
                </SelectTrigger>
                <SelectContent>
                  <SelectGroup>
                    {orders.map((order) => (
                      <SelectItem key={order.id} value={order.id}>
                        {order.number} · {order.unitCount} бытовок
                      </SelectItem>
                    ))}
                  </SelectGroup>
                </SelectContent>
              </Select>
              {ordersQuery.isError ? (
                <FieldError>Не удалось загрузить аренды клиента.</FieldError>
              ) : ordersQuery.isSuccess && client && orders.length === 0 ? (
                <FieldDescription>
                  У клиента нет активной аренды на выбранном складе.
                </FieldDescription>
              ) : null}
            </Field>
            <LogisticsDriverPicker
              accessToken={accessToken}
              id="shipment-driver"
              warehouseId={warehouseId}
              value={driver}
              disabled={mutation.isPending}
              onChange={(next) => {
                setDriver(next)
                commandAttempt.current = null
                setValidationError(null)
              }}
            />
            <FieldSet disabled={!selectedOrder || mutation.isPending}>
              <FieldLegend variant="label">Бытовки и наполнение</FieldLegend>
              <FieldDescription>
                Зарезервированные за{" "}
                {client?.displayName ?? "выбранным клиентом"} бытовки показаны
                первыми. Остальные свободные бытовки можно добавить в эту же
                отгрузку.
              </FieldDescription>
              <FieldGroup>
                {lines.map((line, index) => {
                  const selectedCandidate = line.assetId
                    ? (candidateByAssetId.get(line.assetId) ?? null)
                    : null
                  return (
                    <Card key={line.key} size="sm">
                      <CardHeader>
                        <CardTitle>Бытовка {index + 1}</CardTitle>
                        {lines.length > 1 ? (
                          <CardAction>
                            <Button
                              type="button"
                              size="icon-sm"
                              variant="outline"
                              aria-label={`Удалить бытовку ${index + 1}`}
                              onClick={() =>
                                setLines((current) =>
                                  current.filter(
                                    (item) => item.key !== line.key
                                  )
                                )
                              }
                            >
                              <HugeiconsIcon
                                icon={Delete02Icon}
                                data-icon="inline-start"
                              />
                            </Button>
                          </CardAction>
                        ) : null}
                      </CardHeader>
                      <CardContent className="flex flex-col gap-4">
                        <Field>
                          <FieldLabel htmlFor={`shipment-cabin-${line.key}`}>
                            Номер бытовки
                          </FieldLabel>
                          <Combobox<OrderUnitCandidate>
                            items={candidates}
                            value={selectedCandidate}
                            itemToStringLabel={(candidate) =>
                              candidate.unit.number
                            }
                            itemToStringValue={(candidate) => candidate.unit.id}
                            isItemEqualToValue={(left, right) =>
                              left.unit.id === right.unit.id
                            }
                            disabled={!selectedOrder || mutation.isPending}
                            onValueChange={(candidate) =>
                              selectCandidate(line.key, candidate)
                            }
                          >
                            <ComboboxInput
                              id={`shipment-cabin-${line.key}`}
                              placeholder={
                                selectedOrder
                                  ? "Введите номер бытовки"
                                  : "Сначала выберите аренду"
                              }
                              showClear
                            />
                            <ComboboxContent portalContainer={contentRef}>
                              <ComboboxEmpty>
                                {candidatesQuery.isFetching
                                  ? "Загружаем бытовки…"
                                  : "Доступные бытовки не найдены"}
                              </ComboboxEmpty>
                              <ComboboxList>
                                <ShipmentCandidateGroup
                                  clientName={client?.displayName ?? "клиентом"}
                                  candidates={candidates}
                                  selectedAssetIds={selectedAssetIds}
                                  currentAssetId={line.assetId}
                                />
                              </ComboboxList>
                            </ComboboxContent>
                          </Combobox>
                        </Field>
                        {selectedCandidate ? (
                          <FieldSet>
                            <FieldLegend variant="label">
                              Мебель для бытовки
                            </FieldLegend>
                            <FieldDescription>
                              Выберите мебель со свободного остатка исходного
                              склада. Logistics-service создаст hold и задачу
                              подготовки.
                            </FieldDescription>
                            <FieldGroup>
                              {line.allocations.map((allocation) => {
                                const allowedFurniture = furniture.filter(
                                  (item) =>
                                    item.id === allocation.equipmentId ||
                                    !line.allocations.some(
                                      (other) =>
                                        other.key !== allocation.key &&
                                        other.equipmentId === item.id
                                    )
                                )
                                return (
                                  <div
                                    key={allocation.key}
                                    className="grid gap-2 sm:grid-cols-[minmax(0,1fr)_8rem_auto]"
                                  >
                                    <Field>
                                      <FieldLabel
                                        htmlFor={`shipment-equipment-${allocation.key}`}
                                      >
                                        Мебель
                                      </FieldLabel>
                                      <Select
                                        value={allocation.equipmentId}
                                        onValueChange={(equipmentId) =>
                                          updateAllocation(
                                            line.key,
                                            allocation.key,
                                            { equipmentId }
                                          )
                                        }
                                      >
                                        <SelectTrigger
                                          id={`shipment-equipment-${allocation.key}`}
                                        >
                                          <SelectValue placeholder="Выберите мебель" />
                                        </SelectTrigger>
                                        <SelectContent>
                                          <SelectGroup>
                                            {allowedFurniture.map((item) => (
                                              <SelectItem
                                                key={item.id}
                                                value={item.id}
                                              >
                                                {item.name} · доступно{" "}
                                                {item.availableStock}
                                              </SelectItem>
                                            ))}
                                          </SelectGroup>
                                        </SelectContent>
                                      </Select>
                                    </Field>
                                    <Field>
                                      <FieldLabel
                                        htmlFor={`shipment-quantity-${allocation.key}`}
                                      >
                                        Количество
                                      </FieldLabel>
                                      <Input
                                        id={`shipment-quantity-${allocation.key}`}
                                        type="number"
                                        min={1}
                                        step={1}
                                        value={allocation.quantity}
                                        onChange={(event) =>
                                          updateAllocation(
                                            line.key,
                                            allocation.key,
                                            {
                                              quantity: Number(
                                                event.target.value
                                              ),
                                            }
                                          )
                                        }
                                      />
                                    </Field>
                                    <Button
                                      type="button"
                                      className="self-end"
                                      size="sm"
                                      variant="outline"
                                      onClick={() =>
                                        removeAllocation(
                                          line.key,
                                          allocation.key
                                        )
                                      }
                                    >
                                      Удалить
                                    </Button>
                                  </div>
                                )
                              })}
                              <Button
                                type="button"
                                size="sm"
                                variant="outline"
                                disabled={furniture.length === 0}
                                onClick={() => addAllocation(line.key)}
                              >
                                Добавить мебель
                              </Button>
                              {equipmentQuery.isError ? (
                                <FieldError>
                                  Не удалось загрузить остатки мебели.
                                </FieldError>
                              ) : null}
                            </FieldGroup>
                          </FieldSet>
                        ) : null}
                      </CardContent>
                    </Card>
                  )
                })}
                <Button
                  type="button"
                  variant="outline"
                  disabled={
                    !selectedOrder ||
                    lines.length >= 100 ||
                    candidates.length === 0 ||
                    lines.some((line) => line.assetId === null)
                  }
                  onClick={() =>
                    setLines((current) => [...current, emptyLine()])
                  }
                >
                  <HugeiconsIcon icon={Add01Icon} data-icon="inline-start" />
                  Добавить ещё бытовку
                </Button>
              </FieldGroup>
            </FieldSet>
            {validationError ? (
              <FieldError>{validationError}</FieldError>
            ) : null}
            {mutation.error ? (
              <FieldError>
                {errorMessage(mutation.error, "Не удалось создать отгрузку")}
              </FieldError>
            ) : null}
          </FieldGroup>
          <DialogFooter>
            <Button
              type="button"
              variant="outline"
              onClick={() => onOpenChange(false)}
            >
              Отмена
            </Button>
            <Button type="submit" disabled={mutation.isPending}>
              {mutation.isPending ? "Создаётся…" : "Создать отгрузку"}
            </Button>
          </DialogFooter>
        </form>
      </DialogContent>
    </Dialog>
  )
}

function ShipmentCandidateGroup({
  clientName,
  candidates,
  selectedAssetIds,
  currentAssetId,
}: {
  clientName: string
  candidates: OrderUnitCandidate[]
  selectedAssetIds: string[]
  currentAssetId: string | null
}) {
  const available = candidates.filter(
    (candidate) =>
      candidate.unit.id === currentAssetId ||
      !selectedAssetIds.includes(candidate.unit.id)
  )
  const reserved = available.filter((candidate) => candidate.added)
  const free = available.filter((candidate) => !candidate.added)
  return (
    <>
      {reserved.length ? (
        <ComboboxGroup>
          <ComboboxLabel>Зарезервированы за {clientName}</ComboboxLabel>
          {reserved.map((candidate) => (
            <ComboboxItem key={candidate.unit.id} value={candidate}>
              <span>{candidate.unit.number}</span>
              <Badge variant="secondary">Зарезервирована</Badge>
            </ComboboxItem>
          ))}
        </ComboboxGroup>
      ) : null}
      {free.length ? (
        <ComboboxGroup>
          <ComboboxLabel>Другие доступные</ComboboxLabel>
          {free.map((candidate) => (
            <ComboboxItem key={candidate.unit.id} value={candidate}>
              {candidate.unit.number}
            </ComboboxItem>
          ))}
        </ComboboxGroup>
      ) : null}
    </>
  )
}
