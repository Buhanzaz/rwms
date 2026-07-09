import {
  REPAIR_ESTIMATE_CATALOG_SEED_LINKS,
  REPAIR_ESTIMATE_CATALOG_SEED_NODES,
  REPAIR_ESTIMATE_CATALOG_SEED_SOURCE,
} from "@/features/settings/estimates-repairs/api/repair-estimate-catalog-seed"
import type {
  RepairEstimateCatalogCanvasDto,
  RepairEstimateCatalogLinkDto,
  RepairEstimateCatalogLinkMutation,
  RepairEstimateCatalogNodeDto,
  RepairEstimateCatalogNodeMutation,
  RepairEstimateCatalogNodeType,
  RepairEstimateCatalogSectionDto,
  RepairEstimateCatalogSectionKind,
  RepairEstimateCatalogSnapshotDto,
} from "@/features/settings/estimates-repairs/model/repair-estimate-catalog"

const STORAGE_KEY = "rwms:repair-estimate-catalog:v1"
const DEFAULT_PARENT_LINK_COMMENT = "__anchors__:BOTTOM->TOP"

type RepairEstimateCatalogState = {
  nodes: RepairEstimateCatalogNodeDto[]
  links: RepairEstimateCatalogLinkDto[]
}

type CategoryScope = "ALL" | "NON_FURNITURE" | "FURNITURE_ONLY"

function normalizeCode(value: string) {
  return value.trim().toUpperCase()
}

function normalizeNullableText(value: string | null | undefined) {
  const normalized = value?.trim()
  return normalized ? normalized : null
}

function nextId(prefix: string) {
  if (typeof crypto !== "undefined" && "randomUUID" in crypto) {
    return crypto.randomUUID()
  }

  return `${prefix}-${Date.now()}-${Math.random().toString(16).slice(2)}`
}

function compareBySortThenName(
  left: RepairEstimateCatalogNodeDto,
  right: RepairEstimateCatalogNodeDto
) {
  const leftOrder = left.sortOrder ?? Number.MAX_SAFE_INTEGER
  const rightOrder = right.sortOrder ?? Number.MAX_SAFE_INTEGER
  if (leftOrder !== rightOrder) {
    return leftOrder - rightOrder
  }

  return left.name.localeCompare(right.name, "ru")
}

function getLinkKey(
  link: Pick<
    RepairEstimateCatalogLinkDto,
    "sourceNodeId" | "targetNodeId" | "linkType"
  >
) {
  return `${link.sourceNodeId}:${link.targetNodeId}:${link.linkType}`
}

function buildParentDerivedLinks(
  nodes: RepairEstimateCatalogNodeDto[],
  existingLinks: RepairEstimateCatalogLinkDto[]
) {
  const existingKeys = new Set(existingLinks.map(getLinkKey))
  const links: RepairEstimateCatalogLinkDto[] = []

  for (const node of nodes) {
    if (!node.parentId || node.nodeType === "CATEGORY") {
      continue
    }

    const link: RepairEstimateCatalogLinkDto = {
      id: `seed-parent-link:${node.parentId}:${node.id}`,
      sourceNodeId: node.parentId,
      targetNodeId: node.id,
      linkType: "FOLLOW_UP",
      active: true,
      sortOrder: node.sortOrder,
      comment: DEFAULT_PARENT_LINK_COMMENT,
    }
    const key = getLinkKey(link)
    if (existingKeys.has(key)) {
      continue
    }

    existingKeys.add(key)
    links.push(link)
  }

  return links
}

function withParentDerivedLinks(state: RepairEstimateCatalogState) {
  const derivedLinks = buildParentDerivedLinks(state.nodes, state.links)
  if (derivedLinks.length === 0) {
    return state
  }

  return {
    ...state,
    links: state.links.concat(derivedLinks),
  }
}

function buildSeedState(): RepairEstimateCatalogState {
  const idByCode = new Map(
    REPAIR_ESTIMATE_CATALOG_SEED_NODES.map((node) => [node.code, node.id])
  )

  const nodes = REPAIR_ESTIMATE_CATALOG_SEED_NODES.map((node) => ({
    ...node,
    parentId: node.parentCode ? (idByCode.get(node.parentCode) ?? null) : null,
  }))

  const links = REPAIR_ESTIMATE_CATALOG_SEED_LINKS.map((link) => ({
    id: link.id,
    sourceNodeId: idByCode.get(link.sourceCode) ?? link.sourceCode,
    targetNodeId: idByCode.get(link.targetCode) ?? link.targetCode,
    linkType: link.linkType,
    active: link.active,
    sortOrder: link.sortOrder,
    comment: link.comment,
  }))

  return withParentDerivedLinks({ nodes, links })
}

