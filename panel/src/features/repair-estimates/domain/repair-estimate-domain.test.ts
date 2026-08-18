import { describe, expect, it } from "vitest"

import { createRepairEstimateCatalogIndex } from "@/features/repair-estimate-catalog/api/repair-estimate-catalog-api"
import type { RepairEstimateCatalogNodeDto } from "@/features/repair-estimate-catalog/model/repair-estimate-catalog"
import {
  applyCatalogNodesToEstimateLines,
  applyEstimateRentalItemSelection,
  assertEstimateLinesValid,
  assertTaskPlansValid,
  buildRepairEstimateTaskPlans,
  createManualEstimateLine,
  createNewEstimateDraft,
  getRepairEstimateCatalogQuantityError,
  normalizeEstimateLine,
  validateAutoCompletion,
} from "@/features/repair-estimates/domain/repair-estimate-domain"
import type { RepairEstimateLineDto } from "@/features/repair-estimates/model/repair-estimate"

const furniture: RepairEstimateCatalogNodeDto = {
  id: "00000000-0000-4000-8000-000000000001",
  catalogVersionId: "00000000-0000-4000-8000-000000000002",
  name: "Стул",
  nodeType: "MATERIAL",
  parentId: "00000000-0000-4000-8000-000000000003",
  active: true,
  unit: "шт.",
  unitPrice: "100.00",
  durationMinutes: null,
  showInMainMenu: false,
  routeQueueKind: null,
  queueDefinitionId: null,
  queueDefinitionName: null,
  includeInEstimate: true,
  commonItem: false,
  furnitureCategory: false,
  furnitureEquipment: {
    equipmentId: "00000000-0000-4000-8000-000000000004",
    equipmentName: "Стул",
  },
  forcesCapitalRepair: false,
  characteristic: null,
  comment: null,
}

function catalogNode(
  id: string,
  overrides: Partial<RepairEstimateCatalogNodeDto> = {}
): RepairEstimateCatalogNodeDto {
  return {
    ...furniture,
    id,
    name: `Узел ${id.at(-1)}`,
    parentId: null,
    unit: "ед",
    furnitureEquipment: null,
    ...overrides,
  }
}

function estimateLine(
  id: string,
  node: RepairEstimateCatalogNodeDto
): RepairEstimateLineDto {
  return {
    id,
    sourceLineKey: id,
    lineType: node.nodeType === "WORK" ? "WORK" : "MATERIAL",
    description: node.name,
    lineComment: "",
    unit: node.unit ?? "ед",
    quantity: 1,
    normativeMinutes: node.durationMinutes ?? 0,
    unitPrice: node.unitPrice ?? "0.00",
    lineTotal: node.unitPrice ?? "0.00",
    catalogSnapshot: {
      nodeId: node.id,
      name: node.name,
      nodeType: node.nodeType === "WORK" ? "WORK" : "MATERIAL",
      furnitureEquipment: node.furnitureEquipment,
      characteristic: node.characteristic,
    },
  }
}

describe("furniture estimate quantities", () => {
  it("requires a positive integer only for furniture-linked materials", () => {
    expect(getRepairEstimateCatalogQuantityError(furniture, 2)).toBeNull()
    expect(getRepairEstimateCatalogQuantityError(furniture, 1.5)).toContain(
      "целым положительным"
    )
    expect(getRepairEstimateCatalogQuantityError(furniture, 0)).toContain(
      "целым положительным"
    )
    expect(
      getRepairEstimateCatalogQuantityError(
        { ...furniture, furnitureEquipment: null },
        1.5
      )
    ).toBeNull()
  })

  it("keeps the cabin characteristic link in catalog material snapshots", () => {
    const characteristic = {
      characteristicId: "00000000-0000-4000-8000-000000000050",
      characteristicName: "Металлическая дверь",
    }
    const material = catalogNode("00000000-0000-4000-8000-000000000051", {
      name: "Дверное полотно",
      nodeType: "MATERIAL",
      characteristic,
    })

    const [line] = applyCatalogNodesToEstimateLines({
      lines: [],
      nodes: [material],
      quantity: 1,
      comment: "",
    })

    expect(line.catalogSnapshot?.characteristic).toEqual(characteristic)
  })
})

