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
  cancelInventorySession: vi.fn(),
  getFurnitureReview: vi.fn(),
  getInventoryPreliminaryStatistics: vi.fn(),
  getInventorySession: vi.fn(),
  recalculateInventoryOutcome: vi.fn(),
  refreshInventorySession: vi.fn(),
  resolveInventoryNumber: vi.fn(),
  resolveInventoryFindingConflict: vi.fn(),
  reviewInventoryRegistry: vi.fn(),
  saveFurnitureReview: vi.fn(),
  saveInventoryInspection: vi.fn(),
  startFurnitureReview: vi.fn(),
}))
const queueCapabilities = vi.hoisted(() => ({ get: vi.fn() }))

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

import {
  cancelInventory,
  getInventoryFurnitureReview,
  getInventoryPreliminaryStatistics,
  recalculateInventoryOutcome,
  refreshInventorySession,
  resolveInventoryFindingConflict,
  resolveInventoryNumber,
  reviewInventoryRegistry,
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

const inspectionObservations = {
  passportObservation: rawFinding.passportObservation,
  equipmentObservation: rawFinding.equipmentObservation,
}

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
    inventoryHttp.getInventoryPreliminaryStatistics.mockResolvedValue({
      expectedCount: 1,
      inspectedCount: 1,
      missingCount: 0,
      readyCount: 1,
      withWorkCount: 1,
      addedCount: 0,
      unexpectedExistingCount: 0,
      conflictCount: 0,
      workLineCount: 1,
      materialLineCount: 1,
      workTotalMinor: 10000,
      materialTotalMinor: 5000,
      grandTotalMinor: 15000,
      roundingAdjustmentMinor: 0,
      normativeMinutes: "60",
      durationSeconds: 3600,
      aggregateLines: [
        {
          aggregationKind: "CATALOG",
          catalogVersionId: "66666666-6666-4666-8666-666666666666",
          catalogNodeId: "77777777-7777-4777-8777-777777777777",
          normalizedDescription: "Дверь металлическая",
          type: "MATERIAL",
          unit: "шт.",
          unitPriceMinor: 5000,
          quantity: "1",
          rowTotalMinor: 5000,
        },
      ],
    })
    inventoryHttp.saveInventoryInspection.mockResolvedValue(rawFinding)
    queueCapabilities.get.mockResolvedValue({
      warehouseId: WAREHOUSE_ID,
      movementQueueDefinitions: [],
    })
  })

  it("cancels with the exact session revision and a trimmed reason", async () => {
    const cancelledAt = "2026-07-27T09:00:00Z"
    inventoryHttp.cancelInventorySession.mockResolvedValue({
      ...refreshedSession,
      lifecycle: "CANCELLED",
      sessionRevision: 9,
      terminalAt: cancelledAt,
      cancellation: {
        reason: "Ошибочно выбран склад",
        cancelledAt,
      },
    })

    const result = await cancelInventory({
      inventoryId: INVENTORY_ID,
      expectedVersion: 8,
      reason: "  Ошибочно выбран склад  ",
    })

    expect(inventoryHttp.cancelInventorySession).toHaveBeenCalledWith({
      accessToken: "inventory-token",
      inventoryId: INVENTORY_ID,
      expectedSessionRevision: 8,
      reason: "Ошибочно выбран склад",
      idempotencyKey: expect.any(String),
    })
    expect(result).toMatchObject({
      status: "CANCELLED",
      version: 9,
      cancellation: {
        reason: "Ошибочно выбран склад",
        cancelledAt,
      },
    })
  })

  it("maps server-issued preliminary totals without calculating them in the browser", async () => {
    const result = await getInventoryPreliminaryStatistics(INVENTORY_ID)

    expect(
      inventoryHttp.getInventoryPreliminaryStatistics
    ).toHaveBeenCalledWith("inventory-token", INVENTORY_ID)
    expect(result).toMatchObject({
      workTotal: "100.00",
      materialTotal: "50.00",
      grandTotal: "150.00",
      aggregates: [
        expect.objectContaining({
          description: "Дверь металлическая",
          total: "50.00",
        }),
      ],
    })
  })

  it("refreshes derived session state and then returns the complete saved findings", async () => {
    const sessionAfterRefresh = {
      ...refreshedSession,
      sessionRevision: 9,
      reviewStage: "FURNITURE" as const,
      furnitureReconciliationState: "READY" as const,
      findings: [{ ...rawFinding, inspection: "READY" as const }],
    }
    inventoryHttp.refreshInventorySession.mockResolvedValue({
      ...sessionAfterRefresh,
      findings: undefined,
    })
    inventoryHttp.getInventorySession.mockResolvedValue(sessionAfterRefresh)

    const result = await refreshInventorySession({
      inventoryId: INVENTORY_ID,
      expectedSessionRevision: 8,
    })

    expect(inventoryHttp.refreshInventorySession).toHaveBeenCalledWith({
      accessToken: "inventory-token",
      inventoryId: INVENTORY_ID,
      request: { expectedSessionRevision: 8 },
      idempotencyKey: expect.any(String),
    })
    expect(inventoryHttp.getInventorySession).toHaveBeenLastCalledWith(
      "inventory-token",
      INVENTORY_ID
    )
    expect(result).toMatchObject({
      id: INVENTORY_ID,
      version: 9,
      reviewStage: "FURNITURE",
      findings: [
        expect.objectContaining({
          id: rawFinding.id,
          inspectionStatus: "READY",
        }),
      ],
    })
  })

  it("recalculates a completed outcome with the exact final-plan identity", async () => {
    const outcome = {
      inventoryId: INVENTORY_ID,
      sessionRevision: 8,
      finalPlanVersion: 4,
      finalPlanSha256: "f".repeat(64),
      furnitureReconciliationState: "PENDING" as const,
      createdPublicationCount: 75,
      requeuedPublicationCount: 89,
      preservedSucceededPublicationCount: 52,
      publicationBatch: {
        inventoryId: INVENTORY_ID,
        aggregateState: "PENDING" as const,
        intents: [],
      },
    }
    inventoryHttp.recalculateInventoryOutcome.mockResolvedValue(outcome)

    const result = await recalculateInventoryOutcome({
      inventoryId: INVENTORY_ID,
      expectedSessionRevision: 8,
      finalPlanVersion: 4,
      finalPlanSha256: "f".repeat(64),
    })

    expect(inventoryHttp.recalculateInventoryOutcome).toHaveBeenCalledWith({
      accessToken: "inventory-token",
      inventoryId: INVENTORY_ID,
      request: {
        expectedSessionRevision: 8,
        finalPlanVersion: 4,
        finalPlanSha256: "f".repeat(64),
      },
      idempotencyKey: expect.any(String),
    })
    expect(result).toEqual(outcome)
    expect(inventoryHttp.getInventorySession).not.toHaveBeenCalled()
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

  it("loads an exact registry review and exposes its conflicts to the finish page", async () => {
    const inspectedFinding = { ...rawFinding, inspection: "READY" as const }
    const currentSnapshot = {
      assetId: rawFinding.assetId!,
      assetVersion: 3,
      warehouseId: WAREHOUSE_ID,
      status: "REPAIR",
      displayCanonicalNumber: rawFinding.displayCanonicalNumber,
      tenantSnapshot: null,
      passportSnapshot: {},
      contentsSnapshot: [],
      repairsSnapshot: [],
    }
    inventoryHttp.getInventorySession.mockResolvedValue({
      ...refreshedSession,
      findings: [inspectedFinding],
    })
    inventoryHttp.reviewInventoryRegistry.mockResolvedValue({
      inventoryId: INVENTORY_ID,
      sessionRevision: 8,
      findingRevisions: [
        {
          findingId: rawFinding.id,
          expectedFindingRevision: rawFinding.findingRevision,
        },
      ],
      validatedAt: "2026-08-04T10:00:00Z",
      validatedFindings: [
        {
          findingId: rawFinding.id,
          currentSnapshot,
          conflicts: [
            {
              code: "STATUS_CHANGED",
              message: "Статус бытовки изменился после осмотра",
              expected: "WAREHOUSE",
              actual: "REPAIR",
            },
          ],
        },
      ],
    })

    const result = await reviewInventoryRegistry(INVENTORY_ID)

    expect(inventoryHttp.reviewInventoryRegistry).toHaveBeenCalledWith({
      accessToken: "inventory-token",
      session: expect.objectContaining({
        id: INVENTORY_ID,
        sessionRevision: 8,
        findings: [inspectedFinding],
      }),
    })
    expect(result.findings[0]).toMatchObject({
      currentSnapshot: { status: "REPAIR" },
      reconciliationStatus: "CONFLICT",
      conflicts: [{ code: "STATUS_CHANGED" }],
    })
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
        expectedFindingVersion: rawFinding.findingRevision,
        actor,
        findingId: rawFinding.id,
        comment: "",
        ...inspectionObservations,
        media: [],
        coverMediaId: null,
        lines: [workLine],
        repairPlans: [],
        repairCompletionMode: "AUTO",
        movementToRepair: true,
        priority: 3,
      })
    ).rejects.toThrow("На складе не подключена очередь для перемещений.")

    expect(queueCapabilities.get).toHaveBeenCalledWith(
      "inventory-token",
      WAREHOUSE_ID
    )
    expect(inventoryHttp.saveInventoryInspection).not.toHaveBeenCalled()
  })

  it("refreshes the session fence but keeps the draft finding fence", async () => {
    inventoryHttp.getInventorySession.mockResolvedValue({
      ...refreshedSession,
      sessionRevision: 9,
      findings: [{ ...rawFinding, findingRevision: 5 }],
    })

    await saveInventoryFinding({
      inventoryId: INVENTORY_ID,
      expectedFindingVersion: 4,
      actor,
      findingId: rawFinding.id,
      comment: "Черновик первого оператора",
      passportObservation: {
        presence: "PRESENT",
        value: { rentalType: "БК-2" },
      },
      equipmentObservation: { presence: "EXPLICIT_EMPTY", value: [] },
      media: [],
      coverMediaId: null,
      lines: [],
      repairPlans: [],
      repairCompletionMode: null,
      movementToRepair: false,
      priority: 3,
    })

    expect(inventoryHttp.saveInventoryInspection).toHaveBeenCalledWith(
      expect.objectContaining({
        expectedSessionRevision: 9,
        expectedFindingRevision: 4,
        passportObservation: {
          presence: "PRESENT",
          value: { rentalType: "БК-2" },
        },
        equipmentObservation: { presence: "EXPLICIT_EMPTY", value: [] },
      })
    )
  })

  it("sends the repair movement cycle and selected priority to the inventory contract", async () => {
    inventoryHttp.getInventorySession.mockResolvedValue({
      ...refreshedSession,
      findings: [rawFinding],
    })
    queueCapabilities.get.mockResolvedValue({
      warehouseId: WAREHOUSE_ID,
      movementQueueDefinitions: [
        { queueDefinitionId: "movement", workQueueId: "driver" },
      ],
    })

    await saveInventoryFinding({
      inventoryId: INVENTORY_ID,
      expectedFindingVersion: rawFinding.findingRevision,
      actor,
      findingId: rawFinding.id,
      comment: "",
      ...inspectionObservations,
      media: [],
      coverMediaId: null,
      lines: [workLine],
      repairPlans: [],
      repairCompletionMode: "AUTO",
      movementToRepair: true,
      forceCapitalRepair: false,
      logisticsPlanningMode: "FIXED_DATE",
      logisticsScheduledDate: "2026-08-12",
      priority: 1,
    })

    expect(inventoryHttp.saveInventoryInspection).toHaveBeenCalledWith(
      expect.objectContaining({
        accessToken: "inventory-token",
        inventoryId: INVENTORY_ID,
        findingId: rawFinding.id,
        planSelection: expect.objectContaining({
          priority: 1,
          movementToRepair: true,
          forceCapitalRepair: false,
          logisticsPlanningMode: "FIXED_DATE",
          logisticsScheduledDate: "2026-08-12",
          stages: [],
        }),
      })
    )
  })

  it("does not attach logistics planning when repair movement is not selected", async () => {
    inventoryHttp.getInventorySession.mockResolvedValue({
      ...refreshedSession,
      findings: [rawFinding],
    })
    await saveInventoryFinding({
      inventoryId: INVENTORY_ID,
      expectedFindingVersion: rawFinding.findingRevision,
      actor,
      findingId: rawFinding.id,
      comment: "",
      ...inspectionObservations,
      media: [],
      coverMediaId: null,
      lines: [workLine],
      repairPlans: [],
      repairCompletionMode: "AUTO",
      movementToRepair: false,
      logisticsPlanningMode: "FIXED_DATE",
      logisticsScheduledDate: "2026-08-12",
      priority: 2,
    })

    expect(inventoryHttp.saveInventoryInspection).toHaveBeenCalledWith(
      expect.objectContaining({
        planSelection: expect.objectContaining({
          priority: 2,
          movementToRepair: false,
          logisticsPlanningMode: null,
          logisticsScheduledDate: null,
        }),
      })
    )
  })
})
