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
} from "@/features/logistics/warehouse-transfers/model/warehouse-transfer"
import { useWarehouse } from "@/hooks/use-warehouse"

const ACTIONABLE_STATES = new Set<TransferDocumentState>([
  "DRAFT",
  "DEPARTING",
  "IN_TRANSIT",
  "CONFLICT",
  "RECONCILIATION_REQUIRED",
])

function formatDateTime(value: string) {
  return new Intl.DateTimeFormat("ru-RU", {
    dateStyle: "medium",
    timeStyle: "short",
  }).format(new Date(value))
}

function errorMessage(cause: unknown, fallback: string) {
  return cause instanceof Error ? cause.message : fallback
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

export function WarehouseTransfersPage() {
  const { accessToken, currentUser } = useAuth()
  const { selectedWarehouseId, warehouses } = useWarehouse()
  const queryClient = useQueryClient()
  const [search, setSearch] = useState("")
  const [showAll, setShowAll] = useState(false)
  const [expandedId, setExpandedId] = useState<string | null>(null)
  const [createOpen, setCreateOpen] = useState(false)
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

  const departMutation = useMutation({
    mutationFn: ({
      document,
      line,
    }: {
      document: TransferDocument
      line: TransferLine
    }) =>
      departWarehouseTransferLine({
        accessToken: accessToken!,
        documentId: document.id,
        lineId: line.id,
        expectedVersion: document.version,
        expectedLineVersion: line.version,
        idempotencyKey: keyFor("depart", document, line),
      }),
    onSuccess: (_result, { document, line }) => {
      commandKeys.current.delete(commandKey("depart", document, line))
      setCommandError(null)
      invalidateTransfers()
    },
    onError: (cause) =>
      setCommandError(errorMessage(cause, "Не удалось отправить бытовку")),
  })

  const cancelMutation = useMutation({
    mutationFn: (document: TransferDocument) =>
      cancelWarehouseTransfer({
        accessToken: accessToken!,
        documentId: document.id,
        expectedVersion: document.version,
        idempotencyKey: keyFor("cancel", document),
      }),
    onSuccess: (_result, document) => {
      commandKeys.current.delete(commandKey("cancel", document))
      setCancelTarget(null)
      setCommandError(null)
      invalidateTransfers()
    },
    onError: (cause) =>
      setCommandError(errorMessage(cause, "Не удалось отменить перемещение")),
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
        idempotencyKey: keyFor("reconcile", document, undefined, reason),
        reason,
      }),
    onSuccess: (_result, { document, reason }) => {
      commandKeys.current.delete(
        commandKey("reconcile", document, undefined, reason)
      )
      setReconcileTarget(null)
      setCommandError(null)
      invalidateTransfers()
    },
    onError: (cause) =>
      setCommandError(errorMessage(cause, "Не удалось выполнить сверку")),
  })

  function currentDocument(document: TransferDocument) {
    return expandedId === document.id && detailQuery.data?.id === document.id
      ? detailQuery.data
      : document
  }

  function actions(document: TransferDocument) {
    const current = currentDocument(document)
    const canManageDocument =
      hasWarehouseAccess(currentUser, current.warehouseId, "MANAGE") &&
      hasWarehouseAccess(currentUser, current.destinationWarehouseId, "MANAGE")
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
        {canManageDocument && current.state === "DRAFT" ? (
          <Button
            size="sm"
            variant="outline"
            disabled={cancelMutation.isPending}
            onClick={() => setCancelTarget(current)}
          >
            Отменить документ
          </Button>
        ) : null}
        {canManageDocument &&
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
          server-issued asset UUID и версия. Приёмка скрыта: команда требует
          media refs, а публичная загрузка фотографий для transfer ещё не
          утверждена. Перенос наполнения между бытовками также недоступен до
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
      {commandError ? <FieldError>{commandError}</FieldError> : null}

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
                    canManage={
                      hasWarehouseAccess(
                        currentUser,
                        current.warehouseId,
                        "MANAGE"
                      ) &&
                      hasWarehouseAccess(
                        currentUser,
                        current.destinationWarehouseId,
                        "MANAGE"
                      )
                    }
                    pendingLineId={departMutation.variables?.line.id ?? null}
                    onDepart={(line) =>
                      departMutation.mutate({ document: current, line })
                    }
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
                  canManage={
                    hasWarehouseAccess(
                      currentUser,
                      document.warehouseId,
                      "MANAGE"
                    ) &&
                    hasWarehouseAccess(
                      currentUser,
                      document.destinationWarehouseId,
                      "MANAGE"
                    )
                  }
                  pendingLineId={departMutation.variables?.line.id ?? null}
                  onDepart={(line) => departMutation.mutate({ document, line })}
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
          onSubmit={(reason) =>
            reconcileMutation.mutate({ document: reconcileTarget, reason })
          }
          onOpenChange={(open) => !open && setReconcileTarget(null)}
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
              Logistics-service отменит весь документ. Отмена отдельной строки
              публичным контрактом не поддерживается.
            </AlertDialogDescription>
          </AlertDialogHeader>
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
  pendingLineId,
  onDepart,
}: {
  document: TransferDocument
  canManage: boolean
  pendingLineId: string | null
  onDepart: (line: TransferLine) => void
}) {
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
                className="w-fit"
                size="sm"
                disabled={pendingLineId !== null}
                onClick={() => onDepart(line)}
              >
                {pendingLineId === line.id ? "Отправляется…" : "Отправить"}
              </Button>
            ) : null}
            {line.state === "DEPARTED" ? (
              <span className="text-muted-foreground">
                Приёмка в панели появится после утверждения загрузки media refs
                для transfer.
              </span>
            ) : null}
          </CardContent>
        </Card>
      ))}
    </div>
  )
}

