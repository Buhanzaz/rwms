import { describe, expect, it } from "vitest"

import {
  applyInventoryCompletionPreview,
  toInventoryFurnitureReviewView,
  toInventoryFindingView,
  toInventorySessionView,
} from "@/features/inventory/domain/inventory-view-mapper"
import type {
  InventoryCompletionPreview,
  InventoryFinding,
  InventoryFrozenStatistics,
  InventorySessionDetail,
} from "@/features/inventory/model/inventory-service"

const finding: InventoryFinding = {
  id: "00000000-0000-4000-8000-000000000201",
  inventoryId: "00000000-0000-4000-8000-000000000202",
  findingRevision: 4,
  origin: "EXPECTED",
  inspection: "WORK_STAGED",
  reconciliation: "MATCHED",
  assetId: "00000000-0000-4000-8000-000000000203",
  assetVersion: 7,
  displayCanonicalNumber: "СПБ-01",
  identityMatchKey: "СПБ01",
  passportObservation: {
    presence: "PRESENT",
    value: { rentalType: "БК-1", linoleum: true },
  },
  equipmentObservation: {
    presence: "PRESENT",
    value: [
      {
        equipmentId: "00000000-0000-4000-8000-000000000250",
        quantity: 2,
      },
    ],
  },
  mutationState: "IDLE",
  planFingerprintSha256: "a".repeat(64),
  comment: "Заменить дверь",
  expectedSnapshot: {
    assetId: "00000000-0000-4000-8000-000000000203",
    assetVersion: 7,
    warehouseId: "00000000-0000-4000-8000-000000000204",
    status: "WAREHOUSE",
    displayCanonicalNumber: "СПБ-01",
    tenantSnapshot: "Арендатор 1",
    passportSnapshot: {},
    contentsSnapshot: [],
  },
  currentSnapshot: {
    assetId: "00000000-0000-4000-8000-000000000203",
    assetVersion: 8,
    warehouseId: "00000000-0000-4000-8000-000000000204",
    status: "WAREHOUSE",
    displayCanonicalNumber: "СПБ-01",
    tenantSnapshot: "Арендатор 1",
    passportSnapshot: { type: "БК-3" },
    contentsSnapshot: [{ name: "Стол" }],
    repairsSnapshot: [
      {
        repairId: "00000000-0000-4000-8000-000000000220",
        rootRepairId: "00000000-0000-4000-8000-000000000220",
        origin: "ESTIMATE",
        kind: "PRIMARY",
        executionState: "READY",
        acceptanceState: "NOT_READY",
        planFingerprintSha256: "d".repeat(64),
      },
    ],
  },
  inspectionBaseline: {
    assetId: "00000000-0000-4000-8000-000000000203",
    assetVersion: 7,
    warehouseId: "00000000-0000-4000-8000-000000000204",
    status: "WAREHOUSE",
    displayCanonicalNumber: "СПБ-01",
    tenantSnapshot: "Арендатор 1",
    passportSnapshot: { type: "БК-2" },
    contentsSnapshot: [],
    repairsSnapshot: [],
  },
  conflictResolution: null,
  conflicts: [
    {
      code: "ASSET_CHANGED",
      message: "Бытовка изменилась после начала инвентаризации",
      expected: "7",
      actual: "8",
    },
  ],
  frozenPlan: {
    mode: "MANUAL",
    movementToRepair: false,
    forceCapitalRepair: true,
    logisticsPlanningMode: null,
    logisticsScheduledDate: null,
    catalogVersionId: "00000000-0000-4000-8000-000000000205",
    fingerprintSha256: "a".repeat(64),
    lines: [
      {
        id: "00000000-0000-4000-8000-000000000206",
        sourceKind: "CATALOG",
        lineType: "WORK",
        catalogVersionId: "00000000-0000-4000-8000-000000000205",
        catalogNodeId: "00000000-0000-4000-8000-000000000207",
        routingQueueId: "00000000-0000-4000-8000-000000000209",
        routingQueueName: "Ремонт",
        routingQueueType: "REPAIR",
        description: "Замена двери",
        normalizedDescription: "замена двери",
        unit: "шт",
        quantity: "1.5",
        unitPriceMinor: 20000,
        normativeMinutes: "45",
        groupComment: "Проверить проём",
        mediaReferences: [
          {
            mediaId: "00000000-0000-4000-8000-000000000251",
            generation: 3,
          },
        ],
      },
    ],
    stages: [
      {
        id: "00000000-0000-4000-8000-000000000208",
        order: 0,
        catalogNodeId: "00000000-0000-4000-8000-000000000207",
        catalogNodeName: "Замена двери",
        kind: "REPAIR_WORK",
        routingQueueId: "00000000-0000-4000-8000-000000000209",
        routingQueueName: "Ремонт",
        routingQueueType: "REPAIR",
        photoRequired: true,
        normativeDurationMinutes: 45,
      },
    ],
  },
  media: [
    {
      mediaId: "00000000-0000-4000-8000-000000000210",
      generation: 2,
    },
  ],
  publication: {
    id: "00000000-0000-4000-8000-000000000211",
    inventoryId: "00000000-0000-4000-8000-000000000202",
    findingId: "00000000-0000-4000-8000-000000000201",
    publicationRevision: 1,
    state: "SUCCEEDED",
    sourceRevision: 4,
    attemptCount: 1,
    maintenanceRepairId: "00000000-0000-4000-8000-000000000212",
    failureCode: null,
  },
}

