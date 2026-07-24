import { describe, expect, it } from "vitest"

import { buildInventoryPlanSelection } from "@/features/inventory/domain/inventory-plan-mapper"
import type { RepairEstimateLineDto } from "@/features/repair-estimates/model/repair-estimate"

const catalogWork: RepairEstimateLineDto = {
  id: "work-1",
  sourceLineKey: "work-1",
  lineType: "WORK",
  description: "Замена двери",
  lineComment: "Снача снять старую",
  unit: "шт",
  quantity: 1,
  unitPrice: "2500.00",
  lineTotal: "2500.00",
  catalogSnapshot: {
    nodeId: "00000000-0000-4000-8000-000000000101",
    code: "DOOR_REPLACE",
    name: "Замена двери",
    nodeType: "WORK",
    furnitureEquipment: null,
  },
}

const manualMaterial: RepairEstimateLineDto = {
  id: "material-1",
  sourceLineKey: "material-1",
  lineType: "MATERIAL",
  description: "Петля",
  lineComment: "",
  unit: "шт",
  quantity: 2,
  unitPrice: "150.50",
  lineTotal: "301.00",
  catalogSnapshot: null,
}

describe("inventory plan mapper", () => {
  it("maps the transferred manual plan to the maintenance freeze contract", () => {
    const result = buildInventoryPlanSelection({
      completionMode: "MANUAL",
      movementRequired: false,
      lines: [catalogWork, manualMaterial],
      media: [
        {
          mediaId: "00000000-0000-4000-8000-000000000102",
          generation: 2,
        },
      ],
      taskPlans: [
        {
          id: "plan-1",
          kind: "REPAIR_WORK",
          includedLineIds: [catalogWork.id, manualMaterial.id],
          primaryLineId: catalogWork.id,
          groupComment: "Плановый комментарий",
          queueCode: "REPAIR",
          routeQueueKind: "REPAIR",
          sortOrder: 10,
          generationStatus: "PENDING_GENERATION",
          workflowRequestRef: null,
        },
      ],
    })

    expect(result).toMatchObject({
      mode: "MANUAL",
      lines: [
        {
          aggregationKind: "CATALOG",
          catalogNodeId: catalogWork.catalogSnapshot?.nodeId,
          groupComment: "Плановый комментарий",
        },
        {
          aggregationKind: "MANUAL",
          description: manualMaterial.description,
          unitPriceMinor: 15050,
        },
      ],
      stages: [
        {
          catalogNodeId: catalogWork.catalogSnapshot?.nodeId,
          kind: "REPAIR_WORK",
          order: 0,
        },
      ],
    })
  })

  it("keeps movement unavailable until a location route is defined", () => {
    expect(() =>
      buildInventoryPlanSelection({
        completionMode: "AUTO",
        movementRequired: true,
        taskPlans: [],
        lines: [catalogWork],
        media: [],
      })
    ).toThrow("Для перемещения не задан маршрут локаций")
  })

  it("rejects manual lines in automatic catalog mode", () => {
    expect(() =>
      buildInventoryPlanSelection({
        completionMode: "AUTO",
        movementRequired: false,
        taskPlans: [],
        lines: [manualMaterial],
        media: [],
      })
    ).toThrow("Автоматический режим доступен только для позиций каталога")
  })
})
