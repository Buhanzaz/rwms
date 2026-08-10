import { useMemo, useRef, useState, type FormEvent } from "react"
import {
  useMutation,
  useQuery,
  useQueryClient,
  type QueryClient,
} from "@tanstack/react-query"
import { Link, useNavigate, useSearchParams } from "react-router-dom"

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
  LogisticsFiltersToggle,
  type LogisticsDocumentFiltersState,
} from "@/features/logistics/logistics-document-filters"
import { LogisticsDriverPicker } from "@/features/logistics/logistics-driver-picker"
import {
  logisticsAssetLabel,
  logisticsOrderLabel,
  useLogisticsReferenceLabels,
  type LogisticsReferenceLabels,
} from "@/features/logistics/use-logistics-reference-labels"
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
} from "@/features/logistics/returns/model"
import { StartReturnEstimatesDialog } from "@/features/logistics/returns/start-return-estimates-dialog"
import type { RepairTaskWorkerSnapshotDto } from "@/features/repair-tasks/model/repair-task"
import { useResponsiveFiltersOpen } from "@/hooks/use-responsive-filters-open"
import { useWarehouse } from "@/hooks/use-warehouse"
import { ApiError } from "@/lib/api-client"

type ReturnFilters = LogisticsDocumentFiltersState<ReturnDocumentState> & {
  counterparties: string[]
  drivers: string[]
}

const EMPTY_FILTERS: ReturnFilters = {
  states: [],
  schedule: "ALL",
  dateFrom: "",
  dateTo: "",
  counterparties: [],
  drivers: [],
}

function commandIdentity() {
  return crypto.randomUUID()
}

function formatDate(value: string) {
  return new Intl.DateTimeFormat("ru-RU", {
    dateStyle: "medium",
  }).format(new Date(`${value}T00:00:00`))
}

function matchesDateRange(
  value: string | null,
  filters: LogisticsDocumentFiltersState<string>
) {
  if (filters.schedule === "SCHEDULED" && value === null) return false
  if (filters.schedule === "UNSCHEDULED" && value !== null) return false
  if (!value) return !filters.dateFrom && !filters.dateTo
  if (filters.dateFrom && value < filters.dateFrom) {
    return false
  }
  if (filters.dateTo && value > filters.dateTo) {
    return false
  }
  return true
}

