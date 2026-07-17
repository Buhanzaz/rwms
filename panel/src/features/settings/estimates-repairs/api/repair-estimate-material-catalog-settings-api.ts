import type { EstimateCatalogSettingsActionDto } from "@/features/settings/estimates-repairs/model/estimate-repair-settings"
import {
  deleteRepairEstimateCatalogNode,
  getRepairEstimateCatalogSection,
  saveRepairEstimateCatalogNode,
} from "@/features/settings/estimates-repairs/api/repair-estimate-catalog-store"
import type { RepairEstimateCatalogNodeMutation } from "@/features/settings/estimates-repairs/model/repair-estimate-catalog"

const REPAIR_ESTIMATE_MATERIAL_CATALOG_SETTINGS: EstimateCatalogSettingsActionDto =
  {
    id: "repair-estimate-catalog-materials",
    title: "Материалы",
    legacyRoute: "/repair-estimate-catalog-materials",
    legacyViewId: "RepairEstimateCatalogMaterial.view",
    legacyClassName: "RepairEstimateMaterialCatalogView",
    sectionType: "MATERIAL",
    categoryScope: "NON_FURNITURE",
    order: 30,
  }

export async function getRepairEstimateMaterialCatalogSettings() {
  return REPAIR_ESTIMATE_MATERIAL_CATALOG_SETTINGS
}

export async function getRepairEstimateMaterialCatalogMock(
  warehouseId?: string
) {
  return getRepairEstimateCatalogSection("materials", warehouseId)
}

export async function saveRepairEstimateMaterialCatalogItem(
  input: RepairEstimateCatalogNodeMutation,
  warehouseId?: string
) {
  return saveRepairEstimateCatalogNode(
    {
      ...input,
      nodeType: "MATERIAL",
      furnitureCategory: false,
    },
    warehouseId
  )
}

export async function deleteRepairEstimateMaterialCatalogItem(
  id: string,
  warehouseId?: string
) {
  return deleteRepairEstimateCatalogNode(id, warehouseId)
}
