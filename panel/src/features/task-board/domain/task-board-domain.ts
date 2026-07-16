import type { RepairEstimateCatalogSnapshotDto } from "@/features/repair-estimate-catalog/model/repair-estimate-catalog"
import type {
  RepairTaskDto,
  RepairTaskSubtaskDto,
} from "@/features/repair-tasks/model/repair-task"
import type {
  TaskBoardEntryDto,
  TaskBoardQueueDto,
  TaskBoardSnapshotDto,
} from "@/features/task-board/model/task-board"
import type {
  MockAssignmentStatus,
  MockTaskBoardSnapshotDto,
  MockTaskDto,
  MockTaskStatus,
} from "@/features/task-board/mock/model"
import type { WorkQueueDto } from "@/features/settings/task-board/model/task-board-settings"

const QUEUE_LABELS: Record<string, string> = {
  INTERNAL_WORKS: "Внутренние работы",
  EXTERNAL_WORKS: "Внешние работы",
  SANITARY_DISINFECTION: "СЭС и санитария",
  WELDING: "Сварочные работы",
  PLUMBING: "Сантехника",
  ELECTRICS: "Электрика",
}

const QUEUE_PRIORITY: Record<string, number> = {
  INTERNAL_WORKS: 10,
  EXTERNAL_WORKS: 20,
  ELECTRICS: 30,
  PLUMBING: 40,
  WELDING: 50,
  SANITARY_DISINFECTION: 60,
}

function queueCodeLabel(code: string) {
  return (
    QUEUE_LABELS[code] ??
    code
      .toLocaleLowerCase("ru")
      .split("_")
      .filter(Boolean)
      .map((part) => part[0]?.toLocaleUpperCase("ru") + part.slice(1))
      .join(" ")
  )
}

export function taskBoardQueueKey(subtask: RepairTaskSubtaskDto) {
  if (subtask.queueCode) {
    return `code:${subtask.queueCode}`
  }
  if (subtask.kind === "MOVE_TO_REPAIR") {
    return "kind:MOVE_TO_REPAIR"
  }
  if (subtask.kind === "MOVE_FROM_REPAIR") {
    return "kind:MOVE_FROM_REPAIR"
  }
  if (subtask.routeQueueKind) {
    return `route:${subtask.routeQueueKind}`
  }
  return "unassigned"
}

function createSyntheticQueues(): TaskBoardQueueDto[] {
  return [
    {
      key: "kind:MOVE_TO_REPAIR",
      label: "Перемещение на ремонт",
      kind: "MOVEMENT",
      queueCode: null,
      routeQueueKind: "MOVEMENT",
      entries: [],
    },
    {
      key: "kind:MOVE_FROM_REPAIR",
      label: "Перемещение с ремонта",
      kind: "MOVEMENT",
      queueCode: null,
      routeQueueKind: "MOVEMENT",
      entries: [],
    },
    {
      key: "route:MOVEMENT",
      label: "Прочие перемещения",
      kind: "MOVEMENT",
      queueCode: null,
      routeQueueKind: "MOVEMENT",
      entries: [],
    },
    {
      key: "route:REPAIR",
      label: "Общие ремонтные работы",
      kind: "REPAIR",
      queueCode: null,
      routeQueueKind: "REPAIR",
      entries: [],
    },
    {
      key: "unassigned",
      label: "Без очереди",
      kind: "UNASSIGNED",
      queueCode: null,
      routeQueueKind: null,
      entries: [],
    },
    {
      key: "route:HOLDING",
      label: "Ожидание",
      kind: "HOLDING",
      queueCode: null,
      routeQueueKind: "HOLDING",
      entries: [],
    },
  ]
}

