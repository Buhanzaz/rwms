export type RepairEstimateCatalogNodeType =
  "CATEGORY" | "SUBCATEGORY" | "WORK" | "MATERIAL" | "LOCATION" | "OPTION"

export type RepairEstimateCatalogLinkType = "DEPENDENCY" | "FOLLOW_UP"

export type RepairEstimateCatalogRouteQueueKind =
  "MOVEMENT" | "REPAIR" | "HOLDING"

/** Exact decimal serialized by the catalog service boundary. */
export type RepairEstimateCatalogMoneyDecimal = string

/** Canonical read DTO exposed by the repair-catalog service boundary. */
export type RepairEstimateCatalogNodeDto = {
  id: string
  catalogVersionId?: string | null
  code: string
  name: string
  nodeType: RepairEstimateCatalogNodeType
  parentId: string | null
  parentCode: string | null
  active: boolean
  sortOrder: number | null
  unit: string | null
  unitPrice: RepairEstimateCatalogMoneyDecimal | null
  defaultQuantity: number
  durationMinutes: number | null
  additionalOption: boolean
  showInMainMenu: boolean
  mainMenuOrder: number | null
  mainMenuTitle: string | null
  routeQueueKind: RepairEstimateCatalogRouteQueueKind | null
  workQueueCode: string | null
  workQueueId?: string | null
  photoRequired: boolean
  includeInEstimate: boolean
  commonItem: boolean
  furnitureCategory: boolean
  canvasX: number | null
  canvasY: number | null
  comment: string | null
}

/** Canonical read DTO exposed by the repair-catalog service boundary. */
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

export type RepairEstimateCatalogEffectiveQueueBinding = {
  queueId: string | null
  queueCode: string | null
  queueKind: RepairEstimateCatalogRouteQueueKind | null
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

export function repairEstimateCatalogRouteQueueKindLabel(
  kind: RepairEstimateCatalogRouteQueueKind
) {
  switch (kind) {
    case "MOVEMENT":
      return "Перемещение"
    case "REPAIR":
      return "Ремонт"
    case "HOLDING":
      return "Ожидание"
  }
}
