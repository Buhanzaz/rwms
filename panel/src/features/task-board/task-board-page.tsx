import { useCallback, useEffect, useMemo, useRef, useState } from "react"
import { HugeiconsIcon } from "@hugeicons/react"
import { Add01Icon, Search01Icon } from "@hugeicons/core-free-icons"
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query"
import { Link, useNavigate, useSearchParams } from "react-router-dom"

import {
  PageToolbar,
  PageToolbarActions,
  PageToolbarContent,
} from "@/components/page-toolbar"
import { Badge } from "@/components/ui/badge"
import { Button } from "@/components/ui/button"
import { Checkbox } from "@/components/ui/checkbox"
import { Field, FieldLabel } from "@/components/ui/field"
import {
  InputGroup,
  InputGroupAddon,
  InputGroupInput,
} from "@/components/ui/input-group"
import { Skeleton } from "@/components/ui/skeleton"
import { hasWarehouseAccess } from "@/features/auth/warehouse-access"
import { useAuth } from "@/features/auth/use-auth"
import {
  listMaintenanceRepairs,
  type MaintenanceRepair,
} from "@/features/repair-estimates/api/http-maintenance-lifecycle-client"
import {
  getKpiSettings,
  kpiSettingsKeys,
} from "@/features/settings/kpi/api/kpi-settings-api"
import {
  completeTaskBoardEntry,
  getTaskBoard,
  pauseTaskBoardEntry,
  pinTaskBoardEntry,
  resumeTaskBoardEntry,
  takeTaskBoardEntry,
  TASK_BOARD_QUERY_KEY,
  taskBoardQueryKey,
} from "@/features/task-board/api/task-board-api"
import { mergeQueueCollapsedSettings } from "@/features/task-board/domain/task-board-domain"
import {
  nextTaskTimerTransitionAt,
  paletteForTaskBoard,
} from "@/features/task-board/domain/task-board-kpi-presentation"
import type { TaskBoardEntryDto } from "@/features/task-board/model/task-board"
import type { TaskBoardRepairComplexity } from "@/features/task-board/task-board-card"
import { TaskBoardColumn } from "@/features/task-board/task-board-column"
import { TaskBoardCompletionDialog } from "@/features/task-board/task-board-completion-dialog"
import { TaskBoardTakeDialog } from "@/features/task-board/task-board-take-dialog"
import { useIsMobile } from "@/hooks/use-mobile"
import { useWarehouse } from "@/hooks/use-warehouse"
import { workspaceEntryNavigationOptions } from "@/hooks/use-workspace-back"
import { cn } from "@/lib/utils"

type BoardAction =
  | {
      kind: "take"
      entry: TaskBoardEntryDto
      workerGroupId: string
      workerId: string | null
    }
  | { kind: "pause"; entry: TaskBoardEntryDto }
  | { kind: "resume"; entry: TaskBoardEntryDto }
  | { kind: "complete"; entry: TaskBoardEntryDto }

function errorMessage(error: unknown, fallback: string) {
  return error instanceof Error ? error.message : fallback
}

const ACTIVE_REPAIR_PAGE_SIZE = 200
const EMPTY_ENTRY_IDS: ReadonlySet<string> = new Set()

/** Resolves visible repair complexity in bounded ID batches without scanning warehouse repairs. */
async function loadActiveRepairComplexities(
  accessToken: string,
  warehouseId: string,
  sourceIds: readonly string[]
): Promise<MaintenanceRepair[]> {
  const matches: MaintenanceRepair[] = []
  for (
    let offset = 0;
    offset < sourceIds.length;
    offset += ACTIVE_REPAIR_PAGE_SIZE
  ) {
    const repairIds = sourceIds.slice(offset, offset + ACTIVE_REPAIR_PAGE_SIZE)
    const response = await listMaintenanceRepairs(accessToken, warehouseId, {
      repairIds,
      page: 0,
      size: ACTIVE_REPAIR_PAGE_SIZE,
    })
    if (
      response.page !== 0 ||
      response.size !== ACTIVE_REPAIR_PAGE_SIZE ||
      response.totalElements > repairIds.length ||
      response.items.length !== response.totalElements
    ) {
      throw new Error("Сервис ремонтов вернул некорректную страницу.")
    }
    matches.push(...response.items)
  }
  return Array.from(
    new Map(matches.map((repair) => [repair.id, repair])).values()
  )
}