function textFilterOptions(values: Iterable<string | null | undefined>) {
  return [
    ...new Set(
      [...values].filter((value): value is string => Boolean(value?.trim()))
    ),
  ]
    .sort((left, right) => left.localeCompare(right, "ru"))
    .map((value) => ({ value, label: value }))
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

export function LogisticsReturnsPage() {
  const { selectedWarehouseId } = useWarehouse()
  const { accessToken, currentUser } = useAuth()
  const [searchParams] = useSearchParams()
  const navigate = useNavigate()
  const queryClient = useQueryClient()
  const [search, setSearch] = useState("")
  const [filters, setFilters] = useState(EMPTY_FILTERS)
  const { filtersOpen, setFiltersOpen } = useResponsiveFiltersOpen()
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
  const referenceLabels = useLogisticsReferenceLabels(
    accessToken,
    query.data ?? []
  )

  const stateOptions = useMemo(
    () =>
      RETURN_DOCUMENT_STATES.map((state) => ({
        value: state,
        label: RETURN_STATE_LABELS[state],
      })),
    []
  )
  const counterpartyOptions = useMemo(
    () =>
      textFilterOptions(
        (query.data ?? []).map((document) => document.partySnapshot)
      ),
    [query.data]
  )
  const driverOptions = useMemo(
    () =>
      textFilterOptions(
        (query.data ?? []).map((document) => document.driverSnapshot)
      ),
    [query.data]
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
      if (
        filters.counterparties.length > 0 &&
        !filters.counterparties.includes(document.partySnapshot ?? "")
      ) {
        return false
      }
      if (
        filters.drivers.length > 0 &&
        !filters.drivers.includes(document.driverSnapshot ?? "")
      ) {
        return false
      }
      if (!matchesDateRange(document.scheduledDate, filters)) return false
      if (!needle) return true
      return [
        document.id,
        document.partySnapshot,
        document.driverSnapshot,
        document.rentalOrderId,
        document.rentalOrderId
          ? referenceLabels.orderNumbers.get(document.rentalOrderId)
          : null,
        RETURN_STATE_LABELS[document.state],
        ...document.lines.flatMap((line) => [
          line.id,
          line.assetId,
          referenceLabels.assetNumbers.get(line.assetId),
          line.tenantSnapshot,
          line.rentalOrderId
            ? referenceLabels.orderNumbers.get(line.rentalOrderId)
            : null,
        ]),
      ]
        .filter(Boolean)
        .some((value) => String(value).toLocaleLowerCase("ru").includes(needle))
    })
  }, [
    filters,
    query.data,
    referenceLabels.assetNumbers,
    referenceLabels.orderNumbers,
    search,
    selectedDocumentId,
    selectedLineId,
  ])

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
      driverWorkerId,
      scheduledDate,
    }: {
      document: ReturnDocument
      driverSnapshot: string
      driverWorkerId: string
      scheduledDate: string
    }) => {
      const signature = `pickup:${document.id}:${document.version}:${driverWorkerId}:${driverSnapshot}:${scheduledDate}`
      return registerReturn({
        accessToken: accessToken!,
        documentId: document.id,
        expectedVersion: document.version,
        driverSnapshot,
        driverWorkerId,
        scheduledDate,
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
          `pickup:${variables.document.id}:${variables.document.version}:${variables.driverWorkerId}:${variables.driverSnapshot}:${variables.scheduledDate}`
        )
        void refresh(variables.document.warehouseId)
      }
      setCommandError(errorMessage(cause, "Не удалось создать вывоз"))
    },
  })

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
        <PageToolbarContent className="max-w-xl">
          <Input
            aria-label="Поиск возвратов"
            placeholder="Заказ, бытовка, контрагент или водитель"
            value={search}
            onChange={(event) => setSearch(event.target.value)}
          />
        </PageToolbarContent>
        <PageToolbarActions>
          <LogisticsFiltersToggle
            open={filtersOpen}
            controls="logistics-return-filters"
            onOpenChange={setFiltersOpen}
          />
        </PageToolbarActions>
      </PageToolbar>

      <div id="logistics-return-filters" hidden={!filtersOpen}>
        <LogisticsDocumentFilters
          filters={filters}
          stateOptions={stateOptions}
          dateLabel="Вывоз"
          extraFilters={[
            {
              label: "Контрагент",
              options: counterpartyOptions,
              selected: filters.counterparties,
              onApply: (counterparties) =>
                setFilters((current) => ({ ...current, counterparties })),
            },
            {
              label: "Водитель",
              options: driverOptions,
              selected: filters.drivers,
              onApply: (drivers) =>
                setFilters((current) => ({ ...current, drivers })),
            },
          ]}
          onChange={(nextFilters) =>
            setFilters((current) => ({ ...current, ...nextFilters }))
          }
          onReset={() => setFilters(EMPTY_FILTERS)}
        />
      </div>

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
                referenceLabels={referenceLabels}
                selectedLineId={selectedLineId}
              />
            )}
            columns={[
              {
                id: "scheduledDate",
                label: "Дата вывоза",
                className: "w-48",
                getSortValue: (document) => document.scheduledDate ?? "",
                render: (document) =>
                  document.scheduledDate
                    ? formatDate(document.scheduledDate)
                    : "Не назначена",
              },
              {
                id: "party",
                label: "Контрагент",
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
                  {document.scheduledDate
                    ? formatDate(document.scheduledDate)
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
                  referenceLabels={referenceLabels}
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
        <StartReturnEstimatesDialog
          accessToken={accessToken}
          document={estimateTarget}
          onOpenChange={(open) => !open && setEstimateTarget(null)}
          onSuccess={(result) => {
            storeServiceProjection(queryClient, result)
            void refresh(result.warehouseId)
            const returnEstimateSearch = new URLSearchParams({
              returnId: estimateTarget.id,
              returnLineCount: String(estimateTarget.lines.length),
            })
            setEstimateTarget(null)
            setCommandError(null)
            navigate(`/estimates?${returnEstimateSearch.toString()}`)
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
  onSubmit: (input: {
    driverSnapshot: string
    driverWorkerId: string
    scheduledDate: string
  }) => void
}) {
  const [driver, setDriver] = useState<RepairTaskWorkerSnapshotDto | null>(null)
  const [scheduledDate, setScheduledDate] = useState("")
  const [error, setError] = useState<string | null>(null)

  function submit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault()
    setError(null)
    if (!driver) {
      setError("Выберите водителя.")
      return
    }
    if (!scheduledDate) {
      setError("Укажите дату вывоза.")
      return
    }
    onSubmit({
      driverSnapshot: driver.name.trim(),
      driverWorkerId: driver.id,
      scheduledDate,
    })
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
              <FieldLabel htmlFor="return-scheduled-date">
                Дата вывоза
              </FieldLabel>
              <Input
                id="return-scheduled-date"
                type="date"
                value={scheduledDate}
                aria-invalid={Boolean(error) || undefined}
                onChange={(event) => setScheduledDate(event.target.value)}
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
  referenceLabels,
  selectedLineId,
}: {
  document: ReturnDocument
  referenceLabels: LogisticsReferenceLabels
  selectedLineId: string | null
}) {
  return (
    <div className="grid gap-2">
      {document.lines.map((line) => {
        const orderId = line.rentalOrderId ?? document.rentalOrderId
        return (
          <Card
            key={line.id}
            size="sm"
            data-selected={line.id === selectedLineId}
            className="data-[selected=true]:ring-2 data-[selected=true]:ring-ring"
          >
            <CardHeader>
              <CardTitle>
                Бытовка {logisticsAssetLabel(referenceLabels, line.assetId)}
              </CardTitle>
              <CardDescription>
                {orderId ? (
                  <Link
                    className="underline-offset-4 hover:underline"
                    to={`/orders/${orderId}`}
                  >
                    Заказ {logisticsOrderLabel(referenceLabels, orderId)}
                  </Link>
                ) : (
                  "Заказ не указан"
                )}
              </CardDescription>
              <CardAction>
                <Badge variant="outline">Строка {line.lineNumber}</Badge>
              </CardAction>
            </CardHeader>
            {line.tenantSnapshot ? (
              <CardContent className="text-sm text-muted-foreground">
                Арендатор: {line.tenantSnapshot}
              </CardContent>
            ) : null}
          </Card>
        )
      })}
    </div>
  )
}
