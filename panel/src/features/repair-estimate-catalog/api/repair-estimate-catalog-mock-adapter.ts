import type { RepairEstimateCatalogClient } from "@/features/repair-estimate-catalog/api/repair-estimate-catalog-client"
import type {
  RepairEstimateCatalogNodeDto,
  RepairEstimateCatalogSnapshotDto,
} from "@/features/repair-estimate-catalog/model/repair-estimate-catalog"
import { getRepairEstimateCatalogSnapshot } from "@/features/settings/estimates-repairs/api/repair-estimate-catalog-store"

function toReadNode(
  node: Awaited<
    ReturnType<typeof getRepairEstimateCatalogSnapshot>
  >["nodes"][number]
): RepairEstimateCatalogNodeDto {
  return {
    id: node.id,
    code: node.code,
    name: node.name,
    nodeType: node.nodeType,
    parentId: node.parentId,
    parentCode: node.parentCode,
    active: node.active,
    sortOrder: node.sortOrder,
    unit: node.unit,
    unitPrice: node.unitPrice,
    defaultQuantity: node.defaultQuantity,
    durationMinutes: node.durationMinutes,
    additionalOption: node.additionalOption,
    showInMainMenu: node.showInMainMenu,
    mainMenuOrder: node.mainMenuOrder,
    mainMenuTitle: node.mainMenuTitle,
    routeQueueKind: node.routeQueueKind,
    workQueueCode: node.workQueueCode,
    photoRequired: node.photoRequired,
    includeInEstimate: node.includeInEstimate,
    commonItem: node.commonItem,
    furnitureCategory: node.furnitureCategory,
    canvasX: node.canvasX,
    canvasY: node.canvasY,
    comment: node.comment,
  }
}

async function getMockOperationalCatalog(): Promise<RepairEstimateCatalogSnapshotDto> {
  const snapshot = await getRepairEstimateCatalogSnapshot()
  return {
    nodes: snapshot.nodes.map(toReadNode),
    links: snapshot.links.map((link) => ({
      id: link.id,
      sourceNodeId: link.sourceNodeId,
      targetNodeId: link.targetNodeId,
      linkType: link.linkType,
      active: link.active,
      sortOrder: link.sortOrder,
      comment: link.comment,
    })),
    seedMeta: { ...snapshot.seedMeta },
  }
}

/** Internal mock transport. Operational features consume only the public facade. */
export const repairEstimateCatalogMockClient: RepairEstimateCatalogClient = {
  getOperationalCatalog: getMockOperationalCatalog,
}
