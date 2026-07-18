import {
  completeHttpTaskBoardEntry,
  getHttpTaskBoard,
  listHttpEligibleWorkerGroups,
  moveHttpTaskBoardEntry,
  pauseHttpTaskBoardEntry,
  resumeHttpTaskBoardEntry,
  takeHttpTaskBoardEntry,
} from "@/features/task-board/api/http-task-board-client"
import type {
  TaskBoardEntryDto,
  TaskBoardQueueDto,
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
  if (!entry.queueId) {
    return Promise.resolve([])
  }
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
}) {
  return moveHttpTaskBoardEntry(
    params.accessToken,
    params.entry,
    params.queue.settingsQueueId,
    params.targetIndex
  )
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
