import { useMemo, useRef, useState, type FormEvent } from "react"
import {
  useMutation,
  useQuery,
  useQueryClient,
  type QueryClient,
} from "@tanstack/react-query"
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
  Dialog,
  DialogContent,
  DialogDescription,
  DialogFooter,
  DialogHeader,
  DialogTitle,
} from "@/components/ui/dialog"
import {
  Field,
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
import { AcceptUndamagedDialog } from "@/features/logistics/returns/accept-undamaged-dialog"
import {
  RETURNS_QUERY_KEY,
  listReturns,
  registerReturn,
} from "@/features/logistics/returns/api"
import {
  RETURN_DOCUMENT_STATES,
  RETURN_STATE_LABELS,
  type ReturnDocument,
  type ReturnDocumentState,
  type ReturnLine,
} from "@/features/logistics/returns/model"
import { RequestEstimateDialog } from "@/features/logistics/returns/request-estimate-dialog"
import type { RepairTaskWorkerSnapshotDto } from "@/features/repair-tasks/model/repair-task"
import { useWarehouse } from "@/hooks/use-warehouse"
import { ApiError } from "@/lib/api-client"

const EMPTY_FILTERS: LogisticsDocumentFiltersState<ReturnDocumentState> = {
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

function toIsoDateTime(value: string) {
  const date = new Date(value)
  if (!value || !Number.isFinite(date.getTime())) {
    throw new Error("Укажите корректную дату вывоза.")
  }
  return date.toISOString()
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
  const [search, setSearch] = useState("")
  const [filters, setFilters] = useState(EMPTY_FILTERS)
  const [expandedId, setExpandedId] = useState<string | null>(null)
  const [pickupTarget, setPickupTarget] = useState<ReturnDocument | null>(null)
  const [acceptTarget, setAcceptTarget] = useState<ReturnDocument | null>(null)
  const [estimateTarget, setEstimateTarget] = useState<ReturnDocument | null>(
    null
  )
  const [commandError, setCommandError] = useState<string | null>(null)
  const commandKeys = useRef(new Map<string, string>())
  const selectedDocumentId = searchParams.get("receiptId")
  const selectedLineId = searchParams.get("returnItemId")

  const query = useQuery({
    queryKey: returnListQueryKey(selectedWarehouseId ?? "none"),
    queryFn: () => listReturns(accessToken!, selectedWarehouseId!),
    enabled: Boolean(accessToken && selectedWarehouseId),
    refetchInterval: 5_000,
  })

  const stateOptions = useMemo(
    () =>
      RETURN_DOCUMENT_STATES.map((state) => ({
        value: state,
        label: RETURN_STATE_LABELS[state],
      })),
    []
  )
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
      if (
        filters.states.length > 0 &&
        !filters.states.includes(document.state)
      ) {
        return false
      }
      if (!matchesDateRange(document.scheduledAt, filters)) return false
      if (!needle) return true
      return [
        document.id,
        document.partySnapshot,
        document.driverSnapshot,
        document.rentalOrderId,
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
  }, [filters, query.data, search, selectedDocumentId, selectedLineId])

  function refresh(warehouseId: string) {
    return queryClient.invalidateQueries({
      queryKey: returnListQueryKey(warehouseId),
    })
  }

  function keyFor(signature: string) {
    const existing = commandKeys.current.get(signature)
    if (existing) return existing
    const idempotencyKey = commandIdentity()
    commandKeys.current.set(signature, idempotencyKey)
    return idempotencyKey
  }

  const registerMutation = useMutation({
    mutationFn: ({
      document,
      driverSnapshot,
      scheduledAt,
    }: {
      document: ReturnDocument
      driverSnapshot: string
      scheduledAt: string
    }) => {
      const signature = `pickup:${document.id}:${document.version}:${driverSnapshot}:${scheduledAt}`
      return registerReturn({
        accessToken: accessToken!,
        documentId: document.id,
        expectedVersion: document.version,
        driverSnapshot,
        scheduledAt,
        idempotencyKey: keyFor(signature),
      })
    },
    onSuccess: (result) => {
      storeServiceProjection(queryClient, result)
      setPickupTarget(null)
      setCommandError(null)
      void refresh(result.warehouseId)
    },
    onError: (cause, variables) => {
      if (cause instanceof ApiError && cause.status === 409) {
        commandKeys.current.delete(
          `pickup:${variables.document.id}:${variables.document.version}:${variables.driverSnapshot}:${variables.scheduledAt}`
        )
        void refresh(variables.document.warehouseId)
      }
      setCommandError(errorMessage(cause, "Не удалось создать вывоз"))
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
      registerMutation.variables?.document.id === document.id
    const canEdit = hasWarehouseAccess(
      currentUser,
      document.warehouseId,
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
              current === document.id ? null : document.id
            )
          }
        >
          {expandedId === document.id ? "Скрыть состав" : "Показать состав"}
        </Button>
        {canEdit && document.state === "DRAFT" ? (
          <Button
            type="button"
            size="sm"
            disabled={processing || !accessToken}
            onClick={() => setPickupTarget(document)}
          >
            {processing ? "Создаём…" : "Создать вывоз"}
          </Button>
        ) : null}
        {canEdit && document.state === "INSPECTION_REQUIRED" ? (
          <>
            <Button
              type="button"
              size="sm"
              variant="outline"
              onClick={() => {
                setCommandError(null)
                setAcceptTarget(document)
              }}
            >
              Принять без сметы
            </Button>
            <Button
              type="button"
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
            placeholder="Заказ, бытовка, контрагент или водитель"
            value={search}
            onChange={(event) => setSearch(event.target.value)}
          />
        </PageToolbarContent>
        <PageToolbarActions>
          {selectedDocumentId || selectedLineId ? (
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
        dateLabel="Вывоз"
        onChange={setFilters}
      />

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
                id: "scheduledAt",
                label: "Дата вывоза",
                className: "w-48",
                getSortValue: (document) => document.scheduledAt ?? "",
                render: (document) =>
                  document.scheduledAt
                    ? formatDateTime(document.scheduledAt)
                    : "Не назначена",
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
                render: (document) => document.driverSnapshot ?? "Не назначен",
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
                className: "min-w-96",
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
                  {document.scheduledAt
                    ? formatDateTime(document.scheduledAt)
                    : "Дата вывоза не назначена"}
                  {` · ${document.driverSnapshot ?? "водитель не назначен"}`}
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
                  Измените поиск или фильтры. Возврат появляется автоматически
                  после отгрузки бытовки клиенту.
                </CardDescription>
              </CardHeader>
            </Card>
          ) : null}
        </div>
      </div>

      {pickupTarget && accessToken ? (
        <ReturnPickupDialog
          accessToken={accessToken}
          document={pickupTarget}
          pending={registerMutation.isPending}
          onOpenChange={(open) => !open && setPickupTarget(null)}
          onSubmit={(input) =>
            registerMutation.mutate({ document: pickupTarget, ...input })
          }
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

function ReturnPickupDialog({
  accessToken,
  document,
  pending,
  onOpenChange,
  onSubmit,
}: {
  accessToken: string
  document: ReturnDocument
  pending: boolean
  onOpenChange: (open: boolean) => void
  onSubmit: (input: { driverSnapshot: string; scheduledAt: string }) => void
}) {
  const [driver, setDriver] = useState<RepairTaskWorkerSnapshotDto | null>(null)
  const [scheduledAt, setScheduledAt] = useState("")
  const [error, setError] = useState<string | null>(null)

  function submit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault()
    setError(null)
    if (!driver) {
      setError("Выберите водителя.")
      return
    }
    try {
      onSubmit({
        driverSnapshot: driver.name.trim(),
        scheduledAt: toIsoDateTime(scheduledAt),
      })
    } catch (cause) {
      setError(errorMessage(cause, "Проверьте дату вывоза."))
    }
  }

  return (
    <Dialog open onOpenChange={onOpenChange}>
      <DialogContent>
        <form className="flex flex-col gap-4" onSubmit={submit}>
          <DialogHeader>
            <DialogTitle>Создать вывоз</DialogTitle>
            <DialogDescription>
              Назначьте водителя и дату. После запуска бытовки перейдут в
              состояние «После аренды», а возврат — к осмотру.
            </DialogDescription>
          </DialogHeader>
          <FieldGroup>
            <LogisticsDriverPicker
              accessToken={accessToken}
              id="return-driver"
              value={driver}
              warehouseId={document.warehouseId}
              onChange={setDriver}
            />
            <Field data-invalid={Boolean(error) || undefined}>
              <FieldLabel htmlFor="return-scheduled-at">
                Дата и время вывоза
              </FieldLabel>
              <Input
                id="return-scheduled-at"
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
              {pending ? "Создаём…" : "Создать вывоз"}
            </Button>
          </DialogFooter>
        </form>
      </DialogContent>
    </Dialog>
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
              Бытовка: <span className="font-mono text-xs">{line.assetId}</span>
            </span>
            <span>
              Заказ:{" "}
              {line.rentalOrderId ?? document.rentalOrderId ?? "не указан"}
            </span>
          </CardContent>
        </Card>
      ))}
    </div>
  )
}
