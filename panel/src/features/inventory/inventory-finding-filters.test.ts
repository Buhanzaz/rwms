import { describe, expect, it } from "vitest"

import {
  createEmptyInventoryFindingFilters,
  filterInventoryFindings,
} from "@/features/inventory/inventory-finding-filtering"
import type { InventoryFindingDto } from "@/features/inventory/model/inventory"

function finding(
  id: string,
  overrides: Partial<InventoryFindingDto> = {}
): InventoryFindingDto {
  return {
    id,
    version: 1,
    rentalItemId: `rental-${id}`,
    canonicalNumber: `БЫТ-${id}`,
    cabinNumber: `БЫТ-${id}`,
    origin: "EXPECTED",
    inspectionStatus: "NOT_INSPECTED",
    reconciliationStatus: "MATCHED",
    expectedSnapshot: {
      rentalItemId: `rental-${id}`,
      number: `БЫТ-${id}`,
      canonicalNumber: `БЫТ-${id}`,
      warehouseId: "warehouse-1",
      status: "FREE",
      tenant: null,
      passportSnapshot: {},
      contentsSnapshot: [],
      repairsSnapshot: [],
    },
    currentSnapshot: {
      rentalItemId: `rental-${id}`,
      number: `БЫТ-${id}`,
      canonicalNumber: `БЫТ-${id}`,
      warehouseId: "warehouse-1",
      status: "FREE",
      tenant: null,
      passportSnapshot: {},
      contentsSnapshot: [],
      repairsSnapshot: [],
    },
    inspectionBaseline: null,
    conflictResolution: null,
    conflicts: [],
    comment: "",
    media: [],
    coverMediaId: null,
    inspectionSource: null,
    lines: [],
    repairCompletionMode: null,
    repairPriority: 3,
    movementRequired: false,
    repairPlans: [],
    publicationStatus: "NOT_REQUIRED",
    publicationOperationKey: null,
    publishedRepairTaskId: null,
    publicationError: null,
    ...overrides,
  }
}

describe("inventory finding filters", () => {
  it("combines the cabin, source, status, reconciliation, inspection, addition, presence, and work filters", () => {
    const target = finding("007", {
      origin: "ADDED_NEW",
      inspectionStatus: "WORK_STAGED",
      reconciliationStatus: "MATCHED",
      currentSnapshot: {
        rentalItemId: "rental-007",
        number: "БЫТ-007",
        canonicalNumber: "БЫТ-007",
        warehouseId: "warehouse-1",
        status: "AFTER_RENT",
        tenant: null,
        passportSnapshot: {},
        contentsSnapshot: [],
        repairsSnapshot: [],
      },
      conflicts: [
        {
          code: "STATUS_CHANGED",
          message: "Статус изменён",
          expected: "Свободна",
          actual: "Ожидает осмотра",
        },
      ],
      lines: [{}] as InventoryFindingDto["lines"],
    })
    const withoutWork = { ...target, id: "008", lines: [] }

    const result = filterInventoryFindings([target, withoutWork], {
      ...createEmptyInventoryFindingFilters(),
      cabinNumber: "007",
      origins: ["ADDED_NEW"],
      statuses: ["AFTER_RENT"],
      reconciliations: ["CONFLICT"],
      inspections: ["INSPECTED"],
      additions: ["ADDED"],
      presences: ["FOUND"],
      works: ["WITH_WORK"],
    })

    expect(result).toEqual([target])
  })

  it("supports the inverse inspection, addition, presence, and work filters", () => {
    const missing = finding("013", {
      canonicalNumber: "КАН-013",
      cabinNumber: "БЫТ-013",
      reconciliationStatus: "MISSING",
    })
    const inspected = finding("014", {
      reconciliationStatus: "MISSING",
      inspectionStatus: "READY",
    })

    const result = filterInventoryFindings([missing, inspected], {
      ...createEmptyInventoryFindingFilters(),
      cabinNumber: "кан-013",
      inspections: ["NOT_INSPECTED"],
      additions: ["NOT_ADDED"],
      presences: ["MISSING"],
      works: ["WITHOUT_WORK"],
    })

    expect(result).toEqual([missing])
  })
})
