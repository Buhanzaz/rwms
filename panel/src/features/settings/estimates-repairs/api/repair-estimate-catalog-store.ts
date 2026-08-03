import {
  createMaintenanceCatalog,
  listMaintenanceCatalogLinks,
  listMaintenanceCatalogNodes,
  listMaintenanceCatalogVersions,
  replaceMaintenanceCatalog,
  replaceMaintenanceCatalogLinks,
  replaceMaintenanceCatalogNodes,
  type MaintenanceCatalogLink,
  type MaintenanceCatalogLinkInput,
  type MaintenanceCatalogNode,
  type MaintenanceCatalogNodeInput,
  type MaintenanceCatalogVersion,
} from "@/features/repair-estimate-catalog/api/http-maintenance-catalog-client"
import type {
  RepairEstimateCatalogCanvasDto,
  RepairEstimateCatalogCanvasChangeSet,
  RepairEstimateCatalogLinkDto,
  RepairEstimateCatalogLinkMutation,
  RepairEstimateCatalogNodeDto,
  RepairEstimateCatalogNodeMutation,
  RepairEstimateCatalogRequest,
  RepairEstimateCatalogRouteQueueKind,
  RepairEstimateCatalogRoutingDto,
  RepairEstimateCatalogSectionDto,
  RepairEstimateCatalogSectionKind,
  RepairEstimateCatalogSnapshotDto,
  RepairEstimateCatalogVersionDto,
} from "@/features/settings/estimates-repairs/model/repair-estimate-catalog"

const MONEY_PATTERN = /^(?:0|[1-9][0-9]*)(?:\.[0-9]{2})$/
const DISPLAY_COLOR_PATTERN = /^#[0-9A-Fa-f]{6}$/

export type RepairEstimateCatalogDisplayColorGroup =
  | "CATEGORY"
  | "SUBCATEGORY"
  | "WORK"
  | "MATERIAL"
  | "FURNITURE"
  | "OPTION"
  | "LOCATION"

export const REPAIR_ESTIMATE_CATALOG_DISPLAY_COLOR_GROUPS = [
  "CATEGORY",
  "SUBCATEGORY",
  "WORK",
  "MATERIAL",
  "FURNITURE",
  "OPTION",
  "LOCATION",
] as const satisfies readonly RepairEstimateCatalogDisplayColorGroup[]

export type RepairEstimateCatalogDisplayColors = Record<
  RepairEstimateCatalogDisplayColorGroup,
  string | null
>

function idempotencyKey() {
  if (typeof crypto === "undefined" || !("randomUUID" in crypto)) {
    throw new Error(
      "Браузер не поддерживает создание безопасного ключа команды."
    )
  }
  return crypto.randomUUID()
}

function compareNodes(
  left: RepairEstimateCatalogNodeDto,
  right: RepairEstimateCatalogNodeDto
) {
  return (
    left.name.localeCompare(right.name, "ru") || left.id.localeCompare(right.id)
  )
}

function toVersion(
  value: MaintenanceCatalogVersion
): RepairEstimateCatalogVersionDto {
  return {
    id: value.id,
    warehouseId: value.warehouseId,
    version: value.version,
    lifecycle: value.lifecycle,
    sourceSha256: value.sourceSha256,
    nodeCount: value.counts.nodes,
    linkCount: value.counts.links,
    valid: value.validation.valid,
    createdAt: value.createdAt,
    activatedAt: value.activatedAt,
  }
}

export async function getCurrentRepairEstimateCatalog(
  accessToken: string,
  warehouseId: string
) {
  const page = await listMaintenanceCatalogVersions(
    accessToken,
    warehouseId,
    "ACTIVE"
  )
  const current = page.items.find((value) => value.lifecycle === "ACTIVE")
  return current ? toVersion(current) : null
}

