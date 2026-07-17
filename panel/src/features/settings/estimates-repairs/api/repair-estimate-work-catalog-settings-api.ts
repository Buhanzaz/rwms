import type { EstimateCatalogSettingsActionDto } from "@/features/settings/estimates-repairs/model/estimate-repair-settings"
import {
  deleteRepairEstimateCatalogNode,
  getRepairEstimateCatalogSection,
  saveRepairEstimateCatalogNode,
} from "@/features/settings/estimates-repairs/api/repair-estimate-catalog-store"
import type { RepairEstimateCatalogNodeMutation } from "@/features/settings/estimates-repairs/model/repair-estimate-catalog"

const REPAIR_ESTIMATE_WORK_CATALOG_SETTINGS: EstimateCatalogSettingsActionDto =
  {
    id: "repair-estimate-catalog-works",
    title: "Работы",
    legacyRoute: "/repair-estimate-catalog-works",
    legacyViewId: "RepairEstimateCatalogWork.view",
    legacyClassName: "RepairEstimateWorkCatalogView",
    sectionType: "WORK",
    categoryScope: "NON_FURNITURE",
    order: 20,
  }

export async function getRepairEstimateWorkCatalogSettings() {
  return REPAIR_ESTIMATE_WORK_CATALOG_SETTINGS
}

export async function getRepairEstimateWorkCatalogMock(warehouseId?: string) {
  return getRepairEstimateCatalogSection("works", warehouseId)
}

export async function saveRepairEstimateWorkCatalogItem(
  input: RepairEstimateCatalogNodeMutation,
  warehouseId?: string
) {
  return saveRepairEstimateCatalogNode(
    {
      ...input,
      nodeType: "WORK",
      furnitureCategory: false,
    },
    warehouseId
  )
}

export async function deleteRepairEstimateWorkCatalogItem(
  id: string,
  warehouseId?: string
) {
  return deleteRepairEstimateCatalogNode(id, warehouseId)
}
