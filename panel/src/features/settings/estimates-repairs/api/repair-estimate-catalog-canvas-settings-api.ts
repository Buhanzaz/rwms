import type { EstimateCatalogSettingsActionDto } from "@/features/settings/estimates-repairs/model/estimate-repair-settings"
import {
  getRepairEstimateCatalogCanvas,
  saveRepairEstimateCatalogNode,
} from "@/features/settings/estimates-repairs/api/repair-estimate-catalog-store"
import type {
  RepairEstimateCatalogNodeMutation,
  RepairEstimateCatalogRequest,
} from "@/features/settings/estimates-repairs/model/repair-estimate-catalog"

const REPAIR_ESTIMATE_CATALOG_CANVAS_SETTINGS: EstimateCatalogSettingsActionDto =
  {
    id: "repair-estimate-catalog-canvas",
    title: "Конструктор каталога смет",
    sectionType: "CANVAS",
    categoryScope: "ALL",
    order: 10,
  }

export async function getRepairEstimateCatalogCanvasSettings() {
  return REPAIR_ESTIMATE_CATALOG_CANVAS_SETTINGS
}

export async function getRepairEstimateCatalogCanvasSettingsData(
  request: RepairEstimateCatalogRequest
) {
  return getRepairEstimateCatalogCanvas(request)
}

export async function saveRepairEstimateCatalogCanvasNode(
  request: RepairEstimateCatalogRequest,
  input: RepairEstimateCatalogNodeMutation
) {
  return saveRepairEstimateCatalogNode(request, input)
}
