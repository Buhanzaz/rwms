import { beforeEach, describe, expect, it, vi } from "vitest"

import type { InventoryActorSnapshot } from "@/features/inventory/model/inventory"
import type {
  InventoryFinding,
  InventorySessionDetail,
  InventorySessionView,
} from "@/features/inventory/model/inventory-service"
import type { RepairEstimateLineDto } from "@/features/repair-estimates/model/repair-estimate"

const auth = vi.hoisted(() => ({ getUser: vi.fn() }))
const inventoryHttp = vi.hoisted(() => ({
  getFurnitureReview: vi.fn(),
  getInventorySession: vi.fn(),
  resolveInventoryNumber: vi.fn(),
  resolveInventoryFindingConflict: vi.fn(),
  saveFurnitureReview: vi.fn(),
  saveInventoryInspection: vi.fn(),
  startFurnitureReview: vi.fn(),
}))
const queueCapabilities = vi.hoisted(() => ({ get: vi.fn() }))
const catalogApi = vi.hoisted(() => ({ get: vi.fn() }))

vi.mock("@/features/auth/oidc-client", () => ({
  getUserManager: () => ({ getUser: auth.getUser }),
}))

vi.mock(
  "@/features/inventory/adapters/http-inventory-adapter",
  () => inventoryHttp
)
vi.mock("@/features/repair-estimates/api/warehouse-queue-capabilities", () => ({
  getWarehouseQueueCapabilities: queueCapabilities.get,
}))
vi.mock(
  "@/features/repair-estimate-catalog/api/repair-estimate-catalog-api",
  () => ({
    getOperationalRepairEstimateCatalog: catalogApi.get,
  })
)

import {
  getInventoryFurnitureReview,
  resolveInventoryFindingConflict,
  resolveInventoryNumber,
  saveInventoryFurnitureReview,
  saveInventoryFinding,
  startInventoryFurnitureReview,
} from "@/features/inventory/api/inventory-api"
import { toInventorySessionView } from "@/features/inventory/domain/inventory-view-mapper"

const WAREHOUSE_ID = "11111111-1111-4111-8111-111111111111"
const INVENTORY_ID = "22222222-2222-4222-8222-222222222222"

const actor = {
  id: "inventory-user",
  displayName: "Инвентаризатор",
  permissions: ["EDIT"],
  authorizedWarehouseIds: [WAREHOUSE_ID],
} satisfies InventoryActorSnapshot

const refreshedSession = {
  id: INVENTORY_ID,
  sessionRevision: 8,
  warehouseId: WAREHOUSE_ID,
  warehouseVersion: 1,
  warehouseTimeZone: "Europe/Moscow",
  author: {
    id: actor.id,
    displayName: actor.displayName,
  },
  businessDate: "2026-07-27",
  lifecycle: "ACTIVE",
  expectedCount: 0,
  findingCount: 0,
  inspectedCount: 0,
  startedAt: "2026-07-27T08:00:00Z",
  terminalAt: null,
  publicationState: "NOT_REQUESTED",
  reviewStage: "CABINS",
  furnitureReconciliationState: "NOT_REQUIRED",
  statistics: null,
  cancellation: null,
  membershipMovements: [],
  findings: [],
} satisfies InventorySessionView

const rawFinding = {
  id: "33333333-3333-4333-8333-333333333333",
  inventoryId: INVENTORY_ID,
  findingRevision: 4,
  origin: "EXPECTED",
  inspection: "NOT_INSPECTED",
  reconciliation: "MATCHED",
  assetId: "44444444-4444-4444-8444-444444444444",
  assetVersion: 2,
  displayCanonicalNumber: "БЫТ-001",
  identityMatchKey: "БЫТ001",
  passportObservation: { presence: "ABSENT", value: null },
  equipmentObservation: { presence: "ABSENT", value: null },
  mutationState: "IDLE",
  planFingerprintSha256: null,
  comment: "",
  expectedSnapshot: null,
  currentSnapshot: null,
  inspectionBaseline: null,
  conflictResolution: null,
  conflicts: [],
  frozenPlan: null,
  media: [],
  coverMediaId: null,
  inspectionSource: null,
  publication: null,
} satisfies InventoryFinding

const workLine = {
  id: "55555555-5555-4555-8555-555555555555",
  sourceLineKey: "inventory-work",
  lineType: "WORK",
  description: "Заменить дверь",
  lineComment: "",
  unit: "шт.",
  quantity: 1,
  normativeMinutes: 60,
  unitPrice: "100.00",
  lineTotal: "100.00",
  catalogSnapshot: {
    nodeId: "66666666-6666-4666-8666-666666666666",
    name: "Заменить дверь",
    nodeType: "WORK",
    furnitureEquipment: null,
    characteristic: null,
  },
  customQueueBinding: null,
} satisfies RepairEstimateLineDto