describe("catalog estimate additions", () => {
  it("creates a separate duplicate work unless an existing work was selected", () => {
    const work = catalogNode("00000000-0000-4000-8000-000000000060", {
      name: "Покраска двери",
      nodeType: "WORK",
      durationMinutes: 45,
    })
    const existing = {
      ...estimateLine("00000000-0000-4000-8000-000000000061", work),
      lineComment: "Сохранить этот комментарий",
    }

    const separate = applyCatalogNodesToEstimateLines({
      lines: [existing],
      nodes: [work],
      quantity: 2,
      comment: "Новый комментарий",
    })
    expect(separate).toHaveLength(2)
    expect(separate[0]).toMatchObject({
      id: existing.id,
      quantity: 1,
      lineComment: "Сохранить этот комментарий",
    })
    expect(separate[1]).toMatchObject({
      lineType: "WORK",
      quantity: 2,
      lineComment: "Новый комментарий",
    })

    const merged = applyCatalogNodesToEstimateLines({
      lines: [existing],
      nodes: [work],
      quantity: 2,
      comment: "Не заменять существующий комментарий",
      targetWorkLineIdsByCatalogNodeId: { [work.id]: existing.id },
    })
    expect(merged).toEqual([
      expect.objectContaining({
        id: existing.id,
        quantity: 3,
        lineComment:
          "Сохранить этот комментарий; Не заменять существующий комментарий",
      }),
    ])
  })

  it("aggregates catalog materials by node without copying a work comment", () => {
    const material = catalogNode("00000000-0000-4000-8000-000000000062", {
      name: "Краска",
      nodeType: "MATERIAL",
    })
    const existing = {
      ...estimateLine("00000000-0000-4000-8000-000000000063", material),
      description: "Старое описание материала",
      lineComment: "Комментарий к материалу",
    }

    const aggregated = applyCatalogNodesToEstimateLines({
      lines: [existing],
      nodes: [material],
      quantity: 2,
      comment: "Комментарий к работе",
    })
    expect(aggregated).toEqual([
      expect.objectContaining({
        id: existing.id,
        quantity: 3,
        lineComment: "",
      }),
    ])

    const [newMaterial] = applyCatalogNodesToEstimateLines({
      lines: [],
      nodes: [material],
      quantity: 1,
      comment: "Комментарий к работе",
    })
    expect(newMaterial.lineComment).toBe("")
  })

  it("keeps photos on one work and strips legacy material fields", () => {
    const work = catalogNode("00000000-0000-4000-8000-000000000064", {
      name: "Замена двери",
      nodeType: "WORK",
      durationMinutes: 30,
    })
    const reference = {
      mediaId: "00000000-0000-4000-8000-000000000065",
      generation: 2,
    }
    const [workLine] = applyCatalogNodesToEstimateLines({
      lines: [],
      nodes: [work],
      quantity: 1,
      comment: "Снять старую дверь",
      workMediaReferencesByCatalogNodeId: { [work.id]: [reference] },
    })
    expect(workLine.maintenanceMediaReferences).toEqual([reference])

    const material = normalizeEstimateLine({
      ...estimateLine("00000000-0000-4000-8000-000000000066", furniture),
      lineComment: "legacy",
      maintenanceMediaReferences: [reference],
    })
    expect(material.lineComment).toBe("")
    expect(material.maintenanceMediaReferences).toEqual([])

    expect(() =>
      assertEstimateLinesValid([
        workLine,
        {
          ...workLine,
          id: "00000000-0000-4000-8000-000000000067",
          sourceLineKey: "second-work",
        },
      ])
    ).toThrow("Одна фотография не может принадлежать двум работам")
  })
})

describe("estimate return selection", () => {
  it("starts with the optional capital-repair choice disabled", () => {
    expect(createNewEstimateDraft().forceCapitalRepair).toBe(false)
  })

  it("replaces the estimate source fields with return metadata", () => {
    const draft = {
      ...createNewEstimateDraft(),
      rentalItemId: "previous-item",
      sourceParty: "Предыдущий контрагент",
      dispatchDate: "2026-07-17",
    }

    expect(
      applyEstimateRentalItemSelection(draft, {
        id: "after-rent-item",
        counterparty: "ООО Арендатор",
        arrivalDate: "2026-07-18",
      })
    ).toMatchObject({
      rentalItemId: "after-rent-item",
      sourceParty: "ООО Арендатор",
      dispatchDate: "2026-07-18",
    })
  })
})

