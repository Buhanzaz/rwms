import { beforeEach, describe, expect, it, vi } from "vitest"

import { httpRepairEstimateCatalogClient } from "@/features/repair-estimate-catalog/api/http-repair-estimate-catalog-client"

const maintenance = vi.hoisted(() => ({
  listMaintenanceCatalogLinks: vi.fn(),
  listMaintenanceCatalogNodes: vi.fn(),
  listMaintenanceCatalogVersions: vi.fn(),
}))
const auth = vi.hoisted(() => ({ getUser: vi.fn() }))

vi.mock(
  "@/features/repair-estimate-catalog/api/http-maintenance-catalog-client",
  () => maintenance
)
vi.mock("@/features/auth/oidc-client", () => ({
  getUserManager: () => ({ getUser: auth.getUser }),
}))

const warehouseId = "00000000-0000-4000-8000-000000000001"
const catalogOwnerWarehouseId = "00000000-0000-4000-8000-000000000005"
const versionId = "00000000-0000-4000-8000-000000000002"
const nodeId = "00000000-0000-4000-8000-000000000003"
const linkId = "00000000-0000-4000-8000-000000000004"

describe("operational maintenance catalog adapter", () => {
  beforeEach(() => {
    vi.clearAllMocks()
    window.localStorage.setItem("wms:selected-warehouse-id", warehouseId)
    auth.getUser.mockResolvedValue({
      expired: false,
      access_token: "catalog-token",
    })
    maintenance.listMaintenanceCatalogVersions.mockResolvedValue({
      items: [
        {
          id: versionId,
          warehouseId,
          version: 3,
          lifecycle: "ACTIVE",
          sourceSha256: "a".repeat(64),
          counts: { nodes: 1, links: 0 },
        },
      ],
    })
    maintenance.listMaintenanceCatalogNodes.mockResolvedValue([
      {
        id: nodeId,
        catalogVersionId: versionId,
        name: "Работа",
        displayColor: "#336699",
        nodeType: "WORK",
        parentNodeId: null,
        active: true,
        unit: "шт.",
        unitPrice: "100.00",
        durationMinutes: 30,
        showInMainMenu: true,
        routing: null,
        includeInEstimate: true,
        commonItem: false,
        furnitureCategory: false,
        furnitureEquipment: null,
        comment: null,
      },
    ])
    maintenance.listMaintenanceCatalogLinks.mockResolvedValue([
      {
        id: linkId,
        catalogVersionId: versionId,
        fromNodeId: nodeId,
        toNodeId: nodeId,
        linkType: "FOLLOW_UP",
        sortOrder: 10,
      },
    ])
  })

  it("loads the one global active catalog through the selected warehouse context", async () => {
    const snapshot =
      await httpRepairEstimateCatalogClient.getOperationalCatalog()

    expect(maintenance.listMaintenanceCatalogVersions).toHaveBeenCalledWith(
      "catalog-token",
      warehouseId,
      "ACTIVE"
    )
    expect(snapshot.nodes[0]).toMatchObject({
      id: nodeId,
      catalogVersionId: versionId,
      nodeType: "WORK",
      displayColor: "#336699",
      furnitureCategory: false,
      furnitureEquipment: null,
    })
    expect(snapshot).not.toHaveProperty("seedMeta")
    expect(snapshot.nodes[0]).not.toHaveProperty("defaultQuantity")
    expect(snapshot.nodes[0]).not.toHaveProperty("sortOrder")
    expect(snapshot.nodes[0]).not.toHaveProperty("mainMenuTitle")
    expect(snapshot.nodes[0]).not.toHaveProperty("mediaOwnerId")
    expect(snapshot.links[0]).toEqual({
      id: linkId,
      catalogVersionId: versionId,
      sourceNodeId: nodeId,
      targetNodeId: nodeId,
      linkType: "FOLLOW_UP",
      sortOrder: 10,
    })
  })

  it("uses an authoritative workflow warehouse instead of the stored preference", async () => {
    const workflowWarehouseId = "00000000-0000-4000-8000-000000000006"

    await httpRepairEstimateCatalogClient.getOperationalCatalog(
      workflowWarehouseId
    )

    expect(maintenance.listMaintenanceCatalogVersions).toHaveBeenCalledWith(
      "catalog-token",
      workflowWarehouseId,
      "ACTIVE"
    )
    expect(maintenance.listMaintenanceCatalogNodes).toHaveBeenCalledWith(
      "catalog-token",
      workflowWarehouseId,
      versionId
    )
    expect(maintenance.listMaintenanceCatalogLinks).toHaveBeenCalledWith(
      "catalog-token",
      workflowWarehouseId,
      versionId
    )
  })

  it("does not expose legacy material comments to estimate editors", async () => {
    maintenance.listMaintenanceCatalogNodes.mockResolvedValue([
      {
        id: nodeId,
        catalogVersionId: versionId,
        name: "Монтажная пена",
        nodeType: "MATERIAL",
        parentNodeId: null,
        active: true,
        unit: "баллон",
        unitPrice: "350.00",
        durationMinutes: 0,
        showInMainMenu: true,
        routing: null,
        includeInEstimate: true,
        commonItem: false,
        furnitureCategory: false,
        furnitureEquipment: null,
        forcesCapitalRepair: false,
        characteristic: null,
        comment: "Старый комментарий материала",
      },
    ])

    const snapshot =
      await httpRepairEstimateCatalogClient.getOperationalCatalog()

    expect(snapshot.nodes[0]?.comment).toBeNull()
  })

  it("uses the same global catalog when its recorded warehouse differs from the selected warehouse", async () => {
    maintenance.listMaintenanceCatalogVersions.mockResolvedValue({
      items: [
        {
          id: versionId,
          warehouseId: catalogOwnerWarehouseId,
          version: 17,
          lifecycle: "ACTIVE",
          sourceSha256: "a".repeat(64),
          counts: { nodes: 1, links: 1 },
        },
      ],
    })

    const snapshot =
      await httpRepairEstimateCatalogClient.getOperationalCatalog()

    expect(maintenance.listMaintenanceCatalogNodes).toHaveBeenCalledWith(
      "catalog-token",
      warehouseId,
      versionId
    )
    expect(maintenance.listMaintenanceCatalogLinks).toHaveBeenCalledWith(
      "catalog-token",
      warehouseId,
      versionId
    )
    expect(snapshot.nodes).toHaveLength(1)
    expect(snapshot.nodes[0]?.catalogVersionId).toBe(versionId)
  })

  it("fails closed without an authenticated OIDC session", async () => {
    auth.getUser.mockResolvedValue(null)

    await expect(
      httpRepairEstimateCatalogClient.getOperationalCatalog()
    ).rejects.toThrow("Не получен токен")
    expect(maintenance.listMaintenanceCatalogVersions).not.toHaveBeenCalled()
  })
})
