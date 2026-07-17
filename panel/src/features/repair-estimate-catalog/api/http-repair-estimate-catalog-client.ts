import {
  listMaintenanceCatalogLinks,
  listMaintenanceCatalogNodes,
  listMaintenanceCatalogVersions,
} from "@/features/maintenance/api/maintenance-api"
import type { RepairEstimateCatalogClient } from "@/features/repair-estimate-catalog/api/repair-estimate-catalog-client"

export const httpRepairEstimateCatalogClient: RepairEstimateCatalogClient = {
  async getOperationalCatalog(warehouseId) {
    if (!warehouseId) {
      throw new Error("Не выбран склад для загрузки каталога ремонта.")
    }
    const versions = await listMaintenanceCatalogVersions(warehouseId, "ACTIVE")
    const active = versions.items.find(
      (version) =>
        version.lifecycle === "ACTIVE" && version.warehouseId === warehouseId
    )
    if (!active) {
      throw new Error("Для выбранного склада не активирован каталог ремонта.")
    }
    const [nodes, links] = await Promise.all([
      listMaintenanceCatalogNodes(warehouseId, active.id),
      listMaintenanceCatalogLinks(warehouseId, active.id),
    ])
    const nodeById = new Map(nodes.map((node) => [node.id, node]))
    return {
      nodes: nodes.map((node) => ({
        id: node.id,
        catalogVersionId: node.catalogVersionId,
        code: node.code,
        name: node.name,
        nodeType: node.nodeType,
        parentId: node.parentNodeId,
        parentCode: node.parentNodeId
          ? (nodeById.get(node.parentNodeId)?.code ?? null)
          : null,
        active: node.active,
        sortOrder: null,
        unit: node.unit,
        unitPrice: node.unitPrice,
        defaultQuantity: 1,
        durationMinutes: node.durationMinutes,
        additionalOption: node.nodeType === "OPTION",
        showInMainMenu: node.showInMainMenu,
        mainMenuOrder: null,
        mainMenuTitle: null,
        routeQueueKind:
          node.routing?.queueKind === "MOVEMENT" ||
          node.routing?.queueKind === "REPAIR" ||
          node.routing?.queueKind === "HOLDING"
            ? node.routing.queueKind
            : null,
        workQueueId: node.routing?.queueId ?? null,
        workQueueCode: node.routing?.queueCode ?? null,
        photoRequired: node.photoRequired,
        includeInEstimate: node.includeInEstimate,
        commonItem: node.commonItem,
        furnitureCategory: false,
        canvasX: null,
        canvasY: null,
        comment: node.comment,
      })),
      links: links.map((link) => ({
        id: link.id,
        sourceNodeId: link.fromNodeId,
        targetNodeId: link.toNodeId,
        linkType: link.linkType,
        active: true,
        sortOrder: link.sortOrder,
        comment: null,
      })),
      seedMeta: {
        nodeCount: active.counts.nodes,
        linkCount: active.counts.links,
        note: active.sourceSha256,
      },
    }
  },
}
