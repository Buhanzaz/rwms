import type {
  MaintenanceEstimate,
  MaintenanceEstimateLine,
  MaintenanceMediaReference,
  MaintenancePlanStageInput,
  MaintenanceRepair,
} from "@/features/maintenance/api/maintenance-api"
import { canonicalClientUuid } from "@/features/maintenance/maintenance-runtime"
import type {
  RepairEstimateDto,
  RepairEstimateLineDto,
  RepairEstimateMediaRefDto,
  RepairEstimateTaskPlanCommandDto,
  RepairEstimateTaskPlanDto,
} from "@/features/repair-estimates/model/repair-estimate"
import type {
  RepairTaskDto,
  RepairTaskSubtaskDto,
} from "@/features/repair-tasks/model/repair-task"

function currentRevision(estimate: MaintenanceEstimate) {
  const revision =
    estimate.revisions.find(
      (candidate) => candidate.revision === estimate.currentRevision
    ) ?? estimate.revisions.at(-1)
  if (!revision) {
    throw new Error("Сервис вернул смету без текущей ревизии.")
  }
  return revision
}

function routeKind(value: string) {
  return value === "MOVEMENT" || value === "REPAIR" || value === "HOLDING"
    ? value
    : null
}

export function toViewMedia(
  reference: MaintenanceMediaReference
): RepairEstimateMediaRefDto {
  return {
    id: reference.mediaId,
    generation: reference.generation,
    opaqueOnly: true,
    fileName: reference.mediaId,
    mimeType: "application/octet-stream",
    kind: "IMAGE",
    rotationDegrees: 0,
    variants: {
      small: {
        url: "",
        storageRef: reference.mediaId,
        mimeType: "application/octet-stream",
        width: null,
        height: null,
      },
      largeWebp: {
        url: "",
        storageRef: reference.mediaId,
        mimeType: "application/octet-stream",
        width: null,
        height: null,
      },
    },
    processingStatus: "READY",
    originalAvailable: false,
    createdAt: "",
  }
}

export function toCanonicalMedia(
  references: RepairEstimateMediaRefDto[]
): MaintenanceMediaReference[] {
  return references.map((reference) => {
    if (
      !Number.isSafeInteger(reference.generation) ||
      reference.generation! < 0
    ) {
      throw new Error(
        "Медиа без подтверждённого поколения нельзя прикрепить к производственной команде."
      )
    }
    return { mediaId: reference.id, generation: reference.generation! }
  })
}

function toViewLine(line: MaintenanceEstimateLine): RepairEstimateLineDto {
  const nodeType = line.catalogSnapshot?.nodeType
  const viewNodeType =
    nodeType === "WORK" || nodeType === "MATERIAL" || nodeType === "OPTION"
      ? nodeType
      : null
  return {
    id: line.id,
    sourceLineKey: line.id,
    lineType: nodeType === "MATERIAL" ? "MATERIAL" : "WORK",
    description: line.description,
    lineComment: line.comment ?? "",
    unit: line.catalogSnapshot?.unit ?? "",
    quantity: Number(line.quantity),
    unitPrice: line.unitPrice,
    lineTotal: line.lineTotal,
    catalogSnapshot:
      line.catalogSnapshot && viewNodeType
        ? {
            nodeId: line.catalogSnapshot.nodeId,
            catalogVersionId: line.catalogSnapshot.catalogVersionId,
            code: line.catalogSnapshot.code,
            name: line.catalogSnapshot.name,
            nodeType: viewNodeType,
            unit: line.catalogSnapshot.unit,
            unitPrice: line.catalogSnapshot.unitPrice,
            durationMinutes: line.catalogSnapshot.durationMinutes,
            queueId: line.catalogSnapshot.routing?.queueId ?? null,
            queueCode: line.catalogSnapshot.routing?.queueCode ?? null,
            queueKind: routeKind(line.catalogSnapshot.routing?.queueKind ?? ""),
          }
        : null,
  }
}

function toViewPlan(
  stage: MaintenancePlanStageInput
): RepairEstimateTaskPlanDto {
  return {
    id: stage.id,
    kind: stage.kind,
    includedLineIds: [],
    primaryLineId: null,
    groupComment: "",
    queueId: stage.routing.queueId,
    queueCode: stage.routing.queueCode,
    routeQueueKind: routeKind(stage.routing.queueKind),
    sortOrder: (stage.order + 1) * 10,
    generationStatus: "PENDING_GENERATION",
    workflowRequestRef: null,
  }
}

export function toViewEstimate(
  estimate: MaintenanceEstimate
): RepairEstimateDto {
  const revision = currentRevision(estimate)
  return {
    id: estimate.id,
    version: estimate.version,
    status: estimate.lifecycle,
    warehouseId: estimate.warehouseId,
    rentalItemId: estimate.rentalItemId,
    cabinNumber: estimate.rentalItemId,
    authorName: estimate.actor.actorId,
    sourceParty: revision.sourceParty ?? "",
    destinationText: null,
    dispatchDate: revision.dispatchDate,
    comment: "",
    totalAmount: revision.total,
    lines: revision.lines.map(toViewLine),
    media: estimate.mediaReferences.map(toViewMedia),
    completionMode: revision.plan.length > 0 ? "MANUAL" : null,
    movementRequired: revision.plan.some(
      (stage) => stage.kind !== "REPAIR_WORK"
    ),
    taskPlans: revision.plan.map(toViewPlan),
    createdAt: estimate.createdAt,
    updatedAt: revision.recordedAt,
  }
}

