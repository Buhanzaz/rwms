import { useEffect, useMemo, useState } from "react"
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query"
import { Route, Routes, useNavigate, useParams } from "react-router-dom"
import { toast } from "sonner"

import { PageToolbar, PageToolbarActions } from "@/components/page-toolbar"
import { Badge } from "@/components/ui/badge"
import { Button } from "@/components/ui/button"
import {
  Card,
  CardContent,
  CardDescription,
  CardFooter,
  CardHeader,
  CardTitle,
} from "@/components/ui/card"
import { Checkbox } from "@/components/ui/checkbox"
import { Field, FieldGroup, FieldLabel } from "@/components/ui/field"
import { Input } from "@/components/ui/input"
import {
  Select,
  SelectContent,
  SelectGroup,
  SelectItem,
  SelectTrigger,
  SelectValue,
} from "@/components/ui/select"
import {
  completeInventorySession,
  createAndAttachInventoryAsset,
  getActiveInventorySession,
  getInventorySession,
  getInventoryStatisticsSummary,
  listInventorySessions,
  previewInventoryCompletion,
  publishInventoryFindings,
  resolveInventoryNumber,
  startInventorySession,
} from "@/features/inventory/adapters/http-inventory-adapter"
import { InventoryFindingEditor } from "@/features/inventory/inventory-finding-editor"
import { InventoryPublicationPanel } from "@/features/inventory/inventory-publication-panel"
import { InventoryStartDialog } from "@/features/inventory/inventory-start-dialog"
import {
  FindingList,
  InventoryStatisticsView,
  InventoryUnavailable,
  SessionCards,
} from "@/features/inventory/inventory-service-ui"
import { formatDateTime } from "@/features/inventory/inventory-service-formatters"
import type {
  InventoryCompletionPreview,
  InventoryCreateIntent,
} from "@/features/inventory/model/inventory-service"
import { useAuth } from "@/features/auth/use-auth"
import { useWarehouse } from "@/hooks/use-warehouse"
import { ApiError } from "@/lib/api-client"

const inventoryKeys = {
  all: ["inventory-service"] as const,
  active: (warehouseId: string) =>
    ["inventory-service", "active", warehouseId] as const,
  history: (warehouseId: string, page: number) =>
    ["inventory-service", "history", warehouseId, page] as const,
  detail: (inventoryId: string) =>
    ["inventory-service", "detail", inventoryId] as const,
  summary: (warehouseId: string) =>
    ["inventory-service", "summary", warehouseId] as const,
}

function errorMessage(error: unknown) {
  return error instanceof Error ? error.message : "Операция не выполнена"
}

function retryInventoryQuery(failureCount: number, error: unknown) {
  if (error instanceof ApiError && error.status >= 400 && error.status < 500) {
    return false
  }
  return failureCount < 3
}

function useStableCommandKey(signature: string) {
  const intent = useMemo(
    () => ({ signature, idempotencyKey: crypto.randomUUID() }),
    [signature]
  )
  return intent.idempotencyKey
}

function InventoryLandingPage() {
  const navigate = useNavigate()
  const queryClient = useQueryClient()
  const { accessToken } = useAuth()
  const { selectedWarehouse } = useWarehouse()
  const [startOpen, setStartOpen] = useState(false)
  const startIdempotencyKey = useStableCommandKey(
    `start:${selectedWarehouse?.id ?? "none"}`
  )
  const activeQuery = useQuery({
    queryKey: inventoryKeys.active(selectedWarehouse?.id ?? "none"),
    queryFn: () =>
      getActiveInventorySession(accessToken, selectedWarehouse!.id),
    enabled: accessToken !== null && selectedWarehouse !== null,
    retry: retryInventoryQuery,
  })
  const startMutation = useMutation({
    mutationFn: () =>
      startInventorySession(
        accessToken,
        selectedWarehouse!.id,
        startIdempotencyKey
      ),
    onSuccess: async (session) => {
      await queryClient.invalidateQueries({ queryKey: inventoryKeys.all })
      navigate(`/inventory/${session.id}`)
    },
  })

  useEffect(() => {
    if (activeQuery.data) {
      navigate(`/inventory/${activeQuery.data.id}`, { replace: true })
    }
  }, [activeQuery.data, navigate])

  if (!selectedWarehouse)
    return <InventoryUnavailable>Выберите склад в меню.</InventoryUnavailable>
  if (!accessToken)
    return (
      <InventoryUnavailable>Войдите в систему повторно.</InventoryUnavailable>
    )
  if (activeQuery.error)
    return (
      <InventoryUnavailable>
        {errorMessage(activeQuery.error)}
      </InventoryUnavailable>
    )
  if (activeQuery.isLoading)
    return <p className="text-sm text-muted-foreground">Загрузка...</p>
  if (activeQuery.data) return null

  return (
    <Card className="max-w-2xl">
      <CardHeader>
        <CardTitle>Инвентаризация склада</CardTitle>
        <CardDescription>
          Активной сессии для склада «{selectedWarehouse.name}» нет.
        </CardDescription>
      </CardHeader>
      <CardContent className="flex flex-wrap gap-2">
        <Button type="button" onClick={() => setStartOpen(true)}>
          Начать инвентаризацию
        </Button>
        <Button
          type="button"
          variant="outline"
          onClick={() => navigate("/inventory/history")}
        >
          История
        </Button>
      </CardContent>
      <InventoryStartDialog
        open={startOpen}
        warehouseName={selectedWarehouse.name}
        pending={startMutation.isPending}
        error={startMutation.error ? errorMessage(startMutation.error) : null}
        onOpenChange={setStartOpen}
        onConfirm={() => startMutation.mutate()}
      />
    </Card>
  )
}

