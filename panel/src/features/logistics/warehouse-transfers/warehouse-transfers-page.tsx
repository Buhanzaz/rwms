import { useMemo, useRef, useState, type FormEvent } from "react"
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query"
import { Add01Icon, Delete02Icon } from "@hugeicons/core-free-icons"
import { HugeiconsIcon } from "@hugeicons/react"

import type { WarehouseInfo } from "@/api/warehouse-api"
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
import { Textarea } from "@/components/ui/textarea"
import { hasWarehouseAccess } from "@/features/auth/warehouse-access"
import { useAuth } from "@/features/auth/use-auth"
import {
  WAREHOUSE_TRANSFERS_QUERY_KEY,
  arriveWarehouseTransferLine,
  cancelWarehouseTransfer,
  createWarehouseTransfer,
  departWarehouseTransferLine,
  getWarehouseTransfer,
  listWarehouseTransfers,
  reconcileWarehouseTransfer,
} from "@/features/logistics/warehouse-transfers/api/warehouse-transfer-api"
import {
  TRANSFER_LINE_STATE_LABELS,
  TRANSFER_STATE_LABELS,
  type TransferDocument,
  type TransferDocumentState,
  type TransferLine,
  type TransferMediaReference,
} from "@/features/logistics/warehouse-transfers/model/warehouse-transfer"
import { useWarehouse } from "@/hooks/use-warehouse"
import { ApiError } from "@/lib/api-client"
import { logisticsTransferMediaOwner } from "@/features/media/media-service"
import { ServiceOwnerPhotos } from "@/features/media/service-owner-photos"

