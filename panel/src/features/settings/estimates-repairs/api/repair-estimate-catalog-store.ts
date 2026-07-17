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
import { DEV_MAINTENANCE_FIXTURES_ENABLED } from "@/features/maintenance/maintenance-runtime"
import {
  deleteHttpMaintenanceCatalogLink,
  deleteHttpMaintenanceCatalogNode,
  getHttpMaintenanceCatalogSnapshot,
  saveHttpMaintenanceCatalogLink,
  saveHttpMaintenanceCatalogNode,
} from "@/features/settings/estimates-repairs/api/http-maintenance-catalog-settings"

const STORAGE_KEY = "rwms:repair-estimate-catalog:v2"
const MUTATION_LOCK_NAME = "rwms:repair-estimate-catalog:mutation"
const COMMENT_MAX_LENGTH = 180
const MAIN_MENU_TITLE_MAX_LENGTH = 255

type RepairEstimateCatalogState = {
  revision: number
  nodes: RepairEstimateCatalogNodeDto[]
  links: RepairEstimateCatalogLinkDto[]
}

type RepairEstimateCatalogStorageEnvelope = RepairEstimateCatalogState & {
  service: "repair-estimate-catalog"
  schemaVersion: 2
}

const seedWorkQueueCodeByNodeCode = new Map(
  REPAIR_ESTIMATE_CATALOG_SEED_NODES.filter((node) => node.workQueueCode).map(
    (node) => [node.code, node.workQueueCode ?? null]
  )
)

type CategoryScope = "ALL" | "NON_FURNITURE" | "FURNITURE_ONLY"

function normalizeCode(value: string) {
  return value.trim().toUpperCase()
}

function normalizeNullableText(value: string | null | undefined) {
  const normalized = value?.trim()
  return normalized ? normalized : null
}

function requireProductionWarehouseId(warehouseId: string | undefined) {
  const normalized = warehouseId?.trim()
  if (!normalized) {
    throw new Error("Не выбран склад каталога ремонта.")
  }
  return normalized
}