function InventorySessionPage({ readOnly = false }: { readOnly?: boolean }) {
  const { inventoryId = "" } = useParams()
  const navigate = useNavigate()
  const { accessToken } = useAuth()
  const [selectedFindingId, setSelectedFindingId] = useState<string | null>(
    null
  )
  const [number, setNumber] = useState("")
  const [pendingCreateIntent, setPendingCreateIntent] =
    useState<InventoryCreateIntent | null>(null)
  const [origin, setOrigin] = useState<"ADDED_NEW" | "ADDED_USED">("ADDED_USED")
  const queryClient = useQueryClient()
  const query = useQuery({
    queryKey: inventoryKeys.detail(inventoryId),
    queryFn: () => getInventorySession(accessToken, inventoryId),
    enabled: accessToken !== null && Boolean(inventoryId),
    retry: retryInventoryQuery,
  })
  const resolveIdempotencyKey = useStableCommandKey(
    `resolve:${inventoryId}:${query.data?.sessionRevision ?? "loading"}:${number.trim()}`
  )
  const publishSignature = query.data
    ? query.data.findings
        .map(
          (finding) =>
            `${finding.id}:${finding.publication?.publicationRevision ?? "none"}`
        )
        .join("|")
    : "loading"
  const publishIdempotencyKey = useStableCommandKey(
    `publish:${inventoryId}:${query.data?.sessionRevision ?? "loading"}:${publishSignature}`
  )
  const refreshSession = async () => {
    await queryClient.invalidateQueries({
      queryKey: inventoryKeys.detail(inventoryId),
    })
  }
  const resolveMutation = useMutation({
    mutationFn: () =>
      resolveInventoryNumber({
        accessToken,
        inventoryId,
        expectedSessionRevision: query.data!.sessionRevision,
        submittedNumber: number,
        idempotencyKey: resolveIdempotencyKey,
      }),
    onSuccess: async (resolution) => {
      if (resolution.finding) setSelectedFindingId(resolution.finding.id)
      if (resolution.outcome === "NOT_FOUND") {
        setPendingCreateIntent((current) =>
          current?.number === resolution.displayCanonicalNumber
            ? current
            : {
                findingId: crypto.randomUUID(),
                idempotencyKey: crypto.randomUUID(),
                number: resolution.displayCanonicalNumber,
              }
        )
      } else {
        setPendingCreateIntent(null)
      }
      await refreshSession()
    },
  })
  const createMutation = useMutation({
    mutationFn: () =>
      createAndAttachInventoryAsset({
        accessToken,
        inventoryId,
        findingId: pendingCreateIntent!.findingId,
        expectedSessionRevision: query.data!.sessionRevision,
        expectedFindingRevision: 0,
        origin,
        displayCanonicalNumber: pendingCreateIntent!.number,
        safePassport: {},
        idempotencyKey: pendingCreateIntent!.idempotencyKey,
      }),
    onSuccess: async (finding) => {
      setPendingCreateIntent(null)
      setSelectedFindingId(finding.id)
      await refreshSession()
    },
  })
  const publishMutation = useMutation({
    mutationFn: () =>
      publishInventoryFindings({
        accessToken,
        inventoryId,
        expectedSessionRevision: query.data!.sessionRevision,
        idempotencyKey: publishIdempotencyKey,
      }),
    onSuccess: async (batch) => {
      await refreshSession()
      toast.success(`Публикация: ${batch.aggregateState}`)
    },
  })
  const session = query.data
  const selectedFinding = session?.findings.find(
    (finding) => finding.id === selectedFindingId
  )
  if (query.isLoading)
    return <p className="text-sm text-muted-foreground">Загрузка...</p>
  if (!session)
    return (
      <InventoryUnavailable>
        {query.error
          ? errorMessage(query.error)
          : "Сессия не найдена или недоступна."}
      </InventoryUnavailable>
    )
  if (selectedFinding) {
    return (
      <InventoryFindingEditor
        session={session}
        finding={selectedFinding}
        readOnly={readOnly || session.lifecycle !== "ACTIVE"}
        onClose={() => setSelectedFindingId(null)}
        onSaved={refreshSession}
      />
    )
  }
  const hasBatchEligiblePublication = session.findings.some(
    (finding) =>
      finding.publication?.state === "READY" ||
      finding.publication?.state === "TRANSIENT_FAILED"
  )
  return (
    <div className="flex h-full min-h-0 flex-col gap-4 overflow-y-auto">
      <PageToolbar>
        <div className="flex flex-wrap gap-2">
          <Badge variant="secondary">
            Проверено {session.inspectedCount} из {session.expectedCount}
          </Badge>
          <Badge variant="outline">Ревизия {session.sessionRevision}</Badge>
        </div>
        <PageToolbarActions>
          {session.lifecycle === "ACTIVE" && !readOnly ? (
            <Button
              type="button"
              onClick={() => navigate(`/inventory/${session.id}/finish`)}
            >
              Завершить
            </Button>
          ) : null}
          {session.lifecycle === "COMPLETED" && hasBatchEligiblePublication ? (
            <Button
              type="button"
              disabled={publishMutation.isPending}
              onClick={() => publishMutation.mutate()}
            >
              Передать доступные работы
            </Button>
          ) : null}
        </PageToolbarActions>
      </PageToolbar>
      {session.lifecycle === "ACTIVE" && !readOnly ? (
        <Card size="sm">
          <CardHeader>
            <CardTitle>Найти или добавить бытовку</CardTitle>
          </CardHeader>
          <CardContent>
            <FieldGroup>
              <Field>
                <FieldLabel htmlFor="inventory-number">Номер</FieldLabel>
                <Input
                  id="inventory-number"
                  value={number}
                  onChange={(event) => {
                    setNumber(event.target.value)
                    setPendingCreateIntent(null)
                  }}
                />
              </Field>
              <Button
                type="button"
                disabled={!number.trim() || resolveMutation.isPending}
                onClick={() => resolveMutation.mutate()}
              >
                Проверить номер
              </Button>
            </FieldGroup>
          </CardContent>
        </Card>
      ) : null}
      {pendingCreateIntent ? (
        <Card size="sm">
          <CardHeader>
            <CardTitle>Создать отсутствующую бытовку</CardTitle>
            <CardDescription>{pendingCreateIntent.number}</CardDescription>
          </CardHeader>
          <CardContent>
            <Field>
              <FieldLabel htmlFor="inventory-origin">Состояние</FieldLabel>
              <Select
                value={origin}
                onValueChange={(value) => {
                  setOrigin(value as typeof origin)
                  setPendingCreateIntent((current) =>
                    current
                      ? {
                          ...current,
                          findingId: crypto.randomUUID(),
                          idempotencyKey: crypto.randomUUID(),
                        }
                      : null
                  )
                }}
              >
                <SelectTrigger id="inventory-origin">
                  <SelectValue />
                </SelectTrigger>
                <SelectContent>
                  <SelectGroup>
                    <SelectItem value="ADDED_NEW">Новая</SelectItem>
                    <SelectItem value="ADDED_USED">Б/у</SelectItem>
                  </SelectGroup>
                </SelectContent>
              </Select>
            </Field>
          </CardContent>
          <CardFooter>
            <Button
              type="button"
              disabled={createMutation.isPending}
              onClick={() => createMutation.mutate()}
            >
              Создать и прикрепить
            </Button>
          </CardFooter>
        </Card>
      ) : null}
      {resolveMutation.error ||
      createMutation.error ||
      publishMutation.error ? (
        <p role="alert" className="text-sm text-destructive">
          {errorMessage(
            resolveMutation.error ??
              createMutation.error ??
              publishMutation.error
          )}
        </p>
      ) : null}
      <FindingList
        findings={session.findings}
        onOpen={(finding) => setSelectedFindingId(finding.id)}
      />
      {session.lifecycle === "COMPLETED" ? (
        <InventoryPublicationPanel
          inventoryId={session.id}
          findings={session.findings}
          onChanged={refreshSession}
        />
      ) : null}
      {session.statistics ? (
        <InventoryStatisticsView statistics={session.statistics} />
      ) : null}
    </div>
  )
}

