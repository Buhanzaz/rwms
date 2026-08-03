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
    name: "Замена двери",
    nodeType: "WORK",
    furnitureEquipment: null,
    characteristic: null,
  },
  maintenanceMediaReferences: [
    {
      mediaId: "00000000-0000-4000-8000-000000000102",
      generation: 2,
    },
  ],
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
      logisticsPlanningMode: "AUTO",
      logisticsScheduledDate: null,
      priority: 2,
      coverMediaId: "00000000-0000-4000-8000-000000000102",
      lines: [catalogWork, manualMaterial],
      taskPlans: [
        {
          id: "plan-1",
          kind: "REPAIR_WORK",
          includedLineIds: [catalogWork.id, manualMaterial.id],
          primaryLineId: catalogWork.id,
          groupComment: "Плановый комментарий",
          queueId: "00000000-0000-4000-8000-000000000103",
          queueName: "Ремонт",
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
          mediaReferences: catalogWork.maintenanceMediaReferences,
        },
        {
          aggregationKind: "MANUAL",
          description: manualMaterial.description,
          unitPriceMinor: 15050,
          groupComment: null,
          mediaReferences: [],
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
        logisticsPlanningMode: "AUTO",
        logisticsScheduledDate: null,
        priority: 3,
        coverMediaId: null,
        taskPlans: [],
        lines: [catalogWork],
      })
    ).toThrow("В каталоге не настроено расположение для перемещения.")
  })

  it("rejects manual lines in automatic catalog mode", () => {
    expect(() =>
      buildInventoryPlanSelection({
        completionMode: "AUTO",
        movementRequired: false,
        logisticsPlanningMode: "AUTO",
        logisticsScheduledDate: null,
        priority: 3,
        coverMediaId: null,
        taskPlans: [],
        lines: [manualMaterial],
      })
    ).toThrow("Автоматический режим доступен только для позиций каталога")
  })

  it("keeps repeated catalog work stages in a manual plan", () => {
    const repeatedWork: RepairEstimateLineDto = {
      ...catalogWork,
      id: "work-2",
      sourceLineKey: "work-2",
      lineComment: "Повторная проверка",
    }
    const result = buildInventoryPlanSelection({
      completionMode: "MANUAL",
      movementRequired: false,
      logisticsPlanningMode: "AUTO",
      logisticsScheduledDate: null,
      priority: 2,
      coverMediaId: null,
      lines: [catalogWork, repeatedWork],
      taskPlans: [
        {
          id: "plan-1",
          kind: "REPAIR_WORK",
          includedLineIds: [catalogWork.id],
          primaryLineId: catalogWork.id,
          groupComment: catalogWork.lineComment,
          queueId: "00000000-0000-4000-8000-000000000103",
          queueName: "Ремонт",
          routeQueueKind: "REPAIR",
          sortOrder: 10,
          generationStatus: "PENDING_GENERATION",
          workflowRequestRef: null,
        },
        {
          id: "plan-2",
          kind: "REPAIR_WORK",
          includedLineIds: [repeatedWork.id],
          primaryLineId: repeatedWork.id,
          groupComment: repeatedWork.lineComment,
          queueId: "00000000-0000-4000-8000-000000000103",
          queueName: "Ремонт",
          routeQueueKind: "REPAIR",
          sortOrder: 20,
          generationStatus: "PENDING_GENERATION",
          workflowRequestRef: null,
        },
      ],
    })

    expect(result.stages).toEqual([
      {
        catalogNodeId: catalogWork.catalogSnapshot?.nodeId,
        kind: "REPAIR_WORK",
        order: 0,
      },
      {
        catalogNodeId: catalogWork.catalogSnapshot?.nodeId,
        kind: "REPAIR_WORK",
        order: 1,
      },
    ])
  })

  it("preserves a fixed logistics date and rejects inconsistent planning", () => {
    const fixed = buildInventoryPlanSelection({
      completionMode: "AUTO",
      movementRequired: true,
      logisticsPlanningMode: "FIXED_DATE",
      logisticsScheduledDate: "2026-08-12",
      movementCatalogNodeId: "00000000-0000-4000-8000-000000000104",
      priority: 1,
      coverMediaId: null,
      taskPlans: [],
      lines: [catalogWork],
    })

    expect(fixed).toMatchObject({
      logisticsPlanningMode: "FIXED_DATE",
      logisticsScheduledDate: "2026-08-12",
    })
    expect(() =>
      buildInventoryPlanSelection({
        completionMode: "AUTO",
        movementRequired: true,
        logisticsPlanningMode: "FIXED_DATE",
        logisticsScheduledDate: null,
        movementCatalogNodeId: "00000000-0000-4000-8000-000000000104",
        priority: 1,
        coverMediaId: null,
        taskPlans: [],
        lines: [catalogWork],
      })
    ).toThrow("Дата логистического задания")
  })
})
