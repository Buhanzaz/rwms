import {
  completeHttpTaskBoardEntry,
  getHttpTaskBoard,
  listHttpEligibleWorkerGroups,
  pauseHttpTaskBoardEntry,
  pinHttpTaskBoardEntry,
  reorderHttpTaskBoardEntry,
  resumeHttpTaskBoardEntry,
  setHttpFutureTaskBoardEntryAvailability,
  takeHttpTaskBoardEntry,
  updateHttpTaskBoardWorkerPlan,
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

export function updateTaskBoardWorkerPlan(params: {
  accessToken: string
  warehouseId: string
  queue: TaskBoardQueueDto
  workerFeedEnabled: boolean
  availableTaskLimit: number
}) {
  return updateHttpTaskBoardWorkerPlan({
    accessToken: params.accessToken,
    warehouseId: params.warehouseId,
    queueId: params.queue.settingsQueueId,
    expectedVersion: params.queue.version,
    workerFeedEnabled: params.workerFeedEnabled,
    availableTaskLimit: params.availableTaskLimit,
  })
}

export function reorderTaskBoardEntry(params: {
  accessToken: string
  queue: TaskBoardQueueDto
  entry: TaskBoardEntryDto
  targetEntryId: string
  targetIndex: number
}) {
  return reorderHttpTaskBoardEntry({
    accessToken: params.accessToken,
    entry: params.entry,
    expectedQueueVersion: params.queue.version,
    targetEntryId: params.targetEntryId,
    targetIndex: params.targetIndex,
  })
}

export function setFutureTaskBoardEntryAvailability(params: {
  accessToken: string
  entry: TaskBoardEntryDto
  available: boolean
}) {
  return setHttpFutureTaskBoardEntryAvailability(params)
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
