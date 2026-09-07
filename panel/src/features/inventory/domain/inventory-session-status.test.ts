import { describe, expect, it } from "vitest"

import { inventorySessionStatus } from "@/features/inventory/domain/inventory-session-status"
import type { InventoryFindingDto } from "@/features/inventory/model/inventory"

function snapshot(
  status: NonNullable<InventoryFindingDto["currentSnapshot"]>["status"]
): NonNullable<InventoryFindingDto["currentSnapshot"]> {
  return {
    rentalItemId: "asset-1",
    number: "БЫТ-001",
    canonicalNumber: "БЫТ-001",
    warehouseId: "warehouse-1",
    status,
    tenant: null,
    passportSnapshot: {},
    contentsSnapshot: [],
    repairsSnapshot: [],
  }
}

function finding(
  overrides: Partial<InventoryFindingDto> = {}
): InventoryFindingDto {
  return {
    inspectionStatus: "NOT_INSPECTED",
    lines: [],
    forceCapitalRepair: false,
    currentSnapshot: snapshot("WAREHOUSE"),
    inspectionBaseline: null,
    expectedSnapshot: null,
    preserveOperationalState: false,
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
          currentSnapshot: snapshot("RENTED"),
        })
      )
    ).toBe("FREE")
  })

  it("shows staged work as ordinary or capital repair", () => {
    const staged = finding({
      inspectionStatus: "WORK_STAGED",
      repairCompletionMode: "MANUAL",
      lines: [{}] as InventoryFindingDto["lines"],
      currentSnapshot: snapshot("FREE"),
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
          currentSnapshot: snapshot("FREE"),
        })
      )
    ).toBe("FREE")
  })

  it("uses the return result while the returned cabin is still operationally current", () => {
    expect(
      inventorySessionStatus(
        finding({
          inspectionSource: "LOGISTICS_RETURN",
          inspectionStatus: "READY",
          currentSnapshot: snapshot("FREE"),
        })
      )
    ).toBe("FREE")
    expect(
      inventorySessionStatus(
        finding({
          inspectionSource: "LOGISTICS_RETURN",
          inspectionStatus: "WORK_STAGED",
          currentSnapshot: snapshot("WAITING_ESTIMATE_CONFIRMATION"),
        })
      )
    ).toBe("WAITING_ESTIMATE_CONFIRMATION")
  })

  it("uses only the latest owner status for a preserved historical inspection", () => {
    expect(
      inventorySessionStatus(
        finding({
          inspectionStatus: "WORK_STAGED",
          preserveOperationalState: true,
          currentSnapshot: snapshot("RENTED"),
          inspectionBaseline: snapshot("REPAIR"),
          expectedSnapshot: snapshot("FREE"),
          repairCompletionMode: "MANUAL",
          forceCapitalRepair: true,
        })
      )
    ).toBe("RENTED")
    expect(
      inventorySessionStatus(
        finding({
          preserveOperationalState: true,
          currentSnapshot: null,
          inspectionBaseline: snapshot("REPAIR"),
        })
      )
    ).toBeNull()
  })
})
