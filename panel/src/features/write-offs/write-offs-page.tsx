import { useMemo, useState } from "react"
import { useQuery, useQueryClient } from "@tanstack/react-query"
import {
  Link,
  useLocation,
  useNavigate,
  useSearchParams,
} from "react-router-dom"
import { Add01Icon } from "@hugeicons/core-free-icons"
import { HugeiconsIcon } from "@hugeicons/react"

import { OperationsListGrid } from "@/components/operations-list-grid"
import {
  PageToolbar,
  PageToolbarContent,
} from "@/components/page-toolbar"
import { Badge } from "@/components/ui/badge"
import { Button } from "@/components/ui/button"
import {
  Card,
  CardContent,
  CardDescription,
  CardHeader,
  CardTitle,
} from "@/components/ui/card"
import {
  Select,
  SelectContent,
  SelectGroup,
  SelectItem,
  SelectTrigger,
  SelectValue,
} from "@/components/ui/select"
import { Skeleton } from "@/components/ui/skeleton"
import { Tabs, TabsList, TabsTrigger } from "@/components/ui/tabs"
import { useAuth } from "@/features/auth/use-auth"
import { listDossierActorDisplays } from "@/features/rental-items/dossier/actor/actor-display-api"
import {
  formatDossierActorDisplay,
  type DossierActorDisplay,
} from "@/features/rental-items/dossier/actor/actor-display"
import { useWarehouse } from "@/hooks/use-warehouse"

import { PropertyDispositionCreateDialog } from "./property-disposition-create-dialog"
import {
  canInitiatePropertyDisposition,
  canReviewPropertyDisposition,
  propertyDispositionEffectLabel,
  propertyDispositionSourceLabel,
  propertyDispositionStatusLabel,
} from "./property-disposition-presentation"
import {
  PropertyDispositionReviewDialog,
  type PropertyDispositionReviewAction,
} from "./property-disposition-review-dialog"
import {
  getPropertyDisposition,
  listPropertyDispositions,
  propertyDispositionListQueryKey,
  type PropertyDispositionActor,
  type PropertyDispositionDecision,
  type PropertyDispositionKind,
  type PropertyDispositionState,
} from "./property-dispositions-api"

const PAGE_SIZE = 50

const STATE_OPTIONS: Array<{ value: PropertyDispositionState; label: string }> =
  [
    { value: "PENDING_APPROVAL", label: "На согласовании" },
    { value: "APPROVED", label: "Одобрено" },
    { value: "MOVEMENT_PENDING", label: "Ожидает перемещения" },
    { value: "EFFECT_PENDING", label: "Эффект выполняется" },
    { value: "EFFECTIVE", label: "Исполнено" },
    { value: "REJECTED", label: "Отклонено" },
    { value: "QUARANTINED", label: "Требует восстановления" },
  ]

function formatDateTime(value: string | null) {
  if (!value) return "—"
  return new Intl.DateTimeFormat("ru-RU", {
    dateStyle: "medium",
    timeStyle: "short",
  }).format(new Date(value))
}

function statusVariant(decision: PropertyDispositionDecision) {
  if (decision.state === "QUARANTINED") return "destructive" as const
  if (
    decision.state === "EFFECTIVE" &&
    decision.assetEffectState === "APPLIED"
  ) {
    return "default" as const
  }
  if (decision.state === "REJECTED") return "outline" as const
  return "secondary" as const
}

function actorLabel(
  actor: PropertyDispositionActor | null,
  actorsById: ReadonlyMap<string, DossierActorDisplay>
) {
  if (!actor) return "—"
  if (actor.actorType === "SERVICE") return "Сервис"
  const display = actorsById.get(actor.actorId)
  return display
    ? formatDossierActorDisplay(display)
    : `Пользователь · ${actor.actorId.slice(0, 8)}`
}

function AssetLink({ decision }: { decision: PropertyDispositionDecision }) {
  if (decision.assetKind === "CABIN") {
    return (
      <Button asChild variant="link" size="sm">
        <Link to={`/warehouse/${decision.assetId}`}>
          {decision.assetDisplayName}
        </Link>
      </Button>
    )
  }
  return <span>{decision.assetDisplayName}</span>
}

