import type { EstimateCatalogSettingsActionDto } from "@/features/settings/estimates-repairs/model/estimate-repair-settings"

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