type TransferLineDraft = {
  key: string
  assetId: string
  assetVersion: string
}

function emptyLine(): TransferLineDraft {
  return { key: commandIdentity(), assetId: "", assetVersion: "0" }
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
  const [destinationWarehouseId, setDestinationWarehouseId] = useState("")
  const [lines, setLines] = useState<TransferLineDraft[]>(() => [emptyLine()])
  const [idempotencyKey] = useState(commandIdentity)
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
    const invalidLine = lines.find(
      (line) =>
        !line.assetId.trim() ||
        !Number.isSafeInteger(Number(line.assetVersion)) ||
        Number(line.assetVersion) < 0
    )
    if (!destinationWarehouseId || invalidLine) {
      setValidationError(
        "Выберите другой склад и укажите для каждой строки asset UUID с неотрицательной версией."
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
                {lines.map((line, index) => (
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
                                current.filter((item) => item.key !== line.key)
                              )
                            }
                          >
                            <HugeiconsIcon icon={Delete02Icon} />
                          </Button>
                        </CardAction>
                      ) : null}
                    </CardHeader>
                    <CardContent>
                      <FieldGroup>
                        <Field>
                          <FieldLabel htmlFor={`transfer-asset-${line.key}`}>
                            Asset UUID
                          </FieldLabel>
                          <Input
                            id={`transfer-asset-${line.key}`}
                            required
                            value={line.assetId}
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
                        </Field>
                        <Field>
                          <FieldLabel htmlFor={`transfer-version-${line.key}`}>
                            Текущая версия asset
                          </FieldLabel>
                          <Input
                            id={`transfer-version-${line.key}`}
                            type="number"
                            min={0}
                            step={1}
                            required
                            value={line.assetVersion}
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
                        </Field>
                      </FieldGroup>
                    </CardContent>
                  </Card>
                ))}
              </FieldGroup>
              <Button
                type="button"
                variant="outline"
                disabled={lines.length >= 100}
                onClick={() => setLines((current) => [...current, emptyLine()])}
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
  onSubmit,
  onOpenChange,
}: {
  document: TransferDocument
  pending: boolean
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
