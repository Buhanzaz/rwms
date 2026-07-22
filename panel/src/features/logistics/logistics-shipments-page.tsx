import { useMemo, useRef, useState, type FormEvent } from "react"
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query"
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
} from "@/components/ui/field"
import { Input } from "@/components/ui/input"
import { hasWarehouseAccess } from "@/features/auth/warehouse-access"
import { useAuth } from "@/features/auth/use-auth"
import {
  LogisticsDocumentFilters,
  type LogisticsDocumentFiltersState,
} from "@/features/logistics/logistics-document-filters"
import { LogisticsDriverPicker } from "@/features/logistics/logistics-driver-picker"
import {
  SHIPMENTS_QUERY_KEY,
  cancelShipment,
  confirmShipmentPreparation,
  listShipments,
  replaceShipmentPlan,
} from "@/features/logistics/shipments/api"
import {
  SHIPMENT_DOCUMENT_STATES,
  SHIPMENT_STATE_LABELS,
  type ShipmentDocument,
  type ShipmentDocumentState,
} from "@/features/logistics/shipments/model"
import type { RepairTaskWorkerSnapshotDto } from "@/features/repair-tasks/model/repair-task"
import { useWarehouse } from "@/hooks/use-warehouse"
import { ApiError } from "@/lib/api-client"

const CANCELLABLE_STATES = new Set<ShipmentDocumentState>([
  "DRAFT",
  "PREPARING",
  "AWAITING_CONFIRMATION",
])

const EMPTY_FILTERS: LogisticsDocumentFiltersState<ShipmentDocumentState> = {
  states: [],
  schedule: "ALL",
  dateFrom: "",
  dateTo: "",
}

function commandIdentity() {
  return crypto.randomUUID()
}

function formatDateTime(value: string) {
  return new Intl.DateTimeFormat("ru-RU", {
    dateStyle: "medium",
    timeStyle: "short",
  }).format(new Date(value))
}

function toLocalDateTimeInput(value: string | null) {
  if (!value) return ""
  const date = new Date(value)
  date.setMinutes(date.getMinutes() - date.getTimezoneOffset())
  return date.toISOString().slice(0, 16)
}

function toIsoDateTime(value: string) {
  const date = new Date(value)
  if (!value || !Number.isFinite(date.getTime())) {
    throw new Error("Укажите корректную дату отгрузки.")
  }
  return date.toISOString()
}

function isFutureDate(value: string) {
  return new Date(value).getTime() > Date.now()
}

