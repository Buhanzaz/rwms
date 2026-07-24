import { useMemo, useRef, useState, type FormEvent } from "react"
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query"
import { Add01Icon, Delete02Icon } from "@hugeicons/core-free-icons"
import { HugeiconsIcon } from "@hugeicons/react"

import { getEquipmentItems } from "@/api/equipment-api"
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
  Combobox,
  ComboboxCollection,
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
import { Textarea } from "@/components/ui/textarea"
import { hasWarehouseAccess } from "@/features/auth/warehouse-access"
import { useAuth } from "@/features/auth/use-auth"
import { LogisticsDriverPicker } from "@/features/logistics/logistics-driver-picker"
import { listAssetRentalItems } from "@/features/rental-items/api/asset-rental-items-api"
import {
  CabinFurnitureCompositionDialog,
  CabinFurnitureContents,
  furnitureEquipmentIds,
  type CabinFurnitureRequirementInput,
} from "@/features/rental-items/cabin-furniture-composition-dialog"
import type { RentalItemDto } from "@/features/rental-items/model/rental-item"
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
  type TransferFurnitureReplacement,
  type TransferLine,
  type TransferMediaReference,
} from "@/features/logistics/warehouse-transfers/model/warehouse-transfer"
import type { RepairTaskWorkerSnapshotDto } from "@/features/repair-tasks/model/repair-task"
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

type TransferLineTarget = {
  document: TransferDocument
  line: TransferLine
}

type TransferLineDraft = {
  key: string
  assetId: string | null
  /** null leaves the current cabin composition unchanged; [] clears it. */
  contents: CabinFurnitureRequirementInput[] | null
}

type CommandAttempt = {
  signature: string
  idempotencyKey: string
}

function commandIdentity() {
  return crypto.randomUUID()
}

function emptyLine(): TransferLineDraft {
  return { key: commandIdentity(), assetId: null, contents: null }
}

function formatDateTime(value: string) {
  return new Intl.DateTimeFormat("ru-RU", {
    dateStyle: "medium",
    timeStyle: "short",
  }).format(new Date(value))
}

function formatDate(value: string) {
  const [year, month, day] = value.split("-").map(Number)
  return new Intl.DateTimeFormat("ru-RU", {
    dateStyle: "medium",
    timeZone: "UTC",
  }).format(new Date(Date.UTC(year, month - 1, day)))
}

function localCalendarDate() {
  const now = new Date()
  const year = now.getFullYear()
  const month = String(now.getMonth() + 1).padStart(2, "0")
  const day = String(now.getDate()).padStart(2, "0")
  return `${year}-${month}-${day}`
}

function cabinFurnitureRequirements(
  cabin: Pick<RentalItemDto, "contentsItems">,
  furnitureIds?: ReadonlySet<string>
): CabinFurnitureRequirementInput[] {
  const totals = new Map<string, number>()
  for (const item of cabin.contentsItems) {
    if (
      item.equipmentId &&
      item.quantity > 0 &&
      (!furnitureIds || furnitureIds.has(item.equipmentId))
    ) {
      totals.set(
        item.equipmentId,
        (totals.get(item.equipmentId) ?? 0) + item.quantity
      )
    }
  }
  return [...totals.entries()]
    .sort(([left], [right]) => left.localeCompare(right))
    .map(([equipmentId, quantity]) => ({ equipmentId, quantity }))
}

function errorMessage(cause: unknown, fallback: string) {
  return cause instanceof Error ? cause.message : fallback
}

