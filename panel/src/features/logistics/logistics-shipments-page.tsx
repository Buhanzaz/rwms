import { useMemo, useRef, useState } from "react"
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query"
import { Add01Icon, Delete02Icon } from "@hugeicons/core-free-icons"
import { HugeiconsIcon } from "@hugeicons/react"
import { useSearchParams } from "react-router-dom"

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
import { hasWarehouseAccess } from "@/features/auth/warehouse-access"
import { useAuth } from "@/features/auth/use-auth"
import {
  SHIPMENTS_QUERY_KEY,
  cancelShipment,
  confirmShipmentPreparation,
  createShipment,
  listShipments,
  replaceShipmentPlan,
} from "@/features/logistics/shipments/api"
import {
  SHIPMENT_STATE_LABELS,
  type ShipmentDocument,
  type ShipmentDocumentState,
  type ShipmentPlanLine,
} from "@/features/logistics/shipments/model"
import { useWarehouse } from "@/hooks/use-warehouse"

const TERMINAL_STATES = new Set<ShipmentDocumentState>(["SHIPPED", "CANCELLED"])
const CANCELLABLE_STATES = new Set<ShipmentDocumentState>([
  "DRAFT",
  "PREPARING",
  "AWAITING_CONFIRMATION",
])

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

function commandIdentity() {
  return crypto.randomUUID()
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

  const query = useQuery({
    queryKey: [...SHIPMENTS_QUERY_KEY, selectedWarehouseId],
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
      ].some((value) => value.toLocaleLowerCase("ru").includes(needle))
    })
  }, [query.data, search, selectedShipmentId, showAll])

  function keyFor(action: string, shipment: ShipmentDocument) {
    const identity = `${action}:${shipment.id}:${shipment.version}`
    const existing = commandKeys.current.get(identity)
    if (existing) return existing
    const created = commandIdentity()
    commandKeys.current.set(identity, created)
    return created
  }

  const confirmMutation = useMutation({
    mutationFn: (shipment: ShipmentDocument) =>
      confirmShipmentPreparation({
        accessToken: accessToken!,
        documentId: shipment.id,
        expectedVersion: shipment.version,
        idempotencyKey: keyFor("confirm", shipment),
      }),
    onSuccess: (_result, shipment) => {
      commandKeys.current.delete(`confirm:${shipment.id}:${shipment.version}`)
      setCommandError(null)
      void queryClient.invalidateQueries({ queryKey: SHIPMENTS_QUERY_KEY })
    },
    onError: (cause) =>
      setCommandError(errorMessage(cause, "Не удалось подтвердить подготовку")),
  })

  const cancelMutation = useMutation({
    mutationFn: (shipment: ShipmentDocument) =>
      cancelShipment({
        accessToken: accessToken!,
        documentId: shipment.id,
        expectedVersion: shipment.version,
        idempotencyKey: keyFor("cancel", shipment),
      }),
    onSuccess: (_result, shipment) => {
      commandKeys.current.delete(`cancel:${shipment.id}:${shipment.version}`)
      setCancelTarget(null)
      setCommandError(null)
      void queryClient.invalidateQueries({ queryKey: SHIPMENTS_QUERY_KEY })
    },
    onError: (cause) =>
      setCommandError(errorMessage(cause, "Не удалось отменить отгрузку")),
  })

  function clearSelection() {
    const next = new URLSearchParams(searchParams)
    next.delete("shipmentId")
    setSearchParams(next, { replace: true })
  }

  function actions(shipment: ShipmentDocument) {
    const confirming =
      confirmMutation.isPending && confirmMutation.variables?.id === shipment.id
    const canEditDocument = hasWarehouseAccess(
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
        {canEditDocument && shipment.state === "DRAFT" ? (
          <Button
            size="sm"
            variant="outline"
            disabled
            title="Проекция отгрузки не содержит immutable equipment allocations; создайте новый план"
          >
            Запустить старый черновик
          </Button>
        ) : null}
        {canEditDocument && shipment.state === "AWAITING_CONFIRMATION" ? (
          <Button
            size="sm"
            disabled={confirming || !accessToken}
            onClick={() => confirmMutation.mutate(shipment)}
          >
            {confirming ? "Подтверждается…" : "Подтвердить подготовку"}
          </Button>
        ) : null}
        {canEditDocument && CANCELLABLE_STATES.has(shipment.state) ? (
          <Button
            size="sm"
            variant="destructive"
            disabled={cancelMutation.isPending || !accessToken}
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
            placeholder="ID документа, asset, компания или водитель"
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

      <Card size="sm">
        <CardHeader>
          <CardTitle>Подготовкой управляет logistics-service</CardTitle>
          <CardDescription>
            Документ, версии, task-board задания, equipment holds и компенсация
            отмены находятся на сервере. Панель не изменяет бытовки, остатки и
            задачи напрямую.
          </CardDescription>
        </CardHeader>
        <CardContent className="text-sm text-muted-foreground">
          Справочники компаний, кандидатов, оборудования и бытовок-источников
          пока отсутствуют в публичном shipment contract. Новый план принимает
          явные asset ID/версии без изменений оборудования; отдельной команды
          finalizeShipment контракт не предоставляет.
        </CardContent>
      </Card>

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
                id: "document",
                label: "Документ",
                className: "min-w-64",
                getSortValue: (shipment) => shipment.id,
                render: (shipment) => (
                  <span className="font-mono text-xs">{shipment.id}</span>
                ),
              },
              {
                id: "party",
                label: "Компания",
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
                label: "Строк",
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
                <CardTitle>Отгрузка {shipment.id.slice(0, 8)}</CardTitle>
                <CardDescription>
                  {shipment.partySnapshot} · {shipment.driverSnapshot}
                </CardDescription>
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
                  Измените фильтр или создайте новый документ отгрузки.
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
              Logistics-service отменит свои task-board задания, equipment holds
              и leases. Панель не выполняет компенсацию самостоятельно.
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
            <CardTitle>Строка {line.lineNumber}</CardTitle>
            <CardDescription>
              Server-issued asset и line versions
            </CardDescription>
            <CardAction>
              <Badge variant="outline">v{line.version}</Badge>
            </CardAction>
          </CardHeader>
          <CardContent className="grid gap-1 text-sm">
            <span>
              Asset: <span className="font-mono text-xs">{line.assetId}</span>
            </span>
            <span>
              Версия asset: {line.assetVersion} · Line ID:{" "}
              <span className="font-mono text-xs">{line.id}</span>
            </span>
          </CardContent>
        </Card>
      ))}
    </div>
  )
}

