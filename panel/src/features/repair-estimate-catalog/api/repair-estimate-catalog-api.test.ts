import { describe, expect, it } from "vitest"

import {
  createRepairEstimateCatalogIndex,
  filterRepairEstimateCatalogNodesForUsage,
} from "@/features/repair-estimate-catalog/api/repair-estimate-catalog-api"
import type {
  RepairEstimateCatalogLinkDto,
  RepairEstimateCatalogNodeDto,
} from "@/features/repair-estimate-catalog/model/repair-estimate-catalog"

const versionId = "00000000-0000-4000-8000-000000000001"

function node(
  id: string,
  overrides: Partial<RepairEstimateCatalogNodeDto> = {}
): RepairEstimateCatalogNodeDto {
  return {
    id,
    catalogVersionId: versionId,
    name: `Узел ${id.at(-1)}`,
    nodeType: "CATEGORY",
    parentId: null,
    active: true,
    unit: null,
    unitPrice: null,
    durationMinutes: null,
    showInMainMenu: false,
    routeQueueKind: null,
    queueDefinitionId: null,
    queueDefinitionName: null,
    includeInEstimate: false,
    commonItem: false,
    furnitureCategory: false,
    furnitureEquipment: null,
    forcesCapitalRepair: false,
    characteristic: null,
    comment: null,
    ...overrides,
  }
}

function link(
  id: string,
  sourceNodeId: string,
  targetNodeId: string,
  linkType: RepairEstimateCatalogLinkDto["linkType"],
  sortOrder = 10
): RepairEstimateCatalogLinkDto {
  return {
    id,
    catalogVersionId: versionId,
    sourceNodeId,
    targetNodeId,
    linkType,
    sortOrder,
  }
}

describe("repair estimate furniture catalog usage", () => {
  it("allows only mapped furniture in estimates and excludes the whole furniture tree elsewhere", () => {
    const regularRoot = node("00000000-0000-4000-8000-000000000010", {
      name: "Общее",
    })
    const furnitureRoot = node("00000000-0000-4000-8000-000000000011", {
      name: "Мебель",
      furnitureCategory: true,
    })
    const furnitureGroup = node("00000000-0000-4000-8000-000000000012", {
      name: "Офисная мебель",
      nodeType: "SUBCATEGORY",
      parentId: furnitureRoot.id,
    })
    const mappedFurniture = node("00000000-0000-4000-8000-000000000013", {
      name: "Стул",
      nodeType: "MATERIAL",
      parentId: furnitureGroup.id,
      includeInEstimate: true,
      furnitureEquipment: {
        equipmentId: "00000000-0000-4000-8000-000000000101",
        equipmentName: "Стул",
      },
    })
    const unmappedFurniture = node("00000000-0000-4000-8000-000000000014", {
      name: "Стол",
      nodeType: "MATERIAL",
      parentId: furnitureGroup.id,
      includeInEstimate: true,
    })
    const furnitureWork = node("00000000-0000-4000-8000-000000000015", {
      name: "Сборка стула",
      nodeType: "WORK",
      parentId: furnitureGroup.id,
      includeInEstimate: true,
    })
    const regularMaterial = node("00000000-0000-4000-8000-000000000016", {
      name: "Краска",
      nodeType: "MATERIAL",
      parentId: regularRoot.id,
      includeInEstimate: true,
    })
    const nodes = [
      regularRoot,
      furnitureRoot,
      furnitureGroup,
      mappedFurniture,
      unmappedFurniture,
      furnitureWork,
      regularMaterial,
    ]
    const index = createRepairEstimateCatalogIndex({ nodes, links: [] })

    expect(
      filterRepairEstimateCatalogNodesForUsage(nodes, index, false).map(
        (item) => item.id
      )
    ).toEqual([
      regularRoot.id,
      furnitureRoot.id,
      furnitureGroup.id,
      mappedFurniture.id,
      furnitureWork.id,
      regularMaterial.id,
    ])
    expect(
      filterRepairEstimateCatalogNodesForUsage(nodes, index, true).map(
        (item) => item.id
      )
    ).toEqual([regularRoot.id, regularMaterial.id])
  })
})

