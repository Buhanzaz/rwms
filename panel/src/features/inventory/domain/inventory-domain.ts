import type {
  InventoryFindingDto,
  InventoryRepairPlanSnapshotDto,
} from "@/features/inventory/model/inventory"
import type {
  RepairEstimateLineDto,
  RepairEstimateTaskPlanDto,
} from "@/features/repair-estimates/model/repair-estimate"

export function inventoryCompletionRiskSignature(
  findings: InventoryFindingDto[]
) {
  const risks = findings
    .filter(
      (finding) =>
        finding.inspectionStatus === "NOT_INSPECTED" ||
        finding.reconciliationStatus === "MISSING"
    )
    .map((finding) => ({
      findingId: finding.id,
      inspectionStatus: finding.inspectionStatus,
      reconciliationStatus: finding.reconciliationStatus,
    }))
  return risks.length > 0 ? JSON.stringify(risks) : ""
}

/**
 * Counts a repair logistics cycle once per cabin. A cycle contains both the
 * movement to the repair area and the later removal from it, rather than two
 * independent movements in the completion summary.
 */
export function inventoryRepairMovementCount(findings: InventoryFindingDto[]) {
  return findings.filter(
    (finding) =>
      finding.movementRequired ||
      finding.repairPlans.some(
        (plan) =>
          plan.kind === "MOVE_TO_REPAIR" || plan.kind === "MOVE_FROM_REPAIR"
      )
  ).length
}

export function toInventoryRepairPlanSnapshot(
  plan: RepairEstimateTaskPlanDto
): InventoryRepairPlanSnapshotDto {
  return {
    id: plan.id,
    kind: plan.kind,
    includedLineIds: [...plan.includedLineIds],
    primaryLineId: plan.primaryLineId,
    groupComment: plan.groupComment,
    queueId: plan.queueId ?? null,
    queueName: plan.queueName,
    routeQueueKind: plan.routeQueueKind,
    sortOrder: plan.sortOrder,
    plannedDurationMinutes: null,
    photoRequired: false,
  }
}

export function reconcileInventoryRepairTaskPlans(input: {
  lines: RepairEstimateLineDto[]
  stored: InventoryRepairPlanSnapshotDto[]
  prepared: RepairEstimateTaskPlanDto[]
}) {
  const lineIds = new Set(input.lines.map((line) => line.id))
  const preparedByKind = new Map(
    input.prepared.map((plan) => [plan.kind, plan] as const)
  )
  return input.stored.map((plan) => {
    const fallback = preparedByKind.get(plan.kind)
    const includedLineIds = plan.includedLineIds.filter((id) => lineIds.has(id))
    const primaryLineId =
      plan.primaryLineId && lineIds.has(plan.primaryLineId)
        ? plan.primaryLineId
        : (fallback?.primaryLineId ?? null)
    return {
      ...fallback,
      id: plan.id,
      kind: plan.kind,
      includedLineIds,
      primaryLineId,
      groupComment: plan.groupComment,
      queueId: plan.queueId,
      queueName: plan.queueName,
      routeQueueKind: plan.routeQueueKind,
      sortOrder: plan.sortOrder,
      generationStatus: "PENDING_GENERATION" as const,
      workflowRequestRef: null,
    } satisfies RepairEstimateTaskPlanDto
  })
}