function InventoryFinishPage() {
  const { inventoryId = "" } = useParams()
  const navigate = useNavigate()
  const queryClient = useQueryClient()
  const { accessToken } = useAuth()
  const [acknowledged, setAcknowledged] = useState(false)
  const sessionQuery = useQuery({
    queryKey: inventoryKeys.detail(inventoryId),
    queryFn: () => getInventorySession(accessToken, inventoryId),
    enabled: accessToken !== null && Boolean(inventoryId),
    retry: retryInventoryQuery,
  })
  const revisionSignature = sessionQuery.data
    ? sessionQuery.data.findings
        .map((finding) => `${finding.id}:${finding.findingRevision}`)
        .join("|")
    : "loading"
  const previewIdempotencyKey = useStableCommandKey(
    `preview:${inventoryId}:${sessionQuery.data?.sessionRevision ?? "loading"}:${revisionSignature}`
  )
  const previewQuery = useQuery({
    queryKey: [
      "inventory-service",
      "preview",
      inventoryId,
      sessionQuery.data?.sessionRevision,
      revisionSignature,
    ],
    queryFn: () =>
      previewInventoryCompletion({
        accessToken,
        session: sessionQuery.data!,
        idempotencyKey: previewIdempotencyKey,
      }),
    enabled: Boolean(sessionQuery.data?.lifecycle === "ACTIVE"),
    retry: retryInventoryQuery,
  })
  const completeIdempotencyKey = useStableCommandKey(
    `complete:${inventoryId}:${previewQuery.data?.acknowledgementSha256 ?? "loading"}:${previewQuery.data?.validationSha256 ?? "loading"}`
  )
  const completeMutation = useMutation({
    mutationFn: (preview: InventoryCompletionPreview) =>
      completeInventorySession({
        accessToken,
        preview,
        idempotencyKey: completeIdempotencyKey,
      }),
    onSuccess: async (session) => {
      await queryClient.invalidateQueries({ queryKey: inventoryKeys.all })
      navigate(`/inventory/history/${session.id}`, { replace: true })
    },
  })
  if (sessionQuery.isLoading || previewQuery.isLoading)
    return <p className="text-sm text-muted-foreground">Проверяем итоги...</p>
  const preview = previewQuery.data
  if (!sessionQuery.data || !preview)
    return (
      <InventoryUnavailable>
        {errorMessage(sessionQuery.error ?? previewQuery.error)}
      </InventoryUnavailable>
    )
  return (
    <div className="flex flex-col gap-4 overflow-y-auto">
      <PageToolbar>
        <Button
          type="button"
          variant="outline"
          onClick={() => navigate(`/inventory/${inventoryId}`)}
        >
          Назад
        </Button>
      </PageToolbar>
      <InventoryStatisticsView statistics={preview.statistics} />
      <Card>
        <CardHeader>
          <CardTitle>Риски завершения</CardTitle>
          <CardDescription>
            Проверка выполнена сервером {formatDateTime(preview.validatedAt)}.
          </CardDescription>
        </CardHeader>
        <CardContent className="flex flex-col gap-3">
          {preview.risks.length > 0 ? (
            preview.risks.map((risk) => (
              <Badge key={`${risk.findingId}:${risk.code}`} variant="secondary">
                {risk.code}
              </Badge>
            ))
          ) : (
            <p className="text-sm text-muted-foreground">
              Блокирующих рисков нет.
            </p>
          )}
          <Field orientation="horizontal">
            <Checkbox
              id="inventory-finish-ack"
              checked={acknowledged}
              onCheckedChange={(value) => setAcknowledged(value === true)}
            />
            <FieldLabel htmlFor="inventory-finish-ack">
              Подтверждаю серверную сверку и необратимое завершение
            </FieldLabel>
          </Field>
        </CardContent>
        <CardFooter>
          <Button
            type="button"
            disabled={!acknowledged || completeMutation.isPending}
            onClick={() => completeMutation.mutate(preview)}
          >
            {completeMutation.isPending
              ? "Завершаем..."
              : "Завершить инвентаризацию"}
          </Button>
        </CardFooter>
      </Card>
      {completeMutation.error ? (
        <p role="alert" className="text-sm text-destructive">
          {errorMessage(completeMutation.error)}
        </p>
      ) : null}
    </div>
  )
}