function createCatalogQueues(
  catalog: RepairEstimateCatalogSnapshotDto
): TaskBoardQueueDto[] {
  const byCode = new Map<string, TaskBoardQueueDto>()
  for (const node of catalog.nodes) {
    const code = node.workQueueCode?.trim()
    if (!node.active || !code || byCode.has(code)) {
      continue
    }
    const kind = node.routeQueueKind ?? "REPAIR"
    byCode.set(code, {
      key: `code:${code}`,
      label: queueCodeLabel(code),
      kind,
      queueCode: code,
      routeQueueKind: kind,
      entries: [],
    })
  }
  return Array.from(byCode.values()).sort(
    (left, right) =>
      (QUEUE_PRIORITY[left.queueCode ?? ""] ?? 1_000) -
        (QUEUE_PRIORITY[right.queueCode ?? ""] ?? 1_000) ||
      left.label.localeCompare(right.label, "ru")
  )
}

function queueGroupOrder(queue: TaskBoardQueueDto) {
  if (queue.key === "kind:MOVE_TO_REPAIR") return 0
  if (queue.kind === "REPAIR") return 10
  if (queue.kind === "MOVEMENT") {
    return queue.key === "kind:MOVE_FROM_REPAIR" ? 25 : 20
  }
  if (queue.kind === "UNASSIGNED") return 30
  return 40
}

function createEntry(
  task: RepairTaskDto,
  subtask: RepairTaskSubtaskDto,
  routeIndex: number,
  routeLength: number
): TaskBoardEntryDto {
  return {
    id: `${task.id}:${subtask.id}`,
    queueKey: taskBoardQueueKey(subtask),
    entryType: routeIndex === 0 ? "REAL" : "SHADOW",
    routeIndex,
    routeLength,
    queuePosition: subtask.queuePosition,
    task,
    subtask,
  }
}

export function createTaskBoardSnapshot(params: {
  warehouseId: string
  tasks: RepairTaskDto[]
  catalog: RepairEstimateCatalogSnapshotDto
}): TaskBoardSnapshotDto {
  const queues = [
    ...createCatalogQueues(params.catalog),
    ...createSyntheticQueues(),
  ]
  const queueByKey = new Map(queues.map((queue) => [queue.key, queue]))
  const entries: TaskBoardEntryDto[] = []

  for (const task of params.tasks) {
    if (task.status !== "QUEUED" && task.status !== "IN_PROGRESS") {
      continue
    }
    const unfinished = task.subtasks
      .filter(
        (subtask) => subtask.status !== "DONE" && subtask.status !== "CANCELLED"
      )
      .sort((left, right) => left.sortOrder - right.sortOrder)
    unfinished.forEach((subtask, routeIndex) => {
      const entry = createEntry(task, subtask, routeIndex, unfinished.length)
      entries.push(entry)
      const queue = queueByKey.get(entry.queueKey)
      if (queue) {
        queue.entries.push(entry)
        return
      }
      const dynamicQueue: TaskBoardQueueDto = {
        key: entry.queueKey,
        label: subtask.queueCode
          ? queueCodeLabel(subtask.queueCode)
          : "Без очереди",
        kind: subtask.routeQueueKind ?? "UNASSIGNED",
        queueCode: subtask.queueCode,
        routeQueueKind: subtask.routeQueueKind,
        entries: [entry],
      }
      queues.push(dynamicQueue)
      queueByKey.set(dynamicQueue.key, dynamicQueue)
    })
  }

  queues.forEach((queue) =>
    queue.entries.sort(
      (left, right) =>
        left.queuePosition - right.queuePosition ||
        left.task.createdAt.localeCompare(right.task.createdAt) ||
        left.id.localeCompare(right.id)
    )
  )
  queues.sort(
    (left, right) =>
      queueGroupOrder(left) - queueGroupOrder(right) ||
      (QUEUE_PRIORITY[left.queueCode ?? ""] ?? 1_000) -
        (QUEUE_PRIORITY[right.queueCode ?? ""] ?? 1_000) ||
      left.label.localeCompare(right.label, "ru")
  )

  return {
    warehouseId: params.warehouseId,
    queues,
    totalEntries: entries.length,
    realEntries: entries.filter((entry) => entry.entryType === "REAL").length,
    shadowEntries: entries.filter((entry) => entry.entryType === "SHADOW")
      .length,
  }
}

