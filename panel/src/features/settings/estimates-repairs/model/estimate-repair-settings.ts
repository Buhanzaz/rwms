export type EstimateCatalogSettingsActionId =
  | "repair-estimate-catalog-canvas"
  | "repair-estimate-catalog-works"
  | "repair-estimate-catalog-materials"
  | "repair-estimate-catalog-furniture"

export type EstimateCatalogSectionType = "CANVAS" | "WORK" | "MATERIAL"

export type EstimateCatalogCategoryScope =
  | "ALL"
  | "NON_FURNITURE"
  | "FURNITURE_ONLY"

export type EstimateCatalogSettingsActionDto = {
  id: EstimateCatalogSettingsActionId
  title: string
  legacyRoute: string
  legacyViewId: string
  legacyClassName: string
  sectionType: EstimateCatalogSectionType
  categoryScope: EstimateCatalogCategoryScope
  order: number
}

export type RepairSettingsActionId =
  | "repair-stage-settings"
  | "repair-route-settings"
  | "repair-acceptance-settings"
  | "repair-rework-settings"

export type RepairSettingsActionDto = {
  id: RepairSettingsActionId
  title: string
  mockOnly: true
  order: number
}
