import { afterEach, beforeEach, describe, expect, it, vi } from "vitest"

import {
  bootstrapRepairEstimateCatalog,
  getRepairEstimateCatalogSection,
  getRepairEstimateCatalogSectionItems,
  getRepairEstimateCatalogSnapshot,
  moveRepairEstimateCatalogCanvasNode,
  saveRepairEstimateCatalogCanvasChanges,
  saveRepairEstimateCatalogLink,
  saveRepairEstimateCatalogNode,
} from "@/features/settings/estimates-repairs/api/repair-estimate-catalog-store"
import type {
  RepairEstimateCatalogNodeMutation,
  RepairEstimateCatalogRequest,
} from "@/features/settings/estimates-repairs/model/repair-estimate-catalog"

const http = vi.hoisted(() => ({
  bootstrapMaintenanceCatalog: vi.fn(),
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
  code: "WINDOWS",
  nodeType: "CATEGORY" as const,
  name: "Окна",
  active: true,
  parentNodeId: null,
  furnitureCategory: false,
  furnitureEquipment: null,
  unit: null,
  unitPrice: null,
  durationMinutes: 0,
  includeInEstimate: false,
  commonItem: false,
  showInMainMenu: true,
  routing: { queueId, queueCode: "REPAIR", queueKind: "REPAIR" },
  references: [{ referenceId: "legacy", code: "WINDOWS" }],
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
    code: "WINDOWS",
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
      workQueueId: queueId,
    })
    expect(snapshot.nodes[0]).not.toHaveProperty("mediaOwnerId")
    expect(snapshot.nodes[0]).not.toHaveProperty("mediaReferences")
    expect(snapshot.nodes[0]).not.toHaveProperty("photoRequired")
    expect(snapshot.links[0]?.canvasAnchors).toEqual({
      source: "TOP",
      target: "BOTTOM",
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
          routing: node.routing,
          references: node.references,
          canvasX: 120,
          canvasY: 240,
        }),
      ]
    )
    expect(saved.name).toBe("Новые окна")
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
      { ...node, id: commandId, code: "WINDOW_REPAIR" },
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
      code: "WINDOW_REPAIR",
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

  it("keeps automatic furniture equipment linking on the shared node", async () => {
    const furnitureRoot = {
      ...node,
      id: "00000000-0000-4000-8000-000000000020",
      code: "FURNITURE",
      name: "Мебель",
      furnitureCategory: true,
    }
    const equipment = {
      equipmentId: "00000000-0000-4000-8000-000000000021",
      equipmentCode: "TABLE",
      equipmentName: "Стол",
    }
    const savedNode = {
      ...node,
      id: commandId,
      code: "TABLE",
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
        code: "table",
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
          code: "TABLE",
          furnitureEquipment: null,
        }),
      ])
    )
    expect(saved.furnitureEquipment).toEqual(equipment)
  })

  it("bootstraps the server-owned current catalog idempotently", async () => {
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
      lifecycle: "ACTIVE",
      version: 1,
    })
  })
})