async function catalogState(request: RepairEstimateCatalogRequest) {
  const versions = await listMaintenanceCatalogVersions(
    request.accessToken,
    request.warehouseId,
    "ACTIVE"
  )
  const version = versions.items.find(
    (candidate) => candidate.id === request.catalogVersionId
  )
  if (!version) {
    throw new Error("Текущая версия единого каталога не найдена.")
  }

  const [nodes, links] = await Promise.all([
    listMaintenanceCatalogNodes(
      request.accessToken,
      request.warehouseId,
      version.id
    ),
    listMaintenanceCatalogLinks(
      request.accessToken,
      request.warehouseId,
      version.id
    ),
  ])
  return { version, nodes, links }
}

function toSnapshot(
  _request: RepairEstimateCatalogRequest,
  state: Awaited<ReturnType<typeof catalogState>>
): RepairEstimateCatalogSnapshotDto {
  const nodes = state.nodes
    .map((node): RepairEstimateCatalogNodeDto => {
      const queueKind = node.routing?.queueType
      const routeQueueKind: RepairEstimateCatalogRouteQueueKind | null =
        queueKind === "MOVEMENT" ||
        queueKind === "REPAIR" ||
        queueKind === "HOLDING"
          ? queueKind
          : null
      const routing: RepairEstimateCatalogRoutingDto | null =
        routeQueueKind !== null && node.routing !== null
          ? { ...node.routing, queueType: routeQueueKind }
          : null
      return {
        id: node.id,
        catalogVersionId: node.catalogVersionId,
        name: node.name,
        displayColor: node.displayColor ?? null,
        nodeType: node.nodeType,
        parentId: node.parentNodeId,
        active: node.active,
        unit: node.unit,
        unitPrice: node.unitPrice,
        durationMinutes: node.nodeType === "WORK" ? node.durationMinutes : null,
        showInMainMenu: node.showInMainMenu,
        routeQueueKind,
        queueDefinitionId: routing?.queueId ?? null,
        routing,
        includeInEstimate: node.includeInEstimate,
        commonItem: node.commonItem,
        furnitureCategory: Boolean(node.furnitureCategory),
        furnitureEquipment: node.furnitureEquipment ?? null,
        forcesCapitalRepair: node.forcesCapitalRepair,
        characteristic: node.characteristic,
        canvasX: node.canvasX,
        canvasY: node.canvasY,
        comment: node.comment,
      }
    })
    .sort(compareNodes)

  const links = state.links
    .map((link): RepairEstimateCatalogLinkDto => {
      const anchors =
        link.sourceAnchor !== null && link.targetAnchor !== null
          ? { source: link.sourceAnchor, target: link.targetAnchor }
          : null
      return {
        id: link.id,
        catalogVersionId: link.catalogVersionId,
        sourceNodeId: link.fromNodeId,
        targetNodeId: link.toNodeId,
        linkType: link.linkType,
        sortOrder: link.sortOrder,
        canvasAnchors: anchors,
      }
    })
    .sort(
      (left, right) =>
        left.sortOrder - right.sortOrder || left.id.localeCompare(right.id)
    )

  return { catalogVersion: toVersion(state.version), nodes, links }
}

export async function getRepairEstimateCatalogSnapshot(
  request: RepairEstimateCatalogRequest
) {
  return toSnapshot(request, await catalogState(request))
}

function isFurnitureTreeNode(
  node: RepairEstimateCatalogNodeDto,
  nodesById: ReadonlyMap<string, RepairEstimateCatalogNodeDto>
) {
  const visited = new Set<string>()
  let current: RepairEstimateCatalogNodeDto | undefined = node

  while (current) {
    if (current.furnitureCategory) return true
    if (!current.parentId || visited.has(current.id)) return false
    visited.add(current.id)
    current = nodesById.get(current.parentId)
  }

  return false
}

function rootCategories(
  nodes: RepairEstimateCatalogNodeDto[],
  scope: "ALL" | "NON_FURNITURE" | "FURNITURE_ONLY" = "ALL"
) {
  return nodes
    .filter(
      (node) =>
        node.active &&
        node.nodeType === "CATEGORY" &&
        node.parentId === null &&
        (scope === "ALL" ||
          (scope === "FURNITURE_ONLY"
            ? node.furnitureCategory
            : !node.furnitureCategory))
    )
    .sort(compareNodes)
}