const statistics: InventoryFrozenStatistics = {
  expectedCount: 1,
  inspectedCount: 1,
  missingCount: 0,
  readyCount: 0,
  withWorkCount: 1,
  addedCount: 0,
  unexpectedExistingCount: 0,
  conflictCount: 1,
  workLineCount: 1,
  materialLineCount: 0,
  workTotalMinor: 30000,
  materialTotalMinor: 0,
  grandTotalMinor: 30000,
  roundingAdjustmentMinor: 0,
  normativeMinutes: "45",
  durationSeconds: 600,
  aggregateLines: [],
}

const session: InventorySessionDetail = {
  id: finding.inventoryId,
  sessionRevision: 5,
  warehouseId: "00000000-0000-4000-8000-000000000204",
  warehouseVersion: 3,
  warehouseTimeZone: "Europe/Moscow",
  author: {
    id: "00000000-0000-4000-8000-000000000213",
    displayName: "Кладовщик Иван",
  },
  businessDate: "2026-07-22",
  lifecycle: "COMPLETED",
  expectedCount: 1,
  findingCount: 1,
  inspectedCount: 1,
  startedAt: "2026-07-22T08:00:00Z",
  terminalAt: "2026-07-22T09:00:00Z",
  publicationState: "SUCCEEDED",
  reviewStage: "FURNITURE",
  furnitureReconciliationState: "SUCCEEDED",
  statistics,
  cancellation: null,
  membershipMovements: [
    {
      id: "00000000-0000-4000-8000-000000000214",
      type: "ARRIVED",
      assetId: finding.assetId!,
      displayCanonicalNumber: "СПБ-01",
      origin: "ADDED_USED",
      fromWarehouseId: null,
      toWarehouseId: "00000000-0000-4000-8000-000000000204",
      status: "WAREHOUSE",
      tenantSnapshot: null,
      occurredAt: "2026-07-22T08:30:00Z",
    },
  ],
}

