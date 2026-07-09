import type { EstimateCatalogSettingsActionDto } from "@/features/settings/estimates-repairs/model/estimate-repair-settings"

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
