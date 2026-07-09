export type RepairEstimateCatalogNodeType =
  "CATEGORY" | "SUBCATEGORY" | "WORK" | "MATERIAL" | "LOCATION" | "OPTION"

export type RepairEstimateCatalogLinkType = "DEPENDENCY" | "FOLLOW_UP"

export type RepairEstimateCatalogRouteQueueKind =
  "MOVEMENT" | "REPAIR" | "HOLDING"

export type RepairEstimateCatalogSeedNode = {
  id: string
  code: string
  name: string
  nodeType: RepairEstimateCatalogNodeType
  parentCode: string | null
  active: boolean
  sortOrder: number | null
  unit: string | null
  unitPrice: number | null
  defaultQuantity: number
  durationMinutes: number | null
  additionalOption: boolean
  showInMainMenu: boolean
  mainMenuOrder: number | null
  mainMenuTitle: string | null
  routeQueueKind: RepairEstimateCatalogRouteQueueKind | null
  photoRequired: boolean
  includeInEstimate: boolean
  commonItem: boolean
  furnitureCategory: boolean
  canvasX: number | null
  canvasY: number | null
  comment: string | null
}

export type RepairEstimateCatalogSeedLink = {
  id: string
  sourceCode: string
  targetCode: string
  linkType: RepairEstimateCatalogLinkType
  active: boolean
  sortOrder: number | null
  comment: string | null
}

export type RepairEstimateCatalogNodeDto = Omit<
  RepairEstimateCatalogSeedNode,
  "parentCode"
> & {
  parentId: string | null
  parentCode: string | null
}

export type RepairEstimateCatalogLinkDto = {
  id: string
  sourceNodeId: string
  targetNodeId: string
  linkType: RepairEstimateCatalogLinkType
  active: boolean
  sortOrder: number | null
  comment: string | null
}

export type RepairEstimateCatalogSnapshotDto = {
  nodes: RepairEstimateCatalogNodeDto[]
  links: RepairEstimateCatalogLinkDto[]
  seedMeta: {
    nodeCount: number
    linkCount: number
    note: string
  }
}

export type RepairEstimateCatalogCanvasDto =
  RepairEstimateCatalogSnapshotDto & {
    categories: RepairEstimateCatalogNodeDto[]
  }

export type RepairEstimateCatalogSectionKind =
  "works" | "materials" | "furniture"

export type RepairEstimateCatalogSectionDto = {
  kind: RepairEstimateCatalogSectionKind
  title: string
  sectionType: "WORK" | "MATERIAL"
  categories: RepairEstimateCatalogNodeDto[]
  items: RepairEstimateCatalogNodeDto[]
  links: RepairEstimateCatalogLinkDto[]
}

export type RepairEstimateCatalogNodeMutation = {
  id?: string
  code: string
  name: string
  nodeType: RepairEstimateCatalogNodeType
  parentId: string | null
  active: boolean
  sortOrder: number | null
  unit: string | null
  unitPrice: number | null
  defaultQuantity: number
  durationMinutes: number | null
  additionalOption: boolean
  showInMainMenu: boolean
  includeInEstimate: boolean
  commonItem: boolean
  furnitureCategory: boolean
  canvasX: number | null
  canvasY: number | null
  comment: string | null
}

export type RepairEstimateCatalogLinkMutation = {
  id?: string
  sourceNodeId: string
  targetNodeId: string
  linkType: RepairEstimateCatalogLinkType
  active: boolean
  sortOrder: number | null
  comment: string | null
}

export function repairEstimateCatalogNodeTypeLabel(
  type: RepairEstimateCatalogNodeType
) {
  switch (type) {
    case "CATEGORY":
      return "Категория"
    case "SUBCATEGORY":
      return "Подкатегория"
    case "WORK":
      return "Работа"
    case "MATERIAL":
      return "Материал"
    case "LOCATION":
      return "Расположение"
    case "OPTION":
      return "Опция"
  }
}

export function repairEstimateCatalogLinkTypeLabel(
  type: RepairEstimateCatalogLinkType
) {
  switch (type) {
    case "FOLLOW_UP":
      return "Путь"
    case "DEPENDENCY":
      return "Путь + зависимость"
  }
}
