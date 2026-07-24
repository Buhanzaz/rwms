import { describe, expect, it } from "vitest"

import {
  applyInventoryCompletionPreview,
  toInventoryFindingView,
  toInventorySessionView,
} from "@/features/inventory/domain/inventory-view-mapper"
import type {
  InventoryCompletionPreview,
  InventoryFinding,
  InventoryFrozenStatistics,
  InventorySessionSummary,
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
  passportObservation: { presence: "ABSENT", value: null },
  equipmentObservation: { presence: "ABSENT", value: null },
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
  },
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
    catalogVersionId: "00000000-0000-4000-8000-000000000205",
    fingerprintSha256: "a".repeat(64),
    lines: [
      {
        id: "00000000-0000-4000-8000-000000000206",
        sourceKind: "CATALOG",
        lineType: "WORK",
        catalogVersionId: "00000000-0000-4000-8000-000000000205",
        catalogNodeId: "00000000-0000-4000-8000-000000000207",
        description: "Замена двери",
        normalizedDescription: "замена двери",
        unit: "шт",
        quantity: "1.5",
        unitPriceMinor: 20000,
        normativeMinutes: "45",
        groupComment: "Проверить проём",
      },
    ],
    stages: [
      {
        id: "00000000-0000-4000-8000-000000000208",
        order: 0,
        catalogNodeId: "00000000-0000-4000-8000-000000000207",
        catalogNodeCode: "DOOR_REPLACE",
        kind: "REPAIR_WORK",
        routingQueueId: "00000000-0000-4000-8000-000000000209",
        routingQueueCode: "REPAIR",
        routingQueueKind: "REPAIR",
        movementRequired: false,
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

const session: InventorySessionSummary = {
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
}

describe("inventory service view mapper", () => {
  it("restores the old-panel finding shape from authoritative service data", () => {
    const result = toInventoryFindingView(finding)

    expect(result).toMatchObject({
      version: 4,
      cabinNumber: "СПБ-01",
      comment: "Заменить дверь",
      currentSnapshot: {
        status: "WAREHOUSE",
        tenant: "Арендатор 1",
      },
      repairCompletionMode: "MANUAL",
      publicationStatus: "PUBLISHED",
      publishedRepairTaskId: "00000000-0000-4000-8000-000000000212",
      lines: [
        {
          id: "00000000-0000-4000-8000-000000000206",
          lineTotal: "300.00",
          catalogSnapshot: { code: "DOOR_REPLACE" },
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

  it("uses the service-issued author and applies fresh completion conflicts", () => {
    const view = toInventorySessionView({
      session,
      findings: [finding],
      statistics,
    })
    const preview: InventoryCompletionPreview = {
      inventoryId: session.id,
      sessionRevision: 6,
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
    expect(result.findings[0]).toMatchObject({
      currentSnapshot: { status: "RENTED" },
      reconciliationStatus: "CONFLICT",
      conflicts: [{ code: "RENTED" }, { code: "ASSET_CHANGED" }],
    })
    expect(result.statistics?.grandTotal).toBe("300.00")
  })
})
