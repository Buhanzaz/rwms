import type { EstimateCatalogSettingsActionDto } from "@/features/settings/estimates-repairs/model/estimate-repair-settings"
import {
  deleteRepairEstimateCatalogNode,
  getRepairEstimateCatalogSection,
  saveRepairEstimateCatalogNode,
} from "@/features/settings/estimates-repairs/api/repair-estimate-catalog-store"
import type {
  RepairEstimateCatalogNodeMutation,
  RepairEstimateCatalogRequest,
} from "@/features/settings/estimates-repairs/model/repair-estimate-catalog"

const REPAIR_ESTIMATE_FURNITURE_CATALOG_SETTINGS: EstimateCatalogSettingsActionDto =
  {
    id: "repair-estimate-catalog-furniture",
    title: "Мебель",
    legacyRoute: "/repair-estimate-catalog-furniture",
    legacyViewId: "RepairEstimateCatalogFurniture.view",
    legacyClassName: "RepairEstimateFurnitureCatalogView",
    sectionType: "MATERIAL",
    categoryScope: "FURNITURE_ONLY",
    order: 40,
  }

export async function getRepairEstimateFurnitureCatalogSettings() {
  return REPAIR_ESTIMATE_FURNITURE_CATALOG_SETTINGS
}

export async function getRepairEstimateFurnitureCatalog(
  request: RepairEstimateCatalogRequest
) {
  return getRepairEstimateCatalogSection(request, "furniture")
}

export async function saveRepairEstimateFurnitureCatalogItem(
  request: RepairEstimateCatalogRequest,
  input: RepairEstimateCatalogNodeMutation
) {
  return saveRepairEstimateCatalogNode(request, {
    ...input,
    nodeType: "MATERIAL",
  })
}

export async function deleteRepairEstimateFurnitureCatalogItem(
  request: RepairEstimateCatalogRequest,
  id: string
) {
  return deleteRepairEstimateCatalogNode(request, id)
}
