import { afterEach, beforeEach, describe, expect, it, vi } from "vitest"

import {
  activateRepairEstimateCatalog,
  getRepairEstimateCatalogSnapshot,
  moveRepairEstimateCatalogCanvasNode,
  parseRepairEstimateCatalogImportArtifact,
  saveRepairEstimateCatalogNode,
} from "@/features/settings/estimates-repairs/api/repair-estimate-catalog-store"
import { saveRepairEstimateCatalogLinkAnchors } from "@/features/settings/estimates-repairs/api/repair-estimate-catalog-layout"
import type { RepairEstimateCatalogRequest } from "@/features/settings/estimates-repairs/model/repair-estimate-catalog"

const http = vi.hoisted(() => ({
  activateMaintenanceCatalog: vi.fn(),
  importMaintenanceCatalog: vi.fn(),
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
  })

  it("rejects an unreviewed or cross-warehouse import before HTTP", () => {
    expect(() =>
      parseRepairEstimateCatalogImportArtifact(
        { warehouseId },
        JSON.stringify({
          sourceWarehouseId: "00000000-0000-4000-8000-000000000099",
          sourceEvidenceSha256: "a".repeat(64),
          nodes: [],
          links: [],
        })
      )
    ).toThrow("другому складу")
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
