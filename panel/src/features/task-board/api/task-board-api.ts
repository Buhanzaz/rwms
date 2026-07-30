import {
  completeHttpTaskBoardEntry,
  getHttpTaskBoard,
  listHttpEligibleWorkerGroups,
  moveHttpTaskBoardEntry,
  pauseHttpTaskBoardEntry,
  pinHttpTaskBoardEntry,
  resumeHttpTaskBoardEntry,
  takeHttpTaskBoardEntry,
} from "@/features/task-board/api/http-task-board-client"
import type {
  TaskBoardEntryDto,
  TaskBoardQueueDto,
} from "@/features/task-board/model/task-board"

export const TASK_BOARD_QUERY_KEY = ["task-board"] as const

export function taskBoardQueryKey(
  warehouseId: string,
  date: string | null = null
) {
  return [...TASK_BOARD_QUERY_KEY, warehouseId, date ?? "default"] as const
}

export function getTaskBoard(
  accessToken: string,
  warehouseId: string,
  date?: string | null
) {
  return getHttpTaskBoard(accessToken, warehouseId, date)
}

export async function getTaskBoardsForAvailableDates(
  accessToken: string,
  warehouseId: string
) {
  const initial = await getHttpTaskBoard(accessToken, warehouseId)
  if (initial.availableDates.length === 0) return []
  const byDate = new Map<string, typeof initial>()
  if (initial.selectedDate) byDate.set(initial.selectedDate, initial)
  const remainingDates = initial.availableDates.filter(
    (date) => !byDate.has(date)
  )
  const remainingBoards = await Promise.all(
    remainingDates.map((date) =>
      getHttpTaskBoard(accessToken, warehouseId, date)
    )
  )
  remainingBoards.forEach((board) => {
    if (board.selectedDate) byDate.set(board.selectedDate, board)
  })
  return initial.availableDates.flatMap((date) => {
    const board = byDate.get(date)
    return board ? [board] : []
  })
}

export function listEligibleTaskBoardGroups(
  accessToken: string,
  entry: TaskBoardEntryDto
) {
  return listHttpEligibleWorkerGroups(
    accessToken,
    entry.warehouseId,
    entry.queueId
  )
}

export function moveTaskBoardEntry(params: {
  accessToken: string
  entry: TaskBoardEntryDto
  queue: TaskBoardQueueDto
  targetIndex: number
  targetDate: string
}) {
  return moveHttpTaskBoardEntry(
    params.accessToken,
    params.entry,
    params.queue.settingsQueueId,
    params.targetIndex,
    params.targetDate
  )
}

export function pinTaskBoardEntry(params: {
  accessToken: string
  entry: TaskBoardEntryDto
  pinned: boolean
}) {
  return pinHttpTaskBoardEntry(params.accessToken, params.entry, params.pinned)
}

export function takeTaskBoardEntry(params: {
  accessToken: string
  entry: TaskBoardEntryDto
  workerGroupId: string | null
  workerId: string | null
}) {
  return takeHttpTaskBoardEntry(
    params.accessToken,
    params.entry,
    params.workerGroupId,
    params.workerId
  )
}

export function pauseTaskBoardEntry(
  accessToken: string,
  entry: TaskBoardEntryDto
) {
  return pauseHttpTaskBoardEntry(accessToken, entry)
}

export function resumeTaskBoardEntry(
  accessToken: string,
  entry: TaskBoardEntryDto
) {
  return resumeHttpTaskBoardEntry(accessToken, entry)
}

export function completeTaskBoardEntry(
  accessToken: string,
  entry: TaskBoardEntryDto
) {
  return completeHttpTaskBoardEntry(accessToken, entry)
}