export function taskBoardEntryTitle(subtask: RepairTaskSubtaskDto) {
  if (subtask.kind === "MOVE_TO_REPAIR") return "Перемещение на ремонт"
  if (subtask.kind === "MOVE_FROM_REPAIR") return "Перемещение с ремонта"
  const firstWork = subtask.workLines[0]?.description.trim()
  const firstMaterial = subtask.materialLines[0]?.description.trim()
  const first = firstWork || firstMaterial
  const extra = subtask.workLines.length + subtask.materialLines.length - 1
  return first
    ? `${first}${extra > 0 ? ` · ещё ${extra}` : ""}`
    : "Ремонтные работы"
}

export function taskBoardQueuePositionAt(
  entries: TaskBoardEntryDto[],
  entryId: string
) {
  const index = entries.findIndex((entry) => entry.id === entryId)
  const withoutActive = entries.filter((entry) => entry.id !== entryId)
  const insertionIndex = Math.max(0, Math.min(index, withoutActive.length))
  const previous = withoutActive[insertionIndex - 1]
  const next = withoutActive[insertionIndex]
  if (!previous && !next) return 10
  if (!previous) return next.queuePosition - 10
  if (!next) return previous.queuePosition + 10
  return (previous.queuePosition + next.queuePosition) / 2
}

export function canMoveEntryToQueue(
  entry: TaskBoardEntryDto,
  queue: TaskBoardQueueDto
) {
  if (entry.runtimeTask && entry.entryType === "SHADOW") return false
  if (entry.runtimeTask && entry.runtimeTask.status !== "QUEUED") return false
  if (entry.subtask.status === "IN_PROGRESS") return false
  if (entry.subtask.kind === "MOVE_TO_REPAIR") {
    return queue.queueCode !== null || queue.key === "kind:MOVE_TO_REPAIR"
  }
  if (entry.subtask.kind === "MOVE_FROM_REPAIR") {
    return queue.queueCode !== null || queue.key === "kind:MOVE_FROM_REPAIR"
  }
  return (
    queue.key !== "kind:MOVE_TO_REPAIR" && queue.key !== "kind:MOVE_FROM_REPAIR"
  )
}

function runtimeTaskStatus(
  status: MockTaskStatus
): RepairTaskSubtaskDto["status"] {
  if (status === "IN_PROGRESS") return "IN_PROGRESS"
  if (status === "PAUSED" || status === "RETURNING") return "PAUSED"
  if (status === "DONE") return "DONE"
  if (status === "CANCELLED") return "CANCELLED"
  return "WAITING"
}

function runtimeAssignmentStatus(
  status: MockAssignmentStatus
): RepairTaskSubtaskDto["assignments"][number]["status"] {
  if (status === "ACTIVE") return "ACTIVE"
  if (status === "DONE") return "DONE"
  if (status === "CANCELLED") return "CANCELLED"
  return "PAUSED"
}

function runtimeQueueKind(queue: WorkQueueDto | undefined) {
  if (!queue) return null
  return queue.type
}