describe("inventory service view mapper", () => {
  it("restores the old-panel finding shape from authoritative service data", () => {
    const result = toInventoryFindingView(finding)

    expect(result).toMatchObject({
      version: 4,
      cabinNumber: "СПБ-01",
      comment: "Заменить дверь",
      passportObservation: {
        presence: "PRESENT",
        value: { rentalType: "БК-1", linoleum: true },
      },
      equipmentObservation: {
        presence: "PRESENT",
        value: [
          {
            equipmentId: "00000000-0000-4000-8000-000000000250",
            quantity: 2,
          },
        ],
      },
      currentSnapshot: {
        status: "WAREHOUSE",
        tenant: "Арендатор 1",
        passportSnapshot: { type: "БК-3" },
        repairsSnapshot: [{ executionState: "READY" }],
      },
      inspectionBaseline: {
        passportSnapshot: { type: "БК-2" },
      },
      repairCompletionMode: "MANUAL",
      movementToRepair: false,
      logisticsPlanningMode: "AUTO",
      logisticsScheduledDate: null,
      publicationStatus: "PUBLISHED",
      publishedRepairTaskId: "00000000-0000-4000-8000-000000000212",
      lines: [
        {
          id: "00000000-0000-4000-8000-000000000206",
          lineTotal: "300.00",
          catalogSnapshot: {
            nodeId: "00000000-0000-4000-8000-000000000207",
            name: "Замена двери",
          },
          maintenanceMediaReferences: [
            {
              mediaId: "00000000-0000-4000-8000-000000000251",
              generation: 3,
            },
          ],
        },
      ],
      repairPlans: [
        {
          id: "00000000-0000-4000-8000-000000000208",
          includedLineIds: ["00000000-0000-4000-8000-000000000206"],
          plannedDurationMinutes: 45,
          photoRequired: true,
        },
      ],
    })
  })

  it("reads inbound and outbound movement options from the frozen plan fields", () => {
    const result = toInventoryFindingView({
      ...finding,
      frozenPlan: {
        ...finding.frozenPlan!,
        movementToRepair: true,
        forceCapitalRepair: false,
        logisticsPlanningMode: "AUTO",
      },
    })

    expect(result).toMatchObject({
      movementToRepair: true,
      forceCapitalRepair: false,
      logisticsPlanningMode: "AUTO",
    })
  })

  it("allocates repeated catalog works and materials to exactly one frozen stage", () => {
    const firstLine = finding.frozenPlan!.lines[0]!
    const secondLine = {
      ...firstLine,
      id: "00000000-0000-4000-8000-000000000260",
      groupComment: "Второй проход",
    }
    const materialLine = {
      ...firstLine,
      id: "00000000-0000-4000-8000-000000000261",
      sourceKind: "MANUAL" as const,
      lineType: "MATERIAL" as const,
      catalogVersionId: null,
      catalogNodeId: null,
      description: "Крепёж",
      normalizedDescription: "крепёж",
      groupComment: null,
      mediaReferences: [],
    }
    const secondStage = {
      ...finding.frozenPlan!.stages[0]!,
      id: "00000000-0000-4000-8000-000000000262",
      order: 1,
    }

    const result = toInventoryFindingView({
      ...finding,
      frozenPlan: {
        ...finding.frozenPlan!,
        lines: [firstLine, secondLine, materialLine],
        stages: [finding.frozenPlan!.stages[0]!, secondStage],
      },
    })

    expect(result.repairPlans.map((plan) => plan.includedLineIds)).toEqual([
      [firstLine.id],
      [secondLine.id, materialLine.id],
    ])
    expect(result.repairPlans.flatMap((plan) => plan.includedLineIds)).toEqual([
      firstLine.id,
      secondLine.id,
      materialLine.id,
    ])
  })

  it("restores the selected queue for a manual work line", () => {
    const manualWork = {
      ...finding.frozenPlan!.lines[0]!,
      id: "00000000-0000-4000-8000-000000000263",
      sourceKind: "MANUAL" as const,
      catalogVersionId: null,
      catalogNodeId: null,
      description: "Пользовательская работа",
      normalizedDescription: "пользовательская работа",
    }
    const result = toInventoryFindingView({
      ...finding,
      frozenPlan: {
        ...finding.frozenPlan!,
        lines: [manualWork],
      },
    })

    expect(result.lines[0]?.customQueueBinding).toEqual({
      queueId: manualWork.routingQueueId,
      queueName: manualWork.routingQueueName,
      queueKind: manualWork.routingQueueType,
    })
    expect(result.repairPlans[0]).toMatchObject({
      includedLineIds: [manualWork.id],
      primaryLineId: manualWork.id,
      routingCatalogNodeId: finding.frozenPlan!.stages[0]!.catalogNodeId,
    })
  })

  it("never exposes conflicts for a finding that has not been inspected", () => {
    const result = toInventoryFindingView({
      ...finding,
      inspection: "NOT_INSPECTED",
      reconciliation: "CONFLICT",
    })

    expect(result.inspectionStatus).toBe("NOT_INSPECTED")
    expect(result.conflicts).toEqual([])
  })

  it("preserves the cancellation audit in the panel view", () => {
    const cancelledAt = "2026-07-22T09:15:00Z"
    const result = toInventorySessionView({
      session: {
        ...session,
        lifecycle: "CANCELLED",
        terminalAt: cancelledAt,
        statistics: null,
        cancellation: {
          reason: "Сессия открыта для неверного склада",
          cancelledAt,
        },
      },
      findings: [finding],
    })

    expect(result.status).toBe("CANCELLED")
    expect(result.completedAt).toBe(cancelledAt)
    expect(result.cancellation).toEqual({
      reason: "Сессия открыта для неверного склада",
      cancelledAt,
    })
  })

  it("uses the service-issued author and applies fresh completion conflicts", () => {
    const view = toInventorySessionView({
      session,
      findings: [finding],
      statistics,
    })
    const preview: InventoryCompletionPreview = {
      inventoryId: session.id,
      sessionRevision: 6,
      finalPlanVersion: 1,
      finalPlanSha256: "a".repeat(64),
      findingRevisions: [{ findingId: finding.id, expectedFindingRevision: 4 }],
      validationSha256: "b".repeat(64),
      validatedAt: "2026-07-22T09:01:00Z",
      acknowledgementSha256: "c".repeat(64),
      statistics,
      risks: [{ findingId: finding.id, code: "ASSET_CHANGED" }],
      validatedFindings: [
        {
          findingId: finding.id,
          currentSnapshot: { ...finding.currentSnapshot!, status: "RENTED" },
          conflicts: [
            {
              code: "RENTED",
              message: "Бытовка числится в аренде",
              expected: "WAREHOUSE",
              actual: "RENTED",
            },
          ],
        },
      ],
    }

    const result = applyInventoryCompletionPreview(view, preview)

    expect(result.author.displayName).toBe("Кладовщик Иван")
    expect(result.version).toBe(6)
    expect(result.reviewStage).toBe("FURNITURE")
    expect(result.furnitureReconciliationState).toBe("SUCCEEDED")
    expect(result.findings[0]).toMatchObject({
      currentSnapshot: { status: "RENTED" },
      reconciliationStatus: "CONFLICT",
      conflicts: [{ code: "RENTED" }, { code: "ASSET_CHANGED" }],
    })
    expect(result.statistics?.grandTotal).toBe("300.00")
    expect(result.membershipMovements).toEqual([
      expect.objectContaining({
        type: "ARRIVED",
        status: "WAREHOUSE",
      }),
    ])
  })

  it("maps the authoritative furniture review without a second browser data source", () => {
    const review = toInventoryFurnitureReviewView({
      inventoryId: session.id,
      sessionRevision: 6,
      stage: "FURNITURE",
      assetSnapshotSha256: "e".repeat(64),
      reviewSha256: null,
      confirmed: false,
      items: [
        {
          equipmentId: "00000000-0000-4000-8000-000000000230",
          catalogVersion: 231,
          equipmentName: "Стол",
          currentStockQuantity: 4,
          observedStockQuantity: 3,
          cabins: [
            {
              findingId: finding.id,
              assetId: finding.assetId!,
              cabinNumber: finding.displayCanonicalNumber,
              status: "WAREHOUSE",
              currentQuantity: 1,
              observedQuantity: 0,
            },
          ],
        },
      ],
    })

    expect(review).toEqual({
      inventoryId: session.id,
      sessionRevision: 6,
      stage: "FURNITURE",
      assetSnapshotSha256: "e".repeat(64),
      reviewSha256: null,
      confirmed: false,
      items: [
        expect.objectContaining({
          equipmentName: "Стол",
          currentStockQuantity: 4,
          observedStockQuantity: 3,
          cabins: [
            expect.objectContaining({
              findingId: finding.id,
              observedQuantity: 0,
            }),
          ],
        }),
      ],
    })
  })
})
