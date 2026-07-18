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

export async function getRepairEstimateWorkCatalog(
  request: RepairEstimateCatalogRequest
) {
  return getRepairEstimateCatalogSection(request, "works")
}

export async function saveRepairEstimateWorkCatalogItem(
  request: RepairEstimateCatalogRequest,
  input: RepairEstimateCatalogNodeMutation
) {
  return saveRepairEstimateCatalogNode(request, {
    ...input,
    nodeType: "WORK",
  })
}

export async function deleteRepairEstimateWorkCatalogItem(
  request: RepairEstimateCatalogRequest,
  id: string
) {
  return deleteRepairEstimateCatalogNode(request, id)
}
