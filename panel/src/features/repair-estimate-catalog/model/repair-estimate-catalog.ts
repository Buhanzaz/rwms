export type RepairEstimateCatalogNodeType =
  "CATEGORY" | "SUBCATEGORY" | "WORK" | "MATERIAL" | "LOCATION" | "OPTION"

export type RepairEstimateCatalogLinkType = "DEPENDENCY" | "FOLLOW_UP"

export type RepairEstimateCatalogRouteQueueKind =
  "MOVEMENT" | "REPAIR" | "HOLDING"

/** Exact decimal serialized by the catalog service boundary. */
export type RepairEstimateCatalogMoneyDecimal = string

export type RepairEstimateFurnitureEquipmentReferenceDto = {
  equipmentId: string
  equipmentName: string
}

export type RepairEstimateCabinCharacteristicReferenceDto = {
  characteristicId: string
  characteristicName: string
}

/** Operational panel projection of a maintenance-service catalog node. */
export type RepairEstimateCatalogNodeDto = {
  id: string
  catalogVersionId: string
  name: string
  /** Optional semantic button colour configured in the catalog settings. */
  displayColor?: string | null
  nodeType: RepairEstimateCatalogNodeType
  parentId: string | null
  active: boolean
  unit: string | null
  unitPrice: RepairEstimateCatalogMoneyDecimal | null
  durationMinutes: number | null
  showInMainMenu: boolean
  routeQueueKind: RepairEstimateCatalogRouteQueueKind | null
  queueDefinitionId: string | null
  queueDefinitionName: string | null
  includeInEstimate: boolean
  commonItem: boolean
  furnitureCategory: boolean
  furnitureEquipment: RepairEstimateFurnitureEquipmentReferenceDto | null
  forcesCapitalRepair: boolean
  characteristic: RepairEstimateCabinCharacteristicReferenceDto | null
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
  queueName: string | null
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
