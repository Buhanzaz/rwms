import {
  listMaintenanceCatalogLinks,
  listMaintenanceCatalogNodes,
  listMaintenanceCatalogVersions,
} from "@/features/repair-estimate-catalog/api/http-maintenance-catalog-client"
import type { RepairEstimateCatalogClient } from "@/features/repair-estimate-catalog/api/repair-estimate-catalog-client"
import { getUserManager } from "@/features/auth/oidc-client"

const WAREHOUSE_STORAGE_KEY = "wms:selected-warehouse-id"

async function currentRequest() {
  const user = await getUserManager().getUser()
  if (!user || user.expired || !user.access_token.trim()) {
    throw new Error("Не получен токен доступа к каталогу ремонта.")
  }
  const warehouseId = window.localStorage.getItem(WAREHOUSE_STORAGE_KEY)
  if (!warehouseId) {
    throw new Error("Не выбран склад для загрузки каталога ремонта.")
  }
  return { accessToken: user.access_token, warehouseId }
}

export const httpRepairEstimateCatalogClient: RepairEstimateCatalogClient = {
  async getOperationalCatalog() {
    const { accessToken, warehouseId } = await currentRequest()
    const versions = await listMaintenanceCatalogVersions(
      accessToken,
      warehouseId,
      "ACTIVE"
    )
    const active = versions.items.find(
      (version) =>
        version.lifecycle === "ACTIVE" && version.warehouseId === warehouseId
    )
    if (!active) {
      throw new Error("Для выбранного склада не активирован каталог ремонта.")
    }

    const [nodes, links] = await Promise.all([
      listMaintenanceCatalogNodes(accessToken, warehouseId, active.id),
      listMaintenanceCatalogLinks(accessToken, warehouseId, active.id),
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
        unit: node.unit,
        unitPrice: node.unitPrice,
        durationMinutes: node.nodeType === "WORK" ? node.durationMinutes : null,
        showInMainMenu: node.showInMainMenu,
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
        comment: node.comment,
      })),
      links: links.map((link) => ({
        id: link.id,
        catalogVersionId: link.catalogVersionId,
        sourceNodeId: link.fromNodeId,
        targetNodeId: link.toNodeId,
        linkType: link.linkType,
        sortOrder: link.sortOrder,
      })),
    }
  },
}