type ShipmentLineDraft = {
  key: string
  assetId: string
  assetVersion: string
}

function emptyLine(): ShipmentLineDraft {
  return {
    key: commandIdentity(),
    assetId: "",
    assetVersion: "0",
  }
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
  const [partySnapshot, setPartySnapshot] = useState("")
  const [driverSnapshot, setDriverSnapshot] = useState("")
  const [lines, setLines] = useState<ShipmentLineDraft[]>(() => [emptyLine()])
  const [draft, setDraft] = useState<ShipmentDocument | null>(null)
  const [createKey] = useState(commandIdentity)
  const [planKey] = useState(commandIdentity)
  const [validationError, setValidationError] = useState<string | null>(null)

  function planLines(): ShipmentPlanLine[] {
    return lines.map((line) => ({
      assetId: line.assetId.trim(),
      assetVersion: Number(line.assetVersion),
      allocations: [],
    }))
  }

  const mutation = useMutation({
    mutationFn: async () => {
      const immutableLines = planLines()
      let created = draft
      if (!created) {
        created = await createShipment({
          accessToken,
          warehouseId,
          partySnapshot: partySnapshot.trim(),
          driverSnapshot: driverSnapshot.trim(),
          lines: immutableLines,
          idempotencyKey: createKey,
        })
        setDraft(created)
      }
      if (created.state !== "DRAFT") {
        throw new Error("Созданный документ уже вышел из состояния DRAFT.")
      }
      return replaceShipmentPlan({
        accessToken,
        documentId: created.id,
        expectedVersion: created.version,
        partySnapshot: partySnapshot.trim(),
        driverSnapshot: driverSnapshot.trim(),
        lines: immutableLines,
        idempotencyKey: planKey,
      })
    },
    onSuccess: () => {
      void queryClient.invalidateQueries({ queryKey: SHIPMENTS_QUERY_KEY })
      onOpenChange(false)
    },
    onError: () => {
      void queryClient.invalidateQueries({ queryKey: SHIPMENTS_QUERY_KEY })
    },
  })

  function submit(event: React.FormEvent<HTMLFormElement>) {
    event.preventDefault()
    const invalidLine = lines.find(
      (line) =>
        !line.assetId.trim() ||
        !Number.isSafeInteger(Number(line.assetVersion)) ||
        Number(line.assetVersion) < 0
    )
    if (!partySnapshot.trim() || !driverSnapshot.trim() || invalidLine) {
      setValidationError(
        "Укажите компанию, водителя и для каждой строки asset UUID с неотрицательной версией."
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
            <DialogTitle>Создать и запустить отгрузку</DialogTitle>
            <DialogDescription>
              Панель создаст immutable DRAFT и повторит тот же payload через
              команду plan. При сетевой ошибке обе команды повторяются со
              стабильными Idempotency-Key без browser rollback.
            </DialogDescription>
          </DialogHeader>
          <FieldGroup className="py-4">
            <Field>
              <FieldLabel htmlFor="shipment-party">Компания</FieldLabel>
              <Input
                id="shipment-party"
                required
                maxLength={512}
                disabled={draft !== null}
                value={partySnapshot}
                onChange={(event) => setPartySnapshot(event.target.value)}
              />
              <FieldDescription>
                Это immutable текстовый снимок; справочник компаний пока не
                опубликован.
              </FieldDescription>
            </Field>
            <Field>
              <FieldLabel htmlFor="shipment-driver">Водитель</FieldLabel>
              <Input
                id="shipment-driver"
                required
                maxLength={512}
                disabled={draft !== null}
                value={driverSnapshot}
                onChange={(event) => setDriverSnapshot(event.target.value)}
              />
            </Field>
            <FieldSet>
              <FieldLegend variant="label">Строки отгрузки</FieldLegend>
              <FieldDescription>
                Укажите server-issued asset ID и версию. Equipment allocations
                пусты, потому что публичного discovery contract ещё нет.
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
                            disabled={draft !== null}
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
                          <FieldLabel htmlFor={`shipment-asset-${line.key}`}>
                            Asset UUID
                          </FieldLabel>
                          <Input
                            id={`shipment-asset-${line.key}`}
                            required
                            disabled={draft !== null}
                            placeholder="00000000-0000-0000-0000-000000000000"
                            value={line.assetId}
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
                          <FieldLabel htmlFor={`shipment-version-${line.key}`}>
                            Текущая версия asset
                          </FieldLabel>
                          <Input
                            id={`shipment-version-${line.key}`}
                            type="number"
                            min={0}
                            step={1}
                            required
                            disabled={draft !== null}
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
                disabled={lines.length >= 100 || draft !== null}
                onClick={() => setLines((current) => [...current, emptyLine()])}
              >
                <HugeiconsIcon icon={Add01Icon} data-icon="inline-start" />
                Добавить строку
              </Button>
            </FieldSet>
            {draft ? (
              <FieldDescription>
                Черновик {draft.id} уже создан. Повтор отправит только тот же
                immutable plan с прежним Idempotency-Key.
              </FieldDescription>
            ) : null}
            {validationError ? (
              <FieldError>{validationError}</FieldError>
            ) : null}
            {mutation.error ? (
              <FieldError>
                {errorMessage(
                  mutation.error,
                  "Не удалось создать или запустить отгрузку"
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
              Закрыть
            </Button>
            <Button type="submit" disabled={mutation.isPending}>
              {mutation.isPending
                ? "Запускается…"
                : draft
                  ? "Повторить запуск подготовки"
                  : "Создать и запустить"}
            </Button>
          </DialogFooter>
        </form>
      </DialogContent>
    </Dialog>
  )
}
