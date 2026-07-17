import { DEV_MAINTENANCE_FIXTURES_ENABLED } from "@/features/maintenance/maintenance-runtime"
import {
  completeRepairTaskEntry,
  listRepairTasks,
  moveRepairTaskEntry,
  pauseRepairTaskEntry,
  resumeRepairTaskEntry,
  takeRepairTaskEntry,
} from "@/features/repair-tasks/api/repair-tasks-api"
import type { RepairTaskDto } from "@/features/repair-tasks/model/repair-task"
import type { PendingEstimateMediaUpload } from "@/features/repair-estimates/model/repair-estimate"
import { createMockTaskBoardProjection } from "@/features/task-board/domain/task-board-domain"
import { taskBoardMockClient } from "@/features/task-board/mock"
import type {
  MockTaskBoardSnapshotDto,
  WorkerNotificationDto,
} from "@/features/task-board/mock"
import type {
  TaskBoardEntryDto,
  TaskBoardQueueDto,
  TaskBoardSnapshotDto,
} from "@/features/task-board/model/task-board"
import {
  completeHttpTaskBoardEntry,
  getHttpTaskBoard,
  moveHttpTaskBoardEntry,
  pauseHttpTaskBoardEntry,
  resumeHttpTaskBoardEntry,
  takeHttpTaskBoardEntry,
} from "@/features/task-board/api/http-task-board-client"

export const TASK_BOARD_QUERY_KEY = ["task-board"] as const
export const TASK_BOARD_NOTIFICATIONS_QUERY_KEY = [
  "task-board",
  "notifications",
] as const

export function taskBoardQueryKey(
  warehouseId: string,
  serviceWarehouseId = warehouseId
) {
  return [...TASK_BOARD_QUERY_KEY, warehouseId, serviceWarehouseId] as const
}

function repairRouteTitle(task: RepairTaskDto, subtaskId: string) {
  const subtask = task.subtasks.find((candidate) => candidate.id === subtaskId)
  if (!subtask) return task.reason || `Задание ${task.cabinNumber}`
  if (subtask.kind === "MOVE_TO_REPAIR") return "Перемещение на ремонт"
  if (subtask.kind === "MOVE_FROM_REPAIR") return "Перемещение с ремонта"
  const first =
    subtask.workLines[0]?.description ??
    subtask.materialLines[0]?.description ??
    task.reason
  return first || `Работы по ${task.cabinNumber}`
}

async function ensureRepairTasksRegistered(
  warehouseId: string,
  serviceWarehouseId: string
) {
  const tasks = await listRepairTasks(warehouseId)
  for (const task of tasks) {
    const externalTaskId = `repair:${task.id}`
    let runtimeTask = await taskBoardMockClient.findByExternalTaskId(
      serviceWarehouseId,
      externalTaskId
    )
    const sourceRoute = task.subtasks
      .filter((subtask) => subtask.status !== "CANCELLED")
      .sort((left, right) => left.sortOrder - right.sortOrder)
    const activeSource =
      task.status === "QUEUED" || task.status === "IN_PROGRESS"
    if (!runtimeTask) {
      if (!activeSource || sourceRoute.length === 0) continue
      const initialRoute = sourceRoute.map((subtask) => ({
        queueCode: subtask.queueCode ?? "",
        title: repairRouteTitle(task, subtask.id),
        externalStepId: subtask.id,
      }))
      runtimeTask = await taskBoardMockClient.registerTask({
        externalTaskId,
        warehouseId: serviceWarehouseId,
        queueCode: initialRoute[0]?.queueCode || null,
        title: task.reason || `Ремонт ${task.cabinNumber}`,
        description: task.comment || null,
        cabinNumber: task.cabinNumber,
        route: initialRoute,
      })
    }

    const isUnstarted =
      runtimeTask.status === "QUEUED" &&
      runtimeTask.assignmentId === null &&
      runtimeTask.activeRouteIndex === 0 &&
      runtimeTask.elapsedSeconds === 0
    const locked = isUnstarted
      ? []
      : runtimeTask.route.slice(0, runtimeTask.activeRouteIndex + 1)
    const lockedRoute = locked.map((step, index) => {
      const source =
        task.subtasks.find((subtask) => subtask.id === step.externalStepId) ??
        [...task.subtasks].sort(
          (left, right) => left.sortOrder - right.sortOrder
        )[index]
      return {
        externalStepId: step.externalStepId ?? source?.id ?? step.id,
        queueCode: step.queueCode,
        title: source ? repairRouteTitle(task, source.id) : step.title,
      }
    })
    const lockedIds = new Set(lockedRoute.map((step) => step.externalStepId))
    const route = [
      ...lockedRoute,
      ...sourceRoute
        .filter((subtask) => !lockedIds.has(subtask.id))
        .map((subtask) => ({
          externalStepId: subtask.id,
          queueCode: subtask.queueCode ?? "",
          title: repairRouteTitle(task, subtask.id),
        })),
    ]
    await taskBoardMockClient.syncRegisteredTask({
      warehouseId: serviceWarehouseId,
      externalTaskId,
      expectedVersion: runtimeTask.version,
      title: task.reason || `Ремонт ${task.cabinNumber}`,
      description: task.comment || null,
      cabinNumber: task.cabinNumber,
      route,
      sourceStatus:
        task.status === "CANCELLED"
          ? "CANCELLED"
          : task.status === "COMPLETED"
            ? "COMPLETED"
            : "ACTIVE",
    })
  }
}