function decimalQuantity(value: number) {
  if (!Number.isFinite(value) || value < 0) {
    throw new Error("Количество в строке сметы некорректно.")
  }
  return String(value)
}

export async function toEstimateLineInput(line: RepairEstimateLineDto) {
  const snapshot = line.catalogSnapshot
  const completeSnapshot =
    snapshot?.catalogVersionId &&
    snapshot.unitPrice !== undefined &&
    snapshot.durationMinutes !== undefined
      ? {
          catalogVersionId: snapshot.catalogVersionId,
          nodeId: snapshot.nodeId,
          code: snapshot.code,
          nodeType: snapshot.nodeType,
          name: snapshot.name,
          unit: snapshot.unit ?? null,
          unitPrice: snapshot.unitPrice ?? null,
          durationMinutes: snapshot.durationMinutes,
          routing:
            snapshot.queueId && snapshot.queueCode && snapshot.queueKind
              ? {
                  queueId: snapshot.queueId,
                  queueCode: snapshot.queueCode,
                  queueKind: snapshot.queueKind,
                }
              : null,
        }
      : null
  return {
    id: await canonicalClientUuid(line.id),
    catalogSnapshot: completeSnapshot,
    description: line.description.trim(),
    quantity: decimalQuantity(line.quantity),
    unitPrice: line.unitPrice,
    comment: line.lineComment.trim() || null,
    mediaReferences: [],
  }
}

export async function toPlanStageInput(
  plan: RepairEstimateTaskPlanDto | RepairEstimateTaskPlanCommandDto,
  order: number
): Promise<MaintenancePlanStageInput> {
  if (!plan.queueId || !plan.queueCode || !plan.routeQueueKind) {
    throw new Error(
      "Для этапа ремонта не подтверждена каноническая очередь. Обновите каталог."
    )
  }
  return {
    id: await canonicalClientUuid(plan.id),
    kind: plan.kind,
    order,
    routing: {
      queueId: plan.queueId,
      queueCode: plan.queueCode,
      queueKind: plan.routeQueueKind,
    },
    taskDeadline: null,
  }
}

function stageStatus(
  state: MaintenanceRepair["plan"]["stages"][number]["state"]
): RepairTaskSubtaskDto["status"] {
  switch (state) {
    case "PLANNED":
    case "QUEUED":
      return "WAITING"
    case "IN_PROGRESS":
      return "IN_PROGRESS"
    case "DONE":
      return "DONE"
    case "CANCELLED":
      return "CANCELLED"
  }
}

function toViewSubtask(
  stage: MaintenanceRepair["plan"]["stages"][number]
): RepairTaskSubtaskDto {
  return {
    id: stage.id,
    kind: stage.kind,
    status: stageStatus(stage.state),
    workLines: [],
    materialLines: [],
    groupComment: "",
    queueCode: stage.routing.queueCode,
    queueId: stage.routing.queueId,
    routeQueueKind: routeKind(stage.routing.queueKind),
    sortOrder: (stage.order + 1) * 10,
    queuePosition: (stage.order + 1) * 10,
    plannedDurationMinutes: null,
    photoRequired: false,
    startedAt: null,
    completedAt: stage.completedAt,
    activeStartedAt: null,
    activeWorkSeconds: 0,
    workerGroup: null,
    assignments: [],
    resultMedia: [],
    assigneeName: null,
  }
}

export function toViewRepair(repair: MaintenanceRepair): RepairTaskDto {
  const terminalDecision =
    repair.acceptanceState === "ACCEPTED" ||
    repair.acceptanceState === "WRITTEN_OFF"
  return {
    id: repair.id,
    version: repair.version,
    status: repair.executionState,
    kind: repair.kind === "PRIMARY" ? "REPAIR" : "REWORK",
    origin: repair.origin,
    acceptanceStatus: repair.acceptanceState,
    startedAt: null,
    completedAt:
      repair.executionState === "COMPLETED"
        ? (repair.plan.stages
            .map((stage) => stage.completedAt)
            .filter((value): value is string => value !== null)
            .sort()
            .at(-1) ?? repair.updatedAt)
        : null,
    acceptanceDecidedAt: terminalDecision ? repair.updatedAt : null,
    acceptanceDecidedBy: terminalDecision ? repair.actor.actorId : null,
    acceptanceComment: null,
    warehouseId: repair.warehouseId,
    rentalItemId: repair.rentalItemId,
    cabinNumber: repair.rentalItemId,
    authorName: repair.actor.actorId,
    reason: repair.sourceParty ?? "",
    dispatchDate: repair.dispatchDate,
    comment: "",
    media: repair.mediaReferences.map(toViewMedia),
    subtasks: repair.plan.stages.map(toViewSubtask),
    sourceEstimateId: repair.estimateId,
    sourceEstimateVersion: null,
    sourceInventoryId: null,
    sourceInventoryFindingId: null,
    sourceRepairTaskId: repair.sourceRepairId,
    sourceRepairTaskVersion: null,
    createdAt: repair.createdAt,
    updatedAt: repair.updatedAt,
  }
}