function matchesDateRange(
  value: string | null,
  filters: LogisticsDocumentFiltersState<string>
) {
  if (filters.schedule === "SCHEDULED" && value === null) return false
  if (filters.schedule === "UNSCHEDULED" && value !== null) return false
  if (!value) return !filters.dateFrom && !filters.dateTo
  const timestamp = new Date(value).getTime()
  if (
    filters.dateFrom &&
    timestamp < new Date(`${filters.dateFrom}T00:00:00.000`).getTime()
  ) {
    return false
  }
  if (
    filters.dateTo &&
    timestamp > new Date(`${filters.dateTo}T23:59:59.999`).getTime()
  ) {
    return false
  }
  return true
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

export function LogisticsShipmentsPage() {
  const { selectedWarehouseId } = useWarehouse()
  const { accessToken, currentUser } = useAuth()
  const [searchParams, setSearchParams] = useSearchParams()
  const queryClient = useQueryClient()
  const [search, setSearch] = useState("")
  const [filters, setFilters] = useState(EMPTY_FILTERS)
  const [expandedId, setExpandedId] = useState<string | null>(null)
  const [scheduleTarget, setScheduleTarget] = useState<{
    document: ShipmentDocument
    futureDateWarning: boolean
  } | null>(null)
  const [cancelTarget, setCancelTarget] = useState<ShipmentDocument | null>(
    null
  )
  const [commandError, setCommandError] = useState<string | null>(null)
  const commandKeys = useRef(new Map<string, string>())
  const selectedShipmentId = searchParams.get("shipmentId")
  const queryKey = [...SHIPMENTS_QUERY_KEY, selectedWarehouseId] as const

  const query = useQuery({
    queryKey,
    queryFn: () => listShipments(accessToken!, selectedWarehouseId!),
    enabled: Boolean(accessToken && selectedWarehouseId),
    refetchInterval: 5_000,
  })

  const stateOptions = useMemo(
    () =>
      SHIPMENT_DOCUMENT_STATES.map((state) => ({
        value: state,
        label: SHIPMENT_STATE_LABELS[state],
      })),
    []
  )
  const rows = useMemo(() => {
    const needle = search.trim().toLocaleLowerCase("ru")
    return (query.data ?? []).filter((shipment) => {
      if (selectedShipmentId && shipment.id !== selectedShipmentId) return false
      if (
        filters.states.length > 0 &&
        !filters.states.includes(shipment.state)
      ) {
        return false
      }
      if (!matchesDateRange(shipment.scheduledAt, filters)) return false
      if (!needle) return true
      return [
        shipment.id,
        shipment.partySnapshot,
        shipment.driverSnapshot,
        shipment.rentalOrderId,
        SHIPMENT_STATE_LABELS[shipment.state],
        ...shipment.lines.flatMap((line) => [line.id, line.assetId]),
      ]
        .filter(Boolean)
        .some((value) => String(value).toLocaleLowerCase("ru").includes(needle))
    })
  }, [filters, query.data, search, selectedShipmentId])

  function keyFor(signature: string) {
    const existing = commandKeys.current.get(signature)
    if (existing) return existing
    const idempotencyKey = commandIdentity()
    commandKeys.current.set(signature, idempotencyKey)
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

  const scheduleMutation = useMutation({
    mutationFn: ({
      document,
      driverSnapshot,
      scheduledAt,
    }: {
      document: ShipmentDocument
      driverSnapshot: string
      scheduledAt: string
    }) => {
      const signature = `schedule:${document.id}:${document.version}:${driverSnapshot}:${scheduledAt}`
      return replaceShipmentPlan({
        accessToken: accessToken!,
        documentId: document.id,
        expectedVersion: document.version,
        driverSnapshot,
        scheduledAt,
        idempotencyKey: keyFor(signature),
      })
    },
    onSuccess: (result) => {
      applyServerProjection(result)
      setScheduleTarget(null)
      setCommandError(null)
      void queryClient.invalidateQueries({ queryKey: SHIPMENTS_QUERY_KEY })
    },
    onError: (cause, variables) => {
      if (cause instanceof ApiError && cause.status === 409) {
        commandKeys.current.delete(
          `schedule:${variables.document.id}:${variables.document.version}:${variables.driverSnapshot}:${variables.scheduledAt}`
        )
        void queryClient.invalidateQueries({ queryKey: SHIPMENTS_QUERY_KEY })
      }
      setCommandError(errorMessage(cause, "Не удалось назначить отгрузку"))
    },
  })

  const confirmMutation = useMutation({
    mutationFn: (shipment: ShipmentDocument) => {
      const signature = `confirm:${shipment.id}:${shipment.version}`
      return confirmShipmentPreparation({
        accessToken: accessToken!,
        documentId: shipment.id,
        expectedVersion: shipment.version,
        idempotencyKey: keyFor(signature),
      })
    },
    onSuccess: (result) => {
      applyServerProjection(result)
      setCommandError(null)
      void queryClient.invalidateQueries({ queryKey: SHIPMENTS_QUERY_KEY })
    },
    onError: (cause, shipment) => {
      if (cause instanceof ApiError && cause.status === 409) {
        commandKeys.current.delete(`confirm:${shipment.id}:${shipment.version}`)
        void queryClient.invalidateQueries({ queryKey: SHIPMENTS_QUERY_KEY })
      }
      setCommandError(errorMessage(cause, "Не удалось отметить отгрузку"))
    },
  })

  const cancelMutation = useMutation({
    mutationFn: (shipment: ShipmentDocument) => {
      const signature = `cancel:${shipment.id}:${shipment.version}`
      return cancelShipment({
        accessToken: accessToken!,
        documentId: shipment.id,
        expectedVersion: shipment.version,
        idempotencyKey: keyFor(signature),
      })
    },
    onSuccess: (result) => {
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

  function requestConfirmation(shipment: ShipmentDocument) {
    const futureDateWarning =
      shipment.scheduledAt !== null && isFutureDate(shipment.scheduledAt)
    if (
      !shipment.scheduledAt ||
      !shipment.driverSnapshot ||
      futureDateWarning
    ) {
      setScheduleTarget({
        document: shipment,
        futureDateWarning,
      })
      return
    }
    confirmMutation.mutate(shipment)
  }

  function actions(shipment: ShipmentDocument) {
    const confirming =
      confirmMutation.isPending && confirmMutation.variables?.id === shipment.id
    const cancelling =
      cancelMutation.isPending && cancelMutation.variables?.id === shipment.id
    const scheduling =
      scheduleMutation.isPending &&
      scheduleMutation.variables?.document.id === shipment.id
    const canEdit = hasWarehouseAccess(
      currentUser,
      shipment.warehouseId,
      "EDIT"
    )
    return (
      <div className="flex flex-wrap gap-2">
        <Button
          type="button"
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
        {canEdit && shipment.state === "DRAFT" ? (
          <Button
            type="button"
            size="sm"
            disabled={scheduling || !accessToken}
            onClick={() =>
              setScheduleTarget({
                document: shipment,
                futureDateWarning: false,
              })
            }
          >
            Отгрузить
          </Button>
        ) : null}
        {canEdit && shipment.state === "AWAITING_CONFIRMATION" ? (
          <>
            <Button
              type="button"
              size="sm"
              variant="outline"
              disabled={scheduling || confirming}
              onClick={() =>
                setScheduleTarget({
                  document: shipment,
                  futureDateWarning: false,
                })
              }
            >
              Изменить дату
            </Button>
            <Button
              type="button"
              size="sm"
              disabled={confirming || scheduling || !accessToken}
              onClick={() => requestConfirmation(shipment)}
            >
              {confirming ? "Отмечаем…" : "Отгружена"}
            </Button>
          </>
        ) : null}
        {canEdit && CANCELLABLE_STATES.has(shipment.state) ? (
          <Button
            type="button"
            size="sm"
            variant="destructive"
            disabled={confirming || cancelling || scheduling || !accessToken}
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
            placeholder="Заказ, бытовка, контрагент или водитель"
            value={search}
            onChange={(event) => setSearch(event.target.value)}
          />
        </PageToolbarContent>
        <PageToolbarActions>
          {selectedShipmentId ? (
            <Button type="button" variant="outline" onClick={clearSelection}>
              Показать все документы
            </Button>
          ) : null}
          <Button
            type="button"
            variant="outline"
            disabled={!accessToken || !selectedWarehouseId || query.isFetching}
            onClick={() => void query.refetch()}
          >
            {query.isFetching ? "Обновляется…" : "Обновить"}
          </Button>
        </PageToolbarActions>
      </PageToolbar>

      <LogisticsDocumentFilters
        filters={filters}
        stateOptions={stateOptions}
        dateLabel="Отгрузка"
        onChange={setFilters}
      />

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
                id: "scheduledAt",
                label: "Дата отгрузки",
                className: "w-48",
                getSortValue: (shipment) => shipment.scheduledAt ?? "",
                render: (shipment) =>
                  shipment.scheduledAt
                    ? formatDateTime(shipment.scheduledAt)
                    : "Не назначена",
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
                getSortValue: (shipment) => shipment.driverSnapshot ?? "",
                render: (shipment) => shipment.driverSnapshot ?? "Не назначен",
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
                className: "min-w-96",
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
                <CardDescription>
                  {shipment.scheduledAt
                    ? formatDateTime(shipment.scheduledAt)
                    : "Дата отгрузки не назначена"}
                  {` · ${shipment.driverSnapshot ?? "водитель не назначен"}`}
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
                  Измените поиск или фильтры. Новые отгрузки появляются после
                  сохранения заказа.
                </CardDescription>
              </CardHeader>
            </Card>
          ) : null}
        </div>
      </div>

      {scheduleTarget && accessToken ? (
        <ShipmentScheduleDialog
          accessToken={accessToken}
          document={scheduleTarget.document}
          futureDateWarning={scheduleTarget.futureDateWarning}
          pending={scheduleMutation.isPending}
          onOpenChange={(open) => !open && setScheduleTarget(null)}
          onSubmit={(input) =>
            scheduleMutation.mutate({
              document: scheduleTarget.document,
              ...input,
            })
          }
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
              Сервис отменит задания, резервы мебели и активные leases этой
              отгрузки.
            </AlertDialogDescription>
          </AlertDialogHeader>
          <AlertDialogFooter>
            <AlertDialogCancel disabled={cancelMutation.isPending}>
              Не отменять
            </AlertDialogCancel>
            <AlertDialogAction
              disabled={cancelMutation.isPending || !cancelTarget}
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

function ShipmentScheduleDialog({
  accessToken,
  document,
  futureDateWarning,
  pending,
  onOpenChange,
  onSubmit,
}: {
  accessToken: string
  document: ShipmentDocument
  futureDateWarning: boolean
  pending: boolean
  onOpenChange: (open: boolean) => void
  onSubmit: (input: { driverSnapshot: string; scheduledAt: string }) => void
}) {
  const [driver, setDriver] = useState<RepairTaskWorkerSnapshotDto | null>(null)
  const [scheduledAt, setScheduledAt] = useState(() =>
    toLocalDateTimeInput(document.scheduledAt)
  )
  const [error, setError] = useState<string | null>(null)
  const existingDriver = document.driverSnapshot?.trim() || null

  function submit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault()
    setError(null)
    const driverSnapshot = driver?.name.trim() || existingDriver
    if (!driverSnapshot) {
      setError("Выберите водителя.")
      return
    }
    try {
      onSubmit({ driverSnapshot, scheduledAt: toIsoDateTime(scheduledAt) })
    } catch (cause) {
      setError(errorMessage(cause, "Проверьте дату отгрузки."))
    }
  }

  return (
    <Dialog open onOpenChange={onOpenChange}>
      <DialogContent>
        <form className="flex flex-col gap-4" onSubmit={submit}>
          <DialogHeader>
            <DialogTitle>
              {document.scheduledAt ? "Изменить дату отгрузки" : "Отгрузить"}
            </DialogTitle>
            <DialogDescription>
              {futureDateWarning
                ? "Дата отгрузки ещё не наступила. Измените её на текущую или прошедшую, затем повторите отметку «Отгружена»."
                : "Назначьте водителя и дату. После назначения сервис создаст задания на подготовку бытовок."}
            </DialogDescription>
          </DialogHeader>
          <FieldGroup>
            {existingDriver ? (
              <Field>
                <FieldDescription>
                  Текущий водитель: {existingDriver}. Выберите другого только
                  при необходимости.
                </FieldDescription>
              </Field>
            ) : null}
            <LogisticsDriverPicker
              accessToken={accessToken}
              id="shipment-driver"
              required={!existingDriver}
              value={driver}
              warehouseId={document.warehouseId}
              onChange={setDriver}
            />
            <Field data-invalid={Boolean(error) || undefined}>
              <FieldLabel htmlFor="shipment-scheduled-at">
                Дата и время отгрузки
              </FieldLabel>
              <Input
                id="shipment-scheduled-at"
                type="datetime-local"
                value={scheduledAt}
                aria-invalid={Boolean(error) || undefined}
                onChange={(event) => setScheduledAt(event.target.value)}
              />
              {error ? <FieldError>{error}</FieldError> : null}
            </Field>
          </FieldGroup>
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
              {pending ? "Сохраняем…" : "Сохранить дату"}
            </Button>
          </DialogFooter>
        </form>
      </DialogContent>
    </Dialog>
  )
}

function ShipmentLines({ shipment }: { shipment: ShipmentDocument }) {
  return (
    <div className="grid gap-2">
      {shipment.lines.map((line) => (
        <Card key={line.id} size="sm">
          <CardHeader>
            <CardTitle>Бытовка {line.assetId}</CardTitle>
            <CardDescription>Строка {line.lineNumber}</CardDescription>
            <CardAction>
              <Badge variant="outline">v{line.version}</Badge>
            </CardAction>
          </CardHeader>
          <CardContent className="text-sm text-muted-foreground">
            Заказ: {line.rentalOrderId ?? shipment.rentalOrderId ?? "не указан"}
          </CardContent>
        </Card>
      ))}
    </div>
  )
}
