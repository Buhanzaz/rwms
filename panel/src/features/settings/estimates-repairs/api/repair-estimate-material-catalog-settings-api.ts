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

const REPAIR_ESTIMATE_MATERIAL_CATALOG_SETTINGS: EstimateCatalogSettingsActionDto =
  {
    id: "repair-estimate-catalog-materials",
    title: "Материалы",
    sectionType: "MATERIAL",
    categoryScope: "NON_FURNITURE",
    order: 30,
  }

export async function getRepairEstimateMaterialCatalogSettings() {
  return REPAIR_ESTIMATE_MATERIAL_CATALOG_SETTINGS
}

export async function getRepairEstimateMaterialCatalog(
  request: RepairEstimateCatalogRequest
) {
  return getRepairEstimateCatalogSection(request, "materials")
}

export async function saveRepairEstimateMaterialCatalogItem(
  request: RepairEstimateCatalogRequest,
  input: RepairEstimateCatalogNodeMutation
) {
  return saveRepairEstimateCatalogNode(request, {
    ...input,
    nodeType: "MATERIAL",
  })
}

export async function deleteRepairEstimateMaterialCatalogItem(
  request: RepairEstimateCatalogRequest,
  id: string
) {
  return deleteRepairEstimateCatalogNode(request, id)
}