function sourceTaskId(externalTaskId: string) {
  return externalTaskId.startsWith("repair:")
    ? externalTaskId.slice("repair:".length)
    : null
}

function isAttachedRepairEntry(entry: TaskBoardEntryDto) {
  const id = entry.runtimeTask
    ? sourceTaskId(entry.runtimeTask.externalTaskId)
    : null
  return Boolean(id && entry.task.id === id)
}

function attachRepairTaskSources(
  board: TaskBoardSnapshotDto,
  sourceTasks: RepairTaskDto[]
) {
  const byId = new Map(sourceTasks.map((task) => [task.id, task]))
  board.queues.forEach((queue) =>
    queue.entries.forEach((entry) => {
      const id = entry.runtimeTask
        ? sourceTaskId(entry.runtimeTask.externalTaskId)
        : null
      const source = id ? byId.get(id) : null
      const step = entry.runtimeTask?.route.find(
        (candidate) => candidate.id === entry.subtask.id
      )
      const subtask = source
        ? source.subtasks.find(
            (candidate) => candidate.id === step?.externalStepId
          )
        : null
      if (!source || !subtask) return
      entry.task = source
      entry.subtask = subtask
      entry.detailsHref = `/repairs?repairId=${encodeURIComponent(source.id)}`
    })
  )
  return board
}

