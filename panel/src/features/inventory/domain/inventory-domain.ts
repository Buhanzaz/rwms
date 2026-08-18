import type {
  InventoryFindingDto,
  InventoryRepairPlanSnapshotDto,
} from "@/features/inventory/model/inventory"
import type { RepairEstimateTaskPlanDto } from "@/features/repair-estimates/model/repair-estimate"

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
  return findings.filter((finding) => finding.movementToRepair).length
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
    routingCatalogNodeId: plan.routingCatalogNodeId ?? null,
    queueName: plan.queueName,
    routeQueueKind: plan.routeQueueKind,
    sortOrder: plan.sortOrder,
    plannedDurationMinutes: null,
    photoRequired: false,
  }
}