describe("inventory API", () => {
  beforeEach(() => {
    vi.clearAllMocks()
    auth.getUser.mockResolvedValue({ access_token: "inventory-token" })
    inventoryHttp.resolveInventoryNumber.mockResolvedValue({
      displayCanonicalNumber: "БУ-901",
      identityMatchKey: "БУ901",
      outcome: "NOT_FOUND",
      finding: null,
    })
    inventoryHttp.getInventorySession.mockResolvedValue(refreshedSession)
    inventoryHttp.saveInventoryInspection.mockResolvedValue(rawFinding)
    queueCapabilities.get.mockResolvedValue({
      warehouseId: WAREHOUSE_ID,
      movementToShipmentAvailable: false,
      movementQueueDefinitions: [],
    })
    catalogApi.get.mockResolvedValue({ nodes: [], links: [] })
  })

  it("refreshes and returns the session revision after a missing-number resolution", async () => {
    const result = await resolveInventoryNumber({
      inventoryId: INVENTORY_ID,
      expectedVersion: 7,
      actor,
      number: "бу-901",
    })

    expect(inventoryHttp.resolveInventoryNumber).toHaveBeenCalledWith({
      accessToken: "inventory-token",
      inventoryId: INVENTORY_ID,
      expectedSessionRevision: 7,
      submittedNumber: "бу-901",
      idempotencyKey: expect.any(String),
    })
    expect(inventoryHttp.getInventorySession).toHaveBeenCalledWith(
      "inventory-token",
      INVENTORY_ID
    )
    expect(result).toEqual({
      kind: "NOT_FOUND",
      canonicalNumber: "БУ-901",
      lookup: { kind: "NOT_FOUND" },
      session: expect.objectContaining({
        id: INVENTORY_ID,
        version: 8,
      }),
    })
  })

  it("resolves a finding conflict with current revisions and returns the refreshed session", async () => {
    inventoryHttp.resolveInventoryFindingConflict.mockResolvedValue({})

    const result = await resolveInventoryFindingConflict({
      inventoryId: INVENTORY_ID,
      expectedVersion: 8,
      expectedFindingVersion: 4,
      actor,
      findingId: "33333333-3333-4333-8333-333333333333",
      strategy: "ACCEPT_REGISTRY",
      reason: null,
    })

    expect(inventoryHttp.resolveInventoryFindingConflict).toHaveBeenCalledWith({
      accessToken: "inventory-token",
      inventoryId: INVENTORY_ID,
      findingId: "33333333-3333-4333-8333-333333333333",
      expectedSessionRevision: 8,
      expectedFindingRevision: 4,
      strategy: "ACCEPT_REGISTRY",
      reason: null,
    })
    expect(inventoryHttp.getInventorySession).toHaveBeenLastCalledWith(
      "inventory-token",
      INVENTORY_ID
    )
    expect(result.version).toBe(8)
  })

  it("starts and saves furniture review with the current cabin finding revisions", async () => {
    const review = {
      inventoryId: INVENTORY_ID,
      sessionRevision: 9,
      stage: "FURNITURE" as const,
      assetSnapshotSha256: "a".repeat(64),
      reviewSha256: null,
      confirmed: false,
      items: [
        {
          equipmentId: "77777777-7777-4777-8777-777777777777",
          catalogVersion: 888,
          equipmentName: "Стол",
          currentStockQuantity: 5,
          observedStockQuantity: 4,
          cabins: [
            {
              findingId: rawFinding.id,
              assetId: rawFinding.assetId!,
              cabinNumber: rawFinding.displayCanonicalNumber,
              status: "WAREHOUSE",
              currentQuantity: 1,
              observedQuantity: 0,
            },
          ],
        },
      ],
    }
    const sessionDetail: InventorySessionDetail = refreshedSession
    const mappedSession = toInventorySessionView({
      session: {
        ...sessionDetail,
        sessionRevision: 8,
      },
      findings: [rawFinding],
    })
    inventoryHttp.startFurnitureReview.mockResolvedValue(review)
    inventoryHttp.getFurnitureReview.mockResolvedValue(review)
    inventoryHttp.saveFurnitureReview.mockResolvedValue({
      ...review,
      confirmed: true,
      reviewSha256: "b".repeat(64),
    })

    const started = await startInventoryFurnitureReview({
      session: mappedSession,
      acknowledgeIncomplete: true,
    })
    const loaded = await getInventoryFurnitureReview(INVENTORY_ID)
    const saved = await saveInventoryFurnitureReview({
      review: {
        ...started,
        items: [
          {
            ...started.items[0],
            observedStockQuantity: 3,
            cabins: [{ ...started.items[0].cabins[0], observedQuantity: 0 }],
          },
        ],
      },
      session: mappedSession,
    })

    expect(inventoryHttp.startFurnitureReview).toHaveBeenCalledWith({
      accessToken: "inventory-token",
      inventoryId: INVENTORY_ID,
      request: {
        expectedSessionRevision: 8,
        findingRevisions: [
          {
            findingId: rawFinding.id,
            expectedFindingRevision: rawFinding.findingRevision,
          },
        ],
        acknowledgeIncomplete: true,
      },
      idempotencyKey: expect.any(String),
    })
    expect(loaded.items[0]).toMatchObject({
      equipmentName: "Стол",
      observedStockQuantity: 4,
    })
    expect(inventoryHttp.saveFurnitureReview).toHaveBeenCalledWith({
      accessToken: "inventory-token",
      inventoryId: INVENTORY_ID,
      request: {
        expectedSessionRevision: 9,
        assetSnapshotSha256: "a".repeat(64),
        items: [
          {
            equipmentId: "77777777-7777-4777-8777-777777777777",
            catalogVersion: 888,
            observedStockQuantity: 3,
            cabins: [
              {
                findingId: rawFinding.id,
                expectedFindingRevision: rawFinding.findingRevision,
                observedQuantity: 0,
              },
            ],
          },
        ],
      },
    })
    expect(saved).toMatchObject({
      confirmed: true,
      reviewSha256: "b".repeat(64),
    })
  })

  it("fails closed before saving when movement is requested without a connected warehouse capability", async () => {
    inventoryHttp.getInventorySession.mockResolvedValue({
      ...refreshedSession,
      findings: [rawFinding],
    })

    await expect(
      saveInventoryFinding({
        inventoryId: INVENTORY_ID,
        expectedVersion: 8,
        actor,
        findingId: rawFinding.id,
        comment: "",
        media: [],
        coverMediaId: null,
        lines: [workLine],
        repairPlans: [],
        repairCompletionMode: "AUTO",
        movementRequired: true,
        priority: 3,
      })
    ).rejects.toThrow(
      "На складе не подключена очередь для перемещения на отгрузку."
    )

    expect(queueCapabilities.get).toHaveBeenCalledWith(
      "inventory-token",
      WAREHOUSE_ID
    )
    expect(catalogApi.get).not.toHaveBeenCalled()
    expect(inventoryHttp.saveInventoryInspection).not.toHaveBeenCalled()
  })

  it("selects only a catalog movement node connected to the current warehouse", async () => {
    inventoryHttp.getInventorySession.mockResolvedValue({
      ...refreshedSession,
      findings: [rawFinding],
    })
    queueCapabilities.get.mockResolvedValue({
      warehouseId: WAREHOUSE_ID,
      movementToShipmentAvailable: true,
      movementQueueDefinitions: [
        {
          queueDefinitionId: "current-warehouse-movement",
          workQueueId: "current-warehouse-work-queue",
        },
      ],
    })
    catalogApi.get.mockResolvedValue({
      nodes: [
        {
          id: "allowed-movement-location",
          active: true,
          nodeType: "LOCATION",
          routeQueueKind: "MOVEMENT",
          queueDefinitionId: "current-warehouse-movement",
        },
        {
          id: "other-warehouse-movement-location",
          active: true,
          nodeType: "LOCATION",
          routeQueueKind: "MOVEMENT",
          queueDefinitionId: "other-warehouse-movement",
        },
      ],
      links: [],
    })

    await saveInventoryFinding({
      inventoryId: INVENTORY_ID,
      expectedVersion: 8,
      actor,
      findingId: rawFinding.id,
      comment: "",
      media: [],
      coverMediaId: null,
      lines: [workLine],
      repairPlans: [],
      repairCompletionMode: "AUTO",
      movementRequired: true,
      logisticsPlanningMode: "FIXED_DATE",
      logisticsScheduledDate: "2026-08-12",
      priority: 3,
    })

    expect(inventoryHttp.saveInventoryInspection).toHaveBeenCalledWith(
      expect.objectContaining({
        accessToken: "inventory-token",
        inventoryId: INVENTORY_ID,
        findingId: rawFinding.id,
        planSelection: expect.objectContaining({
          logisticsPlanningMode: "FIXED_DATE",
          logisticsScheduledDate: "2026-08-12",
          stages: [
            {
              catalogNodeId: "allowed-movement-location",
              kind: "MOVE_TO_REPAIR",
              order: 0,
            },
            {
              catalogNodeId: "allowed-movement-location",
              kind: "MOVE_FROM_REPAIR",
              order: 2,
            },
          ],
        }),
      })
    )
  })
})
