import { type CSSProperties, useEffect, useMemo, useState } from "react"
import { useQuery } from "@tanstack/react-query"

import { Alert, AlertDescription, AlertTitle } from "@/components/ui/alert"
import {
  Card,
  CardContent,
  CardDescription,
  CardHeader,
  CardTitle,
} from "@/components/ui/card"
import { Skeleton } from "@/components/ui/skeleton"
import {
  Tooltip,
  TooltipContent,
  TooltipProvider,
  TooltipTrigger,
} from "@/components/ui/tooltip"
import { hasWarehouseAccess } from "@/features/auth/warehouse-access"
import { useAuth } from "@/features/auth/use-auth"
import { listKpiWorkerGroups, kpiKeys } from "@/features/kpi/api/kpi-api"
import {
  getKpiSettings,
  kpiSettingsKeys,
} from "@/features/settings/kpi/api/kpi-settings-api"
import {
  listMaintenanceRepairs,
  type MaintenanceRepair,
} from "@/features/repair-estimates/api/http-maintenance-lifecycle-client"
import {
  dailyBrigadeRows,
  dailyShiftTimelineAt,
  remainingPercentLabel,
  type DailyBrigadeRow,
  type DailyBrigadeTask,
  type DailyShiftTimeline,
} from "@/features/home/daily-brigade-timeline"
import {
  dailyBrigadeActivityQueryKey,
  getDailyBrigadeActivity,
} from "@/features/home/daily-brigade-activity-api"
import { useWarehouse } from "@/hooks/use-warehouse"
import {
  getTaskBoard,
  taskBoardQueryKey,
} from "@/features/task-board/api/task-board-api"
import { paletteForTaskBoard } from "@/features/task-board/domain/task-board-kpi-presentation"
import { cn } from "@/lib/utils"

const ACTIVE_REPAIR_PAGE_SIZE = 200
const TASK_BOARD_REFRESH_INTERVAL_MS = 30_000

/** The minimal maintenance projection required by the Home task envelope. */
type RepairComplexity = Pick<MaintenanceRepair["complexity"], "name">

function errorMessage(error: unknown, fallback: string) {
  return error instanceof Error && error.message.trim()
    ? error.message
    : fallback
}

function useCurrentTime() {
  const [now, setNow] = useState(() => Date.now())

  useEffect(() => {
    const timer = window.setInterval(() => setNow(Date.now()), 1_000)
    return () => window.clearInterval(timer)
  }, [])

  return now
}

