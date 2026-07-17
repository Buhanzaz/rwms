import { beforeEach, describe, expect, it, vi } from "vitest"

import {
  deleteRepairEstimateCatalogLink,
  deleteRepairEstimateCatalogNode,
  getRepairEstimateCatalogCanvas,
  getRepairEstimateCatalogSection,
  getRepairEstimateCatalogSnapshot,
  resetRepairEstimateCatalogMock,
  saveRepairEstimateCatalogLink,
  saveRepairEstimateCatalogNode,
} from "@/features/settings/estimates-repairs/api/repair-estimate-catalog-store"
import type {
  RepairEstimateCatalogLinkMutation,
  RepairEstimateCatalogNodeMutation,
} from "@/features/settings/estimates-repairs/model/repair-estimate-catalog"

const http = vi.hoisted(() => ({
  deleteHttpMaintenanceCatalogLink: vi.fn(),
  deleteHttpMaintenanceCatalogNode: vi.fn(),
  getHttpMaintenanceCatalogSnapshot: vi.fn(),
  saveHttpMaintenanceCatalogLink: vi.fn(),
  saveHttpMaintenanceCatalogNode: vi.fn(),
}))

vi.mock("@/features/maintenance/maintenance-runtime", () => ({
  DEV_MAINTENANCE_FIXTURES_ENABLED: false,
}))
vi.mock(
  "@/features/settings/estimates-repairs/api/http-maintenance-catalog-settings",
  () => http
)

const node: RepairEstimateCatalogNodeMutation = {
  code: "WORK",
  name: "Работа",
  nodeType: "WORK",
  parentId: null,
  active: true,
  sortOrder: 10,
  unit: null,
  unitPrice: null,
  defaultQuantity: 1,
  durationMinutes: null,
  additionalOption: false,
  showInMainMenu: false,
  mainMenuOrder: null,
  mainMenuTitle: null,
  routeQueueKind: null,
  photoRequired: false,
  includeInEstimate: true,
  commonItem: false,
  furnitureCategory: false,
  canvasX: null,
  canvasY: null,
  comment: null,
}

const link: RepairEstimateCatalogLinkMutation = {
  sourceNodeId: "source",
  targetNodeId: "target",
  linkType: "FOLLOW_UP",
  active: true,
  sortOrder: 10,
  comment: null,
}

describe("production repair catalog boundary", () => {
  beforeEach(() => {
    vi.clearAllMocks()
  })

  it.each([
    ["snapshot read", () => getRepairEstimateCatalogSnapshot()],
    ["canvas read", () => getRepairEstimateCatalogCanvas("   ")],
    ["section read", () => getRepairEstimateCatalogSection("works")],
    ["node save", () => saveRepairEstimateCatalogNode(node)],
    ["node delete", () => deleteRepairEstimateCatalogNode("node")],
    ["link save", () => saveRepairEstimateCatalogLink(link, "")],
    ["link delete", () => deleteRepairEstimateCatalogLink("link", "   ")],
    ["fixture reset", () => resetRepairEstimateCatalogMock()],
  ])(
    "fails closed for missing warehouse at the %s boundary",
    async (_name, operation) => {
      const getItem = vi.spyOn(Storage.prototype, "getItem")
      const setItem = vi.spyOn(Storage.prototype, "setItem")

      await expect(operation()).rejects.toThrow()

      expect(getItem).not.toHaveBeenCalled()
      expect(setItem).not.toHaveBeenCalled()
      expect(
        Object.values(http).every((mock) => mock.mock.calls.length === 0)
      ).toBe(true)
    }
  )

  it("trims a valid warehouse and stays on the HTTP boundary", async () => {
    http.getHttpMaintenanceCatalogSnapshot.mockResolvedValue({
      nodes: [],
      links: [],
      seedMeta: { nodeCount: 0, linkCount: 0, note: null },
    })
    http.saveHttpMaintenanceCatalogNode.mockResolvedValue({ id: "node" })
    const getItem = vi.spyOn(Storage.prototype, "getItem")
    const setItem = vi.spyOn(Storage.prototype, "setItem")

    await getRepairEstimateCatalogSnapshot("  warehouse-id  ")
    await saveRepairEstimateCatalogNode(node, "  warehouse-id  ")

    expect(http.getHttpMaintenanceCatalogSnapshot).toHaveBeenCalledWith(
      "warehouse-id"
    )
    expect(http.saveHttpMaintenanceCatalogNode).toHaveBeenCalledWith(
      "warehouse-id",
      node
    )
    expect(getItem).not.toHaveBeenCalled()
    expect(setItem).not.toHaveBeenCalled()
  })
})
