import type { EstimateCatalogSettingsActionDto } from "@/features/settings/estimates-repairs/model/estimate-repair-settings"

const REPAIR_ESTIMATE_CATALOG_CANVAS_SETTINGS: EstimateCatalogSettingsActionDto =
  {
    id: "repair-estimate-catalog-canvas",
    title: "Конструктор каталога смет",
    legacyRoute: "/repair-estimate-catalog-canvas",
    legacyViewId: "RepairEstimateCatalogCanvas.view",
    legacyClassName: "RepairEstimateCatalogCanvasView",
    sectionType: "CANVAS",
    categoryScope: "ALL",
    order: 10,
  }

export async function getRepairEstimateCatalogCanvasSettings() {
  return REPAIR_ESTIMATE_CATALOG_CANVAS_SETTINGS
}
