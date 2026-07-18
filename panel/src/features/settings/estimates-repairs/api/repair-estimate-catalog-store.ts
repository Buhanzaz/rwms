import {
  activateMaintenanceCatalog,
  importMaintenanceCatalog,
  listMaintenanceCatalogLinks,
  listMaintenanceCatalogNodes,
  listMaintenanceCatalogVersions,
  replaceMaintenanceCatalogLinks,
  replaceMaintenanceCatalogNodes,
  type MaintenanceCatalogImportRequest,
  type MaintenanceCatalogLink,
  type MaintenanceCatalogLinkInput,
  type MaintenanceCatalogNode,
  type MaintenanceCatalogNodeInput,
  type MaintenanceCatalogVersion,
} from "@/features/repair-estimate-catalog/api/http-maintenance-catalog-client"
import {
  deleteRepairEstimateCatalogLayoutLink,
  getRepairEstimateCatalogLayout,
  saveRepairEstimateCatalogLinkAnchors,
  saveRepairEstimateCatalogNodePosition,
} from "@/features/settings/estimates-repairs/api/repair-estimate-catalog-layout"
import type {
  RepairEstimateCatalogCanvasDto,
  RepairEstimateCatalogLinkDto,
  RepairEstimateCatalogLinkMutation,
  RepairEstimateCatalogNodeDto,
  RepairEstimateCatalogNodeMutation,
  RepairEstimateCatalogRequest,
  RepairEstimateCatalogSectionDto,
  RepairEstimateCatalogSectionKind,
  RepairEstimateCatalogSnapshotDto,
  RepairEstimateCatalogVersionDto,
} from "@/features/settings/estimates-repairs/model/repair-estimate-catalog"

const CODE_PATTERN = /^[A-Z0-9][A-Z0-9_-]{0,63}$/
const MONEY_PATTERN = /^(?:0|[1-9][0-9]*)(?:\.[0-9]{2})$/

