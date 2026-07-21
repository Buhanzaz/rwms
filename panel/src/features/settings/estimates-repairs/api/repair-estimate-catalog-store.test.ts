import { afterEach, beforeEach, describe, expect, it, vi } from "vitest"

import {
  activateRepairEstimateCatalog,
  bootstrapRepairEstimateCatalog,
  forkRepairEstimateCatalog,
  getRepairEstimateCatalogSection,
  getRepairEstimateCatalogSectionItems,
  getRepairEstimateCatalogSnapshot,
  moveRepairEstimateCatalogCanvasNode,
  saveRepairEstimateCatalogNode,
} from "@/features/settings/estimates-repairs/api/repair-estimate-catalog-store"
import { saveRepairEstimateCatalogLinkAnchors } from "@/features/settings/estimates-repairs/api/repair-estimate-catalog-layout"
import type { RepairEstimateCatalogRequest } from "@/features/settings/estimates-repairs/model/repair-estimate-catalog"

const http = vi.hoisted(() => ({
  activateMaintenanceCatalog: vi.fn(),
  bootstrapMaintenanceCatalog: vi.fn(),
  forkMaintenanceCatalog: vi.fn(),
  listMaintenanceCatalogLinks: vi.fn(),
  listMaintenanceCatalogNodes: vi.fn(),
  listMaintenanceCatalogVersions: vi.fn(),
  replaceMaintenanceCatalogLinks: vi.fn(),
  replaceMaintenanceCatalogNodes: vi.fn(),
}))

vi.mock(
  "@/features/repair-estimate-catalog/api/http-maintenance-catalog-client",
  () => http
)

const warehouseId = "00000000-0000-4000-8000-000000000001"
const versionId = "00000000-0000-4000-8000-000000000002"
const nodeId = "00000000-0000-4000-8000-000000000003"
const queueId = "00000000-0000-4000-8000-000000000004"
const commandId = "00000000-0000-4000-8000-000000000005"
const linkId = "00000000-0000-4000-8000-000000000006"
const forkVersionId = "00000000-0000-4000-8000-000000000007"
const mediaOwnerId = "00000000-0000-4000-8000-000000000008"

const request: RepairEstimateCatalogRequest = {
  accessToken: "catalog-token",
  warehouseId,
  catalogVersionId: versionId,
}

const version = {
  id: versionId,
  warehouseId,
  version: 7,
  lifecycle: "DRAFT" as const,
  sourceSha256: "a".repeat(64),
  counts: { nodes: 1, links: 0 },
  validation: {
    valid: true,
    errorCount: 0,
    warningCount: 0,
    reportSha256: "b".repeat(64),
  },
  createdAt: "2026-07-18T10:00:00Z",
  activatedAt: null,
}

const node = {
  id: nodeId,
  catalogVersionId: versionId,
  mediaOwnerId,
  code: "WINDOWS",
  nodeType: "CATEGORY" as const,
  name: "Окна",
  active: true,
  parentNodeId: null,
  unit: null,
  unitPrice: null,
  durationMinutes: 0,
  includeInEstimate: false,
  commonItem: false,
  showInMainMenu: true,
  photoRequired: false,
  furnitureCategory: false,
  furnitureEquipment: null,
  routing: { queueId, queueCode: "REPAIR", queueKind: "REPAIR" },
  references: [{ referenceId: "legacy", code: "WINDOWS" }],
  comment: null,
  mediaReferences: [],
}

function page(value = version) {
  return { items: [value], page: 0, size: 200, totalElements: 1 }
}