function runtimeRepairTask(
  task: MockTaskDto,
  snapshot: MockTaskBoardSnapshotDto
): RepairTaskDto {
  const assignment = snapshot.assignments.find(
    (candidate) => candidate.id === task.assignmentId
  )
  const group = snapshot.groups.find(
    (candidate) => candidate.id === assignment?.workerGroupId
  )
  const workers = (assignment?.workerIds ?? [])
    .map((workerId) =>
      snapshot.workers.find((worker) => worker.id === workerId)
    )
    .filter((worker): worker is NonNullable<typeof worker> => Boolean(worker))
  const activeStep = task.route[task.activeRouteIndex]
  const queue = snapshot.queues.find(
    (candidate) => candidate.code === (task.queueCode ?? activeStep?.queueCode)
  )
  const assignmentSnapshot = assignment
    ? workers.map((worker) => ({
        id: `${assignment.id}:${worker.id}`,
        worker: { id: worker.id, name: worker.displayName },
        assignedAt: assignment.startedAt,
        startedAt: assignment.startedAt,
        pausedAt:
          assignment.status === "PAUSED" || assignment.status === "RETURNING"
            ? task.updatedAt
            : null,
        finishedAt: assignment.endedAt,
        activeStartedAt:
          assignment.status === "ACTIVE" ? task.activeSince : null,
        activeWorkSeconds: task.elapsedSeconds,
        status: runtimeAssignmentStatus(assignment.status),
      }))
    : []

  return {
    id: task.id,
    version: task.version,
    status:
      task.status === "DONE"
        ? "COMPLETED"
        : task.status === "CANCELLED"
          ? "CANCELLED"
          : task.status === "QUEUED"
            ? "QUEUED"
            : "IN_PROGRESS",
    kind: "REPAIR",
    origin: "DIRECT_REPAIR",
    acceptanceStatus: "NOT_READY",
    startedAt: assignment?.startedAt ?? null,
    completedAt: task.status === "DONE" ? task.updatedAt : null,
    acceptanceDecidedAt: null,
    acceptanceDecidedBy: null,
    acceptanceComment: null,
    warehouseId: task.warehouseId,
    rentalItemId: task.cabinNumber ?? task.id,
    cabinNumber: task.cabinNumber ?? "Без бытовки",
    authorName: "MOCK-доска",
    reason: task.description ?? task.title,
    dispatchDate: null,
    comment: task.description ?? "",
    media: [],
    subtasks: [
      {
        id: activeStep?.id ?? `${task.id}:active`,
        kind:
          runtimeQueueKind(queue) === "MOVEMENT"
            ? "MOVE_TO_REPAIR"
            : "REPAIR_WORK",
        status: runtimeTaskStatus(task.status),
        workLines: [],
        materialLines: [],
        groupComment: task.description ?? "",
        queueCode: task.queueCode ?? activeStep?.queueCode ?? null,
        routeQueueKind: runtimeQueueKind(queue),
        sortOrder: task.activeRouteIndex,
        queuePosition: task.queuePosition,
        plannedDurationMinutes: null,
        photoRequired: false,
        startedAt: assignment?.startedAt ?? null,
        completedAt: task.status === "DONE" ? task.updatedAt : null,
        activeStartedAt: task.activeSince,
        activeWorkSeconds: task.elapsedSeconds,
        workerGroup: group ? { id: group.id, name: group.name } : null,
        assignments: assignmentSnapshot,
        resultMedia: [],
        assigneeName:
          workers.map((worker) => worker.displayName).join(", ") || null,
      },
    ],
    sourceEstimateId: null,
    sourceEstimateVersion: null,
    sourceInventoryId: null,
    sourceInventoryFindingId: null,
    sourceRepairTaskId: null,
    sourceRepairTaskVersion: null,
    createdAt: task.createdAt,
    updatedAt: task.updatedAt,
  }
}

function runtimeQueueKey(code: string | null) {
  return code ? `code:${code}` : "unassigned"
}

