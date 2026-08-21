import {
  completeHttpTaskBoardEntry,
  getHttpTaskBoard,
  listHttpEligibleWorkerGroups,
  pauseHttpTaskBoardEntry,
  pinHttpTaskBoardEntry,
  resumeHttpTaskBoardEntry,
  takeHttpTaskBoardEntry,
} from "@/features/task-board/api/http-task-board-client"
import type {
  TaskBoardEntryDto,
} from "@/features/task-board/model/task-board"

export const TASK_BOARD_QUERY_KEY = ["task-board"] as const

export function taskBoardQueryKey(warehouseId: string) {
  return [...TASK_BOARD_QUERY_KEY, warehouseId] as const
}

export function getTaskBoard(accessToken: string, warehouseId: string) {
  return getHttpTaskBoard(accessToken, warehouseId)
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