describe("maintenance-backed repair catalog store", () => {
  afterEach(() => vi.restoreAllMocks())

  beforeEach(() => {
    vi.clearAllMocks()
    window.localStorage.clear()
    http.listMaintenanceCatalogVersions.mockResolvedValue(page())
    http.listMaintenanceCatalogNodes.mockResolvedValue([node])
    http.listMaintenanceCatalogLinks.mockResolvedValue([])
    http.replaceMaintenanceCatalogNodes.mockResolvedValue({
      ...version,
      version: 8,
    })
    vi.spyOn(crypto, "randomUUID").mockReturnValue(commandId)
  })

  it("loads the canonical graph from maintenance and keeps canvas layout presentation-only", async () => {
    await moveRepairEstimateCatalogCanvasNode(request, nodeId, {
      x: 120,
      y: 240,
    })
    const snapshot = await getRepairEstimateCatalogSnapshot(request)

    expect(snapshot.catalogVersion).toMatchObject({ id: versionId, version: 7 })
    expect(snapshot.nodes[0]).toMatchObject({
      id: nodeId,
      mediaOwnerId,
      canvasX: 120,
      canvasY: 240,
      workQueueId: queueId,
      workQueueCode: "REPAIR",
    })
    expect(http.replaceMaintenanceCatalogNodes).not.toHaveBeenCalled()
  })

  it("keeps link anchors as UI layout without fabricating service fields", async () => {
    http.listMaintenanceCatalogLinks.mockResolvedValue([
      {
        id: linkId,
        catalogVersionId: versionId,
        fromNodeId: nodeId,
        toNodeId: nodeId,
        linkType: "FOLLOW_UP",
        sortOrder: 10,
      },
    ])
    saveRepairEstimateCatalogLinkAnchors(request, linkId, {
      source: "TOP",
      target: "BOTTOM",
    })

    const snapshot = await getRepairEstimateCatalogSnapshot(request)

    expect(snapshot.links[0]).toEqual({
      id: linkId,
      catalogVersionId: versionId,
      sourceNodeId: nodeId,
      targetNodeId: nodeId,
      linkType: "FOLLOW_UP",
      sortOrder: 10,
      canvasAnchors: { source: "TOP", target: "BOTTOM" },
    })
    expect(http.replaceMaintenanceCatalogLinks).not.toHaveBeenCalled()
  })

  it("replaces draft nodes with expectedVersion and preserves opaque service fields", async () => {
    http.listMaintenanceCatalogVersions
      .mockResolvedValueOnce(page())
      .mockResolvedValueOnce(page({ ...version, version: 8 }))
    http.listMaintenanceCatalogNodes
      .mockResolvedValueOnce([node])
      .mockResolvedValueOnce([{ ...node, name: "Новые окна" }])

    await saveRepairEstimateCatalogNode(request, {
      id: nodeId,
      code: "WINDOWS",
      name: "Новые окна",
      nodeType: "CATEGORY",
      parentId: null,
      active: true,
      unit: null,
      unitPrice: null,
      durationMinutes: null,
      showInMainMenu: true,
      routeQueueKind: "REPAIR",
      photoRequired: false,
      includeInEstimate: false,
      commonItem: false,
      furnitureCategory: false,
      furnitureEquipment: null,
      comment: null,
    })

    expect(http.replaceMaintenanceCatalogNodes).toHaveBeenCalledWith(
      "catalog-token",
      warehouseId,
      versionId,
      7,
      [
        expect.objectContaining({
          id: nodeId,
          name: "Новые окна",
          routing: node.routing,
          references: node.references,
        }),
      ]
    )
    const sentNodes = http.replaceMaintenanceCatalogNodes.mock.calls[0]![4]
    expect(sentNodes[0]).not.toHaveProperty("mediaOwnerId")
  })

  it("separates furniture from works and materials while keeping unmapped draft items visible", async () => {
    const furnitureRoot = {
      ...node,
      id: "00000000-0000-4000-8000-000000000010",
      code: "FURNITURE",
      name: "Мебель",
      furnitureCategory: true,
    }
    const regularRoot = {
      ...node,
      id: "00000000-0000-4000-8000-000000000011",
      code: "GENERAL",
      name: "Общее",
    }
    const furnitureMaterial = {
      ...node,
      id: "00000000-0000-4000-8000-000000000012",
      code: "CHAIR",
      name: "Стул",
      nodeType: "MATERIAL" as const,
      parentNodeId: furnitureRoot.id,
      unit: "шт.",
      unitPrice: "100.00",
      includeInEstimate: true,
      showInMainMenu: false,
      routing: null,
    }
    const regularMaterial = {
      ...furnitureMaterial,
      id: "00000000-0000-4000-8000-000000000013",
      code: "PAINT",
      name: "Краска",
      parentNodeId: regularRoot.id,
    }
    const regularWork = {
      ...regularMaterial,
      id: "00000000-0000-4000-8000-000000000014",
      code: "PAINTING",
      name: "Покраска",
      nodeType: "WORK" as const,
    }
    http.listMaintenanceCatalogNodes.mockResolvedValue([
      furnitureRoot,
      regularRoot,
      furnitureMaterial,
      regularMaterial,
      regularWork,
    ])

    const [works, materials, furniture] = await Promise.all([
      getRepairEstimateCatalogSection(request, "works"),
      getRepairEstimateCatalogSection(request, "materials"),
      getRepairEstimateCatalogSection(request, "furniture"),
    ])

    expect(works.categories.map((item) => item.id)).toEqual([regularRoot.id])
    expect(materials.categories.map((item) => item.id)).toEqual([
      regularRoot.id,
    ])
    expect(furniture.categories.map((item) => item.id)).toEqual([
      furnitureRoot.id,
    ])
    expect(
      getRepairEstimateCatalogSectionItems(works).map((item) => item.id)
    ).toEqual([regularWork.id])
    expect(
      getRepairEstimateCatalogSectionItems(materials).map((item) => item.id)
    ).toEqual([regularMaterial.id])
    expect(
      getRepairEstimateCatalogSectionItems(furniture).map((item) => item.id)
    ).toEqual([furnitureMaterial.id])
  })

  it("persists the selected furniture equipment snapshot with the catalog material", async () => {
    const furnitureRoot = {
      ...node,
      id: "00000000-0000-4000-8000-000000000020",
      code: "FURNITURE",
      name: "Мебель",
      furnitureCategory: true,
    }
    const furnitureMaterial = {
      ...node,
      id: "00000000-0000-4000-8000-000000000021",
      code: "CHAIR",
      name: "Стул",
      nodeType: "MATERIAL" as const,
      parentNodeId: furnitureRoot.id,
      unit: "шт.",
      unitPrice: "100.00",
      includeInEstimate: true,
      showInMainMenu: false,
      routing: null,
    }
    const furnitureEquipment = {
      equipmentId: "00000000-0000-4000-8000-000000000022",
      equipmentCode: "CHAIR",
      equipmentName: "Стул",
    }
    http.listMaintenanceCatalogVersions
      .mockResolvedValueOnce(page())
      .mockResolvedValueOnce(page({ ...version, version: 8 }))
    http.listMaintenanceCatalogNodes
      .mockResolvedValueOnce([furnitureRoot, furnitureMaterial])
      .mockResolvedValueOnce([
        furnitureRoot,
        { ...furnitureMaterial, furnitureEquipment },
      ])

    await saveRepairEstimateCatalogNode(request, {
      id: furnitureMaterial.id,
      code: furnitureMaterial.code,
      name: furnitureMaterial.name,
      nodeType: "MATERIAL",
      parentId: furnitureRoot.id,
      active: true,
      unit: "шт.",
      unitPrice: "100.00",
      durationMinutes: null,
      showInMainMenu: false,
      routeQueueKind: null,
      photoRequired: false,
      includeInEstimate: true,
      commonItem: false,
      furnitureCategory: false,
      furnitureEquipment,
      comment: null,
    })

    expect(http.replaceMaintenanceCatalogNodes).toHaveBeenCalledWith(
      "catalog-token",
      warehouseId,
      versionId,
      7,
      expect.arrayContaining([
        expect.objectContaining({
          id: furnitureMaterial.id,
          furnitureCategory: false,
          furnitureEquipment,
        }),
      ])
    )
  })

  it("lets maintenance provision equipment for a new furniture material", async () => {
    const furnitureRoot = {
      ...node,
      id: "00000000-0000-4000-8000-000000000030",
      code: "FURNITURE",
      name: "Мебель",
      furnitureCategory: true,
    }
    const provisionedEquipment = {
      equipmentId: "00000000-0000-4000-8000-000000000031",
      equipmentCode: "TABLE",
      equipmentName: "Стол",
    }
    const provisionedFurniture = {
      ...node,
      id: commandId,
      code: "TABLE",
      name: "Стол",
      nodeType: "MATERIAL" as const,
      parentNodeId: furnitureRoot.id,
      unit: "шт.",
      unitPrice: "2500.00",
      includeInEstimate: true,
      showInMainMenu: false,
      routing: null,
      furnitureEquipment: provisionedEquipment,
    }
    http.listMaintenanceCatalogVersions
      .mockResolvedValueOnce(page())
      .mockResolvedValueOnce(page({ ...version, version: 8 }))
    http.listMaintenanceCatalogNodes
      .mockResolvedValueOnce([furnitureRoot])
      .mockResolvedValueOnce([furnitureRoot, provisionedFurniture])

    const saved = await saveRepairEstimateCatalogNode(request, {
      code: "table",
      name: "Стол",
      nodeType: "MATERIAL",
      parentId: furnitureRoot.id,
      active: true,
      unit: "шт.",
      unitPrice: "2500.00",
      durationMinutes: null,
      showInMainMenu: false,
      routeQueueKind: null,
      photoRequired: false,
      includeInEstimate: true,
      commonItem: false,
      furnitureCategory: false,
      furnitureEquipment: null,
      comment: null,
    })

    expect(http.replaceMaintenanceCatalogNodes).toHaveBeenCalledWith(
      "catalog-token",
      warehouseId,
      versionId,
      7,
      expect.arrayContaining([
        expect.objectContaining({
          id: commandId,
          code: "TABLE",
          name: "Стол",
          furnitureEquipment: null,
        }),
      ])
    )
    expect(saved.furnitureEquipment).toEqual(provisionedEquipment)
  })

  it("preserves the canonical equipment link while editing furniture", async () => {
    const furnitureRoot = {
      ...node,
      id: "00000000-0000-4000-8000-000000000040",
      code: "FURNITURE",
      name: "Мебель",
      furnitureCategory: true,
    }
    const furnitureEquipment = {
      equipmentId: "00000000-0000-4000-8000-000000000041",
      equipmentCode: "CHAIR",
      equipmentName: "Стул",
    }
    const furnitureMaterial = {
      ...node,
      id: "00000000-0000-4000-8000-000000000042",
      code: "CHAIR",
      name: "Стул",
      nodeType: "MATERIAL" as const,
      parentNodeId: furnitureRoot.id,
      unit: "шт.",
      unitPrice: "100.00",
      includeInEstimate: true,
      showInMainMenu: false,
      routing: null,
      furnitureEquipment,
    }
    http.listMaintenanceCatalogVersions
      .mockResolvedValueOnce(page())
      .mockResolvedValueOnce(page({ ...version, version: 8 }))
    http.listMaintenanceCatalogNodes
      .mockResolvedValueOnce([furnitureRoot, furnitureMaterial])
      .mockResolvedValueOnce([
        furnitureRoot,
        { ...furnitureMaterial, name: "Стул складной" },
      ])

    await saveRepairEstimateCatalogNode(request, {
      id: furnitureMaterial.id,
      code: furnitureMaterial.code,
      name: "Стул складной",
      nodeType: "MATERIAL",
      parentId: furnitureRoot.id,
      active: true,
      unit: "шт.",
      unitPrice: "100.00",
      durationMinutes: null,
      showInMainMenu: false,
      routeQueueKind: null,
      photoRequired: false,
      includeInEstimate: true,
      commonItem: false,
      furnitureCategory: false,
      furnitureEquipment: null,
      comment: null,
    })

    expect(http.replaceMaintenanceCatalogNodes).toHaveBeenCalledWith(
      "catalog-token",
      warehouseId,
      versionId,
      7,
      expect.arrayContaining([
        expect.objectContaining({
          id: furnitureMaterial.id,
          furnitureEquipment,
        }),
      ])
    )
  })

  it("rejects an equipment reference outside the furniture tree", async () => {
    await expect(
      saveRepairEstimateCatalogNode(request, {
        code: "PAINT",
        name: "Краска",
        nodeType: "MATERIAL",
        parentId: node.id,
        active: true,
        unit: "шт.",
        unitPrice: "100.00",
        durationMinutes: null,
        showInMainMenu: false,
        routeQueueKind: null,
        photoRequired: false,
        includeInEstimate: true,
        commonItem: false,
        furnitureCategory: false,
        furnitureEquipment: {
          equipmentId: "00000000-0000-4000-8000-000000000050",
          equipmentCode: "PAINT",
          equipmentName: "Краска",
        },
        comment: null,
      })
    ).rejects.toThrow(
      "Дополнительное оборудование можно привязать только к мебели."
    )
    expect(http.replaceMaintenanceCatalogNodes).not.toHaveBeenCalled()
  })

  it.each(["", "стол с пробелом"])(
    "rejects invalid furniture code %j before automatic linking",
    async (invalidCode) => {
      const furnitureRoot = {
        ...node,
        id: "00000000-0000-4000-8000-000000000060",
        code: "FURNITURE",
        name: "Мебель",
        furnitureCategory: true,
      }
      http.listMaintenanceCatalogNodes.mockResolvedValue([furnitureRoot])

      await expect(
        saveRepairEstimateCatalogNode(request, {
          code: invalidCode,
          name: "Стол",
          nodeType: "MATERIAL",
          parentId: furnitureRoot.id,
          active: true,
          unit: "шт.",
          unitPrice: "100.00",
          durationMinutes: null,
          showInMainMenu: false,
          routeQueueKind: null,
          photoRequired: false,
          includeInEstimate: true,
          commonItem: false,
          furnitureCategory: false,
          furnitureEquipment: null,
          comment: null,
        })
      ).rejects.toThrow("Код должен быть задан латиницей, цифрами, _ или -.")
      expect(http.replaceMaintenanceCatalogNodes).not.toHaveBeenCalled()
    }
  )

  it("bootstraps only the reviewed server-owned catalog for the selected warehouse", async () => {
    http.bootstrapMaintenanceCatalog.mockResolvedValue({
      ...version,
      version: 1,
    })

    const result = await bootstrapRepairEstimateCatalog(
      "catalog-token",
      warehouseId
    )

    expect(http.bootstrapMaintenanceCatalog).toHaveBeenCalledWith(
      "catalog-token",
      commandId,
      { warehouseId }
    )
    expect(result).toMatchObject({
      id: versionId,
      warehouseId,
      version: 1,
      lifecycle: "DRAFT",
    })
  })

  it("forks an active or superseded version with CAS and an idempotency key", async () => {
    http.forkMaintenanceCatalog.mockResolvedValue({
      ...version,
      id: forkVersionId,
      version: 1,
    })

    const result = await forkRepairEstimateCatalog(request, 7)

    expect(http.forkMaintenanceCatalog).toHaveBeenCalledWith(
      "catalog-token",
      warehouseId,
      versionId,
      7,
      commandId
    )
    expect(result).toMatchObject({
      id: forkVersionId,
      warehouseId,
      version: 1,
      lifecycle: "DRAFT",
    })
  })

  it("activates the selected version with CAS and a UUID idempotency key", async () => {
    http.activateMaintenanceCatalog.mockResolvedValue({
      ...version,
      version: 8,
      lifecycle: "ACTIVE",
      activatedAt: "2026-07-18T11:00:00Z",
    })

    await activateRepairEstimateCatalog(request, 7)

    expect(http.activateMaintenanceCatalog).toHaveBeenCalledWith(
      "catalog-token",
      warehouseId,
      versionId,
      7,
      commandId
    )
  })
})