export async function getRepairEstimateCatalogCanvas(
  request: RepairEstimateCatalogRequest
) {
  const snapshot = await getRepairEstimateCatalogSnapshot(request)
  return {
    ...snapshot,
    categories: rootCategories(snapshot.nodes),
  } satisfies RepairEstimateCatalogCanvasDto
}

export async function getRepairEstimateCatalogSection(
  request: RepairEstimateCatalogRequest,
  kind: RepairEstimateCatalogSectionKind
) {
  const snapshot = await getRepairEstimateCatalogSnapshot(request)
  const furniture = kind === "furniture"
  return {
    kind,
    title:
      kind === "works"
        ? "Работы"
        : kind === "furniture"
          ? "Мебель"
          : "Материалы",
    sectionType: kind === "works" ? "WORK" : "MATERIAL",
    categories: rootCategories(
      snapshot.nodes,
      furniture ? "FURNITURE_ONLY" : "NON_FURNITURE"
    ),
    nodes: snapshot.nodes,
    links: snapshot.links,
  } satisfies RepairEstimateCatalogSectionDto
}

function normalizeMoney(value: string | null) {
  if (value === null || value.trim() === "") return null
  const normalized = value.trim().replace(",", ".")
  const withScale = /^\d+$/.test(normalized) ? `${normalized}.00` : normalized
  if (!MONEY_PATTERN.test(withScale)) {
    throw new Error(
      "Цена должна быть неотрицательной и содержать две цифры после точки."
    )
  }
  return withScale
}

function normalizeDisplayColor(value: string | null | undefined) {
  if (value === null || value === undefined || value.trim() === "") {
    return null
  }

  const normalized = value.trim().toUpperCase()
  if (!DISPLAY_COLOR_PATTERN.test(normalized)) {
    throw new Error("Цвет кнопки должен быть в формате #RRGGBB.")
  }
  return normalized
}

function toNodeInput(
  node: MaintenanceCatalogNode
): MaintenanceCatalogNodeInput {
  return {
    id: node.id,
    nodeType: node.nodeType,
    name: node.name,
    displayColor: node.displayColor ?? null,
    active: node.active,
    parentNodeId: node.parentNodeId,
    unit: node.unit,
    unitPrice: node.unitPrice,
    durationMinutes: node.durationMinutes,
    includeInEstimate: node.includeInEstimate,
    commonItem: node.commonItem,
    showInMainMenu: node.showInMainMenu,
    furnitureCategory: Boolean(node.furnitureCategory),
    furnitureEquipment: node.furnitureEquipment ?? null,
    forcesCapitalRepair: node.forcesCapitalRepair,
    characteristicId: node.characteristic?.characteristicId ?? null,
    routing: node.routing
      ? { queueId: node.routing.queueId, queueType: node.routing.queueType }
      : null,
    canvasX: node.canvasX,
    canvasY: node.canvasY,
    comment: node.comment,
  }
}

function maintenanceNodeIsInFurnitureTree(
  node: MaintenanceCatalogNode,
  nodesById: ReadonlyMap<string, MaintenanceCatalogNode>
) {
  const visited = new Set<string>()
  let current: MaintenanceCatalogNode | undefined = node

  while (current) {
    if (current.furnitureCategory) return true
    if (!current.parentNodeId || visited.has(current.id)) return false
    visited.add(current.id)
    current = nodesById.get(current.parentNodeId)
  }

  return false
}

function catalogDisplayColorGroup(
  node: MaintenanceCatalogNode,
  nodesById: ReadonlyMap<string, MaintenanceCatalogNode>
): RepairEstimateCatalogDisplayColorGroup {
  if (maintenanceNodeIsInFurnitureTree(node, nodesById)) {
    return "FURNITURE"
  }

  return node.nodeType
}

