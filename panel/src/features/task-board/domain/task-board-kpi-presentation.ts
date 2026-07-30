import type {
  KpiPalette,
  WarehouseKpiSettings,
} from "@/features/settings/kpi/api/kpi-settings-api"
import type {
  TaskBoardEntryDto,
  TaskBoardTimerState,
} from "@/features/task-board/model/task-board"

export type TaskTimerPresentation = {
  countedActiveSeconds: number
  remainingSeconds: number | null
  remainingPercent: number | null
  timerState: TaskBoardTimerState | null
  nextTransitionAt: string | null
}

export function nextTaskTimerTransitionAt(entries: TaskBoardEntryDto[]) {
  const transitionTimes = entries.flatMap((entry) => {
    const value = entry.timerSnapshot?.nextTransitionAt
    if (!value) return []
    const timestamp = Date.parse(value)
    return Number.isFinite(timestamp) ? [timestamp] : []
  })
  return transitionTimes.length > 0 ? Math.min(...transitionTimes) : null
}

export function paletteForTaskBoard(
  settings: Pick<WarehouseKpiSettings, "status" | "palette"> | null
) {
  if (
    !settings ||
    (settings.status !== "ACTIVE" && settings.status !== "SCHEDULED")
  ) {
    return null
  }
  return settings.palette
}

function elapsedWholeSeconds(from: string | null, now: number) {
  if (!from) return 0
  const fromTime = Date.parse(from)
  return Number.isFinite(fromTime)
    ? Math.max(0, Math.floor((now - fromTime) / 1_000))
    : 0
}

export function taskTimerAt(
  entry: TaskBoardEntryDto,
  now: number
): TaskTimerPresentation {
  const snapshot = entry.timerSnapshot
  if (!snapshot) {
    return {
      countedActiveSeconds: entry.activeWorkSeconds,
      remainingSeconds: null,
      remainingPercent: null,
      timerState: null,
      nextTransitionAt: null,
    }
  }

  let locallyElapsed =
    snapshot.timerState === "WORKING"
      ? elapsedWholeSeconds(snapshot.serverTime, now)
      : 0
  if (locallyElapsed > 0 && snapshot.nextTransitionAt) {
    const transitionTime = Date.parse(snapshot.nextTransitionAt)
    if (Number.isFinite(transitionTime)) {
      locallyElapsed = Math.min(
        locallyElapsed,
        elapsedWholeSeconds(snapshot.serverTime, transitionTime)
      )
    }
  }
  const remainingSeconds =
    snapshot.remainingSeconds === null
      ? null
      : snapshot.remainingSeconds - locallyElapsed
  let remainingPercent = snapshot.remainingPercent
  const snapshotBudgetSeconds =
    snapshot.remainingSeconds === null
      ? null
      : snapshot.countedActiveSeconds + snapshot.remainingSeconds
  if (
    locallyElapsed > 0 &&
    remainingSeconds !== null &&
    ((snapshotBudgetSeconds !== null && snapshotBudgetSeconds > 0) ||
      (entry.plannedDurationMinutes !== null &&
        entry.plannedDurationMinutes > 0))
  ) {
    const budgetSeconds =
      snapshotBudgetSeconds !== null && snapshotBudgetSeconds > 0
        ? snapshotBudgetSeconds
        : entry.plannedDurationMinutes! * 60
    remainingPercent = (remainingSeconds / budgetSeconds) * 100
  }

  return {
    countedActiveSeconds: snapshot.countedActiveSeconds + locallyElapsed,
    remainingSeconds,
    remainingPercent,
    timerState: snapshot.timerState,
    nextTransitionAt: snapshot.nextTransitionAt,
  }
}

export function paletteColorForRemainingPercent(
  remainingPercent: number | null,
  palette: KpiPalette | null
) {
  if (remainingPercent === null || !palette) return null
  if (remainingPercent < 0) return palette.overdueColor

  const bounded = Math.min(100, remainingPercent)
  const range = palette.ranges.find(
    ({ fromPercent, toPercent }) =>
      bounded >= fromPercent &&
      (bounded < toPercent || (bounded === 100 && toPercent === 100))
  )
  return range?.color ?? null
}
