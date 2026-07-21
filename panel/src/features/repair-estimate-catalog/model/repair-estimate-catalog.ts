export type RepairEstimateCatalogNodeType =
  "CATEGORY" | "SUBCATEGORY" | "WORK" | "MATERIAL" | "LOCATION" | "OPTION"

export type RepairEstimateCatalogLinkType = "DEPENDENCY" | "FOLLOW_UP"

export type RepairEstimateCatalogRouteQueueKind =
  "MOVEMENT" | "REPAIR" | "HOLDING"

/** Exact decimal serialized by the catalog service boundary. */
export type RepairEstimateCatalogMoneyDecimal = string

export type RepairEstimateFurnitureEquipmentReferenceDto = {
  equipmentId: string
  equipmentCode: string
  equipmentName: string
}

/** Operational panel projection of a maintenance-service catalog node. */
export type RepairEstimateCatalogNodeDto = {
  id: string
  catalogVersionId: string
  mediaOwnerId: string
  code: string
  name: string
  nodeType: RepairEstimateCatalogNodeType
  parentId: string | null
  parentCode: string | null
  active: boolean
  unit: string | null
  unitPrice: RepairEstimateCatalogMoneyDecimal | null
  durationMinutes: number | null
  showInMainMenu: boolean
  routeQueueKind: RepairEstimateCatalogRouteQueueKind | null
  workQueueId: string | null
  workQueueCode: string | null
  photoRequired: boolean
  includeInEstimate: boolean
  commonItem: boolean
  furnitureCategory: boolean
  furnitureEquipment: RepairEstimateFurnitureEquipmentReferenceDto | null
  comment: string | null
}

/** Operational panel projection of a maintenance-service catalog link. */
export type RepairEstimateCatalogLinkDto = {
  id: string
  catalogVersionId: string
  sourceNodeId: string
  targetNodeId: string
  linkType: RepairEstimateCatalogLinkType
  sortOrder: number
}

export type RepairEstimateCatalogSnapshotDto = {
  nodes: RepairEstimateCatalogNodeDto[]
  links: RepairEstimateCatalogLinkDto[]
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