export function createMockTaskBoardProjection(
  snapshot: MockTaskBoardSnapshotDto
): TaskBoardSnapshotDto {
  const configuredQueues: TaskBoardQueueDto[] = snapshot.queues
    .filter((queue) => queue.active && !queue.hidden)
    .sort(
      (left, right) =>
        (left.type === "HOLDING" ? 1 : 0) -
          (right.type === "HOLDING" ? 1 : 0) ||
        left.sortOrder - right.sortOrder ||
        left.name.localeCompare(right.name, "ru")
    )
    .map((queue) => ({
      key: runtimeQueueKey(queue.code),
      label: queue.name,
      kind: queue.type,
      queueCode: queue.code,
      routeQueueKind: queue.type,
      settingsQueueId: queue.id,
      settingsCollapsed: queue.collapsed,
      entries: [],
    }))
  const unassigned: TaskBoardQueueDto = {
    key: "unassigned",
    label: "Без очереди",
    kind: "UNASSIGNED",
    queueCode: null,
    routeQueueKind: null,
    settingsQueueId: null,
    settingsCollapsed: false,
    entries: [],
  }
  const holdingQueues = configuredQueues.filter(
    (queue) => queue.kind === "HOLDING"
  )
  const queues = [
    ...configuredQueues.filter((queue) => queue.kind !== "HOLDING"),
    unassigned,
    ...holdingQueues,
  ]
  const byKey = new Map(queues.map((queue) => [queue.key, queue]))
  const entries: TaskBoardEntryDto[] = []

  for (const runtimeTask of snapshot.tasks) {
    if (runtimeTask.status === "DONE" || runtimeTask.status === "CANCELLED") {
      continue
    }
    const task = runtimeRepairTask(runtimeTask, snapshot)
    const remainingRoute = runtimeTask.route.slice(runtimeTask.activeRouteIndex)
    const route =
      remainingRoute.length > 0
        ? remainingRoute
        : [
            {
              id: `${runtimeTask.id}:active`,
              queueCode: runtimeTask.queueCode ?? "",
              title: runtimeTask.title,
              position: runtimeTask.activeRouteIndex,
            },
          ]
    route.forEach((step, index) => {
      const requestedKey = runtimeQueueKey(
        index === 0 ? (runtimeTask.queueCode ?? step.queueCode) : step.queueCode
      )
      const queue = byKey.get(requestedKey) ?? unassigned
      const subtask: RepairTaskSubtaskDto = {
        ...task.subtasks[0]!,
        id: step.id,
        status: index === 0 ? runtimeTaskStatus(runtimeTask.status) : "WAITING",
        queueCode: queue.queueCode,
        routeQueueKind: queue.routeQueueKind,
        sortOrder: step.position,
        queuePosition: runtimeTask.queuePosition,
        workerGroup: index === 0 ? task.subtasks[0]!.workerGroup : null,
        assignments: index === 0 ? task.subtasks[0]!.assignments : [],
        assigneeName: index === 0 ? task.subtasks[0]!.assigneeName : null,
      }
      const interruption = snapshot.interruptions.find(
        (candidate) =>
          candidate.interruptedTaskId === runtimeTask.id &&
          (candidate.state === "ACTIVE" || candidate.state === "RETURNING")
      )
      const entry: TaskBoardEntryDto = {
        id: `${runtimeTask.id}:${step.id}`,
        queueKey: queue.key,
        entryType: index === 0 ? "REAL" : "SHADOW",
        routeIndex: runtimeTask.activeRouteIndex + index,
        routeLength: runtimeTask.route.length || 1,
        queuePosition: runtimeTask.queuePosition,
        task: { ...task, subtasks: [subtask] },
        subtask,
        runtimeTask,
        interruption,
        detailsHref: runtimeTask.externalTaskId.startsWith("repair:")
          ? `/repairs?repairId=${encodeURIComponent(runtimeTask.externalTaskId.slice("repair:".length))}`
          : null,
      }
      queue.entries.push(entry)
      entries.push(entry)
    })
  }

  queues.forEach((queue) =>
    queue.entries.sort(
      (left, right) =>
        left.queuePosition - right.queuePosition ||
        left.task.createdAt.localeCompare(right.task.createdAt) ||
        left.id.localeCompare(right.id)
    )
  )

  return {
    warehouseId: snapshot.warehouseId,
    now: snapshot.now,
    queues,
    totalEntries: entries.length,
    realEntries: entries.filter((entry) => entry.entryType === "REAL").length,
    shadowEntries: entries.filter((entry) => entry.entryType === "SHADOW")
      .length,
  }
}

export function mergeQueueCollapsedSettings(params: {
  current: Set<string>
  previous: Map<string, boolean> | null
  queues: TaskBoardQueueDto[]
  reset: boolean
}) {
  const settings = new Map(
    params.queues.map((queue) => [queue.key, Boolean(queue.settingsCollapsed)])
  )
  if (params.reset || !params.previous) {
    return {
      collapsed: new Set(
        [...settings]
          .filter(([, collapsed]) => collapsed)
          .map(([queueKey]) => queueKey)
      ),
      settings,
    }
  }
  const collapsed = new Set(params.current)
  settings.forEach((next, queueKey) => {
    const previous = params.previous?.get(queueKey)
    if (previous === undefined || previous === next) return
    if (next) collapsed.add(queueKey)
    else collapsed.delete(queueKey)
  })
  return { collapsed, settings }
}