function storageAvailable() {
  return (
    typeof window !== "undefined" && typeof window.localStorage !== "undefined"
  )
}

function readState(): RepairEstimateCatalogState {
  if (!storageAvailable()) {
    return buildSeedState()
  }

  const raw = window.localStorage.getItem(STORAGE_KEY)
  if (!raw) {
    const seed = buildSeedState()
    writeState(seed)
    return seed
  }

  try {
    const parsed = JSON.parse(raw) as RepairEstimateCatalogState
    if (!Array.isArray(parsed.nodes) || !Array.isArray(parsed.links)) {
      throw new Error("Invalid catalog state")
    }

    if (parsed.links.length === 0) {
      const state = withParentDerivedLinks(parsed)
      writeState(state)
      return state
    }

    return parsed
  } catch {
    const seed = buildSeedState()
    writeState(seed)
    return seed
  }
}

function writeState(state: RepairEstimateCatalogState) {
  if (!storageAvailable()) {
    return
  }

  window.localStorage.setItem(STORAGE_KEY, JSON.stringify(state))
}

function nodeById(nodes: RepairEstimateCatalogNodeDto[]) {
  return new Map(nodes.map((node) => [node.id, node]))
}

function withParentCodes(nodes: RepairEstimateCatalogNodeDto[]) {
  const map = nodeById(nodes)
  return nodes.map((node) => ({
    ...node,
    parentCode: node.parentId ? (map.get(node.parentId)?.code ?? null) : null,
  }))
}

function isFurnitureTreeNode(
  node: RepairEstimateCatalogNodeDto,
  nodesById: Map<string, RepairEstimateCatalogNodeDto>
) {
  let current: RepairEstimateCatalogNodeDto | undefined = node
  const visited = new Set<string>()

  while (current) {
    if (current.furnitureCategory) {
      return true
    }
    if (!current.parentId || visited.has(current.id)) {
      return false
    }
    visited.add(current.id)
    current = nodesById.get(current.parentId)
  }

  return false
}

function categoryMatchesScope(
  category: RepairEstimateCatalogNodeDto,
  scope: CategoryScope
) {
  if (scope === "ALL") {
    return true
  }
  if (scope === "FURNITURE_ONLY") {
    return category.furnitureCategory
  }

  return !category.furnitureCategory
}

function getRootCategories(
  nodes: RepairEstimateCatalogNodeDto[],
  scope: CategoryScope
) {
  return nodes
    .filter(
      (node) =>
        node.active &&
        node.nodeType === "CATEGORY" &&
        node.parentId === null &&
        categoryMatchesScope(node, scope)
    )
    .sort(compareBySortThenName)
}

function belongsToCategory(
  node: RepairEstimateCatalogNodeDto,
  categoryId: string,
  nodesById: Map<string, RepairEstimateCatalogNodeDto>
) {
  let current: RepairEstimateCatalogNodeDto | undefined = node
  const visited = new Set<string>()

  while (current) {
    if (visited.has(current.id)) {
      return false
    }
    visited.add(current.id)

    if (current.id === categoryId) {
      return true
    }
    if (!current.parentId) {
      return false
    }
    current = nodesById.get(current.parentId)
  }

  return false
}

function getSectionType(kind: RepairEstimateCatalogSectionKind) {
  return kind === "works" ? "WORK" : "MATERIAL"
}

function getSectionTitle(kind: RepairEstimateCatalogSectionKind) {
  switch (kind) {
    case "works":
      return "Работы"
    case "materials":
      return "Материалы"
    case "furniture":
      return "Мебель"
  }
}

function getSectionScope(
  kind: RepairEstimateCatalogSectionKind
): CategoryScope {
  return kind === "furniture" ? "FURNITURE_ONLY" : "NON_FURNITURE"
}

function toSnapshot(
  state: RepairEstimateCatalogState
): RepairEstimateCatalogSnapshotDto {
  return {
    nodes: withParentCodes(state.nodes).sort(compareBySortThenName),
    links: state.links.slice().sort((left, right) => {
      const leftOrder = left.sortOrder ?? Number.MAX_SAFE_INTEGER
      const rightOrder = right.sortOrder ?? Number.MAX_SAFE_INTEGER
      if (leftOrder !== rightOrder) {
        return leftOrder - rightOrder
      }

      return left.id.localeCompare(right.id)
    }),
    seedMeta: {
      nodeCount: REPAIR_ESTIMATE_CATALOG_SEED_SOURCE.nodeCount,
      linkCount: REPAIR_ESTIMATE_CATALOG_SEED_SOURCE.linkCount,
      note: REPAIR_ESTIMATE_CATALOG_SEED_SOURCE.note,
    },
  }
}

