import { describe, expect, it } from "vitest"

import { buildAutoInventoryPlan } from "@/features/inventory/domain/inventory-plan-mapper"
import type { RepairEstimateLineDto } from "@/features/repair-estimates/model/repair-estimate"

function catalogLine(
  id: string,
  nodeType: "WORK" | "MATERIAL" | "OPTION"
): RepairEstimateLineDto {
  return {
    id,
    sourceLineKey: id,
    lineType: nodeType === "WORK" ? "WORK" : "MATERIAL",
    description: `${nodeType}-${id}`,
    lineComment: "Проверено оператором",
    unit: "шт.",
    quantity: 2,
    unitPrice: "100.00",
    lineTotal: "200.00",
    catalogSnapshot: {
      nodeId: id,
      code: `CODE-${id}`,
      name: `${nodeType}-${id}`,
      nodeType,
      furnitureEquipment: null,
    },
  }
}

describe("inventory AUTO catalog plan", () => {
  it("sends only exact catalog node and quantity evidence for work and material", () => {
    const work = catalogLine("00000000-0000-4000-8000-000000000701", "WORK")
    const material = catalogLine(
      "00000000-0000-4000-8000-000000000702",
      "MATERIAL"
    )
    const option = catalogLine("00000000-0000-4000-8000-000000000703", "OPTION")

    expect(buildAutoInventoryPlan([work, material, option, work])).toEqual({
      mode: "AUTO",
      lines: [work, material].map((line) => ({
        aggregationKind: "CATALOG",
        catalogNodeId: line.catalogSnapshot!.nodeId,
        description: null,
        type: null,
        unit: null,
        quantity: "2",
        unitPriceMinor: null,
        normativeMinutes: null,
        groupComment: "Проверено оператором",
        mediaReferences: [],
      })),
      stages: [],
    })
  })

  it("fails closed until at least one routed catalog work is selected", () => {
    expect(
      buildAutoInventoryPlan([
        catalogLine("00000000-0000-4000-8000-000000000704", "MATERIAL"),
      ])
    ).toBeNull()
  })
})
