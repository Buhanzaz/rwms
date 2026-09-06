import type { RepairEstimateCatalogIndex } from "@/features/repair-estimate-catalog/api/repair-estimate-catalog-api"
import { buildRepairEstimateTaskPlans } from "@/features/repair-estimates/domain/repair-estimate-domain"
import type {
  RepairEstimateLineDto,
  RepairEstimateTaskPlanDto,
} from "@/features/repair-estimates/model/repair-estimate"
import type {
  RepairTaskDto,
  RepairTaskEditorDraft,
  RepairTaskReworkSeed,
  RepairTaskSubtaskDto,
} from "@/features/repair-tasks/model/repair-task"

function createOpaqueId(prefix: string) {
  void prefix
  if (typeof crypto !== "undefined" && "randomUUID" in crypto) {
    return crypto.randomUUID()
  }
  throw new Error("Браузер не поддерживает создание безопасного ключа команды.")
}

function cloneLine(line: RepairEstimateLineDto) {
  return {
    ...line,
    catalogSnapshot: line.catalogSnapshot ? { ...line.catalogSnapshot } : null,
    customQueueBinding: line.catalogSnapshot
      ? null
      : line.customQueueBinding
        ? { ...line.customQueueBinding }
        : null,
  }
}

function commentsForLines(lines: RepairEstimateLineDto[]) {
  return Array.from(
    new Set(
      lines
        .filter((line) => line.lineType === "WORK")
        .map((line) => line.lineComment.trim())
        .filter(Boolean)
    )
  ).join("; ")
}

function executionRequirements(
  lines: RepairEstimateLineDto[],
  catalog: RepairEstimateCatalogIndex | null
) {
  let hasDuration = false
  let plannedDurationMinutes = 0

  lines
    .filter((line) => line.lineType === "WORK")
    .forEach((line) => {
      const nodeId = line.catalogSnapshot?.nodeId
      const node = nodeId ? catalog?.nodesById.get(nodeId) : null
      if (!node) {
        return
      }
      if (
        typeof node.durationMinutes === "number" &&
        Number.isFinite(node.durationMinutes) &&
        node.durationMinutes >= 0
      ) {
        hasDuration = true
        plannedDurationMinutes +=
          node.durationMinutes * Math.max(0, line.quantity)
      }
    })

  return {
    plannedDurationMinutes: hasDuration
      ? Math.max(0, Math.round(plannedDurationMinutes))
      : null,
  }
}

function snapshotSubtask(
  plan: Pick<
    RepairEstimateTaskPlanDto,
    | "id"
    | "kind"
    | "includedLineIds"
    | "groupComment"
    | "queueId"
    | "queueName"
    | "routeQueueKind"
    | "sortOrder"
  > & { kind: "REPAIR_WORK" },
  lines: RepairEstimateLineDto[],
  catalog: RepairEstimateCatalogIndex | null
): RepairTaskSubtaskDto {
  const requirements = executionRequirements(lines, catalog)
  return {
    id: plan.id || createOpaqueId("repair-subtask"),
    kind: plan.kind,
    status: "WAITING",
    workLines: lines.filter((line) => line.lineType === "WORK").map(cloneLine),
    materialLines: lines
      .filter((line) => line.lineType === "MATERIAL")
      .map(cloneLine),
    groupComment: plan.groupComment.trim() || commentsForLines(lines),
    queueId: plan.queueId ?? null,
    queueName: plan.queueName?.trim() || null,
    routeQueueKind: plan.routeQueueKind,
    sortOrder: plan.sortOrder,
    queuePosition: plan.sortOrder,
    plannedDurationMinutes: requirements.plannedDurationMinutes,
    startedAt: null,
    completedAt: null,
    activeStartedAt: null,
    activeWorkSeconds: 0,
    workerGroup: null,
    assignments: [],
  }
}

