export type EstimateCatalogSettingsActionId =
  | "repair-estimate-catalog-colors"
  | "repair-estimate-catalog-canvas"
  | "repair-estimate-catalog-works"
  | "repair-estimate-catalog-materials"
  | "repair-estimate-catalog-furniture"

export type EstimateCatalogSectionType =
  "COLORS" | "CANVAS" | "WORK" | "MATERIAL"

export type EstimateCatalogCategoryScope =
  "ALL" | "NON_FURNITURE" | "FURNITURE_ONLY"

export type EstimateCatalogSettingsActionDto = {
  id: EstimateCatalogSettingsActionId
  title: string
  sectionType: EstimateCatalogSectionType
  categoryScope: EstimateCatalogCategoryScope
  order: number
}
