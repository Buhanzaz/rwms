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
import {
  Dialog,
  DialogContent,
  DialogDescription,
  DialogFooter,
  DialogHeader,
  DialogTitle,
} from "@/components/ui/dialog"
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
  reorderTaskBoardEntry,
  restoreTaskBoardTask,
  resumeTaskBoardEntry,
  setFutureTaskBoardEntryAvailability,
  takeTaskBoardEntry,
  suspendTaskBoardTask,
  TASK_BOARD_QUERY_KEY,
  taskBoardQueryKey,
  updateTaskBoardWorkerPlan,
} from "@/features/task-board/api/task-board-api"
import { mergeQueueCollapsedSettings } from "@/features/task-board/domain/task-board-domain"
import {
  nextTaskTimerTransitionAt,
  paletteForTaskBoard,
} from "@/features/task-board/domain/task-board-kpi-presentation"
import type {
  TaskBoardEntryDto,
  TaskBoardQueueDto,
  TaskBoardSnapshotDto,
  TaskBoardWorkerPlanDto,
} from "@/features/task-board/model/task-board"
import type { TaskBoardRepairComplexity } from "@/features/task-board/task-board-card"
import { TaskBoardColumn } from "@/features/task-board/task-board-column"
import { TaskBoardCompletionDialog } from "@/features/task-board/task-board-completion-dialog"
import { TaskBoardTakeDialog } from "@/features/task-board/task-board-take-dialog"
import {
  readTaskBoardViewPreferences,
  writeTaskBoardViewPreferences,
  type TaskBoardViewPreferences,
} from "@/features/task-board/task-board-view-preferences"
import { useIsMobile } from "@/hooks/use-mobile"
import { useWarehouse } from "@/hooks/use-warehouse"
import { workspaceEntryNavigationOptions } from "@/hooks/use-workspace-back"
import { cn } from "@/lib/utils"
import {
  getTaskRequirements,
  taskRequirementsQueryKey,
  type TaskRequirement,
} from "@/features/task-board/api/task-requirements-api"

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

type SuspensionAction =
  | { kind: "suspend"; entry: TaskBoardEntryDto }
  | {
      kind: "restore"
      entry: TaskBoardEntryDto
      expectedTaskVersion: number
      availableItemIds: readonly string[]
    }

function errorMessage(error: unknown, fallback: string) {
  return error instanceof Error ? error.message : fallback
}

const ACTIVE_REPAIR_PAGE_SIZE = 200
const EMPTY_ENTRY_IDS: ReadonlySet<string> = new Set()

/** Selects precisely the missing connected component; available/completed entries are never sent. */
function missingRequirementGroup(
  items: readonly TaskRequirement[],
  itemId: string
) {
  const byId = new Map(items.map((item) => [item.itemId, item]))
  const result = new Set<string>()
  const pending = [itemId]
  while (pending.length) {
    const currentId = pending.pop()!
    const current = byId.get(currentId)
    if (!current || current.state !== "MISSING" || result.has(currentId))
      continue
    result.add(currentId)
    current.linkedItemIds.forEach((linkedId) => pending.push(linkedId))
  }
  return result
}

function withWorkerPlan(
  board: TaskBoardSnapshotDto,
  queueKey: string,
  plan: Pick<
    TaskBoardWorkerPlanDto,
    "version" | "workerFeedEnabled" | "availableTaskLimit"
  >
) {
  return {
    ...board,
    queues: board.queues.map((queue) =>
      queue.key === queueKey
        ? {
            ...queue,
            version: plan.version,
            workerFeedEnabled: plan.workerFeedEnabled,
            availableTaskLimit: plan.availableTaskLimit,
          }
        : queue
    ),
  }
}

