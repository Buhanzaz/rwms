import type {
  CopyGroupScheduleRequest,
  GroupScheduleDto,
  GroupScheduleRequest,
  ScheduleDayDto,
  WorkerClassRequest,
  WorkerDto,
  WorkerGroupDto,
  WorkerGroupRequest,
  WorkerRequest,
  WorkQueueDto,
  WorkQueueRequest,
} from "@/features/settings/task-board/model/task-board-settings"
import type {
  QueueOrderItem,
  TaskBoardSettingsClient,
} from "@/features/settings/task-board/api/task-board-settings-client"
import type {
  ConfirmGroupReturnedCommand,
  MarkAllNotificationsReadCommand,
  MockClockDto,
  MockTaskBoardEnvelope,
  MockTaskBoardSnapshotDto,
  MockTaskDto,
  MockTaskVersionCommand,
  MoveMockTaskCommand,
  RegisterMockTaskCommand,
  SimulationClockSnapshotDto,
  SyncRegisteredTaskCommand,
  TakeMockTaskCommand,
  TaskInterruptionDto,
  TaskBoardMockRuntimeClient,
  UpdateSimulationClockRequest,
  WorkerNotificationDto,
} from "@/features/task-board/mock/model"
import {
  TaskBoardMockConflictError,
  TaskBoardMockValidationError,
} from "@/features/task-board/mock/model"
import { BrowserTaskBoardStore } from "@/features/task-board/mock/store"

function clone<T>(value: T): T {
  return structuredClone(value)
}

function normalized(value: string) {
  return value.trim().toLocaleLowerCase("ru")
}

const DEFAULT_TIMEZONE = "Europe/Moscow"

function isValidIanaTimezone(value: string) {
  try {
    new Intl.DateTimeFormat("en", { timeZone: value }).format()
    return true
  } catch {
    return false
  }
}

function validatedTimezone(value: string) {
  const timezone = value.trim()
  if (!timezone || !isValidIanaTimezone(timezone)) {
    throw new TaskBoardMockValidationError(
      "Укажите корректную временную зону IANA."
    )
  }
  return timezone
}

function assertVersion(actual: number, expected: number) {
  if (actual !== expected) throw new TaskBoardMockConflictError()
}

function nowIso(clock: MockClockDto) {
  if (clock.mode === "REAL") return new Date().toISOString()
  if (!clock.running) return clock.anchorSimulatedAt
  const realDelta = Date.now() - Date.parse(clock.anchorRealAt)
  return new Date(
    Date.parse(clock.anchorSimulatedAt) + realDelta * clock.speed
  ).toISOString()
}

function audit(
  draft: MockTaskBoardEnvelope,
  type: string,
  entityId: string,
  details: Record<string, string | number | boolean | null> = {}
) {
  draft.auditEvents.push({
    id: crypto.randomUUID(),
    type,
    entityId,
    actor: "Локальный администратор",
    occurredAt: nowIso(draft.clock),
    details,
  })
}

function taskQueue(draft: MockTaskBoardEnvelope, task: MockTaskDto) {
  return draft.queues.find((queue) => queue.id === task.queueId) ?? null
}

function currentAssignment(draft: MockTaskBoardEnvelope, task: MockTaskDto) {
  return task.assignmentId
    ? (draft.assignments.find((item) => item.id === task.assignmentId) ?? null)
    : null
}

function workerClassById(draft: MockTaskBoardEnvelope, id: string) {
  const workerClass = draft.classes.find((item) => item.id === id)
  if (!workerClass) throw new TaskBoardMockValidationError("Класс не найден.")
  return workerClass
}

function touchTask(task: MockTaskDto, at: string) {
  task.version += 1
  task.updatedAt = at
}

function stopTaskTimer(task: MockTaskDto, at: string) {
  if (!task.activeSince) return
  task.elapsedSeconds += Math.max(
    0,
    Math.floor((Date.parse(at) - Date.parse(task.activeSince)) / 1_000)
  )
  task.activeSince = null
}

function notification(
  draft: MockTaskBoardEnvelope,
  workerId: string,
  type: WorkerNotificationDto["type"],
  message: string,
  taskId: string | null,
  deduplicationKey: string,
  at: string
) {
  if (
    draft.notifications.some(
      (candidate) => candidate.deduplicationKey === deduplicationKey
    )
  ) {
    return
  }
  draft.notifications.push({
    id: crypto.randomUUID(),
    version: 0,
    workerId,
    type,
    message,
    taskId,
    createdAt: at,
    readAt: null,
    deduplicationKey,
  })
}

function dateParts(at: string, timezone: string) {
  const formatter = new Intl.DateTimeFormat("en-GB", {
    timeZone: timezone,
    year: "numeric",
    month: "2-digit",
    day: "2-digit",
    weekday: "short",
    hour: "2-digit",
    minute: "2-digit",
    hourCycle: "h23",
  })
  const parts = Object.fromEntries(
    formatter.formatToParts(new Date(at)).map((part) => [part.type, part.value])
  )
  const weekday = ["Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun"].indexOf(
    parts.weekday
  )
  return {
    dayOfWeek: (weekday + 1) as ScheduleDayDto["dayOfWeek"],
    minute: Number(parts.hour) * 60 + Number(parts.minute),
    localDate: `${parts.year}-${parts.month}-${parts.day}`,
  }
}

function timeMinute(value: string) {
  if (!/^([01]\d|2[0-3]):[0-5]\d$/.test(value)) return Number.NaN
  const [hour, minute] = value.split(":").map(Number)
  return hour * 60 + minute
}

function validateSchedule(days: ScheduleDayDto[], returnGraceMinutes: number) {
  if (
    !Number.isInteger(returnGraceMinutes) ||
    returnGraceMinutes < 0 ||
    returnGraceMinutes > 60
  ) {
    throw new TaskBoardMockValidationError(
      "Время возвращения должно быть целым числом от 0 до 60 минут."
    )
  }
  if (
    days.length !== 7 ||
    new Set(days.map((day) => day.dayOfWeek)).size !== 7
  ) {
    throw new TaskBoardMockValidationError(
      "График должен содержать семь уникальных дней."
    )
  }
  for (const day of days) {
    if (!day.enabled) continue
    const shiftStart = timeMinute(day.shiftStartsAt)
    const shiftEnd = timeMinute(day.shiftEndsAt)
    if (!Number.isFinite(shiftStart) || !Number.isFinite(shiftEnd)) {
      throw new TaskBoardMockValidationError("Укажите корректное время смены.")
    }
    if (shiftStart >= shiftEnd) {
      throw new TaskBoardMockValidationError(
        "Окончание смены должно быть позже начала."
      )
    }
    const ordered = [...day.restPeriods].sort(
      (left, right) => timeMinute(left.startsAt) - timeMinute(right.startsAt)
    )
    ordered.forEach((period, index) => {
      const start = timeMinute(period.startsAt)
      const end = timeMinute(period.endsAt)
      if (!Number.isFinite(start) || !Number.isFinite(end)) {
        throw new TaskBoardMockValidationError(
          "Укажите корректное время перерыва."
        )
      }
      if (
        !Number.isInteger(period.warningMinutes) ||
        period.warningMinutes < 0 ||
        period.warningMinutes > 60
      ) {
        throw new TaskBoardMockValidationError(
          "Предупреждение должно быть целым числом от 0 до 60 минут."
        )
      }
      if (start < shiftStart || end > shiftEnd || start >= end) {
        throw new TaskBoardMockValidationError(
          "Период отдыха должен находиться внутри смены."
        )
      }
      if (index > 0 && start < timeMinute(ordered[index - 1].endsAt)) {
        throw new TaskBoardMockValidationError(
          "Периоды отдыха не должны пересекаться."
        )
      }
    })
  }
}

function safeReturnGraceMinutes(value: number) {
  return Number.isInteger(value) && value >= 0 && value <= 60 ? value : 3
}

