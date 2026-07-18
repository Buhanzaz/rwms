export type EstimateCatalogSettingsActionId =
  | "repair-estimate-catalog-canvas"
  | "repair-estimate-catalog-works"
  | "repair-estimate-catalog-materials"

export type EstimateCatalogSectionType = "CANVAS" | "WORK" | "MATERIAL"

export type EstimateCatalogCategoryScope = "ALL" | "NON_FURNITURE"

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
