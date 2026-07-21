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
    mediaOwnerId: id,
    code: `NODE_${id.at(-1)}`,
    name: `Узел ${id.at(-1)}`,
    nodeType: "CATEGORY",
    parentId: null,
    parentCode: null,
    active: true,
    unit: null,
    unitPrice: null,
    durationMinutes: null,
    showInMainMenu: false,
    routeQueueKind: null,
    workQueueId: null,
    workQueueCode: null,
    photoRequired: false,
    includeInEstimate: false,
    commonItem: false,
    furnitureCategory: false,
    furnitureEquipment: null,
    comment: null,
    ...overrides,
  }
}

describe("repair estimate furniture catalog usage", () => {
  it("allows only mapped furniture in estimates and excludes the whole furniture tree elsewhere", () => {
    const regularRoot = node("00000000-0000-4000-8000-000000000010", {
      code: "GENERAL",
    })
    const furnitureRoot = node("00000000-0000-4000-8000-000000000011", {
      code: "FURNITURE",
      furnitureCategory: true,
    })
    const furnitureGroup = node("00000000-0000-4000-8000-000000000012", {
      code: "OFFICE_FURNITURE",
      nodeType: "SUBCATEGORY",
      parentId: furnitureRoot.id,
      parentCode: furnitureRoot.code,
    })
    const mappedFurniture = node("00000000-0000-4000-8000-000000000013", {
      code: "CHAIR",
      nodeType: "MATERIAL",
      parentId: furnitureGroup.id,
      parentCode: furnitureGroup.code,
      includeInEstimate: true,
      furnitureEquipment: {
        equipmentId: "00000000-0000-4000-8000-000000000101",
        equipmentCode: "CHAIR",
        equipmentName: "Стул",
      },
    })
    const unmappedFurniture = node("00000000-0000-4000-8000-000000000014", {
      code: "TABLE",
      nodeType: "MATERIAL",
      parentId: furnitureGroup.id,
      parentCode: furnitureGroup.code,
      includeInEstimate: true,
    })
    const furnitureWork = node("00000000-0000-4000-8000-000000000015", {
      code: "ASSEMBLE_CHAIR",
      nodeType: "WORK",
      parentId: furnitureGroup.id,
      parentCode: furnitureGroup.code,
      includeInEstimate: true,
    })
    const regularMaterial = node("00000000-0000-4000-8000-000000000016", {
      code: "PAINT",
      nodeType: "MATERIAL",
      parentId: regularRoot.id,
      parentCode: regularRoot.code,
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