function isConflict(cause: unknown): cause is ApiError {
  return cause instanceof ApiError && cause.status === 409
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
        document.driverSnapshot,
        TRANSFER_STATE_LABELS[document.state],
        ...document.lines.flatMap((line) => [line.id, line.assetId]),
      ]
        .filter(Boolean)
        .some((value) => String(value).toLocaleLowerCase("ru").includes(needle))
    })
  }, [query.data, search, showAll])

  function invalidateTransfers() {
    void queryClient.invalidateQueries({
      queryKey: WAREHOUSE_TRANSFERS_QUERY_KEY,
    })
  }

  function applyTransferProjection(projection: TransferDocument) {
    if (selectedWarehouseId) {
      queryClient.setQueryData<TransferDocument[]>(
        [...WAREHOUSE_TRANSFERS_QUERY_KEY, selectedWarehouseId],
        (current) => {
          if (!current) return [projection]
          return current.some((document) => document.id === projection.id)
            ? current.map((document) =>
                document.id === projection.id ? projection : document
              )
            : [projection, ...current]
        }
      )
    }
    queryClient.setQueryData<TransferDocument>(
      [...WAREHOUSE_TRANSFERS_QUERY_KEY, "detail", projection.id],
      projection
    )
  }

  function canManageDocument(document: TransferDocument) {
    return (
      hasWarehouseAccess(currentUser, document.warehouseId, "MANAGE") &&
      hasWarehouseAccess(currentUser, document.destinationWarehouseId, "MANAGE")
    )
  }

  function keyFor(
    action: string,
    document: TransferDocument,
    line?: TransferLine
  ) {
    const identity = `${action}:${document.id}:${document.version}:${line?.id ?? "document"}:${line?.version ?? ""}`
    const existing = commandKeys.current.get(identity)
    if (existing) return existing
    const idempotencyKey = commandIdentity()
    commandKeys.current.set(identity, idempotencyKey)
    return idempotencyKey
  }

  const departMutation = useMutation({
    mutationFn: ({ document, line }: TransferLineTarget) =>
      departWarehouseTransferLine({
        accessToken: accessToken!,
        documentId: document.id,
        lineId: line.id,
        expectedVersion: document.version,
        expectedLineVersion: line.version,
        idempotencyKey: keyFor("depart", document, line),
      }),
    onSuccess: (result) => {
      applyTransferProjection(result)
      setCommandError(null)
      invalidateTransfers()
    },
    onError: (cause) => {
      setCommandError(errorMessage(cause, "Не удалось отправить бытовку"))
      if (isConflict(cause)) invalidateTransfers()
    },
  })

  const arriveMutation = useMutation({
    mutationFn: ({
      document,
      line,
      references,
      idempotencyKey,
    }: TransferLineTarget & {
      references: TransferMediaReference[]
      idempotencyKey: string
    }) =>
      arriveWarehouseTransferLine({
        accessToken: accessToken!,
        documentId: document.id,
        lineId: line.id,
        expectedVersion: document.version,
        expectedLineVersion: line.version,
        references,
        idempotencyKey,
      }),
    onSuccess: (result) => {
      applyTransferProjection(result)
      setArrivalTarget(null)
      setCommandError(null)
      invalidateTransfers()
    },
    onError: (cause) => {
      setCommandError(errorMessage(cause, "Не удалось принять бытовку"))
      if (isConflict(cause)) {
        setArrivalTarget(null)
        invalidateTransfers()
      }
    },
  })

  const cancelMutation = useMutation({
    mutationFn: (document: TransferDocument) =>
      cancelWarehouseTransfer({
        accessToken: accessToken!,
        documentId: document.id,
        expectedVersion: document.version,
        idempotencyKey: keyFor("cancel", document),
      }),
    onSuccess: (result) => {
      applyTransferProjection(result)
      setCancelTarget(null)
      setCommandError(null)
      invalidateTransfers()
    },
    onError: (cause) => {
      setCommandError(errorMessage(cause, "Не удалось отменить перемещение"))
      if (isConflict(cause)) {
        setCancelTarget(null)
        invalidateTransfers()
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
    }) =>
      reconcileWarehouseTransfer({
        accessToken: accessToken!,
        documentId: document.id,
        expectedVersion: document.version,
        idempotencyKey: keyFor("reconcile", document),
        reason,
      }),
    onSuccess: (result) => {
      applyTransferProjection(result)
      setReconcileTarget(null)
      setCommandError(null)
      invalidateTransfers()
    },
    onError: (cause) => {
      setCommandError(errorMessage(cause, "Не удалось выполнить сверку"))
      if (isConflict(cause)) {
        setReconcileTarget(null)
        invalidateTransfers()
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
          {expandedId === document.id ? "Скрыть состав" : "Показать состав"}
        </Button>
        {canManage && current.state === "DRAFT" ? (
          <Button
            size="sm"
            variant="outline"
            disabled={cancelMutation.isPending}
            onClick={() => setCancelTarget(current)}
          >
            Отменить документ
          </Button>
        ) : null}
        {canManage &&
        (current.state === "CONFLICT" ||
          current.state === "RECONCILIATION_REQUIRED") ? (
          <Button size="sm" onClick={() => setReconcileTarget(current)}>
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
            placeholder="ID документа, бытовки, водителя или склада"
            value={search}
            onChange={(event) => setSearch(event.target.value)}
          />
        </PageToolbarContent>
        <PageToolbarActions>
          <Button
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
          {canCreateTransfer ? (
            <Button onClick={() => setCreateOpen(true)}>
              <HugeiconsIcon icon={Add01Icon} data-icon="inline-start" />
              Создать перемещение
            </Button>
          ) : null}
        </PageToolbarActions>
      </PageToolbar>

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
                id: "scheduledDate",
                label: "Дата задания",
                className: "w-48",
                getSortValue: (document) => document.scheduledDate,
                render: (document) => formatDate(document.scheduledDate),
              },
              {
                id: "updatedAt",
                label: "Обновлено",
                className: "w-48",
                getSortValue: (document) => document.updatedAt,
                render: (document) => formatDateTime(document.updatedAt),
              },
              {
                id: "direction",
                label: "Маршрут",
                className: "min-w-72",
                getSortValue: (document) =>
                  `${document.warehouseId}:${document.destinationWarehouseId}`,
                render: (document) => (
                  <span>
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
                  </span>
                ),
              },
              {
                id: "driver",
                label: "Водитель",
                className: "min-w-44",
                getSortValue: (document) => document.driverSnapshot ?? "",
                render: (document) => document.driverSnapshot ?? "Не указан",
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
                id: "actions",
                label: "Действия",
                className: "min-w-72",
                getSortValue: (document) => document.id,
                render: actions,
              },
            ]}
          />
        </div>
        <div className="grid gap-3 md:hidden">
          {rows.map((document) => (
            <Card key={document.id} size="sm">
              <CardHeader>
                <CardTitle>
                  {warehouseLabel(
                    warehouses.find(
                      (warehouse) => warehouse.id === document.warehouseId
                    ),
                    document.warehouseId
                  )}
                </CardTitle>
                <CardDescription>
                  →{" "}
                  {warehouseLabel(
                    warehouses.find(
                      (warehouse) =>
                        warehouse.id === document.destinationWarehouseId
                    ),
                    document.destinationWarehouseId
                  )}
                  {` · ${formatDate(document.scheduledDate)}`}
                </CardDescription>
                <CardAction>
                  <Badge variant={statusVariant(document.state)}>
                    {TRANSFER_STATE_LABELS[document.state]}
                  </Badge>
                </CardAction>
              </CardHeader>
              <CardContent>
                <TransferLines
                  document={currentDocument(document)}
                  canManage={canManageDocument(currentDocument(document))}
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
                  onDepart={(line) =>
                    departMutation.mutate({
                      document: currentDocument(document),
                      line,
                    })
                  }
                  onArrive={(line) =>
                    setArrivalTarget({
                      document: currentDocument(document),
                      line,
                    })
                  }
                />
              </CardContent>
              <CardFooter className="flex-wrap gap-2">
                {actions(document)}
              </CardFooter>
            </Card>
          ))}
        </div>
      </div>

      {createOpen && selectedWarehouseId && accessToken ? (
        <CreateTransferDialog
          accessToken={accessToken}
          currentUser={currentUser}
          warehouseId={selectedWarehouseId}
          warehouses={warehouses}
          onOpenChange={setCreateOpen}
        />
      ) : null}
      {arrivalTarget ? (
        <ArrivalTransferDialog
          accessToken={accessToken}
          document={arrivalTarget.document}
          line={arrivalTarget.line}
          pending={arriveMutation.isPending}
          error={commandError}
          onOpenChange={(open) => !open && setArrivalTarget(null)}
          onSubmit={(references, idempotencyKey) =>
            arriveMutation.mutate({
              ...arrivalTarget,
              references,
              idempotencyKey,
            })
          }
        />
      ) : null}
      {reconcileTarget ? (
        <ReconcileTransferDialog
          document={reconcileTarget}
          pending={reconcileMutation.isPending}
          error={commandError}
          onOpenChange={(open) => !open && setReconcileTarget(null)}
          onSubmit={(reason) =>
            reconcileMutation.mutate({ document: reconcileTarget, reason })
          }
        />
      ) : null}
      <AlertDialog
        open={cancelTarget !== null}
        onOpenChange={(open) => !open && setCancelTarget(null)}
      >
        <AlertDialogContent>
          <AlertDialogHeader>
            <AlertDialogTitle>Отменить перемещение?</AlertDialogTitle>
            <AlertDialogDescription>
              Logistics-service отменит ещё не выполненные серверные задания и
              сохранит историю операции.
            </AlertDialogDescription>
          </AlertDialogHeader>
          {commandError ? <FieldError>{commandError}</FieldError> : null}
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
            <CardTitle>Бытовка {line.lineNumber}</CardTitle>
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
              Версия бытовки: {line.assetVersion} · версия строки:{" "}
              {line.version}
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
  onSubmit: (
    references: TransferMediaReference[],
    idempotencyKey: string
  ) => void
  onOpenChange: (open: boolean) => void
}) {
  const [references, setReferences] = useState<TransferMediaReference[]>([])
  const attempt = useRef<CommandAttempt | null>(null)
  const [validationError, setValidationError] = useState<string | null>(null)

  function submit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault()
    if (references.length === 0 || references.length > 20) {
      setValidationError(
        "Добавьте хотя бы одну готовую фотографию приёмки строки."
      )
      return
    }
    const signature = JSON.stringify(references)
    const idempotencyKey =
      attempt.current?.signature === signature
        ? attempt.current.idempotencyKey
        : commandIdentity()
    attempt.current = { signature, idempotencyKey }
    setValidationError(null)
    onSubmit(references, idempotencyKey)
  }

  return (
    <Dialog open onOpenChange={onOpenChange}>
      <DialogContent className="max-h-[calc(100svh-1rem)] overflow-y-auto sm:max-w-2xl">
        <form onSubmit={submit}>
          <DialogHeader>
            <DialogTitle>Принять бытовку перемещения</DialogTitle>
            <DialogDescription>
              Загрузите фотографии состояния бытовки {line.lineNumber} на складе
              назначения.
            </DialogDescription>
          </DialogHeader>
          <FieldSet className="py-4">
            <FieldLegend variant="label">Фотографии приёмки</FieldLegend>
            <FieldDescription>
              В команду попадут только готовые ссылки media-service.
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
              onReadyReferencesChange={setReferences}
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
              {pending ? "Принимается…" : "Принять"}
            </Button>
          </DialogFooter>
        </form>
      </DialogContent>
    </Dialog>
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
  const contentRef = useRef<HTMLDivElement>(null)
  const attempt = useRef<CommandAttempt | null>(null)
  const destinations = warehouses.filter(
    (warehouse) =>
      warehouse.active &&
      warehouse.id !== warehouseId &&
      hasWarehouseAccess(currentUser, warehouse.id, "EDIT")
  )
  const [destinationWarehouseId, setDestinationWarehouseId] = useState("")
  const [driver, setDriver] = useState<RepairTaskWorkerSnapshotDto | null>(null)
  const [scheduledDate, setScheduledDate] = useState("")
  const [lines, setLines] = useState<TransferLineDraft[]>(() => [emptyLine()])
  const [editingLineKey, setEditingLineKey] = useState<string | null>(null)
  const [validationError, setValidationError] = useState<string | null>(null)

  const cabinsQuery = useQuery({
    queryKey: ["rental-items", "transfer-candidates", warehouseId],
    queryFn: () =>
      listAssetRentalItems({
        accessToken,
        warehouseId,
        page: 0,
        size: 200,
      }),
  })
  const cabins = (cabinsQuery.data?.content ?? [])
    .filter((item) => item.status === "FREE")
    .sort((left, right) => left.number.localeCompare(right.number, "ru"))
  const cabinByAssetId = new Map(cabins.map((cabin) => [cabin.id, cabin]))
  const selectedAssetIds = lines
    .map((line) => line.assetId)
    .filter((assetId): assetId is string => assetId !== null)
  const equipmentQuery = useQuery({
    queryKey: ["equipment", "transfer", warehouseId],
    queryFn: () => getEquipmentItems(accessToken, { warehouseId }),
  })
  const furnitureIds = furnitureEquipmentIds(equipmentQuery.data)
  const mutation = useMutation({
    mutationFn: ({
      idempotencyKey,
      commandLines,
      furnitureReplacements,
      scheduledDate: commandScheduledDate,
    }: {
      idempotencyKey: string
      commandLines: Array<{ assetId: string; assetVersion: number }>
      furnitureReplacements: TransferFurnitureReplacement[]
      scheduledDate: string
    }) =>
      createWarehouseTransfer({
        accessToken,
        warehouseId,
        destinationWarehouseId,
        driverSnapshot: driver?.name ?? null,
        scheduledDate: commandScheduledDate,
        lines: commandLines,
        furnitureReplacements,
        idempotencyKey,
      }),
    onSuccess: (result) => {
      queryClient.setQueryData<TransferDocument[]>(
        [...WAREHOUSE_TRANSFERS_QUERY_KEY, warehouseId],
        (current) => [
          result,
          ...(current ?? []).filter((item) => item.id !== result.id),
        ]
      )
      void queryClient.invalidateQueries({
        queryKey: WAREHOUSE_TRANSFERS_QUERY_KEY,
      })
      onOpenChange(false)
    },
  })
  const editingLine = lines.find((line) => line.key === editingLineKey) ?? null
  const editingCabin = editingLine?.assetId
    ? (cabinByAssetId.get(editingLine.assetId) ?? null)
    : null

  function selectCabin(key: string, cabin: RentalItemDto | null) {
    setLines((current) =>
      current.map((line) =>
        line.key === key
          ? { ...line, assetId: cabin?.id ?? null, contents: null }
          : line
      )
    )
    if (editingLineKey === key) setEditingLineKey(null)
    attempt.current = null
    setValidationError(null)
  }

  function submit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault()
    const selectedCabins = lines.map((line) =>
      line.assetId ? (cabinByAssetId.get(line.assetId) ?? null) : null
    )
    if (
      !destinationWarehouseId ||
      selectedCabins.some((cabin) => cabin === null)
    ) {
      setValidationError("Выберите склад назначения и бытовку в каждой строке.")
      return
    }
    const cabinsForCommand = selectedCabins as RentalItemDto[]
    if (
      new Set(cabinsForCommand.map((cabin) => cabin.id)).size !==
      cabinsForCommand.length
    ) {
      setValidationError(
        "Одну бытовку можно добавить в перемещение только один раз."
      )
      return
    }

    if (
      !/^\d{4}-\d{2}-\d{2}$/.test(scheduledDate) ||
      scheduledDate < localCalendarDate()
    ) {
      setValidationError("Для перемещения укажите дату, начиная с сегодняшней.")
      return
    }

    const commandLines = cabinsForCommand.map((cabin) => ({
      assetId: cabin.id,
      assetVersion: cabin.version,
    }))
    const furnitureReplacements = lines
      .filter(
        (
          line
        ): line is TransferLineDraft & {
          assetId: string
          contents: CabinFurnitureRequirementInput[]
        } => line.assetId !== null && line.contents !== null
      )
      .map((line) => ({
        assetId: line.assetId,
        contents: [...line.contents].sort((left, right) =>
          left.equipmentId.localeCompare(right.equipmentId)
        ),
      }))
      .sort((left, right) => left.assetId.localeCompare(right.assetId))
    const signature = JSON.stringify({
      warehouseId,
      destinationWarehouseId,
      driverSnapshot: driver?.name ?? null,
      scheduledDate,
      lines: commandLines,
      furnitureReplacements,
    })
    const idempotencyKey =
      attempt.current?.signature === signature
        ? attempt.current.idempotencyKey
        : commandIdentity()
    attempt.current = { signature, idempotencyKey }
    setValidationError(null)
    mutation.mutate({
      idempotencyKey,
      commandLines,
      furnitureReplacements,
      scheduledDate,
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
            <DialogTitle>Создать складское перемещение</DialogTitle>
            <DialogDescription>
              Выберите склад назначения и бытовки. Наполнение каждой бытовки
              можно изменить отдельно — сервер создаст нужные задания.
            </DialogDescription>
          </DialogHeader>
          <FieldGroup className="py-4">
            <Field>
              <FieldLabel htmlFor="transfer-destination">
                Склад назначения
              </FieldLabel>
              <Select
                value={destinationWarehouseId}
                disabled={mutation.isPending}
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
            <LogisticsDriverPicker
              accessToken={accessToken}
              id="transfer-driver"
              required={false}
              warehouseId={warehouseId}
              value={driver}
              disabled={mutation.isPending}
              onChange={(next) => {
                setDriver(next)
                attempt.current = null
                setValidationError(null)
              }}
            />
            <Field data-invalid={Boolean(validationError) || undefined}>
              <FieldLabel htmlFor="transfer-scheduled-date">
                Дата задания
              </FieldLabel>
              <Input
                id="transfer-scheduled-date"
                type="date"
                required
                value={scheduledDate}
                onChange={(event) => {
                  setScheduledDate(event.target.value)
                  attempt.current = null
                  setValidationError(null)
                }}
              />
            </Field>
            <FieldSet disabled={mutation.isPending}>
              <FieldLegend variant="label">Бытовки и наполнение</FieldLegend>
              <FieldDescription>
                Выберите свободные бытовки со склада-отправителя. Для каждой
                показывается текущее наполнение и доступно его изменение.
              </FieldDescription>
              <FieldGroup data-testid="transfer-cabin-list">
                {lines.map((line, index) => {
                  const selectedCabin = line.assetId
                    ? (cabinByAssetId.get(line.assetId) ?? null)
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
                          <FieldLabel htmlFor={`transfer-cabin-${line.key}`}>
                            Номер бытовки
                          </FieldLabel>
                          <Combobox<RentalItemDto>
                            items={[
                              {
                                value: "available",
                                items: cabins.filter(
                                  (cabin) =>
                                    cabin.id === line.assetId ||
                                    !selectedAssetIds.includes(cabin.id)
                                ),
                              },
                            ]}
                            value={selectedCabin}
                            itemToStringLabel={(cabin) => cabin.number}
                            itemToStringValue={(cabin) => cabin.id}
                            isItemEqualToValue={(left, right) =>
                              left.id === right.id
                            }
                            onValueChange={(cabin) =>
                              selectCabin(line.key, cabin)
                            }
                          >
                            <ComboboxInput
                              id={`transfer-cabin-${line.key}`}
                              placeholder="Введите номер бытовки"
                              showClear
                            />
                            <ComboboxContent portalContainer={contentRef}>
                              <ComboboxEmpty>
                                {cabinsQuery.isFetching
                                  ? "Загружаем бытовки…"
                                  : "Свободные бытовки не найдены"}
                              </ComboboxEmpty>
                              <ComboboxList>
                                {(group) => (
                                  <ComboboxGroup
                                    key={group.value}
                                    items={group.items}
                                  >
                                    <ComboboxLabel>
                                      Свободные на складе-отправителе
                                    </ComboboxLabel>
                                    <ComboboxCollection>
                                      {(cabin) => (
                                        <ComboboxItem
                                          key={cabin.id}
                                          value={cabin}
                                        >
                                          {cabin.number}
                                        </ComboboxItem>
                                      )}
                                    </ComboboxCollection>
                                  </ComboboxGroup>
                                )}
                              </ComboboxList>
                            </ComboboxContent>
                          </Combobox>
                        </Field>
                        {selectedCabin ? (
                          <CabinFurnitureContents
                            cabin={selectedCabin}
                            disabled={
                              mutation.isPending ||
                              equipmentQuery.isFetching ||
                              equipmentQuery.isError
                            }
                            furnitureIds={furnitureIds}
                            onManage={() => {
                              setEditingLineKey(line.key)
                              setValidationError(null)
                            }}
                          />
                        ) : null}
                      </CardContent>
                    </Card>
                  )
                })}
                <Button
                  type="button"
                  variant="outline"
                  disabled={
                    lines.length >= 100 ||
                    cabins.length === 0 ||
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
            {equipmentQuery.isError ? (
              <FieldError>
                Не удалось загрузить дополнительное оборудование.
              </FieldError>
            ) : null}
            {editingLine && editingCabin ? (
              <CabinFurnitureCompositionDialog
                cabin={editingCabin}
                equipmentItems={equipmentQuery.data ?? []}
                initialContents={
                  editingLine.contents ??
                  cabinFurnitureRequirements(editingCabin, furnitureIds)
                }
                open
                pending={mutation.isPending}
                onOpenChange={(open) => !open && setEditingLineKey(null)}
                onSave={(contents) => {
                  setLines((current) =>
                    current.map((line) =>
                      line.key === editingLine.key
                        ? { ...line, contents }
                        : line
                    )
                  )
                  attempt.current = null
                  setEditingLineKey(null)
                }}
              />
            ) : null}
            {cabinsQuery.isError ? (
              <FieldError>Не удалось загрузить доступные бытовки.</FieldError>
            ) : null}
            {validationError ? (
              <FieldError>{validationError}</FieldError>
            ) : null}
            {mutation.error ? (
              <FieldError>
                {errorMessage(mutation.error, "Не удалось создать перемещение")}
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
              {mutation.isPending ? "Создаётся…" : "Создать перемещение"}
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
            if (reason.trim()) onSubmit(reason.trim())
          }}
        >
          <DialogHeader>
            <DialogTitle>Сверить перемещение</DialogTitle>
            <DialogDescription>
              Logistics-service повторно проверит незавершённые эффекты
              документа {document.id.slice(0, 8)}.
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
            {error ? <FieldError>{error}</FieldError> : null}
          </FieldGroup>
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