function normalizeMoneyDecimal(value: unknown) {
  if (value === null || value === undefined || value === "") {
    return null
  }

  const normalized = String(value).trim().replace(",", ".")
  const match = /^(\d+)(?:\.(\d{0,2}))?$/.exec(normalized)
  if (!match) {
    throw new Error(
      "Цена должна быть неотрицательным числом с точностью до копеек"
    )
  }

  const whole = BigInt(match[1]).toString()
  const fraction = (match[2] ?? "").padEnd(2, "0")
  return `${whole}.${fraction}`
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

function buildSeedState(): RepairEstimateCatalogState {
  const idByCode = new Map(
    REPAIR_ESTIMATE_CATALOG_SEED_NODES.map((node) => [node.code, node.id])
  )

  const nodes = REPAIR_ESTIMATE_CATALOG_SEED_NODES.map((node) => ({
    ...node,
    unitPrice: normalizeMoneyDecimal(node.unitPrice),
    workQueueCode: node.workQueueCode ?? null,
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

  return { revision: 0, nodes, links }
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
    return buildSeedState()
  }

  try {
    const parsed = JSON.parse(
      raw
    ) as Partial<RepairEstimateCatalogStorageEnvelope>
    if (!Array.isArray(parsed.nodes) || !Array.isArray(parsed.links)) {
      throw new Error("Invalid catalog state")
    }
    if (
      (parsed.service !== undefined &&
        parsed.service !== "repair-estimate-catalog") ||
      (parsed.schemaVersion !== undefined && parsed.schemaVersion !== 2) ||
      (parsed.revision !== undefined &&
        (!Number.isSafeInteger(parsed.revision) || parsed.revision < 0))
    ) {
      throw new Error("Invalid catalog envelope")
    }

    const nodes = parsed.nodes.map((node) => {
      const workQueueCode =
        node.workQueueCode ?? seedWorkQueueCodeByNodeCode.get(node.code) ?? null
      const unitPrice = normalizeMoneyDecimal(node.unitPrice)
      return {
        ...node,
        unitPrice,
        workQueueCode,
      }
    })

    return {
      revision: parsed.revision ?? 0,
      nodes,
      links: parsed.links,
    }
  } catch {
    return buildSeedState()
  }
}

function writeState(state: RepairEstimateCatalogState) {
  if (!storageAvailable()) {
    return
  }

  const envelope: RepairEstimateCatalogStorageEnvelope = {
    service: "repair-estimate-catalog",
    schemaVersion: 2,
    ...state,
  }
  window.localStorage.setItem(STORAGE_KEY, JSON.stringify(envelope))
}

let mutationQueue: Promise<void> = Promise.resolve()

function runWithOriginMutationLock<T>(operation: () => Promise<T>) {
  if (typeof navigator !== "undefined" && navigator.locks) {
    return navigator.locks.request(MUTATION_LOCK_NAME, operation)
  }
  return operation()
}

function runSerializedMutation<T>(operation: () => Promise<T>) {
  const run = () => runWithOriginMutationLock(operation)
  const result = mutationQueue.then(run, run)
  mutationQueue = result.then(
    () => undefined,
    () => undefined
  )
  return result
}

function assertExpectedRevision(
  state: RepairEstimateCatalogState,
  expectedRevision: number
) {
  if (state.revision !== expectedRevision) {
    throw new Error(
      "Каталог был изменён в другой вкладке. Обновите данные и повторите действие"
    )
  }
}

function withNextRevision(state: RepairEstimateCatalogState) {
  return {
    ...state,
    revision: state.revision + 1,
  }
}

function nodeById(nodes: RepairEstimateCatalogNodeDto[]) {
  return new Map(nodes.map((node) => [node.id, node]))
}

function withParentCodes(nodes: RepairEstimateCatalogNodeDto[]) {
  const map = nodeById(nodes)
  return nodes.map((node) => ({
    ...node,
    workQueueCode:
      node.workQueueCode ?? seedWorkQueueCodeByNodeCode.get(node.code) ?? null,
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

function createGeneratedCode(
  nodeType: RepairEstimateCatalogNodeType,
  nodes: RepairEstimateCatalogNodeDto[]
) {
  let generatedCode = ""

  do {
    generatedCode = `SYSTEM_${nodeType}_${nextId("catalog")
      .replace(/[^a-zA-Z0-9]/g, "")
      .toUpperCase()}`
  } while (nodes.some((node) => node.code === generatedCode))

  return generatedCode
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
  const nodesByIdMap = nodeById(nodes)
  const existing = input.id ? nodesByIdMap.get(input.id) : undefined
  const normalizedComment = normalizeNullableText(input.comment)
  if ((normalizedComment?.length ?? 0) > COMMENT_MAX_LENGTH) {
    throw new Error(
      `Комментарий не может быть длиннее ${COMMENT_MAX_LENGTH} символов`
    )
  }
  const normalizedMainMenuTitle = normalizeNullableText(input.mainMenuTitle)
  if ((normalizedMainMenuTitle?.length ?? 0) > MAIN_MENU_TITLE_MAX_LENGTH) {
    throw new Error(
      `Название в главном меню не может быть длиннее ${MAIN_MENU_TITLE_MAX_LENGTH} символов`
    )
  }

  const normalizedCode = ensureUniqueCode(
    nodes,
    input.code.trim() ||
      existing?.code ||
      createGeneratedCode(input.nodeType, nodes),
    input.id
  )
  const parentId = validateParent(input, nodesByIdMap)

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
    unitPrice: pricedNode(input.nodeType)
      ? normalizeMoneyDecimal(input.unitPrice)
      : null,
    defaultQuantity: Math.max(1, input.defaultQuantity || 1),
    durationMinutes: input.nodeType === "WORK" ? input.durationMinutes : null,
    additionalOption:
      input.nodeType === "OPTION" && Boolean(input.additionalOption),
    showInMainMenu: input.showInMainMenu,
    mainMenuOrder: normalizeNullableInteger(
      input.mainMenuOrder,
      "Порядок в главном меню"
    ),
    mainMenuTitle: normalizedMainMenuTitle,
    routeQueueKind: canConfigureRouteQueueKind(input.nodeType)
      ? input.routeQueueKind
      : null,
    workQueueCode: existing?.workQueueCode ?? null,
    photoRequired: input.nodeType === "WORK" && Boolean(input.photoRequired),
    includeInEstimate: input.includeInEstimate,
    commonItem: input.commonItem,
    furnitureCategory:
      input.nodeType === "CATEGORY" && Boolean(input.furnitureCategory),
    canvasX: input.canvasX,
    canvasY: input.canvasY,
    comment: normalizedComment,
  }
}

function pricedNode(type: RepairEstimateCatalogNodeType) {
  return type === "WORK" || type === "MATERIAL"
}

function canConfigureRouteQueueKind(type: RepairEstimateCatalogNodeType) {
  return (
    type === "CATEGORY" ||
    type === "SUBCATEGORY" ||
    type === "WORK" ||
    type === "OPTION"
  )
}

function normalizeNullableInteger(value: number | null, label: string) {
  if (value === null) {
    return null
  }
  if (!Number.isFinite(value) || !Number.isInteger(value)) {
    throw new Error(`${label} должен быть целым числом`)
  }
  return value
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

export async function getRepairEstimateCatalogSnapshot(warehouseId?: string) {
  if (!DEV_MAINTENANCE_FIXTURES_ENABLED) {
    return getHttpMaintenanceCatalogSnapshot(
      requireProductionWarehouseId(warehouseId)
    )
  }
  return toSnapshot(readState())
}

export async function getRepairEstimateCatalogCanvas(warehouseId?: string) {
  const snapshot = await getRepairEstimateCatalogSnapshot(warehouseId)

  return {
    ...snapshot,
    categories: getRootCategories(snapshot.nodes, "ALL"),
  } satisfies RepairEstimateCatalogCanvasDto
}

export async function getRepairEstimateCatalogSection(
  kind: RepairEstimateCatalogSectionKind,
  warehouseId?: string
) {
  const snapshot = await getRepairEstimateCatalogSnapshot(warehouseId)
  const sectionType = getSectionType(kind)
  const scope = getSectionScope(kind)

  return {
    kind,
    title: getSectionTitle(kind),
    sectionType,
    categories: getRootCategories(snapshot.nodes, scope),
    nodes: snapshot.nodes,
    links: snapshot.links,
  } satisfies RepairEstimateCatalogSectionDto
}

export async function saveRepairEstimateCatalogNode(
  input: RepairEstimateCatalogNodeMutation,
  warehouseId?: string
) {
  if (!DEV_MAINTENANCE_FIXTURES_ENABLED) {
    return saveHttpMaintenanceCatalogNode(
      requireProductionWarehouseId(warehouseId),
      input
    )
  }
  if (!input.name.trim()) {
    throw new Error("Заполните название")
  }

  const expectedRevision = readState().revision
  return runSerializedMutation(async () => {
    // Read again while holding the origin lock. No await is allowed between this
    // CAS check and localStorage commit.
    const state = readState()
    assertExpectedRevision(state, expectedRevision)
    const normalizedNode = normalizeNodeInput(input, state.nodes)
    const exists = state.nodes.some((node) => node.id === normalizedNode.id)
    const nodes = exists
      ? state.nodes.map((node) =>
          node.id === normalizedNode.id ? normalizedNode : node
        )
      : [...state.nodes, normalizedNode]

    writeState(withNextRevision({ ...state, nodes }))
    return normalizedNode
  })
}

export async function deleteRepairEstimateCatalogNode(
  id: string,
  warehouseId?: string
) {
  if (!DEV_MAINTENANCE_FIXTURES_ENABLED) {
    return deleteHttpMaintenanceCatalogNode(
      requireProductionWarehouseId(warehouseId),
      id
    )
  }
  const expectedRevision = readState().revision
  return runSerializedMutation(async () => {
    const state = readState()
    assertExpectedRevision(state, expectedRevision)
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

    writeState(
      withNextRevision({
        ...state,
        nodes: state.nodes.filter((node) => node.id !== id),
      })
    )
  })
}

export async function saveRepairEstimateCatalogLink(
  input: RepairEstimateCatalogLinkMutation,
  warehouseId?: string
) {
  if (!DEV_MAINTENANCE_FIXTURES_ENABLED) {
    return saveHttpMaintenanceCatalogLink(
      requireProductionWarehouseId(warehouseId),
      input
    )
  }
  const expectedRevision = readState().revision
  return runSerializedMutation(async () => {
    const state = readState()
    assertExpectedRevision(state, expectedRevision)
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

    writeState(withNextRevision({ ...state, links }))
    return normalizedLink
  })
}

export async function deleteRepairEstimateCatalogLink(
  id: string,
  warehouseId?: string
) {
  if (!DEV_MAINTENANCE_FIXTURES_ENABLED) {
    return deleteHttpMaintenanceCatalogLink(
      requireProductionWarehouseId(warehouseId),
      id
    )
  }
  const expectedRevision = readState().revision
  return runSerializedMutation(async () => {
    const state = readState()
    assertExpectedRevision(state, expectedRevision)
    writeState(
      withNextRevision({
        ...state,
        links: state.links.filter((link) => link.id !== id),
      })
    )
  })
}

export async function resetRepairEstimateCatalogMock(warehouseId?: string) {
  if (!DEV_MAINTENANCE_FIXTURES_ENABLED) {
    requireProductionWarehouseId(warehouseId)
    throw new Error(
      "Производственный каталог нельзя сбросить браузерным mock-действием."
    )
  }
  const expectedRevision = readState().revision
  return runSerializedMutation(async () => {
    const state = readState()
    assertExpectedRevision(state, expectedRevision)
    const seed = {
      ...buildSeedState(),
      revision: state.revision + 1,
    }
    writeState(seed)
    return toSnapshot(seed)
  })
}

export function getRepairEstimateCatalogSectionItems(
  section: RepairEstimateCatalogSectionDto
) {
  const nodesByIdMap = nodeById(section.nodes)

  return section.nodes
    .filter((node) => node.active && node.nodeType === section.sectionType)
    .filter((node) => {
      if (section.kind === "furniture") {
        return isFurnitureTreeNode(node, nodesByIdMap)
      }
      if (section.sectionType === "MATERIAL") {
        return !isFurnitureTreeNode(node, nodesByIdMap)
      }

      return true
    })
    .sort(compareBySortThenName)
}

export function getItemsForCategory(
  section: RepairEstimateCatalogSectionDto,
  categoryId: string
) {
  const nodesByIdMap = nodeById(section.nodes)

  return getRepairEstimateCatalogSectionItems(section).filter(
    (item) =>
      item.commonItem || belongsToCategory(item, categoryId, nodesByIdMap)
  )
}
