import {
  useMemo,
  useRef,
  useState,
  type FormEvent,
  type MutableRefObject,
} from "react"
import {
  type QueryClient,
  useMutation,
  useQuery,
  useQueryClient,
} from "@tanstack/react-query"
import { Add01Icon, Delete02Icon } from "@hugeicons/core-free-icons"
import { HugeiconsIcon } from "@hugeicons/react"
import { useSearchParams } from "react-router-dom"

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
import { hasWarehouseAccess } from "@/features/auth/warehouse-access"
import { useAuth } from "@/features/auth/use-auth"
import { LogisticsDriverPicker } from "@/features/logistics/logistics-driver-picker"
import { RentalClientPicker } from "@/features/logistics/rental-client-picker"
import {
  RETURNS_QUERY_KEY,
  createReturn,
  listReturns,
  registerReturn,
} from "@/features/logistics/returns/api"
import { AcceptUndamagedDialog } from "@/features/logistics/returns/accept-undamaged-dialog"
import {
  RETURN_STATE_LABELS,
  type CreateReturnLine,
  type ReturnDocument,
  type ReturnDocumentState,
  type ReturnLine,
} from "@/features/logistics/returns/model"
import { RequestEstimateDialog } from "@/features/logistics/returns/request-estimate-dialog"
import {
  getOrder,
  listOrders,
  ORDERS_QUERY_KEY,
} from "@/features/orders/api/orders-api"
import type {
  OrderClientSearchItem,
  OrderDetail,
  OrderRentalUnit,
} from "@/features/orders/domain/orders"
import type { RepairTaskWorkerSnapshotDto } from "@/features/repair-tasks/model/repair-task"
import { useWarehouse } from "@/hooks/use-warehouse"

const ACTIONABLE_STATES = new Set<ReturnDocumentState>([
  "DRAFT",
  "INSPECTION_REQUIRED",
  "CONFLICT",
  "RECONCILIATION_REQUIRED",
])

type CommandAttempt = {
  signature: string
  idempotencyKey: string
}

type ReturnCandidate = {
  rentalOrderId: string
  orderNumber: string
  unit: OrderRentalUnit
}

type ReturnLineDraft = {
  key: string
  assetId: string | null
}

function commandIdentity() {
  return crypto.randomUUID()
}

function stableCommandKey(
  attempt: MutableRefObject<CommandAttempt | null>,
  signature: string
) {
  if (attempt.current?.signature === signature) {
    return attempt.current.idempotencyKey
  }
  const idempotencyKey = commandIdentity()
  attempt.current = { signature, idempotencyKey }
  return idempotencyKey
}

function emptyReturnLine(): ReturnLineDraft {
  return { key: commandIdentity(), assetId: null }
}

function formatDateTime(value: string) {
  return new Intl.DateTimeFormat("ru-RU", {
    dateStyle: "medium",
    timeStyle: "short",
  }).format(new Date(value))
}

function statusVariant(state: ReturnDocumentState) {
  if (state === "CONFLICT" || state === "RECONCILIATION_REQUIRED") {
    return "destructive" as const
  }
  if (state === "ACCEPTED" || state === "ESTIMATE_REQUESTED") {
    return "secondary" as const
  }
  return "outline" as const
}

function errorMessage(cause: unknown, fallback: string) {
  return cause instanceof Error ? cause.message : fallback
}

function isConflict(cause: unknown) {
  return (
    typeof cause === "object" &&
    cause !== null &&
    "status" in cause &&
    cause.status === 409
  )
}

function returnListQueryKey(warehouseId: string) {
  return [...RETURNS_QUERY_KEY, warehouseId] as const
}

function storeServiceProjection(
  queryClient: QueryClient,
  document: ReturnDocument
) {
  queryClient.setQueryData<ReturnDocument[]>(
    returnListQueryKey(document.warehouseId),
    (current) => {
      if (!current) return [document]
      const exists = current.some((candidate) => candidate.id === document.id)
      return exists
        ? current.map((candidate) =>
            candidate.id === document.id ? document : candidate
          )
        : [document, ...current]
    }
  )
}

function lineSummary(line: ReturnLine) {
  return line.tenantSnapshot?.trim() || line.assetId
}

