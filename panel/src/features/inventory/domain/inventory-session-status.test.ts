import { describe, expect, it } from "vitest"

import { inventorySessionStatus } from "@/features/inventory/domain/inventory-session-status"
import type { InventoryFindingDto } from "@/features/inventory/model/inventory"

function finding(
  overrides: Partial<InventoryFindingDto> = {}
): InventoryFindingDto {
  return {
    inspectionStatus: "NOT_INSPECTED",
    lines: [],
    forceCapitalRepair: false,
    currentSnapshot: { status: "WAREHOUSE" },
    inspectionBaseline: null,
    expectedSnapshot: null,
    ...overrides,
  } as InventoryFindingDto
}

describe("inventory session status", () => {
  it("keeps the warehouse snapshot for an uninspected finding", () => {
    expect(inventorySessionStatus(finding())).toBe("WAREHOUSE")
  })

  it("shows a checked no-work finding as free", () => {
    expect(
      inventorySessionStatus(
        finding({
          inspectionStatus: "READY",
          currentSnapshot: { status: "RENTED" },
        })
      )
    ).toBe("FREE")
  })

  it("shows staged work as ordinary or capital repair", () => {
    const staged = finding({
      inspectionStatus: "WORK_STAGED",
      repairCompletionMode: "MANUAL",
      lines: [{}] as InventoryFindingDto["lines"],
      currentSnapshot: { status: "FREE" },
    })

    expect(inventorySessionStatus(staged)).toBe("REPAIR")
    expect(
      inventorySessionStatus({ ...staged, forceCapitalRepair: true })
    ).toBe("CAPITAL_REPAIR")
  })

  it("does not invent repair when the flattened frozen plan evidence is absent", () => {
    expect(
      inventorySessionStatus(
        finding({
          inspectionStatus: "WORK_STAGED",
          currentSnapshot: { status: "FREE" },
        })
      )
    ).toBe("FREE")
  })
})