function eligibleWorkers(group: WorkerGroupDto, queue: WorkQueueDto) {
  if (!group.workerClass.active || !queue.active || queue.hidden) return false
  if (group.workerClass.code === "DRIVER") return false
  if (queue.type === "HOLDING") return false
  if (queue.bindings.length === 0) return true
  return queue.bindings.some(
    (binding) =>
      binding.workerClass.active &&
      binding.workerClass.id === group.workerClass.id
  )
}

function workerHasQualification(worker: WorkerDto, workerClassId: string) {
  return worker.qualifications.some(
    (qualification) =>
      qualification.active &&
      qualification.workerClass.active &&
      qualification.workerClass.id === workerClassId
  )
}

function workerEligibleForQueue(worker: WorkerDto, queue: WorkQueueDto) {
  return (
    worker.active &&
    (queue.bindings.length === 0 ||
      queue.bindings.some((binding) =>
        workerHasQualification(worker, binding.workerClass.id)
      ))
  )
}

function assertGroupMemberQualification(
  worker: WorkerDto,
  workerClassId: string
) {
  if (!workerHasQualification(worker, workerClassId)) {
    throw new TaskBoardMockValidationError(
      `У рабочего «${worker.displayName}» нет активной квалификации класса бригады.`
    )
  }
}

function removePauseReason(task: MockTaskDto, sourceId: string) {
  const before = task.pauseReasons.length
  task.pauseReasons = task.pauseReasons.filter(
    (reason) => reason.sourceId !== sourceId
  )
  return before !== task.pauseReasons.length
}

function resumeIfClear(
  draft: MockTaskBoardEnvelope,
  task: MockTaskDto,
  at: string
) {
  if (task.pauseReasons.length > 0) return
  const assignment = currentAssignment(draft, task)
  if (
    task.status === "IN_PROGRESS" &&
    task.activeSince !== null &&
    (!assignment || assignment.status === "ACTIVE")
  ) {
    return
  }
  task.status = "IN_PROGRESS"
  task.activeSince = at
  if (assignment && assignment.status !== "ACTIVE") {
    assignment.status = "ACTIVE"
    assignment.version += 1
  }
}

function evaluateRuntime(draft: MockTaskBoardEnvelope, at: string) {
  for (const interruption of draft.interruptions) {
    if (
      interruption.state !== "RETURNING" ||
      !interruption.returnDeadline ||
      Date.parse(interruption.returnDeadline) > Date.parse(at)
    ) {
      continue
    }
    const task = draft.tasks.find(
      (candidate) => candidate.id === interruption.interruptedTaskId
    )
    if (!task) continue
    removePauseReason(task, interruption.id)
    resumeIfClear(draft, task, at)
    touchTask(task, at)
    interruption.state = "RESUMED"
    interruption.resumedAt = at
    interruption.version += 1
    const assignment = currentAssignment(draft, task)
    assignment?.workerIds.forEach((workerId) =>
      notification(
        draft,
        workerId,
        "TASK_RESUMED",
        `Задание «${task.title}» возобновлено.`,
        task.id,
        `interruption-resumed:${interruption.id}:${workerId}`,
        at
      )
    )
  }

  for (const assignment of draft.assignments) {
    if (assignment.status === "DONE" || assignment.status === "CANCELLED") {
      continue
    }
    const task = draft.tasks.find(
      (candidate) => candidate.id === assignment.taskId
    )
    const group = draft.groups.find(
      (candidate) => candidate.id === assignment.workerGroupId
    )
    const schedule = draft.schedules.find(
      (candidate) => candidate.workerGroupId === assignment.workerGroupId
    )
    if (!task || !group || !schedule) continue
    const safeTimezone = isValidIanaTimezone(schedule.timezone)
      ? schedule.timezone
      : DEFAULT_TIMEZONE
    const safeGraceMinutes = safeReturnGraceMinutes(schedule.returnGraceMinutes)
    if (
      safeTimezone !== schedule.timezone ||
      safeGraceMinutes !== schedule.returnGraceMinutes
    ) {
      schedule.timezone = safeTimezone
      schedule.returnGraceMinutes = safeGraceMinutes
      schedule.version += 1
      audit(draft, "SCHEDULE_RUNTIME_VALUES_REPAIRED", schedule.id, {
        timezone: safeTimezone,
        returnGraceMinutes: safeGraceMinutes,
      })
    }
    const local = dateParts(at, safeTimezone)
    const scheduleDays = Array.isArray(schedule.days) ? schedule.days : []
    const day = scheduleDays.find(
      (candidate) => candidate.dayOfWeek === local.dayOfWeek
    )
    const restPeriods = Array.isArray(day?.restPeriods) ? day.restPeriods : []
    const activePeriod = day?.enabled
      ? restPeriods.find(
          (period) =>
            local.minute >= timeMinute(period.startsAt) &&
            local.minute < timeMinute(period.endsAt)
        )
      : undefined
    if (day?.enabled) {
      restPeriods.forEach((period) => {
        const start = timeMinute(period.startsAt)
        if (
          period.warningMinutes > 0 &&
          local.minute >= start - period.warningMinutes &&
          local.minute < start
        ) {
          assignment.workerIds.forEach((workerId) =>
            notification(
              draft,
              workerId,
              "BREAK_WARNING",
              `${period.type === "LUNCH" ? "Обед" : "Перекур"} начнётся через ${start - local.minute} мин.`,
              task.id,
              `break-warning:${period.id}:${local.localDate}:${workerId}`,
              at
            )
          )
        }
      })
    }
    const scheduleSourcePrefix = `${schedule.id}:schedule:`
    for (const reason of [...task.pauseReasons]) {
      if (
        reason.type === "SCHEDULE" &&
        reason.sourceId.startsWith(scheduleSourcePrefix) &&
        (reason.sourceId !==
          `${scheduleSourcePrefix}${activePeriod?.id ?? ""}` ||
          !activePeriod?.autoPause)
      ) {
        removePauseReason(task, reason.sourceId)
        touchTask(task, at)
        assignment.workerIds.forEach((workerId) =>
          notification(
            draft,
            workerId,
            "BREAK_ENDED",
            `${reason.label} завершён.`,
            task.id,
            `break-ended:${reason.sourceId}:${local.localDate}:${workerId}`,
            at
          )
        )
      }
    }
    if (!activePeriod || !activePeriod.autoPause) {
      resumeIfClear(draft, task, at)
      continue
    }
    const sourceId = `${scheduleSourcePrefix}${activePeriod.id}`
    if (
      activePeriod.autoPause &&
      !task.pauseReasons.some((reason) => reason.sourceId === sourceId)
    ) {
      task.pauseReasons.push({
        id: crypto.randomUUID(),
        type: "SCHEDULE",
        sourceId,
        label: activePeriod.type === "LUNCH" ? "Обед" : "Перекур",
        createdAt: at,
      })
      task.status = "PAUSED"
      stopTaskTimer(task, at)
      assignment.status = "PAUSED"
      assignment.version += 1
      touchTask(task, at)
      assignment.workerIds.forEach((workerId) =>
        notification(
          draft,
          workerId,
          "BREAK_STARTED",
          activePeriod.type === "LUNCH" ? "Начался обед." : "Начался перекур.",
          task.id,
          `break-started:${activePeriod.id}:${local.localDate}:${workerId}`,
          at
        )
      )
    }
  }
}