export function LogisticsReturnsPage() {
  const { selectedWarehouseId } = useWarehouse()
  const { accessToken, currentUser } = useAuth()
  const [searchParams, setSearchParams] = useSearchParams()
  const queryClient = useQueryClient()
  const [createOpen, setCreateOpen] = useState(false)
  const [showAll, setShowAll] = useState(false)
  const [search, setSearch] = useState("")
  const [expandedId, setExpandedId] = useState<string | null>(null)
  const [acceptTarget, setAcceptTarget] = useState<ReturnDocument | null>(null)
  const [estimateTarget, setEstimateTarget] = useState<ReturnDocument | null>(
    null
  )
  const [commandError, setCommandError] = useState<string | null>(null)
  const commandKeys = useRef(new Map<string, string>())
  const selectedDocumentId = searchParams.get("receiptId")
  const selectedLineId = searchParams.get("returnItemId")
  const canEditSelectedWarehouse =
    selectedWarehouseId !== null &&
    hasWarehouseAccess(currentUser, selectedWarehouseId, "EDIT")

  const query = useQuery({
    queryKey: returnListQueryKey(selectedWarehouseId ?? "none"),
    queryFn: () => listReturns(accessToken!, selectedWarehouseId!),
    enabled: Boolean(accessToken && selectedWarehouseId),
    refetchInterval: 5_000,
  })

  const rows = useMemo(() => {
    const needle = search.trim().toLocaleLowerCase("ru")
    return (query.data ?? []).filter((document) => {
      if (selectedDocumentId && document.id !== selectedDocumentId) return false
      if (
        selectedLineId &&
        !document.lines.some((line) => line.id === selectedLineId)
      ) {
        return false
      }
      if (!showAll && !ACTIONABLE_STATES.has(document.state)) return false
      if (!needle) return true
      return [
        document.id,
        document.partySnapshot,
        document.driverSnapshot,
        RETURN_STATE_LABELS[document.state],
        ...document.lines.flatMap((line) => [
          line.id,
          line.assetId,
          line.tenantSnapshot,
        ]),
      ]
        .filter(Boolean)
        .some((value) => String(value).toLocaleLowerCase("ru").includes(needle))
    })
  }, [query.data, search, selectedDocumentId, selectedLineId, showAll])

  function refresh(warehouseId: string) {
    return queryClient.invalidateQueries({
      queryKey: returnListQueryKey(warehouseId),
    })
  }

  function keyFor(action: string, document: ReturnDocument) {
    const identity = `${action}:${document.id}:${document.version}`
    const existing = commandKeys.current.get(identity)
    if (existing) return existing
    const idempotencyKey = commandIdentity()
    commandKeys.current.set(identity, idempotencyKey)
    return idempotencyKey
  }

  const registerMutation = useMutation({
    mutationFn: (document: ReturnDocument) =>
      registerReturn({
        accessToken: accessToken!,
        documentId: document.id,
        expectedVersion: document.version,
        idempotencyKey: keyFor("register", document),
      }),
    onSuccess: (result, document) => {
      commandKeys.current.delete(`register:${document.id}:${document.version}`)
      storeServiceProjection(queryClient, result)
      setCommandError(null)
      void refresh(result.warehouseId)
    },
    onError: (cause, document) => {
      if (isConflict(cause)) void refresh(document.warehouseId)
      setCommandError(
        errorMessage(cause, "Не удалось зарегистрировать возврат")
      )
    },
  })

  function clearSelection() {
    const next = new URLSearchParams(searchParams)
    next.delete("receiptId")
    next.delete("returnItemId")
    setSearchParams(next, { replace: true })
  }

  function actions(document: ReturnDocument) {
    const processing =
      registerMutation.isPending &&
      registerMutation.variables?.id === document.id
    const canEdit = hasWarehouseAccess(
      currentUser,
      document.warehouseId,
      "EDIT"
    )
    return (
      <div className="flex flex-wrap gap-2">
        <Button
          size="sm"
          variant="outline"
          onClick={() =>
            setExpandedId((current) =>
              current === document.id ? null : document.id
            )
          }
        >
          {expandedId === document.id ? "Скрыть состав" : "Показать состав"}
        </Button>
        {canEdit && document.state === "DRAFT" ? (
          <Button
            size="sm"
            disabled={processing || !accessToken}
            onClick={() => registerMutation.mutate(document)}
          >
            {processing ? "Регистрируется…" : "Зарегистрировать"}
          </Button>
        ) : null}
        {canEdit && document.state === "INSPECTION_REQUIRED" ? (
          <>
            <Button
              size="sm"
              variant="outline"
              onClick={() => {
                setCommandError(null)
                setAcceptTarget(document)
              }}
            >
              Принять без повреждений
            </Button>
            <Button
              size="sm"
              onClick={() => {
                setCommandError(null)
                setEstimateTarget(document)
              }}
            >
              Создать смету
            </Button>
          </>
        ) : null}
      </div>
    )
  }

  return (
    <div className="flex h-full min-h-0 flex-col gap-4 overflow-hidden">
      <PageToolbar>
        <PageToolbarContent>
          <Input
            aria-label="Поиск возвратов"
            placeholder="ID документа, бытовки или контрагент"
            value={search}
            onChange={(event) => setSearch(event.target.value)}
          />
        </PageToolbarContent>
        <PageToolbarActions>
          {selectedDocumentId || selectedLineId ? (
            <Button variant="outline" onClick={clearSelection}>
              Показать все документы
            </Button>
          ) : null}
          <Button
            className="w-40 shrink-0"
            variant="outline"
            onClick={() => setShowAll((value) => !value)}
          >
            {showAll ? "Требуют действий" : "Показать все"}
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
              Добавить возврат
            </Button>
          ) : null}
        </PageToolbarActions>
      </PageToolbar>

      {!accessToken ? (
        <FieldError>Для просмотра возвратов требуется авторизация.</FieldError>
      ) : null}
      {!selectedWarehouseId ? (
        <FieldError>Выберите склад для просмотра возвратов.</FieldError>
      ) : null}
      {query.error ? (
        <FieldError>
          {errorMessage(query.error, "Не удалось загрузить возвраты")}
        </FieldError>
      ) : null}
      {commandError ? <FieldError>{commandError}</FieldError> : null}

      <div className="min-h-0 flex-1 overflow-auto">
        <div className="hidden min-h-full md:block">
          <OperationsListGrid
            className="min-h-full"
            items={rows}
            expandedItemId={expandedId}
            renderExpandedRow={(document) => (
              <ReturnLines
                document={document}
                selectedLineId={selectedLineId}
              />
            )}
            columns={[
              {
                id: "createdAt",
                label: "Создан",
                className: "w-48",
                getSortValue: (document) => document.createdAt,
                render: (document) => formatDateTime(document.createdAt),
              },
              {
                id: "party",
                label: "От кого",
                className: "min-w-56",
                getSortValue: (document) => document.partySnapshot ?? "",
                render: (document) => document.partySnapshot ?? "Не указан",
              },
              {
                id: "driver",
                label: "Водитель",
                className: "min-w-52",
                getSortValue: (document) => document.driverSnapshot ?? "",
                render: (document) => document.driverSnapshot ?? "Не указан",
              },
              {
                id: "state",
                label: "Статус",
                className: "w-52",
                getSortValue: (document) => RETURN_STATE_LABELS[document.state],
                render: (document) => (
                  <Badge variant={statusVariant(document.state)}>
                    {RETURN_STATE_LABELS[document.state]}
                  </Badge>
                ),
              },
              {
                id: "lines",
                label: "Бытовок",
                className: "w-24",
                getSortValue: (document) => document.lines.length,
                render: (document) => document.lines.length,
              },
              {
                id: "actions",
                label: "Действия",
                className: "min-w-80",
                getSortValue: (document) => document.updatedAt,
                render: actions,
              },
            ]}
          />
        </div>

        <div className="grid gap-3 md:hidden">
          {rows.map((document) => (
            <Card key={document.id} size="sm">
              <CardHeader>
                <CardTitle>{document.partySnapshot ?? "Возврат"}</CardTitle>
                <CardDescription>
                  {formatDateTime(document.createdAt)} ·{" "}
                  {document.driverSnapshot ?? "Водитель не указан"}
                </CardDescription>
                <CardAction>
                  <Badge variant={statusVariant(document.state)}>
                    {RETURN_STATE_LABELS[document.state]}
                  </Badge>
                </CardAction>
              </CardHeader>
              <CardContent>
                <ReturnLines
                  document={document}
                  selectedLineId={selectedLineId}
                />
              </CardContent>
              <CardFooter className="flex-wrap gap-2">
                {actions(document)}
              </CardFooter>
            </Card>
          ))}
          {rows.length === 0 && !query.isLoading ? (
            <Card size="sm">
              <CardHeader>
                <CardTitle>Возвраты не найдены</CardTitle>
                <CardDescription>
                  Измените фильтр или добавьте возврат из аренды.
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
        <CreateReturnDialog
          accessToken={accessToken}
          warehouseId={selectedWarehouseId}
          onOpenChange={setCreateOpen}
        />
      ) : null}
      {estimateTarget &&
      accessToken &&
      hasWarehouseAccess(currentUser, estimateTarget.warehouseId, "EDIT") ? (
        <RequestEstimateDialog
          accessToken={accessToken}
          document={estimateTarget}
          onOpenChange={(open) => !open && setEstimateTarget(null)}
          onSuccess={(result) => {
            storeServiceProjection(queryClient, result)
            void refresh(result.warehouseId)
            setEstimateTarget(null)
            setCommandError(null)
          }}
          onConflict={(cause) => {
            void refresh(estimateTarget.warehouseId)
            setEstimateTarget(null)
            setCommandError(errorMessage(cause, "Версия возврата изменилась"))
          }}
        />
      ) : null}
      {acceptTarget &&
      accessToken &&
      hasWarehouseAccess(currentUser, acceptTarget.warehouseId, "EDIT") ? (
        <AcceptUndamagedDialog
          accessToken={accessToken}
          document={acceptTarget}
          onOpenChange={(open) => !open && setAcceptTarget(null)}
          onSuccess={(result) => {
            storeServiceProjection(queryClient, result)
            void refresh(result.warehouseId)
            setAcceptTarget(null)
            setCommandError(null)
          }}
          onConflict={(cause) => {
            void refresh(acceptTarget.warehouseId)
            setAcceptTarget(null)
            setCommandError(errorMessage(cause, "Версия возврата изменилась"))
          }}
        />
      ) : null}
    </div>
  )
}