async function reconcileRepairRuntime(
  sourceTasks: RepairTaskDto[],
  snapshot: MockTaskBoardSnapshotDto
) {
  const byId = new Map(sourceTasks.map((task) => [task.id, task]))
  let changed = false
  for (const runtimeTask of snapshot.tasks) {
    const id = sourceTaskId(runtimeTask.externalTaskId)
    const source = id ? byId.get(id) : null
    if (!source || runtimeTask.status === "CANCELLED") continue
    const activeStep = runtimeTask.route[runtimeTask.activeRouteIndex]
    const subtask = source.subtasks.find(
      (candidate) => candidate.id === activeStep?.externalStepId
    )
    if (!subtask || subtask.status === "DONE") {
      if (runtimeTask.status !== "DONE") {
        await taskBoardMockClient.completeTask({
          taskId: runtimeTask.id,
          expectedVersion: runtimeTask.version,
        })
        changed = true
      }
      continue
    }
    const assignment = snapshot.assignments.find(
      (candidate) => candidate.id === runtimeTask.assignmentId
    )
    const group = snapshot.groups.find(
      (candidate) => candidate.id === assignment?.workerGroupId
    )
    const workers = (assignment?.workerIds ?? [])
      .map((workerId) =>
        snapshot.workers.find((worker) => worker.id === workerId)
      )
      .filter((worker): worker is NonNullable<typeof worker> => Boolean(worker))

    if (
      runtimeTask.status === "QUEUED" &&
      subtask.status === "IN_PROGRESS" &&
      subtask.workerGroup
    ) {
      await taskBoardMockClient.takeTask({
        taskId: runtimeTask.id,
        expectedVersion: runtimeTask.version,
        workerGroupId: subtask.workerGroup.id,
        workerIds: subtask.assignments
          .map((item) => item.worker?.id)
          .filter((workerId): workerId is string => Boolean(workerId)),
      })
      changed = true
      continue
    }
    if (
      runtimeTask.status === "IN_PROGRESS" &&
      subtask.status === "WAITING" &&
      group
    ) {
      await takeRepairTaskEntry({
        task: source,
        subtaskId: subtask.id,
        workerGroup: { id: group.id, name: group.name },
        workers: workers.map((worker) => ({
          id: worker.id,
          name: worker.displayName,
        })),
      })
      changed = true
      continue
    }
    if (
      (runtimeTask.status === "PAUSED" || runtimeTask.status === "RETURNING") &&
      subtask.status === "IN_PROGRESS"
    ) {
      await pauseRepairTaskEntry({ task: source, subtaskId: subtask.id })
      changed = true
      continue
    }
    if (runtimeTask.status === "IN_PROGRESS" && subtask.status === "PAUSED") {
      await resumeRepairTaskEntry({ task: source, subtaskId: subtask.id })
      changed = true
      continue
    }
    if (
      runtimeTask.queueCode !== subtask.queueCode &&
      runtimeTask.status === "QUEUED"
    ) {
      const queue = snapshot.queues.find(
        (candidate) => candidate.code === subtask.queueCode
      )
      await taskBoardMockClient.moveTask({
        taskId: runtimeTask.id,
        expectedVersion: runtimeTask.version,
        targetQueueId: queue?.id ?? null,
        queuePosition: subtask.queuePosition,
      })
      changed = true
    }
  }
  return changed
}

export async function getTaskBoard(
  warehouseId: string,
  serviceWarehouseId = warehouseId,
  accessToken?: string | null
) {
  if (DEV_MAINTENANCE_FIXTURES_ENABLED) {
    await ensureRepairTasksRegistered(warehouseId, serviceWarehouseId)
    let sourceTasks = await listRepairTasks(warehouseId)
    let snapshot = await taskBoardMockClient.getSnapshot(serviceWarehouseId)
    const reconciliationLimit = Math.max(
      1,
      ...sourceTasks.map((task) => task.subtasks.length + 1)
    )
    for (let attempt = 0; attempt < reconciliationLimit; attempt += 1) {
      if (!(await reconcileRepairRuntime(sourceTasks, snapshot))) break
      sourceTasks = await listRepairTasks(warehouseId)
      snapshot = await taskBoardMockClient.getSnapshot(serviceWarehouseId)
    }
    return attachRepairTaskSources(
      createMockTaskBoardProjection(snapshot),
      sourceTasks
    )
  }
  return getHttpTaskBoard(accessToken, serviceWarehouseId)
}

export function moveTaskBoardEntry(params: {
  task: RepairTaskDto
  subtaskId: string
  queue: TaskBoardQueueDto
  queuePosition: number
  entry?: TaskBoardEntryDto
  accessToken?: string | null
}) {
  if (!DEV_MAINTENANCE_FIXTURES_ENABLED) {
    if (!params.entry) throw new Error("Запись доски заданий не найдена.")
    return moveHttpTaskBoardEntry(
      params.accessToken,
      params.entry,
      params.queue.settingsQueueId ?? null,
      params.queuePosition
    )
  }
  if (params.entry?.runtimeTask) {
    return (async () => {
      if (isAttachedRepairEntry(params.entry!)) {
        await moveRepairTaskEntry({
          task: params.entry!.task,
          subtaskId: params.entry!.subtask.id,
          targetQueueCode: params.queue.queueCode,
          targetRouteQueueKind: params.queue.routeQueueKind,
          targetQueuePosition: params.queuePosition,
        })
      }
      return taskBoardMockClient.moveTask({
        taskId: params.entry!.runtimeTask!.id,
        expectedVersion: params.entry!.runtimeTask!.version,
        targetQueueId: params.queue.settingsQueueId ?? null,
        queuePosition: params.queuePosition,
      })
    })()
  }
  return moveRepairTaskEntry({
    task: params.task,
    subtaskId: params.subtaskId,
    targetQueueCode: params.queue.queueCode,
    targetRouteQueueKind: params.queue.routeQueueKind,
    targetQueuePosition: params.queuePosition,
  })
}