describe("repair estimate catalog effective queue routing", () => {
  it("walks the complete parent and graph chain, including both graph link types", () => {
    const parent = node("00000000-0000-4000-8000-000000000201", {
      name: "Родитель",
    })
    const child = node("00000000-0000-4000-8000-000000000202", {
      name: "Ребёнок",
      parentId: parent.id,
    })
    const graphRoot = node("00000000-0000-4000-8000-000000000203", {
      name: "Маршрут",
      routeQueueKind: "REPAIR",
      queueDefinitionId: "00000000-0000-4000-8000-000000000299",
      queueDefinitionName: "Внешние работы",
    })
    const graphMiddle = node("00000000-0000-4000-8000-000000000204", {
      name: "Промежуточный блок",
    })
    const selected = node("00000000-0000-4000-8000-000000000205", {
      name: "Выбранный блок",
      parentId: child.id,
    })

    const catalog = createRepairEstimateCatalogIndex({
      nodes: [parent, child, graphRoot, graphMiddle, selected],
      links: [
        link(
          "00000000-0000-4000-8000-000000000301",
          graphRoot.id,
          graphMiddle.id,
          "FOLLOW_UP"
        ),
        link(
          "00000000-0000-4000-8000-000000000302",
          graphMiddle.id,
          selected.id,
          "DEPENDENCY"
        ),
      ],
    })

    expect(catalog.getEffectiveQueueBinding(selected.id)).toEqual({
      queueId: graphRoot.queueDefinitionId,
      queueName: graphRoot.queueDefinitionName,
      queueKind: graphRoot.routeQueueKind,
    })
  })

  it("fails closed when different incoming branches resolve to different queues", () => {
    const left = node("00000000-0000-4000-8000-000000000211", {
      name: "Левая ветка",
      routeQueueKind: "REPAIR",
      queueDefinitionId: "00000000-0000-4000-8000-000000000311",
      queueDefinitionName: "Внешние работы",
    })
    const right = node("00000000-0000-4000-8000-000000000212", {
      name: "Правая ветка",
      routeQueueKind: "REPAIR",
      queueDefinitionId: "00000000-0000-4000-8000-000000000312",
      queueDefinitionName: "Внутренние работы",
    })
    const selected = node("00000000-0000-4000-8000-000000000213", {
      name: "Конфликтный блок",
    })

    const catalog = createRepairEstimateCatalogIndex({
      nodes: [left, right, selected],
      links: [
        link(
          "00000000-0000-4000-8000-000000000321",
          left.id,
          selected.id,
          "FOLLOW_UP",
          20
        ),
        link(
          "00000000-0000-4000-8000-000000000322",
          right.id,
          selected.id,
          "DEPENDENCY",
          10
        ),
      ],
    })

    expect(catalog.getEffectiveQueueBinding(selected.id)).toBeNull()
    expect(catalog.getEffectiveRouteQueueKind(selected.id)).toBeNull()
  })

  it("does not recurse forever when graph links contain a cycle", () => {
    const first = node("00000000-0000-4000-8000-000000000221", {
      name: "Первый",
      routeQueueKind: "REPAIR",
      queueDefinitionId: "00000000-0000-4000-8000-000000000399",
      queueDefinitionName: "Сварка",
    })
    const second = node("00000000-0000-4000-8000-000000000222", {
      name: "Второй",
    })

    const catalog = createRepairEstimateCatalogIndex({
      nodes: [first, second],
      links: [
        link(
          "00000000-0000-4000-8000-000000000331",
          first.id,
          second.id,
          "FOLLOW_UP"
        ),
        link(
          "00000000-0000-4000-8000-000000000332",
          second.id,
          first.id,
          "DEPENDENCY"
        ),
      ],
    })

    expect(catalog.getEffectiveQueueBinding(second.id)).toEqual({
      queueId: first.queueDefinitionId,
      queueName: first.queueDefinitionName,
      queueKind: first.routeQueueKind,
    })
  })
})