const ACTIONABLE_STATES = new Set<TransferDocumentState>([
  "DRAFT",
  "DEPARTING",
  "IN_TRANSIT",
  "CONFLICT",
  "RECONCILIATION_REQUIRED",
])
const UUID_PATTERN =
  /^[0-9a-f]{8}-[0-9a-f]{4}-[1-8][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/i

function formatDateTime(value: string) {
  return new Intl.DateTimeFormat("ru-RU", {
    dateStyle: "medium",
    timeStyle: "short",
  }).format(new Date(value))
}

function errorMessage(cause: unknown, fallback: string) {
  return cause instanceof Error ? cause.message : fallback
}

function isConflict(cause: unknown): cause is ApiError {
  return cause instanceof ApiError && cause.status === 409
}

function commandErrorMessage(cause: unknown, fallback: string) {
  const message = errorMessage(cause, fallback)
  return isConflict(cause)
    ? `Конфликт данных: ${message} Данные документа обновляются; повторите действие после обновления.`
    : message
}

function commandIdentity() {
  return crypto.randomUUID()
}

function statusVariant(state: TransferDocumentState) {
  if (state === "CONFLICT" || state === "RECONCILIATION_REQUIRED") {
    return "destructive" as const
  }
  if (state === "COMPLETED") return "secondary" as const
  return "outline" as const
}

function warehouseLabel(warehouse: WarehouseInfo | undefined, id: string) {
  return warehouse ? `${warehouse.name} · ${warehouse.city}` : id
}

function commandKey(
  action: string,
  document: TransferDocument,
  line?: TransferLine,
  fingerprint = ""
) {
  return [
    action,
    document.id,
    document.version,
    line?.id ?? "document",
    line?.version ?? "",
    fingerprint,
  ].join(":")
}

type TransferLineTarget = {
  document: TransferDocument
  line: TransferLine
}

export function WarehouseTransfersPage() {
  const { accessToken, currentUser } = useAuth()
  const { selectedWarehouseId, warehouses } = useWarehouse()
  const queryClient = useQueryClient()
  const [search, setSearch] = useState("")
  const [showAll, setShowAll] = useState(false)
  const [expandedId, setExpandedId] = useState<string | null>(null)
  const [createOpen, setCreateOpen] = useState(false)
  const [arrivalTarget, setArrivalTarget] = useState<TransferLineTarget | null>(
    null
  )
  const [cancelTarget, setCancelTarget] = useState<TransferDocument | null>(
    null
  )
  const [reconcileTarget, setReconcileTarget] =
    useState<TransferDocument | null>(null)
  const [commandError, setCommandError] = useState<string | null>(null)
  const commandKeys = useRef(new Map<string, string>())
  const canEditSelectedWarehouse =
    selectedWarehouseId !== null &&
    hasWarehouseAccess(currentUser, selectedWarehouseId, "EDIT")
  const canCreateTransfer =
    canEditSelectedWarehouse &&
    warehouses.some(
      (warehouse) =>
        warehouse.active &&
        warehouse.id !== selectedWarehouseId &&
        hasWarehouseAccess(currentUser, warehouse.id, "EDIT")
    )

  const query = useQuery({
    queryKey: [...WAREHOUSE_TRANSFERS_QUERY_KEY, selectedWarehouseId],
    queryFn: () => listWarehouseTransfers(accessToken!, selectedWarehouseId!),
    enabled: Boolean(accessToken && selectedWarehouseId),
    refetchInterval: 5_000,
  })

  const detailQuery = useQuery({
    queryKey: [...WAREHOUSE_TRANSFERS_QUERY_KEY, "detail", expandedId],
    queryFn: () => getWarehouseTransfer(accessToken!, expandedId!),
    enabled: Boolean(accessToken && expandedId),
  })

  const rows = useMemo(() => {
    const needle = search.trim().toLocaleLowerCase("ru")
    return (query.data ?? []).filter((document) => {
      if (!showAll && !ACTIONABLE_STATES.has(document.state)) return false
      if (!needle) return true
      return [
        document.id,
        document.warehouseId,
        document.destinationWarehouseId,
        TRANSFER_STATE_LABELS[document.state],
        ...document.lines.flatMap((line) => [line.id, line.assetId]),
      ].some((value) => value.toLocaleLowerCase("ru").includes(needle))
    })
  }, [query.data, search, showAll])

  function keyFor(
    action: string,
    document: TransferDocument,
    line?: TransferLine,
    fingerprint = ""
  ) {
    const identity = commandKey(action, document, line, fingerprint)
    const existing = commandKeys.current.get(identity)
    if (existing) return existing
    const created = commandIdentity()
    commandKeys.current.set(identity, created)
    return created
  }

  function invalidateTransfers() {
    void queryClient.invalidateQueries({
      queryKey: WAREHOUSE_TRANSFERS_QUERY_KEY,
    })
  }

  function applyTransferProjection(projection: TransferDocument) {
    if (selectedWarehouseId) {
      queryClient.setQueryData<TransferDocument[]>(
        [...WAREHOUSE_TRANSFERS_QUERY_KEY, selectedWarehouseId],
        (current) =>
          current?.map((document) =>
            document.id === projection.id ? projection : document
          )
      )
    }
    queryClient.setQueryData<TransferDocument>(
      [...WAREHOUSE_TRANSFERS_QUERY_KEY, "detail", projection.id],
      (current) => (current ? projection : current)
    )
  }

  function canManageDocument(document: TransferDocument) {
    return (
      hasWarehouseAccess(currentUser, document.warehouseId, "MANAGE") &&
      hasWarehouseAccess(currentUser, document.destinationWarehouseId, "MANAGE")
    )
  }

  function handleCommandError(cause: unknown, fallback: string) {
    setCommandError(commandErrorMessage(cause, fallback))
    if (isConflict(cause)) {
      invalidateTransfers()
      return true
    }
    return false
  }

  const departMutation = useMutation({
    mutationFn: ({
      document,
      line,
    }: {
      document: TransferDocument
      line: TransferLine
    }) => {
      if (!canManageDocument(document)) {
        throw new Error(
          "Для отправки нужны права MANAGE на обоих складах перемещения."
        )
      }
      return departWarehouseTransferLine({
        accessToken: accessToken!,
        documentId: document.id,
        lineId: line.id,
        expectedVersion: document.version,
        expectedLineVersion: line.version,
        idempotencyKey: keyFor("depart", document, line),
      })
    },
    onSuccess: (result, { document, line }) => {
      applyTransferProjection(result)
      commandKeys.current.delete(commandKey("depart", document, line))
      setCommandError(null)
      invalidateTransfers()
    },
    onError: (cause, { document, line }) => {
      if (handleCommandError(cause, "Не удалось отправить бытовку")) {
        commandKeys.current.delete(commandKey("depart", document, line))
      }
    },
  })

  const arriveMutation = useMutation({
    mutationFn: ({
      document,
      line,
      references,
    }: TransferLineTarget & { references: TransferMediaReference[] }) => {
      if (!canManageDocument(document)) {
        throw new Error(
          "Для приёмки нужны права MANAGE на обоих складах перемещения."
        )
      }
      const fingerprint = JSON.stringify(references)
      return arriveWarehouseTransferLine({
        accessToken: accessToken!,
        documentId: document.id,
        lineId: line.id,
        expectedVersion: document.version,
        expectedLineVersion: line.version,
        idempotencyKey: keyFor("arrive", document, line, fingerprint),
        references,
      })
    },
    onSuccess: (result, { document, line, references }) => {
      applyTransferProjection(result)
      commandKeys.current.delete(
        commandKey("arrive", document, line, JSON.stringify(references))
      )
      setArrivalTarget(null)
      setCommandError(null)
      invalidateTransfers()
    },
    onError: (cause, { document, line, references }) => {
      if (handleCommandError(cause, "Не удалось принять бытовку")) {
        commandKeys.current.delete(
          commandKey("arrive", document, line, JSON.stringify(references))
        )
        setArrivalTarget(null)
      }
    },
  })

  const cancelMutation = useMutation({
    mutationFn: (document: TransferDocument) => {
      if (!canManageDocument(document)) {
        throw new Error(
          "Для отмены нужны права MANAGE на обоих складах перемещения."
        )
      }
      return cancelWarehouseTransfer({
        accessToken: accessToken!,
        documentId: document.id,
        expectedVersion: document.version,
        idempotencyKey: keyFor("cancel", document),
      })
    },
    onSuccess: (result, document) => {
      applyTransferProjection(result)
      commandKeys.current.delete(commandKey("cancel", document))
      setCancelTarget(null)
      setCommandError(null)
      invalidateTransfers()
    },
    onError: (cause, document) => {
      if (handleCommandError(cause, "Не удалось отменить перемещение")) {
        commandKeys.current.delete(commandKey("cancel", document))
        setCancelTarget(null)
      }
    },
  })

  const reconcileMutation = useMutation({
    mutationFn: ({
      document,
      reason,
    }: {
      document: TransferDocument
      reason: string
    }) => {
      if (!canManageDocument(document)) {
        throw new Error(
          "Для сверки нужны права MANAGE на обоих складах перемещения."
        )
      }
      return reconcileWarehouseTransfer({
        accessToken: accessToken!,
        documentId: document.id,
        expectedVersion: document.version,
        idempotencyKey: keyFor("reconcile", document, undefined, reason),
        reason,
      })
    },
    onSuccess: (result, { document, reason }) => {
      applyTransferProjection(result)
      commandKeys.current.delete(
        commandKey("reconcile", document, undefined, reason)
      )
      setReconcileTarget(null)
      setCommandError(null)
      invalidateTransfers()
    },
    onError: (cause, { document, reason }) => {
      if (handleCommandError(cause, "Не удалось выполнить сверку")) {
        commandKeys.current.delete(
          commandKey("reconcile", document, undefined, reason)
        )
        setReconcileTarget(null)
      }
    },
  })

  function currentDocument(document: TransferDocument) {
    return expandedId === document.id && detailQuery.data?.id === document.id
      ? detailQuery.data
      : document
  }

  function actions(document: TransferDocument) {
    const current = currentDocument(document)
    const canManage = canManageDocument(current)
    return (
      <div className="flex flex-wrap gap-2">
        <Button
          size="sm"
          variant="outline"
          onClick={() =>
            setExpandedId((value) =>
              value === document.id ? null : document.id
            )
          }
        >
          {expandedId === document.id ? "Скрыть строки" : "Показать строки"}
        </Button>
        {canManage && current.state === "DRAFT" ? (
          <Button
            size="sm"
            variant="outline"
            disabled={cancelMutation.isPending}
            onClick={() => {
              setCommandError(null)
              setCancelTarget(current)
            }}
          >
            Отменить документ
          </Button>
        ) : null}
        {canManage &&
        (current.state === "CONFLICT" ||
          current.state === "RECONCILIATION_REQUIRED") ? (
          <Button
            size="sm"
            onClick={() => {
              setCommandError(null)
              setReconcileTarget(current)
            }}
          >
            Выполнить сверку
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
            aria-label="Поиск перемещений"
            placeholder="ID документа, строки, asset или склада"
            value={search}
            onChange={(event) => setSearch(event.target.value)}
          />
        </PageToolbarContent>
        <PageToolbarActions>
          <Button
            variant="outline"
            onClick={() => setShowAll((value) => !value)}
          >
            {showAll ? "Требуют действий" : "Показать завершённые"}
          </Button>
          <Button
            variant="outline"
            disabled={!accessToken || !selectedWarehouseId || query.isFetching}
            onClick={() => void query.refetch()}
          >
            {query.isFetching ? "Обновляется…" : "Обновить"}
          </Button>
          {canCreateTransfer ? (
            <Button onClick={() => setCreateOpen(true)}>
              <HugeiconsIcon icon={Add01Icon} data-icon="inline-start" />
              Создать перемещение
            </Button>
          ) : null}
        </PageToolbarActions>
      </PageToolbar>

      <Card size="sm">
        <CardHeader>
          <CardTitle>Перемещения обслуживает logistics-service</CardTitle>
          <CardDescription>
            Документы, строки, версии, отправка, отмена и сверка работают через
            gateway. Браузер больше не создаёт задания и не меняет бытовки
            напрямую.
          </CardDescription>
        </CardHeader>
        <CardContent className="text-sm text-muted-foreground">
          Публичного списка кандидатов пока нет, поэтому при создании нужны
          server-issued asset UUID и версия. При приёмке фотографии загружаются
          для строки перемещения на складе назначения и передаются только после
          статуса READY. Перенос наполнения между бытовками недоступен до
          появления server command.
        </CardContent>
      </Card>

      {!accessToken ? (
        <FieldError>
          Для просмотра перемещений требуется авторизация.
        </FieldError>
      ) : null}
      {!selectedWarehouseId ? (
        <FieldError>Выберите склад для просмотра перемещений.</FieldError>
      ) : null}
      {query.error ? (
        <FieldError>
          {errorMessage(query.error, "Не удалось загрузить перемещения")}
        </FieldError>
      ) : null}
      {commandError && !arrivalTarget && !cancelTarget && !reconcileTarget ? (
        <FieldError>{commandError}</FieldError>
      ) : null}

      <div className="min-h-0 flex-1 overflow-auto">
        <div className="hidden min-h-full md:block">
          <OperationsListGrid
            className="min-h-full"
            items={rows}
            expandedItemId={expandedId}
            renderExpandedRow={(document) => {
              const current = currentDocument(document)
              return (
                <div className="flex flex-col gap-2">
                  {detailQuery.error && expandedId === document.id ? (
                    <FieldError>
                      {errorMessage(
                        detailQuery.error,
                        "Не удалось обновить документ"
                      )}
                    </FieldError>
                  ) : null}
                  <TransferLines
                    document={current}
                    canManage={canManageDocument(current)}
                    departingLineId={
                      departMutation.isPending
                        ? (departMutation.variables?.line.id ?? null)
                        : null
                    }
                    arrivingLineId={
                      arriveMutation.isPending
                        ? (arriveMutation.variables?.line.id ?? null)
                        : null
                    }
                    onDepart={(line) => {
                      setCommandError(null)
                      departMutation.mutate({ document: current, line })
                    }}
                    onArrive={(line) => {
                      setCommandError(null)
                      setArrivalTarget({ document: current, line })
                    }}
                  />
                </div>
              )
            }}
            columns={[
              {
                id: "updatedAt",
                label: "Обновлено",
                className: "w-48",
                getSortValue: (document) => document.updatedAt,
                render: (document) => formatDateTime(document.updatedAt),
              },
              {
                id: "document",
                label: "Документ",
                className: "min-w-64",
                getSortValue: (document) => document.id,
                render: (document) => (
                  <span className="font-mono text-xs">{document.id}</span>
                ),
              },
              {
                id: "direction",
                label: "Маршрут",
                className: "min-w-72",
                getSortValue: (document) =>
                  `${document.warehouseId}:${document.destinationWarehouseId}`,
                render: (document) => (
                  <div className="flex flex-col gap-1">
                    <span>
                      {warehouseLabel(
                        warehouses.find(
                          (warehouse) => warehouse.id === document.warehouseId
                        ),
                        document.warehouseId
                      )}
                    </span>
                    <span className="text-muted-foreground">
                      →{" "}
                      {warehouseLabel(
                        warehouses.find(
                          (warehouse) =>
                            warehouse.id === document.destinationWarehouseId
                        ),
                        document.destinationWarehouseId
                      )}
                    </span>
                  </div>
                ),
              },
              {
                id: "state",
                label: "Статус",
                className: "w-52",
                getSortValue: (document) =>
                  TRANSFER_STATE_LABELS[document.state],
                render: (document) => (
                  <Badge variant={statusVariant(document.state)}>
                    {TRANSFER_STATE_LABELS[document.state]}
                  </Badge>
                ),
              },
              {
                id: "lines",
                label: "Строк",
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
                <CardTitle>Перемещение {document.id.slice(0, 8)}</CardTitle>
                <CardDescription>
                  {formatDateTime(document.updatedAt)}
                </CardDescription>
                <CardAction>
                  <Badge variant={statusVariant(document.state)}>
                    {TRANSFER_STATE_LABELS[document.state]}
                  </Badge>
                </CardAction>
              </CardHeader>
              <CardContent className="flex flex-col gap-3">
                <p className="text-sm text-muted-foreground">
                  {warehouseLabel(
                    warehouses.find(
                      (warehouse) => warehouse.id === document.warehouseId
                    ),
                    document.warehouseId
                  )}
                  {" → "}
                  {warehouseLabel(
                    warehouses.find(
                      (warehouse) =>
                        warehouse.id === document.destinationWarehouseId
                    ),
                    document.destinationWarehouseId
                  )}
                </p>
                <TransferLines
                  document={document}
                  canManage={canManageDocument(document)}
                  departingLineId={
                    departMutation.isPending
                      ? (departMutation.variables?.line.id ?? null)
                      : null
                  }
                  arrivingLineId={
                    arriveMutation.isPending
                      ? (arriveMutation.variables?.line.id ?? null)
                      : null
                  }
                  onDepart={(line) => {
                    setCommandError(null)
                    departMutation.mutate({ document, line })
                  }}
                  onArrive={(line) => {
                    setCommandError(null)
                    setArrivalTarget({ document, line })
                  }}
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
                <CardTitle>Перемещения не найдены</CardTitle>
                <CardDescription>
                  Измените фильтр или создайте новый документ.
                </CardDescription>
              </CardHeader>
            </Card>
          ) : null}
        </div>
      </div>

      {createOpen && accessToken && selectedWarehouseId && canCreateTransfer ? (
        <CreateTransferDialog
          accessToken={accessToken}
          currentUser={currentUser}
          warehouseId={selectedWarehouseId}
          warehouses={warehouses}
          onOpenChange={setCreateOpen}
        />
      ) : null}
      {arrivalTarget && canManageDocument(arrivalTarget.document) ? (
        <ArrivalTransferDialog
          key={`${arrivalTarget.document.id}:${arrivalTarget.document.version}:${arrivalTarget.line.id}:${arrivalTarget.line.version}`}
          document={arrivalTarget.document}
          line={arrivalTarget.line}
          accessToken={accessToken}
          pending={arriveMutation.isPending}
          error={commandError}
          onSubmit={(references) =>
            arriveMutation.mutate({ ...arrivalTarget, references })
          }
          onOpenChange={(open) => {
            if (!open) {
              setArrivalTarget(null)
              setCommandError(null)
            }
          }}
        />
      ) : null}
      {reconcileTarget &&
      hasWarehouseAccess(currentUser, reconcileTarget.warehouseId, "MANAGE") &&
      hasWarehouseAccess(
        currentUser,
        reconcileTarget.destinationWarehouseId,
        "MANAGE"
      ) ? (
        <ReconcileTransferDialog
          document={reconcileTarget}
          pending={reconcileMutation.isPending}
          error={commandError}
          onSubmit={(reason) =>
            reconcileMutation.mutate({ document: reconcileTarget, reason })
          }
          onOpenChange={(open) => {
            if (!open) {
              setReconcileTarget(null)
              setCommandError(null)
            }
          }}
        />
      ) : null}
      <AlertDialog
        open={cancelTarget !== null}
        onOpenChange={(open) => {
          if (!open) {
            setCancelTarget(null)
            setCommandError(null)
          }
        }}
      >
        <AlertDialogContent>
          <AlertDialogHeader>
            <AlertDialogTitle>Отменить перемещение?</AlertDialogTitle>
            <AlertDialogDescription>
              Logistics-service отменит весь документ. Отмена отдельной строки
              публичным контрактом не поддерживается.
            </AlertDialogDescription>
          </AlertDialogHeader>
          {commandError ? <FieldError>{commandError}</FieldError> : null}
          <AlertDialogFooter>
            <AlertDialogCancel>Не отменять</AlertDialogCancel>
            <AlertDialogAction
              disabled={cancelMutation.isPending}
              onClick={() =>
                cancelTarget && cancelMutation.mutate(cancelTarget)
              }
            >
              {cancelMutation.isPending ? "Отменяется…" : "Отменить документ"}
            </AlertDialogAction>
          </AlertDialogFooter>
        </AlertDialogContent>
      </AlertDialog>
    </div>
  )
}

function TransferLines({
  document,
  canManage,
  departingLineId,
  arrivingLineId,
  onDepart,
  onArrive,
}: {
  document: TransferDocument
  canManage: boolean
  departingLineId: string | null
  arrivingLineId: string | null
  onDepart: (line: TransferLine) => void
  onArrive: (line: TransferLine) => void
}) {
  const lineCommandPending = departingLineId !== null || arrivingLineId !== null

  return (
    <div className="grid gap-2">
      {document.lines.map((line) => (
        <Card key={line.id} size="sm">
          <CardHeader>
            <CardTitle>Строка {line.lineNumber}</CardTitle>
            <CardDescription>
              Asset <span className="font-mono text-xs">{line.assetId}</span>
            </CardDescription>
            <CardAction>
              <Badge variant="outline">
                {TRANSFER_LINE_STATE_LABELS[line.state]}
              </Badge>
            </CardAction>
          </CardHeader>
          <CardContent className="flex flex-col gap-2 text-sm">
            <span>
              Asset version: {line.assetVersion} · Line version: {line.version}
            </span>
            {canManage &&
            line.state === "PENDING" &&
            (document.state === "DRAFT" || document.state === "DEPARTING") ? (
              <Button
                type="button"
                className="w-fit"
                size="sm"
                disabled={lineCommandPending}
                onClick={() => onDepart(line)}
              >
                {departingLineId === line.id ? "Отправляется…" : "Отправить"}
              </Button>
            ) : null}
            {line.state === "DEPARTED" ? (
              <div className="flex flex-col items-start gap-2">
                {canManage &&
                (document.state === "IN_TRANSIT" ||
                  document.state === "ARRIVING") ? (
                  <Button
                    type="button"
                    className="w-fit"
                    size="sm"
                    disabled={lineCommandPending}
                    onClick={() => onArrive(line)}
                  >
                    {arrivingLineId === line.id ? "Принимается…" : "Принять"}
                  </Button>
                ) : null}
                <span className="text-muted-foreground">
                  Перед приёмкой добавьте фотографии состояния бытовки на складе
                  назначения.
                </span>
              </div>
            ) : null}
          </CardContent>
        </Card>
      ))}
    </div>
  )
}

function ArrivalTransferDialog({
  accessToken,
  document,
  line,
  pending,
  error,
  onSubmit,
  onOpenChange,
}: {
  accessToken: string | null
  document: TransferDocument
  line: TransferLine
  pending: boolean
  error: string | null
  onSubmit: (references: TransferMediaReference[]) => void
  onOpenChange: (open: boolean) => void
}) {
  const [references, setReferences] = useState<TransferMediaReference[]>([])
  const [validationError, setValidationError] = useState<string | null>(null)

  function submit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault()
    if (references.length === 0 || references.length > 20) {
      setValidationError(
        "Добавьте хотя бы одну готовую фотографию приёмки строки."
      )
      return
    }

    setValidationError(null)
    onSubmit(references)
  }

  return (
    <Dialog open onOpenChange={onOpenChange}>
      <DialogContent className="max-h-[calc(100svh-1rem)] overflow-y-auto sm:max-w-2xl">
        <form onSubmit={submit}>
          <DialogHeader>
            <DialogTitle>Принять строку перемещения</DialogTitle>
            <DialogDescription>
              Logistics-service примет строку {line.lineNumber} документа{" "}
              {document.id.slice(0, 8)} по её текущим версиям. Загрузите
              фотографии состояния бытовки на складе назначения.
            </DialogDescription>
          </DialogHeader>

          <FieldSet className="py-4">
            <FieldLegend variant="label">Фотографии приёмки</FieldLegend>
            <FieldDescription>
              Media-service создаёт small, medium и large как варианты одной
              логической фотографии.
            </FieldDescription>
            <ServiceOwnerPhotos
              accessToken={accessToken}
              owner={logisticsTransferMediaOwner(
                document.id,
                line.id,
                document.destinationWarehouseId
              )}
              readOnly={pending}
              maxItems={20}
              title={`Фотографии строки ${line.lineNumber}`}
              onReadyReferencesChange={(nextReferences) => {
                setReferences((current) =>
                  current.length === nextReferences.length &&
                  current.every(
                    (reference, index) =>
                      reference.mediaId === nextReferences[index]?.mediaId &&
                      reference.generation === nextReferences[index]?.generation
                  )
                    ? current
                    : nextReferences
                )
              }}
            />
          </FieldSet>

          {validationError ? <FieldError>{validationError}</FieldError> : null}
          {error ? <FieldError>{error}</FieldError> : null}

          <DialogFooter>
            <Button
              type="button"
              variant="outline"
              disabled={pending}
              onClick={() => onOpenChange(false)}
            >
              Отмена
            </Button>
            <Button type="submit" disabled={pending}>
              {pending ? "Принимается…" : "Принять строку"}
            </Button>
          </DialogFooter>
        </form>
      </DialogContent>
    </Dialog>
  )
}

type TransferLineDraft = {
  key: number
  assetId: string
  assetVersion: string
}

function emptyLine(key: number): TransferLineDraft {
  return { key, assetId: "", assetVersion: "0" }
}

function assetIdIsUnique(draft: TransferLineDraft, lines: TransferLineDraft[]) {
  const assetId = draft.assetId.trim().toLowerCase()
  return (
    assetId.length > 0 &&
    lines.filter((line) => line.assetId.trim().toLowerCase() === assetId)
      .length === 1
  )
}

function CreateTransferDialog({
  accessToken,
  currentUser,
  warehouseId,
  warehouses,
  onOpenChange,
}: {
  accessToken: string
  currentUser: ReturnType<typeof useAuth>["currentUser"]
  warehouseId: string
  warehouses: WarehouseInfo[]
  onOpenChange: (open: boolean) => void
}) {
  const queryClient = useQueryClient()
  const destinations = warehouses.filter(
    (warehouse) =>
      warehouse.active &&
      warehouse.id !== warehouseId &&
      hasWarehouseAccess(currentUser, warehouse.id, "EDIT")
  )
  const nextLineKey = useRef(1)
  const [destinationWarehouseId, setDestinationWarehouseId] = useState("")
  const [lines, setLines] = useState<TransferLineDraft[]>(() => [emptyLine(0)])
  const [idempotencyKey] = useState(commandIdentity)
  const [submitted, setSubmitted] = useState(false)
  const [validationError, setValidationError] = useState<string | null>(null)
  const mutation = useMutation({
    mutationFn: () =>
      createWarehouseTransfer({
        accessToken,
        warehouseId,
        destinationWarehouseId,
        idempotencyKey,
        lines: lines.map((line) => ({
          assetId: line.assetId.trim(),
          assetVersion: Number(line.assetVersion),
        })),
      }),
    onSuccess: () => {
      void queryClient.invalidateQueries({
        queryKey: WAREHOUSE_TRANSFERS_QUERY_KEY,
      })
      onOpenChange(false)
    },
  })

  function submit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault()
    setSubmitted(true)
    const invalidLine = lines.find(
      (line) =>
        !UUID_PATTERN.test(line.assetId.trim()) ||
        !assetIdIsUnique(line, lines) ||
        !Number.isSafeInteger(Number(line.assetVersion)) ||
        Number(line.assetVersion) < 0
    )
    if (
      !destinationWarehouseId ||
      lines.length === 0 ||
      lines.length > 100 ||
      invalidLine
    ) {
      setValidationError(
        "Выберите другой склад и укажите от 1 до 100 уникальных asset UUID с неотрицательной версией."
      )
      return
    }
    setValidationError(null)
    mutation.mutate()
  }

  return (
    <Dialog open onOpenChange={onOpenChange}>
      <DialogContent className="max-h-[calc(100svh-1rem)] overflow-y-auto sm:max-w-3xl">
        <form onSubmit={submit}>
          <DialogHeader>
            <DialogTitle>Создать складское перемещение</DialogTitle>
            <DialogDescription>
              Выберите склад назначения и укажите server-issued asset UUID и
              текущую версию. Поиск кандидатов появится после отдельного
              публичного контракта.
            </DialogDescription>
          </DialogHeader>
          <FieldGroup className="py-4">
            <Field>
              <FieldLabel htmlFor="transfer-destination">
                Склад назначения
              </FieldLabel>
              <Select
                value={destinationWarehouseId}
                onValueChange={setDestinationWarehouseId}
              >
                <SelectTrigger id="transfer-destination">
                  <SelectValue placeholder="Выберите склад" />
                </SelectTrigger>
                <SelectContent>
                  <SelectGroup>
                    {destinations.map((warehouse) => (
                      <SelectItem key={warehouse.id} value={warehouse.id}>
                        {warehouse.name} · {warehouse.city}
                      </SelectItem>
                    ))}
                  </SelectGroup>
                </SelectContent>
              </Select>
              {destinations.length === 0 ? (
                <FieldDescription>
                  Нет другого доступного активного склада.
                </FieldDescription>
              ) : null}
            </Field>
            <FieldSet>
              <FieldLegend variant="label">Строки перемещения</FieldLegend>
              <FieldDescription>
                Повторная отправка формы использует тот же Idempotency-Key.
              </FieldDescription>
              <FieldGroup>
                {lines.map((line, index) => {
                  const assetInvalid =
                    submitted &&
                    (!UUID_PATTERN.test(line.assetId.trim()) ||
                      !assetIdIsUnique(line, lines))
                  const assetVersion = Number(line.assetVersion)
                  const versionInvalid =
                    submitted &&
                    (!Number.isSafeInteger(assetVersion) || assetVersion < 0)

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
                              aria-label={`Удалить строку ${index + 1}`}
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
                        <FieldGroup>
                          <Field data-invalid={assetInvalid || undefined}>
                            <FieldLabel htmlFor={`transfer-asset-${line.key}`}>
                              Asset UUID
                            </FieldLabel>
                            <Input
                              id={`transfer-asset-${line.key}`}
                              required
                              value={line.assetId}
                              aria-invalid={assetInvalid || undefined}
                              placeholder="00000000-0000-0000-0000-000000000000"
                              onChange={(event) =>
                                setLines((current) =>
                                  current.map((item) =>
                                    item.key === line.key
                                      ? { ...item, assetId: event.target.value }
                                      : item
                                  )
                                )
                              }
                            />
                            {assetInvalid ? (
                              <FieldError>
                                Укажите корректный уникальный asset UUID.
                              </FieldError>
                            ) : null}
                          </Field>
                          <Field data-invalid={versionInvalid || undefined}>
                            <FieldLabel
                              htmlFor={`transfer-version-${line.key}`}
                            >
                              Текущая версия asset
                            </FieldLabel>
                            <Input
                              id={`transfer-version-${line.key}`}
                              type="number"
                              min={0}
                              step={1}
                              required
                              value={line.assetVersion}
                              aria-invalid={versionInvalid || undefined}
                              onChange={(event) =>
                                setLines((current) =>
                                  current.map((item) =>
                                    item.key === line.key
                                      ? {
                                          ...item,
                                          assetVersion: event.target.value,
                                        }
                                      : item
                                  )
                                )
                              }
                            />
                            {versionInvalid ? (
                              <FieldError>
                                Версия должна быть целым неотрицательным числом.
                              </FieldError>
                            ) : null}
                          </Field>
                        </FieldGroup>
                      </CardContent>
                    </Card>
                  )
                })}
              </FieldGroup>
              <Button
                type="button"
                variant="outline"
                disabled={lines.length >= 100}
                onClick={() => {
                  const key = nextLineKey.current
                  nextLineKey.current += 1
                  setLines((current) => [...current, emptyLine(key)])
                }}
              >
                <HugeiconsIcon icon={Add01Icon} data-icon="inline-start" />
                Добавить строку
              </Button>
            </FieldSet>
            {validationError ? (
              <FieldError>{validationError}</FieldError>
            ) : null}
            {mutation.error ? (
              <FieldError>
                {commandErrorMessage(
                  mutation.error,
                  "Не удалось создать перемещение"
                )}
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
            <Button
              type="submit"
              disabled={mutation.isPending || destinations.length === 0}
            >
              {mutation.isPending ? "Создаётся…" : "Создать черновик"}
            </Button>
          </DialogFooter>
        </form>
      </DialogContent>
    </Dialog>
  )
}

function ReconcileTransferDialog({
  document,
  pending,
  error,
  onSubmit,
  onOpenChange,
}: {
  document: TransferDocument
  pending: boolean
  error: string | null
  onSubmit: (reason: string) => void
  onOpenChange: (open: boolean) => void
}) {
  const [reason, setReason] = useState("")

  return (
    <Dialog open onOpenChange={onOpenChange}>
      <DialogContent className="sm:max-w-lg">
        <form
          onSubmit={(event) => {
            event.preventDefault()
            const normalized = reason.trim()
            if (normalized) onSubmit(normalized)
          }}
        >
          <DialogHeader>
            <DialogTitle>Сверить документ перемещения</DialogTitle>
            <DialogDescription>
              Logistics-service повторно проверит незавершённые эффекты для
              документа {document.id.slice(0, 8)} с его текущей версией.
            </DialogDescription>
          </DialogHeader>
          <FieldGroup className="py-4">
            <Field>
              <FieldLabel htmlFor="transfer-reconcile-reason">
                Причина сверки
              </FieldLabel>
              <Textarea
                id="transfer-reconcile-reason"
                required
                minLength={1}
                maxLength={500}
                value={reason}
                onChange={(event) => setReason(event.target.value)}
              />
            </Field>
          </FieldGroup>
          {error ? <FieldError>{error}</FieldError> : null}
          <DialogFooter>
            <Button
              type="button"
              variant="outline"
              onClick={() => onOpenChange(false)}
            >
              Отмена
            </Button>
            <Button type="submit" disabled={pending || !reason.trim()}>
              {pending ? "Выполняется…" : "Выполнить сверку"}
            </Button>
          </DialogFooter>
        </form>
      </DialogContent>
    </Dialog>
  )
}