function DecisionDetails({
  decision,
  actorsById,
}: {
  decision: PropertyDispositionDecision
  actorsById: ReadonlyMap<string, DossierActorDisplay>
}) {
  return (
    <div className="flex flex-col gap-4 text-sm">
      <div className="grid gap-3 sm:grid-cols-2 lg:grid-cols-4">
        <div className="flex flex-col gap-1">
          <span className="text-muted-foreground">Причина</span>
          <span>{decision.reason}</span>
        </div>
        <div className="flex flex-col gap-1">
          <span className="text-muted-foreground">Инициатор</span>
          <span>{actorLabel(decision.requestedBy, actorsById)}</span>
          <span>{formatDateTime(decision.requestedAt)}</span>
        </div>
        <div className="flex flex-col gap-1">
          <span className="text-muted-foreground">Решение администратора</span>
          <span>{actorLabel(decision.reviewedBy, actorsById)}</span>
          <span>{formatDateTime(decision.reviewedAt)}</span>
        </div>
        <div className="flex flex-col gap-1">
          <span className="text-muted-foreground">Корневое решение</span>
          <span className="font-mono text-xs">{decision.id}</span>
          <span>Версия {decision.version}</span>
        </div>
      </div>

      {decision.reviewComment ? (
        <p>
          <span className="text-muted-foreground">Комментарий: </span>
          {decision.reviewComment}
        </p>
      ) : null}
      {decision.evidenceLink ? (
        <p>
          <a
            className="underline underline-offset-4"
            href={decision.evidenceLink}
            target="_blank"
            rel="noreferrer"
          >
            Открыть подтверждение
          </a>
        </p>
      ) : null}

      {decision.contentsPlan ? (
        <div className="flex flex-col gap-2">
          <span className="font-medium">Наполнение бытовки</span>
          <ul className="flex flex-col gap-1">
            {decision.contentsPlan.lines.map((line) => (
              <li key={line.equipmentId}>
                {line.equipmentName}: в бытовке {line.currentQuantity}, в
                перемещение {line.moveToStockQuantity}, списывается{" "}
                {line.disposeQuantity} {line.equipmentFormat ?? "шт."}
              </li>
            ))}
          </ul>
        </div>
      ) : null}

      {decision.repairChain.length > 0 ? (
        <div className="flex flex-col gap-2">
          <span className="font-medium">
            Цепочка ремонта ({decision.repairChain.length})
          </span>
          <ul className="flex flex-col gap-1 font-mono text-xs">
            {decision.repairChain.map((entry) => (
              <li key={entry.repairId}>
                {entry.repairId} · версия {entry.repairVersion}
              </li>
            ))}
          </ul>
        </div>
      ) : null}

      {decision.failureDetail ? (
        <p role="alert" className="text-destructive">
          {decision.failureCode ? `${decision.failureCode}: ` : ""}
          {decision.failureDetail}
        </p>
      ) : null}
    </div>
  )
}

function DecisionActions({
  decision,
  canReview,
  onReview,
  expanded,
  onToggleDetails,
}: {
  decision: PropertyDispositionDecision
  canReview: boolean
  onReview: (
    action: PropertyDispositionReviewAction,
    decision: PropertyDispositionDecision
  ) => void
  expanded: boolean
  onToggleDetails: () => void
}) {
  return (
    <div className="flex flex-wrap items-center gap-2">
      <Button
        type="button"
        variant="outline"
        size="sm"
        onClick={onToggleDetails}
      >
        {expanded ? "Скрыть" : "Подробнее"}
      </Button>
      {canReview && decision.state === "PENDING_APPROVAL" ? (
        <>
          <Button
            type="button"
            size="sm"
            onClick={() => onReview("APPROVE", decision)}
          >
            Принять
          </Button>
          <Button
            type="button"
            size="sm"
            variant="destructive"
            onClick={() => onReview("REJECT", decision)}
          >
            Отклонить
          </Button>
        </>
      ) : null}
      {canReview && decision.state === "QUARANTINED" ? (
        <Button
          type="button"
          size="sm"
          onClick={() => onReview("RECOVER", decision)}
        >
          Восстановить
        </Button>
      ) : null}
    </div>
  )
}