export async function saveRepairEstimateCatalogDisplayColors(
  request: RepairEstimateCatalogRequest,
  colors: RepairEstimateCatalogDisplayColors
) {
  const normalizedColors = {} as RepairEstimateCatalogDisplayColors
  for (const group of REPAIR_ESTIMATE_CATALOG_DISPLAY_COLOR_GROUPS) {
    normalizedColors[group] = normalizeDisplayColor(colors[group])
  }

  const state = await catalogState(request)
  const nodesById = new Map(state.nodes.map((node) => [node.id, node]))
  const nodes = state.nodes.map((node) => ({
    ...toNodeInput(node),
    displayColor: normalizedColors[catalogDisplayColorGroup(node, nodesById)],
  }))

  await replaceMaintenanceCatalogNodes(
    request.accessToken,
    request.warehouseId,
    state.version.id,
    state.version.version,
    nodes
  )

  return getRepairEstimateCatalogSnapshot(request)
}

function nodeInput(
  input: RepairEstimateCatalogNodeMutation,
  existing: MaintenanceCatalogNode | undefined,
  nodes: MaintenanceCatalogNode[]
): MaintenanceCatalogNodeInput {
  const name = input.name.trim()
  if (!name) throw new Error("Заполните название.")
  if (input.parentId && !nodes.some((node) => node.id === input.parentId)) {
    throw new Error("Родительский узел не найден.")
  }

  const nodesById = new Map(nodes.map((node) => [node.id, node]))
  let parent = input.parentId ? nodesById.get(input.parentId) : undefined
  const visited = new Set<string>()
  let furnitureTree = false
  while (parent) {
    if (parent.furnitureCategory) {
      furnitureTree = true
      break
    }
    if (!parent.parentNodeId || visited.has(parent.id)) break
    visited.add(parent.id)
    parent = nodesById.get(parent.parentNodeId)
  }
  const furnitureMaterial = input.nodeType === "MATERIAL" && furnitureTree
  const furnitureEquipment =
    input.furnitureEquipment ?? existing?.furnitureEquipment ?? null
  if (!furnitureMaterial && furnitureEquipment !== null) {
    throw new Error(
      "Дополнительное оборудование можно привязать только к мебели."
    )
  }

  return {
    id: existing?.id ?? input.id ?? idempotencyKey(),
    nodeType: input.nodeType,
    name,
    displayColor:
      input.displayColor === undefined
        ? (existing?.displayColor ?? null)
        : normalizeDisplayColor(input.displayColor),
    active: input.active,
    parentNodeId: input.nodeType === "CATEGORY" ? null : input.parentId,
    unit: input.unit?.trim() || null,
    unitPrice: normalizeMoney(input.unitPrice),
    durationMinutes:
      input.nodeType === "WORK"
        ? Math.max(0, Math.trunc(input.durationMinutes ?? 0))
        : 0,
    includeInEstimate: input.includeInEstimate,
    commonItem: input.commonItem,
    showInMainMenu: input.showInMainMenu,
    furnitureCategory:
      input.nodeType === "CATEGORY" && Boolean(input.furnitureCategory),
    furnitureEquipment: furnitureMaterial ? furnitureEquipment : null,
    forcesCapitalRepair:
      input.nodeType === "WORK" && Boolean(input.forcesCapitalRepair),
    characteristicId:
      input.nodeType === "MATERIAL" ? input.characteristicId : null,
    routing:
      input.routing === undefined
        ? existing?.routing
          ? {
              queueId: existing.routing.queueId,
              queueType: existing.routing.queueType,
            }
          : null
        : input.routing
          ? {
              queueId: input.routing.queueId,
              queueType: input.routing.queueType,
            }
          : null,
    canvasX: input.canvasX ?? existing?.canvasX ?? null,
    canvasY: input.canvasY ?? existing?.canvasY ?? null,
    comment: input.comment?.trim() || null,
  }
}