function ensureUniqueCode(
  nodes: RepairEstimateCatalogNodeDto[],
  code: string,
  currentId?: string
) {
  const normalized = normalizeCode(code)
  const used = nodes.some(
    (node) => node.code === normalized && node.id !== currentId
  )

  if (used) {
    throw new Error("Код уже существует")
  }

  return normalized
}

function validateParent(
  input: RepairEstimateCatalogNodeMutation,
  nodesByIdMap: Map<string, RepairEstimateCatalogNodeDto>
) {
  if (input.nodeType === "CATEGORY") {
    return null
  }

  if (!input.parentId) {
    throw new Error("Для записи нужно выбрать родителя")
  }

  const parent = nodesByIdMap.get(input.parentId)
  if (!parent) {
    throw new Error("Родитель не найден")
  }

  return parent.id
}

function normalizeNodeInput(
  input: RepairEstimateCatalogNodeMutation,
  nodes: RepairEstimateCatalogNodeDto[]
): RepairEstimateCatalogNodeDto {
  const normalizedCode = ensureUniqueCode(nodes, input.code, input.id)
  const nodesByIdMap = nodeById(nodes)
  const parentId = validateParent(input, nodesByIdMap)
  const existing = input.id ? nodesByIdMap.get(input.id) : undefined

  return {
    id: input.id ?? nextId("catalog-node"),
    code: normalizedCode,
    name: input.name.trim(),
    nodeType: input.nodeType,
    parentId,
    parentCode: parentId ? (nodesByIdMap.get(parentId)?.code ?? null) : null,
    active: input.active,
    sortOrder: input.sortOrder,
    unit: pricedNode(input.nodeType) ? normalizeNullableText(input.unit) : null,
    unitPrice: pricedNode(input.nodeType) ? input.unitPrice : null,
    defaultQuantity: Math.max(1, input.defaultQuantity || 1),
    durationMinutes: input.nodeType === "WORK" ? input.durationMinutes : null,
    additionalOption:
      input.nodeType === "OPTION" && Boolean(input.additionalOption),
    showInMainMenu: input.showInMainMenu,
    mainMenuOrder: existing?.mainMenuOrder ?? null,
    mainMenuTitle: existing?.mainMenuTitle ?? null,
    routeQueueKind: existing?.routeQueueKind ?? null,
    photoRequired: existing?.photoRequired ?? false,
    includeInEstimate: input.includeInEstimate,
    commonItem: input.commonItem,
    furnitureCategory:
      input.nodeType === "CATEGORY" && Boolean(input.furnitureCategory),
    canvasX: input.canvasX,
    canvasY: input.canvasY,
    comment: normalizeNullableText(input.comment),
  }
}

function pricedNode(type: RepairEstimateCatalogNodeType) {
  return type === "WORK" || type === "MATERIAL"
}

function assertLinkValid(
  input: RepairEstimateCatalogLinkMutation,
  state: RepairEstimateCatalogState
) {
  if (input.sourceNodeId === input.targetNodeId) {
    throw new Error("Исходный и целевой узлы должны отличаться")
  }

  const nodesByIdMap = nodeById(state.nodes)
  const source = nodesByIdMap.get(input.sourceNodeId)
  const target = nodesByIdMap.get(input.targetNodeId)
  if (!source || !target) {
    throw new Error("Узел связи не найден")
  }
  if (target.nodeType === "CATEGORY") {
    throw new Error("Категория не может быть целевым блоком")
  }

  const duplicate = state.links.some(
    (link) =>
      link.id !== input.id &&
      link.sourceNodeId === input.sourceNodeId &&
      link.targetNodeId === input.targetNodeId &&
      link.linkType === input.linkType
  )
  if (duplicate) {
    throw new Error("Такая связь уже существует")
  }

  const graph = new Map<string, string[]>()
  for (const link of state.links) {
    if (link.id === input.id) {
      continue
    }
    const targets = graph.get(link.sourceNodeId) ?? []
    targets.push(link.targetNodeId)
    graph.set(link.sourceNodeId, targets)
  }
  const targets = graph.get(input.sourceNodeId) ?? []
  targets.push(input.targetNodeId)
  graph.set(input.sourceNodeId, targets)

  if (hasCatalogLinkCycle(graph)) {
    throw new Error("Связи создают цикл в каталоге")
  }
}

function hasCatalogLinkCycle(graph: Map<string, string[]>) {
  const visiting = new Set<string>()
  const visited = new Set<string>()

  const visit = (nodeId: string): boolean => {
    if (visited.has(nodeId)) {
      return false
    }
    if (visiting.has(nodeId)) {
      return true
    }

    visiting.add(nodeId)
    for (const targetId of graph.get(nodeId) ?? []) {
      if (visit(targetId)) {
        return true
      }
    }
    visiting.delete(nodeId)
    visited.add(nodeId)
    return false
  }

  for (const nodeId of graph.keys()) {
    if (visit(nodeId)) {
      return true
    }
  }

  return false
}

