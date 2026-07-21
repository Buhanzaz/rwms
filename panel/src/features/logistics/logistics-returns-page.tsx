import { type MutableRefObject, useMemo, useRef, useState } from "react"
import {
  type QueryClient,
  useMutation,
  useQuery,
  useQueryClient,
} from "@tanstack/react-query"
import { HugeiconsIcon } from "@hugeicons/react"
import { Add01Icon, Delete02Icon } from "@hugeicons/core-free-icons"
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
import { useWarehouse } from "@/hooks/use-warehouse"

const ACTIONABLE_STATES = new Set<ReturnDocumentState>([
  "DRAFT",
  "INSPECTION_REQUIRED",
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

function commandIdentity() {
  return crypto.randomUUID()
}

type CommandAttempt = {
  signature: string
  idempotencyKey: string
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

function isUuid(value: string) {
  return UUID_PATTERN.test(value)
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

function refreshServiceProjection(
  queryClient: QueryClient,
  warehouseId: string
) {
  return queryClient.invalidateQueries({
    queryKey: returnListQueryKey(warehouseId),
  })
}

function isConflict(cause: unknown) {
  return (
    typeof cause === "object" &&
    cause !== null &&
    "status" in cause &&
    cause.status === 409
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

  function keyFor(action: string, document: ReturnDocument) {
    const identity = `${action}:${document.id}:${document.version}`
    const existing = commandKeys.current.get(identity)
    if (existing) return existing
    const created = commandIdentity()
    commandKeys.current.set(identity, created)
    return created
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
      void refreshServiceProjection(queryClient, result.warehouseId)
    },
    onError: (cause, document) => {
      if (isConflict(cause)) {
        void refreshServiceProjection(queryClient, document.warehouseId)
      }
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
    const processingThisDocument =
      registerMutation.isPending &&
      registerMutation.variables?.id === document.id
    const canEditDocument = hasWarehouseAccess(
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
        {canEditDocument && document.state === "DRAFT" ? (
          <Button
            size="sm"
            disabled={processingThisDocument || !accessToken}
            onClick={() => registerMutation.mutate(document)}
          >
            {processingThisDocument ? "Регистрируется…" : "Зарегистрировать"}
          </Button>
        ) : null}
        {canEditDocument && document.state === "INSPECTION_REQUIRED" ? (
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
              Запросить смету
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
          {canEditSelectedWarehouse ? (
            <Button onClick={() => setCreateOpen(true)}>
              <HugeiconsIcon icon={Add01Icon} data-icon="inline-start" />
              Создать возврат
            </Button>
          ) : null}
        </PageToolbarActions>
      </PageToolbar>

      <Card size="sm">
        <CardHeader>
          <CardTitle>Возвраты обслуживает logistics-service</CardTitle>
          <CardDescription>
            Документы, версии и переходы читаются через gateway. Справочники
            компаний и кандидатов, редактирование состава, импорт арендованной
            бытовки и решения по мебели скрыты до появления подтверждённых
            контрактов.
          </CardDescription>
        </CardHeader>
        <CardContent className="text-sm text-muted-foreground">
          Фотографии осмотра загружаются в media-service для каждой
          server-issued строки и передаются в команду только после статуса
          READY. Запрос сметы доступен по line ID и подтверждённым equipment ID.
        </CardContent>
      </Card>

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
                id: "document",
                label: "Документ",
                className: "min-w-64",
                getSortValue: (document) => document.id,
                render: (document) => (
                  <span className="font-mono text-xs">{document.id}</span>
                ),
              },
              {
                id: "party",
                label: "Контрагент",
                className: "min-w-56",
                getSortValue: (document) =>
                  document.lines.map(lineSummary).join(" "),
                render: (document) => (
                  <div className="flex flex-col gap-1">
                    {Array.from(
                      new Set(
                        document.lines.map((line) =>
                          line.tenantSnapshot?.trim()
                            ? line.tenantSnapshot
                            : "Не указан"
                        )
                      )
                    ).map((party) => (
                      <span key={party}>{party}</span>
                    ))}
                  </div>
                ),
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
                <CardTitle>Возврат {document.id.slice(0, 8)}</CardTitle>
                <CardDescription>
                  {formatDateTime(document.createdAt)}
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
                  Измените фильтр или создайте новый документ возврата.
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
            void refreshServiceProjection(queryClient, result.warehouseId)
            setEstimateTarget(null)
            setCommandError(null)
          }}
          onConflict={(cause) => {
            void refreshServiceProjection(
              queryClient,
              estimateTarget.warehouseId
            )
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
            void refreshServiceProjection(queryClient, result.warehouseId)
            setAcceptTarget(null)
            setCommandError(null)
          }}
          onConflict={(cause) => {
            void refreshServiceProjection(queryClient, acceptTarget.warehouseId)
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
            <CardTitle>Строка {line.lineNumber}</CardTitle>
            <CardDescription>
              {line.tenantSnapshot ?? "Контрагент не указан"}
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

type ReturnLineDraft = {
  key: string
  assetId: string
  assetVersion: string
  tenantSnapshot: string
}

function emptyLine(): ReturnLineDraft {
  return {
    key: commandIdentity(),
    assetId: "",
    assetVersion: "0",
    tenantSnapshot: "",
  }
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
  const [lines, setLines] = useState<ReturnLineDraft[]>(() => [emptyLine()])
  const commandAttempt = useRef<CommandAttempt | null>(null)
  const [validationError, setValidationError] = useState<string | null>(null)
  const mutation = useMutation({
    mutationFn: (command: {
      lines: CreateReturnLine[]
      idempotencyKey: string
    }) =>
      createReturn({
        accessToken,
        warehouseId,
        ...command,
      }),
    onSuccess: (result) => {
      storeServiceProjection(queryClient, result)
      void refreshServiceProjection(queryClient, result.warehouseId)
      onOpenChange(false)
    },
  })

  function submit(event: React.FormEvent<HTMLFormElement>) {
    event.preventDefault()
    const commandLines = lines.map((line) => ({
      assetId: line.assetId.trim(),
      assetVersion: Number(line.assetVersion),
      tenantSnapshot: line.tenantSnapshot.trim(),
    }))
    const invalidLine = lines.find(
      (line) =>
        !isUuid(line.assetId.trim()) ||
        !line.tenantSnapshot.trim() ||
        !Number.isSafeInteger(Number(line.assetVersion)) ||
        Number(line.assetVersion) < 0
    )
    if (invalidLine) {
      setValidationError(
        "Для каждой строки укажите asset UUID, неотрицательную версию и снимок контрагента."
      )
      return
    }
    const assetIds = commandLines.map((line) => line.assetId.toLowerCase())
    if (new Set(assetIds).size !== assetIds.length) {
      setValidationError(
        "Каждый asset UUID можно добавить в документ возврата только один раз."
      )
      return
    }
    setValidationError(null)
    const signature = JSON.stringify({ warehouseId, lines: commandLines })
    mutation.mutate({
      lines: commandLines,
      idempotencyKey: stableCommandKey(commandAttempt, signature),
    })
  }

  return (
    <Dialog open onOpenChange={onOpenChange}>
      <DialogContent className="max-h-[calc(100svh-1rem)] overflow-y-auto sm:max-w-3xl">
        <form onSubmit={submit}>
          <DialogHeader>
            <DialogTitle>Создать документ возврата</DialogTitle>
            <DialogDescription>
              Контракт logistics-service принимает только server-issued asset
              ID, его текущую версию и снимок контрагента. Поиск компании и
              бытовки появится после отдельного публичного контракта кандидатов.
            </DialogDescription>
          </DialogHeader>
          <FieldGroup className="py-4">
            <FieldSet>
              <FieldLegend variant="label">Строки возврата</FieldLegend>
              <FieldDescription>
                Повторная отправка этого диалога использует тот же
                Idempotency-Key.
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
                        <Field>
                          <FieldLabel htmlFor={`return-asset-${line.key}`}>
                            Asset UUID
                          </FieldLabel>
                          <Input
                            id={`return-asset-${line.key}`}
                            value={line.assetId}
                            required
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
                          <FieldLabel htmlFor={`return-version-${line.key}`}>
                            Текущая версия asset
                          </FieldLabel>
                          <Input
                            id={`return-version-${line.key}`}
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
                        <Field>
                          <FieldLabel htmlFor={`return-tenant-${line.key}`}>
                            Снимок контрагента
                          </FieldLabel>
                          <Input
                            id={`return-tenant-${line.key}`}
                            maxLength={512}
                            required
                            value={line.tenantSnapshot}
                            onChange={(event) =>
                              setLines((current) =>
                                current.map((item) =>
                                  item.key === line.key
                                    ? {
                                        ...item,
                                        tenantSnapshot: event.target.value,
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
                {errorMessage(mutation.error, "Не удалось создать возврат")}
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
              {mutation.isPending ? "Создаётся…" : "Создать черновик"}
            </Button>
          </DialogFooter>
        </form>
      </DialogContent>
    </Dialog>
  )
}