export async function saveRepairEstimateCatalogNode(
  request: RepairEstimateCatalogRequest,
  input: RepairEstimateCatalogNodeMutation
) {
  const state = await catalogState(request)
  const existing = input.id
    ? state.nodes.find((node) => node.id === input.id)
    : undefined
  const changed = nodeInput(input, existing, state.nodes)
  const nodes = existing
    ? state.nodes.map((node) =>
        node.id === existing.id ? changed : toNodeInput(node)
      )
    : [...state.nodes.map(toNodeInput), changed]

  await replaceMaintenanceCatalogNodes(
    request.accessToken,
    request.warehouseId,
    state.version.id,
    state.version.version,
    nodes
  )
  const snapshot = await getRepairEstimateCatalogSnapshot(request)
  return snapshot.nodes.find((node) => node.id === changed.id)!
}

export async function deleteRepairEstimateCatalogNode(
  request: RepairEstimateCatalogRequest,
  id: string
) {
  const state = await catalogState(request)
  if (state.nodes.some((node) => node.parentNodeId === id)) {
    throw new Error("Сначала удалите дочерние блоки.")
  }
  if (
    state.links.some((link) => link.fromNodeId === id || link.toNodeId === id)
  ) {
    throw new Error("Сначала удалите связи блока.")
  }
  await replaceMaintenanceCatalogNodes(
    request.accessToken,
    request.warehouseId,
    state.version.id,
    state.version.version,
    state.nodes.filter((node) => node.id !== id).map(toNodeInput)
  )
}

function toLinkInput(
  link: MaintenanceCatalogLink
): MaintenanceCatalogLinkInput {
  return {
    id: link.id,
    fromNodeId: link.fromNodeId,
    toNodeId: link.toNodeId,
    linkType: link.linkType,
    sortOrder: link.sortOrder,
    sourceAnchor: link.sourceAnchor,
    targetAnchor: link.targetAnchor,
  }
}

function catalogLinkInput(
  input: RepairEstimateCatalogLinkMutation,
  nodeIds: ReadonlySet<string>
): MaintenanceCatalogLinkInput {
  if (input.sourceNodeId === input.targetNodeId) {
    throw new Error("Исходный и целевой узлы должны отличаться.")
  }
  if (!nodeIds.has(input.sourceNodeId) || !nodeIds.has(input.targetNodeId)) {
    throw new Error("Узел связи не найден.")
  }

  return {
    id: input.id ?? idempotencyKey(),
    fromNodeId: input.sourceNodeId,
    toNodeId: input.targetNodeId,
    linkType: input.linkType,
    sortOrder: Math.max(0, Math.trunc(input.sortOrder)),
    sourceAnchor: input.canvasAnchors?.source ?? null,
    targetAnchor: input.canvasAnchors?.target ?? null,
  }
}

export async function saveRepairEstimateCatalogLink(
  request: RepairEstimateCatalogRequest,
  input: RepairEstimateCatalogLinkMutation
) {
  const state = await catalogState(request)
  const nodeIds = new Set(state.nodes.map((node) => node.id))
  const changed = catalogLinkInput(input, nodeIds)
  const id = changed.id
  const links = state.links.some((link) => link.id === id)
    ? state.links.map((link) => (link.id === id ? changed : toLinkInput(link)))
    : [...state.links.map(toLinkInput), changed]

  await replaceMaintenanceCatalogLinks(
    request.accessToken,
    request.warehouseId,
    state.version.id,
    state.version.version,
    links
  )

  const snapshot = await getRepairEstimateCatalogSnapshot(request)
  return snapshot.links.find((link) => link.id === id)!
}

export async function deleteRepairEstimateCatalogLink(
  request: RepairEstimateCatalogRequest,
  id: string
) {
  const state = await catalogState(request)
  await replaceMaintenanceCatalogLinks(
    request.accessToken,
    request.warehouseId,
    state.version.id,
    state.version.version,
    state.links.filter((link) => link.id !== id).map(toLinkInput)
  )
}

