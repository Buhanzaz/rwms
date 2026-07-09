import type { EstimateCatalogSettingsActionDto } from "@/features/settings/estimates-repairs/model/estimate-repair-settings"
import {
  deleteRepairEstimateCatalogLink,
  deleteRepairEstimateCatalogNode,
  getRepairEstimateCatalogCanvas,
  resetRepairEstimateCatalogMock,
  saveRepairEstimateCatalogLink,
  saveRepairEstimateCatalogNode,
} from "@/features/settings/estimates-repairs/api/repair-estimate-catalog-store"
import type {
  RepairEstimateCatalogLinkMutation,
  RepairEstimateCatalogNodeMutation,
} from "@/features/settings/estimates-repairs/model/repair-estimate-catalog"

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

export async function getRepairEstimateCatalogCanvasMock() {
  return getRepairEstimateCatalogCanvas()
}

export async function saveRepairEstimateCatalogCanvasNode(
  input: RepairEstimateCatalogNodeMutation
) {
  return saveRepairEstimateCatalogNode(input)
}

export async function deleteRepairEstimateCatalogCanvasNode(id: string) {
  return deleteRepairEstimateCatalogNode(id)
}

export async function saveRepairEstimateCatalogCanvasLink(
  input: RepairEstimateCatalogLinkMutation
) {
  return saveRepairEstimateCatalogLink(input)
}

export async function deleteRepairEstimateCatalogCanvasLink(id: string) {
  return deleteRepairEstimateCatalogLink(id)
}

export async function resetRepairEstimateCatalogCanvasMock() {
  return resetRepairEstimateCatalogMock()
}
