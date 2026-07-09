import type { EstimateCatalogSettingsActionDto } from "@/features/settings/estimates-repairs/model/estimate-repair-settings"

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