export async function moveRepairEstimateCatalogCanvasNode(
  request: RepairEstimateCatalogRequest,
  nodeId: string,
  position: { x: number; y: number }
) {
  const state = await catalogState(request)
  if (!state.nodes.some((node) => node.id === nodeId)) {
    throw new Error("Блок каталога не найден.")
  }
  const nodes = state.nodes.map((node) => ({
    ...toNodeInput(node),
    canvasX: node.id === nodeId ? Math.round(position.x) : node.canvasX,
    canvasY: node.id === nodeId ? Math.round(position.y) : node.canvasY,
  }))
  await replaceMaintenanceCatalogNodes(
    request.accessToken,
    request.warehouseId,
    state.version.id,
    state.version.version,
    nodes
  )
  return position
}

export async function saveRepairEstimateCatalogCanvasChanges(
  request: RepairEstimateCatalogRequest,
  changes: RepairEstimateCatalogCanvasChangeSet
) {
  const state = await catalogState(request)
  const nodeIds = new Set(state.nodes.map((node) => node.id))
  const positions = new Map(
    changes.nodePositions.map((position) => [position.nodeId, position])
  )
  for (const nodeId of positions.keys()) {
    if (!nodeIds.has(nodeId)) {
      throw new Error("Блок каталога не найден.")
    }
  }

  const deletedLinkIds = new Set(changes.deletedLinkIds)
  const linkIds = new Set(
    state.links
      .filter((link) => !deletedLinkIds.has(link.id))
      .map((link) => link.id)
  )
  const addedLinks = changes.addedLinks.map((input) => {
    const link = catalogLinkInput(input, nodeIds)
    if (linkIds.has(link.id)) {
      throw new Error("Связь с таким идентификатором уже существует.")
    }
    linkIds.add(link.id)
    return link
  })

  const nodes = state.nodes.map((node) => {
    const position = positions.get(node.id)
    return {
      ...toNodeInput(node),
      canvasX: position ? Math.round(position.x) : node.canvasX,
      canvasY: position ? Math.round(position.y) : node.canvasY,
    }
  })
  const links = [
    ...state.links
      .filter((link) => !deletedLinkIds.has(link.id))
      .map(toLinkInput),
    ...addedLinks,
  ]

  await replaceMaintenanceCatalog(
    request.accessToken,
    request.warehouseId,
    state.version.id,
    state.version.version,
    nodes,
    links
  )
  return getRepairEstimateCatalogCanvas(request)
}

export function createRepairEstimateCatalog(
  accessToken: string,
  warehouseId: string,
  commandKey: string = idempotencyKey()
) {
  return createMaintenanceCatalog(accessToken, commandKey, {
    warehouseId,
  }).then(toVersion)
}

function nodeById(nodes: RepairEstimateCatalogNodeDto[]) {
  return new Map(nodes.map((node) => [node.id, node]))
}

function belongsToCategory(
  node: RepairEstimateCatalogNodeDto,
  categoryId: string,
  nodes: Map<string, RepairEstimateCatalogNodeDto>
) {
  let current: RepairEstimateCatalogNodeDto | undefined = node
  const visited = new Set<string>()
  while (current) {
    if (current.id === categoryId) return true
    if (!current.parentId || visited.has(current.id)) return false
    visited.add(current.id)
    current = nodes.get(current.parentId)
  }
  return false
}

export function getRepairEstimateCatalogSectionItems(
  section: RepairEstimateCatalogSectionDto
) {
  const nodesById = nodeById(section.nodes)
  return section.nodes
    .filter((node) => {
      if (!node.active || node.nodeType !== section.sectionType) return false
      const furniture = isFurnitureTreeNode(node, nodesById)
      return section.kind === "furniture" ? furniture : !furniture
    })
    .sort(compareNodes)
}

export function getItemsForCategory(
  section: RepairEstimateCatalogSectionDto,
  categoryId: string
) {
  const nodes = nodeById(section.nodes)
  return getRepairEstimateCatalogSectionItems(section).filter(
    (item) => !item.commonItem && belongsToCategory(item, categoryId, nodes)
  )
}