function DecisionMobileCard({
  decision,
  actorsById,
  canReview,
  onReview,
}: {
  decision: PropertyDispositionDecision
  actorsById: ReadonlyMap<string, DossierActorDisplay>
  canReview: boolean
  onReview: (
    action: PropertyDispositionReviewAction,
    decision: PropertyDispositionDecision
  ) => void
}) {
  const [expanded, setExpanded] = useState(false)
  return (
    <Card>
      <CardHeader>
        <CardTitle>
          <AssetLink decision={decision} />
        </CardTitle>
        <CardDescription>
          {propertyDispositionSourceLabel(decision.source)}
        </CardDescription>
      </CardHeader>
      <CardContent className="flex flex-col gap-3">
        <div className="grid grid-cols-2 gap-2 text-sm">
          <span className="text-muted-foreground">Состояние</span>
          <Badge variant={statusVariant(decision)}>
            {propertyDispositionStatusLabel(decision)}
          </Badge>
          <span className="text-muted-foreground">Эффект</span>
          <span>{propertyDispositionEffectLabel(decision)}</span>
          <span className="text-muted-foreground">Инициатор</span>
          <span>{actorLabel(decision.requestedBy, actorsById)}</span>
        </div>
        <DecisionActions
          decision={decision}
          canReview={canReview}
          onReview={onReview}
          expanded={expanded}
          onToggleDetails={() => setExpanded((current) => !current)}
        />
        {expanded ? (
          <DecisionDetails decision={decision} actorsById={actorsById} />
        ) : null}
      </CardContent>
    </Card>
  )
}