describe("repair estimate task plan routing", () => {
  it("routes custom work through its selected queue and keeps its comment with the worker task", () => {
    const line: RepairEstimateLineDto = {
      id: "00000000-0000-4000-8000-000000000100",
      sourceLineKey: "custom-work",
      lineType: "WORK",
      description: "Подтянуть крепления",
      lineComment: "Нужна стремянка",
      unit: "ед",
      quantity: 1,
      normativeMinutes: 45,
      unitPrice: "0.00",
      lineTotal: "0.00",
      catalogSnapshot: null,
      customQueueBinding: {
        queueId: "00000000-0000-4000-8000-000000000099",
        queueName: "Кузовной ремонт",
        queueKind: "REPAIR",
      },
    }
    const catalog = createRepairEstimateCatalogIndex({ nodes: [], links: [] })

    expect(validateAutoCompletion([line], catalog)).toEqual([])
    expect(buildRepairEstimateTaskPlans([line], catalog)).toEqual([
      expect.objectContaining({
        includedLineIds: [line.id],
        primaryLineId: line.id,
        queueId: line.customQueueBinding?.queueId,
        queueName: "Кузовной ремонт",
        routeQueueKind: "REPAIR",
        groupComment: "Нужна стремянка",
      }),
    ])
  })

  it("requires a queue binding for custom work while retaining catalog validation", () => {
    const customLine: RepairEstimateLineDto = {
      id: "00000000-0000-4000-8000-000000000105",
      sourceLineKey: "custom-work-unbound",
      lineType: "WORK",
      description: "Заменить уплотнитель",
      lineComment: "",
      unit: "ед",
      quantity: 1,
      normativeMinutes: 45,
      unitPrice: "0.00",
      lineTotal: "0.00",
      catalogSnapshot: null,
      customQueueBinding: null,
    }
    const catalog = createRepairEstimateCatalogIndex({ nodes: [], links: [] })

    expect(validateAutoCompletion([customLine], catalog)).toEqual([
      "Строка 1: не задан маршрут очереди",
    ])
  })

  it("requires a positive whole duration for a custom work line", () => {
    const line = createManualEstimateLine()

    expect(line.normativeMinutes).toBe(0)
    expect(() => assertEstimateLinesValid([line])).toThrow(
      "укажите время выполнения больше 0 минут"
    )

    expect(() =>
      assertEstimateLinesValid([{ ...line, normativeMinutes: 45 }])
    ).not.toThrow()
  })

  it("creates a valid material-only stage with no primary work", () => {
    const category = catalogNode("00000000-0000-4000-8000-000000000011", {
      name: "Внешняя отделка",
      nodeType: "CATEGORY",
      includeInEstimate: false,
      unit: null,
      unitPrice: null,
      routeQueueKind: "REPAIR",
      queueDefinitionId: "00000000-0000-4000-8000-000000000020",
      queueDefinitionName: "Внешние работы",
    })
    const material = catalogNode("00000000-0000-4000-8000-000000000012", {
      name: "Мастика",
      parentId: category.id,
    })
    const line = estimateLine("00000000-0000-4000-8000-000000000102", material)
    const catalog = createRepairEstimateCatalogIndex({
      nodes: [category, material],
      links: [],
    })

    expect(validateAutoCompletion([line], catalog)).toEqual([])
    const plans = buildRepairEstimateTaskPlans([line], catalog)
    expect(plans).toEqual([
      expect.objectContaining({
        includedLineIds: [line.id],
        primaryLineId: null,
        queueId: category.queueDefinitionId,
        queueName: category.queueDefinitionName,
        routeQueueKind: "REPAIR",
      }),
    ])
    expect(() => assertTaskPlansValid(plans, [line])).not.toThrow()
  })

  it("attaches a custom material to the selected stage that contains custom work", () => {
    const queueBinding = {
      queueId: "00000000-0000-4000-8000-000000000020",
      queueName: "Внешние работы",
      queueKind: "REPAIR" as const,
    }
    const work: RepairEstimateLineDto = {
      id: "00000000-0000-4000-8000-000000000101",
      sourceLineKey: "custom-work",
      lineType: "WORK",
      description: "Заменить панель",
      lineComment: "",
      unit: "ед",
      quantity: 1,
      normativeMinutes: 30,
      unitPrice: "0.00",
      lineTotal: "0.00",
      catalogSnapshot: null,
      customQueueBinding: queueBinding,
    }
    const material: RepairEstimateLineDto = {
      id: "00000000-0000-4000-8000-000000000102",
      sourceLineKey: "custom-material",
      lineType: "MATERIAL",
      description: "Крепёж",
      lineComment: "",
      unit: "упак.",
      quantity: 2,
      normativeMinutes: 0,
      unitPrice: "100.00",
      lineTotal: "200.00",
      catalogSnapshot: null,
      customQueueBinding: queueBinding,
    }
    const catalog = createRepairEstimateCatalogIndex({ nodes: [], links: [] })

    expect(validateAutoCompletion([work, material], catalog)).toEqual([])
    expect(buildRepairEstimateTaskPlans([work, material], catalog)).toEqual([
      expect.objectContaining({
        includedLineIds: [work.id, material.id],
        primaryLineId: work.id,
        queueId: queueBinding.queueId,
        queueName: queueBinding.queueName,
        routeQueueKind: "REPAIR",
      }),
    ])
  })

  it("keeps a material after a work in the work queue group", () => {
    const work = catalogNode("00000000-0000-4000-8000-000000000013", {
      name: "Работа",
      nodeType: "WORK",
      routeQueueKind: "REPAIR",
      queueDefinitionId: "00000000-0000-4000-8000-000000000021",
      queueDefinitionName: "Внутренние работы",
    })
    const materialCategory = catalogNode(
      "00000000-0000-4000-8000-000000000014",
      {
        name: "Внешняя отделка",
        nodeType: "CATEGORY",
        includeInEstimate: false,
        unit: null,
        unitPrice: null,
        routeQueueKind: "REPAIR",
        queueDefinitionId: "00000000-0000-4000-8000-000000000022",
        queueDefinitionName: "Внешние работы",
      }
    )
    const material = catalogNode("00000000-0000-4000-8000-000000000015", {
      name: "Мастика",
      parentId: materialCategory.id,
    })
    const workLine = estimateLine("00000000-0000-4000-8000-000000000103", work)
    const materialLine = estimateLine(
      "00000000-0000-4000-8000-000000000104",
      material
    )
    const catalog = createRepairEstimateCatalogIndex({
      nodes: [work, materialCategory, material],
      links: [],
    })

    expect(
      buildRepairEstimateTaskPlans([workLine, materialLine], catalog)
    ).toEqual([
      expect.objectContaining({
        includedLineIds: [workLine.id, materialLine.id],
        primaryLineId: workLine.id,
        queueId: work.queueDefinitionId,
        queueName: work.queueDefinitionName,
        routeQueueKind: "REPAIR",
      }),
    ])
  })

  it("keeps every work in its own task-plan group even in the same queue", () => {
    const work = catalogNode("00000000-0000-4000-8000-000000000030", {
      name: "Окраска",
      nodeType: "WORK",
      routeQueueKind: "REPAIR",
      queueDefinitionId: "00000000-0000-4000-8000-000000000031",
      queueDefinitionName: "Малярные работы",
    })
    const first = {
      ...estimateLine("00000000-0000-4000-8000-000000000032", work),
      lineComment: "Сначала подготовить",
    }
    const second = {
      ...estimateLine("00000000-0000-4000-8000-000000000033", work),
      lineComment: "Повторно проверить",
    }
    const catalog = createRepairEstimateCatalogIndex({
      nodes: [work],
      links: [],
    })

    const plans = buildRepairEstimateTaskPlans([first, second], catalog)

    expect(plans).toHaveLength(2)
    expect(plans).toEqual([
      expect.objectContaining({
        includedLineIds: [first.id],
        primaryLineId: first.id,
        groupComment: first.lineComment,
        queueId: work.queueDefinitionId,
      }),
      expect.objectContaining({
        includedLineIds: [second.id],
        primaryLineId: second.id,
        groupComment: second.lineComment,
        queueId: work.queueDefinitionId,
      }),
    ])
  })
})