function applyFollowUpParent(
  input: RepairEstimateCatalogLinkMutation,
  state: RepairEstimateCatalogState
) {
  if (input.linkType !== "FOLLOW_UP") {
    return state.nodes
  }

  return state.nodes.map((node) =>
    node.id === input.targetNodeId && node.nodeType !== "CATEGORY"
      ? { ...node, parentId: input.sourceNodeId }
      : node
  )
}

export async function getRepairEstimateCatalogSnapshot() {
  return toSnapshot(readState())
}

export async function getRepairEstimateCatalogCanvas() {
  const state = readState()
  const snapshot = toSnapshot(state)

  return {
    ...snapshot,
    categories: getRootCategories(snapshot.nodes, "ALL"),
  } satisfies RepairEstimateCatalogCanvasDto
}

export async function getRepairEstimateCatalogSection(
  kind: RepairEstimateCatalogSectionKind
) {
  const state = readState()
  const snapshot = toSnapshot(state)
  const nodesByIdMap = nodeById(snapshot.nodes)
  const sectionType = getSectionType(kind)
  const scope = getSectionScope(kind)

  const items = snapshot.nodes
    .filter((node) => node.active && node.nodeType === sectionType)
    .filter((node) => {
      if (kind === "furniture") {
        return isFurnitureTreeNode(node, nodesByIdMap)
      }
      if (sectionType === "MATERIAL") {
        return !isFurnitureTreeNode(node, nodesByIdMap)
      }

      return true
    })
    .sort(compareBySortThenName)

  return {
    kind,
    title: getSectionTitle(kind),
    sectionType,
    categories: getRootCategories(snapshot.nodes, scope),
    items,
    links: snapshot.links,
  } satisfies RepairEstimateCatalogSectionDto
}

export async function saveRepairEstimateCatalogNode(
  input: RepairEstimateCatalogNodeMutation
) {
  if (!input.name.trim() || !input.code.trim()) {
    throw new Error("Заполните название и код")
  }

  const state = readState()
  const normalizedNode = normalizeNodeInput(input, state.nodes)
  const exists = state.nodes.some((node) => node.id === normalizedNode.id)
  const nodes = exists
    ? state.nodes.map((node) =>
        node.id === normalizedNode.id ? normalizedNode : node
      )
    : [...state.nodes, normalizedNode]

  const nextState = { ...state, nodes }
  writeState(nextState)
  return normalizedNode
}

export async function deleteRepairEstimateCatalogNode(id: string) {
  const state = readState()
  const hasChildren = state.nodes.some((node) => node.parentId === id)
  if (hasChildren) {
    throw new Error("Сначала удалите дочерние блоки")
  }

  const hasLinks = state.links.some(
    (link) => link.sourceNodeId === id || link.targetNodeId === id
  )
  if (hasLinks) {
    throw new Error("Сначала удалите связи блока")
  }

  writeState({
    nodes: state.nodes.filter((node) => node.id !== id),
    links: state.links,
  })
}

export async function saveRepairEstimateCatalogLink(
  input: RepairEstimateCatalogLinkMutation
) {
  const state = readState()
  assertLinkValid(input, state)

  const normalizedLink: RepairEstimateCatalogLinkDto = {
    id: input.id ?? nextId("catalog-link"),
    sourceNodeId: input.sourceNodeId,
    targetNodeId: input.targetNodeId,
    linkType: input.linkType,
    active: input.active,
    sortOrder: input.sortOrder,
    comment: normalizeNullableText(input.comment),
  }

  const exists = state.links.some((link) => link.id === normalizedLink.id)
  const links = exists
    ? state.links.map((link) =>
        link.id === normalizedLink.id ? normalizedLink : link
      )
    : [...state.links, normalizedLink]

  const nextState = {
    nodes: applyFollowUpParent(input, state),
    links,
  }
  writeState(nextState)
  return normalizedLink
}

export async function deleteRepairEstimateCatalogLink(id: string) {
  const state = readState()
  writeState({
    nodes: state.nodes,
    links: state.links.filter((link) => link.id !== id),
  })
}

export async function resetRepairEstimateCatalogMock() {
  const seed = buildSeedState()
  writeState(seed)
  return toSnapshot(seed)
}

export function getItemsForCategory(
  section: RepairEstimateCatalogSectionDto,
  categoryId: string
) {
  const nodesByIdMap = nodeById(section.items.concat(section.categories))

  return section.items.filter(
    (item) =>
      item.commonItem || belongsToCategory(item, categoryId, nodesByIdMap)
  )
}
