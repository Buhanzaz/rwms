export type RepairEstimateCatalogNodeType =
  "CATEGORY" | "SUBCATEGORY" | "WORK" | "MATERIAL" | "LOCATION" | "OPTION"

export type RepairEstimateCatalogLinkType = "DEPENDENCY" | "FOLLOW_UP"

export type RepairEstimateCatalogRouteQueueKind =
  "MOVEMENT" | "REPAIR" | "HOLDING"

/** Exact decimal serialized by maintenance-service. */
export type RepairEstimateCatalogMoneyDecimal = string

export type RepairEstimateCatalogRequest = {
  accessToken: string
  warehouseId: string
  catalogVersionId: string
}

export type RepairEstimateCatalogVersionDto = {
  id: string
  warehouseId: string
  version: number
  lifecycle: "DRAFT" | "ACTIVE" | "SUPERSEDED"
  sourceSha256: string
  nodeCount: number
  linkCount: number
  valid: boolean
  createdAt: string
  activatedAt: string | null
}

export type RepairEstimateCatalogRoutingDto = {
  queueId: string
  queueCode: string
  queueKind: string
}

export type RepairEstimateCatalogReferenceDto = {
  referenceId: string
  code: string
}

export type RepairEstimateCatalogMediaReferenceDto = {
  mediaId: string
  generation: number
}

export type RepairEstimateCatalogCanvasLinkAnchors = {
  source: "TOP" | "BOTTOM"
  target: "TOP" | "BOTTOM"
}

export type RepairEstimateCatalogNodeDto = {
  id: string
  catalogVersionId: string
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
  routing: RepairEstimateCatalogRoutingDto | null
  photoRequired: boolean
  includeInEstimate: boolean
  commonItem: boolean
  references: RepairEstimateCatalogReferenceDto[]
  mediaReferences: RepairEstimateCatalogMediaReferenceDto[]
  /** Presentation coordinates only; canonical graph remains in maintenance-service. */
  canvasX: number | null
  canvasY: number | null
  comment: string | null
}

export type RepairEstimateCatalogLinkDto = {
  id: string
  catalogVersionId: string
  sourceNodeId: string
  targetNodeId: string
  linkType: RepairEstimateCatalogLinkType
  sortOrder: number
  /** Presentation anchors only; maintenance-service owns the canonical link. */
  canvasAnchors: RepairEstimateCatalogCanvasLinkAnchors | null
}

export type RepairEstimateCatalogSnapshotDto = {
  catalogVersion: RepairEstimateCatalogVersionDto
  nodes: RepairEstimateCatalogNodeDto[]
  links: RepairEstimateCatalogLinkDto[]
}

export type RepairEstimateCatalogCanvasDto =
  RepairEstimateCatalogSnapshotDto & {
    categories: RepairEstimateCatalogNodeDto[]
  }

export type RepairEstimateCatalogSectionKind = "works" | "materials"

export type RepairEstimateCatalogSectionDto = {
  kind: RepairEstimateCatalogSectionKind
  title: string
  sectionType: "WORK" | "MATERIAL"
  categories: RepairEstimateCatalogNodeDto[]
  nodes: RepairEstimateCatalogNodeDto[]
  links: RepairEstimateCatalogLinkDto[]
}

export type RepairEstimateCatalogNodeMutation = {
  id?: string
  code: string
  name: string
  nodeType: RepairEstimateCatalogNodeType
  parentId: string | null
  active: boolean
  unit: string | null
  unitPrice: RepairEstimateCatalogMoneyDecimal | null
  durationMinutes: number | null
  showInMainMenu: boolean
  routeQueueKind: RepairEstimateCatalogRouteQueueKind | null
  routing?: RepairEstimateCatalogRoutingDto | null
  references?: RepairEstimateCatalogReferenceDto[]
  mediaReferences?: RepairEstimateCatalogMediaReferenceDto[]
  photoRequired: boolean
  includeInEstimate: boolean
  commonItem: boolean
  comment: string | null
}

export type RepairEstimateCatalogLinkMutation = {
  id?: string
  sourceNodeId: string
  targetNodeId: string
  linkType: RepairEstimateCatalogLinkType
  sortOrder: number
  /** Presentation anchors only; never sent to maintenance-service. */
  canvasAnchors: RepairEstimateCatalogCanvasLinkAnchors | null
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
      return "Зависимость"
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