export class BrowserTaskBoardClient
  implements TaskBoardSettingsClient, TaskBoardMockRuntimeClient
{
  private readonly store: BrowserTaskBoardStore

  constructor(store = new BrowserTaskBoardStore()) {
    this.store = store
  }

  subscribe(listener: () => void) {
    return this.store.subscribe(listener)
  }

  async listQueues(_token: string, warehouseId: string) {
    return this.store
      .read()
      .queues.filter((queue) => queue.warehouseId === warehouseId)
      .sort((left, right) => left.sortOrder - right.sortOrder)
  }

  async createQueue(
    _token: string,
    warehouseId: string,
    request: WorkQueueRequest
  ) {
    return this.store.mutate((draft) => {
      if (
        draft.queues.some(
          (queue) =>
            queue.warehouseId === warehouseId &&
            normalized(queue.code) === normalized(request.code)
        )
      ) {
        throw new TaskBoardMockValidationError("Код очереди уже используется.")
      }
      const nextSortOrder =
        Math.max(
          0,
          ...draft.queues
            .filter(
              (queue) =>
                queue.warehouseId === warehouseId && queue.type !== "HOLDING"
            )
            .map((queue) => queue.sortOrder)
        ) + 10
      const queue: WorkQueueDto = {
        ...request,
        id: crypto.randomUUID(),
        version: 0,
        warehouseId,
        sortOrder:
          request.type === "HOLDING" ? nextSortOrder + 10_000 : nextSortOrder,
        bindings: request.bindings.map((binding) => ({
          id: crypto.randomUUID(),
          version: 0,
          workerClass: workerClassById(draft, binding.workerClassId),
          stopTaskOnTake: binding.stopTaskOnTake,
        })),
      }
      draft.queues.push(queue)
      audit(draft, "QUEUE_CREATED", queue.id)
      return queue
    })
  }

  async updateQueue(
    _token: string,
    warehouseId: string,
    id: string,
    request: WorkQueueRequest
  ) {
    return this.store.mutate((draft) => {
      const queue = draft.queues.find(
        (item) => item.id === id && item.warehouseId === warehouseId
      )
      if (!queue) throw new TaskBoardMockValidationError("Очередь не найдена.")
      assertVersion(queue.version, request.version)
      if (
        normalized(queue.code) !== normalized(request.code) &&
        draft.tasks.some(
          (task) =>
            task.warehouseId === warehouseId &&
            (task.queueCode === queue.code ||
              task.route.some((step) => step.queueCode === queue.code))
        )
      ) {
        throw new TaskBoardMockConflictError(
          "Код используемой очереди изменить нельзя."
        )
      }
      if (
        draft.queues.some(
          (candidate) =>
            candidate.id !== id &&
            candidate.warehouseId === warehouseId &&
            normalized(candidate.code) === normalized(request.code)
        )
      ) {
        throw new TaskBoardMockValidationError("Код очереди уже используется.")
      }
      Object.assign(queue, request, {
        version: queue.version + 1,
        sortOrder: queue.sortOrder,
        bindings: request.bindings.map((binding) => ({
          id:
            queue.bindings.find(
              (candidate) => candidate.workerClass.id === binding.workerClassId
            )?.id ?? crypto.randomUUID(),
          version:
            (queue.bindings.find(
              (candidate) => candidate.workerClass.id === binding.workerClassId
            )?.version ?? -1) + 1,
          workerClass: workerClassById(draft, binding.workerClassId),
          stopTaskOnTake: binding.stopTaskOnTake,
        })),
      })
      audit(draft, "QUEUE_UPDATED", queue.id)
      return queue
    })
  }

  async deleteQueue(
    _token: string,
    warehouseId: string,
    id: string,
    expectedVersion: number
  ) {
    await this.store.mutate((draft) => {
      const queue = draft.queues.find(
        (item) => item.id === id && item.warehouseId === warehouseId
      )
      if (!queue) return
      assertVersion(queue.version, expectedVersion)
      if (draft.tasks.some((task) => task.queueId === id)) {
        throw new TaskBoardMockConflictError(
          "Используемую очередь можно только деактивировать."
        )
      }
      draft.queues = draft.queues.filter((item) => item.id !== id)
      draft.tombstones.push(id)
      audit(draft, "QUEUE_DELETED", id)
    })
  }

  async reorderQueues(
    _token: string,
    warehouseId: string,
    items: QueueOrderItem[]
  ) {
    return this.store.mutate((draft) => {
      const queues = draft.queues.filter(
        (queue) => queue.warehouseId === warehouseId
      )
      for (const item of items) {
        const queue = queues.find((candidate) => candidate.id === item.queueId)
        if (!queue)
          throw new TaskBoardMockValidationError("Очередь не найдена.")
        assertVersion(queue.version, item.expectedVersion)
      }
      const requested = items
        .map((item) => queues.find((queue) => queue.id === item.queueId)!)
        .filter((queue) => queue.type !== "HOLDING")
      const omitted = queues.filter(
        (queue) =>
          queue.type !== "HOLDING" &&
          !requested.some((candidate) => candidate.id === queue.id)
      )
      const holding = queues.filter((queue) => queue.type === "HOLDING")
      ;[...requested, ...omitted, ...holding].forEach((queue, index) => {
        queue.sortOrder = (index + 1) * 10
        queue.version += 1
      })
      audit(draft, "QUEUES_REORDERED", warehouseId)
      return queues.sort((left, right) => left.sortOrder - right.sortOrder)
    })
  }

  async listClasses(_token: string) {
    void _token
    return this.store
      .read()
      .classes.sort((left, right) => left.sortOrder - right.sortOrder)
  }

  async createClass(_token: string, request: WorkerClassRequest) {
    return this.store.mutate((draft) => {
      if (
        draft.classes.some(
          (item) => normalized(item.code) === normalized(request.code)
        )
      ) {
        throw new TaskBoardMockValidationError("Код класса уже используется.")
      }
      const item = { ...request, id: crypto.randomUUID(), version: 0 }
      draft.classes.push(item)
      audit(draft, "CLASS_CREATED", item.id)
      return item
    })
  }

  async updateClass(_token: string, id: string, request: WorkerClassRequest) {
    return this.store.mutate((draft) => {
      const item = workerClassById(draft, id)
      assertVersion(item.version, request.version)
      Object.assign(item, request, { version: item.version + 1 })
      draft.queues.forEach((queue) =>
        queue.bindings.forEach((binding) => {
          if (binding.workerClass.id === id) binding.workerClass = clone(item)
        })
      )
      draft.workers.forEach((worker) =>
        worker.qualifications.forEach((qualification) => {
          if (qualification.workerClass.id === id) {
            qualification.workerClass = clone(item)
          }
        })
      )
      draft.groups.forEach((group) => {
        if (group.workerClass.id === id) group.workerClass = clone(item)
      })
      audit(draft, "CLASS_UPDATED", id)
      return item
    })
  }

  async deleteClass(_token: string, id: string, expectedVersion: number) {
    await this.store.mutate((draft) => {
      const item = draft.classes.find((candidate) => candidate.id === id)
      if (!item) return
      assertVersion(item.version, expectedVersion)
      const used =
        draft.queues.some((queue) =>
          queue.bindings.some((binding) => binding.workerClass.id === id)
        ) ||
        draft.groups.some((group) => group.workerClass.id === id) ||
        draft.workers.some((worker) =>
          worker.qualifications.some(
            (qualification) => qualification.workerClass.id === id
          )
        )
      if (used) {
        throw new TaskBoardMockConflictError(
          "Используемый класс можно только деактивировать."
        )
      }
      draft.classes = draft.classes.filter((candidate) => candidate.id !== id)
      draft.tombstones.push(id)
      audit(draft, "CLASS_DELETED", id)
    })
  }

  async listWorkers(_token: string, warehouseId: string) {
    return this.store
      .read()
      .workers.filter((worker) => worker.warehouseId === warehouseId)
      .sort((left, right) =>
        left.displayName.localeCompare(right.displayName, "ru")
      )
  }

  async createWorker(
    _token: string,
    warehouseId: string,
    request: WorkerRequest
  ) {
    return this.store.mutate((draft) => {
      const worker: WorkerDto = {
        ...request,
        id: crypto.randomUUID(),
        version: 0,
        warehouseId,
        credentialStatus: request.appLogin ? "ACTIVE" : "NOT_CONFIGURED",
        credentialError: null,
        qualifications: request.qualifications.map((qualification) => ({
          id: crypto.randomUUID(),
          version: 0,
          workerClass: workerClassById(draft, qualification.workerClassId),
          active: qualification.active,
          comment: qualification.comment,
        })),
      }
      delete (worker as WorkerDto & { password?: string }).password
      draft.workers.push(worker)
      audit(draft, "WORKER_CREATED", worker.id)
      return worker
    })
  }

  async updateWorker(
    _token: string,
    warehouseId: string,
    id: string,
    request: WorkerRequest
  ) {
    return this.store.mutate((draft) => {
      const worker = draft.workers.find(
        (item) => item.id === id && item.warehouseId === warehouseId
      )
      if (!worker) throw new TaskBoardMockValidationError("Рабочий не найден.")
      assertVersion(worker.version, request.version)
      Object.assign(worker, {
        ...request,
        version: worker.version + 1,
        qualifications: request.qualifications.map((qualification) => ({
          id:
            worker.qualifications.find(
              (candidate) =>
                candidate.workerClass.id === qualification.workerClassId
            )?.id ?? crypto.randomUUID(),
          version:
            (worker.qualifications.find(
              (candidate) =>
                candidate.workerClass.id === qualification.workerClassId
            )?.version ?? -1) + 1,
          workerClass: workerClassById(draft, qualification.workerClassId),
          active: qualification.active,
          comment: qualification.comment,
        })),
      })
      delete (worker as WorkerDto & { password?: string }).password
      draft.groups.forEach((group) =>
        group.members.forEach((member) => {
          if (member.workerId === worker.id)
            member.workerName = worker.displayName
        })
      )
      audit(draft, "WORKER_UPDATED", worker.id)
      return worker
    })
  }

  async deleteWorker(
    _token: string,
    warehouseId: string,
    id: string,
    expectedVersion: number
  ) {
    await this.store.mutate((draft) => {
      const worker = draft.workers.find(
        (item) => item.id === id && item.warehouseId === warehouseId
      )
      if (!worker) return
      assertVersion(worker.version, expectedVersion)
      const used =
        draft.groups.some((group) =>
          group.members.some((member) => member.workerId === id)
        ) ||
        draft.assignments.some((assignment) =>
          assignment.workerIds.includes(id)
        )
      if (used) {
        throw new TaskBoardMockConflictError(
          "Используемого рабочего можно только деактивировать."
        )
      }
      draft.workers = draft.workers.filter((item) => item.id !== id)
      draft.tombstones.push(id)
      audit(draft, "WORKER_DELETED", id)
    })
  }

  async resetWorkerCredentials(
    _token: string,
    warehouseId: string,
    id: string,
    expectedVersion: number,
    _password: string
  ) {
    void _password
    return this.store.mutate((draft) => {
      const worker = draft.workers.find(
        (item) => item.id === id && item.warehouseId === warehouseId
      )
      if (!worker) throw new TaskBoardMockValidationError("Рабочий не найден.")
      assertVersion(worker.version, expectedVersion)
      worker.version += 1
      worker.credentialStatus = worker.appLogin ? "ACTIVE" : "NOT_CONFIGURED"
      worker.credentialError = null
      audit(draft, "WORKER_CREDENTIALS_RESET", id)
      return worker
    })
  }

  async disableWorkerCredentials(
    _token: string,
    warehouseId: string,
    id: string,
    expectedVersion: number
  ) {
    return this.store.mutate((draft) => {
      const worker = draft.workers.find(
        (item) => item.id === id && item.warehouseId === warehouseId
      )
      if (!worker) throw new TaskBoardMockValidationError("Рабочий не найден.")
      assertVersion(worker.version, expectedVersion)
      worker.version += 1
      worker.credentialStatus = "NOT_CONFIGURED"
      worker.credentialError = null
      audit(draft, "WORKER_CREDENTIALS_DISABLED", id)
      return worker
    })
  }

  async listGroups(_tokenOrWarehouseId: string, warehouseId?: string) {
    const effectiveWarehouseId = warehouseId ?? _tokenOrWarehouseId
    return this.store
      .read()
      .groups.filter((group) => group.warehouseId === effectiveWarehouseId)
      .sort((left, right) => left.name.localeCompare(right.name, "ru"))
  }

  async createGroup(
    _token: string,
    warehouseId: string,
    request: WorkerGroupRequest
  ) {
    return this.store.mutate((draft) => {
      const groupClass = workerClassById(draft, request.workerClassId)
      const group: WorkerGroupDto = {
        ...request,
        id: crypto.randomUUID(),
        version: 0,
        warehouseId,
        workerClass: groupClass,
        members: request.members.map((member) => {
          const worker = draft.workers.find(
            (candidate) =>
              candidate.id === member.workerId &&
              candidate.warehouseId === warehouseId
          )
          if (!worker) {
            throw new TaskBoardMockValidationError("Рабочий бригады не найден.")
          }
          if (member.active) {
            assertGroupMemberQualification(worker, groupClass.id)
          }
          return {
            id: crypto.randomUUID(),
            version: 0,
            workerId: worker.id,
            workerName: worker.displayName,
            roleInGroup: member.roleInGroup,
            active: member.active,
          }
        }),
      }
      draft.groups.push(group)
      audit(draft, "GROUP_CREATED", group.id)
      return group
    })
  }

  async updateGroup(
    _token: string,
    warehouseId: string,
    id: string,
    request: WorkerGroupRequest
  ) {
    return this.store.mutate((draft) => {
      const group = draft.groups.find(
        (item) => item.id === id && item.warehouseId === warehouseId
      )
      if (!group) throw new TaskBoardMockValidationError("Бригада не найдена.")
      assertVersion(group.version, request.version)
      const groupClass = workerClassById(draft, request.workerClassId)
      const members = request.members.map((member) => {
        const worker = draft.workers.find(
          (candidate) =>
            candidate.id === member.workerId &&
            candidate.warehouseId === warehouseId
        )
        if (!worker) {
          throw new TaskBoardMockValidationError("Рабочий бригады не найден.")
        }
        if (member.active) {
          assertGroupMemberQualification(worker, groupClass.id)
        }
        const current = group.members.find(
          (candidate) => candidate.workerId === worker.id
        )
        return {
          id: current?.id ?? crypto.randomUUID(),
          version: (current?.version ?? -1) + 1,
          workerId: worker.id,
          workerName: worker.displayName,
          roleInGroup: member.roleInGroup,
          active: member.active,
        }
      })
      Object.assign(group, request, {
        version: group.version + 1,
        workerClass: groupClass,
        members,
      })
      audit(draft, "GROUP_UPDATED", group.id)
      return group
    })
  }

  async deleteGroup(
    _token: string,
    warehouseId: string,
    id: string,
    expectedVersion: number
  ) {
    await this.store.mutate((draft) => {
      const group = draft.groups.find(
        (item) => item.id === id && item.warehouseId === warehouseId
      )
      if (!group) return
      assertVersion(group.version, expectedVersion)
      if (
        group.members.length > 0 ||
        draft.assignments.some((assignment) => assignment.workerGroupId === id)
      ) {
        throw new TaskBoardMockConflictError(
          "Используемую бригаду можно только деактивировать."
        )
      }
      draft.groups = draft.groups.filter((item) => item.id !== id)
      draft.schedules = draft.schedules.filter(
        (schedule) => schedule.workerGroupId !== id
      )
      draft.tombstones.push(id)
      audit(draft, "GROUP_DELETED", id)
    })
  }

  async listSchedules(warehouseId: string) {
    return this.store
      .read()
      .schedules.filter((schedule) => schedule.warehouseId === warehouseId)
  }

  async saveSchedule(
    warehouseId: string,
    groupId: string,
    request: GroupScheduleRequest
  ) {
    validateSchedule(request.days, request.returnGraceMinutes)
    const timezone = validatedTimezone(request.timezone)
    return this.store.mutate((draft) => {
      const group = draft.groups.find(
        (candidate) =>
          candidate.id === groupId && candidate.warehouseId === warehouseId
      )
      if (!group) throw new TaskBoardMockValidationError("Бригада не найдена.")
      const schedule = draft.schedules.find(
        (candidate) => candidate.workerGroupId === groupId
      )
      if (schedule) {
        assertVersion(schedule.version, request.version)
        Object.assign(schedule, clone(request), {
          timezone,
          version: schedule.version + 1,
        })
        audit(draft, "SCHEDULE_UPDATED", schedule.id)
        evaluateRuntime(draft, nowIso(draft.clock))
        return schedule
      }
      if (request.version !== 0) throw new TaskBoardMockConflictError()
      const created: GroupScheduleDto = {
        ...clone(request),
        timezone,
        id: crypto.randomUUID(),
        version: 0,
        warehouseId,
        workerGroupId: groupId,
      }
      draft.schedules.push(created)
      audit(draft, "SCHEDULE_CREATED", created.id)
      evaluateRuntime(draft, nowIso(draft.clock))
      return created
    })
  }

  async copySchedule(warehouseId: string, request: CopyGroupScheduleRequest) {
    return this.store.mutate((draft) => {
      const source = draft.schedules.find(
        (schedule) =>
          schedule.workerGroupId === request.sourceGroupId &&
          schedule.warehouseId === warehouseId
      )
      if (!source) throw new TaskBoardMockValidationError("График не найден.")
      assertVersion(source.version, request.sourceExpectedVersion)
      const timezone = validatedTimezone(source.timezone)
      validateSchedule(source.days, source.returnGraceMinutes)
      const copied = request.targets.map((target) => {
        const schedule = draft.schedules.find(
          (candidate) =>
            candidate.workerGroupId === target.groupId &&
            candidate.warehouseId === warehouseId
        )
        if (!schedule) {
          throw new TaskBoardMockValidationError(
            "У целевой бригады нет графика."
          )
        }
        assertVersion(schedule.version, target.expectedVersion)
        schedule.timezone = timezone
        schedule.returnGraceMinutes = source.returnGraceMinutes
        schedule.days = clone(source.days).map((day) => ({
          ...day,
          restPeriods: day.restPeriods.map((period) => ({
            ...period,
            id: crypto.randomUUID(),
            version: 0,
          })),
        }))
        schedule.version += 1
        audit(draft, "SCHEDULE_COPIED", schedule.id, {
          sourceGroupId: request.sourceGroupId,
        })
        return schedule
      })
      evaluateRuntime(draft, nowIso(draft.clock))
      return copied
    })
  }

  async getSimulationClock(): Promise<SimulationClockSnapshotDto> {
    const clock = this.store.read().clock
    return { ...clock, now: nowIso(clock) }
  }

  async updateSimulationClock(
    request: UpdateSimulationClockRequest
  ): Promise<SimulationClockSnapshotDto> {
    return this.store.mutate((draft) => {
      assertVersion(draft.clock.version, request.expectedVersion)
      const currentNow = request.now ?? nowIso(draft.clock)
      draft.clock = {
        ...draft.clock,
        mode: request.mode ?? draft.clock.mode,
        running: request.running ?? draft.clock.running,
        speed: request.speed ?? draft.clock.speed,
        activeWorkerId:
          request.activeWorkerId === undefined
            ? draft.clock.activeWorkerId
            : request.activeWorkerId,
        anchorSimulatedAt: currentNow,
        anchorRealAt: new Date().toISOString(),
        version: draft.clock.version + 1,
      }
      evaluateRuntime(draft, currentNow)
      return { ...draft.clock, now: currentNow }
    })
  }

  async resetSimulationClock(expectedVersion: number) {
    return this.store.mutate((draft) => {
      assertVersion(draft.clock.version, expectedVersion)
      const now = new Date().toISOString()
      draft.clock = {
        ...draft.clock,
        version: draft.clock.version + 1,
        mode: "REAL",
        running: true,
        speed: 1,
        anchorSimulatedAt: now,
        anchorRealAt: now,
      }
      evaluateRuntime(draft, now)
      return { ...draft.clock, now }
    })
  }

  async getSnapshot(warehouseId: string): Promise<MockTaskBoardSnapshotDto> {
    await this.evaluate()
    const envelope = this.store.read()
    const taskIds = new Set(
      envelope.tasks
        .filter((task) => task.warehouseId === warehouseId)
        .map((task) => task.id)
    )
    return {
      warehouseId,
      revision: envelope.revision,
      now: nowIso(envelope.clock),
      queues: envelope.queues
        .filter(
          (queue) =>
            queue.warehouseId === warehouseId && queue.active && !queue.hidden
        )
        .sort((left, right) => left.sortOrder - right.sortOrder),
      tasks: envelope.tasks.filter((task) => taskIds.has(task.id)),
      assignments: envelope.assignments.filter((assignment) =>
        taskIds.has(assignment.taskId)
      ),
      groups: envelope.groups.filter(
        (group) => group.warehouseId === warehouseId
      ),
      workers: envelope.workers.filter(
        (worker) => worker.warehouseId === warehouseId
      ),
      interruptions: envelope.interruptions.filter(
        (interruption) =>
          taskIds.has(interruption.interruptedTaskId) ||
          taskIds.has(interruption.interruptingTaskId)
      ),
    }
  }

  async registerTask(command: RegisterMockTaskCommand) {
    return this.store.mutate((draft) => {
      const existing = draft.tasks.find(
        (task) => task.externalTaskId === command.externalTaskId
      )
      if (existing) return existing
      const queue = command.queueCode
        ? draft.queues.find(
            (candidate) =>
              candidate.warehouseId === command.warehouseId &&
              candidate.code === command.queueCode &&
              candidate.active &&
              !candidate.hidden
          )
        : null
      if (command.queueCode && !queue) {
        throw new TaskBoardMockValidationError("Активная очередь не найдена.")
      }
      const at = nowIso(draft.clock)
      const task: MockTaskDto = {
        id: crypto.randomUUID(),
        version: 0,
        externalTaskId: command.externalTaskId,
        warehouseId: command.warehouseId,
        title: command.title.trim(),
        description: command.description?.trim() || null,
        cabinNumber: command.cabinNumber?.trim() || null,
        queueId: queue?.id ?? null,
        queueCode: queue?.code ?? null,
        queuePosition:
          Math.max(
            0,
            ...draft.tasks
              .filter((candidate) => candidate.queueId === queue?.id)
              .map((candidate) => candidate.queuePosition)
          ) + 10,
        status: "QUEUED",
        demo: command.demo ?? false,
        route: (command.route ?? []).map((step, index) => ({
          id: crypto.randomUUID(),
          ...step,
          position: index,
        })),
        activeRouteIndex: 0,
        assignmentId: null,
        pauseReasons: [],
        elapsedSeconds: 0,
        activeSince: null,
        createdAt: at,
        updatedAt: at,
      }
      draft.tasks.push(task)
      audit(draft, "TASK_REGISTERED", task.id, {
        externalTaskId: task.externalTaskId,
      })
      return task
    })
  }

  async syncRegisteredTask(command: SyncRegisteredTaskCommand) {
    return this.store.mutate((draft) => {
      if (!command.externalTaskId.startsWith("repair:")) {
        throw new TaskBoardMockValidationError(
          "Синхронизация изменяемого маршрута разрешена только ремонтным заданиям."
        )
      }
      const task = draft.tasks.find(
        (candidate) =>
          candidate.warehouseId === command.warehouseId &&
          candidate.externalTaskId === command.externalTaskId
      )
      if (!task) throw new TaskBoardMockValidationError("Задание не найдено.")
      assertVersion(task.version, command.expectedVersion)
      if (
        command.sourceStatus === "ACTIVE" &&
        (task.status === "DONE" || task.status === "CANCELLED")
      ) {
        throw new TaskBoardMockConflictError(
          "Терминальное задание нельзя вернуть в работу синхронизацией."
        )
      }
      const title = command.title.trim()
      if (!title) {
        throw new TaskBoardMockValidationError("Укажите название задания.")
      }
      if (command.sourceStatus === "ACTIVE" && command.route.length === 0) {
        throw new TaskBoardMockValidationError(
          "Активное ремонтное задание должно содержать маршрут."
        )
      }
      const externalStepIds = command.route.map((step) =>
        step.externalStepId.trim()
      )
      if (
        externalStepIds.some((id) => !id) ||
        new Set(externalStepIds).size !== externalStepIds.length
      ) {
        throw new TaskBoardMockValidationError(
          "Этапы ремонтного маршрута должны иметь уникальные externalStepId."
        )
      }
      const allQueuesByCode = new Map(
        draft.queues
          .filter((queue) => queue.warehouseId === command.warehouseId)
          .map((queue) => [queue.code, queue])
      )
      const activeQueuesByCode = new Map(
        [...allQueuesByCode].filter(([, queue]) => queue.active)
      )

      const isUnstarted =
        task.status === "QUEUED" &&
        task.assignmentId === null &&
        task.activeRouteIndex === 0 &&
        task.elapsedSeconds === 0
      const lockedCount = isUnstarted
        ? 0
        : Math.min(task.activeRouteIndex + 1, task.route.length)
      if (command.route.length < lockedCount) {
        throw new TaskBoardMockConflictError(
          "Нельзя удалить завершённый или текущий этап маршрута."
        )
      }
      for (let index = 0; index < lockedCount; index += 1) {
        const current = task.route[index]
        const incoming = command.route[index]
        if (
          (current.externalStepId &&
            current.externalStepId !== incoming.externalStepId) ||
          current.queueCode !== incoming.queueCode
        ) {
          throw new TaskBoardMockConflictError(
            "Завершённый и текущий этапы нельзя удалить, переставить или перенести в другую очередь."
          )
        }
      }
      const currentByExternalStepId = new Map(
        task.route.flatMap((step) =>
          step.externalStepId ? [[step.externalStepId, step] as const] : []
        )
      )
      if (command.sourceStatus === "ACTIVE") {
        command.route.forEach((step, index) => {
          const existing = currentByExternalStepId.get(step.externalStepId)
          const locked = index < lockedCount
          const currentUnchanged =
            existing?.position === task.activeRouteIndex &&
            index === task.activeRouteIndex &&
            existing.queueCode === step.queueCode
          const futureUnchanged =
            existing !== undefined &&
            existing.position === index &&
            existing.queueCode === step.queueCode &&
            existing.title === (step.title.trim() || "Этап ремонта")
          if (
            !locked &&
            !currentUnchanged &&
            !futureUnchanged &&
            !activeQueuesByCode.has(step.queueCode)
          ) {
            throw new TaskBoardMockValidationError(
              `Новая или изменяемая очередь этапа «${step.queueCode}» не найдена или неактивна.`
            )
          }
        })
      }
      const description = command.description?.trim() || null
      const cabinNumber = command.cabinNumber?.trim() || null
      const routeMatches =
        task.route.length === command.route.length &&
        task.route.every((step, index) => {
          const incoming = command.route[index]
          return (
            step.externalStepId === incoming.externalStepId &&
            step.queueCode === incoming.queueCode &&
            step.title === (incoming.title.trim() || "Этап ремонта") &&
            step.position === index
          )
        })
      const sourceStatusMatches =
        (command.sourceStatus === "ACTIVE" &&
          task.status !== "DONE" &&
          task.status !== "CANCELLED") ||
        (command.sourceStatus === "CANCELLED" && task.status === "CANCELLED") ||
        (command.sourceStatus === "COMPLETED" && task.status === "DONE")
      if (
        task.title === title &&
        task.description === description &&
        task.cabinNumber === cabinNumber &&
        routeMatches &&
        sourceStatusMatches
      ) {
        return task
      }
      const previousCurrentStep = task.route[task.activeRouteIndex] ?? null
      const nextRoute = command.route.map((step, index) => {
        const locked = index < lockedCount ? task.route[index] : null
        const existing =
          locked ?? currentByExternalStepId.get(step.externalStepId) ?? null
        return {
          id: existing?.id ?? crypto.randomUUID(),
          externalStepId: step.externalStepId,
          queueCode: locked?.queueCode ?? step.queueCode,
          title: step.title.trim() || "Этап ремонта",
          position: index,
        }
      })
      const at = nowIso(draft.clock)
      task.title = title
      task.description = description
      task.cabinNumber = cabinNumber
      task.route = nextRoute
      if (isUnstarted) {
        task.activeRouteIndex = 0
        const firstStep = nextRoute[0]
        const firstQueue = firstStep
          ? (activeQueuesByCode.get(firstStep.queueCode) ??
            allQueuesByCode.get(firstStep.queueCode))
          : null
        const preservesCurrentQueue =
          firstStep?.externalStepId === previousCurrentStep?.externalStepId &&
          firstStep?.queueCode === task.queueCode
        task.queueId = preservesCurrentQueue
          ? task.queueId
          : (firstQueue?.id ?? null)
        task.queueCode = firstStep?.queueCode ?? null
      }

      const assignment = currentAssignment(draft, task)
      if (command.sourceStatus === "CANCELLED") {
        if (task.status === "DONE") {
          throw new TaskBoardMockConflictError(
            "Завершённое runtime-задание нельзя отменить синхронизацией."
          )
        }
        evaluateRuntime(draft, at)
        stopTaskTimer(task, at)
        task.status = "CANCELLED"
        task.pauseReasons = []
        if (assignment) {
          assignment.status = "CANCELLED"
          assignment.endedAt = at
          assignment.version += 1
        }
        for (const interruption of draft.interruptions) {
          if (interruption.interruptingTaskId === task.id) {
            const interrupted = draft.tasks.find(
              (candidate) => candidate.id === interruption.interruptedTaskId
            )
            if (interrupted) {
              removePauseReason(interrupted, interruption.id)
              resumeIfClear(draft, interrupted, at)
              touchTask(interrupted, at)
            }
            interruption.state = "CANCELLED"
            interruption.version += 1
          } else if (interruption.interruptedTaskId === task.id) {
            interruption.state = "CANCELLED"
            interruption.version += 1
          }
        }
      } else if (command.sourceStatus === "COMPLETED") {
        if (task.status === "CANCELLED") {
          throw new TaskBoardMockConflictError(
            "Отменённое runtime-задание нельзя завершить синхронизацией."
          )
        }
        stopTaskTimer(task, at)
        task.status = "DONE"
        task.pauseReasons = []
        if (assignment) {
          assignment.status = "DONE"
          assignment.endedAt = at
          assignment.version += 1
        }
        for (const interruption of draft.interruptions) {
          if (
            interruption.interruptingTaskId === task.id &&
            interruption.state === "ACTIVE"
          ) {
            const interruptedTask = draft.tasks.find(
              (candidate) => candidate.id === interruption.interruptedTaskId
            )
            const interruptedAssignment = interruptedTask
              ? currentAssignment(draft, interruptedTask)
              : null
            const schedule = draft.schedules.find(
              (candidate) =>
                candidate.workerGroupId === interruption.workerGroupId
            )
            interruption.state = "RETURNING"
            interruption.returnDeadline = new Date(
              Date.parse(at) +
                safeReturnGraceMinutes(schedule?.returnGraceMinutes ?? 3) *
                  60_000
            ).toISOString()
            interruption.version += 1
            if (interruptedTask) {
              interruptedTask.status = "RETURNING"
              touchTask(interruptedTask, at)
            }
            if (interruptedAssignment) {
              interruptedAssignment.status = "RETURNING"
              interruptedAssignment.version += 1
              interruptedAssignment.workerIds.forEach((workerId) =>
                notification(
                  draft,
                  workerId,
                  "RETURN_STARTED",
                  `Вернитесь к заданию «${interruptedTask?.title ?? ""}».`,
                  interruptedTask?.id ?? null,
                  `return-started:${interruption.id}:${workerId}`,
                  at
                )
              )
            }
          } else if (interruption.interruptedTaskId === task.id) {
            interruption.state = "CANCELLED"
            interruption.version += 1
          }
        }
      }
      touchTask(task, at)
      audit(draft, "REPAIR_TASK_SYNCHRONIZED", task.id, {
        sourceStatus: command.sourceStatus,
      })
      return task
    })
  }

  async findByExternalTaskId(warehouseId: string, externalTaskId: string) {
    return (
      this.store
        .read()
        .tasks.find(
          (task) =>
            task.warehouseId === warehouseId &&
            task.externalTaskId === externalTaskId
        ) ?? null
    )
  }

  async moveTask(command: MoveMockTaskCommand) {
    return this.store.mutate((draft) => {
      const task = draft.tasks.find(
        (candidate) => candidate.id === command.taskId
      )
      if (!task) throw new TaskBoardMockValidationError("Задание не найдено.")
      assertVersion(task.version, command.expectedVersion)
      if (task.status !== "QUEUED") {
        throw new TaskBoardMockConflictError(
          "Перемещать между очередями можно только ожидающее задание."
        )
      }
      const queue = command.targetQueueId
        ? draft.queues.find(
            (candidate) =>
              candidate.id === command.targetQueueId &&
              candidate.warehouseId === task.warehouseId
          )
        : null
      task.queueId = queue?.id ?? null
      task.queueCode = queue?.code ?? null
      task.queuePosition = command.queuePosition
      touchTask(task, nowIso(draft.clock))
      audit(draft, "TASK_MOVED", task.id)
      return task
    })
  }

  async takeTask(command: TakeMockTaskCommand) {
    return this.store.mutate((draft) => {
      const at = nowIso(draft.clock)
      const task = draft.tasks.find(
        (candidate) => candidate.id === command.taskId
      )
      if (!task) throw new TaskBoardMockValidationError("Задание не найдено.")
      assertVersion(task.version, command.expectedVersion)
      if (task.status !== "QUEUED") {
        throw new TaskBoardMockConflictError("Задание уже принято.")
      }
      const group = draft.groups.find(
        (candidate) =>
          candidate.id === command.workerGroupId &&
          candidate.warehouseId === task.warehouseId &&
          candidate.active
      )
      const queue = taskQueue(draft, task)
      if (!group || !queue || !eligibleWorkers(group, queue)) {
        throw new TaskBoardMockValidationError(
          "Бригада не может взять это задание."
        )
      }
      const availableWorkerIds = group.members
        .filter((member) => member.active)
        .map((member) => member.workerId)
      const workerIds = command.workerIds?.length
        ? command.workerIds.filter((id) => availableWorkerIds.includes(id))
        : availableWorkerIds
      if (
        workerIds.length === 0 ||
        (command.workerIds?.length &&
          workerIds.length !== new Set(command.workerIds).size)
      ) {
        throw new TaskBoardMockValidationError("Выберите исполнителей.")
      }
      const selectedWorkers = workerIds.map((workerId) =>
        draft.workers.find((worker) => worker.id === workerId)
      )
      if (
        selectedWorkers.some(
          (worker) =>
            !worker ||
            !workerHasQualification(worker, group.workerClass.id) ||
            !workerEligibleForQueue(worker, queue)
        )
      ) {
        throw new TaskBoardMockValidationError(
          "У выбранного исполнителя нет активной квалификации для задания."
        )
      }
      const assignment = {
        id: crypto.randomUUID(),
        version: 0,
        taskId: task.id,
        workerGroupId: group.id,
        workerIds,
        status: "ACTIVE" as const,
        startedAt: at,
        endedAt: null,
      }
      draft.assignments.push(assignment)
      task.assignmentId = assignment.id
      task.status = "IN_PROGRESS"
      task.activeSince = at
      touchTask(task, at)

      const stopOnTake = queue.bindings.some(
        (binding) => binding.stopTaskOnTake
      )
      if (queue.type === "MOVEMENT" && stopOnTake) {
        for (const interruptedAssignment of draft.assignments) {
          if (
            interruptedAssignment.id === assignment.id ||
            !interruptedAssignment.workerIds.some((workerId) =>
              workerIds.includes(workerId)
            ) ||
            interruptedAssignment.status !== "ACTIVE"
          ) {
            continue
          }
          const interruptedTask = draft.tasks.find(
            (candidate) => candidate.id === interruptedAssignment.taskId
          )
          if (!interruptedTask || interruptedTask.status !== "IN_PROGRESS")
            continue
          const interruption: TaskInterruptionDto = {
            id: crypto.randomUUID(),
            version: 0,
            interruptedTaskId: interruptedTask.id,
            interruptingTaskId: task.id,
            workerGroupId: group.id,
            state: "ACTIVE",
            interruptedAt: at,
            returnDeadline: null,
            resumedAt: null,
          }
          draft.interruptions.push(interruption)
          interruptedTask.pauseReasons.push({
            id: crypto.randomUUID(),
            type: "INTERRUPTION",
            sourceId: interruption.id,
            label: `Прервано заданием «${task.title}»`,
            createdAt: at,
          })
          interruptedTask.status = "PAUSED"
          stopTaskTimer(interruptedTask, at)
          interruptedAssignment.status = "PAUSED"
          interruptedAssignment.version += 1
          touchTask(interruptedTask, at)
          interruptedAssignment.workerIds.forEach((workerId) =>
            notification(
              draft,
              workerId,
              "TASK_INTERRUPTED",
              `Задание «${interruptedTask.title}» остановлено. Перейдите к «${task.title}».`,
              task.id,
              `task-interrupted:${interruption.id}:${workerId}`,
              at
            )
          )
        }
      }
      workerIds.forEach((workerId) =>
        notification(
          draft,
          workerId,
          "TASK_ASSIGNED",
          `Назначено задание «${task.title}».`,
          task.id,
          `task-assigned:${assignment.id}:${workerId}`,
          at
        )
      )
      audit(draft, "TASK_TAKEN", task.id, { groupId: group.id })
      return task
    })
  }

  async pauseTask(command: MockTaskVersionCommand) {
    return this.store.mutate((draft) => {
      const task = draft.tasks.find(
        (candidate) => candidate.id === command.taskId
      )
      if (!task) throw new TaskBoardMockValidationError("Задание не найдено.")
      assertVersion(task.version, command.expectedVersion)
      const at = nowIso(draft.clock)
      if (!task.pauseReasons.some((reason) => reason.sourceId === "manual")) {
        task.pauseReasons.push({
          id: crypto.randomUUID(),
          type: "MANUAL",
          sourceId: "manual",
          label: "Остановлено вручную",
          createdAt: at,
        })
      }
      task.status = "PAUSED"
      stopTaskTimer(task, at)
      const assignment = currentAssignment(draft, task)
      if (assignment) {
        assignment.status = "PAUSED"
        assignment.version += 1
      }
      touchTask(task, at)
      audit(draft, "TASK_PAUSED", task.id)
      return task
    })
  }

  async resumeTask(command: MockTaskVersionCommand) {
    return this.store.mutate((draft) => {
      const task = draft.tasks.find(
        (candidate) => candidate.id === command.taskId
      )
      if (!task) throw new TaskBoardMockValidationError("Задание не найдено.")
      assertVersion(task.version, command.expectedVersion)
      const at = nowIso(draft.clock)
      removePauseReason(task, "manual")
      resumeIfClear(draft, task, at)
      touchTask(task, at)
      audit(draft, "TASK_RESUME_REQUESTED", task.id, {
        resumed: task.status === "IN_PROGRESS",
      })
      return task
    })
  }

  async completeTask(command: MockTaskVersionCommand) {
    return this.store.mutate((draft) => {
      const task = draft.tasks.find(
        (candidate) => candidate.id === command.taskId
      )
      if (!task) throw new TaskBoardMockValidationError("Задание не найдено.")
      assertVersion(task.version, command.expectedVersion)
      const at = nowIso(draft.clock)
      const assignment = currentAssignment(draft, task)
      const queue = taskQueue(draft, task)
      if (assignment) {
        assignment.status = "DONE"
        assignment.endedAt = at
        assignment.version += 1
      }
      stopTaskTimer(task, at)
      task.assignmentId = null
      if (task.activeRouteIndex + 1 < task.route.length) {
        task.activeRouteIndex += 1
        const next = task.route[task.activeRouteIndex]
        const nextQueue = draft.queues.find(
          (candidate) =>
            candidate.warehouseId === task.warehouseId &&
            candidate.code === next.queueCode
        )
        task.queueId = nextQueue?.id ?? null
        task.queueCode = nextQueue?.code ?? null
        task.status = "QUEUED"
      } else {
        task.status = "DONE"
      }
      touchTask(task, at)
      if (queue?.type === "MOVEMENT") {
        for (const interruption of draft.interruptions) {
          if (
            interruption.interruptingTaskId !== task.id ||
            interruption.state !== "ACTIVE"
          ) {
            continue
          }
          const schedule = draft.schedules.find(
            (candidate) =>
              candidate.workerGroupId === interruption.workerGroupId
          )
          const interruptedTask = draft.tasks.find(
            (candidate) => candidate.id === interruption.interruptedTaskId
          )
          const interruptedAssignment = interruptedTask
            ? currentAssignment(draft, interruptedTask)
            : null
          interruption.state = "RETURNING"
          interruption.returnDeadline = new Date(
            Date.parse(at) +
              safeReturnGraceMinutes(schedule?.returnGraceMinutes ?? 3) * 60_000
          ).toISOString()
          interruption.version += 1
          if (interruptedTask) {
            interruptedTask.status = "RETURNING"
            touchTask(interruptedTask, at)
          }
          if (interruptedAssignment) {
            interruptedAssignment.status = "RETURNING"
            interruptedAssignment.version += 1
            interruptedAssignment.workerIds.forEach((workerId) =>
              notification(
                draft,
                workerId,
                "RETURN_STARTED",
                `Вернитесь к заданию «${interruptedTask?.title ?? ""}».`,
                interruptedTask?.id ?? null,
                `return-started:${interruption.id}:${workerId}`,
                at
              )
            )
          }
        }
      }
      audit(draft, "TASK_COMPLETED", task.id)
      return task
    })
  }

  async cancelTask(command: MockTaskVersionCommand) {
    return this.store.mutate((draft) => {
      const task = draft.tasks.find(
        (candidate) => candidate.id === command.taskId
      )
      if (!task) throw new TaskBoardMockValidationError("Задание не найдено.")
      if (task.status === "CANCELLED") return task
      assertVersion(task.version, command.expectedVersion)
      if (task.status === "DONE") {
        throw new TaskBoardMockConflictError(
          "Завершённое задание нельзя отменить."
        )
      }
      const at = nowIso(draft.clock)
      evaluateRuntime(draft, at)
      task.status = "CANCELLED"
      stopTaskTimer(task, at)
      const assignment = currentAssignment(draft, task)
      if (assignment) {
        assignment.status = "CANCELLED"
        assignment.endedAt = at
        assignment.version += 1
      }
      for (const interruption of draft.interruptions) {
        if (interruption.interruptingTaskId !== task.id) continue
        const interrupted = draft.tasks.find(
          (candidate) => candidate.id === interruption.interruptedTaskId
        )
        if (interrupted) {
          removePauseReason(interrupted, interruption.id)
          resumeIfClear(draft, interrupted, at)
          touchTask(interrupted, at)
        }
        interruption.state = "CANCELLED"
        interruption.version += 1
      }
      touchTask(task, at)
      audit(draft, "TASK_CANCELLED", task.id)
      return task
    })
  }

  async confirmGroupReturned(command: ConfirmGroupReturnedCommand) {
    return this.store.mutate((draft) => {
      if (
        new Set(command.interruptions.map((item) => item.id)).size !==
        command.interruptions.length
      ) {
        throw new TaskBoardMockValidationError(
          "Прерывания возврата не должны повторяться."
        )
      }
      const at = nowIso(draft.clock)
      evaluateRuntime(draft, at)
      const selected = command.interruptions.map((requested) => {
        const interruption = draft.interruptions.find(
          (candidate) => candidate.id === requested.id
        )
        if (!interruption) {
          throw new TaskBoardMockConflictError("Прерывание уже недоступно.")
        }
        assertVersion(interruption.version, requested.expectedVersion)
        if (
          interruption.workerGroupId !== command.workerGroupId ||
          interruption.state !== "RETURNING"
        ) {
          throw new TaskBoardMockConflictError(
            "Состояние возвращения бригады уже изменилось."
          )
        }
        const task = draft.tasks.find(
          (candidate) => candidate.id === interruption.interruptedTaskId
        )
        if (!task) {
          throw new TaskBoardMockConflictError(
            "Прерванное задание больше не найдено."
          )
        }
        return { interruption, task }
      })
      const resumed: MockTaskDto[] = []
      for (const { interruption, task } of selected) {
        removePauseReason(task, interruption.id)
        resumeIfClear(draft, task, at)
        touchTask(task, at)
        interruption.state = "RESUMED"
        interruption.resumedAt = at
        interruption.version += 1
        resumed.push(task)
      }
      if (selected.length > 0) {
        audit(draft, "GROUP_RETURN_CONFIRMED", command.workerGroupId, {
          resumedTasks: resumed.length,
        })
      }
      return resumed
    })
  }

  async listNotifications(workerId?: string) {
    const envelope = this.store.read()
    const effectiveWorkerId = workerId ?? envelope.clock.activeWorkerId
    return envelope.notifications
      .filter(
        (item) => !effectiveWorkerId || item.workerId === effectiveWorkerId
      )
      .sort((left, right) => right.createdAt.localeCompare(left.createdAt))
  }

  async markNotificationRead(id: string, expectedVersion: number) {
    return this.store.mutate((draft) => {
      const item = draft.notifications.find((candidate) => candidate.id === id)
      if (!item)
        throw new TaskBoardMockValidationError("Уведомление не найдено.")
      assertVersion(item.version, expectedVersion)
      if (!item.readAt) {
        item.readAt = nowIso(draft.clock)
        item.version += 1
      }
      return item
    })
  }

  async markAllNotificationsRead(command: MarkAllNotificationsReadCommand) {
    return this.store.mutate((draft) => {
      const effectiveWorkerId = command.workerId ?? draft.clock.activeWorkerId
      if (
        new Set(command.notifications.map((item) => item.id)).size !==
        command.notifications.length
      ) {
        throw new TaskBoardMockValidationError(
          "Уведомления для отметки не должны повторяться."
        )
      }
      const selected = command.notifications.map((requested) => {
        const item = draft.notifications.find(
          (candidate) => candidate.id === requested.id
        )
        if (!item) {
          throw new TaskBoardMockConflictError("Уведомление уже недоступно.")
        }
        assertVersion(item.version, requested.expectedVersion)
        if (effectiveWorkerId && item.workerId !== effectiveWorkerId) {
          throw new TaskBoardMockValidationError(
            "Нельзя отметить уведомление другого рабочего."
          )
        }
        return item
      })
      const at = nowIso(draft.clock)
      const changed: WorkerNotificationDto[] = []
      for (const item of selected) {
        if (!item.readAt) {
          item.readAt = at
          item.version += 1
          changed.push(item)
        }
      }
      return changed
    })
  }

  async evaluate(at?: string) {
    return this.store.mutate((draft) => {
      const effectiveAt = at ?? nowIso(draft.clock)
      evaluateRuntime(draft, effectiveAt)
      return effectiveAt
    })
  }

  resetForTests() {
    this.store.resetForTests()
  }
}

export const taskBoardMockClient = new BrowserTaskBoardClient()