export function takeTaskBoardEntry(params: {
  entry: TaskBoardEntryDto
  workerGroup: { id: string; name: string }
  workers?: Array<{ id: string; name: string }>
  accessToken?: string | null
}) {
  if (!DEV_MAINTENANCE_FIXTURES_ENABLED) {
    return takeHttpTaskBoardEntry(
      params.accessToken,
      params.entry,
      params.workerGroup.id,
      params.workers?.[0]?.id ?? null
    )
  }
  if (params.entry.runtimeTask) {
    return (async () => {
      if (
        isAttachedRepairEntry(params.entry) &&
        params.entry.subtask.status === "WAITING"
      ) {
        await takeRepairTaskEntry({
          task: params.entry.task,
          subtaskId: params.entry.subtask.id,
          workerGroup: params.workerGroup,
          workers: params.workers,
          accessToken: params.accessToken ?? undefined,
        })
      }
      return taskBoardMockClient.takeTask({
        taskId: params.entry.runtimeTask!.id,
        expectedVersion: params.entry.runtimeTask!.version,
        workerGroupId: params.workerGroup.id,
        workerIds: params.workers?.map((worker) => worker.id),
      })
    })()
  }
  return takeRepairTaskEntry({
    task: params.entry.task,
    subtaskId: params.entry.subtask.id,
    workerGroup: params.workerGroup,
    workers: params.workers,
    accessToken: params.accessToken ?? undefined,
  })
}

export function pauseTaskBoardEntry(
  entry: TaskBoardEntryDto,
  accessToken?: string | null
) {
  if (!DEV_MAINTENANCE_FIXTURES_ENABLED) {
    return pauseHttpTaskBoardEntry(accessToken, entry)
  }
  if (entry.runtimeTask) {
    return (async () => {
      const runtimeTask = await taskBoardMockClient.pauseTask({
        taskId: entry.runtimeTask!.id,
        expectedVersion: entry.runtimeTask!.version,
      })
      if (
        isAttachedRepairEntry(entry) &&
        entry.subtask.status === "IN_PROGRESS"
      ) {
        await pauseRepairTaskEntry({
          task: entry.task,
          subtaskId: entry.subtask.id,
        })
      }
      return runtimeTask
    })()
  }
  return pauseRepairTaskEntry({
    task: entry.task,
    subtaskId: entry.subtask.id,
  })
}

export function resumeTaskBoardEntry(
  entry: TaskBoardEntryDto,
  accessToken?: string | null
) {
  if (!DEV_MAINTENANCE_FIXTURES_ENABLED) {
    return resumeHttpTaskBoardEntry(accessToken, entry)
  }
  if (entry.runtimeTask) {
    return (async () => {
      const runtimeTask = await taskBoardMockClient.resumeTask({
        taskId: entry.runtimeTask!.id,
        expectedVersion: entry.runtimeTask!.version,
      })
      if (
        runtimeTask.status === "IN_PROGRESS" &&
        isAttachedRepairEntry(entry) &&
        entry.subtask.status === "PAUSED"
      ) {
        await resumeRepairTaskEntry({
          task: entry.task,
          subtaskId: entry.subtask.id,
        })
      }
      return runtimeTask
    })()
  }
  return resumeRepairTaskEntry({
    task: entry.task,
    subtaskId: entry.subtask.id,
  })
}