export function buildRepairTaskSubtasks(params: {
  lines: RepairEstimateLineDto[]
  plans: RepairEstimateTaskPlanDto[]
  catalog?: RepairEstimateCatalogIndex | null
}) {
  const lineById = new Map(params.lines.map((line) => [line.id, line]))
  const assignedLineIds = new Set<string>()
  const subtasks: RepairTaskSubtaskDto[] = []

  params.plans
    .slice()
    .sort((left, right) => left.sortOrder - right.sortOrder)
    .forEach((plan) => {
      if (plan.kind !== "REPAIR_WORK") {
        throw new Error(
          "План прямого ремонта может содержать только этапы ремонтных работ."
        )
      }
      const lines = plan.includedLineIds
        .map((lineId) => lineById.get(lineId))
        .filter((line): line is RepairEstimateLineDto => Boolean(line))
        .filter((line) => {
          if (assignedLineIds.has(line.id)) {
            return false
          }
          assignedLineIds.add(line.id)
          return true
        })
      if (lines.length > 0) {
        subtasks.push(snapshotSubtask(plan, lines, params.catalog ?? null))
      }
    })

  const unassignedLines = params.lines.filter(
    (line) => !assignedLineIds.has(line.id)
  )
  if (unassignedLines.length > 0) {
    const fallback = snapshotSubtask(
      {
        id: createOpaqueId("repair-subtask"),
        kind: "REPAIR_WORK",
        includedLineIds: unassignedLines.map((line) => line.id),
        groupComment: commentsForLines(unassignedLines),
        queueId: null,
        queueName: null,
        routeQueueKind: null,
        sortOrder: (subtasks.length + 1) * 10,
      },
      unassignedLines,
      params.catalog ?? null
    )
    subtasks.push(fallback)
  }

  return subtasks.map((subtask, index) => ({
    ...subtask,
    sortOrder: (index + 1) * 10,
    queuePosition: (index + 1) * 10,
  }))
}

export function buildDirectRepairTaskSubtasks(
  lines: RepairEstimateLineDto[],
  catalog: RepairEstimateCatalogIndex
) {
  return buildRepairTaskSubtasks({
    lines,
    plans: buildRepairEstimateTaskPlans(lines, catalog),
    catalog,
  })
}

export function assertRepairTaskCanBeQueued(lines: RepairEstimateLineDto[]) {
  if (!lines.some((line) => line.description.trim().length > 0)) {
    throw new Error("Добавьте хотя бы одну содержательную работу или материал")
  }
}

export function canEditRepairTaskPlan(task: RepairTaskDto) {
  if (task.status === "DRAFT") return true
  if (task.status !== "QUEUED" || task.startedAt !== null) return false
  return task.subtasks.every(
    (subtask) =>
      subtask.status === "WAITING" &&
      subtask.startedAt === null &&
      subtask.activeStartedAt === null &&
      subtask.assignments.every((assignment) => assignment.startedAt === null)
  )
}

export function createNewRepairTaskDraft(
  dispatchDate: string,
  seed?: RepairTaskReworkSeed,
  creationIdempotencyKey: string = createOpaqueId("repair-create")
): RepairTaskEditorDraft {
  return {
    creationIdempotencyKey,
    taskId: null,
    expectedVersion: null,
    kind: seed ? "REWORK" : "REPAIR",
    origin: seed?.sourceOrigin ?? "DIRECT_REPAIR",
    sourceRepairTaskId: seed?.sourceRepairTaskId ?? null,
    sourceRepairTaskVersion: seed?.sourceRepairTaskVersion ?? null,
    sourceEstimateId: seed?.sourceEstimateId ?? null,
    sourceEstimateVersion: seed?.sourceEstimateVersion ?? null,
    rentalItemId: seed?.rentalItemId ?? "",
    reason: "",
    dispatchDate,
    comment: "",
    lines: seed?.lines.map(cloneLine) ?? [],
    media: [],
    maintenanceMediaReferences: [],
    coverMediaId: null,
    forceCapitalRepair: seed?.forceCapitalRepair ?? false,
    pendingUploads: [],
  }
}

export function toRepairTaskEditorDraft(
  task: RepairTaskDto
): RepairTaskEditorDraft {
  return {
    creationIdempotencyKey: task.id,
    taskId: task.id,
    expectedVersion: task.version,
    kind: task.kind,
    origin: task.origin,
    sourceRepairTaskId: task.sourceRepairTaskId,
    sourceRepairTaskVersion: task.sourceRepairTaskVersion,
    sourceEstimateId: task.sourceEstimateId,
    sourceEstimateVersion: task.sourceEstimateVersion,
    rentalItemId: task.rentalItemId,
    reason: task.sourceParty ?? "",
    dispatchDate: task.dispatchDate,
    comment: "",
    lines: task.subtasks.flatMap((subtask) => [
      ...subtask.workLines.map(cloneLine),
      ...subtask.materialLines.map(cloneLine),
    ]),
    media: [],
    maintenanceMediaReferences: task.maintenanceMediaReferences ?? [],
    coverMediaId: task.coverMediaId ?? null,
    forceCapitalRepair: task.forceCapitalRepair === true,
    pendingUploads: [],
  }
}
