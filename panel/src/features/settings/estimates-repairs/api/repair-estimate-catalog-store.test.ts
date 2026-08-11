import { afterEach, beforeEach, describe, expect, it, vi } from "vitest"

import {
  createRepairEstimateCatalog,
  getItemsForCategory,
  getRepairEstimateCatalogSection,
  getRepairEstimateCatalogSectionItems,
  getRepairEstimateCatalogSnapshot,
  moveRepairEstimateCatalogCanvasNode,
  saveRepairEstimateCatalogDisplayColors,
  saveRepairEstimateCatalogCanvasChanges,
  saveRepairEstimateCatalogLink,
  saveRepairEstimateCatalogNode,
} from "@/features/settings/estimates-repairs/api/repair-estimate-catalog-store"
import type {
  RepairEstimateCatalogNodeMutation,
  RepairEstimateCatalogRequest,
  RepairEstimateCatalogRoutingDto,
} from "@/features/settings/estimates-repairs/model/repair-estimate-catalog"

const http = vi.hoisted(() => ({
  createMaintenanceCatalog: vi.fn(),
  listMaintenanceCatalogLinks: vi.fn(),
  listMaintenanceCatalogNodes: vi.fn(),
  listMaintenanceCatalogVersions: vi.fn(),
  replaceMaintenanceCatalog: vi.fn(),
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

const request: RepairEstimateCatalogRequest = {
  accessToken: "catalog-token",
  warehouseId,
  catalogVersionId: versionId,
}

const version = {
  id: versionId,
  warehouseId,
  version: 7,
  lifecycle: "ACTIVE" as const,
  sourceSha256: "a".repeat(64),
  counts: { nodes: 1, links: 0 },
  validation: {
    valid: true,
    errorCount: 0,
    warningCount: 0,
    reportSha256: "b".repeat(64),
  },
  createdAt: "2026-07-18T10:00:00Z",
  activatedAt: "2026-07-18T10:05:00Z",
}

const node = {
  id: nodeId,
  catalogVersionId: versionId,
  nodeType: "CATEGORY" as const,
  name: "Окна",
  active: true,
  parentNodeId: null,
  furnitureCategory: false,
  furnitureEquipment: null,
  forcesCapitalRepair: false,
  characteristic: null,
  unit: null,
  unitPrice: null,
  durationMinutes: 0,
  includeInEstimate: false,
  commonItem: false,
  showInMainMenu: true,
  routing: { queueId, queueName: "Ремонт", queueType: "REPAIR" },
  canvasX: 120,
  canvasY: 240,
  comment: null,
}

function page(value = version) {
  return { items: [value], page: 0, size: 200, totalElements: 1 }
}

function mutation(
  overrides: Partial<RepairEstimateCatalogNodeMutation> = {}
): RepairEstimateCatalogNodeMutation {
  return {
    id: nodeId,
    name: "Окна",
    nodeType: "CATEGORY",
    parentId: null,
    active: true,
    unit: null,
    unitPrice: null,
    durationMinutes: null,
    showInMainMenu: true,
    routeQueueKind: "REPAIR",
    includeInEstimate: false,
    commonItem: false,
    furnitureCategory: false,
    furnitureEquipment: null,
    forcesCapitalRepair: false,
    characteristicId: null,
    canvasX: 120,
    canvasY: 240,
    comment: null,
    ...overrides,
  }
}

describe("maintenance-backed repair catalog store", () => {
  afterEach(() => vi.restoreAllMocks())

  beforeEach(() => {
    vi.clearAllMocks()
    http.listMaintenanceCatalogVersions.mockResolvedValue(page())
    http.listMaintenanceCatalogNodes.mockResolvedValue([node])
    http.listMaintenanceCatalogLinks.mockResolvedValue([])
    http.replaceMaintenanceCatalogNodes.mockResolvedValue({
      ...version,
      version: 8,
    })
    http.replaceMaintenanceCatalog.mockResolvedValue({
      ...version,
      version: 8,
    })
    http.replaceMaintenanceCatalogLinks.mockResolvedValue({
      ...version,
      version: 8,
    })
    vi.spyOn(crypto, "randomUUID").mockReturnValue(commandId)
  })

  it("reads the graph layout from maintenance-service instead of local storage", async () => {
    http.listMaintenanceCatalogLinks.mockResolvedValue([
      {
        id: linkId,
        catalogVersionId: versionId,
        fromNodeId: nodeId,
        toNodeId: commandId,
        linkType: "FOLLOW_UP",
        sortOrder: 10,
        sourceAnchor: "TOP",
        targetAnchor: "BOTTOM",
      },
    ])

    const snapshot = await getRepairEstimateCatalogSnapshot(request)

    expect(snapshot.nodes[0]).toMatchObject({
      id: nodeId,
      canvasX: 120,
      canvasY: 240,
      queueDefinitionId: queueId,
    })
    expect(snapshot.nodes[0]).not.toHaveProperty("mediaOwnerId")
    expect(snapshot.nodes[0]).not.toHaveProperty("mediaReferences")
    expect(snapshot.nodes[0]).not.toHaveProperty("photoRequired")
    expect(snapshot.links[0]?.canvasAnchors).toEqual({
      source: "TOP",
      target: "BOTTOM",
    })
  })

  it("rejects the furniture-movement queue kind as repair catalog routing", async () => {
    http.listMaintenanceCatalogNodes.mockResolvedValue([
      {
        ...node,
        routing: {
          queueId,
          queueName: "Перемещение мебели",
          queueType: "FURNITURE_MOVEMENT",
        },
      },
    ])

    const snapshot = await getRepairEstimateCatalogSnapshot(request)

    expect(snapshot.nodes[0]).toMatchObject({
      routeQueueKind: null,
      queueDefinitionId: null,
      routing: null,
    })
  })

  it("edits the ACTIVE catalog directly and preserves service-owned fields", async () => {
    http.listMaintenanceCatalogVersions
      .mockResolvedValueOnce(page())
      .mockResolvedValueOnce(page({ ...version, version: 8 }))
    http.listMaintenanceCatalogNodes
      .mockResolvedValueOnce([node])
      .mockResolvedValueOnce([{ ...node, name: "Новые окна" }])

    const saved = await saveRepairEstimateCatalogNode(
      request,
      mutation({ name: "Новые окна" })
    )

    expect(http.replaceMaintenanceCatalogNodes).toHaveBeenCalledWith(
      "catalog-token",
      warehouseId,
      versionId,
      7,
      [
        expect.objectContaining({
          id: nodeId,
          name: "Новые окна",
          routing: { queueId, queueType: "REPAIR" },
          canvasX: 120,
          canvasY: 240,
        }),
      ]
    )
    expect(saved.name).toBe("Новые окна")
  })

  it("removes material comments at the catalog service boundary", async () => {
    const material = {
      ...node,
      nodeType: "MATERIAL" as const,
      name: "Монтажная пена",
      comment: "Старый комментарий материала",
    }
    http.listMaintenanceCatalogNodes.mockResolvedValue([material])

    const snapshot = await getRepairEstimateCatalogSnapshot(request)
    await saveRepairEstimateCatalogNode(
      request,
      mutation({
        nodeType: "MATERIAL",
        name: "Монтажная пена",
        comment: "Новый комментарий материала",
      })
    )

    expect(snapshot.nodes[0]?.comment).toBeNull()
    expect(http.replaceMaintenanceCatalogNodes).toHaveBeenCalledWith(
      "catalog-token",
      warehouseId,
      versionId,
      7,
      [expect.objectContaining({ nodeType: "MATERIAL", comment: null })]
    )
  })

  it("replaces an existing category routing with the selected exact queue", async () => {
    const replacementRouting = {
      queueId: "00000000-0000-4000-8000-000000000007",
      queueName: "Электрики",
      queueType: "REPAIR",
    } satisfies RepairEstimateCatalogRoutingDto

    await saveRepairEstimateCatalogNode(
      request,
      mutation({ routing: replacementRouting })
    )

    expect(http.replaceMaintenanceCatalogNodes).toHaveBeenCalledWith(
      "catalog-token",
      warehouseId,
      versionId,
      7,
      [
        expect.objectContaining({
          id: nodeId,
          routing: {
            queueId: replacementRouting.queueId,
            queueType: replacementRouting.queueType,
          },
        }),
      ]
    )
  })

  it("clears an existing category routing when null is submitted explicitly", async () => {
    await saveRepairEstimateCatalogNode(request, mutation({ routing: null }))

    expect(http.replaceMaintenanceCatalogNodes).toHaveBeenCalledWith(
      "catalog-token",
      warehouseId,
      versionId,
      7,
      [
        expect.objectContaining({
          id: nodeId,
          routing: null,
        }),
      ]
    )
  })

  it("persists a moved block and typed link anchors through CAS commands", async () => {
    await moveRepairEstimateCatalogCanvasNode(request, nodeId, {
      x: 333.4,
      y: 444.6,
    })

    expect(http.replaceMaintenanceCatalogNodes).toHaveBeenCalledWith(
      "catalog-token",
      warehouseId,
      versionId,
      7,
      [expect.objectContaining({ id: nodeId, canvasX: 333, canvasY: 445 })]
    )

    http.listMaintenanceCatalogVersions
      .mockResolvedValueOnce(page())
      .mockResolvedValueOnce(page({ ...version, version: 8 }))
    http.listMaintenanceCatalogNodes.mockResolvedValue([
      node,
      { ...node, id: commandId, name: "Ремонт окон" },
    ])
    http.listMaintenanceCatalogLinks
      .mockResolvedValueOnce([])
      .mockResolvedValueOnce([
        {
          id: commandId,
          catalogVersionId: versionId,
          fromNodeId: nodeId,
          toNodeId: commandId,
          linkType: "DEPENDENCY",
          sortOrder: 10,
          sourceAnchor: "BOTTOM",
          targetAnchor: "TOP",
        },
      ])

    const saved = await saveRepairEstimateCatalogLink(request, {
      sourceNodeId: nodeId,
      targetNodeId: commandId,
      linkType: "DEPENDENCY",
      sortOrder: 10,
      canvasAnchors: { source: "BOTTOM", target: "TOP" },
    })

    expect(http.replaceMaintenanceCatalogLinks).toHaveBeenCalledWith(
      "catalog-token",
      warehouseId,
      versionId,
      7,
      [
        expect.objectContaining({
          id: commandId,
          sourceAnchor: "BOTTOM",
          targetAnchor: "TOP",
        }),
      ]
    )
    expect(saved.canvasAnchors).toEqual({ source: "BOTTOM", target: "TOP" })
  })

  it("atomically saves canvas positions and link changes", async () => {
    const targetNode = {
      ...node,
      id: commandId,
      name: "Ремонт окон",
      nodeType: "WORK" as const,
      parentNodeId: nodeId,
    }
    http.listMaintenanceCatalogNodes.mockResolvedValue([node, targetNode])
    http.listMaintenanceCatalogLinks.mockResolvedValue([
      {
        id: linkId,
        catalogVersionId: versionId,
        fromNodeId: nodeId,
        toNodeId: commandId,
        linkType: "FOLLOW_UP",
        sortOrder: 10,
        sourceAnchor: "BOTTOM",
        targetAnchor: "TOP",
      },
    ])

    await saveRepairEstimateCatalogCanvasChanges(request, {
      nodePositions: [{ nodeId, x: 333.4, y: 444.6 }],
      addedLinks: [
        {
          sourceNodeId: commandId,
          targetNodeId: nodeId,
          linkType: "DEPENDENCY",
          sortOrder: 20,
          canvasAnchors: { source: "TOP", target: "BOTTOM" },
        },
      ],
      deletedLinkIds: [linkId],
    })

    expect(http.replaceMaintenanceCatalog).toHaveBeenCalledWith(
      "catalog-token",
      warehouseId,
      versionId,
      7,
      expect.arrayContaining([
        expect.objectContaining({ id: nodeId, canvasX: 333, canvasY: 445 }),
      ]),
      [
        expect.objectContaining({
          id: commandId,
          fromNodeId: commandId,
          toNodeId: nodeId,
          linkType: "DEPENDENCY",
          sourceAnchor: "TOP",
          targetAnchor: "BOTTOM",
        }),
      ]
    )
  })

  it("builds work, material and furniture tables from the same catalog nodes", async () => {
    const furnitureRoot = {
      ...node,
      id: "00000000-0000-4000-8000-000000000010",
      name: "Мебель",
      furnitureCategory: true,
    }
    const regularRoot = {
      ...node,
      id: "00000000-0000-4000-8000-000000000011",
      name: "Общее",
    }
    const furnitureMaterial = {
      ...node,
      id: "00000000-0000-4000-8000-000000000012",
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
      name: "Краска",
      parentNodeId: regularRoot.id,
    }
    const regularWork = {
      ...regularMaterial,
      id: "00000000-0000-4000-8000-000000000014",
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

  it("keeps common items out of regular category tables", async () => {
    const commonRoot = {
      ...node,
      id: "00000000-0000-4000-8000-000000000020",
      name: "Общее",
    }
    const regularWork = {
      ...node,
      id: "00000000-0000-4000-8000-000000000021",
      name: "Обычная работа",
      nodeType: "WORK" as const,
      parentNodeId: commonRoot.id,
      unit: "шт.",
      unitPrice: "100.00",
      includeInEstimate: true,
      showInMainMenu: false,
      routing: null,
      commonItem: false,
    }
    const commonWork = {
      ...regularWork,
      id: "00000000-0000-4000-8000-000000000022",
      name: "Общая работа",
      commonItem: true,
    }
    const regularMaterial = {
      ...regularWork,
      id: "00000000-0000-4000-8000-000000000023",
      name: "Краска",
      nodeType: "MATERIAL" as const,
      commonItem: false,
    }
    const commonMaterial = {
      ...regularMaterial,
      id: "00000000-0000-4000-8000-000000000024",
      name: "Общая краска",
      commonItem: true,
    }
    http.listMaintenanceCatalogNodes.mockResolvedValue([
      commonRoot,
      regularWork,
      commonWork,
      regularMaterial,
      commonMaterial,
    ])

    const [works, materials] = await Promise.all([
      getRepairEstimateCatalogSection(request, "works"),
      getRepairEstimateCatalogSection(request, "materials"),
    ])

    expect(
      getItemsForCategory(works, commonRoot.id).map((item) => item.id)
    ).toEqual([regularWork.id])
    expect(
      getItemsForCategory(materials, commonRoot.id).map((item) => item.id)
    ).toEqual([regularMaterial.id])
  })

  it("keeps automatic furniture equipment linking on the shared node", async () => {
    const furnitureRoot = {
      ...node,
      id: "00000000-0000-4000-8000-000000000020",
      name: "Мебель",
      furnitureCategory: true,
    }
    const equipment = {
      equipmentId: "00000000-0000-4000-8000-000000000021",
      equipmentName: "Стол",
      equipmentVersion: 2,
      maximumPerCabin: 4,
    }
    const savedNode = {
      ...node,
      id: commandId,
      name: "Стол",
      nodeType: "MATERIAL" as const,
      parentNodeId: furnitureRoot.id,
      unit: "шт.",
      unitPrice: "2500.00",
      includeInEstimate: true,
      routing: null,
      furnitureEquipment: equipment,
    }
    http.listMaintenanceCatalogVersions
      .mockResolvedValueOnce(page())
      .mockResolvedValueOnce(page({ ...version, version: 8 }))
    http.listMaintenanceCatalogNodes
      .mockResolvedValueOnce([furnitureRoot])
      .mockResolvedValueOnce([furnitureRoot, savedNode])

    const saved = await saveRepairEstimateCatalogNode(
      request,
      mutation({
        id: undefined,
        name: "Стол",
        nodeType: "MATERIAL",
        parentId: furnitureRoot.id,
        unit: "шт.",
        unitPrice: "2500.00",
        includeInEstimate: true,
        showInMainMenu: false,
        routeQueueKind: null,
        furnitureEquipment: null,
        canvasX: null,
        canvasY: null,
      })
    )

    expect(http.replaceMaintenanceCatalogNodes).toHaveBeenCalledWith(
      "catalog-token",
      warehouseId,
      versionId,
      7,
      expect.arrayContaining([
        expect.objectContaining({
          id: commandId,
          name: "Стол",
          furnitureEquipment: null,
        }),
      ])
    )
    expect(saved.furnitureEquipment).toEqual(equipment)
  })

  it("applies semantic button colours to matching nodes in one versioned replacement", async () => {
    const furnitureRoot = {
      ...node,
      id: "00000000-0000-4000-8000-000000000030",
      name: "Мебель",
      furnitureCategory: true,
    }
    const furnitureWork = {
      ...node,
      id: "00000000-0000-4000-8000-000000000031",
      name: "Собрать кровать",
      nodeType: "WORK" as const,
      parentNodeId: furnitureRoot.id,
      furnitureCategory: false,
    }
    const material = {
      ...node,
      id: "00000000-0000-4000-8000-000000000032",
      name: "Краска",
      nodeType: "MATERIAL" as const,
      parentNodeId: nodeId,
      furnitureCategory: false,
    }
    http.listMaintenanceCatalogNodes.mockResolvedValue([
      node,
      furnitureRoot,
      furnitureWork,
      material,
    ])

    await saveRepairEstimateCatalogDisplayColors(request, {
      CATEGORY: "#1166CC",
      SUBCATEGORY: "#7711CC",
      WORK: "#CC6611",
      MATERIAL: "#1A8B5E",
      FURNITURE: "#A62D73",
      OPTION: "#794D1F",
      LOCATION: "#2458A6",
    })

    expect(http.replaceMaintenanceCatalogNodes).toHaveBeenCalledWith(
      "catalog-token",
      warehouseId,
      versionId,
      7,
      expect.arrayContaining([
        expect.objectContaining({ id: nodeId, displayColor: "#1166CC" }),
        expect.objectContaining({
          id: furnitureRoot.id,
          displayColor: "#A62D73",
        }),
        expect.objectContaining({
          id: furnitureWork.id,
          displayColor: "#A62D73",
        }),
        expect.objectContaining({
          id: material.id,
          displayColor: "#1A8B5E",
        }),
      ])
    )
  })

  it("creates the empty server-owned catalog idempotently", async () => {
    http.createMaintenanceCatalog.mockResolvedValue({
      ...version,
      version: 1,
    })

    const result = await createRepairEstimateCatalog(
      "catalog-token",
      warehouseId
    )

    expect(http.createMaintenanceCatalog).toHaveBeenCalledWith(
      "catalog-token",
      commandId,
      { warehouseId }
    )
    expect(result).toMatchObject({
      id: versionId,
      lifecycle: "ACTIVE",
      version: 1,
    })
  })
})
