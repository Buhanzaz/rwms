const STORAGE_PREFIX = "rwms.task-board.view.v1"

/** Non-authoritative, warehouse-scoped task-board view preferences. */
export type TaskBoardViewPreferences = {
  showFutureSubtasks: boolean
  collapsedQueueKeys: string[] | null
  boardScrollLeft: number
  boardScrollTop: number
  queueScrollTops: Record<string, number>
}

const EMPTY_PREFERENCES: TaskBoardViewPreferences = {
  showFutureSubtasks: false,
  collapsedQueueKeys: null,
  boardScrollLeft: 0,
  boardScrollTop: 0,
  queueScrollTops: {},
}

function storageKey(warehouseId: string) {
  return `${STORAGE_PREFIX}:${warehouseId}`
}

function nonNegativeNumber(value: unknown) {
  return typeof value === "number" && Number.isFinite(value) && value >= 0
    ? value
    : 0
}

function queueKeys(value: unknown): string[] | null {
  if (value === null) return null
  if (!Array.isArray(value) || value.some((item) => typeof item !== "string")) {
    return null
  }
  return Array.from(new Set(value))
}

function queueScrollTops(value: unknown) {
  if (!value || typeof value !== "object" || Array.isArray(value)) return {}
  return Object.fromEntries(
    Object.entries(value).flatMap(([queueKey, scrollTop]) =>
      queueKey &&
      typeof scrollTop === "number" &&
      Number.isFinite(scrollTop) &&
      scrollTop >= 0
        ? [[queueKey, scrollTop]]
        : []
    )
  )
}

/** Loads safe view preferences without allowing malformed browser state to affect server data. */
export function readTaskBoardViewPreferences(
  warehouseId: string
): TaskBoardViewPreferences {
  if (typeof window === "undefined") return { ...EMPTY_PREFERENCES }
  try {
    const raw = window.localStorage.getItem(storageKey(warehouseId))
    if (!raw) return { ...EMPTY_PREFERENCES }
    const value = JSON.parse(raw) as Record<string, unknown>
    if (!value || typeof value !== "object" || value.version !== 1) {
      return { ...EMPTY_PREFERENCES }
    }
    return {
      showFutureSubtasks: value.showFutureSubtasks === true,
      collapsedQueueKeys: queueKeys(value.collapsedQueueKeys),
      boardScrollLeft: nonNegativeNumber(value.boardScrollLeft),
      boardScrollTop: nonNegativeNumber(value.boardScrollTop),
      queueScrollTops: queueScrollTops(value.queueScrollTops),
    }
  } catch {
    return { ...EMPTY_PREFERENCES }
  }
}

/** Persists only UI layout state; operational task state always remains server-owned. */
export function writeTaskBoardViewPreferences(
  warehouseId: string,
  preferences: TaskBoardViewPreferences
) {
  if (typeof window === "undefined") return
  try {
    window.localStorage.setItem(
      storageKey(warehouseId),
      JSON.stringify({ version: 1, ...preferences })
    )
  } catch {
    // The board remains usable when storage is unavailable or exhausted.
  }
}

/** Exposes the warehouse-scoped key for deterministic browser-storage tests. */
export function taskBoardViewPreferencesStorageKey(warehouseId: string) {
  return storageKey(warehouseId)
}