function ReturnLines({
  document,
  selectedLineId,
}: {
  document: ReturnDocument
  selectedLineId: string | null
}) {
  return (
    <div className="grid gap-2">
      {document.lines.map((line) => (
        <Card
          key={line.id}
          size="sm"
          data-selected={line.id === selectedLineId}
          className="data-[selected=true]:ring-2 data-[selected=true]:ring-ring"
        >
          <CardHeader>
            <CardTitle>{lineSummary(line)}</CardTitle>
            <CardDescription>Строка {line.lineNumber}</CardDescription>
            <CardAction>
              <Badge variant="outline">v{line.version}</Badge>
            </CardAction>
          </CardHeader>
          <CardContent className="grid gap-1 text-sm">
            <span>
              Asset: <span className="font-mono text-xs">{line.assetId}</span>
            </span>
            <span>
              Аренда:{" "}
              <span className="font-mono text-xs">{line.rentalOrderId}</span>
            </span>
          </CardContent>
        </Card>
      ))}
    </div>
  )
}

function CreateReturnDialog({
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
  const [client, setClient] = useState<OrderClientSearchItem | null>(null)
  const [driver, setDriver] = useState<RepairTaskWorkerSnapshotDto | null>(null)
  const [lines, setLines] = useState<ReturnLineDraft[]>(() => [
    emptyReturnLine(),
  ])
  const [validationError, setValidationError] = useState<string | null>(null)
  const commandAttempt = useRef<CommandAttempt | null>(null)

  const ordersQuery = useQuery({
    queryKey: [
      ...ORDERS_QUERY_KEY,
      "return-orders",
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
  const candidatesQuery = useQuery({
    queryKey: [
      ...ORDERS_QUERY_KEY,
      "return-candidates",
      warehouseId,
      orders.map((order) => order.id).join("|"),
    ],
    queryFn: async () => {
      const details = await Promise.all(
        orders.map((order) => getOrder(accessToken, order.id))
      )
      return returnCandidates(details)
    },
    enabled: client !== null && orders.length > 0,
  })
  const candidates = useMemo(
    () => candidatesQuery.data ?? [],
    [candidatesQuery.data]
  )
  const candidatesByOrder = useMemo(() => {
    const groups = new Map<string, ReturnCandidate[]>()
    for (const candidate of candidates) {
      const group = groups.get(candidate.rentalOrderId) ?? []
      group.push(candidate)
      groups.set(candidate.rentalOrderId, group)
    }
    return groups
  }, [candidates])
  const selectedAssetIds = lines
    .map((line) => line.assetId)
    .filter((assetId): assetId is string => assetId !== null)
  const candidateByAssetId = new Map(
    candidates.map((item) => [item.unit.id, item])
  )
  const mutation = useMutation({
    mutationFn: ({
      clientId,
      driverSnapshot,
      lines: commandLines,
      idempotencyKey,
    }: {
      clientId: string
      driverSnapshot: string
      lines: CreateReturnLine[]
      idempotencyKey: string
    }) =>
      createReturn({
        accessToken,
        warehouseId,
        clientId,
        driverSnapshot,
        lines: commandLines,
        idempotencyKey,
      }),
    onSuccess: (result) => {
      storeServiceProjection(queryClient, result)
      void queryClient.invalidateQueries({
        queryKey: returnListQueryKey(result.warehouseId),
      })
      onOpenChange(false)
    },
  })

  function selectCandidate(key: string, candidate: ReturnCandidate | null) {
    setLines((current) =>
      current.map((line) =>
        line.key === key
          ? { ...line, assetId: candidate?.unit.id ?? null }
          : line
      )
    )
  }

  function submit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault()
    const selected = lines.map((line) =>
      line.assetId ? (candidateByAssetId.get(line.assetId) ?? null) : null
    )
    if (
      !client ||
      !driver ||
      selected.some((candidate) => candidate === null)
    ) {
      setValidationError(
        "Выберите контрагента, водителя и бытовку в каждой строке возврата."
      )
      return
    }
    const candidatesForCommand = selected as ReturnCandidate[]
    if (
      new Set(candidatesForCommand.map((candidate) => candidate.unit.id))
        .size !== candidatesForCommand.length
    ) {
      setValidationError(
        "Одну бытовку можно добавить в возврат только один раз."
      )
      return
    }
    const commandLines = candidatesForCommand.map((candidate) => ({
      assetId: candidate.unit.id,
      assetVersion: candidate.unit.version,
      tenantSnapshot: client.displayName,
      rentalOrderId: candidate.rentalOrderId,
    }))
    const signature = JSON.stringify({
      warehouseId,
      clientId: client.id,
      driverSnapshot: driver.name,
      lines: commandLines,
    })
    setValidationError(null)
    mutation.mutate({
      clientId: client.id,
      driverSnapshot: driver.name,
      lines: commandLines,
      idempotencyKey: stableCommandKey(commandAttempt, signature),
    })
  }

  return (
    <Dialog open onOpenChange={onOpenChange}>
      <DialogContent
        ref={contentRef}
        className="max-h-[calc(100svh-1rem)] overflow-y-auto sm:max-w-4xl"
      >
        <form onSubmit={submit}>
          <DialogHeader>
            <DialogTitle>Добавить возврат из аренды</DialogTitle>
            <DialogDescription>
              Выберите контрагента, водителя и бытовки, которые находятся у
              контрагента в активной аренде. Каждая строка сохраняет связь с
              конкретным заказом.
            </DialogDescription>
          </DialogHeader>
          <FieldGroup className="py-4">
            <RentalClientPicker
              accessToken={accessToken}
              idPrefix="return"
              portalContainer={contentRef}
              value={client}
              disabled={mutation.isPending}
              onChange={(next) => {
                setClient(next)
                setLines([emptyReturnLine()])
                commandAttempt.current = null
                setValidationError(null)
              }}
            />
            <LogisticsDriverPicker
              accessToken={accessToken}
              id="return-driver"
              warehouseId={warehouseId}
              value={driver}
              disabled={mutation.isPending}
              onChange={(next) => {
                setDriver(next)
                commandAttempt.current = null
                setValidationError(null)
              }}
            />
            <FieldSet disabled={!client || mutation.isPending}>
              <FieldLegend variant="label">Бытовки в аренде</FieldLegend>
              <FieldDescription>
                Показаны только активные серверные резервы выбранного клиента со
                статусом «в аренде».
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
                      <CardContent>
                        <Field>
                          <FieldLabel htmlFor={`return-cabin-${line.key}`}>
                            Номер бытовки
                          </FieldLabel>
                          <Combobox<ReturnCandidate>
                            items={candidates}
                            value={selectedCandidate}
                            itemToStringLabel={(candidate) =>
                              candidate.unit.number
                            }
                            itemToStringValue={(candidate) => candidate.unit.id}
                            isItemEqualToValue={(left, right) =>
                              left.unit.id === right.unit.id
                            }
                            disabled={!client || mutation.isPending}
                            onValueChange={(candidate) =>
                              selectCandidate(line.key, candidate)
                            }
                          >
                            <ComboboxInput
                              id={`return-cabin-${line.key}`}
                              placeholder={
                                client
                                  ? "Введите номер бытовки"
                                  : "Сначала выберите клиента"
                              }
                              showClear
                            />
                            <ComboboxContent portalContainer={contentRef}>
                              <ComboboxEmpty>
                                {candidatesQuery.isFetching
                                  ? "Загружаем бытовки…"
                                  : ordersQuery.isFetching
                                    ? "Загружаем аренды…"
                                    : "Бытовки в аренде не найдены"}
                              </ComboboxEmpty>
                              <ComboboxList>
                                {Array.from(candidatesByOrder.entries()).map(
                                  ([orderId, orderCandidates]) => {
                                    const available = orderCandidates.filter(
                                      (candidate) =>
                                        candidate.unit.id === line.assetId ||
                                        !selectedAssetIds.includes(
                                          candidate.unit.id
                                        )
                                    )
                                    if (!available.length) return null
                                    return (
                                      <ComboboxGroup key={orderId}>
                                        <ComboboxLabel>
                                          Аренда {available[0]?.orderNumber}
                                        </ComboboxLabel>
                                        {available.map((candidate) => (
                                          <ComboboxItem
                                            key={candidate.unit.id}
                                            value={candidate}
                                          >
                                            <span>{candidate.unit.number}</span>
                                            <Badge variant="secondary">
                                              В аренде
                                            </Badge>
                                          </ComboboxItem>
                                        ))}
                                      </ComboboxGroup>
                                    )
                                  }
                                )}
                              </ComboboxList>
                            </ComboboxContent>
                          </Combobox>
                        </Field>
                        {selectedCandidate ? (
                          <FieldDescription>
                            Аренда {selectedCandidate.orderNumber} · версия
                            бытовки {selectedCandidate.unit.version}
                          </FieldDescription>
                        ) : null}
                      </CardContent>
                    </Card>
                  )
                })}
                <Button
                  type="button"
                  variant="outline"
                  disabled={
                    !client ||
                    mutation.isPending ||
                    lines.length >= 100 ||
                    candidates.length === 0 ||
                    lines.some((line) => line.assetId === null)
                  }
                  onClick={() =>
                    setLines((current) => [...current, emptyReturnLine()])
                  }
                >
                  <HugeiconsIcon icon={Add01Icon} data-icon="inline-start" />
                  Добавить ещё бытовку
                </Button>
              </FieldGroup>
            </FieldSet>
            {ordersQuery.isSuccess && client && orders.length === 0 ? (
              <FieldError>
                Для выбранного клиента на этом складе нет активных аренд.
              </FieldError>
            ) : null}
            {ordersQuery.isError || candidatesQuery.isError ? (
              <FieldError>
                Не удалось загрузить доступные бытовки для возврата.
              </FieldError>
            ) : null}
            {validationError ? (
              <FieldError>{validationError}</FieldError>
            ) : null}
            {mutation.error ? (
              <FieldError>
                {errorMessage(mutation.error, "Не удалось добавить возврат")}
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
              {mutation.isPending ? "Создаётся…" : "Создать возврат"}
            </Button>
          </DialogFooter>
        </form>
      </DialogContent>
    </Dialog>
  )
}

function returnCandidates(details: OrderDetail[]): ReturnCandidate[] {
  return details
    .flatMap((order) =>
      order.units
        .filter(
          (candidate) => candidate.added && candidate.unit.status === "RENTED"
        )
        .map((candidate) => ({
          rentalOrderId: order.id,
          orderNumber: order.number,
          unit: candidate.unit,
        }))
    )
    .sort((left, right) =>
      left.unit.number.localeCompare(right.unit.number, "ru")
    )
}
