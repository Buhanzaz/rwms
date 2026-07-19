import { useMemo, useRef, useState } from "react"
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query"
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
  requestReturnEstimate,
} from "@/features/logistics/returns/api"
import {
  RETURN_STATE_LABELS,
  type ReturnDocument,
  type ReturnDocumentState,
  type ReturnLine,
} from "@/features/logistics/returns/model"
import { useWarehouse } from "@/hooks/use-warehouse"

const ACTIONABLE_STATES = new Set<ReturnDocumentState>([
  "DRAFT",
  "INSPECTION_REQUIRED",
  "CONFLICT",
  "RECONCILIATION_REQUIRED",
])

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
  const [estimateTarget, setEstimateTarget] = useState<{
    document: ReturnDocument
    line: ReturnLine
  } | null>(null)
  const [commandError, setCommandError] = useState<string | null>(null)
  const commandKeys = useRef(new Map<string, string>())
  const selectedDocumentId = searchParams.get("receiptId")
  const selectedLineId = searchParams.get("returnItemId")
  const canEditSelectedWarehouse =
    selectedWarehouseId !== null &&
    hasWarehouseAccess(currentUser, selectedWarehouseId, "EDIT")

  const query = useQuery({
    queryKey: [...RETURNS_QUERY_KEY, selectedWarehouseId],
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
    onSuccess: (_result, document) => {
      commandKeys.current.delete(`register:${document.id}:${document.version}`)
      setCommandError(null)
      void queryClient.invalidateQueries({ queryKey: RETURNS_QUERY_KEY })
    },
    onError: (cause) =>
      setCommandError(
        errorMessage(cause, "Не удалось зарегистрировать возврат")
      ),
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
              disabled
              title="Загрузка фотографий возврата ещё не поддерживается публичным media API"
            >
              Принять без повреждений
            </Button>
            {document.lines.map((line) => (
              <Button
                key={line.id}
                size="sm"
                onClick={() => setEstimateTarget({ document, line })}
              >
                Запросить смету · строка {line.lineNumber}
              </Button>
            ))}
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
          Принятие без повреждений временно недоступно в панели: публичный media
          API пока не умеет загружать фотографии с владельцем возврата. Запрос
          сметы доступен по server-issued line ID и equipment ID.
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
      hasWarehouseAccess(
        currentUser,
        estimateTarget.document.warehouseId,
        "EDIT"
      ) ? (
        <RequestEstimateDialog
          accessToken={accessToken}
          target={estimateTarget}
          onOpenChange={(open) => !open && setEstimateTarget(null)}
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
  const [idempotencyKey] = useState(commandIdentity)
  const [validationError, setValidationError] = useState<string | null>(null)
  const mutation = useMutation({
    mutationFn: () =>
      createReturn({
        accessToken,
        warehouseId,
        idempotencyKey,
        lines: lines.map((line) => ({
          assetId: line.assetId.trim(),
          assetVersion: Number(line.assetVersion),
          tenantSnapshot: line.tenantSnapshot.trim(),
        })),
      }),
    onSuccess: () => {
      void queryClient.invalidateQueries({ queryKey: RETURNS_QUERY_KEY })
      onOpenChange(false)
    },
  })

  function submit(event: React.FormEvent<HTMLFormElement>) {
    event.preventDefault()
    const invalidLine = lines.find(
      (line) =>
        !line.assetId.trim() ||
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
    setValidationError(null)
    mutation.mutate()
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
                            <HugeiconsIcon icon={Delete02Icon} />
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

function RequestEstimateDialog({
  accessToken,
  target,
  onOpenChange,
}: {
  accessToken: string
  target: { document: ReturnDocument; line: ReturnLine }
  onOpenChange: (open: boolean) => void
}) {
  const queryClient = useQueryClient()
  const [equipmentId, setEquipmentId] = useState("")
  const [missingQuantity, setMissingQuantity] = useState("1")
  const [idempotencyKey] = useState(commandIdentity)
  const mutation = useMutation({
    mutationFn: () =>
      requestReturnEstimate({
        accessToken,
        documentId: target.document.id,
        expectedVersion: target.document.version,
        idempotencyKey,
        lines: [
          {
            lineId: target.line.id,
            shortages: [
              {
                equipmentId: equipmentId.trim(),
                missingQuantity: Number(missingQuantity),
              },
            ],
          },
        ],
      }),
    onSuccess: () => {
      void queryClient.invalidateQueries({ queryKey: RETURNS_QUERY_KEY })
      onOpenChange(false)
    },
  })
  const validQuantity =
    Number.isSafeInteger(Number(missingQuantity)) && Number(missingQuantity) > 0

  return (
    <Dialog open onOpenChange={onOpenChange}>
      <DialogContent className="sm:max-w-lg">
        <form
          onSubmit={(event) => {
            event.preventDefault()
            if (equipmentId.trim() && validQuantity) mutation.mutate()
          }}
        >
          <DialogHeader>
            <DialogTitle>Запросить смету</DialogTitle>
            <DialogDescription>
              Укажите подтверждённый equipment ID и недостающее количество для
              строки {target.line.lineNumber}. Logistics-service сам выполнит
              settlement и передаст факт в maintenance-service.
            </DialogDescription>
          </DialogHeader>
          <FieldGroup className="py-4">
            <Field>
              <FieldLabel htmlFor="return-shortage-equipment">
                Equipment UUID
              </FieldLabel>
              <Input
                id="return-shortage-equipment"
                required
                value={equipmentId}
                placeholder="00000000-0000-0000-0000-000000000000"
                onChange={(event) => setEquipmentId(event.target.value)}
              />
              <FieldDescription>
                Справочник оборудования не входит в публичный return contract.
              </FieldDescription>
            </Field>
            <Field data-invalid={!validQuantity}>
              <FieldLabel htmlFor="return-shortage-quantity">
                Недостающее количество
              </FieldLabel>
              <Input
                id="return-shortage-quantity"
                type="number"
                min={1}
                step={1}
                required
                aria-invalid={!validQuantity}
                value={missingQuantity}
                onChange={(event) => setMissingQuantity(event.target.value)}
              />
            </Field>
            {mutation.error ? (
              <FieldError>
                {errorMessage(mutation.error, "Не удалось запросить смету")}
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
              disabled={
                mutation.isPending || !equipmentId.trim() || !validQuantity
              }
            >
              {mutation.isPending ? "Запрашивается…" : "Запросить"}
            </Button>
          </DialogFooter>
        </form>
      </DialogContent>
    </Dialog>
  )
}