/** Renders server-ordered queues as side-by-side columns with vertically stacked route entries. */
export function TaskBoardPage() {
  const isMobile = useIsMobile()
  const navigate = useNavigate()
  const [searchParams, setSearchParams] = useSearchParams()
  const queryClient = useQueryClient()
  const { accessToken, currentUser } = useAuth()
  const { selectedWarehouse } = useWarehouse()
  const warehouseId = selectedWarehouse?.id ?? null
  const canEdit = Boolean(
    warehouseId && hasWarehouseAccess(currentUser, warehouseId, "EDIT")
  )
  const [search, setSearch] = useState("")
  const [showFutureSubtasks, setShowFutureSubtasks] = useState(false)
  const [selectedRouteTaskId, setSelectedRouteTaskId] = useState<string | null>(
    null
  )
  const [collapsedQueues, setCollapsedQueues] = useState<Set<string>>(
    () => new Set()
  )
  const [entryCollapseStates, setEntryCollapseStates] = useState<
    Map<string, boolean>
  >(() => new Map())
  const [takeEntry, setTakeEntry] = useState<TaskBoardEntryDto | null>(null)
  const [completeEntry, setCompleteEntry] = useState<TaskBoardEntryDto | null>(
    null
  )
  const [error, setError] = useState<string | null>(null)
  const [notice, setNotice] = useState<string | null>(null)
  const [now, setNow] = useState(() => Date.now())
  const focusedExternalTaskId = searchParams.get("externalTaskId")
  const focusedTaskId = searchParams.get("taskId")
  const collapsedSettingsRef = useRef<{
    warehouseId: string
    values: Map<string, boolean>
  } | null>(null)

  const boardQuery = useQuery({
    queryKey: taskBoardQueryKey(warehouseId ?? "none"),
    queryFn: () => getTaskBoard(accessToken!, warehouseId!),
    enabled: Boolean(accessToken && warehouseId),
  })
  const highlightedTaskId = useMemo(() => {
    if (
      !selectedRouteTaskId ||
      !warehouseId ||
      boardQuery.data?.warehouseId !== warehouseId
    ) {
      return null
    }
    return boardQuery.data.queues.some((queue) =>
      queue.entries.some(
        (entry) =>
          entry.taskId === selectedRouteTaskId && entry.entryType === "REAL"
      )
    )
      ? selectedRouteTaskId
      : null
  }, [boardQuery.data, selectedRouteTaskId, warehouseId])
  const normalizedSearch = search.trim().toLocaleLowerCase("ru")
  const visibleQueues = useMemo(
    () =>
      (boardQuery.data?.queues ?? []).map((queue) => ({
        queue,
        visibleEntries: queue.entries.filter((entry) => {
          const belongsToHighlightedRoute = entry.taskId === highlightedTaskId
          if (
            entry.entryType === "SHADOW" &&
            !showFutureSubtasks &&
            !belongsToHighlightedRoute
          ) {
            return false
          }
          if (
            focusedExternalTaskId &&
            entry.externalTaskId !== focusedExternalTaskId
          ) {
            return false
          }
          if (focusedTaskId && entry.taskId !== focusedTaskId) return false
          if (belongsToHighlightedRoute || !normalizedSearch) return true
          return [
            entry.unitNumber ?? "",
            entry.title,
            entry.taskText ?? "",
            entry.externalTaskId ?? "",
            ...entry.assignments.flatMap((assignment) => [
              assignment.workerName ?? "",
              assignment.workerGroupName ?? "",
            ]),
          ]
            .join(" ")
            .toLocaleLowerCase("ru")
            .includes(normalizedSearch)
        }),
      })),
    [
      boardQuery.data,
      focusedExternalTaskId,
      focusedTaskId,
      highlightedTaskId,
      normalizedSearch,
      showFutureSubtasks,
    ]
  )
  const visibleEntryCount = useMemo(
    () =>
      visibleQueues.reduce(
        (count, current) => count + current.visibleEntries.length,
        0
      ),
    [visibleQueues]
  )
  const dailyPlanEntryIdsByQueue = useMemo(
    () =>
      new Map(
        (boardQuery.data?.queues ?? []).map((queue) => [
          queue.key,
          new Set(
            queue.entries
              .filter(
                (entry) =>
                  entry.entryType === "REAL" && entry.status === "WAITING"
              )
              .slice(0, queue.availableTaskLimit)
              .map((entry) => entry.id)
          ),
        ])
      ),
    [boardQuery.data]
  )
  const highlightedRouteLabel = useMemo(() => {
    if (!highlightedTaskId) return null
    const entry = boardQuery.data?.queues
      .flatMap((queue) => queue.entries)
      .find((candidate) => candidate.taskId === highlightedTaskId)
    return entry?.unitNumber ?? entry?.title ?? "выбранной бытовки"
  }, [boardQuery.data, highlightedTaskId])
  const kpiSettingsQuery = useQuery({
    queryKey: kpiSettingsKeys.warehouse(warehouseId ?? "none"),
    queryFn: () => getKpiSettings(accessToken!, warehouseId!),
    enabled: Boolean(accessToken && warehouseId),
    staleTime: 60_000,
  })
  const taskBoardPalette = paletteForTaskBoard(kpiSettingsQuery.data ?? null)
  const maintenanceRepairSourceIds = useMemo(
    () =>
      Array.from(
        new Set(
          visibleQueues
            .flatMap((queue) => queue.visibleEntries)
            .flatMap((entry) =>
              entry.source?.type === "MAINTENANCE_REPAIR"
                ? [entry.source.sourceId]
                : []
            )
        )
      ).sort(),
    [visibleQueues]
  )
  const repairComplexitiesQuery = useQuery({
    queryKey: [
      "maintenance",
      "repairs",
      warehouseId ?? "none",
      "task-board-complexities",
      maintenanceRepairSourceIds,
    ],
    queryFn: () =>
      loadActiveRepairComplexities(
        accessToken!,
        warehouseId!,
        maintenanceRepairSourceIds
      ),
    enabled: Boolean(
      accessToken && warehouseId && maintenanceRepairSourceIds.length > 0
    ),
  })
  const repairComplexitiesByRepairId = useMemo(() => {
    const visibleSourceIds = new Set(maintenanceRepairSourceIds)
    return new Map<string, TaskBoardRepairComplexity>(
      (repairComplexitiesQuery.data ?? [])
        .filter((repair) => visibleSourceIds.has(repair.id))
        .map((repair) => [repair.id, repair.complexity])
    )
  }, [maintenanceRepairSourceIds, repairComplexitiesQuery.data])

  const isEntryCollapsed = useCallback(
    (entryId: string) => entryCollapseStates.get(entryId) ?? isMobile,
    [entryCollapseStates, isMobile]
  )
  const toggleEntryCollapsed = useCallback(
    (entryId: string) => {
      setEntryCollapseStates((current) => {
        const next = new Map(current)
        next.set(entryId, !(current.get(entryId) ?? isMobile))
        return next
      })
    },
    [isMobile]
  )

  const toggleFullRoute = useCallback(
    (entry: TaskBoardEntryDto) => {
      const nextTaskId =
        highlightedTaskId === entry.taskId ? null : entry.taskId
      setSelectedRouteTaskId(nextTaskId)
      if (!nextTaskId) return
      const routeQueueKeys = new Set(
        (boardQuery.data?.queues ?? [])
          .filter((queue) =>
            queue.entries.some((candidate) => candidate.taskId === nextTaskId)
          )
          .map((queue) => queue.key)
      )
      setCollapsedQueues((current) => {
        const next = new Set(current)
        routeQueueKeys.forEach((queueKey) => next.delete(queueKey))
        return next
      })
    },
    [boardQuery.data, highlightedTaskId]
  )

  useEffect(() => {
    if (!warehouseId || !boardQuery.data) return
    const previous = collapsedSettingsRef.current
    const merged = mergeQueueCollapsedSettings({
      current: collapsedQueues,
      previous:
        previous && previous.warehouseId === warehouseId
          ? previous.values
          : null,
      queues: boardQuery.data.queues,
      reset: previous?.warehouseId !== warehouseId,
    })
    collapsedSettingsRef.current = { warehouseId, values: merged.settings }
    if (
      merged.collapsed.size !== collapsedQueues.size ||
      [...merged.collapsed].some((queueKey) => !collapsedQueues.has(queueKey))
    ) {
      setCollapsedQueues(merged.collapsed)
    }
  }, [boardQuery.data, collapsedQueues, warehouseId])

  useEffect(() => {
    const timer = window.setInterval(() => setNow(Date.now()), 1_000)
    return () => window.clearInterval(timer)
  }, [])

  const nextTimerTransitionAt = nextTaskTimerTransitionAt(
    boardQuery.data?.queues.flatMap((queue) => queue.entries) ?? []
  )
  useEffect(() => {
    if (nextTimerTransitionAt === null) return
    const delay = Math.max(0, nextTimerTransitionAt - Date.now()) + 100
    const timer = window.setTimeout(
      () =>
        void queryClient.invalidateQueries({
          queryKey: taskBoardQueryKey(warehouseId ?? "none"),
          exact: true,
        }),
      delay
    )
    return () => window.clearTimeout(timer)
  }, [nextTimerTransitionAt, queryClient, warehouseId])

  const invalidateTaskBoard = useCallback(
    () => queryClient.invalidateQueries({ queryKey: TASK_BOARD_QUERY_KEY }),
    [queryClient]
  )
  const actionMutation = useMutation({
    mutationFn: (action: BoardAction) => {
      if (!accessToken) {
        throw new Error("Не получен токен доступа к доске заданий.")
      }
      if (action.kind === "take") {
        return takeTaskBoardEntry({
          accessToken,
          entry: action.entry,
          workerGroupId: action.workerGroupId,
          workerId: action.workerId,
        })
      }
      if (action.kind === "pause") {
        return pauseTaskBoardEntry(accessToken, action.entry)
      }
      if (action.kind === "resume") {
        return resumeTaskBoardEntry(accessToken, action.entry)
      }
      return completeTaskBoardEntry(accessToken, action.entry)
    },
    onMutate: () => setNotice(null),
    onSuccess: async () => {
      setError(null)
      setTakeEntry(null)
      setCompleteEntry(null)
      await invalidateTaskBoard()
    },
    onError: async (unknownError) => {
      setNotice(null)
      setError(errorMessage(unknownError, "Не удалось изменить этап"))
      await invalidateTaskBoard()
    },
  })
  const pinMutation = useMutation({
    mutationFn: (params: { entry: TaskBoardEntryDto; pinned: boolean }) => {
      if (!accessToken) {
        throw new Error("Не получен токен доступа к доске заданий.")
      }
      return pinTaskBoardEntry({ accessToken, ...params })
    },
    onMutate: () => setNotice(null),
    onSuccess: async () => {
      setError(null)
      setNotice("Закрепление задания сохранено.")
      await invalidateTaskBoard()
    },
    onError: async (unknownError) => {
      setNotice(null)
      setError(
        errorMessage(unknownError, "Не удалось изменить закрепление задания")
      )
      await invalidateTaskBoard()
    },
  })

  const busy = actionMutation.isPending || pinMutation.isPending

  return (
    <div className="flex h-full min-h-0 flex-col gap-3 overflow-hidden">
      <PageToolbar>
        <PageToolbarContent className="max-w-xl">
          <InputGroup>
            <InputGroupAddon>
              <HugeiconsIcon icon={Search01Icon} />
            </InputGroupAddon>
            <InputGroupInput
              value={search}
              aria-label="Поиск по доске задач"
              name="task-board-search"
              autoComplete="off"
              placeholder="Бытовка, задание или исполнитель"
              onChange={(event) => setSearch(event.target.value)}
            />
          </InputGroup>
        </PageToolbarContent>
        <PageToolbarActions className="w-full sm:w-auto">
          <Field orientation="horizontal" className="h-9 w-auto gap-2">
            <Checkbox
              id="task-board-show-future-subtasks"
              checked={showFutureSubtasks}
              onCheckedChange={(checked) =>
                setShowFutureSubtasks(checked === true)
              }
            />
            <FieldLabel
              htmlFor="task-board-show-future-subtasks"
              className="whitespace-nowrap"
            >
              Отобразить будущие подзадачи
            </FieldLabel>
          </Field>
          <Badge variant="secondary" className="h-9 px-3">
            Показано: {visibleEntryCount}
          </Badge>
          {canEdit ? (
            <Button asChild className="w-full sm:w-auto">
              <Link
                to="/repairs?create=1"
                state={workspaceEntryNavigationOptions.state}
              >
                <HugeiconsIcon icon={Add01Icon} data-icon="inline-start" />
                Создать задание
              </Link>
            </Button>
          ) : (
            <Button className="w-full sm:w-auto" disabled>
              <HugeiconsIcon icon={Add01Icon} data-icon="inline-start" />
              Создать задание
            </Button>
          )}
        </PageToolbarActions>
      </PageToolbar>

      {focusedExternalTaskId || focusedTaskId ? (
        <p
          role="status"
          className="flex flex-wrap items-center gap-2 text-xs text-muted-foreground"
        >
          Открыто связанное задание.
          <Button
            type="button"
            size="sm"
            variant="ghost"
            onClick={() => {
              const next = new URLSearchParams(searchParams)
              next.delete("externalTaskId")
              next.delete("taskId")
              setSearchParams(next, { replace: true })
            }}
          >
            Показать все задания
          </Button>
        </p>
      ) : null}
      {highlightedTaskId ? (
        <p
          role="status"
          className="flex flex-wrap items-center gap-2 text-xs text-muted-foreground"
        >
          Полный путь: {highlightedRouteLabel}.
          <Button
            type="button"
            size="sm"
            variant="ghost"
            onClick={() => setSelectedRouteTaskId(null)}
          >
            Сбросить выделение
          </Button>
        </p>
      ) : null}
      {!accessToken ? (
        <p role="alert" className="text-xs text-destructive">
          Не получен токен доступа к доске заданий.
        </p>
      ) : null}
      {error ? (
        <p role="alert" className="text-xs text-destructive">
          {error}
        </p>
      ) : null}
      {notice ? (
        <p role="status" className="text-xs text-muted-foreground">
          {notice}
        </p>
      ) : null}

      {boardQuery.isLoading ? (
        <div
          className={cn(
            "flex min-h-0 flex-1 gap-3 overflow-hidden",
            isMobile && "flex-col overflow-y-auto"
          )}
        >
          {[0, 1, 2].map((index) => (
            <Skeleton
              key={index}
              className={cn("h-full w-80 shrink-0", isMobile && "h-80 w-full")}
            />
          ))}
        </div>
      ) : boardQuery.isError ? (
        <p role="alert" className="text-xs text-destructive">
          {errorMessage(boardQuery.error, "Не удалось загрузить доску задач.")}
        </p>
      ) : (
        <div
          className={cn(
            "min-h-0 flex-1",
            isMobile
              ? "overflow-x-hidden overflow-y-auto overscroll-y-contain"
              : "touch-pan-x overflow-x-auto overscroll-x-contain"
          )}
        >
          <div
            className={cn(
              "flex items-stretch gap-3 p-px pb-4",
              isMobile
                ? "min-h-full w-full min-w-0 flex-col"
                : "h-full min-w-max"
            )}
          >
            {visibleQueues.map(({ queue, visibleEntries }) => (
              <TaskBoardColumn
                key={queue.key}
                queue={queue}
                visibleEntries={visibleEntries}
                now={now}
                mobile={isMobile}
                canEdit={canEdit}
                collapsed={collapsedQueues.has(queue.key)}
                actionPending={busy || !canEdit}
                queueActionsDisabled={!canEdit || Boolean(normalizedSearch)}
                dailyPlanEntryIds={
                  dailyPlanEntryIdsByQueue.get(queue.key) ?? EMPTY_ENTRY_IDS
                }
                highlightedTaskId={highlightedTaskId}
                palette={taskBoardPalette}
                repairComplexitiesByRepairId={repairComplexitiesByRepairId}
                onToggleCollapsed={(queueKey) =>
                  setCollapsedQueues((current) => {
                    const next = new Set(current)
                    if (next.has(queueKey)) next.delete(queueKey)
                    else next.add(queueKey)
                    return next
                  })
                }
                onDetails={(entry) => {
                  if (entry.source?.type !== "MAINTENANCE_REPAIR") return
                  navigate(
                    `/repairs?repairId=${encodeURIComponent(entry.source.sourceId)}`,
                    workspaceEntryNavigationOptions
                  )
                }}
                onEdit={(entry) => {
                  if (
                    !canEdit ||
                    entry.entryType !== "REAL" ||
                    entry.source?.type !== "MAINTENANCE_REPAIR"
                  ) {
                    return
                  }
                  navigate(
                    `/repairs?repairId=${encodeURIComponent(entry.source.sourceId)}&edit=1`,
                    workspaceEntryNavigationOptions
                  )
                }}
                onTake={(entry) => {
                  if (!canEdit || entry.entryType !== "REAL") return
                  setTakeEntry(entry)
                  setError(null)
                }}
                onPause={(entry) => {
                  if (canEdit && entry.entryType === "REAL") {
                    actionMutation.mutate({ kind: "pause", entry })
                  }
                }}
                onResume={(entry) => {
                  if (canEdit && entry.entryType === "REAL") {
                    actionMutation.mutate({ kind: "resume", entry })
                  }
                }}
                onPin={(entry, pinned) => {
                  if (canEdit && entry.entryType === "REAL") {
                    pinMutation.mutate({ entry, pinned })
                  }
                }}
                onShowFullRoute={toggleFullRoute}
                isEntryCollapsed={isEntryCollapsed}
                onToggleEntryCollapsed={toggleEntryCollapsed}
                onComplete={(entry) => {
                  if (!canEdit || entry.entryType !== "REAL") return
                  setCompleteEntry(entry)
                  setError(null)
                }}
              />
            ))}
          </div>
        </div>
      )}

      <TaskBoardTakeDialog
        entry={takeEntry}
        pending={actionMutation.isPending}
        error={takeEntry ? error : null}
        onOpenChange={(open) => {
          if (!open) {
            setTakeEntry(null)
            setError(null)
          }
        }}
        onTake={({ workerGroupId, workerId }) => {
          if (!canEdit || !takeEntry) return
          actionMutation.mutate({
            kind: "take",
            entry: takeEntry,
            workerGroupId,
            workerId,
          })
        }}
      />
      <TaskBoardCompletionDialog
        entry={completeEntry}
        pending={actionMutation.isPending}
        error={completeEntry ? error : null}
        onOpenChange={(open) => {
          if (!open) {
            setCompleteEntry(null)
            setError(null)
          }
        }}
        onComplete={() => {
          if (!canEdit || !completeEntry) return
          actionMutation.mutate({ kind: "complete", entry: completeEntry })
        }}
      />
    </div>
  )
}