/** Loads complexity only for the current maintenance tasks represented on the dashboard. */
async function loadActiveRepairComplexities(
  accessToken: string,
  warehouseId: string,
  repairIds: readonly string[]
): Promise<MaintenanceRepair[]> {
  const matches: MaintenanceRepair[] = []
  for (
    let offset = 0;
    offset < repairIds.length;
    offset += ACTIVE_REPAIR_PAGE_SIZE
  ) {
    const sourceIds = repairIds.slice(offset, offset + ACTIVE_REPAIR_PAGE_SIZE)
    const response = await listMaintenanceRepairs(accessToken, warehouseId, {
      repairIds: sourceIds,
      page: 0,
      size: ACTIVE_REPAIR_PAGE_SIZE,
    })
    if (
      response.page !== 0 ||
      response.size !== ACTIVE_REPAIR_PAGE_SIZE ||
      response.totalElements > sourceIds.length ||
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

function taskDescription(task: DailyBrigadeTask) {
  const text = task.activity.taskText?.trim()
  if (text) {
    return text.replace(/\s*Материалы\s*:\s*[\s\S]*$/iu, "").trim() || text
  }
  return task.activity.title
}

function repairComplexityLabel({
  task,
  repairComplexities,
  unavailable,
}: {
  task: DailyBrigadeTask
  repairComplexities: ReadonlyMap<string, RepairComplexity>
  unavailable: boolean
}) {
  if (task.activity.source?.type !== "MAINTENANCE_REPAIR") {
    return "Не относится к ремонту"
  }
  if (unavailable) return "Недоступно"
  return (
    repairComplexities.get(task.activity.source.sourceId)?.name ?? "Не указана"
  )
}

function TaskTooltip({
  task,
  repairComplexities,
  repairComplexitiesUnavailable,
}: {
  task: DailyBrigadeTask
  repairComplexities: ReadonlyMap<string, RepairComplexity>
  repairComplexitiesUnavailable: boolean
}) {
  return (
    <TooltipContent
      side="top"
      sideOffset={8}
      className="w-72 max-w-[calc(100vw-2rem)]"
    >
      <div className="flex min-w-0 flex-col gap-2">
        <p className="truncate font-medium">{taskDescription(task)}</p>
        <dl className="grid grid-cols-[auto_minmax(0,1fr)] gap-x-2 gap-y-1 text-background/70">
          <dt>Бытовка</dt>
          <dd className="min-w-0 truncate text-background">
            {task.activity.unitNumber ?? "Не указана"}
          </dd>
          <dt>Этап</dt>
          <dd className="min-w-0 truncate text-background">{task.stage}</dd>
          <dt>Начало</dt>
          <dd className="text-background">{task.startTime}</dd>
          <dt>Окончание</dt>
          <dd className="text-background">
            {task.activity.finishedAt
              ? task.endTime
              : `сейчас, ${task.endTime}`}
          </dd>
          <dt>Сложность</dt>
          <dd className="min-w-0 truncate text-background">
            {repairComplexityLabel({
              task,
              repairComplexities,
              unavailable: repairComplexitiesUnavailable,
            })}
          </dd>
          <dt>Приоритет</dt>
          <dd className="text-background">{task.activity.priority} из 5</dd>
          {task.entry ? (
            <>
              <dt>Осталось</dt>
              <dd className="text-background">
                {remainingPercentLabel(task.remainingPercent)}
              </dd>
            </>
          ) : null}
        </dl>
      </div>
    </TooltipContent>
  )
}

function DailyBrigadeTimeline({
  row,
  timeline,
  repairComplexities,
  repairComplexitiesUnavailable,
}: {
  row: DailyBrigadeRow
  timeline: DailyShiftTimeline
  repairComplexities: ReadonlyMap<string, RepairComplexity>
  repairComplexitiesUnavailable: boolean
}) {
  const accessibleLabel =
    row.tasks.length > 0
      ? `${row.name}: заданий за день ${row.tasks.length}. Текущее время ${timeline.currentTime}.`
      : `${row.name}: нет заданий за день. Текущее время ${timeline.currentTime}.`

  return (
    <li
      className="flex flex-col gap-3"
      data-testid={`daily-brigade-row-${row.id}`}
    >
      <p className="font-medium">{row.name}</p>
      <div className="flex items-center gap-3">
        <span className="shrink-0 text-xs text-muted-foreground tabular-nums">
          {timeline.shiftStart}
        </span>
        <div
          data-testid={`daily-brigade-track-${row.id}`}
          className="relative mt-7 h-4 min-w-0 flex-1 rounded-full bg-muted"
          aria-label={accessibleLabel}
        >
          {row.tasks.map((task, index) => {
            const colorStyle = {
              left: `${task.startPercent}%`,
              width: `${task.widthPercent}%`,
              ...(task.color
                ? { "--daily-brigade-kpi-color": task.color }
                : {}),
            } as CSSProperties
            const finishLabel = task.activity.finishedAt
              ? task.endTime
              : `сейчас ${task.endTime}`
            return (
              <Tooltip
                key={`${task.activity.entryId}:${task.activity.startedAt}`}
              >
                <TooltipTrigger asChild>
                  <span
                    data-testid={`daily-brigade-task-fill-${row.id}-${index}`}
                    className={cn(
                      "absolute inset-y-0 z-10 block min-w-1 cursor-default rounded-full ring-offset-background outline-none focus-visible:ring-2 focus-visible:ring-ring focus-visible:ring-offset-2",
                      task.color
                        ? "bg-(--daily-brigade-kpi-color)"
                        : task.activity.finishedAt
                          ? "bg-primary/45"
                          : "bg-muted-foreground/45"
                    )}
                    style={colorStyle}
                    role="img"
                    tabIndex={0}
                    aria-label={`${taskDescription(task)}: ${task.startTime}–${finishLabel}`}
                  />
                </TooltipTrigger>
                <TaskTooltip
                  task={task}
                  repairComplexities={repairComplexities}
                  repairComplexitiesUnavailable={repairComplexitiesUnavailable}
                />
              </Tooltip>
            )
          })}
          <span
            className="pointer-events-none absolute -top-7 z-20 block -translate-x-1/2 rounded bg-foreground px-1.5 py-0.5 text-[10px] leading-none text-background"
            style={{ left: `${timeline.currentTimePercent}%` }}
            aria-hidden="true"
          >
            {timeline.currentTime}
          </span>
          <span
            className="pointer-events-none absolute top-1/2 z-20 block h-6 w-0.5 -translate-x-1/2 -translate-y-1/2 rounded-full bg-foreground shadow-sm"
            style={{ left: `${timeline.currentTimePercent}%` }}
            aria-hidden="true"
          />
        </div>
        <span className="shrink-0 text-xs text-muted-foreground tabular-nums">
          {timeline.shiftEnd}
        </span>
      </div>
      {row.tasks.length === 0 ? (
        <p className="text-sm text-muted-foreground">Нет заданий за день.</p>
      ) : null}
    </li>
  )
}

function LoadingCard() {
  return (
    <Card size="sm" className="h-full min-h-0">
      <CardHeader>
        <Skeleton className="h-5 w-56" />
        <Skeleton className="h-4 w-40" />
      </CardHeader>
      <CardContent className="flex flex-col gap-6">
        {[0, 1].map((item) => (
          <div key={item} className="flex flex-col gap-3">
            <Skeleton className="h-4 w-36" />
            <Skeleton className="h-4 w-full rounded-full" />
          </div>
        ))}
      </CardContent>
    </Card>
  )
}

function StateCard({
  title,
  description,
}: {
  title: string
  description: string
}) {
  return (
    <Card size="sm" className="h-full min-h-0">
      <CardHeader>
        <CardTitle>{title}</CardTitle>
        <CardDescription>{description}</CardDescription>
      </CardHeader>
    </Card>
  )
}

/** Renders the selected warehouse's current-day operational view for active brigades. */
function HomeContent({
  accessToken,
  warehouseId,
  timeZone,
}: {
  accessToken: string
  warehouseId: string
  timeZone: string
}) {
  const now = useCurrentTime()
  const boardQuery = useQuery({
    queryKey: taskBoardQueryKey(warehouseId),
    queryFn: () => getTaskBoard(accessToken, warehouseId),
    refetchInterval: TASK_BOARD_REFRESH_INTERVAL_MS,
  })
  const activityQuery = useQuery({
    queryKey: dailyBrigadeActivityQueryKey(warehouseId),
    queryFn: () => getDailyBrigadeActivity(accessToken, warehouseId),
    refetchInterval: TASK_BOARD_REFRESH_INTERVAL_MS,
  })
  const groupsQuery = useQuery({
    queryKey: kpiKeys.groups(warehouseId),
    queryFn: () => listKpiWorkerGroups(accessToken, warehouseId),
    staleTime: 60_000,
  })
  const settingsQuery = useQuery({
    queryKey: kpiSettingsKeys.settings,
    queryFn: () => getKpiSettings(accessToken),
    staleTime: 60_000,
  })
  const activeRepairSourceIds = useMemo(
    () =>
      Array.from(
        new Set(
          (activityQuery.data?.intervals ?? []).flatMap((interval) =>
            interval.source?.type === "MAINTENANCE_REPAIR"
              ? [interval.source.sourceId]
              : []
          )
        )
      ).sort(),
    [activityQuery.data]
  )
  const repairComplexitiesQuery = useQuery({
    queryKey: [
      "maintenance",
      "repairs",
      warehouseId,
      "home-active-complexities",
      activeRepairSourceIds,
    ],
    queryFn: () =>
      loadActiveRepairComplexities(
        accessToken,
        warehouseId,
        activeRepairSourceIds
      ),
    enabled: activeRepairSourceIds.length > 0,
  })
  const repairComplexities = useMemo(
    () =>
      new Map<string, RepairComplexity>(
        (repairComplexitiesQuery.data ?? []).map((repair) => [
          repair.id,
          repair.complexity,
        ])
      ),
    [repairComplexitiesQuery.data]
  )
  const palette = paletteForTaskBoard(settingsQuery.data ?? null)
  const timeline = settingsQuery.data?.activeSchedule
    ? dailyShiftTimelineAt({
        schedule: settingsQuery.data.activeSchedule,
        timeZone,
        now: new Date(now),
      })
    : null
  const rows = useMemo(
    () =>
      boardQuery.data && activityQuery.data && timeline
        ? dailyBrigadeRows({
            groups: groupsQuery.data ?? [],
            board: boardQuery.data,
            activity: activityQuery.data,
            timeline,
            timeZone,
            palette,
            now,
          })
        : [],
    [
      activityQuery.data,
      boardQuery.data,
      groupsQuery.data,
      now,
      palette,
      timeZone,
      timeline,
    ]
  )
  const loading =
    boardQuery.isLoading ||
    activityQuery.isLoading ||
    groupsQuery.isLoading ||
    settingsQuery.isLoading
  const sourceError =
    boardQuery.error ??
    activityQuery.error ??
    groupsQuery.error ??
    settingsQuery.error

  if (loading) return <LoadingCard />

  if (sourceError) {
    return (
      <Alert variant="destructive">
        <AlertTitle>Не удалось загрузить статистику дня</AlertTitle>
        <AlertDescription>
          {errorMessage(
            sourceError,
            "Сервисы задач или KPI временно недоступны."
          )}
        </AlertDescription>
      </Alert>
    )
  }

  if (!timeline) {
    return (
      <Alert>
        <AlertTitle>Рабочий день не настроен</AlertTitle>
        <AlertDescription>
          Сначала сохраните и активируйте график работы выбранного склада в
          настройках KPI.
        </AlertDescription>
      </Alert>
    )
  }

  if (timeline.isDayOff) {
    return (
      <Alert>
        <AlertTitle>Сегодня нерабочий день</AlertTitle>
        <AlertDescription>
          В графике выбранного склада этот день отмечен как выходной.
        </AlertDescription>
      </Alert>
    )
  }

  return (
    <Card size="sm" className="h-full min-h-0">
      <CardHeader>
        <CardTitle>Статистика дня по бригадам</CardTitle>
        <CardDescription>
          Смена {timeline.shiftStart}–{timeline.shiftEnd} · текущее время{" "}
          {timeline.currentTime}
        </CardDescription>
      </CardHeader>
      <CardContent className="min-h-0 flex-1 overflow-y-auto">
        <div className="flex flex-col gap-4">
          {!palette ? (
            <Alert>
              <AlertTitle>Палитра KPI не активирована</AlertTitle>
              <AlertDescription>
                Взятые задания останутся серыми, пока диапазоны KPI выбранного
                склада не будут активированы.
              </AlertDescription>
            </Alert>
          ) : null}
          {repairComplexitiesQuery.isError ? (
            <Alert>
              <AlertTitle>Сложность ремонта временно недоступна</AlertTitle>
              <AlertDescription>
                Шкалы отображаются по данным доски задач; сложность в карточке
                задания будет помечена как недоступная.
              </AlertDescription>
            </Alert>
          ) : null}
          {rows.length === 0 ? (
            <Alert>
              <AlertTitle>Нет действующих бригад</AlertTitle>
              <AlertDescription>
                Добавьте или активируйте бригаду в настройках доски задач, чтобы
                увидеть её дневную шкалу.
              </AlertDescription>
            </Alert>
          ) : (
            <TooltipProvider>
              <ul
                className="flex flex-col gap-7"
                aria-label="Шкалы бригад за день"
              >
                {rows.map((row) => (
                  <DailyBrigadeTimeline
                    key={row.id}
                    row={row}
                    timeline={timeline}
                    repairComplexities={repairComplexities}
                    repairComplexitiesUnavailable={
                      repairComplexitiesQuery.isError
                    }
                  />
                ))}
              </ul>
            </TooltipProvider>
          )}
        </div>
      </CardContent>
    </Card>
  )
}

/** Entry page for the default dashboard route. */
export function HomePage() {
  const { accessToken, currentUser } = useAuth()
  const { selectedWarehouse } = useWarehouse()

  if (!selectedWarehouse) {
    return (
      <StateCard
        title="Склад не выбран"
        description="Выберите склад, чтобы открыть его статистику дня."
      />
    )
  }
  if (!hasWarehouseAccess(currentUser, selectedWarehouse.id, "VIEW")) {
    return (
      <StateCard
        title="Недостаточно прав"
        description="Для просмотра статистики дня нужен доступ VIEW к выбранному складу."
      />
    )
  }
  if (!accessToken) {
    return (
      <StateCard
        title="Нет токена доступа"
        description="Повторите вход, чтобы загрузить статистику дня."
      />
    )
  }

  return (
    <HomeContent
      key={selectedWarehouse.id}
      accessToken={accessToken}
      warehouseId={selectedWarehouse.id}
      timeZone={selectedWarehouse.timeZone}
    />
  )
}
