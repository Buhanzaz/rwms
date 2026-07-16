import { describe, expect, it } from "vitest"
import {
  calculateInventoryStatistics,
  canonicalizeInventoryNumber,
  deriveInventoryConflicts,
  deriveInventoryReconciliationStatus,
  reconcileInventoryRepairTaskPlans,
} from "@/features/inventory/domain/inventory-domain"
import type { InventoryFindingDto } from "@/features/inventory/model/inventory"

function finding(overrides: Partial<InventoryFindingDto>): InventoryFindingDto {
  return {
    id: "f-1",
    rentalItemId: "r-1",
    canonicalNumber: "БЫТ-1",
    cabinNumber: "Быт-1",
    origin: "EXPECTED",
    inspectionStatus: "WORK_STAGED",
    reconciliationStatus: "MATCHED",
    expectedSnapshot: null,
    currentSnapshot: null,
    conflicts: [],
    comment: "",
    media: [],
    lines: [],
    repairCompletionMode: null,
    movementRequired: false,
    repairPlans: [],
    publicationStatus: "READY",
    publicationOperationKey: null,
    publishedRepairTaskId: null,
    publicationError: null,
    inspectedAt: "2026-07-11T10:01:00.000Z",
    inspectedBy: {
      id: "u-1",
      displayName: "Тест",
      permissions: ["MANAGE"],
      authorizedWarehouseIds: null,
    },
    ...overrides,
  }
}

describe("inventory domain", () => {
  it("canonicalizes whitespace and case without removing hyphens", () => {
    expect(canonicalizeInventoryNumber("  быт-  12 а ")).toBe("БЫТ- 12 А")
  })

  it("keeps the return movement last when reopening a plan with new work", () => {
    const lines = [
      {
        id: "work-a",
        sourceLineKey: "work-a",
        lineType: "WORK" as const,
        description: "Первая работа",
        lineComment: "",
        unit: "шт",
        quantity: 1,
        unitPrice: "0.00",
        lineTotal: "0.00",
        catalogSnapshot: null,
      },
      {
        id: "work-b",
        sourceLineKey: "work-b",
        lineType: "WORK" as const,
        description: "Новая работа",
        lineComment: "",
        unit: "шт",
        quantity: 1,
        unitPrice: "0.00",
        lineTotal: "0.00",
        catalogSnapshot: null,
      },
    ]
    const reconciled = reconcileInventoryRepairTaskPlans({
      lines,
      stored: [
        {
          id: "move-to",
          kind: "MOVE_TO_REPAIR",
          includedLineIds: [],
          primaryLineId: null,
          groupComment: "",
          queueCode: null,
          routeQueueKind: "MOVEMENT",
          sortOrder: 10,
          plannedDurationMinutes: null,
          photoRequired: false,
        },
        {
          id: "work-a-plan",
          kind: "REPAIR_WORK",
          includedLineIds: ["work-a"],
          primaryLineId: "work-a",
          groupComment: "",
          queueCode: null,
          routeQueueKind: "REPAIR",
          sortOrder: 20,
          plannedDurationMinutes: null,
          photoRequired: false,
        },
        {
          id: "move-from",
          kind: "MOVE_FROM_REPAIR",
          includedLineIds: [],
          primaryLineId: null,
          groupComment: "",
          queueCode: null,
          routeQueueKind: "MOVEMENT",
          sortOrder: 30,
          plannedDurationMinutes: null,
          photoRequired: false,
        },
      ],
      prepared: [
        {
          id: "prepared-work-b",
          kind: "REPAIR_WORK",
          includedLineIds: ["work-b"],
          primaryLineId: "work-b",
          groupComment: "",
          queueCode: null,
          routeQueueKind: "REPAIR",
          sortOrder: 20,
          generationStatus: "PENDING_GENERATION",
          workflowRequestRef: null,
        },
      ],
    })
    expect(reconciled.map((plan) => plan.kind)).toEqual([
      "MOVE_TO_REPAIR",
      "REPAIR_WORK",
      "REPAIR_WORK",
      "MOVE_FROM_REPAIR",
    ])
    expect(reconciled[2]?.includedLineIds).toEqual(["work-b"])
  })

  it("detects registry drift from the start snapshot", () => {
    const expected = {
      rentalItemId: "r-1",
      number: "БЫТ-1",
      canonicalNumber: "БЫТ-1",
      warehouseId: "spb",
      status: "FREE" as const,
      tenant: null,
    }
    const actual = {
      ...expected,
      warehouseId: "msk",
      status: "RENTED" as const,
      tenant: "ООО Арендатор",
    }
    expect(
      deriveInventoryConflicts(expected, actual, "spb").map((item) => item.code)
    ).toEqual(
      expect.arrayContaining([
        "OTHER_WAREHOUSE",
        "RENTED",
        "WAREHOUSE_CHANGED",
        "STATUS_CHANGED",
        "TENANT_CHANGED",
      ])
    )
  })

  it("keeps absent, moved, and written-off items missing during recheck", () => {
    const current = {
      rentalItemId: "r-1",
      number: "БЫТ-1",
      canonicalNumber: "БЫТ-1",
      warehouseId: "spb",
      status: "FREE" as const,
      tenant: null,
    }
    expect(
      deriveInventoryReconciliationStatus(
        null,
        deriveInventoryConflicts(current, null, "spb")
      )
    ).toBe("MISSING")
    const moved = { ...current, warehouseId: "msk" }
    expect(
      deriveInventoryReconciliationStatus(
        moved,
        deriveInventoryConflicts(current, moved, "spb")
      )
    ).toBe("MISSING")
    const writtenOff = { ...current, status: "WRITTEN_OFF" as const }
    expect(
      deriveInventoryReconciliationStatus(
        writtenOff,
        deriveInventoryConflicts(current, writtenOff, "spb")
      )
    ).toBe("MISSING")
  })

  it("uses exact minor-unit totals and stable manual grouping", () => {
    const findings = [
      finding({
        lines: [
          {
            id: "l-1",
            sourceLineKey: "l-1",
            lineType: "WORK",
            description: "Замена окна",
            lineComment: "",
            unit: "шт",
            quantity: 0.1,
            unitPrice: "0.10",
            lineTotal: "0.01",
            catalogSnapshot: null,
          },
          {
            id: "l-2",
            sourceLineKey: "l-2",
            lineType: "WORK",
            description: "  замена   окна ",
            lineComment: "",
            unit: "шт",
            quantity: 0.2,
            unitPrice: "0.10",
            lineTotal: "0.02",
            catalogSnapshot: null,
          },
          {
            id: "l-3",
            sourceLineKey: "l-3",
            lineType: "MATERIAL",
            description: "Стекло",
            lineComment: "",
            unit: "м2",
            quantity: 1,
            unitPrice: "10.00",
            lineTotal: "10.00",
            catalogSnapshot: null,
          },
        ],
      }),
    ]
    const stats = calculateInventoryStatistics({
      startedAt: "2026-07-11T10:00:00.000Z",
      completedAt: "2026-07-11T10:01:30.000Z",
      findings,
    })
    expect(stats.durationSeconds).toBe(90)
    expect(stats.workTotal).toBe("0.03")
    expect(stats.materialTotal).toBe("10.00")
    expect(stats.grandTotal).toBe("10.03")
    expect(stats.aggregates).toHaveLength(2)
    expect(stats.aggregates[0]?.quantity).toBeCloseTo(0.3)
  })
})
