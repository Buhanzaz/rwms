import { describe, expect, it } from "vitest"

import { createRepairEstimateCatalogIndex } from "@/features/repair-estimate-catalog/api/repair-estimate-catalog-api"
import type { RepairEstimateCatalogNodeDto } from "@/features/repair-estimate-catalog/model/repair-estimate-catalog"
import {
  buildRepairEstimateTaskPlans,
  getRepairEstimateCatalogQuantityError,
} from "@/features/repair-estimates/domain/repair-estimate-domain"
import type { RepairEstimateLineDto } from "@/features/repair-estimates/model/repair-estimate"

const furniture: RepairEstimateCatalogNodeDto = {
  id: "00000000-0000-4000-8000-000000000001",
  catalogVersionId: "00000000-0000-4000-8000-000000000002",
  mediaOwnerId: "00000000-0000-4000-8000-000000000005",
  code: "CHAIR",
  name: "Стул",
  nodeType: "MATERIAL",
  parentId: "00000000-0000-4000-8000-000000000003",
  parentCode: "FURNITURE",
  active: true,
  unit: "шт.",
  unitPrice: "100.00",
  durationMinutes: null,
  showInMainMenu: false,
  routeQueueKind: null,
  workQueueId: null,
  workQueueCode: null,
  photoRequired: false,
  includeInEstimate: true,
  commonItem: false,
  furnitureCategory: false,
  furnitureEquipment: {
    equipmentId: "00000000-0000-4000-8000-000000000004",
    equipmentCode: "CHAIR",
    equipmentName: "Стул",
  },
  comment: null,
}

function catalogNode(
  id: string,
  overrides: Partial<RepairEstimateCatalogNodeDto> = {}
): RepairEstimateCatalogNodeDto {
  return {
    ...furniture,
    id,
    mediaOwnerId: id,
    code: `NODE_${id.at(-1)}`,
    name: `Узел ${id.at(-1)}`,
    parentId: null,
    parentCode: null,
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
    unitPrice: node.unitPrice ?? "0.00",
    lineTotal: node.unitPrice ?? "0.00",
    catalogSnapshot: {
      nodeId: node.id,
      code: node.code,
      name: node.name,
      nodeType: node.nodeType === "WORK" ? "WORK" : "MATERIAL",
      furnitureEquipment: node.furnitureEquipment,
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
})

describe("repair estimate task plan routing", () => {
  it("uses an inherited queue binding for a material-only estimate", () => {
    const category = catalogNode("00000000-0000-4000-8000-000000000010", {
      code: "EXTERIOR",
      name: "Внешняя отделка",
      nodeType: "CATEGORY",
      includeInEstimate: false,
      unit: null,
      unitPrice: null,
      routeQueueKind: "REPAIR",
      workQueueId: "00000000-0000-4000-8000-000000000020",
      workQueueCode: "EXTERNAL_WORKS",
    })
    const material = catalogNode("00000000-0000-4000-8000-000000000011", {
      code: "MASTIC",
      name: "Мастика",
      parentId: category.id,
      parentCode: category.code,
    })
    const line = estimateLine("00000000-0000-4000-8000-000000000101", material)
    const catalog = createRepairEstimateCatalogIndex({
      nodes: [category, material],
      links: [],
    })

    expect(buildRepairEstimateTaskPlans([line], catalog)).toEqual([
      expect.objectContaining({
        kind: "REPAIR_WORK",
        includedLineIds: [line.id],
        primaryLineId: null,
        queueId: category.workQueueId,
        queueCode: category.workQueueCode,
        routeQueueKind: "REPAIR",
      }),
    ])
  })

  it("keeps a genuinely unbound standalone material fail-closed", () => {
    const material = catalogNode("00000000-0000-4000-8000-000000000012", {
      code: "UNROUTED_MATERIAL",
      name: "Материал без маршрута",
    })
    const line = estimateLine("00000000-0000-4000-8000-000000000102", material)
    const catalog = createRepairEstimateCatalogIndex({
      nodes: [material],
      links: [],
    })

    expect(buildRepairEstimateTaskPlans([line], catalog)).toEqual([
      expect.objectContaining({
        includedLineIds: [line.id],
        primaryLineId: null,
        queueId: null,
        queueCode: null,
        routeQueueKind: null,
      }),
    ])
  })

  it("keeps a material after a work in the work queue group", () => {
    const work = catalogNode("00000000-0000-4000-8000-000000000013", {
      code: "REPAIR_WORK",
      name: "Работа",
      nodeType: "WORK",
      routeQueueKind: "REPAIR",
      workQueueId: "00000000-0000-4000-8000-000000000021",
      workQueueCode: "INTERNAL_WORKS",
    })
    const materialCategory = catalogNode(
      "00000000-0000-4000-8000-000000000014",
      {
        code: "EXTERIOR",
        name: "Внешняя отделка",
        nodeType: "CATEGORY",
        includeInEstimate: false,
        unit: null,
        unitPrice: null,
        routeQueueKind: "REPAIR",
        workQueueId: "00000000-0000-4000-8000-000000000022",
        workQueueCode: "EXTERNAL_WORKS",
      }
    )
    const material = catalogNode("00000000-0000-4000-8000-000000000015", {
      code: "MASTIC",
      name: "Мастика",
      parentId: materialCategory.id,
      parentCode: materialCategory.code,
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
        queueId: work.workQueueId,
        queueCode: work.workQueueCode,
        routeQueueKind: "REPAIR",
      }),
    ])
  })
})