function withReorderedEntry(
  board: TaskBoardSnapshotDto,
  queueKey: string,
  entryId: string,
  targetIndex: number
) {
  return {
    ...board,
    queues: board.queues.map((queue) => {
      if (queue.key !== queueKey) return queue
      const reorderablePositions = queue.entries.flatMap((entry, index) =>
        entry.entryType === "REAL" &&
        entry.status === "WAITING" &&
        !entry.suspended &&
        !entry.pinned
          ? [index]
          : []
      )
      const reorderableEntries = reorderablePositions.map(
        (index) => queue.entries[index]!
      )
      const sourceIndex = reorderableEntries.findIndex(
        (entry) => entry.id === entryId
      )
      if (
        sourceIndex < 0 ||
        targetIndex < 0 ||
        targetIndex >= reorderableEntries.length ||
        sourceIndex === targetIndex
      ) {
        return queue
      }
      const reorderedEntries = [...reorderableEntries]
      const [movedEntry] = reorderedEntries.splice(sourceIndex, 1)
      reorderedEntries.splice(targetIndex, 0, movedEntry!)
      const entries = [...queue.entries]
      reorderablePositions.forEach((position, index) => {
        entries[position] = reorderedEntries[index]!
      })
      return { ...queue, entries }
    }),
  }
}

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
  const { selectedWarehouse } = useWarehouse()
  return (
    <TaskBoardWarehousePage key={selectedWarehouse?.id ?? "no-warehouse"} />
  )
}

