import type { InventoryPlanSelection } from "@/features/inventory/model/inventory-service"
import type { RepairEstimateLineDto } from "@/features/repair-estimates/model/repair-estimate"

export function buildAutoInventoryPlan(
  lines: RepairEstimateLineDto[]
): InventoryPlanSelection {
  const catalogLines = Array.from(
    new Map(
      lines
        .filter(
          (line) =>
            line.catalogSnapshot &&
            (line.catalogSnapshot.nodeType === "WORK" ||
              line.catalogSnapshot.nodeType === "MATERIAL")
        )
        .map((line) => [line.catalogSnapshot!.nodeId, line] as const)
    ).values()
  )
  if (
    catalogLines.length === 0 ||
    !catalogLines.some((line) => line.catalogSnapshot?.nodeType === "WORK")
  ) {
    return null
  }
  return {
    mode: "AUTO",
    lines: catalogLines.map((line) => ({
      aggregationKind: "CATALOG",
      catalogNodeId: line.catalogSnapshot!.nodeId,
      description: null,
      type: null,
      unit: null,
      quantity: String(line.quantity),
      unitPriceMinor: null,
      normativeMinutes: null,
      groupComment: line.lineComment.trim() || null,
      mediaReferences: [],
    })),
    stages: [],
  }
}
