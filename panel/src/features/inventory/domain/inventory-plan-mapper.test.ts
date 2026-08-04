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

const manualWork: RepairEstimateLineDto = {
  ...manualMaterial,
  id: "manual-work-1",
  sourceLineKey: "manual-work-1",
  lineType: "WORK",
  description: "Подгонка дверцы",
  lineComment: "Проверить зазор",
  unitPrice: "900.00",
  lineTotal: "900.00",
}

describe("inventory plan mapper", () => {
  it("maps manual work and material to their task plan routing catalog node", () => {
    const result = buildInventoryPlanSelection({
      completionMode: "MANUAL",
      movementToRepair: false,
      logisticsPlanningMode: null,
      logisticsScheduledDate: null,
      priority: 2,
      coverMediaId: "00000000-0000-4000-8000-000000000102",
      lines: [catalogWork, manualWork, manualMaterial],
      taskPlans: [
        {
          id: "plan-1",
          kind: "REPAIR_WORK",
          includedLineIds: [
            catalogWork.id,
            manualWork.id,
            manualMaterial.id,
          ],
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
          routingCatalogNodeId: null,
          groupComment: "Плановый комментарий",
          mediaReferences: catalogWork.maintenanceMediaReferences,
        },
        {
          aggregationKind: "MANUAL",
          description: manualWork.description,
          routingCatalogNodeId: catalogWork.catalogSnapshot?.nodeId,
          groupComment: "Плановый комментарий",
        },
        {
          aggregationKind: "MANUAL",
          description: manualMaterial.description,
          routingCatalogNodeId: catalogWork.catalogSnapshot?.nodeId,
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

  it("uses explicit inbound and outbound movement flags without synthetic stages", () => {
    const result = buildInventoryPlanSelection({
      completionMode: "AUTO",
      movementToRepair: true,
      logisticsPlanningMode: "AUTO",
      logisticsScheduledDate: null,
      priority: 3,
      coverMediaId: null,
      taskPlans: [],
      lines: [catalogWork],
    })

    expect(result).toMatchObject({
      movementToRepair: true,
      logisticsPlanningMode: "AUTO",
      logisticsScheduledDate: null,
      lines: [{ routingCatalogNodeId: null }],
      stages: [],
    })
  })

  it("rejects manual lines in automatic catalog mode", () => {
    expect(() =>
      buildInventoryPlanSelection({
        completionMode: "AUTO",
        movementToRepair: false,
        logisticsPlanningMode: null,
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
      movementToRepair: false,
      logisticsPlanningMode: null,
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

  it("maps manual lines to their respective routes when a plan has multiple stages", () => {
    const secondCatalogWork: RepairEstimateLineDto = {
      ...catalogWork,
      id: "work-2",
      sourceLineKey: "work-2",
      catalogSnapshot: {
        ...catalogWork.catalogSnapshot!,
        nodeId: "00000000-0000-4000-8000-000000000104",
      },
    }
    const secondManualMaterial: RepairEstimateLineDto = {
      ...manualMaterial,
      id: "material-2",
      sourceLineKey: "material-2",
      description: "Новая ручка",
    }
    const result = buildInventoryPlanSelection({
      completionMode: "MANUAL",
      movementToRepair: false,
      logisticsPlanningMode: null,
      logisticsScheduledDate: null,
      priority: 2,
      coverMediaId: null,
      lines: [
        catalogWork,
        manualMaterial,
        secondCatalogWork,
        secondManualMaterial,
      ],
      taskPlans: [
        {
          id: "plan-1",
          kind: "REPAIR_WORK",
          includedLineIds: [catalogWork.id, manualMaterial.id],
          primaryLineId: catalogWork.id,
          groupComment: "Первый маршрут",
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
          includedLineIds: [secondCatalogWork.id, secondManualMaterial.id],
          primaryLineId: secondCatalogWork.id,
          groupComment: "Второй маршрут",
          queueId: "00000000-0000-4000-8000-000000000105",
          queueName: "Второй ремонт",
          routeQueueKind: "REPAIR",
          sortOrder: 20,
          generationStatus: "PENDING_GENERATION",
          workflowRequestRef: null,
        },
      ],
    })

    expect(result.lines).toEqual(
      expect.arrayContaining([
        expect.objectContaining({
          aggregationKind: "CATALOG",
          catalogNodeId: catalogWork.catalogSnapshot?.nodeId,
          routingCatalogNodeId: null,
        }),
        expect.objectContaining({
          aggregationKind: "MANUAL",
          description: manualMaterial.description,
          routingCatalogNodeId: catalogWork.catalogSnapshot?.nodeId,
        }),
        expect.objectContaining({
          aggregationKind: "CATALOG",
          catalogNodeId: secondCatalogWork.catalogSnapshot?.nodeId,
          routingCatalogNodeId: null,
        }),
        expect.objectContaining({
          aggregationKind: "MANUAL",
          description: secondManualMaterial.description,
          routingCatalogNodeId: secondCatalogWork.catalogSnapshot?.nodeId,
        }),
      ])
    )
    expect(result.stages).toEqual([
      {
        catalogNodeId: catalogWork.catalogSnapshot?.nodeId,
        kind: "REPAIR_WORK",
        order: 0,
      },
      {
        catalogNodeId: secondCatalogWork.catalogSnapshot?.nodeId,
        kind: "REPAIR_WORK",
        order: 1,
      },
    ])
  })

  it("rejects a manual line that is missing a repair work plan", () => {
    expect(() =>
      buildInventoryPlanSelection({
        completionMode: "MANUAL",
        movementToRepair: false,
        logisticsPlanningMode: null,
        logisticsScheduledDate: null,
        priority: 2,
        coverMediaId: null,
        lines: [catalogWork, manualMaterial],
        taskPlans: [
          {
            id: "plan-1",
            kind: "REPAIR_WORK",
            includedLineIds: [catalogWork.id],
            primaryLineId: catalogWork.id,
            groupComment: "Маршрут",
            queueId: "00000000-0000-4000-8000-000000000103",
            queueName: "Ремонт",
            routeQueueKind: "REPAIR",
            sortOrder: 10,
            generationStatus: "PENDING_GENERATION",
            workflowRequestRef: null,
          },
        ],
      })
    ).toThrow("Для ручной позиции выберите план ремонтных работ")
  })

  it("rejects a line included in more than one repair work plan", () => {
    const secondCatalogWork: RepairEstimateLineDto = {
      ...catalogWork,
      id: "work-2",
      sourceLineKey: "work-2",
      catalogSnapshot: {
        ...catalogWork.catalogSnapshot!,
        nodeId: "00000000-0000-4000-8000-000000000104",
      },
    }
    expect(() =>
      buildInventoryPlanSelection({
        completionMode: "MANUAL",
        movementToRepair: false,
        logisticsPlanningMode: null,
        logisticsScheduledDate: null,
        priority: 2,
        coverMediaId: null,
        lines: [catalogWork, secondCatalogWork, manualMaterial],
        taskPlans: [
          {
            id: "plan-1",
            kind: "REPAIR_WORK",
            includedLineIds: [catalogWork.id, manualMaterial.id],
            primaryLineId: catalogWork.id,
            groupComment: "Одинаковый комментарий",
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
            includedLineIds: [secondCatalogWork.id, manualMaterial.id],
            primaryLineId: secondCatalogWork.id,
            groupComment: "Одинаковый комментарий",
            queueId: "00000000-0000-4000-8000-000000000105",
            queueName: "Второй ремонт",
            routeQueueKind: "REPAIR",
            sortOrder: 20,
            generationStatus: "PENDING_GENERATION",
            workflowRequestRef: null,
          },
        ],
      })
    ).toThrow("Строка не может входить в несколько планов работ")
  })

  it("rejects a manual plan whose primary line is not catalog work", () => {
    expect(() =>
      buildInventoryPlanSelection({
        completionMode: "MANUAL",
        movementToRepair: false,
        logisticsPlanningMode: null,
        logisticsScheduledDate: null,
        priority: 2,
        coverMediaId: null,
        lines: [manualWork, manualMaterial],
        taskPlans: [
          {
            id: "plan-1",
            kind: "REPAIR_WORK",
            includedLineIds: [manualWork.id, manualMaterial.id],
            primaryLineId: manualWork.id,
            groupComment: "Маршрут",
            queueId: "00000000-0000-4000-8000-000000000103",
            queueName: "Ремонт",
            routeQueueKind: "REPAIR",
            sortOrder: 10,
            generationStatus: "PENDING_GENERATION",
            workflowRequestRef: null,
          },
        ],
      })
    ).toThrow("Для ручного этапа выберите основной вид работ из каталога")
  })

  it("rejects a manual plan without a catalog primary line", () => {
    expect(() =>
      buildInventoryPlanSelection({
        completionMode: "MANUAL",
        movementToRepair: false,
        logisticsPlanningMode: null,
        logisticsScheduledDate: null,
        priority: 2,
        coverMediaId: null,
        lines: [catalogWork, manualMaterial],
        taskPlans: [
          {
            id: "plan-1",
            kind: "REPAIR_WORK",
            includedLineIds: [catalogWork.id, manualMaterial.id],
            primaryLineId: null,
            groupComment: "Маршрут",
            queueId: "00000000-0000-4000-8000-000000000103",
            queueName: "Ремонт",
            routeQueueKind: "REPAIR",
            sortOrder: 10,
            generationStatus: "PENDING_GENERATION",
            workflowRequestRef: null,
          },
        ],
      })
    ).toThrow("Для ручного этапа выберите основной вид работ из каталога")
  })

  it("preserves a fixed logistics date and rejects inconsistent planning", () => {
    const fixed = buildInventoryPlanSelection({
      completionMode: "AUTO",
      movementToRepair: true,
      logisticsPlanningMode: "FIXED_DATE",
      logisticsScheduledDate: "2026-08-12",
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
        movementToRepair: true,
        logisticsPlanningMode: "FIXED_DATE",
        logisticsScheduledDate: null,
        priority: 1,
        coverMediaId: null,
        taskPlans: [],
        lines: [catalogWork],
      })
    ).toThrow("Дата логистического задания")
  })
})