/** Owns one warehouse-scoped board session so local view state resets on warehouse changes. */
function TaskBoardWarehousePage() {
  const isMobile = useIsMobile()
  const navigate = useNavigate()
  const [searchParams, setSearchParams] = useSearchParams()
  const queryClient = useQueryClient()
  const { accessToken, currentUser } = useAuth()
  const { selectedWarehouse } = useWarehouse()
  const warehouseId = selectedWarehouse?.id ?? null
  const [initialViewPreferences] = useState<TaskBoardViewPreferences | null>(
    () => (warehouseId ? readTaskBoardViewPreferences(warehouseId) : null)
  )
  const canEdit = Boolean(
    warehouseId && hasWarehouseAccess(currentUser, warehouseId, "EDIT")
  )
  const canManage = Boolean(
    warehouseId && hasWarehouseAccess(currentUser, warehouseId, "MANAGE")
  )
  const [search, setSearch] = useState("")
  const [showFutureSubtasks, setShowFutureSubtasks] = useState(
    initialViewPreferences?.showFutureSubtasks ?? false
  )
  const [selectedRouteTaskId, setSelectedRouteTaskId] = useState<string | null>(
    null
  )
  const [collapsedQueues, setCollapsedQueues] = useState<Set<string>>(
    () => new Set(initialViewPreferences?.collapsedQueueKeys ?? [])
  )
  const [entryCollapseStates, setEntryCollapseStates] = useState<
    Map<string, boolean>
  >(() => new Map())
  const [takeEntry, setTakeEntry] = useState<TaskBoardEntryDto | null>(null)
  const [completeEntry, setCompleteEntry] = useState<TaskBoardEntryDto | null>(
    null
  )
  const [restoreEntry, setRestoreEntry] = useState<TaskBoardEntryDto | null>(
    null
  )
  const [requirementsEntry, setRequirementsEntry] =
    useState<TaskBoardEntryDto | null>(null)
  const [availableItemIds, setAvailableItemIds] = useState<Set<string>>(
    () => new Set()
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
  const fullRouteCollapsedQueuesRef = useRef<Set<string> | null>(null)
  const collapsedPreferencesReadyRef = useRef<string | null>(null)
  const boardScrollRef = useRef<HTMLDivElement | null>(null)
  const boardScrollPositionRef = useRef({
    left: initialViewPreferences?.boardScrollLeft ?? 0,
    top: initialViewPreferences?.boardScrollTop ?? 0,
  })
  const queueScrollTopsRef = useRef<Map<string, number>>(
    new Map(Object.entries(initialViewPreferences?.queueScrollTops ?? {}))
  )
  const restoredBoardScrollWarehouseRef = useRef<string | null>(null)
  const scrollPersistTimerRef = useRef<number | null>(null)
  const latestViewStateRef = useRef({
    warehouseId,
    showFutureSubtasks,
    collapsedQueues,
  })

  const writeLatestViewPreferences = useCallback(() => {
    const latest = latestViewStateRef.current
    if (
      !latest.warehouseId ||
      collapsedPreferencesReadyRef.current !== latest.warehouseId
    ) {
      return
    }
    writeTaskBoardViewPreferences(latest.warehouseId, {
      showFutureSubtasks: latest.showFutureSubtasks,
      collapsedQueueKeys: [...latest.collapsedQueues],
      boardScrollLeft: boardScrollPositionRef.current.left,
      boardScrollTop: boardScrollPositionRef.current.top,
      queueScrollTops: Object.fromEntries(queueScrollTopsRef.current),
    })
  }, [])

  const scheduleScrollPreferenceWrite = useCallback(() => {
    if (scrollPersistTimerRef.current !== null) {
      window.clearTimeout(scrollPersistTimerRef.current)
    }
    scrollPersistTimerRef.current = window.setTimeout(() => {
      scrollPersistTimerRef.current = null
      writeLatestViewPreferences()
    }, 100)
  }, [writeLatestViewPreferences])

  useEffect(() => {
    latestViewStateRef.current = {
      warehouseId,
      showFutureSubtasks,
      collapsedQueues,
    }
  }, [collapsedQueues, showFutureSubtasks, warehouseId])

  useEffect(() => {
    if (collapsedPreferencesReadyRef.current === warehouseId) {
      writeLatestViewPreferences()
    }
  }, [
    collapsedQueues,
    showFutureSubtasks,
    warehouseId,
    writeLatestViewPreferences,
  ])

  useEffect(
    () => () => {
      if (scrollPersistTimerRef.current !== null) {
        window.clearTimeout(scrollPersistTimerRef.current)
        scrollPersistTimerRef.current = null
      }
      writeLatestViewPreferences()
    },
    [writeLatestViewPreferences]
  )

  const boardQuery = useQuery({
    queryKey: taskBoardQueryKey(warehouseId ?? "none"),
    queryFn: () => getTaskBoard(accessToken!, warehouseId!),
    enabled: Boolean(accessToken && warehouseId),
  })
  const requirementEntry = restoreEntry ?? requirementsEntry
  const requirementsQuery = useQuery({
    queryKey: taskRequirementsQueryKey(
      warehouseId ?? "none",
      requirementEntry?.taskId ?? "none"
    ),
    queryFn: () =>
      getTaskRequirements(accessToken!, warehouseId!, requirementEntry!.taskId),
    enabled: Boolean(accessToken && warehouseId && requirementEntry),
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
                  entry.entryType === "REAL" &&
                  entry.status === "WAITING" &&
                  !entry.suspended
              )
              .slice(0, queue.availableTaskLimit)
              .map((entry) => entry.id)
          ),
        ])
      ),
    [boardQuery.data]
  )
  const futureEntryIds = useMemo(() => {
    const entries =
      boardQuery.data?.queues.flatMap((queue) => queue.entries) ?? []
    const earliestRouteIndexByTask = new Map<string, number>()
    entries.forEach((entry) => {
      earliestRouteIndexByTask.set(
        entry.taskId,
        Math.min(
          earliestRouteIndexByTask.get(entry.taskId) ?? entry.routeIndex,
          entry.routeIndex
        )
      )
    })
    return new Set(
      entries
        .filter(
          (entry) =>
            entry.status === "WAITING" &&
            entry.routeIndex >
              (earliestRouteIndexByTask.get(entry.taskId) ?? entry.routeIndex)
        )
        .map((entry) => entry.id)
    )
  }, [boardQuery.data])
  const highlightedRouteLabel = useMemo(() => {
    if (!highlightedTaskId) return null
    const entry = boardQuery.data?.queues
      .flatMap((queue) => queue.entries)
      .find((candidate) => candidate.taskId === highlightedTaskId)
    return entry?.unitNumber ?? entry?.title ?? "выбранной бытовки"
  }, [boardQuery.data, highlightedTaskId])
  const kpiSettingsQuery = useQuery({
    queryKey: kpiSettingsKeys.settings,
    queryFn: () => getKpiSettings(accessToken!),
    enabled: Boolean(accessToken && warehouseId),
    staleTime: 60_000,
    refetchInterval: 30_000,
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
    refetchInterval: 30_000,
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

  const clearFullRoute = useCallback(() => {
    const collapsedBeforeRoute = fullRouteCollapsedQueuesRef.current
    fullRouteCollapsedQueuesRef.current = null
    setSelectedRouteTaskId(null)
    if (collapsedBeforeRoute) {
      setCollapsedQueues(new Set(collapsedBeforeRoute))
    }
  }, [])

  const toggleFullRoute = useCallback(
    (entry: TaskBoardEntryDto) => {
      if (selectedRouteTaskId === entry.taskId) {
        clearFullRoute()
        return
      }
      if (!selectedRouteTaskId) {
        fullRouteCollapsedQueuesRef.current = new Set(collapsedQueues)
      }
      setSelectedRouteTaskId(entry.taskId)
      const routeQueueKeys = new Set(
        (boardQuery.data?.queues ?? [])
          .filter((queue) =>
            queue.entries.some((candidate) => candidate.taskId === entry.taskId)
          )
          .map((queue) => queue.key)
      )
      setCollapsedQueues((current) => {
        const next = new Set(current)
        routeQueueKeys.forEach((queueKey) => next.delete(queueKey))
        return next
      })
    },
    [boardQuery.data, clearFullRoute, collapsedQueues, selectedRouteTaskId]
  )

  useEffect(() => {
    if (!warehouseId || !boardQuery.data) {
      return
    }
    const previous = collapsedSettingsRef.current
    const persistedQueueKeys =
      initialViewPreferences?.collapsedQueueKeys ?? null
    const firstWarehouseMerge = previous?.warehouseId !== warehouseId
    const merged =
      firstWarehouseMerge && persistedQueueKeys !== null
        ? {
            collapsed: new Set(
              persistedQueueKeys.filter((queueKey) =>
                boardQuery.data.queues.some((queue) => queue.key === queueKey)
              )
            ),
            settings: new Map(
              boardQuery.data.queues.map((queue) => [
                queue.key,
                queue.settingsCollapsed,
              ])
            ),
          }
        : mergeQueueCollapsedSettings({
            current: collapsedQueues,
            previous:
              previous && previous.warehouseId === warehouseId
                ? previous.values
                : null,
            queues: boardQuery.data.queues,
            reset: firstWarehouseMerge,
          })
    collapsedSettingsRef.current = { warehouseId, values: merged.settings }
    collapsedPreferencesReadyRef.current = warehouseId
    if (
      merged.collapsed.size !== collapsedQueues.size ||
      [...merged.collapsed].some((queueKey) => !collapsedQueues.has(queueKey))
    ) {
      setCollapsedQueues(merged.collapsed)
    }
  }, [boardQuery.data, collapsedQueues, initialViewPreferences, warehouseId])

  useEffect(() => {
    if (
      !warehouseId ||
      !boardQuery.data ||
      restoredBoardScrollWarehouseRef.current === warehouseId
    ) {
      return
    }
    const frame = window.requestAnimationFrame(() => {
      const scroll = boardScrollRef.current
      if (!scroll) return
      scroll.scrollLeft = boardScrollPositionRef.current.left
      scroll.scrollTop = boardScrollPositionRef.current.top
      restoredBoardScrollWarehouseRef.current = warehouseId
    })
    return () => window.cancelAnimationFrame(frame)
  }, [boardQuery.data, warehouseId])

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
  const suspensionMutation = useMutation({
    mutationFn: (action: SuspensionAction) => {
      if (!accessToken) {
        throw new Error("Не получен токен доступа к доске заданий.")
      }
      return action.kind === "suspend"
        ? suspendTaskBoardTask(accessToken, action.entry)
        : restoreTaskBoardTask(
            accessToken,
            { ...action.entry, taskVersion: action.expectedTaskVersion },
            action.availableItemIds
          )
    },
    onMutate: async (action) => {
      setNotice(null)
      setError(null)
      await queryClient.cancelQueries({
        queryKey: taskBoardQueryKey(action.entry.warehouseId),
        exact: true,
      })
    },
    onSuccess: (snapshot, action) => {
      setError(null)
      setNotice(
        action.kind === "suspend"
          ? "Задание временно отключено, исполнители освобождены."
          : "Задание восстановлено."
      )
      queryClient.setQueryData(
        taskBoardQueryKey(action.entry.warehouseId),
        snapshot
      )
      if (action.kind === "restore") {
        setRestoreEntry(null)
        void queryClient.invalidateQueries({
          queryKey: taskRequirementsQueryKey(
            action.entry.warehouseId,
            action.entry.taskId
          ),
          exact: true,
        })
      }
    },
    onError: async (unknownError, action) => {
      setNotice(null)
      setError(
        errorMessage(
          unknownError,
          action.kind === "suspend"
            ? "Не удалось временно отключить задание"
            : "Не удалось восстановить задание"
        )
      )
      await queryClient.invalidateQueries({
        queryKey: taskBoardQueryKey(action.entry.warehouseId),
        exact: true,
      })
      if (action.kind === "restore") {
        await queryClient.invalidateQueries({
          queryKey: taskRequirementsQueryKey(
            action.entry.warehouseId,
            action.entry.taskId
          ),
          exact: true,
        })
      }
    },
  })
  const workerPlanMutation = useMutation({
    mutationFn: (params: {
      warehouseId: string
      queue: TaskBoardQueueDto
      workerFeedEnabled: boolean
      availableTaskLimit: number
    }) => {
      if (!accessToken) {
        throw new Error("Не получен токен доступа к доске заданий.")
      }
      return updateTaskBoardWorkerPlan({ accessToken, ...params })
    },
    onMutate: async (params) => {
      setNotice(null)
      setError(null)
      const queryKey = taskBoardQueryKey(params.warehouseId)
      await queryClient.cancelQueries({ queryKey, exact: true })
      const previous = queryClient.getQueryData<TaskBoardSnapshotDto>(queryKey)
      if (previous) {
        queryClient.setQueryData(
          queryKey,
          withWorkerPlan(previous, params.queue.key, {
            version: params.queue.version,
            workerFeedEnabled: params.workerFeedEnabled,
            availableTaskLimit: params.availableTaskLimit,
          })
        )
      }
      return { previous, queryKey }
    },
    onSuccess: (plan, params) => {
      setError(null)
      setNotice("Настройки очереди для WorkerApp сохранены.")
      queryClient.setQueryData<TaskBoardSnapshotDto>(
        taskBoardQueryKey(params.warehouseId),
        (current) =>
          current ? withWorkerPlan(current, params.queue.key, plan) : current
      )
    },
    onError: async (unknownError, _params, context) => {
      setNotice(null)
      setError(
        errorMessage(
          unknownError,
          "Не удалось сохранить настройки очереди для WorkerApp"
        )
      )
      if (context?.previous) {
        queryClient.setQueryData(context.queryKey, context.previous)
      }
      await queryClient.invalidateQueries({
        queryKey: context?.queryKey ?? TASK_BOARD_QUERY_KEY,
      })
    },
  })
  const reorderMutation = useMutation({
    mutationFn: (params: {
      queue: TaskBoardQueueDto
      entry: TaskBoardEntryDto
      targetEntryId: string
      targetIndex: number
    }) => {
      if (!accessToken) {
        throw new Error("Не получен токен доступа к доске заданий.")
      }
      return reorderTaskBoardEntry({ accessToken, ...params })
    },
    onMutate: async (params) => {
      setNotice(null)
      setError(null)
      const queryKey = taskBoardQueryKey(params.entry.warehouseId)
      await queryClient.cancelQueries({ queryKey, exact: true })
      const previous = queryClient.getQueryData<TaskBoardSnapshotDto>(queryKey)
      if (previous) {
        queryClient.setQueryData(
          queryKey,
          withReorderedEntry(
            previous,
            params.queue.key,
            params.entry.id,
            params.targetIndex
          )
        )
      }
      return { previous, queryKey }
    },
    onSuccess: (snapshot, params) => {
      setError(null)
      setNotice("Очередность заданий сохранена.")
      queryClient.setQueryData(
        taskBoardQueryKey(params.entry.warehouseId),
        snapshot
      )
    },
    onError: async (unknownError, _params, context) => {
      setNotice(null)
      setError(
        errorMessage(unknownError, "Не удалось изменить очередь заданий")
      )
      if (context?.previous) {
        queryClient.setQueryData(context.queryKey, context.previous)
      }
      await queryClient.invalidateQueries({
        queryKey: context?.queryKey ?? TASK_BOARD_QUERY_KEY,
      })
    },
  })
  const futureAvailabilityMutation = useMutation({
    mutationFn: (params: { entry: TaskBoardEntryDto; available: boolean }) => {
      if (!accessToken) {
        throw new Error("Не получен токен доступа к доске заданий.")
      }
      return setFutureTaskBoardEntryAvailability({ accessToken, ...params })
    },
    onMutate: () => {
      setNotice(null)
      setError(null)
    },
    onSuccess: (snapshot, params) => {
      setNotice(
        params.available
          ? "Будущий этап доступен рабочим."
          : "Будущий этап снова скрыт от рабочих."
      )
      queryClient.setQueryData(
        taskBoardQueryKey(params.entry.warehouseId),
        snapshot
      )
    },
    onError: async (unknownError, params) => {
      setNotice(null)
      setError(
        errorMessage(
          unknownError,
          "Не удалось изменить доступность будущего этапа"
        )
      )
      await queryClient.invalidateQueries({
        queryKey: taskBoardQueryKey(params.entry.warehouseId),
        exact: true,
      })
    },
  })

  const busy =
    actionMutation.isPending ||
    pinMutation.isPending ||
    suspensionMutation.isPending ||
    workerPlanMutation.isPending ||
    reorderMutation.isPending ||
    futureAvailabilityMutation.isPending
  const reorderDisabled =
    isMobile ||
    busy ||
    !canEdit ||
    Boolean(
      normalizedSearch ||
      focusedExternalTaskId ||
      focusedTaskId ||
      showFutureSubtasks ||
      highlightedTaskId
    )

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
            onClick={clearFullRoute}
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
          ref={boardScrollRef}
          data-slot="task-board-scroll"
          className={cn(
            "min-h-0 flex-1",
            isMobile
              ? "overflow-x-hidden overflow-y-auto overscroll-y-contain"
              : "touch-pan-x overflow-x-auto overscroll-x-contain"
          )}
          onScroll={(event) => {
            boardScrollPositionRef.current = {
              left: event.currentTarget.scrollLeft,
              top: event.currentTarget.scrollTop,
            }
            scheduleScrollPreferenceWrite()
          }}
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
                canManage={canManage}
                collapsed={collapsedQueues.has(queue.key)}
                actionPending={busy || !canEdit}
                configPending={busy}
                queueActionsDisabled={!canEdit || Boolean(normalizedSearch)}
                reorderDisabled={reorderDisabled}
                dailyPlanEntryIds={
                  dailyPlanEntryIdsByQueue.get(queue.key) ?? EMPTY_ENTRY_IDS
                }
                futureEntryIds={futureEntryIds}
                highlightedTaskId={highlightedTaskId}
                initialScrollTop={
                  initialViewPreferences?.queueScrollTops[queue.key] ?? 0
                }
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
                onUpdateWorkerPlan={(
                  currentQueue,
                  workerFeedEnabled,
                  availableTaskLimit
                ) => {
                  if (!canManage || !warehouseId) return
                  workerPlanMutation.mutate({
                    warehouseId,
                    queue: currentQueue,
                    workerFeedEnabled,
                    availableTaskLimit,
                  })
                }}
                onReorder={(entry, targetIndex) => {
                  if (reorderDisabled) return
                  const targetEntryId = queue.entries
                    .filter(
                      (candidate) =>
                        candidate.entryType === "REAL" &&
                        candidate.status === "WAITING" &&
                        !candidate.suspended &&
                        !candidate.pinned
                    )
                    .at(targetIndex)?.id
                  if (!targetEntryId) return
                  reorderMutation.mutate({
                    queue,
                    entry,
                    targetEntryId,
                    targetIndex,
                  })
                }}
                onDetails={(entry) => {
                  if (entry.source?.type !== "MAINTENANCE_REPAIR") return
                  navigate(
                    `/repairs?repairId=${encodeURIComponent(entry.source.sourceId)}`,
                    workspaceEntryNavigationOptions
                  )
                }}
                onRequirements={(entry) => {
                  setRequirementsEntry(entry)
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
                  if (
                    canEdit &&
                    entry.entryType === "REAL" &&
                    !entry.suspended
                  ) {
                    actionMutation.mutate({ kind: "pause", entry })
                  }
                }}
                onResume={(entry) => {
                  if (
                    canEdit &&
                    entry.entryType === "REAL" &&
                    !entry.suspended
                  ) {
                    actionMutation.mutate({ kind: "resume", entry })
                  }
                }}
                onPin={(entry, pinned) => {
                  if (
                    canEdit &&
                    entry.entryType === "REAL" &&
                    !entry.suspended
                  ) {
                    pinMutation.mutate({ entry, pinned })
                  }
                }}
                onFutureAvailabilityChange={(entry, available) => {
                  if (
                    !canEdit ||
                    entry.suspended ||
                    !futureEntryIds.has(entry.id)
                  )
                    return
                  futureAvailabilityMutation.mutate({ entry, available })
                }}
                onSuspend={(entry) => {
                  if (
                    canEdit &&
                    entry.entryType === "REAL" &&
                    !entry.suspended &&
                    (entry.status === "IN_PROGRESS" ||
                      entry.status === "PAUSED")
                  ) {
                    suspensionMutation.mutate({ kind: "suspend", entry })
                  }
                }}
                onRestore={(entry) => {
                  if (
                    canEdit &&
                    entry.entryType === "REAL" &&
                    entry.suspended
                  ) {
                    setAvailableItemIds(new Set())
                    setRestoreEntry(entry)
                  }
                }}
                onScrollTopChange={(queueKey, scrollTop) => {
                  queueScrollTopsRef.current.set(queueKey, scrollTop)
                  scheduleScrollPreferenceWrite()
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
      <Dialog
        open={restoreEntry !== null}
        onOpenChange={(open) => {
          if (!open && !suspensionMutation.isPending) {
            setRestoreEntry(null)
            setAvailableItemIds(new Set())
            setError(null)
          }
        }}
      >
        <DialogContent>
          <DialogHeader>
            <DialogTitle>Восстановить задание</DialogTitle>
            <DialogDescription>
              Отметьте появившиеся позиции. Связанные позиции выбираются вместе
              — сервер принимает только полный набор.
            </DialogDescription>
          </DialogHeader>
          {requirementsQuery.isLoading ? (
            <Skeleton className="h-20 w-full" />
          ) : requirementsQuery.data ? (
            <div className="flex flex-col gap-2">
              {requirementsQuery.data.items.map((item) => {
                const selected = availableItemIds.has(item.itemId)
                const linked = missingRequirementGroup(
                  requirementsQuery.data.items,
                  item.itemId
                )
                return (
                  <label
                    key={item.itemId}
                    className={cn(
                      "flex items-center gap-2 rounded-md border p-2",
                      item.state === "MISSING" &&
                        "border-destructive bg-destructive/10",
                      item.state === "RESTORED" &&
                        "border-green-600 bg-green-500/10"
                    )}
                  >
                    <Checkbox
                      checked={selected}
                      disabled={item.state !== "MISSING"}
                      onCheckedChange={(checked) =>
                        setAvailableItemIds((current) => {
                          const next = new Set(current)
                          linked.forEach((id) =>
                            checked ? next.add(id) : next.delete(id)
                          )
                          return next
                        })
                      }
                    />
                    <span className="min-w-0">
                      {item.kind === "WORK" ? "Работа" : "Материал"}:{" "}
                      {item.name}
                      {item.linkedItemIds.length ? " · связанная группа" : ""}
                    </span>
                  </label>
                )
              })}
            </div>
          ) : (
            <div className="flex flex-col gap-2">
              <p role="alert" className="text-destructive">
                {errorMessage(
                  requirementsQuery.error,
                  "Не удалось загрузить требования задания."
                )}
              </p>
              <Button
                type="button"
                variant="outline"
                onClick={() => requirementsQuery.refetch()}
              >
                Повторить
              </Button>
            </div>
          )}
          {restoreEntry && error ? (
            <p role="alert" className="text-sm text-destructive">
              {error}
            </p>
          ) : null}
          <DialogFooter>
            <Button
              type="button"
              variant="outline"
              disabled={suspensionMutation.isPending}
              onClick={() => {
                setRestoreEntry(null)
                setAvailableItemIds(new Set())
                setError(null)
              }}
            >
              Отмена
            </Button>
            <Button
              type="button"
              disabled={
                !restoreEntry ||
                !requirementsQuery.data ||
                suspensionMutation.isPending
              }
              onClick={() => {
                if (restoreEntry && requirementsQuery.data)
                  suspensionMutation.mutate({
                    kind: "restore",
                    entry: restoreEntry,
                    expectedTaskVersion: requirementsQuery.data.taskVersion,
                    availableItemIds: [...availableItemIds],
                  })
              }}
            >
              Восстановить
            </Button>
          </DialogFooter>
        </DialogContent>
      </Dialog>
      <Dialog
        open={requirementsEntry !== null}
        onOpenChange={(open) => {
          if (!open) setRequirementsEntry(null)
        }}
      >
        <DialogContent>
          <DialogHeader>
            <DialogTitle>Требования задания</DialogTitle>
            <DialogDescription>
              Состояния работ и материалов задаёт сервер.
            </DialogDescription>
          </DialogHeader>
          {requirementsQuery.isLoading ? (
            <Skeleton className="h-20 w-full" />
          ) : requirementsQuery.data ? (
            <div className="flex flex-col gap-2">
              {requirementsQuery.data.items.map((item) => (
                <div
                  key={item.itemId}
                  className={cn(
                    "rounded-md border p-2 text-sm",
                    item.state === "MISSING" &&
                      "border-destructive bg-destructive/10 text-destructive",
                    item.state === "RESTORED" &&
                      "border-green-600 bg-green-500/10 text-green-700 dark:text-green-400"
                  )}
                >
                  <span className="font-medium">
                    {item.kind === "WORK" ? "Работа" : "Материал"}:
                  </span>{" "}
                  {item.name}
                </div>
              ))}
            </div>
          ) : (
            <div className="flex flex-col gap-2">
              <p role="alert" className="text-destructive">
                {errorMessage(
                  requirementsQuery.error,
                  "Не удалось загрузить требования задания."
                )}
              </p>
              <Button
                type="button"
                variant="outline"
                onClick={() => requirementsQuery.refetch()}
              >
                Повторить
              </Button>
            </div>
          )}
          <DialogFooter>
            <Button type="button" onClick={() => setRequirementsEntry(null)}>
              Закрыть
            </Button>
          </DialogFooter>
        </DialogContent>
      </Dialog>
    </div>
  )
}