function idempotencyKey() {
  if (typeof crypto === "undefined" || !("randomUUID" in crypto)) {
    throw new Error("Браузер не поддерживает безопасные UUID для команды.")
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

export async function listRepairEstimateCatalogVersions(
  accessToken: string,
  warehouseId: string
) {
  const page = await listMaintenanceCatalogVersions(accessToken, warehouseId)
  return page.items.map(toVersion)
}

async function catalogState(request: RepairEstimateCatalogRequest) {
  const versions = await listMaintenanceCatalogVersions(
    request.accessToken,
    request.warehouseId
  )
  const version = versions.items.find(
    (candidate) => candidate.id === request.catalogVersionId
  )
  if (!version) {
    throw new Error("Версия каталога не найдена для выбранного склада.")
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
  request: RepairEstimateCatalogRequest,
  state: Awaited<ReturnType<typeof catalogState>>
): RepairEstimateCatalogSnapshotDto {
  const layout = getRepairEstimateCatalogLayout(request)
  const nodeById = new Map(state.nodes.map((node) => [node.id, node]))

  const nodes = state.nodes
    .map((node): RepairEstimateCatalogNodeDto => {
      const position = layout.nodePositions[node.id]
      const queueKind = node.routing?.queueKind
      return {
        id: node.id,
        catalogVersionId: node.catalogVersionId,
        code: node.code,
        name: node.name,
        nodeType: node.nodeType,
        parentId: node.parentNodeId,
        parentCode: node.parentNodeId
          ? (nodeById.get(node.parentNodeId)?.code ?? null)
          : null,
        active: node.active,
        unit: node.unit,
        unitPrice: node.unitPrice,
        durationMinutes: node.nodeType === "WORK" ? node.durationMinutes : null,
        showInMainMenu: node.showInMainMenu,
        routeQueueKind:
          queueKind === "MOVEMENT" ||
          queueKind === "REPAIR" ||
          queueKind === "HOLDING"
            ? queueKind
            : null,
        workQueueId: node.routing?.queueId ?? null,
        workQueueCode: node.routing?.queueCode ?? null,
        routing: node.routing,
        photoRequired: node.photoRequired,
        includeInEstimate: node.includeInEstimate,
        commonItem: node.commonItem,
        references: node.references,
        mediaReferences: node.mediaReferences,
        canvasX: position?.x ?? null,
        canvasY: position?.y ?? null,
        comment: node.comment,
      }
    })
    .sort(compareNodes)

  const links = state.links
    .map((link): RepairEstimateCatalogLinkDto => {
      const anchors = layout.linkAnchors[link.id]
      return {
        id: link.id,
        catalogVersionId: link.catalogVersionId,
        sourceNodeId: link.fromNodeId,
        targetNodeId: link.toNodeId,
        linkType: link.linkType,
        sortOrder: link.sortOrder,
        canvasAnchors: anchors ?? null,
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

function rootCategories(nodes: RepairEstimateCatalogNodeDto[]) {
  return nodes
    .filter(
      (node) =>
        node.active && node.nodeType === "CATEGORY" && node.parentId === null
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
  return {
    kind,
    title: kind === "works" ? "Работы" : "Материалы",
    sectionType: kind === "works" ? "WORK" : "MATERIAL",
    categories: rootCategories(snapshot.nodes),
    nodes: snapshot.nodes,
    links: snapshot.links,
  } satisfies RepairEstimateCatalogSectionDto
}

function requireDraft(version: MaintenanceCatalogVersion) {
  if (version.lifecycle !== "DRAFT") {
    throw new Error("Изменять можно только черновую версию каталога.")
  }
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

function toNodeInput(
  node: MaintenanceCatalogNode
): MaintenanceCatalogNodeInput {
  return {
    id: node.id,
    code: node.code,
    nodeType: node.nodeType,
    name: node.name,
    active: node.active,
    parentNodeId: node.parentNodeId,
    unit: node.unit,
    unitPrice: node.unitPrice,
    durationMinutes: node.durationMinutes,
    includeInEstimate: node.includeInEstimate,
    commonItem: node.commonItem,
    showInMainMenu: node.showInMainMenu,
    photoRequired: node.photoRequired,
    routing: node.routing,
    references: node.references,
    comment: node.comment,
    mediaReferences: node.mediaReferences,
  }
}

function nodeInput(
  input: RepairEstimateCatalogNodeMutation,
  existing: MaintenanceCatalogNode | undefined,
  nodes: MaintenanceCatalogNode[]
): MaintenanceCatalogNodeInput {
  const code = input.code.trim().toUpperCase()
  if (!CODE_PATTERN.test(code)) {
    throw new Error("Код должен быть задан латиницей, цифрами, _ или -.")
  }
  if (
    nodes.some(
      (node) => node.code === code && node.id !== (existing?.id ?? input.id)
    )
  ) {
    throw new Error("Код уже существует.")
  }
  const name = input.name.trim()
  if (!name) throw new Error("Заполните название.")
  if (input.parentId && !nodes.some((node) => node.id === input.parentId)) {
    throw new Error("Родительский узел не найден.")
  }

  return {
    id: existing?.id ?? input.id ?? idempotencyKey(),
    code,
    nodeType: input.nodeType,
    name,
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
    photoRequired: input.nodeType === "WORK" && input.photoRequired,
    routing: existing?.routing ?? input.routing ?? null,
    references: existing?.references ?? input.references ?? [],
    comment: input.comment?.trim() || null,
    mediaReferences: existing?.mediaReferences ?? input.mediaReferences ?? [],
  }
}

export async function saveRepairEstimateCatalogNode(
  request: RepairEstimateCatalogRequest,
  input: RepairEstimateCatalogNodeMutation
) {
  const state = await catalogState(request)
  requireDraft(state.version)
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
  requireDraft(state.version)
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
  }
}

export async function saveRepairEstimateCatalogLink(
  request: RepairEstimateCatalogRequest,
  input: RepairEstimateCatalogLinkMutation
) {
  const state = await catalogState(request)
  requireDraft(state.version)
  if (input.sourceNodeId === input.targetNodeId) {
    throw new Error("Исходный и целевой узлы должны отличаться.")
  }
  const nodeIds = new Set(state.nodes.map((node) => node.id))
  if (!nodeIds.has(input.sourceNodeId) || !nodeIds.has(input.targetNodeId)) {
    throw new Error("Узел связи не найден.")
  }
  const id = input.id ?? idempotencyKey()
  const changed: MaintenanceCatalogLinkInput = {
    id,
    fromNodeId: input.sourceNodeId,
    toNodeId: input.targetNodeId,
    linkType: input.linkType,
    sortOrder: Math.max(0, Math.trunc(input.sortOrder)),
  }
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

  if (input.canvasAnchors) {
    saveRepairEstimateCatalogLinkAnchors(request, id, input.canvasAnchors)
  }
  const snapshot = await getRepairEstimateCatalogSnapshot(request)
  return snapshot.links.find((link) => link.id === id)!
}

export async function deleteRepairEstimateCatalogLink(
  request: RepairEstimateCatalogRequest,
  id: string
) {
  const state = await catalogState(request)
  requireDraft(state.version)
  await replaceMaintenanceCatalogLinks(
    request.accessToken,
    request.warehouseId,
    state.version.id,
    state.version.version,
    state.links.filter((link) => link.id !== id).map(toLinkInput)
  )
  deleteRepairEstimateCatalogLayoutLink(request, id)
}

export function moveRepairEstimateCatalogCanvasNode(
  request: RepairEstimateCatalogRequest,
  nodeId: string,
  position: { x: number; y: number }
) {
  return Promise.resolve(
    saveRepairEstimateCatalogNodePosition(request, nodeId, position)
  )
}

type ImportArtifact = {
  sourceWarehouseId?: unknown
  sourceEvidenceSha256?: unknown
  nodes?: unknown
  links?: unknown
}

export function parseRepairEstimateCatalogImportArtifact(
  request: Pick<RepairEstimateCatalogRequest, "warehouseId">,
  source: string
): MaintenanceCatalogImportRequest {
  let artifact: ImportArtifact
  try {
    artifact = JSON.parse(source) as ImportArtifact
  } catch {
    throw new Error("Файл каталога не является корректным JSON.")
  }
  if (artifact.sourceWarehouseId !== request.warehouseId) {
    throw new Error("Снимок каталога относится к другому складу.")
  }
  if (
    typeof artifact.sourceEvidenceSha256 !== "string" ||
    !/^[0-9a-f]{64}$/.test(artifact.sourceEvidenceSha256) ||
    !Array.isArray(artifact.nodes) ||
    !Array.isArray(artifact.links)
  ) {
    throw new Error(
      "Файл не соответствует формату проверенного снимка каталога."
    )
  }
  return {
    warehouseId: request.warehouseId,
    sourceSha256: artifact.sourceEvidenceSha256,
    nodes: artifact.nodes as MaintenanceCatalogNodeInput[],
    links: artifact.links as MaintenanceCatalogLinkInput[],
  }
}

export function importRepairEstimateCatalog(
  accessToken: string,
  warehouseId: string,
  source: string
) {
  return importMaintenanceCatalog(
    accessToken,
    idempotencyKey(),
    parseRepairEstimateCatalogImportArtifact({ warehouseId }, source)
  ).then(toVersion)
}

export function activateRepairEstimateCatalog(
  request: RepairEstimateCatalogRequest,
  expectedVersion: number
) {
  return activateMaintenanceCatalog(
    request.accessToken,
    request.warehouseId,
    request.catalogVersionId,
    expectedVersion,
    idempotencyKey()
  ).then(toVersion)
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
  return section.nodes
    .filter((node) => node.active && node.nodeType === section.sectionType)
    .sort(compareNodes)
}

export function getItemsForCategory(
  section: RepairEstimateCatalogSectionDto,
  categoryId: string
) {
  const nodes = nodeById(section.nodes)
  return getRepairEstimateCatalogSectionItems(section).filter(
    (item) => item.commonItem || belongsToCategory(item, categoryId, nodes)
  )
}