export function WriteOffsPage({
  initialDisposition = "WRITE_OFF",
}: {
  initialDisposition?: PropertyDispositionKind
}) {
  const { accessToken, currentUser } = useAuth()
  const { selectedWarehouseId } = useWarehouse()
  const navigate = useNavigate()
  const location = useLocation()
  const [searchParams, setSearchParams] = useSearchParams()
  const queryClient = useQueryClient()
  const disposition: PropertyDispositionKind =
    location.pathname === "/write-offs/equipment" ? "LOSS" : initialDisposition
  const [page, setPage] = useState(0)
  const [state, setState] = useState<PropertyDispositionState | null>(null)
  const [createOpen, setCreateOpen] = useState(false)
  const [expandedDecisionId, setExpandedDecisionId] = useState<string | null>(
    searchParams.get("decisionId")
  )
  const [review, setReview] = useState<{
    action: PropertyDispositionReviewAction
    decision: PropertyDispositionDecision
  } | null>(null)

  const listParams = {
    warehouseId: selectedWarehouseId ?? "none",
    disposition,
    page,
    size: PAGE_SIZE,
    state,
  }
  const listQuery = useQuery({
    queryKey: propertyDispositionListQueryKey(listParams),
    queryFn: () =>
      listPropertyDispositions({
        ...listParams,
        accessToken,
        warehouseId: selectedWarehouseId!,
      }),
    enabled: Boolean(selectedWarehouseId && accessToken),
  })
  const requestedDecisionId = searchParams.get("decisionId")
  const detailQuery = useQuery({
    queryKey: [
      "property-disposition",
      selectedWarehouseId,
      requestedDecisionId,
    ],
    queryFn: () =>
      getPropertyDisposition({
        accessToken,
        warehouseId: selectedWarehouseId!,
        decisionId: requestedDecisionId!,
      }),
    enabled: Boolean(selectedWarehouseId && accessToken && requestedDecisionId),
  })
  const items = useMemo(() => {
    const values = listQuery.data?.items ?? []
    const detail = detailQuery.data
    if (
      !detail ||
      detail.disposition !== disposition ||
      values.some((candidate) => candidate.id === detail.id)
    ) {
      return values
    }
    return [detail, ...values]
  }, [detailQuery.data, disposition, listQuery.data?.items])
  const actorIds = useMemo(
    () =>
      [
        ...new Set(
          items.flatMap((decision) =>
            [decision.requestedBy, decision.reviewedBy]
              .filter(
                (actor): actor is PropertyDispositionActor =>
                  actor !== null && actor.actorType === "USER"
              )
              .map((actor) => actor.actorId)
          )
        ),
      ].sort(),
    [items]
  )
  const actorsQuery = useQuery({
    queryKey: ["property-dispositions", "actors", actorIds],
    queryFn: () => listDossierActorDisplays(accessToken!, actorIds),
    enabled: Boolean(accessToken && actorIds.length > 0),
  })
  const actorsById = useMemo(
    () =>
      new Map(
        (actorsQuery.data ?? []).map(
          (actor) => [actor.subjectId, actor] as const
        )
      ),
    [actorsQuery.data]
  )
  const canInitiate = Boolean(
    selectedWarehouseId &&
    canInitiatePropertyDisposition(currentUser, selectedWarehouseId)
  )
  const canReview = Boolean(
    selectedWarehouseId &&
    canReviewPropertyDisposition(currentUser, selectedWarehouseId)
  )
  const totalPages = listQuery.data
    ? Math.ceil(listQuery.data.totalElements / listQuery.data.size)
    : 0

  function switchDisposition(value: string) {
    if (value === "WRITE_OFF") navigate("/write-offs")
    if (value === "LOSS") navigate("/write-offs/equipment")
  }

  function toggleDetails(decisionId: string) {
    const next = expandedDecisionId === decisionId ? null : decisionId
    setExpandedDecisionId(next)
    const params = new URLSearchParams(searchParams)
    if (next) params.set("decisionId", next)
    else params.delete("decisionId")
    setSearchParams(params, { replace: true })
  }

  function refresh() {
    void queryClient.invalidateQueries({ queryKey: ["property-dispositions"] })
    void queryClient.invalidateQueries({ queryKey: ["property-disposition"] })
  }

  if (!selectedWarehouseId) {
    return (
      <Card>
        <CardHeader>
          <CardTitle>Склад не выбран</CardTitle>
          <CardDescription>
            Выберите склад, чтобы посмотреть решения.
          </CardDescription>
        </CardHeader>
      </Card>
    )
  }

  return (
    <div className="flex h-full min-h-0 flex-col gap-4 overflow-hidden">
      <PageToolbar className="sm:flex-nowrap">
        <PageToolbarContent className="flex flex-wrap items-center gap-2 sm:flex-nowrap">
          <Tabs value={disposition} onValueChange={switchDisposition}>
            <TabsList>
              <TabsTrigger value="WRITE_OFF">Списания</TabsTrigger>
              <TabsTrigger value="LOSS">Утраты</TabsTrigger>
            </TabsList>
          </Tabs>
          <Select
            value={state ?? "ALL"}
            onValueChange={(value) => {
              setState(
                value === "ALL" ? null : (value as PropertyDispositionState)
              )
              setPage(0)
            }}
          >
            <SelectTrigger
              className="w-full sm:w-64"
              aria-label="Состояние решения"
            >
              <SelectValue />
            </SelectTrigger>
            <SelectContent>
              <SelectGroup>
                <SelectItem value="ALL">Все состояния</SelectItem>
                {STATE_OPTIONS.map((option) => (
                  <SelectItem key={option.value} value={option.value}>
                    {option.label}
                  </SelectItem>
                ))}
              </SelectGroup>
            </SelectContent>
          </Select>
          {canInitiate ? (
            <Button
              type="button"
              className="w-full sm:w-auto"
              onClick={() => setCreateOpen(true)}
            >
              <HugeiconsIcon icon={Add01Icon} data-icon="inline-start" />
              {disposition === "WRITE_OFF"
                ? "Списать бытовку"
                : "Добавить утрату"}
            </Button>
          ) : null}
        </PageToolbarContent>
      </PageToolbar>

      {!accessToken ? (
        <p role="alert" className="text-sm text-destructive">
          Для просмотра решений требуется авторизация.
        </p>
      ) : listQuery.isLoading ? (
        <div className="flex flex-col gap-3">
          <Skeleton className="h-12 w-full" />
          <Skeleton className="h-12 w-full" />
          <Skeleton className="h-12 w-full" />
        </div>
      ) : listQuery.isError ? (
        <p role="alert" className="text-sm text-destructive">
          {listQuery.error instanceof Error
            ? listQuery.error.message
            : "Не удалось загрузить решения по имуществу."}
        </p>
      ) : (
        <div className="min-h-0 flex-1 overflow-y-auto">
          <div className="hidden min-h-0 md:block">
            <OperationsListGrid
              items={items}
              expandedItemId={expandedDecisionId}
              renderExpandedRow={(decision) => (
                <DecisionDetails decision={decision} actorsById={actorsById} />
              )}
              columns={[
                {
                  id: "asset",
                  label: "Имущество",
                  getSortValue: (decision) => decision.assetDisplayName,
                  render: (decision) => <AssetLink decision={decision} />,
                },
                {
                  id: "source",
                  label: "Источник",
                  getSortValue: (decision) => decision.source,
                  render: (decision) =>
                    propertyDispositionSourceLabel(decision.source),
                },
                {
                  id: "reason",
                  label: "Причина",
                  getSortValue: (decision) => decision.reason,
                  render: (decision) => (
                    <span className="line-clamp-2">{decision.reason}</span>
                  ),
                },
                {
                  id: "requester",
                  label: "Инициатор",
                  getSortValue: (decision) =>
                    actorLabel(decision.requestedBy, actorsById),
                  render: (decision) =>
                    actorLabel(decision.requestedBy, actorsById),
                },
                {
                  id: "status",
                  label: "Состояние",
                  getSortValue: (decision) => decision.state,
                  render: (decision) => (
                    <Badge variant={statusVariant(decision)}>
                      {propertyDispositionStatusLabel(decision)}
                    </Badge>
                  ),
                },
                {
                  id: "effect",
                  label: "Эффект",
                  getSortValue: (decision) => decision.assetEffectState,
                  render: (decision) =>
                    propertyDispositionEffectLabel(decision),
                },
                {
                  id: "actions",
                  label: "Действия",
                  getSortValue: () => null,
                  render: (decision) => (
                    <DecisionActions
                      decision={decision}
                      canReview={canReview}
                      expanded={expandedDecisionId === decision.id}
                      onToggleDetails={() => toggleDetails(decision.id)}
                      onReview={(action, selected) =>
                        setReview({ action, decision: selected })
                      }
                    />
                  ),
                },
              ]}
            />
          </div>
          <div className="grid gap-3 md:hidden">
            {items.map((decision) => (
              <DecisionMobileCard
                key={decision.id}
                decision={decision}
                actorsById={actorsById}
                canReview={canReview}
                onReview={(action, selected) =>
                  setReview({ action, decision: selected })
                }
              />
            ))}
          </div>
        </div>
      )}

      {listQuery.data && listQuery.data.totalElements > 0 ? (
        <div className="flex items-center justify-end gap-2">
          <span className="text-sm text-muted-foreground">
            Страница {page + 1} из {Math.max(totalPages, 1)} · решений{" "}
            {listQuery.data.totalElements}
          </span>
          <Button
            type="button"
            size="sm"
            variant="outline"
            disabled={page === 0 || listQuery.isFetching}
            onClick={() => setPage((current) => Math.max(0, current - 1))}
          >
            Назад
          </Button>
          <Button
            type="button"
            size="sm"
            variant="outline"
            disabled={page + 1 >= totalPages || listQuery.isFetching}
            onClick={() => setPage((current) => current + 1)}
          >
            Далее
          </Button>
        </div>
      ) : null}

      {canInitiate ? (
        <PropertyDispositionCreateDialog
          key={`${selectedWarehouseId}:${disposition}:${createOpen}`}
          accessToken={accessToken}
          warehouseId={selectedWarehouseId}
          disposition={disposition}
          open={createOpen}
          onOpenChange={setCreateOpen}
          onSaved={(decision) => {
            setExpandedDecisionId(decision.id)
            setSearchParams({ decisionId: decision.id }, { replace: true })
            refresh()
          }}
        />
      ) : null}

      <PropertyDispositionReviewDialog
        key={review ? `${review.decision.id}:${review.action}` : "closed"}
        accessToken={accessToken}
        decision={review?.decision ?? null}
        action={review?.action ?? "APPROVE"}
        open={review !== null}
        onOpenChange={(nextOpen) => {
          if (!nextOpen) setReview(null)
        }}
        onSaved={(decision) => {
          setReview(null)
          setExpandedDecisionId(decision.id)
          refresh()
        }}
        onConflict={() => {
          setReview(null)
          refresh()
        }}
      />
    </div>
  )
}
