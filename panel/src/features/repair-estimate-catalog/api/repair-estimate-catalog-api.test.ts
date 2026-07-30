import { describe, expect, it } from "vitest"

import {
  createRepairEstimateCatalogIndex,
  filterRepairEstimateCatalogNodesForUsage,
} from "@/features/repair-estimate-catalog/api/repair-estimate-catalog-api"
import type { RepairEstimateCatalogNodeDto } from "@/features/repair-estimate-catalog/model/repair-estimate-catalog"

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