function InventoryHistoryPage() {
  const navigate = useNavigate()
  const { accessToken } = useAuth()
  const { selectedWarehouse } = useWarehouse()
  const warehouseId = selectedWarehouse?.id ?? "none"
  const [pagination, setPagination] = useState({ warehouseId, page: 0 })
  const page = pagination.warehouseId === warehouseId ? pagination.page : 0
  const historyQuery = useQuery({
    queryKey: inventoryKeys.history(selectedWarehouse?.id ?? "none", page),
    queryFn: () =>
      listInventorySessions(accessToken, selectedWarehouse!.id, page),
    enabled: accessToken !== null && selectedWarehouse !== null,
    retry: retryInventoryQuery,
  })
  const summaryQuery = useQuery({
    queryKey: inventoryKeys.summary(selectedWarehouse?.id ?? "none"),
    queryFn: () =>
      getInventoryStatisticsSummary(accessToken, selectedWarehouse!.id),
    enabled: accessToken !== null && selectedWarehouse !== null,
    retry: retryInventoryQuery,
  })
  if (!selectedWarehouse)
    return <InventoryUnavailable>Выберите склад в меню.</InventoryUnavailable>
  const metadata = historyQuery.data?.page
  return (
    <div className="flex h-full min-h-0 flex-col gap-4 overflow-y-auto">
      <PageToolbar>
        <Button
          type="button"
          variant="outline"
          onClick={() => navigate("/inventory")}
        >
          К текущей
        </Button>
      </PageToolbar>
      {summaryQuery.data ? (
        <InventoryStatisticsView statistics={summaryQuery.data.statistics} />
      ) : null}
      {historyQuery.data?.content.length ? (
        <SessionCards
          sessions={historyQuery.data.content}
          onOpen={(session) =>
            navigate(
              session.lifecycle === "ACTIVE"
                ? `/inventory/${session.id}`
                : `/inventory/history/${session.id}`
            )
          }
        />
      ) : historyQuery.isLoading ? (
        <p className="text-sm text-muted-foreground">Загрузка истории...</p>
      ) : (
        <p className="text-sm text-muted-foreground">
          Инвентаризаций пока нет.
        </p>
      )}
      {metadata && metadata.totalPages > 1 ? (
        <nav
          aria-label="Страницы истории инвентаризаций"
          className="flex flex-wrap items-center justify-between gap-2"
        >
          <Button
            type="button"
            variant="outline"
            disabled={metadata.page === 0 || historyQuery.isFetching}
            onClick={() =>
              setPagination({
                warehouseId,
                page: Math.max(0, page - 1),
              })
            }
          >
            Назад
          </Button>
          <span className="text-sm text-muted-foreground">
            Страница {metadata.page + 1} из {metadata.totalPages}
          </span>
          <Button
            type="button"
            variant="outline"
            disabled={
              metadata.page + 1 >= metadata.totalPages ||
              historyQuery.isFetching
            }
            onClick={() =>
              setPagination({
                warehouseId,
                page: Math.min(metadata.totalPages - 1, page + 1),
              })
            }
          >
            Вперёд
          </Button>
        </nav>
      ) : null}
      {historyQuery.error ? (
        <p role="alert" className="text-sm text-destructive">
          {errorMessage(historyQuery.error)}
        </p>
      ) : null}
    </div>
  )
}

export function InventoryServiceRoutes() {
  return (
    <Routes>
      <Route index element={<InventoryLandingPage />} />
      <Route path="history" element={<InventoryHistoryPage />} />
      <Route
        path="history/:inventoryId"
        element={<InventorySessionPage readOnly />}
      />
      <Route path=":inventoryId/finish" element={<InventoryFinishPage />} />
      <Route path=":inventoryId" element={<InventorySessionPage />} />
    </Routes>
  )
}