export function completeTaskBoardEntry(
  entry: TaskBoardEntryDto,
  pendingUploads: PendingEstimateMediaUpload[] = [],
  accessToken?: string | null
) {
  if (!DEV_MAINTENANCE_FIXTURES_ENABLED) {
    if (pendingUploads.length > 0) {
      throw new Error(
        "Загрузка результата недоступна: защищённый HTTP runtime media-service ещё не подключён."
      )
    }
    return completeHttpTaskBoardEntry(accessToken, entry)
  }
  if (entry.runtimeTask) {
    return (async () => {
      if (isAttachedRepairEntry(entry) && entry.subtask.status !== "DONE") {
        await completeRepairTaskEntry({
          task: entry.task,
          subtaskId: entry.subtask.id,
          pendingUploads,
        })
      }
      return taskBoardMockClient.completeTask({
        taskId: entry.runtimeTask!.id,
        expectedVersion: entry.runtimeTask!.version,
      })
    })()
  }
  return completeRepairTaskEntry({
    task: entry.task,
    subtaskId: entry.subtask.id,
    pendingUploads,
  })
}

export async function confirmTaskBoardGroupReturned(entry: TaskBoardEntryDto) {
  const groupId = entry.interruption?.workerGroupId
  const warehouseId = entry.runtimeTask?.warehouseId
  if (!DEV_MAINTENANCE_FIXTURES_ENABLED || !groupId || !warehouseId) return []
  const snapshot = await taskBoardMockClient.getSnapshot(warehouseId)
  const interruptions = snapshot.interruptions
    .filter(
      (interruption) =>
        interruption.workerGroupId === groupId &&
        interruption.state === "RETURNING"
    )
    .map((interruption) => ({
      id: interruption.id,
      expectedVersion: interruption.version,
    }))
  if (interruptions.length === 0) return []
  return taskBoardMockClient.confirmGroupReturned({
    workerGroupId: groupId,
    interruptions,
  })
}

export function subscribeTaskBoardMock(listener: () => void) {
  return DEV_MAINTENANCE_FIXTURES_ENABLED
    ? taskBoardMockClient.subscribe(listener)
    : () => undefined
}

export function listTaskBoardWorkers(serviceWarehouseId: string) {
  if (!DEV_MAINTENANCE_FIXTURES_ENABLED) return Promise.resolve([])
  return taskBoardMockClient
    .getSnapshot(serviceWarehouseId)
    .then((snapshot) => snapshot.workers.filter((worker) => worker.active))
}

export function listTaskBoardNotifications(workerId: string | null) {
  if (!DEV_MAINTENANCE_FIXTURES_ENABLED || !workerId) {
    return Promise.resolve([] as WorkerNotificationDto[])
  }
  return taskBoardMockClient.listNotifications(workerId)
}

export function markTaskBoardNotificationRead(
  notification: WorkerNotificationDto
) {
  return taskBoardMockClient.markNotificationRead(
    notification.id,
    notification.version
  )
}

export function markAllTaskBoardNotificationsRead(params: {
  workerId: string
  notifications: WorkerNotificationDto[]
}) {
  return taskBoardMockClient.markAllNotificationsRead({
    workerId: params.workerId,
    notifications: params.notifications
      .filter((notification) => !notification.readAt)
      .map((notification) => ({
        id: notification.id,
        expectedVersion: notification.version,
      })),
  })
}

export async function setTaskBoardActiveWorker(workerId: string) {
  if (!DEV_MAINTENANCE_FIXTURES_ENABLED) return
  const clock = await taskBoardMockClient.getSimulationClock()
  await taskBoardMockClient.updateSimulationClock({
    expectedVersion: clock.version,
    activeWorkerId: workerId,
  })
}

export async function getTaskBoardActiveWorkerId() {
  if (!DEV_MAINTENANCE_FIXTURES_ENABLED) return null
  return (await taskBoardMockClient.getSimulationClock()).activeWorkerId
}

export function evaluateTaskBoardMock() {
  if (!DEV_MAINTENANCE_FIXTURES_ENABLED) return Promise.resolve(null)
  return taskBoardMockClient.evaluate()
}
