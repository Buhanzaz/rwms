import type { InventoryFindingDto } from "@/features/inventory/model/inventory"
import type { RentalItemStatus } from "@/features/rental-items/model/rental-item"

/** Status derived from inventory evidence without mutating the asset-service snapshot. */
export function inventorySessionStatus(
  finding: InventoryFindingDto
): RentalItemStatus | null {
  if (finding.inspectionStatus === "READY") return "FREE"
  if (
    finding.inspectionStatus === "WORK_STAGED" &&
    finding.repairCompletionMode
  ) {
    return finding.forceCapitalRepair === true ? "CAPITAL_REPAIR" : "REPAIR"
  }
  return (
    finding.currentSnapshot?.status ??
    finding.inspectionBaseline?.status ??
    finding.expectedSnapshot?.status ??
    null
  )
}
